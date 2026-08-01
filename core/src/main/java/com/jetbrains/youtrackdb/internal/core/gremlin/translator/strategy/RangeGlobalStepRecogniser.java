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

    var normalized = normalize(range);
    if (normalized == null) {
      return Outcome.DECLINE;
    }
    if (normalized.noop()) {
      return Outcome.ACCEPTED;
    }
    if (normalized.skip() > 0) {
      ctx.setSkip(ProjectionExpressionFactories.skip(normalized.skip()));
    }
    if (normalized.limit() >= 0) {
      ctx.setLimit(ProjectionExpressionFactories.limit(normalized.limit()));
    }
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
    var normalized = normalize(range);
    if (normalized == null) {
      return Outcome.DECLINE;
    }
    if (normalized.noop()) {
      return Outcome.ACCEPTED;
    }
    ctx.appendPostConcatOp(new PostConcatOp.Range(normalized.skip(), normalized.limit()));
    return Outcome.ACCEPTED;
  }

  /**
   * The single-plan and post-union branches differ only in where the normalised range goes — SQL
   * {@code SKIP}/{@code LIMIT} clauses versus a {@link PostConcatOp.Range} — so both read the step
   * through here and the normalisation rules stay in one place.
   *
   * @param skip rows to drop before emitting; never negative
   * @param limit rows to emit after the skip, or {@code -1} for unbounded (skip-only)
   * @param noop whether the range drops nothing and bounds nothing, so it needs no clause at all
   */
  private record NormalizedRange(long skip, long limit, boolean noop) {
  }

  /** Normalises the step's low/high pair, or returns {@code null} when the shape must decline. */
  private static NormalizedRange normalize(RangeGlobalStepContract<?> range) {
    var lowObj = range.getLowRange();
    var highObj = range.getHighRange();
    if (lowObj == null || highObj == null) {
      return null;
    }
    long low = lowObj;
    long high = highObj;
    if (low < 0) {
      return null;
    }
    var unboundedHigh = high < 0 || high == Long.MAX_VALUE;
    if (unboundedHigh) {
      // range(0, -1) / skip(0) is a no-op — accept without clauses.
      return new NormalizedRange(low, -1L, low == 0);
    }
    if (high < low) {
      // Native emits no traversers; LIMIT 0 matches that empty result.
      high = low;
    }
    return new NormalizedRange(low, high - low, false);
  }

  private static boolean ctxHasSkipOrLimit(RecognitionContext ctx) {
    return ctx.skip() != null || ctx.limit() != null;
  }
}
