package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;

/**
 * Returns the number of records contained in an index
 */
public class CountFromIndexWithKeyStep extends AbstractExecutionStep {

  private final SQLIndexIdentifier target;
  private final String alias;
  private final SQLExpression keyValue;

  /**
   * @param targetIndex      the index name as it is parsed by the SQL parsed
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
    var val =
        idx.getDefinition()
            .createValue(db.getActiveTransaction(), keyValue.execute(new ResultInternal(db), ctx));
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

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CountFromIndexWithKeyStep(target.copy(), keyValue.copy(), alias, ctx,
        profilingEnabled);
  }
}
