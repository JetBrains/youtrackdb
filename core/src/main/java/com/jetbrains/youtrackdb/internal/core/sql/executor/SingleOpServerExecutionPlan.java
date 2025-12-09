package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.ServerCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSimpleExecServerStatement;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SingleOpServerExecutionPlan implements InternalExecutionPlan {
  protected final SQLSimpleExecServerStatement statement;
  private final ServerCommandContext ctx;

  private boolean executed = false;
  private ExecutionStream result;

  public SingleOpServerExecutionPlan(ServerCommandContext ctx, SQLSimpleExecServerStatement stm) {
    this.ctx = ctx;
    this.statement = stm;
  }

  @Override
  public CommandContext getContext() {
    return ctx;
  }

  @Override
  public void close() {
  }

  @Override
  public ExecutionStream start() {
    if (executed && result == null) {
      return ExecutionStream.empty();
    }
    if (!executed) {
      executed = true;
      result = statement.executeSimple(this.ctx);
    }
    return result;
  }

  @Override
  public void reset(CommandContext ctx) {
    executed = false;
  }

  @Override
  public long getCost() {
    return 0;
  }

  @Override
  public boolean canBeCached() {
    return false;
  }

  public ExecutionStream executeInternal()
      throws CommandExecutionException {
    if (executed) {
      throw new CommandExecutionException(
          "Trying to execute a result-set twice. Please use reset()");
    }
    executed = true;
    result = statement.executeSimple(this.ctx);
    return result;
  }

  @Override
  public @Nonnull List<ExecutionStep> getSteps() {
    return Collections.emptyList();
  }

  @Override
  public @Nonnull String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ " + statement.toString();
    return result;
  }

  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSession session) {
    var result = new ResultInternal((DatabaseSessionInternal) session);
    result.setProperty("type", "QueryExecutionPlan");
    result.setProperty("javaType", getClass().getName());
    result.setProperty("stmText", statement.toString());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty("steps", null);
    return result;
  }
}
