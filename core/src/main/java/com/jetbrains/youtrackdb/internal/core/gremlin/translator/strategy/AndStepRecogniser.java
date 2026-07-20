package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.ConnectiveStep;

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
    var decline = walkAndCommit(andStep, ctx);
    return decline != null ? decline : Outcome.ACCEPTED;
  }

  private static Outcome walkAndCommit(ConnectiveStep<?> connective, RecognitionContext ctx) {
    var children = connective.getLocalChildren();
    if (children.isEmpty()) {
      return Outcome.DECLINE;
    }
    // Walk every child before committing anything — a declined child must leave the outer context
    // untouched (the sub-walk capture boundary), mirroring HasStepRecogniser's
    // translate-all-then-contribute shape.
    var adapters = new ArrayList<SubTraversalPredicateAdapter>(children.size());
    for (var child : children) {
      var adapter = ctx.walkChild(child);
      if (adapter.outcome() != Outcome.ACCEPTED) {
        return Outcome.DECLINE;
      }
      adapters.add(adapter);
    }
    for (var adapter : adapters) {
      if (adapter.hasEdges()) {
        ConnectiveStepSupport.commitEdgeBearingChild(ctx, adapter);
      } else {
        ConnectiveStepSupport.commitPureFilterChild(ctx, adapter);
      }
    }
    return null;
  }
}
