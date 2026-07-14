package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;

/**
 * Router for the single {@link VertexStep} registry key. TinkerPop models both a vertex-returning hop
 * ({@code out(L)} / {@code in(L)} / {@code both(L)}, plus the {@code outE(L).inV()} chains {@code
 * IncidentToAdjacentStrategy} folds to the same class) and an edge-returning step ({@code outE(L)} /
 * {@code inE(L)} / {@code bothE(L)}, the non-adjacent edge of the {@code outE(L).has(...).inV()}
 * shape) as a {@link VertexStep}, distinguished by {@link VertexStep#returnsEdge()}. The registry keys
 * one recogniser per class, so this recogniser owns {@code VertexStep.class} and does no recognition
 * itself — it routes to the handler for the step's kind:
 *
 * <ul>
 *   <li>{@code returnsEdge() == false} → the bare-hop {@link VertexHopRecogniser};
 *   <li>{@code returnsEdge() == true} → the edge-filter {@link EdgeHopRecogniser}.
 * </ul>
 *
 * <p>The router <em>peeks</em> the head to read {@code returnsEdge()} and delegates without consuming
 * it: the chosen handler takes the head from the cursor itself, so the router adds no behaviour beyond
 * the split and forwards the handler's {@link Outcome} verbatim. It re-asserts the head is a {@link
 * VertexStep}, so a non-{@link VertexStep} that reached it through a future registry mistake declines
 * cleanly rather than throwing.
 */
final class VertexStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final VertexStepRecogniser INSTANCE = new VertexStepRecogniser();

  private VertexStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    // Peek, do not take: the delegate consumes the head. Defence in depth — the registry keys this
    // recogniser on VertexStep.class, so dispatch only ever hands it a VertexStep, but re-assert the
    // type so a future registry mistake declines cleanly rather than mis-routing.
    if (!(cursor.peek() instanceof VertexStep<?> vertexStep)) {
      return Outcome.DECLINE;
    }
    // Route on the vertex/edge split and forward the handler's outcome verbatim: returnsEdge() == true
    // is the non-adjacent edge step (outE(L) in outE(L).has(...).inV()), handled by the edge-filter
    // recogniser; returnsEdge() == false is a bare hop, handled by the vertex-hop recogniser.
    return vertexStep.returnsEdge()
        ? EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx)
        : VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);
  }
}
