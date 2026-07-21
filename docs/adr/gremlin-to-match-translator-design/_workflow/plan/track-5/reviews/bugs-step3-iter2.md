<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: BG1, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: C1, verdict: VERIFIED, anchor: "#### C1 "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

GATE VERDICT: PASS

Gate-check re-review (iteration 2) after `Review fix:` (BG1). Step diff: `1d0e346011..<fix-sha>`.

## Findings

No new findings.

## Evidence base

#### C1: BG1 VERIFIED — decline edge-bearing NOT when sub-walk captures origin-alias constraints
- **Fix**: `NotStepRecogniser.edgeBearingNotCapturesUnsupportedOriginConstraints` declines when `capturedAliasFilters` or `registeredAliasClasses` keys include the boundary alias, before `buildNotExpression`.
- **Tests**: `NotStepRecogniserTest.edgeBearingChild_withOriginAliasFilter_declines`; existing `edgeBearingChild_appendsDetachedNotExpression` still ACCEPTED for bare `not(out(knows))`.
- **Verdict**: VERIFIED.
