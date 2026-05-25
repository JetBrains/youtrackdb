# Track 3: Pause/resume — shared stream + per-view position

## Purpose / Big Picture

BLUF: After this track, a second `query()` for the same key in the same tx can continue iterating a partially-consumed stream from where the first view left off. Cache enables true incremental population across multiple consumers.

Extend `CachedEntry` to hold the live `ExecutionStream` past the first consumer's iteration, and extend `CachedResultSetView` to fall through to it when the consumer outruns the cached list. Multiple `query()` calls within one tx return independent views sharing the same entry; the first view to pull a particular row is the one that pays the storage cost. Close the stream when exhausted or evicted.

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
- `CachedResultSetView.next()` (now functional, not stubbed): if `position >= entry.results.size()` and `!entry.exhausted`, pull one from `entry.stream`, append to `entry.results` and `entry.cachedRids`, return.

## Plan of Work

1. Implement `IdempotentExecutionStream` wrapper. `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/IdempotentExecutionStream.java`. Fields: `final ExecutionStream underlying`, `boolean closed`. Methods: `hasNext(ctx) → underlying.hasNext(ctx)`, `next(ctx) → underlying.next(ctx)`, `close(ctx) → if (!closed) { closed = true; underlying.close(ctx); }`.
2. Wire the wrapper into the cache-put path. After `statement.createExecutionPlan(...)` / `plan.start(ctx)` yields the underlying stream, the cache wraps via `new IdempotentExecutionStream(underlying)` and stores the wrapper in `entry.stream`. Also reach into the constructed `LocalResultSet` (the one that goes into `activeQueries`) and substitute its internal stream reference with the same wrapper instance, so `LocalResultSet.close()` reaches the wrapper, not the raw underlying. (Implementation detail — Track 3 owns the substitution mechanism; if `LocalResultSet`'s stream field is final, the alternative is to construct the LocalResultSet around the wrapper from the start.)
3. Add `exhausted` flag to `CachedEntry` (defaults false). Stream-pull code flips it when `stream.hasNext(ctx) == false`.
4. Make `CachedEntry.close()` idempotent: null-guard `stream`, `plan`, `ctx`; flip `exhausted=true`; second call is a no-op early-return.
5. Implement view fall-through in `CachedResultSetView.next()`: when position outruns `entry.results.size()`, check `entry.exhausted`. If exhausted → no more data from cache; if not → pull from stream, append, return.
6. Stream-lifecycle tests (T3 set):
   - T3a: two consumers race to next(). First pulls record A; second sees A in `entry.results[0]`; second pulls record B; first sees B in `entry.results[1]`. Both end at same final state.
   - T3b: consumer drops view mid-iteration without closing. Cache retains the entry; new `query()` constructs a new view that continues from current position.
   - T3c: tx end while view is mid-iteration → `cache.clear()` closes the stream → next `view.next()` throws (acceptable for shutdown).
   - T3d (I3): pause stream; LRU-evict the entry (force `removeEldestEntry` via small `maxEntries`); assert wrapper's underlying stream observed exactly one close.
   - T3e (I6 single-caller): call `entry.close()` twice → wrapper sees exactly one underlying close, no exception.
   - T3f (I6 cross-caller — KEY TEST): construct an entry whose underlying stream is a non-idempotent mock (throws on second close); trigger BOTH `closeActiveQueries()` (which closes the wrapped LocalResultSet stream) AND `cache.clear()` (which closes the same wrapped stream via entry.close); assert the underlying is closed exactly once and no exception propagates. This is the scenario the wrapper exists to defend against.

**Invariants to preserve.** Stream lifetime ≤ entry lifetime (I3). After tx end, stream is closed; no leaked resources. View `next()` works correctly when `entry.results.size()` < total query result count (resumes from stream).

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/IdempotentExecutionStream.java` (new — wrapper)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (close idempotency, exhausted flag, stores the wrapper not the raw stream)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (fall-through to stream)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (cache-put path constructs the wrapper, threads it into both `entry.stream` and the LocalResultSet's internal stream slot)

**Out-of-scope files.**
- `DatabaseSessionEmbedded.activeQueries` — cache deliberately does NOT register in this map; cache holds its own reference.
- `DeltaBuilder` — Track 4.

**Inter-track dependencies.**
- Depends on: Track 2 (read path + view).
- Unblocks: Track 4 (delta build needs the stream to be resumable when the view pulls past cached prefix).

**Library / function signatures.**
- `IdempotentExecutionStream(ExecutionStream underlying)` — wrapper constructor.
- `IdempotentExecutionStream.close(CommandContext)` — idempotent by construction.
- `CachedEntry.close()` — idempotent, no-throw.
