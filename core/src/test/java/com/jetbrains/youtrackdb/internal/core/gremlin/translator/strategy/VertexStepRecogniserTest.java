package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link VertexStepRecogniser} in its router role: a thin dispatcher for the single
 * {@link VertexStep} registry key. TinkerPop models both a bare vertex hop ({@code out(L)}, {@code
 * returnsEdge() == false}) and an edge-returning step ({@code outE(L)}, {@code returnsEdge() == true})
 * as a {@code VertexStep}, so this recogniser owns {@code VertexStep.class} and does no recognition
 * itself — it peeks the head, routes on {@link VertexStep#returnsEdge()} to the bare-hop {@link
 * VertexHopRecogniser} or the edge-filter {@link EdgeHopRecogniser}, and forwards the handler's {@link
 * Outcome} verbatim. The per-handler behaviour is pinned by {@link VertexHopRecogniserTest} and {@link
 * EdgeHopRecogniserTest}; these tests pin only that the router dispatches to the right handler on each
 * {@code returnsEdge()} arm (and guards a non-{@code VertexStep}).
 *
 * <p>Each routing test drives the router and the target handler on two independently-seeded contexts
 * and two cursors built from the same raw DSL step list, then asserts the router's outcome, boundary
 * re-pin, and consumed-step count match the handler's — the definition of "forwards verbatim". Both
 * contexts are fresh {@link WalkerContext}s, so their per-context alias sequences restart at {@code 0}
 * and mint identical aliases, making the two runs directly comparable.
 */
public class VertexStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";

  /** The cursor's transparent set, mirroring the walker's production configuration. */
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * A bare {@code out("knows")} hop ({@code returnsEdge() == false}) routes to {@link
   * VertexHopRecogniser}: the router yields the same outcome, consumed-step count, and boundary re-pin
   * as {@code VertexHopRecogniser} does when driven directly with an identically-seeded context and
   * cursor. Proves the {@code returnsEdge() == false} arm dispatches to the bare-hop handler and
   * forwards its result verbatim.
   */
  @Test
  public void bareHop_routesToVertexHopRecogniser() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    assertThat(((VertexStep<?>) admin.getSteps().get(1)).returnsEdge())
        .as("precondition: out(...) is a bare (non-edge) VertexStep")
        .isFalse();

    var routerCtx = contextWithStartBoundary();
    var directCtx = contextWithStartBoundary();
    var routerCursor = cursorAfterStart(admin);
    var directCursor = cursorAfterStart(admin);

    var routerBefore = routerCursor.position();
    var routerResult = VertexStepRecogniser.INSTANCE.recognize(routerCursor, routerCtx);
    var directBefore = directCursor.position();
    var directResult = VertexHopRecogniser.INSTANCE.recognize(directCursor, directCtx);

    assertThat(routerResult)
        .as("the router forwards VertexHopRecogniser's outcome verbatim")
        .isEqualTo(directResult)
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(routerCursor.position() - routerBefore)
        .as("the router consumes the same one folded step as VertexHopRecogniser")
        .isEqualTo(directCursor.position() - directBefore)
        .isEqualTo(1);
    assertThat(routerCtx.boundaryAlias)
        .as("the router re-pins the boundary exactly as VertexHopRecogniser would")
        .isEqualTo(directCtx.boundaryAlias)
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * An edge-returning {@code outE("knows").has("w", 1).inV()} chain ({@code returnsEdge() == true})
   * routes to {@link EdgeHopRecogniser}: the router yields the same outcome, consumed-step count (3 —
   * edge + has + closing hop), and boundary re-pin as {@code EdgeHopRecogniser} does when driven
   * directly. Proves the {@code returnsEdge() == true} arm dispatches to the edge-filter handler.
   */
  @Test
  public void edgeReturningStep_routesToEdgeHopRecogniser() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    assertThat(((VertexStep<?>) admin.getSteps().get(1)).returnsEdge())
        .as("precondition: outE(...) is an edge-returning VertexStep")
        .isTrue();

    var routerCtx = contextWithStartBoundary();
    var directCtx = contextWithStartBoundary();
    var routerCursor = cursorAfterStart(admin);
    var directCursor = cursorAfterStart(admin);

    var routerBefore = routerCursor.position();
    var routerResult = VertexStepRecogniser.INSTANCE.recognize(routerCursor, routerCtx);
    var directBefore = directCursor.position();
    var directResult = EdgeHopRecogniser.INSTANCE.recognize(directCursor, directCtx);

    assertThat(routerResult)
        .as("the router forwards EdgeHopRecogniser's outcome verbatim")
        .isEqualTo(directResult)
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(routerCursor.position() - routerBefore)
        .as("the router consumes the same three steps as EdgeHopRecogniser")
        .isEqualTo(directCursor.position() - directBefore)
        .isEqualTo(3);
    assertThat(routerCtx.boundaryAlias)
        .as("the router re-pins the boundary exactly as EdgeHopRecogniser would")
        .isEqualTo(directCtx.boundaryAlias)
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * A bare edge-returning terminal {@code outE("knows")} (no closing vertex hop) routes to {@link
   * EdgeHopRecogniser}, which declines because an edge result is out of scope. The router forwards that
   * decline verbatim and the walk contributes nothing, proving the {@code returnsEdge() == true} arm
   * also forwards a decline — not only an accept.
   */
  @Test
  public void bareEdgeTerminal_routesToEdgeHopRecogniserAndDeclines() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    assertThat(((VertexStep<?>) admin.getSteps().get(1)).returnsEdge())
        .as("precondition: outE(...) is an edge-returning VertexStep")
        .isTrue();

    var routerCtx = contextWithStartBoundary();
    var directCtx = contextWithStartBoundary();
    var routerResult =
        VertexStepRecogniser.INSTANCE.recognize(cursorAfterStart(admin), routerCtx);
    var directResult = EdgeHopRecogniser.INSTANCE.recognize(cursorAfterStart(admin), directCtx);

    assertThat(routerResult)
        .as("the router forwards EdgeHopRecogniser's decline verbatim")
        .isEqualTo(directResult)
        .isEqualTo(Outcome.DECLINE);
    assertContributedNothing(routerCtx);
  }

  /**
   * A non-{@link VertexStep} (the start {@code GraphStep} at cursor position 0) declines cleanly rather
   * than throwing {@code ClassCastException}: the router re-asserts its {@code instanceof VertexStep}
   * precondition before touching {@code returnsEdge()}, so a future registry mistake that routed a
   * foreign step here declines instead of crashing. The router only peeks, so a decline consumes
   * nothing.
   */
  @Test
  public void nonVertexStep_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    // Fresh cursor: the head is the start GraphStep, not a VertexStep.
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);

    var before = cursor.position();
    var outcome = VertexStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).as("a non-VertexStep must decline, not throw").isEqualTo(Outcome.DECLINE);
    assertThat(cursor.position())
        .as("the router only peeks a non-VertexStep, so it consumes nothing")
        .isEqualTo(before);
    assertContributedNothing(ctx);
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Builds a context pre-seeded as the start-step recogniser would leave it: a pinned {@code $g2m_v0}
   * boundary with one RETURN column keyed on that alias. A routed claim must re-pin the boundary to its
   * new target; a routed decline must leave everything as seeded.
   */
  private static WalkerContext contextWithStartBoundary() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  /** A cursor over the raw step list positioned at the hop / edge step — the start GraphStep is
   *  consumed. */
  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take(); // consume the start GraphStep, leaving the head at the hop / edge step
    return cursor;
  }

  /**
   * A routed decline contributes nothing: the seeded start boundary / RETURN is intact, no edge filter
   * accumulated, and no target node was added.
   */
  private static void assertContributedNothing(WalkerContext ctx) {
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.returnAliases).hasSize(1);
    assertThat(ctx.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.edgeFilters).as("no edge filter accumulated on decline").isEmpty();
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS))
        .as("no target node added on decline")
        .isFalse();
  }
}
