package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Iterator that loads vertices directly from LinkBag's secondary RIDs,
 * bypassing edge record loading. For heavyweight edges, the secondary RID
 * is the opposite vertex; for lightweight edges, both primary and secondary
 * RIDs point to the opposite vertex. Missing records are skipped gracefully.
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
public class VertexFromLinkBagIterator implements Iterator<Vertex>, Sizeable {

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

  @Nullable private Vertex nextVertex;

  public VertexFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size) {
    this(ridPairIterator, session, size, null);
  }

  public VertexFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size,
      @Nullable IntSet acceptedCollectionIds) {
    this.ridPairIterator = ridPairIterator;
    this.session = session;
    this.size = size;
    this.acceptedCollectionIds = acceptedCollectionIds;
  }

  @Override
  public boolean hasNext() {
    while (nextVertex == null && ridPairIterator.hasNext()) {
      nextVertex = loadVertex(ridPairIterator.next());
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

  @Nullable private Vertex loadVertex(RidPair ridPair) {
    var rid = ridPair.secondaryRid();

    // Class filter: check the collection (cluster) ID before loading from storage.
    // The collection ID is part of the RID and already in memory — no disk I/O.
    if (acceptedCollectionIds != null
        && !acceptedCollectionIds.contains(rid.getCollectionId())) {
      return null;
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

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isSizeable() {
    return size >= 0;
  }

  /**
   * Builds an {@link IntSet} of collection IDs for a schema class and all its
   * subclasses. Used to construct the {@code acceptedCollectionIds} filter.
   */
  @Nonnull
  public static IntSet collectionIdsForClass(
      @Nonnull com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass clazz) {
    var ids = clazz.getPolymorphicCollectionIds();
    var set = new IntOpenHashSet(ids.length);
    for (var id : ids) {
      set.add(id);
    }
    return set;
  }
}
