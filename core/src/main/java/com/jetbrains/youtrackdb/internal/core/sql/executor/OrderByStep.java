package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import javax.annotation.Nullable;

/**
 * Intermediate <b>blocking</b> step that sorts all upstream records according to an
 * ORDER BY clause.
 *
 * <p>This step must consume the entire upstream before producing any output (it cannot
 * stream sorted results lazily). The sorted records are cached in-memory.
 *
 * <h2>Bounded path (ORDER BY + LIMIT)</h2>
 * When {@code maxResults} is set (derived from SKIP + LIMIT), a bounded min-heap
 * (priority queue) of size {@code maxResults} is used. Each incoming row is compared
 * against the heap's worst element (O(1) peek). Rows that rank higher replace the
 * worst element (O(log N) re-heapify); rows that rank lower are discarded immediately.
 * After all upstream rows are consumed, the heap is drained and sorted to produce the
 * final ordered output.
 *
 * <p>This reduces memory from O(|all results|) to O(N) and time from
 * O(|results| &times; log(|results|)) to O(|results| &times; log(N)).
 *
 * <h2>Parameterized bounds</h2>
 * The bound may come from a parameterized {@code SKIP}/{@code LIMIT} (e.g. {@code LIMIT :n}).
 * Because this step belongs to a cacheable plan, such a bound is resolved on every execution
 * through {@link SQLLimit#getValue(CommandContext)} rather than baked in at planning time.
 * Baking it in made a second execution of the same statement text with a larger parameter return
 * the row count of the first execution.
 *
 * <h2>Unbounded path (ORDER BY without LIMIT)</h2>
 * All upstream rows are collected into a list and sorted once. The step respects
 * {@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP} and throws a
 * {@link CommandExecutionException} if the result set exceeds the configured limit.
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
   * When non-null, a plan-time constant maximum number of results (SKIP + LIMIT).
   * Used only when no {@code SKIP}/{@code LIMIT} clause was handed to this step;
   * otherwise the bound is resolved per execution from those clauses.
   */
  @Nullable private final Integer fixedMaxResults;

  /**
   * The SKIP clause contributing to the bound, or {@code null} when there is none.
   * Held as an AST node, not a number, so a parameterized SKIP resolves per execution.
   */
  @Nullable private final SQLSkip skipClause;

  /**
   * The LIMIT clause defining the bound, or {@code null} when the step is unbounded or when a
   * plan-time constant was supplied instead. Held as an AST node so a parameterized LIMIT
   * resolves per execution.
   */
  @Nullable private final SQLLimit limitClause;

  /**
   * When non-null, identifies the first ORDER BY item by position. The input stream is known
   * to be sorted by that item. The bounded heap can stop reading when the first key sorts
   * strictly later than the worst retained row's first key.
   *
   * <p>Set by the MATCH planner when IndexOrderedEdgeStep produces results
   * sorted by the primary ORDER BY field, enabling early termination for
   * multi-field ORDER BY queries.
   */
  @Nullable private final SQLOrderByItem primaryKeySortedInput;

  /**
   * When true, the upstream may be an {@code IndexOrderedEdgeStep} that
   * signals at runtime whether its output is already sorted. If the
   * runtime context variable {@code indexOrderedPreSorted} is {@code true},
   * this step becomes a pass-through (streaming, no sort, no buffering).
   * Otherwise it falls back to normal bounded-heap or unbounded sort.
   */
  private final boolean indexOrderedUpstream;

  /**
   * @param orderBy          the ORDER BY clause defining sort keys and directions
   * @param maxResults       max rows needed (SKIP+LIMIT); null for unlimited.
   *                         When set, enables the bounded min-heap path.
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
    this(orderBy, maxResults, null, false, ctx, timeoutMillis, profilingEnabled);
  }

  /**
   * Preferred constructor for a planner: hands over the SKIP and LIMIT clauses instead of a
   * resolved number, so a parameterized bound is read on every execution of a cached plan.
   *
   * @param skipClause  the SKIP clause, or {@code null} when there is none
   * @param limitClause the LIMIT clause, or {@code null} when the step must stay unbounded
   *                    (for instance under EXPAND or UNWIND, which invalidate the bound)
   */
  public OrderByStep(
      SQLOrderBy orderBy,
      @Nullable SQLSkip skipClause,
      @Nullable SQLLimit limitClause,
      @Nullable SQLOrderByItem primaryKeySortedInput,
      boolean indexOrderedUpstream,
      CommandContext ctx,
      long timeoutMillis,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.orderBy = orderBy;
    this.fixedMaxResults = null;
    this.skipClause = skipClause;
    this.limitClause = limitClause;
    assert primaryKeySortedInput == null
        || primaryKeySortedInput.equals(orderBy.getItems().getFirst());
    this.primaryKeySortedInput = primaryKeySortedInput;
    this.indexOrderedUpstream = indexOrderedUpstream;
    this.timeoutMillis = timeoutMillis;
  }

  /**
   * @param primaryKeySortedInput when non-null, enables early termination in
   *        the bounded heap: stop reading when item zero is strictly
   *        worse than the worst retained row's first key.
   * @param indexOrderedUpstream when true, check runtime context for pre-sorted
   *        signal from IndexOrderedEdgeStep; if sorted, pass through without sorting
   */
  public OrderByStep(
      SQLOrderBy orderBy,
      Integer maxResults,
      @Nullable SQLOrderByItem primaryKeySortedInput,
      boolean indexOrderedUpstream,
      CommandContext ctx,
      long timeoutMillis,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.orderBy = orderBy;
    this.fixedMaxResults = maxResults != null && maxResults >= 0 ? maxResults : null;
    this.skipClause = null;
    this.limitClause = null;
    assert primaryKeySortedInput == null
        || primaryKeySortedInput.equals(orderBy.getItems().getFirst());
    this.primaryKeySortedInput = primaryKeySortedInput;
    this.indexOrderedUpstream = indexOrderedUpstream;
    this.timeoutMillis = timeoutMillis;
  }

  /**
   * Resolves the bounded-heap size for one execution. A parameterized SKIP or LIMIT is read from
   * {@code ctx} here, which is what keeps a cached plan correct across parameter values.
   *
   * @return the maximum number of rows this execution needs, or {@code null} when unbounded
   */
  @Nullable private Integer resolveMaxResults(CommandContext ctx) {
    if (limitClause == null) {
      return fixedMaxResults;
    }
    var limitSize = limitClause.getValue(ctx);
    if (limitSize < 0) {
      return null;
    }
    var skipSize = skipClause == null ? 0 : skipClause.getValue(ctx);
    // Summed in long, because two large ints overflow: SKIP 2000000000 LIMIT 2000000000 wrapped
    // to a negative bound, which is neither null nor zero nor above the element cap, so it
    // reached PriorityQueue and raised an argument error. A bound past what any heap may hold
    // is the same thing as no bound, so it falls back to the unbounded sort.
    var total = (long) limitSize + Math.max(skipSize, 0);
    return total > Integer.MAX_VALUE ? null : (int) total;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      return ExecutionStream.empty();
    }

    // Start the upstream pipeline FIRST. IndexOrderedEdgeStep sets the
    // VAR_INDEX_ORDERED_PRE_SORTED context variable inside its
    // internalStart(), which runs when we call prev.start(). Checking the
    // flag before starting upstream would always see null (unset) because
    // the pipeline is started top-down: OrderByStep.internalStart() runs
    // before IndexOrderedEdgeStep.internalStart().
    var upstream = prev.start(ctx);

    // Pass-through: IndexOrderedEdgeStep signaled that output is fully
    // pre-sorted (single-field ORDER BY). No buffering, no sorting — just
    // forward the stream for downstream LimitStep to handle early termination.
    // Multi-field ORDER BY (primaryKeySortedInput != null) still needs the
    // bounded heap for the composite sort, so pass-through is not used.
    if (indexOrderedUpstream
        && primaryKeySortedInput == null
        && Boolean.TRUE.equals(
            ctx.getSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED))) {
      return upstream;
    }

    var results = init(upstream, ctx, resolveMaxResults(ctx));
    return ExecutionStream.resultIterator(results.iterator());
  }

  /**
   * Pulls all records from the provided upstream stream, sorts them, and returns the
   * sorted list.
   *
   * <p>When {@code maxResults} is set (derived from SKIP + LIMIT), a bounded min-heap
   * (priority queue) of size {@code maxResults} is used. Each incoming row is compared
   * against the heap's worst element: rows that rank higher replace the worst and
   * re-heapify; rows that rank lower are discarded immediately. This reduces memory
   * from O(|all results|) to O(maxResults) and avoids repeated full sorts.
   *
   * <p>When {@code maxResults} is not set, all rows are collected and sorted once.
   *
   * @param upstream   the already-started upstream stream to pull from
   * @param ctx        the command context
   * @param maxResults the bound resolved for this execution, or {@code null} when unbounded
   * @return the sorted (and possibly truncated) list of results
   * @throws CommandExecutionException if the number of elements exceeds
   *         {@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP}
   */
  private List<Result> init(
      ExecutionStream upstream, CommandContext ctx, @Nullable Integer maxResults) {
    var timeoutBegin = System.currentTimeMillis();

    if (maxResults != null) {
      if (maxResults == 0) {
        upstream.close(ctx);
        return Collections.emptyList();
      }
      int workItems = orderBy.storedKeyCount();
      return workItems == 0 ? initDirectBoundedHeap(upstream, ctx, timeoutBegin, maxResults)
          : initBoundedHeap(upstream, ctx, timeoutBegin, maxResults, workItems);
    }
    int workItems = orderBy.storedKeyCount();
    return workItems == 0 ? initDirectUnbounded(upstream, ctx, timeoutBegin)
        : initUnbounded(upstream, ctx, timeoutBegin, workItems);
  }

  private static final class SortRow {
    private Result row;
    private final SQLOrderByItem.SortKey[] keys;

    private SortRow(Result row, int items) {
      this.row = row;
      keys = new SQLOrderByItem.SortKey[items];
    }

    private SQLOrderByItem.SortKey key(int index) {
      if (keys[index] == null) {
        keys[index] = new SQLOrderByItem.SortKey();
      }
      return keys[index];
    }

    private void reset(Result row) {
      this.row = row;
      for (var key : keys) {
        if (key != null) {
          key.reset();
        }
      }
    }
  }

  private int compare(SortRow a, SortRow b, CommandContext ctx,
      ResolvedOrderByNullsPlacement nullsDefault) {
    return orderBy.compareKeys(a.row, a.keys, b.row, b.keys, ctx, nullsDefault);
  }

  /**
   * Bounded top-N selection using a min-heap. The heap holds at most {@code maxResults}
   * elements, with the <em>worst</em> element (last in ORDER BY order) at the root.
   * A reversed comparator is used so that {@link PriorityQueue#peek()} returns the
   * worst kept element, enabling O(1) comparison for each incoming row.
   */
  private List<Result> initBoundedHeap(
      ExecutionStream upstream, CommandContext ctx, long timeoutBegin, int maxResults,
      int workItems) {
    var maxElementsAllowed =
        GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    try {
      if (maxElementsAllowed >= 0 && maxResults > maxElementsAllowed) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Limit of allowed entities for in-heap ORDER BY in a single query exceeded ("
                + maxElementsAllowed
                + ") . You can set "
                + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
                + " to increase this limit");
      }
      // Null placement is fixed for the whole scan, exactly like the early-termination flag below.
      // Resolving per comparison would take a storage read lock on every null-involved compare. A
      // configuration change mid-sort would also break the comparator contract.
      var nullsDefault = OrderByNullsUtil.resolvePlacementsForSort(ctx);

      // Reversed comparator: peek() returns the element that sorts LAST (worst in top-N).
      var heap = new PriorityQueue<SortRow>(
          maxResults, (a, b) -> compare(b, a, ctx, nullsDefault));
      // A rejected candidate reuses its storage. Only rows admitted to the heap retain keys.
      SortRow candidate = null;

      // Early-termination eligibility is fixed for the whole scan:
      // IndexOrderedEdgeStep sets VAR_INDEX_ORDERED_PRE_SORTED before this step
      // starts and never changes it mid-iteration, so read it once here rather
      // than per row.
      boolean earlyTerminationEnabled =
          primaryKeySortedInput != null
              && indexOrderedUpstream
              && Boolean.TRUE.equals(
                  ctx.getSystemVariable(
                      CommandContext.VAR_INDEX_ORDERED_PRE_SORTED));

      while (upstream.hasNext(ctx)) {
        if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
          sendTimeout();
        }
        var item = upstream.next(ctx);
        if (candidate == null) {
          candidate = new SortRow(item, workItems);
        } else {
          candidate.reset(item);
        }

        // Early termination: when the input is sorted by the primary ORDER BY
        // field and the heap is full, stop reading as soon as the primary key
        // of the new item is strictly worse than the heap's worst element.
        // All subsequent items will also be worse (input is sorted).
        // Only active when IndexOrderedEdgeStep confirmed pre-sorted output;
        // when the fallback (unsorted) path was taken, cutoff is unsafe.
        if (earlyTerminationEnabled
            && heap.size() >= maxResults
            && orderBy.getItems().getFirst().compareKeys(
                candidate.row, primarySlot(candidate), heap.peek().row, primarySlot(heap.peek()),
                ctx, nullsDefault) > 0) {
          break;
        }

        if (heap.size() < maxResults) {
          heap.offer(candidate);
          candidate = null;
        } else if (compare(candidate, heap.peek(), ctx, nullsDefault) < 0) {
          var evicted = heap.poll();
          heap.offer(candidate);
          candidate = evicted;
        }
      }

      var result = new LinkedList<Result>();
      while (!heap.isEmpty()) {
        result.addFirst(heap.poll().row);
      }
      return result;
    } finally {
      upstream.close(ctx);
    }
  }

  /**
   * Unbounded path: collects all upstream rows, then sorts once.
   * Enforces {@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP}.
   */
  private List<Result> initUnbounded(
      ExecutionStream upstream, CommandContext ctx, long timeoutBegin, int workItems) {
    var maxElementsAllowed =
        GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    List<SortRow> cachedResult = new ArrayList<>();
    // Fix placement when the sort starts, before a potentially long upstream drain.
    var nullsDefault = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    try {
      while (upstream.hasNext(ctx)) {
        if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
          sendTimeout();
        }
        var item = upstream.next(ctx);
        cachedResult.add(new SortRow(item, workItems));
        if (maxElementsAllowed >= 0 && maxElementsAllowed < cachedResult.size()) {
          throw new CommandExecutionException(ctx.getDatabaseSession(),
              "Limit of allowed entities for in-heap ORDER BY in a single query exceeded ("
                  + maxElementsAllowed
                  + ") . You can set "
                  + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
                  + " to increase this limit");
        }
      }
      cachedResult.sort((a, b) -> compare(a, b, ctx, nullsDefault));
      // The returned stream owns rows only, so no key remains reachable downstream.
      var result = new ArrayList<Result>(cachedResult.size());
      for (var item : cachedResult) {
        result.add(item.row);
      }
      return result;
    } finally {
      upstream.close(ctx);
    }
  }

  private SQLOrderByItem.SortKey primarySlot(SortRow row) {
    return orderBy.getItems().getFirst().needsStoredKey() ? row.key(0) : null;
  }

  // All-cheap sorts use row-only selection and output algorithms, without key wrappers.
  private List<Result> initDirectBoundedHeap(
      ExecutionStream upstream, CommandContext ctx, long timeoutBegin, int maxResults) {
    var maxElementsAllowed =
        GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    try {
      if (maxElementsAllowed >= 0 && maxResults > maxElementsAllowed) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Limit of allowed entities for in-heap ORDER BY in a single query exceeded ("
                + maxElementsAllowed + ") . You can set "
                + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
                + " to increase this limit");
      }
      var nullsDefault = OrderByNullsUtil.resolvePlacementsForSort(ctx);
      var heap = new PriorityQueue<Result>(
          maxResults, (a, b) -> orderBy.compare(b, a, ctx, nullsDefault));
      boolean earlyTerminationEnabled = primaryKeySortedInput != null && indexOrderedUpstream
          && Boolean.TRUE.equals(ctx.getSystemVariable(
              CommandContext.VAR_INDEX_ORDERED_PRE_SORTED));
      while (upstream.hasNext(ctx)) {
        if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
          sendTimeout();
        }
        var item = upstream.next(ctx);
        if (earlyTerminationEnabled && heap.size() >= maxResults
            && orderBy.getItems().getFirst().compare(item, heap.peek(), ctx, nullsDefault) > 0) {
          break;
        }
        if (heap.size() < maxResults) {
          heap.offer(item);
        } else if (orderBy.compare(item, heap.peek(), ctx, nullsDefault) < 0) {
          heap.poll();
          heap.offer(item);
        }
      }
      var result = new LinkedList<Result>();
      while (!heap.isEmpty()) {
        result.addFirst(heap.poll());
      }
      return result;
    } finally {
      upstream.close(ctx);
    }
  }

  private List<Result> initDirectUnbounded(
      ExecutionStream upstream, CommandContext ctx, long timeoutBegin) {
    var maxElementsAllowed =
        GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    List<Result> cachedResult = new ArrayList<>();
    var nullsDefault = OrderByNullsUtil.resolvePlacementsForSort(ctx);
    try {
      while (upstream.hasNext(ctx)) {
        if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
          sendTimeout();
        }
        cachedResult.add(upstream.next(ctx));
        if (maxElementsAllowed >= 0 && maxElementsAllowed < cachedResult.size()) {
          throw new CommandExecutionException(ctx.getDatabaseSession(),
              "Limit of allowed entities for in-heap ORDER BY in a single query exceeded ("
                  + maxElementsAllowed + ") . You can set "
                  + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
                  + " to increase this limit");
        }
      }
      cachedResult.sort((a, b) -> orderBy.compare(a, b, ctx, nullsDefault));
      return cachedResult;
    } finally {
      upstream.close(ctx);
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ " + orderBy;
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    // Printed with the step's own context, so a parameterized bound shows the value that
    // context carries rather than a value frozen into the step.
    var printedMaxResults = resolveMaxResults(this.ctx);
    result += (printedMaxResults != null ? "\n  (buffer size: " + printedMaxResults + ")" : "");
    return result;
  }

  /**
   * Cacheable: the ORDER BY clause is a structural AST node deep-copied per execution, and the
   * SKIP/LIMIT bound is resolved per execution rather than stored as a number.
   */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var copiedOrderBy = orderBy.copy();
    // The cutoff always uses item zero, including after a cached plan is copied.
    var copiedHint = primaryKeySortedInput == null ? null : copiedOrderBy.getItems().getFirst();
    if (limitClause != null) {
      return new OrderByStep(
          copiedOrderBy, skipClause == null ? null : skipClause.copy(), limitClause.copy(),
          copiedHint, indexOrderedUpstream, ctx, timeoutMillis, profilingEnabled);
    }
    return new OrderByStep(
        copiedOrderBy, fixedMaxResults, copiedHint,
        indexOrderedUpstream, ctx, timeoutMillis, profilingEnabled);
  }
}
