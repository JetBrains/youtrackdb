package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;

/**
 * Recogniser for {@link WhereTraversalStep} — the positive counterpart of {@code not(traversal)}: a
 * child sub-traversal that must yield at least one result for the current row to pass. Pure-filter
 * children merge into the boundary alias {@code WHERE}; edge-bearing children decline the whole
 * filter, because appending the hop would emit one row per matching path instead of testing
 * existence (see {@link ConnectiveStepSupport#anyEdgeBearing}). Both paths are shared with {@link
 * AndStepRecogniser} through {@link ConnectiveStepSupport#commitPositiveFilterChild}.
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
