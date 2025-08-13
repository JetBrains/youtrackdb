package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import java.util.Iterator;
import javax.annotation.Nonnull;

public class EdgeIterable extends RelationsIterable<Vertex, EdgeInternal> {

  protected EdgeIterable(@Nonnull Vertex sourceEntity,
      Pair<Direction, String> connection,
      String[] labels,
      @Nonnull DatabaseSessionInternal session,
      @Nonnull Iterable<? extends Identifiable> iterable,
      int size, Object multiValue) {
    super(sourceEntity, connection, labels, session, iterable, size, multiValue);
  }

  @Nonnull
  @Override
  public Iterator<EdgeInternal> iterator() {
    return new EdgeIterator(sourceEntity, multiValue, iterable.iterator(), connection, labels, size,
        session);
  }
}