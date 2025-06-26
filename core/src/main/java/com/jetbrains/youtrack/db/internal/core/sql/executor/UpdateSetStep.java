package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLUpdateItem;
import java.util.List;

/**
 *
 */
public class UpdateSetStep extends AbstractExecutionStep {

  private final List<SQLUpdateItem> items;

  public UpdateSetStep(
      List<SQLUpdateItem> updateItems, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.items = updateItems;
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
    result.append("+ UPDATE SET");

    for (var item : items) {
      result.append("\n");
      result.append(spaces);
      result.append("  ");
      result.append(item.toString());
    }
    return result.toString();
  }
}
