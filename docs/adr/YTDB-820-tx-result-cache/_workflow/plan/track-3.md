<!-- workflow-sha: 8995acfc3b0c50453595911342427c60742617b4 -->
# Track 3: Hardening + observability

> Combines former Tracks 7, 8 (hardening, observability + JMH), consolidated for footprint sizing. Test IDs are unchanged from the former tracks.

## Purpose / Big Picture

BLUF: After this track, the cache is production-safe and observable. Hardening adds the non-determinism denylist, `TRUNCATE CLASS` full-wipe invalidation, memory caps, and the deterministic-ORDER-BY admission gate; observability adds `QueryCacheMetrics`, JMH benchmarks against the cache-disabled baseline, and the D13 Hub-replay merge gate. The track is cross-cutting: it layers safety nets, operator knobs, and the pre-merge validation gate on top of the feature work delivered by Tracks 1 and 2.

## Size justification

This track's in-scope footprint (~8 files) sits below the ~12-file fold floor. It is kept separate by design: hardening (non-determinism denylist, DML invalidation, memory bounds) and observability (metrics, JMH, Hub-replay gate) are cross-cutting safety-net and validation concerns that touch files owned by Tracks 1 and 2. Folding them into Track 2 would push that track over the ~20-25 ceiling and mix production safety/validation work with feature work; folding into Track 1 would create a forward dependency on Track 2's MATCH/aggregate shapes. Keeping it a distinct, last-in-order track preserves reviewability and a clean dependency edge (depends on Tracks 1 and 2). Documented per the two-sided track-sizing rule (conventions.md §Scope indicators / planning.md §Track descriptions): an out-of-bounds track with a written justification passes planning autonomously.

## Stage 1 — Hardening (non-determinism, DML invalidation, memory bound)

### Purpose / Big Picture

BLUF: After this stage, the cache is production-safe: non-deterministic queries bypass, `TRUNCATE CLASS` invalidates all, memory caps enforced, deterministic-ORDER-BY admission gate active, schema-DDL canary assert in place.

Production-readiness for correctness: AST denylist for non-deterministic functions/variables, `NOCACHE` hint extension, full-wipe on `SQLTruncateClassStatement` via `cache.invalidateAll()`, LRU enforcement at `maxEntries`, per-entry overflow handling at `maxRecordsPerEntry`, deterministic-ORDER-BY admission gate using `NonDeterministicQueryDetector` on each `SQLOrderByItem.modifier`. Java assert in the cache hook that fires if schema DDL reaches it (D3 canary).

### Context and Orientation

**Codebase state at stage start.** After Tracks 1-2: all shapes (RECORD, AGGREGATE, MATCH Etap A) work with delta build. This stage adds the safety nets and operator-facing knobs.

Existing relevant code:
- `SQLSelectStatement.noCache: Boolean` — already parses; semantics extended here.
- `SQLTruncateClassStatement` — bulk-bypass target.
- `SQLOrderByItem.modifier: SQLModifier` — chain target for deterministic check.
- Grammar source: `core/src/main/grammar/YouTrackDBSql.jjt:3053` — `SQLOrderBy OrderBy()` production. Do not edit generated parser at `core/.../sql/parser/`.
- `LinkedHashMap.removeEldestEntry(Map.Entry)` — LRU eviction hook target.

**Concrete deliverables.**
- `NonDeterministicQueryDetector.contains(SQLStatement) → boolean` — denylist AST walker for `sysdate`, zero-arg `date()`, `uuid`, `random`, `eval`, `currentTimeMillis`, `nanoTime` and context vars `$now`, `$current`, `$currentMatch`, `$matched`, `$thread`, `$parent`, `$depth`. The context-variable list covers every per-row / per-MATCH-candidate binding in `CommandContext` (`VAR_CURRENT`, `VAR_CURRENT_MATCH`, `VAR_MATCHED`, `VAR_DEPTH`) — bypass is conservative because `DeltaBuilder` does not replicate the executor's upstream-step `ctx.setSystemVariable(VAR_CURRENT, record)` chain. Walker scope: full AST traversal — top-level WHERE clauses, ORDER BY items + modifier chains, RETURN expressions, MATCH per-alias WHEREs, MATCH WHILE conditions, all nested expression positions.
- `NonDeterministicQueryDetector.contains(SQLOrderByItem) → boolean` — same walker scoped to a single ORDER BY item's modifier chain.
- Cache lookup gate — `DatabaseSessionEmbedded.query()` checks: idempotent + SELECT/MATCH type + `!NonDeterministicQueryDetector.contains(stmt)` + `!stmt.noCache`. Fail any → bypass cache (no lookup, no put).
- DML invalidation hook in `DatabaseSessionEmbedded.executeInternal()` — for `SQLTruncateClassStatement`, call `cache.invalidateAll()` before delegation to underlying handler.
- Schema-DDL canary `assert` (D3 risk mitigation) — in the cache hook, after parsing: `assert !(stmt instanceof SQLCreateClassStatement || …) || !tx.isActive()` with msg "Schema DDL reached cache hook mid-tx — I8 violation".
- LRU eviction — `QueryResultCache.entries` constructed as `LinkedHashMap` with `accessOrder=true`. Override `removeEldestEntry` to evict + close oldest when `size() > maxEntries`. Snapshot copy for iteration safety (`new ArrayList<>(entries.values())` before per-entry handlers in `clear()` / `invalidateAll()`).
- Per-entry overflow (L7 fix) — when `entry.results.size() == maxRecordsPerEntry`, the view continues to deliver stream results to its caller but stops appending to `entry.results`. The entry is **removed from `entries` map atomically** with the overflow detection, and the key is added to a per-tx `nonCacheableKeys: Set<CacheKey>` on `QueryResultCache`. Subsequent `lookup(key)` short-circuits if `nonCacheableKeys.contains(key)`, skipping cache entirely (no LRU touch, no eviction churn from repeated re-populate-then-overflow cycles).
- Deterministic-ORDER-BY admission — `ShapeClassifier.classify` (extended) rejects (returns NONE) any RECORD-shape query whose ORDER BY items contain a non-deterministic modifier chain. Plain identifiers admitted unconditionally.

### Plan of Work

1. `NonDeterministicQueryDetector` — implement AST walker with explicit denylist. Helper `isNonDeterministicFunction(SQLFunctionCall)` checks function name + arity (for `date()`-vs-`date(arg)` discrimination).
2. Wire `noCache` semantic extension — already a parsed Boolean; `containsNonDeterministicReference(stmt)` includes the check.
3. Cache lookup gate in `DatabaseSessionEmbedded.query(...)` — unify with Track 1's type check (Stage 2, Read path).
4. DML invalidation hook — `executeInternal` branch for `SQLTruncateClassStatement` calls `cache.invalidateAll()`. Other DML (`SQLInsertStatement`, `SQLUpdateStatement`, `SQLDeleteStatement`) flows through `addRecordOperation` per row; cache picks up via delta build on next query.
5. Schema-DDL canary assert — in cache hook, after parsing, before lookup: `assert !(stmt instanceof <schema-DDL types>) || !isInTx() : "I8 violation"`. Picks up `SQLCreateClassStatement`, `SQLDropClassStatement`, `SQLAlterClassStatement`, `SQLCreatePropertyStatement`, `SQLDropPropertyStatement`, `SQLAlterPropertyStatement`, `SQLCreateIndexStatement`, `SQLDropIndexStatement`.
6. LRU eviction — `QueryResultCache.entries` as `LinkedHashMap<>(maxEntries + 1, 0.75f, true)`. Override `removeEldestEntry` to evict + close. Snapshot copy in `clear()` / `invalidateAll()`.
7. Per-entry overflow — when stream-pull-append would exceed `maxRecordsPerEntry`: stop appending, remove entry from `entries` map, add key to `nonCacheableKeys`. Subsequent `lookup(key)` checks `nonCacheableKeys` first and short-circuits to "skip cache" if present. The currently-iterating view continues delivering uncached results to its consumer (it holds a direct stream reference).
8. **Re-entrancy guard (L9 fix + SO5 scope + T7 ordering)**: `QueryResultCache` tracks a re-entrancy counter `cacheCodeDepth: int` (per-tx, single-threaded — counter rather than boolean to handle arbitrarily-nested cache-internal calls). At the START of every cache-mutating code path (`lookup`, `put`, `invalidateAll`, the stream-pull-append loop inside `view.next()`, `DeltaBuilder.buildForRecord`/`buildForAggregate`, **the aggregate eager-drive `plan.start(ctx).next(ctx)` between cache.lookup and cache.put on AGGREGATE_* miss** — per architectural-review finding, this guards UDFs invoked during upstream WHERE eval during populate): **increment FIRST, then check** — if the post-increment value is `> 1`, this call is re-entrant; `cache.lookup` returns null (cache miss) and the caller falls through to `statement.execute(...)` for an uncached execution. Increment/decrement live inside try/finally so the counter is restored on every exit path including exceptions. Catches L9 hazard regardless of which cache-internal code path fires the re-entrant UDF query. Tests T7m (re-entrancy) and T7n (aggregate eager-drive with UDF in upstream WHERE — verify inner lookup bypass, no infinite recursion).
9. Deterministic-ORDER-BY gate in `ShapeClassifier` — for each `SQLOrderByItem`, if `modifier` chain contains non-deterministic reference, return NONE.
10. Test matrix (T7 set):
   - T7a: `SELECT sysdate() FROM …` bypasses cache; no entry created.
   - T7b: `SELECT FROM … WHERE created > sysdate() - 86400000` bypasses (sysdate in WHERE).
   - T7c: `SELECT FROM Foo NOCACHE` bypasses.
   - T7d: `SELECT random() FROM Foo` bypasses.
   - T7e: `ORDER BY lower(name)` — admitted (`lower` is deterministic).
   - T7f: `ORDER BY random()` — classify NONE.
   - T7g: `TRUNCATE CLASS Foo` — `cache.invalidateAll()` fires; cache.size==0.
   - T7h: `INSERT INTO Foo VALUES (...)` does NOT call `invalidateAll`; cache state intact (mutation flows via addRecordOperation; next query picks up via delta).
   - T7i: LRU cap — populate 201 distinct keys with `maxEntries=200`; assert eldest evicted, stream closed.
   - T7j (L7 overflow + nonCacheable): query returning 10001 records with `maxRecordsPerEntry=10000`; entry is removed from cache at the overflow boundary; subsequent `query()` of the same key short-circuits via `nonCacheableKeys` (no second populate, no LRU eviction churn).
   - T7m (L9 re-entrancy): register a UDF that calls `session.query("SELECT FROM Other")` inside `MyFn.score(rec)`; outer query `SELECT FROM Item WHERE MyFn.score(this) > 0.5`; verify the nested lookup short-circuits (no LRU touch, no put), the outer view continues iterating without its paused stream being closed by inner LRU eviction.
   - T7k (I5): aggregate test — any non-deterministic query never creates an entry.
   - T7l: schema-DDL canary — invoke `CREATE CLASS X` via session; assertion in cache hook does not fire because the upstream `SchemaShared.saveInternal` throws first.
   - T7o: per-row context bypass — `SELECT FROM Issue WHERE state = $current.workflow.openState` bypasses cache; no entry created. Repeat for `ORDER BY $current.x`, `SELECT $current.priority FROM …`. Assert detector returns true for each.
   - T7p: MATCH context bypass — `MATCH {as:u, class:User WHERE $matched.something = 'x'} RETURN u` bypasses; `MATCH {as:u, class:User WHERE $currentMatch.depth < 3} RETURN u` bypasses. Assert no entry created.
   - T7q: WHILE / `$depth` bypass — `MATCH {as:u, class:Node}-[edge]->{as:v, WHILE: $depth < 5} RETURN v` bypasses cache. Confirms walker scope reaches WHILE conditions, not just WHERE clauses.

**Invariants to preserve.** I5: non-deterministic / NOCACHE queries never cached. I8: schema DDL upstream guard works (canary should never fire under normal operation). Memory bound: `maxEntries × maxRecordsPerEntry` Result references ceiling.

## Stage 2 — Observability + JMH (QueryCacheMetrics, benchmark, Hub replay)

### Purpose / Big Picture

BLUF: After this stage, operators have hit/miss/delta-build-cost/eviction telemetry; JMH benchmarks cover cache-hit / cache-miss / delta-build / aggregate-replay paths against the cache-disabled baseline; D13 Hub-replay gate is implemented and produces the pre-merge artifact required for production deployment.

Operator-facing observability: new `QueryCacheMetrics` class with hit/miss/delta-build-cost/eviction counters held by `QueryResultCache`, accessible from `FrontendTransactionImpl`. JMH microbenchmark for cache-hit, cache-miss, delta-build-with-N-mutations, and aggregate-replay paths against the cache-disabled baseline. Integration tests assert counter increments. Hub replay scenario (D13 gate) replays an anonymized DNQ-emission sample and asserts ≥70% cacheable-coverage + view-output equivalence vs fresh-execution at mutation sites.

### Context and Orientation

**Codebase state at stage start.** After Tracks 1-2 and Stage 1: full cache functionality. This stage adds observability and the pre-merge validation gate.

Existing relevant code:
- `TransactionMeters` (inline record in `DatabaseSessionEmbedded`) — model for sibling `QueryCacheMetrics` accessor.
- `tests/src/test/java/.../benchmarks/` — existing JMH scaffold.
- `jmh-ldbc/` — JMH LDBC harness; reuse JMH plumbing patterns.

**Concrete deliverables.**
- `QueryCacheMetrics` class — counters: `hits`, `misses`, `evictions`, `deltaBuildTimeNanos`, `deltaBuildCount`, `spliceFailures` (L6 fallback — unexpected step shape on aggregate side-tap plan walk in Track 2 Stage 1, Aggregate), `k0NoneHits` (D18 — K0_NONE entry served as cache hit because `tx.mutationVersion` matched populate stamp), `k0NoneInvalidations` (D18 — K0_NONE entry invalidated at lookup because `tx.mutationVersion` diverged), `k0NoneShortCircuits` (D18 — `nonCacheableKeys` short-circuited a previously-churned K0_NONE key, no lookup attempt), `aggregateCountDistinctHits` (D20 — AGGREGATE_COUNT_DISTINCT entry served via delta-build), `aggregateCountDistinctInvalidations` (D20 — AGGREGATE_COUNT_DISTINCT entry overflowed `maxRecordsPerEntry` and routed through the L7 path). Per-tx (held on `QueryResultCache`).
- `FrontendTransactionImpl.getQueryCacheMetrics() → QueryCacheMetrics` accessor.
- Counter increments at:
  - `cache.lookup` hit / miss.
  - `removeEldestEntry` (eviction).
  - `DeltaBuilder.buildFor*` — timing-wrapped to track p99 cost.
- JMH benchmarks under `tests/src/test/java/.../benchmarks/cache/`:
  - `CacheHitBenchmark` — same query 1000× in one tx; measure per-call cost.
  - `CacheMissBenchmark` — distinct queries; measure cache overhead vs disabled-cache.
  - `DeltaBuildBenchmark` — vary N (tx-mutation count) and p (relevant subset); measure delta-build cost.
  - `AggregateReplayBenchmark` — COUNT/SUM/MIN with various delta sizes.
- Hub replay scenario (D13) — `HubReplayTest`:
  - Load anonymized DNQ-emission sample (sourced from Hub staging).
  - Run all queries in single tx against a test database matching Hub schema.
  - Assert: ≥70% of repeat-shape queries classify as `!= NONE`.
  - Assert: view output matches fresh-execution at every mutation site.
  - Measure: per-query delta-build cost histogram, WHERE re-evaluation top-RIDs, MIN/MAX extremum-churn frequency (informs D14 sorted-value index v1.1/v2 decision), multi-alias-MATCH-CREATED frequency (informs whether the separate Etap-B ADR is high-priority), paginated-workload share (informs whether delta-aware SKIP/LIMIT caching is a v2 priority), K0_NONE statistics (D18: fraction of cached queries classifying as K0_NONE, `k0NoneHits` / `k0NoneInvalidations` ratio per tx, K0_NONE invalidation cause distribution per mutation class — informs class-scoped K0 invalidation v2 priority and the `k0NoneInvalidationThreshold` default; SKIP/LIMIT shares of K0_NONE entries are explicit telemetry under Opcja B), LET-subquery and $matched-binding frequency in K0_NONE entries (informs sub-statement-caching separate-ADR priority — if either appears predominantly, the separate ADR moves up).
  - Outputs committed to `_workflow/` as `hub-replay-results.md`.

### Plan of Work

1. `QueryCacheMetrics` — record class with atomic counters (single-threaded but record-style for accessor clarity).
2. Wire counter increments at cache callsites — minimal overhead per Bash null-checking.
3. JMH scaffolding under `tests/src/test/java/.../benchmarks/cache/` — reuse `jmh-ldbc/` patterns. Maven profile activation per existing convention.
4. `CacheHitBenchmark`, `CacheMissBenchmark`, `DeltaBuildBenchmark`, `AggregateReplayBenchmark`.
5. Integration tests asserting counter increments at all four callsites (hit/miss/eviction/delta-build).
6. Hub replay scenario — wire to load anonymized capture, run replay, assert D13 pass criteria.
7. Tests (T8 set):
   - T8a-d: counter assertions for hit, miss, eviction, delta-build invocation.
   - T8e: JMH baseline vs cached on a synthetic 1000-query-per-tx workload — assert ≥5× speedup median on hit path.
   - T8f: Hub-replay D13 gate — ≥70% cacheable coverage; view-output equivalence asserted.

**Invariants to preserve.** Cache behavior unchanged by adding metrics (counters are observers, not controllers). D13 pass before merge — failures route to follow-up plan or PR re-design.

## Interfaces and Dependencies

**In-scope files (union).**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/NonDeterministicQueryDetector.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryResultCache.java` (LRU eviction + counter wiring)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (overflow flag)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (overflow handling in stream-pull)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (deterministic-ORDER-BY admission)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lookup gate, DML invalidation hook, schema-DDL assert)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryCacheMetrics.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (accessor)
- `tests/src/test/java/.../benchmarks/cache/*Benchmark.java` (new)
- `core/src/test/java/.../tx/cache/HubReplayTest.java` (new)
- `docs/adr/YTDB-820-tx-result-cache/_workflow/hub-replay-results.md` (new, committed artifact)

11 distinct in-scope files.

**Out-of-scope files.**
- `SQLFunction` SPI — no `isDeterministic` flag added; denylist is centralized.
- Generated parser at `core/.../sql/parser/*` — never edited (project convention).
- Anything functional in cache code beyond Stage 1 — all cache behavior already shipped in Tracks 1-2 and this track's Stage 1.
- `jmh-ldbc/` — separate LDBC benchmark, not reused directly.

**Depends on:** Track 1, Track 2.

**Unblocks:** nothing — final track. (D13 Hub-replay pass is the pre-merge gate for Phase 4 / final artifacts; failures route to follow-up plan or PR re-design.)

**Library / function signatures.**
- `NonDeterministicQueryDetector.contains(SQLStatement) → boolean`.
- `NonDeterministicQueryDetector.contains(SQLOrderByItem) → boolean`.
- `QueryResultCache.entries`: `LinkedHashMap<CacheKey, CachedEntry>` with accessOrder + LRU eviction override.
- `CachedEntry.overflow: boolean`.
- `QueryCacheMetrics.recordHit() → void`, `recordMiss()`, `recordEviction()`, `recordDeltaBuild(long nanos)`.
- `QueryCacheMetrics.snapshot() → Map<String, Number>`.
- `FrontendTransactionImpl.getQueryCacheMetrics() → QueryCacheMetrics`.
