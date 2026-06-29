<!--MANIFEST
dimension: performance
target: Track 3 Step 1 — AnalyzedExprLowerer + AnalyzedAstAccess + lowering test
commit_range: 772dd697c6faf2effeb1fa30a0912ecc616b78ee~1..772dd697c6faf2effeb1fa30a0912ecc616b78ee
verdict: PASS
findings_total: 1
blocker: 0
should_fix: 0
suggestion: 1
evidence_base: cert-backed; PSI reference-accuracy confirmed (mcp-steroid reachable, project analyzed-expression-5p7llp6k matches working tree)
cert_index: [C1, C2, C3]
flags: []
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-suggestion-funccall-arg-list-double-allocation"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:279-284"
    cert: C3
    basis: "Compile-time path; two list allocations per method-call node where one suffices. NEGLIGIBLE at all scales."
-->

## Findings

### PF1 [suggestion] FuncCall arg-list double allocation (`new ArrayList` + `List.copyOf`)

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java` (line 279-284)
- **Issue**: `lowerWithOptionalModifier` builds the method-call argument list with `new ArrayList<>()`, fills it, then returns `new FuncCall(methodName, List.copyOf(args))`. `List.copyOf` allocates a second backing array, so a method-call node costs two list allocations where one would do.
- **Evidence**: See cert C3. The lowering pass runs at query-compile time (cert C1), once per method-call node in a parsed expression, over a tiny argument list (zero to a handful of arguments). This is not the per-row path — Track 4's evaluator is. The extra copy is a single short-lived array per method-call node.
- **Impact**: One extra small short-lived heap allocation per method-call node lowered, at compile time. No measurable latency, throughput, or GC effect at any scale. The double-copy is also a deliberate immutability choice: `List.of`/`List.copyOf` give the record an unmodifiable backing list, matching the track-file note that `FuncCall.args()` is read-only by convention. Recorded only because the review lens asks about avoidable per-node allocations.
- **Suggestion**: Optional. If the immutable-list guarantee is wanted with one allocation, the `List.of(...)` factory or a sized `List.copyOf` over a pre-sized accumulator changes little; leaving it as-is is fine. Do not change for performance — the cost is negligible. Flagged for completeness, not action.

## Evidence base

#### C1 — Lowering is a compile-time transform over a small parse tree, not a per-row hot path (CONFIRMED-as-context)
Survived: the lowering pass runs once per expression at query-compile time over a parse tree holding a handful of operators/operands; Track 4's evaluator is the per-row path (track-3.md Context and Orientation; workflow focus note). No row-frequency multiplier applies to any allocation or loop in this diff.

#### C2 — Precedence-climbing fold is linear in operator count, not quadratic (CONFIRMED-as-context)
Survived: `foldArithmetic` / `climb` (AnalyzedExprLowerer.java:129-168) walk a shared `int[] cursor` that advances monotonically (`cursor[0]++` only forward; the outer `while` and the nested `climb(..., priority - 1)` share the same cursor reference), so each operand and operator is consumed exactly once. `Operator.getPriority()` is a plain field read (PSI: `public int getPriority() { return priority; }`), O(1) per operator, not a list scan. `getChildExpressions()` / `getOperators()` return the backing lists directly with no defensive copy (PSI-confirmed). Total fold cost is O(n) in operator count with n `BinaryOp` record allocations — one per arithmetic operator, the minimum for a binary IR tree. The focus note's "accidentally quadratic from repeated list scans/copies" concern was checked and refuted: there is no per-step list rescan or copy. Recursion depth is bounded by expression nesting depth (small for any hand-written SQL; no stack risk).

SCALE CHECK (fold): at 3 operators, 100 operators, or an adversarial 10000-operator expression the fold stays linear; VERDICT NEGLIGIBLE — the algorithm is already optimal for building a nested binary tree. Per-node IR records (`Const`, `Var`, `BinaryOp`, `UnaryOp`, `FuncCall`) each allocate once (immutable records, AnalyzedExpr.java:39-60); `Var` allocates one `List.of(columnName)` singleton list. No boxing in loops, no string concatenation in loops, no repeated `getPriority()` recomputation, no iterator-allocation hot spot. All within the same NEGLIGIBLE verdict given C1.

#### C3 — FuncCall double list allocation (CONFIRMED-as-issue, suggestion-grade)
`lowerWithOptionalModifier` (AnalyzedExprLowerer.java:279-284): `List<AnalyzedExpr> args = new ArrayList<>(); args.add(base); for (...) args.add(lower(param)); return new FuncCall(methodName, List.copyOf(args));`. COST TRACE: one `ArrayList` allocation plus one `List.copyOf` immutable-copy allocation per method-call node lowered; OPERATION runs at compile time (C1), once per `.method()` modifier; DATA SCALE = base + method-call params (single-digit). SCALE CHECK: AT SMALL/MEDIUM/PRODUCTION SCALE the impact is negligible — these are short-lived compile-time allocations with no row multiplier. VERDICT NEGLIGIBLE; recorded as a suggestion only because the lens explicitly asks about avoidable per-node allocations and the double-copy is real. The `List.copyOf` is also a deliberate immutability guarantee (read-only `FuncCall.args()` by convention), so the second copy buys an invariant, not just waste.
