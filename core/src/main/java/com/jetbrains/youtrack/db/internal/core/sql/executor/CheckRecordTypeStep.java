package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Optional;

/**
 * Checks that all the records from the upstream are of a particular type (or subclasses). Throws
 * CommandExecutionException in case it's not true
 */
public class CheckRecordTypeStep extends AbstractExecutionStep {

  private final String clazz;

  public CheckRecordTypeStep(CommandContext ctx, String className, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.clazz = className;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    ExecutionStream upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (!result.isEntity()) {
      throw new CommandExecutionException("record " + result + " is not an instance of " + clazz);
    }
    Entity entity = result.toEntity();
    if (entity == null) {
      throw new CommandExecutionException("record " + result + " is not an instance of " + clazz);
    }
    Optional<SchemaClass> schema = entity.getSchemaType();

    if (schema.isEmpty() || !schema.get().isSubClassOf(ctx.getDatabase(), clazz)) {
      throw new CommandExecutionException("record " + result + " is not an instance of " + clazz);
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String result = ExecutionStepInternal.getIndent(depth, indent) + "+ CHECK RECORD TYPE";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    result += (ExecutionStepInternal.getIndent(depth, indent) + "  " + clazz);
    return result;
  }
}
