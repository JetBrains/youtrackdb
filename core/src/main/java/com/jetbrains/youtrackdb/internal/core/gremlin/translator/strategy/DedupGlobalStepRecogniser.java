package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.PostConcatOp;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;

/**
 * Recogniser for {@link DedupGlobalStep}: {@code dedup()} and named {@code dedup(labels…)} that
 * only name the <em>current</em> boundary alias set {@code RETURN DISTINCT} and leave the existing
 * RETURN / output type alone.
 *
 * <p>Named labels that resolve to a prior hop (or any alias other than the boundary), and any
 * {@code by(...)} modulator, decline: MATCH {@code DISTINCT} applies to the whole RETURN row and
 * cannot express Gremlin's "unique by path label / modulator, emit current traverser" contract.
 * Rewriting RETURN to the dedup keys while keeping {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#ELEMENT} was
 * wrong — {@code projectElement} looks up the boundary alias and emitted null payloads.
 */
final class DedupGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final DedupGlobalStepRecogniser INSTANCE = new DedupGlobalStepRecogniser();

  private DedupGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof DedupGlobalStep<?> dedup)) {
      return Outcome.DECLINE;
    }
    // by(...) needs DISTINCT-ON-style keys while still emitting the current element — not MATCH.
    if (!dedup.getLocalChildren().isEmpty()) {
      return Outcome.DECLINE;
    }
    if (ctx.hasUnionCarrier()) {
      return recognizePostUnion(ctx, dedup);
    }
    // A value / map / scalar projection has already replaced RETURN with a boundary presence column
    // (e.g. values("k") → RETURN $b, $b.k), so RETURN DISTINCT would dedup on (entity, value); the
    // per-row unique entity defeats it and nothing is deduplicated. Decline to native, which dedups
    // the projected values. dedup() while still ELEMENT stays here and dedups the boundary vertices.
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
      // A second dedup is redundant but harmless to decline; a dedup after count has nothing left
      // to dedup, because count already collapsed the concatenation to one scalar row.
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
   * {@code dedup()} keeps one row per distinct boundary element, and the surviving set is the same
   * whichever order the union's arms arrived in — which duplicate survives is not observable once
   * {@link #recognize} has refused every {@code by(...)} modulator and every non-boundary scope
   * key. Stated rather than inherited because this recogniser sits on {@link GremlinStepWalker}'s
   * post-union allow-list.
   */
  @Override
  public boolean selectsPositionally(Step<?, ?> step) {
    return false;
  }

  /**
   * Whether {@code dedup}'s scope keys are all the current boundary alias, so deduplicating on them
   * is deduplicating on the emitted element. An unlabelled {@code dedup()} trivially qualifies. Both
   * the single-plan ({@code RETURN DISTINCT}) and the post-union ({@link PostConcatOp.Dedup})
   * branches gate on this, so the two declines below stay stated once.
   */
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
      // An unknown user label names nothing this walk bound, so its uniqueness contract is
      // unexpressible here.
      if (internalAlias == null) {
        return false;
      }
      // Prior-hop labels would change uniqueness without changing the emitted object — decline
      // rather than rewrite RETURN (which broke ELEMENT projection) or emit the wrong element.
      if (!boundary.equals(internalAlias)) {
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
