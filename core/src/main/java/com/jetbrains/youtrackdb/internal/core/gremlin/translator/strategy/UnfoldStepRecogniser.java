package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.UnfoldListShapingOp;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.UnfoldStep;

/**
 * Recogniser for {@link UnfoldStep}: {@code unfold()} registers a per-payload flat-map
 * {@link ListShapingOp} that expands each projected payload into the payloads it contains. The
 * contribution is one append and nothing else — no RETURN column, no clause, no boundary re-pin — so
 * the stage expands whatever the preceding step projected, and the five shapes it distinguishes are
 * {@link UnfoldListShapingOp}'s business rather than this recogniser's.
 *
 * <h2>Per-payload, so another stage may follow</h2>
 *
 * A flat-map is one more stage on the payload stream rather than a clause on the statement, and it
 * neither drains nor windows that stream, so {@link GremlinStepWalker} admits a further stage behind
 * it: {@code unfold().reverse()} and {@code unfold().fold()} translate. What may not follow is any step
 * whose contribution rides the assembled statement — MATCH applies the statement as the plan runs,
 * strictly before the boundary base applies these stages, so {@code unfold().dedup()} would emit
 * {@code RETURN DISTINCT} over the rows the expansion was meant to consume. That gate is the walker's
 * dispatch loop rather than a check here, and it covers the recognisers written after this one too;
 * {@code GremlinStepWalker.capturedListShapingOp} and
 * {@code GremlinStepWalker.LIST_SHAPING_PER_PAYLOAD_RECOGNISERS} carry the rule and the memberships.
 *
 * <h2>Post-union, the stage runs once over the concatenation</h2>
 *
 * {@code union(...).unfold()} translates. A per-payload stage over the concatenation and the same
 * stage applied to each arm's payloads produce the same multiset, so the one ordering fact the
 * multi-plan boundary and native disagree about — which arm's rows arrive first — cannot reach the
 * answer. That is why this recogniser sits on {@code GremlinStepWalker.POST_UNION_RECOGNISERS} and
 * answers {@link #selectsPositionally} {@code false}; the field's javadoc carries the comparison
 * against the members that answer {@code true}.
 *
 * <p>The remaining decline is the cardinality gate's rather than this recogniser's: an
 * {@code unfold} behind a captured {@code SKIP} / {@code LIMIT} / {@code RETURN DISTINCT} declines
 * there.
 *
 * <p>The one decline this recogniser owns is the combinator channel: a child sub-walk answers
 * {@link RecognitionContext#supportsListShaping()} {@code false}, because its payloads never reach a
 * boundary and a swallowed append would change the child's truth value. That method's javadoc carries
 * the worked case.
 */
final class UnfoldStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final UnfoldStepRecogniser INSTANCE = new UnfoldStepRecogniser();

  private UnfoldStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    // Dispatch is by exact runtime class, so this is a fail-safe rather than a live branch — a
    // registry entry pointing the wrong step class here declines instead of mistranslating.
    if (!(step instanceof UnfoldStep<?, ?>)) {
      return Outcome.DECLINE;
    }
    // The decline channel: a context whose shaping no boundary base reads cannot carry the stage, and
    // appending anyway would either throw or silently drop it.
    if (!ctx.supportsListShaping()) {
      return Outcome.DECLINE;
    }
    // A fresh instance per recognition, never a shared constant — UnfoldListShapingOp's javadoc
    // carries why identity is what declines a union whose arms each carry a stage.
    ctx.appendListShapingOp(new UnfoldListShapingOp());
    return Outcome.ACCEPTED;
  }

  /**
   * {@code false}: expanding a payload reads nothing about where that payload sat in the stream, so
   * the branch-major concatenation and native's interleaving expand the same payloads into the same
   * multiset. Stated rather than inherited because this recogniser is on the post-union allow-list,
   * where {@code GremlinStepWalker.POST_UNION_RECOGNISERS} requires every member to answer for
   * itself.
   */
  @Override
  public boolean selectsPositionally(Step<?, ?> step) {
    return false;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    return step instanceof UnfoldStep;
  }
}
