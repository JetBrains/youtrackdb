package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import javax.annotation.Nullable;

public class FilterByClassStep extends AbstractExecutionStep {

  private SQLIdentifier identifier;
  private final String className;

  public FilterByClassStep(SQLIdentifier identifier, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.identifier = identifier;
    this.className = identifier.getStringValue();
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }

    var resultSet = prev.start(ctx);
    return resultSet.filter(this::filterMap);
  }

  @Nullable
  private Result filterMap(Result result, CommandContext ctx) {
    if (result.isEntity()) {
      var session = ctx.getDatabaseSession();
      var clazz = ((EntityImpl) result.asEntity()).getImmutableSchemaClass(session);
      if (clazz != null && clazz.isSubClassOf(className)) {
        return result;
      }
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    result.append(ExecutionStepInternal.getIndent(depth, indent));
    result.append("+ FILTER ITEMS BY CLASS");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    result.append(" \n");
    result.append(ExecutionStepInternal.getIndent(depth, indent));
    result.append("  ");
    result.append(identifier.getStringValue());
    return result.toString();
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("identifier", identifier.serialize(session));

    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      identifier = SQLIdentifier.deserialize(fromResult.getProperty("identifier"));
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FilterByClassStep(this.identifier.copy(), ctx, this.profilingEnabled);
  }
}
