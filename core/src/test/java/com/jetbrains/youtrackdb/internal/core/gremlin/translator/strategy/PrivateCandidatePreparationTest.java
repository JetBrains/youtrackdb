package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Before;
import org.junit.Test;

/** Preparation-only cache tests never install a partial execution boundary. */
public class PrivateCandidatePreparationTest extends GraphBaseTest {

  private final AtomicInteger walks = new AtomicInteger();
  private final AtomicInteger builds = new AtomicInteger();
  private final PrivateCandidatePreparation.Policy policy =
      new PrivateCandidatePreparation.Policy(Set.of(1, 2, 3, 4, 5, 6));

  @Before
  public void setUpCandidateSchema() {
    var session = YTDBStrategyUtil.resolveYtdbSession(graph.traversal().V().asAdmin());
    session.getConfiguration().setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    var clazz = session.createVertexClass("CandidatePerson");
    clazz.createProperty("age", PropertyType.INTEGER);
    clazz.createProperty("name", PropertyType.STRING);
    session.createEdgeClass("candidateKnows");
    var first = graph.addVertex(T.label, "CandidatePerson", "age", 30, "name", "first");
    first.addEdge("candidateKnows",
        graph.addVertex(T.label, "CandidatePerson", "age", 40, "name", "second"));
    graph.tx().commit();
    GremlinPlanCache.instance(
        YTDBStrategyUtil.resolveYtdbSession(graph.traversal().V().asAdmin())).invalidate();
  }

  private PrivateCandidatePreparation preparation(boolean failBuild) {
    return new PrivateCandidatePreparation(
        new PrivatePrefixDiscovery((traversal, inputs, observer) -> {
          walks.incrementAndGet();
          return GremlinStepWalker.production().walkObserved(traversal, inputs, observer);
        }, (session, translation, scope) -> {
          builds.incrementAndGet();
          if (failBuild) {
            throw new IllegalStateException("test plan failure");
          }
          return GremlinToMatchStrategy.buildPlan(session, translation, scope);
        }));
  }

  private Traversal.Admin<?, ?> query(int prefix, int suffix) {
    return graph.traversal().V().hasLabel("CandidatePerson").has("age", prefix)
        .out("candidateKnows").optional(__.has("age", suffix)).asAdmin();
  }

  /** A real retained hit binds the prefix only and skips both recognition and planning. */
  @Test
  public void warmCandidateUsesFreshPrefixValuesAndLiteralExtent() {
    var prepare = preparation(false);
    var session = YTDBStrategyUtil.resolveYtdbSession(query(30, 40));
    var cache = GremlinPlanCache.instance(session);
    long hits = cache.getTranslationHits();
    long misses = cache.getTranslationMisses();
    for (int invocation = 0; invocation < 3; invocation++) {
      int value = invocation == 1 ? 40 : 30;
      try (var candidate = prepare.prepare(query(value, 100 + invocation), policy)) {
        assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
        assertThat(candidate.extent()).isEqualTo(3);
        assertThat(candidate.cacheHit()).isEqualTo(invocation > 0);
        assertThat(candidate.bindings()).containsEntry(0, value).hasSize(1);
        assertThat(candidate.plan().getContext().getInputParameters())
            .containsEntry(0, value).hasSize(1);
      }
    }
    assertThat(walks.get()).isEqualTo(2);
    assertThat(builds.get()).isEqualTo(1);
    assertThat(cache.getTranslationHits()).isEqualTo(hits + 2);
    assertThat(cache.getTranslationMisses()).isEqualTo(misses + 1);
  }

  /** The admission contract is part of every identity, including completed native decisions. */
  @Test
  public void deterministicRefusalIsRetainedButDoesNotPoisonAnotherPolicy() {
    var prepare = preparation(false);
    var none = new PrivateCandidatePreparation.Policy(Set.of());
    for (int invocation = 0; invocation < 2; invocation++) {
      try (var candidate = prepare.prepare(query(30, 40), none)) {
        assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.FOLD_CUT);
        assertThat(candidate.extent()).isEqualTo(-1);
        assertThat(candidate.cacheHit()).isEqualTo(invocation == 1);
      }
    }
    assertThat(walks.get()).isEqualTo(1);
    assertThat(builds.get()).isZero();
    try (var candidate = prepare.prepare(query(40, 30), policy)) {
      assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
      assertThat(candidate.cacheHit()).isFalse();
    }
    assertThat(walks.get()).isEqualTo(3);
  }

  /** A transient planner exception has an observable reason and is never a cached refusal. */
  @Test
  public void failedPlanIsRetried() {
    var prepare = preparation(true);
    for (int invocation = 0; invocation < 2; invocation++) {
      try (var candidate = prepare.prepare(query(30, 40), policy)) {
        assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.PLAN_BUILD_FAILURE);
        assertThat(candidate.cacheHit()).isFalse();
        assertThat(candidate.plan()).isNull();
      }
    }
    assertThat(walks.get()).isEqualTo(4);
    assertThat(builds.get()).isEqualTo(2);
  }

  /** Suffix ordered operands must not invalidate an independent prefix binding agreement. */
  @Test
  public void suffixOnlyOrderedOperandsDoNotEnterThePrefix() {
    var prepare = preparation(false);
    for (int invocation = 0; invocation < 2; invocation++) {
      var traversal = graph.traversal().V().hasLabel("CandidatePerson").has("age", 30)
          .out("candidateKnows").optional(__.identity()).order().by("age")
          .out("candidateKnows").has("age", 40 + invocation).limit(2).asAdmin();
      try (var candidate = prepare.prepare(traversal, policy)) {
        assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
        assertThat(candidate.extent()).isEqualTo(3);
        assertThat(candidate.bindings()).containsEntry(0, 30).hasSize(1);
        assertThat(candidate.cacheHit()).isEqualTo(invocation == 1);
      }
    }
    assertThat(walks.get()).isEqualTo(2);
    assertThat(builds.get()).isEqualTo(1);
  }

  /** Inputs on safe but unclassified steps bypass the shared cache rather than class-collide. */
  @Test
  public void unclassifiedSafeSuffixIsIncomplete() {
    var first = graph.traversal().V().out("candidateKnows").path().from("first").asAdmin();
    var second = graph.traversal().V().out("candidateKnows").path().from("second").asAdmin();
    var session = YTDBStrategyUtil.resolveYtdbSession(first);
    for (var traversal : List.of(first, second)) {
      assertThat(QuerySafetyAdmission.prepare(traversal).reason())
          .isEqualTo(QuerySafetyAdmission.Reason.SAFE);
      var inputs = GremlinStepWalker.WalkInputs.capture(traversal);
      assertThat(GremlinShapeExtractor.extractPartial(GremlinStepWalker.PRODUCTION_RECOGNISERS,
          GremlinStepWalker.TRANSPARENT_STEPS, traversal, session, inputs.includesMissing(),
          inputs.nulls(), inputs.polymorphic(), inputs).complete()).isFalse();
      assertThat(GremlinStepWalker.extractShape(traversal, session).complete()).isTrue();
      try (var candidate = preparation(false).prepare(traversal, policy)) {
        assertThat(candidate.cacheHit()).isFalse();
      }
    }
    // Every stateless native encoder is explicitly tied to the closed safety list.
    for (var step : graph.traversal().V().optional(__.identity()).asAdmin().getSteps()) {
      if (QuerySafetyAdmission.STRUCTURAL_NATIVE.contains(step.getClass())) {
        assertThat(QuerySafetyAdmission.nativeShapeComplete(step, new GremlinShapeEncoder(null)))
            .isTrue();
      }
    }
  }

  /** Two timeout flips around planning must not retain the intermediate default in MATCH. */
  @Test
  public void timeoutFlipUsesCapturedValueOnColdAndWarmPlans() {
    var session = YTDBStrategyUtil.resolveYtdbSession(query(30, 40));
    var configuration = session.getConfiguration();
    var previous = configuration.getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    configuration.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 17001L);
    var prepare = new PrivateCandidatePreparation(new PrivatePrefixDiscovery(
        GremlinStepWalker.production()::walkObserved, (db, translation, scope) -> {
          assertThat(scope.commandTimeout).isEqualTo(17001L);
          configuration.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 29001L);
          try {
            return GremlinToMatchStrategy.buildPlan(db, translation, scope);
          } finally {
            configuration.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 17001L);
          }
        }));
    try {
      // Synchronise the cache's timeout stamp before capturing the tested preparation.
      GremlinPlanCache.instance(session).getTranslationEntry("timeout-test-miss", session);
      for (int invocation = 0; invocation < 2; invocation++) {
        try (var candidate = prepare.prepare(query(30, 40), policy)) {
          assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
          assertThat(candidate.cacheHit()).isEqualTo(invocation == 1);
          assertThat(candidate.plan().prettyPrint(0, 2).toString())
              .contains("17001ms").doesNotContain("29001ms");
        }
      }
    } finally {
      configuration.setValue(GlobalConfiguration.COMMAND_TIMEOUT, previous);
    }
  }

  /** Full supported outcomes retain the whole root extent, including ordered expansion steps. */
  @Test
  public void fullOrderedCandidateRetainsWholeRootExtent() {
    var prepare = preparation(false);
    for (int invocation = 0; invocation < 2; invocation++) {
      var traversal = graph.traversal().V().hasLabel("CandidatePerson")
          .has("age", 30 + invocation).order().by("age").out("candidateKnows").limit(2)
          .asAdmin();
      try (var candidate = prepare.prepare(traversal, policy)) {
        assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.FULL_SUPPORTED);
        assertThat(candidate.extent()).isEqualTo(traversal.getSteps().size());
        assertThat(candidate.cacheHit()).isEqualTo(invocation == 1);
        assertThat(candidate.bindings()).containsEntry(0, 30 + invocation).hasSize(1);
        assertThat(candidate.plan()).isNotNull();
      }
    }
    assertThat(walks.get()).isEqualTo(1);
    assertThat(builds.get()).isEqualTo(1);
  }

  /** Unsafe queries and disabled translation refuse before any recognition or planning. */
  @Test
  public void admissionAndEnablementRefuseBeforeWalking() {
    var prepare = preparation(false);
    try (var candidate = prepare.prepare(graph.traversal().V().drop().asAdmin(), policy)) {
      assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SAFETY_VETO);
      assertThat(candidate.plan()).isNull();
    }
    var session = YTDBStrategyUtil.resolveYtdbSession(query(30, 40));
    session.getConfiguration().setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
    try {
      try (var candidate = prepare.prepare(query(30, 40), policy)) {
        assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.DISABLED);
        assertThat(candidate.plan()).isNull();
      }
    } finally {
      session.getConfiguration().setValue(
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    }
    assertThat(walks.get()).isZero();
    assertThat(builds.get()).isZero();
  }

  /** Native predicate inputs use the encoder, while steps outside the safe list stay incomplete. */
  @Test
  public void nativePredicateClassificationEncodesOperatorInputs() {
    var keys = new java.util.ArrayList<String>();
    for (var predicate : List.of(org.apache.tinkerpop.gremlin.process.traversal.P.eq(30),
        org.apache.tinkerpop.gremlin.process.traversal.P.gt(30))) {
      var traversal = graph.traversal().V().is(predicate).asAdmin();
      var encoder = new GremlinShapeEncoder(null);
      assertThat(QuerySafetyAdmission.prepare(traversal).reason())
          .isEqualTo(QuerySafetyAdmission.Reason.SAFE);
      assertThat(QuerySafetyAdmission.nativeShapeComplete(traversal.getEndStep(), encoder))
          .isTrue();
      keys.add(encoder.key());
      assertThat(QuerySafetyAdmission.nativeShapeComplete(traversal.getStartStep(), encoder))
          .isFalse();
    }
    assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
  }

  /** Captured productive keys cannot change when their source collection changes later. */
  @Test
  public void walkInputsCopyMutableSettings() {
    var keys = new java.util.HashSet<>(Set.of("name"));
    var inputs = new GremlinStepWalker.WalkInputs(true, false, null, keys, true, null, true, 0);
    keys.add("age");
    assertThat(inputs.productiveKeys()).containsExactly("name");
  }
}
