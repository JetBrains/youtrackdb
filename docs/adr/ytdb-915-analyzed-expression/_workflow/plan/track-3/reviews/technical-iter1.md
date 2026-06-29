<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: T1, sev: should-fix, loc: "core/.../query/analyzed/BinaryOperator.java + track-3.md Plan-of-Work step 4 / D12 / Validation", anchor: "### T1 ", cert: "Premise: BinaryOperator covers the AST arithmetic operators the fold maps", basis: "IR BinaryOperator has only PLUS/MINUS/STAR/SLASH (+comparisons); AST Operator has 8 more (REM,<<,>>,>>>,&,^,|,??) — fold must throw on them but no operator-level subset gate is stated"}
  - {id: T2, sev: should-fix, loc: "track-3.md D14 + Plan-of-Work step 1; YouTrackDBSql.Expression() 6224-6298", anchor: "### T2 ", cert: "Edge case: SimpleNode.value reachability on the modern parser path (the named D14 Phase-A deliverable)", basis: "value IS non-null on the modern path (Expression() mirrors typed field into value); D14's 'if dead, ignore' branch is unreachable, throw-default branch is the operative and sound one — verdict must be recorded"}
  - {id: T3, sev: suggestion, loc: "track-3.md Plan-of-Work step 2 (number -> Const); SQLNumber/SQLInteger/SQLFloatingPoint", anchor: "### T3 ", cert: "Premise: negative literal lowers to a negative Const, sign already folded in"}
evidence_base: {section: "## Evidence base", certs: 19, matches: 16}
cert_index:
  - {id: P-BinOp, verdict: PARTIAL, anchor: "#### P-BinOp "}
  - {id: E-value, verdict: WRONG, anchor: "#### E-value "}
  - {id: P-Number, verdict: CONFIRMED, anchor: "#### P-Number "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise `P-BinOp` (IR `BinaryOperator` vs AST `SQLMathExpression.Operator` coverage) — verdict PARTIAL.
**Location**: `track-3.md` `## Plan of Work` step 4 (precedence-climbing fold), `## Decision Log` D12, `## Validation and Acceptance` throw-cases; IR enum `core/.../query/analyzed/BinaryOperator.java`.
**Issue**: The IR `BinaryOperator` enum (Track 1 output) has exactly `PLUS, MINUS, STAR, SLASH, EQ, NE, LT, LE, GT, GE`. The AST `SQLMathExpression.Operator` enum has twelve arithmetic/bitwise operators: `STAR, SLASH, REM, PLUS, MINUS, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, XOR, BIT_OR, NULL_COALESCING` (PSI-confirmed). The precedence fold (D12) reads `SQLMathExpression.operators` — a `List<Operator>` that may contain ANY of those twelve — and maps each into a `BinaryOp(BinaryOperator, …)`. For the eight operators the IR enum lacks (`REM` = `%`, the three shifts, `BIT_AND` = `&`, `XOR` = `^`, `BIT_OR` = `|`, `NULL_COALESCING` = `??`) there is no `BinaryOperator` to build, so the fold MUST throw `UnsupportedAnalyzedNodeException`. D12 and Plan-of-Work step 4 describe only the precedence/associativity nesting mechanism; neither states an operator-level subset gate, and the `## Validation and Acceptance` throw-case list enumerates `SQLExpression`-field throws (`rid`, `arrayConcatExpression`, `json`), subquery, CASE, `levelZero`, multi-segment `Var`, and `inputParam` — but **not** an arithmetic expression carrying `%`, `<<`, `&`, `??`, etc. D14's field-walk default ("throw on anything else") protects the `SQLExpression`-field axis, but the operator axis lives one level down inside a recognized `mathExpression`, so the fold itself must carry the gate. Without it the lowerer either builds a wrong `BinaryOp` (if it blindly maps `Operator.name()`/ordinal) or NPEs/throws an unmapped-enum error rather than the contract `UnsupportedAnalyzedNodeException` — an I2 ("no silent fallback / clean throw") hole.
**Proposed fix**: In decomposition, make the fold's operator mapping explicit: map only `{PLUS, MINUS, STAR, SLASH}` (the AST→IR arithmetic operators that exist on both enums) and throw `UnsupportedAnalyzedNodeException` on every other `SQLMathExpression.Operator`. Add a one-line note to D12 (or a sibling sentence in Plan-of-Work step 4) recording the supported-operator subset, and add throw-cases for at least `%` (`REM`), a shift, and `??` (`NULL_COALESCING`) to `## Validation and Acceptance`. (This is a track-file/decomposition adjustment, not a design-record change — the supported-operator subset is consistent with the existing IR enum Track 1 shipped.)

### T2 [should-fix]
**Certificate**: Edge case `E-value` (is `SimpleNode.value` ever non-null on the modern parser path?) — verdict WRONG relative to the track's hopeful framing; this is the named D14 Phase-A deliverable.
**Location**: `track-3.md` `## Decision Log` D14 Risks/Caveats + `## Plan of Work` step 1 ("Record the Phase-A PSI note: confirm whether `SimpleNode.value` is ever non-null on the modern parser path"); generated parser `YouTrackDBSql.Expression()` lines 6224-6298.
**Issue**: D14 frames the `value` reachability as open ("if dead on the modern path, the walk may ignore it; if reachable, the throw-default already makes lowering throw"). The Phase-A verdict is now definitive and lands on the *reachable* side: the generated `Expression()` production sets `SimpleNode.value` on every `SQLExpression` it builds — it mirrors the typed field into `value` (line 6232 `value = arrayConcatExpression`, 6237 `isNull = true; value = null`, 6242/6247 `booleanValue = …; value = …`, 6254 `value = rid`, 6257 `value = mathExpression`, 6262 `value = json`). So `value` is **alive** on the modern path; the "ignore it because dead" branch of D14 is unreachable and must not be implemented. The lowerer's correct posture is the throw-default branch: dispatch only on the typed in-subset fields and never read `value`, so the mirrored `value` is irrelevant to lowering, and any field the walk does not recognize hits the throw-default — which is exactly what D14's exhaustive-or-throw default already prescribes. The conclusion is sound; the risk is that the implementer, reading D14's two-branch framing, picks or even considers the dead-path "ignore" branch. Note also that `value` mirroring happens **only** in `Expression()`: `SQLExpression` nodes built by `FunctionParam` (`booleanExpression`, PSI-confirmed at `YouTrackDBSql.java:5682`) or programmatically via `setLiteralValue`/`copy`/`deserialize` do not set `value` — another reason the lowerer must dispatch on typed fields, never `value`.
**Proposed fix**: Resolve the D14 Phase-A note with the definitive verdict in the track file's `## Surprises & Discoveries` (or as a D14 caveat amendment during decomposition): "`SimpleNode.value` IS non-null on the modern parser path (`Expression()` mirrors the typed field into `value`); the lowerer dispatches on typed fields only and never reads `value`, so this is benign — the throw-default (D14) covers any unrecognized field regardless." Drop or down-weight the "if dead, ignore it" half of the D14 caveat so the implementer cannot read it as license to special-case `value`.

### T3 [suggestion]
**Certificate**: Premise `P-Number` (negative literal lowers to a negative `Const`; sign already folded in) — verdict CONFIRMED, with an implementation caveat.
**Location**: `track-3.md` `## Plan of Work` step 2 (`number` (`SQLNumber`) → `Const`); `SQLNumber`/`SQLInteger`/`SQLFloatingPoint`.
**Issue**: The claim holds, but the way it holds is non-obvious and worth pinning so the implementer does not reach for the wrong accessor. `SQLNumber.getValue()` in the base class returns a literal `null` (it is effectively abstract — only `SQLInteger` and `SQLFloatingPoint` instances exist). `SQLInteger.getValue()` returns the already-signed `value` (its `setValue(int sign, String)` folds the sign in at parse time via `* sign` / `"-" + stringValue`). `SQLFloatingPoint.getValue()` multiplies the parsed magnitude by its `sign` field in all three branches (`* sign`). So building `new Const(number.getValue())` yields a correctly-signed `Number` for both subtypes — but only via `getValue()`; reading a raw `value`/`stringValue`/`sign` field directly would lose or double-apply the sign.
**Proposed fix**: Add a one-line note to Plan-of-Work step 2: "lower a number to `new Const(number.getValue())` — `getValue()` already folds the sign (`SQLInteger`/`SQLFloatingPoint`); never read the raw `value`/`stringValue`/`sign` fields." No design change.

## Evidence base

#### P-SQLExpression-fields: SQLExpression field set
- **Track claim**: `SQLExpression` fields are `singleQuotes`, `doubleQuotes`, `isNull`, `rid`, `mathExpression`, `arrayConcatExpression`, `json`, `booleanExpression`, `booleanValue`, `literalValue` (`## Decision Log` D14, `## Interfaces and Dependencies`).
- **Search performed**: PSI `PsiShortNamesCache.getClassesByName("SQLExpression")` + field dump.
- **Code location**: `com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression`.
- **Actual behavior**: 10 fields, exact match: `singleQuotes:Boolean, doubleQuotes:Boolean, isNull:boolean, rid:SQLRid, mathExpression:SQLMathExpression, arrayConcatExpression:SQLArrayConcatExpression, json:SQLJson, booleanExpression:SQLBooleanExpression, booleanValue:Boolean, literalValue:Object`. Superclass `SimpleNode`.
- **Verdict**: CONFIRMED.

#### P-SQLBaseExpression-fields: SQLBaseExpression field set
- **Track claim**: fields `number`, `identifier`, `inputParam`, `string`, `modifier`.
- **Search performed**: PSI field dump.
- **Code location**: `SQLBaseExpression` (superclass `SQLMathExpression`).
- **Actual behavior**: 5 fields exact: `number:SQLNumber, identifier:SQLBaseIdentifier, inputParam:SQLInputParameter, string:String, modifier:SQLModifier`. `isBaseIdentifier()` = `identifier != null && modifier == null && identifier.isBaseIdentifier()`; `execute` dispatches `number→getValue`, `identifier→execute`, `string→decode`, `inputParam→getValue`.
- **Verdict**: CONFIRMED. (`SQLBaseExpression extends SQLMathExpression`, so the leaf participates in the math node hierarchy — consistent with the fold descending into leaves.)

#### P-SQLParenthesisExpression-fields: paren payloads
- **Track claim**: `expression: SQLExpression` (grouping) and `statement: SQLStatement` (subquery), mutually exclusive (D10).
- **Search performed**: PSI field dump + `execute` body.
- **Code location**: `SQLParenthesisExpression` (superclass `SQLMathExpression`).
- **Actual behavior**: 2 fields exact: `expression:SQLExpression, statement:SQLStatement`. `execute`: `if (expression != null) return expression.execute(...)` then `if (statement != null) throw new UnsupportedOperationException(...)`. So grouping delegates transparently; subquery throws even in the AST.
- **Verdict**: CONFIRMED. (D10 transparency claim is exact; the AST itself throws on the subquery case.)

#### P-SQLNotBlock-fields: NOT node shape
- **Track claim**: `sub: SQLBooleanExpression`, `negate: boolean` (Plan-of-Work step 5).
- **Search performed**: PSI field dump.
- **Code location**: `SQLNotBlock` (superclass `SQLBooleanExpression`).
- **Actual behavior**: 2 fields exact: `sub:SQLBooleanExpression, negate:boolean`.
- **Verdict**: CONFIRMED.

#### P-SQLMathExpression-shape: flat n-ary arithmetic node
- **Track claim**: `childExpressions: List<SQLMathExpression>`, `operators: List<Operator>`; precedence resolved at evaluate time (D12).
- **Search performed**: PSI field dump + method bodies.
- **Code location**: `SQLMathExpression`.
- **Actual behavior**: fields `NULL_VALUE:Object, childExpressions:List<SQLMathExpression>, operators:List<Operator>`. `unwrapIfNeeded()` collapses a single-child node. Precedence resolved by `calculateWithOpPriority` → `iterateOnPriorities` (see P-Precedence).
- **Verdict**: CONFIRMED.

#### P-SQLBaseIdentifier-shape: levelZero XOR suffix
- **Track claim**: exactly one of `levelZero` (`SQLLevelZeroIdentifier`) or `suffix` (`SQLSuffixIdentifier`) non-null (D18).
- **Search performed**: PSI field dump.
- **Code location**: `SQLBaseIdentifier`.
- **Actual behavior**: 2 fields: `levelZero:SQLLevelZeroIdentifier, suffix:SQLSuffixIdentifier`. Methods include `isFunctionAny`/`isFunctionAll` (the identifier-level any/all detectors), `getSuffix`, `getLevelZero`, `getCollate`.
- **Verdict**: CONFIRMED.

#### P-SQLLevelZeroIdentifier-payloads: function/self/collection
- **Track claim**: `SQLLevelZeroIdentifier` carries a top-level `functionCall` (incl. `any()`/`all()`), `self` (`@this`), or inline `collection` (D18).
- **Search performed**: PSI field dump.
- **Code location**: `SQLLevelZeroIdentifier`.
- **Actual behavior**: 3 fields: `functionCall:SQLFunctionCall, self:Boolean, collection:SQLCollection`. Exact match to the three payloads.
- **Verdict**: CONFIRMED.

#### P-SQLSuffixIdentifier-shape: single suffix segment
- **Track claim**: a single-segment `suffix` column → `Var` (D6-R); `SQLModifier.methodCall` carries the method call → `FuncCall` (D18).
- **Search performed**: PSI field dump + `SQLModifier` field dump + `Modifier()` production read.
- **Code location**: `SQLSuffixIdentifier` (`identifier:SQLIdentifier, recordAttribute:SQLRecordAttribute, star:boolean`); `SQLModifier` (`squareBrackets, arrayRange, condition, arraySingleValues, rightBinaryCondition, methodCall:SQLMethodCall, suffix:SQLSuffixIdentifier, next:SQLModifier`).
- **Actual behavior**: a plain column is `identifier` set with `modifier == null` (`isBaseIdentifier`). A multi-segment path (`p.name`) is carried by the `SQLModifier` suffix/next chain (the `Modifier()` production at `YouTrackDBSql.java:6180-6182` consumes `DOT` then `SuffixIdentifier()` into `jjtn000.suffix`). A method call is `SQLModifier.methodCall != null`.
- **Verdict**: CONFIRMED. The exact flatten `identifierToPath` performs (identifier + modifier suffix-chain → `List<String>`, `Var` only when size==1) is correctly flagged in D6-R as a Phase-A lowering-design detail; it is a design detail, not a blocker.

#### P-SQLModifier-methodCall: method-call modifier
- **Track claim**: `SQLModifier.methodCall` is the method-call payload (FuncCall source).
- **Search performed**: PSI field dump.
- **Code location**: `SQLModifier`.
- **Actual behavior**: field `methodCall:SQLMethodCall` present.
- **Verdict**: CONFIRMED.

#### P-Precedence: precedence-climbing reduction with <= left-associative reduction
- **Track claim**: AST resolves precedence via `Operator.getPriority()` and a precedence-climbing reduction (`calculateWithOpPriority` → `iterateOnPriorities`) with `<=` left-associative reduction; `MathExpression()` collects mixed-precedence operators into one flat list and `unwrapIfNeeded()` collapses a single child (D12).
- **Search performed**: PSI method bodies of `calculateWithOpPriority`, `iterateOnPriorities`, `unwrapIfNeeded`, `Operator.getPriority`.
- **Code location**: `SQLMathExpression#calculateWithOpPriority` / `#iterateOnPriorities` / `#unwrapIfNeeded`.
- **Actual behavior**: both methods reduce on `operatorsStack.peek().getPriority() <= nextOperator.getPriority()` (the `<=` left-associative reduction). `iterateOnPriorities` is the multi-pass stack drain. `unwrapIfNeeded()` returns the single child when `childExpressions.size() == 1`. Two overloads exist (`Result` and `Identifiable` records) with identical reduction logic.
- **Verdict**: CONFIRMED.

#### P-Priorities: operator priority values (a + b*c case)
- **Track claim**: `STAR` (priority 10) binds tighter than `PLUS` (priority 20); `a + b*c` = `a + (b*c)` (D12).
- **Search performed**: PSI enum-constant dump of `SQLMathExpression.Operator`.
- **Code location**: `SQLMathExpression.Operator`.
- **Actual behavior**: priorities `STAR/SLASH/REM=10`, `PLUS/MINUS=20`, `NULL_COALESCING=25`, `LSHIFT/RSHIFT/RUNSIGNEDSHIFT=30`, `BIT_AND=40`, `XOR=50`, `BIT_OR=60`. STAR(10) < PLUS(20) so STAR binds tighter — the D12 worked example is correct.
- **Verdict**: CONFIRMED.

#### P-BinOp: BinaryOperator IR enum vs AST Operator coverage
- **Track claim**: the fold maps the flat `SQLMathExpression` operators into nested `BinaryOp(op, left, right)` (D12, Plan-of-Work step 4); the IR side has the operator enum(s) the fold needs.
- **Search performed**: PSI enum-constant dump of IR `BinaryOperator` and AST `SQLMathExpression.Operator`.
- **Code location**: `com.jetbrains.youtrackdb.internal.core.query.analyzed.BinaryOperator` vs `…sql.parser.SQLMathExpression.Operator`.
- **Actual behavior**: IR `BinaryOperator` = `PLUS, MINUS, STAR, SLASH, EQ, NE, LT, LE, GT, GE` (10 constants: 4 arithmetic + 6 comparison). AST `Operator` = 12 arithmetic/bitwise: `STAR, SLASH, REM, PLUS, MINUS, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, XOR, BIT_OR, NULL_COALESCING`. The IR enum covers only `{PLUS, MINUS, STAR, SLASH}` of the AST arithmetic operators — the other eight have no IR counterpart.
- **Verdict**: PARTIAL.
- **Detail**: the four common arithmetic operators map cleanly; the fold must throw `UnsupportedAnalyzedNodeException` on the other eight (`REM`, three shifts, `BIT_AND`, `XOR`, `BIT_OR`, `NULL_COALESCING`). The track does not state this operator-level subset gate. → drives T1.

#### P-UnaryOp: UnaryOperator enum has NOT
- **Track claim**: the IR side has the NOT unary operator; `UnaryOp(NOT, lower(sub))` (Plan-of-Work step 5).
- **Search performed**: PSI enum-constant dump of IR `UnaryOperator`.
- **Code location**: `com.jetbrains.youtrackdb.internal.core.query.analyzed.UnaryOperator`.
- **Actual behavior**: enum with a single constant `NOT`.
- **Verdict**: CONFIRMED.

#### P-IRTypes: AnalyzedExpr variants and constructors
- **Track claim**: Track-1 IR types `Const`, `Var`, `BinaryOp`, `UnaryOp`, `FuncCall` (nested in `AnalyzedExpr`) and `UnsupportedAnalyzedNodeException` exist with the constructors the lowering Plan of Work needs.
- **Search performed**: PSI class dump (record components + constructors) of `AnalyzedExpr` and `UnsupportedAnalyzedNodeException`.
- **Code location**: `com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr` (`public sealed interface`).
- **Actual behavior**: nested records `Var(List<String> path)`, `Const(Object value)`, `BinaryOp(BinaryOperator op, AnalyzedExpr left, AnalyzedExpr right)`, `UnaryOp(UnaryOperator op, AnalyzedExpr operand)`, `FuncCall(String name, List<AnalyzedExpr> args)`. `UnsupportedAnalyzedNodeException(Class<?> astNodeClass)` (+ copy ctor). Constructors match Plan-of-Work needs: `BinaryOp(op, left, right)`, `UnaryOp(NOT, child)`, `new Const(signedNumber)`, single-segment `Var(List.of(col))`, `FuncCall(name, args)`, `UnsupportedAnalyzedNodeException(node.getClass())`.
- **Verdict**: CONFIRMED.

#### P-Number: negative literal Const, sign folded in
- **Track claim**: a negative literal lowers to a negative `Const`; the `sign` flag is already folded in (Plan-of-Work step 2).
- **Search performed**: PSI `getValue` bodies of `SQLNumber`, `SQLInteger`, `SQLFloatingPoint`; `ClassInheritorsSearch` on `SQLNumber`.
- **Code location**: `SQLNumber#getValue` (base, returns `null`), `SQLInteger#getValue`/`setValue`, `SQLFloatingPoint#getValue`.
- **Actual behavior**: base `SQLNumber.getValue()` returns literal `null` (effectively abstract; only `SQLInteger`/`SQLFloatingPoint` instances exist). `SQLInteger` folds sign in `setValue(int sign, String)` (`* sign` / `"-" + stringValue`), `getValue()` returns the signed `value`. `SQLFloatingPoint.getValue()` multiplies magnitude by `sign` in all three branches. `new Const(number.getValue())` therefore yields a correctly-signed value.
- **Verdict**: CONFIRMED. → informs T3 (implementation caveat: lower via `getValue()`, not raw fields).

#### E-value: SimpleNode.value reachability on the modern parser path (D14 Phase-A deliverable)
- **Trigger**: a parsed `SQLExpression` reaches the lowerer; is `SimpleNode.value` non-null, and does the "old executor" fallback chain in `SQLExpression.execute` run on the modern path?
- **Code path trace**:
  1. `YouTrackDBSql.Expression()` @ `YouTrackDBSql.java:6224` — builds `SQLExpression jjtn000`.
  2. each branch sets both the typed field AND mirrors it into `value`: `6232 value=arrayConcatExpression`, `6237 isNull=true;value=null`, `6242 booleanValue=true;value=true`, `6247 booleanValue=false;value=false`, `6254 value=rid`, `6257 value=mathExpression`, `6262 value=json` — so on the modern path `value` is set for every shape `Expression()` builds (deliberately `null` only for `NULL`).
  3. `SimpleNode.value` write-sites (`ReferencesSearch`, 21 writes): `jjtSetValue`, the `Expression()`/`ArrayConcatExpressionElement` parser productions, `SQLInputParameter.toParsedTree` (param substitution), `SQLValueExpression`/`SQLNumber.copy` — confirming `value` is alive, not legacy-only.
  4. `SQLExpression.execute` reads the typed fields first (`isNull, rid, mathExpression, arrayConcatExpression, booleanExpression, json, booleanValue, literalValue`); only after all are null does it fall through to `value instanceof …` ("only for old executor (manually replaced params)"). The lowerer does not run `execute`; it reads typed fields directly.
  5. `booleanExpression`/`literalValue` are NOT set by `Expression()`: `booleanExpression` is set by `YouTrackDBSql.FunctionParam` @ `5682` (and `copy`/`deserialize`), `literalValue` by `setLiteralValue`/`copy`/`deserialize` — those `SQLExpression` nodes do NOT mirror into `value`.
- **Outcome**: `value` IS non-null on the modern path for `Expression()`-built nodes; the D14 "if dead, ignore it" branch is unreachable. The correct lowerer behavior — dispatch on typed fields only, never read `value`, throw-default on any unrecognized field — is exactly D14's exhaustive-or-throw default, so the conclusion stands and the gap (if `value` were ever the only signal) degrades to a clean throw, never a wrong value.
- **Track coverage**: PARTIAL — the track flags this as a Phase-A note but leaves the verdict open with a misleading "if dead, ignore" half. → drives T2.

#### E-multisegment-throw: multi-segment Var throws (D6-R)
- **Trigger**: lowering `p.name` (a two-segment path).
- **Code path trace**:
  1. parsed as `SQLBaseExpression.identifier` = `p` with a `SQLModifier` whose `suffix`/`next` chain carries `name` (the `Modifier()` `DOT SuffixIdentifier()` production @ `YouTrackDBSql.java:6180-6182`).
  2. `identifierToPath` (new, this track) flattens identifier + modifier suffix-chain into a `List<String>` of length > 1.
  3. lowerer produces `Var` only when `path.size() == 1`; otherwise throws `UnsupportedAnalyzedNodeException` (D6-R).
- **Outcome**: clean throw; round-trip parity (I1) preserved by construction (IR handles only operand shapes it faithfully reproduces).
- **Track coverage**: yes — D6-R + Plan-of-Work step 2 + Validation throw-cases (multi-segment `Var`).

#### E-levelZero-throw: any()/all()/@this/inline-collection throw (D18)
- **Trigger**: lowering a `levelZero` identifier (`any()`, `all()`, `@this`, `[..]`).
- **Code path trace**:
  1. `SQLBaseIdentifier.levelZero != null` (the non-`suffix` branch).
  2. `identifierToPath` handles only the single-segment `suffix` column shape, so a `levelZero` identifier matches no recognized field.
  3. hits the field-walk exhaustive-or-throw default (D14) → `UnsupportedAnalyzedNodeException`.
- **Outcome**: clean throw. `any()`/`all()` carry property-iteration semantics (`SQLBinaryCondition.evaluateAny` / `evaluateAllFunction`, both PSI-confirmed present) the IR comparison evaluator does not reproduce; throwing prevents a silent parity hole.
- **Track coverage**: yes — D18 + Validation throw-cases (`levelZero`, incl. `any()`/`all()`).
- **Note**: the iteration-function detectors are also exposed on `SQLBaseIdentifier` as `isFunctionAny()`/`isFunctionAll()`; the track correctly cites the evaluator-side branches `evaluateAny`/`evaluateAllFunction` on `SQLBinaryCondition` (PSI-confirmed). A separate design.md citation reconciliation (CR1, noted in the branch's Phase-2 review) does not affect this track file, which is already correct.

#### I-greenfield: AnalyzedExprLowerer / identifierToPath absent (planned by this track)
- **Plan claim**: `AnalyzedExprLowerer` and `identifierToPath` are new, created by this track; the `query/analyzed/` package and IR types exist from Track 1.
- **Actual entry point**: PSI `getClassesByName("AnalyzedExprLowerer")` = 0; package `…query.analyzed` exists with `AnalyzedExpr, AnalyzedExprTest, AnalyzedExprTransform, AnalyzedExprVisitor, BinaryOperator, UnaryOperator, UnsupportedAnalyzedNodeException`. `AnalyzedExprEvaluator` also absent (Track 4).
- **Caller analysis**: none — greenfield class, no callers to break; lowerer reads AST classes but modifies none (in-scope/out-of-scope lists in `## Interfaces and Dependencies` confirmed against the package contents).
- **Breaking change risk**: none — new class, no AST mutation, S0 ships behind no consumer.
- **Verdict**: MATCHES — `AnalyzedExprLowerer`/`identifierToPath` logged as planned-by-this-track CONFIRMED premises, not NOT-FOUND blockers.
