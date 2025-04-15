package com.jetbrains.youtrack.db.internal.core.record.impl;


import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.common.util.Resettable;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class RelationsIteratorAbstract<E extends Entity, L extends Relation<E>> implements
    Iterator<L>, Resettable, Sizeable {

  @Nonnull
  protected final E sourceEntity;
  protected final Pair<Direction, String> connection;
  protected final String[] labels;

  @Nonnull
  protected final DatabaseSessionInternal session;

  @Nonnull
  private final Iterator<? extends Identifiable> iterator;
  protected final int size; // -1 = UNKNOWN
  private final Object multiValue;
  private L nextLink;

  public RelationsIteratorAbstract(
      @Nonnull final E sourceEntity,
      final Object multiValue,
      @Nonnull final Iterator<? extends Identifiable> iterator,
      final Pair<Direction, String> connection,
      final String[] labels,
      final int size, @Nonnull DatabaseSessionInternal session) {
    this.iterator = iterator;
    this.multiValue = multiValue;
    this.size = size;
    this.sourceEntity = sourceEntity;
    this.connection = connection;
    this.labels = labels;
    this.session = session;
  }

  @Nullable
  protected abstract L createBidirectionalLink(@Nullable Identifiable identifiable);

  @Override
  public L next() {
    if (hasNext()) {
      var currentLink = this.nextLink;
      this.nextLink = null;
      return currentLink;
    }

    throw new NoSuchElementException();
  }

  public boolean filter(final L link) {
    return link.isLabeled(labels);
  }

  @Override
  public void reset() {
    if (iterator instanceof Resettable resettable) {
      resettable.reset();
      nextLink = null;
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
    return size > -1 || iterator instanceof Sizeable iSizeable && iSizeable.isSizeable()
        || multiValue instanceof Sizeable mSizable && mSizable.isSizeable()
        || multiValue instanceof Collection<?>;
  }

  @Override
  public boolean hasNext() {
    while (nextLink == null && iterator.hasNext()) {
      nextLink = createBidirectionalLink(iterator.next());
    }

    return nextLink != null;
  }

  public Object getMultiValue() {
    return multiValue;
  }
}
