package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;

/**
 * Source step that counts the total number of entries in an index (without scanning
 * records), producing a single result with the count.
 *
 * <p>Used for {@code SELECT count(*) FROM index:indexName} queries.
 */
public class CountFromIndexStep extends AbstractExecutionStep {

  private final SQLIndexIdentifier target;
  private final String alias;

  /**
   * @param targetIndex      the index name as parsed by the SQL parser
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
    // Drain predecessor for side effects (this step is a self-contained source).
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(this::produce).limit(1);
  }

  private Result produce(CommandContext ctx) {
    final var database = ctx.getDatabaseSession();
    // Look up the index by name through the shared (cross-session) index manager.
    var idx =
        database
            .getSharedContext()
            .getIndexManager()
            .getIndex(target.getIndexName());
    var size = database.computeInTxInternal(tx -> idx.size(database));
    var result = new ResultInternal(database);
    result.setProperty(alias, size);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ CALCULATE INDEX SIZE: " + target;
  }

  /**
   * Not cacheable: the index entry count changes between executions as records
   * are inserted or deleted, so the result must always be recomputed.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CountFromIndexStep(target.copy(), alias, ctx, profilingEnabled);
  }
}
