package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractUnrollStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Execution step that repeatedly executes a body plan while a condition holds.
 *
 * <p><b>Note: this class is currently not instantiated by any production code path.</b>
 * The {@link MatchExecutionPlanner} handles WHILE-based recursive traversal inline via
 * {@link MatchEdgeTraverser#executeTraversal}, which performs depth expansion directly
 * within the traverser. This step exists as an alternative, plan-level wrapper for
 * WHILE semantics and may be wired in by future planner enhancements or used by
 * external callers that need to compose WHILE traversals as discrete plan steps.
 *
 * <p>This step supports the `WHILE` clause in MATCH patterns, which enables iterative /
 * recursive graph traversal. It extends {@link AbstractUnrollStep}, which means:
 * for each upstream result, the {@link #unroll} method is called, producing a
 * collection of results. Those results replace the single upstream record in the
 * output stream (one-to-many mapping). This is how a single starting point can
 * fan out into multiple records at various depths.
 *
 * Inside {@link #unroll}, the body plan is reset and executed. The actual WHILE-loop
 * depth control and condition checking happens inside
 * {@link MatchEdgeTraverser#executeTraversal} — this step serves as a higher-level
 * wrapper that integrates the iterative traversal into the execution plan pipeline.
 *
 * The `body` plan is constructed by the planner (typically a sub-plan containing
 * {@link MatchFirstStep} and one or more {@link MatchStep}s that implement the
 * recursive edge traversal). The `condition` is the parsed `WHILE` clause; it is
 * evaluated internally by the body plan's traverser, not by this step directly.
 *
 * <pre>
 * For each upstream row:
 *
 *   upstream row ──→ unroll() ──→ body.reset()
 *                                    │
 *                                    ▼
 *                                 body.start() ──→ collect all results
 *                                    │               (fan-out at multiple depths)
 *                                    ▼
 *                                 [result1, result2, …, resultN]
 *                                    │
 *                                    ▼
 *                                 replace upstream row with N downstream rows
 * </pre>
 *
 * @see AbstractUnrollStep
 * @see MatchEdgeTraverser#executeTraversal
 */
public class WhileMatchStep extends AbstractUnrollStep {

  /** The body plan to execute on each iteration. */
  private final InternalExecutionPlan body;

  /** The WHILE condition (evaluated by the body plan internally). */
  private final SQLWhereClause condition;

  /**
   * @param ctx              the command context
   * @param condition        the WHILE condition clause
   * @param body             the execution plan to run iteratively
   * @param profilingEnabled whether to collect execution statistics
   */
  public WhileMatchStep(
      CommandContext ctx,
      SQLWhereClause condition,
      InternalExecutionPlan body,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.body = body;
    this.condition = condition;
  }

  /**
   * Resets the body plan and executes it, collecting all results produced by the
   * iterative traversal.
   */
  @Override
  protected Collection<Result> unroll(Result res, CommandContext iContext) {
    body.reset(iContext);
    List<Result> result = new ArrayList<>();
    var block = body.start();
    while (block.hasNext(iContext)) {
      result.add(block.next(iContext));
    }
    block.close(iContext);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var indentStep = ExecutionStepInternal.getIndent(1, indent);
    var spaces = ExecutionStepInternal.getIndent(depth, indent);

    var result =
        spaces
            + "+ WHILE\n"
            + spaces
            + indentStep
            + condition.toString()
            + "\n"
            + spaces
            + "  DO\n"
            + body.prettyPrint(depth + 1, indent)
            + "\n"
            + spaces
            + "  END\n";

    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new WhileMatchStep(ctx, condition.copy(), body.copy(ctx), profilingEnabled);
  }
}
