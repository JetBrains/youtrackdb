package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

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
        assertThat(candidate.extent()).isEqualTo(5);
        assertThat(candidate.cacheHit()).isEqualTo(invocation == 1);
        assertThat(candidate.bindings()).containsEntry(0, 30 + invocation).hasSize(1);
        assertThat(candidate.plan()).isNotNull();
      }
    }
    assertThat(walks.get()).isEqualTo(1);
    assertThat(builds.get()).isEqualTo(1);
  }

  /** Source slicing before expansion retains a full candidate and actually reuses its template. */
  @Test
  public void sourceSliceBeforeHopHasWarmCandidate() {
    var prepare = preparation(false);
    for (int invocation = 0; invocation < 2; invocation++) {
      var traversal = graph.traversal().V().hasLabel("CandidatePerson")
          .order().by("age").limit(2).out("candidateKnows").asAdmin();
      try (var candidate = prepare.prepare(traversal, policy)) {
        assertThat(candidate.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.FULL_SUPPORTED);
        assertThat(candidate.extent()).isEqualTo(5);
        assertThat(candidate.cacheHit()).isEqualTo(invocation == 1);
        assertThat(candidate.plan()).isNotNull();
      }
    }
    assertThat(walks.get()).isEqualTo(1);
    assertThat(builds.get()).isEqualTo(1);
  }

  /** Full-path apply captures its timeout before the real builder sees a changed live setting. */
  @Test
  public void fullStrategyUsesCapturedTimeoutWhenConfigurationFlipsBeforePlanning() {
    var traversal = graph.traversal().V().hasLabel("CandidatePerson")
        .out("candidateKnows").asAdmin();
    var session = YTDBStrategyUtil.resolveYtdbSession(traversal);
    var configuration = session.getConfiguration();
    long previous = configuration.getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    configuration.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 17001L);
    var planned = new AtomicInteger();
    var strategy = new GremlinToMatchStrategy(GremlinToMatchTranslator::translate,
        (db, translation, scope) -> {
          planned.incrementAndGet();
          configuration.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 29001L);
          return GremlinToMatchStrategy.buildPlan(db, translation, scope);
        });
    GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.set(true);
    try {
      strategy.apply(traversal);
      assertThat(planned.get()).isEqualTo(1);
      assertThat(traversal.getStartStep())
          .isInstanceOf(
              com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep.class);
      var boundary =
          (com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep<?,
              ?>) traversal.getStartStep();
      assertThat(boundary.getPlan().prettyPrint(0, 2).toString())
          .contains("17001ms").doesNotContain("29001ms");
      boundary.getPlan().close();
    } finally {
      GremlinToMatchStrategy.FORCE_MATCH_FOR_TESTS.remove();
      configuration.setValue(GlobalConfiguration.COMMAND_TIMEOUT, previous);
    }
  }

  /** Full-only extraction does not allocate private root checkpoints. */
  @Test
  public void checkpointsBelongOnlyToPartialExtraction() {
    var traversal = query(30, 40);
    var session = YTDBStrategyUtil.resolveYtdbSession(traversal);
    assertThat(GremlinStepWalker.extractShape(traversal, session).rootEnds()).isEmpty();
    var inputs = GremlinStepWalker.WalkInputs.capture(traversal);
    var partial = GremlinShapeExtractor.extractPartial(GremlinStepWalker.PRODUCTION_RECOGNISERS,
        GremlinStepWalker.TRANSPARENT_STEPS, traversal, session, inputs.includesMissing(),
        inputs.nulls(), inputs.polymorphic(), inputs);
    assertThat(partial.rootEnds()).hasSize(4);
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

  /** Capture the actual lookup key, so interleavings cannot accidentally exercise a cold key. */
  private final class PublicationProbe implements AutoCloseable {
    final DatabaseSessionEmbedded session = YTDBStrategyUtil.resolveYtdbSession(query(30, 40));
    final GremlinPlanCache cache = spy(new GremlinPlanCache(32));
    final MockedStatic<GremlinPlanCache> statics =
        mockStatic(GremlinPlanCache.class, CALLS_REAL_METHODS);
    final List<InternalExecutionPlan> originals = new ArrayList<>();
    final List<InternalExecutionPlan> copies = new ArrayList<>();
    String key;
    Runnable duringWalk = () -> {
    };
    Runnable duringBuild = () -> {
    };
    boolean cacheable = true;

    PublicationProbe() {
      statics.when(() -> GremlinPlanCache.instance(session)).thenReturn(cache);
      doAnswer(call -> {
        key = call.getArgument(0);
        return call.callRealMethod();
      }).when(cache).getTranslationEntry(anyString(), any());
      cache.getTranslationEntry("synchronise-timeout", session);
    }

    PrivateCandidatePreparation prepare() {
      return new PrivateCandidatePreparation(
          new PrivatePrefixDiscovery((traversal, inputs, obs) -> {
            var callback = duringWalk;
            duringWalk = () -> {
            };
            callback.run();
            return GremlinStepWalker.production().walkObserved(traversal, inputs, obs);
          }, (db, translation, scope) -> {
            duringBuild.run();
            var original = spy(GremlinToMatchStrategy.buildPlan(db, translation, scope));
            originals.add(original);
            doReturn(cacheable).when(original).canBeCached();
            doAnswer(call -> {
              var copy = spy((InternalExecutionPlan) call.callRealMethod());
              copies.add(copy);
              return copy;
            }).when(original).copy(any(CommandContext.class));
            return original;
          }));
    }

    GremlinPlanCache.StampedTranslation resident() {
      return cache.getTranslationEntry(key, session);
    }

    @Override
    public void close() {
      statics.close();
    }
  }

  /** A clear between lookup and publication forbids retaining a stale partial candidate. */
  @Test
  public void invalidationDuringPartialBuildRejectsPublicationButKeepsPrivateCopy() {
    try (var probe = new PublicationProbe()) {
      long generation = probe.cache.getGeneration();
      probe.duringBuild = probe.cache::invalidate;
      try (var own = probe.prepare().prepare(query(30, 40), policy)) {
        assertThat(own.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
        assertThat(own.cacheHit()).isFalse();
        assertThat(own.plan().getContext().getInputParameters()).containsEntry(0, 30);
        assertThat(probe.cache.getGeneration()).isGreaterThan(generation);
        assertThat(probe.resident()).isNull();
        assertThat(probe.cache.getTranslationHits()).isZero();
        verify(probe.originals.getFirst()).close();
        verify(probe.copies.getFirst()).close();
        verify(own.plan(), never()).close();
      }
      verify(probe.copies.getLast()).close();
    }
  }

  /** A live timeout change cannot relabel a candidate built from the captured old timeout. */
  @Test
  public void timeoutChangeBetweenLookupAndPublicationCannotBeReused() {
    try (var probe = new PublicationProbe()) {
      var config = probe.session.getConfiguration();
      long old = config.getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
      try {
        config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 17001L);
        probe.cache.getTranslationEntry("synchronise", probe.session);
        long generation = probe.cache.getGeneration();
        probe.duringBuild = () -> config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 29001L);
        try (var own = probe.prepare().prepare(query(30, 40), policy)) {
          assertThat(own.plan().prettyPrint(0, 2).toString()).contains("17001ms");
          assertThat(own.cacheHit()).isFalse();
        }
        // Lookup observes the new timeout and clears the old-snapshot publication.
        assertThat(probe.resident()).isNull();
        assertThat(probe.cache.getGeneration()).isGreaterThan(generation);
        probe.duringBuild = () -> {
        };
        try (var fresh = probe.prepare().prepare(query(40, 30), policy)) {
          assertThat(fresh.cacheHit()).isFalse();
          assertThat(fresh.plan().prettyPrint(0, 2).toString()).contains("29001ms");
        }
        assertThat(probe.cache.getTranslationHits()).isZero();
      } finally {
        config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, old);
      }
    }
  }

  /** Partial, private-full and complete-refusal losers keep their own outcome without retry. */
  @Test
  public void coldLosersKeepOwnCompleteOutcomeAndNeverRepairWinner() {
    for (int kind = 0; kind < 3; kind++) {
      try (var probe = new PublicationProbe()) {
        var winner = new GremlinTranslationTemplate.Candidate(-1,
            PrivatePrefixDiscovery.Reason.COMPLETE_NATIVE, null);
        var envelope = new java.util.concurrent.atomic.AtomicReference<
            GremlinPlanCache.StampedTranslation>();
        probe.duringWalk = () -> {
          probe.cache.putTranslationInternal(probe.key, winner, probe.session,
              probe.cache.getGeneration());
          envelope.set(probe.resident());
        };
        var traversal = kind == 1
            ? graph.traversal().V().hasLabel("CandidatePerson").out("candidateKnows").asAdmin()
            : query(30, 40);
        var admission = kind == 2 ? new PrivateCandidatePreparation.Policy(Set.of()) : policy;
        try (var own = probe.prepare().prepare(traversal, admission)) {
          assertThat(own.reason()).isEqualTo(kind == 0 ? PrivatePrefixDiscovery.Reason.SELECTED
              : kind == 1 ? PrivatePrefixDiscovery.Reason.FULL_SUPPORTED
                  : PrivatePrefixDiscovery.Reason.FOLD_CUT);
          assertThat(own.cacheHit()).isFalse();
          assertThat(probe.resident()).isSameAs(envelope.get());
          assertThat(probe.resident().template()).isSameAs(winner);
          if (kind != 2) {
            assertThat(own.plan()).isNotSameAs(probe.originals.getFirst())
                .isNotSameAs(probe.copies.getFirst());
            verify(probe.originals.getFirst()).close();
            verify(probe.copies.getFirst()).close();
            verify(own.plan(), never()).close();
          }
        }
        // One competitor publication and exactly one own publication, with no second repair.
        verify(probe.cache, times(2)).putTranslationInternal(anyString(), any(), any(),
            org.mockito.ArgumentMatchers.anyLong(), any());
        assertThat(probe.cache.getTranslationHits()).isEqualTo(3);
      }
    }
  }

  /** A competing valid repair or equal-content newer envelope survives a delayed prepare repair. */
  @Test
  public void rejectedResidentCannotReplaceEqualNewerCandidateEnvelope() {
    for (boolean equalWinner : List.of(false, true)) {
      try (var probe = new PublicationProbe()) {
        var prepare = probe.prepare();
        try (var seed = prepare.prepare(query(30, 40), policy)) {
          assertThat(seed.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
        }
        var valid = (GremlinTranslationTemplate.Candidate) probe.resident().template();
        var malformed = new GremlinTranslationTemplate.Candidate(99, valid.reason(),
            valid.translation());
        probe.cache.putTranslationInternal(probe.key, malformed, probe.session,
            probe.cache.getGeneration(), probe.resident());
        var rejected = probe.resident();
        var winner = new java.util.concurrent.atomic.AtomicReference<
            GremlinPlanCache.StampedTranslation>();
        probe.duringWalk = () -> {
          probe.cache.putTranslationInternal(probe.key,
              new GremlinTranslationTemplate.Candidate(equalWinner ? 99 : valid.extent(),
                  valid.reason(), valid.translation()),
              probe.session, probe.cache.getGeneration(), rejected);
          winner.set(probe.resident());
          assertThat(winner.get()).isNotSameAs(rejected);
          if (equalWinner) {
            assertThat(winner.get()).isEqualTo(rejected);
          }
        };
        long hits = probe.cache.getTranslationHits();
        int closes = org.mockito.Mockito.mockingDetails(valid.translation().planTemplate())
            .getInvocations().stream().filter(i -> i.getMethod().getName().equals("close"))
            .toList().size();
        try (var own = prepare.prepare(query(40, 30), policy)) {
          assertThat(own.cacheHit()).isFalse();
          assertThat(own.bindings()).containsEntry(0, 40);
          assertThat(own.extent()).isEqualTo(3);
          assertThat(probe.cache.getTranslationHits()).isEqualTo(hits + 2);
          assertThat(probe.resident()).isSameAs(winner.get());
          verify(valid.translation().planTemplate(), times(closes)).close();
        }
        // Seed, explicit rejection, competing repair and the losing repair. No second repair.
        verify(probe.cache, times(4)).putTranslationInternal(anyString(), any(), any(),
            org.mockito.ArgumentMatchers.anyLong(), any());
      }
    }
  }

  /** A warm copy invalidated while copying is closed before cold preparation takes over. */
  @Test
  public void invalidatedWarmCopyClosesWithoutClosingSharedTemplateAgain() {
    try (var probe = new PublicationProbe()) {
      var prepare = probe.prepare();
      try (var seed = prepare.prepare(query(30, 40), policy)) {
        assertThat(seed.cacheHit()).isFalse();
      }
      var candidate = (GremlinTranslationTemplate.Candidate) probe.resident().template();
      var template = candidate.translation().planTemplate();
      var rejectedCopy = new java.util.concurrent.atomic.AtomicReference<InternalExecutionPlan>();
      doAnswer(call -> {
        var copy = spy((InternalExecutionPlan) call.callRealMethod());
        rejectedCopy.set(copy);
        probe.cache.invalidate();
        return copy;
      }).when(template).copy(any(CommandContext.class));
      long hits = probe.cache.getTranslationHits();
      try (var own = prepare.prepare(query(40, 30), policy)) {
        assertThat(own.cacheHit()).isFalse();
        assertThat(own.bindings()).containsEntry(0, 40);
        assertThat(probe.cache.getTranslationHits()).isEqualTo(hits + 1);
        verify(rejectedCopy.get()).close();
        verify(template).close();
        assertThat(probe.resident()).isNull();
      }
    }
  }

  /** Schema-local preparations neither look up nor publish, even with an existing valid entry. */
  @Test
  public void schemaTransactionBypassesReuseAndPublication() {
    try (var probe = new PublicationProbe()) {
      var prepare = probe.prepare();
      try (var seed = prepare.prepare(query(30, 40), policy)) {
        assertThat(seed.cacheHit()).isFalse();
      }
      var resident = probe.resident();
      long hits = probe.cache.getTranslationHits();
      long misses = probe.cache.getTranslationMisses();
      assertThat(graph.tx().isOpen()).isTrue();
      probe.session.ensureTxSchemaState();
      try {
        try (var own = prepare.prepare(query(40, 30), policy)) {
          assertThat(own.cacheHit()).isFalse();
          assertThat(own.plan()).isNotNull();
        }
        assertThat(probe.cache.getTranslationHits()).isEqualTo(hits);
        assertThat(probe.cache.getTranslationMisses()).isEqualTo(misses);
        verify(probe.cache, times(1)).putTranslationInternal(anyString(), any(), any(),
            org.mockito.ArgumentMatchers.anyLong(), any());
      } finally {
        graph.tx().rollback();
      }
      var freshSession = YTDBStrategyUtil.resolveYtdbSession(query(30, 40));
      assertThat(probe.cache.getTranslationEntry(probe.key, freshSession)).isSameAs(resident);
    }
  }

  /** A non-cacheable partial plan stays private, closes its original and returns a usable copy. */
  @Test
  public void nonCacheablePartialPlanNeverPublishes() {
    try (var probe = new PublicationProbe()) {
      probe.cacheable = false;
      for (int invocation = 0; invocation < 2; invocation++) {
        try (var own = probe.prepare().prepare(query(30, 40), policy)) {
          assertThat(own.cacheHit()).isFalse();
          assertThat(own.plan().getContext().getInputParameters()).containsEntry(0, 30);
          assertThat(probe.resident()).isNull();
          verify(probe.originals.getLast()).close();
          verify(own.plan(), never()).close();
        }
        verify(probe.copies.getLast()).close();
      }
      verify(probe.cache, never()).putTranslationInternal(anyString(), any(), any(),
          org.mockito.ArgumentMatchers.anyLong(), any());
    }
  }

  /** Closed, unbound templates survive replacement without affecting independently usable copies. */
  @Test
  public void publishedTemplateAndReturnedCopiesHaveIndependentOwnership() {
    try (var probe = new PublicationProbe()) {
      var prepare = probe.prepare();
      try (var cold = prepare.prepare(query(30, 40), policy)) {
        var resident = probe.resident();
        var candidate = (GremlinTranslationTemplate.Candidate) resident.template();
        var template = candidate.translation().planTemplate();
        assertThat(template).isNotSameAs(cold.plan()).isNotSameAs(probe.originals.getFirst());
        assertThat(template.getContext().getInputParameters()).isNullOrEmpty();
        verify(template).close();
        verify(probe.originals.getFirst()).close();
        long hits = probe.cache.getTranslationHits();
        try (var warm = prepare.prepare(query(40, 30), policy)) {
          assertThat(warm.cacheHit()).isTrue();
          assertThat(probe.cache.getTranslationHits()).isEqualTo(hits + 1);
          assertThat(warm.plan()).isNotSameAs(template).isNotSameAs(cold.plan());
          probe.cache.putTranslationInternal(probe.key,
              new GremlinTranslationTemplate.Candidate(-1,
                  PrivatePrefixDiscovery.Reason.COMPLETE_NATIVE, null),
              probe.session, probe.cache.getGeneration(), resident);
          probe.cache.invalidate();
          // Shared templates are closed before publication, not by eviction or replacement.
          verify(template).close();
          assertThat(cold.plan().getContext().getInputParameters()).containsEntry(0, 30);
          assertThat(warm.plan().getContext().getInputParameters()).containsEntry(0, 40);
          assertThat(cold.plan().prettyPrint(0, 2).toString()).isNotEmpty();
          assertThat(warm.plan().prettyPrint(0, 2).toString()).isNotEmpty();
          verify(cold.plan(), never()).close();
        }
      }
      verify(probe.copies.getLast()).close();
    }
  }

  /** Copy failures propagate, while the original private plan is still closed by its owner. */
  @Test
  public void returnedCopyFailureStillClosesOriginal() {
    try (var probe = new PublicationProbe()) {
      var prepare = new PrivateCandidatePreparation(new PrivatePrefixDiscovery(
          GremlinStepWalker.production()::walkObserved, (db, translation, scope) -> {
            var original = spy(GremlinToMatchStrategy.buildPlan(db, translation, scope));
            probe.originals.add(original);
            doReturn(false).when(original).canBeCached();
            doAnswer(call -> {
              throw new IllegalStateException("copy failure");
            })
                .when(original).copy(any(CommandContext.class));
            return original;
          }));
      assertThatThrownBy(() -> prepare.prepare(query(30, 40), policy))
          .isInstanceOf(IllegalStateException.class).hasMessage("copy failure");
      verify(probe.originals.getFirst()).close();
      assertThat(probe.resident()).isNull();
    }
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
