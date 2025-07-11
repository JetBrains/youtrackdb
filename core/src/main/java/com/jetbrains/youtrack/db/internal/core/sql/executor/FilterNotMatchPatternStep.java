package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FilterNotMatchPatternStep extends AbstractExecutionStep {

  private final List<AbstractExecutionStep> subSteps;

  public FilterNotMatchPatternStep(
      List<AbstractExecutionStep> steps, CommandContext ctx, boolean enableProfiling) {
    super(ctx, enableProfiling);
    this.subSteps = steps;
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
    if (!matchesPattern(result, ctx)) {
      return result;
    }
    return null;
  }

  private boolean matchesPattern(Result nextItem, CommandContext ctx) {
    var plan = createExecutionPlan(nextItem, ctx);
    var rs = plan.start();
    try {
      return rs.hasNext(ctx);
    } finally {
      rs.close(ctx);
    }
  }

  private SelectExecutionPlan createExecutionPlan(Result nextItem, CommandContext ctx) {
    var plan = new SelectExecutionPlan(ctx);
    var db = ctx.getDatabaseSession();

    plan.chain(
        new ChainStep(ctx, nextItem, db));
    subSteps.forEach(plan::chain);
    return plan;
  }

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    //noinspection unchecked,rawtypes
    return (List) subSteps;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ NOT (\n");
    this.subSteps.forEach(x -> result.append(x.prettyPrint(depth + 1, indent)).append("\n"));
    result.append(spaces);
    result.append("  )");
    return result.toString();
  }

  @Override
  public void close() {
    super.close();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var subStepsCopy = subSteps.stream().map(x -> (AbstractExecutionStep) x.copy(ctx)).toList();
    return new FilterNotMatchPatternStep(subStepsCopy, ctx, profilingEnabled);
  }

  private class ChainStep extends AbstractExecutionStep {

    private final Result nextItem;
    private final DatabaseSessionEmbedded db;

    public ChainStep(CommandContext ctx, Result nextItem, DatabaseSessionEmbedded db) {
      super(ctx, FilterNotMatchPatternStep.this.profilingEnabled);
      this.nextItem = nextItem;
      this.db = db;
    }

    @Override
    public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
      return ExecutionStream.singleton(copy(nextItem));
    }

    private Result copy(Result nextItem) {
      var result = new ResultInternal(db);
      for (var prop : nextItem.getPropertyNames()) {
        result.setProperty(prop, nextItem.getProperty(prop));
      }
      if (nextItem instanceof ResultInternal nextResult) {
        for (var md : nextResult.getMetadataKeys()) {
          result.setMetadata(md, nextResult.getMetadata(md));
        }
      }

      return result;
    }

    @Override
    public ExecutionStep copy(CommandContext ctx) {
      return new ChainStep(ctx, nextItem, db);
    }
  }
}
