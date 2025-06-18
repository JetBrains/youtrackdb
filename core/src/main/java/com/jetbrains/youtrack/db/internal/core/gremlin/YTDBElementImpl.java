package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkNotNull;

import com.jetbrains.youtrack.db.api.record.Entity;

public abstract class YTDBElementImpl extends YTDBAbstractElement {
  private final ThreadLocal<Entity> threadLocalEntity = new ThreadLocal<>();
  private final Entity fastPathEntity;

  public YTDBElementImpl(final YTDBGraphInternal graph, final Entity rawEntity) {
    super(graph, rawEntity);
    fastPathEntity = checkNotNull(rawEntity);
  }


  @Override
  public Entity getRawEntity() {
    var graphTx = (YTDBTransaction) graph().tx();
    var session = graphTx.getSession();

    if (fastPathEntity.isNotBound(session)) {
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
    } else {
      return fastPathEntity;
    }
  }
}
