package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.UnfoldListShapingOp;
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
 * <p>The other two declines are the neighbouring gates' rather than this recogniser's. An
 * {@code unfold} after a {@code union(...)} declines through the post-union suffix gate — coverage lost
 * rather than correctness, since a per-payload stage over the concatenation and one per arm coincide —
 * and an {@code unfold} behind a captured {@code SKIP} / {@code LIMIT} / {@code RETURN DISTINCT}
 * declines through the cardinality gate.
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
}
