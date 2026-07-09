package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;

/**
 * Recogniser for a top-level {@link NoOpBarrierStep} — the no-op barrier {@code LazyBarrierStrategy}
 * injects <em>between</em> chained steps at translator time. It claims the barrier without changing
 * anything else about the walk: it advances the cursor past the barrier and mutates nothing else.
 *
 * <h2>Why it is load-bearing, not belt-and-suspenders</h2>
 *
 * A plain multi-hop chain ({@code g.V().out(L).out(L)}) arrives with a {@code NoOpBarrierStep}
 * wedged between the two {@code VertexStep}s. Without a recogniser for the barrier, the walker hits
 * it, finds no registry entry, and declines the whole traversal under all-or-nothing — so every
 * multi-hop {@code out()}/{@code in()}/{@code both()} chain would fall back to the native pipeline. A
 * single hop carries no barrier and translated even before this recogniser existed. Claiming the
 * barrier as a transparent pass-through is what lets a multi-hop chain translate: the hops on either
 * side chain off each other exactly as if the barrier were not there.
 *
 * <h2>No context mutation beyond the cursor</h2>
 *
 * A barrier contributes nothing to the pattern — no node, no edge, no filter, no boundary re-pin. It
 * is a pure execution hint the translated MATCH plan does not need. So this recogniser reports one
 * consumed step and mutates nothing: it does not touch the pattern builder, the alias maps, the
 * return lists, or the boundary metadata. Because it makes no mutation, a barrier that turned out to
 * be the traversal's terminator (nothing after it) is harmless — the boundary the prior hop pinned
 * stays intact, and the walker's terminator invariant still holds.
 */
final class NoOpBarrierRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final NoOpBarrierRecogniser INSTANCE = new NoOpBarrierRecogniser();

  private NoOpBarrierRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public int recognize(Step<?, ?> step, WalkerContext ctx) {
    // Defence in depth: the registry keys this recogniser on NoOpBarrierStep.class, so dispatch only
    // ever hands it a barrier. Re-assert the type so a future registry mistake declines cleanly
    // rather than claiming a step it never validated.
    if (!(step instanceof NoOpBarrierStep<?>)) {
      return 0;
    }
    // A barrier is a transparent pass-through: report the one step consumed and mutate nothing else,
    // so the hops on either side chain off each other as if the barrier were absent.
    return 1;
  }
}
