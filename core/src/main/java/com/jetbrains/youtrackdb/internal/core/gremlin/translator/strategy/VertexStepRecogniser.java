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
 * a {@link VertexStepPlaceholder} or a singleton edge-returning {@link VertexStep} until a later
 * GValue reducer fires. The registry keys one recogniser per exact class, so this recogniser owns both
 * {@code VertexStep.class} and {@code VertexStepPlaceholder.class} and routes to the handler for the
 * step's kind:
 *
 * <ul>
 *   <li>{@code returnsEdge() == false} → {@link VertexHopRecogniser};
 *   <li>{@code returnsEdge() == true} with a following {@link HasStep} → {@link EdgeHopRecogniser};
 *   <li>{@code returnsEdge() == true} singleton inside a combinator sub-walk → {@link
 *       CombinatorFoldedHopRecogniser};
 *   <li>any other edge-returning head (e.g. top-level {@code outE(L)}) → {@link Outcome#DECLINE}.
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
    if (!(cursor.peek() instanceof VertexStepContract<?> hop)) {
      return Outcome.DECLINE;
    }
    if (!hop.returnsEdge()) {
      return VertexHopRecogniser.INSTANCE.recognize(cursor, ctx);
    }
    if (cursor.peek(1) instanceof HasStep<?>) {
      return EdgeHopRecogniser.INSTANCE.recognize(cursor, ctx);
    }
    if (cursor.peek(1) == null && ctx instanceof SubTraversalPredicateAdapter) {
      return CombinatorFoldedHopRecogniser.INSTANCE.recognize(cursor, ctx);
    }
    return Outcome.DECLINE;
  }
}
