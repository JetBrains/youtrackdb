package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * SQL function that returns outgoing edges of a vertex.
 */
public class SQLFunctionOutE extends SQLFunctionMove implements SQLGraphNavigationFunction {

  public static final String NAME = "outE";

  public SQLFunctionOutE() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph, final Identifiable record, final String[] labels) {
    return v2e(graph, record, Direction.OUT, labels);
  }

  @Nullable @Override
  public Collection<String> propertyNamesForIndexCandidates(String[] labels,
      SchemaClass schemaClass, boolean polymorphic, DatabaseSessionEmbedded session) {
    return SQLGraphNavigationFunction.propertiesForV2ENavigation(schemaClass, session,
        Direction.OUT, labels);
  }
}
