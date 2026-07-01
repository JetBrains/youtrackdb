package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link GremlinToMatchStrategy}, the skeleton of the Gremlin-to-MATCH
 * provider-optimization strategy.
 *
 * <p>The strategy is a structural no-op in its current state — the production {@link
 * GremlinToMatchTranslator} facade declines every shape — so the tests fall into two groups:
 *
 * <ul>
 *   <li><b>Production-facade tests</b> run {@code GremlinToMatchStrategy.instance().apply(...)}
 *       against a real {@link GraphBaseTest} graph and assert the traversal is left untouched
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
public class GremlinToMatchStrategyTest extends GraphBaseTest {

  /**
   * A translation the fixture translator hands back to drive the splice path. The concrete
   * inputs never reach a real planner in these tests (the plan builder is stubbed), so a bare
   * single-alias {@link MatchPlanInputs} over an empty {@link Pattern} is sufficient.
   */
  private static GremlinToMatchTranslator.TranslationResult fixtureTranslation() {
    var inputs =
        new MatchPlanInputs(
            new Pattern(), null, null, null, null, null, null, null, null, null, null, null, null,
            null, false, false, false, false, false);
    return new GremlinToMatchTranslator.TranslationResult(
        inputs, "v", BoundaryOutputType.ELEMENT, Vertex.class);
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
    session()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
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
    Function<Traversal.Admin<?, ?>,
        Optional<GremlinToMatchTranslator.TranslationResult>> countingTranslator =
            t -> {
              translated[0]++;
              return Optional.of(fixtureTranslation());
            };
    var strategy = new GremlinToMatchStrategy(countingTranslator);

    strategy.apply(admin);

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(translated[0]).as("translator must not be consulted once a boundary exists")
        .isZero();
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
                return Optional.of(fixtureTranslation());
              },
              (s, i) -> {
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
  // All-or-nothing decline — the production facade declines every shape; a declined
  // traversal is left byte-for-byte unchanged (native pipeline handles it).
  // ---------------------------------------------------------------------------

  /**
   * The production strategy (with its declining facade) leaves a {@code g.V()} traversal's step
   * list verbatim — same step instances, same order — because the facade declines. This pins
   * the skeleton's "decline is a no-op mutation" contract: the start step is still the plain
   * {@code GraphStep} the native pipeline expects, not a boundary step.
   */
  @Test
  public void apply_productionFacadeDeclines_leavesNativeStepListVerbatim() {
    var admin = graph.traversal().V().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
  }

  // ---------------------------------------------------------------------------
  // Throw-safety net: an ordinary Exception from a translator declines cleanly (the
  // exception never escapes apply() and the step list is left untouched), but an Error
  // or AssertionError propagates so a fatal JVM error or an -ea invariant violation
  // surfaces loudly instead of degrading to a silent decline.
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

    assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException();
    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
  }

  /**
   * An {@link Error} from the translator seam must propagate, not decline: a fatal JVM error
   * (e.g. {@code OutOfMemoryError} / {@code StackOverflowError}) must not be swallowed and handed
   * to the native pipeline to re-attempt in an already-exhausted JVM. The net catches {@link
   * Exception} only; {@code Error} is re-thrown by the dedicated {@code catch (Error)} arm.
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
            t -> Optional.of(fixtureTranslation()),
            (s, i) -> {
              throw new IllegalStateException("simulated planner failure");
            });

    assertThatCode(() -> strategy.apply(admin)).doesNotThrowAnyException();
    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
  }

  // ---------------------------------------------------------------------------
  // Splice path (all-or-nothing) — a non-empty translation replaces the ENTIRE
  // step list with a single boundary step. Unreachable via the production facade
  // (it declines); driven here through the fixture seams.
  // ---------------------------------------------------------------------------

  /**
   * A fixture translator returning a non-empty result, paired with a stub plan builder, drives
   * the replace-all-steps splice: after {@code apply}, the traversal contains exactly one step —
   * a {@link YTDBMatchPlanStep} carrying the stub plan and the translation's boundary metadata.
   * This exercises the {@code applyTranslation} / {@code replaceAllStepsWithBoundary} path that
   * is dead code until the facade starts recognizing shapes.
   */
  @Test
  public void apply_nonEmptyTranslation_replacesAllStepsWithSingleBoundary() {
    var admin = graph.traversal().V().asAdmin();
    var stubPlan = mock(InternalExecutionPlan.class);
    var translation = fixtureTranslation();

    var strategy =
        new GremlinToMatchStrategy(t -> Optional.of(translation), (s, i) -> stubPlan);

    strategy.apply(admin);

    assertThat(admin.getSteps()).hasSize(1);
    var only = admin.getSteps().getFirst();
    assertThat(only).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) only;
    assertThat(boundary.getPlan()).isSameAs(stubPlan);
    assertThat(boundary.getBoundaryAlias()).isEqualTo("v");
    assertThat(boundary.getOutputType()).isEqualTo(BoundaryOutputType.ELEMENT);
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
              return Optional.of(fixtureTranslation());
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
              return Optional.of(fixtureTranslation());
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
              return Optional.of(fixtureTranslation());
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
              return Optional.empty();
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
        new MatchPlanInputs(
            ir.pattern(),
            ir.aliasClasses(),
            ir.aliasFilters(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            true,
            false,
            false,
            false);
    var translation =
        new GremlinToMatchTranslator.TranslationResult(
            inputs, "v", BoundaryOutputType.ELEMENT, Vertex.class);

    // Single-arg constructor → production plan builder (real MatchExecutionPlanner).
    var strategy = new GremlinToMatchStrategy(t -> Optional.of(translation));

    strategy.apply(admin);

    assertThat(admin.getSteps()).hasSize(1);
    var only = admin.getSteps().getFirst();
    assertThat(only).isInstanceOf(YTDBMatchPlanStep.class);
    var boundary = (YTDBMatchPlanStep<?, ?>) only;
    assertThat(boundary.getPlan()).as("a real execution plan was built and installed").isNotNull();
    assertThat(boundary.getBoundaryAlias()).isEqualTo("v");
  }

  // ---------------------------------------------------------------------------
  // TranslationResult record — non-null field invariants.
  // ---------------------------------------------------------------------------

  /**
   * The {@link GremlinToMatchTranslator.TranslationResult} compact constructor rejects a null
   * value for every field, so a malformed translation fails loudly at construction rather than
   * NPE-ing deep inside the splice. One assertion per field pins which field is guarded.
   */
  @Test
  public void translationResult_rejectsNullFields() {
    var inputs =
        new MatchPlanInputs(
            new Pattern(), null, null, null, null, null, null, null, null, null, null, null, null,
            null, false, false, false, false, false);

    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(
            null, "v", BoundaryOutputType.ELEMENT, Vertex.class))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("inputs");
    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(
            inputs, null, BoundaryOutputType.ELEMENT, Vertex.class))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("boundaryAlias");
    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(inputs, "v", null, Vertex.class))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("outputType");
    assertThatCode(
        () -> new GremlinToMatchTranslator.TranslationResult(
            inputs, "v", BoundaryOutputType.ELEMENT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("returnClass");
  }

  private static boolean isBoundary(Step<?, ?> step) {
    return step instanceof YTDBMatchPlanStep<?, ?>;
  }
}
