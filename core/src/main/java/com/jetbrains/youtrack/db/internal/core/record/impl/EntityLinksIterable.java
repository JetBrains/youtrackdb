package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Iterator;
import javax.annotation.Nonnull;

public class EntityLinksIterable extends
    BidirectionalLinkIterable<Entity, LightweightBidirectionalLinkImpl<Entity>> {

  protected EntityLinksIterable(@Nonnull Entity sourceEntity,
      Pair<Direction, String> connection,
      String[] labels,
      @Nonnull DatabaseSessionInternal session,
      @Nonnull Iterable<? extends Identifiable> iterable,
      int size, Object multiValue) {
    super(sourceEntity, connection, labels, session, iterable, size, multiValue);
  }

  @Nonnull
  @Override
  public Iterator<LightweightBidirectionalLinkImpl<Entity>> iterator() {
    return new EntityLinksIterator(
        sourceEntity,
        connection,
        iterable.iterator(),
        connection,
        labels,
        size, session);
  }
}
