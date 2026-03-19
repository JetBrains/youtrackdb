package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Iterator over edges connected to a vertex.
 * Loads edge records lazily and skips null/missing entries.
 */
public class EdgeIterator implements Iterator<EdgeInternal>, Resettable, Sizeable {

  @Nonnull
  private final DatabaseSessionEmbedded session;

  @Nonnull
  private final Iterator<? extends Identifiable> iterator;
  private final int size; // -1 = UNKNOWN
  private final Object multiValue;
  private EdgeInternal nextEdge;

  public EdgeIterator(
      final Object multiValue,
      @Nonnull final Iterator<? extends Identifiable> iterator,
      final int size,
      @Nonnull DatabaseSessionEmbedded session) {
    this.iterator = iterator;
    this.multiValue = multiValue;
    this.size = size;
    this.session = session;
  }

  @Override
  public boolean hasNext() {
    while (nextEdge == null && iterator.hasNext()) {
      nextEdge = loadEdge(iterator.next());
    }
    return nextEdge != null;
  }

  @Override
  public EdgeInternal next() {
    if (hasNext()) {
      var current = this.nextEdge;
      this.nextEdge = null;
      return current;
    }
    throw new NoSuchElementException();
  }

  @Override
  public void reset() {
    if (iterator instanceof Resettable resettable) {
      resettable.reset();
      nextEdge = null;
      return;
    }
    throw new UnsupportedOperationException("Reset is not supported");
  }

  @Override
  public boolean isResetable() {
    return iterator instanceof Resettable resettable && resettable.isResetable();
  }

  @Override
  public int size() {
    if (size > -1) {
      return size;
    }
    if (iterator instanceof Sizeable sizeable) {
      return sizeable.size();
    }
    if (multiValue instanceof Sizeable sizeable) {
      return sizeable.size();
    }
    if (multiValue instanceof Collection<?> collection) {
      return collection.size();
    }
    throw new UnsupportedOperationException("Size is not supported");
  }

  @Override
  public boolean isSizeable() {
    return size > -1 || (iterator instanceof Sizeable iSizeable && iSizeable.isSizeable())
        || (multiValue instanceof Sizeable mSizable && mSizable.isSizeable())
        || multiValue instanceof Collection<?>;
  }

  public Object getMultiValue() {
    return multiValue;
  }

  /**
   * Attempts to load an edge from an identifiable reference. Returns null
   * for null/missing records (which are skipped by the hasNext loop).
   */
  @Nullable private EdgeInternal loadEdge(@Nullable Identifiable identifiable) {
    if (identifiable == null) {
      return null;
    }

    if (identifiable instanceof Entity entity && entity.isEdge()) {
      return (EdgeInternal) entity.asEdge();
    }

    final Entity entity;
    try {
      var transaction = session.getActiveTransaction();
      entity = transaction.loadEntity(identifiable);
    } catch (RecordNotFoundException rnf) {
      LogManager.instance().warn(this, "Record (%s) is null", identifiable);
      return null;
    }

    if (entity.isVertex()) {
      // Legacy lightweight edges stored raw vertex RIDs in LinkBags. After edge
      // unification, all LinkBag entries must point to edge records, not vertices.
      throw new IllegalStateException(
          "Legacy lightweight edge detected: LinkBag entry "
              + identifiable
              + " points to a vertex instead of an edge record. "
              + "Legacy lightweight edges are no longer supported.");
    } else if (entity.isEdge()) {
      return (EdgeInternal) entity.asEdge();
    } else {
      throw new IllegalStateException(
          "Invalid content found while iterating edges, value '" + entity + "' is not an edge");
    }
  }
}
