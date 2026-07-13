package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link VertexHopRecogniser}, the recogniser that claims a folded bare vertex hop
 * ({@code out(L)} / {@code in(L)} / {@code both(L)}). These drive the recogniser directly with a
 * hand-built {@link WalkerContext} so each decline branch and the accept path's exact context
 * mutations are pinned in isolation; the end-to-end result equivalence is covered by {@link
 * EdgeTraversalEquivalenceTest}.
 *
 * <p>Real {@link VertexStep}s are taken from the raw DSL step list ({@code out("knows")} builds a
 * {@code VertexStep} at index 1 before any strategy runs), so no strategy application is needed. The
 * one branch a real step cannot reach — a single blank edge label — is driven with a Mockito mock.
 */
public class VertexHopRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";

  // ---------------------------------------------------------------------------
  // Accept path — a single-label bare hop appends an edge + target node, re-pins
  // the boundary to the new target, replaces the RETURN column, and reports one
  // consumed step (the walker, not the recogniser, advances the cursor).
  // ---------------------------------------------------------------------------

  /**
   * {@code out("knows")} is claimed: the recogniser appends a target node under a fresh anonymous
   * alias, re-pins the boundary metadata to that target (still {@code ELEMENT} / {@code Vertex}),
   * <em>replaces</em> the single RETURN column so it is keyed on the new target (not the old
   * boundary), attaches the generic {@code V} class to the target with <em>no</em> {@code @class}
   * filter (no subclass undercount), and reports one consumed step. The context is pre-seeded with a
   * start-step boundary and RETURN column so the replacement (not append) is observable.
   */
  @Test
  public void outHop_claimsAndRePinsBoundaryToNewTarget() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);
    var hop = stepAt(admin, 1);

    var recognized = VertexHopRecogniser.INSTANCE.recognize(hop, ctx);

    assertThat(recognized).as("a single-label out() hop consumes one folded step").isEqualTo(1);
    // Boundary re-pinned to the fresh anonymous target, output still an ELEMENT / Vertex.
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.outputType).isEqualTo(BoundaryOutputType.ELEMENT);
    assertThat(ctx.returnClass).isEqualTo(Vertex.class);
    // Exactly one RETURN column, keyed on the new target — the start column was replaced, not
    // appended to.
    assertThat(ctx.returnItems).hasSize(1);
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnNestedProjections).hasSize(1);
    assertThat(ctx.returnNestedProjections.getFirst())
        .as("a bare hop has no nested projection")
        .isNull();
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(ctx.returnItems.getFirst().toString()).contains(FIRST_ANON_ALIAS);
    // The consumed count is the return value (asserted above); the recogniser does not advance the
    // cursor — the walker does — so the seeded cursor is left untouched.
    assertThat(ctx.stepIndex).as("the recogniser leaves the cursor for the walker").isEqualTo(1);

    // The pattern carries the start node, the new target node under class V, and no @class filter on
    // the target (the no-narrowing rule — a bare hop target roots at V polymorphically).
    var ir = ctx.patternBuilder.build();
    assertThat(ir.pattern().aliasToNode).containsOnlyKeys(BOUNDARY_ALIAS, FIRST_ANON_ALIAS);
    assertThat(ir.aliasClasses()).containsEntry(FIRST_ANON_ALIAS, "V");
    assertThat(ir.aliasFilters())
        .as("a bare hop target carries no @class filter (no subclass undercount)")
        .doesNotContainKey(FIRST_ANON_ALIAS);
  }

  /**
   * {@code in("knows")} is claimed and re-pins the boundary, exercising the {@code IN} direction arm
   * of the direction mapping. Light assertions — the detailed mutation shape is pinned by the
   * {@code out} case above; this covers the direction branch.
   */
  @Test
  public void inHop_isClaimed() {
    var admin = graph.traversal().V().in("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = VertexHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("a single-label in() hop consumes one folded step").isEqualTo(1);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * {@code both("knows")} is claimed, exercising the {@code BOTH} direction arm of the direction
   * mapping.
   */
  @Test
  public void bothHop_isClaimed() {
    var admin = graph.traversal().V().both("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = VertexHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("a single-label both() hop consumes one folded step").isEqualTo(1);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * A label-less hop {@code out()} (all edge types) is claimed, not declined: {@code getEdgeLabels()}
   * is empty (length 0), which the guard now accepts, passing a null edge label to the assembler (the
   * IR renders it as the all-edges {@code out('E')} form). The claim mirrors the single-label case —
   * one folded step consumed, boundary re-pinned to the fresh target, target under the generic {@code
   * V} class with no {@code @class} filter (the same no-narrowing rule). End-to-end multiset
   * equivalence with native {@code out()} is pinned by {@link EdgeTraversalEquivalenceTest}.
   */
  @Test
  public void labelLessHop_isClaimedAsAllEdges() {
    var admin = graph.traversal().V().out().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = VertexHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("a label-less hop consumes one folded step (all edge types)")
        .isEqualTo(1);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    var ir = ctx.patternBuilder.build();
    assertThat(ir.pattern().aliasToNode).containsOnlyKeys(BOUNDARY_ALIAS, FIRST_ANON_ALIAS);
    assertThat(ir.aliasClasses()).containsEntry(FIRST_ANON_ALIAS, "V");
    assertThat(ir.aliasFilters())
        .as("a label-less hop target carries no @class filter (the same no-narrowing rule)")
        .doesNotContainKey(FIRST_ANON_ALIAS);
  }

  // ---------------------------------------------------------------------------
  // Decline paths — every decline leaves the context unmutated (the walker's
  // no-mutation-on-decline contract), which the shared assertion below verifies.
  // ---------------------------------------------------------------------------

  /**
   * A non-{@link VertexStep} declines cleanly with {@code 0} rather than throwing {@code
   * ClassCastException} (guards BG1). {@link VertexHopRecogniser} is reached only by delegation from
   * {@link VertexStepRecogniser}'s {@code returnsEdge() == false} branch, but it re-asserts its own
   * {@code instanceof VertexStep} precondition so a direct mis-call declines cleanly — contract
   * parity with {@link EdgeHopRecogniser}. The start {@code GraphStep} at index 0 is fed to the
   * recogniser directly.
   */
  @Test
  public void nonVertexStep_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);
    var graphStep = stepAt(admin, 0); // the GraphStep, not a VertexStep

    var recognized = VertexHopRecogniser.INSTANCE.recognize(graphStep, ctx);

    assertThat(recognized).as("a non-VertexStep must decline (0), not throw").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * An edge-returning {@link VertexStep} ({@code returnsEdge() == true}, e.g. {@code outE("knows")})
   * declines cleanly with {@code 0}: that step belongs to {@link EdgeHopRecogniser}, so a direct
   * mis-call here must not mis-translate it as a bare hop. Mirrors {@link
   * EdgeHopRecogniserTest}'s wrong-kind decline (a non-edge-returning step handed to {@code
   * EdgeHopRecogniser}). The router never sends an edge-returning step here, so this exercises the
   * defensive {@code returnsEdge()} half of the precondition; the context is left unmutated.
   */
  @Test
  public void edgeReturningVertexStep_declines() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);
    var edgeStep = stepAt(admin, 1);
    assertThat(((VertexStep<?>) edgeStep).returnsEdge())
        .as("precondition: outE(...) is an edge-returning VertexStep")
        .isTrue();

    var recognized = VertexHopRecogniser.INSTANCE.recognize(edgeStep, ctx);

    assertThat(recognized)
        .as("an edge-returning VertexStep must decline (0) — it belongs to EdgeHopRecogniser")
        .isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * A hop reaching the recogniser with no pinned boundary ({@code boundaryAlias == null}) declines:
   * there is no "from" endpoint to hang the edge off. This models a {@code VertexStep} arriving
   * before any node was pinned — a defensive decline rather than a dangling edge.
   */
  @Test
  public void nullBoundary_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = new WalkerContext(admin, true); // boundaryAlias stays null, cursor stays 0
    var hop = stepAt(admin, 1);

    var recognized = VertexHopRecogniser.INSTANCE.recognize(hop, ctx);

    assertThat(recognized).as("a hop with no pinned boundary must decline (0)").isEqualTo(0);
    // The bare context is left exactly as constructed: no boundary, cursor unmoved, nothing minted.
    assertThat(ctx.boundaryAlias).isNull();
    assertThat(ctx.stepIndex).isEqualTo(0);
    assertThat(ctx.returnItems).isEmpty();
    assertThat(ctx.nextAnonVertexAlias())
        .as("no anonymous alias minted on decline")
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * Two sequential hops chain: the second hop's edge starts from the first hop's target, and the
   * boundary/RETURN follow to the final target. Driving the recogniser twice on a shared context
   * (mirroring the walker's index-driven loop, but without the {@code NoOpBarrierStep} a real
   * multi-hop traversal interleaves) pins the chaining: hop 1 re-pins to {@code $g2m_anon_0}, hop 2
   * hangs its edge off {@code $g2m_anon_0} and re-pins to {@code $g2m_anon_1}, leaving a three-node
   * pattern with the final target as the single result.
   */
  @Test
  public void twoSequentialHops_chainOffPreviousTarget() {
    var admin = graph.traversal().V().out("knows").out("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);

    assertThat(VertexHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx))
        .as("first hop consumes one folded step").isEqualTo(1);
    assertThat(ctx.boundaryAlias).as("first hop re-pins to the first anon target").isEqualTo(
        FIRST_ANON_ALIAS);

    assertThat(VertexHopRecogniser.INSTANCE.recognize(stepAt(admin, 2), ctx))
        .as("second hop consumes one folded step").isEqualTo(1);
    assertThat(ctx.boundaryAlias).as("second hop re-pins to the second anon target").isEqualTo(
        "$g2m_anon_1");
    // Each hop reports one consumed step (asserted above); a bare hop reads no cursor, so driving
    // the recogniser directly twice chains via the re-pinned boundary while the seeded cursor —
    // which the walker, not the recogniser, would advance — stays put.
    assertThat(ctx.stepIndex).as("recognisers leave the cursor for the walker").isEqualTo(1);

    // Exactly one RETURN column, keyed on the final target — the chain leaves one result column.
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo("$g2m_anon_1");

    // A three-node chain pattern (start → anon_0 → anon_1), each rooted at the generic V class.
    var ir = ctx.patternBuilder.build();
    assertThat(ir.pattern().aliasToNode)
        .containsOnlyKeys(BOUNDARY_ALIAS, FIRST_ANON_ALIAS, "$g2m_anon_1");
    assertThat(ir.aliasClasses())
        .containsEntry(FIRST_ANON_ALIAS, "V")
        .containsEntry("$g2m_anon_1", "V");
  }

  /**
   * A multi-label hop {@code out("knows", "likes")} declines — multi-label edge traversal is out of
   * scope for Phase 1. {@code getEdgeLabels()} has length 2, so the {@code length > 1} guard declines.
   */
  @Test
  public void multiLabelHop_declines() {
    var admin = graph.traversal().V().out("knows", "likes").asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = VertexHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized).as("a multi-label hop must decline (0)").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * A single blank edge label declines via the {@code isBlank()} guard — the branch a real DSL hop
   * cannot reach (the DSL rejects a blank label at construction), so it is driven with a mock that
   * reports one blank label. Without the guard a blank label would build a degenerate unlabelled
   * MATCH edge; the recogniser declines instead.
   */
  @Test
  public void blankSingleLabel_declines() {
    var admin = graph.traversal().V().asAdmin();
    var ctx = contextWithStartBoundary(admin);
    @SuppressWarnings("unchecked")
    VertexStep<Vertex> blankLabelHop = mock(VertexStep.class);
    when(blankLabelHop.returnsEdge()).thenReturn(false);
    when(blankLabelHop.getEdgeLabels()).thenReturn(new String[] {"  "});

    var recognized = VertexHopRecogniser.INSTANCE.recognize(blankLabelHop, ctx);

    assertThat(recognized).as("a single blank edge label must decline (0)").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  /**
   * A label-less hop under {@link EdgeLabelVerificationStrategy} declines, even though a label-less
   * hop normally translates ({@link #labelLessHop_isClaimedAsAllEdges}). That opt-in strategy exists
   * to reject a label-less edge traversal; translating the hop into a boundary step would remove it
   * before the verification runs and swallow the error. Declining leaves the native {@code
   * VertexStep} for the strategy to reject — the recogniser's part of that contract.
   */
  @Test
  public void labelLessHop_underEdgeLabelVerificationStrategy_declines() {
    var admin =
        graph.traversal()
            .withStrategies(EdgeLabelVerificationStrategy.build().create())
            .V().out().asAdmin();
    var ctx = contextWithStartBoundary(admin);

    var recognized = VertexHopRecogniser.INSTANCE.recognize(stepAt(admin, 1), ctx);

    assertThat(recognized)
        .as("a label-less hop must decline when EdgeLabelVerificationStrategy is active")
        .isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Builds a context pre-seeded as the start-step recogniser would leave it: a pinned {@code $g2m_v0}
   * boundary with one RETURN column keyed on that alias. A successful hop must replace this column
   * with one keyed on its new target; a declined hop must leave it exactly as seeded.
   */
  private static WalkerContext contextWithStartBoundary(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin) {
    var ctx = new WalkerContext(admin, true);
    ctx.patternBuilder.addNode(BOUNDARY_ALIAS, "V", null, false);
    ctx.boundaryAlias = BOUNDARY_ALIAS;
    ctx.outputType = BoundaryOutputType.ELEMENT;
    ctx.returnClass = Vertex.class;
    ctx.returnItems.add(new SQLExpression(new SQLIdentifier(BOUNDARY_ALIAS)));
    ctx.returnAliases.add(new SQLIdentifier(BOUNDARY_ALIAS));
    ctx.returnNestedProjections.add(null);
    ctx.stepIndex = 1; // positioned at the hop, after the start step
    return ctx;
  }

  /**
   * No-mutation-on-decline: a declining recogniser must leave the context exactly as
   * {@link #contextWithStartBoundary} seeded it — the start boundary/RETURN intact, no target node,
   * no anonymous alias minted, cursor unmoved.
   *
   * <p>The final assertion is a destructive mint-probe: {@code nextAnonVertexAlias()} advances the
   * per-context alias counter, so calling this helper is itself a mutation. It is sound only because
   * each decline test calls it exactly once, as its last statement — do not call it mid-test and
   * then drive more recogniser calls, or the alias sequence starts at {@code _1} and a later real
   * mint fails an unrelated equality check.
   */
  private static void assertContextUnmutated(WalkerContext ctx) {
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.stepIndex).as("cursor must not advance on decline").isEqualTo(1);
    // No target node was added and no anonymous alias was minted (the next mint is still _0).
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS))
        .as("no target node added on decline")
        .isFalse();
    assertThat(ctx.nextAnonVertexAlias())
        .as("no anonymous alias minted on decline")
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  private static Step<?, ?> stepAt(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin, int index) {
    return admin.getSteps().get(index);
  }
}
