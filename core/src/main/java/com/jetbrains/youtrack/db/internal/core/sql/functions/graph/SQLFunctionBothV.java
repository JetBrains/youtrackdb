package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.Relation;

/**
 *
 */
public class SQLFunctionBothV extends SQLFunctionMove {

  public static final String NAME = "bothV";

  public SQLFunctionBothV() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionInternal graph, final Identifiable record, final String[] labels) {
    return e2v(graph, record, Direction.BOTH);
  }

  @Override
  protected Object move(DatabaseSessionInternal db,
      Relation<?> bidirectionalLink, String[] labels) {
    return e2v(bidirectionalLink, Direction.BOTH);
  }
}
