package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateRemoveItem;
import java.util.List;

/**
 *
 */
public class UpdateRemoveStep extends AbstractExecutionStep {

  private final List<SQLUpdateRemoveItem> items;

  public UpdateRemoveStep(
      List<SQLUpdateRemoveItem> items, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.items = items;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (result instanceof ResultInternal) {
      for (var item : items) {
        item.applyUpdate((ResultInternal) result, ctx);
      }
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ UPDATE REMOVE");
    for (var item : items) {
      result.append("\n");
      result.append(spaces);
      result.append("  ");
      result.append(item.toString());
    }
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new UpdateRemoveStep(items.stream().map(SQLUpdateRemoveItem::copy).toList(), ctx,
        profilingEnabled);
  }
}
