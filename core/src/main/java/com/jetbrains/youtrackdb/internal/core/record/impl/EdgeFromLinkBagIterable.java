package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Iterable that wraps a LinkBag and yields edge records directly from the primary RIDs
 * stored in each RidPair. This provides an optimized path for {@code getEdgesInternal()}
 * when the underlying storage is a LinkBag, supporting zero-I/O pre-filtering by
 * collection ID or RID set.
 *
 * <p>Mirrors {@link VertexFromLinkBagIterable} but yields {@link EdgeInternal} from
 * primary RIDs (edge records) instead of vertices from secondary RIDs.
 *
 * <p>Use {@link #withClassFilter(IntSet)} to create a filtered copy that skips
 * edges whose collection ID is not in the accepted set — this avoids loading
 * records from storage entirely (only the in-memory RID is inspected).
 */
public class EdgeFromLinkBagIterable
    implements PreFilterableLinkBagIterable, Iterable<EdgeInternal> {

  @Nonnull
  private final LinkBag linkBag;
  @Nonnull
  private final DatabaseSessionEmbedded session;

  /**
   * When non-null, only edges whose collection ID is in this set are loaded.
   * Passed through to {@link EdgeFromLinkBagIterator}.
   */
  @Nullable private final IntSet acceptedCollectionIds;

  @Nullable private final Set<RID> acceptedRids;

  /**
   * Metric that receives the lazy pre-filter effectiveness report when the
   * iterator created from this iterable is exhausted or closed. Always
   * {@link Ratio#NOOP} unless this iterable was produced by
   * {@link #withRidFilter(Set, Ratio)} with a non-NOOP metric. Carried through
   * subsequent {@link #withClassFilter(IntSet)} chaining so callers can mix
   * filters in any order without losing the metric reference.
   */
  @Nonnull
  private final Ratio effectivenessMetric;

  public EdgeFromLinkBagIterable(
      @Nonnull LinkBag linkBag,
      @Nonnull DatabaseSessionEmbedded session) {
    this(linkBag, session, null, null, Ratio.NOOP);
  }

  private EdgeFromLinkBagIterable(
      @Nonnull LinkBag linkBag,
      @Nonnull DatabaseSessionEmbedded session,
      @Nullable IntSet acceptedCollectionIds,
      @Nullable Set<RID> acceptedRids,
      @Nonnull Ratio effectivenessMetric) {
    this.linkBag = linkBag;
    this.session = session;
    this.acceptedCollectionIds = acceptedCollectionIds;
    this.acceptedRids = acceptedRids;
    this.effectivenessMetric = effectivenessMetric;
  }

  /**
   * Returns a new iterable that only yields edges whose collection (cluster)
   * ID is in the given set. Edges with non-matching collection IDs are
   * skipped without any disk I/O.
   *
   * @param collectionIds accepted collection IDs
   */
  @Nonnull
  @Override
  public EdgeFromLinkBagIterable withClassFilter(@Nonnull IntSet collectionIds) {
    return new EdgeFromLinkBagIterable(
        linkBag, session, collectionIds, acceptedRids, effectivenessMetric);
  }

  /**
   * Returns a new iterable that only yields edges whose RID is in the given set.
   * Edges not in the set are skipped without any disk I/O. The resulting
   * iterator reports the true {@code (filtered, probed)} ratio to
   * {@code effectivenessMetric} on exhaustion or close.
   *
   * @param ridSet              accepted RIDs (typically built from an index query)
   * @param effectivenessMetric metric receiving the lazy effectiveness report;
   *                            {@link Ratio#NOOP} disables reporting
   */
  @Nonnull
  @Override
  public EdgeFromLinkBagIterable withRidFilter(
      @Nonnull Set<RID> ridSet, @Nonnull Ratio effectivenessMetric) {
    return new EdgeFromLinkBagIterable(
        linkBag, session, acceptedCollectionIds, ridSet, effectivenessMetric);
  }

  /**
   * Covariant override of the single-argument {@link
   * PreFilterableLinkBagIterable#withRidFilter(Set)} default — narrows the
   * return type back to {@link EdgeFromLinkBagIterable} so chained calls
   * (and tests typed via {@code var}) keep the concrete type. Delegates to
   * the two-argument form with {@link Ratio#NOOP}; effectiveness reporting
   * is disabled.
   */
  @Nonnull
  @Override
  public EdgeFromLinkBagIterable withRidFilter(@Nonnull Set<RID> ridSet) {
    return withRidFilter(ridSet, Ratio.NOOP);
  }

  @Nonnull
  @Override
  public Iterator<EdgeInternal> iterator() {
    return new EdgeFromLinkBagIterator(
        linkBag.iterator(), session, linkBag.size(),
        acceptedCollectionIds, acceptedRids, effectivenessMetric);
  }

  @Override
  public int size() {
    return linkBag.size();
  }

  @Override
  public boolean isSizeable() {
    return linkBag.isSizeable();
  }
}
