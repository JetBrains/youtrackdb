<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "track-4.md D6-R / SQLSuffixIdentifier.java:505-520", anchor: "### T1 ", cert: P-collate, basis: "D6-R collate pseudocode is guard-free; AST chain has isEntity() guard, (EntityImpl) cast, and per-hop null guards — literal impl throws where AST returns null collate (parity divergence)"}
  - {id: T2, sev: suggestion,  loc: "track-4.md §Validation / SQLExpression.java:123, SQLBinaryCondition.java:70", anchor: "### T2 ", cert: P-oracle, basis: "round-trip oracle is two methods by shape (execute:Object vs evaluate:boolean), not one uniform execute; harness must dispatch by shape"}
  - {id: T3, sev: suggestion,  loc: "track-4.md §Plan-of-Work step 2 / NumericOps.java:36-101", anchor: "### T3 ", cert: P-numericops, basis: "NumericOps has dedicated plusObject/minusObject for +/- but applyObject(STAR/SLASH,..) for */; visitBinaryOp must route per-operator, not via one uniform entry"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 11}
cert_index:
  - {id: P-collate, verdict: PARTIAL, anchor: "#### P-collate "}
  - {id: P-oracle, verdict: PARTIAL, anchor: "#### P-oracle "}
  - {id: P-numericops, verdict: PARTIAL, anchor: "#### P-numericops "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise `P-collate` (collate single-property resolution chain)
**Location**: `track-4.md` `## Decision Log` D6-R (the fenced pseudocode) and `## Plan of Work` step 5; mirrors `SQLSuffixIdentifier.getCollate` @ `core/.../sql/parser/SQLSuffixIdentifier.java:505-520`.
**Issue**: D6-R gives the collate-resolution chain as a bare fluent expression —
`result.asEntity().getImmutableSchemaClass(session).getProperty(name).getCollate()` —
with no guards. The actual AST single-segment branch it must reproduce for parity is
guarded at every hop (verified):

1. `if (identifier != null && currentResult != null)` and `if (currentResult.isEntity())` —
   the entry guard. `Result.asEntity()` returns `Entity` and is only valid when `isEntity()`
   is true.
2. `var record = (EntityImpl) currentResult.asEntity();` — an explicit cast to `EntityImpl`
   (`getImmutableSchemaClass` lives on `EntityImpl`, not `Entity`; PSI-confirmed
   `EntityImpl.getImmutableSchemaClass` present, `Result.asEntity(): Entity`).
3. `if (schemaClass != null)` — `getImmutableSchemaClass(session)` can return null.
4. `if (property != null)` — `schemaClass.getProperty(name)` can return null.
5. only then `return property.getCollate();`, else fall through to `return null`.

If the implementer codes the D6-R pseudocode literally, a non-entity `Result`, a null
`schemaClass`, or an unknown property name yields `NullPointerException` /
`ClassCastException` where the AST returns `null` (no collation). The slow path then
diverges from `SQLBinaryCondition.evaluate(Result,ctx)`, breaking I1 round-trip parity
on exactly the rows the matrix calls out (`ci-column = 'Foo'`) when the row's entity is
schemaless or the column is absent. The track's intent is right ("matching the AST's
single-segment `getCollate` branch", "never re-derive the AST's collate logic"), but the
concrete pseudocode it hands the implementer drops the guards.
**Proposed fix**: In D6-R (and the step-5 helper note), replace the bare fluent chain with
the guarded form — `isEntity()` check, `(EntityImpl)` cast, null-guard on `schemaClass`
and `property`, return `null` on any miss — i.e. transcribe `SQLSuffixIdentifier.getCollate`
lines 505-520 verbatim rather than its happy path. Decomposition should pin a parity test
row with a schemaless / missing-column operand so the guard is exercised, not just the
happy `ci`-column case.

### T2 [suggestion]
**Certificate**: Premise `P-oracle` (round-trip oracle methods)
**Location**: `track-4.md` `## Purpose / Big Picture`, `## Validation and Acceptance`
(the `lower(parse(sql)).evaluate(row, ctx)` == `parse(sql).execute(row, ctx)` statement).
**Issue**: The Validation prose states the parity oracle uniformly as
`parse(sql).execute(row, ctx)`. There is no single uniform oracle method: arithmetic
fragments parse to `SQLExpression`, whose oracle is `execute(Result, CommandContext): Object`
(`SQLExpression.java:123`); comparison fragments parse to `SQLBinaryCondition`, whose oracle
is `evaluate(Result, CommandContext): boolean` (`SQLBinaryCondition.java:70`) — a different
method name, on a different AST type, with a different return type. The matrix mixes both
shapes (arithmetic rows + `ci-column = 'Foo'` / type-coercing `!=`), so the harness must
dispatch the oracle by parsed shape. This is feasible (both methods PSI-confirmed; the
Track-3 test already parses both shapes via `parseExpression`→`Expression()` and
`parseComparison`→`BinaryCondition()`), but the "execute" wording could mislead the
implementer into seeking one method.
**Proposed fix**: In §Validation, note the oracle is shape-dependent — `SQLExpression.execute`
for arithmetic, `SQLBinaryCondition.evaluate(Result,ctx)` for comparison — and that the
analyzed evaluator likewise returns `Object` (arithmetic/coercion) vs `boolean` (comparison),
so the `Objects.equals` assertion compares boxed `Boolean` against boxed `Boolean` on the
comparison rows. No track-scope change; clarity only.

### T3 [suggestion]
**Certificate**: Premise `P-numericops` (NumericOps arithmetic API surface)
**Location**: `track-4.md` `## Plan of Work` step 2 ("`visitBinaryOp` for `+ - * /` delegates
to the shared `NumericOps`"); `NumericOps.java:36-101,142-176`.
**Issue**: `NumericOps` does not expose one uniform `apply(op, l, r)` object-level entry for
all four arithmetic operators. `+` and `-` have dedicated object-level methods with operator-
specific null semantics — `plusObject(Object,Object)` (null-propagation + `Date±Long` +
`String` concat) and `minusObject(Object,Object)` (null acts as additive identity). `*` and
`/` have no dedicated object method; they route through `applyObject(Operator.STAR/SLASH,
Object, Object)` (returns the other operand on a null, else widens via `apply(Number, Operator,
Number)`). So `visitBinaryOp`'s arithmetic arm must map each IR `BinaryOperator` to the matching
entry — `PLUS→plusObject`, `MINUS→minusObject`, `STAR/SLASH→applyObject(Operator.*,…)` — to
inherit the AST's exact null/`Date`/`String` behavior. A single uniform call would lose the
`+`/`-` object-level null and `Date`/`String` semantics that the matrix's null-propagation and
`Date + Long` rows pin. The track's intent ("the IR runs the same code the AST runs") is
served only by per-operator routing.
**Proposed fix**: In step 2, name the per-operator NumericOps entry points
(`plusObject`/`minusObject`/`applyObject`) so decomposition wires each IR arithmetic operator
to its AST-equivalent method rather than assuming a single `apply`. Implementation detail; no
Decision Record change.

## Evidence base

#### P-eval-class: AnalyzedExprEvaluator is created by this track
- **Track claim**: "New `AnalyzedExprEvaluator` in `core/.../query/analyzed/` (greenfield package, PSI-confirmed absent on develop) that implements `AnalyzedExprVisitor<Object>` directly."
- **Search performed**: PSI `PsiShortNamesCache.getClassesByName("AnalyzedExprEvaluator", allScope)`.
- **Code location**: NOT FOUND (planned by this track).
- **Actual behavior**: No class resolves. The package `com.jetbrains.youtrackdb.internal.core.query.analyzed` exists (Track 1) and holds `AnalyzedExpr`, `AnalyzedExprVisitor`, `AnalyzedExprLowerer`, `BinaryOperator`, `UnaryOperator`.
- **Verdict**: CONFIRMED
- **Detail**: Correctly absent — the track creates it. Planned-class rule satisfied (`## Context and Orientation` explicitly says "New ... PSI-confirmed absent").

#### P-visitor: AnalyzedExprVisitor<T> has one visitX per variant, no defaults (I3)
- **Track claim**: evaluator "implements `AnalyzedExprVisitor<Object>` directly — so the compiler forces it to enumerate every variant (invariant I3)"; Plan of Work step 1 names `visitVar`/`visitConst`/`visitBinaryOp`/`visitUnaryOp`/`visitFuncCall`.
- **Search performed**: PSI findClass + Read `AnalyzedExprVisitor.java`.
- **Code location**: `core/.../query/analyzed/AnalyzedExprVisitor.java:22-33`.
- **Actual behavior**: `interface AnalyzedExprVisitor<T>` declares exactly `visitVar(Var)`, `visitConst(Const)`, `visitBinaryOp(BinaryOp)`, `visitUnaryOp(UnaryOp)`, `visitFuncCall(FuncCall)` — all abstract, **no default methods** (Javadoc explicitly states "carries no default methods: a direct implementer ... must enumerate every variant").
- **Verdict**: CONFIRMED
- **Detail**: Method names match the Plan of Work exactly; the no-defaults shape backs I3.

#### P-ir-records: AnalyzedExpr variant record shapes match the evaluator's per-variant work
- **Track claim**: `visitVar` resolves a name path; `visitConst` returns the literal; `visitFuncCall` evaluates "the method name and its arguments"; `visitBinaryOp`/`visitUnaryOp` carry the IR operator.
- **Search performed**: Read `AnalyzedExpr.java`.
- **Code location**: `core/.../query/analyzed/AnalyzedExpr.java:39-60`.
- **Actual behavior**: `Var(List<String> path)`, `Const(Object value)`, `BinaryOp(BinaryOperator op, AnalyzedExpr left, AnalyzedExpr right)`, `UnaryOp(UnaryOperator op, AnalyzedExpr operand)`, `FuncCall(String name, List<AnalyzedExpr> args)`. `dispatch` routes each to the matching `visitX`.
- **Verdict**: CONFIRMED
- **Detail**: Record fields align with each step's described work. `FuncCall.args()` is read-only by convention (Track-1 episode) — the evaluator only reads it.

#### P-ir-enums: BinaryOperator / UnaryOperator constant sets
- **Track claim**: comparison ops `= != < <= > >=` + arithmetic `+ - * /`; unary `NOT` only.
- **Search performed**: Read `BinaryOperator.java`, `UnaryOperator.java`.
- **Code location**: `core/.../query/analyzed/BinaryOperator.java`, `UnaryOperator.java`.
- **Actual behavior**: `enum BinaryOperator { PLUS, MINUS, STAR, SLASH, EQ, NE, LT, LE, GT, GE }`; `enum UnaryOperator { NOT }`.
- **Verdict**: CONFIRMED
- **Detail**: Exactly the prior-episode contract and the track's stated operator set. `visitBinaryOp` must branch arithmetic (PLUS..SLASH) vs comparison (EQ..GE).

#### P-cmp-sequence: SQLBinaryCondition.evaluate(Result,ctx) slow-path four-step sequence (D11)
- **Track claim**: D11 — IR comparison reproduces "evaluate both operands, fetch the collate left-then-right, apply the collate transform when non-null, delegate to the parser's own `SQLBinaryCompareOperator` instance".
- **Search performed**: Read `SQLBinaryCondition.java`.
- **Code location**: `core/.../sql/parser/SQLBinaryCondition.java:99-109`.
- **Actual behavior**: slow path is exactly: `leftVal = left.execute(...)`; `rightVal = right.execute(...)`; `collate = left.getCollate(...)`; `if (collate==null) collate = right.getCollate(...)`; `if (collate!=null) { leftVal = collate.transform(leftVal); rightVal = collate.transform(rightVal); }`; `return operator.execute(ctx.getDatabaseSession(), leftVal, rightVal);`.
- **Verdict**: CONFIRMED
- **Detail**: D11's four steps are byte-faithful. The `evaluateAny`/`evaluateAllFunction` and `tryInPlaceComparison` branches above are the ANY/ALL and fast paths, both out of S0 subset (D16) — slow path is the parity reference, as the track states.

#### P-eqne-session: EQ passes the real session, NE passes null (D11)
- **Track claim**: D11 — "EQ calls `QueryOperatorEquals.equals` with the real session while NE passes a `null` session, changing how the cross-type coercion resolves, so EQ and NE can differ on mixed-type operands." Reproduced "by construction" by delegating to the operator instance.
- **Search performed**: Read `SQLEqualsOperator.java`, `SQLNeOperator.java`, `QueryOperatorEquals.java`.
- **Code location**: `SQLEqualsOperator.java:26-28`; `SQLNeOperator.java:21-24`; `QueryOperatorEquals.java:60-61`.
- **Actual behavior**: `SQLEqualsOperator.execute(session,l,r)` → `QueryOperatorEquals.equals(session, l, r)` (real session). `SQLNeOperator.execute(session,l,r)` → `!QueryOperatorEquals.equals(null, l, r)` (null session, ignores its own session arg). `QueryOperatorEquals.equals(@Nullable DatabaseSessionEmbedded session, ...)` — session is `@Nullable` and feeds `PropertyTypeInternal.convert(session, …)`.
- **Verdict**: CONFIRMED
- **Detail**: The asymmetry lives INSIDE the operator classes, not in `SQLBinaryCondition` (which always passes the real session at line 109). So the evaluator delegating `operator.execute(realSession, …)` reproduces both EQ (real) and NE (null) by construction — D11 is correct. Implementer note: the evaluator must NOT itself null the session for NE; it passes the real session and lets `SQLNeOperator` override to null.

#### P-ordering: ordering operators map doCompare's sign against 0 (D11)
- **Track claim**: D11 — "ordering operators carry too: their shared `doCompare` returns a sign each maps against 0."
- **Search performed**: Read `SQLLtOperator.java`, `SQLBinaryCompareOperator.doCompare`.
- **Code location**: `SQLLtOperator.java:22-26`; `SQLBinaryCompareOperator.java:329-364`.
- **Actual behavior**: `SQLLtOperator.execute` → `var c = SQLBinaryCompareOperator.doCompare(l,r); return c != null && c < 0;`. `doCompare` is a static returning a nullable `Integer` (null on null operand / unconvertible / non-Comparable). Le/Gt/Ge follow the same `c <op> 0` shape (confirmed via the `tryInPlaceComparison` mappings in SQLBinaryCondition 137-145).
- **Verdict**: CONFIRMED
- **Detail**: Delegating to the operator instance inherits the null-`doCompare`→false behavior automatically; the evaluator need not re-encode the sign mapping.

#### P-collate: single-property collate resolution chain (D6-R)
- **Track claim**: D6-R — collate fetch is `result.asEntity() → getImmutableSchemaClass(session) → getProperty(name) → getCollate()`, returning `null` for any non-`Var` operand, tried left-then-right; "matching the AST's single-segment `getCollate` branch."
- **Search performed**: Read `SQLBaseExpression.getCollate`, `SQLBaseIdentifier.getCollate`, `SQLSuffixIdentifier.getCollate`; PSI confirmed `Result.asEntity(): Entity`, `EntityImpl.getImmutableSchemaClass` present.
- **Code location**: `SQLSuffixIdentifier.java:505-520` (single-segment branch reached via `SQLBaseExpression.java:364-365` → `SQLBaseIdentifier.java:388-389` when `modifier == null`).
- **Actual behavior**: guarded: `if (identifier != null && currentResult != null)`, `if (currentResult.isEntity())`, `record = (EntityImpl) currentResult.asEntity()`, `if (schemaClass != null)`, `if (property != null) return property.getCollate()`, else `return null`. `Result.asEntity()` returns `Entity` (the cast to `EntityImpl` is real and necessary).
- **Verdict**: PARTIAL
- **Detail**: The resolution shape is right and the chain exists, but the D6-R pseudocode omits the `isEntity()` guard, the `(EntityImpl)` cast, and the per-hop null guards — produces finding **T1**. The left-then-right ordering and null-for-non-`Var` rule are confirmed against `SQLBinaryCondition.java:101-104`.

#### P-numericops: NumericOps arithmetic entry points (D5 / Track 2)
- **Track claim**: Plan of Work step 2 — "`visitBinaryOp` for `+ - * /` delegates to the shared `NumericOps`."
- **Search performed**: Read `NumericOps.java`.
- **Code location**: `core/.../sql/util/NumericOps.java:36-101,142-176,251-327`.
- **Actual behavior**: `+`/`-` have dedicated object-level methods (`plusObject`, `minusObject`) with operator-specific null + `Date`/`String` semantics; `*`/`/` have no object method and route via `applyObject(Operator op, Object, Object)` → `apply(Number, Operator, Number)` widening. Integer `/` widening (matrix `a / b / c`) lives in `apply(Operator, Integer, Integer)` SLASH branch (lines 145-150).
- **Verdict**: PARTIAL
- **Detail**: The shared engine and divide-widening exist, but the API is per-operator, not one uniform `apply` — produces finding **T3**. No blocker: all entry points exist and are public/static.

#### P-oracle: round-trip oracle methods (I1)
- **Track claim**: §Validation — `lower(parse(sql)).evaluate(row,ctx)` is `Objects.equals` to `parse(sql).execute(row,ctx)`.
- **Search performed**: grep `SQLExpression.execute`; Read `SQLBinaryCondition.evaluate`.
- **Code location**: `SQLExpression.java:123` (`execute(Result, CommandContext): Object`); `SQLBinaryCondition.java:70` (`evaluate(Result, CommandContext): boolean`).
- **Actual behavior**: two distinct oracle methods by parsed shape — arithmetic `SQLExpression.execute(...):Object`, comparison `SQLBinaryCondition.evaluate(...):boolean`. Both confirmed present.
- **Verdict**: PARTIAL
- **Detail**: Feasible but the §Validation "execute" wording is shape-blind — produces finding **T2**. No blocker.

#### P-lower-entry: lowering entry points the round-trip suite consumes (Track 3 contract)
- **Track claim**: §Purpose — round-trip suite evaluates `lower(parse(sql))`; prior-episode contract: `lowerBoolean` is package-visible for boolean round-trips.
- **Search performed**: grep public/static API of `AnalyzedExprLowerer`; Read `AnalyzedExprLowererTest` parse harness.
- **Code location**: `AnalyzedExprLowerer.java:67` (`public static AnalyzedExpr lower(SQLExpression)`), `:306` (`static AnalyzedExpr lowerBoolean(SQLBooleanExpression)` — package-visible). `AnalyzedExprLowererTest.java:1` (package `...query.analyzed`), `:41-66` (parse helpers).
- **Actual behavior**: `lower(SQLExpression)` is public; `lowerBoolean(SQLBooleanExpression)` is package-private. The Track-3 test (same package as the future round-trip suite) parses arithmetic via `parser(sql).Expression()` → `lower`, comparison via `parser(sql).BinaryCondition()` → `lowerBoolean`, NOT via `parser(sql).NotBlock()` → `lowerBoolean`.
- **Verdict**: CONFIRMED
- **Detail**: The round-trip suite, living in the same package, reuses this exact harness. Comparison/NOT fragments must be parsed directly (not as a wrapped `SQLExpression`) and routed through `lowerBoolean` — exactly the prior-episode contract. Unparenthesized-NOT input constraint also from Track 3 episode.

#### P-d15-callers: evaluate(Identifiable,ctx) production caller count (D15)
- **Track claim**: D15 — "PSI find-usages returns ~12 production callers, including `SQLWhereClause` and `SecurityEngine`" (an S1+/S7 forward obligation, not S0 work).
- **Search performed**: PSI `ReferencesSearch` on the base polymorphic `SQLBooleanExpression.evaluate(Identifiable, CommandContext)`.
- **Code location**: `SQLBooleanExpression.evaluate(Identifiable, CommandContext)` base method.
- **Actual behavior**: 12 references to the base (polymorphic) method; 0 direct references to the `SQLBinaryCondition` override (callers dispatch through the base type).
- **Verdict**: CONFIRMED
- **Detail**: D15's "~12" is exact (12). This is recorded as an S1/S7 obligation, not S0 work; the count substantiates the forward commitment. No S0 finding.

#### P-named-refs: all other production classes named in the track file resolve
- **Track claim**: track names `SQLBinaryCondition`, `SQLBinaryCompareOperator`, `SQLEqualsOperator`, `SQLNeOperator`, `SQLNeqOperator`, `SQLLtOperator`, `SQLLeOperator`, `SQLGtOperator`, `SQLGeOperator`, `QueryOperatorEquals`, `NumericOps`, `AnalyzedExpr`, `AnalyzedExprVisitor`, `AnalyzedExprLowerer`, `SQLMathExpression`.
- **Search performed**: PSI `PsiShortNamesCache.getClassesByName(...)` over all-scope for each.
- **Code location**: all resolve under `com.jetbrains.youtrackdb.internal.core.{sql.parser, sql.operator, sql.util, query.analyzed}`.
- **Actual behavior**: every named class resolves to a single FQN (no version-suffix V1→V2/V3 collapse trap; the six comparison operators are distinct classes, not a generic). `AnalyzedExprEvaluator` is the only NOT FOUND — created by this track (see P-eval-class).
- **Verdict**: CONFIRMED
- **Detail**: Component Map and §Interfaces references are accurate; no phantom class. `SQLNeqOperator` (the `<>` spelling) and `SQLNeOperator` (the `!=` spelling) both exist and both route to `!QueryOperatorEquals.equals(null,…)` (SQLNeqOperator confirmed via the `tryInPlaceComparison` instanceof at `SQLBinaryCondition.java:127-128`).
