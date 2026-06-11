<!-- conventions-execution.md §2.5 review-file schema; verdict-producer variant; file-when-handed-a-path mode -->
<!--REVIEW_MANIFEST_START
role: reviewer-technical
phase: 3A
track: "Track 2: Aggregate shapes — side-tap, storage-parity replay, COUNT_DISTINCT"
iteration: 2
verdict: approved
findings: 0
overall: PASS
verdicts:
  - id: T1
    verdict: VERIFIED
    loc: "track-2.md §Plan of Work step 5"
  - id: T2
    verdict: VERIFIED
    loc: "track-2.md §Plan of Work step 1 'AVG finalization'; §Key signatures"
  - id: T3
    verdict: VERIFIED
    loc: "implementation-plan.md Track-2 carry-over block:406-420"
  - id: T4
    verdict: VERIFIED
    loc: "track-2.md §Plan of Work step 1 'Memory cap'; plan carry-over:411-416"
  - id: T5
    verdict: VERIFIED
    loc: "track-2.md §Plan of Work step 1 SUM/AVG bullet:135-138"
  - id: T6
    verdict: VERIFIED
    loc: "track-2.md §Plan of Work step 3-4:169-192"
index: []
evidence_base:
  premises: 6
  edge_cases: 0
  integrations: 0
  tooling: "Documentation re-read only; orchestrator pre-verified load-bearing code facts against source. No PSI needed (per spawn instructions)."
REVIEW_MANIFEST_END-->

# Track 2 Technical Gate Verification — Iteration 2

One-line summary: All six iteration-1 technical findings (T1-T6, all ACCEPTED) are correctly resolved in the amended track-2.md and plan carry-over; the edits introduce no internal contradictions or decomposability problems. PASS.

#### Verify T1: firstFunctionCall over-broad
- **Original issue**: Step 5 overstated classifier work as greenfield; real gap is `count(*) + 1` classifying AGGREGATE_COUNT instead of K0_NONE.
- **Fix applied**: Step 5 rewritten (track-2.md:193-206).
- **Re-check**: Step 5 now states the aggregate gates "already exist in Track 1 … verify, do not rebuild them," then names exactly two Track-2 tightenings: (a) `count(*) + 1` must classify K0_NONE (cites the `topLevelFunctionCall` "harmless here" Javadoc), and (b) bare/indexed `COUNT(*)` ride fallback or K0_NONE. Validation line 241 asserts `count(*) + 1` → K0_NONE. Criteria met: greenfield overstatement removed; arithmetic-wrapped gap captured + tested.
- **Regression check**: Checked step 5 vs Context/Decision-Log COUNT(*) handling — consistent. Clean.
- **Verdict**: VERIFIED

#### Verify T2: AVG needs computeAverage + total
- **Original issue**: Step 1 said "SUM/AVG via increment" but AVG needs `int total` and `computeAverage`'s type-dispatched division.
- **Fix applied**: Step 1 "AVG finalization" bullet (track-2.md:144-148); Decision Log:62-64; Key signatures:300-301,305.
- **Re-check**: New bullet states AVG tracks `total` and finalizes through `computeAverage` (integer truncation Integer/Long, BigDecimal HALF_UP), "not a plain `sum/total`," explicitly noting `increment` "covers only the SUM half." Key signatures list AVG `total` + `SQLFunctionAverage#computeAverage`. Validation:248-249 asserts integer-truncation and HALF_UP parity. Criteria met.
- **Regression check**: Checked vs T5 seeding rule — compatible (seed verbatim, fold via increment, finalize via computeAverage). Clean.
- **Verdict**: VERIFIED

#### Verify T3: metrics carry-over stale
- **Original issue**: Carry-over implied per-tx hit/miss increments were unwired; they already exist in Track 1.
- **Fix applied**: Plan Track-2 carry-over block (implementation-plan.md:406-420).
- **Re-check**: Block now reads "per-tx hit/miss/k0/overflow counters already exist in Track 1's `QueryResultCache`; what remains is the new `incrementSpliceFailures` counter plus the global `CoreMetrics.QUERY_CACHE_*_RATE` bridge … never incremented." Matches T3's proposed reword exactly. `recordPulledRow`/`inFlightLookup`/`exitCacheCodeUnchecked` cautions retained (418-420). Criteria met.
- **Regression check**: Checked vs Key signatures:306-307 ("only new per-tx metric; hit/miss/k0/overflow increments already exist in Track 1") — consistent. Clean.
- **Verdict**: VERIFIED

#### Verify T4: recordPulledRow cap mis-specified for aggregates
- **Original issue**: Carry-over said route aggregate per-RID material through `recordPulledRow`; aggregate material lives in `AggregateState`, not `results`.
- **Fix applied**: Step 1 "Memory cap (aggregate-specific)" bullet (track-2.md:158-165); Decision Log:57-60; plan carry-over:411-416.
- **Re-check**: Step 1 bullet states `recordPulledRow` "bounds `results` (one scalar row) and does NOT bound the per-contributor material"; bound `AggregateState` collections (`contributingValues`/`contributingRids`/`distinctBuckets`) against `maxRecordsPerEntry`; overflow routes key non-cacheable via L7 path. Plan carry-over agrees. Validation:252-254 has the high-cardinality overflow case. Criteria met.
- **Regression check**: Checked vs Interfaces `CachedEntry` field note (278) and I6 cap test (211-213) — consistent. Clean.
- **Verdict**: VERIFIED

#### Verify T5: seed first value verbatim
- **Original issue**: Step 1 omitted the verbatim-first-value seeding rule (no typed-zero start).
- **Fix applied**: Step 1 SUM/AVG storage-parity bullet (track-2.md:135-138).
- **Re-check**: Bullet states `observe` "seeds the first contributing value verbatim, then folds each subsequent value via `PropertyTypeInternal.increment` (the primitive `SQLFunctionSum.sum`/`SQLFunctionAverage.sum` use)." Matches T5 proposed fix. Criteria met.
- **Regression check**: Compatible with T2 finalization. Clean.
- **Verdict**: VERIFIED

#### Verify T6: split serveThroughCache gate; viewOwnsGuard; plan.start not .next
- **Original issue**: Step 3 didn't state the aggregate branch integration point; step 4 attributed the drain to `.next()` not `plan.start`.
- **Fix applied**: Steps 3-4 (track-2.md:169-192).
- **Re-check**: Step 3 now states the aggregate miss path "is a separate branch in the cache shape gate … not folded into it, and preserves Track 1's two-guard `viewOwnsGuard`/`cacheCodeDepth` contract." Step 4 attributes the full drain to `plan.start(ctx)` ("the blocking `AggregateProjectionCalculationStep` produces its single row only after every contributor is observed"), re-mirrors stamp-version/guard-release/idempotent-close. The mermaid label (116) still reads `plan.start(ctx).next(ctx)`; step-4 prose is the authoritative correction and is correct. Criteria met.
- **Regression check**: Step 3 fallback sets viewOwnsGuard correctly per the existing populate fallback. Clean.
- **Verdict**: VERIFIED

## Findings

(No new findings.)

## Cross-section consistency check
Plan of Work, Validation, Decision Log, and Interfaces/Key-signatures now agree across all six edits: AVG `total`+`computeAverage`, verbatim-first-value, AggregateState-targeted cap, `incrementSpliceFailures` as the sole new per-tx metric, and the separate-branch/eager-drive integration. No contradictions or decomposability regressions introduced. The mermaid `.next(ctx)` label is the lone stale artifact; the load-bearing step-4 prose corrects it, so it is not blocking.

## Summary
PASS
