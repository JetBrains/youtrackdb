<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Adversarial review — Track 4: Evaluator + round-trip parity (iter 1)

## Manifest

```yaml
review_type: adversarial
role: reviewer-adversarial
phase: 3A
track: "Track 4: Evaluator + round-trip parity"
iteration: 1
verdict: should-fix
findings: 6
blockers: 0
index:
  - id: A1
    sev: should-fix
    anchor: "A1"
    loc: "track-4.md §Plan of Work step 2 (arithmetic); §Decision Log D11"
    cert: "Assumption: NumericOps entry takes the AST Operator enum, IR holds its own BinaryOperator enum"
    basis: psi
  - id: A2
    sev: should-fix
    anchor: "A2"
    loc: "track-4.md §Decision Log D11/D3; design.md:679,712,714; §Context and Orientation mermaid"
    cert: "Challenge: D11 — 'the same operator object the AST holds' is structurally false for the IR path"
    basis: psi
  - id: A3
    sev: should-fix
    anchor: "A3"
    loc: "track-4.md §Plan of Work step 5; §Decision Log D6-R; design.md:717,732"
    cert: "Assumption: the collate chain asEntity().getImmutableSchemaClass(session).getProperty(name).getCollate()"
    basis: psi
  - id: A4
    sev: suggestion
    anchor: "A4"
    loc: "track-4.md §Plan of Work step 3 + step 1 'operand evaluation'; §Decision Log D11"
    cert: "Assumption: visitVar/visitConst already produce the operand values the AST's left.execute/right.execute produce"
    basis: psi
  - id: A5
    sev: suggestion
    anchor: "A5"
    loc: "track-4.md §Plan of Work step 6; §Decision Log D3/D15; §Interfaces and Dependencies"
    cert: "Scope: the S1+ Identifiable adapter is dead code in S0 with no exercising test"
    basis: reasoning
  - id: A6
    sev: suggestion
    anchor: "A6"
    loc: "track-4.md §Validation and Acceptance matrix; §Plan of Work step 7"
    cert: "Assumption: the round-trip suite can drive lowerBoolean for the two comparison rows and FuncCall has a covered fragment"
    basis: psi
evidence_base:
  certificates: 7
  psi_queries: 8
  summary: >
    PSI-grounded against the live tree (project analyzed-expression-5p7llp6k). The
    track's three inline design decisions are not re-litigated (D9-narrowed pass);
    the challenges target track realization. Core result: the IR BinaryOp variant
    retains only the IR-local BinaryOperator enum (PSI-confirmed comps
    `BinaryOperator op, AnalyzedExpr left, AnalyzedExpr right`), so the evaluator
    holds no parser operator object — it must construct fresh SQLBinaryCompareOperator
    instances and map two enum domains. Both mapping obligations are absent from the
    Plan of Work, and the D11/D3 phrasing "the same operator object the AST holds" is
    structurally false. Session threading (EQ real / NE null) and DatabaseSessionEmbedded
    availability both survive. No blocker — every gap is a realizable small addition,
    but several land as unstated work or false-by-construction prose the implementer
    would otherwise inherit.
```

## Findings

### A1 [should-fix]
**Certificate**: Assumption test — "NumericOps delegation is a direct call; the IR BinaryOp's operator feeds NumericOps unchanged"
**Target**: Plan of Work step 2 (Arithmetic) / Decision D11 realization
**Challenge**: Step 2 says `visitBinaryOp` for `+ - * /` "delegates to the shared `NumericOps`" and that "the evaluator only walks it and applies promotion at each node." But the two operator domains are distinct enums. PSI: `NumericOps.apply(Number, Operator, Number)` takes `com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression.Operator` (the 12-constant AST enum). The IR `BinaryOp` record component is `com.jetbrains.youtrackdb.internal.core.query.analyzed.BinaryOperator op` (the 10-constant IR enum: PLUS, MINUS, STAR, SLASH, EQ…GE). The evaluator must map IR `BinaryOperator.{PLUS,MINUS,STAR,SLASH}` → AST `SQLMathExpression.Operator.{PLUS,MINUS,STAR,SLASH}` before it can call `NumericOps.apply`. That inverse map exists nowhere: `AnalyzedExprLowerer.toArithmeticOperator(Operator) -> BinaryOperator` is the AST→IR direction and is `private`; `BinaryOperator` references are confined to `AnalyzedExpr`, `AnalyzedExprLowerer`, and the two test files (PSI find-usages). The `Date + Long` parity row depends on this map being correct.
**Evidence**: `core/.../sql/util/NumericOps.java` `apply(Number,Operator,Number)` param FQN = `SQLMathExpression.Operator`; `AnalyzedExpr.java` inner `record BinaryOp comps=BinaryOperator op,AnalyzedExpr left,AnalyzedExpr right`; `AnalyzedExprLowerer.toArithmeticOperator` is private and one-directional.
**Proposed fix**: Add an explicit sub-step to Plan of Work step 2: "map IR `BinaryOperator` → `SQLMathExpression.Operator` for the four arithmetic constants before delegating to `NumericOps.apply`." Name it as authored-here work (no reusable inverse exists). Survival: the decision survives — the map is trivial and total over four constants — but the step is under-described and the implementer would otherwise discover the type mismatch at compile time.

### A2 [should-fix]
**Certificate**: Challenge — Decision D11 / D3: "delegate to the parser's own `SQLBinaryCompareOperator` instance — the same operator object the AST holds"
**Target**: Decision D11 (and D3, §Context-and-Orientation mermaid note "same operator object as the AST")
**Chosen approach**: The track says comparison delegates to "the parser's own `SQLBinaryCompareOperator` instance — the same operator object the AST holds" (track-4.md D11 rationale, the mermaid Note "same operator object as the AST", design.md:679/712/714).
**Best rejected alternative**: none needed — the chosen approach as *worded* is not constructible; the realizable form is "construct a fresh `SQLBinaryCompareOperator` per IR enum constant."
**Counterargument trace**:
  1. The IR `BinaryOp` carries only `BinaryOperator op` (PSI: record comps above). It holds **no** reference to any parse node or operator object. `SQLBinaryCondition` holds the operator as an instance field `SQLBinaryCompareOperator operator` bound to the parsed tree — that object lives on the AST side and is unreachable from an `AnalyzedExpr`.
  2. So to "delegate to the operator instance," the evaluator must itself **instantiate** one: the six classes (`SQLEqualsOperator`, `SQLNeOperator`, `SQLNeqOperator`, `SQLLtOperator`, `SQLLeOperator`, `SQLGtOperator`, `SQLGeOperator`) extend `SimpleNode` and expose only `ctor(int)` / `ctor(YouTrackDBSql, int)` (PSI). The evaluator does e.g. `new SQLEqualsOperator(-1)`, not reuse of an AST object.
  3. Outcome: the parity argument ("the IR runs the same code the AST runs") holds at the *method-body* level — `SQLEqualsOperator.execute` runs `QueryOperatorEquals.equals(session, …)` identically regardless of which instance hosts it — but the prose "same operator *object*" is false and the construction mechanism (map IR enum → fresh operator instance, choosing a sentinel JJTree node id) is unstated work.
**Codebase evidence**: `AnalyzedExpr.java` `BinaryOp` comps (enum only); `SQLBinaryCondition` field `SQLBinaryCompareOperator operator`; `SQLEqualsOperator` ctors `(int)`/`(YouTrackDBSql,int)`; `SQLEqualsOperator.execute` body `return QueryOperatorEquals.equals(session, iLeft, iRight);`.
**Survival test**: WEAK — the decision's *intent* (run the operator's own `execute` so EQ/NE/ordering nuances are reproduced by construction) survives and is sound, but the rationale rests on a false claim ("same operator object") and omits the per-enum instantiation + node-id choice. Without correction the implementer must reverse-engineer that the AST object is unreachable and pick a JJTree id with no guidance.
**Proposed fix**: Reword D11/D3 and the mermaid note from "the same operator object the AST holds" to "a fresh `SQLBinaryCompareOperator` of the same class the AST uses for this operator — its `execute` body is identical, so behavioral parity is structural even though the instance differs." Add a Plan-of-Work sub-step under step 3: construct the operator instance per IR `BinaryOperator` comparison constant (note the `SimpleNode` int-id ctor and that the id is behaviorally inert for `execute`).

### A3 [should-fix]
**Certificate**: Assumption test — the collate chain `asEntity().getImmutableSchemaClass(session).getProperty(name).getCollate()`
**Target**: Plan of Work step 5 (collate-resolution helper) / Decision D6-R; design.md:717,732
**Claim**: The collate helper resolves via `result.asEntity()` → `getImmutableSchemaClass(session)` → `getProperty(name)` → `getCollate()` (track D6-R code block; design.md:732).
**Stress scenario**: Implement the helper against the public types the chain names.
**Code evidence**: `Result.asEntity()` returns the public `Entity` interface (PSI: `Entity asEntity()`). `Entity` exposes `getSchemaClass() -> SchemaClass` and `getProperty(String) -> T` — it has **no** `getImmutableSchemaClass(...)` and its `getProperty(String)` returns the column **value** (`T`), not a `SchemaProperty`. `getImmutableSchemaClass` exists only on `EntityImpl` (`getImmutableSchemaClass(DatabaseSessionEmbedded) -> SchemaImmutableClass`, PSI), the impl class, not the interface. The collate-bearing call is `SchemaClass.getProperty(String) -> SchemaProperty` then `SchemaProperty.getCollate() -> Collate` (PSI), reached off the schema class, not off the entity.
**Verdict**: FRAGILE — the resolution is reachable but the chain as written is type-inaccurate on two counts: (a) `getImmutableSchemaClass(session)` is an `EntityImpl` method requiring a downcast of `asEntity()`'s `Entity` (or use the no-arg `Entity.getSchemaClass()`); (b) the schema property comes from `SchemaClass.getProperty(name).getCollate()`, not `Entity.getProperty(name)` (which returns the value). The design's "exactly what the `Result` resolution consumes" framing (design.md:717) papers over the entity-vs-schema-class split.
**Proposed fix**: Correct the chain in D6-R and design.md to the accurate form, e.g. `entity.getSchemaClass().getProperty(name).getCollate()` (no-arg, interface-only) or, if the immutable variant is required for parity with the AST's `getImmutableSchemaClass` path, state the `EntityImpl` downcast and the `DatabaseSessionEmbedded` argument explicitly. Add a guard for `result.isEntity()` false / `getSchemaClass()` null → `null` collate (matches the non-column behavior the helper already promises).

### A4 [suggestion]
**Certificate**: Assumption test — visitVar/visitConst operand values match the AST's `left.execute`/`right.execute`
**Target**: Plan of Work step 1 ("operand evaluation") + step 3 ("evaluate both operands") / Decision D11
**Claim**: D11's step-1 ("evaluate both operands: `leftVal = left.execute(...)`") is reproduced by recursively evaluating the IR operands via the visitor (`visitVar` resolves the name against the `Result`; `visitConst` returns the literal).
**Stress scenario**: The AST applies `PropertyTypeInternal.convert` cross-type coercion *inside* `QueryOperatorEquals.equals(session,…)` (design.md:707), keyed on the session. If `visitVar`/`visitConst` produce operand values of different *runtime types* than the AST's `left.execute`/`right.execute` (e.g. a `Result` column read that boxes differently than the AST's `SQLBaseExpression.execute`, or a `Const` that the lowerer stored pre-coerced), the coercion inside `equals` resolves differently and the `type-coercing !=` parity row diverges.
**Code evidence**: prior-episode contract says "`Const` from SQL path is only a number/string leaf" and "lowerer builds STRUCTURE ONLY"; the cross-type coercion lives in `QueryOperatorEquals.equals` reached via the operator instance. The risk is in operand *value-type* fidelity, which the track asserts only structurally ("the IR runs the same code the AST runs") and does not pin with a matrix row beyond `type-coercing !=`.
**Verdict**: HOLDS (for the listed matrix) but under-pinned — the structural argument is sound because the *operator* code is shared, yet operand-type fidelity between `visitVar`-against-`Result` and AST `left.execute` is an untested seam.
**Proposed fix**: Add one matrix row that exercises a `Var op Const` where the column and literal are different numeric types (e.g. a `Long` column vs an `Integer` literal under `!=`), so the operand-coercion seam is asserted, not just the operator branch. Survival: decision holds; this strengthens the parity net at near-zero cost (a cheap addition, per the scope-challenge prompt).

### A5 [suggestion]
**Certificate**: Scope challenge — the S1+ Identifiable adapter is dead code in S0
**Target**: Plan of Work step 6 (`Identifiable` adapter) / D3/D15 / Interfaces "In scope"
**Challenge**: Step 6 ships "a small helper wrapping an `Identifiable` in a synthetic entity-backed `Result`" that the track itself marks "recorded for the S1+ path; S0 itself exercises only the `Result` overload." It is in-scope, untested code (the round-trip suite drives only the `Result` overload, I1). Shipping an unexercised adapter into a substrate PR adds a review surface and a coverage hole (the 85%/70% gate) for code with no S0 caller, and risks bit-rot before S1/S7 validate the ~12 `evaluate(Identifiable)` callers (D15).
**Evidence**: track Interfaces "In scope" lists the adapter; §Validation drives only `Result` rows; D15 defers the cross-caller validation to S1/S7. The track's own §Out-of-scope already excludes "the cross-caller `Identifiable`-path collation validation."
**Proposed fix**: Either (a) drop step 6 from S0 and record the adapter design in design.md as an S1+ obligation only (cleanest — keeps S0 to its acceptance bar), or (b) keep it but add a focused unit test that wraps an `Identifiable`, evaluates a `ci`-collated comparison, and asserts the convergence (so the coverage gate and the D15 behavior change are both exercised). Survival: the decision to *design* the seam survives; *shipping unexercised code* is the weak part. Lower severity because the planner can defend (b) as cheap forward-proofing.

### A6 [suggestion]
**Certificate**: Assumption test — the round-trip suite can drive the comparison/boolean rows and a FuncCall fragment
**Target**: Validation and Acceptance matrix / Plan of Work step 7 + step 1 (`visitFuncCall`)
**Claim**: The matrix's two comparison rows (`ci-column = 'Foo'`, `type-coercing !=`) and the boolean `NOT` path (step 4) are round-trippable via `lower(parse(sql))`.
**Stress scenario**: A comparison/NOT fragment parses to a `SQLBooleanExpression` (`SQLBinaryCondition` / `SQLNotBlock`), not an `SQLExpression`. PSI: `AnalyzedExprLowerer.lower(SQLExpression)` is the only public entry; `lowerBoolean(SQLBooleanExpression)` is package-visible (no modifier — confirmed) and `lowerComparison(SQLBinaryCondition)` is private. The round-trip suite must therefore call `lowerBoolean` directly for the comparison/NOT rows (the prior-episode contract states exactly this), and the suite lives in the same package to reach it. Separately, step 1 implements `visitFuncCall` ("evaluate the wrapped method call… coercing the result to the column type") but the matrix has **no** `FuncCall` row, so `visitFuncCall` ships unexercised by I1.
**Code evidence**: `AnalyzedExprLowerer.lower` public / `lowerBoolean` package-visible / `lowerComparison` private (PSI); matrix rows in §Validation list no function-call fragment; `AnalyzedExpr.FuncCall comps=String name,List<AnalyzedExpr> args` exists.
**Verdict**: HOLDS for the boolean path (the contract is honored: same-package suite, `lowerBoolean` reachable) — recorded so the implementer keeps the suite in `query/analyzed/`. FRAGILE for `visitFuncCall`: it is implemented but unpinned by parity. Note: prior-episode contract flags `FuncCall.args()` read-only by convention — the evaluator must honor that (not mutate args while evaluating).
**Proposed fix**: (a) State in step 7 that the round-trip suite resides in `core/.../query/analyzed/` so it can call package-visible `lowerBoolean`. (b) Add a `FuncCall` round-trip row (e.g. a covered method-call fragment over a column) so `visitFuncCall` is exercised by I1 and counted by the coverage gate, or, if no FuncCall fragment is in the S0 covered subset, move `visitFuncCall` to a throw and record the deferral. Survival: holds; both are cheap additions that close coverage seams.

## Evidence base

#### Challenge: Decision D11 — IR comparison evaluator delegates to "the same operator object the AST holds"
- **Chosen approach**: Reproduce `SQLBinaryCondition.evaluate(Result, ctx)` by delegating to the parser's own `SQLBinaryCompareOperator` instance — "the same operator object the AST holds."
- **Best rejected alternative**: N/A (the wording is non-constructible; the realizable form is per-enum fresh instantiation).
- **Counterargument trace**:
  1. IR `BinaryOp` carries only `BinaryOperator op` (PSI: `record BinaryOp comps=BinaryOperator op,AnalyzedExpr left,AnalyzedExpr right`); no parse-node reference.
  2. The AST operator object is a field of `SQLBinaryCondition` (`SQLBinaryCompareOperator operator`), bound to the parsed tree and unreachable from an `AnalyzedExpr`.
  3. The evaluator must `new SQLEqualsOperator(id)` etc. (ctors `(int)` / `(YouTrackDBSql,int)` only); behavioral parity holds at the `execute` body level, but "same object" is false and the instantiation/node-id is unstated.
- **Codebase evidence**: `AnalyzedExpr.java` BinaryOp comps; `SQLBinaryCondition` operator field; `SQLEqualsOperator` ctors + `execute` body.
- **Survival test**: WEAK (intent survives; rationale rests on a false claim and omits per-enum construction).

#### Challenge: Decision D11 — EQ real-session vs NE null-session reproduction
- **Chosen approach**: Delegate per operator so EQ passes the live session and NE passes `null`.
- **Counterargument trace**: PSI confirms `SQLEqualsOperator.execute` → `QueryOperatorEquals.equals(session, iLeft, iRight)` and `SQLNeOperator.execute` → `!QueryOperatorEquals.equals(null, left, right)`. Delegating to the per-operator instance reproduces this by construction.
- **Codebase evidence**: the two `execute` bodies above.
- **Survival test**: YES — the nuance is real and the per-operator-instance approach reproduces it exactly; this is the strongest part of D11.

#### Assumption test: NumericOps takes the AST Operator enum; IR holds its own BinaryOperator
- **Claim**: Arithmetic "delegates to the shared NumericOps" with no operator translation.
- **Stress scenario**: Call `NumericOps.apply` with the IR enum.
- **Code evidence**: `NumericOps.apply(Number,Operator,Number)` param = `SQLMathExpression.Operator`; IR `BinaryOp.op` = `query.analyzed.BinaryOperator`. Two distinct enums; no inverse map exists (`toArithmeticOperator` is AST→IR and private).
- **Verdict**: FRAGILE — delegation needs an IR→AST arithmetic-enum map authored in this track.

#### Assumption test: collate chain asEntity().getImmutableSchemaClass(session).getProperty(name).getCollate()
- **Claim**: The helper resolves collation via that chain on the `Result` entity.
- **Code evidence**: `Result.asEntity() -> Entity`; `Entity` has `getSchemaClass() -> SchemaClass` and `getProperty(String) -> T` (value), no `getImmutableSchemaClass`; `getImmutableSchemaClass(DatabaseSessionEmbedded)` is on `EntityImpl` only; `SchemaClass.getProperty(String) -> SchemaProperty`; `SchemaProperty.getCollate() -> Collate`.
- **Verdict**: FRAGILE — reachable, but the stated chain is type-inaccurate (downcast to `EntityImpl` for the immutable variant; collate comes off `SchemaClass.getProperty`, not `Entity.getProperty`).

#### Assumption test: operand value-type fidelity between visitVar/visitConst and AST left.execute/right.execute
- **Claim**: Recursively evaluating IR operands reproduces the AST operand values, so the in-`equals` coercion resolves identically.
- **Code evidence**: cross-type coercion (`PropertyTypeInternal.convert`) lives inside `QueryOperatorEquals.equals` keyed on session; operand-type fidelity is asserted only structurally and pinned by a single `type-coercing !=` row.
- **Verdict**: HOLDS for the listed matrix; under-pinned for the operand-coercion seam.

#### Scope: the S1+ Identifiable adapter is dead code in S0
- **Claim**: Ship the `Identifiable`→synthetic-`Result` adapter in S0 (in-scope, step 6).
- **Evidence**: round-trip suite drives only the `Result` overload (I1); D15 defers cross-caller validation to S1/S7; the adapter has no S0 caller or test → coverage hole + bit-rot risk.
- **Verdict**: weak to ship unexercised; design-only deferral or a focused convergence test resolves it.

#### Assumption test: round-trip suite reaches lowerBoolean and a FuncCall fragment exists
- **Claim**: The matrix's comparison/NOT rows round-trip via lowering, and `visitFuncCall` is meaningful.
- **Code evidence**: `lower(SQLExpression)` public; `lowerBoolean(SQLBooleanExpression)` package-visible; `lowerComparison` private; no FuncCall row in the matrix; `FuncCall` IR variant exists with read-only `args` (prior-episode convention).
- **Verdict**: HOLDS for boolean (suite must sit in `query/analyzed/`); FRAGILE for `visitFuncCall` (implemented, unpinned by I1).
