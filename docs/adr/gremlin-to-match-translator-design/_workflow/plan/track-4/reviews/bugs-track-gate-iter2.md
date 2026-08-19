<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK, GATE_PASS]
-->

## Gate verification — iteration 2

Track-level bugs review iter 1 had 0 blockers and 0 should-fix (1 suggestion BG1, accepted-not-fixed). Phase C fixes applied before gate:

- **C8 (iter 1, now superseded):** iter 1 claimed `eq(null)` used `IS DEFINED AND IS NULL`; Phase C pin (`nullComparand_nativeMembership_pinnedBeforeEquivalence`) showed native membership is absent + present-null, so `GremlinPredicateAdapter` now emits bare `IS NULL`. Equivalence test passes.
- **CQ1/CQ2:** stale Javadoc in `HasStepRecogniser` and `PredicateTraversalEquivalenceTest` updated for the Step 3 strict-throw pivot.
- **TQ1–TQ3:** equivalence fixtures added for NULL/absent, range/singleton-collection, and remaining String Text variants.

All iter-1 certificate claims re-verified on HEAD except C6 (BG1 suggestion, documented tradeoff on declared-String `startingWith` index range vs schema-violating non-String operand — accepted-not-fixed).

**Verdict:** PASS — no blockers, no open should-fix on the bugs axis.
