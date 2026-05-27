# Track 3: Pause/resume — shared stream + per-view position

## Purpose / Big Picture

BLUF: After this track, a second `query()` for the same key in the same tx can continue iterating a partially-consumed stream from where the first view left off. Cache enables true incremental population across multiple consumers. The miss path also stamps `entry.populateMutationVersion = tx.mutationVersion` immediately before driving the executor (D21) and the view ctor pins the entry via `entry.liveViewCount++` (I9).

Extend `CachedEntry` to hold the live `ExecutionStream` past the first consumer's iteration, and extend `CachedResultSetView` to fall through to it when the consumer outruns the cached list. Multiple `query()` calls within one tx return independent views sharing the same entry; the first view to pull a particular row is the one that pays the storage cost. Close the stream when exhausted or evicted. Pinned entries (any `liveViewCount > 0`) are exempt from LRU eviction so a slow consumer never sees its result silently truncated to a cached prefix.

## Context and Orientation

**Codebase state at track start.** After Track 2:
- Cache populates on miss via consumer-driven stream pull. Each `view.next()` pulls one record from `entry.stream`, appends to `entry.results` + `entry.cachedRids`, returns to caller.
- `entry.stream` field exists but in Track 2 was either always-active (no close) or always-eager (force-exhaust on miss). Track 3 makes it paused-and-resumable.

Existing relevant code:
- `ExecutionStream` interface — `next(ctx)`, `hasNext(ctx)`, `close(ctx)`. The interface does NOT mandate `close(ctx)` idempotency; concrete impls in `core/.../resultset/` vary (some have `alreadyClosed` guards, some don't). This track wraps in `IdempotentExecutionStream` to defend regardless of underlying impl.
- `LocalResultSet` / `activeQueries` (`DatabaseSessionEmbedded.java:238`) — weak-value map. The cache's strong reference to the wrapped stream (held via `entry.stream`) is what keeps it alive past LocalResultSet GC; tx-end ordering is `closeActiveQueries() → … → cache.clear()`, so both paths can reach the same stream at tx end if the LocalResultSet hasn't been GC'd yet.

**Concrete deliverables.**
- `IdempotentExecutionStream` wrapper class (new) — wraps an `ExecutionStream`, makes `close(ctx)` idempotent. `hasNext(ctx)` / `next(ctx)` forward unconditionally. Field `closed: boolean`; first `close(ctx)` call sets to true and forwards; subsequent calls no-op.
- Cache substitutes this wrapper into BOTH `entry.stream` AND the paired `LocalResultSet`'s stream reference at cache-put time, so cross-caller double-close (closeActiveQueries + cache.clear at tx-end) reaches the same wrapper and is safe.
- `CachedEntry.exhausted: boolean` flips to true when stream reports `hasNext == false`.
- `CachedEntry.close()` closes `stream` if non-null; sets `stream=null, plan=null, ctx=null`; sets `exhausted=true` for idempotency. Calling twice is a no-op (the null-out makes the second call cheap; the wrapper makes the underlying close safe).
- **`entry.populateMutationVersion = tx.mutationVersion` stamping (D21)** — the cache miss path captures the stamp on the owning thread immediately **before** the first `plan.start(ctx)` call. Capturing later (at view construction, after populate has driven the executor) would defeat the filter because the post-populate view's delta would no longer cleanly post-date populate. Track 4's `DeltaBuilder` reads this field and filters `tx.recordOperations.values()` by `op.version > entry.populateMutationVersion`.
- **`entry.liveViewCount` refcount (I9)** — `CachedResultSetView` constructor increments; `close()` and natural exhaustion (`hasNext()` returns false after both cache + delta + stream are drained) decrement. Decrement path is idempotent: a view that exhausts then is explicitly closed decrements at most once (guarded by a local `decremented: boolean` on the view). `QueryResultCache.removeEldestEntry` skips entries with `liveViewCount > 0` (soft cap) UNLESS the map size exceeds `2 × maxEntries` in which case force-close the eldest with a warning log (fallback for pathological pin patterns).
- **`activeQueries` registration** — `CachedResultSetView` registers in `DatabaseSessionEmbedded.activeQueries` at construction (same map LocalResultSet uses — `WeakValueHashMap` in embedded mode, `HashMap` in server mode per `DatabaseSessionEmbedded.java:238/256`). Registration uses a unique session-scoped key (UUID or similar). This guarantees that tx-end `closeActiveQueries()` reaches every constructed view and invokes `view.close()`, which decrements `liveViewCount` and closes the underlying stream wrapper. **Without this registration, abandoned views (not explicitly closed by the app) would leak `liveViewCount > 0` references that prevent LRU eviction even after tx-end**. The cache's own `cache.clear()` clears entries map, but views still hold entry references; without explicit view.close() the liveViewCount stays positive. closeActiveQueries closes the view first; cache.clear runs second per the existing tx-end ordering at `FrontendTransactionImpl.java:973`.
- `CachedResultSetView.next()` (now functional, not stubbed): if `position >= entry.results.size()` and `!entry.exhausted`, pull one from `entry.stream`, append to `entry.results` and `entry.cachedRids`, return.

## Plan of Work

1. Implement `IdempotentExecutionStream` wrapper. `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/IdempotentExecutionStream.java`. Fields: `final ExecutionStream underlying`, `boolean closed`. Methods: `hasNext(ctx) → underlying.hasNext(ctx)`, `next(ctx) → underlying.next(ctx)`, `close(ctx) → if (!closed) { closed = true; underlying.close(ctx); }`.
2. Wire the wrapper into the cache-put path. **Before** `statement.createExecutionPlan(...)` / `plan.start(ctx)`, capture `entry.populateMutationVersion = tx.mutationVersion` (D21 stamping). After `plan.start(ctx)` yields the underlying stream, the cache wraps via `new IdempotentExecutionStream(underlying)` and stores the wrapper in `entry.stream`. Also reach into the constructed `LocalResultSet` (the one that goes into `activeQueries`) and substitute its internal stream reference with the same wrapper instance, so `LocalResultSet.close()` reaches the wrapper, not the raw underlying. (Implementation detail — Track 3 owns the substitution mechanism; if `LocalResultSet`'s stream field is final, the alternative is to construct the LocalResultSet around the wrapper from the start.)
3. Add `exhausted` flag to `CachedEntry` (defaults false). Stream-pull code flips it when `stream.hasNext(ctx) == false`. Add `liveViewCount: int` (defaults 0; D21 / I9 refcount).
4. Make `CachedEntry.close()` idempotent: null-guard `stream`, `plan`, `ctx`; flip `exhausted=true`; second call is a no-op early-return. `close()` does NOT touch `liveViewCount` — refcount is the consumer's responsibility through view ctor/close.
5. Implement view fall-through in `CachedResultSetView.next()`: when position outruns `entry.results.size()`, check `entry.exhausted`. If exhausted → no more data from cache; if not → pull from stream, append, return. View ctor increments `entry.liveViewCount`; view `close()` and natural exhaustion path each decrement at most once via a `decremented: boolean` view-local guard.
6. **Override `removeEldestEntry` in `QueryResultCache` to skip pinned entries (I9) with fallback for pathological pins**: pseudocode:
   ```
   if (size() <= maxEntries) return false;
   if (eldest.getValue().liveViewCount > 0) {
     if (size() > 2 * maxEntries) {
       LOG.warn("cache size {} exceeded 2× soft cap {}, force-evicting pinned entry {} (livenessCount={})",
                size(), maxEntries, eldest.getKey(), eldest.getValue().liveViewCount);
       eldest.getValue().close();  // force close; abandoned views start throwing on next()
       return true;
     }
     return false;  // normal pin skip
   }
   eldest.getValue().close();
   return true;
   ```
   The normal pin skip lets LinkedHashMap stay above soft cap transiently; the fallback at 2× hard cap bounds memory in pathological abandoned-view scenarios.
7. **`CachedResultSetView` registration in `activeQueries`** — view constructor registers itself in `DatabaseSessionEmbedded.activeQueries` map (same map LocalResultSet uses) under a unique session-scoped key. `view.close()` deregisters. This guarantees tx-end `closeActiveQueries()` reaches every constructed view (including app-abandoned ones), calling `view.close()` which decrements `liveViewCount` and closes the underlying stream wrapper. Without this hookup, abandoned views leak `liveViewCount > 0` past cache.clear and prevent next-tx LRU eviction.
8. Stream-lifecycle tests (T3 set):
   - T3a: two consumers race to next(). First pulls record A; second sees A in `entry.results[0]`; second pulls record B; first sees B in `entry.results[1]`. Both end at same final state.
   - T3b: consumer drops view mid-iteration without closing. Cache retains the entry; new `query()` constructs a new view that continues from current position.
   - T3c: tx end while view is mid-iteration → `cache.clear()` closes the stream → next `view.next()` throws (acceptable for shutdown).
   - T3d (I3): pause stream; LRU-evict the entry (force `removeEldestEntry` via small `maxEntries`); assert wrapper's underlying stream observed exactly one close.
   - T3e (I6 single-caller): call `entry.close()` twice → wrapper sees exactly one underlying close, no exception.
   - T3f (I6 cross-caller — KEY TEST): construct an entry whose underlying stream is a non-idempotent mock (throws on second close); trigger BOTH `closeActiveQueries()` (which closes the wrapped LocalResultSet stream) AND `cache.clear()` (which closes the same wrapped stream via entry.close); assert the underlying is closed exactly once and no exception propagates. This is the scenario the wrapper exists to defend against.
   - **T3i9 (I9 view pinning under LRU pressure)**: configure `maxEntries=2`; cache miss on Q1 returns a view; consumer pulls 1 row but does not exhaust; issue 5 more distinct queries (Q2..Q6) to flood LRU pressure; resume iterating the Q1 view; assert it returns the full row sequence a parallel uncached `db.query(Q1)` would (matching by RID), proving the pinned entry was NOT evicted; close the Q1 view; issue one more query; assert the Q1 entry NOW becomes eligible for eviction (size shrinks).
   - **T3i9b (abandoned-view tx-end safety)**: open view on Q1; drop reference without closing (set local var to null, no .close() call); issue 5 more distinct queries; commit tx; assert `closeActiveQueries` was called and reached the abandoned view's close; assert no force-evict warning was logged (because abandoned view's slot in activeQueries' WeakValueHashMap may or may not have GC'd, but closeActiveQueries hits all hard refs first; the cleanup completes correctly).
   - **T3i9c (fallback eviction at 2× soft cap)**: configure `maxEntries=2`; open and HOLD references to 5 views (Q1..Q5) all unfinished; issue 6th query; assert force-evict warning fires for the eldest pinned entry (Q1) with `livenessCount=1`; assert Q1's underlying stream is closed; assert subsequent Q1 view.next() throws (acceptable for pathological case); assert cache size dropped back below 2× cap.
   - **T3d21a (D21 stamping timing)**: spy on `entry.populateMutationVersion` assignment; assert the assignment happens BEFORE `plan.start(ctx)` is invoked; assert the captured value equals `tx.mutationVersion` at that exact moment (not later).

**Invariants to preserve.** Stream lifetime ≤ entry lifetime (I3). After tx end, stream is closed; no leaked resources. View `next()` works correctly when `entry.results.size()` < total query result count (resumes from stream). Live views never observe truncation under LRU pressure (I9). Populate-time stamp captured pre-`plan.start` to anchor D21 filter for Track 4.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/IdempotentExecutionStream.java` (new — wrapper)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (close idempotency, exhausted flag, stores the wrapper not the raw stream)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (fall-through to stream)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (cache-put path constructs the wrapper, threads it into both `entry.stream` and the LocalResultSet's internal stream slot)

**Out-of-scope files.**
- `DatabaseSessionEmbedded.activeQueries` — the cache entries themselves are NOT registered (cache holds its own reference via `entries: Map<CacheKey, CachedEntry>`); only the views handed to consumers register, like any other ResultSet does. This ensures tx-end closeActiveQueries closes views and decrements liveViewCount.
- `DeltaBuilder` — Track 4.

**Inter-track dependencies.**
- Depends on: Track 2 (read path + view).
- Unblocks: Track 4 (delta build needs the stream to be resumable when the view pulls past cached prefix).

**Library / function signatures.**
- `IdempotentExecutionStream(ExecutionStream underlying)` — wrapper constructor.
- `IdempotentExecutionStream.close(CommandContext)` — idempotent by construction.
- `CachedEntry.close()` — idempotent, no-throw.
