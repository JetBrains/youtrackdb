package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.record.Entity;

public abstract class YTDBElementWrapper extends YTDBAbstractElement {

  private final Entity rawEntity;

  public YTDBElementWrapper(YTDBGraphInternal graph, Entity rawEntity) {
    super(graph, rawEntity);
    this.rawEntity = rawEntity;
  }

  @Override
  public Entity getRawEntity() {
    return rawEntity;
  }
}
