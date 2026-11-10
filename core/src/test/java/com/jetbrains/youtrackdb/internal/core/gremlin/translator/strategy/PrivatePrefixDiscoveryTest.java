package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.RequirementsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.RepeatUnrollStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.junit.Before;
import org.junit.Test;

/** Private preparation tests assert cuts and work independently of result fallback. */
public class PrivatePrefixDiscoveryTest extends GraphBaseTest {

  private final List<Integer> recognitions = new ArrayList<>();
  private final AtomicInteger builds = new AtomicInteger();
  private InternalExecutionPlan plan;

  @Before
  public void enableTranslator() {
    var session = YTDBStrategyUtil.resolveYtdbSession(graph.traversal().V().asAdmin());
    session.getConfiguration().setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    plan = mock(InternalExecutionPlan.class);
    when(plan.getSteps()).thenReturn(List.of());
  }

  private PrivatePrefixDiscovery discovery() {
    return discovery(-1, false);
  }

  private PrivatePrefixDiscovery discovery(int declinedSize, boolean failBuild) {
    return new PrivatePrefixDiscovery((traversal, inputs, observer) -> {
      if (observer == null) {
        recognitions.add(traversal.getSteps().size());
      }
      var result = GremlinStepWalker.production().walkObserved(traversal, inputs, observer);
      return observer == null && traversal.getSteps().size() == declinedSize ? null : result;
    }, (session, translation, scope) -> {
      builds.incrementAndGet();
      assertThat(scope.eligible).isFalse();
      if (failBuild) {
        throw new IllegalStateException("build fixture");
      }
      return plan;
    });
  }

  private PrivatePrefixDiscovery.Outcome run(PrivatePrefixDiscovery discovery,
      Traversal.Admin<?, ?> traversal, IntPredicate admission) {
    var safety = QuerySafetyAdmission.prepare(traversal);
    assertThat(safety.reason()).isEqualTo(QuerySafetyAdmission.Reason.SAFE);
    var evidence = QuerySafetyAdmission.stored(traversal);
    var result = discovery.discover(traversal, safety, admission);
    // Prefix recognition never replaces or prepares the root's safety evidence.
    assertThat(QuerySafetyAdmission.stored(traversal)).isSameAs(evidence);
    return result;
  }

  /** Latest completed unit wins, owns one private plan, and leaves original steps unchanged. */
  @Test
  public void longestCutUsesOneFreshRecognitionAndOneBuild() {
    var traversal = graph.traversal().V().out("knows").values("name")
        .optional(__.identity()).asAdmin();
    var steps = List.copyOf(traversal.getSteps());
    try (var outcome = run(discovery(), traversal, cut -> true)) {
      assertThat(outcome.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
      assertThat(outcome.cut()).isEqualTo(3);
      assertThat(recognitions).containsExactly(3);
      assertThat(builds.get()).isEqualTo(1);
      assertThat(outcome.plan()).isSameAs(plan);
      assertThat(traversal.getSteps()).containsExactlyElementsOf(steps);
    }
    verify(plan).close();
  }

  /** A failed longer candidate cannot contaminate the shorter candidate or a later invocation. */
  @Test
  public void failedCandidateAndNextInvocationHaveFreshState() {
    var discovery = discovery(3, false);
    for (int invocation = 0; invocation < 2; invocation++) {
      var traversal = graph.traversal().V().out("knows").values("name")
          .optional(__.identity()).asAdmin();
      try (var outcome = run(discovery, traversal, cut -> true)) {
        assertThat(outcome.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
        assertThat(outcome.cut()).isEqualTo(2);
        assertThat(outcome.translation().boundaryAlias()).isEqualTo("$g2m_anon_0");
      }
    }
    assertThat(recognitions).containsExactly(3, 2, 3, 2);
    assertThat(builds.get()).isEqualTo(2);
  }

  /** Multi-step edge units and transparent barriers offer only completed root positions. */
  @Test
  public void multiStepAndTransparentUnitsKeepLiteralCuts() {
    var shapes = List.of(
        graph.traversal().V().outE("knows").inV().optional(__.out()),
        graph.traversal().V().barrier().out("knows").optional(__.out()),
        graph.traversal().V().order().by("name").out("knows").limit(1)
            .values("name").optional(__.identity()));
    var cuts = List.of(3, 3, 5);
    for (int i = 0; i < shapes.size(); i++) {
      try (var outcome = run(discovery(), shapes.get(i).asAdmin(), cut -> true)) {
        assertThat(outcome.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.SELECTED);
        assertThat(outcome.cut()).isEqualTo(cuts.get(i));
      }
    }
    assertThat(recognitions).containsExactly(3, 3, 5);
    assertThat(builds.get()).isEqualTo(3);
  }

  /** A missing recogniser does not flush a deferred hop. A recognised successor can settle it. */
  @Test
  public void deferredHopLimitIsOverReportedCompletions() {
    var shapes = List.of(
        graph.traversal().V().order().by("name").out("knows").optional(__.out()),
        graph.traversal().V().order().by("name").out("knows").values("name")
            .optional(__.identity()));
    var cuts = List.of(2, 4);
    for (int i = 0; i < shapes.size(); i++) {
      try (var outcome = run(discovery(), shapes.get(i).asAdmin(), cut -> true)) {
        assertThat(outcome.cut()).isEqualTo(cuts.get(i));
      }
    }
    assertThat(recognitions).containsExactly(2, 4);
    assertThat(builds.get()).isEqualTo(2);
  }

  /** Every filter in an open native source run folds, but a transparent barrier closes it. */
  @Test
  public void foldCutsAgreeWithNativePreparation() {
    var traversal = graph.traversal().V().hasLabel("Person").barrier()
        .has("name", "marko").asAdmin();
    traversal.addStep(2, __.has("age", 29).asAdmin().getStartStep().clone());
    var rules = new PartialCutRules(traversal.getSteps());
    assertThat(rules.splitsFold(1)).isTrue();
    assertThat(rules.splitsFold(2)).isTrue();
    assertThat(rules.splitsFold(3)).isFalse();
    assertThat(rules.splitsFold(4)).isFalse();
    YTDBGraphStepStrategy.instance().apply(traversal);
    var source = (YTDBGraphStep<?, ?>) traversal.getStartStep();
    assertThat(source.getHasContainers()).hasSize(2);
    assertThat(traversal.getSteps()).hasSize(3);
    var laterSource = graph.traversal().V().barrier().V().has("name", "marko").asAdmin();
    assertThat(new PartialCutRules(laterSource.getSteps()).splitsFold(3)).isTrue();
  }

  /** Consecutive suffix orders both read labels before the cut, not only the preceding sort. */
  @Test
  public void orderCollationDependenciesCrossConsecutiveSorts() {
    var traversal = graph.traversal().V().hasLabel("Person").order().by("age")
        .order().by("name").optional(__.out()).asAdmin();
    var rules = new PartialCutRules(traversal.getSteps());
    assertThat(rules.splitsOrder(1)).isTrue();
    assertThat(rules.splitsOrder(2)).isTrue();
    assertThat(rules.splitsOrder(3)).isTrue();
    assertThat(rules.splitsOrder(4)).isFalse();
    try (var outcome = run(discovery(), traversal, cut -> cut == 3)) {
      assertThat(outcome.plan()).isNull();
    }
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** Root union candidates cost no fresh recognition or build when earlier cuts lack admission. */
  @Test
  public void rootUnionIsRefusedStructurally() {
    var traversal = graph.traversal().V().union(__.out("knows"), __.out("created"))
        .optional(__.out()).asAdmin();
    try (var outcome = run(discovery(), traversal, cut -> cut >= 2)) {
      assertThat(outcome.plan()).isNull();
    }
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** RID-bearing accepted prefixes terminate without a plan, even if an earlier prefix exists. */
  @Test
  public void identifiersNeverReachPlanBuilding() {
    var traversal = graph.traversal().V("#12:0").out("knows").optional(__.out()).asAdmin();
    try (var outcome = run(discovery(), traversal, cut -> true)) {
      assertThat(outcome.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.RECORD_IDENTIFIERS);
    }
    assertThat(recognitions).containsExactly(2);
    assertThat(builds.get()).isZero();
  }

  /** A selected build failure is terminal rather than permission to build a shorter plan. */
  @Test
  public void planFailureDoesNotResumeSearch() {
    var traversal = graph.traversal().V().out("knows").optional(__.out()).asAdmin();
    try (var outcome = run(discovery(-1, true), traversal, cut -> true)) {
      assertThat(outcome.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.PLAN_BUILD_FAILURE);
    }
    assertThat(recognitions).containsExactly(2);
    assertThat(builds.get()).isEqualTo(1);
  }

  /** A real plain plan is closed, remains native, and does not reopen candidate selection. */
  @Test
  public void plainSelectedPlanIsTerminal() {
    var discovery = new PrivatePrefixDiscovery((traversal, inputs, observer) -> {
      if (observer == null) {
        recognitions.add(traversal.getSteps().size());
      }
      return GremlinStepWalker.production().walkObserved(traversal, inputs, observer);
    }, (session, translation, scope) -> {
      builds.incrementAndGet();
      return GremlinToMatchStrategy.buildPlan(session, translation, scope);
    });
    try (var outcome = run(discovery,
        graph.traversal().V().optional(__.out()).asAdmin(), cut -> true)) {
      assertThat(outcome.reason()).isEqualTo(PrivatePrefixDiscovery.Reason.PLAIN);
    }
    assertThat(recognitions).containsExactly(1);
    assertThat(builds.get()).isEqualTo(1);
  }

  /** Missing evidence, safety veto, full support and missing cut admission have distinct reasons. */
  @Test
  public void admissionOutcomesAreDistinctAndArgumentOnly() {
    var discovery = discovery();
    var traversal = graph.traversal().V().optional(__.out()).asAdmin();
    assertThat(discovery.discover(traversal, null, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.MISSING_SAFETY);
    var unsafe = graph.traversal().V().as("$native").drop().asAdmin();
    assertThat(
        discovery.discover(unsafe, QuerySafetyAdmission.prepare(unsafe), cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.SAFETY_VETO);
    assertThat(run(discovery, graph.traversal().V().asAdmin(), cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.FULL_SUPPORTED);
    assertThat(run(discovery, traversal, null).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.CUT_ADMISSION);
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** Repeat evidence survives unrolling without suppressing a repeat-free child's own full walk. */
  @Test
  public void repeatMarkerAndRecursiveScanBothRefusePartialOnly() {
    var traversal = graph.traversal().V().repeat(__.out("knows")).times(2)
        .optional(__.out()).asAdmin();
    assertThat(PartialSourceState.repeat(traversal)).isTrue();
    QuerySafetyAdmission.prepare(traversal);
    RepeatDeclineStrategy.instance().apply(traversal);
    RepeatUnrollStrategy.instance().apply(traversal);
    assertThat(PartialSourceState.repeat(traversal)).isTrue();
    assertThat(run(discovery(), traversal, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.REPEAT);
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().out("knows").asAdmin()))
        .isNotNull();
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** Structural cuts are rejected before recognition, with a reason that does not contain values. */
  @Test
  public void structuralReasonsRequireNoCandidateWork() {
    var shapes = List.of(
        graph.traversal().V().has("name", "marko"),
        graph.traversal().V().hasLabel("Person").order().by("age").order().by("name"),
        graph.traversal().V().union(__.out("knows"), __.out("created")));
    var cuts = List.of(1, 3, 2);
    var reasons = List.of(PrivatePrefixDiscovery.Reason.FOLD_CUT,
        PrivatePrefixDiscovery.Reason.ORDER_CUT, PrivatePrefixDiscovery.Reason.MULTI_PLAN);
    for (int i = 0; i < shapes.size(); i++) {
      int completion = cuts.get(i);
      // The seam supplies only the declined attempt's history. A candidate call is a test failure.
      var discovery = new PrivatePrefixDiscovery((traversal, inputs, observer) -> {
        assertThat(observer).isNotNull();
        observer.accept(completion);
        return null;
      }, (session, translation, scope) -> {
        throw new AssertionError("structural refusal built a plan");
      });
      var traversal = shapes.get(i).optional(__.identity()).asAdmin();
      assertThat(run(discovery, traversal, cut -> true).reason()).isEqualTo(reasons.get(i));
    }
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** Disabled and non-root private calls refuse without candidates or a plan. */
  @Test
  public void completeNativeAndDisabledRemainDistinctFromCapabilityDecline() {
    var child = __.out("knows").asAdmin();
    var parent = graph.traversal().V().optional(child).asAdmin();
    var safety = QuerySafetyAdmission.prepare(parent);
    assertThat(discovery().discover(child, safety, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.COMPLETE_NATIVE);
    var traversal = graph.traversal().V().optional(__.out()).asAdmin();
    safety = QuerySafetyAdmission.prepare(traversal);
    var configuration = YTDBStrategyUtil.resolveYtdbSession(traversal).getConfiguration();
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
    assertThat(discovery().discover(traversal, safety, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.DISABLED);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    assertThat(new PrivatePrefixDiscovery().discover(traversal, safety, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.PLAIN);
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** The shared safety scan runs once on the root and never on any copied prefix. */
  @Test
  public void discoveryDoesNotRepeatSafetyPreparation() {
    var traversal = org.mockito.Mockito.spy(
        graph.traversal().V().out("knows").optional(__.out()).asAdmin());
    var scans = new AtomicInteger();
    org.mockito.Mockito.doAnswer(invocation -> {
      if (java.util.Arrays.stream(Thread.currentThread().getStackTrace())
          .anyMatch(frame -> frame.getClassName().endsWith("QuerySafetyAdmission$Scan")
              && frame.getMethodName().equals("visit"))) {
        scans.incrementAndGet();
      }
      return invocation.callRealMethod();
    }).when(traversal).getSteps();
    try (var outcome = run(discovery(), traversal, cut -> true)) {
      assertThat(outcome.cut()).isEqualTo(2);
    }
    assertThat(scans.get()).isEqualTo(1);
    assertThat(recognitions).containsExactly(2);
    assertThat(builds.get()).isEqualTo(1);
  }

  /** Source suppliers run only during native execution with identical translator-on/off results. */
  @Test
  public void sourceStateKeepsProductionNativeWithoutExtraSupplierCalls() {
    var session = YTDBStrategyUtil.resolveYtdbSession(graph.traversal().V().asAdmin());
    var configuration = session.getConfiguration();
    ModernGraphFixture.seed(graph, session);
    var calls = new AtomicInteger();
    List<?> expected = null;
    int expectedCalls = -1;
    for (boolean enabled : List.of(false, true)) {
      configuration.setValue(
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
      calls.set(0);
      var traversal = graph.traversal().withSideEffect("state",
          (java.util.function.Supplier<Integer>) calls::incrementAndGet)
          .V().hasLabel("Person").optional(__.out("knows")).values("name").asAdmin();
      var results = traversal.toList().stream().map(Object::toString).sorted().toList();
      assertThat(TranslatorEquivalenceSupport.countBoundarySteps(traversal)).isZero();
      assertThat(results).isNotEmpty();
      if (expected == null) {
        expected = results;
        expectedCalls = calls.get();
      } else {
        assertThat(results).isEqualTo(expected);
        assertThat(calls.get()).isEqualTo(expectedCalls);
      }
    }
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** Attached named state and operator-only sack state are partial refusals without evaluation. */
  @Test
  public void attachedStateAndSackDeclarationsAreDetected() {
    var calls = new AtomicInteger();
    var attached = graph.traversal().V().optional(__.out()).asAdmin();
    attached.getSideEffects().register("state", calls::incrementAndGet, null);
    assertThat(run(discovery(), attached, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.SOURCE_STATE);
    var operatorOnly = graph.traversal().V().optional(__.out()).asAdmin();
    operatorOnly.getSideEffects().setSack(null, value -> {
      calls.incrementAndGet();
      return value;
    }, null);
    assertThat(PartialSourceState.configured(operatorOnly)).isTrue();
    var sack = graph.traversal().withSack(
        (java.util.function.Supplier<Integer>) calls::incrementAndGet,
        (java.util.function.UnaryOperator<Integer>) value -> {
          calls.incrementAndGet();
          return value;
        }, (java.util.function.BinaryOperator<Integer>) (left, right) -> {
          calls.incrementAndGet();
          return left;
        }).V().optional(__.out()).asAdmin();
    assertThat(run(discovery(), sack, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.SOURCE_STATE);
    assertThat(calls.get()).isZero();
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }

  /** Declarations and attached sacks, operators and requirements are inspected, never evaluated. */
  @Test
  public void sourceStateInspectionDoesNotCallUserFunctions() {
    var calls = new AtomicInteger();
    java.util.function.Supplier<Integer> supplier = () -> calls.incrementAndGet();
    var shapes = List.of(
        graph.traversal().withSideEffect("state", supplier).V().optional(__.out()),
        graph.traversal().withSack(supplier).V().optional(__.out()),
        graph.traversal().withBulk(false).V().optional(__.out()),
        graph.traversal().withPath().V().optional(__.out()));
    for (var shape : shapes) {
      assertThat(run(discovery(), shape.asAdmin(), cut -> true).reason())
          .isEqualTo(PrivatePrefixDiscovery.Reason.SOURCE_STATE);
    }
    var attached = graph.traversal().V().optional(__.out()).asAdmin();
    attached.getSideEffects().setSack(supplier, x -> {
      calls.incrementAndGet();
      return x;
    }, (a, b) -> {
      calls.incrementAndGet();
      return a;
    });
    assertThat(run(discovery(), attached, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.SOURCE_STATE);
    var requirement = graph.traversal().V().optional(__.out()).asAdmin();
    RequirementsStrategy.addRequirements(requirement.getStrategies(), TraverserRequirement.PATH);
    assertThat(run(discovery(), requirement, cut -> true).reason())
        .isEqualTo(PrivatePrefixDiscovery.Reason.SOURCE_STATE);
    assertThat(calls.get()).isZero();
    assertThat(recognitions).isEmpty();
    assertThat(builds.get()).isZero();
  }
}
