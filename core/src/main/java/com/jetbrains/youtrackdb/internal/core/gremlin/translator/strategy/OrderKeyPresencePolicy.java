package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/**
 * The single decision point for whether a translated {@code order().by(key)} emits the
 * {@code key IS DEFINED} conjunct that Gremlin's modulator drop implies.
 *
 * <h2>Why the condition exists</h2>
 *
 * A Gremlin {@code by(...)} modulator is a traversal, not a field reference. {@code
 * order().by("age")} maps each element through {@code values("age")}, and an element with no
 * {@code age} produces nothing, so its traverser is dropped before the comparator ever runs. SQL
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
 * through {@link #emitsPatternPresenceConjunct()}, which answers {@code true} today, so the
 * translated plan is byte-for-byte what it was before the seam existed. A follow-up track that
 * moves the drop into the ordered-scan planner flips this one method and its documentation, and
 * every call site follows without being visited.
 *
 * <p>Deliberately not a configuration setting. A {@code GlobalConfiguration} entry would make the
 * translated row set depend on a runtime flag, which the plan cache keys nothing on, and would ship
 * a way to select the semantics that lose rows. The seam is a compile-time constant so the flip is
 * a code review rather than an operator decision.
 */
final class OrderKeyPresencePolicy {

  private OrderKeyPresencePolicy() {
    // Static policy — no instances.
  }

  /**
   * Whether an {@code order().by(key)} modulator contributes {@code key IS DEFINED} to the pattern.
   *
   * <p>{@code true} is the shipped behaviour: the drop happens inside the plan, before {@code ORDER
   * BY} / {@code SKIP} / {@code LIMIT}, which is where Gremlin puts it. A follow-up track flips
   * this to {@code false} once the ordered-scan planner excludes key-less records on its own, and
   * at that point this method's Javadoc has to state which component owns the drop instead.
   */
  static boolean emitsPatternPresenceConjunct() {
    return true;
  }
}
