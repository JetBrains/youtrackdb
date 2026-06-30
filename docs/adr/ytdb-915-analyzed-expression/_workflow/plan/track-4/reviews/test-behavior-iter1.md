<!--
MANIFEST
dimension: test-behavior
iteration: 1
verdict: PASS
counts: {blocker: 0, should-fix: 0, suggestion: 3}
evidence_base: present
cert_index: [C1, C2, C3]
flags: []
index:
  - id: TB1
    sev: suggestion
    anchor: "#tb1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:526"
    cert: C1
    basis: "PSI + production-code trace of SQLEqualsOperator/SQLNeOperator.execute and QueryOperatorEquals.equals"
  - id: TB2
    sev: suggestion
    anchor: "#tb2"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:612"
    cert: C2
    basis: "Trace of visitFuncCall $current seeding side effect (AnalyzedExprEvaluator.java:179-181)"
  - id: TB3
    sev: suggestion
    anchor: "#tb3"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:578"
    cert: C3
    basis: "Assertion-precision read of comparisonAllOperatorsParity vs the comment's claimed coverage"
-->

## Findings

### TB1 [suggestion] Session-threading tests pin parity but never establish that EQ and NE actually diverge

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java`, methods `comparisonNotEqualSessionThreading` (line 526) and `comparisonLongColumnVsIntegerLiteral` (line 547)

**Issue.** Both tests exist to pin D11's load-bearing nuance: `SQLEqualsOperator.execute` passes the live session to `QueryOperatorEquals.equals`, while `SQLNeOperator.execute` passes `null` (confirmed at `SQLEqualsOperator.java:27` and `SQLNeOperator.java:23`). The tests assert IR-vs-oracle parity for an EQ row and an NE row, which is correct and behavior-driven. But each row asserts only `lower(...).evaluate(...)` equals `ast.evaluate(...)` for that single operator; neither test asserts that the EQ result and the NE result are anything other than trivially complementary booleans. The mechanism the test name claims to pin — that the *session argument* changes the coercion outcome — is never independently established.

**Falsifiability.** FALSIFIABILITY CHECK: mutate `SQLNeOperator.execute` to pass the live `session` instead of `null` (i.e. delete the deliberate null-threading). For `val = 7`, `val != 'x'`: the NE path becomes `!QueryOperatorEquals.equals(session, 7, "x")` instead of `!QueryOperatorEquals.equals(null, 7, "x")`. Both the IR and the AST oracle call the *same* `SQLNeOperator.execute`, so the mutation moves both sides identically — the parity assertion stays green regardless. The test catches a divergence between IR and AST, but it cannot catch a regression in the shared operator's session handling, because the oracle shares that exact code. So these two tests do not actually verify the session-threading distinction they are named for; they verify that the IR reuses the AST operator (which `comparisonAllOperatorsParity` already covers structurally). The distinction is real in production, but the only thing pinning it here is the comment.

**Missing behavior.** A direct assertion that EQ and NE resolve the mixed-type operands *differently* in a way attributable to the session — i.e. that the two operator classes are not interchangeable for these operands. That is what makes "reproducing the AST's concrete operator class" load-bearing rather than incidental.

**Suggested fix.**
```java
// In comparisonNotEqualSessionThreading, after the two parity asserts, pin that the
// operators are genuinely not interchangeable for these operands — otherwise the
// "session threading matters" claim rests on the comment alone.
boolean ne = (Boolean) AnalyzedExprEvaluator.evaluate(
    AnalyzedExprLowerer.lowerBoolean(parseComparison("val != 'x'")), stored, ctx);
boolean eq = (Boolean) AnalyzedExprEvaluator.evaluate(
    AnalyzedExprLowerer.lowerBoolean(parseComparison("val = 'x'")), stored, ctx);
// If these are ever equal-and-non-complementary the session difference would be untested;
// assert the complement that the concrete-class reconstruction must preserve.
assertNotEquals("EQ and NE must not collapse to the same result on mixed-type operands",
    eq, ne);
```
(If EQ/NE are merely complementary here, the assertion is weak too — consider an operand pair where the live-session vs null-session coercion actually flips one side, so the mutation above is caught.)

### TB2 [suggestion] `visitFuncCall` seeds `$current` into the context as a side effect that no test verifies

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java`, method `funcCallMethodCoercionParity` (line 612)

**Issue.** `visitFuncCall` performs a guarded mutation of the command context (`AnalyzedExprEvaluator.java:179-181`): when `$current` (`VAR_CURRENT`) is unset and the row is non-null, it sets `$current` to the row so a method that reads `$current` sees it. The guard ("only when unset") is itself a behavior — a method call must not clobber a `$current` already bound by an enclosing scope. `funcCallMethodCoercionParity` exercises the seeding indirectly through `name.asInteger()` parity, but no test asserts (a) that `$current` is actually seeded, or (b) that a pre-existing `$current` is left untouched.

**Falsifiability.** FALSIFIABILITY CHECK: mutate the guard from `getSystemVariable(VAR_CURRENT) == null` to unconditional `setSystemVariable(VAR_CURRENT, row)`. `asInteger()` does not read `$current`, so the parity assertion stays green and the clobber goes undetected. The guarded-seed branch is real production behavior with no falsifying assertion.

**Missing behavior.** A direct unit assertion that an enclosing `$current` survives a `FuncCall` evaluation, and (optionally) that `$current` is seeded when previously unset.

**Suggested fix.**
```java
@Test
public void funcCallDoesNotClobberExistingCurrentVariable() {
  var ctx = context();
  Result outer = row("name", "outer");
  ctx.setSystemVariable(CommandContext.VAR_CURRENT, outer);
  AnalyzedExpr ir = AnalyzedExprLowerer.lower(parseExpression("name.asInteger()"));
  AnalyzedExprEvaluator.evaluate(ir, row("name", "42"), ctx);
  // The guarded seed must leave an already-bound $current untouched.
  assertSame(outer, ctx.getSystemVariable(CommandContext.VAR_CURRENT));
}
```

### TB3 [suggestion] `comparisonAllOperatorsParity` could pin the ordering `doCompare`-vs-0 boundaries more explicitly

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java`, method `comparisonAllOperatorsParity` (line 578)

**Issue.** The test's doc comment claims it sweeps "the ordering `doCompare`-vs-0 mapping" across every concrete operator class. It does run all six operators (and both `!=` spellings) against `a=3,b=5` and a second `a=5,b=5` row, which is genuinely behavior-driven parity. But the rows never exercise the `a > b` true case or `a >= b` strict-greater case (the `eq` row only adds `< / <= / >= / =` at equality; the first row has `a < b`). The `doCompare`-vs-0 mapping for each operator (`< 0`, `<= 0`, `> 0`, `>= 0`) is therefore only partially boundary-covered: `>` and `>=` are never asserted true via an `a > b` row.

**Falsifiability.** FALSIFIABILITY CHECK: mutate `SQLGtOperator`'s mapping so it returns the `>=` result (`c >= 0`) instead of `c > 0`. Against `a=3,b=5` the parity oracle and IR both compute `false` (3 is not >= 5 and not > 5 — same), and against `a=5,b=5` `>` is not asserted, so the mutation survives. Because both IR and oracle reuse the same operator class, this is again a shared-code mutation the parity check cannot catch on its own — but a row where `a > b` is true (and asserted) would at least exercise the boundary directly.

**Missing behavior.** A row with `a > b` (e.g. `row("a", 5, "b", 3)`) sweeping all six operators, so each operator's true *and* false sides are both asserted, including the strict-greater boundary.

**Suggested fix.**
```java
// Add the greater-than row so >/>= are asserted true and </<= asserted false,
// completing the boundary sweep the comment claims.
Result gt = row("a", 5, "b", 3);
assertComparisonParity("a > b", gt);
assertComparisonParity("a >= b", gt);
assertComparisonParity("a < b", gt);
assertComparisonParity("a <= b", gt);
assertComparisonParity("a = b", gt);
assertComparisonParity("a != b", gt);
```

## Evidence base

#### C1 — EQ/NE session-threading divergence is real, but parity tests share the operator with the oracle
CONFIRMED. `SQLEqualsOperator.execute` (`core/.../sql/parser/SQLEqualsOperator.java:27`) calls `QueryOperatorEquals.equals(session, l, r)` with the live session; `SQLNeOperator.execute` (`SQLNeOperator.java:23`) calls `!QueryOperatorEquals.equals(null, l, r)`. In `QueryOperatorEquals.equals` (`core/.../sql/operator/QueryOperatorEquals.java:60-113`) the session reaches `PropertyTypeInternal.convert(session, …)` only on the "ALL OTHER CASES" branch (line 101), which is the branch a mixed-type `Integer/Long vs String` comparison takes. The divergence the tests are named for is genuine. The falsifiability gap is structural: the IR comparison path delegates to a freshly constructed instance of the *same* concrete operator class the AST oracle uses (`AnalyzedExprEvaluator.comparisonOperator`, diff lines 288-301), so any mutation to the shared operator moves IR and oracle in lockstep and the `Objects.equals` parity assertion cannot observe it. The tests verify IR-reuses-AST-operator (valuable) but not the session distinction itself.

#### C2 — `visitFuncCall` guarded `$current` seed is an unasserted side effect
CONFIRMED. `AnalyzedExprEvaluator.visitFuncCall` (diff lines 179-181) reads `ctx.getSystemVariable(CommandContext.VAR_CURRENT)` and sets it to `row` only when null. `VAR_CURRENT == 0` and the get/set API are confirmed at `core/.../command/CommandContext.java:41,64,69` and `BasicCommandContext.java:81,108`. `funcCallMethodCoercionParity` (line 612) uses `name.asInteger()`, which does not read `$current`, so neither the seed nor the only-when-unset guard is falsifiable through that test. Minor: it is a real branch with no direct assertion.

#### C3 — `comparisonAllOperatorsParity` ordering coverage is partial relative to its doc comment
CONFIRMED. The test (lines 578-593) uses `a=3,b=5` (so `a < b`) and `a=5,b=5` (equality), never `a > b`. `SQLBinaryCompareOperator.doCompare` (`core/.../sql/parser/SQLBinaryCompareOperator.java:329-364`) returns a sign each ordering operator maps against 0; the `>`-true and `>=`-strict-greater boundaries are never asserted true. The comment's claim to sweep "the ordering `doCompare`-vs-0 mapping" overstates the coverage. The existing assertions are precise parity checks (not shallow), so this is a completeness-of-boundary note at suggestion severity, not a precision defect.

### Note on the flagged coverage-gate hazard (no finding)
The review brief flagged that the implementer "converted two unreachable defensive operator-mismatch switch arms to a single `default ->` throw each and added direct-unit tests for the reachable guard branches" to clear branch coverage. Verified honest:
- The two `default -> throw new IllegalStateException(...)` arms in `toArithmeticOperator` (diff lines 210-212) and `comparisonOperator` (diff lines 298-300) are unreachable by construction: `visitBinaryOp` (diff lines 130-135) partitions the `BinaryOperator` enum into arithmetic (`PLUS/MINUS/STAR/SLASH`) and comparison (`EQ/NE/LT/LE/GT/GE`) before dispatching, so a comparison constant never reaches the arithmetic switch and vice versa. Collapsing the per-constant mismatch arms into one `default` is a correct branch-count reduction, and — correctly — **no test was added to hit those arms**, because they cannot be reached.
- The added reachable-branch unit tests (`varResolvesNullForAbsentProperty`, `varResolvesNullForNullRow`, `varResolvesResultMetadataFallback`, `varResolvesTemporaryPropertyFallback`, `funcCallUnknownMethodThrows`, `collateGuardOnEntityWithAbsentProperty`, `collateResolvedFromRightOperand`, `constReturnsLiteralValue`, `notNegatesBooleanDirectly`) each assert a real, distinct value or exception, not a non-null placeholder. `varResolvesResultMetadataFallback`/`varResolvesTemporaryPropertyFallback` mirror the AST's `SQLSuffixIdentifier.execute(Result, ctx)` fallback chain (`core/.../sql/parser/SQLSuffixIdentifier.java:156-168`) and assert the exact value (`7`, `"value"`). `funcCallUnknownMethodThrows` uses `assertThrows(UnsupportedAnalyzedNodeException.class, …)` against a hand-built `FuncCall` with an unknown name — the precise reachable guard at diff lines 166-170. These are behavior-driven, not coverage-chasing.

### Overall assessment (no finding)
The suite is the strongest kind of parity harness: a shape-dispatched AST oracle (`SQLExpression.execute` for arithmetic/funccall, `SQLBinaryCondition.evaluate(Result, ctx)` for comparison/NOT, both confirmed at their sources) compared by real `Objects.equals`, never a self-comparison. The collate helper (diff lines 252-276) is a verified line-by-line mirror of `SQLSuffixIdentifier.getCollate` (`SQLSuffixIdentifier.java:505-521`), and the schemaless / absent-property / right-operand collate branches are each pinned. Precedence/associativity, integer-vs-double widening, parenthesis grouping, null-propagation, and `Date + Long` (`NumericOps.plusObject`, `core/.../sql/util/NumericOps.java:53-73`) are all exercised against the oracle. The `parityHarnessComparesByValue` self-check guards the harness against identity-vs-value confusion. The two `wrap`/`Identifiable` tests assert both the positive (`Boolean.TRUE`) and negative (`Boolean.FALSE`) convergence, so the adapter cannot be a constant-true stub. No blocker or should-fix; the three suggestions above are all "the parity test is valid but does not independently pin the named mechanism" notes, inherent to a harness whose IR and oracle share the AST operator classes by design (D11).
