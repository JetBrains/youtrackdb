package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Filters upstream MATCH results by applying a **NOT** pattern — rows that match the
 * negative pattern are discarded.
 * <p>
 * This step implements the `NOT { … }` syntax in MATCH queries. For each upstream
 * result row, it constructs a temporary execution plan consisting of:
 * <p>
 * 1. A shallow copy of the row published as $matched and injected as the starting point.
 * 2. The list of sub-steps (typically {@link MatchStep}s) that represent the NOT
 *    pattern's edges.
 * <p>
 * If the temporary plan produces **any** result, the NOT pattern matched — meaning the
 * upstream row is discarded. If it produces no results, the row passes through.
 *
 * <pre>
 * NOT pattern evaluation per upstream row:
 *
 *   upstream row ──→ copied row ──→ MatchStep(NOT edges) ──→ any result?
 *                                                                          │
 *                                                          ┌───────────────┴───────────────┐
 *                                                          │                               │
 *                                                         YES                             NO
 *                                                          │                               │
 *                                                    discard row                      keep row
 * </pre>
 *
 * This is the **inverse** of normal MATCH: results that match are removed instead of
 * kept.
 *
 * @see MatchExecutionPlanner
 */
public class FilterNotMatchPatternStep extends AbstractExecutionStep {

  /** The traversal sub-steps representing the NOT pattern's edges. */
  private final List<AbstractExecutionStep> subSteps;

  public FilterNotMatchPatternStep(
      List<AbstractExecutionStep> steps, CommandContext ctx, boolean enableProfiling) {
    super(ctx, enableProfiling);
    assert MatchAssertions.checkNotNull(steps, "sub-steps list");
    this.subSteps = steps;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }
    var resultSet = prev.start(ctx);
    // Keep only rows that do NOT match the negative pattern
    return resultSet.filter(this::filterMap);
  }

  /**
   * Returns the result if it does NOT match the pattern, or `null` (to drop it) if it
   * does. This inverts the normal filter logic.
   */
  @Nullable private Result filterMap(Result result, CommandContext ctx) {
    if (!matchesPattern(result, ctx)) {
      return result;
    }
    return null;
  }

  /**
   * Tests whether the given row matches the NOT pattern by building and executing a
   * temporary plan. Returns `true` if at least one result is produced (= pattern matched).
   */
  private boolean matchesPattern(Result nextItem, CommandContext ctx) {
    return DetachedMatchPatternProbe.matches(nextItem, subSteps, ctx, profilingEnabled);
  }

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    //noinspection unchecked,rawtypes
    return (List) subSteps;
  }

  @Override
  public boolean canBeCached() {
    return subSteps.stream().allMatch(ExecutionStepInternal::canBeCached);
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

}
