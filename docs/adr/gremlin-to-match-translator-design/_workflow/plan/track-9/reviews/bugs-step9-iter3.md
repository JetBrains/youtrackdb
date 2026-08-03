<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
<!-- MANIFEST
kind: gate-check
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
verdicts:
  - {id: BG2, verdict: REGRESSION, anchor: "### BG2 ", sev: blocker, loc: PropertiesStepRecogniser.java:116-127}
index:
  - {id: BG7, sev: should-fix, loc: GremlinProjectionAssembler.java:106-109, anchor: "### BG7 ", cert: n/a, basis: "the main-line arm sets dropOnAbsent shaping, which the plan step applies after the plan's LIMIT, so g.V().values(age).limit(1) returns [] against native's [44] when the property-less vertex sorts first; pre-existing and order-dependent, which is why the existing cases pass"}
flags: [CONTRACT_OK, ITERATION_CAP_REACHED]
-->
# bugs Review (gate check) — step 9, iteration 3

Diff under check: `c252146ba5~1..02325d1fcd`, restricted to `core`. Third and final
iteration under `review-iteration.md` §Limits.

## Verdicts

- **BG2: REGRESSION** — the four spellings from iteration 2 now agree by measurement (`and(values(age).dedup())` 2 rows with the conjunct, `and(values(age).dedup(), values(name))` 1, `limit(1)` and `order()` decline at boundary 0), but the classifier looks **one step ahead only**: `dedup()` is classified PRESERVED without inspecting what follows it. So `and(values(age).dedup().count())` now returns 2 against native's 3, and it **agreed at `a5f38f1cc9`** — iteration 2 broke a shape iteration 1 had working. The same break reaches `where(values(age).dedup().count())` (2 vs 3), `not(values(age).dedup().count())` (1 vs 0) and `and(values(age).dedup().limit(0))` (2 vs 0), at `PropertiesStepRecogniser.java:116-127`. (grep-only for the successor enumeration.) Reviewer's fix: classify the whole remaining child chain, or restrict PRESERVED to a `dedup()` that ends the child.

## New findings

### BG7 [should-fix] GremlinProjectionAssembler.java:106-109

The main-line arm sets `dropOnAbsent` shaping, which the plan step applies **after** the plan's `LIMIT`, so `g.V().values(age).limit(1)` returned `[]` against native's `[44]` when the property-less vertex sorts first. The answer flips with cluster and scan order, which is why the existing cases pass. Pre-existing: the main-line path is byte-identical before and after the fix, so the commit's "byte-identical" claim holds — what is wrong is its *justification*, "the boundary step applies after `SKIP` / `LIMIT`". Fix: refuse a slice over a `dropOnAbsent` projection, or apply the row drop before `SKIP` / `LIMIT`.

**Cross-step consequence.** Step 10's `POST_SLICE_RECOGNISERS` allow-list admits `values` / `valueMap` / `elementMap` on precisely that justification. If BG7 holds, the allow-list's membership rule is unsound and step 10's load-bearing case `ProjectionEquivalenceTest.orderLimit_matchNative` passes only because its fixture's scan order hides the divergence.

## Summary

FAIL — iteration cap reached, escalating to the user.
