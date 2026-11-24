package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Cross-feature contracts between runtime starts, deferred bindings and cached routing. */
@Category(SequentialTest.class)
public class RuntimeRidMergeTest extends GraphBaseTest {

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  /** Both container orders keep the RID slot and ordinary values on cold and warm execution. */
  @Test
  public void hasIdContainerOrderRebindsColdAndWarm() {
    var a = graph.addVertex(T.label, "MergePerson", "name", "first");
    var b = graph.addVertex(T.label, "MergePerson", "name", "second");
    var x = graph.addVertex(T.label, "MergePerson", "name", "x");
    a.addEdge("mergeLink", x);
    b.addEdge("mergeLink", a);
    graph.tx().commit();
    var support = new TranslatorEquivalenceSupport(this::db);
    support.setTranslatorEnabled(true);
    var cache = GremlinPlanCache.instance(db());
    for (boolean idFirst : List.of(false, true)) {
      cache.invalidate();
      for (int run = 0; run < 3; run++) {
        var id = run == 1 ? b.id() : a.id();
        var name = run == 1 ? "second" : "first";
        var hits = cache.getTranslationHits();
        var start = graph.traversal().V();
        if (idFirst) {
          start.hasId(id).has("name", name);
        } else {
          start.has("name", name).hasId(id);
        }
        var query = start.out("mergeLink").values("name").asAdmin();
        query.applyStrategies();
        assertThat(query.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
        assertThat(((YTDBMatchPlanStep<?, ?>) query.getStartStep()).getPlan().prettyPrint(0, 2))
            .contains("FETCH FROM RID PARAMETER");
        assertThat(query.toList()).containsExactly(run == 1 ? "first" : "x");
        assertThat(cache.getTranslationHits()).isEqualTo(hits + (run == 0 ? 0 : 1));
      }
    }
  }

  private GraphTraversal<?, ?> form(GraphTraversalSource source, Object id,
      boolean hasId, int form) {
    var start = hasId ? source.V().hasId(id) : source.V(id);
    if (form == 4) {
      return start.outE("mergeWork").has("workFrom", 2009)
          .inV().hasLabel("MergeOrganisation").count();
    }
    var friends = start.out("mergeLink").hasLabel("MergePerson");
    return switch (form) {
      case 0 -> friends.count();
      case 1 -> friends.values("name");
      case 2 -> friends.order().by("name");
      default -> friends.has("name", "friend").count();
    };
  }

  /** Each PR 1341 form and spelling hits once without planning or a runtime-start retry. */
  @Test
  public void benchmarkFormsWarmWithoutPlanningOnBothSpellings() {
    var a = graph.addVertex(T.label, "MergePerson", "name", "root");
    var b = graph.addVertex(T.label, "MergePerson", "name", "other");
    var x = graph.addVertex(T.label, "MergePerson", "name", "friend");
    var organisation = graph.addVertex(T.label, "MergeOrganisation");
    for (var root : List.of(a, b)) {
      root.addEdge("mergeLink", x);
      root.addEdge("mergeWork", organisation, "workFrom", 2009);
    }
    graph.tx().commit();
    var support = new TranslatorEquivalenceSupport(this::db);
    support.setTranslatorEnabled(true);
    var cache = GremlinPlanCache.instance(db());
    var plans = new AtomicInteger();
    cache.beforeUncachedPlanning = (inputs, context) -> plans.incrementAndGet();
    try {
      for (boolean hasId : List.of(false, true)) {
        for (int shape = 0; shape < 5; shape++) {
          cache.invalidate();
          var cold = form(graph.traversal(), a.id(), hasId, shape).asAdmin();
          cold.applyStrategies();
          assertThat(cold.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
          var expected = cold.toList();
          plans.set(0);
          var hits = cache.getTranslationHits();
          var warm = form(graph.traversal(), b.id(), hasId, shape).asAdmin();
          warm.applyStrategies();
          assertThat(warm.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
          assertThat(((YTDBMatchPlanStep<?, ?>) warm.getStartStep()).getPlan().prettyPrint(0, 2))
              .contains("FETCH FROM RID PARAMETER");
          assertThat(warm.toList()).isEqualTo(expected);
          assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
          assertThat(plans).hasValue(0);
        }
      }
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Full and buffered encoders share one runtime decision and produce the walker's RID slot. */
  @Test
  public void runtimeStartOracleAgreesWithWalkAndFastEmission() {
    var a = graph.addVertex(T.label, "MergePerson", "name", "root");
    graph.tx().commit();
    for (boolean hasId : List.of(false, true)) {
      var query = form(graph.traversal(), a.id(), hasId, 0).asAdmin();
      var session = db();
      var decision = RuntimeRidStartEligibility.evaluate(query, true);
      assertThat(decision).isNotNull();
      var environment = TranslationEnvironment.resolve(query, session);
      var settings =
          com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil
              .translationSettings(query, session);
      var full = GremlinStepWalker.extractShape(query, session, settings.orderIncludesMissingKey(),
          settings.nullPlacements(), settings.polymorphic(), environment, decision);
      var walk = GremlinStepWalker.production().walk(query, GremlinStepWalker.NO_CHILD_SCOPE,
          settings.orderIncludesMissingKey(), settings.nullPlacements(), settings.polymorphic(),
          true, decision, environment);
      assertThat(full.bindings()).isEqualTo(walk.inputParameters());
      assertThat(GremlinToMatchStrategy.matchingRuntimeRidSlot(full, walk.inputs())).isTrue();
      if (!hasId) {
        try (var buffer = GremlinShapeBuffer.acquire()) {
          var fast = GremlinShapeExtractor.emit(GremlinStepWalker.PRODUCTION_RECOGNISERS,
              GremlinStepWalker.TRANSPARENT_STEPS, query, settings.orderIncludesMissingKey(),
              settings.nullPlacements(), settings.polymorphic(), environment,
              new GremlinShapeEncoder(session.getSchema(), buffer, decision)).extraction();
          assertThat(fast.key()).isEqualTo(full.key());
          assertThat(fast.bindings()).isEqualTo(full.bindings());
          assertThat(fast.runtimeRidSlot()).isEqualTo(full.runtimeRidSlot());
        }
      }
    }
  }

  /** Dedup and property maps stay literal because runtime-start eligibility excludes their tails. */
  @Test
  public void dedupAndValueMapDoNotUseRuntimeStarts() {
    var a = graph.addVertex(T.label, "MergePerson");
    graph.tx().commit();
    assertThat(RuntimeRidStartEligibility.evaluate(
        graph.traversal().V(a.id()).out("mergeLink").dedup().asAdmin(), true)).isNull();
    assertThat(RuntimeRidStartEligibility.evaluate(
        graph.traversal().V(a.id()).out("mergeLink").valueMap().asAdmin(), true)).isNull();
  }
}
