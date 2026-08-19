package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;

/**
 * No-op recogniser for a redundant {@link EdgeVertexStep} left by a partial {@code
 * IncidentToAdjacentStrategy} fold.
 *
 * <p>Adjacent {@code outE(L).inV()} normally folds to one vertex-returning {@link
 * org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep}. When a later global step
 * (e.g. {@code order()}, {@code select()}) blocks a full fold, the step list can arrive as {@code
 * VertexStep(out)} followed by an orphan {@link EdgeVertexStep(inV)}. The first step already moved
 * the walk boundary to the far vertex; the closing {@code inV}/{@code outV} is a runtime no-op.
 *
 * <p>This recogniser consumes that orphan: it binds any {@code as(...)} label on the closing hop to
 * the current {@link RecognitionContext#boundaryAlias()} and contributes nothing to the pattern.
 * It declines when no boundary is pinned (the step would be meaningful only as part of an {@link
 * EdgeHopRecogniser} chain, which consumes its own closing hop while the head is still an
 * edge-returning {@code VertexStep}).
 */
final class RedundantEdgeVertexStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final RedundantEdgeVertexStepRecogniser INSTANCE = new RedundantEdgeVertexStepRecogniser();

  private RedundantEdgeVertexStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof EdgeVertexStep)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    if (!ctx.bindStepLabels(step, boundary)) {
      return Outcome.DECLINE;
    }
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof EdgeVertexStep edgeVertexStep)) {
      return false;
    }
    encoder.appendToken("dir", edgeVertexStep.getDirection().name());
    return true;
  }
}
