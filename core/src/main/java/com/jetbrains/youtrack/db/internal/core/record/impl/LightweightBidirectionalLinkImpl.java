package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LightweightBidirectionalLinkImpl<T extends Entity> implements BidirectionalLink<T> {
  @Nullable
  protected final T out;
  @Nullable
  protected final T in;

  @Nonnull
  protected final DatabaseSessionInternal session;

  protected final String label;

  public LightweightBidirectionalLinkImpl(@Nonnull DatabaseSessionInternal session,
      @Nullable T out, @Nullable T in, String label) {
    this.out = out;
    this.in = in;
    this.session = session;
    this.label = label;
  }


  @Nullable
  @Override
  public T getFromEntity() {
    return out;
  }

  @Nullable
  @Override
  public T getToEntity() {
    return in;
  }

  @Override
  public boolean isLabeled(@Nonnull String[] labels) {
    if (labels.length == 0) {
      return true;
    }

    for (var label : labels) {
      return label.equalsIgnoreCase(this.label);
    }

    return false;
  }

  @Nullable
  public T getEntity(@Nonnull Direction dir) {
    if (dir == Direction.IN) {
      return in;
    } else if (dir == Direction.OUT) {
      return out;
    }

    throw new IllegalArgumentException("Direction not supported: " + dir);
  }
}
