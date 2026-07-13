package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;

/**
 * Router for the single {@link VertexStep} registry key. TinkerPop models both a vertex-returning
 * hop ({@code out(L)} / {@code in(L)} / {@code both(L)}, plus the {@code outE(L).inV()} chains
 * {@code IncidentToAdjacentStrategy} folds to the same class) and an edge-returning step ({@code
 * outE(L)} / {@code inE(L)} / {@code bothE(L)}, the non-adjacent edge of the {@code
 * outE(L).has(...).inV()} shape) as a {@link VertexStep}, distinguished by {@link
 * VertexStep#returnsEdge()}. The registry keys one recogniser per class, so this recogniser owns
 * {@code VertexStep.class} and does no recognition itself — it routes to the handler for the step's
 * kind:
 *
 * <ul>
 *   <li>{@code returnsEdge() == false} → the bare-hop {@link VertexHopRecogniser};
 *   <li>{@code returnsEdge() == true} → the edge-filter {@link EdgeHopRecogniser}.
 * </ul>
 *
 * <p>The chosen handler's {@code int} result is forwarded verbatim — {@code 0} declines the whole
 * traversal, a positive count is the number of steps consumed — so routing adds no behaviour of its
 * own beyond the {@code returnsEdge()} split. Both handlers re-assert their own {@code instanceof
 * VertexStep} precondition, so a non-{@link VertexStep} that reached this router through a future
 * registry mistake still declines cleanly with {@code 0} rather than throwing.
 */
final class VertexStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final VertexStepRecogniser INSTANCE = new VertexStepRecogniser();

  private VertexStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public int recognize(Step<?, ?> step, WalkerContext ctx) {
    // Defence in depth: the registry keys this recogniser on VertexStep.class, so dispatch only
    // ever hands it a VertexStep. Re-assert the type so a future registry mistake declines cleanly
    // rather than ClassCastException-ing.
    if (!(step instanceof VertexStep<?> vertexStep)) {
      return 0;
    }
    // Route on the vertex/edge split and forward the handler's result verbatim: returnsEdge() ==
    // true is the non-adjacent edge step (outE(L) in outE(L).has(...).inV()), handled by the edge-
    // filter recogniser; returnsEdge() == false is a bare hop, handled by the vertex-hop recogniser.
    // Both handlers honour the no-mutation-on-decline contract, so a delegated 0 leaves ctx clean.
    return vertexStep.returnsEdge()
        ? EdgeHopRecogniser.INSTANCE.recognize(vertexStep, ctx)
        : VertexHopRecogniser.INSTANCE.recognize(vertexStep, ctx);
  }
}
