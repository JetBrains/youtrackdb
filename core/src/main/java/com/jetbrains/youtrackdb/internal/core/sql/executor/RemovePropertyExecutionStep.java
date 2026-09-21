package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes named properties from each upstream row.
 *
 * <p>Used to strip optimizer-minted {@code _$$$ORDER_BY_ALIAS$$$_N} columns after sorting (or
 * after an index-applied ORDER BY) without rebuilding the projection list — alias-list rebuild
 * breaks select-all / exclusion shapes.
 */
public class RemovePropertyExecutionStep extends AbstractExecutionStep {

  private final List<String> propertyNames;

  public RemovePropertyExecutionStep(
      List<String> propertyNames, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.propertyNames = List.copyOf(propertyNames);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("Cannot remove properties without a previous source");
    }
    if (propertyNames.isEmpty()) {
      return prev.start(ctx);
    }
    return prev.start(ctx).map(this::strip);
  }

  private Result strip(Result result, CommandContext ctx) {
    ResultInternal row;
    if (result instanceof ResultInternal internal) {
      row = internal;
    } else {
      row = new ResultInternal(ctx.getDatabaseSession());
      for (var name : result.getPropertyNames()) {
        row.setProperty(name, result.getProperty(name));
      }
    }
    for (var name : propertyNames) {
      row.removeProperty(name);
    }
    return row;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ REMOVE PROPERTIES";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    result += ("\n" + spaces + "  " + propertyNames);
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new RemovePropertyExecutionStep(new ArrayList<>(propertyNames), ctx, profilingEnabled);
  }
}
