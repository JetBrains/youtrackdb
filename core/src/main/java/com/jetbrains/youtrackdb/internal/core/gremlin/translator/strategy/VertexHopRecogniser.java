package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;

/**
 * Recogniser for a single folded vertex hop — the bare {@code out(L)} / {@code in(L)} / {@code
 * both(L)} shape, and the adjacent {@code outE(L).inV()} / {@code bothE(L).otherV()} chains that
 * {@code IncidentToAdjacentStrategy} folds down to a vertex-returning {@link VertexStep} before this
 * walker runs (see {@code FoldedEdgeStepDispatchClassTest}). Each such hop appends one edge and one
 * target node to the pattern and re-pins the boundary to the new target, so a multi-hop chain ({@code
 * g.V().out(L).out(L)}) is a sequence of single-hop claims and the last hop's target becomes the
 * traversal's result.
 *
 * <h2>Reached by delegation, not registered directly</h2>
 *
 * The bare hop is a {@link VertexStep} with {@code returnsEdge() == false}, the same registry class as
 * the edge-returning step. {@link VertexStepRecogniser} owns {@code VertexStep.class} and routes here
 * on the {@code returnsEdge() == false} branch. This recogniser is never in the walker registry. It
 * takes the head from the cursor and re-asserts its full precondition — the step is a {@link
 * VertexStep} <em>and</em> is <em>not</em> edge-returning — so a direct mis-call (a non-{@code
 * VertexStep}, or an edge-returning step) declines cleanly rather than mis-translating an edge step as
 * a bare hop. Combinator child sub-walk fold artifacts ({@code returnsEdge() == true} singletons) are
 * handled by {@link CombinatorFoldedHopRecogniser}, not here.
 *
 * <h2>Bare hop targets root at {@code V} polymorphically — no {@code @class} narrowing</h2>
 *
 * The hop target is registered with the generic vertex class {@code V} and <em>no</em> {@code @class}
 * filter, <em>regardless of {@link RecognitionContext#polymorphic()}</em>. Native {@code out(L)} never
 * class-filters its target: it follows every {@code L}-labelled edge and yields whatever vertex sits
 * on the other end, whatever its class. Narrowing the target — even under {@code polymorphic=false} —
 * would drop subclass instances the native pipeline keeps, so a bare chain hop mirrors the start step,
 * which likewise emits no class filter ({@link StartStepRecogniser}). {@code @class} narrowing
 * reappears only for an explicit user-named class (the folded {@code hasLabel}, added later), through
 * the shared {@code MatchWhereBuilder.classEquals} seam — never here.
 *
 * <h2>Single or no edge label — multi-label declines</h2>
 *
 * A single-label hop ({@code out("knows")}) and a label-less hop ({@code out()}, all edge types) both
 * translate. The label-less hop passes a null edge label to the assembler, which the IR renders as the
 * all-edges {@code out('E')} form ({@code E} traversed polymorphically). A multi-label hop ({@code
 * out("a", "b")}) declines: {@code MatchPatternBuilder.addEdge} carries a single edge label with no
 * multi-label slot, so a multi-label hop falls back to the native pipeline.
 */
final class VertexHopRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final VertexHopRecogniser INSTANCE = new VertexHopRecogniser();

  private VertexHopRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof VertexStep<?> hop) || hop.returnsEdge()) {
      return Outcome.DECLINE;
    }
    return GremlinPatternAssembler.claimFoldedHop(hop, ctx);
  }
}
