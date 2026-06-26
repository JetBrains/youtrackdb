<!--MANIFEST
dimension: test-structure
prefix: TS
output_path: docs/adr/ytdb-915-analyzed-expression/_workflow/plan/track-2/reviews/test-structure-iter1.md
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: TS1, sev: suggestion, anchor: "TS1", loc: "MathExpressionTest.java:235,239,276,280,284,288", cert: n/a, basis: "diff + full test file read" }
findings_count: 1
-->

## Findings

### TS1 [suggestion] New tests declare locals as explicit `Object` while the file's idiom is `var`

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`, methods `testIntegerDivideWidening` (lines 235, 239) and `testDateArithmetic` (lines 276, 280, 284, 288)
- **Issue**: The four new tests are well-structured, isolated, and self-documenting (see Reviewer notes), but the captured-result locals are declared with an explicit `Object` type — `Object intResult = ...`, `Object longResult = ...`, `Object plusRight = ...`, `Object plusLeft = ...`, `Object minus = ...`. Every pre-existing test in this file declares its locals with `var` (`var expr`, `var exp`, `var result`, `var base`). This is a readability/consistency nit, not a correctness or isolation problem: the explicit `Object` type does carry a small documentation benefit (it signals that `apply(...)` returns an erased `Object`/`Number` so the following `.getClass()` assertion is meaningful), so the deviation is defensible. The `var base = new Date(1_000L)` line in the same `testDateArithmetic` already uses `var`, which makes the mixed style within one method the visible inconsistency.
- **Suggestion**: For uniformity with the surrounding file, prefer `var` for these captured-result locals (e.g. `var intResult = Operator.SLASH.apply(7, 2);`). If the explicit `Object` is kept deliberately to document the erased return type, leave as-is — this is optional and either choice is acceptable.

## Evidence base
