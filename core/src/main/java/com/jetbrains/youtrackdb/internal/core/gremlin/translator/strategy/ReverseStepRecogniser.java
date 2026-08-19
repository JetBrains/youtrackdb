package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ReverseListShapingOp;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ReverseStep;

/**
 * Recogniser for {@link ReverseStep}: {@code reverse()} registers a per-payload value-transform
 * {@link ListShapingOp} that reverses each projected payload's own value. The contribution is one
 * append and nothing else — no RETURN column, no clause, no boundary re-pin.
 *
 * <h2>It reverses values, not the stream</h2>
 *
 * Native {@code ReverseStep} is a {@code ScalarMapStep}, so {@code g.V().values("name").reverse()}
 * returns each name spelled backwards in arrival order. Reading it as a stream reverse would keep the
 * row count and the payload types intact while returning a different multiset on every shape whose
 * order is observable, which is why the mapping lives in {@link ReverseListShapingOp} beside the arms
 * it mirrors rather than being inferred from the step's name.
 *
 * <h2>Per-payload, so another stage may follow</h2>
 *
 * The stage neither drains nor windows the payload stream, so {@link GremlinStepWalker} admits a further
 * stage behind it: {@code reverse().unfold()} and {@code reverse().fold()} translate, and the carrier is
 * ordered so {@code reverse().unfold()} and {@code unfold().reverse()} stay observably different shapes.
 * What may not follow is any step whose contribution rides the assembled statement, which MATCH applies
 * before the boundary base applies these stages; that gate is the walker's dispatch loop rather than a
 * check here, and {@code GremlinStepWalker.LIST_SHAPING_PER_PAYLOAD_RECOGNISERS} argues the membership.
 *
 * <h2>Post-union, the stage runs once over the concatenation</h2>
 *
 * {@code union(...).reverse()} translates. Reversing a payload's own value reads nothing about the
 * payload's position, so the branch-major concatenation and native's interleaving transform the same
 * payloads into the same multiset. That is why this recogniser sits on
 * {@code GremlinStepWalker.POST_UNION_RECOGNISERS} and answers {@link #selectsPositionally}
 * {@code false} — the field's javadoc carries the comparison against the members that answer
 * {@code true}, and it is the value-transform reading above that makes the answer {@code false}: a
 * stream reverse would be as positional as {@code tail}.
 *
 * <p>The neighbouring cardinality gate owns one more decline — a {@code reverse()} behind a captured
 * cardinality clause — which costs coverage rather than correctness.
 *
 * <p>The one decline this recogniser owns is the combinator channel: a child sub-walk answers
 * {@link RecognitionContext#supportsListShaping()} {@code false}, because its payloads never reach a
 * boundary and a swallowed append would change the child's truth value. That method's javadoc carries
 * the worked case.
 */
final class ReverseStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final ReverseStepRecogniser INSTANCE = new ReverseStepRecogniser();

  private ReverseStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    // Dispatch is by exact runtime class, so this is a fail-safe rather than a live branch — a
    // registry entry pointing the wrong step class here declines instead of mistranslating.
    if (!(step instanceof ReverseStep<?, ?>)) {
      return Outcome.DECLINE;
    }
    // The decline channel: a context whose shaping no boundary base reads cannot carry the stage, and
    // appending anyway would either throw or silently drop it.
    if (!ctx.supportsListShaping()) {
      return Outcome.DECLINE;
    }
    // A fresh instance per recognition, never a shared constant — UnfoldListShapingOp's javadoc
    // carries why identity is what declines a union whose arms each carry a stage.
    ctx.appendListShapingOp(new ReverseListShapingOp());
    return Outcome.ACCEPTED;
  }

  /**
   * {@code false}: the stage rewrites each payload's own value and reads nothing about where that
   * payload sat in the stream, so the branch-major concatenation and native's interleaving transform
   * the same payloads into the same multiset. Stated rather than inherited because this recogniser is
   * on the post-union allow-list, where {@code GremlinStepWalker.POST_UNION_RECOGNISERS} requires
   * every member to answer for itself.
   */
  @Override
  public boolean selectsPositionally(Step<?, ?> step) {
    return false;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    return step instanceof ReverseStep;
  }
}
