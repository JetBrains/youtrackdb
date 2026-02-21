package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Source step for {@code SELECT FROM metadata:INDEXES}.
 *
 * <p>Produces one result record per index defined in the schema, with properties:
 * <ul>
 *   <li>{@code name} -- index name</li>
 *   <li>{@code className} -- the class the index belongs to</li>
 *   <li>{@code properties} -- list of indexed field names</li>
 *   <li>{@code type} -- index type (e.g. UNIQUE, NOTUNIQUE)</li>
 *   <li>{@code nullValuesIgnored} -- whether null values are excluded</li>
 *   <li>{@code collate} -- collation setting</li>
 *   <li>{@code metadata} -- additional index metadata</li>
 * </ul>
 *
 * @see SelectExecutionPlanner#handleMetadataAsTarget
 */
public class FetchFromIndexManagerStep extends AbstractExecutionStep {

  public FetchFromIndexManagerStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before querying indexes.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var schema = ctx.getDatabaseSession().getSchema();
    // getIndexes() returns only index names; we look up the full definition for each.
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
