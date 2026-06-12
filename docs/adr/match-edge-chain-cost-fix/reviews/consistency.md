# Consistency Review — match-edge-chain-cost-fix

**Phase**: 2 Step 1
**Date**: 2026-04-23
**Verdict**: PASS (after 2 iterations)

## Summary

Plan and design document validated against the actual codebase using
semi-formal reasoning protocol. All 7 verified line-number references
(2102, 2143-2203, 2530, 2545, 2581, test:183, test:4708) are accurate.
Call flows traced through code match the sequence diagram's non-chain
branch exactly. All APIs the new code depends on (`extractEdgeClassName`,
`parseDirection`, `getMethodNameString`, shared helper body from
line 2545) are accessible with documented behaviour. Invariants around
TimSort stability and MAX_VALUE short-circuit are ENFORCED by existing
code. IndexOrderedPlanner boundary claim confirmed — no regression risk.

## Iteration 1 — Findings

5 findings: 0 blockers, 3 should-fix, 2 suggestions.

### Finding CR1 [should-fix] — ACCEPTED
**Location**: implementation-plan.md §Track 1; design.md §Chain Detection Rule
**Issue**: Both documents referenced "edge.item.getMethod() method name
(lower-cased)" without pointing to `SQLMethodCall#getMethodNameString()`
(used by `parseDirection` @ MatchExecutionPlanner.java:2373).
**Fix**: Both documents now explicitly name `getMethodNameString()` and
pin the accessor to the parseDirection call site.

### Finding CR2 [suggestion] — REJECTED
**Location**: Reviewer context (not the documents)
**Issue**: Pattern.java package path was described as `executor.match` in
the reviewer's prompt, actually lives in `sql.parser`. No document
contains this incorrect claim.
**Rejection reason**: No document to fix — error was in context only.

### Finding CR3 [should-fix] — ACCEPTED
**Location**: implementation-plan.md §Track 1
**Issue**: Class-inference bullet list lacked explicit precedence:
`aliasClasses.get(alias)` first, then edge-schema fallback. Required for
Track 3 test 4 (bothE→bothV with explicit `class:` annotation).
**Fix**: Track 1 now has numbered precedence: (1) aliasClasses lookup,
(2) edge-schema derivation — mirrors `resolveTargetClass` @ 2587-2590.

### Finding CR4 [should-fix] — ACCEPTED
**Location**: implementation-plan.md §Track 1
**Issue**: Track 1 didn't state where `effectiveTargetAlias` comes from
(`targetNode.out.iterator().next().in.alias`). Risk of misusing
`targetNode.alias` (the intermediate edge alias).
**Fix**: Track 1 now has explicit extraction pointer + warning against
using `targetNode.alias`.

### Finding CR5 [suggestion] — ACCEPTED
**Location**: design.md §Workflow sequence diagram
**Issue**: Alt-branch label compressed 4 detection-rule clauses to 2.
**Fix**: Label now cross-references §Chain Detection Rule for all four
clauses.

## Iteration 2 — Gate Verification

All four ACCEPTED findings VERIFIED:
- CR1: Both documents explicitly name `getMethodNameString()` @ 2373.
- CR3: Numbered precedence list present in Track 1, consistent with
  Track 3 test 4 and design §Chain Detection Rule.
- CR4: Explicit extraction sequence with warning present in Track 1;
  consistent with class diagram's PatternEdge/PatternNode shape.
- CR5: Sequence-diagram alt-label cross-references the 4-clause rule.

CR2 REJECTED verdict remains sound — no downstream issue.

**Regression re-scan**:
- Track 1 restructure ↔ Track 2 call-site description: consistent.
- Track 1 extraction ↔ Track 3 test cases: consistent.
- Design §Chain Detection Rule prose ↔ sequence diagram: consistent.
- Design class diagram ↔ plan overload signature: consistent.

No new blockers, no new findings. **PASS**.
