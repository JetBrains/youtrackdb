<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index:
  - {id: C-bg1-recheck, verdict: RESOLVED, anchor: "#### C-bg1-recheck "}
  - {id: C-capture-boundary, verdict: MATCHES, anchor: "#### C-capture-boundary "}
  - {id: C-refactor, verdict: MATCHES, anchor: "#### C-refactor "}
  - {id: C-nested, verdict: MATCHES, anchor: "#### C-nested "}
  - {id: C-bg2-deferred, verdict: DEFERRED, anchor: "#### C-bg2-deferred "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

GATE VERDICT: PASS — BG1 resolved; no new blocker or should-fix.

Gate-check re-review (iteration 2) after the `Review fix:` commit `5d3bf3d6b7`. Full step diff: `cca4ae166b..5d3bf3d6b7`. mcp-steroid was not reachable; symbol audits used grep and direct file reads with a reference-accuracy caveat on caller enumeration.

## Findings

No new findings. BG2 (reserved-`$`-prefix guard for child sub-traversals) remains intentionally deferred — not under re-check.

## Evidence base

#### C-bg1-recheck: BG1 resolved — hasEdges is hop-only; hasLabel re-type stays pure-filter
- **Original issue**: `SubTraversalPredicateAdapter.addNode` set `hasEdges = true` unconditionally, misclassifying a folded `hasLabel(L)` boundary re-type (`HasStepRecogniser` → `ctx.addNode(boundary, labelClass)`) as edge-bearing.
- **Fix verification**:
  1. `addNode` (SubTraversalPredicateAdapter.java:190-198) is classification-neutral — no `hasEdges` flip.
  2. `addEdge` / `addEdgeAsNode` (lines 201-222) set `hasEdges = true`.
  3. `GremlinPatternAssembler.appendFoldedHop` calls `addEdge` then `addNode` (lines 52-53); `appendEdgeAsNode` calls `addEdgeAsNode` then `addNode` (lines 73-75) — genuine hops flip the flag before the target `addNode`.
  4. Regression test `reTypeOnlyChild_isPureFilter` drives a re-type-only child through `walkChild` and asserts `hasEdges()` stays false; `patternContributions_capturedNotCommitted` pins that bare `addNode` does not flip while `addEdge` does.
- **Verdict**: RESOLVED.

#### C-capture-boundary: declined child does not commit partial state to parent
- **Refutation check**: All mutating methods write adapter-local buffers; `decline_doesNotCommitPartialStateToOuterContext` asserts parent's pattern/aliasFilters/boundary/return surfaces stay untouched while the adapter holds the partial contribution.
- **Verdict**: MATCHES (unchanged from iter1).

#### C-refactor: dispatchAll/subWalk extraction preserves top-level walk behavior
- **Refutation check**: `walk()` still runs empty gate, reserved-prefix scan, flag resolution, boundary-invariant assertion, then `dispatchAll`, then `buildResult`. `subWalk` adds only the empty-child gate and reuses `dispatchAll`.
- **Verdict**: MATCHES (unchanged from iter1).

#### C-nested: recursive walkChild does not corrupt buffered state
- **Refutation check**: `walkChild` on the adapter delegates to `GremlinStepWalker.subWalk` with a fresh adapter per nested child; grandchild test asserts independent capture and `hasEdges` on the inner adapter.
- **Verdict**: MATCHES (unchanged from iter1).

#### C-bg2-deferred: reserved-$ guard for child sub-traversals — intentionally not re-checked
- **Status**: BG2 from iter1 remains a latent suggestion; combinators are not registered yet and child `as(...)` labels are not consumed as aliases until Track 6. Not reopened.
- **Verdict**: DEFERRED.
