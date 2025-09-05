package com.jetbrains.youtrackdb.api.record;

import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface Relation<L extends Entity> extends Element {
  @Nullable
  L getFrom();

  @Nullable
  L getTo();

  boolean isLabeled(@Nonnull String[] labels);

  L getEntity(@Nonnull Direction dir);

  boolean isLightweight();

  Entity asEntity();

  @Nonnull
  Map<String, Object> toMap();

  @Nonnull
  String toJSON();

  String label();
}
