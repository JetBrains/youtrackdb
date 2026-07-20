package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;

/**
 * Router for folded vertex-hop step classes ({@link VertexStep} and {@link VertexStepPlaceholder}).
 * TinkerPop models both a vertex-returning hop ({@code out(L)} / {@code in(L)} / {@code both(L)},
 * plus the {@code outE(L).inV()} chains {@code IncidentToAdjacentStrategy} folds to the same class)
 * and an edge-returning step ({@code outE(L)} / {@code inE(L)} / {@code bothE(L)}, the non-adjacent
 * edge of the {@code outE(L).has(...).inV()} shape) under {@link VertexStepContract}, distinguished
 * by {@link VertexStepContract#returnsEdge()}. When {@code AdjacentToIncidentStrategy} runs
 * recursively on combinator child traversals during {@code applyStrategies()}, bare hops may remain as
 * a {@link VertexStepPlaceholder} until a later GValue reducer fires. The registry keys one
 * recogniser per exact class, so this recogniser owns both {@code VertexStep.class} and {@code
 * VertexStepPlaceholder.class} and routes to the handler for the step's kind:
 *
 * <ul>
 *   <li>{@code returnsEdge() == false} → the bare-hop {@link VertexHopRecogniser};
 *   <li>{@code returnsEdge() == true} → the edge-filter {@link EdgeHopRecogniser}.
 * </ul>
 *
 * <p>The router <em>peeks</em> the head to read {@code returnsEdge()} and delegates without consuming
 * it: the chosen handler takes the head from the cursor itself, so the router adds no behaviour beyond
 * the split and forwards the handler's {@link Outcome} verbatim. It re-asserts the head implements
 * {@link VertexStepContract}, so a step outside that contract that reached it through a future registry
 * mistake declines cleanly rather than throwing.
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
    // recogniser on VertexStep / VertexStepPlaceholder, but re-assert the contract so a future registry
    // mistake declines cleanly rather than mis-routing.
    if (!(cursor.peek() instanceof VertexStepContract<?> hop)) {
      return Outcome.DECLINE;
    }
    // Route on the vertex/edge split. A non-adjacent {@code outE.has.inV} chain is an
    // edge-returning head followed by {@link HasStep}; a singleton edge-returning hop (common in
    // combinator child sub-traversals after {@code AdjacentToIncidentStrategy} runs recursively
    // during {@code applyStrategies()}) translates as a bare hop.
    if (hop.returnsEdge() && cursor.peek(1) instanceof HasStep<?>) {
      return EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);
    }
    return VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);
  }
}
