package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkNotNull;

import com.jetbrains.youtrack.db.api.record.Entity;

public abstract class YTDBElementImpl extends YTDBAbstractElement {
  private final ThreadLocal<Entity> threadLocalEntity = new ThreadLocal<>();

  public YTDBElementImpl(final YTDBGraphInternal graph, final Entity rawEntity) {
    super(graph, rawEntity);

    var entity = checkNotNull(rawEntity);
    threadLocalEntity.set(entity);
  }


  @Override
  public Entity getRawEntity() {
    var session = graph.getUnderlyingDatabaseSession();
    var tx = session.getActiveTransaction();

    var entity = threadLocalEntity.get();
    if (entity == null) {
      entity = tx.loadEntity(rid);
      threadLocalEntity.set(entity);

      return entity;
    }

    if (entity.isNotBound(session)) {
      entity = tx.load(entity);
      threadLocalEntity.set(entity);
    }

    return entity;
  }
}
