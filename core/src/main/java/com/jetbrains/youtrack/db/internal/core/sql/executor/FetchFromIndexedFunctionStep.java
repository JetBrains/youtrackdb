package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLFromClause;
import java.util.Iterator;

/**
 *
 */
public class FetchFromIndexedFunctionStep extends AbstractExecutionStep {

  private SQLBinaryCondition functionCondition;
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
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var fullResult = init(ctx);
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
  public Result serialize(DatabaseSessionInternal session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("functionCondition", this.functionCondition.serialize(session));
    result.setProperty("queryTarget", this.queryTarget.serialize(session));

    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      functionCondition = new SQLBinaryCondition(-1);
      functionCondition.deserialize(fromResult.getProperty("functionCondition "));

      queryTarget = new SQLFromClause(-1);
      queryTarget.deserialize(fromResult.getProperty("functionCondition "));

    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }
}
