package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Relation;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;

/**
 *
 */
public class SQLFunctionOutV extends SQLFunctionMove {

  public static final String NAME = "outV";

  public SQLFunctionOutV() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph, final Identifiable record, final String[] labels) {
    return e2v(graph, record, Direction.OUT);
  }

  @Override
  protected Object move(DatabaseSessionEmbedded db,
      Relation<?> bidirectionalLink, String[] labels) {
    return e2v(bidirectionalLink, Direction.OUT);
  }
}
