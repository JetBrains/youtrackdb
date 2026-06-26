<!--MANIFEST
dimension: code-quality
iteration: 1
verdict: PASS
findings_total: 2
findings_by_severity: {blocker: 0, should-fix: 0, suggestion: 2}
evidence_base: {certs: 0}
index:
  - id: CQ1
    sev: suggestion
    anchor: "CQ1"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java:126"
    cert: n/a
    basis: "diff read; import block inspection"
  - id: CQ2
    sev: suggestion
    anchor: "CQ2"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java:116-135"
    cert: n/a
    basis: "diff read"
cert_index: []
flags: []
-->

## Findings

### CQ1 [suggestion] Inline fully-qualified `java.util.ArrayList` instead of an import

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java` (line 126)

**Issue**: `transformChildren` uses the fully-qualified `new java.util.ArrayList<>(...)` even though the file already imports `java.util.List` at the top. Mixing a fully-qualified inline reference with a sibling import from the same package is a small consistency wrinkle — every other `java.util` type in the file (`List`) is imported and used by simple name.

**Suggestion**: Add `import java.util.ArrayList;` and use `new ArrayList<>(args.subList(0, i))`. This matches the file's own import style and reads cleaner. Spotless will keep the import ordering correct on `spotless:apply`. Purely cosmetic; the current form compiles and behaves identically.

### CQ2 [suggestion] Lazy-copy `FuncCall` rebuild reads more procedurally than the sibling compound arms

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java` (lines 116-135)

**Issue**: The `BinaryOp`/`UnaryOp` arms of `transformChildren` are compact and declarative (transform children, compare by `==`, rebuild or return). The `FuncCall` arm is a hand-rolled loop with a `newArgs == null` sentinel that doubles as both the "nothing changed yet" flag and the "have I started accumulating" flag, plus a `List.copyOf(newArgs)` at the end that copies the freshly-built `ArrayList` a second time. The inline comment explains the lazy-allocation intent well, so the logic is verifiable — this is a readability/idiom note, not a correctness concern, and the lazy-copy-on-first-change behavior is exactly what D8 and the track's Validation rows require, and is directly tested (cases d/e/f).

**Suggestion**: Optional. The arm is correct and documented; leave it as-is if the lazy single-allocation is the deliberate shape (it is, per the comment and the design's structural-sharing goal). If a future reader trips on the double-meaning of the `newArgs` sentinel, a one-line clarifying rename or a short `// null until the first change; non-null thereafter` note at the declaration would remove the ambiguity. No change required for this slice. (Allocation strategy itself is out of this dimension's scope — flagged here only as a readability observation.)

## Evidence base
