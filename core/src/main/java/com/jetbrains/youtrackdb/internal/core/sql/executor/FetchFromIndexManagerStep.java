package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

public class FetchFromIndexManagerStep extends AbstractExecutionStep {

  public FetchFromIndexManagerStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var schema = ctx.getDatabaseSession().getMetadata().getFastImmutableSchemaSnapshot();
    var indexes = schema.getIndexes();

    return ExecutionStream.iterator(indexes.stream().map(index -> {
      var indexDefinition = index.getDefinition();

      var result = new ResultInternal(ctx.getDatabaseSession());
      result.setProperty("name", index.getName());
      result.setProperty("className", indexDefinition.getClassName());
      result.setProperty("properties", indexDefinition.getProperties());
      result.setProperty("type", index.getType().name());
      result.setProperty("nullValuesIgnored", indexDefinition.isNullValuesIgnored());
      result.setProperty("collate", indexDefinition.getCollate().getName());
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
