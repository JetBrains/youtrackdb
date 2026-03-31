package com.jetbrains.youtrackdb.internal.core.record.impl;

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
   * @param ridSet accepted RIDs (typically built from an index query)
   * @return a filtered copy of this iterable
   */
  @Nonnull
  PreFilterableLinkBagIterable withRidFilter(@Nonnull Set<RID> ridSet);

  /**
   * Returns an iterator over the records in this iterable (after any applied
   * filters). Concrete implementations return a typed iterator (e.g.
   * {@code Iterator<Vertex>} or {@code Iterator<EdgeInternal>}); this method
   * provides a common accessor so callers don't need to cast to {@code Iterable}.
   */
  @Nonnull
  Iterator<?> iterator();
}
