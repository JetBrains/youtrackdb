package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.Collection;
import java.util.Iterator;
import javax.annotation.Nonnull;

/**
 * Iterable that produces {@link EdgeIterator} instances over edges connected
 * to a vertex.
 */
public class EdgeIterable implements Iterable<EdgeInternal>, Sizeable {

  @Nonnull
  private final DatabaseSessionEmbedded session;

  @Nonnull
  private final Iterable<? extends Identifiable> iterable;
  private final int size; // -1 = UNKNOWN
  private final Object multiValue;

  public EdgeIterable(
      @Nonnull DatabaseSessionEmbedded session,
      @Nonnull Iterable<? extends Identifiable> iterable,
      int size,
      Object multiValue) {
    this.session = session;
    this.iterable = iterable;
    this.size = size;
    this.multiValue = multiValue;
  }

  @Nonnull
  @Override
  public Iterator<EdgeInternal> iterator() {
    return new EdgeIterator(multiValue, iterable.iterator(), size, session);
  }

  @Override
  public int size() {
    if (size >= 0) {
      return size;
    }
    if (iterable instanceof Sizeable sizeable) {
      return sizeable.size();
    }
    if (iterable instanceof Collection<?> collection) {
      return collection.size();
    }
    throw new UnsupportedOperationException(
        "Size is not supported for this iterable: " + iterable.getClass());
  }

  @Override
  public boolean isSizeable() {
    return size >= 0 || (iterable instanceof Sizeable sizeable && sizeable.isSizeable())
        || iterable instanceof Collection<?>;
  }
}
