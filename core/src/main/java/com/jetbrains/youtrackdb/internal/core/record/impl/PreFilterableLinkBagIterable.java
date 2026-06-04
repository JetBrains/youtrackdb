package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Shared interface for LinkBag-backed iterables that support zero-I/O pre-filtering
 * by collection ID (class filter) or RID set (index-based filter). Both vertex and
 * edge iterables implement this interface, allowing {@code applyPreFilter} in
 * {@code MatchEdgeTraverser} to handle them uniformly.
 *
 * <p>Extends {@link Sizeable} to inherit {@code size()} and {@code isSizeable()},
 * which are used by the adaptive abort guards to decide whether pre-filtering is
 * worthwhile.
 */
public interface PreFilterableLinkBagIterable extends Sizeable {

  /**
   * Returns a new iterable that only yields records whose collection (cluster) ID
   * is in the given set. Records with non-matching collection IDs are skipped
   * without any disk I/O.
   *
   * @param collectionIds accepted collection IDs
   * @return a filtered copy of this iterable
   */
  @Nonnull
  PreFilterableLinkBagIterable withClassFilter(@Nonnull IntSet collectionIds);

  /**
   * Returns a new iterable that only yields records whose RID is in the given set.
   * Records not in the set are skipped without any disk I/O.
   *
   * <p>Equivalent to {@link #withRidFilter(Set, Ratio)} with {@link Ratio#NOOP} —
   * use the two-argument overload when actual {@code (probed, filtered)} counts
   * should be reported to a metric.
   *
   * @param ridSet accepted RIDs (typically built from an index query)
   * @return a filtered copy of this iterable
   */
  @Nonnull
  default PreFilterableLinkBagIterable withRidFilter(@Nonnull Set<RID> ridSet) {
    return withRidFilter(ridSet, Ratio.NOOP);
  }

  /**
   * Returns a new iterable that only yields records whose RID is in the given set,
   * and reports the true filtered/probed counts to {@code effectivenessMetric} once
   * the resulting iterator is exhausted or closed.
   *
   * <p>{@code probed} counts every LinkBag entry that survived the class filter
   * and was tested against {@code ridSet}; {@code filtered} counts those rejected
   * by the test. The metric reflects the actual intersection size, not the
   * approximation {@code linkBagSize − ridSet.size()} (which is wrong whenever
   * {@code ridSet} is not a subset of the link bag).
   *
   * @param ridSet              accepted RIDs (typically built from an index query)
   * @param effectivenessMetric metric to receive the lazy {@code record(filtered, probed)}
   *                            call on iterator exhaustion or close;
   *                            {@link Ratio#NOOP} disables reporting
   * @return a filtered copy of this iterable
   */
  @Nonnull
  PreFilterableLinkBagIterable withRidFilter(
      @Nonnull Set<RID> ridSet, @Nonnull Ratio effectivenessMetric);

  /**
   * Returns an iterator over the records in this iterable (after any applied
   * filters). Concrete implementations return a typed iterator (e.g.
   * {@code Iterator<Vertex>} or {@code Iterator<EdgeInternal>}); this method
   * provides a common accessor so callers don't need to cast to {@code Iterable}.
   */
  @Nonnull
  Iterator<?> iterator();
}
