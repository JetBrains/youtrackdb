package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link CombinatorFoldedHopRecogniser} — the combinator child sub-walk artefact where
 * {@code AdjacentToIncidentStrategy} leaves a singleton edge-returning {@link VertexStep} that still
 * semantically behaves as a bare hop. End-to-end {@code and(out, out)} coverage lives in {@link
 * AndStepRecogniserTest} and {@link EdgeTraversalEquivalenceTest}.
 */
public class CombinatorFoldedHopRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";

  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * A singleton {@code outE("knows")} inside a {@link SubTraversalPredicateAdapter} is accepted and
   * contributes the same folded hop as a bare {@code out("knows")}.
   */
  @Test
  public void singletonEdgeReturningHop_inSubWalk_claimsFoldedHop() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    assertThat(((VertexStep<?>) admin.getSteps().get(1)).returnsEdge()).isTrue();

    var adapter = subWalkAdapterWithBoundary();
    var cursor = cursorAfterStart(admin);

    var before = cursor.position();
    var outcome = CombinatorFoldedHopRecogniser.INSTANCE.recognize(cursor, adapter);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(cursor.position() - before).isEqualTo(1);
    assertThat(adapter.boundaryAlias()).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(adapter.hasEdges()).isTrue();
    assertThat(adapter.capturedPattern().hasAlias(FIRST_ANON_ALIAS)).isTrue();
  }

  /** The same singleton shape declines at the top level — only sub-walk context may claim it. */
  @Test
  public void singletonEdgeReturningHop_atTopLevel_declines() {
    var admin = graph.traversal().V().outE("knows").asAdmin();
    var ctx = contextWithStartBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = CombinatorFoldedHopRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isFalse();
  }

  /** A trailing step means this is not the singleton fold artifact. */
  @Test
  public void edgeReturningHop_withTrailingStep_declines() {
    var admin = graph.traversal().V().outE("knows").has("w", 1).asAdmin();
    var adapter = subWalkAdapterWithBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = CombinatorFoldedHopRecogniser.INSTANCE.recognize(cursor, adapter);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(adapter.capturedPattern().hasAlias(FIRST_ANON_ALIAS)).isFalse();
  }

  /** A vertex-returning hop is out of scope for this recogniser. */
  @Test
  public void bareVertexHop_declines() {
    var admin = graph.traversal().V().out("knows").asAdmin();
    var adapter = subWalkAdapterWithBoundary();
    var cursor = cursorAfterStart(admin);

    var outcome = CombinatorFoldedHopRecogniser.INSTANCE.recognize(cursor, adapter);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
  }

  private static SubTraversalPredicateAdapter subWalkAdapterWithBoundary() {
    var parent = contextWithStartBoundary();
    return new SubTraversalPredicateAdapter(parent, Map.of());
  }

  private static WalkerContext contextWithStartBoundary() {
    var ctx = new WalkerContext(true, false);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take();
    return cursor;
  }
}
