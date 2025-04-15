package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class MatchPrefetchStep extends AbstractExecutionStep {

  public static final String PREFETCHED_MATCH_ALIAS_PREFIX = "$$YouTrackDB_Prefetched_Alias_Prefix__";

  private final String alias;
  private final InternalExecutionPlan prefetchExecutionPlan;

  public MatchPrefetchStep(
      CommandContext ctx,
      InternalExecutionPlan prefetchExecPlan,
      String alias,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.prefetchExecutionPlan = prefetchExecPlan;
    this.alias = alias;
  }

  @Override
  public void reset() {
    prefetchExecutionPlan.reset(ctx);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var nextBlock = prefetchExecutionPlan.start();
    List<Result> prefetched = new ArrayList<>();
    while (nextBlock.hasNext(ctx)) {
      prefetched.add(nextBlock.next(ctx));
    }
    nextBlock.close(ctx);
    prefetchExecutionPlan.close();
    ctx.setVariable(PREFETCHED_MATCH_ALIAS_PREFIX + alias, prefetched);
    return ExecutionStream.empty();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces
        + "+ PREFETCH "
        + alias
        + "\n"
        + prefetchExecutionPlan.prettyPrint(depth + 1, indent);
  }
}
