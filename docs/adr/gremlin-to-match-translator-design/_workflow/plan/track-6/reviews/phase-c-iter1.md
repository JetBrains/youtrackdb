# Track 6 Phase C — code review iter1 (main-session remediation) + deeper re-audit

**Date:** 2026-07-22  
**Base:** `d7dd3f8171`..HEAD  
**Mode:** First pass was main-session after Task subagents hit a usage limit (shallow PASS). User challenged empty findings; deeper re-audit found real bugs. This file records both.

## Iter1 shallow pass (superseded)

**Verdict: PASS** — 0 blockers, 0 should-fix, 2 suggestions — **withdrawn**. Missed named/modulated dedup emission bugs below.

## Iter1 deeper re-audit

**Verdict: FAIL** — 0 blockers, 2 should-fix, 3 suggestions. Fix iteration required before track completion.

### Should-fix

**SF1 — Named / modulated `dedup` rewrote RETURN under ELEMENT and emitted nulls**  
`DedupGlobalStepRecogniser` called `setNamedDedupReturnProjection` / cleared RETURN for `by(...)`, but left `BoundaryOutputType.ELEMENT`. `YTDBMatchPlanStep.projectElement` looks up `boundaryAlias` on the row; custom RETURN aliases columns under user labels or property names, so `getVertex(boundaryAlias)` returned null. Confirmed by equivalence tests: `g.V().as("v").dedup("v")`, `dedup().by("name")`, and `as("a").out().as("b").dedup("a")` all produced `["null",…]` vs native vertices.

Gremlin also requires unique-by-label / unique-by-modulator while still emitting the **current** traverser. MATCH `DISTINCT` on rewritten RETURN cannot express prior-hop or property keys without changing the payload.

**Fix applied:** Accept only anonymous `dedup()` and named labels that all resolve to the current `boundaryAlias` — set `returnDistinct` only, do not rewrite RETURN. Decline `by(...)` and prior-hop named labels to native.

**SF2 — Equivalence suite never exercised named / `by` dedup**  
`ProjectionEquivalenceTest` covered only anonymous `dedup()`. Unit tests asserted RETURN shape, not emitted payloads — so SF1 shipped green.

**Fix applied:** Added current-boundary named-dedup equivalence (RECOGNIZED) plus decline fixtures for `by("name")` and prior-hop named dedup; updated recogniser / walker unit expectations.

### Suggestions

**S1 — `projectSingleValue` dual-flag path is dead for current assemblers**  
(unchanged from shallow pass)

**S2 — YQL/GQL plan-cache miss counters advance without named CoreMetrics**  
(unchanged)

**S3 — `convertMapColumn` wraps every non-`elementMap` RID as `YTDBVertexImpl`**  
Fine while edge-producing prefixes decline; edge `select` / MAP will need `YTDBEdgeImpl` dispatch.

**S4 — Fingerprint still omits unused `MatchPlanInputs` fields**  
`unwind`, `matchExpressions`, `returnNestedProjections`, `returnElements` / `returnPaths` / … stay defaulted today. Latent collision if a later track sets them without extending `GremlinPlanFingerprint` (same class of gap as Track 5 BG1).

## Dimensions checked (deeper pass)

| Dimension | Result |
|---|---|
| Bugs | SF1 confirmed with failing equivalence; fix + regression tests |
| Code quality | Decline preferred over wrong accept for DISTINCT-ON shapes |
| Test quality | SF2 closed for named/`by` dedup paths in scope |
| Concurrency | Unchanged — Guava LRU + LongAdder OK |

## Gate

SF1/SF2 fixed in-tree; targeted tests green (`DedupGlobalStepRecogniserTest`, `ProjectionEquivalenceTest`, named-dedup walker cases). Phase C may treat should-fixes as closed for this iteration; track completion still needs user Approve.
