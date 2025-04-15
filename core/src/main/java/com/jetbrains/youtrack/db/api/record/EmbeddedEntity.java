package com.jetbrains.youtrack.db.api.record;

public interface EmbeddedEntity extends Entity {

  @Override
  default boolean isEmbedded() {
    return true;
  }
}
