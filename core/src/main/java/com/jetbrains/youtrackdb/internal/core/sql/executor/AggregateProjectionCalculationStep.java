package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Intermediate step that performs aggregation (GROUP BY + aggregate functions).
 *
 * <p>This is a <b>blocking</b> step: it must consume all upstream records before
 * producing any output, because aggregate functions (count, sum, max, etc.) need
 * to see all rows in each group before computing a result.
 *
 * <pre>
 *  SQL:   SELECT city, count(*), max(price) FROM Product GROUP BY city
 *
 *  Processing:
 *    1. Pull all records from upstream
 *    2. Group by the GROUP BY key (city)
 *    3. For each group, accumulate aggregate functions
 *    4. Finalize accumulators (getFinalValue)
 *    5. Emit one result per group
 *
 *  Without GROUP BY (e.g. SELECT count(*) FROM Product):
 *    All records go into a single group with key = [null]
 * </pre>
 *
 * <pre>
 *  Upstream records
 *    |
 *    v
 *  [Group by key] --&gt; Map&lt;key, ResultInternal&gt;
 *    |                   |
 *    |                   +-- non-aggregate props: set on first visit
 *    |                   +-- aggregate props: stored as AggregationContext (temp)
 *    v
 *  [Finalize] -- for each group, replace AggregationContext with scalar
 *    |
 *    v
 *  Output: one Result per group
 * </pre>
 *
 * <p>The {@code limit} parameter allows early termination when no ORDER BY is
 * present: once enough groups have been accumulated, the step can stop.
 *
 * @see SelectExecutionPlanner#handleProjections
 * @see AggregationContext
 */
public class AggregateProjectionCalculationStep extends ProjectionCalculationStep {

  /** GROUP BY clause (null for queries without GROUP BY). */
  private final SQLGroupBy groupBy;

  /** Query timeout in milliseconds (-1 = no timeout). */
  private final long timeoutMillis;

  /**
   * Maximum number of groups to produce (-1 = unlimited).
   * Set to SKIP + LIMIT when no ORDER BY invalidates the optimization.
   */
  private final long limit;

  public AggregateProjectionCalculationStep(
      SQLProjection projection,
      SQLGroupBy groupBy,
      long limit,
      CommandContext ctx,
      long timeoutMillis,
      boolean profilingEnabled) {
    super(projection, ctx, profilingEnabled);
    this.groupBy = groupBy;
    this.timeoutMillis = timeoutMillis;
    this.limit = limit;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var finalResults = executeAggregation(ctx);
    return ExecutionStream.resultIterator(finalResults.iterator());
  }

  /**
   * Consumes all upstream records, groups them, accumulates aggregate values,
   * finalizes each group's accumulators, and returns the result list.
   *
   * <pre>
   *  1. Pull all records from prev.start(ctx)
   *  2. For each record: aggregate() into the group map
   *  3. For each group result: finalize AggregationContext -> final value
   *  4. Return list of finalized group results
   * </pre>
   */
  private List<Result> executeAggregation(CommandContext ctx) {
    var timeoutBegin = System.currentTimeMillis();
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession().getDatabaseName(),
          "Cannot execute an aggregation or a GROUP BY without a previous result");
    }

    var prevStep = prev;
    var lastRs = prevStep.start(ctx);
    Map<List<?>, ResultInternal> aggregateResults = new LinkedHashMap<>();
    while (lastRs.hasNext(ctx)) {
      if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
        sendTimeout();
      }
      aggregate(lastRs.next(ctx), ctx, aggregateResults);
    }
    lastRs.close(ctx);
    List<Result> finalResults = new ArrayList<>(aggregateResults.values());
    aggregateResults.clear();
    for (var ele : finalResults) {
      var item = (ResultInternal) ele;
      if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
        sendTimeout();
      }
      for (var name : item.getTemporaryProperties()) {
        var prevVal = item.getTemporaryProperty(name);
        if (prevVal instanceof AggregationContext aggregationContext) {
          item.setTemporaryProperty(name, aggregationContext.getFinalValue());
        }
      }
    }
    return finalResults;
  }

  /**
   * Processes a single upstream record: computes the GROUP BY key, looks up or creates
   * the group's accumulator row, evaluates non-aggregate projections (first visit only),
   * and feeds the record into each aggregate function's accumulation context.
   */
  private void aggregate(
      Result next, CommandContext ctx, Map<List<?>, ResultInternal> aggregateResults) {
    var db = ctx.getDatabaseSession();
    List<Object> key = new ArrayList<>();
    if (groupBy != null) {
      for (var item : groupBy.getItems()) {
        var val = item.execute(next, ctx);
        key.add(val);
      }
    }
    var preAggr = aggregateResults.get(key);
    if (preAggr == null) {
      // Early termination: when a limit is set (SKIP+LIMIT with no ORDER BY),
      // stop creating new groups once we have enough. Uses > (not >=) because
      // the current record hasn't been added yet and we need exactly `limit` groups.
      if (limit > 0 && aggregateResults.size() > limit) {
        return;
      }
      preAggr = new ResultInternal(ctx.getDatabaseSession());

      for (var proj : this.projection.getItems()) {
        var alias = proj.getProjectionAlias().getStringValue();
        if (!proj.isAggregate(db)) {
          preAggr.setProperty(alias, proj.execute(next, ctx));
        }
      }
      aggregateResults.put(key, preAggr);
    }

    for (var proj : this.projection.getItems()) {
      var alias = proj.getProjectionAlias().getStringValue();
      if (proj.isAggregate(db)) {
        var aggrCtx = (AggregationContext) preAggr.getTemporaryProperty(alias);
        if (aggrCtx == null) {
          aggrCtx = proj.getAggregationContext(ctx);
          preAggr.setTemporaryProperty(alias, aggrCtx);
        }
        aggrCtx.apply(next, ctx);
      }
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ CALCULATE AGGREGATE PROJECTIONS";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    result +=
        "\n"
            + spaces
            + "      "
            + projection.toString()
            + (groupBy == null ? "" : (spaces + "\n  " + groupBy));
    return result;
  }

  /** Cacheable: projection and GROUP BY ASTs are deep-copied per execution via {@link #copy}. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new AggregateProjectionCalculationStep(
        projection.copy(),
        groupBy == null ? null : groupBy.copy(),
        limit,
        ctx,
        timeoutMillis,
        profilingEnabled);
  }
}
