package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.DedupByModulatorListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.DedupPayloadListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;

/**
 * Recogniser for {@link DedupGlobalStep}: anonymous / current-boundary named {@code dedup} sets
 * {@code RETURN DISTINCT}; {@code values(k).dedup()} and {@code dedup().by(prop)} append post-
 * projection list-shaping; prior-label {@code dedup(a)} keeps the first row per prior alias RID
 * then emits the current boundary element; post-union bare {@code dedup()} becomes a
 * {@link PostConcatOp.Dedup} and post-union {@code dedup().by(prop)} reuses the list-shaping path.
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
      return recognizeDedupBy(ctx, dedup, byChildren);
    }
    if (ctx.hasUnionCarrier()) {
      return recognizePostUnion(ctx, dedup);
    }
    var priorAlias = singlePriorScopeAlias(ctx, dedup);
    if (priorAlias != null) {
      return recognizePriorLabelDedup(ctx, priorAlias);
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

  private static Outcome recognizeDedupBy(
      RecognitionContext ctx,
      DedupGlobalStep<?> dedup,
      java.util.List<? extends Traversal<?, ?>> byChildren) {
    if (byChildren.size() != 1) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryOutputType() != BoundaryOutputType.ELEMENT) {
      return Outcome.DECLINE;
    }
    if (!scopeKeysNameOnlyBoundary(ctx, dedup)) {
      return Outcome.DECLINE;
    }
    if (ctx.hasUnionCarrier()) {
      for (var op : ctx.postConcatOps()) {
        if (op instanceof PostConcatOp.Dedup || op instanceof PostConcatOp.Count) {
          return Outcome.DECLINE;
        }
      }
    }
    if (!ctx.supportsListShaping()) {
      return Outcome.DECLINE;
    }
    var modulatorKey =
        ByModulatorTranslator.translateDedupModulatorKey(byChildren.getFirst().asAdmin());
    if (modulatorKey.isEmpty()) {
      return Outcome.DECLINE;
    }
    ctx.appendListShapingOp(new DedupByModulatorListShapingOp(modulatorKey.get()));
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

  /**
   * Unique-by prior alias RID, emit the current boundary element. The prior column must appear in
   * RETURN so the post-plan stream filter can read its identity; projection still emits only the
   * boundary entity.
   */
  private static Outcome recognizePriorLabelDedup(RecognitionContext ctx, String priorAlias) {
    if (ctx.boundaryOutputType() != BoundaryOutputType.ELEMENT) {
      return Outcome.DECLINE;
    }
    if (ctx.rowDedupAlias() != null) {
      return Outcome.DECLINE;
    }
    ensurePriorReturnColumn(ctx, priorAlias);
    ctx.setRowDedupAlias(priorAlias);
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
   * returns that internal alias; otherwise {@code null} (empty scope, boundary-only names, unbound
   * labels, or multi-key scopes fall through to other arms).
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

  private static void ensurePriorReturnColumn(RecognitionContext ctx, String priorAlias) {
    ctx.markReturnAliasIfForeign(priorAlias);
    if (ctx instanceof WalkerContext wc && alreadyReturnsAlias(wc, priorAlias)) {
      return;
    }
    ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(priorAlias), priorAlias);
  }

  private static boolean alreadyReturnsAlias(WalkerContext ctx, String alias) {
    for (var columnAlias : ctx.returnAliases) {
      if (columnAlias != null && alias.equals(columnAlias.getStringValue())) {
        return true;
      }
    }
    return false;
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
