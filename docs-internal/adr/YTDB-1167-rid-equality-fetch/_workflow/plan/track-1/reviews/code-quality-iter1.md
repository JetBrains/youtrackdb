<!--MANIFEST
dimension: code-quality
iteration: 1
verdict: PASS
findings_total: 1
findings_by_sev: {blocker: 0, should-fix: 0, suggestion: 1}
evidence_base: {certs: 0}
cert_index: []
flags: [evidence-trail-exempt]
index:
  - id: CQ1
    sev: suggestion
    anchor: "### CQ1"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java:1122
    cert: n/a
    basis: grep + Read (PSI unavailable; symbols uniquely named)
-->

## Findings

### CQ1 [suggestion] `tryExtractRidInListFromTerm` omits the post-unwrap `assert` its sibling carries

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java` (line 1122-1145, the new `tryExtractRidInListFromTerm`)
- **Issue**: The new detector is a faithful structural sibling of `tryExtractRidFromTerm` (SQLWhereClause.java:1152) — same single-element-wrapper unwrap loop, same non-negated `NotBlock` skip, same left-side `tryExtractRidValue` reuse. But `tryExtractRidFromTerm` guards the loop's exit with an assertion (`assert !(term instanceof SQLOrBlock) && !(term instanceof SQLAndBlock)`, line 1172-1173) documenting the invariant that the loop peeled off every wrapper; the new sibling drops it. The two loops are byte-for-byte identical, so the invariant holds equally in both — the omission is a consistency gap, not a correctness one.
- **Suggestion**: Add the same `assert` after the `while (true)` loop in `tryExtractRidInListFromTerm` so the two siblings stay symmetric and the invariant is self-documenting. Note (`CLAUDE.md` JaCoCo trap): `assert` lines are excluded from the changed-code coverage gate, so this adds no coverage burden.
- **Certainty**: high. grep + Read against the worktree; both methods read in full. Reference accuracy is not load-bearing here (structural comparison of two adjacent methods in one file, no symbol search).

## Evidence base
