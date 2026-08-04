package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder.Direction;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.UnionStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link GremlinStepWalker} + {@link StartStepRecogniser}, the walker layer that
 * translates the Phase 1 vertex-source shapes ({@code g.V()} / {@code g.V(id)} /
 * {@code g.V(id1, id2, …)}) into {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs}.
 *
 * <p>The tests drive the walker directly (not through the strategy) against real
 * {@link GraphBaseTest} traversals so that {@code YTDBStrategyUtil.isPolymorphic} — which needs
 * an attached YouTrackDB graph — resolves. They verify three things:
 *
 * <ul>
 *   <li><b>Translation correctness</b> — each recognized shape produces the right single-node
 *       {@code $g2m_v0} pattern, with any RIDs rendered as an {@code @rid IN [...]} filter that
 *       the planner promotes to pinned RIDs.
 *   <li><b>The plain-{@code GraphStep} key</b> — the registry keys {@link StartStepRecogniser}
 *       under the plain TinkerPop {@code GraphStep}, NOT {@code YTDBGraphStep}. A pinned
 *       regression test would fail if it keyed on {@code YTDBGraphStep}, because at translator
 *       time (before {@code YTDBGraphStepStrategy} runs) the start step is a plain
 *       {@code GraphStep}. A second test drives a {@code YTDBGraphStep} through the walker to
 *       prove class-keyed dispatch fails safe on the unexpected subclass — {@code
 *       map.get(YTDBGraphStep.class)} finds no entry, so the traversal declines.
 *   <li><b>Decline discipline</b> — an unrecognized step declines the whole walk (the native
 *       step list would be preserved by the caller), a detached / null-{@code isPolymorphic}
 *       traversal declines, and a declining recognizer contributes nothing to the {@link
 *       WalkerContext} (a decline discards the whole walk anyway).
 *   <li><b>Multi-step walker infrastructure</b> — the cursor-driven loop lets a recogniser
 *       consume several steps in one claim; an accept that consumes nothing trips the walker's
 *       progress guard; the reserved-{@code $} pre-flight scan throws on a traversal carrying a
 *       {@code $}-prefixed user label; and {@link WalkerContext}'s anonymous-alias generator mints
 *       distinct, per-context sequences.
 * </ul>
 */
public class GremlinStepWalkerTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";

  // ---------------------------------------------------------------------------
  // Translation correctness — g.V() / g.V(id) / g.V(ids) → MatchPlanInputs.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V()} translates to a single-node pattern under the boundary alias {@code $g2m_v0}
   * with the default vertex class {@code V}, no RID hint, and — under the default polymorphic
   * mode — no {@code @class} narrowing filter. The boundary metadata pins the {@code ELEMENT}
   * output type and {@code Vertex} return class.
   */
  @Test
  public void walk_bareVertexSource_translatesToSingleNodePattern() {
    var admin = graph.traversal().V().asAdmin();

    var translation = GremlinStepWalker.production().walk(admin);

    assertThat(translation).isNotNull();
    assertThat(translation.boundaryAlias()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(translation.outputType()).isEqualTo(BoundaryOutputType.ELEMENT);
    assertThat(translation.returnClass()).isEqualTo(Vertex.class);

    var inputs = translation.inputs();
    assertThat(inputs.pattern().aliasToNode).containsOnlyKeys(BOUNDARY_ALIAS);
    assertThat(inputs.aliasClasses()).containsEntry(BOUNDARY_ALIAS, "V");
    assertThat(inputs.aliasFilters()).as("bare g.V() has no RID filter").doesNotContainKey(
        BOUNDARY_ALIAS);
  }

  /**
   * {@code g.V(id)} with a single RID-shaped ID builds an {@code @rid IN [#X:Y]} filter on
   * {@code aliasFilters}; the planner promotes the size-1 IN list to a single pinned RID (the
   * {@code SELECT FROM #X:Y} fast path). The rendered filter carries the requested RID.
   */
  @Test
  public void walk_singleId_buildsRidInFilter() {
    // #25:3 is an arbitrary well-formed RID literal: the walker only renders it into MATCH SQL and
    // never dereferences it against storage, so no record with this RID need exist.
    var admin = graph.traversal().V("#25:3").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    var inputs = result.inputs();
    assertThat(inputs.aliasFilters()).containsKey(BOUNDARY_ALIAS);
    var rendered = inputs.aliasFilters().get(BOUNDARY_ALIAS).toString();
    // Pin the IN operator and the RID, not just token presence.
    assertThat(rendered).contains("@rid IN ").contains("#25:3");
    assertThat(rendered).doesNotContain("NOT IN");
  }

  /**
   * {@code g.V(id1, id2)} with multiple RID-shaped IDs builds an {@code @rid IN [#..:.., #..:..]}
   * filter on {@code aliasFilters} rather than an {@code aliasRids} hint (which the grammar caps
   * at one RID per alias). The rendered filter carries both requested RIDs.
   */
  @Test
  public void walk_multipleIds_buildsRidInFilter() {
    // #25:3 and #25:7 are arbitrary well-formed RID literals used only to check IN-filter
    // rendering; the walker never dereferences them against storage.
    var admin = graph.traversal().V("#25:3", "#25:7").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    var inputs = result.inputs();
    assertThat(inputs.aliasFilters()).containsKey(BOUNDARY_ALIAS);
    var rendered = inputs.aliasFilters().get(BOUNDARY_ALIAS).toString();
    // Pin the IN operator and both RIDs, not just token presence: an equality-OR rewrite
    // ("@rid = #25:3 OR @rid = #25:7"), a dropped IN operator, or a NOT-IN negation would all
    // still contain the three bare tokens the looser check accepted.
    assertThat(rendered).contains("@rid IN ").contains("#25:3").contains("#25:7");
    assertThat(rendered).doesNotContain("NOT IN");
  }

  // ---------------------------------------------------------------------------
  // The plain-GraphStep gate — recognizer keys on plain GraphStep, not YTDBGraphStep.
  // ---------------------------------------------------------------------------

  /**
   * Regression guard for the plain-{@code GraphStep} gate. At translator time — before
   * {@code YTDBGraphStepStrategy} runs — the traversal's start step is a plain TinkerPop
   * {@code GraphStep}, NOT {@code YTDBGraphStep}. The walker must recognize this shape. This test
   * asserts the precondition (start step is a plain {@code GraphStep}, not the YTDB subclass) and
   * then that the walk succeeds. If the recognizer keyed on {@code YTDBGraphStep} it would decline
   * here, translating nothing — so this test fails loudly under the wrong gate.
   */
  @Test
  public void walk_plainGraphStepStart_isRecognized() {
    var admin = graph.traversal().V().asAdmin();
    assertThat(admin.getStartStep())
        .as("precondition: at translator time the start step is a plain GraphStep")
        .isInstanceOf(GraphStep.class)
        .isNotInstanceOf(YTDBGraphStep.class);

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("plain GraphStep start must be recognized, not declined").isNotNull();
  }

  /**
   * Class-keyed dispatch fails safe on an unexpected subclass. If the strategy ordering ever
   * changed so the translator ran after {@code YTDBGraphStepStrategy} folded the plain
   * {@code GraphStep} into a {@code YTDBGraphStep}, the walker would see a step whose runtime
   * class ({@code YTDBGraphStep}) has no registry entry — {@code map.get(YTDBGraphStep.class)}
   * returns {@code null} — so it declines the whole traversal rather than misrouting the
   * unexpected subclass through the {@code GraphStep} recogniser. Under the production strategy
   * ordering this never happens: the translator runs before {@code YTDBGraphStepStrategy}, so it
   * sees the plain {@code GraphStep}. The decline is the safe default for a shape we did not
   * expect.
   */
  @Test
  public void walk_ytdbGraphStepStart_declinesAsUnexpectedSubclass() {
    var traversal = graph.traversal().V();
    // Run the half-measure strategy that rewrites the plain GraphStep into a YTDBGraphStep,
    // simulating the case where the translator ran after (not before) YTDBGraphStepStrategy.
    YTDBGraphStepStrategy.instance().apply(traversal.asAdmin());
    var admin = traversal.asAdmin();
    assertThat(admin.getStartStep()).isInstanceOf(YTDBGraphStep.class);

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a YTDBGraphStep has no registry entry, so class-keyed dispatch declines it")
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // Decline discipline — unrecognized step, edge start, detached traversal,
  // no-mutation-on-decline.
  // ---------------------------------------------------------------------------

  /**
   * All-or-nothing across a recognized prefix: a recognized hop followed by a step no recogniser
   * claims declines the whole walk, and no partial translation of the recognized prefix leaks. The
   * fixture is {@code g.V().out("knows").map(...)}: the walker recognizes the vertex source and the
   * {@code out("knows")} hop — which mutates the context (mints an alias, re-pins the boundary,
   * appends the edge and target nodes) — then dispatches the lambda map, finds no recogniser, and
   * declines. A lambda is arbitrary user code with no MATCH equivalent, so it is a permanently
   * out-of-scope decline fixture no later track will start translating (unlike a hop / predicate /
   * aggregate, each of which a later track teaches a recogniser). The walk returns null — nothing
   * partial escapes — and the native step list is left untouched. This is the sharp guarantee now
   * that hops themselves translate: a recognized prefix must not survive as a half-built plan when a
   * later step declines.
   */
  @Test
  public void walk_recognizedHopThenUnrecognizedStep_declinesWholeWalk() {
    var admin = graph.traversal().V().out("knows").map(t -> t.get()).asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a recognized hop followed by an unrecognized step declines the whole walk")
        .isNull();
    assertThat(admin.getSteps())
        .as("the walker never mutates the traversal's native step list")
        .isEqualTo(stepsBefore);
  }

  /**
   * A multi-step traversal is now walked (no up-front size gate) and declines at the first
   * unrecognized step class. {@code g.V().fold()} has two steps: the walker recognizes the
   * vertex source, then hits {@code FoldStep} — which has no registry entry — so under
   * all-or-nothing the whole walk declines. This pins that removing the upper-bound size gate
   * did not let a multi-step traversal translate: it still declines, via the no-recogniser path
   * rather than a step-count check. The traversal's native step list is left untouched.
   */
  @Test
  public void walk_multiStepTraversal_declinesAtUnrecognizedFollowUpStep() {
    var admin = graph.traversal().V().fold().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a multi-step traversal declines at the unrecognized follow-up step")
        .isNull();
    assertThat(admin.getSteps())
        .as("the walk never mutates the traversal's native step list")
        .isEqualTo(stepsBefore);
  }

  /** {@code g.V().count()} translates end-to-end with {@code SCALAR} output and {@code count(*)}. */
  @Test
  public void walk_count_pinsScalarOutput() {
    var admin = graph.traversal().V().count().asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.outputType()).isEqualTo(BoundaryOutputType.SCALAR);
    assertThat(result.inputs().returnItems().getFirst().toString())
        .containsIgnoringCase("count(*)");
  }

  /**
   * An edge start ({@code g.E()}) declines: the start-step recognizer accepts only vertex-rooted
   * ({@code returnsVertex()}) sources.
   */
  @Test
  public void walk_edgeStart_declines() {
    var admin = graph.traversal().E().asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * A label-less hop under {@code EdgeLabelVerificationStrategy} declines the whole walk. The walker
   * resolves the strategy's presence once up front and stores it on the context; the hop recogniser
   * reads that flag and declines a label-less {@code out()} so the native pipeline can raise the
   * label-less error the strategy exists to produce. This pins the walker's own resolution of the flag
   * from the strategy list (the recogniser-side decline is pinned by {@link VertexHopRecogniserTest}).
   */
  @Test
  public void walk_labelLessHopUnderEdgeLabelVerification_declines() {
    var admin =
        graph
            .traversal()
            .withStrategies(EdgeLabelVerificationStrategy.build().create())
            .V()
            .out()
            .asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a label-less hop under EdgeLabelVerificationStrategy declines the whole walk")
        .isNull();
  }

  /**
   * A single-step, graph-less traversal declines at the walker's own null-{@code isPolymorphic}
   * gate. The walker resolves the graph-level polymorphism flag once, up front (see {@link
   * GremlinStepWalker#walk}), before dispatching any step; a {@code null} result — no attached
   * YTDB graph — declines the whole walk without a recognizer ever running. This pins that
   * walker-level decline branch.
   *
   * <p>The traversal is a mock whose {@code getGraph()} is empty, so {@code
   * YTDBStrategyUtil.isPolymorphic} returns {@code null}. {@code isPolymorphic} is null-safe (it
   * gates on an attached YTDB graph and transaction before touching {@code tx()}), so a real
   * detached {@code EmptyGraph} traversal would likewise return {@code null} here rather than
   * throw. The single well-formed vertex {@code GraphStep} sharpens the point: a traversal that
   * would otherwise translate is declined purely because the graph is absent.
   */
  @Test
  public void walk_nullIsPolymorphic_declines() {
    // A real vertex GraphStep so the mock traversal carries a shape that would otherwise
    // translate; it is never dispatched because the walker's null-isPolymorphic gate fires first
    // (before the per-step recognizer loop).
    Step<?, ?> vertexStart = graph.traversal().V().asAdmin().getStartStep();

    @SuppressWarnings("unchecked")
    Traversal.Admin<Object, Object> graphless = mock(Traversal.Admin.class);
    when(graphless.getSteps()).thenReturn(List.of(vertexStart));
    when(graphless.getGraph()).thenReturn(Optional.empty());

    var result = GremlinStepWalker.production().walk(graphless);

    assertThat(result).as("a null isPolymorphic declines the whole walk").isNull();
  }

  /**
   * A declining {@link StartStepRecogniser} contributes nothing (here for an edge start): no pattern
   * node, no alias filter/RID, no boundary metadata. The recogniser validates before it contributes,
   * so a decline leaves the {@link WalkerContext} clean — and a decline discards the whole walk
   * anyway, so nothing it did could leak.
   */
  @Test
  public void recognizer_declines_contributesNothing() {
    var admin = graph.traversal().E().asAdmin();
    var ctx = new WalkerContext(true, false);
    var cursor = new StepStreamCursor(admin.getSteps(), Set.of(NoOpBarrierStep.class));

    var outcome = StartStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("edge start is not a vertex source").isEqualTo(Outcome.DECLINE);
    assertThat(ctx.patternBuilder.build().pattern().aliasToNode)
        .as("declining recognizer must add no pattern node").isEmpty();
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.returnItems).isEmpty();
    assertThat(ctx.boundaryAlias).isNull();
    assertThat(ctx.outputType).isNull();
    assertThat(ctx.returnClass).isNull();
  }

  /**
   * The start recognizer's "I am the start" guard is a state check: a context whose boundary is
   * already pinned (a start step already ran, or a misregistered registry placing the start recognizer
   * after another step) declines even for a well-formed vertex {@code GraphStep}. This replaces the old
   * absolute-index check with the equivalent boundary-state condition.
   */
  @Test
  public void recognizer_afterBoundaryPinned_declines() {
    var admin = graph.traversal().V().asAdmin();
    var ctx = new WalkerContext(true, false);
    // Simulate a prior start step having pinned the boundary.
    ctx.pinBoundary("$g2m_v0", BoundaryOutputType.ELEMENT, Vertex.class);
    var cursor = new StepStreamCursor(admin.getSteps(), Set.of(NoOpBarrierStep.class));

    var outcome = StartStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("start recognizer declines once a boundary is already pinned")
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.boundaryAlias).isEqualTo("$g2m_v0");
  }

  /**
   * A malformed RID string ({@code g.V("not-a-rid")}) declines cleanly rather than throwing: the
   * ID cannot be normalized to a record id, so the recognizer returns false and the whole walk
   * declines. This keeps unconvertible-ID traversals on the native pipeline that knows how to
   * resolve every Gremlin ID shape.
   */
  @Test
  public void walk_unconvertibleId_declines() {
    var admin = graph.traversal().V("not-a-rid").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("an unconvertible ID declines the whole walk").isNull();
  }

  /**
   * A numeric id ({@code g.V(1L)}) declines via a branch DISTINCT from the malformed-String case:
   * {@code toRecordId} takes its {@code case null, default -> null} arm for a non-String,
   * non-{@code Identifiable} id, so the recogniser returns the decline sentinel and the whole walk
   * declines. Numeric ids are a common Gremlin shape (upstream TinkerPop suites lean on them), and
   * the all-or-nothing parity contract depends on declining every id the recogniser cannot convert
   * so the native pipeline resolves it.
   */
  @Test
  public void walk_numericId_declines() {
    var admin = graph.traversal().V(1L).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("a numeric (non-RID) id declines the whole walk").isNull();
  }

  /**
   * A blank / whitespace-only RID string ({@code g.V("   ")}) declines via a branch DISTINCT from
   * the malformed-RID case above: {@code RecordIdInternal.fromString} maps a blank string to the
   * {@code #-1:-1} changeable-RID placeholder rather than throwing, so without the recogniser's
   * explicit {@code isBlank()} guard a blank id would translate into a degenerate lookup that
   * diverges from native {@code g.V("")}'s empty result. This pins that guard: a blank id declines
   * the whole walk to the native pipeline.
   */
  @Test
  public void walk_blankRidString_declines() {
    var admin = graph.traversal().V("   ").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("a blank RID string declines the whole walk").isNull();
  }

  // ---------------------------------------------------------------------------
  // Polymorphism invariant — g.V() / g.V(ids) never narrow by @class, so the
  // polymorphicQuery flag cannot change their translation. Later tracks that add
  // new node aliases (out()/in() chain hops, hasLabel) WILL honour the flag and
  // add @class narrowing; these tests pin the current-scope invariant.
  // ---------------------------------------------------------------------------

  /**
   * Non-polymorphic mode does NOT narrow a bare {@code g.V()} by class. Native non-polymorphic
   * {@code g.V()} still returns the full polymorphic vertex set: the no-id branch of
   * {@code YTDBGraphImplAbstract.elements} browses the class polymorphically regardless of the
   * flag. Emitting {@code @class = 'V'} would exclude every subclass instance the native path
   * keeps, so under {@code QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT = false} the recogniser must emit
   * no boundary-alias filter for a bare {@code g.V()}; the {@code V} scan stays pinned via
   * {@code aliasClasses} (polymorphic by MATCH default).
   */
  @Test
  public void walk_nonPolymorphicBareVertexSource_emitsNoClassFilter() {
    withNonPolymorphicDefault(() -> {
      var admin = graph.traversal().V().asAdmin();

      var result = GremlinStepWalker.production().walk(admin);

      assertThat(result).isNotNull();
      // No @class narrowing: a bare g.V() carries no boundary-alias filter even under non-poly.
      assertThat(result.inputs().aliasFilters())
          .as("non-poly bare g.V() must not narrow by @class")
          .doesNotContainKey(BOUNDARY_ALIAS);
      // The single-node polymorphic V-class scan is still pinned via aliasClasses.
      assertThat(result.inputs().aliasClasses()).containsEntry(BOUNDARY_ALIAS, "V");
    });
  }

  /**
   * Non-polymorphic mode does NOT narrow {@code g.V(id)} either. The by-id path resolves purely by
   * RID — {@code YTDBGraphImplAbstract.elements} applies no class filter on the by-id branch — so
   * the RID's class is irrelevant and the {@code polymorphicQuery} flag is inert. The single RID
   * lands on an {@code @rid IN [#25:3]} filter (which the planner promotes to a single pinned RID,
   * the {@code SELECT FROM #X:Y} fast path) with no {@code @class} predicate, exactly as under the
   * default mode.
   */
  @Test
  public void walk_nonPolymorphicSingleId_buildsRidInFilterWithoutClassFilter() {
    withNonPolymorphicDefault(() -> {
      // #25:3 is an arbitrary well-formed RID literal; the walker only renders it.
      var admin = graph.traversal().V("#25:3").asAdmin();

      var result = GremlinStepWalker.production().walk(admin);

      assertThat(result).isNotNull();
      var inputs = result.inputs();
      assertThat(inputs.aliasFilters()).containsKey(BOUNDARY_ALIAS);
      var rendered = inputs.aliasFilters().get(BOUNDARY_ALIAS).toString();
      assertThat(rendered).contains("@rid IN ").contains("#25:3");
      // No @class narrowing added even under non-poly: the by-id lookup is RID-only.
      assertThat(rendered)
          .as("non-poly g.V(id) must not narrow by @class")
          .doesNotContain("@class");
    });
  }

  /**
   * Non-polymorphic mode does NOT narrow {@code g.V(id1, id2)} either. The multi-id path emits an
   * {@code @rid IN [...]} filter and, like the single-id path, resolves by RID alone — no
   * {@code @class} predicate is ANDed onto the filter under {@code polymorphic=false}. The rendered
   * filter carries the IN operator and both RIDs and nothing about {@code @class}.
   */
  @Test
  public void walk_nonPolymorphicMultipleIds_buildsRidInFilterWithoutClassFilter() {
    withNonPolymorphicDefault(() -> {
      // #25:3 and #25:7 are arbitrary well-formed RID literals used only for filter rendering.
      var admin = graph.traversal().V("#25:3", "#25:7").asAdmin();

      var result = GremlinStepWalker.production().walk(admin);

      assertThat(result).isNotNull();
      var inputs = result.inputs();
      assertThat(inputs.aliasFilters()).containsKey(BOUNDARY_ALIAS);
      var rendered = inputs.aliasFilters().get(BOUNDARY_ALIAS).toString();
      assertThat(rendered).contains("@rid IN ").contains("#25:3").contains("#25:7");
      // The key assertion: no @class narrowing ANDed in under non-poly.
      assertThat(rendered)
          .as("non-poly g.V(ids) must not narrow by @class")
          .doesNotContain("@class");
    });
  }

  /**
   * {@code g.V(id, id)} with a repeated id declines the whole walk. An {@code @rid IN [...]}
   * filter has set semantics — MATCH emits each matching vertex once regardless of how many times
   * its id appears in the list — while native {@code g.V(ids)}
   * ({@code YTDBGraphImplAbstract.elements}) streams the id array one-to-one and emits the vertex
   * once per occurrence. Since MATCH cannot reproduce the native duplicate-emission multiset, the
   * recogniser declines the shape to the native pipeline rather than return a smaller multiset.
   */
  @Test
  public void walk_duplicateIds_declines() {
    var admin = graph.traversal().V("#25:3", "#25:3").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a repeated id cannot be expressed exactly by @rid IN, so the walk declines")
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // Walker gate + invariant discipline — an empty traversal declines up front at
  // the empty gate; an accept that consumes nothing, or a walk that leaves the
  // boundary unpinned, trips a walker guard rather than declining silently.
  // ---------------------------------------------------------------------------

  /**
   * An empty traversal (zero steps) declines up front at the empty-traversal gate, before the
   * walk loop and before the boundary invariant. A step-less traversal has nothing to translate
   * and could never pin a boundary; declining it here keeps it a normal decline (a {@code null}
   * return) rather than letting it reach the post-walk invariant assert, which is reserved for a
   * recogniser that claims a step without pinning the boundary. A Mockito traversal with an empty
   * step list drives the {@code steps.isEmpty()} branch directly.
   */
  @Test
  public void walk_emptyTraversal_declinesAtEmptyGate() {
    @SuppressWarnings("unchecked")
    Traversal.Admin<Object, Object> emptyTraversal = mock(Traversal.Admin.class);
    when(emptyTraversal.getSteps()).thenReturn(List.of());

    var result = GremlinStepWalker.production().walk(emptyTraversal);

    assertThat(result).as("an empty traversal declines at the empty gate").isNull();
  }

  /**
   * A recogniser that claims its step (returns {@code true}) but never pins the boundary metadata
   * violates the walker's post-walk invariant: every fully-recognised non-empty traversal must
   * carry a pinned boundary. Because empty traversals are gated out earlier, reaching the invariant
   * with a null boundary can only be a recogniser-logic bug, so the walker asserts rather than
   * declining silently — a silent decline would mask the bug. Under {@code -ea} (the test/CI
   * default) the assert throws {@link AssertionError}, which {@code GremlinToMatchStrategy}'s
   * throw-safety net does NOT catch (it catches only {@code RuntimeException}), so the bug surfaces
   * loudly instead of degrading to a silent decline. The fixture recogniser takes its head (so the
   * cursor advances and the walk reaches the post-walk boundary invariant) but pins nothing on the
   * context, isolating the boundary invariant from the in-loop progress guard.
   */
  @Test
  public void walk_recogniserLeavesBoundaryUnpinned_tripsInvariantAssert() {
    // Fixture recogniser: takes its head (so the cursor advances) but pins no boundary metadata —
    // isolating the post-walk boundary invariant from the in-loop progress guard.
    StepRecogniser unpinning =
        (cursor, ctx) -> {
          cursor.take();
          return Outcome.ACCEPTED;
        };
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, unpinning));
    var admin = graph.traversal().V().asAdmin();

    assertThatThrownBy(() -> walker.walk(admin))
        .as("a recognised walk that leaves the boundary unpinned trips the invariant assert")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * A recogniser that returns {@link Outcome#DECLINE} makes the walk return {@code null} rather than
   * spin: the walker sees the decline and stops the whole walk. This pins that a decline is a clean
   * decline, not a hang.
   */
  @Test
  public void walk_recogniserDeclines_declinesWithoutSpinning() {
    // Fixture recogniser: always declines. The walker must return null, never loop.
    StepRecogniser declining = (cursor, ctx) -> Outcome.DECLINE;
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, declining));
    var admin = graph.traversal().V().asAdmin();

    var result = walker.walk(admin);

    assertThat(result).as("a DECLINE stops the walk cleanly; the walker never spins").isNull();
  }

  /**
   * A recogniser that returns {@link Outcome#ACCEPTED} without consuming any step trips the walker's
   * progress guard. An accept that advanced nothing would re-dispatch the same head forever, so the
   * guard asserts {@code cursor.position() > positionBefore}. Under {@code -ea} the assert throws
   * {@link AssertionError} (not swallowed by the strategy's {@code RuntimeException}-only net), so the
   * bug surfaces loudly rather than spinning a live query. The fixture accepts without ever calling
   * {@code take}, the exact no-progress bug this guard defends against.
   */
  @Test
  public void walk_acceptWithoutConsuming_tripsProgressGuard() {
    // Fixture recogniser: accepts without consuming any step (never calls take) — the no-progress bug.
    StepRecogniser nonConsuming = (cursor, ctx) -> Outcome.ACCEPTED;
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, nonConsuming));
    var admin = graph.traversal().V().asAdmin();

    assertThatThrownBy(() -> walker.walk(admin))
        .as("an accept that consumes nothing trips the walker's progress guard")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * The cursor-driven walker supports a multi-step claim: a recogniser may consume several steps in
   * one call, and the walker resumes dispatch at the new cursor position rather than re-inspecting the
   * consumed steps. The fixture delegates to {@link StartStepRecogniser} (which takes the start step
   * and pins the boundary) and then takes one more step, so it claims BOTH steps of {@code
   * g.V().out()} in a single call. The {@code out()} {@code VertexStep} at index 1 is therefore never
   * dispatched (no recogniser is registered for it), yet the walk succeeds — proving the loop honours
   * a recogniser consuming multiple steps. This is a walker-mechanic test; the fixture's claim over
   * {@code out()} is contrived (real edge-hop translation lands in a dedicated recogniser).
   */
  @Test
  public void walk_multiStepClaim_recogniserConsumesMultipleSteps() {
    // Fixture: reuse StartStepRecogniser's valid single-node build (takes the start step and pins the
    // boundary), then take one more step so the claim spans both steps of g.V().out().
    StepRecogniser twoStepClaim =
        (cursor, ctx) -> {
          var base = StartStepRecogniser.INSTANCE.recognize(cursor, ctx);
          if (base == Outcome.DECLINE) {
            return Outcome.DECLINE;
          }
          cursor.take(); // consume the out() step too, so the claim spans both steps
          return Outcome.ACCEPTED;
        };
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, twoStepClaim));
    var admin = graph.traversal().V().out("knows").asAdmin();
    assertThat(admin.getSteps()).as("precondition: g.V().out() is a two-step traversal").hasSize(2);

    var result = walker.walk(admin);

    assertThat(result)
        .as("a recogniser that consumes both steps translates the whole traversal")
        .isNotNull();
    assertThat(result.boundaryAlias()).isEqualTo(BOUNDARY_ALIAS);
  }

  // ---------------------------------------------------------------------------
  // Reserved-prefix pre-flight scan + anonymous-alias generator — the walker
  // infrastructure this step adds ahead of the edge-hop recognisers.
  // ---------------------------------------------------------------------------

  /**
   * A user label starting with the reserved {@code $} prefix is prohibited: the walker's
   * reserved-prefix pre-flight scan throws a {@link ReservedAliasException} rather than declining, so
   * the query fails loudly instead of silently running on the native pipeline. {@code as("$foo")}
   * labels the vertex {@code GraphStep} with {@code $foo}; the scan runs before any recogniser, so the
   * translator's minted {@code $g2m_} alias namespace can never be shadowed by a user label. The
   * strategy's throw-safety net re-throws this one exception type rather than degrading it to a native
   * decline (see {@code GremlinToMatchStrategyTest#apply_reservedAliasException_propagates}).
   */
  @Test
  public void walk_reservedDollarUserLabel_throwsReservedAlias() {
    var admin = graph.traversal().V().as("$foo").asAdmin();

    assertThatThrownBy(() -> GremlinStepWalker.production().walk(admin))
        .as("a $-prefixed user label is prohibited and throws")
        .isInstanceOf(ReservedAliasException.class)
        .hasMessageContaining("$foo");
  }

  /**
   * A non-{@code $} user label does not trip the reserved-prefix scan. {@code g.V().as("foo")} is
   * a single {@code GraphStep} carrying the label {@code foo}; the scan keys specifically on the
   * {@code $} prefix, so this traversal still translates (the label has no consumer step in this
   * track, so it is inert on the single-node pattern). This pins that the scan does not decline
   * every labelled traversal — only reserved-prefix ones.
   */
  @Test
  public void walk_nonReservedUserLabel_notDeclinedByReservedScan() {
    var admin = graph.traversal().V().as("foo").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a non-$ user label must not be declined by the reserved-prefix scan")
        .isNotNull();
  }

  /**
   * {@code g.V().dedup()} translates end-to-end and sets {@code returnDistinct} on the assembled
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs}.
   */
  @Test
  public void walk_vertexSourceWithDedup_setsReturnDistinct() {
    var admin = graph.traversal().V().dedup().asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().returnDistinct()).isTrue();
  }

  /**
   * {@code g.V().as("v").dedup("v")} sets distinct and keeps the boundary RETURN (does not rewrite
   * columns under the user label).
   */
  @Test
  public void walk_namedDedupWithAs_setsDistinctKeepsBoundaryReturn() {
    var admin = graph.traversal().V().as("v").dedup("v").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().returnDistinct()).isTrue();
    assertThat(result.inputs().returnAliases()).hasSize(1);
    assertThat(result.inputs().returnAliases().getFirst().getStringValue())
        .isEqualTo(result.boundaryAlias());
  }

  /** {@code dedup("missing")} without a matching {@code as(...)} declines the whole walk. */
  @Test
  public void walk_namedDedupUnboundLabel_declines() {
    var admin = graph.traversal().V().dedup("missing").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * {@code g.V().as("a").out().as("b").dedup("a","b")} declines — prior-hop uniqueness while
   * emitting the current traverser is not expressible as MATCH {@code DISTINCT} on RETURN.
   */
  @Test
  public void walk_multiHopNamedDedup_declines() {
    var admin = graph.traversal().V().as("a").out().as("b").dedup("a", "b").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * A hop after a captured slice declines the whole walk. The slice becomes a statement-level
   * {@code LIMIT}, which MATCH applies after the pattern, so a translated
   * {@code g.V().limit(2).out()} would slice the hop's output where Gremlin slices its input.
   */
  @Test
  public void walk_sliceThenHop_declines() {
    var admin = graph.traversal().V().limit(2).out("knows").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * The gate is not hop-specific. A filter after a slice lands in the pattern's {@code WHERE},
   * which also runs before the statement-level {@code LIMIT}, so it declines the same way.
   */
  @Test
  public void walk_sliceThenFilter_declines() {
    var admin = graph.traversal().V().limit(2).has("name", "Hub").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * {@code order()} after a slice declines too: MATCH sorts before it slices, so the translated
   * statement would select the two smallest rows of the whole match where Gremlin sorts the two
   * rows the slice already picked.
   */
  @Test
  public void walk_sliceThenOrder_declines() {
    var admin = graph.traversal().V().limit(2).order().by("name").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * The gate arms on a captured clause, not on the presence of a range step. {@code skip(0)}
   * normalises away to nothing and sets no clause, so the following hop still translates.
   */
  @Test
  public void walk_noopSliceThenHop_translates() {
    var admin = graph.traversal().V().skip(0).out("knows").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().skip()).isNull();
    assertThat(result.inputs().limit()).isNull();
  }

  // ---------------------------------------------------------------------------
  // where(...) scope bindings are no longer transparent.
  // ---------------------------------------------------------------------------

  /**
   * A {@code where} child whose result must equal a labelled traverser declines. While {@code
   * WhereEndStep} was transparent the comparison was dropped and the translation asked only that
   * the child produce something, which is a weaker filter than the user wrote.
   */
  @Test
  public void walk_whereChildWithEndLabel_declines() {
    var admin =
        graph.traversal().V().as("a").out().as("b").where(__.as("a").out().as("b")).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * The start binding alone is enough to decline, with no end label present. The child must run
   * from {@code a}, and skipping the binding ran it from the current element instead — a different
   * question whenever the label is not the current element.
   */
  @Test
  public void walk_whereChildWithStartLabelOnly_declines() {
    var admin = graph.traversal().V().as("a").out().as("b").where(__.as("a").out()).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * The decline is confined to the labelled family. An unlabelled {@code where(__.has(...))} is a
   * {@code TraversalFilterStep} whose child carries neither scope class, so it keeps translating —
   * the boundary the surface-loss claim rests on. The child is a pure filter rather than a hop on
   * purpose: an edge-bearing child is declined by a separate gate on the child-commit path, which
   * would mask what this case is here to show.
   */
  @Test
  public void walk_plainWhereChild_stillTranslates() {
    var admin = graph.traversal().V().where(__.has("name", "Bob")).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
  }

  /**
   * The scope binding declines on its own, with no hop anywhere in the child. This is the case that
   * isolates the transparency change: the child is a pure filter, so no edge-bearing-child gate can
   * account for the decline, and the only thing left to explain it is the {@code WhereStartStep}.
   * The shape is also one the translation got <em>right</em> before, since {@code a} is the current
   * element — it is here as the priced surface, not as a defect witness.
   */
  @Test
  public void walk_whereChildLabelledPureFilter_declines() {
    var admin = graph.traversal().V().as("a").where(__.as("a").has("age", 30)).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * {@code g.V().as(a).out().as(b).where(__.as(a).out().as(b))} returns native's rows once the walk
   * declines. Before the fix the translated arm returned nothing where native returned the one row
   * whose hop target matches {@code b}.
   *
   * <p>The transparent reading passed as the control is what the pre-fix walk collapsed the shape
   * onto: with both scope steps skipped the child was the bare {@code out()}. Natively the two
   * spellings disagree on this fixture — the shape returns Bob, the reading returns nothing, since
   * Bob has no out-edge — which is what stops the equality below from holding for a reason
   * unrelated to the binding.
   */
  @Test
  public void whereWithEndLabel_declinesAndReturnsNativeRows() {
    seedOneKnowsEdgeNoSelfLoop();

    assertDeclinesAndMatchesNative(
        "g.V().as(a).out().as(b).where(__.as(a).out().as(b))",
        () -> graph.traversal().V().as("a").out().as("b").where(__.as("a").out().as("b")),
        () -> graph.traversal().V().as("a").out().as("b").where(__.out()));
  }

  /**
   * The opposite direction of the same defect: the child asks for a self-loop, the fixture has
   * none, so native returns nothing. Before the fix the dropped end comparison turned the child
   * into "has an out-edge" and the translated arm returned a row.
   *
   * <p>Both arms are empty once the walk declines, so the multiset equality carries nothing on its
   * own here. What carries the case is the boundary assertion together with the transparent-reading
   * control: {@code g.V().where(__.out())} is exactly the traversal the skipped scope steps left
   * behind, and it returns Alice where the shape returns nothing. The separation is the fixture
   * property the case depends on, so it is asserted rather than described.
   */
  @Test
  public void whereWithSelfComparingEndLabel_declinesAndReturnsNativeRows() {
    seedOneKnowsEdgeNoSelfLoop();

    assertDeclinesAndMatchesNative(
        "g.V().as(a).where(__.as(a).out().as(a))",
        () -> graph.traversal().V().as("a").where(__.as("a").out().as("a")),
        () -> graph.traversal().V().where(__.out()));
  }

  // ---------------------------------------------------------------------------
  // A captured cardinality clause ends the single-plan walk.
  // ---------------------------------------------------------------------------

  /**
   * The mirror of the slice gate: a slice behind a {@code values(k)} declines, because the
   * projection's row-dropping rides post-plan shaping and a statement-level {@code LIMIT} would
   * count rows the drop has not removed yet.
   */
  @Test
  public void walk_valuesThenSlice_declines() {
    var admin = graph.traversal().V().values("age").limit(1).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * That decline is keyed on a real slice, so a {@code skip(0)} that selects no position leaves the
   * projection translatable.
   */
  @Test
  public void walk_valuesThenNoopSlice_translates() {
    var admin = graph.traversal().V().values("age").skip(0).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().skip()).isNull();
    assertThat(result.outputType()).isEqualTo(BoundaryOutputType.SINGLE_VALUE);
  }

  /**
   * {@code valueMap} pins no {@code dropOnAbsent} — absent keys are omitted from the map rather than
   * dropping the row — so a slice behind one still translates. This keeps the decline above from
   * being read as covering every projection.
   */
  @Test
  public void walk_valueMapThenSlice_translates() {
    var admin = graph.traversal().V().valueMap("name").limit(2).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().limit()).isNotNull();
  }

  /**
   * {@code RETURN DISTINCT} arms the same gate as a slice: MATCH applies the {@code DISTINCT} after
   * the pattern, so a hop after {@code dedup()} would traverse from rows the distinct has not
   * collapsed yet.
   */
  @Test
  public void walk_distinctThenHop_declines() {
    var admin = graph.traversal().V().in("knows").dedup().out("knows").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /**
   * A pure projection is the gate's allow-listed exception: {@code values(k)} contributes RETURN
   * columns and result shaping, both applied after the statement's {@code LIMIT}, so the walk
   * survives.
   */
  @Test
  public void walk_sliceThenValues_translates() {
    var admin = graph.traversal().V().limit(2).values("name").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().limit()).isNotNull();
    assertThat(result.outputType()).isEqualTo(BoundaryOutputType.SINGLE_VALUE);
  }

  /**
   * The allow-list stops at the projections that contribute nothing but columns. A {@code
   * select(...).by(key)} contributes a {@code key IS DEFINED} conjunct into the pattern, which
   * would filter rows before the slice counted them, so it declines.
   */
  @Test
  public void walk_sliceThenSelectByKey_declines() {
    var admin = graph.traversal().V().as("a").limit(2).select("a").by("name").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNull();
  }

  /** A terminal slice is unaffected — {@code g.V().out().limit(2)} still translates. */
  @Test
  public void walk_hopThenSlice_translates() {
    var admin = graph.traversal().V().out("knows").limit(2).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().limit()).isNotNull();
  }

  /** {@code g.V().values("name")} translates end-to-end with {@code SINGLE_VALUE} boundary type. */
  @Test
  public void walk_valuesSingleKey_pinsSingleValueOutput() {
    var admin = graph.traversal().V().values("name").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.outputType()).isEqualTo(BoundaryOutputType.SINGLE_VALUE);
    assertThat(result.shaping().dropOnAbsent()).isTrue();
    assertThat(result.shaping().presencePropertyKeys()).containsExactly("name");
    assertThat(result.inputs().returnItems()).hasSize(2);
    assertThat(result.inputs().returnItems().get(1).toString()).contains("name");
  }

  /** {@code g.V().values("age").mean()} translates end-to-end with {@code SCALAR} + dropNullRows. */
  @Test
  public void walk_valuesMean_pinsScalarDropNullRows() {
    var admin = graph.traversal().V().values("age").mean().asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.outputType()).isEqualTo(BoundaryOutputType.SCALAR);
    assertThat(result.shaping().dropNullRows()).isTrue();
  }

  /** {@code g.V().as("v").select("v")} translates with {@code MAP} output and labelled RETURN. */
  @Test
  public void walk_selectBoundLabel_pinsMapOutput() {
    var admin = graph.traversal().V().as("v").select("v").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.outputType()).isEqualTo(BoundaryOutputType.MAP);
    assertThat(result.inputs().returnAliases().getFirst().getStringValue()).isEqualTo("v");
  }

  /** {@code g.V().valueMap("name")} translates with property column in RETURN. */
  @Test
  public void walk_valueMapSingleKey_pinsMapOutput() {
    var admin = graph.traversal().V().valueMap("name").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.outputType()).isEqualTo(BoundaryOutputType.MAP);
    assertThat(result.shaping().wrapMapValuesInLists()).isTrue();
    assertThat(result.shaping().presencePropertyKeys()).containsExactly("name");
    assertThat(result.inputs().returnAliases().getFirst().getStringValue())
        .isEqualTo("$g2m_v0");
  }

  /** {@code g.V().order().by("name", Order.desc)} translates with an ORDER BY clause. */
  @Test
  public void walk_orderByProperty_setsOrderBy() {
    var admin =
        graph.traversal().V().order()
            .by("name", org.apache.tinkerpop.gremlin.process.traversal.Order.desc)
            .asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().orderBy()).isNotNull();
    assertThat(result.inputs().orderBy().toString()).contains("name");
  }

  /** {@code g.V().range(1, 4)} translates to SKIP 1 LIMIT 3. */
  @Test
  public void walk_range_setsSkipAndLimit() {
    var admin = graph.traversal().V().range(1, 4).asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.inputs().skip()).isNotNull();
    assertThat(result.inputs().limit()).isNotNull();
    assertThat(result.inputs().skip().toString()).contains("1");
    assertThat(result.inputs().limit().toString()).contains("3");
  }

  /**
   * A null user label must not throw from the reserved-prefix scan. A step's label set can carry a
   * null — {@code as((String) null)} reaches {@code AbstractStep.addLabel}, which adds the label
   * with no null guard — and the scan's inner loop calls {@code startsWith} on each label. Without
   * a null guard that call NPEs; the strategy's {@code RuntimeException} net would mask it to a
   * native decline, but a direct walk (as here, and as a future refactor that moves or directly
   * calls the scan might do) would surface the NPE. The scan skips nulls, so a null label is inert:
   * it cannot collide with the reserved {@code $} namespace, and the bare {@code g.V()} still
   * translates rather than declining or throwing.
   */
  @Test
  public void walk_nullUserLabel_notDeclinedByReservedScanAndDoesNotThrow() {
    var admin = graph.traversal().V().asAdmin();
    // Inject a null label directly onto the start step, mirroring g.V().as((String) null):
    // AbstractStep.addLabel adds it with no null guard, so getLabels() returns a set containing
    // null — the exact input that NPE'd the reserved-prefix scan before the null guard.
    admin.getStartStep().addLabel(null);
    assertThat(admin.getStartStep().getLabels())
        .as("precondition: the start step carries a null label")
        .contains((String) null);

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a null user label must not NPE the reserved scan; the bare g.V() still translates")
        .isNotNull();
  }

  /**
   * The anonymous-alias generator mints distinct, sequenced aliases under the reserved {@code
   * $g2m_} prefixes, with independent per-kind counters that reset per {@link WalkerContext}. The
   * vertex sequence is {@code $g2m_anon_0}, {@code $g2m_anon_1}, …; the edge sequence is {@code
   * $g2m_edge_0}, {@code $g2m_edge_1}, …; minting an edge alias does not perturb the vertex counter
   * (and vice versa). A fresh context restarts both sequences at 0, so alias names are deterministic
   * per query rather than monotonic across the JVM. The {@code edgeFilters} map — infrastructure
   * this step adds, populated by a later edge recogniser — starts empty.
   */
  @Test
  public void context_anonAliasGenerator_mintsDistinctSequencedAliases() {
    var ctx = new WalkerContext(true, false);

    assertThat(ctx.nextAnonVertexAlias()).isEqualTo("$g2m_anon_0");
    assertThat(ctx.nextAnonVertexAlias()).isEqualTo("$g2m_anon_1");
    // Independent counter: minting edge aliases does not advance the vertex counter.
    assertThat(ctx.nextEdgeAlias()).isEqualTo("$g2m_edge_0");
    assertThat(ctx.nextEdgeAlias()).isEqualTo("$g2m_edge_1");
    assertThat(ctx.nextAnonVertexAlias()).isEqualTo("$g2m_anon_2");
    assertThat(ctx.edgeFilters).as("edgeFilters starts empty until a recogniser populates it")
        .isEmpty();

    // Per-context reset: a fresh walk restarts both sequences at 0.
    var fresh = new WalkerContext(true, false);
    assertThat(fresh.nextAnonVertexAlias()).isEqualTo("$g2m_anon_0");
    assertThat(fresh.nextEdgeAlias()).isEqualTo("$g2m_edge_0");
  }

  /**
   * A suffix the post-union gate refuses declines the union <em>before</em> any child is forked.
   * The traversal-level outcome is a decline either way, so only the fork count can tell the two
   * apart: forking first means every arm runs a complete sub-walk whose result is then discarded,
   * on every compilation of a query that never translates.
   */
  @Test
  public void union_untranslatableSuffix_declinesWithoutForkingAnyChild() {
    var admin = graph.traversal().V().union(__.out(), __.in()).out().asAdmin();
    var cursor = cursorAtUnion(admin);
    var host = new CountingUnionForkHost(cursor, POST_UNION_GATE_REGISTRY);
    var ctx = unionSeededContext(host);

    var outcome = UnionStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(host.forkCalls)
        .as("a hop after the union is refused by the gate, so no arm may be walked")
        .isZero();
  }

  /**
   * The complement: a suffix the gate accepts ({@code count()} is a post-concat op) does reach the
   * fork, so the pre-fork check narrows nothing that used to translate. The fork stub declines, so
   * the recogniser stops after the first arm.
   */
  @Test
  public void union_translatableSuffix_reachesTheFork() {
    var admin = graph.traversal().V().union(__.out(), __.in()).count().asAdmin();
    var cursor = cursorAtUnion(admin);
    var host = new CountingUnionForkHost(cursor, POST_UNION_GATE_REGISTRY);
    var ctx = unionSeededContext(host);

    var outcome = UnionStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(host.forkCalls)
        .as("count() is post-concat translatable, so the first arm must still be walked")
        .isEqualTo(1);
  }

  /**
   * A positional slice is on the post-union allow-list but only translates when a {@code count()}
   * follows it immediately, and the pre-fork gate mirrors that rather than leaving it to the
   * recogniser. Without the mirror {@code union(...).limit(3)} — the commonest post-union suffix —
   * would walk every arm on each compilation and throw the results away.
   */
  @Test
  public void union_positionalSuffixWithoutCount_declinesWithoutForkingAnyChild() {
    var admin = graph.traversal().V().union(__.out(), __.in()).limit(3).asAdmin();
    var cursor = cursorAtUnion(admin);
    var host = new CountingUnionForkHost(cursor, POST_UNION_GATE_REGISTRY);
    var ctx = unionSeededContext(host);

    var outcome = UnionStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(host.forkCalls)
        .as("a bare slice after the union is refused by the gate, so no arm may be walked")
        .isZero();
  }

  /**
   * The complement: the same slice with its {@code count()} attached is translatable, so it must
   * still reach the fork. Pins that the mirror above narrows only the diverging half of the shape.
   * The traversal-level outcome is {@code DECLINE} either way — {@link CountingUnionForkHost} is a
   * stub whose {@code walkFork} declines — so {@code forkCalls} is the observable under test.
   */
  @Test
  public void union_positionalSuffixEndingInCount_reachesTheFork() {
    var admin = graph.traversal().V().union(__.out(), __.in()).limit(3).count().asAdmin();
    var cursor = cursorAtUnion(admin);
    var host = new CountingUnionForkHost(cursor, POST_UNION_GATE_REGISTRY);
    var ctx = unionSeededContext(host);

    var outcome = UnionStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome)
        .as("the fork stub declines, so the traversal-level outcome is DECLINE either way")
        .isEqualTo(Outcome.DECLINE);
    assertThat(host.forkCalls)
        .as("limit(n).count() is post-concat translatable, so the first arm must still be walked")
        .isEqualTo(1);
  }

  /**
   * A slice that normalises away to nothing selects no position, so the mirror must let it through
   * unchanged: {@code skip(0)} still reaches the fork even though no count follows it. Without the
   * carve-out the gate would be stricter than the recogniser and would decline a shape that used to
   * translate. As above, the traversal-level outcome is {@code DECLINE} either way because the fork
   * stub declines; {@code forkCalls} is the observable.
   */
  @Test
  public void union_noOpSliceWithoutCount_stillReachesTheFork() {
    var admin = graph.traversal().V().union(__.out(), __.in()).skip(0).asAdmin();
    var cursor = cursorAtUnion(admin);
    var host = new CountingUnionForkHost(cursor, POST_UNION_GATE_REGISTRY);
    var ctx = unionSeededContext(host);

    var outcome = UnionStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome)
        .as("the fork stub declines, so the traversal-level outcome is DECLINE either way")
        .isEqualTo(Outcome.DECLINE);
    assertThat(host.forkCalls)
        .as("skip(0) appends no reduction, so the gate must not treat it as a positional slice")
        .isEqualTo(1);
  }

  /**
   * Every recogniser on the post-union allow-list must answer {@link
   * StepRecogniser#selectsPositionally} itself rather than inherit the interface default. The
   * membership gate and the positional gate are two separate decisions, and a member that inherits
   * {@code false} would get the first and silently skip the second — which is how a step that
   * selects by position (a {@code tail(n)} recogniser, say) could translate after a union and hand
   * back rows the concatenation ordered differently from native. Failing the build is the point:
   * whoever widens the allow-list has to state an answer instead of omitting one.
   */
  @Test
  public void everyPostUnionRecogniserStatesItsOwnPositionalAnswer() {
    for (var recogniser : GremlinStepWalker.POST_UNION_RECOGNISERS) {
      var declaresOwn =
          Arrays.stream(recogniser.getClass().getDeclaredMethods())
              .anyMatch(m -> m.getName().equals("selectsPositionally"));

      assertThat(declaresOwn)
          .as(
              recogniser.getClass().getSimpleName()
                  + " is on the post-union allow-list, so it must override selectsPositionally"
                  + " rather than inherit the StepRecogniser default")
          .isTrue();
    }
  }

  /**
   * The interface default answers {@code false}, which is safe only for the recognisers the
   * membership gate refuses before the question is asked — {@link VertexStepRecogniser} is one, and
   * a hop after a union declines on membership alone. Pins the default's value so the fail-closed
   * reasoning above rests on a checked fact rather than on the absence of an override.
   */
  @Test
  public void recogniserOutsideThePostUnionAllowList_inheritsANonPositionalAnswer() {
    var admin = graph.traversal().V().out().asAdmin();
    var hop = admin.getSteps().get(1);
    assertThat(GremlinStepWalker.POST_UNION_RECOGNISERS)
        .as("fixture premise: the hop recogniser must be off the allow-list")
        .doesNotContain(VertexStepRecogniser.INSTANCE);

    assertThat(VertexStepRecogniser.INSTANCE.selectsPositionally(hop)).isFalse();
  }

  /**
   * Registry the gate reads in the tests above: the hop recogniser (not post-concat capable), the
   * count recogniser (post-concat capable), and the range recogniser (post-concat capable only
   * ahead of a count), under every step class the un-strategized traversal can produce for those
   * shapes.
   */
  private static final Map<Class<?>, StepRecogniser> POST_UNION_GATE_REGISTRY =
      Map.of(
          VertexStep.class, VertexStepRecogniser.INSTANCE,
          VertexStepPlaceholder.class, VertexStepRecogniser.INSTANCE,
          CountGlobalStep.class, CountGlobalStepRecogniser.INSTANCE,
          RangeGlobalStep.class, RangeGlobalStepRecogniser.INSTANCE,
          RangeGlobalStepPlaceholder.class, RangeGlobalStepRecogniser.INSTANCE);

  /** Advances a fresh cursor over {@code admin}'s steps until the union is the head. */
  private static StepStreamCursor cursorAtUnion(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), Set.of(NoOpBarrierStep.class));
    while (cursor.peek() != null && !(cursor.peek() instanceof UnionStep)) {
      cursor.take();
    }
    Assert.assertTrue("fixture must reach a UnionStep", cursor.peek() instanceof UnionStep);
    return cursor;
  }

  /** Context with a pinned boundary and {@code host} installed as the union fork seam. */
  private static WalkerContext unionSeededContext(UnionForkHost host) {
    var ctx = new WalkerContext(true, false);
    ctx.addNode("$g2m_v0", "V");
    ctx.pinBoundary("$g2m_v0", BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setUnionForkHost(host);
    return ctx;
  }

  /**
   * Fork seam stand-in that counts {@link #walkFork} calls and answers the suffix gate through the
   * production check, so the tests above pin the ordering of the two operations rather than a
   * hand-written verdict.
   */
  private static final class CountingUnionForkHost implements UnionForkHost {

    private final StepCursor cursor;
    private final Map<Class<?>, StepRecogniser> recognisers;
    private int forkCalls;

    CountingUnionForkHost(StepCursor cursor, Map<Class<?>, StepRecogniser> recognisers) {
      this.cursor = cursor;
      this.recognisers = recognisers;
    }

    @Override
    public List<Step<?, ?>> recognisedPrefixSteps() {
      // Non-empty: the recogniser declines a start-position union, which is not what is under test.
      return List.of(mock(Step.class));
    }

    @Override
    public boolean postUnionSuffixTranslatable() {
      return GremlinStepWalker.postUnionSuffixTranslatable(cursor, recognisers);
    }

    @Override
    public GremlinToMatchTranslator.TranslationResult walkFork(List<Step<?, ?>> childSuffix) {
      forkCalls++;
      return null;
    }

    @Override
    public void stashAcceptedChildren(
        List<com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs> childInputs,
        List<Map<Object, Object>> childInputParameters,
        List<Boolean> childCacheEligible) {
      throw new AssertionError("no arm translates in these fixtures, so nothing may be stashed");
    }
  }

  // ---------------------------------------------------------------------------
  // bindPathItemConstraints — the preservation rules, one method per rule.
  // The equivalence suites cover the binding itself end-to-end, and the
  // leave-an-unlisted-alias-alone rule as well (EdgeTraversalEquivalenceTest's
  // edgePathItemFilter_survivesTargetConstraintBinding runs a shape that hits it).
  // The other rules have no traversal shape that reaches them, so a regression
  // would break them silently and these unit tests are their only net. When a
  // shape does reach one, promote it to an equivalence case and delete the unit
  // test — these exist only for as long as no shape does.
  // ---------------------------------------------------------------------------

  /**
   * Binding the target alias's class and {@code WHERE} onto the closing vertex item leaves the edge
   * item alone: the edge alias is in neither map, and its own predicate lives nowhere else, so
   * touching it would drop the {@code outE(L).has(p, v).inV()} edge filter with no error.
   */
  @Test
  public void bindPathItemConstraints_bindsTarget_andLeavesUnlistedEdgeAliasAlone() {
    var wb = new MatchWhereBuilder();
    var edgeWhere = wb.wrap(wb.eq("weight", MatchLiteralBuilder.toLiteral(1L)));
    var targetWhere = wb.wrap(wb.eq("name", MatchLiteralBuilder.toLiteral("vadas")));
    var ir =
        new MatchPatternBuilder()
            .addEdgeAsNode("a", "e", "t", Direction.OUT, "knows", Direction.IN, edgeWhere)
            .build();

    GremlinStepWalker.bindPathItemConstraints(
        ir.pattern(), Map.of("t", targetWhere), Map.of("t", "Person"));

    var edgeItem = itemTargeting(ir.pattern(), "e");
    assertThat(renderWhere(edgeItem)).contains("weight");
    assertThat(edgeItem.getFilter().getClassName(null)).isNull();
    var targetItem = itemTargeting(ir.pattern(), "t");
    assertThat(renderWhere(targetItem)).contains("name");
    assertThat(targetItem.getFilter().getClassName(null)).isEqualTo("Person");
  }

  /**
   * A path item that already carries a {@code WHERE} keeps it — the alias filter is AND-composed on
   * top rather than written over it. An overwriting rebind would reintroduce the dropped-constraint
   * defect on a second surface, this time losing the item's own predicate instead of the alias's.
   */
  @Test
  public void bindPathItemConstraints_andComposesWithExistingItemWhere() {
    var wb = new MatchWhereBuilder();
    var itemWhere = wb.wrap(wb.eq("since", MatchLiteralBuilder.toLiteral(2020L)));
    var aliasWhere = wb.wrap(wb.eq("name", MatchLiteralBuilder.toLiteral("vadas")));
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "t", Direction.OUT, "knows", itemWhere, null, null)
            .build();

    GremlinStepWalker.bindPathItemConstraints(
        ir.pattern(), Map.of("t", aliasWhere), Map.of());

    assertThat(renderWhere(itemTargeting(ir.pattern(), "t"))).contains("since").contains("name");
  }

  /**
   * A class already on the path item is not replaced by the alias map's class. The item's class is
   * the more specific of the two by construction — it was written by whichever pass already knew the
   * concrete type — so overwriting it would widen the constraint and return rows native excludes.
   */
  @Test
  public void bindPathItemConstraints_keepsClassAlreadyOnItem() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "t", Direction.OUT, "knows", null, null, null)
            .build();
    itemTargeting(ir.pattern(), "t").getFilter().setClassName("Employee");

    GremlinStepWalker.bindPathItemConstraints(ir.pattern(), Map.of(), Map.of("t", "Person"));

    assertThat(itemTargeting(ir.pattern(), "t").getFilter().getClassName(null))
        .isEqualTo("Employee");
  }

  /**
   * The bound {@code WHERE} is a copy, not the instance the alias map holds. The same map is handed
   * to the planner, so binding the instance itself would leave one mutable AST with two owners —
   * exactly the arrangement the planner copies to avoid everywhere else it shares a clause.
   */
  @Test
  public void bindPathItemConstraints_bindsACopyOfTheAliasFilter() {
    var wb = new MatchWhereBuilder();
    var aliasWhere = wb.wrap(wb.eq("name", MatchLiteralBuilder.toLiteral("vadas")));
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "t", Direction.OUT, "knows", null, null, null)
            .build();

    GremlinStepWalker.bindPathItemConstraints(ir.pattern(), Map.of("t", aliasWhere), Map.of());

    assertThat(itemTargeting(ir.pattern(), "t").getFilter().getFilter())
        .isNotSameAs(aliasWhere);
    assertThat(renderWhere(itemTargeting(ir.pattern(), "t"))).contains("name");
  }

  /**
   * The generic {@code V} root class the hop recognisers register on every target is not bound: it
   * excludes nothing a vertex hop can reach, so binding it would only add a class check per
   * candidate row.
   */
  @Test
  public void bindPathItemConstraints_skipsGenericVertexRootClass() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "t", Direction.OUT, "knows", null, null, null)
            .build();

    GremlinStepWalker.bindPathItemConstraints(
        ir.pattern(), Map.of(), Map.of("t", WalkerContext.VERTEX_ROOT_CLASS));

    assertThat(itemTargeting(ir.pattern(), "t").getFilter().getClassName(null)).isNull();
  }

  /** Returns the single path item whose target node is {@code alias}. */
  private static SQLMatchPathItem itemTargeting(Pattern pattern, String alias) {
    for (var node : pattern.aliasToNode.values()) {
      for (var edge : node.out) {
        if (alias.equals(edge.in.alias)) {
          return edge.item;
        }
      }
    }
    throw new AssertionError("no path item targets alias " + alias);
  }

  /** Renders a path item's {@code WHERE} as generic statement text, or "" when it carries none. */
  private static String renderWhere(SQLMatchPathItem item) {
    var where = item.getFilter().getFilter();
    if (where == null) {
      return "";
    }
    var sb = new StringBuilder();
    where.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }

  /**
   * Runs {@code body} with {@code QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT} forced to false, restoring
   * the previous value in a finally block. The non-polymorphic tests share this so each asserts
   * only its translation outcome, not the config plumbing. The restore keeps later traversals in
   * the SAME test on the default; cross-test isolation is already guaranteed by the per-method
   * database drop, not by this restore.
   */
  private void withNonPolymorphicDefault(Runnable body) {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    Assert.assertNotNull(config);
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  /** Alice knows Bob, plus an isolated vertex. No self-loops, so a child asking for one matches
   *  nothing natively. */
  private void seedOneKnowsEdgeNoSelfLoop() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Solo");
    alice.addEdge("knows", bob);
    graph.tx().commit();
  }

  /**
   * Runs {@code shape} with the translator on and again off, asserting the translated arm declined
   * and that both arms return the same rows.
   *
   * <p>A decline makes the translator-on arm <em>be</em> the native pipeline, so the multiset
   * equality compares native against native and holds whatever the fixture contains. Two things
   * stop that from making the case vacuous. The boundary count on the on arm is what the decline
   * itself is measured by, and the off arm's count is pinned at zero so a kill-switch flip that
   * never reached the traversal cannot go unnoticed — the flag defaults on. {@code
   * transparentReading} is the traversal the pre-fix walk collapsed the shape onto, with the scope
   * steps skipped, and its native answer is asserted to differ from the shape's: without that, the
   * case would pass on a fixture where the binding makes no difference and would pin nothing.
   */
  private void assertDeclinesAndMatchesNative(
      String scenario,
      Supplier<GraphTraversal<?, ?>> shape,
      Supplier<GraphTraversal<?, ?>> transparentReading) {
    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      setTranslatorFlag(true);
      var onAdmin = shape.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn =
          onAdmin.getSteps().stream().filter(AbstractMatchPlanStep.class::isInstance).count();
      var onRows = onAdmin.toList().stream().map(String::valueOf).sorted().toList();

      setTranslatorFlag(false);
      var offAdmin = shape.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff =
          offAdmin.getSteps().stream().filter(AbstractMatchPlanStep.class::isInstance).count();
      var offRows = offAdmin.toList().stream().map(String::valueOf).sorted().toList();
      var readingAdmin = transparentReading.get().asAdmin();
      readingAdmin.applyStrategies();
      var readingRows = readingAdmin.toList().stream().map(String::valueOf).sorted().toList();

      assertThat(offRows)
          .as(scenario + ": the fixture must separate the shape from its transparent reading, or "
              + "the assertions below witness nothing")
          .isNotEqualTo(readingRows);
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(boundaryOn)
          .as(scenario + ": a where child carrying a scope binding must decline the whole walk")
          .isEqualTo(0);
      assertThat(onRows)
          .as(scenario + ": translator-on and translator-off multisets must match")
          .isEqualTo(offRows);
    } finally {
      setTranslatorFlag(original);
    }
  }

  private void setTranslatorFlag(boolean enabled) {
    session
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }
}
