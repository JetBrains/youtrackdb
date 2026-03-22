package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-record LET step that handles a group of correlated LET subqueries sharing
 * the same inner FROM subquery. The shared inner subquery is executed once per
 * outer row, and each LET entry's outer query is evaluated against the
 * materialized results.
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
 * <p>The shared inner subquery ({@code SELECT expand(in('HAS_CREATOR')) ...}) is
 * executed once. Each LET entry's full query plan is then built normally, but
 * its {@link SubQueryStep} is replaced with a {@link ListSourceStep} that streams
 * from the materialized results.
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
  private final List<LetEntry> entries;
  /** Cached per-entry plan-cache flags — computed once on first use. */
  private boolean[] entryPlanCacheFlags;

  /**
   * @param sharedInnerQuery the common inner FROM subquery shared by all entries
   * @param entries          the LET items in this group (varName + full query)
   */
  public MaterializedLetGroupStep(
      SQLStatement sharedInnerQuery,
      List<LetEntry> entries,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.sharedInnerQuery = sharedInnerQuery;
    this.entries = entries;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot execute a local LET on a query without a target");
    }
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

    var materializedBase = executeAndMaterialize(sharedInnerQuery, subCtx);

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
   * building the full query plan and replacing its {@link SubQueryStep} with
   * a {@link ListSourceStep} sourced from the materialized list.
   */
  private void executeWithMaterialized(
      ResultInternal result, LetEntry entry,
      List<Result> materializedBase,
      BasicCommandContext subCtx,
      DatabaseSessionEmbedded session,
      boolean usePlanCache) {
    var outerPlan = usePlanCache
        ? entry.fullQuery.createExecutionPlan(subCtx, profilingEnabled)
        : entry.fullQuery.createExecutionPlanNoCache(subCtx, profilingEnabled);

    // Replace the SubQueryStep (which would re-execute the shared inner subquery)
    // with a ListSourceStep that streams from the already-materialized results.
    // Guard: only replace if the plan structure matches expectations.
    if (outerPlan instanceof SelectExecutionPlan selectPlan
        && !selectPlan.getSteps().isEmpty()
        && selectPlan.getSteps().getFirst() instanceof SubQueryStep) {
      var listSource = new ListSourceStep(
          materializedBase, subCtx, profilingEnabled);
      selectPlan.replaceFirstStep(listSource);
    }
    // If the plan structure doesn't match (e.g. no SubQueryStep as first step),
    // the full query executes normally without materialization — correct but slower.

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
        sharedInnerQuery.copy(), copiedEntries, ctx, profilingEnabled);
  }

  /**
   * A single LET variable + its full query AST within a materialization group.
   */
  public record LetEntry(SQLIdentifier varName, SQLStatement fullQuery) {
  }
}
