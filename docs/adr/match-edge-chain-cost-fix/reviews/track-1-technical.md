# Track 1 — Technical Review

**Phase**: 3A (pre-execution)
**Track**: Track 1 — Chain detection helper
**Date**: 2026-04-23
**Verdict**: PASS (after 2 iterations)

## Summary

All APIs the helper depends on exist and are accessible. Two blockers
(signature omissions) and two should-fix items (stale line numbers,
missing null-guard) corrected in plan/design. Track is ready for
implementation.

## Iteration 1 — Findings

7 findings: 2 blockers, 2 should-fix, 3 suggestions.

### T1 [blocker] — `targetNode` contract — ACCEPTED (Option b)
- **Issue**: Plan did not specify whether helper receives `edge.in`
  (syntactic) or `neighbor` (direction-dependent from sort loop).
  Reverse traversals could fold incorrectly.
- **Fix**: Option b — helper receives direction-dependent `neighbor`
  (already computed at MatchExecutionPlanner.java:2113). Structural
  rule naturally rejects reverse traversals because reverse `neighbor`
  has no `inV/outV/bothV` continuation.

### T2 [blocker] — missing `aliasClasses` parameter — ACCEPTED
- **Issue**: Class-inference precedence step 1 requires
  `aliasClasses.get(effectiveTargetAlias)`, but the helper signature
  omitted that map.
- **Fix**: Signature now `resolveChainedTarget(edge, neighbor,
  visitedEdges, aliasClasses, session)`.

### T3 [should-fix] — null-method pre-check — ACCEPTED
- **Issue**: `SQLMatchPathItem.method` can be null; existing callers
  null-guard it. Plan did not mention.
- **Fix**: Structural rule now opens with explicit pre-check for
  `edge.item != null && edge.item.getMethod() != null`, with
  references to the existing null-guards at `estimateEdgeCost:2297-2300`
  and `resolveTargetClass:2523-2524`. Rejection-case test added.

### T4 [should-fix] — stale line numbers — ACCEPTED
- **Issue**: Branch switched from `index-ordered-match` to
  `origin/develop`; line numbers shifted by ~70.
- **Fix**: All MatchExecutionPlanner.java:XXXX references updated:
  2102→2032, 2530→2460, 2581→2511, 2587-2590→2517-2520, 3026→2956,
  2373→2303/2359, 2143-2203→2108-2133, 2545→2475. Spot-checked against
  live source in iteration 2.

### T5 [suggestion] — step 2 class-inference redundancy — ACCEPTED
- **Issue**: For outE→inV / inE→outV, aliasClasses is pre-populated
  by `addAliases`; step 2 (edge-schema fallback) is mostly redundant.
- **Fix**: Plan now comments step 2 as "defensive for while-expression
  aliases skipped by `whileAliases` filter at line 4495".

### T6 [suggestion] — test location — ACCEPTED
- **Issue**: Plan didn't specify where tests live.
- **Fix**: Plan Track 1 explicitly names
  `MatchExecutionPlannerMutationTest.java` with the Mockito pattern at
  lines 513-558.

### T7 [suggestion] — LinkedHashSet informational — NO-OP
- Confirmed safe; no document change needed. Comment to be added in
  code during implementation.

## Iteration 2 — Gate Verification

All 6 ACCEPTED fixes VERIFIED. Spot-checked line numbers against live
source on develop:
- `updateScheduleStartingAt` @ :2032 ✓
- `applyTargetSelectivity` @ :2460 ✓
- `resolveTargetClass` @ :2511 ✓
- `extractEdgeClassName` @ :2956 ✓
- Sort loop @ :2108-2133 ✓
- Shared body start @ :2475 ✓
- `neighbor` computation @ :2113 ✓

### Residual doc-only issues — FIXED

**T8** — design.md class diagram still said `targetNode` on line 27.
Fixed: now shows full signature `(edge, neighbor, visitedEdges,
aliasClasses, session)`.

**T9** — ambiguous "line 4518, 4558" for `inferClassFromEdgeSchema`.
Clarified to "called at line 4518, method body starts at :4558".

**PASS** — Track 1 is ready for implementation.
