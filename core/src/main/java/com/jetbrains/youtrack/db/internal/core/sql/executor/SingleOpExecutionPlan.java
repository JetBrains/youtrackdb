package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSimpleExecStatement;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class SingleOpExecutionPlan implements InternalExecutionPlan {

  protected final SQLSimpleExecStatement statement;
  private final CommandContext ctx;

  private boolean executed = false;
  private ExecutionStream result;

  public SingleOpExecutionPlan(CommandContext ctx, SQLSimpleExecStatement stm) {
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
  public @Nonnull Result toResult(@Nullable DatabaseSession db) {
    var result = new ResultInternal((DatabaseSessionInternal) db);
    result.setProperty("type", "QueryExecutionPlan");
    result.setProperty("javaType", getClass().getName());
    result.setProperty("stmText", statement.toString());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty("steps", null);
    return result;
  }
}
