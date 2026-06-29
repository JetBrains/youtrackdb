<!--
MANIFEST
dimension: test-structure
prefix: TS
verdict: pass
findings_total: 3
blockers: 0
should_fix: 0
suggestions: 3
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: TS1
    sev: suggestion
    anchor: "TS1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:679-685"
    cert: n/a
    basis: "in-context-diff"
  - id: TS2
    sev: suggestion
    anchor: "TS2"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:683"
    cert: n/a
    basis: "in-context-diff"
  - id: TS3
    sev: suggestion
    anchor: "TS3"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:538-867"
    cert: n/a
    basis: "in-context-diff"
-->

## Findings

### TS1 [suggestion] Comparison helpers placed mid-file, away from the top helper cluster

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java`, helpers `comparison` / `lowerComparison` (lines 679-685)

**Issue**: The shared helpers `parseExpression`, `parseComparison`, `parseNotBlock`, `parseOrBlock`, `parser`, `lower`, and `var` are grouped at the top of the class (lines 490-536), but `comparison(BinaryOperator)` and `lowerComparison(String)` are defined between test methods in the `// ---- Comparison ----` section (lines 679-685). A reader scanning for the helper set has to find two of them in the body. This is purely an organization nit — both helpers are well-named and documented inline, and isolation is unaffected.

**Suggestion**: Optionally relocate `comparison` and `lowerComparison` to the top helper cluster (near `var`, line 534), or leave them where they are if co-locating them with the comparison tests they serve is the deliberate intent. Low priority either way.

### TS2 [suggestion] Test helper `lowerComparison` mirrors a production method name with different routing

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java`, helper `lowerComparison` (line 683)

**Issue**: The test helper `lowerComparison(String)` parses SQL through the `BinaryCondition()` production and calls `AnalyzedExprLowerer.lowerBoolean(...)`. The production class has a private method `lowerComparison(SQLBinaryCondition)` that does something narrower (it maps the operator and recurses). The names coincide while the test helper actually exercises the `lowerBoolean` entry, not the production `lowerComparison`. A reader cross-referencing the two could momentarily expect a 1:1 correspondence. The doc comment on the helper (line 498-499 region) does clarify it parses a comparison, so the risk is small.

**Suggestion**: Consider a name that reflects the entry point exercised, e.g. `lowerBooleanFrom(String)` or `lowerComparisonSql(String)`, to keep the test-helper name from shadowing the production method name. Cosmetic; the existing doc comments already make the behavior unambiguous.

### TS3 [suggestion] Section banners are clear; a one-line class-level map of the section order would aid navigation

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java`, section comments across the class (lines 538-867)

**Issue**: The class is well-sectioned with `// ----` banners (Arithmetic precedence fold, Parenthesized grouping, Leaf shapes, Comparison, NOT, then the throw-case groups). The class-level `///` doc (lines 466-485) describes the two property families and the throw-case taxonomy in prose, which maps onto the banners well. This is already strong documentation; the only gap is that the prose taxonomy and the in-body banner order are stated in two places and a future editor must keep them in step. No current mismatch — the banners faithfully realize the documented taxonomy.

**Suggestion**: No change required. If the throw-case taxonomy grows, keep the class-`///` enumeration and the `// ----` banner sequence in the same order so the two stay a single readable story. Noted only so a later editor preserves the alignment.

## Evidence base
