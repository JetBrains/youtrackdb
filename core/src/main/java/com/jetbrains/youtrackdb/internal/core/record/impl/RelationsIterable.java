package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import java.util.Collection;
import javax.annotation.Nonnull;

public abstract class RelationsIterable<E extends Entity, L extends Relation<E>> implements
    Iterable<L>, Sizeable {
  @Nonnull
  protected final E sourceEntity;
  protected final Pair<Direction, String> connection;
  protected final String[] labels;

  @Nonnull
  protected final DatabaseSessionEmbedded session;

  @Nonnull
  protected final Iterable<? extends Identifiable> iterable;
  protected final int size; // -1 = UNKNOWN
  protected final Object multiValue;

  protected RelationsIterable(@Nonnull E sourceEntity, Pair<Direction, String> connection,
      String[] labels, @Nonnull DatabaseSessionEmbedded session,
      @Nonnull Iterable<? extends Identifiable> iterable, int size, Object multiValue) {
    this.sourceEntity = sourceEntity;
    this.connection = connection;
    this.labels = labels;
    this.session = session;
    this.iterable = iterable;
    this.size = size;
    this.multiValue = multiValue;
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
