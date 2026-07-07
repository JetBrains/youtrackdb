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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
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
 *       {@code $g2m_v0} pattern, with the RID landing on {@code aliasRids} (single ID) or an
 *       {@code @rid IN [...]} filter (multi ID).
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
    assertThat(inputs.aliasRids()).as("bare g.V() has no RID hint").doesNotContainKey(
        BOUNDARY_ALIAS);
  }

  /**
   * {@code g.V(id)} with a single RID-shaped ID lands the RID on {@code aliasRids} (the planner's
   * {@code SELECT FROM #X:Y} fast path), not on an {@code @rid IN [...]} filter. The rendered RID
   * matches the requested ID.
   */
  @Test
  public void walk_singleId_landsOnAliasRids() {
    // #25:3 is an arbitrary well-formed RID literal: the walker only renders it into MATCH SQL and
    // never dereferences it against storage, so no record with this RID need exist.
    var admin = graph.traversal().V("#25:3").asAdmin();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    var inputs = result.inputs();
    assertThat(inputs.aliasRids()).containsKey(BOUNDARY_ALIAS);
    SQLRid rid = inputs.aliasRids().get(BOUNDARY_ALIAS);
    assertThat(rid.toString()).isEqualTo("#25:3");
    // The single-ID path uses the RID hint, so no @rid IN [...] filter is emitted for the alias
    // under the default (polymorphic) mode.
    assertThat(inputs.aliasFilters()).doesNotContainKey(BOUNDARY_ALIAS);
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
    assertThat(inputs.aliasRids()).as("multi-ID uses an IN filter, not the single-RID hint")
        .doesNotContainKey(BOUNDARY_ALIAS);
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
   * The minimal-prefix (size-1) gate declines any traversal larger than the single vertex source
   * the Phase 1 registry can translate whole, without walking a single step. {@code g.V().count()}
   * has two steps; the walker's up-front size check declines it (the recognizer for the second
   * step does not exist yet, so the all-or-nothing loop would decline anyway, but the size gate is
   * the direct, greppable bound on the recognized set). The traversal's native step list is left
   * untouched.
   */
  @Test
  public void walk_multiStepTraversal_declinesUnderMinimalPrefixGate() {
    var admin = graph.traversal().V().count().asAdmin();
    var stepsBefore = List.copyOf(admin.getSteps());

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("a multi-step traversal declines under the size-1 gate").isNull();
    assertThat(admin.getSteps())
        .as("the size gate never mutates the traversal's native step list")
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
   * A traversal whose {@code getGraph()} is empty makes {@code YTDBStrategyUtil.isPolymorphic}
   * return {@code null}; the recognizer treats a null polymorphism result as a clean decline
   * rather than proceeding with an unresolved flag. This pins the null-{@code isPolymorphic}
   * decline branch. A real vertex {@code GraphStep} clears the structural gates (vertex source,
   * no ids, no hasContainers) so the recognizer reaches the polymorphism resolution; the
   * {@link WalkerContext}'s traversal is a graph-less mock so {@code isPolymorphic} returns null
   * (rather than throwing on {@code EmptyGraph.tx()}, which a real detached traversal would do —
   * that non-transactional case is filtered out by the strategy's own gates before the walker
   * ever runs).
   */
  @Test
  public void walk_nullIsPolymorphic_declines() {
    // A real vertex GraphStep to satisfy the recognizer's structural gates.
    var realAdmin = graph.traversal().V().asAdmin();
    Step<?, ?> vertexStart = realAdmin.getStartStep();

    // A graph-less traversal so YTDBStrategyUtil.isPolymorphic returns null.
    @SuppressWarnings("unchecked")
    Traversal.Admin<Object, Object> graphless = mock(Traversal.Admin.class);
    when(graphless.getGraph()).thenReturn(Optional.empty());
    var ctx = new WalkerContext(graphless);

    boolean recognized = StartStepRecogniser.INSTANCE.recognize(vertexStart, ctx);

    assertThat(recognized).as("a null isPolymorphic declines cleanly").isFalse();
    assertThat(ctx.boundaryAlias).as("no-mutation-on-decline").isNull();
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
    var ctx = new WalkerContext(admin);
    Step<?, ?> edgeStart = admin.getStartStep();

    boolean recognized = StartStepRecogniser.INSTANCE.recognize(edgeStart, ctx);

    assertThat(recognized).as("edge start is not a vertex source").isFalse();
    assertThat(ctx.patternBuilder.build().pattern().aliasToNode)
        .as("declining recognizer must add no pattern node").isEmpty();
    assertThat(ctx.aliasRids).isEmpty();
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
    var ctx = new WalkerContext(admin);
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
   * still lands on {@code aliasRids} (the {@code SELECT FROM #X:Y} fast path) with no {@code @class}
   * filter on {@code aliasFilters}, exactly as under the default mode.
   */
  @Test
  public void walk_nonPolymorphicSingleId_landsOnAliasRidsWithoutClassFilter() {
    withNonPolymorphicDefault(() -> {
      // #25:3 is an arbitrary well-formed RID literal; the walker only renders it.
      var admin = graph.traversal().V("#25:3").asAdmin();

      var result = GremlinStepWalker.production().walk(admin);

      assertThat(result).isNotNull();
      var inputs = result.inputs();
      assertThat(inputs.aliasRids()).containsKey(BOUNDARY_ALIAS);
      assertThat(inputs.aliasRids().get(BOUNDARY_ALIAS).toString()).isEqualTo("#25:3");
      // No @class filter added to the alias even under non-poly: the by-id lookup is RID-only.
      assertThat(inputs.aliasFilters())
          .as("non-poly g.V(id) must not narrow by @class")
          .doesNotContainKey(BOUNDARY_ALIAS);
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
      assertThat(inputs.aliasRids())
          .as("multi-ID uses an IN filter, not the single-RID hint")
          .doesNotContainKey(BOUNDARY_ALIAS);
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
  // the size gate; a recogniser that claims a step but leaves the boundary
  // unpinned trips the post-walk invariant assert rather than declining silently.
  // ---------------------------------------------------------------------------

  /**
   * An empty traversal (zero steps) declines up front at the size gate, before the walk loop and
   * before the boundary invariant. A step-less traversal has nothing to translate and could never
   * pin a boundary; declining it here keeps it a normal decline (a {@code null} return) rather
   * than letting it reach the post-walk invariant assert, which is reserved for a recogniser that
   * claims a step without pinning the boundary. A Mockito traversal with an empty step list drives
   * the {@code steps.isEmpty()} branch directly.
   */
  @Test
  public void walk_emptyTraversal_declinesAtSizeGate() {
    @SuppressWarnings("unchecked")
    Traversal.Admin<Object, Object> emptyTraversal = mock(Traversal.Admin.class);
    when(emptyTraversal.getSteps()).thenReturn(List.of());

    var result = GremlinStepWalker.production().walk(emptyTraversal);

    assertThat(result).as("an empty traversal declines at the size gate").isNull();
  }

  /**
   * A recogniser that claims its step (returns {@code true}) but never pins the boundary metadata
   * violates the walker's post-walk invariant: every fully-recognised non-empty traversal must
   * carry a pinned boundary. Because empty traversals are gated out earlier, reaching the invariant
   * with a null boundary can only be a recogniser-logic bug, so the walker asserts rather than
   * declining silently — a silent decline would mask the bug. Under {@code -ea} (the test/CI
   * default) the assert throws {@link AssertionError}, which {@code GremlinToMatchStrategy}'s
   * throw-safety net does NOT catch (it catches only {@code RuntimeException}), so the bug surfaces
   * loudly instead of degrading to a silent decline. This test drives a fixture registry whose
   * recogniser claims the start {@code GraphStep} without mutating the context, and asserts the
   * walk trips that invariant.
   */
  @Test
  public void walk_recogniserLeavesBoundaryUnpinned_tripsInvariantAssert() {
    // Fixture recogniser: claims every step it is handed, pins nothing on the context.
    StepRecogniser unpinning = (step, ctx) -> true;
    var walker = new GremlinStepWalker(Map.of(GraphStep.class, unpinning));
    var admin = graph.traversal().V().asAdmin();

    assertThatThrownBy(() -> walker.walk(admin))
        .as("a recognised walk that leaves the boundary unpinned trips the invariant assert")
        .isInstanceOf(AssertionError.class);
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
