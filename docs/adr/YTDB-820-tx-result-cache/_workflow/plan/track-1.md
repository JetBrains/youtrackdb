# Track 1: Skeleton — knobs, data structures, lifecycle wiring

## Purpose / Big Picture
After this track the transaction-result-cache machinery exists and lives correctly on a transaction's lifecycle, but does no work yet.

Lay down the foundational pieces with no behavioral change: three `GlobalConfiguration` knobs (enabled flag + two memory bounds), new `QueryResultCache` and `CachedEntry` types (skeleton classes with no-op or trivial methods), a `queryResultCache` field on `FrontendTransactionImpl`, and the begin/clear lifecycle hooks. After this track the cache exists, is allocated lazily when enabled, and is correctly wiped on every tx-end path — but no `query()` reads or writes it yet.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

## Outcomes & Retrospective

## Context and Orientation

The cache field's natural home is `FrontendTransactionImpl` (core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java). The lifecycle methods of relevance:
- `beginInternal()` (line 164) — outermost begin already clears `localCache` at line 182; new cache field gets a defensive clear in the same spot.
- `clearUnfinishedChanges()` (line 998) — single sink for commit, rollback, close. Currently clears `recordOperations`, `recordsInTransaction`, `indexEntries`, `recordIndexOperations`, `userData`. New cache field gets a `.clear()` call here.
- `addRecordOperation()` (line 510) and `executeInternal()` non-idempotent path (DatabaseSessionEmbedded:735) are touched by later tracks.

The knob convention is set by existing `STATEMENT_CACHE_SIZE` (GlobalConfiguration:952) and the `QUERY_*` family at lines 838–950. Hot-changeable boolean defaults to false for opt-in. Integer knobs declare with `Integer.class` and a positional default. Each knob has a free-form description that should mention the feature and the trade-off.

`QueryResultCache` is a brand-new type under `internal.core.tx` (same package as `FrontendTransactionImpl`) — keeping the cache implementation tightly coupled to its only consumer avoids a public-API surface. `CachedEntry` is package-private alongside it. Both classes start as skeletons in this track: `QueryResultCache` exposes `clear()`, `size()`, `isEnabled()` — all the lifecycle-visible API. The lookup / put / invalidate methods land in later tracks as stubs returning safe defaults (`lookup → null`, `invalidateOnMutation → no-op`, etc.).

Concrete deliverables:
- New file `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCache.java` (skeleton).
- New file `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CachedEntry.java` (skeleton).
- Modified `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java` (three new enum entries).
- Modified `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (one field + two lifecycle hooks).
- New test class for lifecycle invariants (I1, I2) — probably extending the existing `FrontendTransactionImplCoverageTest` pattern.

## Plan of Work

1. Declare three knobs in `GlobalConfiguration`: `QUERY_TX_RESULT_CACHE_ENABLED` (Boolean, default false, hot-changeable), `QUERY_TX_RESULT_CACHE_MAX_ENTRIES` (Integer, default 200, hot-changeable), `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY` (Integer, default 10000, hot-changeable). Descriptions reference the feature and the trade-off.
2. Create `QueryResultCache` skeleton: constructor takes a `FrontendTransactionImpl` reference (back-pointer for `assertOnOwningThread`), reads bounds and enabled flag from `GlobalConfiguration` at construction (snapshot — the cache instance's bounds don't change after tx start). Methods: `clear()` (drops internal state, closes any held streams when those exist in later tracks; **idempotent** — second invocation no-ops, see I6), `size()` (entry count), `isEnabled()` (the snapshot). Internal state at this point: a `LinkedHashMap` constructed with `new LinkedHashMap<>(16, 0.75f, /*accessOrder=*/true)` so successful `lookup` calls promote entries to head (LRU touch). Override `removeEldestEntry(Map.Entry)`: when `size() > maxEntries`, invoke `eldest.getValue().close()` and return `true`. The actual `CacheKey` type lands in Track 2; we use `Object` placeholder here, marked TODO. The LRU eviction policy is encoded but inert until entries exist.
3. Create `CachedEntry` skeleton: package-private class with the fields the later tracks need (`List<Result> results`, `ExecutionStream stream`, `boolean exhausted`, `long version` (initialized to 0; incremented by K1 merge dispatch in Track 4), plus the merge metadata). Constructor sets all fields. One method now: `close()` — **idempotent**; if `stream != null`, call `stream.close(ctx)`, then null out `stream`, `plan`, `ctx` so second invocation early-returns. Used by `QueryResultCache.clear()` and by LRU eviction.
4. Add `queryResultCache` field to `FrontendTransactionImpl`: declared `@Nullable private QueryResultCache queryResultCache`. Lazy accessor `getQueryResultCache()` — allocates on first call when enabled, returns null when disabled (keeping the zero-cost-when-off contract). Hook `clear()` in `clearUnfinishedChanges()` — guarded by null-check, since the field may never have been allocated for a read-only transaction with no queries. Defensive `clear()` in `beginInternal()` outermost block (after `localCache.clear()` at line 182).
5. Lifecycle tests. Five scenarios: (a) commit path — open tx, allocate cache via `getQueryResultCache()` (returns non-null when enabled, null when disabled), commit, assert cache cleared; (b) rollback path — same, but rollback instead of commit; (c) exception-during-iterate — induce exception inside a no-op cache operation, assert cleanup still fires; (d) **idempotent clear (I6)** — call `cache.clear()` twice in a row on the same cache instance; assert no exception and `size() == 0` after each call; call `entry.close()` twice on the same entry (skeleton-state entry has null stream, so this is a degenerate test in Track 1; Track 3 extends it with a populated entry); (e) threading test — another thread tries to invoke a cache MUTATION path through the tx (e.g., via `addRecordOperation` or a mock `lookup`) → `assertOnOwningThread` assertion failure. NOTE: tx-end `clear()` is explicitly excluded from the threading assertion (covered by I6 not I2).

Ordering: knobs and skeletons (steps 1-3) can land in any order; field/hooks (step 4) depends on the skeletons; tests (step 5) depend on everything. Step 1 alone is a Spotless concern — three enum entries.

Invariants enforced by this track: I1 (cleanup on every tx-end path), I2 (owning thread on mutation paths), I6 (idempotent clear/close).

## Concrete Steps

## Episodes

## Validation and Acceptance

- Toggling `QUERY_TX_RESULT_CACHE_ENABLED` true vs false controls whether `getQueryResultCache()` returns non-null on a fresh transaction.
- `clearUnfinishedChanges()` calls `queryResultCache.clear()` on every commit, rollback, and exception-path close. Verified by mock or by direct field-state inspection.
- `beginInternal()` on a nested begin (`txStartCounter > 0`) does NOT re-clear the cache. This mirrors the existing `localCache.clear()` semantics.
- A cross-thread access attempt on the cache field fails fast via `assertOnOwningThread`.
- Zero behavioral change when the knob is disabled (default state) — existing test suite passes unchanged.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In scope:**
- `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java` — three new enum entries near the existing `QUERY_*` block (around line 940).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCache.java` (NEW).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CachedEntry.java` (NEW).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` — one field, lazy accessor, two hook sites.
- Test class under `core/src/test/java/com/jetbrains/youtrackdb/internal/core/tx/` — new file or extension of existing `FrontendTransactionImplCoverageTest`.

**Out of scope:**
- `DatabaseSessionEmbedded` — not touched in this track.
- `FrontendTransactionNoTx` — not adding the field; auto-commit path is non-goal.
- Public `FrontendTransaction` interface — cache stays internal to `FrontendTransactionImpl`.
- `CacheKey` type — placeholder `Object` for now; defined in Track 2.

**Inter-track dependencies:**
- This track is the prerequisite for Tracks 2, 4, 5 (all need the field + lifecycle hooks).
- No dependencies on prior tracks (first in the chain).

**Library / function signatures introduced:**
- `QueryResultCache(FrontendTransactionImpl owner)` constructor.
- `void QueryResultCache.clear()`.
- `int QueryResultCache.size()`.
- `boolean QueryResultCache.isEnabled()`.
- `@Nullable QueryResultCache FrontendTransactionImpl.getQueryResultCache()`.
- `void CachedEntry.close()`.
