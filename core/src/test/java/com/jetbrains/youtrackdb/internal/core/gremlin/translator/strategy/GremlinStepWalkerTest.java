package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
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
 *       traversal declines, and a declining recognizer leaves the {@link WalkerContext}
 *       unmutated (no-mutation-on-decline).
 *   <li><b>Multi-step walker infrastructure</b> — the index-driven loop lets a recogniser
 *       consume several steps in one claim (a fixture advances the cursor by two); a recogniser
 *       that claims a step without advancing the cursor trips the walker's guard; the
 *       reserved-{@code $} pre-flight scan declines a traversal carrying a {@code $}-prefixed
 *       user label; and {@link WalkerContext}'s anonymous-alias generator mints distinct,
 *       per-context sequences.
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
   * A traversal carrying a step past the vertex source ({@code g.V().out()}) declines: the
   * walker recognizes the start step but no recognizer claims the {@code out()} step, so under
   * all-or-nothing the whole walk declines. The native step list the caller holds is untouched
   * (the walker never mutates the traversal's steps — it only reads them).
   */
  @Test
  public void walk_unrecognizedStep_declinesWholeWalk() {
    var admin = graph.traversal().V().out().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("a step past the vertex source declines the whole walk").isNull();
    assertThat(admin.getSteps())
        .as("the walker never mutates the traversal's native step list")
        .isEqualTo(stepsBefore);
  }

  /**
   * A multi-step traversal is now walked (no up-front size gate) and declines at the first
   * unrecognized step class. {@code g.V().count()} has two steps: the walker recognizes the
   * vertex source, the claiming recogniser advances the cursor past it, and the walker then
   * dispatches the {@code CountGlobalStep} — which has no registry entry — so under all-or-nothing
   * the whole walk declines. This pins that removing the upper-bound size gate did not let a
   * multi-step traversal translate: it still declines, via the no-recogniser path rather than a
   * step-count check. The traversal's native step list is left untouched.
   */
  @Test
  public void walk_multiStepTraversal_declinesAtUnrecognizedFollowUpStep() {
    var admin = graph.traversal().V().count().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result)
        .as("a multi-step traversal declines at the unrecognized follow-up step")
        .isNull();
    assertThat(admin.getSteps())
        .as("the walk never mutates the traversal's native step list")
        .isEqualTo(stepsBefore);
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
   * No-mutation-on-decline invariant: when {@link StartStepRecogniser} declines a step (here an
   * edge start), it must leave the {@link WalkerContext} exactly as it found it — no pattern node,
   * no alias filter/RID, no boundary metadata. A later recognizer (or the walker's own boundary
   * pinning) must never inspect tainted state left by a declining recognizer.
   */
  @Test
  public void recognizer_declines_leavesContextUnmutated() {
    var admin = graph.traversal().E().asAdmin();
    var ctx = new WalkerContext(admin, true);
    Step<?, ?> edgeStart = admin.getStartStep();

    boolean recognized = StartStepRecogniser.INSTANCE.recognize(edgeStart, ctx);

    assertThat(recognized).as("edge start is not a vertex source").isFalse();
    assertThat(ctx.patternBuilder.build().pattern().aliasToNode)
        .as("declining recognizer must add no pattern node").isEmpty();
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.returnItems).isEmpty();
    assertThat(ctx.boundaryAlias).isNull();
    assertThat(ctx.outputType).isNull();
    assertThat(ctx.returnClass).isNull();
  }

  /**
   * The recognizer only accepts at step index 0: a non-zero {@code stepIndex} (a misregistered
   * registry placing the start recognizer after another step) declines even for a well-formed
   * vertex {@code GraphStep}, and leaves the context unmutated.
   */
  @Test
  public void recognizer_atNonZeroIndex_declines() {
    var admin = graph.traversal().V().asAdmin();
    var ctx = new WalkerContext(admin, true);
    ctx.stepIndex = 1;
    Step<?, ?> vertexStart = admin.getStartStep();

    boolean recognized = StartStepRecogniser.INSTANCE.recognize(vertexStart, ctx);

    assertThat(recognized).as("start recognizer only accepts at index 0").isFalse();
    assertThat(ctx.boundaryAlias).isNull();
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
  // the empty gate; a recogniser that claims a step without advancing the cursor,
  // or without pinning the boundary, trips a walker guard rather than declining
  // silently (or, for the cursor, spinning forever).
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
   * loudly instead of degrading to a silent decline. The fixture recogniser advances the cursor
   * (so it clears the walker's cursor-advance guard and reaches the post-walk boundary invariant)
   * but pins nothing on the context, isolating the boundary invariant from the cursor guard.
   */
  @Test
  public void walk_recogniserLeavesBoundaryUnpinned_tripsInvariantAssert() {
    // Fixture recogniser: claims the step and advances the cursor by one (clearing the
    // cursor-advance guard), but pins no boundary metadata — isolating the post-walk boundary
    // invariant from the in-loop cursor guard.
    StepRecogniser unpinning =
        (step, ctx) -> {
          ctx.stepIndex++;
          return true;
        };
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, unpinning));
    var admin = graph.traversal().V().asAdmin();

    assertThatThrownBy(() -> walker.walk(admin))
        .as("a recognised walk that leaves the boundary unpinned trips the invariant assert")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * A recogniser that returns {@code true} without advancing the cursor trips the walker's
   * in-loop cursor-advance guard. Under the index-driven contract the claiming recogniser owns
   * the cursor advance; a recogniser that claims a step but leaves {@code stepIndex} unchanged
   * would spin the walk loop forever, so the walker asserts the cursor moved forward by at least
   * one. Under {@code -ea} the assert throws {@link AssertionError} (not swallowed by the
   * strategy's {@code RuntimeException}-only net); under {@code -da} the walker's defensive
   * decline (asserted separately by the return value here being unreachable) keeps a live query
   * off an infinite loop. The fixture claims the start {@code GraphStep} without touching the
   * cursor, which is exactly the mis-implemented recogniser this guard defends against.
   */
  @Test
  public void walk_recogniserClaimsWithoutAdvancing_tripsCursorAssert() {
    // Fixture recogniser: returns true but never advances the cursor — the infinite-loop bug the
    // walker's cursor-advance guard catches.
    StepRecogniser nonAdvancing = (step, ctx) -> true;
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, nonAdvancing));
    var admin = graph.traversal().V().asAdmin();

    assertThatThrownBy(() -> walker.walk(admin))
        .as("a recogniser that claims a step without advancing the cursor trips the guard")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * The index-driven walker supports a multi-step claim: a recogniser may consume several steps in
   * one call by advancing the cursor past all of them, and the walker resumes dispatch at the new
   * cursor rather than re-inspecting the consumed steps. The fixture delegates to {@link
   * StartStepRecogniser} (which pins the boundary and advances the cursor by one) and then advances
   * the cursor by one more, so it claims BOTH steps of {@code g.V().out()} in a single call. The
   * {@code out()} {@code VertexStep} at index 1 is therefore never dispatched (no recogniser is
   * registered for it), yet the walk succeeds — proving the loop honours a recogniser-driven
   * multi-step advance. This is a walker-mechanic test; the fixture's claim over {@code out()} is
   * contrived (real edge-hop translation lands in a later step).
   */
  @Test
  public void walk_multiStepClaim_recogniserConsumesMultipleSteps() {
    // Fixture: reuse StartStepRecogniser's valid single-node build (advances the cursor by one and
    // pins the boundary), then consume one extra step so the claim spans both steps of g.V().out().
    StepRecogniser twoStepClaim =
        (step, ctx) -> {
          boolean recognised = StartStepRecogniser.INSTANCE.recognize(step, ctx);
          if (recognised) {
            ctx.stepIndex++;
          }
          return recognised;
        };
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, twoStepClaim));
    var admin = graph.traversal().V().out("knows").asAdmin();
    assertThat(admin.getSteps()).as("precondition: g.V().out() is a two-step traversal").hasSize(2);

    var result = walker.walk(admin);

    assertThat(result)
        .as("a recogniser that advances the cursor past both steps translates the whole traversal")
        .isNotNull();
    assertThat(result.boundaryAlias()).isEqualTo(BOUNDARY_ALIAS);
  }

  // ---------------------------------------------------------------------------
  // Reserved-prefix pre-flight scan + anonymous-alias generator — the walker
  // infrastructure this step adds ahead of the edge-hop recognisers.
  // ---------------------------------------------------------------------------

  /**
   * A user label starting with the reserved {@code $} prefix declines the whole walk. {@code
   * as("$foo")} labels the vertex {@code GraphStep} with {@code $foo}; the walker's reserved-prefix
   * pre-flight scan runs before any recogniser and declines the traversal, so the translator's
   * minted {@code $g2m_} alias namespace can never collide with a user label. Declining (not
   * throwing) keeps a pre-existing {@code as("$foo")} query on the native pipeline unchanged.
   */
  @Test
  public void walk_reservedDollarUserLabel_declines() {
    var admin = graph.traversal().V().as("$foo").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("a $-prefixed user label declines the whole walk").isNull();
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
    var admin = graph.traversal().V().asAdmin();
    var ctx = new WalkerContext(admin, true);

    assertThat(ctx.nextAnonVertexAlias()).isEqualTo("$g2m_anon_0");
    assertThat(ctx.nextAnonVertexAlias()).isEqualTo("$g2m_anon_1");
    // Independent counter: minting edge aliases does not advance the vertex counter.
    assertThat(ctx.nextEdgeAlias()).isEqualTo("$g2m_edge_0");
    assertThat(ctx.nextEdgeAlias()).isEqualTo("$g2m_edge_1");
    assertThat(ctx.nextAnonVertexAlias()).isEqualTo("$g2m_anon_2");
    assertThat(ctx.edgeFilters).as("edgeFilters starts empty until a recogniser populates it")
        .isEmpty();

    // Per-context reset: a fresh walk restarts both sequences at 0.
    var fresh = new WalkerContext(admin, true);
    assertThat(fresh.nextAnonVertexAlias()).isEqualTo("$g2m_anon_0");
    assertThat(fresh.nextEdgeAlias()).isEqualTo("$g2m_edge_0");
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
}
