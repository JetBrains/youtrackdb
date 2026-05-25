# Track 1: Skeleton — knobs, data structures, lifecycle wiring

## Purpose / Big Picture

BLUF: After this track, the cache exists, is allocated lazily when enabled, and is correctly wiped on every tx-end path — but no `query()` reads or writes it yet.

Lay down the foundational pieces with no behavioral change: three `GlobalConfiguration` knobs, new `QueryResultCache`, `CachedEntry`, `TxDeltaCursor` types (skeletons or no-op), `queryResultCache` field on `FrontendTransactionImpl`, and the begin/clear lifecycle hooks.

## Context and Orientation

**Codebase state at track start.** Existing relevant code:
- `FrontendTransactionImpl.java` — owns the `recordOperations: HashMap<RecordIdInternal, RecordOperation>` (line 83), `assertOnOwningThread` guard (line 133), `beginInternal` (line 164), `clearUnfinishedChanges` (called from `clear()` → `close()` / `rollbackInternal()`).
- `GlobalConfiguration` — registry for the three new knobs (`youtrackdb.query.txResultCache.*`).
- `localCache.clear()` at line 182 is the model for the defensive clear in `beginInternal`.

**Concrete deliverables.**
- `QueryResultCache` class (new, package `internal.core.tx` or sub-package) with `LinkedHashMap<CacheKey, CachedEntry>` field, `lookup`/`put`/`invalidateAll`/`clear` API stubs.
- `CachedEntry` class skeleton with `shape`, `results`, `cachedRids`, `stream`, AST-metadata fields, `close()` idempotent no-op.
- `TxDeltaCursor` class skeleton with `skipSet`, `injectList`, `peek`/`pop`/`shouldSkip` stubs.
- `CacheableShape` enum.
- `CacheKey` class skeleton (statement + params holder; equals/hashCode stubs).
- `FrontendTransactionImpl.queryResultCache` field (nullable; lazy-allocated via `getQueryResultCache()`).
- `beginInternal()` defensive `queryResultCache.clear()` if non-null.
- `clearUnfinishedChanges()` calls `queryResultCache.clear()` if non-null.
- Three knobs in `GlobalConfiguration`: `QUERY_TX_RESULT_CACHE_ENABLED` (Boolean, default false), `QUERY_TX_RESULT_CACHE_MAX_ENTRIES` (Integer, default 200), `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY` (Integer, default 10000). All hot-changeable per project convention.

## Plan of Work

1. Add three knobs to `GlobalConfiguration` with conservative defaults. Disabled by default → zero behavioral change for existing deployments.
2. Skeleton classes in order: `CacheableShape` enum → `CacheKey` record → `TxDeltaCursor` → `CachedEntry` → `QueryResultCache`. Each with method stubs (no logic yet beyond null-guards and idempotent close).
3. Wire `queryResultCache` field onto `FrontendTransactionImpl`: nullable, lazily allocated by `getQueryResultCache()` only when `QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean()` is true. Same pattern as other tx-scoped fields.
4. Hook lifecycle: `beginInternal()` calls defensive clear; `clearUnfinishedChanges()` calls final clear. Both null-guard.
5. Lifecycle invariant tests (T1 set):
   - I1: cache.size==0 after commit, rollback, exception-during-iterate.
   - I2: cache mutation paths run only on owning thread.
   - I6: `cache.clear()` idempotent (call twice, assert no exception); `CachedEntry.close()` idempotent.
   - I8: schema DDL during active tx throws; cache state unchanged.

**Invariants to preserve.** No behavioral change with cache disabled. With cache enabled, no `query()` path reads or writes it yet (that comes in Track 2). Tx lifecycle (begin / commit / rollback / close) unchanged.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/config/GlobalConfiguration.java` (knob entries)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (field + lifecycle hooks)
- New files under `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/` (or similar package): `QueryResultCache.java`, `CachedEntry.java`, `CacheKey.java`, `CacheableShape.java`, `TxDeltaCursor.java`

**Out-of-scope files.**
- `DatabaseSessionEmbedded.java` — Track 2 hooks `query()` and `executeInternal()` here.
- Any AST node or `SQLStatement` subclass — Track 2 introduces `CacheKey.equals` / `ShapeClassifier`.

**Inter-track dependencies.**
- Unblocks: Track 2 (read path needs the skeleton types).
- Depends on: nothing — this is the seed track.

**Library / function signatures.**
- `QueryResultCache.lookup(CacheKey) → CachedEntry` (returns null on miss).
- `QueryResultCache.put(CacheKey, CachedEntry) → void`.
- `QueryResultCache.invalidateAll() → void`.
- `QueryResultCache.clear() → void` (idempotent).
- `CachedEntry.close() → void` (idempotent).
- `FrontendTransactionImpl.getQueryResultCache() → @Nullable QueryResultCache` (lazy alloc).
