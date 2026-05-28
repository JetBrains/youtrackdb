<!-- workflow-sha: 7cdacac6aecc5fac81f314418453a8831c3ef37e -->
# Track 2: Read path — cache key, lookup, population, CachedResultSetView

## Purpose / Big Picture

BLUF: After this track, repeated `query()` calls for the same SELECT/MATCH within a single tx return cached results without re-executing storage. Delta logic is a no-op placeholder (Track 4 fills it).

Wire the cache into `DatabaseSessionEmbedded.query()` idempotent SELECT/MATCH branch. Build the cache key from parsed AST + normalized parameters (with D12 identity fast-path); on miss, execute normally and wrap the result in a `CachedResultSetView` that incrementally populates the entry as the consumer iterates; on hit, return a view over the existing entry with an empty `TxDeltaCursor`. No delta logic yet — only populate-and-replay path within one consumer's lifetime.

## Context and Orientation

**Codebase state at track start.** After Track 1, the cache skeleton exists. Existing relevant code:
- `DatabaseSessionEmbedded.query(...)` overloads — three forms (no-args, `Object[] args`, `Map args`). All go through `SQLEngine.parse()` then `statement.execute(...)`.
- `DatabaseSessionEmbedded.executeInternal(...)` — at line 740 (`else` block), line 742 (the idempotent return statement).
- `SQLStatement.execute(...)` — `Map<Object, Object>` form at lines 62/66/83/89.
- `SQLEngine.parse()` — backed by `STATEMENT_CACHE` (LRU by SQL text); same text reissue returns the **same `SQLStatement` instance** — enables D12 identity fast-path.

**Concrete deliverables.**
- `CacheKey` complete: `statement`, `params: Map<Object, Object>`, `equals(o)` and `hashCode()` delegating to `SQLStatement.equals` / `hashCode` with D12 identity fast-path before the deep walk, defensive-copied parameter map.
- `CachedResultSetView` complete: sorted-merge skeleton in `next()` with **empty** `TxDeltaCursor` (always picks from cache cursor in this track). Increments `position`; pulls from `entry.stream` and appends to `entry.results` + `entry.cachedRids` when the cached list is exhausted (Track 3 wires the actual stream pause).
- `DatabaseSessionEmbedded.query(...)` lookup logic in all three overloads:
  - Parse AST.
  - Idempotent + cacheable type gate (SQLSelectStatement or SQLMatchStatement).
  - Build `CacheKey`.
  - Cache lookup.
  - On miss: execute normally, build `CachedEntry`, `cache.put`, return `CachedResultSetView`.
  - On hit: return new `CachedResultSetView` over existing entry (empty deltaCursor placeholder).

## Plan of Work

1. Complete `CacheKey` with `equals(o)`:
   - **Identity fast-path** (D12): `if (this.statement == other.statement && Objects.equals(this.params, other.params)) return true;`. Catches identical-text repeats served by `STATEMENT_CACHE`.
   - **Structural fall-through**: if the fast-path missed, deep equals: `this.statement.equals(other.statement) && Objects.equals(this.params, other.params)`. `SQLStatement.equals` is structural per `SQLSelectStatement:380` (13 fields) and `SQLMatchStatement:508` (11 fields). Different statement classes → false.
   - `params` compared deep with `Arrays.deepEquals` for array-valued entries and RID equality for identifiables.
   - `hashCode() = statement.hashCode() ^ params.hashCode()`; cache lazily.
   - Defensive-copy params at constructor time.
2. Implement `CachedResultSetView.next()` sorted-merge skeleton with empty delta. Initially: read from `entry.results[position]`, increment position; if past `results.size()` and `!entry.exhausted`, pull from `entry.stream` (Track 3 wires this fully — for now stub returns null/throws).
3. Hook `DatabaseSessionEmbedded.query(...)` overloads: parse → gate check → lookup → miss/hit branches. Both branches construct a fresh `CachedResultSetView` with empty deltaCursor (Track 4 replaces with real builder).
4. `executeInternal(...)` non-idempotent branch — no cache work yet (Track 7 handles invalidation).
5. Regression spy: optional debug flag `youtrackdb.query.txResultCache.verifyHits` that re-executes the query on each hit and compares result sets. Disabled by default; documented for the D13 Hub-replay scenario.
6. Tests (T2 set):
   - T2a: second `query()` with same SQL returns same results as first.
   - T2b: D12 identity fast-path — verify `CacheKey.equals` short-circuits on `==`; verify deep-equals path activates after `STATEMENT_CACHE` eviction.
   - T2c: non-SELECT/non-MATCH (e.g., `SQLProfileStatement`) bypasses cache.
   - T2d: mutable parameter list passed to `query()` then mutated post-call → next `query()` with new state still hits the right key (defensive copy works).
   - T2e: AST node equals coverage — per-node tests for every cacheable AST construct that participates in `SQLStatement.equals`. For `SQLSelectStatement` (13 fields): target, projection, whereClause, groupBy, orderBy, unwind, skip, limit, fetchPlan, letClause, timeout, parallel, noCache. For `SQLMatchStatement` (11 fields): matchExpressions, notMatchExpressions, returnItems, returnAliases, returnNestedProjections, groupBy, orderBy, unwind, skip, limit, returnDistinct. Two statements differing in any one field MUST produce non-equal CacheKeys.
   - T2f: `SELECT FROM Foo SKIP 0 LIMIT 20` and `SELECT FROM Foo SKIP 20 LIMIT 20` produce DISTINCT CacheKeys (SKIP is part of `SQLStatement.equals`). Each populates its own entry; verify `cache.size() == 2` after both queries. Each entry classifies as K0_NONE under D18 (SKIP/LIMIT routes to K0_NONE — see Track 4).
   - T2g: `SELECT FROM Foo LIMIT 10` and `SELECT FROM Foo LIMIT 100` produce distinct keys. Two cache entries. Verify `cache.size() == 2`.
   - T2h: `MATCH … RETURN u SKIP 0 LIMIT 10` and `MATCH … RETURN u SKIP 10 LIMIT 10` produce distinct keys symmetrically.
   - T2_inputParameterEquals (D22): configure `STATEMENT_CACHE` size = 2; issue `SELECT FROM Foo SKIP :n LIMIT :m` with `{n=0, m=20}` (parse #1, statement instance A); issue 3 unrelated parameterized queries to force `STATEMENT_CACHE` eviction of statement A; re-issue the identical `SELECT FROM Foo SKIP :n LIMIT :m` with `{n=0, m=20}` (parse #2, fresh statement instance B). Assert `cache.lookup` returns the entry populated by parse #1 — proving the D2 deep-equals path (via Track 1's `SQLInputParameter.equals` audit) handles distinct statement instances correctly. Without the D22 audit fix, the deep-equals path would compare two distinct raw `SQLInputParameter` instances via identity and miss.

**Invariants to preserve.** Caching disabled = zero behavioral change. With caching enabled, view output equivalence to fresh execution holds when no intra-tx mutations occur (mutations are Track 4's domain). View `next()` MUST handle empty deltaCursor gracefully (no NPE).

## Interfaces and Dependencies

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CacheKey.java` (complete)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/CachedResultSetView.java` (new, skeleton sorted-merge)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lookup hooks in `query()` overloads)

**Out-of-scope files.**
- `ShapeClassifier` — Track 4 introduces (this track puts `shape = NONE_PLACEHOLDER` or skips classify entirely).
- `DeltaBuilder` — Track 4 introduces (this track uses empty `TxDeltaCursor`).
- `executeInternal()` non-idempotent invalidation — Track 7.
- Stream pause/resume — Track 3.
- AST classify rules for SKIP / SELECT shape / aggregates — Track 4 + Track 5.

**Inter-track dependencies.**
- Depends on: Track 1 (skeleton types).
- Unblocks: Tracks 3, 4 (read path must exist before stream-hold and delta-build).

**Library / function signatures.**
- `CacheKey(SQLStatement, Map<Object,Object>) → defensive-copied Map`.
- `CacheKey.equals(Object)` — D12 `==` fast-path on `statement` reference; on miss, deep `statement.equals` + `Objects.equals(params, params)`. `SQLStatement.equals` is the existing structural override (`SQLSelectStatement:380`, `SQLMatchStatement:508`).
- `CacheKey.hashCode()` — `statement.hashCode() ^ params.hashCode()`.
- `CachedResultSetView(CachedEntry, TxDeltaCursor, DatabaseSessionEmbedded)`.
- `DatabaseSessionEmbedded.query(...)` returns `ResultSet` (unchanged signature; new internal branching).
