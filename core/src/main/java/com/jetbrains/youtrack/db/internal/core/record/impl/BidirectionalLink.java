package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface BidirectionalLink<T extends Entity> {

  @Nullable
  T fromEntity();

  @Nullable
  T toEntity();

  boolean isLabeled(@Nonnull String[] labels);

  T getEntity(@Nonnull Direction dir);

  boolean isLightweight();

  Entity asEntity();

  Map<String, Object> toMap();

  String toJSON();

  String label();
}
