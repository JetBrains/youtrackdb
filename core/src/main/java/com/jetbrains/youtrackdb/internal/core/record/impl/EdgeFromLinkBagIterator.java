package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Iterator that loads edge records directly from LinkBag's primary RIDs.
 * The primary RID in each {@link RidPair} is the edge record RID. Missing
 * records are skipped gracefully.
 *
 * <p>Validates each {@link RidPair} via {@link RidPair#validateEdgePair()}
 * to detect corrupt entries (where primaryRid == secondaryRid).
 *
 * <p>When {@code acceptedCollectionIds} is set, the iterator checks the
 * edge's collection (cluster) ID <em>before</em> loading it from storage.
 * Edges whose collection ID is not in the accepted set are skipped without
 * any disk I/O — only the RID (already in memory from the LinkBag) is
 * inspected.
 *
 * <p>Mirrors {@link VertexFromLinkBagIterator} but uses primary RIDs
 * (edges) instead of secondary RIDs (vertices).
 *
 * <p>Requires an active transaction on the session.
 *
 * <p>Note: {@link #size()} returns an upper bound (the LinkBag size), not the
 * exact count of edges yielded, since entries with missing records are
 * silently skipped.
 */
public class EdgeFromLinkBagIterator implements Iterator<EdgeInternal>, Sizeable {

  @Nonnull
  private final Iterator<RidPair> ridPairIterator;
  @Nonnull
  private final DatabaseSessionEmbedded session;
  private final int size;

  /**
   * When non-null, only edges whose collection ID is in this set are loaded.
   * All others are skipped without touching storage — the collection ID is
   * extracted directly from the RID which is already in memory.
   */
  @Nullable private final IntSet acceptedCollectionIds;

  /**
   * When non-null, only edges whose RID is in this set are loaded from storage.
   * Built at execution time from an index query; provides zero-I/O skipping for
   * records that do not satisfy an indexed property condition.
   */
  @Nullable private final Set<RID> acceptedRids;

  @Nullable private EdgeInternal nextEdge;

  public EdgeFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size) {
    this(ridPairIterator, session, size, null, null);
  }

  public EdgeFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size,
      @Nullable IntSet acceptedCollectionIds) {
    this(ridPairIterator, session, size, acceptedCollectionIds, null);
  }

  public EdgeFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size,
      @Nullable IntSet acceptedCollectionIds,
      @Nullable Set<RID> acceptedRids) {
    this.ridPairIterator = ridPairIterator;
    this.session = session;
    this.size = size;
    this.acceptedCollectionIds = acceptedCollectionIds;
    this.acceptedRids = acceptedRids;
  }

  @Override
  public boolean hasNext() {
    while (nextEdge == null && ridPairIterator.hasNext()) {
      nextEdge = loadEdge(ridPairIterator.next());
    }
    return nextEdge != null;
  }

  @Override
  public EdgeInternal next() {
    if (hasNext()) {
      var current = nextEdge;
      nextEdge = null;
      return current;
    }
    throw new NoSuchElementException();
  }

  @Nullable private EdgeInternal loadEdge(RidPair ridPair) {
    ridPair.validateEdgePair();
    var rid = ridPair.primaryRid();

    // Class filter: check the collection (cluster) ID before loading from storage.
    // The collection ID is part of the RID and already in memory — no disk I/O.
    if (acceptedCollectionIds != null
        && !acceptedCollectionIds.contains(rid.getCollectionId())) {
      return null;
    }

    if (acceptedRids != null && !acceptedRids.contains(rid)) {
      return null;
    }

    try {
      var transaction = session.getActiveTransaction();
      var entity = transaction.loadEntity(rid);
      if (entity.isEdge()) {
        return (EdgeInternal) entity.asEdge();
      }
      if (entity.isVertex()) {
        // Legacy lightweight edges stored raw vertex RIDs in LinkBags. After edge
        // unification, all LinkBag entries must point to edge records, not vertices.
        throw new IllegalStateException(
            "Legacy lightweight edge detected: LinkBag entry "
                + rid
                + " points to a vertex instead of an edge record. "
                + "Legacy lightweight edges are no longer supported.");
      }
      LogManager.instance().warn(this,
          "Expected edge but found %s for primary RID %s (secondary RID %s)",
          entity.getClass().getSimpleName(), rid, ridPair.secondaryRid());
      return null;
    } catch (RecordNotFoundException rnf) {
      LogManager.instance().warn(this,
          "Edge record (%s) not found (secondary RID %s), skipping",
          rid, ridPair.secondaryRid());
      return null;
    }
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
