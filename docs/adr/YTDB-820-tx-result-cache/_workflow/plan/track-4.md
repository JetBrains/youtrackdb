# Track 4: Lazy delta core + RECORD shape

## Purpose / Big Picture

BLUF: After this track, cached SELECT queries without SKIP/LIMIT reflect intra-tx mutations (CREATE/UPDATE/DELETE) on relevant classes via per-query sorted-merge between the immutable cached list and a snapshot delta cursor built from `tx.recordOperations`. Queries that carry SKIP or LIMIT route through K0_NONE under D18's mutation-version gate.

Implement `ShapeClassifier.classify(stmt) → CacheableShape` returning RECORD for cacheable simple SELECT shapes (no SKIP, no LIMIT), K0_NONE for SKIP/LIMIT queries and other delta-unreconcilable shapes. Implement `DeltaBuilder.buildForRecord(entry, recordOps, ctx) → TxDeltaCursor`: iterate `tx.recordOperations`, class-filter by `effectiveFromClasses`, dispatch on `(op.type, cached, match_after)` to build skip-set + sorted inject-list. Implement `CachedResultSetView.next()` sorted-merge between cache cursor and `TxDeltaCursor` for RECORD; K0_NONE branch iterates `entry.results` and falls through to `entry.stream`. Polymorphism gate via `effectiveFromClasses` per D11. K0_NONE entries served under D18's `populateMutationVersion` gate.

This is the load-bearing track for the lazy architecture. Most of the design's complexity lives here.

## Context and Orientation

**Codebase state at track start.** After Tracks 1-3: cache scaffold + read path + pause/resume work. Views return correct results when no intra-tx mutations occur. This track adds the delta reconciliation.

Existing relevant code:
- `FrontendTransactionImpl.recordOperations: HashMap<RecordIdInternal, RecordOperation>` — line 83. `addRecordOperation(record, status)` line 510 — collapses multi-ops per RID (CREATE→DELETE = DELETE, CREATE→UPDATE = CREATE, UPDATE→DELETE = DELETE).
- `RecordOperation` — `byte type` (CREATED=3, UPDATED=2, DELETED=1), `RecordAbstract record` (post-mutation state; pre-state unavailable).
- `SQLWhereClause.matchesFilters(record, ctx)` — evaluates WHERE in memory.
- `SQLOrderBy` + `SQLOrderByItem.compare(a, b, ctx)` — comparator API.
- `SchemaClass.getAllSubclasses()` — for D11 closure expansion.

**Concrete deliverables.**
- `ShapeClassifier` static `classify(SQLStatement) → CacheableShape`. **First gate**: if `stmt.skip != null || stmt.limit != null` → return `K0_NONE` immediately. Subsequent checks apply only to queries without SKIP/LIMIT. Returns `RECORD` for SELECT statements that satisfy: single SQLFromClause with a class or class-list FROM, no GROUP BY, no aggregates in projection, no LET, no subqueries in WHERE/target, no `$current`/`$matched` in WHERE, ORDER BY items each pass deterministic-modifier-chain check (Track 7 finalizes this gate; this track stubs to "always admit modifier-chain"). Returns `K0_NONE` (D18) for SELECT/MATCH statements that fail one of the structural delta-build gates but are deterministically reproducible: any SKIP/LIMIT query (caught by first gate), GROUP BY, LET, subqueries in WHERE/target, MATCH with `$matched`/`$current`/`$depth`/`$parent` cross-alias-state references, MATCH with subqueries in pattern WHEREs, MATCH with LET/UNWIND, MATCH with any pattern node lacking `class:` (defeats polymorphism gate so delta can't class-filter but the query result itself is deterministic). Aggregates handled in Track 5; for Track 4 boundary, classify returns K0_NONE for aggregate shapes (Track 5 narrows to AGGREGATE_*).
- `OrderByComparator(SQLOrderBy, CommandContext)` building a `Comparator<Result>` that delegates per-item to `SQLOrderByItem.compare`. Handles ascending/descending and modifier chains.
- `CachedEntry` populated metadata: `effectiveFromClasses` (closure-expanded), `whereClause`, `orderBy`, `shape = RECORD`. Built at entry construction in `DatabaseSessionEmbedded.query()` miss path. No SKIP/LIMIT fields — RECORD queries do not carry them (those route to K0_NONE).
- `cachedRids: Set<RID>` populated alongside `entry.results` during stream pull.
- **No plan rewriting.** The cache executes the parsed plan as-is on every cache-miss. For RECORD/AGGREGATE/MATCH_TUPLE_MULTI shapes (which by definition carry no SKIP or LIMIT), the executor's natural stream feeds `entry.results` up to `maxRecordsPerEntry`; if the stream produces more, the entry overflows per the L7 path (remove from `entries`, add key to `nonCacheableKeys`). For K0_NONE shapes the executor runs the original plan including any SKIP/LIMIT it carries; the K0_NONE branch in `CachedResultSetView.next()` iterates the stored results directly with no view-level SKIP/LIMIT application.
- `DeltaBuilder.buildForRecord(entry, tx, ctx) → TxDeltaCursor`:
  - **Version check first**: `v = tx.getMutationVersion()`. If `entry.cachedDeltaVersion == v && entry.cachedSkipSet != null`: reuse `entry.cachedSkipSet` and `entry.cachedInjectList` (both immutable shared refs); return `new TxDeltaCursor(entry.cachedSkipSet, entry.cachedInjectList, injectPosition=0)`. Otherwise rebuild below and promote to entry cache. **Sentinel value**: `entry.cachedDeltaVersion` is initialized to `-1L` at construction (NOT 0L — `mutationVersion` starts at 0 too, and a `cachedDeltaVersion=0` default would collide with the first real `tx.mutationVersion=0` check, silently reusing a never-built pair).
  - **Snapshot first (T1 fix)**: `var snapshot = new ArrayList<>(tx.recordOperations.values())` — required because WHERE eval may invoke UDFs that call `session.save(...)`, which would structurally modify `tx.recordOperations` and trigger `ConcurrentModificationException` on direct iteration. Records added by UDF-triggered mutations during the build are visible only to the NEXT view (when mutationVersion has advanced).
  - Iterate the `snapshot`.
  - For each: class filter (`effectiveFromClasses.contains(record.getSchemaClass().getName())` + Entity-shape guard), WHERE eval, cache-membership check.
  - Dispatch table (from design.md § Lazy merge-on-read → TxDeltaCursor):
    - CREATED + match_after=true → inject_list.add (CREATED RIDs are temp; cached_at_build irrelevant)
    - CREATED + match_after=false → no-op
    - UPDATED + match_after=true → skip_set.add + inject_list.add (skip_set guards both cached prefix AND later stream pull)
    - UPDATED + match_after=false → skip_set.add (suppress any cache OR stream emission)
    - DELETED → skip_set.add (suppress any cache OR stream emission, regardless of cached_at_build)
  - Note: cached_at_build (entry.cachedRids.contains) is read for diagnostic / metrics purposes; it does NOT branch the dispatch, since the lazy stream-pull may still produce mutated RIDs from storage. The skip_set unifies cache-prefix and stream-pull filtering.
  - Sort inject_list by `OrderByComparator`. For no ORDER BY: append in iteration order.
  - Promote to entry cache: `entry.cachedSkipSet = unmodifiableSet(skipSet); entry.cachedInjectList = unmodifiableList(injectList); entry.cachedDeltaVersion = v`. Older versions held by live views' TxDeltaCursor refs stay reachable; once those views close, the old pair is GC'd.
  - Return `new TxDeltaCursor(skipSet, injectList, injectPosition=0)`.
- `CachedResultSetView.next()` sorted-merge logic for RECORD shape (see design.md § Lazy merge-on-read → view.next pseudocode). RECORD shape carries no SKIP/LIMIT (Mutation 17 routes any SKIP/LIMIT query to K0_NONE), so the view has no SKIP/LIMIT counter.
- **K0_NONE handling (D18)**: at populate, stamp `entry.populateMutationVersion = tx.getMutationVersion()` and initialize `entry.k0InvalidationCount = 0`. `QueryResultCache.lookup` adds a K0_NONE branch: if `entry.shape == K0_NONE`, compare `tx.getMutationVersion()` against `entry.populateMutationVersion`. Equal → return entry as hit (no delta build for K0_NONE; the view's K0_NONE branch iterates `entry.results` and falls through to `entry.stream` for backfill, no skip-set, no sorted-merge). Diverged → `entry.k0InvalidationCount++`, remove the entry from `entries`, call `entry.close()`, return null (miss). If `entry.k0InvalidationCount >= k0NoneInvalidationThreshold` (default 3, knob), add the key to `nonCacheableKeys` so subsequent lookups short-circuit. `CachedResultSetView.next()` for K0_NONE iterates `entry.results` (position-based), falling through to `entry.stream` via `stream_pull_one()` (no skip-set filter — K0_NONE has no delta) until exhausted. The original plan including any SKIP/LIMIT runs unchanged at populate time, so the executor already applied SKIP/LIMIT before the entry's results were captured.
- `DatabaseSessionEmbedded.query(...)` integration: after `cache.lookup` / `cache.put`, for RECORD-family shapes call `DeltaBuilder.buildForRecord(entry, tx.recordOperations, ctx)` and pass the resulting `TxDeltaCursor` to the view constructor. For K0_NONE shape, pass null delta — the K0_NONE view branch handles iteration without delta logic.

## Plan of Work

1. `ShapeClassifier.classify` — AST shape inspection. Initial scope: RECORD for cacheable SELECT shapes, NONE for everything else (aggregates and MATCH handled in Tracks 5-6 by extending classify).
2. `OrderByComparator` — wrap `SQLOrderBy` for use at sort time. Plain identifier and modifier-chain support (deterministic gate refined in Track 7).
3. Entry construction extension — capture `effectiveFromClasses` via D11 closure, `whereClause`, `orderBy`, `skip`, `limit`. Populate `cachedRids` during stream pull.
4. `DeltaBuilder.buildForRecord` — single pass over `recordOps`, class filter, WHERE eval, dispatch, sort. Use `entry.cachedRids` for cache-membership check.
5. `CachedResultSetView.next()` sorted-merge — replace Track 2's empty-delta logic. The merge algorithm is the one in design.md § Stream-pull dispatch unification → `view.next()` pseudocode. Critical point: when `cache_head == null` AND `!entry.exhausted`, the view MUST pull from `stream_pull_one()` BEFORE consulting `delta_head` — otherwise the view returns delta records ahead of not-yet-pulled storage records that sort before them, violating the sorted-merge invariant. Stream-pull-append path consults `deltaCursor.shouldSkip(rid)` for each pulled Result BEFORE appending to `entry.results`; skipped Results are dropped and the loop pulls the next one. RECORD shape carries no SKIP/LIMIT (Mutation 17 routes any SKIP/LIMIT query to K0_NONE), so the view has no SKIP/LIMIT counter.
6. Wire into `DatabaseSessionEmbedded.query()` — build delta on both miss (after entry populate) and hit paths.
7. Test matrix (T4 set):
   - T4a: CREATE matching WHERE — appears in view via inject; ORDER BY position correct.
   - T4b: UPDATE in-WHERE same-rank — view sees update via skip+inject; rank stable.
   - T4c: UPDATE crossing WHERE boundary (in→out, out→in) — both directions correctly reflected.
   - T4d: DELETE cached record — skipped from view; no NPE.
   - T4e: DELETE record not in cache — no-op; no spurious skip.
   - T4f: no-LIMIT natural overflow — `SELECT FROM Foo` against 50000 storage rows; classify=RECORD; consumer iterates; entry fills up to 10000 then triggers overflow at row 10001; entry removed from `entries`, key added to `nonCacheableKeys`; consumer continues receiving rows 10001..50000 directly from stream (uncached). Re-issue: `nonCacheableKeys` short-circuits to bypass.
   - T4g: ORDER BY natural drain — `SELECT FROM Foo ORDER BY x` (no LIMIT) against a class with no index on `x`. Plan contains `OrderByStep` which drains all upstream into its sort buffer; cache appends up to `maxRecordsPerEntry`. Verify entry holds min(matching rows, 10000) sorted Results; verify a subsequent cache hit returns the same rows in the same order.
   - T4k1 (D18 — K0_NONE pure-read replay, SKIP/LIMIT): `SELECT FROM Person SKIP 0 LIMIT 20` in tx with no mutations. First call: classify=K0_NONE (SKIP/LIMIT routes to K0_NONE), populate, `populateMutationVersion=0`. Second call (same query, no mutations between): lookup sees `tx.mutationVersion (0) == entry.populateMutationVersion (0)` → cache hit, view returns same 20 rows. Verify exactly one storage execution; second call returns from cache.
   - T4k1b (D18 — K0_NONE pure-read replay, GROUP BY): `SELECT FROM Person GROUP BY age` in tx with no mutations. Same hit pattern as T4k1.
   - T4k1c (D18 — K0_NONE distinct entry per (SKIP, LIMIT)): `SELECT FROM Person SKIP 0 LIMIT 20` and `SELECT FROM Person SKIP 20 LIMIT 20` populate distinct K0_NONE entries. Verify `cache.size() == 2` after both queries; each subsequent identical query returns from its own entry.
   - T4k2 (D18 — K0_NONE mutation invalidates): same setup as T4k1. After first call, do `tx.save(newPerson)` → `tx.mutationVersion` increments to 1. Second call: lookup sees `1 != 0` → invalidate entry, fall to miss, repopulate. Verify `entry.k0InvalidationCount == 1` after second call's repopulate; second populate has `populateMutationVersion = 1`.
   - T4k3 (D18 — K0_NONE invalidation threshold): same setup, but do mutation+repeat 4 times. After third invalidation: cache key in `nonCacheableKeys`. Fourth call: lookup short-circuits via `nonCacheableKeys.contains(key)` → miss, no entry created. Verify `QueryCacheMetrics.k0NoneShortCircuits == 1`.
   - T4k4 (D18 — K0_NONE coexistence with RECORD): in one tx, run BOTH a RECORD-classify query (e.g., `SELECT FROM Person WHERE active=true` — no SKIP/LIMIT) AND a K0_NONE query (`SELECT FROM Person SKIP 0 LIMIT 20`). After mutation that affects Person: RECORD entry's view reflects the mutation via delta-build (existing behavior); K0_NONE entry is invalidated on next lookup. Verify both behaviors fire correctly in the same tx; cache entries don't cross-interfere.
   - T4k5 (D18 — K0_NONE overflow at cap): `SELECT FROM Foo LIMIT 50000` with `maxRecordsPerEntry=10000`. Classify=K0_NONE (SKIP/LIMIT routes there); populate begins; at row 10001 the entry overflows; entry removed from `entries`, key added to `nonCacheableKeys`. Consumer still receives all 50000 rows from the live stream. Re-issue: `nonCacheableKeys` short-circuits.
   - T4r (G1 regression — sorted-merge with un-pulled stream tail): schema `User(name STRING)` with `ORDER BY name ASC`; pre-existing storage rows `[Alice, Charlie, Dave, Eve]` (sorted). Cache miss on `SELECT FROM User ORDER BY name`. Before the consumer's first `view.next()`: `tx.save(new User(name = "Bob"))` so the delta inject_list = `[Bob_created]`. Consumer iterates view to exhaustion. Assert: `view` returns exactly `[Alice, Bob, Charlie, Dave, Eve]` in that order. Buggy pseudocode (pre-fix) returns `[Bob, Alice, Charlie, Dave, Eve]` because it would return `Bob` while `cache_head` was still `null` (entry.results empty) instead of pulling `Alice` from the stream first. Variant T4r2: `Bob` sorts before all (`name = "Aaron"`) → expected `[Aaron, Alice, Charlie, Dave, Eve]`. Variant T4r3: `Bob` sorts after all (`name = "Frank"`) → expected `[Alice, Charlie, Dave, Eve, Frank]`. All three variants exercise the materialise-cache-head-before-delta-compare invariant.
   - T4i (I7): mid-iteration mutation — assert current view does NOT see new mutation; fresh `query()` DOES see it.
   - T4j (D11 polymorphism): SELECT FROM Person sees Employee subclass mutations via effectiveFromClasses closure.
   - T4k (L1 regression): partial-populated entry + UPDATED on un-pulled storage record — second view does NOT emit the record twice (once from delta inject, once from stream-pull). Stream-pull-skip-set filter drops the storage emission; only the inject_list emission reaches the consumer.
   - T4l (L2 regression): partial-populated entry + UPDATED on un-pulled storage record where ORDER-BY key changed — second view emits the record EXACTLY ONCE at the post-update ORDER-BY position (from inject_list), not at the pre-update storage position.
   - T4m (L12 regression): partial-populated entry + DELETED on un-pulled storage record — second view does NOT emit the deleted record when the stream reaches it.
   - T4n (L10 empty-SKIP edge case): `SELECT FROM Foo SKIP 100 LIMIT 10` against empty class with 5 mid-tx CREATEs — view returns empty (the CREATEs don't push the count past SKIP, matching fresh-execution semantics).
   - T4o (SO1 / Option C — delta sharing): construct view-A on entry at `mutationVersion=5`; verify `entry.cachedDeltaVersion == 5` and the deltaCursor's skipSet+injectList are the same Java refs as `entry.cachedSkipSet/cachedInjectList`. Construct view-B (no further mutations between, version still 5); assert view-B's deltaCursor.skipSet IS view-A's deltaCursor.skipSet (reference equality, not just content equality). Then `session.save(record)` → version increments to 6. Construct view-C; assert view-C builds a fresh skipSet+injectList, `entry.cachedDeltaVersion == 6`, but view-A's frozen refs unchanged.
   - T4p (SO1 — UPDATE-then-DELETE collapse version sensitivity): construct view-A at version=2 (after one UPDATE). Then a second mutation flips UPDATE→DELETE on the same RID (recordOperations.size() unchanged but version increments to 3). Construct view-B; assert view-B's delta does NOT inject the now-DELETED record (proves the version key catches type-collapsing mutations that size alone misses).
   - T4q (T1 — CME under UDF re-entrancy): register a UDF that calls `session.save(otherRecord)` (which goes through `addRecordOperation` and mutates `tx.recordOperations`). Build delta on entry whose WHERE invokes the UDF on each record. Assert: (i) no `ConcurrentModificationException` thrown during build, (ii) the just-built delta does NOT include the otherRecord mutation (snapshot semantics — mutationVersion at build start, not build end), (iii) a subsequent view's build at the post-mutation version DOES include it.

**Invariants to preserve.** I4: view output equivalent to fresh execution against (cache + delta) snapshot. I7: view's deltaCursor immutable post-construction, and the cached `entry.results` / `entry.cachedRids` are append-only during stream pull, never mutated by tx state.

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/OrderByComparator.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/TxDeltaCursor.java` (complete from skeleton)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (metadata fields populated)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (full sorted-merge)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (delta build invocation)

**Out-of-scope files.**
- `AggregateCacheTapStep`, `AggregateState` — Track 5.
- `returnProjector` for MATCH — Track 6a.
- `NonDeterministicQueryDetector` ORDER-BY admission gate — Track 7 finalizes; this track stubs.

**Inter-track dependencies.**
- Depends on: Tracks 2, 3.
- Unblocks: Tracks 5 (aggregate), 6 (MATCH Etap A), 7 (hardening).

**Library / function signatures.**
- `ShapeClassifier.classify(SQLStatement) → CacheableShape`.
- `DeltaBuilder.buildForRecord(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → TxDeltaCursor`.
- `OrderByComparator(SQLOrderBy, CommandContext) → Comparator<Result>`.
- `TxDeltaCursor(Set<RID> skipSet, List<Result> injectList)`.
- `CachedResultSetView(CachedEntry, TxDeltaCursor, DatabaseSessionEmbedded)`.
