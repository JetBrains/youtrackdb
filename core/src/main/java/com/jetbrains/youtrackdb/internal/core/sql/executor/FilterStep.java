package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprEvaluator;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprLowerer;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.UnsupportedAnalyzedNodeException;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExpireResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import javax.annotation.Nullable;

/**
 * Intermediate step that filters upstream records using a WHERE clause.
 *
 * <p>Dual-carry: the step holds both the original AST ({@code whereClause}) and, when the
 * predicate is within the analyzed-expression lowering subset, a pre-lowered IR tree
 * ({@code analyzed}). Evaluation branches on the IR first: when present, the IR path
 * ({@link AnalyzedExprEvaluator}) evaluates the predicate; otherwise, the AST fallback
 * ({@code whereClause.matchesFilters}) is used.
 *
 * <p>Lowering happens once, in the constructor, so every {@code new FilterStep(...)} site
 * inherits dual-carry automatically without planner edits. Predicates outside the lowering
 * subset (e.g. IN, BETWEEN, $-variables, multi-segment paths) silently fall back to the AST
 * path — correctness is preserved, only the performance benefit of the IR path is lost.
 *
 * <pre>
 *  Pipeline:
 *    ... upstream ... -&gt; FilterStep(WHERE age &gt; 30) -&gt; ... downstream ...
 *
 *  For each record:
 *    if analyzed != null:
 *      evaluate via AnalyzedExprEvaluator -&gt; Boolean.TRUE.equals(result) -&gt; pass/discard
 *    else:
 *      if whereClause.matchesFilters(record) == true -&gt; pass through
 *      else                                          -&gt; discard
 * </pre>
 *
 * <p>When a timeout is configured, the step wraps the filtered stream with an
 * {@link ExpireResultSet} that checks elapsed time between records and sends
 * a timeout signal when exceeded.
 *
 * @see SelectExecutionPlanner#handleWhere
 */
public class FilterStep extends AbstractExecutionStep {
  /** Query timeout in milliseconds; values &lt;= 0 mean no timeout is applied. */
  private final long timeoutMillis;

  /** The WHERE condition to evaluate against each record (AST carrier). */
  private final SQLWhereClause whereClause;

  /**
   * Pre-lowered IR tree for the WHERE predicate, or {@code null} when the predicate is outside
   * the analyzed-expression lowering subset. When non-null, {@link #filterMap} evaluates via the
   * IR path; when null, it falls back to the AST's {@code matchesFilters}.
   */
  @Nullable private final AnalyzedExpr analyzed;

  public FilterStep(
      SQLWhereClause whereClause, CommandContext ctx, long timeoutMillis,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.whereClause = whereClause;
    this.timeoutMillis = timeoutMillis;
    this.analyzed = tryLower(whereClause);
  }

  /**
   * Private copy-constructor: accepts an already-lowered IR tree directly (no re-lowering).
   * The IR is deeply immutable, so sharing it across copies is safe and avoids redundant work.
   */
  private FilterStep(
      SQLWhereClause whereClause, @Nullable AnalyzedExpr analyzed, CommandContext ctx,
      long timeoutMillis, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.whereClause = whereClause;
    this.timeoutMillis = timeoutMillis;
    this.analyzed = analyzed;
  }

  /**
   * Attempts to lower the WHERE clause's base expression to the analyzed-expression IR.
   * Returns {@code null} when the predicate is outside the lowering subset or when the
   * WHERE clause has no base expression (empty WHERE).
   */
  @Nullable private static AnalyzedExpr tryLower(SQLWhereClause whereClause) {
    SQLBooleanExpression baseExpr = whereClause.getBaseExpression();
    if (baseExpr == null) {
      return null;
    }
    try {
      return AnalyzedExprLowerer.lowerBoolean(baseExpr);
    } catch (UnsupportedAnalyzedNodeException e) {
      return null;
    }
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }

    // Register the WHERE expression so that upstream LET steps can check whether
    // a variable is referenced by the WHERE clause (via ctx.getParentWhereExpressions()).
    // This enables GlobalLetQueryStep to decide if the subquery result must be
    // materialized into a List for repeated iteration.
    ctx.registerBooleanExpression(whereClause.getBaseExpression());
    var resultSet = prev.start(ctx);
    resultSet = resultSet.filter(this::filterMap);
    if (timeoutMillis > 0) {
      resultSet = new ExpireResultSet(resultSet, timeoutMillis, this::sendTimeout);
    }
    return resultSet;
  }

  /**
   * Evaluates the predicate for a single record. Branches on the IR first (N1 mitigation):
   * when {@code analyzed} is non-null, the IR path is used; otherwise, the AST fallback.
   *
   * <p>The boolean extraction contract ({@code Boolean.TRUE.equals(...)}) matches the AST's
   * own contract in {@code SQLBinaryCondition.evaluate}: null and non-Boolean evaluator results
   * are treated as {@code false}, preserving the two-valued filter semantics.
   */
  @Nullable private Result filterMap(Result result, CommandContext ctx) {
    if (analyzed != null) {
      Object evaluatorResult = AnalyzedExprEvaluator.evaluate(analyzed, result, ctx);
      if (Boolean.TRUE.equals(evaluatorResult)) {
        return result;
      }
      return null;
    }
    if (whereClause.matchesFilters(result, ctx)) {
      return result;
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    result.append(ExecutionStepInternal.getIndent(depth, indent)).append("+ FILTER ITEMS WHERE ");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    result.append("\n");
    result.append(ExecutionStepInternal.getIndent(depth, indent));
    result.append("  ");
    result.append(whereClause.toString());
    return result.toString();
  }

  /** Cacheable: the WHERE clause is a structural AST node that is deep-copied per execution. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    // Share the deeply-immutable IR; copy only the mutable AST carrier.
    return new FilterStep(
        this.whereClause.copy(), this.analyzed, ctx, timeoutMillis, profilingEnabled);
  }

  /**
   * Returns the pre-lowered IR tree, or {@code null} when the predicate is outside the
   * analyzed-expression lowering subset. Package-private: used by tests to verify the N1
   * mitigation (IR-first vs AST-fallback branch selection).
   */
  @Nullable AnalyzedExpr getAnalyzed() {
    return analyzed;
  }
}
