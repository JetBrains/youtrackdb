package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;

/**
 * Recogniser for {@link AndStep}, the {@code ConnectiveStrategy} form of logical AND over child
 * sub-traversals. Each child is driven through the sub-walker ({@link RecognitionContext#walkChild});
 * the whole step declines when any child declines.
 *
 * <ul>
 *   <li><b>Pure-filter children</b> ({@link SubTraversalPredicateAdapter#hasEdges()} {@code false})
 *       — captured alias filters AND-composed into the parent boundary via {@link
 *       RecognitionContext#putAliasFilter}; a boundary re-type from a folded {@code hasLabel(L)}
 *       captured in the child's pattern buffer is committed through {@link RecognitionContext#addNode}.
 *   <li><b>Edge-bearing children</b> decline the whole {@code AndStep}, including a mixed AND whose
 *       other arms are pure filters. Appending the hop would turn the existence test into a join
 *       that emits one row per matching path — see {@link ConnectiveStepSupport#anyEdgeBearing} for
 *       the full reasoning and for why {@code RETURN DISTINCT} is not the repair.
 * </ul>
 */
final class AndStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final AndStepRecogniser INSTANCE = new AndStepRecogniser();

  private AndStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof AndStep<?> andStep)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    var adapters = ConnectiveStepSupport.walkAcceptedChildren(andStep, ctx);
    if (adapters == null) {
      return Outcome.DECLINE;
    }
    // Check every child before committing any of them: a mixed AND must leave the outer context
    // untouched when one arm is edge-bearing, not commit the pure-filter arms and then decline.
    if (ConnectiveStepSupport.anyEdgeBearing(adapters)) {
      return Outcome.DECLINE;
    }
    for (var adapter : adapters) {
      ConnectiveStepSupport.commitPureFilterChild(ctx, adapter);
    }
    return Outcome.ACCEPTED;
  }
}
