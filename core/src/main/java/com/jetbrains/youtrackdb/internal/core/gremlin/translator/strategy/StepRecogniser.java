package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * Pluggable handler that {@link GremlinStepWalker} runs for the step at the cursor's head. A
 * recogniser reads the step stream through a {@link StepCursor}, validates the shape of the Gremlin
 * construct it owns, contributes the translation through a {@link RecognitionContext}, and returns
 * {@link Outcome#ACCEPTED} or {@link Outcome#DECLINE}.
 *
 * <h2>Contract</h2>
 *
 * The walker dispatches the head to at most one recogniser, selected by the head's exact runtime
 * class through a class-keyed registry ({@code Map<Class<?>, StepRecogniser>}). Exactly one recogniser
 * is registered per step class, so the lookup either finds that recogniser or finds none. A step
 * class with no registry entry, and a {@link Outcome#DECLINE} from the recogniser it dispatches to,
 * both decline the whole traversal under the all-or-nothing rule; the traversal then stays on the
 * native TinkerPop pipeline verbatim.
 *
 * <p>Because dispatch keys on the exact runtime class, {@link #recognize} answers a single
 * well-formedness question: does the shape at the head encode a translatable construct? A recogniser
 * consumes its head with {@link StepCursor#take()} and any trailing steps of its shape with the
 * cursor's conditional matchers, then contributes and returns {@link Outcome#ACCEPTED}. It never
 * touches a step index, a consumed count, the traversal, or the strategy list.
 *
 * <h2>Advancement and decline</h2>
 *
 * An {@link Outcome#ACCEPTED} must have advanced the cursor; the walker asserts it, because an accept
 * that consumed nothing would spin the dispatch loop. A {@link Outcome#DECLINE} discards the whole
 * walk, so a recogniser may read and contribute in any order — there is no write-order discipline and
 * no per-step rollback for a new author to get wrong.
 */
@FunctionalInterface
interface StepRecogniser {

  /**
   * Inspects the step at the cursor's head and either translates its shape into {@code ctx} and
   * returns {@link Outcome#ACCEPTED}, or returns {@link Outcome#DECLINE} to decline the whole
   * traversal.
   *
   * @param cursor the forward view of the step stream; the recogniser consumes its head and any
   *     trailing steps of its shape through this. Transparent steps (barriers) are already skipped.
   * @param ctx the recogniser-facing walk state — resolved flags, the current boundary, alias
   *     minting, and the contribution methods. The traversal, the strategy list, and the pattern
   *     builder are not reachable through it.
   * @return {@link Outcome#ACCEPTED} after consuming at least one step and contributing it, or
   *     {@link Outcome#DECLINE} to decline the whole traversal.
   */
  Outcome recognize(StepCursor cursor, RecognitionContext ctx);

  /**
   * Whether {@code step} — a step this recogniser would claim — selects rows <em>by position</em>
   * out of the post-union concatenation. {@code MultiPlanMatchStep} emits child one's rows then
   * child two's, while native {@code union(...)} interleaves the arms as it pulls each incoming
   * traverser, and no MATCH plan can reproduce that interleaving. A step that answers {@code true}
   * therefore translates post-union only when the very next step collapses the selection to a
   * cardinality; {@link GremlinStepWalker#postUnionSuffixTranslatable} enforces that before forking
   * the union's arms, and the recogniser enforces it again on its own step.
   *
   * <p><strong>Every member of {@link GremlinStepWalker}'s post-union allow-list must state its own
   * answer here</strong> rather than inherit this default — the walker's unit tests fail the build
   * when one does not. The default serves the recognisers outside that allow-list, which the
   * post-union gate refuses before this question is ever asked. Answering {@code false} for a step
   * that does select by position ships a silently different multiset under a kill switch that
   * defaults on; {@link RangeGlobalStepRecogniser} carries the worked case.
   *
   * @param step the step at the cursor's head, matched to this recogniser by exact class
   * @return {@code true} when the step selects rows by position out of the concatenation
   */
  default boolean selectsPositionally(Step<?, ?> step) {
    return false;
  }
}
