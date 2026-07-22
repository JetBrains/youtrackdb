# Track 6 Phase C — code review iter1 (main-session remediation)

**Date:** 2026-07-22  
**Base:** `d7dd3f8171`..HEAD  
**Mode:** Main-session dimensional review (Task subagents failed: account usage limit). Covers retroactive high-step `review-bugs` focus (steps 1/3/4/6/7) plus track-level bugs / code-quality / test-quality / concurrency.

**Verdict: PASS** — 0 blockers, 0 should-fix, 2 suggestions.

## Scope notes

- High steps that skipped Phase B sub-step 4: 1, 3, 4, 6, 7 (medium 2, 5 correctly deferred).
- Post-step cache fingerprint + monitoring commits included in cumulative diff.
- mcp-steroid PSI unavailable in Cursor Task path; main session used file reads + grep (reference-accuracy caveat on caller enumeration).

## Findings

### Suggestions

**S1 — `projectSingleValue` dual-flag path is dead for current assemblers**  
`YTDBMatchPlanStep.projectSingleValue` skips present-null when both `dropOnAbsent` and `dropNullRows` are true. Assemblers never leave both set (`configureSingleKeyValues` only sets `dropOnAbsent`; aggregates clear `dropOnAbsent` when setting `dropNullRows`). Javadoc matches the values-only path. Optional: `assert !(dropOnAbsent && dropNullRows)` or drop the inner `dropNullRows` check.

**S2 — YQL/GQL plan-cache miss counters now advance on every enabled miss**  
`YqlExecutionPlanCache` / `GqlExecutionPlanCache` wire `recordHit`/`recordMiss` without named CoreMetrics. Lifetime counters grow on cold lookups; expected. Named profiler rates remain Gremlin-only (Decision Log).

## Dimensions checked

| Dimension | Result |
|---|---|
| Bugs (step + track) | No logic/null/RID/state issues found in boundary projection, fingerprint result-shaping, hardwired count hook, ByModulatorTranslator declines |
| Code quality | Assembler flag resets consistent; fingerprint `toString` for limit/skip correct vs `SQLNumber` `?` collapse |
| Test quality | `ProjectionEquivalenceTest` covers absent/present-null; `GremlinPlanCacheTest` covers miss→hit and cold≡warm |
| Concurrency | Guava LRU + `LongAdder` hit/miss OK; timeout invalidate pattern matches YQL; get-then-copy race same as existing plan caches |

## Gate

No in-scope fix iteration required. Proceed to track-completion approval when user confirms.
