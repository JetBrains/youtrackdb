<!--MANIFEST
review: test-behavior
target: "Track 3 Step 1 — AnalyzedExprLowerer + AnalyzedAstAccess + AnalyzedExprLowererTest"
commit_range: 772dd697c6faf2effeb1fa30a0912ecc616b78ee~1..772dd697c6faf2effeb1fa30a0912ecc616b78ee
iteration: 1
verdict: PASS_WITH_FINDINGS
counts: { blocker: 0, should_fix: 1, suggestion: 2 }
evidence_base: present
cert_index: [C1, C2, C3]
flags:
  psi_used: true
  psi_caveat: none
index:
  - { id: TB1, sev: should-fix, anchor: "#tb1-methodcallwithoutofsubsetargumentthrows-throws-on-the-wrong-gate-argument-recursion-throw-never-exercised", loc: "AnalyzedExprLowererTest.java:811-818 (methodCallWithOutOfSubsetArgumentThrows); AnalyzedExprLowerer.java:263-285 (lowerWithOptionalModifier)", cert: C1, basis: "Parsed-AST probe: name.f(a = b) yields modifier.getMethodCall()==null; throw fires at the modifier-shape gate (line 270), the arg loop (281-283) is never entered" }
  - { id: TB2, sev: suggestion, anchor: "#tb2-method-call-argument-recursion-pinned-only-with-a-leaf-arg-non-leaf-recursion-untested", loc: "AnalyzedExprLowererTest.java:653-661 (methodCallWithArgumentLowersArgumentsAfterBase); AnalyzedExprLowerer.java:279-284", cert: C2, basis: "Probe: name.f(a + c) lowers to FuncCall[..., BinaryOp[PLUS,...]] — the recursive lower(param) over a non-leaf arg is reachable but unpinned; only a leaf 'x' arg is asserted" }
  - { id: TB3, sev: suggestion, anchor: "#tb3-throw-test-comments-claim-shape-specific-discrimination-the-assertions-do-not-carry", loc: "AnalyzedExprLowererTest.java:847-854 (anyFunctionThrows), 843-846 (topLevelFunctionCallThrows); AnalyzedExprLowerer.java:241-254", cert: C3, basis: "any() and count() both throw via the identical levelZero branch; the assertion (exception type only) cannot distinguish the any()-specific reason the comment claims it pins" }
-->

## Findings

### TB1 [should-fix] `methodCallWithOutOfSubsetArgumentThrows` throws on the wrong gate; argument-recursion throw never exercised

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:811-818`, method `methodCallWithOutOfSubsetArgumentThrows`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:263-285` (`lowerWithOptionalModifier`)
- **Issue**: The test asserts `lower("name.f(a = b)")` throws and its comment states: "The argument `a = b` parses as a boolean inside the method-call modifier, an out-of-subset modifier shape, so the whole expression is rejected. This pins that a method call is lowered only when its modifier and arguments are themselves in subset." The throw is real, but it does **not** come from the argument. A parsed-AST probe (C1) shows `name.f(a = b)` produces a `SQLBaseExpression` whose `modifier.getMethodCall()` is `null` — so the throw fires at the modifier-shape gate (`AnalyzedExprLowerer.java:270`, `methodCall == null || modifier.getNext() != null`). The argument-lowering loop (lines 281-283, reached only when `methodCall != null`) is never entered, so `a = b` is never lowered. The test pins the modifier-shape gate, not the argument gate the comment names, and the second half of the comment describes a behavior the test does not exercise.
- **Evidence — FALSIFIABILITY CHECK**: MUTATION — delete the entire argument-recursion loop and replace it with `args.add(base)` only (i.e., a method call lowers but drops every argument). ANALYSIS — `name.f(a = b)` still parses to `methodCall == null` and still throws at line 270, so this test would still PASS. A bug that broke argument recursion would not be caught here. The behavior the method name promises ("out-of-subset argument throws") is therefore coverage-driven, not behavior-driven: the test executes the throw path but verifies a different cause than it claims.
- **Missing behavior**: A method call whose **argument** is out of subset must throw because the recursive `lower(param)` (line 282) throws and that propagates. The probe (C1) confirms `name.f(p.q)` is the reachable shape that exercises this: `methodCall=f` is non-null, the loop is entered, `lower(p.q)` throws on the multi-segment path, and the whole expression is rejected from inside the argument loop.
- **Suggested fix** (replace the input so the throw originates in the argument loop, and correct the comment):
  ```java
  /// WHEN a method call carries an out-of-subset argument `name.f(p.q)`, THE pass throws: the
  /// argument-lowering loop recurses `lower(p.q)`, which rejects the multi-segment path, and the
  /// rejection propagates out of the method call. This pins that a method call is lowered only
  /// when each of its arguments is itself in subset — the recursive arg-throw path, distinct from
  /// the modifier-shape gate.
  @Test
  public void methodCallWithOutOfSubsetArgumentThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("name.f(p.q)"));
  }
  ```
  (Optionally keep the `name.f(a = b)` case as a separately-named test pinning the modifier-shape gate, with a comment that states the real cause: `methodCall == null`.)

### TB2 [suggestion] Method-call argument recursion pinned only with a leaf arg; non-leaf recursion untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:653-661`, method `methodCallWithArgumentLowersArgumentsAfterBase`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:279-284` (the `for (SQLExpression param : methodCall.getParams()) args.add(lower(param))` loop)
- **Issue**: The only method-call argument lowered is `'x'` (a string leaf → `Const("x")`). The loop calls `lower(param)` recursively, but with a leaf argument the recursion bottoms out immediately, so the test does not show that a **compound** argument lowers correctly inside a `FuncCall`. The assertion is precise for what it covers (`new FuncCall("append", List.of(var("name"), new Const("x")))` — full content, ordered), so this is a depth gap rather than a precision gap.
- **Evidence — FALSIFIABILITY CHECK**: MUTATION — change `lower(param)` to `new Const(null)` (lower every argument to a constant null instead of recursing). ANALYSIS — `name.append('x')` lowers `'x'` to `Const("x")` today; the mutation would produce `Const(null)`, so this test would FAIL and catch that. But a subtler mutation — recurse only one level, or short-circuit a non-leaf arg — would not be caught, because the only arg under test is already a leaf. A probe (C2) confirms `name.f(a + c)` lowers to `FuncCall[name=f, args=[Var[name], BinaryOp[PLUS, Var[a], Var[c]]]]`: the non-leaf recursion path is reachable and currently unpinned.
- **Missing behavior**: A method-call argument that is itself a compound expression lowers to its full IR subtree as the corresponding `FuncCall` argument. (Overlaps the completeness lens — flagged here only for the behavioral assertion that arg-recursion produces a nested tree, not merely a leaf.)
- **Suggested fix** (extend the existing test or add a sibling):
  ```java
  /// WHEN a method-call argument is itself a compound expression `name.f(a + c)`, THE FuncCall
  /// carries the fully-lowered argument subtree after the base — pinning that the argument loop
  /// recurses through lower(param) rather than only handling leaf arguments.
  @Test
  public void methodCallWithCompoundArgumentLowersArgumentSubtree() {
    assertEquals(
        new FuncCall("f",
            List.of(var("name"),
                new BinaryOp(BinaryOperator.PLUS, var("a"), var("c")))),
        lower("name.f(a + c)"));
  }
  ```

### TB3 [suggestion] Throw-test comments claim shape-specific discrimination the assertions do not carry

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:847-854` (`anyFunctionThrows`), with the same pattern at 843-846 (`topLevelFunctionCallThrows`), 856-860 (`thisSelfReferenceThrows`), 862-867 (`inlineCollectionThrows`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:241-254` (`lowerIdentifier`: every `levelZero` shape throws at line 243-244 with `identifier.getLevelZero().getClass()`)
- **Issue**: `anyFunctionThrows` comments "THE pass throws specifically — `any()` carries property-iteration comparison semantics the IR comparison evaluator does not reproduce, so it must not slip through as a `FuncCall`." The assertion is `assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("any()"))` — the same assertion `topLevelFunctionCallThrows` makes for `count()`. Both inputs hit the identical `levelZero` branch (line 243) and throw the same exception type; the lowerer has no `any()`-specific code path. The assertion cannot distinguish "threw because `any()` is special" from "threw because every `levelZero` throws," so the word "specifically" claims a discrimination the test does not carry. This is a comment-vs-assertion precision mismatch, not a wrong assertion — the behavior (throws) is correct and the exception type is the right granularity (it is the only exception the lowerer throws; no cause chain to verify).
- **Evidence — ASSERTION PRECISION CHECK**: ASSERTION at line 853: `assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("any()"))`. PRODUCTION VALUE — the throw carries `SQLLevelZeroIdentifier.getClass()` in its message (C3), identical for `any()`, `count()`, `@this`, and `[1,2,3]`. PRECISION — adequate for the contract ("out-of-subset shape throws") but WEAKER than the comment asserts: the assertion would pass identically for any `levelZero` input, so it does not pin the `any()`-specific reasoning. STRONGER ALTERNATIVE — if shape-specific discrimination is genuinely wanted, assert on the message naming the unsupported class; otherwise soften the comment to "as an out-of-subset `levelZero` shape" (matching `topLevelFunctionCallThrows`), so the documentation does not overstate the guarantee.
- **Missing behavior**: None at the behavioral level — the throw contract is verified. The fix is to align the comment with what the assertion actually discriminates.
- **Suggested fix** (comment-only; no assertion change required):
  ```java
  /// WHEN the iteration function `any()` is lowered, THE pass throws as an out-of-subset
  /// `levelZero` shape — `any()` carries property-iteration semantics the IR does not reproduce,
  /// so it must not slip through as a FuncCall. (Throws via the same levelZero gate as count();
  /// the exception type is what is pinned, not an any()-specific path.)
  @Test
  public void anyFunctionThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("any()"));
  }
  ```

## Evidence base

#### C1 — `name.f(a = b)` throws at the modifier-shape gate, not the argument gate
Parsed-AST probe via the real grammar (`new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes())).Expression()`, then dispatch into `AnalyzedExprLowerer`): `name.f(a = b)` parses to `SQLExpression{mathExpression=SQLBaseExpression}` with `base.identifier=name`, `modifier=SQLModifier`, and crucially `modifier.getMethodCall()==null` (`methodCall=null next=null`). Lowering threw `UnsupportedAnalyzedNodeException` whose message names `...SQLBaseExpression` — the class passed at `AnalyzedExprLowerer.java:273` inside the `methodCall == null` branch (line 270), confirming the throw originates at the modifier-shape gate and the argument loop (lines 281-283) is never entered. Contrast probe `name.indexOf(a > b)` (a comparison arg) — also `methodCall=null`, same modifier-shape throw, so a comparison argument never reaches the argument loop through the public `Expression()` path. This same probe establishes that the `lower()`→`lowerBoolean` dispatch branch (`AnalyzedExprLowerer.java:81-84`) is unreachable from any parsed expression: `SQLExpression.booleanExpression` is assigned only in `YouTrackDBSql.FunctionParam()` (`YouTrackDBSql.java:5682`, via `OrBlock()`), and the `Expression()` value path never sets it — so routing the comparison/NOT tests through the package-visible `lowerBoolean` is not a behavioral shortcut but the only available route, and the test comment justifying that choice (lines 482-485) is factually correct.

#### C2 — non-leaf method-call argument recursion is reachable and currently unpinned
Same parse-and-lower probe: `name.f(a + c)` parses to `methodCall=f` (non-null), `params=[a + c]` with the param's `mathExpression=SQLMathExpression`, and lowers OK to `FuncCall[name=f, args=[Var[path=[name]], BinaryOp[op=PLUS, left=Var[path=[a]], right=Var[path=[c]]]]]`. So the argument loop's recursive `lower(param)` over a compound argument is reachable and produces a nested IR subtree. The only argument the test suite lowers is the leaf `'x'` in `methodCallWithArgumentLowersArgumentsAfterBase` (`Const("x")`), which exercises the loop but not the recursion depth. Probe `name.f(p.q)` lowers `methodCall=f`, `params=[p.q]`, then throws from inside the loop (the multi-segment path rejection) — the reachable shape for the TB1 fix.

#### C3 — every `levelZero` identifier throws through one shared gate
`AnalyzedExprLowerer.lowerIdentifier` (`AnalyzedExpr...Lowerer.java:241-254`): `if (identifier.getLevelZero() != null) throw new UnsupportedAnalyzedNodeException(identifier.getLevelZero().getClass());` is the first statement. A `SQLLevelZeroIdentifier` carries a top-level function call (incl. `any()`/`all()`/`count()`), the `@this` self reference, or an inline collection (per track-3.md D18, PSI-confirmed). All of these reach this one branch and throw the same `UnsupportedAnalyzedNodeException` with the `SQLLevelZeroIdentifier` subclass name in the message. There is no `any()`-specific or `count()`-specific code path in the lowerer, so `assertThrows(UnsupportedAnalyzedNodeException.class, ...)` is identical for every `levelZero` input and cannot discriminate among them. `UnsupportedAnalyzedNodeException` (PSI read) extends `CommandExecutionException` and exposes only the `(Class)` and copy constructors — it carries no typed payload field, so the only finer-grained assertion available is on the rendered message string.
