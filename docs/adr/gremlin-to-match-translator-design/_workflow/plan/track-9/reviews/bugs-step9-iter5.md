<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
<!-- MANIFEST
kind: gate-check
findings: 1   severity: {blocker: 1, should-fix: 0, suggestion: 0}
verdicts:
  - {id: BG2, verdict: VERIFIED, anchor: "### BG2 "}
index:
  - {id: BG8, sev: blocker, loc: GremlinStepWalker.java:124, anchor: "### BG8 ", cert: n/a, basis: "WhereStartStep/WhereEndStep sit in TRANSPARENT_STEPS, so a where(__.as(a)...as(b)) child's end-label comparison is skipped and the shape translates without it; pre-existing, in a file this diff does not touch, independent of BG2"}
flags: [CONTRACT_OK, ROUTED_OUT_OF_STEP]
-->
# bugs Review (gate check) — step 9, iteration 5 (final)

Diff under check: `c252146ba5~1..d6e0920e5c`, restricted to `core`.

## Verdicts

- **BG2: VERIFIED.** Measured `d6e0920e5c` against `02325d1fcd` and the diff base. The four iteration-3 spellings decline at boundary 0 and match native; the six kept shapes translate at boundary 1 and match; the four deliberately withdrawn shapes decline and match; scoped `dedup(a)` declines under both `and` and `where`. The count arm's termination clause is load-bearing — `and(values(age).count().limit(0))` and `and(values(age).dedup().count().limit(0))` were wrong at the prior HEAD and now decline and match. Twenty further fail-closed probes (barrier tails, nested combinators, `fold` / `skip` / `is` / `min` / `aggregate`, double-`count`, double-`dedup`) all match. `ProjectionEquivalenceTest` is 58/58. The inertness fixture (Alice 30 / Bob 30 / Carol none, plus a list-cardinality duplicate) discriminates and confirms `dedup` inert across `and` / `or` / `not` / `where`. `projectsReturnedPayload()` has exactly two implementations — `WalkerContext` true, `SubTraversalPredicateAdapter` false — so no captured child bypasses the gate. (grep-only)

## New findings

### BG8 [blocker] GremlinStepWalker.java:124

`WhereStartStep` and `WhereEndStep` sit in `TRANSPARENT_STEPS`, so a `where(__.as(a)…as(b))` child's end-label comparison is skipped and the shape translates without it:

- `g.V().as(a).out().as(b).where(__.as(a).out().as(b))` → `[]` against native's one row
- `g.V().as(a).where(__.as(a).out().as(a))` → one row against native's `[]`

**Routing, stated by the reviewer rather than inferred.** Pre-existing at the diff base, in a file this diff does not touch, and independent of BG2 — both witnesses contain no `PropertiesStep`, and step 9's commit *reduced* the divergence set on this surface from 6 to 4. It needs its own item, not a fourth step-9 attempt.

Fix: drop both from `TRANSPARENT_STEPS` and decline any child carrying one.

## Summary

FAIL on BG8 only; BG2 is closed and step 9's own finding set is exhausted.
