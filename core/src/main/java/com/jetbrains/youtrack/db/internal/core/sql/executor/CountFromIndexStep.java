package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ProduceExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIndexIdentifier;

/**
 * Returns the number of records contained in an index
 */
public class CountFromIndexStep extends AbstractExecutionStep {

  private final SQLIndexIdentifier target;
  private final String alias;

  /**
   * @param targetIndex      the index name as it is parsed by the SQL parsed
   * @param alias            the name of the property returned in the result-set
   * @param ctx              the query context
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public CountFromIndexStep(
      SQLIndexIdentifier targetIndex, String alias, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.target = targetIndex;
    this.alias = alias;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(this::produce).limit(1);
  }

  private Result produce(CommandContext ctx) {
    final var database = ctx.getDatabaseSession();
    var idx =
        database
            .getSharedContext()
            .getIndexManager()
            .getIndex(database, target.getIndexName());
    var size = idx.size(database);
    var result = new ResultInternal(database);
    result.setProperty(alias, size);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ CALCULATE INDEX SIZE: " + target;
  }
}
