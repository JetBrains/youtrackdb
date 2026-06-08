<!-- workflow-sha: 8995acfc3b0c50453595911342427c60742617b4 -->
# Track 1: Read path + RECORD delta core
> Combines former Tracks 1–4 (skeleton, read path, pause/resume, RECORD delta core), consolidated for footprint sizing. Test IDs (T1, T2x, …) are unchanged from the former tracks.

## Purpose / Big Picture

BLUF: After this track, the per-tx query result cache exists, is allocated lazily when enabled, and is wiped on every tx-end path; repeated `query()` calls for the same SELECT/MATCH within a tx return cached results without re-executing storage; a shared resumable stream lets multiple consumers continue iterating from where the first view left off; and cached SELECT queries without SKIP/LIMIT reflect intra-tx CREATE/UPDATE/DELETE via a lazy sorted-merge against a version-filtered delta cursor. SKIP/LIMIT and other delta-unreconcilable shapes route through K0_NONE under a mutation-version gate. This is the seed track and the load-bearing track for the lazy architecture — most of the design's complexity lives in Stage 4.

## Stage 1 — Skeleton (knobs, data structures, lifecycle wiring)

BLUF: After this stage, the cache exists, is allocated lazily when enabled, and is correctly wiped on every tx-end path; no `query()` reads or writes it yet.

Lay down the foundational pieces with no behavioral change: four `GlobalConfiguration` knobs, new `QueryResultCache`, `CachedEntry`, `TxDeltaCursor` types (skeletons or no-op), `queryResultCache` field on `FrontendTransactionImpl`, and the begin/clear lifecycle hooks.

### Context and Orientation

**Codebase state at track start.** Existing relevant code:
- `FrontendTransactionImpl.java` — owns the `recordOperations: HashMap<RecordIdInternal, RecordOperation>` (line 83), `assertOnOwningThread` guard (line 133), `beginInternal` (line 164), `clearUnfinishedChanges` (called from `clear()` → `close()` / `rollbackInternal()`).
- `GlobalConfiguration` — registry for the four new knobs (`youtrackdb.query.txResultCache.*`).
- `localCache.clear()` at line 182 is the model for the defensive clear in `beginInternal`.

**Concrete deliverables.**
- `QueryResultCache` class (new, package `internal.core.tx` or sub-package) with `LinkedHashMap<CacheKey, CachedEntry>` field, `lookup`/`put`/`invalidateAll`/`clear` API stubs. Override `removeEldestEntry(eldest)` to skip entries with `eldest.getValue().liveViewCount > 0` (I9 — prevents silent result truncation under LRU pressure when a view is still iterating); evicted entries' `close()` is invoked.
- `CachedEntry` class skeleton with `shape`, `results`, `cachedRids`, `stream`, AST-metadata fields, `populateMutationVersion: long` (D18 + D21 — stamped pre-`plan.start(ctx)` on cache miss; used by K0_NONE for lookup invalidation gate and by cacheable shapes as DeltaBuilder filter `op.version > populateMutationVersion`), `liveViewCount: int` (I9 — incremented by `CachedResultSetView` ctor, decremented idempotently by `close()` and natural exhaustion), `close()` idempotent no-op.
- `RecordOperation` skeleton adjusted: gain `version: long` field stamped from `tx.mutationVersion` at every `addRecordOperation` call (D21). `FrontendTransactionImpl.addRecordOperation` stamps at both code paths — new-entry (`txEntry = new RecordOperation(record, status)`) and collapse-in-place (the existing `txEntry.type` flip per `FrontendTransactionImpl.java:591-612`). The collapse path re-stamps the existing op's version to the new value so `op.version` always reflects the latest mutation timestamp for its RID — DeltaBuilder's `op.version > entry.populateMutationVersion` filter relies on this to decide whether the latest collapsed state was already observed by populate.
- `TxDeltaCursor` class skeleton with `skipSet`, `injectList`, `peek`/`pop`/`shouldSkip` stubs.
- `CacheableShape` enum.
- `CacheKey` class skeleton (statement + params holder; equals/hashCode stubs).
- `FrontendTransactionImpl.queryResultCache` field (nullable; lazy-allocated via `getQueryResultCache()`).
- `FrontendTransactionImpl.mutationVersion: long` field (initialized to 0) + **public** `getMutationVersion() → long` accessor on the concrete class (T4 fix — consumers in `internal/core/tx/cache/*` reach `FrontendTransactionImpl` directly via the existing `DatabaseSessionEmbedded.getActiveTransaction()` accessor — already returns `FrontendTransactionImpl` (used at `CreateEdgesStep:201/209/219`, `FetchEdgesToVerticesStep:91`, `ResultInternal:485/517`); no cast needed; the accessor is NOT exposed on the public `FrontendTransaction` interface — it is implementation detail of the delta-sharing optimization). Incremented at the **END** of `addRecordOperation(record, status)` (inside the success path, after the recordOperations.put completes) — including when the call collapses an existing entry's type (e.g., UPDATED→DELETED on same RID; size unchanged but dispatch outcome differs). End-of-method timing ensures the counter reflects committed state — if an exception fires mid-method between the put and a later step, the version is NOT advanced for the failed mutation. This is the version key for `DeltaBuilder`'s cross-view delta sharing (per design.md § Cross-view delta sharing via mutationVersion).
- `beginInternal()` defensive `queryResultCache.clear()` if non-null **AND** `mutationVersion = 0` reset — both gated on `txStartCounter == 0` (truly outermost begin only). On nested begin (`txStartCounter > 0`), neither reset nor clear (T2 fix — mutationVersion is per-outermost-transaction; nested begin/commit pairs do not affect the counter, otherwise nested-begin would zero the version mid-tx and break Option C's delta sharing).
- `clearUnfinishedChanges()` calls `queryResultCache.clear()` if non-null.
- Four knobs in `GlobalConfiguration`: `QUERY_TX_RESULT_CACHE_ENABLED` (Boolean, default false), `QUERY_TX_RESULT_CACHE_MAX_ENTRIES` (Integer, default 200), `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY` (Integer, default 10000), `QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD` (Integer, default 3 — D18; after N K0_NONE invalidations in a tx the key joins `nonCacheableKeys`). All hot-changeable per project convention.
- **`SQLInputParameter.equals` / `hashCode` audit (D22)** — `SQLInputParameter` is currently concrete with no `equals` / `hashCode` overrides (identity-based via `Object`). Subclasses `SQLNamedParameter` / `SQLPositionalParameter` override correctly. Add concrete `equals` / `hashCode` to `SQLInputParameter` that delegate to subclass-comparable fields (`paramNumber` for positional, `paramName` for named); subclass overrides remain primary dispatch targets but the base-class implementation closes the gap if a raw `SQLInputParameter` instance ever reaches `SQLSkip.equals` / `SQLLimit.equals` via `Objects.equals(inputParam, that.inputParam)`. Without this audit, post-`STATEMENT_CACHE`-eviction re-parse of identical `SELECT ... SKIP :n LIMIT :m` queries would compare unequal and miss cache.

### Plan of Work

1. Add four knobs to `GlobalConfiguration` with conservative defaults. Disabled by default → zero behavioral change for existing deployments.
2. Skeleton classes in order: `CacheableShape` enum → `CacheKey` record → `TxDeltaCursor` → `CachedEntry` → `QueryResultCache`. Each with method stubs (no logic yet beyond null-guards and idempotent close).
3. Wire `queryResultCache` field onto `FrontendTransactionImpl`: nullable, lazily allocated by `getQueryResultCache()` only when `QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean()` is true. Same pattern as other tx-scoped fields.
4. Hook lifecycle: `beginInternal()` calls defensive clear; `clearUnfinishedChanges()` calls final clear. Both null-guard.
5. Lifecycle invariant tests (T1 set):
   - I1: cache.size==0 after commit, rollback, exception-during-iterate.
   - I2: cache mutation paths run only on owning thread.
   - I6: `cache.clear()` idempotent (call twice, assert no exception); `CachedEntry.close()` idempotent.
   - I8: schema DDL during active tx throws; cache state unchanged.
   - T1f (T2 fix — nested-begin version preservation): outer `beginInternal()` sets mutationVersion=0; outer save() bumps version=1; nested `beginInternal()` (txStartCounter 1→2) MUST NOT reset version; nested save() bumps to 2; nested commit (txStartCounter 2→1) does not reset; outer save bumps to 3. Asserts mutationVersion monotonic across nested boundaries.

**Invariants to preserve.** No behavioral change with cache disabled. With cache enabled, no `query()` path reads or writes it yet (that comes in Stage 2). Tx lifecycle (begin / commit / rollback / close) unchanged.

## Stage 2 — Read path (cache key, lookup, CachedResultSetView)

BLUF: After this stage, repeated `query()` calls for the same SELECT/MATCH within a single tx return cached results without re-executing storage. Delta logic is a no-op placeholder (Stage 4 fills it).

Wire the cache into `DatabaseSessionEmbedded.query()` idempotent SELECT/MATCH branch. Build the cache key from parsed AST + normalized parameters (with D12 identity fast-path); on miss, execute normally and wrap the result in a `CachedResultSetView` that incrementally populates the entry as the consumer iterates; on hit, return a view over the existing entry with an empty `TxDeltaCursor`. No delta logic yet — only populate-and-replay path within one consumer's lifetime.

### Context and Orientation

**Codebase state at track start.** After Stage 1, the cache skeleton exists. Existing relevant code:
- `DatabaseSessionEmbedded.query(...)` overloads — three forms (no-args, `Object[] args`, `Map args`). All go through `SQLEngine.parse()` then `statement.execute(...)`.
- `DatabaseSessionEmbedded.executeInternal(...)` — at line 740 (`else` block), line 742 (the idempotent return statement).
- `SQLStatement.execute(...)` — `Map<Object, Object>` form at lines 62/66/83/89.
- `SQLEngine.parse()` — backed by `STATEMENT_CACHE` (LRU by SQL text); same text reissue returns the **same `SQLStatement` instance** — enables D12 identity fast-path.

**Concrete deliverables.**
- `CacheKey` complete: `statement`, `params: Map<Object, Object>`, `equals(o)` and `hashCode()` delegating to `SQLStatement.equals` / `hashCode` with D12 identity fast-path before the deep walk, defensive-copied parameter map.
- `CachedResultSetView` complete: sorted-merge skeleton in `next()` with **empty** `TxDeltaCursor` (always picks from cache cursor in this stage). Increments `position`; pulls from `entry.stream` and appends to `entry.results` + `entry.cachedRids` when the cached list is exhausted (Stage 3 wires the actual stream pause).
- `DatabaseSessionEmbedded.query(...)` lookup logic in all three overloads:
  - Parse AST.
  - Idempotent + cacheable type gate (SQLSelectStatement or SQLMatchStatement).
  - Build `CacheKey`.
  - Cache lookup.
  - On miss: execute normally, build `CachedEntry`, `cache.put`, return `CachedResultSetView`.
  - On hit: return new `CachedResultSetView` over existing entry (empty deltaCursor placeholder).

### Plan of Work

1. Complete `CacheKey` with `equals(o)`:
   - **Identity fast-path** (D12): `if (this.statement == other.statement && Objects.equals(this.params, other.params)) return true;`. Catches identical-text repeats served by `STATEMENT_CACHE`.
   - **Structural fall-through**: if the fast-path missed, deep equals: `this.statement.equals(other.statement) && Objects.equals(this.params, other.params)`. `SQLStatement.equals` is structural per `SQLSelectStatement:380` (13 fields) and `SQLMatchStatement:508` (11 fields). Different statement classes → false.
   - `params` compared deep with `Arrays.deepEquals` for array-valued entries and RID equality for identifiables.
   - `hashCode() = statement.hashCode() ^ params.hashCode()`; cache lazily.
   - Defensive-copy params at constructor time.
2. Implement `CachedResultSetView.next()` sorted-merge skeleton with empty delta. Initially: read from `entry.results[position]`, increment position; if past `results.size()` and `!entry.exhausted`, pull from `entry.stream` (Stage 3 wires this fully — for now stub returns null/throws).
3. Hook `DatabaseSessionEmbedded.query(...)` overloads: parse → gate check → lookup → miss/hit branches. Both branches construct a fresh `CachedResultSetView` with empty deltaCursor (Stage 4 replaces with real builder).
4. `executeInternal(...)` non-idempotent branch — no cache work yet (Track 3 (Stage 1, Hardening) handles invalidation).
5. Regression spy: optional debug flag `youtrackdb.query.txResultCache.verifyHits` that re-executes the query on each hit and compares result sets. Disabled by default; documented for the D13 Hub-replay scenario.
6. Tests (T2 set):
   - T2a: second `query()` with same SQL returns same results as first.
   - T2b: D12 identity fast-path — verify `CacheKey.equals` short-circuits on `==`; verify deep-equals path activates after `STATEMENT_CACHE` eviction.
   - T2c: non-SELECT/non-MATCH (e.g., `SQLProfileStatement`) bypasses cache.
   - T2d: mutable parameter list passed to `query()` then mutated post-call → next `query()` with new state still hits the right key (defensive copy works).
   - T2e: AST node equals coverage — per-node tests for every cacheable AST construct that participates in `SQLStatement.equals`. For `SQLSelectStatement` (13 fields): target, projection, whereClause, groupBy, orderBy, unwind, skip, limit, fetchPlan, letClause, timeout, parallel, noCache. For `SQLMatchStatement` (11 fields): matchExpressions, notMatchExpressions, returnItems, returnAliases, returnNestedProjections, groupBy, orderBy, unwind, skip, limit, returnDistinct. Two statements differing in any one field MUST produce non-equal CacheKeys.
   - T2f: `SELECT FROM Foo SKIP 0 LIMIT 20` and `SELECT FROM Foo SKIP 20 LIMIT 20` produce DISTINCT CacheKeys (SKIP is part of `SQLStatement.equals`). Each populates its own entry; verify `cache.size() == 2` after both queries. Each entry classifies as K0_NONE under D18 (SKIP/LIMIT routes to K0_NONE — see Stage 4).
   - T2g: `SELECT FROM Foo LIMIT 10` and `SELECT FROM Foo LIMIT 100` produce distinct keys. Two cache entries. Verify `cache.size() == 2`.
   - T2h: `MATCH … RETURN u SKIP 0 LIMIT 10` and `MATCH … RETURN u SKIP 10 LIMIT 10` produce distinct keys symmetrically.
   - T2_inputParameterEquals (D22): configure `STATEMENT_CACHE` size = 2; issue `SELECT FROM Foo SKIP :n LIMIT :m` with `{n=0, m=20}` (parse #1, statement instance A); issue 3 unrelated parameterized queries to force `STATEMENT_CACHE` eviction of statement A; re-issue the identical `SELECT FROM Foo SKIP :n LIMIT :m` with `{n=0, m=20}` (parse #2, fresh statement instance B). Assert `cache.lookup` returns the entry populated by parse #1 — proving the D2 deep-equals path (via Stage 1's `SQLInputParameter.equals` audit) handles distinct statement instances correctly. Without the D22 audit fix, the deep-equals path would compare two distinct raw `SQLInputParameter` instances via identity and miss.

**Invariants to preserve.** Caching disabled = zero behavioral change. With caching enabled, view output equivalence to fresh execution holds when no intra-tx mutations occur (mutations are Stage 4's domain). View `next()` MUST handle empty deltaCursor gracefully (no NPE).

## Stage 3 — Pause/resume (shared stream + per-view position)

BLUF: After this stage, a second `query()` for the same key in the same tx can continue iterating a partially-consumed stream from where the first view left off. Cache enables true incremental population across multiple consumers. The miss path also stamps `entry.populateMutationVersion = tx.mutationVersion` immediately before driving the executor (D21) and the view ctor pins the entry via `entry.liveViewCount++` (I9).

Extend `CachedEntry` to hold the live `ExecutionStream` past the first consumer's iteration, and extend `CachedResultSetView` to fall through to it when the consumer outruns the cached list. Multiple `query()` calls within one tx return independent views sharing the same entry; the first view to pull a particular row is the one that pays the storage cost. Close the stream when exhausted or evicted. Pinned entries (any `liveViewCount > 0`) are exempt from LRU eviction so a slow consumer never sees its result silently truncated to a cached prefix.

### Context and Orientation

**Codebase state at track start.** After Stage 2:
- Cache populates on miss via consumer-driven stream pull. Each `view.next()` pulls one record from `entry.stream`, appends to `entry.results` + `entry.cachedRids`, returns to caller.
- `entry.stream` field exists but in Stage 2 was either always-active (no close) or always-eager (force-exhaust on miss). Stage 3 makes it paused-and-resumable.

Existing relevant code:
- `ExecutionStream` interface — `next(ctx)`, `hasNext(ctx)`, `close(ctx)`. The interface does NOT mandate `close(ctx)` idempotency; concrete impls in `core/.../resultset/` vary (some have `alreadyClosed` guards, some don't). This stage wraps in `IdempotentExecutionStream` to defend regardless of underlying impl.
- `LocalResultSet` / `activeQueries` (`DatabaseSessionEmbedded.java:238`) — weak-value map. The cache's strong reference to the wrapped stream (held via `entry.stream`) is what keeps it alive past LocalResultSet GC; tx-end ordering is `closeActiveQueries() → … → cache.clear()`, so both paths can reach the same stream at tx end if the LocalResultSet hasn't been GC'd yet.

**Concrete deliverables.**
- `IdempotentExecutionStream` wrapper class (new) — wraps an `ExecutionStream`, makes `close(ctx)` idempotent. `hasNext(ctx)` / `next(ctx)` forward unconditionally. Field `closed: boolean`; first `close(ctx)` call sets to true and forwards; subsequent calls no-op.
- Cache substitutes this wrapper into BOTH `entry.stream` AND the paired `LocalResultSet`'s stream reference at cache-put time, so cross-caller double-close (closeActiveQueries + cache.clear at tx-end) reaches the same wrapper and is safe.
- `CachedEntry.exhausted: boolean` flips to true when stream reports `hasNext == false`.
- `CachedEntry.close()` closes `stream` if non-null; sets `stream=null, plan=null, ctx=null`; sets `exhausted=true` for idempotency. Calling twice is a no-op (the null-out makes the second call cheap; the wrapper makes the underlying close safe).
- **`entry.populateMutationVersion = tx.mutationVersion` stamping (D21)** — the cache miss path captures the stamp on the owning thread immediately **before** the first `plan.start(ctx)` call. Capturing later (at view construction, after populate has driven the executor) would defeat the filter because the post-populate view's delta would no longer cleanly post-date populate. Stage 4's `DeltaBuilder` reads this field and filters `tx.recordOperations.values()` by `op.version > entry.populateMutationVersion`.
- **`entry.liveViewCount` refcount (I9)** — `CachedResultSetView` constructor increments; `close()` and natural exhaustion (`hasNext()` returns false after both cache + delta + stream are drained) decrement. Decrement path is idempotent: a view that exhausts then is explicitly closed decrements at most once (guarded by a local `decremented: boolean` on the view). `QueryResultCache.removeEldestEntry` skips entries with `liveViewCount > 0` (soft cap) UNLESS the map size exceeds `2 × maxEntries` in which case force-close the eldest with a warning log (fallback for pathological pin patterns).
- **`activeQueries` registration** — `CachedResultSetView` registers in `DatabaseSessionEmbedded.activeQueries` at construction (same map LocalResultSet uses — `WeakValueHashMap` in embedded mode, `HashMap` in server mode per `DatabaseSessionEmbedded.java:238/256`). Registration uses a unique session-scoped key (UUID or similar). This guarantees that tx-end `closeActiveQueries()` reaches every constructed view and invokes `view.close()`, which decrements `liveViewCount` and closes the underlying stream wrapper. **Without this registration, abandoned views (not explicitly closed by the app) would leak `liveViewCount > 0` references that prevent LRU eviction even after tx-end**. The cache's own `cache.clear()` clears entries map, but views still hold entry references; without explicit view.close() the liveViewCount stays positive. closeActiveQueries closes the view first; cache.clear runs second per the existing tx-end ordering at `FrontendTransactionImpl.java:973`.
- `CachedResultSetView.next()` (now functional, not stubbed): if `position >= entry.results.size()` and `!entry.exhausted`, pull one from `entry.stream`, append to `entry.results` and `entry.cachedRids`, return.

### Plan of Work

1. Implement `IdempotentExecutionStream` wrapper. `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/IdempotentExecutionStream.java`. Fields: `final ExecutionStream underlying`, `boolean closed`. Methods: `hasNext(ctx) → underlying.hasNext(ctx)`, `next(ctx) → underlying.next(ctx)`, `close(ctx) → if (!closed) { closed = true; underlying.close(ctx); }`.
2. Wire the wrapper into the cache-put path. **Before** `statement.createExecutionPlan(...)` / `plan.start(ctx)`, capture `entry.populateMutationVersion = tx.mutationVersion` (D21 stamping). After `plan.start(ctx)` yields the underlying stream, the cache wraps via `new IdempotentExecutionStream(underlying)` and stores the wrapper in `entry.stream`. Also reach into the constructed `LocalResultSet` (the one that goes into `activeQueries`) and substitute its internal stream reference with the same wrapper instance, so `LocalResultSet.close()` reaches the wrapper, not the raw underlying. (Implementation detail — Stage 3 owns the substitution mechanism; if `LocalResultSet`'s stream field is final, the alternative is to construct the LocalResultSet around the wrapper from the start.)
3. Add `exhausted` flag to `CachedEntry` (defaults false). Stream-pull code flips it when `stream.hasNext(ctx) == false`. Add `liveViewCount: int` (defaults 0; D21 / I9 refcount).
4. Make `CachedEntry.close()` idempotent: null-guard `stream`, `plan`, `ctx`; flip `exhausted=true`; second call is a no-op early-return. `close()` does NOT touch `liveViewCount` — refcount is the consumer's responsibility through view ctor/close.
5. Implement view fall-through in `CachedResultSetView.next()`: when position outruns `entry.results.size()`, check `entry.exhausted`. If exhausted → no more data from cache; if not → pull from stream, append, return. View ctor increments `entry.liveViewCount`; view `close()` and natural exhaustion path each decrement at most once via a `decremented: boolean` view-local guard.
6. **Override `removeEldestEntry` in `QueryResultCache` to skip pinned entries (I9) with fallback for pathological pins**: pseudocode:
   ```
   if (size() <= maxEntries) return false;
   if (eldest.getValue().liveViewCount > 0) {
     if (size() > 2 * maxEntries) {
       LOG.warn("cache size {} exceeded 2× soft cap {}, force-evicting pinned entry {} (livenessCount={})",
                size(), maxEntries, eldest.getKey(), eldest.getValue().liveViewCount);
       eldest.getValue().close();  // force close; abandoned views start throwing on next()
       return true;
     }
     return false;  // normal pin skip
   }
   eldest.getValue().close();
   return true;
   ```
   The normal pin skip lets LinkedHashMap stay above soft cap transiently; the fallback at 2× hard cap bounds memory in pathological abandoned-view scenarios.
7. **`CachedResultSetView` registration in `activeQueries`** — view constructor registers itself in `DatabaseSessionEmbedded.activeQueries` map (same map LocalResultSet uses) under a unique session-scoped key. `view.close()` deregisters. This guarantees tx-end `closeActiveQueries()` reaches every constructed view (including app-abandoned ones), calling `view.close()` which decrements `liveViewCount` and closes the underlying stream wrapper. Without this hookup, abandoned views leak `liveViewCount > 0` past cache.clear and prevent next-tx LRU eviction.
8. Stream-lifecycle tests (T3 set):
   - T3a: two consumers race to next(). First pulls record A; second sees A in `entry.results[0]`; second pulls record B; first sees B in `entry.results[1]`. Both end at same final state.
   - T3b: consumer drops view mid-iteration without closing. Cache retains the entry; new `query()` constructs a new view that continues from current position.
   - T3c: tx end while view is mid-iteration → `cache.clear()` closes the stream → next `view.next()` throws (acceptable for shutdown).
   - T3d (I3): pause stream; LRU-evict the entry (force `removeEldestEntry` via small `maxEntries`); assert wrapper's underlying stream observed exactly one close.
   - T3e (I6 single-caller): call `entry.close()` twice → wrapper sees exactly one underlying close, no exception.
   - T3f (I6 cross-caller — KEY TEST): construct an entry whose underlying stream is a non-idempotent mock (throws on second close); trigger BOTH `closeActiveQueries()` (which closes the wrapped LocalResultSet stream) AND `cache.clear()` (which closes the same wrapped stream via entry.close); assert the underlying is closed exactly once and no exception propagates. This is the scenario the wrapper exists to defend against.
   - **T3i9 (I9 view pinning under LRU pressure)**: configure `maxEntries=2`; cache miss on Q1 returns a view; consumer pulls 1 row but does not exhaust; issue 5 more distinct queries (Q2..Q6) to flood LRU pressure; resume iterating the Q1 view; assert it returns the full row sequence a parallel uncached `db.query(Q1)` would (matching by RID), proving the pinned entry was NOT evicted; close the Q1 view; issue one more query; assert the Q1 entry NOW becomes eligible for eviction (size shrinks).
   - **T3i9b (abandoned-view tx-end safety)**: open view on Q1; drop reference without closing (set local var to null, no .close() call); issue 5 more distinct queries; commit tx; assert `closeActiveQueries` was called and reached the abandoned view's close; assert no force-evict warning was logged (because abandoned view's slot in activeQueries' WeakValueHashMap may or may not have GC'd, but closeActiveQueries hits all hard refs first; the cleanup completes correctly).
   - **T3i9c (fallback eviction at 2× soft cap)**: configure `maxEntries=2`; open and HOLD references to 5 views (Q1..Q5) all unfinished; issue 6th query; assert force-evict warning fires for the eldest pinned entry (Q1) with `livenessCount=1`; assert Q1's underlying stream is closed; assert subsequent Q1 view.next() throws (acceptable for pathological case); assert cache size dropped back below 2× cap.
   - **T3d21a (D21 stamping timing)**: spy on `entry.populateMutationVersion` assignment; assert the assignment happens BEFORE `plan.start(ctx)` is invoked; assert the captured value equals `tx.mutationVersion` at that exact moment (not later).

**Invariants to preserve.** Stream lifetime ≤ entry lifetime (I3). After tx end, stream is closed; no leaked resources. View `next()` works correctly when `entry.results.size()` < total query result count (resumes from stream). Live views never observe truncation under LRU pressure (I9). Populate-time stamp captured pre-`plan.start` to anchor D21 filter for Stage 4.

## Stage 4 — RECORD delta core + shape classifier

BLUF: After this stage, cached SELECT queries without SKIP/LIMIT reflect intra-tx mutations (CREATE/UPDATE/DELETE) on relevant classes via per-query sorted-merge between the cached list and a snapshot delta cursor built from `tx.recordOperations` **filtered by D21's `op.version > entry.populateMutationVersion`** to skip mutations the tx-aware executor already baked into populate. Queries that carry SKIP or LIMIT route through K0_NONE under D18's mutation-version gate. The dispatch table retains its `cached_at_build` column to handle the CREATE+UPDATE collapse case correctly.

Implement `ShapeClassifier.classify(stmt) → CacheableShape` returning RECORD for cacheable simple SELECT shapes (no SKIP, no LIMIT), K0_NONE for SKIP/LIMIT queries and other delta-unreconcilable shapes. Implement `DeltaBuilder.buildForRecord(entry, recordOps, ctx) → TxDeltaCursor`: filter `tx.recordOperations` by `op.version > entry.populateMutationVersion` (D21), class-filter by `effectiveFromClasses`, dispatch on `(op.type, cached_at_build, match_after)` to build skip-set + sorted inject-list. Implement `CachedResultSetView.next()` sorted-merge between cache cursor and `TxDeltaCursor` for RECORD; K0_NONE branch iterates `entry.results` and falls through to `entry.stream`. Polymorphism gate via `effectiveFromClasses` per D11. K0_NONE entries served under D18's `populateMutationVersion` gate.

This is the load-bearing stage for the lazy architecture. Most of the design's complexity lives here.

### Context and Orientation

**Codebase state at track start.** After Stages 1-3: cache scaffold + read path + pause/resume work. Views return correct results when no intra-tx mutations occur. This stage adds the delta reconciliation.

Existing relevant code:
- `FrontendTransactionImpl.recordOperations: HashMap<RecordIdInternal, RecordOperation>` — line 83. `addRecordOperation(record, status)` line 510 — collapses multi-ops per RID (CREATE→DELETE = DELETE, CREATE→UPDATE = CREATE, UPDATE→DELETE = DELETE).
- `RecordOperation` — `byte type` (CREATED=3, UPDATED=2, DELETED=1), `RecordAbstract record` (post-mutation state; pre-state unavailable).
- `SQLWhereClause.matchesFilters(record, ctx)` — evaluates WHERE in memory.
- `SQLOrderBy` + `SQLOrderByItem.compare(a, b, ctx)` — comparator API.
- `SchemaClass.getAllSubclasses()` — for D11 closure expansion.

**Concrete deliverables.**
- `ShapeClassifier` static `classify(SQLStatement) → CacheableShape`. **First gate**: if `stmt.skip != null || stmt.limit != null` → return `K0_NONE` immediately. Subsequent checks apply only to queries without SKIP/LIMIT. Returns `RECORD` for SELECT statements that satisfy: single SQLFromClause with a class or class-list FROM, no GROUP BY, no aggregates in projection, no LET, no subqueries in WHERE/target, no `$current`/`$matched` in WHERE, ORDER BY items each pass deterministic-modifier-chain check (Track 3 (Stage 1, Hardening) finalizes this gate; this stage stubs to "always admit modifier-chain"). Returns `K0_NONE` (D18) for SELECT/MATCH statements that fail one of the structural delta-build gates but are deterministically reproducible: any SKIP/LIMIT query (caught by first gate), GROUP BY, LET, subqueries in WHERE/target, MATCH with `$matched`/`$current`/`$depth`/`$parent` cross-alias-state references, MATCH with subqueries in pattern WHEREs, MATCH with LET/UNWIND, MATCH with any pattern node lacking `class:` (defeats polymorphism gate so delta can't class-filter but the query result itself is deterministic). Aggregates handled in Track 2 (Stage 1, Aggregate); for the Stage 4 boundary, classify returns K0_NONE for aggregate shapes (Track 2 (Stage 1, Aggregate) narrows to AGGREGATE_*).
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

### Plan of Work

1. `ShapeClassifier.classify` — AST shape inspection. Initial scope: RECORD for cacheable SELECT shapes, NONE for everything else (aggregates and MATCH handled in Track 2 (Stage 1, Aggregate), Track 2 (Stage 2, MATCH Etap A), and Track 2 (Stage 3, MATCH partial Etap B) by extending classify).
2. `OrderByComparator` — wrap `SQLOrderBy` for use at sort time. Plain identifier and modifier-chain support (deterministic gate refined in Track 3 (Stage 1, Hardening)).
3. Entry construction extension — capture `effectiveFromClasses` via D11 closure, `whereClause`, `orderBy`. Populate `cachedRids` during stream pull.
4. `DeltaBuilder.buildForRecord` — single pass over `recordOps`, **filtered by `op.version > entry.populateMutationVersion` per D21** to skip pre-populate mutations the tx-aware executor already baked into `entry.results`, class filter, WHERE eval, cache-membership check via `cached_at_build = entry.cachedRids.contains(op.rid)`, dispatch on `(op.type, cached_at_build, match_after)` per design.md § Lazy merge-on-read → TxDeltaCursor (10-row table; the `cached_at_build` column remains load-bearing under D21 because `FrontendTransactionImpl.addRecordOperation` collapses CREATE+UPDATE in place — op type stays CREATED while version advances, so a CREATED op with `op.version > populateMutationVersion` can be either a true post-populate CREATE or a collapsed pre-populate CREATE with post-populate UPDATEs), sort inject_list by `OrderByComparator`.
5. `CachedResultSetView.next()` sorted-merge — replace Stage 2's empty-delta logic. The merge algorithm is the one in design.md § Stream-pull dispatch unification → `view.next()` pseudocode. Critical point: when `cache_head == null` AND `!entry.exhausted`, the view MUST pull from `stream_pull_one()` BEFORE consulting `delta_head` — otherwise the view returns delta records ahead of not-yet-pulled storage records that sort before them, violating the sorted-merge invariant. Stream-pull-append path consults `deltaCursor.shouldSkip(rid)` for each pulled Result BEFORE appending to `entry.results`; skipped Results are dropped and the loop pulls the next one. RECORD shape carries no SKIP/LIMIT (Mutation 17 routes any SKIP/LIMIT query to K0_NONE), so the view has no SKIP/LIMIT counter.
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
   - **T4d21a (D21 — pre-populate CREATE not double-applied)**: `tx.begin(); db.save(Alice); db.query("SELECT FROM Person")`. Populate runs the tx-aware executor which includes Alice in `entry.results`. `entry.populateMutationVersion = 1` (Alice's mutation version). DeltaBuilder filter: `op.version > 1` is false for Alice's op → Alice's op skipped from delta. View emits Alice **exactly once**, from cached prefix. Assert: view.next() returns Alice once and only once; total emission count matches parallel uncached `query()`.
   - **T4d21b (D21 — collapse CREATE+post-populate-UPDATE where new state fails WHERE)**: `tx.begin(); db.save(new Person(name="Alice", age=25))` (v=1, op.type=CREATED, op.version=1); `db.query("SELECT FROM Person WHERE age > 18")` triggers populate at v=1; Alice in entry.results because age=25 passes WHERE. `db.save(Alice with age=10)` collapses op (type stays CREATED, version bumps to 2). Second `query()`: DeltaBuilder filter `op.version (2) > populateMutationVersion (1)` → included; cache-membership check `cached_at_build=true`; dispatch CREATED + cached=true + match_after=false → `skip_set.add(Alice.rid)`. View: cached cursor reaches Alice → suppressed. Assert: Alice NOT in view emission (new state violates WHERE); without `cached_at_build` column the naive dispatch (`CREATED + match_after=false → no-op`) would leave Alice in the cached prefix and emit her incorrectly.
   - **T4d21c (D21 — UPDATED on cached record re-positions in ORDER BY)**: schema `Item(prio INT)`; storage holds `[A(prio=10), B(prio=20), C(prio=30)]`. `tx.begin(); db.query("SELECT FROM Item ORDER BY prio")` populates entry.results = `[A, B, C]` at populateMutationVersion=0. `db.save(A with prio=25)` (v=1, type=UPDATED, version=1). Second `query()`: DeltaBuilder filter `1 > 0` → included; cache-membership `cached_at_build=true`; dispatch UPDATED + cached=true + match_after=true → `skip_set.add(A.rid); inject_list.add(A with prio=25)`. View merges: skip A from cached cursor, inject A with prio=25 at the position prio=25 sorts to → `[B(20), A(25), C(30)]`. Assert: order matches parallel uncached `query()`.
   - **T4d21d (D21 — true post-populate CREATE not in cache)**: `tx.begin(); db.query("SELECT FROM Person")` populates with whatever's in storage; populateMutationVersion captured. `db.save(Alice)` post-populate (Alice has temp RID, op.version > populateMutationVersion, cached_at_build=false). Second `query()`: dispatch CREATED + cached=false + match_after=true → `inject_list.add(Alice)`. View merges cached + inject. Assert: Alice emitted exactly once, from inject_list.

**Invariants to preserve.** I4: view output equivalent to fresh execution against (cache + delta) snapshot. I7: view's deltaCursor immutable post-construction, and the cached `entry.results` / `entry.cachedRids` are append-only during stream pull, never mutated by tx state.

## Interfaces and Dependencies

**In-scope files (union).**
- `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java` (knob entries — note `api/config`, not `internal/core/config`; this is public API surface)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (field + lifecycle hooks; `mutationVersion` field + `getMutationVersion()`; `RecordOperation.version` stamping)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryResultCache.java` (new — `LinkedHashMap` cache, `removeEldestEntry` pinning + fallback, K0_NONE lookup branch)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedEntry.java` (skeleton → metadata fields populated; close idempotency, exhausted flag, holds the wrapper not the raw stream)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CacheKey.java` (skeleton → complete equals/hashCode with D12 fast-path)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CacheableShape.java` (new — enum)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/TxDeltaCursor.java` (skeleton → complete)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (new — skeleton sorted-merge → stream fall-through → full sorted-merge + K0_NONE branch)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/IdempotentExecutionStream.java` (new — wrapper)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/ShapeClassifier.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/DeltaBuilder.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/OrderByComparator.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lookup hooks in `query()` overloads; cache-put path threads the wrapper into `entry.stream` and the LocalResultSet's internal stream slot; delta build invocation)

Distinct in-scope file count: **13**.

**Depends on:** nothing — this is the seed track.

**Unblocks:** Track 2, Track 3.

**Library / function signatures.**
- `QueryResultCache.lookup(CacheKey) → CachedEntry` (returns null on miss).
- `QueryResultCache.put(CacheKey, CachedEntry) → void`.
- `QueryResultCache.invalidateAll() → void`.
- `QueryResultCache.clear() → void` (idempotent; empties `entries`, `nonCacheableKeys`, and resets `cacheCodeDepth` to 0).
- `CachedEntry.close() → void` (idempotent, no-throw).
- `FrontendTransactionImpl.getQueryResultCache() → @Nullable QueryResultCache` (lazy alloc).
- `CacheKey(SQLStatement, Map<Object,Object>) → defensive-copied Map`.
- `CacheKey.equals(Object)` — D12 `==` fast-path on `statement` reference; on miss, deep `statement.equals` + `Objects.equals(params, params)`. `SQLStatement.equals` is the existing structural override (`SQLSelectStatement:380`, `SQLMatchStatement:508`).
- `CacheKey.hashCode()` — `statement.hashCode() ^ params.hashCode()`.
- `CachedResultSetView(CachedEntry, TxDeltaCursor, DatabaseSessionEmbedded)`.
- `DatabaseSessionEmbedded.query(...)` returns `ResultSet` (unchanged signature; new internal branching).
- `IdempotentExecutionStream(ExecutionStream underlying)` — wrapper constructor.
- `IdempotentExecutionStream.close(CommandContext)` — idempotent by construction.
- `ShapeClassifier.classify(SQLStatement) → CacheableShape`.
- `DeltaBuilder.buildForRecord(CachedEntry, Map<RecordIdInternal, RecordOperation>, CommandContext) → TxDeltaCursor`.
- `OrderByComparator(SQLOrderBy, CommandContext) → Comparator<Result>`.
- `TxDeltaCursor(Set<RID> skipSet, List<Result> injectList)`.
