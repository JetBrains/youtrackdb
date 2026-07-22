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
 *   <li><b>Edge-bearing children</b> — captured hop fragments appended to the positive pattern via
 *       {@link RecognitionContext#appendPattern}, with any target filters merged through {@link
 *       RecognitionContext#putAliasFilter}.
 *   <li><b>Mixed</b> pure-filter and edge-bearing children in one {@code AndStep} are supported.
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
    for (var adapter : adapters) {
      if (adapter.hasEdges()) {
        ConnectiveStepSupport.commitEdgeBearingChild(ctx, adapter);
      } else {
        ConnectiveStepSupport.commitPureFilterChild(ctx, adapter);
      }
    }
    return Outcome.ACCEPTED;
  }
}
