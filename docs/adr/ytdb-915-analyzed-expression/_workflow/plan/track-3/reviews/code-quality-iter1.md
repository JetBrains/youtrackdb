<!--
MANIFEST
dimension: code-quality
iteration: 1
verdict: PASS
blocker_count: 0
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: CQ1
    sev: should-fix
    anchor: "cq1-comment-line-width"
    loc: "AnalyzedExprLowerer.java, AnalyzedAstAccess.java, AnalyzedExprLowererTest.java (Javadoc/line comments)"
    cert: n/a
    basis: "awk column scan of the three new files + sibling Track-1/2 files; eclipse-formatter.xml comment-format settings; CLAUDE.md/house-style 100-col rule"
  - id: CQ2
    sev: suggestion
    anchor: "cq2-int-array-cursor"
    loc: "AnalyzedExprLowerer.java:129-168 (foldArithmetic / climb)"
    cert: n/a
    basis: "diff read of the precedence-climbing fold; Java idiom judgment"
  - id: CQ3
    sev: suggestion
    anchor: "cq3-paren-double-throw-readability"
    loc: "AnalyzedExprLowerer.java:193-202 (lowerParenthesis)"
    cert: n/a
    basis: "diff read; PSI-confirmed SQLParenthesisExpression has exactly expression/statement payloads"
-->

## Findings

### CQ1 [should-fix] Comment lines exceed the 100-column convention; inconsistent with sibling tracks

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java`, `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/AnalyzedAstAccess.java`, `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java` (Javadoc `///` and `//` comment lines)

**Issue**: About 90 comment lines across the three files run past 100 columns, the widest at 107 (`AnalyzedExprLowerer.java:51`). Every over-width line is a comment — a column scan finds **zero code lines over 100** in any of the three files, so the code body itself is clean. This is not a Spotless build blocker: `project-config/eclipse-formatter.xml` disables comment reflow (`comment.format_javadoc_comments=false`, `format_line_comments=false`, `join_lines_in_comments=false`), so the formatter leaves these lines alone and the build stays green. But the documented convention (CLAUDE.md / house-style: "Line width: 100 characters") draws no comment exemption, and the inconsistency is internal to this feature: Track 1's `AnalyzedExpr.java` has 0 comment lines over 100 and Track 2's `NumericOps.java` has 2, so the earlier slices wrapped their prose at ~100 and this slice did not. The comments are accurate and valuable — this is purely a wrap-width gap, not a content problem.

**Suggestion**: Re-wrap the over-width comment lines at 100 columns to match the convention and the sibling tracks. Since the formatter will not do it, it is a manual reflow. Low effort; mechanical.

### CQ2 [suggestion] `int[] cursor` mutable out-parameter in the precedence fold reads as a C idiom

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java` (lines 129-168, `foldArithmetic` / `climb`)

**Issue**: The precedence-climbing fold threads cursor position through a single-element `int[] cursor` so the recursive `climb` can advance a shared index by mutating `cursor[0]`. The logic is correct and the surrounding comments explain the climb well, but boxing an `int` in a one-element array to get pass-by-reference is a C-style workaround that a Java reader has to decode before trusting the index bookkeeping. It is contained to two private methods, so the blast radius is small.

**Suggestion**: Optional. A tiny mutable cursor holder (e.g. a private `static final class Cursor { int index; }` or a local class) names the intent — `cursor.index++` reads more directly than `cursor[0]++`, and the field name documents what the slot is. Equally acceptable to leave as-is given the contained scope and the explanatory comments; flagging for the reviewer's judgment, not asking for a rewrite.

### CQ3 [suggestion] `lowerParenthesis` throws the same exception from two adjacent guards

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java` (lines 193-202, `lowerParenthesis`)

**Issue**: `lowerParenthesis` throws `UnsupportedAnalyzedNodeException(parenthesis.getClass())` once for the subquery case (`parenStatement != null`) and again for the `inner == null` case, two lines apart. Both are correct: PSI confirms `SQLParenthesisExpression` carries exactly the two mutually-exclusive payloads `expression` / `statement`, so `statement == null && expression == null` is an unexpected-shape guard rather than dead code, and keeping it is defensive-correct. The minor readability cost is that a reader sees two identical throws and has to confirm they are distinct conditions rather than a copy-paste slip.

**Suggestion**: Optional. The `inner == null` branch could carry a one-line comment (mirroring the `statement != null` comment just above) noting it guards the both-null unexpected shape, so the two identical throws read as deliberate. No structural change needed.

## Evidence base
