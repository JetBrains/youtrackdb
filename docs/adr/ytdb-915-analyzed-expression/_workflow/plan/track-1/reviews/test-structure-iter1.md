<!--MANIFEST
dimension: test-structure
prefix: TS
verdict: PASS
blockers: 0
findings_total: 3
findings_by_sev: {blocker: 0, should-fix: 0, suggestion: 3}
high_water_mark: 3
evidence_base: {certs: 0}
cert_index: []
flags: [evidence-trail-exempt]
index:
  - id: TS1
    sev: suggestion
    anchor: "TS1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java, method funcCallWithTwoArgumentsChangedAccumulatesIntoOneNewList (line 554)"
    cert: n/a
    basis: "branch-row ordering: (f) two-args case sits below the negative case, breaking the (a)-(f) label sequence"
  - id: TS2
    sev: suggestion
    anchor: "TS2"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java, methods dispatchAppliesTransformDefaultsAndRebuildsChangedSpine (line 583), dispatchWithIdentityTransformSharesEntireNestedTree (line 613)"
    cert: n/a
    basis: "two dispatch-driven nested-tree tests sit under the transformChildren section comment; a dedicated section header would aid navigation"
  - id: TS3
    sev: suggestion
    anchor: "TS3"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java, method equalButRebuiltCopyDefeatsSharing (line 534)"
    cert: n/a
    basis: "assertTrue(result instanceof BinaryOp) is weaker and less informative on failure than a cast/assertEquals on the variant"
-->

## Findings

### TS1 [suggestion] Branch-row label sequence is interrupted by the negative case

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`, method `funcCallWithTwoArgumentsChangedAccumulatesIntoOneNewList` (line 554)
- **Issue**: The track's `## Validation and Acceptance` names branch rows (a)-(e) plus a negative case, and the test maps to them cleanly with `(a)`...`(f)` labels in the method-doc comments. The reading order is broken once: row `(f)` (two-arguments-changed) is placed *after* the negative-case test (`equalButRebuiltCopyDefeatsSharing`, line 534) and after row `(e)` (line 505). So the on-file order runs (a), (b), (c), (c'), (d), (e), negative, (f). A reader scanning the alphabetic labels hits the negative case between (e) and (f). This is purely an ordering nit; every row is present, individually named, and self-documenting, which is exactly what the track requires.
- **Suggestion**: Move `funcCallWithTwoArgumentsChangedAccumulatesIntoOneNewList` (f) up to sit beside the other `FuncCall` rows (d)/(e), leaving the negative case (`equalButRebuiltCopyDefeatsSharing`) as the last item in the structural-sharing block. Optional; the labels are otherwise unambiguous.

### TS2 [suggestion] Dispatch-driven nested-tree tests lack their own section header

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`, methods `dispatchAppliesTransformDefaultsAndRebuildsChangedSpine` (line 583) and `dispatchWithIdentityTransformSharesEntireNestedTree` (line 613)
- **Issue**: The file uses section comments (`// ---- Dispatch exhaustiveness ----`, `// ---- Structural sharing by reference identity (transformChildren) ----`, `// ---- UnsupportedAnalyzedNodeException ----`) to group methods, which reads well. The two tests that drive a transform through the real `AnalyzedExpr.dispatch` entry point over a *nested* tree (exercising the `AnalyzedExprTransform` recurse-into-children defaults, distinct from the direct `transformChildren` unit-level rows above them) live under the `transformChildren` section without their own marker. These two are a meaningfully different test category — they cover the transform *defaults* and end-to-end dispatch rather than the helper in isolation — so a reader has to read the method bodies to see the boundary.
- **Suggestion**: Add a section comment such as `// ---- Transform defaults via dispatch (nested trees) ----` before line 583 to mirror the existing grouping convention. Cosmetic; the method names and docs already explain each test's intent.

### TS3 [suggestion] Negative-case variant assertion could be more precise

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`, method `equalButRebuiltCopyDefeatsSharing` (line 534)
- **Issue**: The final assertion `assertTrue(result instanceof BinaryOp)` (line 546) confirms the rebuilt result is still a `BinaryOp`, but `assertTrue` collapses to a bare "expected true, was false" on failure and carries less documentary weight than the surrounding `assertNotSame`/`assertEquals` calls. The other tests in the same area cast the result and assert on the rebuilt node's fields (e.g. `assertEquals(BinaryOperator.STAR, rebuilt.op())`), which both documents the expected shape and gives a useful failure message. Readability-only; the assertion is correct.
- **Suggestion**: Consider replacing the bare `instanceof` with the cast-and-assert pattern used elsewhere (e.g. cast to `BinaryOp` and assert `op()` is `PLUS`), or at minimum `assertEquals(BinaryOp.class, result.getClass())` so a failure names the actual type. Optional.

## Evidence base
