package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Filters upstream MATCH results by applying a **NOT** pattern — rows that match the
 * negative pattern are discarded.
 *
 * This step implements the `NOT { … }` syntax in MATCH queries. For each upstream
 * result row, it constructs a temporary execution plan consisting of:
 *
 * 1. A {@link ChainStep} that injects the current row as the starting point.
 * 2. The list of sub-steps (typically {@link MatchStep}s) that represent the NOT
 *    pattern's edges.
 *
 * If the temporary plan produces **any** result, the NOT pattern matched — meaning the
 * upstream row is discarded. If it produces no results, the row passes through.
 *
 * <pre>
 * NOT pattern evaluation per upstream row:
 *
 *   upstream row ──→ ChainStep(copy of row) ──→ MatchStep(NOT edges) ──→ any result?
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
  @Nullable
  private Result filterMap(Result result, CommandContext ctx) {
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
    var plan = createExecutionPlan(nextItem, ctx);
    var rs = plan.start();
    try {
      return rs.hasNext(ctx);
    } finally {
      rs.close(ctx);
    }
  }

  /**
   * Builds a temporary execution plan that starts with the current row (via
   * {@link ChainStep}) and chains the NOT-pattern sub-steps.
   */
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

  /**
   * Internal step that injects a single MATCH result row as the starting point
   * for the NOT-pattern sub-plan. It shallow-copies the row's properties and
   * metadata so that the sub-plan's traversal does not mutate the original row
   * structure (note: property values themselves are shared, not deep-copied).
   */
  private class ChainStep extends AbstractExecutionStep {

    private final Result nextItem;
    private final DatabaseSessionEmbedded db;

    public ChainStep(CommandContext ctx, Result nextItem, DatabaseSessionEmbedded db) {
      super(ctx, FilterNotMatchPatternStep.this.profilingEnabled);
      this.nextItem = nextItem;
      this.db = db;
    }

    /** Emits a single shallow-copied row as a singleton stream. */
    @Override
    public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
      return ExecutionStream.singleton(copy(nextItem));
    }

    /**
     * Shallow-copies properties and metadata from the source row into a new result.
     * Property names and metadata keys are copied, but the values themselves are
     * shared references (not deep-cloned). This is sufficient because the NOT-pattern
     * sub-plan only reads the values — it never mutates them in place.
     */
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
