package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface BidirectionalLink<T extends Entity> {
  @Nullable
  T getFromEntity();

  @Nullable
  T getToEntity();

  boolean isLabeled(@Nonnull String[] labels);

  T getEntity(@Nonnull Direction dir);
}
