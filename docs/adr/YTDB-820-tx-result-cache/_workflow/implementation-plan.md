# YTDB-820 Transaction-scoped query result cache

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Restore Xodus `EntityIterable`-style query result caching that DNQ-on-YTDB lost when Hub migrated off OrientDB. The cache is transaction-scoped, opt-in, and transparent — consumers see normal `ResultSet` semantics with a speedup on duplicate idempotent queries within one transaction. Target: Hub transactions issuing thousands of duplicate-shape SELECT/MATCH queries return their second-and-later executions from memory.

The v1 architecture is **lazy merge-on-read** — cached entries are immutable from populate time; intra-tx mutations are reconciled per-query at view-construction via a snapshot `TxDeltaCursor` (record/match shape) or a replayed `AggregateState` copy (aggregate shape). Mutations never touch the cache.

### Constraints

- **Opt-in.** Disabled by default via `youtrackdb.query.txResultCache.enabled`. Existing deployments must observe zero behavioral change unless the knob is flipped.
- **Transaction-scoped only.** Cache lives on `FrontendTransactionImpl` and is wiped on every tx-end path. No cross-tx leakage; no persistent or session-scoped variant in v1.
- **Idempotent queries only.** `SQLStatement.isIdempotent()` gates entry. DML statements bypass; `TRUNCATE CLASS` invalidates.
- **Thread-affine.** `FrontendTransactionImpl` is single-threaded by design (`assertOnOwningThread`); cache inherits this — no locks.
- **Memory bounded.** Two knobs (`maxEntries`, `maxRecordsPerEntry`) cap per-tx footprint to predictable limits.
- **Result semantics preserved.** Cached views must return results equivalent to a fresh execution at the same query-call moment: WHERE/ORDER BY/LIMIT honored against (cached + tx-delta) snapshot. Live views are immune to mid-iteration mutations (matches `OrderByStep` blocking-materializer contract).
- **Implementation in `core` module.** No changes required in `server`, `embedded`, or higher modules. Lucene module is excluded per project convention.

### Architecture Notes

#### Component Map

```mermaid
flowchart LR
    App["DNQ / Hub / SQL user"] --> SessQ["DatabaseSessionEmbedded.query()"]
    App --> SessE["DatabaseSessionEmbedded.executeInternal()"]
    SessQ --> Cache["QueryResultCache (NEW)"]
    SessE -.invalidate.-> Cache
    Cache --> Entry["CachedEntry (NEW)"]
    SessQ -.if miss.-> Stream["ExecutionStream (existing)"]
    SessQ --> DB["DeltaBuilder (NEW)"]
    DB -.reads.-> RecordOps["FrontendTransactionImpl.recordOperations (existing)"]
    DB --> DeltaCursor["TxDeltaCursor (NEW)"]
    SessQ --> View["CachedResultSetView (NEW)"]
    View --> Entry
    View --> DeltaCursor
    View -.fall-through.-> Stream
    Tx["FrontendTransactionImpl"] --> Cache
    SQLStmt["SQLStatement.isIdempotent / equals"] -.predicate.-> Cache
    Shape["ShapeClassifier (NEW)"] -.classify.-> Entry
    NDD["NonDeterministicQueryDetector (NEW)"] -.bypass-gate.-> Cache
    Metrics["QueryCacheMetrics (NEW)"] --- Cache
    AggTap["AggregateCacheTapStep (NEW)"] -.populate.-> Entry
```

- **`DatabaseSessionEmbedded`** (modified) — `query()` overloads gain a cache lookup before `statement.execute()`; on hit or miss, after entry is populated (or already present), `DeltaBuilder` builds a `TxDeltaCursor` (or `AggregateState` copy) from current `recordOperations`, and the result is wrapped in a `CachedResultSetView`. `executeInternal()` calls `cache.invalidateAll()` for `SQLTruncateClassStatement`.
- **`FrontendTransactionImpl`** (modified) — owns a lazily-allocated `QueryResultCache`. Defensive clear in `beginInternal()`. Final clear in `clearUnfinishedChanges()`. **No `invalidateOnMutation` hook on `addRecordOperation`** — under lazy, mutations only grow `recordOperations`; the cache does not react.
- **`QueryResultCache` (new)** — LRU-bounded map keyed by `CacheKey`, value `CachedEntry`. Public API: `lookup`, `put`, `invalidateAll`, `clear`.
- **`CachedEntry` (new)** — one cache slot: `List<Result>` (immutable in content from populate time), `Set<RID> cachedRids` (for delta cache-membership checks), paused `ExecutionStream`, exhaustion flag, AST metadata (`effectiveFromClasses`, `whereClause`, `orderBy`, `returnProjector`) for delta build. `aggregateState` for AGGREGATE_* shapes.
- **`CachedResultSetView` (new)** — `ResultSet` implementation backed by a `CachedEntry` + a per-view `TxDeltaCursor` (or per-view `deltaAggregateState`). Owns its own `position`; falls through to `entry.stream` when local position outruns cached list. Performs sorted-merge between cache and delta-cursor in `next()`.
- **`CacheKey` (new)** — record holding `(SQLStatement, normalizedParams)`; key type for `QueryResultCache`. Reuses `SQLStatement.equals()` / `hashCode()` for structural equality (with D12 identity fast-path) and a defensive-copied normalized parameter map (`Map<Object, Object>` to hold the positional-Integer + named-String union).
- **`CacheableShape` (new enum)** — discriminator computed by `ShapeClassifier.classify(stmt)`: `RECORD | AGGREGATE_COUNT | AGGREGATE_SUM | AGGREGATE_AVG | AGGREGATE_MIN | AGGREGATE_MAX | NONE`. Drives `DeltaBuilder` dispatch. `NONE` entries are non-cacheable (cache.put skips them).
- **`AggregateState` (new)** — per-entry container for aggregate caches: `currentScalar`, `contributingRids`, `contributingValues`, `count` (AVG only), `extremumRid` (MIN/MAX only). Encapsulates `observe` (called by `AggregateCacheTapStep` during populate), `applyMutation` (called by `DeltaBuilder.buildForAggregate` during delta replay), and `copy` (for view-time snapshot).
- **`TxDeltaCursor` (new)** — immutable per-view delta snapshot: `Set<RID> skipSet` (hide these RIDs from the cached cursor) + sorted `List<Result> injectList` (interleave these into the merge). Built once at view construction; never mutated.
- **`DeltaBuilder` (new utility)** — static methods `buildForRecord(entry, recordOps, ctx) → TxDeltaCursor` and `buildForAggregate(entry, recordOps, ctx) → AggregateState`. Iterates `tx.recordOperations.values()` once per call.
- **`ShapeClassifier` (new)** — static `classify(SQLStatement) → CacheableShape`; AST-only inspection (no execution) called once per entry at construction. Encodes cacheability + which delta-build path applies.
- **`AggregateCacheTapStep` (new)** — `AbstractExecutionStep` spliced upstream of `AggregateProjectionCalculationStep` during cache-miss execution. Observes each record before forwarding; populates `entry.aggregateState` for later view-time delta replay. Transparent to the downstream aggregate step.
- **`NonDeterministicQueryDetector` (new)** — denylist AST walker for `sysdate`/`random`/`uuid`/`eval` function calls and `$now`/`$current`/`$thread`/etc identifier nodes. Single static `contains(SQLStatement)`; gates cache lookup (Track 7) and the deterministic-ORDER-BY admission gate (D9).
- **`QueryCacheMetrics` (new)** — operator telemetry: hit / miss / delta-build-cost / eviction counters owned by `QueryResultCache`. Surfaced via `FrontendTransactionImpl.getQueryCacheMetrics()`.
- **`SQLStatement.isIdempotent()` + `equals()` + `hashCode()`** (existing, reused) — DML predicate and cache-key primitive. No changes to existing override semantics.
- **`GlobalConfiguration`** (modified) — three new knobs: `QUERY_TX_RESULT_CACHE_ENABLED`, `QUERY_TX_RESULT_CACHE_MAX_ENTRIES`, `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY`.

#### D1: Cache value type is `List<Result>`, not `List<RecordAbstract>`

- **Alternatives considered**: literal `List<RecordAbstract>` per spec wording; `List<Result>` (chosen).
- **Rationale**: `ResultSet.next()` returns `Result`. SELECT queries with projections (`SELECT name, age+1 FROM …`) produce `Result`s that wrap computed properties, not records. Caching `RecordAbstract` would exclude all projection queries — half of DNQ's emission according to the issue context. `Result` is the type that crosses the API boundary.
- **Risks/Caveats**: `Result`s referencing the session must remain valid for replay — they don't carry session state directly, so safe.
- **Implemented in**: Track 2.

#### D2: Cache key = (parsed `SQLStatement`, normalized parameter map)

- **Alternatives considered**: raw SQL text hash; AST + params (chosen); AST with toCanonicalString output.
- **Rationale**: `SQLStatement.equals()` is structural (verified on `SQLSelectStatement:380`). Reusing it gives whitespace/alias-invariant keys for free. Parsing already runs on the hot path; we don't pay extra parse cost. Parameter map is `Map<Object, Object>` to hold the positional-Integer + named-String union per `SQLStatement.execute(...)` API (`SQLStatement.java:62/66/83/89`). Defensive-copied at lookup time.
- **Risks/Caveats**: AST equality is only as good as `equals()` overrides on every node type — bugs there give wrong cache hits. `STATEMENT_CACHE_SIZE` keys by **SQL text**, not AST, so `SQLStatement.equals()` on deep AST trees is effectively new ground; latent override bugs may surface. Track 2 hardening: per-node-type equality tests for every AST node touched by D2; plus a regression spy in the cache that, on every hit, optionally re-executes and compares result sets under a debug flag (`youtrackdb.query.txResultCache.verifyHits`). Verified pre-merge against the Hub-replay scenario in D13.
- **Implemented in**: Track 2.

#### D3: Cache lookup gated on `instanceof SQLSelectStatement || SQLMatchStatement`; bulk-bypass types invalidate

- **Alternatives considered**: cache all statements (wrong — DML is non-deterministic); cache via `isIdempotent()` predicate (too wide — PROFILE/EXPLAIN/IF also return true); narrow type check (chosen).
- **Rationale**: PROFILE and EXPLAIN return plan/timing metadata that changes per call. A direct `instanceof` check against the two cacheable statement types keeps the gate narrow and obvious. The DML invalidation path uses an explicit type list (`SQLTruncateClassStatement` only). Regular `INSERT`/`UPDATE`/`DELETE` flow through `addRecordOperation` per affected record — under lazy, the cache doesn't react per-mutation; each subsequent `query()` picks them up via fresh delta build. Schema DDL is excluded because I8 makes it unreachable mid-tx. Track 7 wires a `Java assert` that fires if a schema-DDL statement reaches the cache hook while a tx is active.
- **Risks/Caveats**: New idempotent statement types added in the future need explicit cache opt-in. If I8 is ever relaxed, the bulk-bypass list must be re-expanded; the Track 7 assert is the canary.
- **Implemented in**: Track 2 (cache-lookup gate), Track 7 (DML invalidation hook + assert).

#### D4: Pause/resume via shared `ExecutionStream` + per-view position counters

- **Alternatives considered**: force-exhaust on first hit (consumer-unfriendly); materialize-on-demand without resume (spec violation); pause/resume with shared stream (chosen).
- **Rationale**: spec requires "continue iterating during the next execution of the same query". Holding the live stream in the cache entry achieves this. Per-view position counters make multiple concurrent consumers safe (within the single-threaded tx). Pulls from stream append to the shared list, so later consumers see the full ordered result.
- **Risks/Caveats**: storage cursor lifetime across `next()` calls — already exercised by normal consumer-paced iteration. Cache holds longer-lived reference but no new failure mode.
- **Implemented in**: Track 3.
- **Full design**: design.md §"Pause/resume mechanics"

#### D5-lazy: Lazy merge-on-read via snapshot `TxDeltaCursor` at view construction

- **Alternatives considered**: K0 wipe-on-mutation baseline (kills cache after first save for Hub workload); eager K1 sharp-merge (mutates `entry.results` in place per mutation + fail-fast `IllegalStateException` on live views — supersedes prior D5 from the eager design); lazy merge-on-read (chosen, per @andrii0lomakin review on PR #1077).
- **Rationale**: choice is **architecture-driven, not perf-driven**. Cache entry is immutable from populate time. Each `query()` (hit or miss) builds a per-view `TxDeltaCursor` (record/match shape) or `AggregateState` copy (aggregate shape) from a snapshot of `tx.recordOperations` at view-construction. `view.next()` is a sorted-merge between the immutable cache list and the frozen delta cursor. Eliminates `entry.version`, `expectedEntryVersion`, fail-fast `IllegalStateException`, K1 sharp-merge dispatch in `invalidateOnMutation`. Aligns with `OrderByStep` blocking-materializer contract — caching no longer introduces a fail-fast path consumers must handle. Honors the "transparent cache invisible behind ResultSet API" promise from the design Overview. Same `WHERE.matchesFilters`, `ORDER BY` comparator, and `AggregateState.applyMutation` primitives as eager — driver changed, algorithms identical.
- **Risks/Caveats**: **lazy has measurably higher total work than eager in read-mostly transactions with any writes**. Per-mutation work drops to O(0), but per-query delta-build is O(N) on tx-mutation count (O(p) with v2 per-class index), and per-`next()` is O(log p) when delta is non-empty for this query's class. The "delta empty" common-case condition (`p = 0`) holds only when no tx-mutation has happened on a class in this query's `effectiveFromClasses` — true for pure read-only segments, false in Hub's typical DNQ "save then query same class" pattern (1-3 writes followed by 50-200 same-class reads). For Hub-shaped workloads lazy does ~10-20× more raw operations than eager; absolute magnitude is sub-millisecond per request (noise-floor against hundreds-of-ms HTTP response time). The perf hit is **accepted explicitly** in exchange for the architectural and behavioral wins above. WHERE re-evaluation per query per delta record amortizes worse than eager — measured under D13. UPDATED records lose their pre-mutation state, so ORDER-BY repositioning always uses skip+inject (no "key didn't change" optimization possible). If D13 Hub-replay shows >5% request-latency regression vs eager, the v2 per-class index activates as a hardening response rather than v2 work.
- **Implemented in**: Track 4 (RECORD shape), Track 5 (AGGREGATE shapes), Track 6 (MATCH Etap A).
- **Full design**: design.md §"Lazy merge-on-read"

#### D6: Non-determinism via denylist AST walk + reused `noCache` hint

- **Alternatives considered**: `SQLFunction.isDeterministic()` SPI (adds API surface); denylist + opt-out (chosen); ignore problem.
- **Rationale**: known set of non-deterministic primitives is small and stable. A single `NonDeterministicQueryDetector.contains(SQLStatement)` walker handles it. `SQLSelectStatement.noCache` Boolean already parses; we extend its semantics to "skip result cache" in addition to "skip execution-plan cache".
- **Risks/Caveats**: user-defined Java functions cannot be inspected — documented escape valve is `NOCACHE` hint. New non-deterministic stdlib functions need an entry in the detector; coupling localized.
- **Implemented in**: Track 7.
- **Full design**: design.md §"Non-determinism handling"

#### D7: Per-tx memory bound — LRU at `maxEntries` + per-entry `maxRecordsPerEntry`

- **Alternatives considered**: unbounded (OOM); time-based eviction (per-tx meaningless); LRU + per-entry cap (chosen).
- **Rationale**: two-dimensional bound. LRU eviction is standard for working-set workloads. Defaults (200 × 10000 = 2M refs) are pessimistic-but-safe for Hub.
- **Risks/Caveats**: knob tuning is workload-dependent. Hot-changeable per `GlobalConfiguration`.
- **Implemented in**: Track 1 (knobs + LRU map), Track 7 (per-entry overflow handling).

#### D8-lazy: MATCH Etap A as RECORD-shape composition; Etap B v2-deferred

- **Alternatives considered**: K0 for all MATCH (loses cache for any matching mutation); eager K1 MATCH per-tuple with `reverseIndex` (the prior D8 from the eager design — superseded); MATCH Etap A as RECORD with `returnProjector` (chosen).
- **Rationale**: Single-alias MATCH `MATCH {as:u, class:X WHERE ...} RETURN <projection of u>` is semantically equivalent to `SELECT <projection> FROM X WHERE ...` with a tuple-shaped RETURN projection. Under lazy, this folds cleanly into the RECORD-shape `DeltaBuilder.buildForRecord` path — same delta logic, the `returnProjector` (stored on `CachedEntry`) wraps each inject-list record into a single-binding tuple `Result`. No per-tuple `Set<RID>`, no `reverseIndex`, no per-alias maps. Multi-alias / cross-join / pattern-with-edges classifies as NONE (non-cacheable in v1). Etap B requires constrained pattern walk via `MatchPrefetchStep` + edge-CREATED dispatch — same scope as eager Etap B (lazy doesn't reduce that work, just moves it to view-construction time); v2 candidate.
- **Risks/Caveats**: `returnProjector` correctness depends on the projection closure built at entry construction matching the original execution's projection semantics exactly. Track 6 step-g test validates equivalence vs fresh re-execution.
- **Implemented in**: Track 6.
- **Full design**: design.md §"MATCH Etap A — RECORD-shape composition"

#### D9: Deterministic ORDER BY admission (modifier-chain supported)

- **Alternatives considered**: plain-identifier-only ORDER BY (loses caching for `ORDER BY lower(name)`); allow any ORDER BY expression (would require grammar extension — current grammar accepts only `Identifier [Modifier]`); allow deterministic modifier-chain ORDER BY (chosen).
- **Rationale**: `SQLOrderByItem` carries an alias `String` plus an optional `SQLModifier` chain. Reusing `NonDeterministicQueryDetector` on each item's `modifier` gives a clean admission gate. The ORDER BY comparator runs at delta-build time (sorting inject_list) and at first-execution time (sorting fresh results from storage) — both must give consistent ranks across the entry's lifetime, hence the determinism gate.
- **Risks/Caveats**: per-comparator-call `modifier.execute(...)` adds CPU vs direct field lookup — bounded by inject_list size × log(inject_list size). Acceptable trade-off for the cache hits saved.
- **Implemented in**: Track 7 (classify-gate). `OrderByComparator` from Track 4 already delegates ranking to `SQLOrderByItem.compare`.

#### D10-lazy: SKIP support in lazy delta with prefix cap

- **Alternatives considered**: SKIP always NONE (loses cache for paginated queries); SKIP K1 with unbounded prefix (memory blowup); SKIP cacheable when `skip + limit <= maxRecordsPerEntry` (chosen).
- **Rationale**: typical UI pagination has `skip + limit` in 10-1000 range — well under 10000 default cap. Hub list views reissue the same query shape as the user pages. Cache stores the full prefix up to `skip + limit` records; delta-build operates on the prefix; view-level LIMIT clipping after sorted-merge enforces the visible window. Above the cap, classify returns NONE (non-cacheable — bypass).
- **Risks/Caveats**: prefix cache is `skip + limit` records, not `limit` — slightly larger entry footprint. Sorted-merge with CREATED records can shift the visible window — correct behavior.
- **Implemented in**: Track 4 (folded into RECORD-shape delta).

#### D11: Pre-expand `fromClasses` to subclass closure at entry construction (`effectiveFromClasses`)

- **Alternatives considered**: per-mutation `isSubClassOf` loop over raw `fromClasses`; pre-expanded closure stored as `effectiveFromClasses` (chosen, justified by I8); cache `SchemaClass` references (no benefit).
- **Rationale**: I8 guarantees schema is immutable per tx, so the closure computed via `SchemaClass.getAllSubclasses()` at entry construction is stable for the entry's lifetime. The polymorphism gate at delta-build time becomes a single O(1) `Set<String>.contains(record.class.name)`. Field name makes "is a closure, not raw FROM names" self-documenting.
- **Risks/Caveats**: `SchemaClass.getAllSubclasses()` cost at construction is `O(subclass count)` — acceptable since it happens once per entry. If I8 is ever relaxed, the closure becomes stale; Track 7 assert is the canary.
- **Implemented in**: Track 4 step 1 (capture `effectiveFromClasses` via closure expansion).
- **Full design**: design.md §"Lazy merge-on-read" → Class filter

#### D12: AST identity fast-path on cache lookup

- **Alternatives considered**: deep `SQLStatement.equals()` on every lookup (baseline); identity (`==`) fast-path before deep equals (chosen); pre-canonicalized text key (loses D2's whitespace/alias-invariance).
- **Rationale**: `SQLEngine.parse()` is backed by `STATEMENT_CACHE` (size-bounded LRU keyed by raw SQL text). Same text reissued returns the **same `SQLStatement` instance** — `==` identity. Cache-key comparison can short-circuit. For DNQ workloads with thousands of duplicate-text queries, collapses lookup cost from deep-AST-walk to a pointer compare plus parameter-map equality.
- **Risks/Caveats**: identity fast-path is purely an optimization — correctness fall-through to deep equals preserves D2's semantics. Risk localized to `CacheKey.equals` implementation: identity comparison must NOT replace deep equals, only precede it. Track 2 test verifies both paths.
- **Implemented in**: Track 2 (`CacheKey.equals(Object)` body).

#### D13: Hub-replay validation gate (pre-merge)

- **Alternatives considered**: ship on synthetic JMH alone (baseline); ship after Hub-workload replay validates lazy coverage (chosen, justified by D5-lazy K1-NONE-coverage shift).
- **Rationale**: Under lazy, the relevant coverage metric shifts from "what fraction of queries reach K1 sharp-merge" (eager) to "what fraction of queries classify as cacheable (not NONE)" (lazy). Before Hub deployment, Track 8 will record an anonymized DNQ-emission sample from a Hub staging environment (single tx, ~1000 queries) and replay against the cache; pass criteria: ≥70% of repeat-shape queries classify as cacheable (`ShapeClassifier.classify(stmt) != NONE`) AND view output matches fresh execution across the recorded mutation sites. Also measures: per-query delta-build cost (informs per-class-index v2 decision), WHERE re-evaluation hot RIDs (informs memoization v2 decision), MIN/MAX recompute frequency (informs D14 decision).
- **Risks/Caveats**: replay requires DNQ-query-log capture from staging — coordinate with Hub team. If cacheable coverage falls below 70%, follow-up classify-relaxation work (e.g., LET support, multi-alias MATCH via Etap B) becomes higher priority.
- **Implemented in**: Track 8 (JMH harness extended with a `HubReplay` scenario).

#### D14: MIN/MAX sorted-value index (deferred to v2, gated by D13)

- **Alternatives considered**: O(n) recompute when extremum leaves at delta-build time (v1 baseline — chosen); `TreeMap<Number, Set<RID>>` sorted index on `AggregateState` giving O(log n) per-op.
- **Rationale**: D5-lazy worst case for MIN/MAX is O(`maxRecordsPerEntry`) = O(10000) recompute at delta-build time when extremum is removed / transitions out of WHERE / updated to non-extremum. Sorted-value index trades O(1) common-case for O(log n) consistent. Net win depends on workload: stable extremum → status quo wins; churning extremum → sorted index wins. Hub's actual MIN/MAX usage pattern is the gating question; D13 replay measures it.
- **Risks/Caveats**: ~4× memory growth for MIN/MAX entries; negligible for typical counts. Numeric comparator needs `BigDecimal`-coerced equality to avoid cross-Number-subtype hazards.
- **Implemented in**: deferred — v2 candidate. Decision gate: D13 measurement.

#### D15: `TxDeltaCursor` snapshot at view construction; not refreshed mid-iteration

- **Alternatives considered**: rebuild deltaCursor on every `view.next()` (consistent with live recordOperations but introduces moving-target semantics, mid-iteration mutations could cause duplicate/missing rows); snapshot at view construction (chosen).
- **Rationale**: Matches the existing `OrderByStep` blocking-materializer contract — materialized result sets don't reflect mid-iteration mutations. Eliminates "moving target under iterator" failure modes that the eager K1 sharp-merge design solved with fail-fast `IllegalStateException`. The natural refresh boundary is `query()` itself — every new `query()` call constructs a fresh view with a fresh delta snapshot. Application code that wants "read its own writes" after a mutation just issues a new `query()`, which is the same mental model as SQL-standard REPEATABLE READ.
- **Risks/Caveats**: views started before a mutation will not see that mutation. Documented behavior; same as uncached `OrderByStep` already does. No correctness hazard — consumers who want fresh state issue new query.
- **Implemented in**: Track 4 (`DeltaBuilder.buildForRecord` returns a snapshotted cursor; view holds the reference and never refreshes).
- **Full design**: design.md §"Pause/resume mechanics" → Mid-iteration mutation

### Invariants

- **I1** — Cache cleared on every tx-end path (commit, rollback, close). Enforced by single hook in `clearUnfinishedChanges()`. Test: T1.
- **I2** — Cache MUTATION paths (`lookup`, `put`, `invalidateAll`, begin-time `clear()`) accessed only by owning thread. Enforced via existing `assertOnOwningThread()` guards in `FrontendTransactionImpl` at lines 165 (`beginInternal`), 224 (`commitInternalImpl`), 250 (`getRecord`), 474 (`deleteRecord`), 511 (`addRecordOperation`). Tx-end `clear()` is the explicit exception, covered by I6. Test: T1.
- **I3** — Paused `ExecutionStream` in a `CachedEntry` is closed when the entry is evicted or the tx ends. Test: T3.
- **I4** — View output equals fresh-execution result composed with tx-delta-applied snapshot: WHERE / ORDER BY / LIMIT honored against (cached + tx-delta). Test: T4 (RECORD), T5 (AGGREGATE), T6 (MATCH Etap A).
- **I5** — Non-deterministic queries (denylist hit or `NOCACHE` hint) never produce a cache entry and never hit a cache entry. Test: T7.
- **I6** — Tx-end `clear()` is idempotent and safe under cross-thread invocation. `QueryResultCache.clear()`, `CachedEntry.close()`, and `ExecutionStream.close()` are all idempotent. Tests: T1, T3.
- **I7** — View's `TxDeltaCursor` (or `deltaAggregateState`) is immutable post-construction. `recordOperations` growth — appending new mutations from any thread, including the owning thread mid-iteration — does NOT affect any live view's delta or output. Matches `OrderByStep` blocking-materializer contract. Test: T4 (mid-iteration mutation does not appear in current view; fresh view sees it).
- **I8** — Schema is immutable for the lifetime of a transaction (ENFORCED upstream). `SchemaShared.saveInternal` (`SchemaShared.java:820-823`) throws `SchemaException` on every CREATE/DROP/ALTER CLASS|PROPERTY mid-tx; `IndexManagerEmbedded` (lines 307, 459) throws on index DDL mid-tx. `effectiveFromClasses` and other AST-derived metadata therefore do not require recomputation. Test: T1.

### Integration Points

- `DatabaseSessionEmbedded.query(...)` and `executeInternal(...)` — cache lookup / population / invalidation hooks; view construction with delta build.
- `FrontendTransactionImpl.beginInternal()` / `clearUnfinishedChanges()` — lifecycle hooks. `addRecordOperation()` is **not** hooked by cache.
- `FrontendTransactionImpl.recordOperations` — read by `DeltaBuilder` at view construction.
- `SQLStatement.isIdempotent()` and `equals()` — cache predicate and key.
- `SQLWhereClause.matchesFilters(Identifiable | Result, CommandContext)` — delta-build primitive.
- `SQLSelectStatement.noCache` — opt-out hint, semantics extended.

### Non-Goals

- Cross-transaction result sharing (between concurrent `FrontendTransaction` instances).
- Persistent / disk-backed cache.
- Cache for the `computeScript(...)` path or for Gremlin queries (separate engine in `embedded`).
- Server-mode propagation (remote storage). Cache lives in the embedded session.
- `FrontendTransactionNoTx` (auto-commit) support — single-statement tx have no replay potential.
- Eviction tuning beyond LRU + caps (e.g., size-aware eviction, TTL).
- Cache-aware query plans (planner reading the cache to pick join orders).
- Aggregate caching for shapes other than `COUNT(*)`, `SUM(prop)`, `AVG(prop)`, `MIN(prop)`, `MAX(prop)` over a plain property. `GROUP BY`, `HAVING`, expression-aggregates (`SUM(a+b)`), `COUNT(DISTINCT col)`, `MEDIAN`, `MODE`, `PERCENTILE` all classify as NONE (non-cacheable) in v1.
- MATCH `CREATED` **multi-alias** delta (Etap B) — constrained pattern walk on a single new record across edge traversals, plus dispatch on edge-CREATED. v2 candidate; uses `MatchPrefetchStep` + `PREFETCHED_MATCH_ALIAS_PREFIX` primitive. Etap A — single-alias MATCH — IS in scope for v1 via the RECORD-shape composition with `returnProjector` (D8-lazy).
- SKIP queries where `skip + limit > maxRecordsPerEntry` — classify returns NONE, query bypasses cache.
- LET-based unions (`SELECT EXPAND($u) LET ..., $u = unionall($a, $b)`) — NONE in v1.
- Per-entry per-RID WHERE-evaluation memoization — v2 optimization, gated on D13 measurement of WHERE re-evaluation cost.
- Per-class indexing of `recordOperations` for O(p) delta-build (vs O(N)) — v2 optimization, gated on D13.
- **`NOCACHE` hint extension to MATCH** — the grammar accepts it only on SELECT (`YouTrackDBSql.jjt:1245` MATCH production lacks the token); MATCH's narrower non-determinism surface is fully covered by `NonDeterministicQueryDetector`'s built-in denylist. v2 candidate gated on D13 measurement. See design.md §"Non-determinism handling" → MATCH NOCACHE asymmetry for the full rationale.

## Checklist

- [ ] Track 1: Skeleton — knobs, data structures, lifecycle wiring
  > Lay down the foundational pieces with no behavioral change: three `GlobalConfiguration` knobs, new `QueryResultCache`, `CachedEntry`, `TxDeltaCursor` types (skeletons or no-op), `queryResultCache` field on `FrontendTransactionImpl`, and the begin/clear lifecycle hooks. After this track the cache exists, is allocated lazily when enabled, and is correctly wiped on every tx-end path — but no `query()` reads or writes it yet.
  > **Scope:** ~4-5 steps covering knob declarations, `QueryResultCache` + `CachedEntry` + `TxDeltaCursor` skeleton, `FrontendTransactionImpl` field wiring, lifecycle invariant tests.

- [ ] Track 2: Read path — cache key, lookup, population, `CachedResultSetView`
  > Wire the cache into `DatabaseSessionEmbedded.query()` idempotent SELECT/MATCH branch. Build the cache key from parsed AST + normalized parameters (with D12 identity fast-path); on miss, execute normally and wrap the result in a `CachedResultSetView` that incrementally populates the entry as the consumer iterates; on hit, return a view over the existing entry with an empty `TxDeltaCursor` (Track 4 fills the delta build). No delta logic yet — only populate-and-replay path within one consumer's lifetime.
  > **Scope:** ~5 steps covering `CacheKey`, empty `TxDeltaCursor` placeholder, `CachedResultSetView` sorted-merge skeleton (empty delta), lookup at all three `query()` overloads, behavioral tests for second-query hit, idempotent-only gate.
  > **Depends on:** Track 1

- [ ] Track 3: Pause/resume — shared stream + per-view position
  > Extend `CachedEntry` to hold the live `ExecutionStream` past the first consumer's iteration, and extend `CachedResultSetView` to fall through to it when the consumer outruns the cached list. Multiple `query()` calls within one tx return independent views sharing the same entry; the first view to pull a particular row is the one that pays the storage cost. Close the stream when exhausted, evicted, or invalidated.
  > **Scope:** ~4-5 steps covering stream-hold in `CachedEntry`, fall-through in `CachedResultSetView`, exhaustion flip, stream-lifecycle tests.
  > **Depends on:** Track 2

- [ ] Track 4: Lazy delta core + RECORD shape
  > Implement `ShapeClassifier` RECORD/NONE classify, `DeltaBuilder.buildForRecord` (class-filtered single-pass over `recordOperations` producing skip-set + sorted inject-list), and `CachedResultSetView` sorted-merge. SKIP/LIMIT, polymorphism (D11), and the dispatch table for `(op.type, cached, match_after)` all fold into the RECORD path. Detail in `plan/track-4.md`.
  > **Scope:** ~6 steps covering `ShapeClassifier` RECORD/NONE, `DeltaBuilder.buildForRecord`, `TxDeltaCursor` sorted-merge in `CachedResultSetView.next()`, polymorphism + SKIP/LIMIT integration, full test matrix (CREATED/UPDATED/DELETED × cached/not × match_after × ORDER BY × SKIP).
  > **Depends on:** Tracks 2, 3

- [ ] Track 5: Aggregate delta — AGGREGATE_* shapes
  > Extend `ShapeClassifier` to AGGREGATE_COUNT/SUM/AVG/MIN/MAX, splice `AggregateCacheTapStep` upstream of `AggregateProjectionCalculationStep` to populate `AggregateState`, and reuse the `DeltaBuilder` pattern for copy-and-replay of `applyMutation` at view construction. Detail in `plan/track-5.md`.
  > **Scope:** ~5 steps covering `AggregateState` with `copy`/`applyMutation`, `AggregateCacheTapStep`, plan-rewrite splice, `DeltaBuilder.buildForAggregate`, aggregate-shape view, full test matrix (COUNT/SUM/AVG/MIN/MAX × CREATED/UPDATED/DELETED × transition cases × MIN/MAX recompute).
  > **Depends on:** Track 4

- [ ] Track 6: MATCH Etap A delta — single-alias as RECORD-shape composition
  > Extend `ShapeClassifier` to classify single-alias MATCH (Etap A) as RECORD with a `returnProjector` built from the RETURN clause at entry construction. Multi-alias / pattern-with-edges classify as NONE. Detail in `plan/track-6.md`.
  > **Scope:** ~4 steps covering MATCH classify rules, `returnProjector` builder, MATCH-specific test matrix (DELETE/UPDATE/CREATE × single-alias/multi-alias-NONE/pattern-with-edges-NONE), Etap-B-explicit-non-goal documentation.
  > **Depends on:** Track 4

- [ ] Track 7: Hardening — non-determinism, DML invalidation, memory bound
  > Production-readiness for correctness: AST denylist for non-deterministic functions/variables, `NOCACHE` hint extension, full-wipe on `SQLTruncateClassStatement` via `cache.invalidateAll()`, LRU enforcement at `maxEntries`, per-entry overflow handling at `maxRecordsPerEntry`, deterministic-ORDER-BY admission gate using `NonDeterministicQueryDetector` on each `SQLOrderByItem.modifier`. Java assert in the cache hook that fires if schema DDL reaches it (D3 canary).
  > **Scope:** ~5 steps covering `NonDeterministicQueryDetector` + wiring, `noCache` semantic extension, DML invalidation hook + assert, per-entry overflow handling, deterministic-ORDER-BY gate, integration tests across all surfaces.
  > **Depends on:** Tracks 1, 2, 3, 4

- [ ] Track 8: Observability + JMH — `QueryCacheMetrics` + benchmark + Hub replay
  > Add `QueryCacheMetrics` (hit/miss/eviction/delta-build counters), JMH cache-vs-baseline benchmarks, and the D13 Hub-replay validation gate (≥70% cacheable-coverage + view-output equivalence). Detail in `plan/track-8.md`.
  > **Scope:** ~4 steps covering `QueryCacheMetrics` class + accessor, counter increments at cache callsites, JMH scenarios (hit/miss/delta-build/aggregate-replay), Hub replay test + D13 pass criteria, integration counter assertions.
  > **Depends on:** Tracks 5, 6, 7

## Plan Review
- [x] Plan review (consistency + structural) — passed iteration 1 after lazy pivot rewrite (Mutation 11)

**Consistency review (CR1-CR9)**:
- CR1 (blocker, mechanical): grammar source citation `YouTrackDBSql.jjt:3726-3729` was TruncateClassStatement — fixed to `:3053 SQLOrderBy OrderBy()` in track-7.md.
- CR2 (mechanical): `getPrev()` does not exist on `AbstractExecutionStep` (public `prev` field at line 66) — fixed in design.md § Aggregate side-tap and plan/track-5.md.
- CR3 (mechanical): phantom invariant `I11` reference in track-4.md — replaced with I7 framing.
- CR4 (design-decision, accepted ścieżka A): MATCH grammar has never carried `NOCACHE` (pre-existing limitation `YouTrackDBSql.jjt:1245`); preserved deliberately and documented as Non-Goal with full rationale in design.md § Non-determinism handling → MATCH NOCACHE asymmetry. v2 candidate gated on D13.
- CR5 (mechanical): `SQLMatchStatement.returnItems` is `List<SQLExpression>`, not `List<SQLProjectionItem>` — fixed in plan/track-6.md.
- CR6 (mechanical): `SQLMatchFilter` has no direct `clazz` field; uses accessor `getClassName(ctx)` over internal items list — fixed in plan/track-6.md.
- CR7 (mechanical): `GlobalConfiguration` path corrected from `internal/core/config/` to `api/config/` in plan/track-1.md.
- CR8 (mechanical): `steps` field is on concrete `SelectExecutionPlan:54`, not `InternalExecutionPlan` interface — fixed in plan/track-5.md.
- CR9 (mechanical, regression of CR8 fix): `statement.execute(...)` returns `ResultSet` (LocalResultSet), not the plan; cache miss path must call `statement.createExecutionPlan(ctx, false)` instead — fixed in design.md § Aggregate side-tap and plan/track-5.md.

**Structural review (S1-S5)**:
- S1-S4 (mechanical): Track 4/5/6/8 intro paragraphs in plan-file checklist were 4-8 sentences each, exceeding 1-3 sentence cap — compressed to 1-3 sentences ending with "Detail in `plan/track-N.md`".
- S5 (mechanical): NOCACHE-for-MATCH Non-Goal bullet was ~12-sentence essay — compressed to one-paragraph cross-reference with full rationale moved to design.md § Non-determinism handling → MATCH NOCACHE asymmetry.

**Architecture honesty pass (Mutation 12, post-review)**: D5-lazy Risks/Caveats and design.md § Overview → "Why lazy merge-on-read" + § Lazy merge-on-read TL;DR were rewritten to honestly acknowledge the perf trade-off after user feedback that the reviewer's "p = 0 in common read-mostly case" framing is incorrect for Hub workloads with any writes. Decision now framed as architecture-driven (not perf-driven): lazy does ~10-20× more raw work than eager in Hub-shaped tx (1-3 writes + many same-class reads), but in absolute terms sub-millisecond per request. Trade-off accepted explicitly in exchange for elimination of K1 dispatch / version counters / fail-fast `IllegalStateException` and honored "transparent cache" promise.

**Pre-existing structural debt observed (deferred)**:
- 23 should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — pre-existing house-style debt, +4 from this revision (Mutation 11+12 prose adds em-dashes). Deferred to Phase 4 global sweep per `house-style.md § Em-dash discipline`.

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
