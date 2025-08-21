package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.DDLStatement;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class DDLExecutionPlan implements InternalExecutionPlan {
  private final DDLStatement statement;
  private final CommandContext ctx;

  private boolean executed = false;

  public DDLExecutionPlan(CommandContext ctx, DDLStatement stm) {
    this.ctx = ctx;
    this.statement = stm;
  }

  @Override
  public void close() {
  }

  @Override
  public CommandContext getContext() {
    return ctx;
  }

  @Override
  public ExecutionStream start() {
    return ExecutionStream.empty();
  }

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

  public ExecutionStream executeInternal(BasicCommandContext ctx)
      throws CommandExecutionException {
    if (executed) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Trying to execute a result-set twice. Please use reset()");
    }
    executed = true;
    var result = statement.executeDDL(this.ctx);
    return result;
  }

  @Override
  public @Nonnull List<ExecutionStep> getSteps() {
    return Collections.emptyList();
  }

  @Override
  public @Nonnull String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ DDL\n" + "  " + statement.toString();
    return result;
  }

  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSession session) {
    var result = new ResultInternal((DatabaseSessionInternal) session);
    result.setProperty("type", "DDLExecutionPlan");
    result.setProperty(JAVA_TYPE, getClass().getName());
    result.setProperty("stmText", statement.toString());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    return result;
  }
}
