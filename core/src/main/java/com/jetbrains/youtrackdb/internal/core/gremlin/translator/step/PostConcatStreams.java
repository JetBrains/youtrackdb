package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * The {@link ExecutionStream} decorators that realise {@link PostConcatOp} over a concatenated union
 * stream, plus the single-row {@code count} result both count paths emit.
 *
 * <p>These plain stream decorators have no tie to the boundary-step lifecycle. The {@link #apply}
 * factory assembles them for {@link MultiPlanMatchStep}. Keeping them here gives each operator a
 * unit-test home that needs no boundary step. Both count paths share the {@code "count"} column
 * name.
 */
final class PostConcatStreams {

  /** The column name both count paths write; the SCALAR projection reads the row's sole column. */
  static final String COUNT_COLUMN = "count";

  private PostConcatStreams() {
    // Static factories only.
  }

  /**
   * Builds the one-row result both count paths emit: the push-down sum over per-child {@code RETURN
   * count(*)} rows, and the drain-and-count over an already-reduced concatenation.
   */
  static Result singleCountRow(@Nonnull CommandContext ctx, long total) {
    var result = new ResultInternal((DatabaseSessionEmbedded) ctx.getDatabaseSession());
    result.setProperty(COUNT_COLUMN, total);
    return result;
  }

  /**
   * Drains {@code upstream} and emits one {@code count} row. Used when {@code count()} follows
   * another post-concat reduction, so the per-child {@code RETURN count(*)} push-down is unavailable
   * and the reduced rows have to be counted one by one.
   */
  static ExecutionStream count(@Nonnull ExecutionStream upstream) {
    return new CountStream(upstream);
  }

  /** Skips the first {@code skip} rows of {@code upstream} then passes the rest through. */
  static ExecutionStream skip(@Nonnull ExecutionStream upstream, long skip) {
    return new SkipStream(upstream, skip);
  }

  /**
   * Filters {@code upstream} down to the first row per distinct boundary identity.
   *
   * <p>The identity is read straight off the boundary column rather than through {@link
   * Result#getEntity(String)}: the column already holds an {@link Identifiable}, and {@code
   * getEntity} would call {@code loadEntity} on it — a full record materialisation — before the
   * caller discards everything but the RID it started from. On a duplicate row that load buys
   * nothing at all (the row is dropped), and under {@code union(…).dedup().count()} no row needs its
   * content. A boundary column holding something other than an {@link Identifiable} is used as its
   * own key, which also keeps a non-element payload from throwing the way {@code getEntity} does.
   */
  static ExecutionStream dedup(@Nonnull ExecutionStream upstream, @Nonnull String boundaryAlias) {
    final Set<Object> seen = new HashSet<>();
    return upstream.filter(
        (result, ctx) -> {
          Object raw = result.getProperty(boundaryAlias);
          if (raw == null) {
            return null;
          }
          Object id = raw instanceof Identifiable identifiable ? identifiable.getIdentity() : raw;
          return seen.add(id) ? result : null;
        });
  }

  /**
   * Keeps only rows whose {@code entityColumnAlias} entity has {@code propertyKey} present
   * ({@code EntityImpl.hasProperty}). Mirrors Gremlin's {@code by(key)} drop under standard order
   * semantics when the union path cannot push {@code IS DEFINED} into sealed child plans.
   */
  static ExecutionStream requireDefined(
      @Nonnull ExecutionStream upstream,
      @Nonnull String entityColumnAlias,
      @Nonnull String propertyKey) {
    return upstream.filter(
        (result, ctx) -> {
          var entity = result.getEntity(entityColumnAlias);
          if (!(entity instanceof EntityImpl entityImpl)
              || !entityImpl.hasProperty(propertyKey)) {
            return null;
          }
          return result;
        });
  }

  /**
   * Drains {@code upstream}, sorts rows with {@link SQLOrderByItem#compare}, and replays them in
   * order. Used for {@code union(…).order().by(…)} where SQL {@code ORDER BY} cannot span arms.
   */
  static ExecutionStream sort(
      @Nonnull ExecutionStream upstream, @Nonnull List<SQLOrderByItem> items) {
    return new SortStream(upstream, List.copyOf(items), -1);
  }

  /**
   * Assembles the union suffix without changing the recognised operations. An ended slice bounds
   * the preceding sort. Dedup between those two operations moves before the sort so duplicate rows
   * cannot consume the top-N budget. No operation crosses a slice or a count barrier.
   */
  static ExecutionStream apply(
      ExecutionStream stream, List<PostConcatOp> ops, String boundaryAlias) {
    for (int i = 0; i < ops.size(); i++) {
      var op = ops.get(i);
      if (op instanceof PostConcatOp.Order order) {
        int next = i + 1;
        boolean dedup = next < ops.size() && ops.get(next) instanceof PostConcatOp.Dedup;
        if (dedup) {
          next++;
        }
        if (next < ops.size() && ops.get(next) instanceof PostConcatOp.Range range
            && range.limit() >= 0) {
          if (dedup) {
            stream = dedup(stream, boundaryAlias);
            i++;
          }
          // Saturation also handles hand-built operation lists whose skip + limit overflows.
          long end = range.limit() == 0 ? 0
              : range.skip() + Math.min(range.limit(), Long.MAX_VALUE - range.skip());
          stream = sort(stream, order.items(), end);
          if (end == 0) {
            // The empty draining sort implements the slice itself. A limit(0) wrapper would
            // bypass the sort and hide upstream failures.
            i = next;
          }
          continue;
        }
      }
      stream = switch (op) {
        case PostConcatOp.Count ignored -> count(stream);
        case PostConcatOp.Range range -> {
          var sliced = range.skip() > 0 ? skip(stream, range.skip()) : stream;
          yield range.limit() >= 0 ? sliced.limit(range.limit()) : sliced;
        }
        case PostConcatOp.Dedup ignored -> dedup(stream, boundaryAlias);
        case PostConcatOp.RequireDefined defined -> requireDefined(
            stream, defined.entityColumnAlias(), defined.propertyKey());
        case PostConcatOp.Order order -> sort(stream, order.items());
      };
    }
    return stream;
  }

  /** Stable top-N sort with a long bound and no allocation based on the declared slice end. */
  static ExecutionStream sort(
      ExecutionStream upstream, List<SQLOrderByItem> items, long end) {
    return new SortStream(upstream, List.copyOf(items), end);
  }

  /** Drain-and-count decorator; see {@link #count(ExecutionStream)}. */
  private static final class CountStream implements ExecutionStream {

    private final ExecutionStream upstream;
    private Result pending;
    private boolean computed;
    private boolean upstreamClosed;

    CountStream(ExecutionStream upstream) {
      this.upstream = upstream;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      ensure(ctx);
      return pending != null;
    }

    @Override
    public Result next(CommandContext ctx) {
      ensure(ctx);
      if (pending == null) {
        throw new IllegalStateException("no counted row");
      }
      var out = pending;
      pending = null;
      return out;
    }

    @Override
    public void close(CommandContext ctx) {
      closeUpstreamOnce(ctx);
      pending = null;
    }

    /**
     * Releases the concatenator at most once. The drain in {@link #ensure} closes it eagerly so an
     * exhausted union frees its last child immediately, and the base then closes this stream again
     * when it releases the arming; {@link ExecutionStream} states no idempotency requirement, so the
     * latch keeps the second call from re-entering the whole child chain.
     */
    private void closeUpstreamOnce(CommandContext ctx) {
      if (upstreamClosed) {
        return;
      }
      upstreamClosed = true;
      upstream.close(ctx);
    }

    private void ensure(CommandContext ctx) {
      if (computed) {
        return;
      }
      computed = true;
      long total = 0L;
      try {
        while (upstream.hasNext(ctx)) {
          upstream.next(ctx);
          total++;
        }
      } finally {
        closeUpstreamOnce(ctx);
      }
      pending = singleCountRow(ctx, total);
    }
  }

  /** Skip decorator; see {@link #skip(ExecutionStream, long)}. */
  private static final class SkipStream implements ExecutionStream {

    private final ExecutionStream upstream;
    private final long skip;
    private long skipped;

    SkipStream(ExecutionStream upstream, long skip) {
      this.upstream = upstream;
      this.skip = skip;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      while (skipped < skip && upstream.hasNext(ctx)) {
        upstream.next(ctx);
        skipped++;
      }
      return upstream.hasNext(ctx);
    }

    @Override
    public Result next(CommandContext ctx) {
      if (!hasNext(ctx)) {
        throw new IllegalStateException();
      }
      return upstream.next(ctx);
    }

    @Override
    public void close(CommandContext ctx) {
      upstream.close(ctx);
    }
  }

  /** Sort decorator; see {@link #sort(ExecutionStream, List)}. */
  private static final class SortStream implements ExecutionStream {

    private final ExecutionStream upstream;
    private final List<SQLOrderByItem> items;
    private final List<Result> sorted = new ArrayList<>();
    private final long end;
    private PriorityQueue<SortRow> heap;
    private int index;
    private boolean materialized;
    private boolean upstreamClosed;

    SortStream(ExecutionStream upstream, List<SQLOrderByItem> items, long end) {
      this.upstream = upstream;
      this.items = items;
      this.end = end;
    }

    /** Arrival order is the final key, matching the full sort's stable treatment of ties. */
    private record SortRow(Result result, long ordinal) {
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      ensure(ctx);
      return index < sorted.size();
    }

    @Override
    public Result next(CommandContext ctx) {
      if (!hasNext(ctx)) {
        throw new NoSuchElementException();
      }
      return sorted.get(index++);
    }

    @Override
    public void close(CommandContext ctx) {
      closeUpstreamOnce(ctx);
      sorted.clear();
      if (heap != null) {
        heap.clear();
        heap = null;
      }
      index = 0;
      materialized = false;
    }

    private void closeUpstreamOnce(CommandContext ctx) {
      if (upstreamClosed) {
        return;
      }
      upstreamClosed = true;
      upstream.close(ctx);
    }

    private void ensure(CommandContext ctx) {
      if (materialized) {
        return;
      }
      materialized = true;
      try {
        if (end == 0) {
          // A sort is a draining barrier even for an empty slice. Preserve upstream failures
          // and cancellation without retaining any rows.
          while (upstream.hasNext(ctx)) {
            upstream.next(ctx);
          }
          return;
        }
        // Resolve once: a mid-sort config flip would break the comparator, and each resolution
        // takes a storage lock. Both paths keep the upstream hasNext/next cancellation checks.
        var nullsDefault = OrderByNullsUtil.resolvePlacementsForSort(ctx);
        Comparator<Result> comparator = (a, b) -> {
          for (var item : items) {
            var cmp = item.compare(a, b, ctx, nullsDefault);
            if (cmp != 0) {
              return cmp;
            }
          }
          return 0;
        };
        if (end < 0) {
          while (upstream.hasNext(ctx)) {
            sorted.add(upstream.next(ctx));
          }
          sorted.sort(comparator);
        } else {
          Comparator<SortRow> stable = (a, b) -> {
            int cmp = comparator.compare(a.result(), b.result());
            return cmp != 0 ? cmp : Long.compare(a.ordinal(), b.ordinal());
          };
          long ordinal = 0;
          while (upstream.hasNext(ctx)) {
            var result = upstream.next(ctx);
            long arrival = ordinal++;
            if (heap == null && sorted.size() < end) {
              sorted.add(result);
              continue;
            }
            if (heap == null) {
              // Delay the heap until the bound actually discards a row. Inputs that fit keep
              // the full sort's adaptive comparison count and need no SortRow wrappers.
              // Capacity never depends on end, and there is no row-count cap.
              heap = new PriorityQueue<>(1, stable.reversed());
              for (int i = 0; i < sorted.size(); i++) {
                heap.add(new SortRow(sorted.set(i, null), i));
              }
              sorted.clear();
            }
            var row = new SortRow(result, arrival);
            if (stable.compare(row, heap.element()) < 0) {
              heap.remove();
              heap.add(row);
            }
          }
          if (heap == null) {
            sorted.sort(comparator);
          } else {
            // Sorting the heap contents avoids a logarithmic poll for every output row.
            // Release the heap array before replay, and the wrappers when ensure returns.
            var kept = new ArrayList<>(heap);
            heap.clear();
            heap = null;
            kept.sort(stable);
            for (var row : kept) {
              sorted.add(row.result());
            }
          }
        }
      } finally {
        closeUpstreamOnce(ctx);
      }
      index = 0;
    }
  }
}
