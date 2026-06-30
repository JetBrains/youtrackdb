<!--
MANIFEST
review: test-completeness
iter: 1
target: Track 4 Step 1 — AnalyzedExprEvaluator + round-trip parity suite
commit_range: 74a93c20347bb3229bd92d521ca87aeafab0024a~1..74a93c20347bb3229bd92d521ca87aeafab0024a
level: high
evidence_base: "#evidence-base"
cert_index: { TC1: C1, TC2: C2, TC3: C3, TC4: C4, TC5: C5 }
flags: []
index:
  - id: TC1
    sev: should-fix
    anchor: "#tc1-should-fix--null-propagation-only-pins-plus-the-three-other-arithmetic-operators-have-different-null-semantics"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:487
    cert: C1
    basis: "Operator.apply(Object,Object) bodies: PLUS=plusObject, MINUS=minusObject, STAR/SLASH return null on any null operand (SQLMathExpression.java:81-219, NumericOps.java:53-101); only PLUS tested"
  - id: TC2
    sev: should-fix
    anchor: "#tc2-should-fix--string-concatenation-and-mixed-string-arithmetic-the-plusobject-toString-fallback-is-untested"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:496
    cert: C2
    basis: "plusObject falls back to String.valueOf(left)+right when neither operand path matches (NumericOps.java:75); minusObject returns null for the same operands (NumericOps.java:100); both in-subset via string literals, neither tested"
  - id: TC3
    sev: suggestion
    anchor: "#tc3-suggestion--divide-by-zero-and-divide-non-integer-widening-only-the-exact-divisor-case-is-pinned"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:466
    cert: C3
    basis: "NumericOps.apply(Operator,Integer,Integer) SLASH branches on left%right==0 → int vs widened double (NumericOps.java:145-150); a/b/c=100/5/2 only exercises the exact-divide arm"
  - id: TC4
    sev: suggestion
    anchor: "#tc4-suggestion--funccall-with-method-parameters-and-the-current-seed-only-a-zero-arg-method-is-covered"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:612
    cert: C4
    basis: "visitFuncCall builds params[] for args 1..n and guards-seeds VAR_CURRENT (AnalyzedExprEvaluator.java:172-181); only asInteger() (zero params, no $current read) is tested"
  - id: TC5
    sev: suggestion
    anchor: "#tc5-suggestion--nested-not-and-not-over-a-non-comparison-boolean-operand-untested"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:601
    cert: C5
    basis: "lowerBoolean recurses NOT over NOT and collapses a non-negated NOT block to a pass-through (AnalyzedExprLowerer.java:310-327); only single-level NOT over a comparison is tested"
-->

# Test completeness review — Track 4 Step 1

## Findings

### TC1 [should-fix] — Null-propagation only pins PLUS; the three other arithmetic operators have different null semantics

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:487` (`arithmeticNullPropagation`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMathExpression.java:81-219` (the per-constant `Operator.apply(Object, Object)` bodies), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java:53-101` (`plusObject` / `minusObject`)
- **Missing scenario**: A null operand under `*`, `/`, and `-` (and a null left vs. null right under `-`). The suite tests null propagation only through `a + b` with `a` absent (PLUS).
- **Why it matters**: The four covered arithmetic operators do not share a null contract — the evaluator routes each IR constant to a *different* `Operator.apply(Object, Object)` body, and those bodies disagree on null:
  - `PLUS` → `plusObject`: `null + 3 = 3`, `3 + null = 3` (`NumericOps.java:53-76`).
  - `MINUS` → `minusObject`: `3 - null = 3`, `null - 3 = 0 - 3 = -3` (negation), non-numeric pair → `null` (`NumericOps.java:82-101`).
  - `STAR` / `SLASH` → guard `if (left == null || right == null) return null` *before* `applyObject` (`SQLMathExpression.java:81-86`, `113-120`).

  The IR→AST enum map (`AnalyzedExprEvaluator.toArithmeticOperator`) is what selects the body, so a transcription slip there (e.g. mapping IR `MINUS` to AST `PLUS`) would still pass `arithmeticNullPropagation` because both reduce a null operand to a non-null result — but it would flip the sign on `null - x` and would not be caught. The single PLUS row pins one of four divergent null contracts; the other three (including the asymmetric `null - x` negation) are unexercised. This is the "test data that happens to avoid all edge cases" pattern: PLUS is the one operator whose null behavior is least likely to expose a mis-mapping.
- **Evidence**: Input-domain table row "null operand × {PLUS, MINUS, STAR, SLASH}" — only PLUS is `YES (line 487)`; MINUS/STAR/SLASH are `NO`. See cert C1.
- **Refutation considered**: Could the AST oracle make these trivially parity-equal regardless? No — the oracle (`SQLExpression.execute` → `SQLMathExpression.execute`) reaches the *same* `Operator.apply(Object, Object)` body, so parity holds *if the IR maps the operator correctly*. That is exactly the property under test: the round-trip suite's job is to catch a mis-mapped operator, and a MINUS/STAR/SLASH null row is where a mis-map shows up while a PLUS null row hides it. Confirmed meaningful.
- **Suggested test**:
  ```java
  /// Null-operand propagation differs per arithmetic operator: STAR/SLASH null-guard to null,
  /// MINUS returns the other operand (and negates on null-left), PLUS already covered. Each row
  /// pins the AST Operator.apply(Object,Object) body the IR routes that operator to.
  @Test
  public void arithmeticNullPropagationPerOperator() {
    assertValueParity("a * b", row("b", 3));   // null * 3  -> null
    assertValueParity("a / b", row("b", 3));   // null / 3  -> null
    assertValueParity("a - b", row("b", 3));   // null - 3  -> -3 (0 - 3)
    assertValueParity("a - b", row("a", 3));   // 3 - null  -> 3
    assertValueParity("a * b", row("a", 3));   // 3 * null  -> null
  }
  ```

### TC2 [should-fix] — String concatenation and mixed String arithmetic: the `plusObject` `toString()` fallback is untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:496` (arithmetic block; no concat row exists)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java:75` (the `String.valueOf(left) + right` fallback in `plusObject`) and `:100` (`minusObject` returns `null` for the same non-numeric operands)
- **Missing scenario**: `'foo' + 'bar'` (String concat), `'foo' + 1` / `1 + 'foo'` (mixed String/Number through PLUS), and the contrasting `'foo' - 'bar'` (MINUS over non-numeric → `null`). String literals are in the lowering subset (`AnalyzedExprLowerer.lowerBase` handles `getStringLiteralValue`, `:222-225`), so these fragments lower and the IR walks them.
- **Why it matters**: `plusObject` has a third behavior beyond numeric add and null: when neither operand is a matched Number/Date it concatenates via `String.valueOf(left) + right` (`NumericOps.java:75`). This is the only operator with that fallback — `minusObject` returns `null` for the same operands (`NumericOps.java:100`), and `STAR`/`SLASH` return `null`. The evaluator's doc explicitly calls out `String` concat as one of the three object-level semantics the AST-routing preserves that a direct numeric call would skip; that claim is asserted in prose but never exercised. A regression that bypassed the object-level entry (e.g. a future fast-path that called `NumericOps.apply(Number, …)` directly) would throw `ClassCastException` on `'foo' + 'bar'`, and no test would catch it. The asymmetry (`+` concatenates, `-` returns null) is a second under-tested promotion edge.
- **Evidence**: Input-domain table rows "String op String" and "String op Number" — all `NO`. See cert C2.
- **Refutation considered**: Is concat reachable in the S0 subset, or only numeric arithmetic? Reachable — `lowerBase` lowers a string literal to `Const(String)` and `lowerMath`/`foldArithmetic` will join two string `Const`s under `PLUS` exactly as for numbers; the AST parses `'foo' + 'bar'` as an `SQLMathExpression` with `SQLBaseExpression` string leaves. Not refuted.
- **Suggested test**:
  ```java
  /// PLUS concatenates non-numeric operands (String.valueOf(left)+right) while MINUS over the
  /// same operands returns null — the object-level promotion semantics the AST-routing preserves.
  @Test
  public void arithmeticStringConcatAndMixed() {
    assertValueParity("a + b", row("a", "foo", "b", "bar")); // "foobar"
    assertValueParity("a + b", row("a", "foo", "b", 1));     // "foo1"
    assertValueParity("a + b", row("a", 1, "b", "foo"));     // "1foo"
    assertValueParity("a - b", row("a", "foo", "b", "bar")); // null (minusObject final else)
  }
  ```

### TC3 [suggestion] — Divide-by-zero and divide-non-integer widening: only the exact-divisor case is pinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:466` (`arithmeticDivideWideningLeftAssociative`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java:145-150` (`SLASH` on `Integer`/`Integer`: `left % right == 0 ? left/right : ((double) left)/right`)
- **Missing scenario**: An integer divide that does *not* divide evenly (the widening-to-`double` arm) and integer divide-by-zero. `a / b / c` = `100 / 5 / 2` exercises only the `left % right == 0` branch at both nodes (`100/5=20`, `20/2=10`), so the `((double) left) / right` arm and the `right == 0` boundary are never hit.
- **Why it matters**: The matrix row claims to pin "integer-vs-double divide widening through `NumericOps`", but the chosen data divides evenly at every step, so the `int` arm is the only one taken — the double-widening arm the row is named for is untested. Divide-by-zero is a documented value-sensitive boundary: `5 / 0` (int) throws `ArithmeticException`, while `5.0 / 0` (double) yields `Infinity`; whatever the AST does, the IR must match, and that is exactly a `NumericOps`-shared edge the round-trip suite exists to pin.
- **Evidence**: Input-domain table row "SLASH widening: even vs. uneven vs. zero divisor" — even = `YES (line 466)`, uneven = `NO`, zero = `NO`. See cert C3.
- **Refutation considered**: Is the uneven arm covered indirectly elsewhere? Searched the suite — no other `/` fragment exists; `arithmeticDivideWideningLeftAssociative` is the only divide row, and its operands divide evenly. Not covered. Severity held to suggestion because the shared `NumericOps` has its own `NumericOpsTest` (Track 2), so the promotion arm is pinned there — this is a round-trip-parity completeness gap, not an unguarded engine path.
- **Suggested test**:
  ```java
  /// Integer divide that does not divide evenly widens to double; divide-by-zero is a value-
  /// sensitive boundary. Both must match the AST through the shared NumericOps.
  @Test
  public void arithmeticDivideWideningAndByZero() {
    assertValueParity("a / b", row("a", 7, "b", 2));   // 3.5 (double-widen arm)
    assertValueParity("a / b", row("a", 7, "b", 0));   // AST vs IR must agree (throw or value)
  }
  ```

### TC4 [suggestion] — FuncCall with method parameters and the `$current` seed: only a zero-arg method is covered

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:612` (`funcCallMethodCoercionParity`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java:172-182` (the `params[i-1]` loop over `args[1..n]` and the guarded `VAR_CURRENT` seed)
- **Missing scenario**: A method call carrying at least one parameter (`args.size() > 1`), so the `for (int i = 1; i < args.size(); i++)` parameter-evaluation loop runs with a non-empty body. `name.asInteger()` lowers to a `FuncCall` with `args = [target]` only, so the loop never iterates and `params` is always length 0.
- **Why it matters**: `visitFuncCall` evaluates each remaining arg into `params[i-1]` and passes the array to `SQLMethod.execute`. With only a zero-parameter method tested, an off-by-one in the param-index arithmetic (`params[i-1]`, `new Object[args.size()-1]`) or a failure to recurse into a parameter sub-expression would never be exercised. A method that takes an argument and whose argument is itself an IR sub-expression (e.g. another column or a literal) is the case that pins the loop. The matrix row admits `name.asInteger()` as "a covered method-call fragment"; a parameterized coercion (e.g. `value.asDecimal(2)` or a string method taking an argument) would close the param-loop gap. (The `$current` guarded seed at `:179` is also unexercised by a method that ignores `$current` — minor, noted in C4.)
- **Evidence**: Input-domain table row "FuncCall args: zero-param vs. ≥1-param" — zero-param = `YES (line 612)`, ≥1-param = `NO`. See cert C4.
- **Refutation considered**: Is a parameterized method in the S0 subset? `lowerWithOptionalModifier` (`AnalyzedExprLowerer.java:284-290`) lowers every `methodCall.getParams()` entry into `args[i+1]`, so a parameterized method-call modifier is in-subset and lowers. Pick a `SQLMethod` registered with `SQLEngine` that accepts a parameter; the round-trip oracle handles correctness. Not refuted; severity suggestion because the param-loop is small and the AST oracle backstops the value.
- **Suggested test**:
  ```java
  /// A method-call coercion that carries a parameter exercises the params[] loop (args[1..n]),
  /// which name.asInteger() (zero params) leaves unrun. Choose any SQLMethod that takes an arg.
  @Test
  public void funcCallWithParameterParity() {
    // e.g. a left/substring/format-style method registered in SQLEngine that takes one argument
    assertValueParity("name.<methodTakingOneArg>(2)", row("name", "abcdef"));
  }
  ```

### TC5 [suggestion] — Nested NOT and NOT over a non-comparison boolean operand untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:601` (`notNegatesComparison`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:310-327` (`lowerBoolean` recursion: `NOT` over a boolean sub, `notBlock.isNegate()` true/false), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java:138-149` (`visitUnaryOp`)
- **Missing scenario**: Double negation (`NOT NOT a = b`, or its parser equivalent) and a non-negated `NOT` block (the `isNegate() == false` pass-through arm at `AnalyzedExprLowerer.java:325-327`). The suite tests only a single `NOT` over one comparison via `assertNotParity`, and the direct-IR `notNegatesBooleanDirectly` builds a single `UnaryOp(NOT, EQ)`.
- **Why it matters**: `lowerBoolean` recurses NOT over its sub through the same boolean entry (`AnalyzedExprLowerer.java:321`), so `NOT NOT x` produces `UnaryOp(NOT, UnaryOp(NOT, …))` and `visitUnaryOp` must compose two casts/negations. The non-negated `SQLNotBlock` arm collapses to a pass-through (the sub without a wrapping `UnaryOp`) — an arm with no round-trip coverage at all. A single-level NOT cannot exercise either the recursion or the pass-through. These are state-transition / nesting boundaries the matrix's one `NOT a = b` row does not reach.
- **Evidence**: Input-domain table rows "NOT nesting depth = 1 vs. ≥2" and "NOT block negate=true vs. false" — depth-1/negate-true = `YES (line 601)`, depth-≥2 and negate-false = `NO`. See cert C5.
- **Refutation considered**: Does the parser even produce a non-negated `SQLNotBlock`, or is the pass-through arm dead? The arm exists because `lowerBoolean` is package-visible and a same-package caller (this suite) can parse a `NotBlock` whose grammar admits a non-negated form; the lowerer explicitly handles `isNegate() == false`. Whether the round-trip parser path reaches it depends on the `NotBlock()` production — if it does not, a direct hand-built IR assertion (as `notNegatesBooleanDirectly` already does for the negated case) covers the pass-through is *not* applicable since the pass-through is a lowerer arm, not an evaluator arm. Held to suggestion: the lowerer arm is Track 3's surface, and this suite's job is evaluator parity; the durable gap here is double-negation in `visitUnaryOp`, which is squarely Track 4.
- **Suggested test**:
  ```java
  /// Double negation composes two visitUnaryOp(NOT) negations; a single NOT cannot pin the
  /// recursion. Build the IR directly to avoid depending on the parser's NotBlock nesting shape.
  @Test
  public void notDoubleNegationComposes() {
    AnalyzedExpr eq =
        new AnalyzedExpr.BinaryOp(BinaryOperator.EQ,
            new AnalyzedExpr.Var(java.util.List.of("a")), new AnalyzedExpr.Const(5));
    AnalyzedExpr notNot =
        new AnalyzedExpr.UnaryOp(UnaryOperator.NOT, new AnalyzedExpr.UnaryOp(UnaryOperator.NOT, eq));
    var ctx = context();
    assertEquals(true, AnalyzedExprEvaluator.evaluate(notNot, row("a", 5), ctx));  // NOT NOT true
    assertEquals(false, AnalyzedExprEvaluator.evaluate(notNot, row("a", 6), ctx)); // NOT NOT false
  }
  ```

## Evidence base

The suite is genuinely strong on the dimensions the matrix names: precedence (`a+b*c`, `a*b+c`), left-associativity (`a-b-c`, `a-b+c`), parenthesis recursion (both sides), the six comparison operators on a plain row plus equal-operand boundaries, collation matched/unmatched/right-operand/absent-property/schemaless, the EQ-vs-NE session difference on two mixed-type rows, `longCol != 1` cross-type, the `Identifiable` adapter (match + non-match), and the four `visitVar` fallback branches plus the unknown-method throw. The findings below are the corner cases *beyond* that minimum that a naive-but-coverage-passing implementation would still get wrong.

#### C1 — Per-operator null divergence (CONFIRMED)
Survived: `Operator.apply(Object,Object)` routes PLUS→`plusObject`, MINUS→`minusObject`, STAR/SLASH→null-guard-then-`applyObject` (`SQLMathExpression.java:81-219`); `plusObject`/`minusObject` differ on null (`NumericOps.java:53-101`). Only PLUS null is tested (line 487). A mis-mapped IR→AST operator constant is invisible to a PLUS-only null row. PSI: `Operator.apply(Object,Object)` is the entry the evaluator and the AST oracle both reach, so parity is map-correctness-gated.

#### C2 — String concat / mixed-String fallback (CONFIRMED)
Survived: `plusObject` has a `String.valueOf(left)+right` fallback (`NumericOps.java:75`) absent from every other operator; `minusObject` returns `null` for the same operands (`NumericOps.java:100`). String literals lower (`AnalyzedExprLowerer.java:222-225`). No concat row exists in the suite. The evaluator Javadoc asserts the concat semantic is preserved; nothing exercises it.

#### C3 — Divide widening / by-zero (CONFIRMED, suggestion)
Survived: `100/5/2` takes only the `left%right==0` arm at both `/` nodes (`NumericOps.java:145-150`); the `((double)left)/right` arm and the `right==0` boundary are unexercised. The matrix row is named for widening it does not reach. Backstopped by Track 2 `NumericOpsTest`, hence suggestion.

#### C4 — FuncCall param loop / $current seed (CONFIRMED, suggestion)
Survived: `name.asInteger()` produces `args=[target]`, so the `params[i-1]` loop body (`AnalyzedExprEvaluator.java:172-175`) never runs and the guarded `$current` seed (`:179`) is never read by the method. A parameterized method-call modifier is in-subset (`AnalyzedExprLowerer.java:284-290`). AST oracle backstops the value, hence suggestion.

#### C5 — Nested / non-negated NOT (CONFIRMED, suggestion)
Survived: `lowerBoolean` recurses NOT over NOT (`AnalyzedExprLowerer.java:321`) and has a non-negated pass-through arm (`:325-327`); `visitUnaryOp` composes nested negations (`AnalyzedExprEvaluator.java:138-149`). Only depth-1 negated NOT is tested. The durable evaluator-side gap is double-negation composition in `visitUnaryOp`; the pass-through arm is a Track 3 lowerer surface, hence suggestion.

#### Refuted / not-reported (recorded for the gate-check)
- **`collateFor` `(EntityImpl) row.asEntity()` CCE on a non-`EntityImpl` entity.** Refuted as a parity gap: the AST's `SQLSuffixIdentifier.getCollate` performs the identical downcast, so the IR and AST throw together — parity holds by construction, and query-result entities are `EntityImpl` in practice. No finding.
- **`Const(null)` / null-literal arithmetic and comparison.** Covered: `constReturnsLiteralValue` (line 786) pins `Const(null)`; `arithmeticNullPropagation` pins a null operand reaching `plusObject`. The remaining null-operator gap is TC1, not a separate Const finding.
- **Boundary integer values (`Integer.MAX_VALUE` add-overflow → Long upgrade).** Considered: `NumericOps.apply(Operator,Integer,Integer)` PLUS upgrades overflow to Long (`NumericOps.java:152-158`). Not reported as a Track-4 finding — this is a shared-engine arm owned by Track 2 `NumericOpsTest`; adding it to the round-trip suite is marginal over TC1/TC2, which already pin the object-level promotion entry. Mentioned here so the gate-check sees it was weighed.
