package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.TraversalCache;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Per-record LET step that handles a group of correlated LET subqueries sharing
 * the same inner FROM subquery. The shared inner subquery is executed once per
 * outer row, and each LET entry's outer query is evaluated against the
 * materialized results.
 *
 * <p>When entries share common WHERE conditions (e.g., {@code @class = 'Post'}),
 * a wrapper query pushes the common filter into the materialization traversal
 * via the planner's predicate push-down, so non-matching records are skipped
 * with zero I/O. Per-entry conditions are preserved intact in each entry's
 * FilterStep by setting {@link CommandContext#setSkipExpandPushDown(boolean)}
 * during plan creation.
 *
 * <p>For IC10's pattern:
 * <pre>
 *  LET $posScore = (SELECT count(*) FROM (
 *        SELECT expand(in('HAS_CREATOR')) FROM Person
 *        WHERE @rid = $parent.$current.fofVertex
 *      ) WHERE @class = 'Post' AND &lt;tag condition&gt;),
 *      $negScore = (SELECT count(*) FROM (
 *        SELECT expand(in('HAS_CREATOR')) FROM Person
 *        WHERE @rid = $parent.$current.fofVertex    -- same inner subquery
 *      ) WHERE @class = 'Post' AND NOT &lt;tag condition&gt;)
 * </pre>
 *
 * <p>The common filter {@code @class = 'Post'} is pushed into the
 * materialization query's ExpandStep (collection ID check — zero I/O for
 * Comments). The per-entry filters ({@code hasTag} / {@code NOT hasTag}) are
 * evaluated from the materialized list via each entry's preserved FilterStep.
 *
 * <p>If the materialized results exceed
 * {@link GlobalConfiguration#QUERY_LET_MATERIALIZATION_MAX_SIZE}, falls back to
 * independent execution (same behavior as separate {@link LetQueryStep}s).
 *
 * @see ListSourceStep
 * @see LetQueryStep
 */
public class MaterializedLetGroupStep extends AbstractExecutionStep {

  private final SQLStatement sharedInnerQuery;
  private final @Nullable SQLWhereClause commonFilter;
  private final List<LetEntry> entries;
  /** Cached per-entry plan-cache flags — computed once on first use. */
  private boolean[] entryPlanCacheFlags;

  /**
   * @param sharedInnerQuery the common inner FROM subquery shared by all entries
   * @param commonFilter     the common WHERE conditions shared by all entries,
   *                         or {@code null} if there are no common conditions
   * @param entries          the LET items in this group (varName + full query)
   */
  public MaterializedLetGroupStep(
      SQLStatement sharedInnerQuery,
      @Nullable SQLWhereClause commonFilter,
      List<LetEntry> entries,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.sharedInnerQuery = sharedInnerQuery;
    this.commonFilter = commonFilter;
    this.entries = entries;
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

  private ResultInternal calculate(ResultInternal result, CommandContext ctx) {
    var session = ctx.getDatabaseSession();

    var currentRowCtx = new BasicCommandContext();
    currentRowCtx.setSystemVariable(CommandContext.VAR_CURRENT, result);
    currentRowCtx.setParentWithoutOverridingChild(ctx);

    var subCtx = new BasicCommandContext();
    subCtx.setDatabaseSession(session);
    subCtx.setParentWithoutOverridingChild(currentRowCtx);

    // Build materialization query with common filter. Push-down is NOT
    // skipped here — we want the planner to push the common filter into
    // ExpandStep (e.g., @class='Post' becomes a collection ID check,
    // skipping non-matching records with zero I/O).
    var materializationQuery = buildMaterializationQuery(
        sharedInnerQuery, commonFilter);
    var materializedBase = executeAndMaterialize(materializationQuery, subCtx);

    int maxSize = GlobalConfiguration.QUERY_LET_MATERIALIZATION_MAX_SIZE
        .getValueAsInteger();
    if (materializedBase.size() > maxSize) {
      executeFallback(result, entries, subCtx, session);
      return result;
    }

    if (entryPlanCacheFlags == null) {
      entryPlanCacheFlags = new boolean[entries.size()];
      for (int i = 0; i < entries.size(); i++) {
        entryPlanCacheFlags[i] = usePlanCache(entries.get(i).fullQuery);
      }
    }

    for (int i = 0; i < entries.size(); i++) {
      executeWithMaterialized(
          result, entries.get(i), materializedBase, subCtx, session,
          entryPlanCacheFlags[i]);
    }
    return result;
  }

  /**
   * Wraps the inner query with the common filter so the planner can push it
   * into the ExpandStep. Returns the bare inner query when there is no common
   * filter.
   */
  private static SQLStatement buildMaterializationQuery(
      SQLStatement innerQuery, @Nullable SQLWhereClause commonFilter) {
    if (commonFilter == null || commonFilter.refersToParent()) {
      return innerQuery;
    }

    // Build: SELECT * FROM (<innerQuery>) WHERE <commonFilter>
    var wrapper = new SQLSelectStatement(-1);

    var fromItem = new SQLFromItem(-1);
    fromItem.setStatement(innerQuery);
    var fromClause = new SQLFromClause(-1);
    fromClause.setItem(fromItem);
    wrapper.setTarget(fromClause);

    wrapper.setWhereClause(commonFilter);
    return wrapper;
  }

  private List<Result> executeAndMaterialize(
      SQLStatement query, BasicCommandContext subCtx) {
    // Same caching strategy as LetQueryStep: use cached plans unless the query
    // contains positional parameters (?), where ordinal-to-value mapping may differ.
    var plan = usePlanCache(query)
        ? query.createExecutionPlan(subCtx, profilingEnabled)
        : query.createExecutionPlanNoCache(subCtx, profilingEnabled);
    return toList(new LocalResultSet(subCtx.getDatabaseSession(), plan));
  }

  /**
   * Executes a single LET entry against the materialized base results by
   * building the full query plan with expand push-down disabled and replacing
   * its {@link SubQueryStep} with a {@link ListSourceStep} sourced from the
   * materialized list.
   *
   * <p>The {@link CommandContext#setSkipExpandPushDown(boolean)} flag is set
   * before plan creation so the planner preserves the outer FilterStep (which
   * contains per-entry conditions). The flag is also included in the plan
   * cache key, so plans with and without push-down are cached separately.
   */
  private void executeWithMaterialized(
      ResultInternal result, LetEntry entry,
      List<Result> materializedBase,
      BasicCommandContext subCtx,
      DatabaseSessionEmbedded session,
      boolean usePlanCache) {
    // Set the flag BEFORE plan creation so the planner skips push-down
    // and the cache key includes the flag.
    subCtx.setSkipExpandPushDown(true);
    var outerPlan = usePlanCache
        ? entry.fullQuery.createExecutionPlan(subCtx, profilingEnabled)
        : entry.fullQuery.createExecutionPlanNoCache(subCtx, profilingEnabled);
    subCtx.setSkipExpandPushDown(false);

    // Replace the SubQueryStep (which would re-execute the shared inner
    // subquery) with a ListSourceStep that streams from the already-
    // materialized results. With skipExpandPushDown, the FilterStep is
    // preserved intact — no filter is lost.
    if (outerPlan instanceof SelectExecutionPlan selectPlan
        && !selectPlan.getSteps().isEmpty()
        && selectPlan.getSteps().getFirst() instanceof SubQueryStep) {
      var listSource = new ListSourceStep(
          materializedBase, subCtx, profilingEnabled);
      selectPlan.replaceFirstStep(listSource);
    }

    result.setMetadata(entry.varName.getStringValue(),
        toList(new LocalResultSet(session, outerPlan)));
  }

  /**
   * Fallback: executes each LET entry independently (same as {@link LetQueryStep}).
   * Used when the materialized base exceeds the configured size limit.
   */
  private void executeFallback(
      ResultInternal result, List<LetEntry> entries,
      BasicCommandContext subCtx,
      DatabaseSessionEmbedded session) {
    for (var entry : entries) {
      var cache = usePlanCache(entry.fullQuery);
      var plan = cache
          ? entry.fullQuery.createExecutionPlan(subCtx, profilingEnabled)
          : entry.fullQuery.createExecutionPlanNoCache(subCtx, profilingEnabled);
      result.setMetadata(entry.varName.getStringValue(),
          toList(new LocalResultSet(session, plan)));
    }
  }

  /**
   * Returns {@code true} if the query's execution plan can be cached.
   * Queries with positional parameters ({@code ?}) cannot be cached because
   * the ordinal-to-value mapping may differ between invocations.
   * Same strategy as {@link LetQueryStep}.
   */
  private static boolean usePlanCache(SQLStatement query) {
    return !query.toString().contains("?");
  }

  private List<Result> toList(LocalResultSet rs) {
    try (rs) {
      List<Result> list = new ArrayList<>();
      while (rs.hasNext()) {
        list.add(rs.next());
      }
      return list;
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var sb = new StringBuilder();
    sb.append(spaces).append("+ MATERIALIZED LET GROUP (shared base)\n");
    sb.append(spaces).append("  shared: (").append(sharedInnerQuery).append(")\n");
    if (commonFilter != null) {
      sb.append(spaces).append("  common filter: ").append(commonFilter).append("\n");
    }
    for (var entry : entries) {
      sb.append(spaces).append("  ").append(entry.varName)
          .append(" = (").append(entry.fullQuery).append(")\n");
    }
    return sb.toString();
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var copiedEntries = new ArrayList<LetEntry>();
    for (var entry : entries) {
      copiedEntries.add(new LetEntry(entry.varName.copy(), entry.fullQuery.copy()));
    }
    return new MaterializedLetGroupStep(
        sharedInnerQuery.copy(),
        commonFilter != null ? commonFilter.copy() : null,
        copiedEntries, ctx, profilingEnabled);
  }

  /**
   * A single LET variable + its full query AST within a materialization group.
   */
  public record LetEntry(SQLIdentifier varName, SQLStatement fullQuery) {
  }
}
