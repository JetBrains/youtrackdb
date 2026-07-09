package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * Pluggable handler that {@link GremlinStepWalker} invokes for each step in a traversal
 * being considered for translation. A recogniser inspects the step, validates its shape
 * against the rules of the Gremlin construct it knows about, and — if everything checks
 * out — appends the step's contribution to the {@link WalkerContext} and reports how many
 * steps it consumed.
 *
 * <h2>Contract</h2>
 *
 * The walker dispatches each step to at most one recogniser, selected by the step's
 * concrete runtime class through a class-keyed registry ({@code Map<Class<?>,
 * StepRecogniser>}). Exactly one recogniser is registered per step class, so the lookup
 * either finds that single recogniser or finds none — there is no ordered scan and no
 * first-match-wins. Two outcomes decline the whole traversal under the all-or-nothing
 * rule: no registry entry for the step's class, or a registered recogniser that returns
 * {@code 0}. A declined traversal stays on the native TinkerPop pipeline verbatim.
 *
 * <p>Because dispatch keys on the exact runtime class, a recogniser is only ever handed a
 * step of the class it was registered under, so {@link #recognize} answers a single
 * well-formedness question: does this step's payload encode a translatable shape? When it
 * does not (an unconvertible ID, an unsupported predicate variant), the recogniser returns
 * {@code 0} and the traversal declines. A {@code 0} return is never a "not mine, try the
 * next one" signal — the registry has already selected the sole recogniser for the class. A
 * recogniser MAY still re-assert its step type with an {@code instanceof} check as
 * defence-in-depth, but that check is not the routing mechanism.
 *
 * <h2>Consumed-step count: recognisers report, the walker advances</h2>
 *
 * The walker's loop is index-driven — it reads {@code steps.get(ctx.stepIndex)}, dispatches it,
 * and is the <em>sole writer</em> of the cursor. A recogniser does not touch {@link
 * WalkerContext#stepIndex}; it returns the number of steps it consumed and the walker advances the
 * cursor by that count. A single-step claim returns {@code 1}; a multi-step claim returns {@code N}
 * (the non-adjacent {@code outE(L).has(...).inV()} chain returns the total of the edge step, the
 * interleaved {@code has(...)} steps, and the closing vertex hop). A recogniser reads {@code
 * ctx.stepIndex} to know where it sits, but reporting the count rather than mutating the cursor
 * makes "claim without progress" impossible: a claim is a positive count and {@code 0} is the
 * decline, so a recogniser can never spin the walker's loop. The walker still guards the one
 * residual logic bug — a claim longer than the steps that remain (an overrun) — with an assertion.
 *
 * <h2>Discipline</h2>
 *
 * Recognisers MUST validate before mutating {@link WalkerContext}. A recogniser that partially
 * mutates the context and then returns {@code 0} would leave a later recogniser inspecting tainted
 * state — the walker does not roll back per-step mutations. The contract is "look first, write
 * last": a declining recogniser leaves {@code ctx} exactly as it found it. Recognisers never write
 * {@link WalkerContext#stepIndex}, so a decline needs no cursor rollback.
 */
@FunctionalInterface
interface StepRecogniser {

  /**
   * Inspects the step at the walker's current cursor. Returns the number of steps consumed — the
   * count the walker advances its cursor by — when the step (and, for a multi-step claim, its
   * trailing consumed steps) has been fully translated into {@code ctx}. Returns {@code 0} to
   * decline the whole traversal: dispatch is class-keyed, so this recogniser is the only one
   * registered for the step's class and there is no next recogniser to try.
   *
   * @param step the current step under consideration; never {@code null}
   * @param ctx  the in-progress walker state — recognisers read traversal-level information
   *             (attached graph, polymorphism resolution) and the current cursor {@link
   *             WalkerContext#stepIndex}, and append their contribution to the pattern builder,
   *             alias maps, and return-projection lists. Recognisers MUST NOT mutate {@code ctx}
   *             unless they are committing to claim the step(s), and MUST NOT write {@link
   *             WalkerContext#stepIndex} at all — the walker owns cursor advancement.
   * @return the number of steps consumed ({@code >= 1}) on a successful claim, or {@code 0} to
   *         decline. Never negative.
   */
  int recognize(Step<?, ?> step, WalkerContext ctx);
}
