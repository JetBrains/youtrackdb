package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Relation;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.sql.functions.IndexableSQLFunction;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLFromClause;

public class SQLFunctionInV extends SQLFunctionMove implements IndexableSQLFunction {
  public static final String NAME = "inV";

  public SQLFunctionInV() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph, final Identifiable record, final String[] labels) {
    return e2v(graph, record, Direction.IN);
  }

  @Override
  protected Object move(DatabaseSessionEmbedded db,
      Relation<?> bidirectionalLink, String[] labels) {
    return e2v(bidirectionalLink, Direction.IN);
  }

  @Override
  public Iterable<Identifiable> searchFromTarget(SQLFromClause target,
      SQLBinaryCompareOperator operator, Object rightValue, CommandContext ctx,
      SQLExpression... args) {
    var className = target.getItem().getIdentifier().getStringValue();

    var session = ctx.getDatabaseSession();
    var schemaClass = session.getMetadata().getImmutableSchemaSnapshot()
        .getClassInternal(className);
    if (schemaClass != null && schemaClass.isEdgeType() && !schemaClass.isEdgeType()) {

    }
    return null;
  }

  @Override
  public long estimate(SQLFromClause target, SQLBinaryCompareOperator operator, Object rightValue,
      CommandContext ctx, SQLExpression... args) {
    return -1;
  }

  @Override
  public boolean canExecuteInline(SQLFromClause target, SQLBinaryCompareOperator operator,
      Object rightValue, CommandContext ctx, SQLExpression... args) {
    return true;
  }

  @Override
  public boolean allowsIndexedExecution(SQLFromClause target, SQLBinaryCompareOperator operator,
      Object rightValue, CommandContext ctx, SQLExpression... args) {
    var className = target.getItem().getIdentifier().getStringValue();

    var session = ctx.getDatabaseSession();
    var schemaClass = session.getMetadata().getImmutableSchemaSnapshot()
        .getClassInternal(className);

    if (schemaClass != null && schemaClass.isEdgeType() && !schemaClass.isEdgeType()) {
      return schemaClass.areIndexed(session, Edge.DIRECTION_IN);
    }

    return false;
  }

  @Override
  public boolean shouldExecuteAfterSearch(SQLFromClause target, SQLBinaryCompareOperator operator,
      Object rightValue, CommandContext ctx, SQLExpression... args) {
    return false;
  }
}
