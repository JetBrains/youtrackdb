package com.jetbrains.youtrackdb.internal.core.db.record.record;

public interface EmbeddedEntity extends Entity {

  @Override
  default boolean isEmbedded() {
    return true;
  }
}
