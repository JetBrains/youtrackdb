package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;

/**
 * The **entry point** step for a MATCH pattern traversal — produces the initial set of
 * records for the first node in the scheduled edge order.
 * <p>
 * ### Data source selection
 * <p>
 * The step obtains its records from one of two sources, checked in order:
 * <p>
 * 1. **Prefetched cache** — if the alias was eagerly loaded by a preceding
 *    {@link MatchPrefetchStep}, the cached results are read from the context variable
 *    `$$YouTrackDB_Prefetched_Alias_Prefix__<alias>`.
 * 2. **Sub-execution plan** — otherwise, a synthetic `SELECT` plan is executed to scan
 *    the alias's class or RID.
 * <p>
 * ### Output format
 * <p>
 * Each input record is wrapped in a new {@link ResultInternal} that stores the record
 * under the node's alias. This "row" accumulates additional alias → record mappings
 * as subsequent {@link MatchStep}s are executed. The context variable `$matched` is
 * also updated to point to the current row.
 *
 * @see MatchStep
 * @see MatchPrefetchStep
 * @see MatchExecutionPlanner
 */
public class MatchFirstStep extends AbstractExecutionStep {

  /** The pattern node whose records this step produces. */
  private final PatternNode node;

  /**
   * An optional sub-execution plan (typically a `SELECT` scan) used when no
   * prefetched data is available. May be `null` when prefetched data is expected.
   */
  private final InternalExecutionPlan executionPlan;

  /**
   * Constructs a step that reads from prefetched cache only (no sub-plan).
   */
  public MatchFirstStep(CommandContext context, PatternNode node, boolean profilingEnabled) {
    this(context, node, null, profilingEnabled);
  }

  /**
   * @param context          the command execution context
   * @param node             the pattern node whose alias names the output property
   * @param subPlan          the sub-execution plan to scan records, or `null` to use
   *                         prefetched data
   * @param profilingEnabled whether to collect execution statistics
   */
  public MatchFirstStep(
      CommandContext context,
      PatternNode node,
      InternalExecutionPlan subPlan,
      boolean profilingEnabled) {
    super(context, profilingEnabled);
    assert MatchAssertions.checkNotNull(node, "pattern node");
    assert MatchAssertions.checkNotEmpty(node.alias, "pattern node alias");
    this.node = node;
    this.executionPlan = subPlan;
  }

  @Override
  public void reset() {
    if (executionPlan != null) {
      executionPlan.reset(ctx);
    }
  }

  /**
   * Produces the initial stream of MATCH rows. Each output row contains a single
   * property `alias → record`, and the context variable `$matched` is set to the
   * current row so that downstream `WHERE` clauses can reference previously matched
   * aliases via `$matched.<alias>`.
   */
  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain any previous step (shouldn't normally exist for the first step in a plan)
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    ExecutionStream data;
    var alias = getAlias();

    // Check whether the alias was prefetched by a MatchPrefetchStep
    @SuppressWarnings("unchecked")
    var matchedNodes =
        (List<Result>) ctx.getVariable(MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + alias);
    if (matchedNodes != null) {
      data = ExecutionStream.resultIterator(matchedNodes.iterator());
    } else {
      data = executionPlan.start();
    }

    // Wrap each raw record into a MATCH row: { alias → record }
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
