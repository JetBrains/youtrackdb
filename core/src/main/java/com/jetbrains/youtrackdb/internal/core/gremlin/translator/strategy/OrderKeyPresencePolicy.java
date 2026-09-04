package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/**
 * The single decision point for whether a translated {@code order().by(key)} emits the
 * {@code key IS DEFINED} conjunct that Gremlin's modulator drop implies.
 *
 * <h2>Why the condition exists</h2>
 *
 * A Gremlin {@code by(...)} modulator is a traversal, not a field reference. {@code
 * order().by("age")} maps each element through {@code values("age")}, and an element with no
 * {@code age} produces nothing, so its traverser is dropped before the comparator ever runs. YQL
 * {@code ORDER BY} keeps every row and sorts the missing keys as {@code null}. The conjunct is what
 * makes the translated row set the same multiset as the native one, and {@link ByModulatorPresence}
 * carries the full argument.
 *
 * <h2>Why the decision is a seam rather than an inline call</h2>
 *
 * The conjunct is the only filter on the sorted alias whenever the traversal wrote none of its own,
 * and the MATCH root-selection cost model has no estimator for {@code IS DEFINED} — it falls back
 * to {@code classCount / 2}, so a presence-only alias looks twice as selective as an unfiltered one
 * and can win the root slot. Dropping the conjunct in favour of an executor-side rule that keeps
 * key-less records out of an ordered scan is therefore a live design option, and it has to be
 * decided in one place rather than found.
 *
 * <p>This class is that place. {@link OrderGlobalStepRecogniser} routes its per-comparator emission
 * through {@link #emitsPatternPresenceConjunct(RecognitionContext)}, so the decision is made once
 * and every call site follows without being visited.
 *
 * <h2>What the answer depends on</h2>
 *
 * The answer is the resolved {@code orderIncludesMissingKey} setting, inverted: the per-traversal
 * option first, the session default second, read through the same resolver the native
 * {@code YTDBProductiveOrderByStrategy} reads. Under the shipped default the record survives the
 * pattern and sorts as a null key. Under the portable opt-out the conjunct is emitted exactly as
 * it was before the setting existed.
 *
 * <p>Because the answer is now a runtime value, the translation cache must key on it:
 * {@code GremlinShapeExtractor.appendStrategyFlags} encodes the resolved value as the {@code oim}
 * token, beside {@code poly}, {@code elv} and {@code pb}. Without that token a plan cached under
 * one setting would be spliced verbatim into a traversal running under the other, because the
 * cache is storage-wide rather than per session.
 *
 * <p>One order path deliberately bypasses this policy. {@code values(k).order()} is an IDENTITY
 * modulator over a property the walk already projects, and its drop belongs to the {@code
 * values(k)} step rather than to the order key, so it flows through the projected-property
 * requirement instead. That record is dropped under either setting, which matches native
 * {@code values(k)}.
 */
final class OrderKeyPresencePolicy {

  private OrderKeyPresencePolicy() {
    // Static policy — no instances.
  }

  /**
   * Whether an {@code order().by(key)} modulator contributes {@code key IS DEFINED} to the pattern.
   *
   * <p>{@code false} under the shipped default, where {@code orderIncludesMissingKey} is on:
   * nothing drops the record, so it reaches {@code ORDER BY} and sorts as a null key, which is what
   * YQL does with a missing column and what the native {@code YTDBProductiveOrderByStrategy} does
   * with a missing modulator value.
   *
   * <p>{@code true} under the portable opt-out: the drop happens inside the plan, before
   * {@code ORDER BY} / {@code SKIP} / {@code LIMIT}, which is where portable Gremlin puts it.
   *
   * @param ctx the walk whose resolved {@link RecognitionContext#orderIncludesMissingKey()} answers
   *     for this traversal
   */
  static boolean emitsPatternPresenceConjunct(RecognitionContext ctx) {
    return !ctx.orderIncludesMissingKey();
  }
}
