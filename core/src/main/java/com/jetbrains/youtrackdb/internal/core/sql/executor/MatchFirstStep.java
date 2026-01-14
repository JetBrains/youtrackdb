package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;

public class MatchFirstStep extends AbstractExecutionStep {

  private final PatternNode node;
  private final InternalExecutionPlan executionPlan;

  public MatchFirstStep(CommandContext context, PatternNode node, boolean profilingEnabled) {
    this(context, node, null, profilingEnabled);
  }

  public MatchFirstStep(
      CommandContext context,
      PatternNode node,
      InternalExecutionPlan subPlan,
      boolean profilingEnabled) {
    super(context, profilingEnabled);
    this.node = node;
    this.executionPlan = subPlan;
  }

  @Override
  public void reset() {
    if (executionPlan != null) {
      executionPlan.reset(ctx);
    }
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    ExecutionStream data;
    var alias = getAlias();

    @SuppressWarnings("unchecked")
    var matchedNodes =
        (List<Result>) ctx.getVariable(MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + alias);
    if (matchedNodes != null) {
      data = ExecutionStream.resultIterator(matchedNodes.iterator());
    } else {
      data = executionPlan.start();
    }

    return data.map(
        (result, context) -> {
          var newResult = new ResultInternal(context.getDatabaseSession());
          newResult.setProperty(getAlias(), result);
          context.setVariable("$matched", newResult);
          return newResult;
        });
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ SET \n");
    result.append(spaces);
    result.append("   ");
    result.append(getAlias());
    if (executionPlan != null) {
      result.append("\n");
      result.append(spaces);
      result.append("  AS\n");
      result.append(executionPlan.prettyPrint(depth + 1, indent));
    }

    return result.toString();
  }

  private String getAlias() {
    return this.node.alias;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    PatternNode nodeCopy = null;
    InternalExecutionPlan executionPlanCopy = null;

    if (node != null) {
      nodeCopy = node.copy();
    }
    if (executionPlan != null) {
      executionPlanCopy = executionPlan.copy(ctx);
    }

    return new MatchFirstStep(ctx, nodeCopy, executionPlanCopy, profilingEnabled);
  }
}
