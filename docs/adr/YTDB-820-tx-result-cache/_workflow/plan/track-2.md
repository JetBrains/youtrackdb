# Track 2: Read path — cache key, lookup, population, `CachedResultSetView`

## Purpose / Big Picture
After this track, an enabled cache turns the second-and-later `query()` calls with the same parsed AST + parameters into in-memory replays of the first call's results — within one consumer's lifetime, without pause/resume across queries yet.

Wire the cache into `DatabaseSessionEmbedded.query()` and the idempotent branch of `executeInternal()`. Build the cache key from the parsed AST + normalized parameters; on miss, execute normally and wrap the result in a `CachedResultSetView` that incrementally populates the entry as the consumer iterates; on hit, return a view over the existing entry. No dirty-merge, no resume from paused stream across queries yet — only the populate-and-replay path. The track fully delivers the "happy path" cache hit for simple repeat-query scenarios.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

## Outcomes & Retrospective

## Context and Orientation

The three `query()` overloads at `DatabaseSessionEmbedded.java:617`, `:648`, `:652` all parse + check `isIdempotent()` + execute. They converge on `statement.execute(...)` + `queryStartedLifecycle(original)`. The cache lookup must happen after parse (so we have the AST) and pass a **narrower** type check than `isIdempotent()`: only `SQLSelectStatement` and `SQLMatchStatement` get cached (per D3 — `SQLProfileStatement`, `SQLExplainStatement`, idempotent `SQLIfStatement` are also `isIdempotent()==true` but cache-wrong because their results contain plan/timing metadata or branch on context). The existing `query()` rejection of non-idempotent statements still happens at the `isIdempotent()` gate at line 633/673.

`executeInternal()` at line 702 branches at line 733 on `statement.isIdempotent()`: the true branch wraps in `queryStartedLifecycle`; the false branch fetches all into `InternalResultSet`, closes the source, and wraps in `LocalResultSetLifecycleDecorator`. The true branch is the same shape as `query()` — same cache wiring applies. The false branch (DML inside `execute`) calls `cache.invalidateAll()` instead (deferred to Track 5).

`SQLStatement.equals()` is already structural (`SQLSelectStatement:380`). Parameter normalization: when caller passes `Object[]`, we convert to a positional `LinkedHashMap<Integer,Object>`; when caller passes `Map`, we wrap it (read-only view) plus defensive-copy when storing in the key. The `CacheKey` record holds both and computes `hashCode` once.

`CachedResultSetView` is a `ResultSet` implementation. It needs:
- `hasNext()` — return true if either `position < entry.results.size()` or `!entry.exhausted` (Track 3 will make the second clause actually pull; this track just always materializes synchronously during the first consumer's iteration).
- `next()` — return `entry.results.get(position++)`. When `position == entry.results.size()` and `!entry.exhausted`, pull one from the underlying stream and append (Track 3 generalizes this to "pull from cached stream for subsequent queries"; this track just keeps the underlying stream private to the first consumer).
- `close()` — for now (Track 2), close = mark this view closed but DO NOT close `entry.stream` (it might be needed by future views in Track 3). Mark the entry exhausted if we hit the end.
- `getExecutionPlan()`, lifecycle listener support — delegate to existing infra where applicable.

Where the AST metadata for sharp-merge gets captured: when constructing `CachedEntry` in `query()`-miss path, we extract `fromClasses` (from `SQLSelectStatement.target`), `whereClause`, `orderBy`. Track 4 uses these; Track 2 just records them.

Concrete deliverables:
- New `CacheKey` record (or class) — `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CacheKey.java`.
- New `CachedResultSetView` class — same package.
- Filled-in methods on `QueryResultCache`: `lookup(CacheKey)`, `put(CacheKey, CachedEntry)`.
- Filled-in `CachedEntry` constructor + `add(Result)` method.
- Modified `DatabaseSessionEmbedded.query()` × 3 overloads + `executeInternal` idempotent branch (4 sites — keep DRY via a private helper).
- Tests: hit on second query, miss on first, cache disabled = no behavioral change, non-idempotent bypass.

## Plan of Work

1. Define `CacheKey` — record holding `SQLStatement stmt` and `Map<Object,Object> params`. `equals` and `hashCode` are auto-generated for record; we hand-implement to control parameter-map equality (deep equals for arrays, RID equals for identifiables). Static factory `CacheKey.of(SQLStatement, Object[] args)` and `CacheKey.of(SQLStatement, Map args)` that handle parameter normalization. Defensive copy on the static-factory side so caller mutation can't corrupt the key.
2. Promote `QueryResultCache` internal map from `Map<Object, CachedEntry>` to `Map<CacheKey, CachedEntry>`. Implement `lookup(CacheKey)` and `put(CacheKey, CachedEntry)`. The LRU policy lives in the underlying `LinkedHashMap` (access-order, with `removeEldestEntry` evicting at `maxEntries`). Eviction calls `entry.close()` so any streams Track 3 adds are cleaned up.
3. Implement `CachedResultSetView` — see Context above. Two pull-modes: (a) cached read (`position < entry.results.size()`), (b) stream read + append (`position == size && !exhausted`). Lifecycle: close view = mark this view closed; do NOT close entry stream (deferred to Track 3 for cross-query resume). Implement `BasicResultSet`/`ResultSet` contract including `forEachRemaining`, `tryAdvance`, `getExecutionPlan` (delegate to `entry.plan`).
4. Wire cache into `query()` overloads. Extract a private helper `executeOrCacheLookup(SQLStatement, Map paramsNormalized, Object[] argsRaw)` returning a `ResultSet`. The helper does: cache enabled? → cacheable-type? (`stmt instanceof SQLSelectStatement || stmt instanceof SQLMatchStatement`) → parse params → CacheKey → lookup → if hit, return new `CachedResultSetView(entry, this)`; if miss, run real `statement.execute(...)`, allocate new `CachedEntry` with the live stream + AST metadata, `cache.put(key, entry)`, return `new CachedResultSetView(entry, this)`. Statements that are idempotent but not cacheable-type fall through to the existing `statement.execute(...)` + `queryStartedLifecycle` path. Call this helper from all three `query()` overloads (lines 617, 648, 652) — replace direct `statement.execute(...)` with the helper. Same helper invoked from `executeInternal` idempotent branch (the `else` block starting at line 740; the idempotent return is at line 742) — note that this branch already handles a parsed statement parameter, so the helper signature accommodates that.
5. Behavioral tests. Cases: (a) cache disabled → identical behavior to current (no entries created, results identical); (b) cache enabled, single query → entry created, view returns expected results; (c) cache enabled, query A then query A again with same params → second call returns from cache (verified by storage spy: storage hit count = 1, not 2); (d) cache enabled, query A then query A with different params → both miss, two entries; (e) non-idempotent inside `query()` still throws (existing behavior preserved); (f) `executeInternal` non-idempotent path still works (no caching there, but no breakage); (g) `MATCH` query is cacheable (since it returns `isIdempotent()==true`).

Ordering: 1 and 3 can run in parallel-ish (separate files). 2 depends on 1. 4 depends on 1, 2, 3. 5 depends on everything.

## Concrete Steps

## Episodes

## Validation and Acceptance

- Two consecutive `query("SELECT FROM Class WHERE id=?", 42)` calls in one transaction hit storage exactly once. Verified by storage-level spy or by record-load counter.
- `query("SELECT...")` then `query("SELECT...")` with different parameters create two cache entries, both hit storage.
- Cache disabled (default) produces identical results to today's code, with no cache state mutated.
- Returned `ResultSet`s behave identically per the `BasicResultSet`/`ResultSet` contract (forEach, stream, hasNext/next, close, getExecutionPlan).
- A `MATCH` query is cached. An `INSERT` via `execute()` is not cached and (after Track 5) wipes the cache.
- `PROFILE SELECT ...`, `EXPLAIN SELECT ...`, and `IF (cond) { SELECT ... }` are NOT cached — verified by storage-spy counter on repeat issue (storage hit count = N, not 1) and by `cache.size() == 0` for these statement types.
- `SQLSelectStatement` with `noCache==TRUE` bypasses both lookup and put (Track 5 finalizes detection — Track 2 just needs to leave the hook in place).
- Tests assert `cache.size()` matches expected entry count across the scenarios.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In scope:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CacheKey.java` (NEW).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CachedResultSetView.java` (NEW).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCache.java` — flesh out `lookup`, `put`, LRU map type.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/CachedEntry.java` — constructor + `add(Result)`.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` — `query()` × 3 overloads, `executeInternal()` idempotent branch.
- Test classes under `core/src/test/java/com/jetbrains/youtrackdb/internal/core/tx/`.

**Out of scope:**
- Cross-query pause/resume of streams — Track 3.
- Dirty-merge — Track 4.
- Non-determinism detection / DML invalidation / memory bounds enforcement — Track 5 (the LRU map is in place but only entry-count bound is wired here; per-record cap is Track 5).
- `NOCACHE` hint extension semantics — Track 5 (Track 2 just leaves the gate hook so Track 5 can wire it).

**Inter-track dependencies:**
- Depends on Track 1 (cache field + lifecycle hooks).
- Track 3 extends the `CachedResultSetView` and `CachedEntry` types built here.
- Track 4 uses the AST metadata Track 2 captures into the entry.
- Track 5 invalidation hooks call `cache.invalidateAll()` which this track wires as a no-op-shaped stub if not already.

**Library / function signatures introduced:**
- `CacheKey.of(SQLStatement, Object[]) → CacheKey`, `CacheKey.of(SQLStatement, Map) → CacheKey`.
- `CachedEntry.add(Result)` — appends to results list, respecting per-entry cap (cap enforcement deferred to Track 5).
- `QueryResultCache.lookup(CacheKey) → @Nullable CachedEntry`.
- `QueryResultCache.put(CacheKey, CachedEntry) → void`.
- `CachedResultSetView(CachedEntry, DatabaseSessionEmbedded)` constructor.
