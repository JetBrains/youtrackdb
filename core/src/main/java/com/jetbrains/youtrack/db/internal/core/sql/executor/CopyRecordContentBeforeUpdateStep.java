package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Reads an upstream result set and returns a new result set that contains copies of the original
 * Result instances
 *
 * <p>This is mainly used from statements that need to copy of the original data before modifying
 * it, eg. UPDATE ... RETURN BEFORE
 */
public class CopyRecordContentBeforeUpdateStep extends AbstractExecutionStep {

  public CopyRecordContentBeforeUpdateStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var lastFetched = prev.start(ctx);
    return lastFetched.map(CopyRecordContentBeforeUpdateStep::mapResult);
  }

  private static Result mapResult(Result result, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    if (result instanceof UpdatableResult) {
      var prevValue = new ResultInternal(session);
      var rec = result.asEntityOrNull();
      prevValue.setProperty("@rid", rec.getIdentity());
      prevValue.setProperty("@version", rec.getVersion());
      if (rec instanceof EntityImpl) {
        SchemaImmutableClass result1 = null;
        if (rec != null) {
          result1 = ((EntityImpl) rec).getImmutableSchemaClass(session);
        }
        prevValue.setProperty(
            "@class",
            result1.getName());
      }
      if (!result.asEntityOrNull().getIdentity().isNew()) {
        for (var propName : result.getPropertyNames()) {
          prevValue.setProperty(
              propName, LiveQueryHookV2.unboxRidbags(result.getProperty(propName)));
        }
      }
      ((UpdatableResult) result).previousValue = prevValue;
    } else {
      throw new CommandExecutionException(session, "Cannot fetch previous value: " + result);
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ COPY RECORD CONTENT BEFORE UPDATE");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CopyRecordContentBeforeUpdateStep(ctx, profilingEnabled);
  }
}
