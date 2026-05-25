# Track 3: Pause/resume — shared stream + per-view position

## Purpose / Big Picture

BLUF: After this track, a second `query()` for the same key in the same tx can continue iterating a partially-consumed stream from where the first view left off. Cache enables true incremental population across multiple consumers.

Extend `CachedEntry` to hold the live `ExecutionStream` past the first consumer's iteration, and extend `CachedResultSetView` to fall through to it when the consumer outruns the cached list. Multiple `query()` calls within one tx return independent views sharing the same entry; the first view to pull a particular row is the one that pays the storage cost. Close the stream when exhausted or evicted.

## Context and Orientation

**Codebase state at track start.** After Track 2:
- Cache populates on miss via consumer-driven stream pull. Each `view.next()` pulls one record from `entry.stream`, appends to `entry.results` + `entry.cachedRids`, returns to caller.
- `entry.stream` field exists but in Track 2 was either always-active (no close) or always-eager (force-exhaust on miss). Track 3 makes it paused-and-resumable.

Existing relevant code:
- `ExecutionStream` interface — `next(ctx)`, `hasNext(ctx)`, `close(ctx)`. The latter must be idempotent (this track adds a regression test).
- `LocalResultSet` / `activeQueries` (`DatabaseSessionEmbedded.java:256`) — weak-value map; cache holds its own strong reference to the bare `ExecutionStream`.

**Concrete deliverables.**
- `CachedEntry.exhausted: boolean` flips to true when stream reports `hasNext == false`.
- `CachedEntry.close()` closes `stream` if non-null and exhausted=false; sets `stream=null, plan=null, ctx=null`; sets `exhausted=true` for idempotency. Calling twice is a no-op.
- `CachedResultSetView.next()` (now functional, not stubbed): if `position >= entry.results.size()` and `!entry.exhausted`, pull one from `entry.stream`, append to `entry.results` and `entry.cachedRids`, return.
- `ExecutionStream.close(ctx)` idempotent regression test (Track 3 verifies the existing implementation; if not idempotent, add the no-op guard at top of `close()`).

## Plan of Work

1. Add `exhausted` flag to `CachedEntry` (defaults false). Stream-pull code flips it when `stream.hasNext(ctx) == false`.
2. Make `CachedEntry.close()` idempotent: null-guard `stream`, `plan`, `ctx`; flip `exhausted=true`; second call is a no-op early-return.
3. Implement view fall-through in `CachedResultSetView.next()`: when position outruns `entry.results.size()`, check `entry.exhausted`. If exhausted → no more data from cache; if not → pull from stream, append, return.
4. Regression test: call `ExecutionStream.close(ctx)` twice on a populated stream; assert no exception. If the existing implementation throws on double-close, add a guard at the top.
5. Stream-lifecycle tests (T3 set):
   - T3a: two consumers race to next(). First pulls record A; second sees A in `entry.results[0]`; second pulls record B; first sees B in `entry.results[1]`. Both end at same final state.
   - T3b: consumer drops view mid-iteration without closing. Cache retains the entry; new `query()` constructs a new view that continues from current position.
   - T3c: tx end while view is mid-iteration → `cache.clear()` closes the stream → next `view.next()` throws (acceptable for shutdown).
   - T3d (I3): pause stream; LRU-evict the entry (force `removeEldestEntry` via small `maxEntries`); assert `stream.isClosed()`.
   - T3e: stream `close(ctx)` called twice → no exception (I6).

**Invariants to preserve.** Stream lifetime ≤ entry lifetime (I3). After tx end, stream is closed; no leaked resources. View `next()` works correctly when `entry.results.size()` < total query result count (resumes from stream).

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (close idempotency, exhausted flag)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (fall-through to stream)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/ExecutionStream.java` (idempotency guard if needed)

**Out-of-scope files.**
- `DatabaseSessionEmbedded.activeQueries` — cache deliberately does NOT register in this map; cache holds its own reference.
- `DeltaBuilder` — Track 4.

**Inter-track dependencies.**
- Depends on: Track 2 (read path + view).
- Unblocks: Track 4 (delta build needs the stream to be resumable when the view pulls past cached prefix).

**Library / function signatures.**
- `CachedEntry.close()` — idempotent, no-throw.
- `ExecutionStream.close(CommandContext)` — idempotent (asserted by test).
