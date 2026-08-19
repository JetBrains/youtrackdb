package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.FoldListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;

/**
 * Recogniser for the list form of {@link FoldStep}: {@code fold()} registers a drain
 * {@link ListShapingOp} that collects the projected payload stream into one {@code List} payload. The
 * contribution is one append and nothing else — no RETURN column, no clause, no boundary re-pin — so
 * the fold builds its list out of whatever the preceding step already projected, and an empty stream
 * still yields the one empty list native produces.
 *
 * <h2>Two steps behind one class</h2>
 *
 * {@code FoldStep} carries both {@code fold()} and the seeded reduce {@code fold(seed, operator)},
 * told apart by {@link FoldStep#isListFold()}. Dispatch keys on the runtime class, so this recogniser
 * is handed both and declines the reduce: mapping it onto the drain would turn
 * {@code g.V().values("age").fold(0, Operator.sum)} from one summed scalar into a list of ages —
 * translated rather than declined, and therefore silent.
 *
 * <h2>Where the position rule lives</h2>
 *
 * A fold is accepted only as the last step, and that rule is {@link GremlinStepWalker}'s dispatch
 * loop rather than a check here. A row-level clause a following step writes rides the assembled
 * statement, which MATCH applies as the plan runs, while this stage runs afterwards over the payload
 * stream the projection built — so {@code fold().count()} would count the rows the fold was meant to
 * consume and {@code fold().limit(2)} would slice them. The loop refuses any step dispatched behind a
 * captured stage (see {@code GremlinStepWalker.capturedListShapingOp}), which covers every such
 * suffix at once and covers the ones written after this recogniser too. Two neighbouring gates in the
 * same loop finish the picture:
 *
 * <ul>
 *   <li>A fold after a {@code union(...)} declines through the post-union suffix gate, and that is the
 *       right answer — one list per arm and one list over the concatenation differ, and native
 *       interleaves the arms besides. The next section says how this recogniser is kept off that
 *       path.
 *   <li>A fold behind a captured {@code SKIP} / {@code LIMIT} / {@code RETURN DISTINCT} declines
 *       through the cardinality gate, which is stricter than it has to be: the clause runs in the
 *       statement and the stage runs after it, exactly as Gremlin orders {@code limit(2).fold()}. The
 *       shape loses coverage, not correctness.
 * </ul>
 *
 * <h2>Why there is no {@code selectsPositionally} override here</h2>
 *
 * The post-union gate has two conditions, and this recogniser is kept off the path by the first of
 * them: it is absent from {@code GremlinStepWalker.POST_UNION_RECOGNISERS}, so the second condition —
 * {@link StepRecogniser#selectsPositionally}, which every member of that list must answer for itself —
 * is never asked, and the interface default stands unoverridden on purpose. The alternative shape was
 * membership plus a {@code true} answer, which declines {@code union(...).fold()} the same way and in
 * addition admits {@code union(...).fold().count()} as far as the fork. That spelling then declines at
 * the list-shaping gate anyway, because {@code count} writes {@code count(*)} into the statement and
 * MATCH applies the statement before this stage runs. So the two shapes differ in nothing a caller can
 * observe, and the absence is the one that needs no second rule to be read alongside it. The
 * allow-list's own javadoc carries the same decision from the list's side, which is where a reader
 * adding a member will be looking.
 *
 * <p>The third decline is the combinator channel: a child sub-walk answers
 * {@link RecognitionContext#supportsListShaping()} {@code false}, because its payloads never reach a
 * boundary and a swallowed append would change the child's truth value. That method's javadoc carries
 * the worked case.
 *
 * <h2>Nothing reaches the statement, so nothing reaches the cache key</h2>
 *
 * {@code g.V()} and {@code g.V().fold()} produce identical {@code MatchPlanInputs} and therefore the
 * same {@link GremlinPlanCache} fingerprint. They legitimately share one execution plan: the shaping
 * is not part of the cached artifact, travelling instead on the boundary step the strategy splices per
 * compilation, so each traversal applies its own stages over the plan they share.
 */
final class FoldStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final FoldStepRecogniser INSTANCE = new FoldStepRecogniser();

  private FoldStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    // Dispatch is by exact runtime class, so this is a fail-safe rather than a live branch — a
    // registry entry pointing the wrong step class here declines instead of mistranslating.
    if (!(step instanceof FoldStep<?, ?> fold)) {
      return Outcome.DECLINE;
    }
    // fold(seed, operator) is an arbitrary reduce over a caller-supplied BiFunction, not a list
    // drain; there is nothing to express.
    if (!fold.isListFold()) {
      return Outcome.DECLINE;
    }
    // The decline channel: a context whose shaping no boundary base reads cannot carry the stage, and
    // appending anyway would either throw or silently drop it.
    if (!ctx.supportsListShaping()) {
      return Outcome.DECLINE;
    }
    // A fresh instance per recognition, never a shared constant. Two equal-comparing ops would let
    // union(...) agree across arms carrying a fold each and translate a shape whose answers differ;
    // FoldListShapingOp's javadoc carries that argument along with why the op holds no state.
    ctx.appendListShapingOp(new FoldListShapingOp());
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof FoldStep<?, ?> fold)) {
      return false;
    }
    encoder.appendToken("fold", fold.isListFold() ? "1" : "0");
    return true;
  }
}
