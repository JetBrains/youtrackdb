package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Iterator that loads vertices directly from LinkBag's secondary RIDs,
 * bypassing edge record loading. The secondary RID in each {@link RidPair}
 * is the opposite vertex RID. Missing records are skipped gracefully.
 *
 * <p>Validates each {@link RidPair} via {@link RidPair#validateEdgePair()}
 * to detect corrupt entries (where primaryRid == secondaryRid).
 *
 * <p>When {@code acceptedCollectionIds} is set, the iterator checks the
 * target vertex's collection (cluster) ID <em>before</em> loading it from
 * storage. Vertices whose collection ID is not in the accepted set are
 * skipped without any disk I/O — only the RID (already in memory from the
 * LinkBag) is inspected.
 *
 * <p>Requires an active transaction on the session.
 *
 * <p>Note: {@link #size()} returns an upper bound (the LinkBag size), not the
 * exact count of vertices yielded, since entries with missing or non-vertex
 * records are silently skipped.
 */
public class VertexFromLinkBagIterator
    implements Iterator<Vertex>, Sizeable, AutoCloseable {

  @Nonnull
  private final Iterator<RidPair> ridPairIterator;
  @Nonnull
  private final DatabaseSessionEmbedded session;
  private final int size;

  /**
   * When non-null, only vertices whose collection ID is in this set are loaded.
   * All others are skipped without touching storage — the collection ID is
   * extracted directly from the RID which is already in memory.
   */
  @Nullable private final IntSet acceptedCollectionIds;

  /**
   * When non-null, only vertices whose RID is in this set are loaded from storage.
   * Built at execution time from an index query; provides zero-I/O skipping for
   * records that do not satisfy an indexed property condition.
   */
  @Nullable private final Set<RID> acceptedRids;

  /**
   * When pre-filter effectiveness reporting is enabled, this metric receives a
   * single {@code record(filtered, probed)} call on iterator exhaustion or
   * close. {@link Ratio#NOOP} disables reporting. {@code probed} is the number
   * of RIDs tested against {@link #acceptedRids} (i.e. survived the class
   * filter); {@code filtered} is the subset rejected by that test. The metric
   * reflects the true intersection size, not the {@code linkBagSize − ridSet.size()}
   * approximation.
   */
  @Nonnull
  private final Ratio effectivenessMetric;

  private long probedCount;
  private long filteredCount;
  private boolean metricFlushed;

  @Nullable private Vertex nextVertex;

  public VertexFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size) {
    this(ridPairIterator, session, size, null, null, Ratio.NOOP);
  }

  public VertexFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size,
      @Nullable IntSet acceptedCollectionIds) {
    this(ridPairIterator, session, size, acceptedCollectionIds, null, Ratio.NOOP);
  }

  public VertexFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size,
      @Nullable IntSet acceptedCollectionIds,
      @Nullable Set<RID> acceptedRids) {
    this(ridPairIterator, session, size, acceptedCollectionIds, acceptedRids, Ratio.NOOP);
  }

  public VertexFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size,
      @Nullable IntSet acceptedCollectionIds,
      @Nullable Set<RID> acceptedRids,
      @Nonnull Ratio effectivenessMetric) {
    this.ridPairIterator = ridPairIterator;
    this.session = session;
    this.size = size;
    this.acceptedCollectionIds = acceptedCollectionIds;
    this.acceptedRids = acceptedRids;
    this.effectivenessMetric = effectivenessMetric;
  }

  @Override
  public boolean hasNext() {
    while (nextVertex == null && ridPairIterator.hasNext()) {
      nextVertex = loadVertex(ridPairIterator.next());
    }
    if (nextVertex == null) {
      // Source iterator exhausted — flush the effectiveness metric. Idempotent.
      flushEffectivenessMetric();
    }
    return nextVertex != null;
  }

  @Override
  public Vertex next() {
    if (hasNext()) {
      var current = nextVertex;
      nextVertex = null;
      return current;
    }
    throw new NoSuchElementException();
  }

  /**
   * Flushes the {@link #effectivenessMetric} with the accumulated probed/filtered
   * counts and marks the flush as done so it never fires twice. Called from
   * {@link #hasNext()} on natural exhaustion and from {@link #close()} on
   * early termination. Safe to call repeatedly.
   */
  private void flushEffectivenessMetric() {
    if (metricFlushed) {
      return;
    }
    metricFlushed = true;
    if (probedCount > 0) {
      effectivenessMetric.record(filteredCount, probedCount);
    }
  }

  @Override
  public void close() {
    flushEffectivenessMetric();
  }

  @Nullable private Vertex loadVertex(RidPair ridPair) {
    ridPair.validateEdgePair();
    var rid = ridPair.secondaryRid();

    // Class filter: check the collection (cluster) ID before loading from storage.
    // The collection ID is part of the RID and already in memory — no disk I/O.
    if (acceptedCollectionIds != null
        && !acceptedCollectionIds.contains(rid.getCollectionId())) {
      return null;
    }

    // RID-set pre-filter: count probed/filtered for the effectiveness metric.
    // Only entries that reach this branch count toward the metric, so the
    // ratio describes the RID-set filter in isolation (not class filter).
    // The filter order here mirrors acceptRid(); this eager path keeps it
    // inline to increment the per-instance effectiveness counters, which a
    // static helper cannot touch.
    if (acceptedRids != null) {
      probedCount++;
      if (!acceptedRids.contains(rid)) {
        filteredCount++;
        return null;
      }
    }

    try {
      var transaction = session.getActiveTransaction();
      var entity = transaction.loadEntity(rid);
      if (entity.isVertex()) {
        return entity.asVertex();
      }
      LogManager.instance().warn(this,
          "Expected vertex but found %s for secondary RID %s (primary RID %s)",
          entity.getClass().getSimpleName(), rid, ridPair.primaryRid());
      return null;
    } catch (RecordNotFoundException rnf) {
      LogManager.instance().warn(this,
          "Vertex record (%s) not found (primary RID %s), skipping",
          rid, ridPair.primaryRid());
      return null;
    }
  }

  /**
   * Resolves the target vertex RID from a {@link RidPair} and applies the
   * collection-ID and accepted-RID filters. Returns the secondary RID when both
   * filters accept it, or {@code null} when either rejects.
   *
   * <p>Class filter (collection ID) is checked against the in-memory RID — no
   * disk I/O. RID filter is a {@link Set#contains} probe against the index-built
   * accepted set.
   *
   * <p>Used by {@code VertexFromLinkBagIterable.RidOnlyIterator} (lazy MATCH
   * path), which needs the filter decision without effectiveness metrics. The
   * eager {@link #loadVertex} path applies the same two filters in the same
   * order inline, because it also increments per-instance effectiveness
   * counters that a static helper cannot reach. Keep the two filter orderings
   * in sync when changing either.
   */
  @Nullable static RID acceptRid(
      @Nonnull RidPair ridPair,
      @Nullable IntSet acceptedCollectionIds,
      @Nullable Set<RID> acceptedRids) {
    ridPair.validateEdgePair();
    var rid = ridPair.secondaryRid();
    if (acceptedCollectionIds != null
        && !acceptedCollectionIds.contains(rid.getCollectionId())) {
      return null;
    }
    if (acceptedRids != null && !acceptedRids.contains(rid)) {
      return null;
    }
    return rid;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isSizeable() {
    return size >= 0;
  }
}
