<!--MANIFEST
dimension: test-behavior
prefix: TB
output_path: docs/adr/ytdb-915-analyzed-expression/_workflow/plan/track-2/reviews/test-behavior-iter1.md
evidence_base: { certs: 4 }
cert_index: [C1, C2, C3, C4]
flags: { evidence_trail_exempt: false }
index:
  - { id: TB1, sev: suggestion, anchor: "TB1", loc: "MathExpressionTest.java:272-287 (testDateArithmetic)", cert: C3, basis: "diff + full test read + PSI overload resolution + NumericOps source" }
findings_count: 1
-->

## Findings

### TB1 [suggestion] `testDateArithmetic` does not pin that `Date ± Long` leaves the input `Date` unmutated

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java`, method `testDateArithmetic` (lines 272-287)
- **Issue**: The test reuses a single `var base = new Date(1_000L)` across all three operations (`PLUS.apply(base, 500L)`, `PLUS.apply(500L, base)`, `MINUS.apply(base, 250L)`) and asserts the *returned* object's type (`Date.class`) and epoch (1500, 1500, 750). It never asserts that `base` itself is still `1_000L` afterward. The contract the lifted engine implements is non-mutating: `NumericOps.plusObject`/`minusObject` build a fresh `new Date(result.longValue())` and never touch the operand. A regression to in-place mutation of the input `Date` (e.g. `base.setTime(...)` instead of `new Date(...)`) would still produce a `Date` of the right epoch on the first operation and so would slip past the current assertions — the later operations read 500L/250L as the *other* operand, not `base`'s field, so a corrupted `base` would not necessarily flip them either. The test would give false confidence for that specific mutation class.
- **Evidence**: FALSIFIABILITY CHECK C3 — the type+value assertions are strong against the branch-selection and arithmetic mutations (those are caught), but the "fresh `Date`, operand untouched" half of the contract is unverified. This is a precision gap, not a coverage-driven defect; the test is otherwise behavior-driven and binds to the lifted code.
- **Missing behavior**: that the operand `Date` is not mutated by the operation (the engine returns a new instance).
- **Suggested fix**:
  ```java
  Object plusRight = Operator.PLUS.apply(base, 500L);
  Assert.assertEquals(Date.class, plusRight.getClass());
  Assert.assertEquals(1_500L, ((Date) plusRight).getTime());
  // Operand must be left unmutated: the engine builds a fresh Date.
  Assert.assertEquals(1_000L, base.getTime());
  // Returned Date must be a distinct instance, not the operand handed back.
  Assert.assertNotSame(base, plusRight);
  ```

## Evidence base

C1, C2, C4 confirmed-as-strong (compressed to one line each per the survived-claim rendering); C3 is the surviving suggestion, shown in full.

#### C1 — testIntegerDivideWidening binds to the lifted engine and pins type+value on both branches: SURVIVED (test is correct and falsifiable)
`Operator.SLASH.apply(6, 2)` and `apply(6L, 2L)` resolve (PSI) to `SQLMathExpression.Operator#apply(Integer,Integer)` / `apply(Long,Long)` enum-constant-body overrides, which delegate to `NumericOps.apply(this, …)`; `apply(7, 2)` / `apply(7L, 2L)` hit the same path. Exact-division assertions use `assertEquals(Integer.valueOf(3), …)` / `assertEquals(Long.valueOf(3L), …)` (both resolve to `assertEquals(Object,Object)`), so `Integer.equals`/`Long.equals` pin value AND integral type (Integer-vs-Long would flip). Non-exact assertions pin `getClass() == Double.class` AND `assertEquals(3.5, intResult)` — the latter resolves to `assertEquals(Object,Object)` with `3.5` autoboxed to `Double`, i.e. `Double.equals`, not a `double,double` compare, so no epsilon is needed and 3.5 (exactly representable) compares exactly. Mutation drop-the-widening-branch → returns Integer 3 → `Double.class` assertion fails; mutation always-widen → 6/2 returns Double 3.0 → `Integer(3).equals(Double(3.0))` is false → fails. Both caught.

C2 — testArithmeticNullPropagation binds to `apply(Object,Object)` on PLUS/MINUS/STAR/SLASH (PSI-confirmed; the `(Object)` casts correctly disambiguate away from the typed overloads) and pins each null-handling branch with `assertEquals(5/-5, …)` (Integer.equals pins value+type) or `assertNull(…)`: SURVIVED. Mutations — PLUS treating null as null-propagating → `assertEquals(5,…)` fails; STAR/SLASH returning the non-null operand instead of null → `assertNull` fails. Caught.

#### C3 — testDateArithmetic: PARTIAL (type+value caught, operand-non-mutation NOT caught) → finding TB1
The three `apply(…)` calls resolve (PSI) to `SQLMathExpression.Operator#apply(Object,Object)` on PLUS/MINUS, which delegate to `NumericOps.plusObject`/`minusObject` (Date branch → `new Date(toLong(left) ± toLong(right))`). `getClass() == Date.class` + `getTime()` (resolved to `assertEquals(long,long)`, exact) pin branch selection and epoch: drop the Date branch → PLUS falls through to String concat, `Date.class` assertion fails; corrupt the epoch arithmetic → `getTime()` assertion fails. Both caught. NOT caught: a regression to in-place operand mutation (`new Date` → `setTime`) — the returned epoch is still correct and `base`'s post-state is never asserted. See TB1.

C4 — testStringConcatenation binds to `apply(Object,Object)` on PLUS (PSI-confirmed) and pins exact concatenated strings ("ab","a1","1b") covering String+String, String+int, int+String: SURVIVED. Mutation — drop the String branch (return null / attempt numeric) → exact-string `assertEquals` fails (or NPE/ClassCast). Caught. (`String+null` / `null+String` interplay is a null-propagation-vs-concat corner; that is a test-completeness axis, out of this dimension.)
