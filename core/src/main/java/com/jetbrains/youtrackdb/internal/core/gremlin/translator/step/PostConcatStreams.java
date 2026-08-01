package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * The {@link ExecutionStream} decorators that realise {@link PostConcatOp} over a concatenated union
 * stream, plus the single-row {@code count} result both count paths emit.
 *
 * <p>These are plain stream decorators with no tie to the boundary-step lifecycle — only their
 * construction site lives in {@link MultiPlanMatchStep}. Keeping them here gives each operator a
 * unit-test home that needs no boundary step, and keeps the one {@code "count"} column name in a
 * single place instead of once per count path.
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
}
