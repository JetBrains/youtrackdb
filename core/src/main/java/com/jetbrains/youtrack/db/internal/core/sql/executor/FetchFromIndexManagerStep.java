package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

public class FetchFromIndexManagerStep extends AbstractExecutionStep {

  public FetchFromIndexManagerStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var schema = ctx.getDatabaseSession().getSchema();
    var indexes = schema.getIndexes();

    return ExecutionStream.iterator(indexes.stream().map(indexName -> {
      var indexDefinition = schema.getIndexDefinition(indexName);

      var result = new ResultInternal(ctx.getDatabaseSession());
      result.setProperty("name", indexDefinition.name());
      result.setProperty("className", indexDefinition.className());
      result.setProperty("properties", indexDefinition.properties());
      result.setProperty("type", indexDefinition.type().name());
      result.setProperty("nullValuesIgnored", indexDefinition.nullValuesIgnored());
      result.setProperty("collate", indexDefinition.collate());
      result.setProperty("metadata", indexDefinition.metadata());
      return result;
    }).iterator());
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ FETCH INDEXES METADATA";

    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }

    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromIndexManagerStep(ctx, profilingEnabled);
  }
}
