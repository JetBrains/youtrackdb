package com.jetbrains.youtrackdb.internal.core.db.record.record;

import javax.annotation.Nonnull;

public interface StatefulEdge extends Edge, Entity {

  @Nonnull
  @Override
  default Entity asEntity() {
    return this;
  }
}
