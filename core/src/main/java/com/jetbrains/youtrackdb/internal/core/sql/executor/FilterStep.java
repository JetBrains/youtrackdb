package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExpireResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import javax.annotation.Nullable;

/**
 * Intermediate step that filters upstream records using a WHERE clause.
 *
 * <p>Each record is tested against {@code whereClause.matchesFilters(record, ctx)}.
 * Records that do not match are discarded (filtered out).
 *
 * <pre>
 *  Pipeline:
 *    ... upstream ... -&gt; FilterStep(WHERE age &gt; 30) -&gt; ... downstream ...
 *
 *  For each record:
 *    if whereClause.matchesFilters(record) == true -&gt; pass through
 *    else                                          -&gt; discard
 * </pre>
 *
 * <p>When a timeout is configured, the step wraps the filtered stream with an
 * {@link ExpireResultSet} that checks elapsed time between records and sends
 * a timeout signal when exceeded.
 *
 * @see SelectExecutionPlanner#handleWhere
 */
public class FilterStep extends AbstractExecutionStep {
  /** Query timeout in milliseconds; values &lt;= 0 mean no timeout is applied. */
  private final long timeoutMillis;

  /** The WHERE condition to evaluate against each record. */
  private SQLWhereClause whereClause;

  public FilterStep(
      SQLWhereClause whereClause, CommandContext ctx, long timeoutMillis,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.whereClause = whereClause;
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }

    // Register the WHERE expression so that upstream LET steps can check whether
    // a variable is referenced by the WHERE clause (via ctx.getParentWhereExpressions()).
    // This enables GlobalLetQueryStep to decide if the subquery result must be
    // materialized into a List for repeated iteration.
    ctx.registerBooleanExpression(whereClause.getBaseExpression());
    var resultSet = prev.start(ctx);
    resultSet = resultSet.filter(this::filterMap);
    if (timeoutMillis > 0) {
      resultSet = new ExpireResultSet(resultSet, timeoutMillis, this::sendTimeout);
    }
    return resultSet;
  }

  @Nullable
  private Result filterMap(Result result, CommandContext ctx) {
    if (whereClause.matchesFilters(result, ctx)) {
      return result;
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    result.append(ExecutionStepInternal.getIndent(depth, indent)).append("+ FILTER ITEMS WHERE ");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    result.append("\n");
    result.append(ExecutionStepInternal.getIndent(depth, indent));
    result.append("  ");
    result.append(whereClause.toString());
    return result.toString();
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    if (whereClause != null) {
      result.setProperty("whereClause", whereClause.serialize(session));
    }

    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      whereClause = new SQLWhereClause(-1);
      whereClause.deserialize(fromResult.getProperty("whereClause"));
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  /** Cacheable: the WHERE clause is a structural AST node that is deep-copied per execution. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FilterStep(this.whereClause.copy(), ctx, timeoutMillis, profilingEnabled);
  }
}
