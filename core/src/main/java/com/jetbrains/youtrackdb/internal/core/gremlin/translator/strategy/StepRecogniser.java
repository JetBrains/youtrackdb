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
 * <h2>Consumed-step count: recognisers advance the cursor</h2>
 *
 * The walker's loop is index-driven — it reads {@code steps.get(ctx.stepIndex)}, dispatches
 * it, and does <em>not</em> advance the cursor itself. A recogniser that claims its step
 * (returns {@code true}) MUST advance {@link WalkerContext#stepIndex} past every step it
 * consumed: by one for a single-step claim, by N for a multi-step claim (the non-adjacent
 * {@code outE(L).has(...).inV()} chain, where one recogniser consumes the edge step, the
 * interleaved {@code has(...)} steps, and the closing vertex hop in a single call). The
 * advance count is the recogniser's report of how many steps it consumed; the walker resumes
 * dispatch at the new cursor. A recogniser that returns {@code true} without advancing would
 * spin the walker's loop forever, so this is a hard part of the contract, guarded by an
 * assertion in the walker.
 *
 * <h2>Discipline</h2>
 *
 * Recognisers MUST validate before mutating {@link WalkerContext} — including {@link
 * WalkerContext#stepIndex}. A recogniser that partially mutates the context (or advances the
 * cursor) and then returns {@code false} would leave a later recogniser inspecting tainted
 * state — the walker does not roll back per-step mutations. The contract is "look first, write
 * last": a declining recogniser leaves {@code ctx} — cursor included — exactly as it found it.
 */
@FunctionalInterface
interface StepRecogniser {

  /**
   * Inspects the step at the walker's current cursor. Returns {@code true} when the step (and,
   * for a multi-step claim, its trailing consumed steps) has been fully translated into {@code
   * ctx} and the cursor {@link WalkerContext#stepIndex} advanced past every consumed step. A
   * {@code false} return declines the whole traversal: dispatch is class-keyed, so this
   * recogniser is the only one registered for the step's class and there is no next recogniser
   * to try.
   *
   * @param step the current step under consideration; never {@code null}
   * @param ctx  the in-progress walker state — recognisers read traversal-level
   *             information (attached graph, polymorphism resolution) and append their
   *             contribution to the pattern builder, alias maps, and return-projection
   *             lists. Recognisers MUST NOT mutate {@code ctx} unless they are committing
   *             to return {@code true}, and on {@code true} MUST advance {@link
   *             WalkerContext#stepIndex} by the number of steps consumed (≥ 1).
   * @return {@code true} iff the step was recognised, the context was updated, and the cursor
   *         was advanced past every consumed step.
   */
  boolean recognize(Step<?, ?> step, WalkerContext ctx);
}
