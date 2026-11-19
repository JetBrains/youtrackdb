package com.jetbrains.youtrackdb.internal.core.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PresencePlanTestSupport;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Fill completion must invalidate plans after final statistics, including failed fills. */
@Category(SequentialTest.class)
public class IndexFillPlanInvalidationTest extends GraphBaseTest {

  /** Creation fires one additional event after fill and removes interval-generation plans. */
  @Test
  public void creationCompletion_invalidatesAfterProgressCompletion() {
    exercise(false, false);
  }

  /** Rebuild fires exactly one completion event rather than one at fill and another at histogram. */
  @Test
  public void rebuildCompletion_invalidatesAfterProgressCompletion() {
    exercise(true, false);
  }

  /** Failure also rejects plans published during a partial creation fill. */
  @Test
  public void creationFailure_invalidatesPartialCountPlans() {
    exercise(false, true);
  }

  /** Failed rebuild cannot retain a template built while the index held partial counts. */
  @Test
  public void rebuildFailure_invalidatesPartialCountPlans() {
    exercise(true, true);
  }

  /** The SQL entry point rejects interval templates and plans again from final counters. */
  @Test
  public void sqlRebuild_replansFromFinalCounters() {
    exercise(true, false, 1);
  }

  /** The import stale-index rebuild uses the same SQL path and completion invalidation. */
  @Test
  public void importRebuild_replansFromFinalCounters() {
    exercise(true, false, 2);
  }

  private void exercise(boolean rebuild, boolean fail) {
    exercise(rebuild, fail, 0);
  }

  private void exercise(boolean rebuild, boolean fail, int entryPoint) {
    var clazz = session.createVertexClass("Filled");
    clazz.createProperty("k", PropertyType.INTEGER).setNotNull(true);
    var sources = session.createVertexClass("Source");
    var link = session.createEdgeClass("Link");
    link.getProperty("out").setLinkedClass(sources);
    link.getProperty("in").setLinkedClass(clazz);
    for (int i = 0; i < 100; i++) {
      var target = graph.addVertex(T.label, "Filled", "k", i);
      graph.addVertex(T.label, "Source").addEdge("Link", target);
    }
    graph.tx().commit();
    var manager = session.getSharedContext().getIndexManager();
    var definition = new PropertyIndexDefinition("Filled", "k", PropertyTypeInternal.INTEGER);
    var index = rebuild ? manager.createIndex(session, "Filled.k", "NOTUNIQUE", definition,
        clazz.getCollectionIds(), null, Map.of()) : null;
    var events = new AtomicInteger();
    var atCompletion = new AtomicInteger(-1);
    var generationAtCompletion = new AtomicLong(-1);
    var cache = session.getSharedContext().getGremlinPlanCache();
    var observer = mock(MetadataUpdateListener.class);
    doAnswer(call -> {
      events.incrementAndGet();
      return null;
    }).when(observer).onIndexManagerUpdate(any(), anyString(), any());
    var shared = session.getSharedContext();
    shared.registerListener(observer);
    var oldBatch = GlobalConfiguration.TX_BATCH_SIZE.getValue();
    GlobalConfiguration.TX_BATCH_SIZE.setValue(10);
    var reader = Executors.newSingleThreadExecutor();
    var observed = new AtomicInteger();
    var progress = new AtomicInteger();
    var intervalTemplate = new AtomicReference<InternalExecutionPlan>();
    var listener = new ProgressListener() {
      @Override
      public void onBegin(Object task, long total, Object metadata) {
      }

      @Override
      public boolean onProgress(Object task, long count, float percent) {
        var current = progress.incrementAndGet();
        if (current == 11) {
          try {
            reader.submit(() -> {
              var traversal = graph.traversal().V().hasLabel("Source").out("Link")
                  .hasLabel("Filled").values("k").limit(1000);
              try {
                graph.tx().readWrite();
                var db = ((YTDBTransaction) graph.tx()).getDatabaseSession();
                try (var inspected = PresencePlanTestSupport.plan(traversal.asAdmin(), db)) {
                  inspected.assertFillState(rebuild, false);
                }
                assertThat(traversal.toList()).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.IntStream.range(0, 100).boxed().toList());
                intervalTemplate
                    .set(((YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep()).getPlan());
                assertThat(traversal.asAdmin().getStartStep())
                    .isInstanceOf(YTDBMatchPlanStep.class);
                var plan = ((YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep())
                    .getPlan().prettyPrint(0, 2);
                assertThat(plan).doesNotContain("FETCH FROM INDEX");
                assertThat(plan).contains("FETCH FROM CLASS");
                observed.incrementAndGet();
              } finally {
                graph.tx().rollback();
              }
            }).get(30, TimeUnit.SECONDS);
          } catch (Exception failure) {
            throw new AssertionError("partial-fill reader failed", failure);
          }
        }
        if (fail && current >= 11) {
          throw new IllegalStateException("test fill failure");
        }
        return true;
      }

      @Override
      public void onCompletition(DatabaseSessionEmbedded db, Object task, boolean success) {
        assertThat(success).isEqualTo(!fail);
        atCompletion.set(events.get());
        generationAtCompletion.set(cache.getGeneration());
      }
    };
    try (var output = org.mockito.Mockito.mockConstruction(IndexRebuildOutputListener.class,
        (mock, context) -> {
          org.mockito.Mockito.when(mock.onProgress(any(), org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyFloat())).thenAnswer(
                  call -> listener.onProgress(
                      call.getArgument(0), call.getArgument(1), call.getArgument(2)));
          org.mockito.Mockito.doAnswer(call -> {
            listener.onCompletition(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
          }).when(mock).onCompletition(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        })) {
      Runnable operation = () -> {
        if (entryPoint == 1) {
          session.execute("REBUILD INDEX Filled.k").close();
        } else if (entryPoint == 2) {
          try {
            var importer = new com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport(
                session, new java.io.ByteArrayInputStream(new byte[0]), message -> {
                });
            try {
              // Supply the stale-index roster normally collected by the import parser. The actual
              // import rebuild routine, SQL command, fill and invalidation remain production code.
              var field = importer.getClass().getDeclaredField("indexesToRebuild");
              field.setAccessible(true);
              @SuppressWarnings("unchecked")
              var stale = (java.util.Set<String>) field.get(importer);
              stale.add("Filled.k");
              importer.rebuildIndexes();
            } finally {
              importer.close();
            }
          } catch (Exception failure) {
            throw new AssertionError("import rebuild failed", failure);
          }
        } else if (rebuild) {
          index.rebuild(session, listener);
        } else {
          manager.createIndex(session, "Filled.k", "NOTUNIQUE", definition,
              clazz.getCollectionIds(), listener, Map.of());
        }
      };
      if (fail) {
        assertThatThrownBy(operation::run).isInstanceOf(RuntimeException.class);
      } else {
        operation.run();
      }
      assertThat(observed.get()).isEqualTo(1);
      assertThat(atCompletion.get()).isNotNegative();
      // Any earlier manager events are irrelevant. Exactly one new event follows fill completion.
      assertThat(events.get()).isEqualTo(atCompletion.get() + 1);
      assertThat(cache.getGeneration()).isEqualTo(generationAtCompletion.get() + 1);
      if (!fail) {
        reader.submit(() -> {
          var traversal = graph.traversal().V().hasLabel("Source").out("Link")
              .hasLabel("Filled").values("k").limit(1000);
          graph.tx().readWrite();
          var db = ((YTDBTransaction) graph.tx()).getDatabaseSession();
          try (var inspected = PresencePlanTestSupport.plan(traversal.asAdmin(), db)) {
            inspected.assertFillState(rebuild, true);
            var misses = cache.getTranslationMisses();
            traversal.asAdmin().applyStrategies();
            assertThat(cache.getTranslationMisses()).isEqualTo(misses + 1);
            var step = (YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep();
            assertThat(step.getPlan()).isNotSameAs(intervalTemplate.get());
            assertThat(traversal.toList()).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.range(0, 100).boxed().toList());
          } finally {
            graph.tx().rollback();
          }
        }).get(30, TimeUnit.SECONDS);
      }
    } catch (Exception failure) {
      throw new AssertionError("final-statistics reader failed", failure);
    } finally {
      GlobalConfiguration.TX_BATCH_SIZE.setValue(oldBatch);
      shared.unregisterListener(observer);
      reader.shutdownNow();
      try {
        assertThat(reader.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("reader shutdown interrupted", interrupted);
      }
    }
  }
}
