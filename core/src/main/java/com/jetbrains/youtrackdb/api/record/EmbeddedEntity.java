package com.jetbrains.youtrackdb.api.record;

public interface EmbeddedEntity extends Entity {

  @Override
  default boolean isEmbedded() {
    return true;
  }
}
