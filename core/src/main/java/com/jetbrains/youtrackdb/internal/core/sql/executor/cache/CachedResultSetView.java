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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The consumer-facing {@link ResultSet} a cached {@code query()} returns. It reconstructs the rows a
 * fresh uncached execution would emit (invariant I10) by merging a {@link CachedEntry}'s frozen output
 * with the in-transaction mutations the {@link DeltaBuilder} captured into a {@link TxDeltaCursor},
 * without ever re-running storage.
 *
 * <p><b>RECORD path (sorted-merge).</b> {@link #next()} merges two cursors: the positional
 * <i>cache cursor</i> (a per-view index into the entry's lazily-grown {@link CachedEntry#getResults()})
 * and the per-view <i>delta cursor</i> ({@link TxDeltaCursor}). Each step drops any cache row whose RID
 * the delta marks for skip (DELETED or re-positioned), materializes the next storage row from the
 * shared paused stream when the cached prefix is exhausted but the stream is not (so a delta inject can
 * never be emitted ahead of a not-yet-pulled storage row that sorts earlier), then emits whichever of
 * the two heads sorts smaller per the entry's ORDER BY. Ties favour the inject side, which keeps a
 * mutated row at-or-before an equally-ranked cached row; either choice is correct because a tie can
 * only occur between distinct RIDs (a skip on the cache head was already consumed).
 *
 * <p><b>K0_NONE path (direct replay).</b> A K0_NONE entry carries no delta cursor: the lookup-time
 * mutation-version gate already guaranteed no post-populate mutation, so there is nothing to merge. The
 * view replays {@link CachedEntry#getResults()} directly, lazy-pulling the stream tail with no skip-set
 * filtering, exactly as a non-cached re-run of that deterministic plan would.
 *
 * <p><b>Stream-pull unification.</b> Both paths share one {@link #pullOneFromStream} helper. On the
 * RECORD path it drops any pulled row whose RID is in the delta skip-set (closing the lazy-pull gap:
 * a mutated RID living beyond the cached prefix must not surface from storage with stale state); on the
 * K0_NONE path the skip-set is empty so nothing is dropped. Pulled rows are appended to the shared
 * {@code entry.results} / {@code cachedRids} so later views replay the full ordered result.
 *
 * <p><b>View pinning.</b> The constructor increments {@link CachedEntry#getLiveViewCount()} and
 * {@link #close()} (or natural exhaustion) decrements it exactly once, pinning the entry against LRU
 * eviction while this view iterates so a mid-iteration view never loses rows.
 *
 * <p><b>Re-entrancy guard ownership.</b> The session opens the transaction's cache-code guard
 * ({@code FrontendTransactionImpl.enterCacheCode()}) around the synchronous lookup and hands the open
 * guard to this view. The view holds it for its whole iteration lifetime and releases it
 * ({@code exitCacheCode()}) exactly once, paired with the pin release on close / exhaustion. This is
 * load-bearing: the rows of a cached query are pulled lazily during {@link #next()} /
 * {@link #pullOneFromStream}, after the session's {@code serveThroughCache} has already returned, so a
 * UDF embedded in the populating query's WHERE / projection fires during that lazy pull. Holding the
 * guard across iteration keeps such a re-entrant {@code query()} bypassed (depth &gt; 0) for the whole
 * consumption window, not just the lookup window.
 *
 * <p><b>Idempotent close.</b> {@link #close()} is a no-op after the first call and releases the pin
 * and the cache-code guard exactly once. The view never closes the entry's shared stream itself: the
 * stream is owned by the {@link CachedEntry} (closed at tx-end cache clear) and possibly other views,
 * so closing it here would truncate them.
 *
 * <p>Single-transaction state observed only by the owning thread; no field is synchronised.
 */
public final class CachedResultSetView implements ResultSet {

  private final CachedEntry entry;

  /** Null for K0_NONE (direct replay); non-null for RECORD (sorted-merge). */
  @Nullable private final TxDeltaCursor delta;

  @Nullable private DatabaseSessionEmbedded session;

  /**
   * The transaction whose cache-code guard this view holds for its iteration lifetime. The session
   * entered the guard around the lookup and passed ownership here; the view exits it exactly once on
   * close / exhaustion via {@link #releasePin()}.
   */
  private final FrontendTransactionImpl tx;

  @Nullable private final InternalExecutionPlan executionPlan;

  /** Context used to drive the entry's shared stream when lazy-pulling the tail. */
  private final CommandContext ctx;

  /** Per-view positional cursor into the entry's shared {@link CachedEntry#getResults()}. */
  private int position;

  /** One-element lookahead the merge fills in {@link #hasNext()} and drains in {@link #next()}. */
  @Nullable private Result lookahead;

  private boolean closed;

  /**
   * Guards the single release of the pin AND the cache-code guard so {@link #close()} and exhaustion
   * together release each at most once.
   */
  private boolean pinReleased;

  public CachedResultSetView(
      @Nonnull CachedEntry entry,
      @Nullable TxDeltaCursor delta,
      @Nullable DatabaseSessionEmbedded session,
      @Nonnull FrontendTransactionImpl tx,
      @Nullable InternalExecutionPlan executionPlan,
      @Nonnull CommandContext ctx) {
    this.entry = entry;
    this.delta = delta;
    this.session = session;
    this.tx = tx;
    this.executionPlan = executionPlan;
    this.ctx = ctx;
    // Pin the entry for this view's whole lifetime so LRU eviction cannot close the shared stream
    // out from under an in-flight iteration. The cache-code guard is already open (entered by the
    // session around the lookup); this view now owns it and releases it on close / exhaustion.
    entry.incrementLiveViewCount();
  }

  @Override
  public boolean hasNext() {
    if (closed) {
      return false;
    }
    if (lookahead != null) {
      return true;
    }
    lookahead = computeNext();
    if (lookahead == null) {
      // Both cursors are drained: release the pin now so an iterated-to-exhaustion view stops
      // blocking eviction even before the consumer calls close(). The release is idempotent.
      releasePin();
      return false;
    }
    return true;
  }

  @Override
  public Result next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    var r = lookahead;
    lookahead = null;
    return r;
  }

  /**
   * Produces the next merged row, or {@code null} when both the cache cursor and the delta cursor are
   * exhausted. K0_NONE routes to a delta-free replay; RECORD runs the sorted-merge.
   */
  @Nullable private Result computeNext() {
    if (delta == null) {
      return computeNextK0None();
    }
    return computeNextRecord();
  }

  /**
   * K0_NONE direct replay: emit cached rows in order, lazy-pulling the stream tail (no skip-set, no
   * delta) until both are drained. The lookup-time version gate guarantees no post-populate mutation,
   * so the cached output already equals a fresh deterministic re-run.
   */
  @Nullable private Result computeNextK0None() {
    var results = entry.getResults();
    if (position < results.size()) {
      return results.get(position++);
    }
    if (!entry.isExhausted()) {
      var pulled = pullOneFromStream();
      if (pulled != null) {
        // Appended at the tail; advance past it and return it.
        position++;
        return pulled;
      }
    }
    return null;
  }

  /**
   * RECORD sorted-merge, transcribing the design's {@code view.next()} algorithm: skip cache heads the
   * delta replaces, materialize the next storage row before consulting the delta head (load-bearing for
   * sort correctness), then emit the smaller of the two heads with ties favouring the inject side.
   */
  @Nullable private Result computeNextRecord() {
    var results = entry.getResults();
    while (true) {
      var cacheHead = position < results.size() ? results.get(position) : null;

      // Suppress a cached row the delta marks for replacement or deletion; consume the position
      // without emitting and re-loop.
      if (cacheHead != null && isSkipped(cacheHead)) {
        position++;
        continue;
      }

      // Materialize the next storage row BEFORE looking at the delta head. The stream is the only
      // source of rows sorting between the pulled prefix and the storage tail; pulling here keeps a
      // delta inject from being emitted ahead of a not-yet-pulled storage row that sorts earlier.
      if (cacheHead == null && !entry.isExhausted()) {
        var pulled = pullOneFromStream();
        if (pulled != null) {
          // The pull appended to results; re-loop so cacheHead becomes that row at position.
          continue;
        }
        // null means the stream just drained; fall through with cacheHead still null.
      }

      var deltaHead = delta.peekInject();

      if (cacheHead == null && deltaHead == null) {
        return null;
      }
      // Cache drained, delta has rows: drain the (already sorted) inject list.
      if (cacheHead == null) {
        return delta.advanceInject();
      }
      // Delta drained, cache has rows: drain the cache.
      if (deltaHead == null) {
        position++;
        return cacheHead;
      }
      // Both heads present: emit the smaller per ORDER BY; ties favour the inject side.
      var orderBy = entry.getOrderBy();
      // With no ORDER BY there is no sort key, so the inject list (mutation-iteration order) drains
      // first, matching the unsorted fresh-execution contract for that query shape.
      var cmp = orderBy == null ? -1 : orderBy.compare(deltaHead, cacheHead, ctx);
      if (cmp <= 0) {
        return delta.advanceInject();
      }
      position++;
      return cacheHead;
    }
  }

  /** Whether the delta skip-set suppresses this row. Non-identifiable rows are never skipped. */
  private boolean isSkipped(@Nonnull Result r) {
    if (delta == null) {
      return false;
    }
    if (!r.isIdentifiable()) {
      return false;
    }
    var rid = r.getIdentity();
    // getIdentity() is @Nullable, but an identifiable result always carries a non-null RID. The skip
    // set is a Set<RID>, so a null here would silently miss (a stale row would never be suppressed)
    // rather than fail loudly, degrading into a wrong-result merge with no test signal. Assert the
    // invariant so a future change to isIdentifiable() semantics surfaces in the -ea test runs the
    // suite mandates.
    assert rid != null : "isIdentifiable() result has a null getIdentity()";
    return delta.shouldSkip(rid);
  }

  /**
   * Pulls one row from the entry's shared paused stream, appending it to {@code entry.results} and
   * {@code cachedRids} so later views replay it, and returns it. A pulled row whose RID is in the delta
   * skip-set is dropped (and the pull recurses) so a mutated RID beyond the cached prefix never
   * surfaces with stale storage state. Returns {@code null} when the stream is drained, flipping the
   * entry to exhausted and releasing its stream.
   */
  @Nullable private Result pullOneFromStream() {
    var stream = entry.getStream();
    if (stream == null) {
      // No live stream (already exhausted/closed): nothing more to pull.
      entry.setExhausted(true);
      return null;
    }
    var streamCtx = entry.getCtx();
    while (stream.hasNext(streamCtx)) {
      var r = stream.next(streamCtx);
      // Append to the shared cache before any skip decision so later views see the full ordered result
      // regardless of this view's skip-set. recordPulledRow also enforces the per-entry record cap: a
      // pull that crosses maxRecordsPerEntry overflows the entry (evicted from the cache, key routed
      // non-cacheable) and stops caching, but this view keeps emitting r so the consumer loses nothing.
      entry.recordPulledRow(r);
      if (isSkipped(r)) {
        // Suppressed for this view; keep pulling for a row this view can emit.
        continue;
      }
      return r;
    }
    // Stream drained: flip exhausted and release the stream so the entry stops holding it open.
    entry.setExhausted(true);
    entry.close();
    return null;
  }

  /**
   * Releases the entry's live-view pin and the transaction's cache-code guard exactly once across
   * {@link #close()} and natural exhaustion. The guard exit is paired with the pin decrement so the
   * re-entrancy bypass holds for the whole view-consumption lifetime (covering the lazy stream pulls
   * during iteration) and is lifted once the view can no longer pull rows.
   */
  private void releasePin() {
    if (!pinReleased) {
      pinReleased = true;
      entry.decrementLiveViewCount();
      // Unchecked exit: this release also fires on the tx-end clear path, which the pool-shutdown
      // thread may reach cross-thread (an abandoned-but-strongly-held view closed by
      // closeActiveQueries() during realClose()). The owning-thread assert on exitCacheCode() would
      // trip there under -ea even though the floored decrement is harmless off-thread, so the view
      // releases through the unchecked path while the synchronous session path keeps the assert.
      tx.exitCacheCodeUnchecked();
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    lookahead = null;
    session = null;
    // Release the pin; never close the entry's shared stream here — the entry (and other views) own
    // it, and the tx-end cache clear closes it exactly once.
    releasePin();
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Nullable @Override
  public DatabaseSessionEmbedded getBoundToSession() {
    return session;
  }

  @Nullable @Override
  public ExecutionPlan getExecutionPlan() {
    return executionPlan;
  }

  @Override
  public void forEachRemaining(@Nonnull Consumer<? super Result> action) {
    while (hasNext()) {
      action.accept(next());
    }
  }

  @Override
  public boolean tryAdvance(Consumer<? super Result> action) {
    if (hasNext()) {
      action.accept(next());
      return true;
    }
    return false;
  }

  @Nullable @Override
  public ResultSet trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public int characteristics() {
    return ORDERED;
  }
}
