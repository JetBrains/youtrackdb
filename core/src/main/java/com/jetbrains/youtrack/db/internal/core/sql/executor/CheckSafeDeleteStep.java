package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Checks if a record can be safely deleted (throws CommandExecutionException in case). A record
 * cannot be safely deleted if it's a vertex or an edge (it requires additional operations).
 *
 * <p>The result set returned by syncPull() throws an CommandExecutionException as soon as it
 * finds a record that cannot be safely deleted (eg. a vertex or an edge)
 *
 * <p>This step is used used in DELETE statement to make sure that you are not deleting vertices or
 * edges without passing for an explicit DELETE VERTEX/EDGE
 */
public class CheckSafeDeleteStep extends AbstractExecutionStep {

  public CheckSafeDeleteStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    ExecutionStream upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (result.isEntity()) {
      var elem = result.toEntity();
      SchemaClass clazz = EntityInternalUtils.getImmutableSchemaClass((EntityImpl) elem);
      if (clazz != null) {
        if (clazz.getName().equalsIgnoreCase("V") || clazz.isSubClassOf(ctx.getDatabase(), "V")) {
          throw new CommandExecutionException(
              "Cannot safely delete a vertex, please use DELETE VERTEX or UNSAFE");
        }
        if (clazz.getName().equalsIgnoreCase("E") || clazz.isSubClassOf(ctx.getDatabase(), "E")) {
          throw new CommandExecutionException(
              "Cannot safely delete an edge, please use DELETE EDGE or UNSAFE");
        }
      }
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    StringBuilder result = new StringBuilder();
    result.append(spaces);
    result.append("+ CHECK SAFE DELETE");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }
}
