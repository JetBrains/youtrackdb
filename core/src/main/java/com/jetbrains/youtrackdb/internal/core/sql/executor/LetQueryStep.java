package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.TraversalCache;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-record LET step that executes a subquery for each row flowing through the
 * pipeline and attaches the result list as metadata on the record.
 *
 * <pre>
 *  SQL:  SELECT *, $orders FROM Customer
 *        LET $orders = (SELECT FROM Order WHERE customer = $parent.$current)
 *
 *  For each Customer record:
 *    1. Execute the subquery with $parent.$current = current record
 *    2. Collect results into a List
 *    3. Store as record.metadata("orders")
 * </pre>
 *
 * <p>A new child context is created per execution to avoid leaking variables
 * between records. Queries with positional parameters ({@code ?}) bypass plan
 * caching to avoid ordinal conflicts.
 *
 * @see SelectExecutionPlanner#handleLet
 */
public class LetQueryStep extends AbstractExecutionStep {

  /** The variable name to store per-record query results under. */
  private final SQLIdentifier varName;

  /** The subquery AST to execute for each record. */
  private final SQLStatement query;

  /**
   * A preview execution plan built lazily on first {@link #prettyPrint} call,
   * used only for EXPLAIN display. The actual per-record execution builds a
   * fresh plan in {@link #calculate} with the correct parent context
   * ($parent.$current). Lazy initialization avoids the cost of planning the
   * subquery during normal (non-EXPLAIN) execution.
   */
  private InternalExecutionPlan previewPlan;

  public LetQueryStep(
      SQLIdentifier varName, SQLStatement query, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.varName = varName;
    this.query = query;
  }

  private LetQueryStep(
      SQLIdentifier varName, SQLStatement query,
      InternalExecutionPlan previewPlan, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.varName = varName;
    this.query = query;
    this.previewPlan = previewPlan;
  }

  /**
   * Returns the preview execution plan, building it lazily on first access.
   * The plan is never executed — it exists solely so that {@link #prettyPrint}
   * can display the optimizer's decisions (class filters, index pre-filters,
   * etc.) for the subquery.
   */
  private InternalExecutionPlan getPreviewPlan() {
    if (previewPlan == null) {
      var previewCtx = new BasicCommandContext();
      previewCtx.setDatabaseSession(ctx.getDatabaseSession());
      previewCtx.setParentWithoutOverridingChild(ctx);
      // Positional parameters (?) prevent plan caching: the cached plan may
      // bind a different ordinal than the one needed in this preview context.
      if (query.containsPositionalParameters()) {
        previewPlan = query.createExecutionPlanNoCache(previewCtx, profilingEnabled);
      } else {
        previewPlan = query.createExecutionPlan(previewCtx, profilingEnabled);
      }
    }
    return previewPlan;
  }

  private ResultInternal calculate(ResultInternal result, CommandContext ctx) {
    var session = ctx.getDatabaseSession();

    // Create an intermediate context that holds $current = result.
    // This sits between the subquery context (subCtx) and the outer context (ctx),
    // so that $parent.$current in the subquery resolves to the current LET row
    // without corrupting ctx's own $current (which may be used by a lazy upstream
    // MATCH pipeline still producing results).
    //
    // IMPORTANT: setSystemVariable must be called BEFORE setParentWithoutOverridingChild.
    // BasicCommandContext.setSystemVariable delegates to the parent when the parent
    // already owns the variable, so setting the parent first would cause VAR_CURRENT
    // to be written to ctx (the outer context) instead of stored locally.
    var currentRowCtx = new BasicCommandContext();
    currentRowCtx.setSystemVariable(CommandContext.VAR_CURRENT, result);
    currentRowCtx.setParentWithoutOverridingChild(ctx);

    var subCtx = new BasicCommandContext();
    subCtx.setDatabaseSession(session);
    subCtx.setParentWithoutOverridingChild(currentRowCtx);

    InternalExecutionPlan subExecutionPlan;
    if (query.containsPositionalParameters()) {
      // Positional parameters prevent plan caching: the cached plan may
      // bind a different ordinal than the one needed in this context.
      subExecutionPlan = query.createExecutionPlanNoCache(subCtx, profilingEnabled);
    } else {
      subExecutionPlan = query.createExecutionPlan(subCtx, profilingEnabled);
    }
    result.setMetadata(varName.getStringValue(),
        toList(new LocalResultSet(session, subExecutionPlan)));
    return result;
  }

  private List<Result> toList(LocalResultSet oLocalResultSet) {
    List<Result> result = new ArrayList<>();
    while (oLocalResultSet.hasNext()) {
      result.add(oLocalResultSet.next());
    }
    oLocalResultSet.close();
    return result;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot execute a local LET on a query without a target");
    }
    TraversalCache.installIfNeeded(ctx);
    return prev.start(ctx).map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    return calculate((ResultInternal) result, ctx);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var cacheTag = isCacheEnabled() ? " [traversal-cache=enabled]" : " [traversal-cache=disabled]";
    return spaces
        + "+ LET (for each record)"
        + cacheTag
        + "\n"
        + spaces
        + "  "
        + varName
        + " = \n"
        + box(spaces + "    ", getPreviewPlan().prettyPrint(0, indent));
  }

  /**
   * Returns {@code true} when the traversal-result cache is enabled in the database configuration.
   * Used only for EXPLAIN display — the actual cache is created at execution time in {@link
   * #internalStart} via {@link TraversalCache#installIfNeeded}.
   */
  private boolean isCacheEnabled() {
    if (ctx == null) {
      return false;
    }
    var db = ctx.getDatabaseSession();
    if (db == null) {
      return false;
    }
    return db.getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_TRAVERSAL_CACHE_ENABLED);
  }

  @Override
  public List<ExecutionPlan> getSubExecutionPlans() {
    return Collections.singletonList(getPreviewPlan());
  }

  private String box(String spaces, String s) {
    var rows = s.split("\n", -1);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+-------------------------\n");
    for (var row : rows) {
      result.append(spaces);
      result.append("| ");
      result.append(row);
      result.append("\n");
    }
    result.append(spaces);
    result.append("+-------------------------");
    return result.toString();
  }

  /** Cacheable: subquery AST is deep-copied per execution via {@link #copy}. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    SQLIdentifier varNameCopy = null;
    SQLStatement queryCopy = null;
    InternalExecutionPlan previewPlanCopy = null;

    if (varName != null) {
      varNameCopy = varName.copy();
    }
    if (query != null) {
      queryCopy = query.copy();
    }
    if (previewPlan != null) {
      previewPlanCopy = previewPlan.copy(ctx);
    }

    return new LetQueryStep(varNameCopy, queryCopy, previewPlanCopy, ctx, profilingEnabled);
  }
}
