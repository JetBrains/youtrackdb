package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;

/**
 * Recogniser for {@link WhereTraversalStep} — the positive counterpart of {@code not(traversal)}: a
 * child sub-traversal that must yield at least one result for the current row to pass. Pure-filter
 * children merge into the boundary alias {@code WHERE}; edge-bearing children decline the whole
 * filter, because appending the hop would emit one row per matching path instead of testing
 * existence (see {@link ConnectiveStepSupport#anyEdgeBearing}). Both paths are shared with {@link
 * AndStepRecogniser} through {@link ConnectiveStepSupport#commitPositiveFilterChild}.
 *
 * <p>Neither path is reachable from a traversal today. TinkerPop builds this step class only when
 * the {@code where} child carries a start or end label, and it holds that binding in a scope step
 * inside the child: a {@code WhereStartStep} for a labelled start, a {@code WhereEndStep} for a
 * labelled end, and, when the child is headed by a connective, the scope step inside one of its
 * arms. {@link GremlinStepWalker} registers a recogniser for neither class, so the child sub-walk
 * declines under all three shapes before this recogniser classifies anything. The recogniser is kept
 * because the binding is
 * resolvable in principle: once the walker maps a scope label to its alias, this class is where the
 * resolved shape lands, and the gates below are the ones it will need. See the transparency set's
 * Javadoc in {@link GremlinStepWalker} for why skipping the scope steps is not an option.
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
