package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import java.util.Iterator;

/**
 * Source step that fetches records by executing an indexed function
 * (e.g. a spatial function like {@code ST_Within()}).
 *
 * <p>The function is identified as a {@link SQLBinaryCondition} in the WHERE clause
 * that supports index-accelerated execution. The step calls
 * {@code functionCondition.executeIndexedFunction(queryTarget, ctx)} which returns
 * a collection of {@link Identifiable} records matching the function's criteria.
 *
 * <pre>
 *  SQL:   SELECT FROM City WHERE ST_Within(location, ...) = true
 *
 *  Pipeline:
 *    FetchFromIndexedFunction -&gt; FilterByClass -&gt; [remaining FilterStep] -&gt; ...
 * </pre>
 *
 * @see SelectExecutionPlanner#handleClassAsTargetWithIndexedFunction
 */
public class FetchFromIndexedFunctionStep extends AbstractExecutionStep {

  /** The indexed function condition from the WHERE clause. */
  private SQLBinaryCondition functionCondition;

  /** The FROM clause target (class name) on which the function operates. */
  private SQLFromClause queryTarget;

  public FetchFromIndexedFunctionStep(
      SQLBinaryCondition functionCondition,
      SQLFromClause queryTarget,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.functionCondition = functionCondition;
    this.queryTarget = queryTarget;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var prev = this.prev;
    // Drain predecessor for side effects before executing indexed function.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var fullResult = init(ctx);
    // Mark as interruptable so the execution engine can check for cancellation between records.
    return ExecutionStream.loadIterator(fullResult).interruptable();
  }

  private Iterator<Identifiable> init(CommandContext ctx) {
    return functionCondition.executeIndexedFunction(queryTarget, ctx).iterator();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result =
        ExecutionStepInternal.getIndent(depth, indent)
            + "+ FETCH FROM INDEXED FUNCTION "
            + functionCondition.toString();
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("functionCondition", this.functionCondition.serialize(session));
    result.setProperty("queryTarget", this.queryTarget.serialize(session));

    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      functionCondition = new SQLBinaryCondition(-1);
      functionCondition.deserialize(fromResult.getProperty("functionCondition"));

      queryTarget = new SQLFromClause(-1);
      queryTarget.deserialize(fromResult.getProperty("queryTarget"));

    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  /**
   * Not cacheable: indexed function results depend on the current spatial/full-text
   * index state which may change between executions.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromIndexedFunctionStep(functionCondition.copy(), queryTarget.copy(), ctx,
        profilingEnabled);
  }
}
