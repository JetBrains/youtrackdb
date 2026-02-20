package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Eagerly loads all records for a small alias into memory and stores them in the
 * execution context for later reuse.
 * <p>
 * When the {@link MatchExecutionPlanner} estimates that an alias has fewer than
 * {@code MatchExecutionPlanner.THRESHOLD} records, it inserts a `MatchPrefetchStep`
 * at the beginning of the plan. The step executes a `SELECT` sub-plan, collects all
 * results into a list, and stores them under the context variable
 * `$$YouTrackDB_Prefetched_Alias_Prefix__<alias>`.
 * <p>
 * Downstream {@link MatchFirstStep}s check for this variable before running their own
 * sub-plan, avoiding redundant scans of the same small set during the nested-loop
 * pattern matching.
 * <p>
 * <pre>
 * Prefetch → consume handoff:
 *
 *   MatchPrefetchStep                         MatchFirstStep
 *   ┌───────────────────────────┐             ┌───────────────────────────┐
 *   │ Execute SELECT sub-plan   │             │ Check context variable:   │
 *   │ Collect all results       │──ctx.set──→ │   key exists?             │
 *   │ Store in context under    │  (key)      │     YES → read from cache │
 *   │   PREFETCHED_...+alias    │             │     NO  → execute sub-plan│
 *   │ Return empty stream       │             │ Wrap each record as       │
 *   └───────────────────────────┘             │   {alias → record}        │
 *                                             └───────────────────────────┘
 * </pre>
 *
 * This step produces an **empty** output stream — it is a side-effect-only step whose
 * sole purpose is to populate the context cache.
 *
 * @see MatchFirstStep
 * @see MatchExecutionPlanner
 */
public class MatchPrefetchStep extends AbstractExecutionStep {

  /**
   * Prefix for context variable keys that hold prefetched alias data.
   * The full key is `PREFETCHED_MATCH_ALIAS_PREFIX + alias`.
   */
  public static final String PREFETCHED_MATCH_ALIAS_PREFIX = "$$YouTrackDB_Prefetched_Alias_Prefix__";

  /** The alias whose records are being prefetched. */
  private final String alias;

  /** The sub-plan that scans/filters the alias's records. */
  private final InternalExecutionPlan prefetchExecutionPlan;

  public MatchPrefetchStep(
      CommandContext ctx,
      InternalExecutionPlan prefetchExecPlan,
      String alias,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    assert prefetchExecPlan != null : "prefetch execution plan must not be null";
    assert alias != null : "alias must not be null";
    this.prefetchExecutionPlan = prefetchExecPlan;
    this.alias = alias;
  }

  @Override
  public void reset() {
    prefetchExecutionPlan.reset(ctx);
  }

  /**
   * Executes the sub-plan, collects all results into a list, stores them in the context,
   * and returns an empty stream.
   */
  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    // Execute the sub-plan and materialize all results into a list
    var nextBlock = prefetchExecutionPlan.start();
    List<Result> prefetched = new ArrayList<>();
    while (nextBlock.hasNext(ctx)) {
      prefetched.add(nextBlock.next(ctx));
    }
    nextBlock.close(ctx);
    prefetchExecutionPlan.close();

    // Store the prefetched list in the context for MatchFirstStep to consume
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

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    InternalExecutionPlan prefetchExecutionPlanCopy = null;

    if (prefetchExecutionPlan != null) {
      prefetchExecutionPlanCopy = prefetchExecutionPlan.copy(ctx);
    }

    return new MatchPrefetchStep(ctx, prefetchExecutionPlanCopy, alias, profilingEnabled);
  }
}
