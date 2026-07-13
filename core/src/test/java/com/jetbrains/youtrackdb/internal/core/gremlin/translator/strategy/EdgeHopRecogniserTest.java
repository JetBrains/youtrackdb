package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link EdgeHopRecogniser}, the peek-ahead recogniser that claims the non-adjacent
 * {@code outE(L).has(edgeProp).inV()} chain and its analogues. These drive the recogniser directly
 * on the raw (un-strategised) DSL step list — which arrives as the exact
 * {@code VertexStep(outE) / HasStep / EdgeVertexStep} sequence the recogniser peeks — so each accept
 * and decline branch is pinned in isolation. End-to-end result equivalence (translator on vs off) is
 * covered by {@link EdgeTraversalEquivalenceTest}.
 *
 * <p>The recogniser is reached in production by delegation from {@link VertexStepRecogniser} on its
 * {@code returnsEdge()} branch; {@link #outEdgeFilterChain_claimedViaVertexStepDelegation} exercises
 * that real dispatch path, the rest drive {@link EdgeHopRecogniser} directly for clarity.
 */
public class EdgeHopRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_EDGE_ALIAS = "$g2m_edge_0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";

  // ---------------------------------------------------------------------------
  // Accept path.
  // ---------------------------------------------------------------------------

  /**
   * {@code outE("knows").has("w", 1).inV()} is claimed through the real {@link VertexStepRecogniser}
   * delegation: the edge is node-ized under a minted edge alias carrying the {@code has} filter, the
   * target vertex is minted under the generic {@code V} class, the boundary / RETURN re-pin to the
   * target, and the claim reports all three consumed steps (edge, has, closing hop) for the walker
   * to advance past.
   */
  @Test
  public void outEdgeFilterChain_claimedViaVertexStepDelegation() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);
    var edgeStep = stepAt(admin, 1);

    // Delegation entry point: VertexStepRecogniser routes the edge-returning VertexStep here.
    var recognized = VertexStepRecogniser.INSTANCE.recognize(edgeStep, ctx);

    assertThat(recognized).as("outE.has.inV consumes edge + has + closing hop").isEqualTo(3);
    // Boundary re-pinned to the target vertex; output still an ELEMENT / Vertex.
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.ELEMENT);
    assertThat(ctx.returnClass).isEqualTo(Vertex.class);
    // Exactly one RETURN column, keyed on the target (the start column was replaced).
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(FIRST_ANON_ALIAS);
    // The edge filter is accumulated under the minted edge alias.
    assertThat(ctx.edgeFilters).containsKey(FIRST_EDGE_ALIAS);
    // The consumed count (3) is the return value asserted above; the recogniser does not advance
    // the cursor — the walker does — so the seeded cursor is untouched.
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);

    // Three-node pattern (source → edge node → target); the target roots at the generic V class
    // with no @class filter (no subclass undercount).
    var ir = ctx.patternBuilder.build();
    assertThat(ir.pattern().aliasToNode)
        .containsOnlyKeys(BOUNDARY_ALIAS, FIRST_EDGE_ALIAS, FIRST_ANON_ALIAS);
    assertThat(ir.aliasClasses()).containsEntry(FIRST_ANON_ALIAS, "V");
    assertThat(ir.aliasFilters())
        .as("the target vertex carries no @class filter")
        .doesNotContainKey(FIRST_ANON_ALIAS);
  }

  /**
   * The {@code inE("knows").has("w", 1).outV()} analogue is claimed, exercising the {@code IN} edge
   * direction with an {@code outV} close. Light assertions — the detailed mutation shape is pinned by
   * the {@code outE} case above; this covers the direction branch.
   */
  @Test
  public void inEdgeFilterChain_isClaimed() {
    var admin = graph.traversal().V().inE("knows").has("w", 1).outV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("inE.has.outV consumes edge + has + closing hop").isEqualTo(3);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);
  }

  /**
   * An unfiltered edge chain ({@code outE("knows").inV()} with no {@code has}) is claimed with no
   * edge filter — the branch reachable when a barrier (not a has) blocked the adjacency fold. The
   * edge-filter map stays empty and only two steps (edge + closing hop) are consumed.
   */
  @Test
  public void unfilteredEdgeChain_isClaimedWithNoEdgeFilter() {
    var admin = graph.traversal().V().outE("knows").inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("outE.inV (no has) consumes edge + closing hop").isEqualTo(2);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.edgeFilters).as("an unfiltered edge accumulates no filter").isEmpty();
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);
  }

  /**
   * Two {@code has(...)} calls AND-merge into a single edge filter recorded under the edge alias.
   * TinkerPop folds consecutive {@code has} calls into one {@link
   * org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep} carrying two
   * {@code HasContainer}s, so {@code outE("knows").has("weight", 1).has("since", 2010).inV()} arrives
   * as edge / has / closing — three inner steps — and the recogniser AND-merges both containers.
   */
  @Test
  public void multipleHasSteps_andMergeIntoOneEdgeFilter() {
    var admin =
        graph.traversal().V().outE("knows").has("weight", 1).has("since", 2010).inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized)
        .as("two has containers AND-merged into one filter; edge + has + closing consumed")
        .isEqualTo(3);
    assertThat(ctx.edgeFilters).containsKey(FIRST_EDGE_ALIAS);
    // Assert both containers of the single folded HasStep survive the AND-merge, not just that a
    // filter was recorded under the edge alias. Render the merged clause and check both field
    // names: a merge bug that dropped the second container (e.g. a break after the first) would
    // still pass the key-presence check above but fail here.
    var where = new StringBuilder();
    ctx.edgeFilters.get(FIRST_EDGE_ALIAS).toGenericStatement(where);
    assertThat(where.toString())
        .as("both containers of the folded HasStep AND-merge into the edge WHERE")
        .contains("weight")
        .contains("since");
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);
  }

  /**
   * A {@link NoOpBarrierStep} interleaved between the has step and the closing hop is skipped (not
   * consumed as a filter) — the recogniser's belt-and-suspenders barrier-skip. The barrier is
   * inserted manually because {@code LazyBarrierStrategy}'s {@code returnsEdge()} carve-out keeps a
   * real barrier out of this window; the recogniser must still tolerate one. All four inner steps
   * (edge, has, barrier, closing) are consumed.
   */
  @Test
  public void interleavedBarrier_isSkipped() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    // Insert a barrier between the has step (index 2) and the closing EdgeVertexStep (index 3).
    admin.addStep(3, new NoOpBarrierStep<>(admin));
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("barrier skipped; edge + has + barrier + closing consumed")
        .isEqualTo(4);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);
  }

  /**
   * Two <em>separate</em> {@link HasStep} instances between the edge and its close both AND-merge
   * into the one edge filter, and every predicate survives. Consecutive {@code has(...)} calls
   * normally fold into a single {@code HasStep} carrying multiple {@code HasContainer}s — the shape
   * {@link #multipleHasSteps_andMergeIntoOneEdgeFilter} pins, which drives the inner container loop. A
   * second distinct {@code HasStep} arrives only when something broke that fold, and a {@link
   * NoOpBarrierStep} between the two {@code has} calls is the realistic cause. This drives the outer
   * peek-ahead loop across two distinct {@code HasStep} objects (append, skip barrier, append) — a
   * path the folded single-step cases never reach — and asserts both the {@code weight} and the
   * {@code since} predicate land in the merged {@code WHERE}. A dropped predicate would still pass the
   * edge-alias key-presence check but fail the clause-content assertion here. Edge, has, barrier, has,
   * and the closing hop are all consumed (5).
   */
  @Test
  public void twoSeparateHasSteps_andMergeAcrossBarrier() {
    var admin = graph.traversal().V().outE("knows").has("weight", 5).inV().asAdmin();
    // Build the anti-fold shape by hand: outE / has(weight) / barrier / has(since) / inV. The closing
    // EdgeVertexStep starts at index 3; inserting the second HasStep then the barrier at index 3 each
    // time leaves two distinct HasStep instances separated by a barrier, the shape a broken adjacency
    // fold produces.
    admin.addStep(3, new HasStep<>(admin, new HasContainer("since", P.eq(2010))));
    admin.addStep(3, new NoOpBarrierStep<>(admin));
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized)
        .as("edge + has + barrier + has + closing hop all consumed")
        .isEqualTo(5);
    assertThat(ctx.edgeFilters).containsKey(FIRST_EDGE_ALIAS);
    // Both predicates from the two separate has steps survive the AND-merge into one edge WHERE.
    var where = new StringBuilder();
    ctx.edgeFilters.get(FIRST_EDGE_ALIAS).toGenericStatement(where);
    assertThat(where.toString())
        .as("both separate has() predicates AND-merge into the edge filter")
        .contains("weight")
        .contains("since");
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // Decline paths — every decline leaves the context unmutated.
  // ---------------------------------------------------------------------------

  /**
   * The {@code both} edge chain closes on an {@code EdgeOtherVertexStep} ({@code otherV}), for which
   * the MATCH executor has no method, so {@code bothE("knows").has("w", 1).otherV()} declines and
   * stays on the native pipeline.
   */
  @Test
  public void otherVClose_declines() {
    var admin = graph.traversal().V().bothE("knows").has("w", 1).otherV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("otherV close must decline (0) — no MATCH otherV method")
        .isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * An edge-returning terminal with no closing vertex hop ({@code outE("knows")}) declines — an edge
   * result is out of scope.
   */
  @Test
  public void noClosingHop_declines() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("an edge-returning terminal must decline (0)").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * A {@code has} predicate the adapter cannot translate ({@code P.within(...)}) declines the whole
   * chain — no half-applied edge filter that would diverge from native.
   */
  @Test
  public void untranslatablePredicate_declines() {
    var admin = graph.traversal().V().outE("knows").has("w", P.within(1, 2)).inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("an untranslatable predicate must decline (0)").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * A step other than {@code HasStep} / {@code NoOpBarrierStep} between the edge and its close
   * ({@code outE("knows").dedup().inV()}) declines — the recogniser only understands the has / barrier
   * window.
   */
  @Test
  public void nonHasNonBarrierStepInWindow_declines() {
    var admin = graph.traversal().V().outE("knows").dedup().inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("a foreign step in the window must decline (0)").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * A user {@code as(...)} label on the edge step ({@code outE("knows").as("e").has("w",1).inV()})
   * declines — a named edge would need to be exposed as a result, which is out of scope.
   */
  @Test
  public void edgeStepWithAsLabel_declines() {
    var admin = graph.traversal().V().outE("knows").as("e").has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("an as(...) label on the edge must decline (0)").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /** A multi-label edge ({@code outE("knows", "likes")}) declines — multi-label is out of scope. */
  @Test
  public void multiLabelEdge_declines() {
    var admin = graph.traversal().V().outE("knows", "likes").inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("a multi-label edge must decline (0)").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * A label-less edge chain ({@code outE().has("w", 1).inV()}, all edge types) is claimed, not
   * declined: {@code getEdgeLabels()} is empty (length 0), which the guard now accepts, passing a null
   * edge label to the edge-as-node builder (rendered as the all-types bare {@code outE(){...}} form).
   * The edge filter still accumulates under the minted edge alias, and the chain consumes edge + has +
   * closing hop. End-to-end multiset equivalence with native {@code outE().has(...).inV()} is pinned
   * by {@link EdgeTraversalEquivalenceTest}.
   */
  @Test
  public void labelLessEdge_isClaimed() {
    var admin = graph.traversal().V().outE().has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("a label-less edge chain consumes edge + has + closing hop")
        .isEqualTo(3);
    assertThat(ctx.edgeFilters).containsKey(FIRST_EDGE_ALIAS);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);
  }

  /**
   * A non-{@link org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep} (defence in
   * depth against a direct mis-call) declines cleanly rather than throwing.
   */
  @Test
  public void nonVertexStep_declines() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary(admin);
    var graphStep = stepAt(admin, 0); // the GraphStep, not an edge VertexStep

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(graphStep, ctx);

    assertThat(recognized).as("a non-VertexStep must decline (0), not throw").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * An edge step reaching the recogniser with no pinned boundary ({@code boundaryAlias == null})
   * declines — there is no "from" endpoint to hang the edge off.
   */
  @Test
  public void nullBoundary_declines() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    var ctx = new WalkerContext(admin, true); // boundary stays null, cursor stays 0
    ctx.stepIndex = 1;

    var recognized = EdgeHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("an edge step with no pinned boundary must decline (0)").isEqualTo(0);
    assertThat(ctx.boundaryAlias).isNull();
    assertThat(ctx.edgeFilters).isEmpty();
    assertThat(ctx.nextEdgeAlias())
        .as("no edge alias minted on decline")
        .isEqualTo(FIRST_EDGE_ALIAS);
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Builds a context pre-seeded as the start-step recogniser would leave it: a pinned {@code $g2m_v0}
   * boundary with one RETURN column keyed on that alias, cursor positioned at the edge step. A
   * claimed chain must replace the column with one keyed on its target; a declined chain must leave
   * everything as seeded.
   */
  private static WalkerContext contextWithStartBoundary(Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(admin, true);
    ctx.patternBuilder.addNode(BOUNDARY_ALIAS, "V", null, false);
    ctx.boundaryAlias = BOUNDARY_ALIAS;
    ctx.outputType = BoundaryOutputType.ELEMENT;
    ctx.returnClass = Vertex.class;
    ctx.returnItems.add(new SQLExpression(new SQLIdentifier(BOUNDARY_ALIAS)));
    ctx.returnAliases.add(new SQLIdentifier(BOUNDARY_ALIAS));
    ctx.returnNestedProjections.add(null);
    ctx.stepIndex = 1; // positioned at the edge step, after the start step
    return ctx;
  }

  /**
   * No-mutation-on-decline: a declining recogniser must leave the context exactly as
   * {@link #contextWithStartBoundary} seeded it — the start boundary / RETURN intact, no target or
   * edge node, no alias minted, cursor unmoved.
   *
   * <p>The final two assertions are a destructive mint-probe: {@code nextEdgeAlias()} and {@code
   * nextAnonVertexAlias()} advance the per-context alias counters, so calling this helper is itself
   * a mutation. It is sound only because each decline test calls it exactly once, as its last
   * statement — do not call it mid-test and then drive more recogniser calls, or the alias sequence
   * starts at {@code _1} and a later real mint fails an unrelated equality check.
   */
  private static void assertContextUnmutated(WalkerContext ctx) {
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.stepIndex).as("cursor must not advance on decline").isEqualTo(1);
    assertThat(ctx.edgeFilters).as("no edge filter accumulated on decline").isEmpty();
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS))
        .as("no target node added on decline")
        .isFalse();
    assertThat(ctx.patternBuilder.hasAlias(FIRST_EDGE_ALIAS))
        .as("no edge node added on decline")
        .isFalse();
    assertThat(ctx.nextEdgeAlias()).as("no edge alias minted on decline").isEqualTo(
        FIRST_EDGE_ALIAS);
    assertThat(ctx.nextAnonVertexAlias())
        .as("no anonymous vertex alias minted on decline")
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  private static Step<?, ?> stepAt(Traversal.Admin<?, ?> admin, int index) {
    return admin.getSteps().get(index);
  }
}
