package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Optional;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
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

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isPresent();
    var translation = result.get();
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

    assertThat(result).isPresent();
    var inputs = result.get().inputs();
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

    assertThat(result).isPresent();
    var inputs = result.get().inputs();
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

    assertThat(result).as("plain GraphStep start must be recognized, not declined").isPresent();
  }

  /**
   * Class-keyed dispatch fails safe on an unexpected subclass. If the strategy ordering ever
   * changed so the translator ran after {@code YTDBGraphStepStrategy} folded the plain
   * {@code GraphStep} into a {@code YTDBGraphStep}, the walker would see a step whose runtime
   * class ({@code YTDBGraphStep}) has no registry entry — {@code map.get(YTDBGraphStep.class)}
   * returns {@code null} — so it declines the whole traversal rather than misrouting the
   * unexpected subclass through the {@code GraphStep} recogniser (D9). Under D4 this never
   * happens: the translator runs first and sees the plain {@code GraphStep}. The decline is the
   * safe default for a shape we did not expect.
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
        .isEmpty();
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

    assertThat(result).as("a step past the vertex source declines the whole walk").isEmpty();
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

    assertThat(result).as("a multi-step traversal declines under the size-1 gate").isEmpty();
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

    assertThat(result).isEmpty();
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

    assertThat(result).as("an unconvertible ID declines the whole walk").isEmpty();
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

    assertThat(result).as("a blank RID string declines the whole walk").isEmpty();
  }

  /**
   * Non-polymorphic mode does NOT narrow a bare {@code g.V()} by class. Native non-polymorphic
   * {@code g.V()} still returns the full polymorphic vertex set: the no-id branch of
   * {@code YTDBGraphImplAbstract.elements} browses the class polymorphically regardless of the
   * flag, and the by-id branch applies no class filter at all. Emitting {@code @class = 'V'}
   * would exclude every subclass instance the native path keeps, so under
   * {@code QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT = false} the recogniser must emit no filter on
   * the boundary alias for a bare {@code g.V()}. The flag is restored in a finally block so later
   * traversals in this same test see the default; cross-test isolation is already guaranteed by
   * the per-method database drop, not by this restore.
   */
  @Test
  public void walk_nonPolymorphicBareVertexSource_emitsNoClassFilter() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
    try {
      var admin = graph.traversal().V().asAdmin();

      var result = GremlinStepWalker.production().walk(admin);

      assertThat(result).isPresent();
      // No @class narrowing: a bare g.V() carries no boundary-alias filter even under non-poly.
      assertThat(result.get().inputs().aliasFilters())
          .as("non-poly bare g.V() must not narrow by @class")
          .doesNotContainKey(BOUNDARY_ALIAS);
      // The single-node polymorphic V-class scan is still pinned via aliasClasses.
      assertThat(result.get().inputs().aliasClasses()).containsEntry(BOUNDARY_ALIAS, "V");
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
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
        .isEmpty();
  }
}
