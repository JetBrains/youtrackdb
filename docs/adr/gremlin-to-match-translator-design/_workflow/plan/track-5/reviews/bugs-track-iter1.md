<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: BG1, sev: suggestion, loc: "GremlinPlanFingerprint.java:32-38", anchor: "### BG1 ", cert: "C-match-expr", basis: "fingerprint omits matchExpressions — latent cache-key collision if a future recogniser populates positive detached MATCH expressions without extending the key"}
  - {id: BG2, sev: suggestion, loc: "GremlinStepWalker.java:265-295 vs :295-310", anchor: "### BG2 ", cert: "C-reserved-subwalk", basis: "rejectReservedPrefixLabels runs on top-level walk only; subWalk omits it — $-prefixed as(...) inside combinator children not rejected until Track 6 consumes child labels"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 6}
cert_index:
  - {id: C-hasedges-fix, verdict: VERIFIED, anchor: "#### C-hasedges-fix "}
  - {id: C-alias-delegation, verdict: MATCHES, anchor: "#### C-alias-delegation "}
  - {id: C-cache-rebind, verdict: MATCHES, anchor: "#### C-cache-rebind "}
  - {id: C-rid-bypass, verdict: MATCHES, anchor: "#### C-rid-bypass "}
  - {id: C-not-fingerprint, verdict: MATCHES, anchor: "#### C-not-fingerprint "}
  - {id: C-combinator-hop, verdict: MATCHES, anchor: "#### C-combinator-hop "}
  - {id: C-match-expr, verdict: CONFIRMED, anchor: "#### C-match-expr "}
  - {id: C-reserved-subwalk, verdict: CONFIRMED, anchor: "#### C-reserved-subwalk "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

GATE VERDICT: PASS

Track-level bugs review (76e42c957a..HEAD). Main-agent review (Auto); diff read + source inspection.

## Findings

### BG1 [suggestion]
**Certificate**: C-match-expr
**Location**: `GremlinPlanFingerprint.fingerprint` (GremlinPlanFingerprint.java:32-38).
**Issue**: Fingerprint enumerates pattern, alias filters, `notMatchExpressions`, and return projection, but not `MatchPlanInputs.matchExpressions()`. No recogniser populates positive detached MATCH expressions today (`GremlinStepWalker.buildResult` leaves the field at default). A later recogniser that writes `matchExpressions` without extending the fingerprint could map two shapes onto one cache entry.
**Refutation considered**: Step 5 `bugs-step5-iter1` BG1; R6 tests cover NOT-differing shapes via `notMatchExpressions`.
**Suggestion**: Add an `;M:` section mirroring `appendNotExpressions` before any recogniser writes positive detached MATCH expressions.

### BG2 [suggestion]
**Certificate**: C-reserved-subwalk
**Location**: `GremlinStepWalker.walk` calls `rejectReservedPrefixLabels` (line 164); `subWalk` (lines 265-295) does not.
**Issue**: Reserved `$`-prefix `as(...)` labels inside combinator child traversals bypass the loud rejection guard. Latent until Track 6 wires child labels as aliases.
**Suggestion**: Run the same guard over child sub-traversal steps when Track 6 consumes child `as(...)` labels, or document the deferral in Interfaces.

## Evidence base

#### C-hasedges-fix: Step 1 BG1 resolved on cumulative diff
Step 1 `bugs-iter1` BG1 flagged `SubTraversalPredicateAdapter.addNode` flipping `hasEdges`. HEAD: `addNode` is classification-neutral (lines 206-214); only `addEdge` / `addEdgeAsNode` / edge-bearing `appendPattern` flip the flag. `reTypeOnlyChild_isPureFilter` and `hasLabelChild_isPureFilter` in `SubTraversalPredicateAdapterTest` pin it. **Verdict: VERIFIED fixed.**

#### C-alias-delegation: A4 alias isolation across combinators
`SubTraversalPredicateAdapter` delegates `nextAnonVertexAlias` / `nextEdgeAlias` to parent. `EdgeTraversalEquivalenceTest` covers `and(out(a), out(b))` with differing targets. **Verdict: MATCHES.**

#### C-cache-rebind: positional param rebind on cache hit
`GremlinToMatchStrategy.buildPlan` get/put keyed on fingerprint; `YTDBMatchPlanStep` carries `inputParameters`; `cachedPlan_rebindsSecondValue` asserts second predicate value multiset. **Verdict: MATCHES.**

#### C-rid-bypass: RID-bearing walks skip cache
`markRidBearing` in `StartStepRecogniser` / `HasStepRecogniser`; `cacheEligible = !ridBearing` in `buildResult`; `hasId_bypassesPlanCache` pins bypass. **Verdict: MATCHES.**

#### C-not-fingerprint: NOT-differing shapes distinct
`appendNotExpressions` structural render; `notDifferingShapes_distinctFingerprints` regression. **Verdict: MATCHES.**

#### C-combinator-hop: CombinatorFoldedHopRecogniser routing
`VertexStepRecogniser` routes singleton edge-returning hops in `SubTraversalPredicateAdapter` to `CombinatorFoldedHopRecogniser`; top-level `outE` declines. Shared `claimFoldedHop` with strict `VertexHopRecogniser`. Unit tests in `CombinatorFoldedHopRecogniserTest` / `VertexStepRecogniserTest`. **Verdict: MATCHES.**

#### C-match-expr: fingerprint omits matchExpressions (BG1)
CONFIRMED-as-latent — see BG1 body.

#### C-reserved-subwalk: subWalk skips reserved-prefix guard (BG2)
CONFIRMED-as-latent — see BG2 body.
