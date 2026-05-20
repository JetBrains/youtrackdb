# Track 6: Observability — `QueryCacheMetrics` + JMH benchmark

## Purpose / Big Picture
After this track, operators and JMH have concrete numbers for the cache: hit/miss/eviction counters per transaction, and a microbenchmark that measures cache-hit, cache-miss, sharp-merge, and wipe paths against the cache-disabled baseline.

Introduce `QueryCacheMetrics` as a sibling class to the existing `TransactionMeters` record. The metrics object is owned by `QueryResultCache` and accessible via `FrontendTransactionImpl.getQueryCacheMetrics()`. JMH microbenchmark exercises the four canonical paths so we have a baseline before Hub deployment.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

## Outcomes & Retrospective

## Context and Orientation

`TransactionMeters` lives inline at `DatabaseSessionEmbedded.java:190` as an immutable record over three `TimeRate` fields. Adding cache counters there would force record-shape changes plus modifications to both constructor sites (NOOP at line 196, real instance at line 275) and change all counter types to `TimeRate` (the third-party rate class). Choosing a sibling class instead: `QueryCacheMetrics` lives next to the cache machinery in `internal.core.tx`, owns three plain `long` counters with simple getters, and is held by `QueryResultCache` (one instance per cache instance, lifecycle matches).

Counters: `hits`, `misses`, `evictions`. Increments:
- `hits` — `QueryResultCache.lookup` returns non-null entry (and entry is not in overflow state).
- `misses` — `lookup` returns null or treats an overflow entry as miss.
- `evictions` — `removeEldestEntry` fires (LRU at `maxEntries`); plus per-K0 wipe in `invalidateOnMutation` when an entry's class matches; plus per-call to `invalidateAll`.

Access path for test inspection and operator probes: `FrontendTransactionImpl.getQueryCacheMetrics()` — returns null if the cache is disabled / never allocated, else returns `queryResultCache.getMetrics()`.

JMH benchmark lives at `core/src/jmh/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCacheBenchmark.java`. Existing JMH infra under `core/src/jmh/` provides the harness and dataset fixtures (LDBC-style schema or a simple synthetic schema — pick whatever is fastest to set up). Scenarios:
- **Hit path** — pre-warm cache with a SELECT, then measure throughput of repeated invocations of the same query.
- **Miss path (cache disabled)** — baseline. Same query loop with `QUERY_TX_RESULT_CACHE_ENABLED=false`.
- **Miss path (cache enabled, distinct keys)** — pessimistic case where every query has a new key. Should measure the cost of the cache-lookup overhead added to a normal execution.
- **Sharp-merge path** — pre-warm cache, then do a record mutation per iteration, measure invalidation + sharp-merge cost.
- **Wipe path** — pre-warm cache, then trigger DML per iteration, measure full-wipe cost.

Concrete deliverables:
- `QueryCacheMetrics` class with three counters and getters.
- `FrontendTransactionImpl.getQueryCacheMetrics()` accessor.
- Counter increments at cache-lookup, eviction, and invalidation sites.
- JMH benchmark class with the five scenarios above.
- Integration tests asserting counter increments under all paths.

## Plan of Work

1. Create `QueryCacheMetrics` class. Fields: three `private long` counters. Methods: `recordHit()`, `recordMiss()`, `recordEviction()` (or single `recordEvictions(int n)` for bulk-invalidate), plus three getters. Override `toString()` for log readability.
2. Wire metrics object into `QueryResultCache`. Field `private final QueryCacheMetrics metrics = new QueryCacheMetrics();`. Public `getMetrics()`. Increments at: `lookup` (hit/miss based on outcome), `removeEldestEntry` (one eviction per call), `invalidateAll` (count of cleared entries as evictions), `invalidateOnMutation` (one eviction per K0-wiped entry; sharp-merge does not count as eviction).
3. Add `FrontendTransactionImpl.getQueryCacheMetrics()` accessor — null-safe pass-through to `queryResultCache.getMetrics()`.
4. JMH benchmark class with five scenarios listed above. Use `@Param` for cache enabled/disabled toggle. Each scenario has a `@Setup` that primes the cache appropriately. Report throughput per scenario; comparison against the cache-disabled baseline is the headline number. **Threading: `@Threads(1)` + `@State(Scope.Thread)` are mandatory** — `FrontendTransaction` is thread-affine (`assertOnOwningThread`), so sharing a session across JMH threads violates the tx contract and would deadlock or assert. Each benchmark fixture creates its own session + tx in `@Setup(Level.Iteration)`; sessions are not shared across threads even if a later experiment adds `@Threads(N)`.
5. Integration tests for counter assertions. Each test sets up the relevant scenario and asserts the counter delta. (e.g., 100 repeat queries → `metrics.getHits() == 99` and `metrics.getMisses() == 1`.) Also assert that a disabled cache returns null metrics object (or whatever the null contract is).

## Concrete Steps

## Episodes

## Validation and Acceptance

- Cache hit ratio for a benchmark workload of N repeat SELECTs is measurably > 0.9 when no mutations occur, 0.0 when cache is disabled. Verified by `QueryCacheMetrics.getHits() / (getHits() + getMisses())`.
- After 200 distinct queries with `maxEntries=200`, `getEvictions() == 0`; after 201, `getEvictions() == 1`.
- DML inside a tx with a populated cache produces eviction count equal to entry count before the DML.
- JMH benchmark report shows cache-hit path is at least an order of magnitude faster than cache-miss path on a representative SELECT query. (Numbers documented in commit message; benchmark output committed to `_workflow/` for branch lifetime.)
- Disabling the cache via `QUERY_TX_RESULT_CACHE_ENABLED=false` makes `getQueryCacheMetrics()` return null and all metric assertions degrade gracefully.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In scope:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/QueryCacheMetrics.java` (NEW).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCache.java` — own a metrics field; record increments at lookup / removeEldestEntry / invalidateAll / invalidateOnMutation.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` — `getQueryCacheMetrics()` accessor.
- `core/src/jmh/java/com/jetbrains/youtrackdb/internal/core/tx/QueryResultCacheBenchmark.java` (NEW).
- Tests under `core/src/test/java/com/jetbrains/youtrackdb/internal/core/tx/`.

**Out of scope:**
- Persistent metrics export to external monitoring (operators wire that themselves using the per-tx accessor).
- Cross-tx aggregate metrics (no JVM-wide counter — design choice).
- New non-determinism functions or DML invalidation strategies (Track 5).

**Inter-track dependencies:**
- Depends on Track 5 (DML invalidation hook fires the `evictions` counter; per-entry overflow handling triggers `misses` counter on second issue).
- Terminal track — nothing depends on it.

**Library / function signatures introduced:**
- `QueryCacheMetrics(){}` constructor; `recordHit()`, `recordMiss()`, `recordEviction(int n)`, three getters, `toString()`.
- `@Nullable QueryCacheMetrics FrontendTransactionImpl.getQueryCacheMetrics()`.
- `QueryCacheMetrics QueryResultCache.getMetrics()`.
