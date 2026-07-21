package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link VertexHopRecogniser}, the recogniser that claims a folded bare vertex hop
 * ({@code out(L)} / {@code in(L)} / {@code both(L)}). Each test drives the recogniser directly with a
 * {@link StepStreamCursor} over the raw (un-strategised) DSL step list and a hand-built {@link
 * WalkerContext}, so each decline branch and the accept path's exact context mutations are pinned in
 * isolation. End-to-end result equivalence is covered by {@link EdgeTraversalEquivalenceTest}.
 *
 * <p>Consumption is read as a cursor-position delta: the recogniser takes its head (and, for a chain,
 * trailing steps) through the cursor, and the walker guards that an accept advanced. A single bare hop
 * advances the cursor by one folded step.
 */
public class VertexHopRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";

  /** The cursor's transparent set, mirroring the walker's production configuration. */
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  // ---------------------------------------------------------------------------
  // Accept path — a single-label bare hop appends an edge + target node, re-pins
  // the boundary to the new target, replaces the RETURN column, and advances the
  // cursor by one folded step.
  // ---------------------------------------------------------------------------

  /**
   * {@code out("knows")} is claimed: the recogniser appends a target node under a fresh anonymous
   * alias, re-pins the boundary metadata to that target (still {@code ELEMENT} / {@code Vertex}),
   * <em>replaces</em> the single RETURN column so it is keyed on the new target (not the old
   * boundary), attaches the generic {@code V} class to the target with <em>no</em> {@code @class}
   * filter (no subclass undercount), and advances the cursor by one folded step. The context is
   * pre-seeded with a start-step boundary and RETURN column so the replacement (not append) is
   * observable.
   */
  @Test
  public void outHop_claimsAndRePinsBoundaryToNewTarget() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a single-label out() hop is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before)
        .as("a single-label out() hop consumes one folded step")
        .isEqualTo(1);
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
   * of the direction mapping. Light assertions — the detailed mutation shape is pinned by the {@code
   * out} case above; this covers the direction branch.
   */
  @Test
  public void inHop_isClaimed() {
    var admin = graph.traversal().V().in("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a single-label in() hop is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before).as("consumes one folded step").isEqualTo(1);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * {@code both("knows")} is claimed, exercising the {@code BOTH} direction arm of the direction
   * mapping.
   */
  @Test
  public void bothHop_isClaimed() {
    var admin = graph.traversal().V().both("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a single-label both() hop is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * A label-less hop {@code out()} (all edge types) is claimed, not declined: {@code getEdgeLabels()}
   * is empty (length 0), which the guard accepts, passing a null edge label to the assembler (the IR
   * renders it as the all-edges {@code out('E')} form). The claim mirrors the single-label case — one
   * folded step consumed, boundary re-pinned to the fresh target, target under the generic {@code V}
   * class with no {@code @class} filter. End-to-end multiset equivalence with native {@code out()} is
   * pinned by {@link EdgeTraversalEquivalenceTest}.
   */
  @Test
  public void labelLessHop_isClaimedAsAllEdges() {
    var admin = graph.traversal().V().out().asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a label-less hop is accepted (all edge types)")
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before).as("consumes one folded step").isEqualTo(1);
    assertThat(ctx.boundaryAlias).isEqualTo(FIRST_ANON_ALIAS);
    var ir = ctx.patternBuilder.build();
    assertThat(ir.pattern().aliasToNode).containsOnlyKeys(BOUNDARY_ALIAS, FIRST_ANON_ALIAS);
    assertThat(ir.aliasClasses()).containsEntry(FIRST_ANON_ALIAS, "V");
    assertThat(ir.aliasFilters())
        .as("a label-less hop target carries no @class filter (the same no-narrowing rule)")
        .doesNotContainKey(FIRST_ANON_ALIAS);
  }

  // ---------------------------------------------------------------------------
  // Decline paths — a decline discards the whole walk, so the recogniser
  // contributes nothing before declining, which the shared assertion verifies.
  // ---------------------------------------------------------------------------

  /**
   * A non-{@link VertexStep} declines cleanly rather than throwing {@code ClassCastException} (guards
   * BG1). {@link VertexHopRecogniser} is reached only by delegation from {@link
   * VertexStepRecogniser}'s {@code returnsEdge() == false} branch, but it re-asserts its own {@code
   * instanceof VertexStep} precondition so a direct mis-call declines cleanly — contract parity with
   * {@link EdgeHopRecogniser}. The head is the start {@code GraphStep} at cursor position 0.
   */
  @Test
  public void nonVertexStep_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    // Fresh cursor: the head is the start GraphStep, not a VertexStep.
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);

    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a non-VertexStep must decline, not throw").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * An edge-returning {@link VertexStep} ({@code returnsEdge() == true}, e.g. {@code outE("knows")})
   * declines here: that step belongs to {@link EdgeHopRecogniser} (when followed by {@code has}) or
   * {@link CombinatorFoldedHopRecogniser} (singleton in a combinator sub-walk). The router never sends
   * an edge-returning step here, so this exercises the strict {@code returnsEdge()} precondition.
   */
  @Test
  public void edgeReturningVertexStep_declines() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);
    assertThat(((VertexStep<?>) admin.getSteps().get(1)).returnsEdge())
        .as("precondition: outE(...) is an edge-returning VertexStep")
        .isTrue();

    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome)
        .as("an edge-returning VertexStep must decline — it belongs to EdgeHopRecogniser")
        .isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * A hop reaching the recogniser with no pinned boundary ({@code boundaryAlias() == null}) declines:
   * there is no "from" endpoint to hang the edge off. This models a {@code VertexStep} arriving before
   * any node was pinned — a defensive decline rather than a dangling edge.
   */
  @Test
  public void nullBoundary_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = new WalkerContext(true, false); // boundaryAlias stays null
    var cursor = cursorAfterStart(admin);

    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a hop with no pinned boundary must decline").isEqualTo(Outcome.DECLINE);
    // Nothing minted: the next anonymous alias is still _0.
    assertThat(ctx.boundaryAlias).isNull();
    assertThat(ctx.returnItems).isEmpty();
    assertThat(ctx.nextAnonVertexAlias())
        .as("no anonymous alias minted on decline")
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * Two sequential hops chain: the second hop's edge starts from the first hop's target, and the
   * boundary/RETURN follow to the final target. Driving the recogniser twice on one cursor (mirroring
   * the walker's loop, but on the raw list which carries no {@code NoOpBarrierStep} between the hops)
   * pins the chaining: hop 1 re-pins to {@code $g2m_anon_0}, hop 2 hangs its edge off {@code
   * $g2m_anon_0} and re-pins to {@code $g2m_anon_1}, leaving a three-node pattern with the final target
   * as the single result. Each hop advances the cursor by one folded step.
   */
  @Test
  public void twoSequentialHops_chainOffPreviousTarget() {
    var admin = graph.traversal().V().out("knows").out("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var beforeFirst = cursor.position();
    assertThat(VertexHopRecogniser.INSTANCE.recognize(cursor, ctx))
        .as("first hop is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - beforeFirst).as("first hop consumes one folded step")
        .isEqualTo(1);
    assertThat(ctx.boundaryAlias).as("first hop re-pins to the first anon target")
        .isEqualTo(FIRST_ANON_ALIAS);

    var beforeSecond = cursor.position();
    assertThat(VertexHopRecogniser.INSTANCE.recognize(cursor, ctx))
        .as("second hop is accepted").isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - beforeSecond).as("second hop consumes one folded step")
        .isEqualTo(1);
    assertThat(ctx.boundaryAlias).as("second hop re-pins to the second anon target")
        .isEqualTo("$g2m_anon_1");

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
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a multi-label hop must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * A single blank edge label declines via the {@code isBlank()} guard — the branch a real DSL hop
   * cannot reach (the DSL rejects a blank label at construction), so it is driven with a mock that
   * reports one blank label. Without the guard a blank label would build a degenerate unlabelled MATCH
   * edge; the recogniser declines instead.
   */
  @Test
  public void blankSingleLabel_declines() {
    var ctx = contextWithStartBoundary();
    @SuppressWarnings("unchecked")
    VertexStep<Vertex> blankLabelHop = mock(VertexStep.class);
    when(blankLabelHop.returnsEdge()).thenReturn(false);
    when(blankLabelHop.getEdgeLabels()).thenReturn(new String[] {"  "});
    // A one-element stream whose head is the mock hop.
    var cursor = new StepStreamCursor(java.util.List.of(blankLabelHop), TRANSPARENT);

    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a single blank edge label must decline").isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  /**
   * A label-less hop declines when the context reports {@code EdgeLabelVerificationStrategy} is active,
   * even though a label-less hop normally translates ({@link #labelLessHop_isClaimedAsAllEdges}). That
   * opt-in strategy exists to reject a label-less edge traversal; translating the hop into a boundary
   * step would remove it before the verification runs and swallow the error. The walker resolves the
   * flag once and stores it on the context; here it is seeded {@code true} directly. Declining leaves
   * the native {@code VertexStep} for the strategy to reject. The walker's own resolution of the flag
   * from the strategy list is covered by {@link GremlinStepWalkerTest}.
   */
  @Test
  public void labelLessHop_underEdgeLabelVerification_declines() {
    var admin = graph.traversal().V().out().asAdmin();
    // edgeLabelVerification = true on the context, as the walker would resolve for a traversal that
    // opts into EdgeLabelVerificationStrategy.
    var ctx = new WalkerContext(true, true);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    var cursor = cursorAfterStart(admin);

    var outcome = VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome)
        .as("a label-less hop must decline when EdgeLabelVerificationStrategy is active")
        .isEqualTo(Outcome.DECLINE);
    assertContributedNothing(ctx);
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Builds a context pre-seeded as the start-step recogniser would leave it: a pinned {@code $g2m_v0}
   * boundary with one RETURN column keyed on that alias. A successful hop must replace this column with
   * one keyed on its new target; a declined hop must leave it as seeded.
   */
  private static WalkerContext contextWithStartBoundary() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  /** A cursor over the raw step list positioned at the first hop — the start GraphStep is consumed. */
  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take(); // consume the start GraphStep, leaving the head at the first hop
    return cursor;
  }

  /**
   * A declining hop recogniser contributes nothing before declining (it validates before it
   * contributes): the seeded start boundary / RETURN is intact and no target node or anonymous alias
   * was minted. A decline would discard the whole walk anyway, so this pins the contribute-last shape
   * rather than a required rollback.
   *
   * <p>The final assertion is a destructive mint-probe: {@code nextAnonVertexAlias()} advances the
   * per-context alias counter, so calling this helper is itself a mutation. Call it exactly once, as
   * the last statement — do not call it mid-test and then drive more recogniser calls, or the alias
   * sequence starts at {@code _1} and a later real mint fails an unrelated equality check.
   */
  private static void assertContributedNothing(WalkerContext ctx) {
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS))
        .as("no target node added on decline")
        .isFalse();
    assertThat(ctx.nextAnonVertexAlias())
        .as("no anonymous alias minted on decline")
        .isEqualTo(FIRST_ANON_ALIAS);
  }
}
