package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.BidirectionalLink;

/**
 *
 */
public class SQLFunctionBothE extends SQLFunctionMove {

  public static final String NAME = "bothE";

  public SQLFunctionBothE() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionInternal graph, final Identifiable record, final String[] labels) {
    return v2e(graph, record, Direction.BOTH, labels);
  }

  @Override
  protected Object move(DatabaseSessionInternal db,
      BidirectionalLink<?> bidirectionalLink, String[] labels) {
    throw new UnsupportedOperationException(
        "Function 'bothE' is not supported for bidirectional links");
  }
}
