package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;

/**
 * Hardwired optimization step for {@code SELECT count(*) FROM ClassName WHERE field = ?}
 * when a single-field index exists.
 *
 * <p>Instead of scanning and filtering, this step performs a single index key lookup
 * and counts the distinct RIDs in the result, producing one record with the count.
 *
 * @see SelectExecutionPlanner#handleHardwiredCountOnClassUsingIndex
 */
public class CountFromIndexWithKeyStep extends AbstractExecutionStep {

  private final SQLIndexIdentifier target;
  private final String alias;
  private final SQLExpression keyValue;

  /**
   * @param targetIndex      the index name as parsed by the SQL parser
   * @param keyValue         the key value expression to look up in the index
   * @param alias            the name of the property returned in the result-set
   * @param ctx              the query context
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public CountFromIndexWithKeyStep(
      SQLIndexIdentifier targetIndex,
      SQLExpression keyValue,
      String alias,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.target = targetIndex;
    this.alias = alias;
    this.keyValue = keyValue;
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
    var session = ctx.getDatabaseSession();
    var idx = session.getSharedContext().getIndexManager()
        .getIndex(target.getIndexName());
    var db = ctx.getDatabaseSession();
    // Evaluate the key expression against an empty result (no "current record" in a count query).
    var val =
        idx.getDefinition()
            .createValue(db.getActiveTransaction(), keyValue.execute(new ResultInternal(db), ctx));
    // Count distinct RIDs since non-unique indexes may store duplicate RIDs.
    var size = idx.getRids(db, val).distinct().count();
    var result = new ResultInternal(db);
    result.setProperty(alias, size);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ CALCULATE INDEX SIZE BY KEY: " + target;
  }

  /**
   * Not cacheable: the index entry count for a given key changes between executions
   * as records are inserted or deleted.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CountFromIndexWithKeyStep(target.copy(), keyValue.copy(), alias, ctx,
        profilingEnabled);
  }
}
