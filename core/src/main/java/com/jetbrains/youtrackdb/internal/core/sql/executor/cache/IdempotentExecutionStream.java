/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import javax.annotation.Nonnull;

/**
 * An {@link ExecutionStream} decorator that makes {@link #close} safe to call more than once.
 *
 * <p>The cache stores the live execution stream of a populating query and substitutes this wrapper
 * into the consumer-facing result set's stream slot, so two independent owners can reach the same
 * underlying stream: the cache (via the cached entry's {@code close()} fired from the tx-end cache
 * clear) and the consumer-facing result set (via its own {@code close()} fired from {@code
 * closeActiveQueries()}). The tx-end ordering runs {@code closeActiveQueries()} before the cache
 * clear, so without idempotency the underlying stream would receive two {@code close} calls. The
 * {@link ExecutionStream} contract does not promise a no-op second close, so the cache provides one
 * here.
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
