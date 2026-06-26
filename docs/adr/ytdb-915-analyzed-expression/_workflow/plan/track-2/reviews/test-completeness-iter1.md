<!--
MANIFEST
dimension: test-completeness
prefix: TC
iteration: 1
target: Track 2, Step 1 — NumericOps extraction (commit 09389fdc16)
verdict: changes-requested
evidence_base: "## Evidence base"
cert_index: { C1: G1, C2: G2, C3: G3, C4: G4, C5: G5, C6: G6 }
flags: [coverage-100pct-but-value-untested, lift-and-shift-preserves-untested-behavior]
index:
  - id: TC1
    sev: should-fix
    anchor: "#tc1-integerlong-divide-by-zero-arithmeticexception-is-unpinned-per-type-divergence"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java (apply(Operator,Integer,Integer) case SLASH / apply(Operator,Long,Long) case SLASH / apply(Operator,BigDecimal,BigDecimal) case SLASH)
    cert: C1
    basis: "left % right throws ArithmeticException for int/long right==0; float/double yield Infinity/NaN; BigDecimal throws — divergent per-type behavior, no test pins any of it"
  - id: TC2
    sev: should-fix
    anchor: "#tc2-integer-overflow-underflow-widening-value-is-asserted-only-by-class-not-value"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java (apply(Operator,Integer,Integer) case PLUS / case MINUS)
    cert: C2
    basis: "testTypes asserts only .getClass()==Long for MAX_VALUE+1 / MIN_VALUE-1; the widened value (2147483648L) is never asserted, so a wrap-then-widen bug passes"
  - id: TC3
    sev: suggestion
    anchor: "#tc3-bit_and-on-float-double-truncate-to-long-branch-has-no-test"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java (apply(Operator,Float,Float) case BIT_AND / apply(Operator,Double,Double) case BIT_AND)
    cert: C3
    basis: "BIT_AND on Float/Double truncates each operand to long then & — no test drives Float&Float or Double&Double at any layer"
  - id: TC4
    sev: suggestion
    anchor: "#tc4-bigdecimal-slash-half_up-rounding-and-scale-is-unpinned"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java (apply(Operator,BigDecimal,BigDecimal) case SLASH)
    cert: C4
    basis: "left.divide(right, HALF_UP) — the rounding mode and resulting scale are the one thing distinguishing BigDecimal SLASH; only the exact 1/1 case is driven (testTypes)"
  - id: TC5
    sev: suggestion
    anchor: "#tc5-xor-runsignedshift-bit_or-and-negative-operand-rem-value-semantics-unpinned"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java (apply(Operator,Integer,Integer) cases XOR/RUNSIGNEDSHIFT/BIT_OR/REM)
    cert: C5
    basis: "XOR, RUNSIGNEDSHIFT, BIT_OR value semantics are pinned nowhere; REM is class-pinned only, never value-checked on negative operands (sign-of-dividend)"
  - id: TC6
    sev: suggestion
    anchor: "#tc6-mixed-type-promotion-value-edges-bigdecimal-vs-float-double-are-class-pinned-not-value-pinned"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java (apply(Number,Operator,Number) widening arms — Float/Double -> BigDecimal via BigDecimal.valueOf)
    cert: C6
    basis: "widening of Float/Double to BigDecimal via BigDecimal.valueOf vs new BigDecimal(int/long) — only operand 1 is exercised; the float-binary-noise edge (0.1f) is never value-checked"
-->

## Findings

### TC1 [should-fix] Integer/Long divide-by-zero (ArithmeticException) is unpinned; per-type divergence

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java`, the `SLASH` and `REM` arms of `apply(Operator,Integer,Integer)`, `apply(Operator,Long,Long)`, `apply(Operator,Float,Float)`, `apply(Operator,Double,Double)`, `apply(Operator,BigDecimal,BigDecimal)`.
- **Missing scenario**: a zero divisor (`right == 0`) for `SLASH` and `REM` on every typed pair.
- **Why it matters**: the divisor-zero boundary is where the five typed forms diverge in observable behavior, and the divergence is invisible in the diff:
  - Integer `SLASH`: `left % right` is evaluated first (the exact-division test), so `x / 0` throws `ArithmeticException` from the `%`, not the `/`. Same for Integer `REM`.
  - Long `SLASH`/`REM`: same `ArithmeticException`.
  - Float/Double `SLASH`: `left / right` yields `Infinity`/`-Infinity`/`NaN` (no throw); Float/Double `REM` yields `NaN`.
  - BigDecimal `SLASH`: `left.divide(right, HALF_UP)` throws `ArithmeticException`; BigDecimal `REM` (`remainder`) throws too.
  This is a real, per-type-distinct contract the engine carries, and a future edit to the `SLASH` exact-division shortcut (the `if (left % right == 0)` line) could silently change which exception fires or whether one fires at all. No test pins any of it.
- **Evidence**: input-domain table row `right = 0` is `NO` for all five typed pairs. The nearest existing test, `SQLFunctionEvalTest.divisionByZeroExpressionIsSwallowedToBooleanOrNumber`, drives the literal `"10 / 0"` through the *predicate* path (it asserts `Boolean.FALSE`), which does not reach `NumericOps` at all — false comfort. `testIntegerDivideWidening` uses `6/2` and `7/2` only.
- **Refutation considered**: see cert C1 — checked whether the SQL layer guards a runtime-zero divisor (it does not; `v / w` with `w==0` reaches the engine), and whether the throw is "correct by construction" (it is behavior the engine defines differently per type, so a regression is observable). Survived.
- **Suggested test**:
  ```java
  @Test
  public void testDivideAndRemByZero() {
    // Integer/Long SLASH and REM throw ArithmeticException (the % shortcut throws first).
    Assert.assertThrows(ArithmeticException.class, () -> Operator.SLASH.apply(1, 0));
    Assert.assertThrows(ArithmeticException.class, () -> Operator.SLASH.apply(1L, 0L));
    Assert.assertThrows(ArithmeticException.class, () -> Operator.REM.apply(1, 0));
    // Float/Double SLASH do NOT throw — they yield Infinity / NaN.
    Assert.assertEquals(Float.POSITIVE_INFINITY, Operator.SLASH.apply(1f, 0f));
    Assert.assertTrue(((Double) Operator.SLASH.apply(0d, 0d)).isNaN());
    // BigDecimal SLASH throws (non-terminating / div-by-zero).
    Assert.assertThrows(
        ArithmeticException.class, () -> Operator.SLASH.apply(BigDecimal.ONE, BigDecimal.ZERO));
  }
  ```

### TC2 [should-fix] Integer overflow/underflow widening value is asserted only by class, not value

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java`, `apply(Operator,Integer,Integer)` `case PLUS` (the `sum < 0 && left > 0 && right > 0` upgrade) and `case MINUS` (the `result > 0 && left < 0 && right > 0` upgrade).
- **Missing scenario**: the *value* returned by the overflow/underflow-to-Long upgrade.
- **Why it matters**: this is the one branch in the whole extraction that exists purely to produce a *correct value* under overflow, and the only test touching it (`testTypes`, lines 69-72) asserts `.getClass() == Long.class` and nothing else. The upgrade body is `left.longValue() + right`; a regression to `(long)(left + right)` (widen *after* the int wrap) would also return a `Long` — so it passes the class check while returning `-2147483648L` instead of `2147483648L`. The class-only assertion cannot catch the exact bug the branch defends against. The review focus calls out `Integer.MAX_VALUE + 1` specifically; coverage here is 100% (the branch is taken) yet value-correctness is untested.
- **Evidence**: input-domain row `left = Integer.MAX_VALUE, right = 1` for `PLUS` → `Currently Tested? = class only at MathExpressionTest:69-70`; value column = `NO`. Symmetric row for `MINUS` (`Integer.MIN_VALUE, 1`) → class only at `:71-72`.
- **Refutation considered**: see cert C2 — confirmed no other test (SQL-level included) asserts the numeric value of an overflowing int add/subtract through `SQLMathExpression`. Survived.
- **Suggested test**:
  ```java
  @Test
  public void testIntegerOverflowWidensToCorrectLongValue() {
    Object plus = Operator.PLUS.apply(Integer.MAX_VALUE, 1);
    Assert.assertEquals(Long.class, plus.getClass());
    Assert.assertEquals(2147483648L, plus); // not the wrapped -2147483648
    Object minus = Operator.MINUS.apply(Integer.MIN_VALUE, 1);
    Assert.assertEquals(Long.class, minus.getClass());
    Assert.assertEquals(-2147483649L, minus); // not the wrapped 2147483647
    // The non-overflow case must stay Integer (the branch must not fire spuriously).
    Assert.assertEquals(Integer.valueOf(3), Operator.PLUS.apply(1, 2));
  }
  ```

### TC3 [suggestion] BIT_AND on Float/Double (truncate-to-long branch) has no test

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java`, `apply(Operator,Float,Float)` `case BIT_AND` and `apply(Operator,Double,Double)` `case BIT_AND` (`apply(op, left.longValue(), right.longValue())`).
- **Missing scenario**: `BIT_AND` driven on a `Float`/`Float` and `Double`/`Double` pair.
- **Why it matters**: this is the only floating-point arithmetic case in the engine that does *not* return `null` (every other shift/bitwise float case is `null`); it instead truncates each operand to `long` and ANDs. `testTypes` drives only `PLUS/MINUS/STAR/SLASH/REM` across typed pairs, never `BIT_AND`. `testAnd`/`testAnd2` drive only Integer operands. So the truncation semantics (`2.9f & 3.9f` → `2L & 3L` → `2L`) are pinned nowhere; a regression that dropped the `BIT_AND` arm into the `null` group would not fail any test.
- **Evidence**: input-domain row `op = BIT_AND, type = Float|Double` → `Currently Tested? = NO` (testTypes basic-op set excludes BIT_AND; testAnd/testAnd2 are Integer-only).
- **Refutation considered**: see cert C3. Survived (reachable via both the typed overload and `execute`; no indirect SQL test for `&` on floats).
- **Suggested test**:
  ```java
  @Test
  public void testBitwiseAndOnFloatingPointTruncatesToLong() {
    // BIT_AND on floats truncates each operand to long, then ANDs.
    Assert.assertEquals(2L, Operator.BIT_AND.apply(2.9f, 3.9f)); // 2L & 3L
    Assert.assertEquals(2L, Operator.BIT_AND.apply(6.5d, 3.5d)); // 6L & 3L
    // The other float bitwise/shift operators stay null.
    Assert.assertNull(Operator.BIT_OR.apply(1f, 1f));
    Assert.assertNull(Operator.XOR.apply(1d, 1d));
    Assert.assertNull(Operator.LSHIFT.apply(1f, 1f));
  }
  ```

### TC4 [suggestion] BigDecimal SLASH HALF_UP rounding and scale is unpinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java`, `apply(Operator,BigDecimal,BigDecimal)` `case SLASH` (`left.divide(right, RoundingMode.HALF_UP)`).
- **Missing scenario**: a non-terminating / rounding `BigDecimal` division.
- **Why it matters**: `RoundingMode.HALF_UP` is the single detail that distinguishes BigDecimal SLASH from the other typed SLASH forms, and it is load-bearing: without it, `1.divide(3)` throws (non-terminating decimal). The only BigDecimal SLASH the suite drives is `testTypes`' `BigDecimal.ONE / BigDecimal.ONE` (exact, no rounding). So a regression that changed the rounding mode, or dropped it, would either change the result silently or start throwing — neither is caught. The resulting scale (`1.divide(3, HALF_UP)` returns scale-0 `0`) is also surprising and worth pinning.
- **Evidence**: input-domain row `op = SLASH, type = BigDecimal, operands non-exact` → `NO`. Only `1/1` exact is at `MathExpressionTest:56`.
- **Refutation considered**: see cert C4. Survived.
- **Suggested test**:
  ```java
  @Test
  public void testBigDecimalDivideRoundsHalfUp() {
    // 10 / 3 with HALF_UP and the dividend's scale-0 → "3".
    Assert.assertEquals(
        new BigDecimal(3), Operator.SLASH.apply(new BigDecimal(10), new BigDecimal(3)));
    // 1.0 / 8 (scale 1) rounds HALF_UP at scale 1 → 0.1.
    Assert.assertEquals(
        new BigDecimal("0.1"), Operator.SLASH.apply(new BigDecimal("1.0"), new BigDecimal("8")));
  }
  ```

### TC5 [suggestion] XOR / RUNSIGNEDSHIFT / BIT_OR and negative-operand REM value semantics unpinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java`, `apply(Operator,Integer,Integer)` cases `XOR`, `RUNSIGNEDSHIFT`, `BIT_OR`, `REM`.
- **Missing scenario**: a value assertion for `^`, `>>>`, `|`, and `%` on a negative dividend.
- **Why it matters**: of the 12 operators, `XOR`, `RUNSIGNEDSHIFT`, and `BIT_OR` have no value test at any layer (the `Operator.apply` typed overloads are called only from `MathExpressionTest`, which never drives these three; the SQL-level `DocValidationTest` pins `>>`, `<<`, `null`-arith, and string-concat but not `^`/`>>>`/`|`). `RUNSIGNEDSHIFT` in particular has distinct semantics from `RSHIFT` only on a negative left operand (`-1 >>> 1` vs `-1 >> 1`) — exactly the operand the suite avoids. `REM` is class-pinned by `testTypes` but never value-checked on a negative dividend, where Java's sign-of-dividend rule (`-10 % 3 == -1`) is the contract.
- **Evidence**: input-domain rows `op ∈ {XOR, RUNSIGNEDSHIFT, BIT_OR}` → `NO` (no caller in tests); `op = REM, left < 0` → `NO`.
- **Refutation considered**: see cert C5 — this is lift-and-shift of preserved behavior, which lowers severity, but three operator families having zero value coverage is a real gap that the "math tests stay green" gate cannot defend. Kept as suggestion.
- **Suggested test**:
  ```java
  @Test
  public void testRemainingBitwiseAndSignedRemainder() {
    Assert.assertEquals(6, Operator.XOR.apply(5, 3));           // 0b101 ^ 0b011
    Assert.assertEquals(7, Operator.BIT_OR.apply(5, 3));        // 0b101 | 0b011
    // RUNSIGNEDSHIFT differs from RSHIFT precisely on a negative left operand.
    Assert.assertEquals(-1 >>> 1, Operator.RUNSIGNEDSHIFT.apply(-1, 1));
    Assert.assertEquals(-1 >> 1, Operator.RSHIFT.apply(-1, 1));
    // REM keeps the sign of the dividend.
    Assert.assertEquals(-1, Operator.REM.apply(-10, 3));
  }
  ```

### TC6 [suggestion] Mixed-type promotion value edges (BigDecimal vs Float/Double) are class-pinned, not value-pinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java`, `apply(Number,Operator,Number)` — specifically the `Float -> BigDecimal` (`BigDecimal.valueOf(a.floatValue())`) and `Double -> BigDecimal` (`BigDecimal.valueOf(a.doubleValue())`) widening arms, contrasted with the `Integer/Long -> BigDecimal` arms (`new BigDecimal(a.intValue())` / `new BigDecimal(a.longValue())`).
- **Missing scenario**: the *value* of a mixed Float-with-BigDecimal (or Double-with-BigDecimal) operation where the float has binary-representation noise (e.g. `0.1f`).
- **Why it matters**: the widening engine deliberately uses `BigDecimal.valueOf(double)` (canonical decimal string) for Float/Double but `new BigDecimal(int)` for integral types — a distinction that only shows up in the *value*, never the result *class*. `testTypes` exercises every mixed pair but always with operand `1`, where `BigDecimal.valueOf(1f)` and `new BigDecimal(1)` are indistinguishable. A regression swapping `BigDecimal.valueOf(a.floatValue())` for `new BigDecimal(a.floatValue())` (which would inject `0.1000000014901...`) would pass every existing test. This is the canonical "test data that happens to avoid all edge cases" gap: the operand `1` is chosen such that all promotion arms collapse to the same value.
- **Evidence**: input-domain row `a = Float|Double, b = BigDecimal, value with binary noise` → `NO`; `testTypes` covers the row only with operand `1` (class assertion).
- **Refutation considered**: see cert C6. Survived as a low-severity test-data-quality gap (the operand-`1` choice masks the `valueOf`-vs-`new BigDecimal` distinction the engine encodes).
- **Suggested test**:
  ```java
  @Test
  public void testFloatToBigDecimalUsesCanonicalDecimal() {
    // BigDecimal.valueOf(0.1f) yields the canonical "0.1"; new BigDecimal(0.1f) would not.
    Object result = Operator.PLUS.apply(0.1f, BigDecimal.ZERO);
    Assert.assertEquals(BigDecimal.class, result.getClass());
    Assert.assertEquals(BigDecimal.valueOf(0.1f), result);
    // Double form, same property.
    Assert.assertEquals(
        BigDecimal.valueOf(0.2d), Operator.PLUS.apply(0.2d, BigDecimal.ZERO));
  }
  ```

## Evidence base

#### Premises (production code read; PSI-confirmed reference graph)

- **P1**: `NumericOps` is greenfield (`sql/util/`, confirmed absent on develop per track-2.md). PSI find-usages: every `NumericOps.apply(...)` typed method and `applyObject` is called only from `SQLMathExpression.java` (the enum delegators) and from inside `NumericOps` itself — there is no third production caller and no direct test caller of `NumericOps`. Tests reach the engine only through `SQLMathExpression.Operator`'s public surface.
- **P2**: `Operator.apply(Object,Object)` has 15 references — 6 from `SQLMathExpression.execute`/widening internals and 9 from `MathExpressionTest`. The five typed `Operator.apply(T,T)` overloads have exactly one reference each, all in `MathExpressionTest.testTypes`. So the engine's value behavior is exercised from two surfaces: `MathExpressionTest` (direct typed + Object calls) and the SQL `execute` path (`DocValidationTest` syntax tests).
- **P3**: the engine's per-type branch set (read from the diff): each `apply(Operator,T,T)` is a 12-arm switch. SLASH/REM integer/long arms throw on zero divisor (via `%`); float/double shift+OR+XOR arms return `null`; float/double BIT_AND truncates to long; BigDecimal shift+bitwise arms return `null`, SLASH rounds HALF_UP. The widening entry `apply(Number,Operator,Number)` selects an arm by the right operand's runtime type and uses `BigDecimal.valueOf` for Float/Double but `new BigDecimal(int/long)` for integral types.
- **P4**: existing indirect coverage at the SQL layer (`DocValidationTest`): `testSyntaxBitwiseRightShift` (`8>>2`), `testSyntaxBitwiseLeftShift` (`2<<2`), `testSyntaxNullInPlus/Minus/Multiply/Divide`, `testSyntaxStringConcatenation`, and `WHERE total % 4 = 3` (REM, class/path only). `QueryOperatorModTest`/`QueryOperatorPlus/MinusTest` drive a *separate* operator implementation that never touches `SQLMathExpression.Operator` (track-2.md, gate-verified) — false comfort for this engine.

#### Refutation roster (Phase 4)

- **C1 (TC1) — CONFIRMED**: divisor-zero behavior diverges per type (int/long/BigDecimal throw; float/double yield Infinity/NaN) and is pinned by no test; `SQLFunctionEvalTest`'s `10/0` rides the predicate path, not the engine; new tests use non-zero divisors only. Survived → should-fix.
- **C2 (TC2) — CONFIRMED**: `testTypes` asserts only `.getClass()==Long` for the overflow/underflow upgrade; the widened value is never asserted, so a wrap-then-widen regression passes. The branch is 100%-covered yet value-untested. Survived → should-fix.
- **C3 (TC3) — CONFIRMED**: `BIT_AND` on Float/Double (the one non-null float bitwise arm, truncate-to-long) is driven by no test — `testTypes` basic-op set excludes `BIT_AND`; `testAnd/testAnd2` are Integer-only; no SQL `&`-on-floats test. Survived → suggestion.
- **C4 (TC4) — CONFIRMED**: BigDecimal SLASH `HALF_UP` rounding/scale pinned only by the exact `1/1` case; rounding-mode regression uncaught. Survived → suggestion.
- **C5 (TC5) — CONFIRMED**: `XOR`/`RUNSIGNEDSHIFT`/`BIT_OR` have zero value coverage at any layer; `REM` value never checked on a negative dividend (sign-of-dividend contract). Lift-and-shift of preserved behavior lowers severity. Survived → suggestion.
- **C6 (TC6) — CONFIRMED**: the `BigDecimal.valueOf(float/double)` vs `new BigDecimal(int/long)` widening distinction is value-only and is masked by `testTypes` using operand `1` everywhere; a `valueOf`→`new BigDecimal` regression on the float arm passes every test. Test-data-quality gap. Survived → suggestion.

No claims were refuted. One candidate was dropped below the reporting bar and is not filed as a finding: the `NULL_COALESCING` typed overloads contain `left != null ? left : right` where `left` is a boxed non-null parameter on the path that reaches them (the widening entry throws on null), making the `!= null` test dead/always-true — correct by construction (a faithful lift of the original enum bodies), no value-correctness gap, so not reported.
