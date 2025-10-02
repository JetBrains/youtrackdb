package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Relation;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaClass;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 *
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
      ImmutableSchemaClass schemaClass, boolean polymorphic, DatabaseSessionEmbedded session) {
    return List.of(Edge.DIRECTION_OUT);
  }
}
