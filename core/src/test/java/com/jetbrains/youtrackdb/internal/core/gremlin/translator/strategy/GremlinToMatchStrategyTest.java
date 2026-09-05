package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Unit tests for {@link GremlinToMatchStrategy}, the skeleton of the Gremlin-to-MATCH
 * provider-optimization strategy.
 *
 * <p>The production facade recognizes the vertex source ({@code g.V()} / {@code g.V(ids)}) and
 * declines every other shape, so the tests fall into two groups:
 *
 * <ul>
 *   <li><b>Production-facade tests</b> run {@code GremlinToMatchStrategy.instance().apply(...)}
 *       against a real {@link GraphBaseTest} graph and assert the outcome: a recognized {@code
 *       g.V()} is replaced by a single boundary step, and an unrecognized shape is left untouched
 *       (the whole-traversal decline).
 *   <li><b>Fixture-injection tests</b> construct a strategy with a fixture translator (and,
 *       where the splice path is exercised, a fixture plan builder returning a stub plan) so
 *       the post-gate behaviors — kill-switch gating, the throw-safety net, and the
 *       replace-all-steps splice — can be driven deterministically without a real walker or a
 *       real {@code MatchExecutionPlanner}.
 * </ul>
 *
 * <p>Every fixture test uses a real graph so {@code apply}'s session-resolution and per-session
 * kill-switch read exercise the production path; only the translation and plan-building seams
 * are stubbed.
 */
// Test-scoped IDE-inspection noise, suppressed class-wide the way the rest of the core test suite
// does: unchecked (generic mocks / raw assertj isInstanceOf) and resource (detached traversals and
// the session handle that the test never iterates or closes). DataFlowIssue is NOT class-wide: it is
// narrowed to the methods that dereference the @Nullable getConfiguration(), so a genuine
// null-dereference in a future test added to this class is not silenced.
@SuppressWarnings({"unchecked", "resource"})
@Category(SequentialTest.class)
public class GremlinToMatchStrategyTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::session);

  /**
   * A translation the fixture translator hands back to drive the splice path. The concrete
   * inputs never reach a real planner in these tests (the plan builder is stubbed), so a bare
   * single-alias {@link MatchPlanInputs} over an empty {@link Pattern} is sufficient.
   */
  private static GremlinToMatchTranslator.TranslationResult fixtureTranslation() {
    var inputs = MatchPlanInputs.builder(new Pattern()).build();
    return GremlinToMatchTranslator.TranslationResult.singlePlan(
        inputs, "v", BoundaryOutputType.ELEMENT, Vertex.class, Map.of(), true);
  }

  private static GremlinToMatchTranslator.TranslationResult fixtureMultiPlanTranslation(
      List<MatchPlanInputs> childInputs, List<Map<Object, Object>> childParameters) {
    var childCacheEligible = childInputs.stream().map(ignored -> Boolean.TRUE).toList();
    return fixtureMultiPlanTranslation(childInputs, childParameters, childCacheEligible);
  }

  private static GremlinToMatchTranslator.TranslationResult fixtureMultiPlanTranslation(
      List<MatchPlanInputs> childInputs,
      List<Map<Object, Object>> childParameters,
      List<Boolean> childCacheEligible) {
    return GremlinToMatchTranslator.TranslationResult.multiPlan(
        childPlans(childInputs, childParameters, childCacheEligible),
        List.of(),
        "v",
        BoundaryOutputType.ELEMENT,
        Vertex.class,
        ResultShaping.NONE);
  }

  /** Zips the three per-child facts into the carrier's {@code ChildPlan} list. */
  private static List<GremlinToMatchTranslator.TranslationResult.ChildPlan> childPlans(
      List<MatchPlanInputs> childInputs,
      List<Map<Object, Object>> childParameters,
      List<Boolean> childCacheEligible) {
    var plans =
        new ArrayList<GremlinToMatchTranslator.TranslationResult.ChildPlan>(childInputs.size());
    for (int i = 0; i < childInputs.size(); i++) {
      plans.add(
          new GremlinToMatchTranslator.TranslationResult.ChildPlan(
              childInputs.get(i), childParameters.get(i), childCacheEligible.get(i)));
    }
    return plans;
  }

  /** Reads/writes the kill-switch on the graph's live session. */
  private DatabaseSessionEmbedded session() {
    var tx = (YTDBTransaction) graph.tx();
    // Activate the transaction before reaching for its session: getDatabaseSession() throws
    // "Transaction is not active" until readWrite() has opened it. This mirrors what the
    // strategy's own session-resolution does.
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private void setKillSwitch(boolean enabled) {
    support.setTranslatorEnabled(enabled);
  }

  /**
   * Runs {@code action} with a capturing handler attached to the strategy's DEBUG logger and
   * returns every {@link LogRecord} it emitted. {@code declineOnThrow} records the swallowed cause
   * at DEBUG only — an operator's sole "why is nothing translating?" signal — and the SLF4J facade
   * gates that call behind {@code isDebugEnabled()}, which is false at JUL's default INFO level. So
   * this helper raises the strategy logger to {@code FINE} (SLF4J DEBUG maps to JUL FINE under the
   * slf4j-jdk14 binding on the test classpath) and attaches a collector, restoring the level and
   * handler in a finally so no global logging state leaks to sibling tests in the fork.
   */
  private static List<LogRecord> captureStrategyDebugLogs(Runnable action) {
    var julLogger = Logger.getLogger(GremlinToMatchStrategy.class.getName());
    List<LogRecord> records = new CopyOnWriteArrayList<>();
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {
          }

          @Override
          public void close() {
          }
        };
    handler.setLevel(Level.ALL);
    var savedLevel = julLogger.getLevel();
    var savedUseParent = julLogger.getUseParentHandlers();
    julLogger.addHandler(handler);
    julLogger.setLevel(Level.FINE);
    // Keep our FINE records off the root handlers, which sit at INFO and would drop them anyway.
    julLogger.setUseParentHandlers(false);
    try {
      action.run();
    } finally {
      julLogger.removeHandler(handler);
      julLogger.setLevel(savedLevel);
      julLogger.setUseParentHandlers(savedUseParent);
    }
    return records;
  }

  // ---------------------------------------------------------------------------
  // Ordering — the translator declares empty ordering; the half-measure strategies
  // name it in THEIR applyPrior(), so this strategy must not name them in its sets.
  // ---------------------------------------------------------------------------

  /** applyPrior() is empty (ordering is expressed by the half-measure strategies). */
  @Test
  public void applyPrior_isEmpty() {
    assertThat(GremlinToMatchStrategy.instance().applyPrior()).isEmpty();
  }

  /** applyPost() is empty (the strategy declares no downstream ordering constraint). */
  @Test
  public void applyPost_isEmpty() {
    assertThat(GremlinToMatchStrategy.instance().applyPost()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Idempotency: a traversal already carrying a boundary step is left alone.
  // ---------------------------------------------------------------------------

  /**
   * A traversal that already contains a {@link YTDBMatchPlanStep} (as a re-applied strategy
   * chain would produce) is a no-op: the idempotency scan finds the boundary and returns
   * before consulting the translator, so the step list is unchanged and no new plan is built.
   * The scan covers the whole list — here the boundary is preceded by an ordinary step, which
   * a start-step-only scan would miss.
   */
  @Test
  public void apply_traversalAlreadyContainsBoundary_isNoOp() {
    var admin = graph.traversal().V().asAdmin();
    // Splice a boundary step (backed by a stub plan) into the middle of the list, mimicking a
    // previously-translated traversal wrapped by an extra source step.
    @SuppressWarnings({"unchecked", "rawtypes"})
    var boundary =
        new YTDBMatchPlanStep(
            admin, Vertex.class, mock(InternalExecutionPlan.class), "v",
            BoundaryOutputType.ELEMENT);
    admin.addStep(boundary);
    var stepsBefore = List.copyOf(admin.getSteps());

    // A translator that would translate if consulted — proves the idempotency gate short-
    // circuits before the translator runs.
    var translated = new int[1];
    GremlinToMatchStrategy.TraversalTranslator countingTranslator =
        t -> {
          translated[0]++;
          return fixtureTranslation();
        };
    var strategy = new GremlinToMatchStrategy(countingTranslator);

    strategy.apply(admin);

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(translated[0]).as("translator must not be consulted once a boundary exists")
        .isZero();
  }

  /**
   * The productive-order setting is resolved ONCE per compilation, and the same resolved value
   * reaches the shape key and the walk.
   *
   * <p>Two independent reads can straddle a runtime flip. The key would then describe one setting
   * while the plan filed under it was built for the other, in a cache that is storage-wide and
   * outlives the session. The fixture below flips the setting from inside the translator, which is
   * the exact window between the two former reads, and asserts the translator was handed the value
   * that was live before the flip.
   *
   * <p>The single-argument overload throws, so a return to the two-read shape fails here rather
   * than passing quietly.
   */
  @Test
  public void apply_resolvesTheProductiveOrderSettingOncePerCompilation() {
    var config = session().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, true);
    try {
      var handedToTranslator = new java.util.concurrent.atomic.AtomicReference<Boolean>();
      var flippingTranslator = new GremlinToMatchStrategy.TraversalTranslator() {
        @Override
        public GremlinToMatchTranslator.TranslationResult translate(Traversal.Admin<?, ?> t) {
          throw new AssertionError(
              "the strategy must hand the resolved setting to the translator");
        }

        @Override
        public GremlinToMatchTranslator.TranslationResult translate(
            Traversal.Admin<?, ?> t, Boolean orderIncludesMissingKey) {
          handedToTranslator.set(orderIncludesMissingKey);
          // The runtime flip lands between the former two reads.
          config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, false);
          return null;
        }
      };

      new GremlinToMatchStrategy(flippingTranslator)
          .apply(graph.traversal().V().order().by("age").asAdmin());

      assertThat(handedToTranslator.get())
          .as("the walk reads the value the shape key was built from, not a later one")
          .isTrue();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
  }

  // ---------------------------------------------------------------------------
  // Kill-switch (runtime opt-out) — off means decline even for a shape that would
  // otherwise translate.
  // ---------------------------------------------------------------------------

  /**
   * With the kill-switch off, a traversal whose fixture translator WOULD translate is declined:
   * the step list is left verbatim and the translator is never consulted (the session-enabled
   * gate returns before it). This isolates the kill-switch from the facade's own decline.
   */
  @Test
  public void apply_killSwitchOff_declinesEvenWhenTranslationAvailable() {
    setKillSwitch(false);
    try {
      var admin = graph.traversal().V().asAdmin();
      var stepsBefore = List.copyOf(admin.getSteps());

      var consulted = new int[1];
      var neverBuilt = new int[1];
      var strategy =
          new GremlinToMatchStrategy(
              t -> {
                consulted[0]++;
                return fixtureTranslation();
              },
              (s, tr, planningStart) -> {
                neverBuilt[0]++;
                return mock(InternalExecutionPlan.class);
              });

      strategy.apply(admin);

      assertThat(admin.getSteps()).isEqualTo(stepsBefore);
      assertThat(consulted[0]).as("translator consulted despite kill-switch off").isZero();
      assertThat(neverBuilt[0]).as("plan built despite kill-switch off").isZero();
    } finally {
      setKillSwitch(true);
    }
  }

  // ---------------------------------------------------------------------------
  // Production translation — with the walker wired, the production strategy recognizes the
  // vertex source (g.V()) and splices in a single boundary step end to end.
  // ---------------------------------------------------------------------------

  /**
   * The production strategy (with the walker-backed facade) recognizes a bare {@code g.V()} and
   * replaces its entire step list with a single {@link YTDBMatchPlanStep} carrying a real
   * execution plan. This pins the end-to-end production path — gates on, walker recognizes,
   * planner builds, splice runs — that the earlier decline-only skeleton could not exercise.
   */
  @Test
  public void apply_productionVertexSource_translatesToSingleBoundary() {
    var admin = graph.traversal().V().asAdmin();

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(admin.getSteps()).hasSize(1);
    var only = admin.getSteps().getFirst();
    assertThat(only).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) only;
    assertThat(boundary.getPlan()).as("a real execution plan was built and installed").isNotNull();
    assertThat(boundary.getBoundaryAlias()).isEqualTo("$g2m_v0");
    assertThat(boundary.getOutputType()).isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * The production strategy propagates a reserved-{@code $} alias rejection end to end. A user {@code
   * as("$foo")} label reaches the walker-backed facade, whose reserved-prefix pre-flight throws a
   * {@link ReservedAliasException}; the throw-safety net re-throws it rather than degrading to a
   * decline, so the query fails with a clear error instead of silently running on native (which would
   * accept the {@code $} label). This pins the real wiring — gates on, production walker, net
   * re-throw — that the fixture-injection test {@link #apply_reservedAliasException_propagates}
   * exercises in isolation.
   */
  @Test
  public void apply_productionReservedAliasLabel_propagates() {
    var admin = graph.traversal().V().as("$foo").asAdmin();

    assertThatCode(() -> GremlinToMatchStrategy.instance().apply(admin))
        .as("a prohibited reserved-$ alias must surface through the production strategy")
        .isInstanceOf(ReservedAliasException.class)
        .hasMessageContaining("$foo");
  }

  /**
   * All-or-nothing at the strategy layer: a recognized prefix followed by an unrecognized step leaves
   * the traversal byte-for-byte unchanged, with no boundary step spliced. The fixture is {@code
   * g.V().out("knows").map(...)}: the {@code out("knows")} hop is recognizable, but the trailing
   * lambda map is not (a lambda is arbitrary user code with no MATCH equivalent — a permanently
   * out-of-scope fixture no later track starts translating). Under all-or-nothing one unrecognized
   * step declines the whole traversal, so {@code apply} must not splice a partial boundary for the
   * recognized prefix: the native step list — same step instances, same order — is preserved for the
   * native pipeline.
   */
  @Test
  public void apply_recognizedPrefixThenUnrecognizedStep_leavesNativeStepListVerbatim() {
    var admin = graph.traversal().V().out("knows").map(t -> t.get()).asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
  }

  // ---------------------------------------------------------------------------
  // Throw-safety net: an unchecked (RuntimeException) failure from a translator declines
  // cleanly (the exception never escapes apply() and the step list is left untouched), but an
  // Error or AssertionError propagates so a fatal JVM error or an -ea invariant violation
  // surfaces loudly instead of degrading to a silent decline. The one RuntimeException subtype that
  // also propagates is ReservedAliasException — a prohibited user alias in the reserved '$'
  // namespace, an input rejection rather than a translator failure.
  // ---------------------------------------------------------------------------

  /**
   * A translator that throws an ordinary {@link RuntimeException} (the realistic walker /
   * recognizer bug) must not break the traversal: {@code apply} catches the exception, declines
   * to the native pipeline, and leaves the step list unchanged. Without the net such a bug would
   * abort compilation for every Gremlin query (the strategy runs on the every-traversal critical
   * path).
   */
  @Test
  public void apply_translatorThrowsRuntimeException_declinesWithoutPropagating() {
    var admin = graph.traversal().V().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              throw new IllegalStateException("simulated walker/recognizer failure");
            });

    var logs =
        captureStrategyDebugLogs(
            () -> assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException());

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
    // declineOnThrow records the swallowed cause at DEBUG — an operator's only signal that a
    // translator bug is silently declining. Pin that the record fired and carries the originating
    // exception, so dropping the log turns this test red instead of letting it go dark unnoticed.
    assertThat(logs)
        .anySatisfy(
            r -> {
              assertThat(r.getMessage()).contains("translation declined");
              assertThat(r.getThrown()).isInstanceOf(IllegalStateException.class);
            });
  }

  /**
   * An {@link Error} from the translator seam must propagate, not decline: a fatal JVM error
   * (e.g. {@code OutOfMemoryError} / {@code StackOverflowError}) must not be swallowed and handed
   * to the native pipeline to re-attempt in an already-exhausted JVM. The net catches {@link
   * RuntimeException} only; {@code Error} is not a {@code RuntimeException}, so it propagates
   * uncaught.
   */
  @Test
  public void apply_translatorThrowsError_propagates() {
    var admin = graph.traversal().V().asAdmin();

    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              throw new StackOverflowError("simulated fatal JVM error");
            });

    assertThatCode(() -> strategy.apply(admin))
        .as("a fatal Error must surface, not degrade to a silent decline")
        .isInstanceOf(StackOverflowError.class);
  }

  /**
   * An {@link AssertionError} (a subclass of {@link Error}) from the translator seam must
   * propagate. Under {@code -ea} — the test / CI default — a genuine invariant violation in the
   * walk or plan build must surface loudly so the broken invariant is visible in the suite,
   * rather than being swallowed into a silent decline that masks a real correctness bug.
   */
  @Test
  public void apply_translatorThrowsAssertionError_propagates() {
    var admin = graph.traversal().V().asAdmin();

    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              throw new AssertionError("simulated invariant violation");
            });

    assertThatCode(() -> strategy.apply(admin))
        .as("an -ea invariant violation must surface, not be swallowed")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * A {@link ReservedAliasException} — the walker's rejection of a user {@code as(...)} label in the
   * reserved {@code $} namespace — must propagate, not decline. It is prohibited input rather than a
   * best-effort-translation failure, so {@code apply} re-throws it (caught before the {@link
   * RuntimeException} clause) and the query fails with a clear error instead of silently running on
   * native, which would accept the {@code $} label. This is the one {@code RuntimeException} subtype
   * the throw-safety net does not swallow.
   */
  @Test
  public void apply_reservedAliasException_propagates() {
    var admin = graph.traversal().V().asAdmin();

    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              throw new ReservedAliasException("Gremlin alias '$foo' uses the reserved '$' prefix");
            });

    assertThatCode(() -> strategy.apply(admin))
        .as("a prohibited reserved-$ alias must surface, not degrade to a native decline")
        .isInstanceOf(ReservedAliasException.class);
  }

  /**
   * A plan builder that throws (a malformed {@code MatchPlanInputs} reaching the planner, say)
   * is caught by the same net. Because the step-list mutation runs only AFTER the plan is
   * built, the throw leaves the original step list intact — the traversal is never left
   * half-rewritten.
   */
  @Test
  public void apply_planBuilderThrows_declinesWithStepListIntact() {
    var admin = graph.traversal().V().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var strategy =
        new GremlinToMatchStrategy(
            t -> fixtureTranslation(),
            (s, tr, planningStart) -> {
              throw new IllegalStateException("simulated planner failure");
            });

    var logs =
        captureStrategyDebugLogs(
            () -> assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException());

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
    // The same net catches a plan-builder throw and logs it at DEBUG. Pin that the record fired
    // and carries the originating exception, so the decline stays observable to an operator.
    assertThat(logs)
        .anySatisfy(
            r -> {
              assertThat(r.getMessage()).contains("translation declined");
              assertThat(r.getThrown()).isInstanceOf(IllegalStateException.class);
            });
  }

  // ---------------------------------------------------------------------------
  // Splice path (all-or-nothing) — a non-empty translation replaces the ENTIRE
  // step list with a single boundary step. Driven here through the fixture seams
  // so the splice is isolated from the production walker.
  // ---------------------------------------------------------------------------

  /**
   * A fixture translator returning a non-empty result, paired with a stub plan builder, drives
   * the replace-all-steps splice: after {@code apply}, the traversal contains exactly one step —
   * a {@link YTDBMatchPlanStep} carrying the stub plan and the translation's boundary metadata.
   * This exercises the {@code applyTranslation} / {@code replaceAllStepsWithBoundary} path with a
   * stub plan, isolating the splice from the production planner; the production path is covered by
   * {@code apply_productionVertexSource_translatesToSingleBoundary}.
   */
  @Test
  public void apply_nonEmptyTranslation_replacesAllStepsWithSingleBoundary() {
    var admin = graph.traversal().V().asAdmin();
    var stubPlan = mock(InternalExecutionPlan.class);
    var translation = fixtureTranslation();

    var strategy =
        new GremlinToMatchStrategy(t -> translation, (s, tr, planningStart) -> stubPlan);

    strategy.apply(admin);

    assertThat(admin.getSteps()).hasSize(1);
    var only = admin.getSteps().getFirst();
    assertThat(only).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) only;
    assertThat(boundary.getPlan()).isSameAs(stubPlan);
    assertThat(boundary.getBoundaryAlias()).isEqualTo("v");
    assertThat(boundary.getOutputType()).isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * A multi-plan translation builds one child plan per child input, installs each child's positional
   * parameters onto that child's own context, and replaces the traversal with a single {@link
   * MultiPlanMatchStep}. The base parameter map stays empty; the child contexts are the sole owners of
   * the child slot values.
   */
  @Test
  public void apply_multiPlanTranslation_buildsEveryChildAndSplicesMultiPlanBoundary() {
    var admin = graph.traversal().V().asAdmin();
    var childA = MatchPlanInputs.builder(new Pattern()).build();
    var childB = MatchPlanInputs.builder(new Pattern()).build();
    var paramsA = Map.<Object, Object>of(0, "alice");
    var paramsB = Map.<Object, Object>of(0, "bob", 1, 42);
    var translation =
        fixtureMultiPlanTranslation(List.of(childA, childB), List.of(paramsA, paramsB));

    var planA = mock(InternalExecutionPlan.class);
    var planB = mock(InternalExecutionPlan.class);
    var ctxA = new BasicCommandContext();
    var ctxB = new BasicCommandContext();
    when(planA.getContext()).thenReturn(ctxA);
    when(planB.getContext()).thenReturn(ctxB);
    var built = new java.util.ArrayList<MatchPlanInputs>();
    var strategy =
        new GremlinToMatchStrategy(
            t -> translation,
            (s, tr, planningStart) -> {
              built.add(tr.inputs());
              return built.size() == 1 ? planA : planB;
            });

    strategy.apply(admin);

    assertThat(built).containsExactly(childA, childB);
    assertThat(ctxA.getInputParameters()).isEqualTo(paramsA);
    assertThat(ctxB.getInputParameters()).isEqualTo(paramsB);
    assertThat(admin.getSteps()).hasSize(1);
    assertThat(admin.getSteps().getFirst()).isInstanceOf(MultiPlanMatchStep.class);
    var boundary = (MultiPlanMatchStep<?, ?>) admin.getSteps().getFirst();
    assertThat(boundary.getPlans()).containsExactly(planA, planB);
    assertThat(boundary.getBoundaryAlias()).isEqualTo("v");
    assertThat(boundary.getOutputType()).isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * A mid-build failure while assembling a multi-plan boundary closes every earlier child plan and
   * declines with the native step list intact. The throwing child never returns a plan, later children
   * are never attempted, and the throw-safety net swallows the build failure into a clean decline.
   */
  @Test
  public void apply_multiPlanChildBuildThrows_closesEarlierChildrenAndDeclines() {
    var admin = graph.traversal().V().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());
    var childA = MatchPlanInputs.builder(new Pattern()).build();
    var childB = MatchPlanInputs.builder(new Pattern()).build();
    var childC = MatchPlanInputs.builder(new Pattern()).build();
    var translation =
        fixtureMultiPlanTranslation(
            List.of(childA, childB, childC),
            List.of(Map.of(), Map.of(0, "boom"), Map.of(0, "unused")));
    var planA = mock(InternalExecutionPlan.class);
    when(planA.getContext()).thenReturn(new BasicCommandContext());
    var builds = new int[1];
    var strategy =
        new GremlinToMatchStrategy(
            t -> translation,
            (s, tr, planningStart) -> {
              builds[0]++;
              if (builds[0] == 1) {
                return planA;
              }
              throw new IllegalStateException("child build blew up");
            });

    var logs =
        captureStrategyDebugLogs(
            () -> assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException());

    assertThat(builds[0]).isEqualTo(2);
    verify(planA, times(1)).close();
    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
    assertThat(logs)
        .anySatisfy(
            r -> {
              assertThat(r.getMessage()).contains("translation declined");
              assertThat(r.getThrown()).isInstanceOf(IllegalStateException.class);
            });
  }

  // ---------------------------------------------------------------------------
  // Gating cascade — non-YTDB / detached start, and the plain-GraphStep start gate.
  // ---------------------------------------------------------------------------

  /**
   * An anonymous, detached traversal ({@code __.V()}) has no attached YouTrackDB graph, so
   * {@code apply} declines at the session-resolution gate without touching {@code tx()} (which
   * would throw {@code UnsupportedOperationException} on TinkerPop's {@code EmptyGraph}). The
   * translator is never consulted and the step list is unchanged.
   */
  @Test
  public void apply_anonymousDetachedTraversal_declines() {
    var admin = __.V().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var consulted = new int[1];
    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              consulted[0]++;
              return fixtureTranslation();
            });

    assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException();
    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(consulted[0]).as("translator consulted for a detached traversal").isZero();
  }

  /**
   * A session whose {@code getConfiguration()} returns {@code null} (its {@code @Nullable}
   * contract permits it) declines cleanly at the kill-switch gate instead of NPE-ing on the
   * flag read. The graph / transaction / session chain is mocked so {@code getConfiguration()}
   * yields {@code null}; {@code apply} must complete without throwing, leave the step list
   * verbatim, and never consult the translator. This pins the defensive null-guard so the
   * decline does not depend on the throw-safety net catching an NPE.
   */
  @Test
  public void apply_nullSessionConfiguration_declinesWithoutNpe() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getConfiguration()).thenReturn(null);
    var tx = mock(YTDBTransaction.class);
    when(tx.getDatabaseSession()).thenReturn(session);
    var ytdbGraph = mock(YTDBGraph.class);
    when(ytdbGraph.tx()).thenReturn(tx);

    // A real traversal with the mocked YTDB graph attached, so resolveSessionIfEnabled walks
    // graph -> tx -> session and reaches the null configuration.
    var admin = new DefaultGraphTraversal<>();
    admin.setGraph(ytdbGraph);
    var stepsBefore = List.copyOf(admin.getSteps());

    var consulted = new int[1];
    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              consulted[0]++;
              return fixtureTranslation();
            });

    assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException();
    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(consulted[0])
        .as("translator consulted despite an unresolvable (null) configuration")
        .isZero();
  }

  /**
   * A non-vertex start ({@code g.E()}) is declined by the vertex-start gate before the
   * translator runs: the current skeleton models only vertex-rooted patterns, and the start
   * step is a plain {@code GraphStep} that returns edges. The step list is left verbatim.
   */
  @Test
  public void apply_edgeStart_declinesBeforeConsultingTranslator() {
    var admin = graph.traversal().E().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var consulted = new int[1];
    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              consulted[0]++;
              return fixtureTranslation();
            });

    strategy.apply(admin);

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(consulted[0]).as("translator consulted for an edge start").isZero();
  }

  /**
   * Sanity control for the gating cascade: when nothing gates out — kill-switch on (default),
   * no pre-existing boundary, plain vertex {@code GraphStep} start — the translator IS
   * consulted. Guards against a gate that silently swallows every traversal and makes the other
   * decline tests vacuous. The fixture translator declines (returns empty), so the traversal is
   * still left unchanged.
   */
  @Test
  public void apply_recognizableStart_consultsTranslator() {
    var admin = graph.traversal().V().asAdmin();
    // Precondition: the start step is a plain GraphStep, not YTDBGraphStep (this strategy runs
    // before YTDBGraphStepStrategy), so the vertex-start gate keys on the right class.
    assertThat(admin.getStartStep()).isNotInstanceOf(YTDBGraphStep.class);
    var stepsBefore = List.copyOf(admin.getSteps());

    var consulted = new int[1];
    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              consulted[0]++;
              return null;
            });

    strategy.apply(admin);

    assertThat(consulted[0]).as("translator not consulted for a plain vertex GraphStep start")
        .isEqualTo(1);
    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
  }

  // ---------------------------------------------------------------------------
  // Production plan-builder path — a real MatchExecutionPlanner plan for a single
  // vertex node, driven through the production constructor (no stub plan builder).
  // ---------------------------------------------------------------------------

  /**
   * Exercises the production splice end to end: a fixture translator returns a translation whose
   * {@link MatchPlanInputs} is a real single-node {@code MATCH {as: v, class: V}} pattern, and the
   * strategy is built with the PRODUCTION plan builder (single-arg constructor). {@code apply}
   * therefore runs the real {@code MatchExecutionPlanner.createExecutionPlan}, and the traversal
   * ends up with exactly one {@link YTDBMatchPlanStep} carrying a real (non-mock) execution plan.
   * This covers the production {@code buildPlan} helper that the stub-plan splice test bypasses.
   */
  @Test
  public void apply_productionPlanBuilder_singleVertexNode_splicesRealPlan() {
    var admin = graph.traversal().V().asAdmin();

    var ir = new MatchPatternBuilder().addNode("v", "V", null, false).build();
    var inputs =
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(ir.aliasFilters())
            .returnElements(true)
            .build();
    var translation =
        GremlinToMatchTranslator.TranslationResult.singlePlan(
            inputs, "v", BoundaryOutputType.ELEMENT, Vertex.class, Map.of(), true);

    // Single-arg constructor → production plan builder (real MatchExecutionPlanner).
    var strategy = new GremlinToMatchStrategy(t -> translation);

    strategy.apply(admin);

    assertThat(admin.getSteps()).hasSize(1);
    var only = admin.getSteps().getFirst();
    assertThat(only).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) only;
    assertThat(boundary.getPlan()).as("a real execution plan was built and installed").isNotNull();
    assertThat(boundary.getBoundaryAlias()).isEqualTo("v");
  }

  /**
   * Multi-plan translations mark the carrier itself non-cacheable ({@code cacheEligible=false});
   * children carry their own eligibility flags for the single-plan {@link GremlinPlanCache}.
   */
  @Test
  public void multiPlanTranslation_isNotCacheEligible() {
    var translation =
        fixtureMultiPlanTranslation(
            List.of(MatchPlanInputs.builder(new Pattern()).build()),
            List.of(Map.of(0, "alice")));

    assertThat(translation.cacheEligible()).isFalse();
    assertThat(translation.childPlans())
        .extracting(GremlinToMatchTranslator.TranslationResult.ChildPlan::cacheEligible)
        .containsExactly(true);
  }

  /**
   * The multi-plan carrier must coerce its own {@code cacheEligible} to {@code false} even when the
   * caller explicitly asks for {@code true}, because an N-plan union is never one plan-cache entry:
   * caching it as one would serve a single stored plan for a shape whose arms cache individually.
   * Built through the canonical constructor rather than {@code multiPlan(...)} on purpose — the
   * factory hardcodes {@code false}, so going through it would read back the fixture's own answer
   * instead of the normalization under test. Per-child eligibility must survive the coercion.
   */
  @Test
  public void multiPlanCarrier_coercesCacheEligibleToFalse_whenCallerPassesTrue() {
    var child = MatchPlanInputs.builder(new Pattern()).build();

    var carrier =
        new GremlinToMatchTranslator.TranslationResult(
            null,
            childPlans(List.of(child), List.of(Map.of()), List.of(true)),
            List.of(),
            "v",
            BoundaryOutputType.ELEMENT,
            Vertex.class,
            Map.of(),
            /* cacheEligible = */ true,
            ResultShaping.NONE);

    assertThat(carrier.cacheEligible())
        .as("the N-plan carrier is never one GremlinPlanCache entry, whatever the caller passed")
        .isFalse();
    assertThat(carrier.childPlans())
        .extracting(GremlinToMatchTranslator.TranslationResult.ChildPlan::cacheEligible)
        .as("per-child eligibility is untouched by the carrier-level coercion")
        .containsExactly(true);
  }

  /**
   * {@code TranslationResult} rejects carriers that are neither single-plan nor multi-plan, that
   * park positional parameters on the base of a multi-plan translation (they belong on the child
   * contexts, one slot map per child), or that hang post-concat reductions off a single-plan
   * translation (a single plan folds its count / limit / dedup into the MATCH statement instead).
   * The former child-list parity checks are gone by construction: the three per-child facts now
   * travel in one {@code ChildPlan}, so they cannot fall out of alignment.
   */
  @Test
  public void translationResult_rejectsInvalidCarriers() {
    var child = MatchPlanInputs.builder(new Pattern()).build();
    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(
            null,
            List.of(),
            List.of(),
            "v",
            BoundaryOutputType.ELEMENT,
            Vertex.class,
            Map.of(),
            false,
            ResultShaping.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("either one plan input or an ordered child plan list");

    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(
            child,
            childPlans(List.of(child), List.of(Map.of()), List.of(true)),
            List.of(),
            "v",
            BoundaryOutputType.ELEMENT,
            Vertex.class,
            Map.of(),
            false,
            ResultShaping.NONE))
        .as("a carrier cannot be single-plan and multi-plan at once")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("either one plan input or an ordered child plan list");

    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(
            child,
            List.of(),
            List.of(PostConcatOp.Count.INSTANCE),
            "v",
            BoundaryOutputType.ELEMENT,
            Vertex.class,
            Map.of(),
            true,
            ResultShaping.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry post-concat reductions");

    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(
            null,
            childPlans(List.of(child), List.of(Map.of()), List.of(true)),
            List.of(),
            "v",
            BoundaryOutputType.ELEMENT,
            Vertex.class,
            Map.of(0, "leak"),
            false,
            ResultShaping.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keep positional parameters on child contexts");
  }

  /**
   * When a mid-build failure closes earlier children, a close failure on an earlier plan is
   * attached as a suppressed exception and does not replace the primary build failure.
   */
  @Test
  public void apply_multiPlanChildBuildThrows_closeFailureIsSuppressed() {
    var admin = graph.traversal().V().asAdmin();
    var childA = MatchPlanInputs.builder(new Pattern()).build();
    var childB = MatchPlanInputs.builder(new Pattern()).build();
    var translation =
        fixtureMultiPlanTranslation(List.of(childA, childB), List.of(Map.of(), Map.of()));
    var planA = mock(InternalExecutionPlan.class);
    when(planA.getContext()).thenReturn(new BasicCommandContext());
    org.mockito.Mockito.doThrow(new IllegalStateException("close failed")).when(planA).close();
    var builds = new int[1];
    var strategy =
        new GremlinToMatchStrategy(
            t -> translation,
            (s, tr, planningStart) -> {
              builds[0]++;
              if (builds[0] == 1) {
                return planA;
              }
              throw new IllegalStateException("child build blew up");
            });

    var logs =
        captureStrategyDebugLogs(
            () -> assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException());

    assertThat(builds[0]).isEqualTo(2);
    verify(planA, times(1)).close();
    assertThat(logs)
        .anySatisfy(
            r -> {
              assertThat(r.getThrown()).isInstanceOf(IllegalStateException.class);
              assertThat(r.getThrown().getMessage()).contains("child build blew up");
              assertThat(r.getThrown().getSuppressed())
                  .singleElement()
                  .isInstanceOf(IllegalStateException.class)
                  .extracting(Throwable::getMessage)
                  .isEqualTo("close failed");
            });
  }

  /**
   * Building a multi-plan boundary through the production plan builder populates {@link
   * GremlinPlanCache} under each eligible child's fingerprint. The multi-plan carrier itself stays
   * non-cacheable; RID-ineligible children bypass.
   */
  @Test
  public void apply_multiPlanWithProductionBuilder_cachesEligibleChildren() {
    GremlinPlanCache.instance(session()).invalidate();

    var ir = new MatchPatternBuilder().addNode("v", "V", null, false).build();
    var childInputs =
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(ir.aliasFilters())
            .returnElements(true)
            .build();
    var fingerprint = GremlinPlanFingerprint.fingerprint(childInputs, ResultShaping.NONE);
    var translation =
        fixtureMultiPlanTranslation(
            List.of(childInputs, childInputs), List.of(Map.of(), Map.of()));
    var admin = graph.traversal().V().asAdmin();
    var strategy = new GremlinToMatchStrategy(t -> translation);

    strategy.apply(admin);

    assertThat(admin.getSteps()).hasSize(1);
    assertThat(admin.getSteps().getFirst()).isInstanceOf(MultiPlanMatchStep.class);
    assertThat(GremlinPlanCache.instance(session()).contains(fingerprint)).isTrue();

    // Second apply of the same child shape reuses the cached plan (still splices MultiPlanMatchStep).
    var admin2 = graph.traversal().V().asAdmin();
    new GremlinToMatchStrategy(t -> translation).apply(admin2);
    assertThat(admin2.getSteps().getFirst()).isInstanceOf(MultiPlanMatchStep.class);
    assertThat(GremlinPlanCache.instance(session()).contains(fingerprint)).isTrue();
  }

  /**
   * The concurrent-DDL guard covers every child, not just the first. {@code buildChildPlans} takes
   * one {@code planningStart} snapshot before the walk and reuses it for all N children, so each
   * child's publish decision is made at a different instant against the same time-of-check value.
   * This drives the harmful interleaving directly: child 0 is built and published, a second thread
   * invalidates the cache the way a concurrent DDL would, and child 1 is then built against the new
   * schema. Child 1 must not be published — with both children sharing a fingerprint, a missing
   * guard would leave the post-invalidation plan in the cache and serve it to every later query of
   * this shape.
   */
  @Test
  public void multiPlanBuild_invalidationBetweenChildren_publishesNeitherChild() throws Exception {
    var cache = GremlinPlanCache.instance(session());
    cache.invalidate();

    var ir = new MatchPatternBuilder().addNode("v", "V", null, false).build();
    var childInputs =
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(ir.aliasFilters())
            .returnElements(true)
            .build();
    var fingerprint = GremlinPlanFingerprint.fingerprint(childInputs, ResultShaping.NONE);
    var translation =
        fixtureMultiPlanTranslation(
            List.of(childInputs, childInputs), List.of(Map.of(), Map.of()));

    var firstChildBuilt = new CountDownLatch(1);
    var invalidated = new CountDownLatch(1);
    var strategy =
        new GremlinToMatchStrategy(
            t -> translation,
            (s, tr, planningStart) -> {
              var plan = GremlinToMatchStrategy.buildPlan(s, tr, planningStart);
              if (firstChildBuilt.getCount() > 0) {
                firstChildBuilt.countDown();
                awaitLatch(invalidated);
              }
              return plan;
            });

    // The cache is shared per database, so the invalidating thread never touches the (thread-bound)
    // session — it holds the cache instance resolved on this thread.
    var ddl =
        new Thread(
            () -> {
              awaitLatch(firstChildBuilt);
              cache.invalidate();
              invalidated.countDown();
            },
            "concurrent-ddl");
    ddl.start();
    try {
      strategy.apply(graph.traversal().V().asAdmin());
    } finally {
      ddl.join(TimeUnit.SECONDS.toMillis(10));
    }

    assertThat(ddl.isAlive()).as("the invalidating thread must not hang").isFalse();
    assertThat(cache.contains(fingerprint))
        .as("neither the invalidated child 0 nor the post-invalidation child 1 may be in the cache")
        .isFalse();
  }

  /** Awaits {@code latch}, converting an interrupt into a test failure rather than a silent skip. */
  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for the other thread");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /**
   * A multi-plan child marked non-cacheable (RID-bearing fork) does not populate {@link
   * GremlinPlanCache}, matching the single-plan RID bypass.
   */
  @Test
  public void apply_multiPlanRidBearingChild_bypassesPlanCache() {
    GremlinPlanCache.instance(session()).invalidate();

    var ir = new MatchPatternBuilder().addNode("v", "V", null, false).build();
    var childInputs =
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(ir.aliasFilters())
            .returnElements(true)
            .build();
    var fingerprint = GremlinPlanFingerprint.fingerprint(childInputs, ResultShaping.NONE);
    var translation =
        fixtureMultiPlanTranslation(
            List.of(childInputs, childInputs),
            List.of(Map.of(), Map.of()),
            List.of(false, false));
    var admin = graph.traversal().V().asAdmin();
    new GremlinToMatchStrategy(t -> translation).apply(admin);

    assertThat(admin.getSteps().getFirst()).isInstanceOf(MultiPlanMatchStep.class);
    assertThat(GremlinPlanCache.instance(session()).contains(fingerprint)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Multiset parity against the native pipeline — the translator-on result must equal
  // the native (translator-off) result for every recognized shape. These run the spliced
  // traversal end to end against a real graph and compare vertex-id multisets.
  // ---------------------------------------------------------------------------

  /**
   * Non-polymorphic bare {@code g.V()} over a schema with a {@code Person extends V} subclass
   * returns the SAME vertices translated as native. Native non-poly {@code g.V()} returns the
   * full polymorphic set (subclass instances included), so a translated plan that narrowed to
   * {@code @class = 'V'} would drop every {@code Person} row and diverge. Under
   * {@code QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT = false} the translated plan must emit no
   * {@code @class} filter and therefore return the identical id set. Both flags are restored in
   * a finally block so later traversals in this same test see the defaults; cross-test isolation
   * is already guaranteed by the per-method database drop, not by this restore.
   */
  @Test
  @SuppressWarnings("DataFlowIssue") // getConfiguration() is @Nullable-inferred but non-null here
  public void nonPolymorphicBareVertexSource_returnsSameVerticesAsNative() {
    // Person extends V; create the subclass before any data transaction is active — schema
    // changes are non-transactional. Use the base-class session so no graph write tx is open.
    session.createVertexClass("Person");

    // isPolymorphic reads the flag off the GRAPH tx session, so set it there (not on the base
    // session): open the graph tx after schema creation, then flip the default-polymorphic flag.
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    var previousPoly =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
    try {
      // Instantiate the subclass so an @class = 'V' narrowing would exclude these rows.
      graph.addVertex(T.label, "Person");
      graph.addVertex(T.label, "Person");
      graph.addVertex(); // a plain V instance too, so the native set spans both classes.
      graph.tx().commit();

      // The baseline must run WITHOUT the translator so it exercises the native pipeline. The
      // kill-switch is read per-session off this same config, so flip it off for the baseline run
      // and restore it — otherwise applyStrategies() would translate g.V() and the parity check
      // would compare translated against translated (tautological).
      var previousKill =
          config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
      final java.util.List<Object> nativeIds;
      try {
        nativeIds = vertexIds(graph.traversal().V().asAdmin(), false);
      } finally {
        config.setValue(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, previousKill);
      }

      var translatedAdmin = graph.traversal().V().asAdmin();
      GremlinToMatchStrategy.instance().apply(translatedAdmin);
      // Precondition: the translator actually claimed this shape (otherwise the parity is vacuous).
      assertThat(translatedAdmin.getSteps()).anyMatch(GremlinToMatchStrategyTest::isBoundary);
      var translatedIds = vertexIds(translatedAdmin, true);

      assertThat(translatedIds)
          .as("non-poly bare g.V() must return the full polymorphic set, matching native")
          .containsExactlyInAnyOrderElementsOf(nativeIds);
      assertThat(translatedIds).hasSize(3);
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previousPoly);
    }
  }

  /**
   * {@code g.V(id, id)} with a repeated id is left on the native pipeline: the production strategy
   * declines the shape (no boundary step is spliced in), because an {@code @rid IN [...]} filter
   * would emit the vertex once while native emits it once per list occurrence. Declining preserves
   * the native duplicate-emission multiset instead of silently returning a smaller one.
   */
  @Test
  public void duplicateIdVertexSource_leftOnNativePipeline() {
    var v = graph.addVertex();
    graph.tx().commit();
    var id = v.id().toString();

    var admin = graph.traversal().V(id, id).asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(admin.getSteps())
        .as("a duplicate-id g.V(ids) must decline to native, leaving the step list verbatim")
        .isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
  }

  /**
   * Planning must see bound {@code ?} values (like SQLMatchStatement). Without them a UNIQUE
   * {@code id = ?} estimates {@code classCount / 2}; a smaller mid-walk class with any filter that
   * the estimator over-narrows (historically {@code IS DEFINED} from {@code select().by()}) can win
   * the root and full-scan. Fixture: many Forums, one Post with UNIQUE id; assert the plan roots at
   * the Post origin, not at Forum.
   */
  @Test
  public void uniqueIdParamAtPlanTime_rootsAtStartNotSmallerMidWalkAlias() {
    var post = session.createVertexClass("Post");
    post.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    session.createVertexClass("Forum");
    session.createEdgeClass("CONTAINER_OF");

    session.begin();
    for (var i = 0; i < 40; i++) {
      session.execute("CREATE VERTEX Forum SET title = 'f" + i + "'").close();
    }
    session.execute("CREATE VERTEX Post SET id = 1").close();
    session.execute(
        "CREATE EDGE CONTAINER_OF FROM (SELECT FROM Forum WHERE title = 'f0') "
            + "TO (SELECT FROM Post WHERE id = 1)")
        .close();
    session.commit();

    var admin =
        graph.traversal()
            .V()
            .hasLabel("Post")
            .has("id", 1L)
            .in("CONTAINER_OF")
            .hasLabel("Forum")
            .as("forum")
            .select("forum")
            .by("title")
            .asAdmin();
    admin.applyStrategies();
    assertThat(admin.getSteps())
        .as("shape must translate")
        .anyMatch(GremlinToMatchStrategyTest::isBoundary);

    var boundary =
        admin.getSteps().stream()
            .filter(YTDBMatchPlanStep.class::isInstance)
            .map(s -> (YTDBMatchPlanStep<?, ?>) s)
            .findFirst()
            .orElseThrow();
    var planText = boundary.getPlan().prettyPrint(0, 2);
    var rootAlias = planRootAliasFromPretty(planText);
    assertThat(rootAlias)
        .as(
            "UNIQUE id=? must win root over a smaller Forum class; plan was:\n" + planText)
        .isEqualTo("$g2m_v0");
  }

  /**
   * Person → KNOWS → friends → HAS_CREATOR ← Message, {@code ORDER BY creationDate DESC, id ASC},
   * {@code LIMIT 20}. SQL MATCH and the translated Gremlin shape must both pick INDEX ORDERED
   * MATCH FILTERED_BOUND on HAS_CREATOR.
   */
  @Test
  public void ic2FriendsMessages_translatedPlanUsesIndexOrderedFilteredBound() throws Exception {
    seedIc2FriendsMessagesGraph();
    var maxDate = new Date(4_000L);

    try (var ignored = setIndexOrderedTestConfig()) {
      session.begin();
      try (var sqlResult = session.query(
          "MATCH {class: Person, as: p, where: (id = 1)}"
              + ".out('KNOWS'){as: friend}"
              + ".in('HAS_CREATOR'){class: Message, as: msg,"
              + " where: (creationDate < ?)} "
              + "RETURN friend.id as personId, friend.firstName as firstName,"
              + " friend.lastName as lastName, msg.id as messageId,"
              + " msg.content as messageContent, msg.creationDate as messageCreationDate "
              + "ORDER BY messageCreationDate DESC, messageId ASC LIMIT 20",
          maxDate)) {
        var sqlPlan = sqlResult.getExecutionPlan().prettyPrint(0, 2);
        assertThat(sqlPlan)
            .as("SQL friends→messages must use INDEX ORDERED MATCH FILTERED_BOUND; plan was:\n"
                + sqlPlan)
            .contains("INDEX ORDERED MATCH")
            .contains("FILTERED_BOUND");
      }
      session.commit();

      support.withTranslator(true, () -> {
        var admin = graph.traversal().V()
            .hasLabel("Person").has("id", 1L)
            .out("KNOWS").as("personId", "firstName", "lastName")
            .in("HAS_CREATOR")
            .hasLabel("Message").as("messageId", "messageContent", "messageCreationDate")
            .has("creationDate", P.lt(maxDate))
            .order().by("creationDate", Order.desc).by("id", Order.asc)
            .limit(20)
            .select(
                "personId",
                "firstName",
                "lastName",
                "messageId",
                "messageContent",
                "messageCreationDate")
            .by("id")
            .by("firstName")
            .by("lastName")
            .by("id")
            .by("content")
            .by("creationDate")
            .asAdmin();
        admin.applyStrategies();
        var steps = admin.getSteps().stream()
            .map(s -> s.getClass().getSimpleName())
            .toList();
        var boundary = admin.getSteps().stream()
            .filter(YTDBMatchPlanStep.class::isInstance)
            .map(s -> (YTDBMatchPlanStep<?, ?>) s)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "friends→messages Gremlin shape must translate; steps after applyStrategies: "
                    + steps));
        var gremlinPlan = boundary.getPlan().prettyPrint(0, 2);
        assertThat(gremlinPlan)
            .as("Gremlin friends→messages must use INDEX ORDERED MATCH FILTERED_BOUND; plan was:\n"
                + gremlinPlan)
            .contains("INDEX ORDERED MATCH")
            .contains("FILTERED_BOUND");
      });
    }
  }

  /**
   * Friends' messages with multi-{@code as} before the slice and {@code select().by()} after
   * {@code limit(20)}. MATCH must keep INDEX ORDERED and defer CALCULATE PROJECTIONS past
   * ORDER BY + LIMIT — same contract as {@link #is2PersonMessages_translatedPlanDefersProjectionsUntilAfterLimit}.
   */
  @Test
  public void ic2FriendsMessages_translatedPlanDefersProjectionsUntilAfterLimit() throws Exception {
    seedIc2FriendsMessagesGraph();
    var maxDate = new Date(4_000L);

    try (var ignored = setIndexOrderedTestConfig()) {
      support.withTranslator(true, () -> {
        var admin = graph.traversal().V()
            .hasLabel("Person").has("id", 1L)
            .out("KNOWS").as("personId", "firstName", "lastName")
            .in("HAS_CREATOR")
            .hasLabel("Message").as("messageId", "messageContent", "messageCreationDate")
            .has("creationDate", P.lt(maxDate))
            .order().by("creationDate", Order.desc).by("id", Order.asc)
            .limit(20)
            .select(
                "personId",
                "firstName",
                "lastName",
                "messageId",
                "messageContent",
                "messageCreationDate")
            .by("id")
            .by("firstName")
            .by("lastName")
            .by("id")
            .by("content")
            .by("creationDate")
            .asAdmin();
        admin.applyStrategies();
        var steps = admin.getSteps().stream()
            .map(s -> s.getClass().getSimpleName())
            .toList();
        var boundary = admin.getSteps().stream()
            .filter(YTDBMatchPlanStep.class::isInstance)
            .map(s -> (YTDBMatchPlanStep<?, ?>) s)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "friends→messages deferred-projection shape must translate; steps after applyStrategies: "
                    + steps));
        assertPlanDefersProjectionsUntilAfterLimit(boundary.getPlan().prettyPrint(0, 2));
      });
    }
  }

  /**
   * Recent replies with foreign-alias {@code order().by(select().by())} before the slice and
   * multi-{@code select().by()} after {@code limit(20)}.
   */
  @Test
  public void ic8RecentReplies_translatedPlanDefersProjectionsUntilAfterLimit() throws Exception {
    seedIc8RecentRepliesGraph();

    try (var ignored = setIndexOrderedTestConfig()) {
      support.withTranslator(true, () -> {
        var admin = graph.traversal().V()
            .hasLabel("Person").has("id", 2L)
            .in("HAS_CREATOR")
            .in("REPLY_OF").hasLabel("Comment")
            .as("commentCreationDate", "commentId", "commentContent")
            .out("HAS_CREATOR").as("personId", "firstName", "lastName")
            .order()
            .by(__.select("commentCreationDate").by("creationDate"), Order.desc)
            .by(__.select("commentId").by("id"), Order.asc)
            .limit(20)
            .select(
                "personId",
                "firstName",
                "lastName",
                "commentCreationDate",
                "commentId",
                "commentContent")
            .by("id")
            .by("firstName")
            .by("lastName")
            .by("creationDate")
            .by("id")
            .by("content")
            .asAdmin();
        admin.applyStrategies();
        var steps = admin.getSteps().stream()
            .map(s -> s.getClass().getSimpleName())
            .toList();
        var boundary = admin.getSteps().stream()
            .filter(YTDBMatchPlanStep.class::isInstance)
            .map(s -> (YTDBMatchPlanStep<?, ?>) s)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "recent-replies deferred-projection shape must translate; steps after applyStrategies: "
                    + steps));
        var plan = boundary.getPlan().prettyPrint(0, 2);
        assertThat(plan)
            .as("recent-replies shape must use INDEX ORDERED MATCH; plan was:\n" + plan)
            .contains("INDEX ORDERED MATCH");
        assertPlanDefersProjectionsUntilAfterLimit(plan);
      });
    }
  }

  /** Sets index-ordered knobs to the values the plan-shape tests need, and restores them on close. */
  private static AutoCloseable setIndexOrderedTestConfig() {
    var oldMinLinkBag = GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValue();
    var oldMaxScan = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValue();
    var oldCostBias = GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValue();
    var oldMaxSources = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.getValue();

    GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(1);
    GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(10_000_000);
    GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(1.0);
    GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(100_000);

    return () -> {
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.setValue(oldMinLinkBag);
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.setValue(oldMaxScan);
      GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.setValue(oldCostBias);
      GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.setValue(oldMaxSources);
    };
  }

  private static void assertPlanDefersProjectionsUntilAfterLimit(String plan) {
    assertThat(plan).contains("INDEX ORDERED MATCH");
    var orderAt = plan.indexOf("+ ORDER BY");
    var limitAt = plan.indexOf("+ LIMIT");
    var projectAt = plan.indexOf("+ CALCULATE PROJECTIONS");
    assertThat(orderAt).as("missing ORDER BY:\n" + plan).isGreaterThanOrEqualTo(0);
    assertThat(limitAt).as("missing LIMIT:\n" + plan).isGreaterThanOrEqualTo(0);
    assertThat(projectAt).as("missing CALCULATE PROJECTIONS:\n" + plan).isGreaterThanOrEqualTo(0);
    assertThat(orderAt)
        .as("ORDER BY must precede projections; plan was:\n" + plan)
        .isLessThan(projectAt);
    assertThat(limitAt)
        .as("LIMIT must precede projections; plan was:\n" + plan)
        .isLessThan(projectAt);
  }

  /**
   * Alice (id=1) knows bob and carol; each friend authored 30 messages before {@code t=4000},
   * for 60 in the {@code creationDate} index. The count is three times what the shape needs,
   * because the plan-time check refuses an ordered scan whose LIMIT reaches the whole index and
   * this shape asks for twenty rows.
   */
  private void seedIc2FriendsMessagesGraph() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    person.createProperty("firstName", PropertyType.STRING);
    person.createProperty("lastName", PropertyType.STRING);
    var message = session.createVertexClass("Message");
    message.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    message.createProperty("creationDate", PropertyType.DATETIME)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    message.createProperty("content", PropertyType.STRING);
    session.createEdgeClass("KNOWS");
    session.createEdgeClass("HAS_CREATOR");

    var alice = graph.addVertex(T.label, "Person", "id", 1L, "firstName", "Alice", "lastName", "A");
    var msgId = 100L;
    for (int f = 2; f <= 3; f++) {
      var friend = graph.addVertex(
          T.label, "Person", "id", (long) f, "firstName", "F" + f, "lastName", "L" + f);
      alice.addEdge("KNOWS", friend);
      // Thirty messages per friend, where ten used to do. The plan-time check now rejects an
      // ordered scan whose LIMIT reaches the whole index, and this shape asks for twenty rows.
      // With twenty messages in the index the scan would read every entry and load every
      // record, so no ordered plan could pay and the plan-shape assertions had nothing to
      // observe. Sixty messages make the LIMIT a real cut.
      for (int m = 0; m < 30; m++) {
        var msg = graph.addVertex(
            T.label, "Message",
            "id", msgId++,
            "creationDate", new Date(1000L + m),
            "content", "c" + m);
        msg.addEdge("HAS_CREATOR", friend);
      }
    }
    graph.tx().commit();
  }

  /**
   * Person → HAS_CREATOR ← Message → REPLY_OF ← Comment → HAS_CREATOR → author,
   * {@code ORDER BY comment.creationDate DESC, comment.id ASC}, {@code LIMIT 20}. SQL uses
   * {@code {class: Comment}}; Gremlin uses {@code hasLabel(Comment)} for the same constraint.
   * The JMH harness shape that omits the label relies on edge-schema class inference
   * ({@code REPLY_OF.out → Comment}) in {@link IndexOrderedPlanner}.
   */
  @Test
  public void ic8RecentReplies_translatedPlanUsesIndexOrderedMatch() throws Exception {
    seedIc8RecentRepliesGraph();

    try (var ignored = setIndexOrderedTestConfig()) {
      session.begin();
      try (var sqlResult = session.query(
          "MATCH {class: Person, as: p, where: (id = 2)}"
              + ".in('HAS_CREATOR'){as: message}"
              + ".in('REPLY_OF'){class: Comment, as: comment}"
              + ".out('HAS_CREATOR'){as: creator} "
              + "RETURN creator.id as personId, creator.firstName as firstName,"
              + " creator.lastName as lastName, comment.creationDate as commentCreationDate,"
              + " comment.id as commentId, comment.content as commentContent "
              + "ORDER BY commentCreationDate DESC, commentId ASC LIMIT 20")) {
        var sqlPlan = sqlResult.getExecutionPlan().prettyPrint(0, 2);
        assertThat(sqlPlan)
            .as("SQL recent-replies must use INDEX ORDERED MATCH; plan was:\n" + sqlPlan)
            .contains("INDEX ORDERED MATCH");
      }
      session.commit();

      support.withTranslator(true, () -> {
        var admin = graph.traversal().V()
            .hasLabel("Person").has("id", 2L)
            .in("HAS_CREATOR")
            .in("REPLY_OF")
            .hasLabel("Comment").as("commentCreationDate", "commentId", "commentContent")
            .out("HAS_CREATOR").as("personId", "firstName", "lastName")
            .order()
            .by(__.select("commentCreationDate").by("creationDate"), Order.desc)
            .by(__.select("commentId").by("id"), Order.asc)
            .limit(20)
            .select(
                "personId",
                "firstName",
                "lastName",
                "commentCreationDate",
                "commentId",
                "commentContent")
            .by("id")
            .by("firstName")
            .by("lastName")
            .by("creationDate")
            .by("id")
            .by("content")
            .asAdmin();
        admin.applyStrategies();
        var steps = admin.getSteps().stream()
            .map(s -> s.getClass().getSimpleName())
            .toList();
        var boundary = admin.getSteps().stream()
            .filter(YTDBMatchPlanStep.class::isInstance)
            .map(s -> (YTDBMatchPlanStep<?, ?>) s)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "recent-replies Gremlin shape must translate; steps after applyStrategies: "
                    + steps));
        var gremlinPlan = boundary.getPlan().prettyPrint(0, 2);
        assertThat(gremlinPlan)
            .as("Gremlin recent-replies must use INDEX ORDERED MATCH; plan was:\n" + gremlinPlan)
            .contains("INDEX ORDERED MATCH");
      });
    }
  }

  /**
   * {@code valueMap} after {@code order().limit()}: translated MATCH must use INDEX ORDERED and
   * defer CALCULATE PROJECTIONS until after ORDER BY + LIMIT so only the top-N rows are projected.
   */
  @Test
  public void is2PersonMessages_translatedPlanDefersProjectionsUntilAfterLimit() throws Exception {
    seedIs2PersonMessagesGraph();

    try (var ignored = setIndexOrderedTestConfig()) {
      support.withTranslator(true, () -> {
        var admin = graph.traversal().V()
            .hasLabel("Person").has("id", 2L)
            .in("HAS_CREATOR")
            .hasLabel("Message")
            .order().by("creationDate", Order.desc)
            .limit(20)
            .valueMap("id", "content", "creationDate")
            .asAdmin();
        admin.applyStrategies();
        var steps = admin.getSteps().stream()
            .map(s -> s.getClass().getSimpleName())
            .toList();
        var boundary = admin.getSteps().stream()
            .filter(YTDBMatchPlanStep.class::isInstance)
            .map(s -> (YTDBMatchPlanStep<?, ?>) s)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "order().limit().valueMap must translate; steps after applyStrategies: " + steps));
        assertPlanDefersProjectionsUntilAfterLimit(boundary.getPlan().prettyPrint(0, 2));
      });
    }
  }

  /**
   * {@code order().by(key).range(…).values(key)}: ORDER BY / SKIP / LIMIT must precede CALCULATE
   * PROJECTIONS so only the page survivors are projected.
   */
  @Test
  public void knowsOrderedPage_translatedPlanDefersProjectionsUntilAfterRange() {
    seedKnowsOrderedPageGraph();

    support.withTranslator(true, () -> {
      var admin = graph.traversal().V()
          .hasLabel("Person").has("id", 1L)
          .out("KNOWS")
          .order().by("firstName")
          .range(1, 3)
          .values("firstName")
          .asAdmin();
      admin.applyStrategies();
      var steps = admin.getSteps().stream()
          .map(s -> s.getClass().getSimpleName())
          .toList();
      var boundary = admin.getSteps().stream()
          .filter(YTDBMatchPlanStep.class::isInstance)
          .map(s -> (YTDBMatchPlanStep<?, ?>) s)
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "order().range().values must translate; steps after applyStrategies: " + steps));
      var plan = boundary.getPlan().prettyPrint(0, 2);
      var orderAt = plan.indexOf("+ ORDER BY");
      var skipAt = plan.indexOf("+ SKIP");
      var limitAt = plan.indexOf("+ LIMIT");
      var projectAt = plan.indexOf("+ CALCULATE PROJECTIONS");
      assertThat(orderAt).as("missing ORDER BY:\n" + plan).isGreaterThanOrEqualTo(0);
      assertThat(projectAt).as("missing CALCULATE PROJECTIONS:\n" + plan)
          .isGreaterThanOrEqualTo(0);
      assertThat(orderAt)
          .as("ORDER BY must precede projections; plan was:\n" + plan)
          .isLessThan(projectAt);
      // range(1, 3) must produce both a SKIP and a LIMIT. Guarding these assertions on
      // skipAt / limitAt being present would let a plan that lost either clause pass silently.
      assertThat(skipAt).as("missing SKIP:\n" + plan).isGreaterThanOrEqualTo(0);
      assertThat(limitAt).as("missing LIMIT:\n" + plan).isGreaterThanOrEqualTo(0);
      assertThat(skipAt)
          .as("SKIP must precede projections; plan was:\n" + plan)
          .isLessThan(projectAt);
      assertThat(limitAt)
          .as("LIMIT must precede projections; plan was:\n" + plan)
          .isLessThan(projectAt);
    });
  }

  /**
   * {@code valueMap} after {@code order()} without LIMIT: ORDER BY is {@code alias.property},
   * which compares on MATCH bindings, so CALCULATE PROJECTIONS stays <em>after</em> ORDER BY
   * even without a slice. Projecting the RETURN list first would only be safe when every
   * ordered alias survives as an entity column; the uniform binding-key deferral keeps
   * {@code RETURN a.name ORDER BY a.id} correct and still lets a later LIMIT cut before the
   * projection.
   *
   * <p>With a LIMIT the same deferral is what
   * {@link #is2PersonMessages_translatedPlanDefersProjectionsUntilAfterLimit} pins; this
   * case shows the rule is not gated on the slice alone.
   */
  @Test
  public void is3FriendsWithNames_translatedPlanDefersProjectionsAfterOrderByWithoutSlice() {
    seedKnowsOrderedPageGraph();

    support.withTranslator(true, () -> {
      var admin = graph.traversal().V()
          .hasLabel("Person").has("id", 1L)
          .out("KNOWS")
          .order().by("firstName")
          .valueMap("id", "firstName", "lastName")
          .asAdmin();
      admin.applyStrategies();
      var steps = admin.getSteps().stream()
          .map(s -> s.getClass().getSimpleName())
          .toList();
      var boundary = admin.getSteps().stream()
          .filter(YTDBMatchPlanStep.class::isInstance)
          .map(s -> (YTDBMatchPlanStep<?, ?>) s)
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "order().valueMap must translate; steps after applyStrategies: " + steps));
      var plan = boundary.getPlan().prettyPrint(0, 2);
      var orderAt = plan.indexOf("+ ORDER BY");
      var projectAt = plan.indexOf("+ CALCULATE PROJECTIONS");
      assertThat(orderAt).as("missing ORDER BY:\n" + plan).isGreaterThanOrEqualTo(0);
      assertThat(projectAt).as("missing CALCULATE PROJECTIONS:\n" + plan)
          .isGreaterThanOrEqualTo(0);
      assertThat(orderAt)
          .as("binding-key ORDER BY defers projections even without a slice; plan was:\n" + plan)
          .isLessThan(projectAt);
    });
  }

  /**
   * A post-cut multi-alias {@code select().by()} on an <em>unfiltered</em> root keeps every
   * upstream binding and returns native's rows.
   *
   * <p>This is the shape the LDBC benchmark queries escape. They pin the root with
   * {@code has("id", …)}, which puts a {@code WHERE} filter on the source alias, and
   * {@code IndexOrderedPlanner} reads that filter alone to classify the source as filtered and
   * take a FILTERED mode. A bare {@code hasLabel} sets a class and no filter, so the planner
   * reaches the UNFILTERED arm and has to decide binding on its own — from the RETURN clause.
   *
   * <p>It used to decide wrongly. The select shipped an empty RETURN clause, which
   * {@code IndexOrderedPlanner.isUpstreamBindingNeeded} read as "no alias is needed downstream";
   * it then chose UNFILTERED_UNBOUND, whose empty upstream row leaves the source alias unbound, and
   * the presence check dropped every row. So the query returned nothing where native returns the
   * twenty maps the slice keeps.
   *
   * <p>Four things are asserted, because the row equality alone cannot tell a fixed planner from a
   * plan that never became index-ordered: the sort keys are all distinct, the plan is
   * index-ordered, its multi-source mode is the BOUND one, and the rows match native.
   */
  @Test
  public void unfilteredRootPostCutMultiSelect_keepsUpstreamBindingsAndMatchesNative()
      throws Exception {
    seedIs2PersonMessagesGraph();
    assertDistinctCreationDatesAcrossMessages();

    try (var ignored = setIndexOrderedTestConfig()) {
      support.withTranslator(true, () -> {
        var admin = unfilteredRootPostCutMultiSelect().asAdmin();
        admin.applyStrategies();
        var steps = admin.getSteps().stream().map(s -> s.getClass().getSimpleName()).toList();
        var boundary = admin.getSteps().stream()
            .filter(YTDBMatchPlanStep.class::isInstance)
            .map(s -> (YTDBMatchPlanStep<?, ?>) s)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "unfiltered-root post-cut select must translate; steps after applyStrategies: "
                    + steps));
        var plan = boundary.getPlan().prettyPrint(0, 2);
        assertThat(plan)
            .as("the fixture must reach the index-ordered plan, or the mode pin below is "
                + "vacuous; plan was:\n" + plan)
            .contains("INDEX ORDERED MATCH");
        assertThat(plan)
            .as("an unfiltered root whose alias the RETURN reads must bind it; plan was:\n" + plan)
            .contains("UNFILTERED_BOUND");
      });

      support.assertEquivalent(
          "g.V().hasLabel(Person).as(a).in(HAS_CREATOR).hasLabel(Message).as(b)"
              + ".order().by(creationDate).limit(20).select(a, b).by(id).by(id)",
          TranslatorEquivalenceSupport.Recognition.RECOGNIZED,
          TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
          TranslatorEquivalenceSupport::sortedStrings,
          this::unfilteredRootPostCutMultiSelect);
    }
  }

  /**
   * The shape under test above, built twice: once to read the plan off and once per arm of the
   * equivalence comparison. {@code limit(20)} is a real slice — the hop yields all 25 messages of
   * the one seeded author — so which rows survive is part of the answer, and the comparison holds
   * only because the sort keys are all distinct. {@link #assertDistinctCreationDatesAcrossMessages}
   * states that premise, so a fixture edit that introduces a duplicate timestamp fails there
   * instead of flaking here.
   *
   * <p>{@code hasLabel("Message")} narrows the hop target's class so the sort key has an index to
   * be ordered by. Without it the target's class is {@code V} and no index-ordered candidate is
   * detected at all. The root keeps only its own class narrowing, which is what makes it
   * unfiltered: a class is not a {@code WHERE} filter.
   *
   * <p>Source and target are deliberately different classes. The order-key presence conjunct
   * ({@code creationDate IS DEFINED}) makes the target look selective to the MATCH root estimator,
   * so a same-class pattern hands the root slot to the target, schedules the edge in reverse, and
   * loses the candidate before the mode decision is ever reached. One Person against 25 Messages
   * keeps the root on the source.
   */
  private GraphTraversal<Vertex, Map<String, Object>> unfilteredRootPostCutMultiSelect() {
    return graph.traversal().V()
        .hasLabel("Person").as("a")
        .in("HAS_CREATOR").hasLabel("Message").as("b")
        .order().by("creationDate", Order.desc)
        .limit(20)
        .select("a", "b").by("id").by("id");
  }

  /**
   * States the premise the ordered-slice comparison rests on: the 25 seeded messages carry 25
   * distinct {@code creationDate} values, so the top twenty are the same twenty on either arm.
   *
   * <p>Read with the translator off, because the premise is about the seeded data rather than about
   * either pipeline. Without this the multiset equality would still pass today and would start
   * flaking the moment two messages shared a timestamp, since a cut inside a tie group is resolved
   * differently by the two arms.
   */
  private void assertDistinctCreationDatesAcrossMessages() {
    support.withTranslator(
        false,
        () -> {
          var dates = graph.traversal().V().hasLabel("Message").values("creationDate").toList();
          assertThat(dates).as("the fixture seeds 25 messages").hasSize(25);
          assertThat(new java.util.HashSet<>(dates))
              .as("the ordered slice is only deterministic while every sort key is distinct")
              .hasSize(25);
        });
  }

  /** Person id=1 with four friends that have distinct {@code firstName} values. */
  private void seedKnowsOrderedPageGraph() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    person.createProperty("firstName", PropertyType.STRING)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    person.createProperty("lastName", PropertyType.STRING);
    session.createEdgeClass("KNOWS");

    var alice = graph.addVertex(T.label, "Person", "id", 1L, "firstName", "Alice", "lastName", "A");
    for (var name : List.of("Dana", "Cara", "Bea", "Eve")) {
      var friend = graph.addVertex(
          T.label, "Person", "id", (long) name.charAt(0), "firstName", name, "lastName", "X");
      alice.addEdge("KNOWS", friend);
    }
    graph.tx().commit();
  }

  /** Bob (id=2) authored 25 messages with monotonic {@code creationDate}. */
  private void seedIs2PersonMessagesGraph() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    var message = session.createVertexClass("Message");
    message.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    message.createProperty("creationDate", PropertyType.DATETIME)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    message.createProperty("content", PropertyType.STRING);
    session.createEdgeClass("HAS_CREATOR");

    var bob = graph.addVertex(T.label, "Person", "id", 2L);
    for (int m = 0; m < 25; m++) {
      var msg = graph.addVertex(
          T.label, "Message",
          "id", 100L + m,
          "creationDate", new Date(1000L + m),
          "content", "m" + m);
      msg.addEdge("HAS_CREATOR", bob);
    }
    graph.tx().commit();
  }

  /**
   * Bob (id=2) authored one message; Carol (id=3) replied to it 41 times; Alice (id=1) is an
   * unused anchor. One reply would leave the ordered plan unreachable, because the plan-time
   * check refuses a scan whose LIMIT reaches the whole index and this shape asks for twenty
   * rows.
   */
  private void seedIc8RecentRepliesGraph() {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    person.createProperty("firstName", PropertyType.STRING);
    person.createProperty("lastName", PropertyType.STRING);
    var message = session.createVertexClass("Message");
    message.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    message.createProperty("creationDate", PropertyType.DATETIME)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    var comment = session.createVertexClass("Comment");
    comment.createProperty("id", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    comment.createProperty("creationDate", PropertyType.DATETIME)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    comment.createProperty("content", PropertyType.STRING);
    session.createEdgeClass("HAS_CREATOR");
    session.createEdgeClass("REPLY_OF");

    var bob = graph.addVertex(T.label, "Person", "id", 2L, "firstName", "Bob", "lastName", "B");
    var carol = graph.addVertex(T.label, "Person", "id", 3L, "firstName", "Carol", "lastName", "C");
    var msg = graph.addVertex(
        T.label, "Message", "id", 100L, "creationDate", new Date(1000L), "content", "post");
    var reply = graph.addVertex(
        T.label, "Comment", "id", 200L, "creationDate", new Date(2000L), "content", "reply");
    msg.addEdge("HAS_CREATOR", bob);
    reply.addEdge("HAS_CREATOR", carol);
    reply.addEdge("REPLY_OF", msg);
    // Forty more replies by Carol on the same message. The shape asks for twenty rows ordered
    // by comment creation date, and the plan-time check rejects an ordered scan whose LIMIT
    // reaches the whole index. One comment in the index left the plan-shape assertions with
    // nothing to observe.
    for (var i = 0; i < 40; i++) {
      var extra = graph.addVertex(
          T.label, "Comment",
          "id", 300L + i,
          "creationDate", new Date(2100L + i),
          "content", "reply" + i);
      extra.addEdge("HAS_CREATOR", carol);
      extra.addEdge("REPLY_OF", msg);
    }
    graph.tx().commit();
  }

  /** Alias after the first {@code + SET} line in a MATCH plan pretty-print. */
  private static String planRootAliasFromPretty(String planText) {
    var lines = planText.lines().toList();
    for (var i = 0; i < lines.size() - 1; i++) {
      if ("+ SET".equals(lines.get(i).strip())) {
        return lines.get(i + 1).strip();
      }
    }
    throw new AssertionError("plan names no root alias on a SET line:\n" + planText);
  }

  /**
   * Materialises a traversal to the set of matched vertex ids, running inside a read-write
   * transaction so the (possibly translated) boundary step can open its execution stream. The
   * {@code applyDefaultStrategies} flag distinguishes the native path (let the default strategy
   * chain compile {@code g.V()} into its native steps) from the already-translated path (the
   * boundary step was spliced by an explicit {@code apply}, so re-running strategies would only
   * risk touching an already-final plan).
   */
  private java.util.List<Object> vertexIds(
      Traversal.Admin<?, ?> admin, boolean alreadyTranslated) {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    try {
      if (!alreadyTranslated) {
        admin.applyStrategies();
      }
      var ids = new java.util.ArrayList<>();
      admin.forEachRemaining(t -> ids.add(((Vertex) t).id()));
      return ids;
    } finally {
      tx.commit();
    }
  }

  private static boolean isBoundary(Step<?, ?> step) {
    return step instanceof YTDBMatchPlanStep<?, ?>;
  }
}
