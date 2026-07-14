<!--MANIFEST
dimension: test-structure
iteration: 1
verdict: PASS
counts: { blocker: 0, should-fix: 0, suggestion: 1 }
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: TS1
    sev: suggestion
    anchor: "TS1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java:337-354"
    cert: n/a
    basis: "read"
-->

## Findings

### TS1 [suggestion] Near-duplicate EXPLAIN helpers differ only by the params argument

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java`, methods `explainPlan` (line 337) and `explainPlanWithParams` (line 347)

**Issue**: The two private helpers are byte-for-byte identical apart from the extra `params` argument passed to `session.query`. Both run EXPLAIN, assert a row exists, read `executionPlanAsString`, assert it is non-null, and return it. The duplication is small but it is live documentation that a reader now has to diff line-by-line to confirm the two paths behave the same, and any future change to the EXPLAIN assertion contract has to be made in two places.

**Suggestion**: Collapse to one implementation. Have the no-params helper delegate, e.g. `private String explainPlan(String sql) { return explainPlanWithParams(sql, Map.of()); }`, so the assertion contract lives in exactly one method. This is optional polish; the current form is correct and readable.

## Evidence base
