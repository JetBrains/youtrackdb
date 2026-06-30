<!--MANIFEST
dimension: code-quality
prefix: CQ
iter: 1
verdict: APPROVE
findings_total: 3
blockers: 0
should_fix: 0
suggestions: 3
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: CQ1
    sev: suggestion
    anchor: cq1-assertnull
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:375
    cert: n/a
    basis: diff + JUnit 4 convention
  - id: CQ2
    sev: suggestion
    anchor: cq2-fqn-imports
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:373
    cert: n/a
    basis: diff + import-style convention
  - id: CQ3
    sev: suggestion
    anchor: cq3-row-helper-even-length
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:115
    cert: n/a
    basis: diff
-->

## Findings

### CQ1 [suggestion] Use `assertNull` instead of `assertEquals(null, ...)` {#cq1-assertnull}

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java` (lines 375, 387, 481)

**Issue**: Three assertions use `assertEquals(null, value)` / `assertEquals(null, ...)`
(`varResolvesNullForAbsentProperty`, `varResolvesNullForNullRow`, and the null-literal arm
of `constReturnsLiteralValue`). The JUnit 4 idiom for a null expectation is `assertNull`,
which reads as intent and produces a clearer failure message (`expected null, but was: <…>`)
than the equality form. The class already statically imports `assertEquals`, `assertTrue`,
`assertFalse`, so adding `assertNull` is consistent with the existing import block.

**Suggestion**: Replace `assertEquals(null, value)` with `assertNull(value)` at the three
sites and add `import static org.junit.Assert.assertNull;`.

### CQ2 [suggestion] Inline fully-qualified names where the class already imports the same package's siblings {#cq2-fqn-imports}

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java` (lines 373, 384, 397, 410, 424, 492, 498)

**Issue**: The test reaches for `java.util.List.of(...)` fully-qualified in the direct-IR
construction tests (`varResolvesNullForAbsentProperty`, `varResolvesNullForNullRow`,
`varResolvesResultMetadataFallback`, `varResolvesTemporaryPropertyFallback`,
`funcCallUnknownMethodThrows`, `notNegatesBooleanDirectly`) and `org.junit.Assert.assertThrows(...)`
fully-qualified in `funcCallUnknownMethodThrows`, even though the file already imports JUnit
statics and a normal `import java.util.List;` would match how the production class imports its
own dependencies. The inline FQNs add visual noise and are inconsistent within the file (other
assertions are imported statically). House style is no wildcard imports, but single-type
imports are the norm.

**Suggestion**: Add `import java.util.List;` and `import static org.junit.Assert.assertThrows;`,
then use the simple names. (`AnalyzedExpr.Var`/`Const`/`FuncCall`/`UnaryOp`/`BinaryOp` are
referenced via the outer `AnalyzedExpr.` prefix, which is fine and need not change.)

### CQ3 [suggestion] `row(Object...)` helper silently assumes an even argument count {#cq3-row-helper-even-length}

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java` (lines 115-121)

**Issue**: The `row(Object... namesAndValues)` helper steps `i += 2` and reads
`namesAndValues[i + 1]`. An odd-length call (a typo dropping a value) reads past the array and
fails with a bare `ArrayIndexOutOfBoundsException` that names neither the helper nor the
offending pair. Every current caller passes an even count, so this is not a live bug, but a
guard makes a future mis-call fail with intent. This is a test-only helper, so the bar is low.

**Suggestion**: Optionally add `assert namesAndValues.length % 2 == 0 : "row() expects
name/value pairs";` at the top of the helper. Skippable if you judge the convention obvious
enough from the call sites.

## Evidence base

<!-- This dimension is evidence-trail-exempt: (a) no refutation or certificate phase to persist. -->
