package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;

/**
 * Recogniser for a single folded vertex hop — the bare {@code out(L)} / {@code in(L)} /
 * {@code both(L)} shape, and the adjacent {@code outE(L).inV()} / {@code bothE(L).otherV()}
 * chains that {@code IncidentToAdjacentStrategy} folds down to the same
 * {@link VertexStep} before this walker runs (see {@code FoldedEdgeStepDispatchClassTest}).
 * Each such hop appends one edge and one target node to the pattern and re-pins the boundary
 * to the new target, so a multi-hop chain ({@code g.V().out(L).out(L)}) is a sequence of
 * single-hop claims and the last hop's target becomes the traversal's result.
 *
 * <h2>Bare hop targets root at {@code V} polymorphically — no {@code @class} narrowing</h2>
 *
 * The hop target is registered with the generic vertex class {@code V} and <em>no</em>
 * {@code @class} filter, <em>regardless of {@link WalkerContext#polymorphic}</em>. Native
 * {@code out(L)} never class-filters its target: it follows every {@code L}-labelled edge and
 * yields whatever vertex sits on the other end, whatever its class. Narrowing the target to a
 * concrete class — even under {@code polymorphic=false} — would drop subclass instances the
 * native pipeline keeps (a subclass undercount), so a bare chain hop mirrors the start step, which
 * likewise emits no class filter ({@link StartStepRecogniser}). {@code @class} narrowing reappears
 * only for an explicit user-named class (the folded {@code hasLabel}, added later), produced through
 * the shared {@link MatchClassFilters} seam — never here.
 *
 * <h2>Edge steps delegate to the edge-filter recogniser</h2>
 *
 * The single {@link VertexStep} registry key covers both bare hops (this recogniser's scope) and
 * the non-adjacent edge step of the {@code outE(L).has(...).inV()} shape — TinkerPop models the
 * latter as a {@code VertexStep} with {@code returnsEdge() == true}. Because the registry keys one
 * recogniser per class, this recogniser owns the class: it handles the bare-hop branch ({@code
 * returnsEdge() == false}) itself and delegates the edge-returning branch to {@link
 * EdgeStepRecogniser}, which either claims the whole {@code outE(L).has(...).inV()} chain or declines
 * (leaving the traversal on the native pipeline under all-or-nothing).
 *
 * <h2>Single edge label only</h2>
 *
 * Only a single-label hop ({@code out("knows")}) translates. A label-less hop ({@code out()},
 * all edge types) and a multi-label hop ({@code out("a", "b")}) both decline: multi-label edge
 * traversal is out of scope for Phase 1, and a label-less hop is declined rather than modelled as
 * an unlabelled MATCH edge so this recogniser stays inside the plan's {@code out(L)} scope. Both
 * shapes fall back to the native pipeline unchanged.
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
    // returnsEdge() == true is the non-adjacent edge step (outE(L) in outE(L).has(...).inV()). The
    // registry keys one recogniser per class and both shapes are a VertexStep, so this recogniser
    // owns the class and delegates the edge-returning branch to the edge-filter recogniser. That
    // recogniser peeks ahead for the has()/closing-hop window and either claims the whole chain or
    // declines (e.g. a bare outE(L) terminal with no closing hop) — its decline is a no-op on ctx,
    // preserving no-mutation-on-decline.
    if (vertexStep.returnsEdge()) {
      return EdgeStepRecogniser.INSTANCE.recognize(vertexStep, ctx);
    }
    // A hop with no boundary to hang off cannot be translated: the "from" endpoint is the current
    // terminator's alias, pinned by the start step (or a prior hop). A null here would mean a
    // VertexStep reached the walker before any node was pinned — decline defensively rather than
    // build a dangling edge.
    if (ctx.boundaryAlias == null) {
      return 0;
    }
    // Only a single-label hop is in scope. A label-less hop (out(), all edge types) and a
    // multi-label hop (out("a", "b")) both decline — see the class Javadoc "Single edge label".
    var edgeLabels = vertexStep.getEdgeLabels();
    if (edgeLabels.length != 1) {
      return 0;
    }
    var edgeLabel = edgeLabels[0];
    if (edgeLabel == null || edgeLabel.isBlank()) {
      return 0;
    }
    // Map the TinkerPop traversal direction onto the pattern-builder direction.
    var direction = GremlinPatternAssembler.toBuilderDirection(vertexStep.getDirection());

    // Validation done; commit mutations. The target is a fresh anonymous alias so a multi-hop
    // chain gets distinct intermediate-node names. The assembler appends the folded hop (edge +
    // target node under the generic V class, no @class filter — no subclass undercount) and re-pins
    // the boundary / single RETURN column to the new target.
    var fromAlias = ctx.boundaryAlias;
    var targetAlias = ctx.nextAnonVertexAlias();
    GremlinPatternAssembler.appendFoldedHop(ctx, fromAlias, targetAlias, direction, edgeLabel);

    // Report the one folded step this hop consumed; the walker advances the cursor. Every context
    // mutation above happened before this commit point, so a decline earlier leaves ctx untouched.
    return 1;
  }
}
