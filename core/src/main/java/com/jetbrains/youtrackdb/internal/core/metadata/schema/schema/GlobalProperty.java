package com.jetbrains.youtrackdb.internal.core.metadata.schema.schema;

import javax.annotation.Nonnull;

public interface GlobalProperty {

  @Nonnull
  Integer getId();

  @Nonnull
  String getName();

  @Nonnull
  PropertyType getType();
}
