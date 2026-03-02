package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Pass-through step for `RETURN $paths` — returns the full MATCH result rows as-is.
 * <p>
 * When the user writes `RETURN $paths`, each result row already contains all the
 * matched aliases (both user-defined and auto-generated) as properties, which together
 * describe the full matched path. No transformation is needed, so this step simply
 * forwards the upstream stream unchanged.
 * <p>
 * ### Output format
 * <p>
 * Each output row is a map-like structure containing every alias in the pattern:
 * <pre>
 *   { p: Person#12:0, $YOUTRACKDB_DEFAULT_ALIAS_0: Knows#15:3, f: Person#13:1, city: City#20:0 }
 * </pre>
 *
 * This includes auto-generated aliases (prefixed with
 * {@link MatchExecutionPlanner#DEFAULT_ALIAS_PREFIX}) for intermediate nodes. At this
 * point in the pipeline, the {@code $matched} context variable also holds the same
 * row with both user-defined and auto-generated aliases — the variable is set by
 * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ResultSetEdgeTraverser#next}
 * after each edge traversal. Compare with {@link ReturnMatchPatternsStep} which strips
 * auto-generated aliases, and {@link ReturnMatchElementsStep} which unrolls each
 * user-defined alias into a separate row.
 * <p>
 * The step exists as a distinct type so that the planner's return-mode branching logic
 * ({@code MatchExecutionPlanner#addReturnStep()}) has a concrete step to chain.
 */
public class ReturnMatchPathsStep extends AbstractExecutionStep {

  public ReturnMatchPathsStep(CommandContext context, boolean profilingEnabled) {
    super(context, profilingEnabled);
  }

  /** Simply forwards the upstream stream unchanged. */
  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert MatchAssertions.checkNotNull(prev, "previous step");
    return prev.start(ctx);
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    assert depth >= 0 : "depth must be non-negative";
    assert indent >= 0 : "indent must be non-negative";
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ RETURN $paths";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ReturnMatchPathsStep(ctx, profilingEnabled);
  }
}
