package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * SQL function that returns the outgoing vertex from an edge.
 */
public class SQLFunctionOutV extends SQLFunctionMove implements SQLGraphNavigationFunction {
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

  @Nullable
  @Override
  public Collection<String> propertyNamesForIndexCandidates(String[] labels,
      SchemaClass schemaClass, boolean polymorphic, DatabaseSessionEmbedded session) {
    return List.of(Edge.DIRECTION_OUT);
  }
}
