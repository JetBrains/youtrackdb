package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
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
  @Nullable
  private Vertex nextVertex;

  public VertexFromLinkBagIterator(
      @Nonnull Iterator<RidPair> ridPairIterator,
      @Nonnull DatabaseSessionEmbedded session,
      int size) {
    this.ridPairIterator = ridPairIterator;
    this.session = session;
    this.size = size;
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

  @Nullable
  private Vertex loadVertex(RidPair ridPair) {
    try {
      var transaction = session.getActiveTransaction();
      var entity = transaction.loadEntity(ridPair.secondaryRid());
      if (entity.isVertex()) {
        return entity.asVertex();
      }
      LogManager.instance().warn(this,
          "Expected vertex but found %s for secondary RID %s (primary RID %s)",
          entity.getClass().getSimpleName(), ridPair.secondaryRid(),
          ridPair.primaryRid());
      return null;
    } catch (RecordNotFoundException rnf) {
      LogManager.instance().warn(this,
          "Vertex record (%s) not found (primary RID %s), skipping",
          ridPair.secondaryRid(), ridPair.primaryRid());
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
