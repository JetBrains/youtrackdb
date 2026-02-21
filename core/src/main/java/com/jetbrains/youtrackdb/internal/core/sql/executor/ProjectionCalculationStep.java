package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;

/**
 * Intermediate step that computes the SELECT projections for each record.
 *
 * <p>Takes each upstream result, sets it as the {@code $current} context variable,
 * and evaluates the projection expressions to produce a new result with the
 * requested output columns.
 *
 * <pre>
 *  SQL:  SELECT name, age * 2 AS doubleAge FROM Person
 *
 *  Input record:  { name: "Alice", age: 30, city: "NYC" }
 *  Output record: { name: "Alice", doubleAge: 60 }
 * </pre>
 *
 * <p>This step is used in multiple roles during the projection pipeline:
 * <ul>
 *   <li>Pre-aggregate projection (Phase 1)</li>
 *   <li>Post-aggregate / final projection (Phase 3)</li>
 *   <li>Post-ORDER-BY projection (stripping temporary ORDER BY aliases)</li>
 * </ul>
 *
 * <pre>
 *  Three-phase projection pipeline (when aggregates are present):
 *    Phase 1: ProjectionCalculationStep    -- pre-aggregate expressions
 *    Phase 2: AggregateProjectionCalcStep  -- aggregate functions (COUNT, SUM, ...)
 *    Phase 3: ProjectionCalculationStep    -- post-aggregate aliases and expressions
 *
 *  Simple projection (no aggregates):
 *    upstream --&gt; ProjectionCalculationStep --&gt; downstream
 * </pre>
 *
 * @see SelectExecutionPlanner#handleProjections
 */
public class ProjectionCalculationStep extends AbstractExecutionStep {

  /** The projection definition (list of expressions with aliases). */
  protected final SQLProjection projection;

  public ProjectionCalculationStep(
      SQLProjection projection, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.projection = projection;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("Cannot calculate projections without a previous source");
    }

    var parentRs = prev.start(ctx);
    return parentRs.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    // Temporarily set $current so projection expressions (e.g. $current.name) can reference this row.
    var oldCurrent = ctx.getVariable("$current");
    ctx.setVariable("$current", result);
    var newResult = calculateProjections(ctx, result);
    // Restore the previous $current to support correct behavior in nested contexts.
    ctx.setVariable("$current", oldCurrent);
    return newResult;
  }

  private Result calculateProjections(CommandContext ctx, Result next) {
    return this.projection.calculateSingle(ctx, next);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);

    var result = spaces + "+ CALCULATE PROJECTIONS";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    result += ("\n" + spaces + "  " + projection.toString());
    return result;
  }

  /** Cacheable: the projection definition is a structural AST node deep-copied per execution. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ProjectionCalculationStep(projection.copy(), ctx, profilingEnabled);
  }
}
