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
- **`FrontendTransactionImpl`** (modified) — owns a lazily-allocated `QueryResultCache`. Defensive clear in `beginInternal()`. Final clear in `clearUnfinishedChanges()`. **No `invalidateOnMutation` hook on `addRecordOperation`** — under lazy, mutations only grow `recordOperations`; the cache does not react. Gains `mutationVersion: long` counter incremented on every `addRecordOperation` call (whether new or type-change collapse); `DeltaBuilder` uses this as the version key for cross-view delta sharing per design.md § Cross-view delta sharing via mutationVersion.
- **`QueryResultCache` (new)** — LRU-bounded map keyed by `CacheKey`, value `CachedEntry`. Also holds `nonCacheableKeys: Set<CacheKey>` (short-circuits lookup after overflow per L7 fix) and a per-tx `inFlightLookup: boolean` flag (re-entrancy guard per L9 fix; nested `lookup` / `put` calls from UDFs in WHERE evaluation bypass the cache). Public API: `lookup`, `put`, `invalidateAll`, `clear`.
- **`CachedEntry` (new)** — one cache slot: `List<Result>` (immutable in content from populate time), `Set<RID> cachedRids` (diagnostic; the L1 fix made dispatch independent of this), paused `ExecutionStream`, exhaustion flag, AST metadata (`effectiveFromClasses`, `whereClause`, `orderBy`, `returnProjector`) for delta build. `aggregateState` for AGGREGATE_* shapes. For MATCH_TUPLE_MULTI shape: `aliasClasses: Map<String, Set<String>>`, `aliasWheres: Map<String, SQLWhereClause>`, `contributingRids: Map<Integer, Set<RID>>`, `reverseIndex: Map<RID, Set<Integer>>`, `tombstoned: boolean` (set at delta-build pre-scan when a CREATED hits a pattern class, forcing evict + miss). Holds the cached delta artifacts shared across views: `cachedSkipSet: Set<RID>`, `cachedInjectList: List<Result>`, `cachedDeltaVersion: long` — populated/replaced by `DeltaBuilder` when a new view's `tx.mutationVersion` doesn't match the cached value (per design.md § Cross-view delta sharing via mutationVersion). For K0_NONE shape (D18): `populateMutationVersion: long` stamped at populate time and compared at lookup to gate cache hits; `k0InvalidationCount: int` incremented when a lookup observes diverged mutationVersion and forces re-populate, with the third increment routing the key to `nonCacheableKeys` to bound churn.
- **`CachedResultSetView` (new)** — `ResultSet` implementation backed by a `CachedEntry` + a per-view delta object (either `TxDeltaCursor` for RECORD / MATCH-Etap-A, or `AggregateState` for AGGREGATE_*, or `MatchMultiDelta` for MATCH_TUPLE_MULTI). Owns its own `position` and `emitted`; falls through to `entry.stream` when local position outruns cached list. RECORD/MATCH-Etap-A: sorted-merge between cache and delta-cursor. MATCH_TUPLE_MULTI: per-tuple-index skip iteration with stream-pull RID-skip-set filter. AGGREGATE: single-row read of `deltaAggregateState.toResult()`.
- **`CacheKey` (new)** — record holding `(SQLStatement, normalizedParams)`; key type for `QueryResultCache`. `equals` and `hashCode` delegate to `SQLStatement.equals` / `hashCode` (structural per `SQLSelectStatement:380` and `SQLMatchStatement:507`), with the D12 identity fast-path checking `this.stmt == other.stmt` before the deep walk. Different SKIP/LIMIT values produce distinct cache entries; correctness for these shapes is handled by routing them to K0_NONE under D18's mutation-version gate. Defensive-copied normalized parameter map (`Map<Object, Object>` to hold the positional-Integer + named-String union).
- **`CacheableShape` (new enum)** — discriminator computed by `ShapeClassifier.classify(stmt)`: `RECORD | AGGREGATE_COUNT | AGGREGATE_SUM | AGGREGATE_AVG | AGGREGATE_MIN | AGGREGATE_MAX | MATCH_TUPLE_MULTI | K0_NONE`. Drives `DeltaBuilder` dispatch and the K0 invalidation gate. Classify checks `stmt.skip != null || stmt.limit != null` first and routes such queries to K0_NONE before any shape-specific check. MATCH_TUPLE_MULTI introduced for multi-alias MATCH (Etap B partial in v1; see D8-lazy); supports DELETED + UPDATED via reverseIndex, tombstones on CREATED. K0_NONE covers delta-unreconcilable shapes (any SKIP/LIMIT query, LET, GROUP BY, $matched, subqueries); D18 caches them under `tx.mutationVersion` gate; entry serves cache hits while mutationVersion is unchanged, invalidates when it diverges.
- **`AggregateState` (new)** — per-entry container for aggregate caches: `currentScalar`, `contributingRids`, `contributingValues`, `count` (AVG only), `extremumRid` (MIN/MAX only — `@Nullable RID` field; `was_extremum = rid.equals(extremumRid)` uses RID identity, NOT `Number.equals`, sidestepping the cross-`Number`-subtype hazard). Encapsulates `observe` (called by `AggregateCacheTapStep` during populate), `applyMutation` (called by `DeltaBuilder.buildForAggregate` during delta replay), and `copy` (for view-time snapshot). D14 sorted-value index (`TreeMap<BigDecimal, Set<RID>>`) for O(log n) extremum maintenance is v2-deferred per D14.
- **`TxDeltaCursor` (new)** — immutable per-view delta snapshot for RECORD / MATCH-Etap-A shape: `Set<RID> skipSet` (hide these RIDs from the cached cursor) + sorted `List<Result> injectList` (interleave these into the merge). Built once at view construction; never mutated.
- **`MatchMultiDelta` (new)** — immutable per-view delta snapshot for MATCH_TUPLE_MULTI shape: `Set<Integer> tupleSkipSet` (tuple-index skip — drop these existing tuples from the cache cursor) + `Set<RID> ridSkipSet` (RID skip — drop stream-pulled tuples whose ANY alias binding's RID is in this set). No injectList — partial Etap B does not discover new tuples on CREATED (tombstone path takes over). Built once at view construction.
- **`DeltaBuilder` (new utility)** — static methods `buildForRecord(entry, recordOps, ctx) → TxDeltaCursor`, `buildForAggregate(entry, recordOps, ctx) → AggregateState`, `buildForMatchMulti(entry, recordOps, ctx) → MatchMultiDelta` (or TOMBSTONE sentinel signaling cache-lookup to evict + miss). Iterates `tx.recordOperations.values()` once per call.
- **`ShapeClassifier` (new)** — static `classify(SQLStatement) → CacheableShape`; AST-only inspection (no execution) called once per entry at construction. Encodes cacheability + which delta-build path applies.
- **`AggregateCacheTapStep` (new)** — `AbstractExecutionStep` spliced upstream of `AggregateProjectionCalculationStep` during cache-miss execution. Observes each record before forwarding; populates `entry.aggregateState` for later view-time delta replay. Transparent to the downstream aggregate step.
- **`IdempotentExecutionStream` (new)** — wrapper around an `ExecutionStream` that makes `close(ctx)` idempotent (first call forwards to underlying, subsequent calls no-op). The cache substitutes this wrapper into both its own `CachedEntry.stream` field and the paired `LocalResultSet`'s stream slot at cache-put time, so cross-caller double-close (closeActiveQueries + cache.clear at tx-end) is safe regardless of underlying `ExecutionStream` impl behaviour. The `ExecutionStream` interface contract itself does NOT mandate idempotency, so the wrapper is the load-bearing safety net.
- **`NonDeterministicQueryDetector` (new)** — denylist AST walker for `sysdate`/`random`/`uuid`/`eval` function calls and `$now`/`$current`/`$thread`/etc identifier nodes. Single static `contains(SQLStatement)`; gates cache lookup (Track 7) and the deterministic-ORDER-BY admission gate (D9).
- **`QueryCacheMetrics` (new)** — operator telemetry: hit / miss / delta-build-cost / eviction counters owned by `QueryResultCache`. Surfaced via `FrontendTransactionImpl.getQueryCacheMetrics()`.
- **`SQLStatement.isIdempotent()` + `equals()` + `hashCode()`** (existing, reused) — DML predicate and cache-key primitive. No changes to existing override semantics.
- **`GlobalConfiguration`** (modified) — four new knobs: `QUERY_TX_RESULT_CACHE_ENABLED`, `QUERY_TX_RESULT_CACHE_MAX_ENTRIES`, `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY`, `QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD` (D18 — N invalidations of a K0_NONE entry before its key joins `nonCacheableKeys`, default 3).

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

#### D8-lazy: MATCH Etap A as RECORD-shape composition; partial Etap B in v1; CREATED-discovery deferred to separate ADR

- **Alternatives considered**:
  - K0 for all MATCH (loses cache for any matching mutation — original baseline).
  - Eager K1 MATCH per-tuple with `reverseIndex` (the prior D8 from the eager design — superseded by lazy).
  - **MATCH Etap A as RECORD-shape composition (chosen)** for single-alias MATCH.
  - **Partial Etap B as MATCH_TUPLE_MULTI shape (chosen, in v1)** for multi-alias MATCH: DELETED + UPDATED handled via per-tuple `reverseIndex`-driven delta build; CREATED on a class in `effectiveFromClasses` tombstones the entry (force miss + re-execute).
  - Full Etap B (constrained-pattern-walk discovery of new tuples on CREATED via `MatchPrefetchStep` + edge-CREATED dispatch) — deferred to separate ADR.
- **Rationale (Etap A)**: Single-alias MATCH `MATCH {as:u, class:X WHERE ...} RETURN <projection of u>` is semantically equivalent to `SELECT <projection> FROM X WHERE ...` with a tuple-shaped RETURN projection. Under lazy, this folds cleanly into the RECORD-shape `DeltaBuilder.buildForRecord` path — same delta logic, the `returnProjector` (stored on `CachedEntry`) wraps each inject-list record into a single-binding tuple `Result`. No per-tuple `Set<RID>`, no `reverseIndex`, no per-alias maps.
- **Rationale (partial Etap B in v1)**: The eager design's `MergeKind.MATCH_TUPLE` already covered DELETED + UPDATED for multi-alias MATCH (with K0 wipe on CREATED). The lazy pivot dropped multi-alias MATCH entirely as a side-effect of architectural simplification — that was not an intentional cost-benefit decision. Restoring DELETED + UPDATED coverage reuses the eager design's bookkeeping (`reverseIndex`, `aliasClasses`, `aliasWheres`) in the lazy framework at modest implementation cost (~6-8 steps in Track 6). Hub uses multi-alias MATCH heavily for graph traversal (Issue↔Project, User↔Team, Comment↔Issue patterns); the "save then re-read" Hub pattern produces UPDATED + DELETED multi-alias scenarios on every list-view refresh after a mutation. Without partial Etap B, every such refresh is a cache miss + full re-execute.
- **Rationale (CREATED-discovery in separate ADR)**: Discovering new tuples that contain a CREATED record requires constrained pattern walk — pre-populate `ctx[PREFETCHED_MATCH_ALIAS_PREFIX + alias] = [rec]` and re-execute the cached `SelectExecutionPlan` (MATCH compiles down to SelectExecutionPlan via `MatchExecutionPlanner`) for each alias the CREATED record could bind into. Plus edge-CREATED dispatch (a freshly-created vertex only appears in multi-alias tuples once its edges are created — edge records also flow through `addRecordOperation`). This is genuinely new infrastructure (constrained walker + edge dispatch); deserves its own ADR. The partial Etap B falls back to tombstone-on-CREATED for the multi-alias case, which restores eager-design parity (eager wiped on CREATED multi-alias).
- **Risks/Caveats**:
  - `returnProjector` correctness depends on the projection closure built at entry construction matching the original execution's projection semantics exactly. Track 6 step-g test validates equivalence vs fresh re-execution.
  - **MATCH_TUPLE_MULTI tombstone latency** — a CREATED on a multi-alias-pattern class tombstones the entry only at the NEXT lookup (when DeltaBuilder.buildForMatchMulti runs the tombstone pre-scan). Views constructed BEFORE that lookup continue iterating with their frozen `MatchMultiDelta` and won't see the new tuples — same I7 contract as RECORD shape views. Documented as expected; matches the `OrderByStep` blocking-materializer contract.
  - **`reverseIndex` memory** — `Map<RID, Set<Integer>>` per MATCH_TUPLE_MULTI entry. Bounded by `entry.results.size() × avg-aliases-per-tuple`. For Hub-typical 100-row × 3-alias tuples: ~300 entries per `reverseIndex`. Per-entry overhead ~20-50 KB.
  - **Self-join / self-loop patterns** (e.g., `MATCH {as:u, class:User}.out('reportsTo'){as:m, class:User}`) — a record can bind to BOTH alias `u` AND alias `m` in the same tuple. UPDATED dispatch iterates all aliases the record's class matches; if any alias's WHERE fails for the post-update record, the tuple is dropped. Multi-alias-same-class is correctly handled by the alias-iteration loop.
- **Implemented in**: Track 6 (Etap A + partial Etap B).
- **Full design**: design.md § MATCH Etap A — RECORD-shape composition AND § MATCH multi-alias (partial Etap B in v1)

#### D9: Deterministic ORDER BY admission (modifier-chain supported)

- **Alternatives considered**: plain-identifier-only ORDER BY (loses caching for `ORDER BY lower(name)`); allow any ORDER BY expression (would require grammar extension — current grammar accepts only `Identifier [Modifier]`); allow deterministic modifier-chain ORDER BY (chosen).
- **Rationale**: `SQLOrderByItem` carries an alias `String` plus an optional `SQLModifier` chain. Reusing `NonDeterministicQueryDetector` on each item's `modifier` gives a clean admission gate. The ORDER BY comparator runs at delta-build time (sorting inject_list) and at first-execution time (sorting fresh results from storage) — both must give consistent ranks across the entry's lifetime, hence the determinism gate.
- **Risks/Caveats**: per-comparator-call `modifier.execute(...)` adds CPU vs direct field lookup — bounded by inject_list size × log(inject_list size). Acceptable trade-off for the cache hits saved.
- **Implemented in**: Track 7 (classify-gate). `OrderByComparator` from Track 4 already delegates ranking to `SQLOrderByItem.compare`.

#### D11: Pre-expand `fromClasses` to subclass closure at entry construction (`effectiveFromClasses`)

- **Alternatives considered**: per-mutation `isSubClassOf` loop over raw `fromClasses`; pre-expanded closure stored as `effectiveFromClasses` (chosen, justified by I8); cache `SchemaClass` references (no benefit).
- **Rationale**: I8 guarantees schema is immutable per tx, so the closure computed via `SchemaClass.getAllSubclasses()` at entry construction is stable for the entry's lifetime. The polymorphism gate at delta-build time becomes a single O(1) `Set<String>.contains(record.class.name)`. Field name makes "is a closure, not raw FROM names" self-documenting.
- **Risks/Caveats**: `SchemaClass.getAllSubclasses()` cost at construction is `O(subclass count)` — acceptable since it happens once per entry. If I8 is ever relaxed, the closure becomes stale; Track 7 assert is the canary.
- **Implemented in**: Track 4 step 1 (capture `effectiveFromClasses` via closure expansion).
- **Full design**: design.md §"Lazy merge-on-read" → TxDeltaCursor (step 1: Class filter)

#### D12: AST identity fast-path on cache lookup

- **Alternatives considered**: deep `SQLStatement.equals()` on every lookup (baseline); identity (`==`) fast-path before deep equals (chosen); pre-canonicalized text key (loses D2's whitespace/alias-invariance).
- **Rationale**: `SQLEngine.parse()` is backed by `STATEMENT_CACHE` (size-bounded LRU keyed by raw SQL text). Same text reissued returns the **same `SQLStatement` instance** — `==` identity. Cache-key comparison can short-circuit. For DNQ workloads with thousands of duplicate-text queries, collapses lookup cost from deep-AST-walk to a pointer compare plus parameter-map equality.
- **Risks/Caveats**: identity fast-path is purely an optimization — correctness fall-through to deep equals preserves D2's semantics. Risk localized to `CacheKey.equals` implementation: identity comparison must NOT replace deep equals, only precede it. Track 2 test verifies both paths.
- **Implemented in**: Track 2 (`CacheKey.equals(Object)` body).

#### D13: Hub-replay validation gate (pre-merge)

- **Alternatives considered**: ship on synthetic JMH alone (baseline); ship after Hub-workload replay validates lazy coverage (chosen, justified by D5-lazy K1-NONE-coverage shift).
- **Rationale**: Under lazy + D18, the relevant coverage metric is "what fraction of queries classify as cacheable" (every shape including K0_NONE under D18's version gate). Before Hub deployment, Track 8 will record an anonymized DNQ-emission sample from a Hub staging environment (single tx, ~1000 queries) and replay against the cache; pass criteria: ≥70% of repeat-shape queries classify cacheable AND view output matches fresh execution across the recorded mutation sites. Also measures: per-query delta-build cost (informs per-class-index v2 decision), WHERE re-evaluation hot RIDs (informs per-RID memoization v2 decision), MIN/MAX extremum-churn frequency (informs D14 sorted-value index v1.1/v2 decision), MATCH Etap B coverage (informs whether the separate Etap-B ADR is high-priority), paginated-workload share of K0_NONE invalidation rate under typical write patterns (informs whether class-scoped K0_NONE invalidation is needed in v1.1).
- **Risks/Caveats**: replay requires DNQ-query-log capture from staging — coordinate with Hub team. If cacheable coverage falls below 70%, follow-up classify-relaxation work (e.g., LET support, multi-alias MATCH via Etap B) becomes higher priority.
- **Implemented in**: Track 8 (JMH harness extended with a `HubReplay` scenario).

#### D14: MIN/MAX sorted-value index — v2-deferred, gated on D13 measurement

- **Alternatives considered**:
  - **O(n) recompute when extremum leaves at delta-build time (chosen for v1)** — worst case O(`maxRecordsPerEntry`) = O(10000) when the cached extremum is removed / transitions out of WHERE / updated to a non-extremum value. `AggregateState` carries `extremumRid: @Nullable RID`; `was_extremum = rid.equals(extremumRid)` (RID identity, never `Number.equals`) sidesteps the cross-`Number`-subtype hazard at zero memory cost.
  - `TreeMap<BigDecimal, Set<RID>>` sorted index on `AggregateState` for MIN/MAX giving O(log n) per-op — v2 candidate, not v1.
- **Rationale (v1 baseline)**: under Hub-typical workloads the v1 baseline performance is fine in absolute terms. Cost-benefit analysis: 5-20 MIN/MAX queries per request × 100-1000 contributors × 1-5 mutations × ~1/n extremum-hit rate ≈ ~500 ops per request worst case (~5 μs at 10 ns/op). Sorted index would reduce to ~9 ops (~90 ns). Saved time per HTTP request: ~5 μs against typical hundreds-of-ms response. Memory cost: ~3× growth per MIN/MAX entry (TreeMap + per-value Set buckets + BigDecimal storage). Trade-off does not justify v1 promotion absent measurement showing pathological extremum churn.
- **Decision gate**: D13 Hub-replay measures extremum-churn frequency. Promote to v1.1 hardening if either (a) MIN/MAX recompute appears in >5% of delta-build cost histogram, or (b) churning-extremum workload (worker queue "next priority" patterns) is identified in DNQ emission. Otherwise stays v2.
- **Risks/Caveats (when promoted)**:
  - **Numeric coercion to `BigDecimal`** — `Long.valueOf(5L).equals(Integer.valueOf(5))` returns `false` in Java; mixing boxed `Number` subtypes corrupts the sorted index's key identity. Coerce via `new BigDecimal(value.toString())` at observe-time (string round-trip is the only path that preserves cross-subtype mathematical equality; `BigDecimal.valueOf(double)` rejects non-double inputs). Note: this hazard does NOT exist in the v1 baseline because the v1 design uses RID identity, not numeric equality, to track the extremum.
  - **~3× memory growth** for `AGGREGATE_MIN` / `AGGREGATE_MAX` entries (TreeMap + per-value `Set<RID>` buckets + per-RID `BigDecimal` instances). Hub typically has <50 MIN/MAX entries per tx; at the 10k contribution cap, growth is ~1.5 MB per entry vs ~700 KB baseline. Absolute cost is small but non-zero.
  - **`AggregateState.copy()` cost** — copying the TreeMap + each bucket Set is O(n log n). Replaces the O(n) shallow-copy of the prior `contributingValues` map. Net additional cost per view-construction is sub-millisecond at typical n; acceptable.
- **Implemented in**: deferred. Decision gate: D13 measurement; v1.1 if measurement justifies, else v2 candidate.

#### D15: `TxDeltaCursor` snapshot at view construction; not refreshed mid-iteration

- **Alternatives considered**: rebuild deltaCursor on every `view.next()` (consistent with live recordOperations but introduces moving-target semantics, mid-iteration mutations could cause duplicate/missing rows); snapshot at view construction (chosen).
- **Rationale**: Matches the existing `OrderByStep` blocking-materializer contract — materialized result sets don't reflect mid-iteration mutations. Eliminates "moving target under iterator" failure modes that the eager K1 sharp-merge design solved with fail-fast `IllegalStateException`. The natural refresh boundary is `query()` itself — every new `query()` call constructs a fresh view with a fresh delta snapshot. Application code that wants "read its own writes" after a mutation just issues a new `query()`, which is the same mental model as SQL-standard REPEATABLE READ.
- **Risks/Caveats**: views started before a mutation will not see that mutation. Documented behavior; same as uncached `OrderByStep` already does. No correctness hazard — consumers who want fresh state issue new query.
- **Implemented in**: Track 4 (`DeltaBuilder.buildForRecord` returns a snapshotted cursor; view holds the reference and never refreshes).
- **Full design**: design.md §"Pause/resume mechanics" → Mid-iteration mutation

#### D18: K0-version-fallback for NONE shapes — cache complex queries while no mutation occurs

- **Alternatives considered**:
  - **Never cache NONE (prior baseline)** — eliminates cache benefit for complex queries permanently, even in pure-read transactions. Mismatch analysis against LDBC SNB workload showed 19 of 20 queries fall into NONE due to LET (`IC1`, `IC10`), GROUP BY (`IC3`-`IC7`, `IC12`), $matched / $depth (`IS2`, `IS7`, `IC1`, `IC3`, `IC5`, `IC6`, `IC10`, `IC11`), or multi-alias MATCH without `class:` on every node (`IS1`, `IS3`, `IS5`, `IS8`, `IC2`, `IC8`). Under prior NONE rules, warm-tx replay benefits limited to `IS4` (the only RECORD shape). For analytical workloads and Hub read-mostly fragments this is a large missed opportunity.
  - **Eager K0 (pre-pivot design — rejected at lazy-pivot)** — invalidate all entries on any mutation. Too aggressive globally; loses lazy delta-build benefits for RECORD / AGGREGATE / MATCH_TUPLE_MULTI shapes that Hub workload depends on.
  - **K0-version-gate scoped to NONE entries only (chosen)** — populate K0_NONE entries with `entry.populateMutationVersion = tx.mutationVersion` at populate time; at lookup, compare `tx.mutationVersion` against the stamp. Equal: cache hit (pure replay; correctness trivial because no mutation has occurred). Diverged: invalidate this entry, fall to miss, repopulate at the new version. Cacheable shapes (RECORD / AGGREGATE / MATCH_TUPLE_MULTI) are unaffected — their delta-build path is what handles their mutation reconciliation, and `populateMutationVersion` is unused on those entries.
  - Class-scoped K0 invalidation (extract `effectiveFromClasses` for K0_NONE entries; invalidate only when mutation class intersects) — better precision, more complex extraction (NONE shapes have variable FROM extraction depth: inner subqueries, nested MATCH, LET-based unions). v2 candidate, gated on D13 measurement of cross-class invalidation frequency.
- **Rationale**: classify returns NONE for shapes where the delta-build cannot reconcile mutations with the cached result. The pre-D18 conclusion was "therefore don't cache at all". D18 separates "cannot reconcile" from "cannot cache": reconciliation is unnecessary when no mutation has happened. For pure-read tx (LDBC analytical queries, Hub page-render fragments with no writes), `tx.mutationVersion` stays at populate-time value throughout, so every repeated `query()` after the first is a cache hit even for complex queries (LET-laden, GROUP BY, $matched-using). For read-mostly tx (Hub typical: a few writes amid many reads) K0_NONE entries hit until the first write, then re-populate on next call — partial benefit proportional to the read-fraction. Coverage delta: pre-D18 LDBC warm-tx coverage = 5% (IS4 only). Post-D18 LDBC warm-tx coverage = 100% (every query benefits from cache hit on repeat, with the K0 gate handling correctness).
- **Risks/Caveats**:
  - **Coarse invalidation in mixed-write tx.** v1 D18 invalidates ALL K0_NONE entries when ANY mutation occurs in tx, even mutations on classes unrelated to the K0_NONE query's data. For pure-read or sparsely-written tx the overhead is hypothetical; for write-heavy fragments the K0_NONE entries become useless after the first mutation. The `nonCacheableKeys` route after 3 invalidations bounds the memory churn. Class-scoped invalidation (v2 hardening, see Open questions) reduces this overhead by extracting `effectiveFromClasses` from inner subquery / MATCH structure for K0_NONE entries.
  - **Memory cost of K0_NONE entries.** Complex queries can produce large result sets (an IC1-shaped outer SELECT could return hundreds of rows including projected sub-results). K0_NONE entries respect `maxRecordsPerEntry`; overflow removes the entry and adds its key to `nonCacheableKeys` per L7. Worst-case bound unchanged: `maxEntries × maxRecordsPerEntry × Result_ref_size`.
  - **Population path simplification, not extension.** K0_NONE entries use a simpler populate path than RECORD: no delta-cursor, no skip-set, no sorted-merge in `CachedResultSetView.next()`. The view iterates `entry.results`, falling through to `entry.stream` when local position outruns the cached list — same lazy stream-pull mechanism, but without the dispatch table from § Lazy merge-on-read → TxDeltaCursor. Smaller code path, easier to validate. Track 4 step adds the K0_NONE branch in `CachedResultSetView.next()` after the existing RECORD branch.
  - **Aggregate-inside-K0_NONE shapes.** A `SELECT COUNT(*) FROM Person GROUP BY age` classifies as K0_NONE because GROUP BY is the disqualifier; the inner COUNT(*) is structurally AGGREGATE_COUNT but classify sees GROUP BY first. The K0 mechanism caches the whole thing; the inner aggregate's delta-build benefit (would have been usable in absence of GROUP BY) is lost for that query shape. Compositional classify (recognize cacheable sub-shapes inside K0_NONE outer) is v2+ territory.
  - **Exception during populate.** Same as RECORD: exception bubbles to consumer; entry's stream is closed by next tx-end hook. K0_NONE populate wraps in try / cache.put-on-success-only — mirrors SO4 fix for AGGREGATE eager-drive. On exception, the entry never enters `entries`, no partially-populated K0_NONE entry can serve a future hit.
  - **`cacheCodeDepth` re-entrancy.** K0_NONE lookups respect `cacheCodeDepth > 0` bypass (SO5 invariant) same as cacheable shapes. UDFs invoking nested `session.query()` during outer K0_NONE iteration get fresh uncached `LocalResultSet`s; the outer entry's `populateMutationVersion` is unaffected by the nested call.
- **Implemented in**: Track 4 (extends `ShapeClassifier.classify` to route SKIP/LIMIT and other delta-unreconcilable shapes to `K0_NONE`; extends `CachedEntry` with `populateMutationVersion: long` and `k0InvalidationCount: int` fields; extends `QueryResultCache.lookup` with version-gate branch; extends `CachedResultSetView.next()` with K0_NONE branch (lazy stream-pull without delta). Track 7 adds the `k0NoneInvalidationThreshold` knob to `GlobalConfiguration`. Track 8 adds K0_NONE-specific metrics: `k0NoneHits`, `k0NoneInvalidations`, `k0NoneShortCircuits` (when `nonCacheableKeys` short-circuits a previously-churned key).
- **Full design**: design.md § Cache invalidation → K0-version-fallback for NONE shapes

### Invariants

- **I1** — Cache cleared on every tx-end path (commit, rollback, close). Enforced by single hook in `clearUnfinishedChanges()`. Test: T1.
- **I2** — Cache MUTATION paths (`lookup`, `put`, `invalidateAll`, begin-time `clear()`) accessed only by owning thread. Enforced via existing `assertOnOwningThread()` guards in `FrontendTransactionImpl` at lines 165 (`beginInternal`), 224 (`commitInternalImpl`), 250 (`getRecord`), 474 (`deleteRecord`), 511 (`addRecordOperation`). Tx-end `clear()` is the explicit exception, covered by I6. Test: T1.
- **I3** — Paused `ExecutionStream` in a `CachedEntry` is closed when the entry is evicted or the tx ends. Test: T3.
- **I4** — View output equals fresh-execution result composed with tx-delta-applied snapshot: WHERE / ORDER BY / LIMIT honored against (cached + tx-delta). Test: T4 (RECORD), T5 (AGGREGATE), T6 (MATCH Etap A).
- **I5** — Non-deterministic queries (denylist hit or `NOCACHE` hint) never produce a cache entry and never hit a cache entry. Test: T7.
- **I6** — Tx-end `clear()` is idempotent and safe under cross-thread invocation. `QueryResultCache.clear()` and `CachedEntry.close()` are idempotent by local null-out. The underlying `ExecutionStream.close(ctx)` may not be idempotent across all impls; the cache defends by wrapping every stream in `IdempotentExecutionStream` at cache-put time and threading the wrapper into both `entry.stream` and the paired `LocalResultSet`'s stream slot, so cross-caller double-close (closeActiveQueries + cache.clear at tx-end) reaches the wrapper and is safe regardless of underlying impl. Tests: T1 (cache.clear idempotency), T3e (single-caller entry.close idempotency), T3f (cross-caller closeActiveQueries + cache.clear with non-idempotent underlying mock).
- **I7** — View's `TxDeltaCursor` (or `deltaAggregateState`) is immutable post-construction. Guarantees the SET of RIDs emitted by the view and their relative order, NOT property-level snapshot isolation (cached `Result` wraps a record reference; mid-tx `save()` mutates the reference in place — both cache-cursor and stream-pull observe post-mutation property values). Stream-pull-append consults `deltaCursor.shouldSkip` so set+order remain correct under property mutation. Matches `OrderByStep` blocking-materializer contract. Test: T4i (mid-iteration mutation does not change the emitted RID set of the current view; fresh view's set reflects the mutation).
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
- Delta-build for aggregate shapes other than `COUNT(*)`, `SUM(prop)`, `AVG(prop)`, `MIN(prop)`, `MAX(prop)` over a plain property. `GROUP BY`, `HAVING`, expression-aggregates (`SUM(a+b)`), `COUNT(DISTINCT col)`, `MEDIAN`, `MODE`, `PERCENTILE` classify as `K0_NONE` in v1 — cacheable under D18's version gate (pure-read repetition hits cache; any mutation invalidates) but not reachable by AGGREGATE delta-build. Delta-build for these shapes is v2+.
- MATCH `CREATED` **multi-alias** discovery (Etap B proper) — constrained pattern walk on a single new record across edge traversals, plus dispatch on edge-CREATED. v1 caches multi-alias MATCH as `MATCH_TUPLE_MULTI` (DELETED + UPDATED via reverseIndex; CREATED on a pattern class tombstones the entry and forces re-execute on next lookup). Full constrained-pattern-walk discovery on CREATED **belongs in a separate ADR** — adds a constrained-pattern-walk path via `MatchPrefetchStep` + `PREFETCHED_MATCH_ALIAS_PREFIX` and an edge-CREATED dispatch hook on `addRecordOperation` for edge records. Scope is comparable to the rest of YTDB-820; D13 replay measures multi-alias-MATCH-CREATED frequency to prioritise that ADR.
- Delta-build for LET-based unions (`SELECT EXPAND($u) LET ..., $u = unionall($a, $b)`) — classifies as `K0_NONE` (D18 version-gate caches; mutations invalidate). LET-aware delta-build is v2+.
- Per-entry per-RID WHERE-evaluation memoization — v2 optimization, gated on D13 measurement of WHERE re-evaluation cost.
- Per-class indexing of `recordOperations` for O(p) delta-build (vs O(N)) — v2 optimization, gated on D13.
- Delta-build for SKIP / LIMIT-bounded queries. v1 routes any query carrying SKIP or LIMIT to K0_NONE under D18's mutation-version gate; pure-read repetition hits cache, any tx-write invalidates. Reconciling a LIMIT-bounded window with mid-tx mutations would require either an over-fetch mechanism that mutates `LimitExecutionStep`/`OrderByStep.maxResults` post-plan-construction or a cross-page entry-sharing scheme; both were considered and rejected for v1 because `OrderByStep`'s bounded heap is sized at planner-construction time and resists post-build mutation, and because cross-page sharing was prone to silent short-list when the entry didn't actually hold the required window. Cacheable delta-build for SKIP / LIMIT shapes is a v2 candidate gated on D13 paginated-workload measurement.
- **`NOCACHE` hint extension to MATCH** — the grammar accepts it only on SELECT (`YouTrackDBSql.jjt:1245` MATCH production lacks the token); MATCH's narrower non-determinism surface is fully covered by `NonDeterministicQueryDetector`'s built-in denylist. v2 candidate gated on D13 measurement. See design.md §"Non-determinism handling" → MATCH NOCACHE asymmetry for the full rationale.
- **Sub-statement caching (LET sub-expressions + $matched bindings)** — separate ADR, future work. Two complementary mechanisms operating below the statement boundary that v1's statement-level cache cannot reach. (1) **LET sub-expression cache**: for shapes like `SELECT … FROM (…) LET $X = (SELECT … WHERE @rid = $parent.$current.someRid)`, each outer row resolves `$parent.$current.someRid` to a concrete RID; the LET subquery becomes a well-formed standalone SELECT that classifies as RECORD. Cache key: `(synthesized SQLStatement after binding substitution, outer params)`. Hits when the outer query repeats or the same binding RID appears across outer rows. (2) **$matched binding cache**: for sub-patterns referencing `$matched.<alias>.<field>`, the executor substitutes the concrete binding value at iteration time. Synthesizing the post-substitution sub-pattern as a cache key gives per-binding memoization (cost-aware admission required — cache.lookup overhead vs sub-execution cost). Both require executor-step integration (`LetExpressionStep` and pattern-step hooks) rather than v1's `DatabaseSessionEmbedded.query()` hook; the ADR scope is comparable to YTDB-820 itself. D13 measures Hub workload's LET / $matched frequency to prioritise. Particularly impactful for LDBC analytical queries that derive most cost from per-row correlated sub-execution (IC1, IC10). See design.md § Open questions deferred to execution → Sub-statement caching for the full sketch and trade-offs.

## Checklist

- [ ] Track 1: Skeleton — knobs, data structures, lifecycle wiring
  > Lay down the foundational pieces with no behavioral change: four `GlobalConfiguration` knobs, new `QueryResultCache`, `CachedEntry`, `TxDeltaCursor` types (skeletons or no-op), `queryResultCache` field on `FrontendTransactionImpl`, and the begin/clear lifecycle hooks. After this track the cache exists, is allocated lazily when enabled, and is correctly wiped on every tx-end path; no `query()` reads or writes it yet.
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
  > Implement `ShapeClassifier` RECORD / `K0_NONE` classify (with SKIP/LIMIT routed to K0_NONE), `DeltaBuilder.buildForRecord` (class-filtered single-pass over `recordOperations` producing skip-set + sorted inject-list), `CachedResultSetView` sorted-merge for RECORD plus the K0_NONE branch (lazy stream-pull, no delta, no plan-rewrite), and the D18 version-gate at `QueryResultCache.lookup`. Polymorphism (D11) and the dispatch table for `(op.type, cached, match_after)` fold into the RECORD path. Detail in `plan/track-4.md`.
  > **Scope:** ~6 steps covering `ShapeClassifier` RECORD + K0_NONE (SKIP/LIMIT routing), `DeltaBuilder.buildForRecord`, `TxDeltaCursor` sorted-merge + K0_NONE branch in `CachedResultSetView.next()`, polymorphism integration, D18 version-gate + `k0InvalidationCount` + `nonCacheableKeys` threshold, full test matrix (CREATED/UPDATED/DELETED × cached/not × match_after × ORDER BY, plus K0_NONE coverage including SKIP/LIMIT hit/invalidate scenarios).
  > **Depends on:** Tracks 2, 3

- [ ] Track 5: Aggregate delta — AGGREGATE_* shapes
  > Extend `ShapeClassifier` to AGGREGATE_COUNT/SUM/AVG/MIN/MAX, splice `AggregateCacheTapStep` upstream of `AggregateProjectionCalculationStep` to populate `AggregateState`, and reuse the `DeltaBuilder` pattern for copy-and-replay of `applyMutation` at view construction. Detail in `plan/track-5.md`.
  > **Scope:** ~5 steps covering `AggregateState` with `copy`/`applyMutation`, `AggregateCacheTapStep`, plan-rewrite splice, `DeltaBuilder.buildForAggregate`, aggregate-shape view, full test matrix (COUNT/SUM/AVG/MIN/MAX × CREATED/UPDATED/DELETED × transition cases × MIN/MAX recompute).
  > **Depends on:** Track 4

- [ ] Track 6: MATCH delta — Etap A (single-alias as RECORD) + partial Etap B (MATCH_TUPLE_MULTI)
  > Extend `ShapeClassifier` to classify single-alias MATCH (Etap A) as RECORD with a `returnProjector` built from the RETURN clause at entry construction, AND multi-alias / pattern-with-edges MATCH as MATCH_TUPLE_MULTI with per-tuple `reverseIndex` + per-alias `aliasClasses` / `aliasWheres`. Implement `DeltaBuilder.buildForMatchMulti` covering DELETED (skip affected tuples) + UPDATED (re-eval alias WHEREs, skip on fail) + CREATED-on-pattern-class (tombstone entry → force re-execute). Patterns with classless nodes / cross-alias-state / subqueries in pattern WHEREs classify as `K0_NONE` (cacheable under D18's version gate; not reachable by MATCH delta-build). Detail in `plan/track-6.md`.
  > **Scope:** ~10 steps covering Etap A classify + projector + tests, MATCH_TUPLE_MULTI classify rules + entry metadata, reverseIndex population during stream pull, `DeltaBuilder.buildForMatchMulti` (tombstone pre-scan + tupleSkipSet + ridSkipSet), `MatchMultiDelta` class, view branching for multi-shape, full test matrix (Etap A DELETE/UPDATE/CREATE; partial Etap B DELETE/UPDATE-pattern-WHERE-fail/UPDATE-pattern-WHERE-pass/CREATED-tombstone × self-loop/cross-class/cross-join × multi-alias-same-class; classify `K0_NONE` for cross-alias-state / classless-node / pattern-WHERE-subquery shapes).
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
- [x] Plan review (consistency + structural) — re-run iteration 2 after Opcja B retreat (Mutation 17), passed

**Re-run consistency review (CR10-CR14, on top of prior CR1-CR9 + Mutations 11-16)**:
- **CR10** (blocker, design-decision, RESOLVED via Opcja B): the D10-lazy over-fetch mechanism + D17 per-plan-shape cap were built on a false premise — `OrderByStep` is constructed with `maxResults = skip + limit` at planner-construction time (`SelectExecutionPlanner.handleOrderBy` lines 2030-2065) and uses a bounded top-N min-heap (`OrderByStep.initBoundedHeap` lines 130-180); mutating the downstream `LimitExecutionStep` post-plan-construction does not reshape the upstream heap. For blocking-sort plans the cache could not actually over-fetch, leaving the LIMIT-after-DELETE short-list hazard unresolved and breaking the SKIP-stripped cross-page sharing scheme (D16). User resolution: **Opcja B** — `ShapeClassifier.classify` now routes any query carrying SKIP or LIMIT to `K0_NONE` (D18 mutation-version gate) instead of attempting to share entries or rewrite plans. The cache executes the parsed plan as-is; correctness is preserved by D18's invariant (cache hits only while `tx.mutationVersion == entry.populateMutationVersion`). Coverage trade-off: paginated workloads still cache during pure-read scrolling but invalidate on the first tx-write; analytical workloads with stable read sets get full benefit. Architectural simplification: D10-lazy, D16, D17 deleted from Decision Records; `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY_FOR_BLOCKING_SORT` knob removed; `CacheableShape.HARD_NONE` removed (subsumed by L7 overflow → `nonCacheableKeys`); plan-rewrite logic removed from Track 4 (no `LimitExecutionStep`/`SkipExecutionStep` mutation); CacheKey reverts to strict delegation to `SQLStatement.equals` (no field-by-field walk).
- **CR11** (mechanical, RESOLVED): design.md § Open questions referenced a non-existent class `MatchExecutionPlan`. The class is `SelectExecutionPlan` (MATCH compiles to SelectExecutionPlan via `MatchExecutionPlanner`). Fixed in design.md and plan D8-lazy CREATED-discovery rationale.
- **CR12** (mechanical, RESOLVED by CR10 cascade): `LimitStep`/`SkipStep` references throughout plan/design/tracks were phantom (actual classes are `LimitExecutionStep` / `SkipExecutionStep`). With Opcja B the plan-rewrite logic is removed entirely, eliminating every site that referenced these classes. No rename needed.
- **CR13** (should-fix, design-decision, RESOLVED by CR10 cascade): `LimitExecutionStep.limit` and `SkipExecutionStep.skip` are `private final SQLLimit`/`SQLSkip` fields. The "mutate LimitStep.limit" wording in the prior design was misleading. With Opcja B no plan-rewrite occurs, so the implementation-strategy question (replace-in-list vs reflective swap vs setter) is moot.
- **CR14** (blocker, mechanical, RESOLVED): D16 field list for `SQLMatchStatement.equals` claimed 5 fields (`matchExpressions`, `returnItems`, `limit`, `orderBy`, `groupBy`); actual implementation at `SQLMatchStatement.java:507-550` covers 11 fields (the missing 5: `notMatchExpressions`, `returnAliases`, `returnNestedProjections`, `unwind`, `returnDistinct`). With Opcja B `CacheKey.equals` now delegates strictly to `SQLStatement.equals` so the field list is correct by construction. Track 2 T2e test scope updated to enumerate all 11 SQLMatchStatement fields (and all 13 SQLSelectStatement fields) for AST-equals regression coverage.

**Mutation 17 summary (Opcja B retreat)**:
- Decision Records deleted: D10-lazy (over-fetch), D16 (canonical CacheKey strip SKIP), D17 (per-plan-shape over-fetch cap).
- Component Map: `CacheableShape` enum loses `HARD_NONE`; ShapeClassifier description updated for "SKIP/LIMIT → K0_NONE first gate"; `GlobalConfiguration` knob list reduced from 5 to 4 (drops `MAX_RECORDS_PER_ENTRY_FOR_BLOCKING_SORT`); CacheKey description simplified to "delegate `equals`/`hashCode` to `SQLStatement.equals`/`hashCode` with D12 identity fast-path".
- design.md: § Cache key composition → Canonical key for SKIP subsection removed; § Lazy merge-on-read → Over-fetch for backfill rewritten to "SKIP and LIMIT handling" without rewriting mechanism; § Per-plan-shape cap subsection removed; CacheableShape enum diagram drops HARD_NONE; Memory bounds drops blocking-sort cap; references to D10-lazy/D16/D17 removed from References footers.
- Track files: Track 1 knob list 5→4; Track 2 CacheKey custom equals replaced with strict delegation, T2e field-list updated to 13/11 fields, T2f-T2h updated to assert distinct entries per (SKIP, LIMIT); Track 4 deliverables drop plan-rewrite, T4h-T4h8 tests replaced with simpler T4f (no-LIMIT overflow), T4g (ORDER BY drain), T4k1-T4k5 K0_NONE coverage including SKIP/LIMIT scenarios; Track 6 MATCH classify drops SKIP/LIMIT cap gate (SKIP/LIMIT routes to K0_NONE); Track 8 QueryCacheMetrics drops `blockingSortOverCap` counter and related Hub-replay measurements.
- Non-Goals updated to reflect that delta-build for SKIP/LIMIT is v2 work, gated on D13 paginated measurement.

**Prior reviews (preserved for traceability — CR1-CR9, S1-S5, L1-L12, SO1/SO4/SO5/SO6, T1-T7, A1-A5)**:

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

**Architectural optimality pass (Mutation 16, non-typical review)**: a fifth-iteration review combined end-to-end logical walkthrough with architectural-optimality challenge. Verdict: ship after 5 tightening fixes. All applied:
- A1: track-7.md duplicate-8 numbering renumbered (full split into 7a/7b deferred — invasive).
- A2: cacheCodeDepth enumeration now explicitly brackets aggregate eager-drive (between cache.lookup and cache.put on AGGREGATE_* miss).
- A3: `entry.cachedDeltaVersion = -1L` sentinel pinned (avoids collision with mutationVersion=0).
- A4: design.md § TxDeltaCursor step ordering fixed — for MATCH Etap A, projector runs BEFORE sort.
- A5: mutationVersion increment at END of `addRecordOperation` (exception-safe semantics).

v2 candidates documented (deferred): remove diagnostic-only cachedRids, factor common helper across DeltaBuilder methods, AggregateEntry/RecordEntry subclasses, Track 7a/7b split.

**Tertiary-order pass (Mutation 15, post-re-re-review)**: a tertiary review surfaced 7 issues (T1-T7) — 1 blocker, 1 should-fix, 5 suggestions. All closed:
- T1 (blocker — CME): `DeltaBuilder.buildFor*` now snapshots `tx.recordOperations.values()` to ArrayList before iterating, preventing CME when WHERE-eval UDF calls save(). Test T4q.
- T2 (should-fix — nested begin): `mutationVersion = 0` reset gated on `txStartCounter == 0` (outermost begin only). Test T1f.
- T3 (suggestion): TxDeltaCursor consistently receives unmodifiable wrappers on both first-build and reuse paths.
- T4 (suggestion): `getMutationVersion()` declared public on concrete class, not on public interface.
- T5 (suggestion): self-healing stale-on-arrival invariant documented.
- T6 (suggestion): `clear()` owner-thread-only invariant documented (future cross-thread cleanup must null ref, not call clear()).
- T7 (suggestion): `cacheCodeDepth` increment-first-then-check ordering tightened; test T7n.

**Second-order pass (Mutation 14, post-re-review)**: a re-review after Mutation 13 surfaced 4 second-order issues (SO1, SO4, SO5, SO6) plus a cross-reference nit. All closed:
- SO1 (delta-cursor memory unbounded): adopted Option C — shared immutable `(skipSet, injectList)` pair per entry, keyed by `FrontendTransactionImpl.mutationVersion` counter (incremented on every `addRecordOperation` including type-collapse cases). Hub case: 1 shared pair per entry. Tests T4o (sharing via ref equality), T4p (UPDATE→DELETE collapse version sensitivity).
- SO4 (eager-drive exception safety): plan-drive inside try; `cache.put` only on success; throw leaves cache empty. Test T5l.
- SO5 (`inFlightLookup` scope): replaced boolean with `cacheCodeDepth: int` counter; incremented at every cache-mutating code path; re-entrant lookups bypass cache while depth > 0.
- SO6 (`nonCacheableKeys` lifecycle): `clear()` explicitly empties it; counter reset to 0.
- Cross-ref nit: D11's `Full design` link points to a real subsection heading.

**Logical correctness pass (Mutation 13)**: a deep logical review surfaced 12 findings (L1-L12), with L1+L2+L4+L12 sharing a common root cause — the design conflated "rows already pulled into entry.results" with "the cache's view of storage" under lazy stream-pull. Fix: stream-pull-append path now consults `deltaCursor.shouldSkip(rid)` just as the cache cursor does; the dispatch table's UPDATED/DELETED branches always add the RID to skip_set regardless of `cached_at_build`. Other findings closed:
- L3 (MIN/MAX empty-set semantics): explicit null/0 handling per SQL standard.
- L5 (MATCH returnProjector alias-binding): ctx.setVariable before SQLExpression.execute.
- L6 (splice failure fallback): re-route through statement.execute(...) on unexpected planner shape.
- L7 (overflow LRU churn): remove entry from map + per-tx nonCacheableKeys set.
- L8 (aggregate partial-populate hazard): eager drive plan on cache-put.
- L9 (re-entrant UDF query): inFlightLookup flag, nested calls bypass cache.
- L10 (SKIP empty-window): documented as matching fresh-execution behavior.
- L11 (I7 wording): tightened to specify set+order frozen, not property-level snapshot.

**Pre-existing structural debt observed (deferred)**:
- 26 should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — pre-existing house-style debt, +7 since Mutation 10 baseline (Mutation 11+12+13 prose adds em-dashes). Deferred to Phase 4 global sweep per `house-style.md § Em-dash discipline`.

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
