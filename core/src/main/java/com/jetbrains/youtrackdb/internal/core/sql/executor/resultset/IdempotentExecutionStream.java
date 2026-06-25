package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import javax.annotation.Nonnull;

/**
 * An {@link ExecutionStream} decorator that makes {@link #close} safe to call more than once. A
 * general resultset utility; the tx-result cache is its first user (it shares one stream between a
 * cached entry and a consumer-facing view, so close must collapse to a single underlying close).
 *
 * <p>The cache stores the live execution stream of a populating query and substitutes this wrapper
 * into both the cached entry and the populating {@code LocalResultSet}'s stream slot. On the current
 * wiring only one owner ever closes the underlying stream: the cached entry, via {@code
 * CachedEntry.close()} — fired at the tx-end cache clear, or earlier when the entry is evicted,
 * invalidated, or overflowed (deferred via the pin mechanism until the last live view releases it).
 * The consumer-facing {@code
 * CachedResultSetView} deliberately never closes the shared stream, and the populating {@code
 * LocalResultSet} is orphaned (never registered in the session's active-query set), so its own {@code
 * close()} never runs. The idempotency here is therefore a defensive guard, not a live double-close
 * path: it costs nothing today and protects against a future second owner (or a future shape that
 * returns the {@code LocalResultSet} directly) without depending on the {@link ExecutionStream}
 * contract, which does not promise a no-op second close.
 *
 * <p>{@link #hasNext} and {@link #next} forward unconditionally — the wrapper adds no buffering or
 * state beyond the {@code closed} flag, so iteration behaves exactly as the underlying stream's.
 * Whichever owner calls {@link #close} first closes the underlying once; every later call is a no-op.
 *
 * <p>No synchronisation: the cache is single-transaction state observed only by the owning thread,
 * consistent with the rest of the tx-result-cache classes.
 */
public final class IdempotentExecutionStream implements ExecutionStream {

  private final ExecutionStream underlying;
  private boolean closed;

  public IdempotentExecutionStream(@Nonnull ExecutionStream underlying) {
    this.underlying = underlying;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    return underlying.hasNext(ctx);
  }

  @Override
  public Result next(CommandContext ctx) {
    return underlying.next(ctx);
  }

  @Override
  public void close(CommandContext ctx) {
    if (closed) {
      return;
    }
    closed = true;
    underlying.close(ctx);
  }

  /** Visible for tests: whether the underlying stream has been closed through this wrapper. */
  public boolean isClosed() {
    return closed;
  }
}
