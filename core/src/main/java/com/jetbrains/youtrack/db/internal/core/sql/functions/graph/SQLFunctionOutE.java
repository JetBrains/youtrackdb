package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.Relation;

/**
 *
 */
public class SQLFunctionOutE extends SQLFunctionMove {

  public static final String NAME = "outE";

  public SQLFunctionOutE() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionInternal graph, final Identifiable record, final String[] labels) {
    return v2e(graph, record, Direction.OUT, labels);
  }

  @Override
  protected Object move(DatabaseSessionInternal db,
      Relation<?> bidirectionalLink, String[] labels) {
    throw new UnsupportedOperationException(
        "Function 'outE' is not supported for bidirectional links");
  }
}
