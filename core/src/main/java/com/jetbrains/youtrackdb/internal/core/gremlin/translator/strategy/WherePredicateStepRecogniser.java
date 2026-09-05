package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;

/**
 * Recogniser for {@link WherePredicateStep} ({@code where(P)} / {@code where(startLabel, P)}): compares
 * Gremlin step labels via {@code $matched.<label>} accessors in the boundary alias {@code WHERE}. The
 * sub-walker does not apply — the step carries a predicate plus label references, not a child traversal.
 * {@code modulateBy} children ({@code where(P).by(...)} property projections) decline.
 */
final class WherePredicateStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final WherePredicateStepRecogniser INSTANCE = new WherePredicateStepRecogniser();

  /** Stateless builder for WHERE wrapping; construction is trivial so a shared instance is fine. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private WherePredicateStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof WherePredicateStep<?> whereStep)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }

    // Property projections via modulateBy are out of scope for Phase 1.
    if (!whereStep.getLocalChildren().isEmpty()) {
      return Outcome.DECLINE;
    }

    var predicate = whereStep.getPredicate().orElse(null);
    if (predicate == null) {
      return Outcome.DECLINE;
    }

    var startLabel = whereStep.getStartKey().orElse(null);
    GremlinPredicateAdapter.PropertyTypeGate typeGate =
        GremlinPredicateAdapter.schemaGate(ctx, ctx.boundaryClassName());

    // The label goes through the walker's label-to-alias map: $matched rows are keyed on pattern
    // aliases, and a Gremlin as(...) label is never one of them. An unresolved label yields a null
    // filter and declines, rather than an accessor that reads nothing and drops or keeps every
    // candidate depending on how the comparison treats a missing operand.
    var expr =
        GremlinPredicateAdapter.INSTANCE.toMatchedLabelFilter(
            startLabel, predicate, typeGate, ctx::resolveUserLabel);
    if (expr == null) {
      return Outcome.DECLINE;
    }

    ctx.putAliasFilter(boundary, WHERE.wrap(expr));
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof WherePredicateStep<?> whereStep)) {
      return false;
    }
    encoder.appendToken("ws", whereStep.getStartKey().orElse("-"));
    encoder.appendPredicate(whereStep.getPredicate().orElse(null), true);
    return true;
  }
}
