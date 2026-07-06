package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * Pluggable handler that {@link GremlinStepWalker} invokes for each step in a traversal
 * being considered for translation. A recogniser inspects the step, validates its shape
 * against the rules of the Gremlin construct it knows about, and — if everything checks
 * out — appends the step's contribution to the {@link WalkerContext}.
 *
 * <h2>Contract</h2>
 *
 * The walker dispatches each step to at most one recogniser, selected by the step's
 * concrete runtime class through a class-keyed registry ({@code Map<Class<?>,
 * StepRecogniser>}). Exactly one recogniser is registered per step class, so the lookup
 * either finds that single recogniser or finds none — there is no ordered scan and no
 * first-match-wins. Two outcomes decline the whole traversal under the all-or-nothing
 * rule: no registry entry for the step's class, or a registered recogniser that returns
 * {@code false}. A declined traversal stays on the native TinkerPop pipeline verbatim.
 *
 * <p>Because dispatch keys on the exact runtime class, a recogniser is only ever handed a
 * step of the class it was registered under, so {@link #recognize} answers a single
 * well-formedness question: does this step's payload encode a translatable shape? When it
 * does not (an unconvertible ID, an unsupported predicate variant), the recogniser returns
 * {@code false} and the traversal declines. A {@code false} return is never a "not mine,
 * try the next one" signal — the registry has already selected the sole recogniser for the
 * class. A recogniser MAY still re-assert its step type with an {@code instanceof} check as
 * defence-in-depth, but that check is not the routing mechanism.
 *
 * <h2>Discipline</h2>
 *
 * Recognisers MUST validate before mutating {@link WalkerContext}. A recogniser that
 * partially mutates the context and then returns {@code false} would leave a later
 * recogniser inspecting tainted state — the walker does not roll back per-step
 * mutations. The contract is "look first, write last".
 */
@FunctionalInterface
interface StepRecogniser {

  /**
   * Inspects a single step from the traversal. Returns {@code true} when the step has
   * been fully consumed and its effect appended to {@code ctx}; otherwise the walker
   * tries the next recogniser, and if no recogniser claims the step the walk declines.
   *
   * @param step the current step under consideration; never {@code null}
   * @param ctx  the in-progress walker state — recognisers read traversal-level fields
   *             (graph, polymorphism) and append their contribution to the pattern
   *             builder, alias maps, and return-projection lists. Recognisers MUST NOT
   *             mutate {@code ctx} unless they are committing to return {@code true}.
   * @return {@code true} iff the step was recognised and the context was updated.
   */
  boolean recognize(Step<?, ?> step, WalkerContext ctx);
}
