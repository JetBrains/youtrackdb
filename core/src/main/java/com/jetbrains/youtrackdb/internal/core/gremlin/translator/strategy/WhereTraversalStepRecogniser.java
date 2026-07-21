package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;

/**
 * Recogniser for {@link WhereTraversalStep} — the positive counterpart of {@link NotStep}: a child
 * sub-traversal that must yield at least one result for the current row to pass. Pure-filter children
 * merge into the boundary alias {@code WHERE}; edge-bearing children append hop fragments to the
 * positive pattern (the same commit paths as {@link AndStepRecogniser}'s single child).
 */
final class WhereTraversalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final WhereTraversalStepRecogniser INSTANCE = new WhereTraversalStepRecogniser();

  private WhereTraversalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof WhereTraversalStep<?> whereStep)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }

    var children = whereStep.getLocalChildren();
    if (children.size() != 1) {
      return Outcome.DECLINE;
    }

    var adapter = ctx.walkChild(children.getFirst());
    return ConnectiveStepSupport.commitPositiveFilterChild(ctx, adapter);
  }
}
