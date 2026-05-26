# Track 8: Observability + JMH — QueryCacheMetrics + benchmark + Hub replay

## Purpose / Big Picture

BLUF: After this track, operators have hit/miss/delta-build-cost/eviction telemetry; JMH benchmarks cover cache-hit / cache-miss / delta-build / aggregate-replay paths against the cache-disabled baseline; D13 Hub-replay gate is implemented and produces the pre-merge artifact required for production deployment.

Operator-facing observability: new `QueryCacheMetrics` class with hit/miss/delta-build-cost/eviction counters held by `QueryResultCache`, accessible from `FrontendTransactionImpl`. JMH microbenchmark for cache-hit, cache-miss, delta-build-with-N-mutations, and aggregate-replay paths against the cache-disabled baseline. Integration tests assert counter increments. Hub replay scenario (D13 gate) replays an anonymized DNQ-emission sample and asserts ≥70% cacheable-coverage + view-output equivalence vs fresh-execution at mutation sites.

## Context and Orientation

**Codebase state at track start.** After Tracks 1-7: full cache functionality. This track adds observability and the pre-merge validation gate.

Existing relevant code:
- `TransactionMeters` (inline record in `DatabaseSessionEmbedded`) — model for sibling `QueryCacheMetrics` accessor.
- `tests/src/test/java/.../benchmarks/` — existing JMH scaffold.
- `jmh-ldbc/` — JMH LDBC harness; reuse JMH plumbing patterns.

**Concrete deliverables.**
- `QueryCacheMetrics` class — counters: `hits`, `misses`, `evictions`, `deltaBuildTimeNanos`, `deltaBuildCount`, `spliceFailures` (L6 fallback — unexpected step shape on aggregate side-tap plan walk in Track 5), `k0NoneHits` (D18 — K0_NONE entry served as cache hit because `tx.mutationVersion` matched populate stamp), `k0NoneInvalidations` (D18 — K0_NONE entry invalidated at lookup because `tx.mutationVersion` diverged), `k0NoneShortCircuits` (D18 — `nonCacheableKeys` short-circuited a previously-churned K0_NONE key, no lookup attempt). Per-tx (held on `QueryResultCache`).
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

## Plan of Work

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

**In-scope files.**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryCacheMetrics.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/cache/QueryResultCache.java` (counter wiring)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (accessor)
- `tests/src/test/java/.../benchmarks/cache/*Benchmark.java` (new)
- `core/src/test/java/.../tx/cache/HubReplayTest.java` (new)
- `docs/adr/YTDB-820-tx-result-cache/_workflow/hub-replay-results.md` (new, committed artifact)

**Out-of-scope files.**
- Anything functional in cache code — all cache behavior already shipped in Tracks 1-7.
- `jmh-ldbc/` — separate LDBC benchmark, not reused directly.

**Inter-track dependencies.**
- Depends on: Tracks 5, 6, 7 (full cache functionality available).
- Unblocks: Phase 4 (final artifacts) — D13 pass is the merge gate.

**Library / function signatures.**
- `QueryCacheMetrics.recordHit() → void`, `recordMiss()`, `recordEviction()`, `recordDeltaBuild(long nanos)`.
- `QueryCacheMetrics.snapshot() → Map<String, Number>`.
- `FrontendTransactionImpl.getQueryCacheMetrics() → QueryCacheMetrics`.
