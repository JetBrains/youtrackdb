package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.DedupPayloadListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;

/**
 * Recogniser for {@link DedupGlobalStep}: anonymous / current-boundary named {@code dedup} sets
 * {@code RETURN DISTINCT}; {@code values(k).dedup()} / {@code valueMap(…).dedup()} append
 * post-projection payload list-shaping; post-union bare {@code dedup()} becomes a
 * {@link PostConcatOp.Dedup}.
 *
 * <p>{@code dedup().by(prop)} and prior-label {@code dedup(a)} decline: both keep a first-wins
 * survivor in stream order, and MATCH row order is not Gremlin traversal order, so the survivor
 * can diverge when keys collide.
 */
final class DedupGlobalStepRecogniser implements StepRecogniser {

  static final DedupGlobalStepRecogniser INSTANCE = new DedupGlobalStepRecogniser();

  private DedupGlobalStepRecogniser() {
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof DedupGlobalStep<?> dedup)) {
      return Outcome.DECLINE;
    }
    var byChildren = dedup.getLocalChildren();
    if (!byChildren.isEmpty()) {
      // dedup().by(prop): first element per modulator value is order-dependent vs native.
      return Outcome.DECLINE;
    }
    if (ctx.hasUnionCarrier()) {
      return recognizePostUnion(ctx, dedup);
    }
    if (singlePriorScopeAlias(ctx, dedup) != null) {
      // Prior-label dedup(a): boundary survivor per a's RID is order-dependent vs native.
      return Outcome.DECLINE;
    }
    if (ctx.boundaryOutputType() == BoundaryOutputType.SINGLE_VALUE
        || ctx.boundaryOutputType() == BoundaryOutputType.MAP) {
      return recognizeProjectedPayloadDedup(ctx, dedup);
    }
    if (ctx.boundaryOutputType() != BoundaryOutputType.ELEMENT) {
      return Outcome.DECLINE;
    }
    if (!scopeKeysNameOnlyBoundary(ctx, dedup)) {
      return Outcome.DECLINE;
    }
    // FilterRankingStrategy can move as("t") onto DedupGlobalStep; without binding here,
    // a following select("t") sees an unbound label and the whole walk declines.
    var boundary = ctx.boundaryAlias();
    if (boundary == null || !ctx.bindStepLabels(dedup, boundary)) {
      return Outcome.DECLINE;
    }
    ctx.setReturnDistinct(true);
    return Outcome.ACCEPTED;
  }

  private static Outcome recognizePostUnion(RecognitionContext ctx, DedupGlobalStep<?> dedup) {
    if (ctx.boundaryOutputType() != BoundaryOutputType.ELEMENT) {
      return Outcome.DECLINE;
    }
    for (var op : ctx.postConcatOps()) {
      if (op instanceof PostConcatOp.Dedup || op instanceof PostConcatOp.Count) {
        return Outcome.DECLINE;
      }
    }
    if (!scopeKeysNameOnlyBoundary(ctx, dedup)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null || !ctx.bindStepLabels(dedup, boundary)) {
      return Outcome.DECLINE;
    }
    ctx.appendPostConcatOp(PostConcatOp.Dedup.INSTANCE);
    return Outcome.ACCEPTED;
  }

  private static Outcome recognizeProjectedPayloadDedup(
      RecognitionContext ctx, DedupGlobalStep<?> dedup) {
    var scopeKeys = dedup.getScopeKeys();
    if (scopeKeys != null && !scopeKeys.isEmpty()) {
      return Outcome.DECLINE;
    }
    if (!ctx.supportsListShaping()) {
      return Outcome.DECLINE;
    }
    ctx.appendListShapingOp(new DedupPayloadListShapingOp());
    return Outcome.ACCEPTED;
  }

  /**
   * When {@code dedup} names exactly one scope key that resolves to a prior (non-boundary) alias,
   * returns that internal alias; otherwise {@code null}.
   */
  @Nullable private static String singlePriorScopeAlias(RecognitionContext ctx, DedupGlobalStep<?> dedup) {
    var scopeKeys = dedup.getScopeKeys();
    if (scopeKeys == null || scopeKeys.size() != 1) {
      return null;
    }
    var userLabel = scopeKeys.iterator().next();
    var internalAlias = ctx.resolveUserLabel(userLabel);
    if (internalAlias == null) {
      return null;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null || boundary.equals(internalAlias)) {
      return null;
    }
    return internalAlias;
  }

  @Override
  public boolean selectsPositionally(Step<?, ?> step) {
    return false;
  }

  private static boolean scopeKeysNameOnlyBoundary(RecognitionContext ctx,
      DedupGlobalStep<?> dedup) {
    var scopeKeys = dedup.getScopeKeys();
    if (scopeKeys == null || scopeKeys.isEmpty()) {
      return true;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return false;
    }
    for (String userLabel : scopeKeys) {
      var internalAlias = ctx.resolveUserLabel(userLabel);
      if (internalAlias == null || !boundary.equals(internalAlias)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof DedupGlobalStep<?> dedup)) {
      return false;
    }
    var scope = dedup.getScopeKeys();
    if (scope == null || scope.isEmpty()) {
      encoder.appendToken("dk", "-");
      return true;
    }
    encoder.appendStringSeq("dk", new java.util.TreeSet<>(scope));
    return true;
  }
}
