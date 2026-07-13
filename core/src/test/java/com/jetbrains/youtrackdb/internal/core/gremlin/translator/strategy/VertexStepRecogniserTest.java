package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link VertexStepRecogniser} in its post-refactor role: a thin <em>router</em> for
 * the single {@link VertexStep} registry key. TinkerPop models both a bare vertex hop ({@code
 * out(L)}, {@code returnsEdge() == false}) and an edge-returning step ({@code outE(L)}, {@code
 * returnsEdge() == true}) as a {@code VertexStep}, so this recogniser owns {@code VertexStep.class}
 * and does no recognition itself — it routes on {@link VertexStep#returnsEdge()} to the bare-hop
 * {@link VertexHopRecogniser} or the edge-filter {@link EdgeHopRecogniser} and forwards the handler's
 * {@code int} result verbatim. The per-handler recognition behaviour is pinned by {@link
 * VertexHopRecogniserTest} and {@link EdgeHopRecogniserTest}; these tests pin only that the router
 * dispatches to the right handler on each {@code returnsEdge()} arm (and guards a non-{@code
 * VertexStep}), closing the router's branch-coverage gap.
 *
 * <p>Each routing test drives the router and the target handler on two independently-seeded contexts
 * built from the same raw (un-strategised) DSL step list, then asserts the router's result and
 * boundary re-pin match the handler's — the definition of "forwards verbatim". Both contexts are
 * fresh {@link WalkerContext}s, so their per-context alias sequences restart at {@code 0} and mint
 * identical aliases, making the two runs directly comparable.
 */
public class VertexStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";

  /**
   * A bare {@code out("knows")} hop ({@code returnsEdge() == false}) routes to {@link
   * VertexHopRecogniser}: the router yields the same positive consumed-step count (1) and re-pins the
   * boundary to the same minted target as {@code VertexHopRecogniser} does when driven directly with
   * an identically-seeded context. Proves the {@code returnsEdge() == false} arm dispatches to the
   * bare-hop handler and forwards its result verbatim.
   */
  @Test
  public void bareHop_routesToVertexHopRecogniser() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var hop = stepAt(admin, 1);
    assertThat(((VertexStep<?>) hop).returnsEdge())
        .as("precondition: out(...) is a bare (non-edge) VertexStep")
        .isFalse();

    var routerCtx = contextWithStartBoundary(admin);
    var directCtx = contextWithStartBoundary(admin);

    var routerResult = VertexStepRecogniser.INSTANCE.recognize(hop, routerCtx);
    var directResult = VertexHopRecogniser.INSTANCE.recognize(hop, directCtx);

    assertThat(routerResult)
        .as("the router forwards VertexHopRecogniser's positive result verbatim")
        .isEqualTo(directResult)
        .isEqualTo(1);
    assertThat(routerCtx.boundaryAlias)
        .as("the router re-pins the boundary exactly as VertexHopRecogniser would")
        .isEqualTo(directCtx.boundaryAlias)
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * An edge-returning {@code outE("knows").has("w", 1).inV()} chain ({@code returnsEdge() == true})
   * routes to {@link EdgeHopRecogniser}: the router yields the same positive consumed-step count (3 —
   * edge + has + closing hop) and re-pins the boundary to the same minted target as {@code
   * EdgeHopRecogniser} does when driven directly. Proves the {@code returnsEdge() == true} arm
   * dispatches to the edge-filter handler and forwards its result verbatim.
   */
  @Test
  public void edgeReturningStep_routesToEdgeHopRecogniser() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).inV().asAdmin();
    var edgeStep = stepAt(admin, 1);
    assertThat(((VertexStep<?>) edgeStep).returnsEdge())
        .as("precondition: outE(...) is an edge-returning VertexStep")
        .isTrue();

    var routerCtx = contextWithStartBoundary(admin);
    var directCtx = contextWithStartBoundary(admin);

    var routerResult = VertexStepRecogniser.INSTANCE.recognize(edgeStep, routerCtx);
    var directResult = EdgeHopRecogniser.INSTANCE.recognize(edgeStep, directCtx);

    assertThat(routerResult)
        .as("the router forwards EdgeHopRecogniser's positive result verbatim")
        .isEqualTo(directResult)
        .isEqualTo(3);
    assertThat(routerCtx.boundaryAlias)
        .as("the router re-pins the boundary exactly as EdgeHopRecogniser would")
        .isEqualTo(directCtx.boundaryAlias)
        .isEqualTo(FIRST_ANON_ALIAS);
  }

  /**
   * A bare edge-returning terminal {@code outE("knows")} (no closing vertex hop) routes to {@link
   * EdgeHopRecogniser}, which declines ({@code 0}) because an edge result is out of scope. The router
   * forwards that decline verbatim and leaves the context unmutated, proving the {@code returnsEdge()
   * == true} arm also forwards a decline — not only a positive claim — without mutating {@code ctx}.
   */
  @Test
  public void bareEdgeTerminal_routesToEdgeHopRecogniserAndDeclines() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    var edgeStep = stepAt(admin, 1);
    assertThat(((VertexStep<?>) edgeStep).returnsEdge())
        .as("precondition: outE(...) is an edge-returning VertexStep")
        .isTrue();

    var routerCtx = contextWithStartBoundary(admin);
    var directCtx = contextWithStartBoundary(admin);

    var routerResult = VertexStepRecogniser.INSTANCE.recognize(edgeStep, routerCtx);
    var directResult = EdgeHopRecogniser.INSTANCE.recognize(edgeStep, directCtx);

    assertThat(routerResult)
        .as("the router forwards EdgeHopRecogniser's decline (0) verbatim")
        .isEqualTo(directResult)
        .isEqualTo(0);
    assertContextUnmutated(routerCtx);
  }

  /**
   * A non-{@link VertexStep} (the start {@code GraphStep} at index 0) declines cleanly with {@code 0}
   * rather than throwing {@code ClassCastException}: the router re-asserts its {@code instanceof
   * VertexStep} precondition before touching {@code returnsEdge()}, so a future registry mistake that
   * routed a foreign step here declines instead of crashing. Closes the router's guard branch (CQ2).
   */
  @Test
  public void nonVertexStep_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var ctx = contextWithStartBoundary(admin);
    var graphStep = stepAt(admin, 0); // the GraphStep, not a VertexStep

    var recognized = VertexStepRecogniser.INSTANCE.recognize(graphStep, ctx);

    assertThat(recognized).as("a non-VertexStep must decline (0), not throw").isEqualTo(0);
    assertContextUnmutated(ctx);
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Builds a context pre-seeded as the start-step recogniser would leave it: a pinned {@code $g2m_v0}
   * boundary with one RETURN column keyed on that alias, cursor positioned at the hop. A routed claim
   * must re-pin the boundary to its new target; a routed decline must leave everything as seeded.
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
    ctx.stepIndex = 1; // positioned at the hop, after the start step
    return ctx;
  }

  /**
   * No-mutation-on-decline: a router that forwards a handler's decline must leave the context exactly
   * as {@link #contextWithStartBoundary} seeded it — the start boundary / RETURN intact, no target or
   * edge node added, cursor unmoved.
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
  }

  private static Step<?, ?> stepAt(Traversal.Admin<?, ?> admin, int index) {
    return admin.getSteps().get(index);
  }
}
