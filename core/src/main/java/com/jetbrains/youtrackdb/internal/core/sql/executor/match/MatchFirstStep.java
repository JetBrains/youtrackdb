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
import javax.annotation.Nonnull;

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
          context.setSystemVariable(CommandContext.VAR_MATCHED, newResult);
          return newResult;
        });
  }

  @Override
  public boolean canBeCached() {
    return executionPlan == null || executionPlan.canBeCached();
  }

  /**
   * Exposes the scan sub-plan's steps so plan introspection can see the nested fetch, or an empty
   * list when this step carries no sub-plan.
   * <p>
   * The inherited default reports no children, which hides the sub-plan from every caller that
   * walks {@link ExecutionStep#getSubSteps()} — {@code EXPLAIN} result documents built by
   * {@link ExecutionStep#toResult}, and the index-usage scans in the test tree that ask whether a
   * plan fetches from an index. (No production index-usage scan reads this accessor;
   * {@code YTDBGraphQuery.usedIndexes} walks top-level steps and {@code getSubExecutionPlans()}
   * only.) {@link #prettyPrint} already inlines the same sub-plan, so the text rendering was the
   * only place the nested steps were visible.
   * <p>
   * The empty list means the pattern planner built this step without a sub-plan, which it does
   * for an alias a {@link MatchPrefetchStep} already loads — an alias whose estimated cardinality
   * is below {@code MatchExecutionPlanner.THRESHOLD} and whose filter does not depend on
   * {@code $matched}. That step reads the prefetch cache in {@link #internalStart} and would
   * never start a sub-plan, so its fetch lives under {@code MatchPrefetchStep}, which overrides
   * this accessor for the same reason. An alias the planner does not prefetch keeps its scan
   * here. The rule is about how the planner built the step, not about pattern shape: it holds for
   * an isolated node and for the root of an edge pattern alike. It does not extend to the
   * NOT-pattern and hash-join branch builders, which construct their own {@code MatchFirstStep}s
   * without consulting the prefetch set — a step inside one of those build plans can carry a
   * sub-plan for an alias that is prefetched anyway, and its fetch is then reachable under both
   * steps.
   * <p>
   * {@code getSubExecutionPlans()} deliberately keeps its empty default. Callers such as the
   * index-counting test helpers walk both accessors, so publishing the same nested steps through
   * both would make them count every nested fetch twice.
   */
  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    return executionPlan == null ? List.of() : List.copyOf(executionPlan.getSteps());
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
