package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.DedupByModulatorListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;

/**
 * Recogniser for {@link DedupGlobalStep}: {@code dedup()} and named {@code dedup(labels…)} that
 * only name the <em>current</em> boundary alias set {@code RETURN DISTINCT} and leave the existing
 * RETURN / output type alone.
 *
 * <p>Named labels that resolve to a prior hop (or any alias other than the boundary) decline. A
 * {@code by(...)} modulator on a single-plan {@link BoundaryOutputType#ELEMENT} walk registers a
 * post-projection dedup stage; post-union {@code by(...)} still declines.
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
    if (byChildren.size() != 1 || ctx.hasUnionCarrier()) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryOutputType() != BoundaryOutputType.ELEMENT) {
      return Outcome.DECLINE;
    }
    if (!scopeKeysNameOnlyBoundary(ctx, dedup)) {
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
