package com.jetbrains.youtrack.db.api.record;

import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface Relation<L extends Entity> extends Element {
  @Nullable
  L fromEntity();

  @Nullable
  L toEntity();

  boolean isLabeled(@Nonnull String[] labels);

  L getEntity(@Nonnull Direction dir);

  boolean isLightweight();

  Entity asEntity();

  Map<String, Object> toMap();

  String toJSON();

  String label();
}
