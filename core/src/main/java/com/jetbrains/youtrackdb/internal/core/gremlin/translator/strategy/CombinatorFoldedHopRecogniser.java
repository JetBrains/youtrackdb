package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;

/**
 * Recogniser for a combinator child sub-walk artefact: a singleton edge-returning {@link
 * VertexStepContract} ({@code returnsEdge() == true}) with no following steps. After {@code
 * applyStrategies()} runs {@code AdjacentToIncidentStrategy} recursively on combinator children, a bare
 * {@code out(L)} child can remain as an edge-returning {@code VertexStep} or {@link
 * org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder} instead of the
 * vertex-returning folded hop the top-level walk sees — semantically still a bare hop, not {@code
 * outE(L).has(...).inV()}.
 *
 * <h2>Reached by delegation, not registered directly</h2>
 *
 * {@link VertexStepRecogniser} routes here only when the head is edge-returning, the cursor has no
 * step after the head, and the walk runs inside a {@link SubTraversalPredicateAdapter}. Top-level
 * singleton {@code outE(L)} still declines at the router. This recogniser is never in the walker
 * registry.
 *
 * <h2>Same IR contribution as a bare hop</h2>
 *
 * The folded hop tail ({@link GremlinPatternAssembler#claimFoldedHop}) matches {@link
 * VertexHopRecogniser}: one edge + generic {@code V} target, boundary re-pinned to the target.
 */
final class CombinatorFoldedHopRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final CombinatorFoldedHopRecogniser INSTANCE = new CombinatorFoldedHopRecogniser();

  private CombinatorFoldedHopRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof VertexStepContract<?> hop) || !hop.returnsEdge()) {
      return Outcome.DECLINE;
    }
    // A trailing step means this is not the singleton fold artifact — {@link EdgeHopRecogniser} or
    // another recogniser owns the shape.
    if (cursor.peek() != null) {
      return Outcome.DECLINE;
    }
    // Defence in depth: the router gates on sub-walk context; a direct mis-call at the top level
    // must not translate a terminal outE as a bare hop.
    if (!(ctx instanceof SubTraversalPredicateAdapter)) {
      return Outcome.DECLINE;
    }
    return GremlinPatternAssembler.claimFoldedHop(hop, ctx);
  }
}
