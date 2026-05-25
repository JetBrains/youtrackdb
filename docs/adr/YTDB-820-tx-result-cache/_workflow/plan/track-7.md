# Track 7: Hardening — non-determinism, DML invalidation, memory bound

## Purpose / Big Picture

BLUF: After this track, the cache is production-safe: non-deterministic queries bypass, `TRUNCATE CLASS` invalidates all, memory caps enforced, deterministic-ORDER-BY admission gate active, schema-DDL canary assert in place.

Production-readiness for correctness: AST denylist for non-deterministic functions/variables, `NOCACHE` hint extension, full-wipe on `SQLTruncateClassStatement` via `cache.invalidateAll()`, LRU enforcement at `maxEntries`, per-entry overflow handling at `maxRecordsPerEntry`, deterministic-ORDER-BY admission gate using `NonDeterministicQueryDetector` on each `SQLOrderByItem.modifier`. Java assert in the cache hook that fires if schema DDL reaches it (D3 canary).

## Context and Orientation

**Codebase state at track start.** After Tracks 1-6: all shapes (RECORD, AGGREGATE, MATCH Etap A) work with delta build. This track adds the safety nets and operator-facing knobs.

Existing relevant code:
- `SQLSelectStatement.noCache: Boolean` — already parses; semantics extended here.
- `SQLTruncateClassStatement` — bulk-bypass target.
- `SQLOrderByItem.modifier: SQLModifier` — chain target for deterministic check.
- Grammar source: `core/src/main/grammar/YouTrackDBSql.jjt:3053` — `SQLOrderBy OrderBy()` production. Do not edit generated parser at `core/.../sql/parser/`.
- `LinkedHashMap.removeEldestEntry(Map.Entry)` — LRU eviction hook target.

**Concrete deliverables.**
- `NonDeterministicQueryDetector.contains(SQLStatement) → boolean` — denylist AST walker for `sysdate`, zero-arg `date()`, `uuid`, `random`, `eval`, `currentTimeMillis`, `nanoTime` and context vars `$now`, `$current`, `$thread`, `$parent`, `$depth`.
- `NonDeterministicQueryDetector.contains(SQLOrderByItem) → boolean` — same walker scoped to a single ORDER BY item's modifier chain.
- Cache lookup gate — `DatabaseSessionEmbedded.query()` checks: idempotent + SELECT/MATCH type + `!NonDeterministicQueryDetector.contains(stmt)` + `!stmt.noCache`. Fail any → bypass cache (no lookup, no put).
- DML invalidation hook in `DatabaseSessionEmbedded.executeInternal()` — for `SQLTruncateClassStatement`, call `cache.invalidateAll()` before delegation to underlying handler.
- Schema-DDL canary `assert` (D3 risk mitigation) — in the cache hook, after parsing: `assert !(stmt instanceof SQLCreateClassStatement || …) || !tx.isActive()` with msg "Schema DDL reached cache hook mid-tx — I8 violation".
- LRU eviction — `QueryResultCache.entries` constructed as `LinkedHashMap` with `accessOrder=true`. Override `removeEldestEntry` to evict + close oldest when `size() > maxEntries`. Snapshot copy for iteration safety (`new ArrayList<>(entries.values())` before per-entry handlers in `clear()` / `invalidateAll()`).
- Per-entry overflow (L7 fix) — when `entry.results.size() == maxRecordsPerEntry`, the view continues to deliver stream results to its caller but stops appending to `entry.results`. The entry is **removed from `entries` map atomically** with the overflow detection, and the key is added to a per-tx `nonCacheableKeys: Set<CacheKey>` on `QueryResultCache`. Subsequent `lookup(key)` short-circuits if `nonCacheableKeys.contains(key)`, skipping cache entirely (no LRU touch, no eviction churn from repeated re-populate-then-overflow cycles).
- Deterministic-ORDER-BY admission — `ShapeClassifier.classify` (extended) rejects (returns NONE) any RECORD-shape query whose ORDER BY items contain a non-deterministic modifier chain. Plain identifiers admitted unconditionally.

## Plan of Work

1. `NonDeterministicQueryDetector` — implement AST walker with explicit denylist. Helper `isNonDeterministicFunction(SQLFunctionCall)` checks function name + arity (for `date()`-vs-`date(arg)` discrimination).
2. Wire `noCache` semantic extension — already a parsed Boolean; `containsNonDeterministicReference(stmt)` includes the check.
3. Cache lookup gate in `DatabaseSessionEmbedded.query(...)` — unify with Track 2's type check.
4. DML invalidation hook — `executeInternal` branch for `SQLTruncateClassStatement` calls `cache.invalidateAll()`. Other DML (`SQLInsertStatement`, `SQLUpdateStatement`, `SQLDeleteStatement`) flows through `addRecordOperation` per row; cache picks up via delta build on next query.
5. Schema-DDL canary assert — in cache hook, after parsing, before lookup: `assert !(stmt instanceof <schema-DDL types>) || !isInTx() : "I8 violation"`. Picks up `SQLCreateClassStatement`, `SQLDropClassStatement`, `SQLAlterClassStatement`, `SQLCreatePropertyStatement`, `SQLDropPropertyStatement`, `SQLAlterPropertyStatement`, `SQLCreateIndexStatement`, `SQLDropIndexStatement`.
6. LRU eviction — `QueryResultCache.entries` as `LinkedHashMap<>(maxEntries + 1, 0.75f, true)`. Override `removeEldestEntry` to evict + close. Snapshot copy in `clear()` / `invalidateAll()`.
7. Per-entry overflow — when stream-pull-append would exceed `maxRecordsPerEntry`: stop appending, remove entry from `entries` map, add key to `nonCacheableKeys`. Subsequent `lookup(key)` checks `nonCacheableKeys` first and short-circuits to "skip cache" if present. The currently-iterating view continues delivering uncached results to its consumer (it holds a direct stream reference).
8. **Re-entrancy guard (L9 fix + SO5 scope + T7 ordering)**: `QueryResultCache` tracks a re-entrancy counter `cacheCodeDepth: int` (per-tx, single-threaded — counter rather than boolean to handle arbitrarily-nested cache-internal calls). At the START of every cache-mutating code path (`lookup`, `put`, `invalidateAll`, the stream-pull-append loop inside `view.next()`, `DeltaBuilder.buildForRecord`/`buildForAggregate`): **increment FIRST, then check** — if the post-increment value is `> 1`, this call is re-entrant; `cache.lookup` returns null (cache miss) and the caller falls through to `statement.execute(...)` for an uncached execution. Increment/decrement live inside try/finally so the counter is restored on every exit path including exceptions. Catches L9 hazard regardless of which cache-internal code path fires the re-entrant UDF query. Tests T7m (re-entrancy) and T7n (aggregate eager-drive with UDF in upstream WHERE — verify inner lookup bypass, no infinite recursion).
8. Deterministic-ORDER-BY gate in `ShapeClassifier` — for each `SQLOrderByItem`, if `modifier` chain contains non-deterministic reference, return NONE.
9. Test matrix (T7 set):
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

**Invariants to preserve.** I5: non-deterministic / NOCACHE queries never cached. I8: schema DDL upstream guard works (canary should never fire under normal operation). Memory bound: `maxEntries × maxRecordsPerEntry` Result references ceiling.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/NonDeterministicQueryDetector.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryResultCache.java` (LRU eviction)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (overflow flag)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (overflow handling in stream-pull)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (deterministic-ORDER-BY admission)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lookup gate, DML invalidation hook, schema-DDL assert)

**Out-of-scope files.**
- `SQLFunction` SPI — no `isDeterministic` flag added; denylist is centralized.
- Generated parser at `core/.../sql/parser/*` — never edited (project convention).

**Inter-track dependencies.**
- Depends on: Tracks 1, 2, 3, 4 (skeleton + read path + pause/resume + delta).
- Unblocks: Track 8 (observability + JMH).

**Library / function signatures.**
- `NonDeterministicQueryDetector.contains(SQLStatement) → boolean`.
- `NonDeterministicQueryDetector.contains(SQLOrderByItem) → boolean`.
- `QueryResultCache.entries`: `LinkedHashMap<CacheKey, CachedEntry>` with accessOrder + LRU eviction override.
- `CachedEntry.overflow: boolean`.
