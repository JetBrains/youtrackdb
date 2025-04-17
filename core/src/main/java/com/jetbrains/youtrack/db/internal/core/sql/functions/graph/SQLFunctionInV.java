package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Relation;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;

/**
 *
 */
public class SQLFunctionInV extends SQLFunctionMove {

  public static final String NAME = "inV";

  public SQLFunctionInV() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionInternal graph, final Identifiable record, final String[] labels) {
    return e2v(graph, record, Direction.IN);
  }

  @Override
  protected Object move(DatabaseSessionInternal db,
      Relation<?> bidirectionalLink, String[] labels) {
    return e2v(bidirectionalLink, Direction.IN);
  }
}
