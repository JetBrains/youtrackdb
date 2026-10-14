package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Deterministic two-map generation races and explicit ownership tests. */
public class GenerationStampedGremlinPlanCacheTest extends GraphBaseTest {

  /** Another session misses both maps after advance, before clear, and after late old insertion. */
  @Test
  public void advanceBeforeClearAndLatePublicationMissInBothMaps() throws Exception {
    var advanced = new CountDownLatch(1);
    var clear = new CountDownLatch(1);
    var cache = new GremlinPlanCache(8, () -> {
      advanced.countDown();
      await(clear);
    });
    var db = graphSession();
    var generation = cache.getGeneration();
    var plan = plan(db);
    cache.putInternal("physical", plan, db, generation);
    cache.putTranslationInternal("translation", new GremlinTranslationTemplate.Decline(), db,
        generation);
    var failure = new AtomicReference<Throwable>();
    var invalidator = new Thread(() -> {
      try {
        cache.invalidate();
      } catch (Throwable t) {
        failure.set(t);
      }
    });
    invalidator.start();
    try {
      await(advanced);
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        assertMisses(cache, other);
        cache.putInternal("physical", plan(other), other, generation);
        cache.putTranslationInternal("translation", new GremlinTranslationTemplate.Decline(),
            other, generation);
        assertMisses(cache, other);
      }
    } finally {
      clear.countDown();
      invalidator.join(10_000);
      db.activateOnCurrentThread();
    }
    assertThat(invalidator.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
    assertMisses(cache, db);
    cache.putInternal("physical", plan, db, generation);
    cache.putTranslationInternal("translation", new GremlinTranslationTemplate.Decline(), db,
        generation);
    assertMisses(cache, db);
    // Fresh builds replace stale entries without post-insert cleanup.
    var fresh = cache.putInternal("physical", plan, db, cache.getGeneration());
    var decline = new GremlinTranslationTemplate.Decline();
    cache.putTranslationInternal("translation", decline, db, cache.getGeneration());
    assertThat(cache.templateInternal("physical", db)).isSameAs(fresh);
    assertThat(cache.getTranslationInternal("translation", db)).isSameAs(decline);
  }

  /** A delayed old publisher cannot replace either newer entry with the same key. */
  @Test
  public void newerPhysicalAndTranslationPublicationsSurviveOldBuilds() {
    var cache = new GremlinPlanCache(8);
    var db = graphSession();
    var old = cache.getGeneration();
    var plan = plan(db);
    cache.invalidate();
    var fresh = cache.putInternal("physical", plan, db, cache.getGeneration());
    var decline = new GremlinTranslationTemplate.Decline();
    cache.putTranslationInternal("translation", decline, db, cache.getGeneration());
    assertThat(cache.putInternal("physical", plan, db, old)).isNull();
    cache.putTranslationInternal("translation", new GremlinTranslationTemplate.Decline(), db, old);
    assertThat(cache.peekStored("physical")).isSameAs(fresh);
    assertThat(cache.getTranslationInternal("translation", db)).isSameAs(decline);
  }

  /** Real index invalidation makes an injected late decline miss and the next walk replace it. */
  @Test
  public void staleDeclineAfterIndexCreationIsReevaluated() {
    session.createVertexClass("Person").createProperty("name",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var db = graphSession();
    db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        true);
    var cache = GremlinPlanCache.instance(db);
    var key = GremlinStepWalker.extractShape(
        graph.traversal().V().hasLabel("Person").has("name", "Alice").asAdmin(), db).key();
    var old = cache.getGeneration();
    graph.tx().commit();
    session.activateOnCurrentThread();
    session.execute("CREATE INDEX Person_name ON Person (name) NOTUNIQUE").close();
    db = graphSession();
    assertThat(cache.getGeneration()).isGreaterThan(old);
    cache.putTranslationInternal(key, new GremlinTranslationTemplate.Decline(), db, old);
    assertThat(cache.getTranslationInternal(key, db)).isNull();
    var traversal = graph.traversal().V().hasLabel("Person").has("name", "Alice").asAdmin();
    GremlinToMatchStrategy.instance().apply(traversal);
    assertThat(traversal.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    assertThat(traversal.toList()).hasSize(1);
    assertThat(cache.getTranslationInternal(key, db))
        .isInstanceOf(GremlinTranslationTemplate.Translate.class);
  }

  /** Shape extraction reads schema before harvesting predicates. DDL during that harvest keeps g0. */
  @Test
  public void invalidationDuringShapeExtractionUsesTheGenerationCapturedBeforeItsMetadataRead() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var db = enabledSession();
    var cache = GremlinPlanCache.instance(db);
    var before = cache.getGeneration();
    var traversal = graph.traversal().V().has("name", "Alice").asAdmin();
    var key = GremlinStepWalker.extractShape(traversal, db).key();
    var has = spy((HasStep<?>) traversal.getSteps().get(1));
    var reads = new AtomicInteger();
    doAnswer(call -> {
      var containers = call.callRealMethod();
      // The first intercepted read is contributeShape inside shape extraction. Invalidate
      // after the extractor obtains schema but before the walker reads the same containers.
      if (reads.incrementAndGet() == 1) {
        cache.invalidate();
      }
      return containers;
    }).when(has).getHasContainers();
    traversal.removeStep(1);
    traversal.addStep(1, has);
    new GremlinToMatchStrategy(GremlinToMatchTranslator::translate, (s, translation, scope) -> {
      assertThat(scope.generation).isEqualTo(before);
      return GremlinToMatchStrategy.buildPlan(s, translation, scope);
    }, true).apply(traversal);
    assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    assertThat(cache.getGeneration()).isGreaterThan(before);
    assertThat(traversal.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    assertThat(cache.getTranslationInternal(key, db)).isNull();
  }

  /** Invalidation inside the walker stamps both late publications with the original generation. */
  @Test
  public void invalidationBeforeWalkingCannotRestampPhysicalOrTranslationPublication() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var db = enabledSession();
    var cache = GremlinPlanCache.instance(db);
    var traversal = graph.traversal().V().has("name", "Alice").asAdmin();
    var key = GremlinStepWalker.extractShape(traversal, db).key();
    var strategy = new GremlinToMatchStrategy(t -> {
      cache.invalidate();
      return GremlinToMatchTranslator.translate(t);
    }, GremlinToMatchStrategy::buildPlan, true);
    strategy.apply(traversal);
    assertThat(traversal.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) traversal.getStartStep();
    var privatePlan = boundary.getPlan();
    assertThat(cache.getTranslationInternal(key, db)).isNull();
    assertThat(traversal.toList()).hasSize(1);
    assertThat(boundary.getPlan()).isSameAs(privatePlan);
  }

  /** Recognition of physical ownership never restamps the translation publication after DDL. */
  @Test
  public void invalidationAfterPhysicalOwnershipLeavesTranslationStale() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var db = enabledSession();
    var cache = GremlinPlanCache.instance(db);
    var traversal = graph.traversal().V().has("name", "Alice").asAdmin();
    var key = GremlinStepWalker.extractShape(traversal, db).key();
    var strategy = new GremlinToMatchStrategy(GremlinToMatchTranslator::translate,
        (s, translation, scope) -> {
          var template = GremlinToMatchStrategy.buildPlan(s, translation, scope);
          assertThat(scope.isShared(template)).isTrue();
          cache.invalidate();
          return template;
        }, true);
    strategy.apply(traversal);
    assertThat(traversal.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    assertThat(cache.getTranslationInternal(key, db)).isNull();
    assertThat(traversal.toList()).hasSize(1);
  }

  /** A g0 walk can use a g1 physical hit but its translation entry must still retain g0. */
  @Test
  public void oldWalkUsingAnotherSessionsFreshPhysicalEntryKeepsItsOwnStamp() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var db = enabledSession();
    var cache = GremlinPlanCache.instance(db);
    var traversal = graph.traversal().V().has("name", "Alice").asAdmin();
    var key = GremlinStepWalker.extractShape(traversal, db).key();
    var strategy = new GremlinToMatchStrategy(t -> {
      var translation = GremlinToMatchTranslator.translate(t);
      cache.invalidate();
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        GremlinToMatchStrategy.buildPlan(other, translation,
            new GremlinToMatchStrategy.CacheScope(cache.getGeneration(), true));
      } finally {
        db.activateOnCurrentThread();
      }
      return translation;
    }, GremlinToMatchStrategy::buildPlan, true);
    var hits = cache.getHits();
    strategy.apply(traversal);
    assertThat(cache.getHits()).isEqualTo(hits + 1);
    assertThat(cache.getTranslationInternal(key, db)).isNull();
    assertThat(traversal.toList()).hasSize(1);
  }

  /** A fixture private plan sharing a physical key does not become a lazy shared boundary. */
  @Test
  public void keyEqualityDoesNotConferSharedOwnership() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var db = enabledSession();
    var cache = GremlinPlanCache.instance(db);
    var translation = GremlinToMatchTranslator.translate(
        graph.traversal().V().has("name", "Alice").asAdmin());
    var scope = new GremlinToMatchStrategy.CacheScope(cache.getGeneration(), true);
    var shared = GremlinToMatchStrategy.buildPlan(db, translation, scope);
    assertThat(scope.isShared(shared)).isTrue();
    var privatePlan = shared.copy(new BasicCommandContext(db));
    var traversal = graph.traversal().V().has("name", "Alice").asAdmin();
    var key = GremlinStepWalker.extractShape(traversal, db).key();
    new GremlinToMatchStrategy(t -> translation, (s, tr, access) -> privatePlan, true)
        .apply(traversal);
    assertThat(cache.containsTranslation(key)).isFalse();
    var boundary = (YTDBMatchPlanStep<?, ?>) traversal.getStartStep();
    assertThat(boundary.getPlan()).isSameAs(privatePlan);
    assertThat(traversal.toList()).hasSize(1);
    assertThat(boundary.getPlan()).isSameAs(privatePlan);
  }

  /** A timeout configuration change advances before lookup and invalidates both maps. */
  @Test
  public void changedTimeoutRejectsPhysicalAndTranslationTemplates() {
    var db = graphSession();
    var previous = db.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    var cache = new GremlinPlanCache(8);
    var generation = cache.getGeneration();
    cache.putInternal("physical", plan(db), db, generation);
    cache.putTranslationInternal("translation", new GremlinTranslationTemplate.Decline(), db,
        generation);
    db.getConfiguration().setValue(GlobalConfiguration.COMMAND_TIMEOUT, previous + 1);
    try {
      assertThat(cache.templateInternal("physical", db)).isNull();
      assertThat(cache.getGeneration()).isGreaterThan(generation);
      assertThat(cache.getTranslationInternal("translation", db)).isNull();
    } finally {
      db.getConfiguration().setValue(GlobalConfiguration.COMMAND_TIMEOUT, previous);
    }
  }

  /** Disabled maps and null arguments do not publish or count, and non-cacheable plans stay private. */
  @Test
  public void disabledAndRefusedPublicationsHaveNoSharedProvenance() {
    var db = graphSession();
    var disabled = new GremlinPlanCache(0);
    var plan = plan(db);
    assertThat(disabled.putInternal("p", plan, db, 0)).isNull();
    assertThat(disabled.templateInternal("p", db)).isNull();
    disabled.putTranslationInternal("t", new GremlinTranslationTemplate.Decline(), db, 0);
    assertThat(disabled.getTranslationInternal("t", db)).isNull();
    assertThat(disabled.containsTranslation("t")).isFalse();
    assertThat(disabled.getMisses()).isZero();
    var cache = new GremlinPlanCache(8);
    assertThat(cache.putInternal(null, plan, db, 0)).isNull();
    assertThat(cache.templateInternal(null, db)).isNull();
    assertThat(cache.getTranslationInternal(null, db)).isNull();
    cache.putTranslationInternal(null, new GremlinTranslationTemplate.Decline(), db, 0);
    var refused = mock(InternalExecutionPlan.class);
    assertThat(cache.putInternal("refused", refused, db, 0)).isNull();
    assertThat(GremlinPlanCache.put(null, plan, db, 0)).isNull();
    assertThat(GremlinPlanCache.put("p", plan, null, 0)).isNull();
    assertThat(GremlinPlanCache.template(null, db)).isNull();
    assertThat(GremlinPlanCache.template("p", null)).isNull();
    assertThat(GremlinPlanCache.get(null, new BasicCommandContext(db), db)).isNull();
    assertThat(GremlinPlanCache.get("p", new BasicCommandContext(db), null)).isNull();
    assertThat(GremlinPlanCache.getTranslation(null, db)).isNull();
    assertThat(GremlinPlanCache.getTranslation("t", null)).isNull();
    GremlinPlanCache.putTranslation(null, new GremlinTranslationTemplate.Decline(), db, 0);
    GremlinPlanCache.putTranslation("t", null, db, 0);
    GremlinPlanCache.putTranslation("t", new GremlinTranslationTemplate.Decline(), null, 0);
  }

  private void assertMisses(GremlinPlanCache cache, DatabaseSessionEmbedded db) {
    assertThat(cache.peekStored("physical")).isNull();
    assertThat(cache.contains("physical")).isFalse();
    assertThat(cache.templateInternal("physical", db)).isNull();
    assertThat(cache.getInternal("physical", new BasicCommandContext(db), db)).isNull();
    assertThat(cache.containsTranslation("translation")).isFalse();
    assertThat(cache.getTranslationInternal("translation", db)).isNull();
  }

  private static InternalExecutionPlan plan(DatabaseSessionEmbedded db) {
    var plan = mock(InternalExecutionPlan.class);
    when(plan.canBeCached()).thenReturn(true);
    when(plan.copy(any())).thenAnswer(invocation -> {
      var copy = mock(InternalExecutionPlan.class);
      when(copy.getContext()).thenReturn(invocation.getArgument(0));
      return copy;
    });
    return plan;
  }

  private DatabaseSessionEmbedded enabledSession() {
    var db = graphSession();
    db.getConfiguration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        true);
    return db;
  }

  private DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("race participant must make progress")
          .isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
