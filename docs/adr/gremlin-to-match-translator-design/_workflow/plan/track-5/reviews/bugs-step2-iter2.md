<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: BG1, verdict: VERIFIED}
  - {id: BG2, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 2, matches: 2}
cert_index:
  - {id: C1, verdict: VERIFIED, anchor: "#### C1 "}
  - {id: C2, verdict: VERIFIED, anchor: "#### C2 "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

GATE VERDICT: PASS

Gate-check re-review (iteration 2) after `Review fix:` `38604b38f9`. Full step diff: `deff77513b..38604b38f9`. mcp-steroid unreachable; symbol audits used grep + direct reads `(grep-only)`.

## Findings

No new findings.

## Evidence base

#### C1: BG1 VERIFIED — appendPattern flips hasEdges on edge-bearing merge
- **Fix**: `SubTraversalPredicateAdapter.appendPattern` (SubTraversalPredicateAdapter.java:246-255) reads `captured.edgeCount() > 0` then sets `hasEdges = true` after `appendFrom`. `MatchPatternBuilder.edgeCount()` (MatchPatternBuilder.java:247-248) exposes `pattern.getNumOfEdges()`.
- **Tests**: `appendPattern_mergesCapturedHopIntoAdapter` expects `hasEdges() == true`; `AndStepRecogniserTest.nestedAndOfOutHops_thenHas_keepsBothHops` asserts two edges kept; `EdgeTraversalEquivalenceTest.nestedAndOfOutHops_thenHas_matchesNative` locks multiset equality. `(grep-only)`
- **Verdict**: VERIFIED.

#### C2: BG2 VERIFIED — OR folds boundary re-types as classEquals (option a)
- **Fix**: `ConnectiveStepSupport.singleCapturedFilter` (ConnectiveStepSupport.java:100-114) ANDs each boundary `registeredAliasClasses` entry via `WHERE.classEquals` into the OR operand; non-boundary re-type declines. `OrStepRecogniser` Javadoc documents the fold; path remains `collectOrExpressions` → one `putAliasFilter`.
- **Tests**: `OrStepRecogniserTest.polymorphicHasLabelPlusHas_foldsClassEqualsIntoEachOrOperand` asserts two `@class =` arms; `PredicateTraversalEquivalenceTest.polymorphicOrHasLabelPlusHas_matchesNative` pins Person/Company discrimination under polymorphic mode. `(grep-only)`
- **Verdict**: VERIFIED.
