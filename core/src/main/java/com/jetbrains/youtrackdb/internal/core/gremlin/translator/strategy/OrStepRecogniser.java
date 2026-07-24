package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;

/**
 * Recogniser for {@link OrStep}, the {@code ConnectiveStrategy} form of logical OR over child
 * sub-traversals. Every child must be pure-filter ({@link SubTraversalPredicateAdapter#hasEdges()}
 * {@code false}); the step declines when any child is edge-bearing or when any child sub-walk
 * declines.
 *
 * <p>Captured filters are composed with {@link MatchWhereBuilder#or} and committed once on the
 * boundary alias. Individual {@link RecognitionContext#putAliasFilter} calls are not used for OR
 * children — that API AND-composes same-alias contributions. Boundary re-types from a child's
 * {@code hasLabel(L)} are folded into that child's OR operand as {@code classEquals} (including under
 * polymorphic mode, where {@code hasLabel} otherwise emits no WHERE) so label discrimination is not
 * dropped.
 */
final class OrStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final OrStepRecogniser INSTANCE = new OrStepRecogniser();

  /** Stateless builder for OR composition and WHERE wrapping. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private OrStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof OrStep<?> orStep)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var merged = ConnectiveStepSupport.collectOrExpressions(orStep, ctx, boundary);
    if (merged == null) {
      return Outcome.DECLINE;
    }
    ctx.putAliasFilter(boundary, WHERE.wrap(merged));
    return Outcome.ACCEPTED;
  }
}
