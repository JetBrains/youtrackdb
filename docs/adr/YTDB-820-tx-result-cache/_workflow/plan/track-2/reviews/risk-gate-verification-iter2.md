<!-- role: reviewer-risk | phase: 3A | track: Track 2 — Aggregate shapes | iteration: 2 | gate-verification -->
# Track 2 Risk Gate Verification — iteration 2

## Manifest

```yaml
review_type: risk
track: "Track 2: Aggregate shapes — side-tap, storage-parity replay, COUNT_DISTINCT"
phase: 3A
iteration: 2
reviewer: reviewer-risk
kind: gate-verification
findings: 0
overall: PASS
verdicts:
  - id: R1
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
  - id: R2
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
  - id: R3
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
  - id: R4
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
  - id: R5
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
  - id: R6
    prior_sev: suggestion
    disposition: REJECTED
    verdict: REJECTED
tooling_note: >
  Code re-checks (R5 ServiceLoader manifest, R6 ShapeClassifier:177-198 +
  firstFunctionCall:147-161) used direct Read of the cited file:line —
  known-location confirmations, not symbol audits. mcp-steroid PSI timed out
  earlier this session, so no PSI inheritor search was attempted; the R5
  factory set rests on the manifest file, the canonical registration surface.
```

#### Verify R1: bare COUNT(*) hardwired, headline test vacuous
- **Original issue**: `SELECT COUNT(*) FROM C` hardwires to `CountFromClassStep`; side-tap never reached; I4 test asserts fallback while believing it tests the tap.
- **Fix applied**: Context "hardwired and untappable" bullet (lines 96-104); step 5 (202-205) and step 6 (209-214); Validation (230-240) requires a *tappable* shape, forbids bare `COUNT(*)` for cache-hit, adds a hardwired-rides-fallback case + `incrementSpliceFailures` assertion.
- **Re-check**: Current state names the hardwired shapes, the tappable set (SUM/AVG/MIN/MAX/COUNT_DISTINCT + non-indexed-WHERE COUNT(*)), and the explicit Decision-Log resolution (lines 51-56). Test framing no longer vacuous.
- **Regression check**: Checked step 5 vs Validation vs Decision Log — consistent; the "ride fallback OR classify K0_NONE, pick one and test" choice is deferred to decomposer but bounded. Clean.
- **Verdict**: VERIFIED

#### Verify R2: AVG finalization + SUM empty=0
- **Original issue**: D19 `increment` covers SUM accumulation only; AVG needs `total` + `computeAverage` type dispatch; SUM empty = 0 not null.
- **Fix applied**: step 1 "AVG finalization" (144-148), SUM-empty=0 (line 143); Key signatures (300-301, 305) carry `total` + `computeAverage`.
- **Re-check**: Matches the iter-1 code evidence (`computeAverage:101`, `getResult:89`). Integer truncation + BigDecimal HALF_UP + empty-set 0 all named.
- **Regression check**: Validation lines 248-249 add the matching parity cases. Clean.
- **Verdict**: VERIFIED

#### Verify R3: cap targets AggregateState, not results
- **Original issue**: carried `recordPulledRow` cap bounds `results` (1 scalar), leaving `contributingRids`/`distinctBuckets` uncapped — wrong-collection OOM vector.
- **Fix applied**: step 1 "Memory cap (aggregate-specific)" (158-165) bounds the AggregateState collections against `maxRecordsPerEntry`, overflow → L7 non-cacheable; Decision Log (57-60).
- **Re-check**: Obligation now correctly retargeted; L7 one-shot semantics preserved. Validation line 252 adds the overflow case.
- **Regression check**: No conflict with R4 populate path. Clean.
- **Verdict**: VERIFIED

#### Verify R4: eager-drive parallel populate path contracts
- **Original issue**: parallel populate path must re-mirror stamp-before-drive, cacheCodeDepth release, idempotent close.
- **Fix applied**: step 4 (183-192) names all three contracts explicitly; step 3 (176-178) preserves the two-guard `viewOwnsGuard`/`cacheCodeDepth` contract.
- **Re-check**: All three contracts present and ordered (stamp before drive). Matches iter-1 trace of `populateAndBuildView`/`serveThroughCache`.
- **Regression check**: Compatibility note (283-287) consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R5: four factories, not three
- **Original issue**: I5 names three; four registered; `DynamicSQLElementFactory` omitted silently.
- **Fix applied**: Context "Four SQLFunctionFactory implementations" (91-95); step 6 "all four" (213-214); Validation "four factories" (259).
- **Re-check**: ServiceLoader manifest re-read directly — registers exactly four: `DefaultSQLFunctionFactory`, `DynamicSQLElementFactory`, `DatabaseFunctionFactory`, `CustomSQLFunctionFactory`. Track now matches.
- **Regression check**: Surprises & Discoveries (42-44) records the correction. Clean.
- **Verdict**: VERIFIED

#### Verify R6 (REJECTED): COUNT(DISTINCT prop) nested-distinct parse
- **Rejection reason**: already implemented in Track 1 — `ShapeClassifier.isDistinct` walks into the nested `distinct(...)` call; step 5 is verify-not-build.
- **Downstream check**: Read `ShapeClassifier:177-198` — `count` routes to `AGGREGATE_COUNT_DISTINCT` iff `isDistinct(call)`; `isDistinct` applies `firstFunctionCall` (pre-order subtree walk, 147-161) to each param and matches a nested `distinct`. The `count(distinct(prop))` shape is recognized. Surprises note (35-37) and step 5 (193-198) accurately record this as Track-1 verify-and-tighten work; the two genuine Track-2 tightenings (arithmetic-buried aggregate → K0_NONE; hardwired COUNT(*)) are correctly separated out. No downstream issue from leaving the parse as Track-1 work.
- **Verdict**: REJECTED (no action needed)

## Findings

<!-- No new findings surfaced by this verification pass. -->

## Summary

PASS — all five accepted findings (R1–R5) VERIFIED with fixes applied to the amended track-2.md; R6 REJECTED is sound per direct code read. No internal contradictions across the amended Context/Decision Log/Plan of Work/Validation/Key signatures sections. No regressions, no new findings.
