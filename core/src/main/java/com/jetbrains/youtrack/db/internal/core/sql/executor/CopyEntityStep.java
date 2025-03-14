package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;


/**
 * Reads an upstream result set and returns a new result set that contains copies of the original
 * Result instances
 *
 * <p>This is mainly used from statements that need to copy of the original data to save it
 * somewhere else, eg. INSERT ... FROM SELECT
 */
public class CopyEntityStep extends AbstractExecutionStep {

  private final String className;

  public CopyEntityStep(CommandContext ctx, boolean profilingEnabled, String className) {
    super(ctx, profilingEnabled);
    this.className = className;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    var resultEntity = ctx.getDatabaseSession().newEntity(className);
    if (result.isEntity()) {
      var docToCopy = (EntityImpl) result.asEntity();
      for (var propName : docToCopy.getPropertyNames()) {
        resultEntity.setProperty(propName, docToCopy.getProperty(propName));
      }
    } else {
      for (var propName : result.getPropertyNames()) {
        resultEntity.setProperty(propName, result.getProperty(propName));
      }
    }

    return new UpdatableResult(ctx.getDatabaseSession(), resultEntity);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ COPY ENTITY");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }
}
