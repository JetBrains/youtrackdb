package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepContract;

/**
 * Recogniser for {@code RangeGlobalStep} / {@code RangeGlobalStepPlaceholder}: {@code limit(n)},
 * {@code skip(n)}, and {@code range(low, high)} map to {@code SQLSkip}/{@code SQLLimit} on a
 * single-plan walk, or to a {@link PostConcatOp.Range} after a union (early-stop on the
 * concatenator).
 */
final class RangeGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final RangeGlobalStepRecogniser INSTANCE = new RangeGlobalStepRecogniser();

  private RangeGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof RangeGlobalStepContract<?> range)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    if (ctx.hasUnionCarrier()) {
      return recognizePostUnion(ctx, range);
    }
    // A second range/limit/skip has no clear MATCH composition rule in Phase 1.
    if (ctxHasSkipOrLimit(ctx)) {
      return Outcome.DECLINE;
    }

    var lowObj = range.getLowRange();
    var highObj = range.getHighRange();
    if (lowObj == null || highObj == null) {
      return Outcome.DECLINE;
    }
    long low = lowObj;
    long high = highObj;
    if (low < 0) {
      return Outcome.DECLINE;
    }

    var unboundedHigh = high < 0 || high == Long.MAX_VALUE;
    if (unboundedHigh) {
      if (low == 0) {
        // range(0, -1) / skip(0) is a no-op — accept without clauses.
        return Outcome.ACCEPTED;
      }
      ctx.setSkip(ProjectionExpressionFactories.skip(low));
      return Outcome.ACCEPTED;
    }
    if (high < low) {
      // Native emits no traversers; LIMIT 0 matches that empty result.
      high = low;
    }
    long limit = high - low;
    if (low > 0) {
      ctx.setSkip(ProjectionExpressionFactories.skip(low));
    }
    ctx.setLimit(ProjectionExpressionFactories.limit(limit));
    return Outcome.ACCEPTED;
  }

  private static Outcome recognizePostUnion(
      RecognitionContext ctx, RangeGlobalStepContract<?> range) {
    for (var op : ctx.postConcatOps()) {
      if (op instanceof PostConcatOp.Range || op instanceof PostConcatOp.Count) {
        // Second range, or range after count: decline (count already collapsed the stream).
        return Outcome.DECLINE;
      }
    }
    var lowObj = range.getLowRange();
    var highObj = range.getHighRange();
    if (lowObj == null || highObj == null) {
      return Outcome.DECLINE;
    }
    long low = lowObj;
    long high = highObj;
    if (low < 0) {
      return Outcome.DECLINE;
    }
    var unboundedHigh = high < 0 || high == Long.MAX_VALUE;
    if (unboundedHigh) {
      if (low == 0) {
        return Outcome.ACCEPTED;
      }
      ctx.appendPostConcatOp(new PostConcatOp.Range(low, -1L));
      return Outcome.ACCEPTED;
    }
    if (high < low) {
      high = low;
    }
    ctx.appendPostConcatOp(new PostConcatOp.Range(low, high - low));
    return Outcome.ACCEPTED;
  }

  private static boolean ctxHasSkipOrLimit(RecognitionContext ctx) {
    return ctx.skip() != null || ctx.limit() != null;
  }
}
