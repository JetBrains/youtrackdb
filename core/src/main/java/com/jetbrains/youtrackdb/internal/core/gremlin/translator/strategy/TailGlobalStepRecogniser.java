package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.TailListShapingOp;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TailGlobalStepContract;

/**
 * Recogniser for {@code tail(n)} in both its forms: {@code TailGlobalStep} and the GValue-bearing
 * {@code TailGlobalStepPlaceholder}, which {@link TailGlobalStepContract#CONCRETE_STEPS} enumerates and
 * {@link GremlinStepWalker} registers from rather than from two hand-written literals. The step
 * registers a window {@link ListShapingOp} that keeps the last {@code n} projected payloads in arrival
 * order; the contribution is one append and nothing else — no RETURN column, no clause, no boundary
 * re-pin.
 *
 * <h2>A window, so nothing may follow it</h2>
 *
 * {@code tail(n)} takes N payloads to a bounded few, so a stage behind it would reshape an output nobody
 * wrote a stage for and a clause behind it would ride the assembled statement, which MATCH applies
 * strictly before the boundary base applies the window. {@code tail(3).unfold()} and
 * {@code tail(3).count()} therefore decline. That rule is {@link GremlinStepWalker}'s dispatch loop
 * rather than a check here, through {@code LIST_SHAPING_DRAIN_RECOGNISERS} and the loop's drain latch,
 * so it covers the recognisers written after this one too.
 *
 * <p>The window itself does sit behind a row-dropping projection safely, which is worth naming because
 * the neighbouring {@code LIMIT} does not. {@code values(key)} drops absent-property rows in the
 * boundary's own payload projection, and the window runs after that projection, so
 * {@code values(k).tail(2)} takes the last two <em>survivors</em> — which is what Gremlin does with that
 * spelling. A statement-level {@code LIMIT} counts rows the drop has not removed yet, which is the
 * divergence {@code RangeGlobalStepRecogniser} declines on {@code dropsRowsOnAbsentProperty}.
 *
 * <h2>Post-union, the window is refused because it reads positions</h2>
 *
 * {@code union(...).tail(n)} declines, and correctly — the concatenation emits one arm's rows and then
 * the next while native interleaves them, so a window over the end of the concatenation keeps
 * different payloads than native's window keeps. This recogniser is nonetheless <em>on</em>
 * {@code GremlinStepWalker.POST_UNION_RECOGNISERS}: that list is the first of two conditions, and the
 * second is {@link #selectsPositionally}, which this recogniser answers {@code true}. Membership
 * without the positional answer would ship the divergence; the positional answer without membership
 * would decline one spelling more than it needs to. Together they leave {@code tail} reaching the fork
 * only where the walker's look-ahead sees an immediate {@code count()} behind it, and that spelling
 * declines for its own reason at the list-shaping gate, so no post-union {@code tail} translates today.
 *
 * <p>The neighbouring cardinality gate owns one more decline — a {@code tail} behind a captured
 * {@code SKIP} / {@code LIMIT} / {@code RETURN DISTINCT} — which costs coverage rather than
 * correctness.
 *
 * <h2>{@code n = 0} translates, {@code n < 0} declines</h2>
 *
 * A zero window emits nothing, which is native's answer as well: {@code TailGlobalStep} trims its deque
 * back to the limit after each add, so a limit of zero trims everything. A negative window has no
 * meaning to reproduce, and TinkerPop does not reject one at construction — {@code tail(-1)} builds —
 * so this recogniser declines it and the traversal keeps whatever native makes of it.
 *
 * <h2>Reading the limit without pinning a variable this walk declines</h2>
 *
 * {@code TailGlobalStepPlaceholder.getLimit()} is not a pure read: on a variable GValue it calls
 * {@code traversal.getGValueManager().pinVariable(name)} before returning the concrete {@code Long},
 * which is TinkerPop's own signal that some consumer has baked the value in. So the order of reads is a
 * decision rather than a detail, and this recogniser settles it as follows.
 *
 * <p><b>Every decline branch reads {@link TailGlobalStepContract#getLimitAsGValue()}, which is a pure
 * field read on the placeholder and a wrapper around the concrete field on the other form.</b> The
 * pinning {@code getLimit()} is reached only once the shape is going to be accepted, so a declined
 * traversal leaves the GValue manager exactly as it found it. {@code RangeGlobalStepRecogniser} reads its
 * pinning accessor ahead of its own decline branches, which is the one part of that precedent not copied
 * here.
 *
 * <p><b>A variable limit is accepted, not declined.</b> Declining it would cost the whole traversal's
 * plan rather than the window alone — the walk is all-or-nothing — which is the price the slice recogniser
 * refuses to pay for a parameterised {@code limit(n)} as well. The pin on the accept path is what makes
 * that safe: the window size becomes plain state inside a stage that is rebuilt on every strategy
 * application, and pinning declares the variable consumed rather than free to be re-bound underneath it.
 * Nothing else in the translation reads the limit — it never reaches the statement, so it never reaches
 * the plan-cache fingerprint either, and {@code g.V().tail(3)} legitimately shares a cached plan with
 * {@code g.V().tail(5)} because each compilation applies its own stage over the plan they share.
 *
 * <p>The one decline this recogniser owns beyond the limit's shape is the combinator channel: a child
 * sub-walk answers {@link RecognitionContext#supportsListShaping()} {@code false}, because its payloads
 * never reach a boundary and a swallowed append would change the child's truth value.
 */
final class TailGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final TailGlobalStepRecogniser INSTANCE = new TailGlobalStepRecogniser();

  private TailGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    // Dispatch is by exact runtime class over CONCRETE_STEPS, so this is a fail-safe rather than a live
    // branch — a registry entry pointing the wrong step class here declines instead of mistranslating.
    // Matched on the contract because both registered forms implement it and only it.
    if (!(step instanceof TailGlobalStepContract<?> tail)) {
      return Outcome.DECLINE;
    }
    // The decline channel: a context whose shaping no boundary base reads cannot carry the stage, and
    // appending anyway would either throw or silently drop it.
    if (!ctx.supportsListShaping()) {
      return Outcome.DECLINE;
    }
    // Validate through the pure accessor so nothing below pins a variable the walk then declines — see
    // the class Javadoc's "Reading the limit without pinning a variable this walk declines".
    var limitAsGValue = tail.getLimitAsGValue();
    if (limitAsGValue == null) {
      return Outcome.DECLINE;
    }
    Long candidate = limitAsGValue.get();
    if (candidate == null || candidate < 0) {
      return Outcome.DECLINE;
    }
    // Committed: read through getLimit() so the placeholder form's pinVariable side effect lands exactly
    // where the value is baked into the stage. The two reads come off the same field, so the assert is a
    // contract check on the fork rather than a guard against a live disagreement.
    long window = tail.getLimit();
    assert window == candidate
        : "tail's GValue read " + candidate + " disagrees with its concrete read " + window;
    // A fresh instance per recognition, never a shared constant — TailListShapingOp's javadoc carries why
    // identity, and not just the limit, is what declines a union whose arms each carry a window.
    ctx.appendListShapingOp(new TailListShapingOp(window));
    return Outcome.ACCEPTED;
  }

  /**
   * {@code true} for every window, including {@code tail(0)}. The window is defined by where a
   * payload sits in the stream, and the multi-plan boundary hands the stage a branch-major
   * concatenation where native hands it an interleaving, so the last {@code n} payloads differ
   * between the two. Answered on the step's shape rather than on its limit: {@code tail(0)} keeps
   * nothing under either order and so could safely answer {@code false}, but that special case buys
   * one degenerate spelling and costs the reader the rule.
   */
  @Override
  public boolean selectsPositionally(Step<?, ?> step) {
    return true;
  }
}
