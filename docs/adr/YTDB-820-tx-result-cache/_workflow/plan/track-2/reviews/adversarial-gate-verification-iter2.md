<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Adversarial gate verification — Track 2 (Aggregate shapes) — iteration 2

- role: reviewer-adversarial
- phase: 3A
- track: "Track 2: Aggregate shapes — side-tap, storage-parity replay, COUNT_DISTINCT"
- review_type: adversarial
- overall: PASS
- findings: 0

## Manifest

```yaml
verdicts:
  - id: A1
    disposition: accepted-should-fix
    verdict: VERIFIED
  - id: A2
    disposition: rejected
    verdict: REJECTED
  - id: A3
    disposition: accepted-should-fix
    verdict: VERIFIED
  - id: A4
    disposition: accepted-should-fix
    verdict: VERIFIED
  - id: A5
    disposition: accepted-suggestion
    verdict: VERIFIED
  - id: A6.1
    disposition: accepted
    verdict: VERIFIED
  - id: A6.2
    disposition: rejected
    verdict: REJECTED
overall: PASS
new_findings: 0
source_rechecks:
  - "YqlExecutionPlanCache.java:66-76,119-139 (static get → getInternal → result.copy(ctx))"
  - "SelectExecutionPlanner.java:223-262 (cache hit returns plan; fresh SelectExecutionPlan or hardwired result)"
  - "SelectExecutionPlanner.java:470-475,488-508,553-569 (hardwired count gated isCountStar+isMinimalQuery)"
```

## Verification certificates

#### Verify A1: hardwired COUNT(*) untappable — downgrade soundness
- **Fix applied**: Context & Orientation bullet (track L96-104) and Plan of Work
  step 5(b)/step 6 + Validation now state bare/single-field-indexed `COUNT(*)`
  ride the splice fallback or classify K0_NONE; headline validation case changed
  to a tappable shape (`SUM(price) ... WHERE active`, or non-indexed `COUNT(*)`).
- **Re-check**: `SelectExecutionPlanner.java:470-475` routes through
  `handleHardwiredCountOnClass` (`:488`, gated `isCountStar` `:505` +
  `isMinimalQuery` `:508`) and `handleHardwiredCountOnClassUsingIndex` (`:553`,
  `isCountStar` `:569`); both short-circuit before the aggregation step is built,
  exactly as A1 claimed. The track's fallback (step 3: no
  `AggregateProjectionCalculationStep` → close plan, `incrementSpliceFailures`,
  plain uncached `LocalResultSet`) is a correctness-safe path, so the downgrade
  to should-fix is sound — no correctness bug, only a vacuous-test risk, which
  the amended Validation now closes.
- **Regression check**: Decision Log records the exclusion; Validation asserts the
  fallback fires and the scalar still matches fresh. Clean.
- **Verdict**: VERIFIED

#### Verify A2 (REJECTED): splice mutates the shared cached plan
- **Rejection reason**: `createExecutionPlan` never hands back the cached
  instance; the cache copies before returning.
- **Source re-check (priority)**: `YqlExecutionPlanCache.getInternal`
  (`:119-139`) ends `return result != null ? result.copy(ctx) : null;` with the
  comment "Copy outside cache". Static `get` (`:66-76`) is a thin delegate to
  `getInternal`. `SelectExecutionPlanner.createExecutionPlan` (`:235-240`) returns
  that copy on a hit, or builds a fresh `new SelectExecutionPlan(ctx)` (`:248`)
  / returns the hardwired `result` (`:261`) on a miss. No path returns the shared
  cache instance. The iter-1 finding cited `:235-240` of a *different* file
  (it read the legacy no-copy path); the orchestrator's rejection holds.
- **Downstream check**: Track step 3 now documents the copy-on-return guarantee
  inline, so the splice mutates a private plan. No downstream issue.
- **Verdict**: REJECTED (no action needed)

#### Verify A3: recordPulledRow mis-specified for aggregate cap
- **Fix applied**: step 1 Memory-cap bullet (L158-165) + Decision Log drop the
  literal "route per-RID through `recordPulledRow`" and bound the
  `AggregateState` collections against `maxRecordsPerEntry`, overflow → L7
  non-cacheable; `results` stays one scalar.
- **Re-check**: matches the corrected cap target; Validation adds the
  high-cardinality COUNT(DISTINCT) overflow case.
- **Regression check**: I10 single-row transparency preserved. Clean.
- **Verdict**: VERIFIED

#### Verify A4: AVG needs computeAverage + total
- **Fix applied**: step 1 "AVG finalization" bullet (L144-148) + Key signatures
  (L300-301) specify `total` tracking and finalization via
  `SQLFunctionAverage.computeAverage` (type-dispatched division), not `sum/total`.
- **Re-check**: Validation asserts Integer/Long truncation and BigDecimal
  HALF_UP match fresh. Matches A4's required parity.
- **Verdict**: VERIFIED

#### Verify A5: caching bare COUNT(*) duplicates O(1) path
- **Fix applied**: folded into A1 — Decision Log "Hardwired COUNT(*) → not
  cached" (L51-56) excludes bare/indexed `COUNT(*)`.
- **Re-check**: Decision Log records the exclusion and flags design-doc
  reconciliation for Phase 4. Clean.
- **Verdict**: VERIFIED

#### Verify A6.1: D21 "never op.type" contradicts the RECORD path
- **Fix applied**: step 1 Dispatch bullet (L152-157) now reads "status is
  consulted only to fold DELETED into now_contributing = false, never as a
  stand-in for the before-state (D21 ... design.md's wording, not the looser
  'never op.type')".
- **Re-check**: matches design.md framing; the absolute "never op.type" is gone.
- **Verdict**: VERIFIED

#### Verify A6.2 (REJECTED): boolean matchAfter can't carry value-changing UPDATE
- **Rejection reason**: the new value is read from the mutated `RecordAbstract`
  (the first `applyMutation` arg) and written into `contributingValues[rid]`
  before the re-fold; `matchAfter` carries WHERE-membership only.
- **Downstream check**: track step 1 (L139-143), Decision Log (L65-68), and Key
  signatures (L297-299) all state the value-from-record contract consistently.
  `applyMutation(RecordAbstract, byte status, boolean matchAfter)` takes the
  full record, so re-extraction of the summed/extremum property is possible — the
  signature is sufficient. No downstream issue; the rejection is sound.
- **Verdict**: REJECTED (no action needed)

## Findings

(none — pure-verdict pass)

## Summary

PASS — all 5 accepted findings VERIFIED (A1, A3, A4, A5, A6.1); both rejections
(A2, A6.2) confirmed sound against source. The priority A2 re-check reads
`YqlExecutionPlanCache.getInternal` returning `result.copy(ctx)` (`:138`) with
static `get` delegating to it (`:76`), and `SelectExecutionPlanner.createExecutionPlan`
returning that copy or a fresh plan (`:238`/`:248`/`:261`) — the caller never
holds the shared cached instance, so the splice cannot corrupt other callers.
No regressions, no new findings.
