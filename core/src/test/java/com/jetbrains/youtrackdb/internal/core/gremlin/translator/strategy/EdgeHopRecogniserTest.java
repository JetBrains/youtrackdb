package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link EdgeHopRecogniser}, the recogniser that claims the non-adjacent {@code
 * outE(L).has(edgeProp).inV()} chain and its analogues. Each test drives the recogniser directly on a
 * {@link StepStreamCursor} over the raw (un-strategised) DSL step list — which arrives as the exact
 * {@code VertexStep(outE) / HasStep / EdgeVertexStep} sequence the recogniser reads through the cursor
 * — so each accept and decline branch is pinned in isolation. End-to-end result equivalence
 * (translator on vs off) is covered by {@link EdgeTraversalEquivalenceTest}.
 *
 * <p>The recogniser is reached in production by delegation from {@link VertexStepRecogniser} on its
 * {@code returnsEdge()} branch; {@link #outEdgeFilterChain_claimedViaVertexStepDelegation} exercises
 * that real dispatch path, the rest drive {@link EdgeHopRecogniser} directly for clarity. Consumption
 * is read as a cursor-position delta.
 */
public class EdgeHopRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_EDGE_ALIAS = "$g2m_edge_0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";

  /** The cursor's transparent set, mirroring the walker's production configuration. */
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  // ---------------------------------------------------------------------------
  // Accept path.
  // ---------------------------------------------------------------------------

  /**
   * {@code outE("knows").has("w", 1).inV()} is claimed through the real {@link VertexStepRecogniser}
   * delegation: the edge is node-ized under a minted edge alias carrying the {@code has} filter, the
   * target vertex is minted under the generic {@code V} class, the boundary / RETURN re-pin to the
   * target, and the claim consumes all three steps (edge, has, closing hop).
   */
  @Test
  public void outEdgeFilterChain_claimedViaVertexStepDelegation() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    // Delegation entry point: VertexStepRecogniser routes the edge-returning VertexStep here.
    var outcome = VertexStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("outE.has.inV is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
        .as("outE.has.inV consumes edge + has + closing hop")
        .isEqualTo(3);
    // Boundary re-pinned to the target vertex; output still an ELEMENT / Vertex.
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.ELEMENT);
    assertThat(ctx.returnClass).isEqualTo(Vertex.class);
    // Exactly one RETURN column, keyed on the target (the start column was replaced).
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(FIRST_ANON_ALIAS);
    // The edge filter is accumulated under the minted edge alias.
    assertThat(ctx.edgeFilters).containsKey(FIRST_EDGE_ALIAS);

    // Three-node pattern (source → edge node → target); the target roots at the generic V class with
    // no @class filter (no subclass undercount).
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
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("inE.has.outV is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
        .as("inE.has.outV consumes edge + has + closing hop")
        .isEqualTo(3);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * An unfiltered edge chain ({@code outE("knows").inV()} with no {@code has}) is claimed with no edge
   * filter — the branch reachable when a barrier (not a has) blocked the adjacency fold. The
   * edge-filter map stays empty and only two steps (edge + closing hop) are consumed.
   */
  @Test
  public void unfilteredEdgeChain_isClaimedWithNoEdgeFilter() {
    var admin = graph.traversal().V().outE("knows").inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("outE.inV (no has) is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
        .as("outE.inV (no has) consumes edge + closing hop")
        .isEqualTo(2);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.edgeFilters).as("an unfiltered edge accumulates no filter").isEmpty();
  }

  /**
   * Two {@code has(...)} calls AND-merge into a single edge filter recorded under the edge alias.
   * TinkerPop folds consecutive {@code has} calls into one {@link HasStep} carrying two {@code
   * HasContainer}s, so {@code outE("knows").has("weight", 1).has("since", 2010).inV()} arrives as edge
   * / has / closing — three inner steps — and the recogniser AND-merges both containers.
   */
  @Test
  public void multipleHasSteps_andMergeIntoOneEdgeFilter() {
    var admin =
        graph.traversal().V().outE("knows").has("weight", 1).has("since", 2010).inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("the folded has step is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
        .as("two has containers AND-merged into one filter; edge + has + closing consumed")
        .isEqualTo(3);
    assertThat(ctx.edgeFilters).containsKey(FIRST_EDGE_ALIAS);
    // Assert both containers of the single folded HasStep survive the AND-merge. Render the merged
    // clause and check both field names: a merge bug that dropped the second container would still pass
    // the key-presence check above but fail here.
    var where = new StringBuilder();
    ctx.edgeFilters.get(FIRST_EDGE_ALIAS).toGenericStatement(where);
    assertThat(where.toString())
        .as("both containers of the folded HasStep AND-merge into the edge WHERE")
        .contains("weight")
        .contains("since");
  }

  /**
   * A {@link NoOpBarrierStep} interleaved between the has step and the closing hop is skipped by the
   * cursor (not consumed as a filter). The barrier is inserted manually because {@code
   * LazyBarrierStrategy}'s {@code returnsEdge()} carve-out keeps a real barrier out of this window; the
   * cursor must still skip one. All four inner steps (edge, has, barrier, closing) are consumed.
   */
  @Test
  public void interleavedBarrier_isSkipped() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    // Insert a barrier between the has step (index 2) and the closing EdgeVertexStep (index 3).
    admin.addStep(3, new NoOpBarrierStep<>(admin));
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("the barrier is skipped and the chain is accepted")
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
        .as("barrier skipped; edge + has + barrier + closing consumed")
        .isEqualTo(4);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * Two <em>separate</em> {@link HasStep} instances between the edge and its close both AND-merge into
   * the one edge filter, and every predicate survives. Consecutive {@code has(...)} calls normally fold
   * into a single {@code HasStep} carrying multiple {@code HasContainer}s — the shape {@link
   * #multipleHasSteps_andMergeIntoOneEdgeFilter} pins, which drives the inner container loop. A second
   * distinct {@code HasStep} arrives only when something broke that fold, and a {@link NoOpBarrierStep}
   * between the two {@code has} calls is the realistic cause. This drives the outer has-run across two
   * distinct {@code HasStep} objects with a barrier skipped between them, and asserts both the {@code
   * weight} and the {@code since} predicate land in the merged {@code WHERE}. Edge, has, barrier, has,
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
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("both separate has steps AND-merge and the chain is accepted")
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
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
  }

  // ---------------------------------------------------------------------------
  // Decline paths — a decline discards the whole walk, so the recogniser
  // contributes nothing before declining.
  // ---------------------------------------------------------------------------

  /**
   * The {@code both} edge chain closes on an {@code EdgeOtherVertexStep} ({@code otherV}), a distinct
   * exact class the {@code takeIf(EdgeVertexStep.class)} matcher rejects and for which the MATCH
   * executor has no method, so {@code bothE("knows").has("w", 1).otherV()} declines and stays on the
   * native pipeline.
   */
  @Test
  public void otherVClose_declines() {
    var admin = graph.traversal().V().bothE("knows").has("w", 1).otherV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("otherV close must decline — no MATCH otherV method")
        .isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * An edge-returning terminal with no closing vertex hop ({@code outE("knows")}) declines — an edge
   * result is out of scope.
   */
  @Test
  public void noClosingHop_declines() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("an edge-returning terminal must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * A {@code has} predicate the adapter cannot translate ({@code P.within(...)}) declines the whole
   * chain — no half-applied edge filter that would diverge from native.
   */
  @Test
  public void untranslatablePredicate_declines() {
    var admin = graph.traversal().V().outE("knows").has("w", P.within(1, 2)).inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("an untranslatable predicate must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * A step other than {@code HasStep} / {@code NoOpBarrierStep} between the edge and its close ({@code
   * outE("knows").dedup().inV()}) declines — the has-run stops at {@code dedup}, and the closing matcher
   * finds no {@code EdgeVertexStep} at the head.
   */
  @Test
  public void nonHasNonBarrierStepInWindow_declines() {
    var admin = graph.traversal().V().outE("knows").dedup().inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a foreign step in the window must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * A user {@code as(...)} label on the edge step ({@code outE("knows").as("e").has("w",1).inV()})
   * declines — a named edge would need to be exposed as a result, which is out of scope.
   */
  @Test
  public void edgeStepWithAsLabel_declines() {
    var admin = graph.traversal().V().outE("knows").as("e").has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("an as(...) label on the edge must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /** A multi-label edge ({@code outE("knows", "likes")}) declines — multi-label is out of scope. */
  @Test
  public void multiLabelEdge_declines() {
    var admin = graph.traversal().V().outE("knows", "likes").inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a multi-label edge must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * A label-less edge chain ({@code outE().has("w", 1).inV()}, all edge types) is claimed, not
   * declined: {@code getEdgeLabels()} is empty (length 0), which the guard accepts, passing a null edge
   * label to the edge-as-node builder (rendered as the all-types bare {@code outE(){...}} form). The
   * edge filter still accumulates under the minted edge alias, and the chain consumes edge + has +
   * closing hop.
   */
  @Test
  public void labelLessEdge_isClaimed() {
    var admin = graph.traversal().V().outE().has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a label-less edge chain is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
        .as("a label-less edge chain consumes edge + has + closing hop")
        .isEqualTo(3);
    assertThat(ctx.edgeFilters).containsKey(FIRST_EDGE_ALIAS);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * A non-{@code VertexStep} (defence in depth against a direct mis-call) declines cleanly rather than
   * throwing. The head is the start {@code GraphStep} at cursor position 0.
   */
  @Test
  public void nonVertexStep_declines() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    var ctx = contextWithStartBoundary();
    // Fresh cursor: the head is the start GraphStep, not an edge VertexStep.
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a non-VertexStep must decline, not throw").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * An edge step reaching the recogniser with no pinned boundary ({@code boundaryAlias() == null})
   * declines — there is no "from" endpoint to hang the edge off.
   */
  @Test
  public void nullBoundary_declines() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    var ctx = new WalkerContext(true, false); // boundary stays null
    var cursor = cursorAfterStart(admin);

    var outcome = EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("an edge step with no pinned boundary must decline")
        .isEqualTo(Outcome.DECLINE);
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
   * boundary with one RETURN column keyed on that alias. A claimed chain must replace the column with
   * one keyed on its target; a declined chain must leave everything as seeded.
   */
  private static WalkerContext contextWithStartBoundary() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  /** A cursor over the raw step list positioned at the edge step — the start GraphStep is consumed. */
  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take(); // consume the start GraphStep, leaving the head at the edge step
    return cursor;
  }

  /**
   * A declining edge recogniser contributes nothing before declining (it validates before it
   * contributes): the seeded start boundary / RETURN is intact, no edge filter accumulated, and no
   * target or edge node or alias was minted. A decline would discard the whole walk anyway, so this
   * pins the contribute-last shape rather than a required rollback.
   *
   * <p>The final two assertions are a destructive mint-probe: {@code nextEdgeAlias()} and {@code
   * nextAnonVertexAlias()} advance the per-context alias counters, so calling this helper is itself a
   * mutation. Call it exactly once, as the last statement.
   */
  private static void assertContributedNothing(WalkerContext ctx) {
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.edgeFilters).as("no edge filter accumulated on decline").isEmpty();
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS))
        .as("no target node added on decline")
        .isFalse();
    assertThat(ctx.patternBuilder.hasAlias(FIRST_EDGE_ALIAS))
        .as("no edge node added on decline")
        .isFalse();
    assertThat(ctx.nextEdgeAlias()).as("no edge alias minted on decline")
        .isEqualTo(FIRST_EDGE_ALIAS);
    assertThat(ctx.nextAnonVertexAlias())
        .as("no anonymous vertex alias minted on decline")
        .isEqualTo(FIRST_ANON_ALIAS);
  }
}
