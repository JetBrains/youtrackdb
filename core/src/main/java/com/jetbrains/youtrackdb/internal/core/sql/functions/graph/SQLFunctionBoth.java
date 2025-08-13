package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Relation;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 *
 */
public class SQLFunctionBoth extends SQLFunctionMove implements SQLGraphNavigationFunction {

  public static final String NAME = "both";

  public SQLFunctionBoth() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph, final Identifiable record, final String[] labels) {
    return v2v(graph, record, Direction.BOTH, labels);
  }

  @Override
  protected Object move(DatabaseSessionEmbedded db,
      Relation<?> bidirectionalLink, String[] labels) {
    throw new UnsupportedOperationException(
        "Function 'both' is not supported for bidirectional links");
  }

  @Nullable
  @Override
  public Collection<String> propertyNamesForIndexCandidates(String[] labels,
      SchemaClass schemaClass,
      boolean polymorphic, DatabaseSessionEmbedded session) {
    return SQLGraphNavigationFunction.propertiesForV2VNavigation(schemaClass, session,
        Direction.BOTH, labels);
  }
}
