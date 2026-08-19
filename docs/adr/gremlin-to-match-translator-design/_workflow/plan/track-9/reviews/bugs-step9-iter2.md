<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
<!-- MANIFEST
kind: gate-check
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
verdicts:
  - {id: BG1, verdict: VERIFIED, anchor: "### BG1 "}
  - {id: BG2, verdict: STILL OPEN, anchor: "### BG2 ", sev: should-fix, loc: GremlinProjectionAssembler.java:104-108}
  - {id: BG3, verdict: VERIFIED, anchor: "### BG3 "}
  - {id: BG4, verdict: VERIFIED, anchor: "### BG4 "}
  - {id: BG5, verdict: VERIFIED, anchor: "### BG5 "}
index: []
flags: [CONTRACT_OK]
-->
# bugs Review (gate check) — step 9, iteration 2

Diff under check: `c252146ba5~1..a5f38f1cc9`, restricted to `core`.

## Verdicts

- **BG1: VERIFIED** — `elementFormIsUnobserved` now requires `peek(0) == null` for the sub-walk arm, so `where(properties(friendWeight).has(acl, private))` measures 0 boundary steps; all four combinator spellings pinned as declines.
- **BG2: STILL OPEN** — the conjunct is contributed only at `peek(0) == null` (`GremlinProjectionAssembler.java:104-108`), but the `@param` premise "a following `count()` — the sole successor `PropertiesStepRecogniser` accepts" is false for the VALUE form. Measured: `and(values(age).dedup())`, `and(values(age).limit(1))` and `and(values(age).order())` each translate at 1 boundary step with no conjunct and return 3 rows against native's 2; `and(values(age).dedup(), values(name))` returns 2 against 1.
- **BG3: VERIFIED** — `isSingleKeyProperty` gates the count arm only; `g.V().group().by(T.label).by(values(age).count())` translates and agrees (`{Person=2}` on both arms), and the `!isSingleValueProperty` early return withdraws nothing since the block's tail was already `Optional.empty()`.
- **BG4: VERIFIED** — the sub-walk control is now `and(values(a), values(b))`, which reaches `walkChild`, with `assertRewrittenToElementForm` pinning the strategy premise; `where(values(age))` kept and retitled for the `has(key)` desugar.
- **BG5: VERIFIED** — `successor.getClass() == CountGlobalStep.class`, citing `RangeGlobalStepRecogniser.followedByCount` (grep-only for the "no subclass exists" half).

## Implementer claims checked

The `lastStepInWalk` gate is correct in direction: `where(values(age).count())` and `and(values(k).count(), values(j))` both agree with native, and a conjunct in that position would have filtered rows native keeps. `configureSingleKeyValues` has exactly one call site, which passes `cursor.peek(0) == null` correctly (grep-only). The three newly-translating shapes — `or(values(a), values(b))`, `not(and(values(a), values(b)))`, `and(values(k).count(), values(j))` — all agree with native, and `not(values(a))`, `filter(values(a))`, `and(out(knows).values(a))` and `where(and(values(a), values(b)))` show no regression from the new conjunct.

## Summary

FAIL
