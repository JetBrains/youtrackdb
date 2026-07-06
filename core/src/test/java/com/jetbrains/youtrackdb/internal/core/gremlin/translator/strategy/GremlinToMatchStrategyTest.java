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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

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
public class GremlinToMatchStrategyTest extends GraphBaseTest {

  /**
   * A translation the fixture translator hands back to drive the splice path. The concrete
   * inputs never reach a real planner in these tests (the plan builder is stubbed), so a bare
   * single-alias {@link MatchPlanInputs} over an empty {@link Pattern} is sufficient.
   */
  private static GremlinToMatchTranslator.TranslationResult fixtureTranslation() {
    var inputs = MatchPlanInputs.builder(new Pattern()).build();
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

  // getConfiguration() is @Nullable-inferred but non-null on a live session.
  @SuppressWarnings("DataFlowIssue")
  private void setKillSwitch(boolean enabled) {
    session()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
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
   * The production strategy leaves an <em>unrecognized</em> traversal ({@code g.V().out()}, whose
   * {@code out()} step has no recognizer yet) byte-for-byte unchanged: under all-or-nothing one
   * unrecognized step declines the whole traversal, so the native step list — same step instances,
   * same order — is preserved for the native pipeline and no boundary step is spliced in.
   */
  @Test
  public void apply_productionUnrecognizedStep_leavesNativeStepListVerbatim() {
    var admin = graph.traversal().V().out().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(admin.getSteps()).isEqualTo(stepsBefore);
    assertThat(admin.getSteps()).noneMatch(GremlinToMatchStrategyTest::isBoundary);
  }

  // ---------------------------------------------------------------------------
  // Throw-safety net: an unchecked (RuntimeException) failure from a translator declines
  // cleanly (the exception never escapes apply() and the step list is left untouched), but an
  // Error or AssertionError propagates so a fatal JVM error or an -ea invariant violation
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
        MatchPlanInputs.builder(ir.pattern())
            .aliasClasses(ir.aliasClasses())
            .aliasFilters(ir.aliasFilters())
            .returnElements(true)
            .build();
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
