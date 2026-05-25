# Track 4: Lazy delta core + RECORD shape

## Purpose / Big Picture

BLUF: After this track, cached SELECT queries reflect intra-tx mutations (CREATE/UPDATE/DELETE) on relevant classes via per-query sorted-merge between the immutable cached list and a snapshot delta cursor built from `tx.recordOperations`. SKIP/LIMIT honored.

Implement `ShapeClassifier.classify(stmt) → CacheableShape` returning RECORD for cacheable simple SELECT shapes (including SKIP within cap) and NONE otherwise. Implement `DeltaBuilder.buildForRecord(entry, recordOps, ctx) → TxDeltaCursor`: iterate `tx.recordOperations`, class-filter by `effectiveFromClasses`, dispatch on `(op.type, cached, match_after)` to build skip-set + sorted inject-list. Implement `CachedResultSetView.next()` sorted-merge between cache cursor and `TxDeltaCursor`. Polymorphism gate via `effectiveFromClasses` per D11. LIMIT clipping at iteration. SKIP support via prefix-shape entries.

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
- `ShapeClassifier` static `classify(SQLStatement) → CacheableShape`. Returns RECORD for SELECT statements that satisfy: single SQLFromClause with a class or class-list FROM, no GROUP BY, no aggregates in projection, no LET, no subqueries in WHERE/target, no `$current`/`$matched` in WHERE, ORDER BY items each pass deterministic-modifier-chain check (Track 7 finalizes this gate; this track stubs to "always admit modifier-chain"), `SKIP n + LIMIT m <= maxRecordsPerEntry`. Otherwise NONE (with v1 caveat: aggregates → handled in Track 5; for Track 4 boundary, classify returns NONE for aggregate shapes).
- `OrderByComparator(SQLOrderBy, CommandContext)` building a `Comparator<Result>` that delegates per-item to `SQLOrderByItem.compare`. Handles ascending/descending and modifier chains.
- `CachedEntry` populated metadata: `effectiveFromClasses` (closure-expanded), `whereClause`, `orderBy`, `skip`, `limit`, `shape = RECORD`. Built at entry construction in `DatabaseSessionEmbedded.query()` miss path.
- `cachedRids: Set<RID>` populated alongside `entry.results` during stream pull.
- `DeltaBuilder.buildForRecord(entry, recordOps, ctx) → TxDeltaCursor`:
  - Iterate `recordOps.values()`.
  - For each: class filter (`effectiveFromClasses.contains(record.getSchemaClass().getName())` + Entity-shape guard), WHERE eval, cache-membership check.
  - Dispatch table (from design.md § Lazy merge-on-read → TxDeltaCursor):
    - CREATED + match_after=true → inject_list.add
    - UPDATED + cached=true + match_after=true → skip_set.add + inject_list.add
    - UPDATED + cached=true + match_after=false → skip_set.add
    - UPDATED + cached=false + match_after=true → inject_list.add
    - DELETED + cached=true → skip_set.add
  - Sort inject_list by `OrderByComparator`. For no ORDER BY: append in iteration order.
  - Return `new TxDeltaCursor(skipSet, injectList)`.
- `CachedResultSetView.next()` sorted-merge logic (see design.md § Lazy merge-on-read → view.next pseudocode). LIMIT clip enforced via returned-count counter; SKIP applied via initial position offset (`position = skip` at view construction for SKIP entries).
- `DatabaseSessionEmbedded.query(...)` integration: after `cache.lookup` / `cache.put`, call `DeltaBuilder.buildForRecord(entry, tx.recordOperations, ctx)` and pass the resulting `TxDeltaCursor` to the view constructor.

## Plan of Work

1. `ShapeClassifier.classify` — AST shape inspection. Initial scope: RECORD for cacheable SELECT shapes, NONE for everything else (aggregates and MATCH handled in Tracks 5-6 by extending classify).
2. `OrderByComparator` — wrap `SQLOrderBy` for use at sort time. Plain identifier and modifier-chain support (deterministic gate refined in Track 7).
3. Entry construction extension — capture `effectiveFromClasses` via D11 closure, `whereClause`, `orderBy`, `skip`, `limit`. Populate `cachedRids` during stream pull.
4. `DeltaBuilder.buildForRecord` — single pass over `recordOps`, class filter, WHERE eval, dispatch, sort. Use `entry.cachedRids` for cache-membership check.
5. `CachedResultSetView.next()` sorted-merge — replace Track 2's empty-delta logic. SKIP/LIMIT enforced at iteration.
6. Wire into `DatabaseSessionEmbedded.query()` — build delta on both miss (after entry populate) and hit paths.
7. Test matrix (T4 set):
   - T4a: CREATE matching WHERE — appears in view via inject; ORDER BY position correct.
   - T4b: UPDATE in-WHERE same-rank — view sees update via skip+inject; rank stable.
   - T4c: UPDATE crossing WHERE boundary (in→out, out→in) — both directions correctly reflected.
   - T4d: DELETE cached record — skipped from view; no NPE.
   - T4e: DELETE record not in cache — no-op; no spurious skip.
   - T4f: LIMIT clipping — CREATE pushing 11th match into LIMIT 10 results — visible count stays at 10 (correct per I4 caveat about short-list-after-DELETE).
   - T4g: SKIP + LIMIT — paginated query with mid-page CREATE shifts visible window.
   - T4h: SKIP + LIMIT exceeding maxRecordsPerEntry — classify returns NONE; query bypasses cache.
   - T4i (I7): mid-iteration mutation — assert current view does NOT see new mutation; fresh `query()` DOES see it.
   - T4j (D11 polymorphism): SELECT FROM Person sees Employee subclass mutations via effectiveFromClasses closure.

**Invariants to preserve.** I4: view output equivalent to fresh execution against (cache + delta) snapshot. I7: view's deltaCursor immutable post-construction. I11: cached entry never mutated.

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
- `returnProjector` for MATCH — Track 6.
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
