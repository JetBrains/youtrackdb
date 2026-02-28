package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Intermediate <b>blocking</b> step that sorts all upstream records according to an
 * ORDER BY clause.
 *
 * <p>This step must consume the entire upstream before producing any output (it cannot
 * stream sorted results lazily). The sorted records are cached in-memory.
 *
 * <pre>
 *  SQL:   SELECT FROM Person ORDER BY lastName, firstName
 *
 *  Processing:
 *    1. Pull ALL records from upstream into cachedResult list
 *    2. Sort using orderBy.apply(comparator)
 *    3. Emit sorted records as an iterator-based stream
 * </pre>
 *
 * <h2>Memory optimization</h2>
 * When {@code maxResults} is set (derived from SKIP + LIMIT), the step periodically
 * sorts and truncates the cached list to keep at most {@code maxResults * 2} entries
 * in memory at a time. This trades extra sort passes for reduced peak heap usage.
 *
 * <p>The step also respects {@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP} and throws
 * a {@link CommandExecutionException} if the result set exceeds the configured limit.
 *
 * @see SelectExecutionPlanner#handleOrderBy
 * @see SelectExecutionPlanner#handleProjectionsBlock
 */
public class OrderByStep extends AbstractExecutionStep {

  /** The ORDER BY clause defining sort keys and directions. */
  private final SQLOrderBy orderBy;

  /** Query timeout in milliseconds (-1 = no timeout). */
  private final long timeoutMillis;

  /**
   * When non-null, the maximum number of results needed (SKIP + LIMIT).
   * Used to compact the sort buffer periodically, reducing memory usage.
   */
  private Integer maxResults;

  public OrderByStep(
      SQLOrderBy orderBy, CommandContext ctx, long timeoutMillis, boolean profilingEnabled) {
    this(orderBy, null, ctx, timeoutMillis, profilingEnabled);
  }

  /**
   * @param orderBy          the ORDER BY clause defining sort keys and directions
   * @param maxResults       max rows needed (SKIP+LIMIT); null for unlimited.
   *                         When set, enables periodic buffer compaction to reduce memory.
   * @param ctx              the query context
   * @param timeoutMillis    query timeout in milliseconds (-1 = no timeout)
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public OrderByStep(
      SQLOrderBy orderBy,
      Integer maxResults,
      CommandContext ctx,
      long timeoutMillis,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.orderBy = orderBy;
    this.maxResults = maxResults;
    if (this.maxResults != null && this.maxResults < 0) {
      this.maxResults = null;
    }
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    List<Result> results;

    if (prev != null) {
      results = init(prev, ctx);
    } else {
      results = Collections.emptyList();
    }

    return ExecutionStream.resultIterator(results.iterator());
  }

  /**
   * Pulls all records from the upstream step, sorts them, and returns the sorted list.
   *
   * <p>When {@code maxResults} is set (derived from SKIP + LIMIT), a memory optimization
   * is applied: once the buffer exceeds {@code 2 * maxResults}, it is sorted and truncated
   * to {@code maxResults} entries. This trades extra sort passes for reduced peak memory.
   *
   * <pre>
   *  Without maxResults:
   *    pull all -> sort once -> return
   *
   *  With maxResults = 100:
   *    pull records into buffer
   *    when buffer > 200: sort + truncate to 100
   *    ... continue pulling ...
   *    final sort + truncate -> return
   * </pre>
   *
   * @param p   the upstream step to pull from
   * @param ctx the command context
   * @return the sorted (and possibly truncated) list of results
   * @throws CommandExecutionException if the buffer exceeds
   *         {@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP}
   */
  private List<Result> init(ExecutionStepInternal p, CommandContext ctx) {
    var timeoutBegin = System.currentTimeMillis();
    List<Result> cachedResult = new ArrayList<>();
    final var maxElementsAllowed =
        GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    var sorted = true;
    var lastBatch = p.start(ctx);
    while (lastBatch.hasNext(ctx)) {
      if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
        sendTimeout();
      }

      var item = lastBatch.next(ctx);
      cachedResult.add(item);
      if (maxElementsAllowed >= 0 && maxElementsAllowed < cachedResult.size()) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Limit of allowed entities for in-heap ORDER BY in a single query exceeded ("
                + maxElementsAllowed
                + ") . You can set "
                + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
                + " to increase this limit");
      }
      sorted = false;
      // Memory optimization: when we know only maxResults rows are needed (SKIP+LIMIT),
      // periodically sort and truncate the buffer to maxResults once it exceeds
      // 2x maxResults. This trades extra sort passes for reduced peak heap usage.
      if (this.maxResults != null) {
        var compactThreshold = 2L * maxResults;
        if (compactThreshold < cachedResult.size()) {
          cachedResult.sort((a, b) -> orderBy.compare(a, b, ctx));
          cachedResult = new ArrayList<>(cachedResult.subList(0, maxResults));
          sorted = true;
        }
      }
    }
    lastBatch.close(ctx);
    // Post-loop compaction: sort and truncate if new unsorted records were added.
    if (!sorted && this.maxResults != null && maxResults < cachedResult.size()) {
      cachedResult.sort((a, b) -> orderBy.compare(a, b, ctx));
      cachedResult = new ArrayList<>(cachedResult.subList(0, maxResults));
      sorted = true;
    }
    if (!sorted) {
      cachedResult.sort((a, b) -> orderBy.compare(a, b, ctx));
    }
    return cachedResult;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ " + orderBy;
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    result += (maxResults != null ? "\n  (buffer size: " + maxResults + ")" : "");
    return result;
  }

  /** Cacheable: the ORDER BY clause is a structural AST node deep-copied per execution. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new OrderByStep(orderBy.copy(), maxResults, ctx, timeoutMillis, profilingEnabled);
  }
}
