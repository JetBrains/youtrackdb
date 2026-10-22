package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHook;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Real shared-cache publication, ownership and runtime binding contracts. */
@Category(SequentialTest.class)
public class RuntimeRidStartCacheIntegrationTest extends GraphBaseTest {

  private DatabaseSessionEmbedded db(YTDBGraph current) {
    var tx = (YTDBTransaction) current.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private YTDBGraph readerGraph() {
    var config = getBaseConfiguration();
    config.setProperty(YTDBGraphFactory.CONFIG_USER_NAME, readerUser);
    config.setProperty(YTDBGraphFactory.CONFIG_USER_PWD, readerPassword);
    config.setProperty(YTDBGraphFactory.CONFIG_CREATE_IF_NOT_EXISTS, false);
    return (YTDBGraph) GraphFactory.open(config);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).as("bounded rendezvous").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static GremlinToMatchStrategy.MatchPlanBuilder builder(
      java.util.function.Consumer<GremlinPlanCache.Entry<InternalExecutionPlan>> afterReturn) {
    return new GremlinToMatchStrategy.MatchPlanBuilder() {
      @Override
      public GremlinPlanCache.Entry<InternalExecutionPlan> buildPlanEntry(
          DatabaseSessionEmbedded session, GremlinToMatchTranslator.TranslationResult result,
          GremlinToMatchStrategy.CacheScope scope) {
        var entry = GremlinToMatchStrategy.buildPlanEntry(session, result, scope);
        afterReturn.accept(entry);
        return entry;
      }
    };
  }

  /**
   * Every structural arm plans once across distinct RID answers and both cache routes. Literal
   * and native controls pin actual slice members, count multiplicities, absent values and order.
   */
  @Test
  public void structuralFamiliesReuseTemplatesAcrossRids() {
    db(graph).getMetadata().getSchema().getClass("V")
        .createProperty("name",
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING)
        .createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    graph.tx().commit();
    var a = graph.addVertex(T.label, "StructuralStart");
    var b = graph.addVertex(T.label, "StructuralStart");
    var x = graph.addVertex(T.label, "StructuralTarget", "name", "tie");
    var y = graph.addVertex(T.label, "StructuralTarget", "name", "tie");
    var z = graph.addVertex(T.label, "StructuralTarget");
    var n = graph.addVertex(T.label, "StructuralTarget", "name", null);
    a.addEdge("structuralFirst", x);
    a.addEdge("structuralFirst", x);
    a.addEdge("structuralSecond", y);
    a.addEdge("structuralSecond", z);
    a.addEdge("structuralSecond", n);
    b.addEdge("structuralFirst", y);
    b.addEdge("structuralSecond", b);
    // Distinct incoming answers and repeated links make direction and BOTH multiplicity visible.
    z.addEdge("structuralFirst", a);
    z.addEdge("structuralFirst", a);
    y.addEdge("structuralSecond", a);
    n.addEdge("structuralFirst", b);
    b.addEdge("structuralFirst", b);
    graph.tx().commit();
    var session = db(graph);
    var cache = GremlinPlanCache.instance(session);
    var flag =
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY;
    var original = session.getConfiguration().getValueAsBoolean(flag);
    var runs = new AtomicInteger();
    var cappedOrder = new java.util.concurrent.atomic.AtomicBoolean(true);
    cache.beforeUncachedPlanning = (input, context) -> {
      runs.incrementAndGet();
      if (cappedOrder.get()) {
        assertThat(input.runtimeRidStarts()).isEmpty();
      }
      if (!input.runtimeRidStarts().isEmpty()) {
        assertThat(input.runtimeRidStarts().values())
            .allSatisfy(start -> assertThat(start.parameterSlot()).isZero());
        assertThat(context.getInputParameters()).isNullOrEmpty();
      }
    };
    try {
      // The indexed finite-cap control proves the index is usable for the actual target alias V.
      session.getConfiguration().setValue(flag, false);
      for (var id : List.of(a.id(), b.id())) {
        var cap = graph.traversal().V(id).out("structuralFirst")
            .order().by("name").limit(1).asAdmin();
        assertThat(RuntimeRidStartEligibility.evaluate(cap, true)).isNull();
        cap.applyStrategies();
        assertThat(((YTDBMatchPlanStep<?, ?>) cap.getStartStep()).getPlan().prettyPrint(0, 2))
            .contains("INDEX ORDERED MATCH").doesNotContain("FETCH FROM RID PARAMETER");
        assertThat(TranslatorEquivalenceSupport.sortedIds(cap.toList())).isEqualTo(
            TranslatorEquivalenceSupport.sortedIds(graph.traversal()
                .withoutStrategies(GremlinToMatchStrategy.class).V(id).out("structuralFirst")
                .order().by("name").limit(1).toList()));
      }
      assertThat(runs.get()).isEqualTo(2);
      cappedOrder.set(false);
      for (boolean missingKeys : List.of(false, true)) {
        session.getConfiguration().setValue(flag, missingKeys);
        for (int arm = 0; arm < 41; arm++) {
          // Additional direction/label combinations need one cross-RID hit, not every cache route.
          if (arm >= 14 && arm < 39 && missingKeys) {
            continue;
          }
          for (boolean hasId : arm < 14 || arm >= 39 ? List.of(false, true) : List.of(false)) {
            cache.invalidate();
            runs.set(0);
            InternalExecutionPlan template = null;
            for (int route = 0; route < (arm < 14 || arm >= 39 ? 3 : 2); route++) {
              var id = route == 0 ? a.id() : b.id();
              var source = graph.traversal().withStrategies(
                  GremlinToMatchStrategy.withTranslationCache(route != 1));
              // Explicit ProductiveBy exercises wrapped keys under both missing-key settings.
              if (arm >= 39) {
                source = source.withStrategies(
                    org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy
                        .instance());
              }
              if (arm == 13) {
                source = source.withoutStrategies(
                    com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy.class);
              }
              var query = structural(source, id, hasId, arm).asAdmin();
              var hits = cache.getHits();
              var translationHits = cache.getTranslationHits();
              query.applyStrategies();
              assertThat(query.getStartStep()).as("arm %s", arm)
                  .isInstanceOf(YTDBMatchPlanStep.class);
              var boundary = (YTDBMatchPlanStep<?, ?>) query.getStartStep();
              if (template == null) {
                template = boundary.getPlan();
              }
              assertThat(boundary.getPlan()).as("arm %s route %s", arm, route).isSameAs(template);
              assertThat(runs.get()).as("warm planner runs arm %s", arm).isEqualTo(1);
              assertThat(cache.getHits()).isEqualTo(hits + (route == 1 ? 1 : 0));
              assertThat(cache.getTranslationHits())
                  .isEqualTo(translationHits + (route == 2 ? 1 : 0));
              var rows = query.toList();
              assertThat(boundary.getPlan()).isNotSameAs(template);
              assertThat(boundary.getPlan().getContext().getInputParameters())
                  .isEqualTo(java.util.Map.of(0, id));
              assertThat(template.getContext().getInputParameters()).isNullOrEmpty();
              assertThat(template.prettyPrint(0, 2)).contains("FETCH FROM RID PARAMETER")
                  .doesNotContain(a.id().toString(), b.id().toString(), "INDEX ORDERED");
              var nativeSource = source.withoutStrategies(GremlinToMatchStrategy.class);
              var nativeRows = structural(nativeSource, id, hasId, arm).toList();
              cache.setRuntimeRidStartEnabled(false);
              List<?> literalRows;
              try {
                literalRows = structural(source, id, hasId, arm).toList();
              } finally {
                cache.setRuntimeRidStartEnabled(true);
              }
              // Literal controls do real planning. Exclude only their observed attempt.
              runs.decrementAndGet();
              if ((arm >= 6 && arm <= 9) || arm == 13 || arm >= 39
                  || (arm >= 14 && (arm - 14) % 5 == 4)) {
                assertThat(rows).isEqualTo(nativeRows).isEqualTo(literalRows);
              } else {
                assertThat(TranslatorEquivalenceSupport.sortedIdsOrValues(rows))
                    .isEqualTo(TranslatorEquivalenceSupport.sortedIdsOrValues(nativeRows))
                    .isEqualTo(TranslatorEquivalenceSupport.sortedIdsOrValues(literalRows));
              }
            }
          }
        }
      }
    } finally {
      session.getConfiguration().setValue(flag, original);
      cache.beforeUncachedPlanning = null;
      cache.setRuntimeRidStartEnabled(true);
    }
  }

  private static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>
      structural(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource g,
          Object id, boolean hasId, int arm) {
    var start = hasId ? g.V().hasId(id) : g.V(id);
    var direction = org.apache.tinkerpop.gremlin.structure.Direction.OUT;
    String[] labels = {"structuralFirst", "structuralSecond"};
    if (arm >= 39) {
      arm = arm == 39 ? 6 : 7;
    } else if (arm >= 14) {
      int combination = (arm - 14) / 5;
      direction = switch (combination) {
        case 0 -> org.apache.tinkerpop.gremlin.structure.Direction.OUT;
        case 1, 2 -> org.apache.tinkerpop.gremlin.structure.Direction.IN;
        default -> org.apache.tinkerpop.gremlin.structure.Direction.BOTH;
      };
      if (combination == 0 || combination == 1 || combination == 3) {
        labels = new String[] {"structuralFirst"};
      }
      arm = switch ((arm - 14) % 5) {
        case 0 -> 0;
        case 1 -> 2;
        case 2 -> 3;
        case 3 -> 4;
        default -> 6;
      };
    }
    var hop = start.to(direction, labels);
    return switch (arm) {
      case 0 -> hop;
      case 1 -> hop.count();
      case 2 -> hop.values("name");
      case 3 -> hop.limit(2);
      case 4 -> hop.range(1, 3);
      case 5 -> hop.range(1, 1);
      case 6, 13 -> hop.order().by("name");
      case 7 -> hop.order().by("name", org.apache.tinkerpop.gremlin.process.traversal.Order.desc);
      case 8 -> hop.order().by("name").by(T.id);
      case 9 -> hop.order().by("name", org.apache.tinkerpop.gremlin.process.traversal.Order.desc)
          .by(T.id, org.apache.tinkerpop.gremlin.process.traversal.Order.desc);
      // Replace the just-added vertex hop with its explicit edge-count counterpart.
      case 10, 11, 12 -> {
        hop.asAdmin().removeStep(hop.asAdmin().getSteps().size() - 1);
        yield switch (arm) {
          case 10 -> start.outE("structuralFirst", "structuralSecond").count();
          case 11 -> start.inE("structuralFirst", "structuralSecond").count();
          default -> start.bothE("structuralFirst", "structuralSecond").count();
        };
      }
      default -> throw new AssertionError(arm);
    };
  }

  /** Missing, wrong-class and deleted starts retain the scalar zero on a warm count template. */
  @Test
  public void countMissingAndDeletedStartsReturnExactlyOneZero() {
    var a = graph.addVertex(T.label, "CountStart");
    var empty = graph.addVertex(T.label, "CountStart");
    var other = graph.addVertex(T.label, "CountOther");
    a.addEdge("countLink", other);
    a.addEdge("countLink", other);
    graph.tx().commit();
    var rid = (com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) a.id();
    var missing = new com.jetbrains.youtrackdb.internal.core.id.RecordId(
        rid.getCollectionId(), Long.MAX_VALUE);
    var cache = GremlinPlanCache.instance(db(graph));
    var runs = new AtomicInteger();
    cache.beforeUncachedPlanning = (input, context) -> runs.incrementAndGet();
    try {
      for (boolean hasId : List.of(false, true)) {
        cache.invalidate();
        runs.set(0);
        InternalExecutionPlan template = null;
        for (var id : List.of(a.id(), empty.id(), missing, other.id())) {
          var source = graph.traversal();
          var query = (hasId ? source.V().hasId(id) : source.V(id))
              .hasLabel("CountStart").out("countLink").count().asAdmin();
          query.applyStrategies();
          var boundary = (YTDBMatchPlanStep<?, ?>) query.getStartStep();
          if (template == null) {
            template = boundary.getPlan();
          }
          assertThat(boundary.getPlan()).isSameAs(template);
          assertThat(query.toList()).containsExactly(id.equals(a.id()) ? 2L : 0L);
          var nativeSource = graph.traversal().withoutStrategies(GremlinToMatchStrategy.class);
          assertThat((hasId ? nativeSource.V().hasId(id) : nativeSource.V(id))
              .hasLabel("CountStart").out("countLink").count().toList())
              .containsExactly(id.equals(a.id()) ? 2L : 0L);
        }
        assertThat(runs.get()).isEqualTo(1);
      }
      // Compare actual target READ events with the same RID, data and empty transaction cache.
      // Count may materialize targets in both plans, but runtime must not add any target reads.
      var target = new com.jetbrains.youtrackdb.internal.core.id.RecordId(
          (com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) other.id());
      var targetLoads = new AtomicInteger();
      RecordHook hook = new RecordHook() {
        @Override
        public void onUnregister() {
        }

        @Override
        public void onTrigger(TYPE type, DBRecord record) {
          if (type == TYPE.READ && target.equals(record.getIdentity())) {
            targetLoads.incrementAndGet();
          }
        }
      };
      int runtimeLoads = 0;
      List<?> runtimeRows = List.of();
      for (boolean runtime : List.of(true, false)) {
        graph.tx().commit();
        var session = db(graph);
        session.getLocalCache().clear();
        assertThat(session.getLocalCache().findRecord(target)).isNull();
        targetLoads.set(0);
        cache.setRuntimeRidStartEnabled(runtime);
        session.registerHook(hook);
        try {
          var rows = graph.traversal().V().hasId(a.id()).hasLabel("CountStart")
              .out("countLink").count().toList();
          assertThat(rows).containsExactly(2L);
          if (runtime) {
            runtimeRows = rows;
            runtimeLoads = targetLoads.get();
            assertThat(runs.get()).isEqualTo(1);
          } else {
            assertThat(rows).isEqualTo(runtimeRows);
            assertThat(runtimeLoads).as("runtime target READs versus literal target READs")
                .isLessThanOrEqualTo(targetLoads.get());
          }
        } finally {
          session.unregisterHook(hook);
          cache.setRuntimeRidStartEnabled(true);
        }
      }
      cache.setRuntimeRidStartEnabled(false);
      try {
        assertThat(graph.traversal().V(missing).hasLabel("CountStart")
            .out("countLink").count().toList()).containsExactly(0L);
      } finally {
        cache.setRuntimeRidStartEnabled(true);
      }
      var beforeDelete = runs.get();
      graph.traversal().V(a.id()).drop().iterate();
      assertThat(graph.traversal().V().hasId(a.id()).hasLabel("CountStart")
          .out("countLink").count().toList()).containsExactly(0L);
      graph.tx().commit();
      assertThat(graph.traversal().V().hasId(a.id()).hasLabel("CountStart")
          .out("countLink").count().toList()).containsExactly(0L);
      assertThat(runs.get()).isEqualTo(beforeDelete);
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Removal after a real builder return never makes the surviving shared template owned. */
  @Test
  public void postReturnInvalidationStillCopiesOnOpen() throws Exception {
    var a = graph.addVertex(T.label, "OwnershipPerson");
    var b = graph.addVertex(T.label, "OwnershipPerson");
    var x = graph.addVertex(T.label, "OwnershipPerson");
    a.addEdge("ownershipLink", x);
    b.addEdge("ownershipLink", a);
    graph.tx().commit();
    var session = db(graph);
    var cache = GremlinPlanCache.instance(session);
    var runs = new AtomicInteger();
    cache.beforeUncachedPlanning = (inputs, context) -> runs.incrementAndGet();
    var returned = new CountDownLatch(1);
    var removed = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var worker = new Thread(() -> {
      try {
        await(returned);
        cache.invalidate();
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        removed.countDown();
      }
    });
    try {
      var source =
          graph.traversal().withStrategies(GremlinToMatchStrategy.withTranslationCache(false));
      var cold = source.V(a.id()).out("ownershipLink").asAdmin();
      cold.applyStrategies();
      var originalTemplate = ((YTDBMatchPlanStep<?, ?>) cold.getStartStep()).getPlan();
      var publishing = org.mockito.Mockito.spy(originalTemplate);
      org.mockito.Mockito
          .doAnswer(invocation -> org.mockito.Mockito.spy(invocation.callRealMethod()))
          .when(publishing).copy(org.mockito.ArgumentMatchers.any());
      var raw = source.V(a.id()).out("ownershipLink").asAdmin();
      var decision = RuntimeRidStartEligibility.evaluate(raw, true);
      var result = GremlinToMatchTranslator.translate(raw, null, null, null, decision);
      var fp = GremlinPlanFingerprint.fingerprint(result.inputs(), result.shaping());
      cache.putInternal(fp, publishing, session, cache.getInvalidationCounter());
      var template = cache.peekStored(fp);
      org.mockito.Mockito.clearInvocations(template);
      var survivor = source.V(a.id()).out("ownershipLink").asAdmin();
      survivor.applyStrategies();
      assertThat(((YTDBMatchPlanStep<?, ?>) survivor.getStartStep()).getPlan()).isSameAs(template);
      worker.start();
      var strategy = new GremlinToMatchStrategy(t -> GremlinToMatchTranslator.translate(t,
          null, null, null, RuntimeRidStartEligibility.evaluate(t, true)), builder(entry -> {
            assertThat(entry.value).isSameAs(template);
            returned.countDown();
            await(removed);
          }));
      var query = graph.traversal().withoutStrategies(GremlinToMatchStrategy.class)
          .V(b.id()).out("ownershipLink").asAdmin();
      strategy.apply(query);
      var boundary = (YTDBMatchPlanStep<?, ?>) query.getStartStep();
      assertThat(boundary.getPlan()).isSameAs(template);
      assertThat(query.toList())
          .extracting(v -> ((org.apache.tinkerpop.gremlin.structure.Vertex) v).id())
          .containsExactly(a.id());
      assertThat(boundary.getPlan()).isNotSameAs(template);
      query.close();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).start();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).close();
      assertThat(survivor.toList()).hasSize(1);
      assertThat(((YTDBMatchPlanStep<?, ?>) survivor.getStartStep()).getPlan())
          .isNotSameAs(template).isNotSameAs(boundary.getPlan());
      assertThat(template.getContext().getInputParameters()).isNullOrEmpty();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).start();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).close();
      assertThat(runs.get()).isEqualTo(1);
    } finally {
      returned.countDown();
      removed.countDown();
      worker.join(30000);
      cache.beforeUncachedPlanning = null;
    }
    assertThat(worker.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
  }

  /**
   * Invalidation after a union child's production holder returns cannot transfer template ownership.
   * Both this boundary and a later boundary must copy the retained holder independently.
   */
  @Test
  public void postReturnUnionInvalidationStillCopiesChildren() throws Exception {
    var a = graph.addVertex(T.label, "UnionOwnershipPerson");
    var b = graph.addVertex(T.label, "UnionOwnershipPerson");
    a.addEdge("unionOwnershipLink", b);
    graph.tx().commit();
    var session = db(graph);
    var cache = GremlinPlanCache.instance(session);
    var child = GremlinToMatchTranslator.translate(graph.traversal().V()
        .hasLabel("UnionOwnershipPerson").out("unionOwnershipLink").asAdmin());
    assertThat(child).isNotNull();
    var scope = new GremlinToMatchStrategy.CacheScope(cache.getGeneration(), true);
    var initial = GremlinToMatchStrategy.buildPlanEntry(session, child, scope);
    assertThat(initial.sharedPlanTemplate).isTrue();
    var publisher = org.mockito.Mockito.spy(initial.value);
    org.mockito.Mockito.doAnswer(invocation -> org.mockito.Mockito.spy(invocation.callRealMethod()))
        .when(publisher).copy(org.mockito.ArgumentMatchers.any());
    var fingerprint = GremlinPlanFingerprint.fingerprint(child.inputs(), child.shaping());
    cache.putInternal(fingerprint, publisher, session, scope.generation);
    var template = cache.peekStored(fingerprint);
    org.mockito.Mockito.clearInvocations(template);
    var multi = GremlinToMatchTranslator.TranslationResult.multiPlan(
        List.of(new GremlinToMatchTranslator.TranslationResult.ChildPlan(
            child.inputs(), child.inputParameters(), child.cacheEligible())),
        List.of(), child.boundaryAlias(), child.outputType(), child.returnClass(), child.shaping());
    var returned = new CountDownLatch(1);
    var removed = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var retained = new AtomicReference<GremlinPlanCache.Entry<InternalExecutionPlan>>();
    var worker = new Thread(() -> {
      try {
        await(returned);
        cache.invalidate();
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        removed.countDown();
      }
    });
    worker.start();
    try {
      var strategy = new GremlinToMatchStrategy(t -> multi, builder(entry -> {
        assertThat(entry.value).isSameAs(template);
        assertThat(entry.sharedPlanTemplate).isTrue();
        retained.set(entry);
        returned.countDown();
        await(removed);
      }));
      var query = graph.traversal().withoutStrategies(GremlinToMatchStrategy.class).V().asAdmin();
      strategy.apply(query);
      assertThat(query.getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
      var boundary = (MultiPlanMatchStep<?, ?>) query.getStartStep();
      var copy = boundary.getPlans().getFirst();
      assertThat(copy).isNotSameAs(template);
      assertThat(cache.peekStored(fingerprint)).isNull();
      assertThat(TranslatorEquivalenceSupport.sortedIds(query.toList()))
          .containsExactly(b.id().toString());
      query.close();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).start();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).close();

      // A retained production holder remains shared even when installed by another boundary later.
      var survivor =
          graph.traversal().withoutStrategies(GremlinToMatchStrategy.class).V().asAdmin();
      new GremlinToMatchStrategy(t -> multi, (db, translation, access) -> retained.get())
          .apply(survivor);
      assertThat(survivor.getStartStep()).isInstanceOf(MultiPlanMatchStep.class);
      var survivorCopy = ((MultiPlanMatchStep<?, ?>) survivor.getStartStep()).getPlans().getFirst();
      assertThat(survivorCopy).isNotSameAs(template).isNotSameAs(copy);
      assertThat(TranslatorEquivalenceSupport.sortedIds(survivor.toList()))
          .containsExactly(b.id().toString());
      survivor.close();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).start();
      org.mockito.Mockito.verify(template, org.mockito.Mockito.never()).close();
    } finally {
      returned.countDown();
      removed.countDown();
      worker.join(30000);
    }
    assertThat(worker.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
  }

  /** A real cached empty adjacency observes data-only growth without changing its generation. */
  @Test
  public void cachedEmptyTargetSeesCommittedLinksAndTransactionChanges() {
    var a = graph.addVertex(T.label, "GrowthPerson");
    // Create the label before warming, without giving the measured start an outgoing edge.
    var b = graph.addVertex(T.label, "GrowthPerson");
    b.addEdge("growthLink", a);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    var inputs = new java.util.ArrayList<
        com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs>();
    cache.beforeUncachedPlanning = (input, context) -> {
      if (!input.runtimeRidStarts().isEmpty()) {
        inputs.add(input);
      }
    };
    try {
      var cold = graph.traversal().V(a.id()).out("growthLink").asAdmin();
      cold.applyStrategies();
      var template = ((YTDBMatchPlanStep<?, ?>) cold.getStartStep()).getPlan();
      assertThat(template.canBeCached()).isTrue();
      assertThat(inputs).hasSize(1);
      var fp = GremlinPlanFingerprint.fingerprint(inputs.getFirst(), ResultShaping.NONE);
      assertThat(cache.peekStored(fp)).isSameAs(template);
      assertThat(cold.toList()).isEmpty();
      var generation = cache.getInvalidationCounter();
      graph.tx().commit();
      graph.traversal().V(a.id()).next().addEdge("growthLink", graph.traversal().V(b.id()).next());
      graph.tx().commit();
      assertThat(cache.getInvalidationCounter()).isEqualTo(generation);
      for (boolean hasId : List.of(false, true)) {
        var source = graph.traversal();
        var query =
            (hasId ? source.V().hasId(a.id()) : source.V(a.id())).out("growthLink").asAdmin();
        query.applyStrategies();
        assertThat(((YTDBMatchPlanStep<?, ?>) query.getStartStep()).getPlan()).isSameAs(template);
        assertThat(TranslatorEquivalenceSupport.sortedIds(query.toList()))
            .containsExactly(b.id().toString());
      }
      assertThat(inputs).hasSize(1);
      var persistent = (com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) a.id();
      var missing = new com.jetbrains.youtrackdb.internal.core.id.RecordId(
          persistent.getCollectionId(), Long.MAX_VALUE);
      assertThat(graph.traversal().V(missing).out("growthLink").toList()).isEmpty();
      assertThat(graph.traversal().withoutStrategies(GremlinToMatchStrategy.class)
          .V(missing).out("growthLink").toList()).isEmpty();
      graph.traversal().V(a.id()).outE("growthLink").drop().iterate();
      assertThat(graph.traversal().V(a.id()).out("growthLink").toList()).isEmpty();
      graph.tx().rollback();
      assertThat(TranslatorEquivalenceSupport
          .sortedIds(graph.traversal().V(a.id()).out("growthLink").toList()))
          .containsExactly(b.id().toString());
      graph.traversal().V(a.id()).drop().iterate();
      assertThat(graph.traversal().V(a.id()).out("growthLink").toList()).isEmpty();
      graph.tx().rollback();
      assertThat(graph.traversal().V(a.id()).out("growthLink").toList()).hasSize(1);
      graph.traversal().V(a.id()).drop().iterate();
      graph.tx().commit();
      assertThat(graph.traversal().V(a.id()).out("growthLink").toList()).isEmpty();
      var nativeSource = graph.traversal().withoutStrategies(GremlinToMatchStrategy.class);
      assertThat(nativeSource.V(a.id()).out("growthLink").toList()).isEmpty();
      assertThat(inputs).hasSize(1);
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** An uncommitted create stays literal, then its persistent identity reuses the runtime shape. */
  @Test
  public void pendingCreateIsLiteralAndCommittedCreateReusesWarmTemplate() {
    var a = graph.addVertex(T.label, "CreatedPerson");
    var b = graph.addVertex(T.label, "CreatedPerson");
    a.addEdge("createdLink", b);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    var attempts = new java.util.ArrayList<
        com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs>();
    cache.beforeUncachedPlanning = (input, context) -> attempts.add(input);
    try {
      var cold = graph.traversal().V(a.id()).out("createdLink").asAdmin();
      cold.applyStrategies();
      var template = ((YTDBMatchPlanStep<?, ?>) cold.getStartStep()).getPlan();
      assertThat(cold.toList()).hasSize(1);
      var target =
          graph.traversal().withoutStrategies(GremlinToMatchStrategy.class).V(b.id()).next();
      var pending = graph.addVertex(T.label, "CreatedPerson");
      pending.addEdge("createdLink", target);
      var query = graph.traversal().V(pending.id()).out("createdLink").asAdmin();
      assertThat(RuntimeRidStartEligibility.evaluate(query, true)).isNull();
      query.applyStrategies();
      assertThat(query.toList()).hasSize(1);
      assertThat(attempts).hasSize(2);
      assertThat(attempts.getLast().runtimeRidStarts()).isEmpty();
      graph.tx().commit();
      var committed = graph.traversal().V(pending.id()).out("createdLink").asAdmin();
      committed.applyStrategies();
      var boundary = (YTDBMatchPlanStep<?, ?>) committed.getStartStep();
      assertThat(boundary.getPlan()).isSameAs(template);
      assertThat(committed.toList()).hasSize(1);
      assertThat(boundary.getPlan()).isNotSameAs(template);
      assertThat(attempts).hasSize(2);
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Warm base-class templates must recheck subclass and unrelated starts in the current mode. */
  @Test
  public void warmClassConstraintChecksActualStartInBothModes() {
    var session = db(graph);
    session.createVertexClass("ClassBase");
    session.createClass("ClassSub", "ClassBase");
    session.createVertexClass("ClassOther");
    graph.tx().commit();
    var base = graph.addVertex(T.label, "ClassBase");
    var sub = graph.addVertex(T.label, "ClassSub");
    var other = graph.addVertex(T.label, "ClassOther");
    for (var v : List.of(base, sub, other)) {
      v.addEdge("classLink", other);
    }
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    var runs = new AtomicInteger();
    cache.beforeUncachedPlanning = (input, context) -> runs.incrementAndGet();
    var config = db(graph).getConfiguration();
    var flag =
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT;
    var original = config.getValueAsBoolean(flag);
    try {
      for (boolean poly : List.of(true, false)) {
        config.setValue(flag, poly);
        for (boolean hasId : List.of(false, true)) {
          cache.invalidate();
          runs.set(0);
          InternalExecutionPlan template = null;
          for (var id : List.of(base.id(), sub.id(), other.id())) {
            var source = graph.traversal();
            var q = (hasId ? source.V().hasId(id) : source.V(id)).hasLabel("ClassBase")
                .out("classLink").asAdmin();
            q.applyStrategies();
            var step = (YTDBMatchPlanStep<?, ?>) q.getStartStep();
            if (template == null) {
              template = step.getPlan();
            }
            assertThat(step.getPlan()).isSameAs(template);
            var rows = TranslatorEquivalenceSupport.sortedIds(q.toList());
            var nativeSource = graph.traversal().withoutStrategies(GremlinToMatchStrategy.class);
            var nativeQ = (hasId ? nativeSource.V().hasId(id) : nativeSource.V(id))
                .hasLabel("ClassBase").out("classLink");
            assertThat(rows).isEqualTo(TranslatorEquivalenceSupport.sortedIds(nativeQ.toList()));
            assertThat(rows).hasSize(id.equals(base.id()) || poly && id.equals(sub.id()) ? 1 : 0);
            assertThat(step.getPlan()).isNotSameAs(template);
            assertThat(step.getPlan().getContext().getInputParameters())
                .isEqualTo(java.util.Map.of(0, id));
          }
          assertThat(runs.get()).isEqualTo(1);
        }
      }
    } finally {
      config.setValue(flag, original);
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Runtime plans skip shared maps during schema transactions and rebuild after committed DDL. */
  @Test
  public void runtimeSchemaChangesBypassAndInvalidateBothMaps() {
    var a = graph.addVertex(T.label, "SchemaPerson");
    var b = graph.addVertex(T.label, "SchemaPerson");
    a.addEdge("schemaLink", b);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    var attempts = new AtomicInteger();
    cache.beforeUncachedPlanning = (input, context) -> attempts.incrementAndGet();
    try {
      var cold = graph.traversal().V(a.id()).out("schemaLink").asAdmin();
      cold.applyStrategies();
      var template = ((YTDBMatchPlanStep<?, ?>) cold.getStartStep()).getPlan();
      assertThat(cold.toList()).hasSize(1);
      var hits = cache.getHits();
      var misses = cache.getMisses();
      var th = cache.getTranslationHits();
      var tm = cache.getTranslationMisses();
      db(graph).createClass("RuntimeTxOnly");
      var local = graph.traversal().V(a.id()).out("schemaLink").asAdmin();
      local.applyStrategies();
      var localStep = (YTDBMatchPlanStep<?, ?>) local.getStartStep();
      var owned = localStep.getPlan();
      assertThat(owned).isNotSameAs(template);
      assertThat(local.toList()).hasSize(1);
      assertThat(localStep.getPlan()).isSameAs(owned);
      assertThat(cache.getHits()).isEqualTo(hits);
      assertThat(cache.getMisses()).isEqualTo(misses);
      assertThat(cache.getTranslationHits()).isEqualTo(th);
      assertThat(cache.getTranslationMisses()).isEqualTo(tm);
      graph.tx().rollback();
      var restored = graph.traversal().V(a.id()).out("schemaLink").asAdmin();
      restored.applyStrategies();
      assertThat(((YTDBMatchPlanStep<?, ?>) restored.getStartStep()).getPlan()).isSameAs(template);
      assertThat(restored.toList()).hasSize(1);
      var generation = cache.getInvalidationCounter();
      db(graph).createClass("RuntimeCommitted");
      graph.tx().commit();
      assertThat(cache.getInvalidationCounter()).isGreaterThan(generation);
      var rebuilt = graph.traversal().V(a.id()).out("schemaLink").asAdmin();
      rebuilt.applyStrategies();
      assertThat(((YTDBMatchPlanStep<?, ?>) rebuilt.getStartStep()).getPlan())
          .isNotSameAs(template);
      assertThat(rebuilt.toList()).hasSize(1);
      assertThat(attempts.get()).isEqualTo(3);
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Lazy clones, unchanged reset and closed reopen retain per-execution state on real cache hits. */
  @Test
  public void publishedTemplateHasIndependentLazyClonesAndReopens() throws Exception {
    var a = graph.addVertex(T.label, "LifecyclePerson");
    var b = graph.addVertex(T.label, "LifecyclePerson");
    a.addEdge("lifecycleLink", b);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    var inputs = new AtomicReference<
        com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs>();
    cache.beforeUncachedPlanning = (input, context) -> inputs.set(input);
    try {
      var query = graph.traversal().V(a.id()).out("lifecycleLink").asAdmin();
      query.applyStrategies();
      var step = (YTDBMatchPlanStep<?, ?>) query.getStartStep();
      var template = step.getPlan();
      var clone = query.clone();
      var cloneStep = (YTDBMatchPlanStep<?, ?>) clone.getStartStep();
      assertThat(cloneStep.getPlan()).isSameAs(template);
      var fp = GremlinPlanFingerprint.fingerprint(inputs.get(), ResultShaping.NONE);
      assertThat(cache.peekStored(fp)).isSameAs(template);
      assertThat(template.getContext().getInputParameters()).isNullOrEmpty();
      assertThat(((com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext) template
          .getContext()).hasLocalVariableKeys()).isFalse();
      assertThat(query.next().id()).isEqualTo(b.id());
      var live = step.getPlan();
      assertThat(live).isNotSameAs(template);
      assertThat(live.getContext().getParent()).isNull();
      assertThat(live.getContext().getDatabaseSession()).isSameAs(db(graph));
      assertThat(live.getContext().getInputParameters()).isEqualTo(java.util.Map.of(0, a.id()));
      query.reset();
      assertThat(query.next().id()).isEqualTo(b.id());
      assertThat(step.getPlan()).isSameAs(live);
      assertThat(clone.next().id()).isEqualTo(b.id());
      assertThat(cloneStep.getPlan()).isNotSameAs(live).isNotSameAs(template);
      query.close();
      query.reset();
      assertThat(query.next().id()).isEqualTo(b.id());
      assertThat(step.getPlan()).isNotSameAs(live).isNotSameAs(template);
      query.close();
      clone.close();
      assertThat(template.prettyPrint(0, 2)).contains("FETCH FROM RID PARAMETER")
          .doesNotContain(a.id().toString(), b.id().toString());
      assertThat(((com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext) template
          .getContext()).hasLocalVariableKeys()).isFalse();
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** A non-cacheable unrestricted count adopts a restricted session's generic shared plan. */
  @Test
  public void nonCacheableCountAdoptsAnotherSessionsPublication() throws Exception {
    graph.addVertex(T.label, "AdoptionPerson", "name", "allowed");
    graph.addVertex(T.label, "AdoptionPerson", "name", "denied");
    graph.tx().commit();
    var admin = db(graph);
    var security = admin.getSharedContext().getSecurity();
    var policy = security.createSecurityPolicy(admin, "adoptionReadPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'allowed'");
    security.saveSecurityPolicy(admin, policy);
    security.setSecurityPolicy(admin, security.getRole(admin, "reader"),
        "database.class.AdoptionPerson", policy);
    graph.tx().commit();
    admin = db(graph);
    var cache = GremlinPlanCache.instance(admin);
    cache.invalidate();
    var generation = cache.getInvalidationCounter();
    var missed = new CountDownLatch(1);
    var published = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var stored = new AtomicReference<InternalExecutionPlan>();
    var publications = new AtomicInteger();
    var attempts = new AtomicInteger();
    cache.afterPlanPublication = publications::incrementAndGet;
    cache.beforeUncachedPlanning = (inputs, context) -> {
      attempts.incrementAndGet();
      if (context.getDatabaseSession().getCurrentUser().getName(context.getDatabaseSession())
          .equals("admin")) {
        missed.countDown();
        await(published);
      }
    };
    var worker = new Thread(() -> {
      try (var reader = readerGraph()) {
        await(missed);
        assertThat(GremlinPlanCache.instance(db(reader))).isSameAs(cache);
        var query =
            reader.traversal().withStrategies(GremlinToMatchStrategy.withTranslationCache(false))
                .V().hasLabel("AdoptionPerson").count().asAdmin();
        query.applyStrategies();
        assertThat(query.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
        var template = ((YTDBMatchPlanStep<?, ?>) query.getStartStep()).getPlan();
        assertThat(template.canBeCached()).isTrue();
        stored.set(template);
        assertThat(query.toList()).containsExactly(1L);
        assertThat(((YTDBMatchPlanStep<?, ?>) query.getStartStep()).getPlan())
            .isNotSameAs(template);
        reader.tx().rollback();
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        published.countDown();
      }
    });
    worker.start();
    try {
      var query =
          graph.traversal().withStrategies(GremlinToMatchStrategy.withTranslationCache(false))
              .V().hasLabel("AdoptionPerson").count().asAdmin();
      query.applyStrategies();
      assertThat(failure.get()).isNull();
      assertThat(query.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
      var boundary = (YTDBMatchPlanStep<?, ?>) query.getStartStep();
      assertThat(boundary.getPlan()).isSameAs(stored.get());
      assertThat(query.toList()).containsExactly(2L);
      assertThat(boundary.getPlan()).isNotSameAs(stored.get());
      assertThat(publications.get()).isEqualTo(1);
      assertThat(attempts.get()).isEqualTo(2);
      assertThat(cache.getInvalidationCounter()).isEqualTo(generation);
    } finally {
      missed.countDown();
      published.countDown();
      worker.join(30000);
      cache.beforeUncachedPlanning = null;
      cache.afterPlanPublication = null;
    }
    assertThat(worker.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
  }
}
