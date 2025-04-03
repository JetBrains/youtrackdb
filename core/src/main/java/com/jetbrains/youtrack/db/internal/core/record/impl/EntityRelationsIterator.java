package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntityRelationsIterator extends
    RelationsIteratorAbstract<Entity, LightweightRelationImpl<Entity>> {

  public EntityRelationsIterator(@Nonnull Entity sourceEntity,
      Object multiValue,
      @Nonnull Iterator<? extends Identifiable> iterator,
      Pair<Direction, String> connection,
      String[] labels, int size,
      @Nonnull DatabaseSessionInternal session) {
    super(sourceEntity, multiValue, iterator, connection, labels, size, session);
  }

  @Nullable
  @Override
  protected LightweightRelationImpl<Entity> createBidirectionalLink(
      @Nullable Identifiable identifiable) {
    if (identifiable == null) {
      return null;
    }

    final Entity entity;
    try {
      var transaction = session.getActiveTransaction();
      entity = transaction.loadEntity(identifiable);
    } catch (RecordNotFoundException rnf) {
      // SKIP IT
      LogManager.instance().warn(this, "Record (%s) is null", identifiable);
      return null;
    }

    if (connection.getKey() == Direction.OUT) {
      return
          new LightweightRelationImpl<>(session,
              this.sourceEntity, entity, connection.value);
    } else {
      return
          new LightweightRelationImpl<>(session,
              entity, this.sourceEntity, connection.value);
    }

  }
}
