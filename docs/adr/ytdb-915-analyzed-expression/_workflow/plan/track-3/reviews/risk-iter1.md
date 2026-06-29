<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: R1, sev: should-fix, loc: "track-3.md Plan-of-Work step 4 (D12); SQLMathExpression.iterateOnPriorities", anchor: "### R1 ", cert: EXP-fold, basis: "Structural precedence fold is the sole correctness risk; value parity pinned only in Track 4, so a fold bug lands as a Track-4 failure — add an in-track tree-shape assertion for mixed-precedence + left-assoc"}
  - {id: R2, sev: should-fix, loc: "track-3.md D14 / Plan-of-Work step 1; SQLExpression.execute value-fallback chain", anchor: "### R2 ", cert: TEST-valuewalk, basis: "Inherited SimpleNode.value legacy branch likely unreachable via the modern parser; the throw-default covering it may be an unhittable branch, threatening the 70% branch gate — resolve the Phase-A reachability note and document the coverage exemption"}
  - {id: R3, sev: suggestion, loc: "track-3.md D18; design.md:469", anchor: "### R3 ", cert: ASM-anyall, basis: "design.md:469 names isFunctionAny/isFunctionAll; the real branches are SQLBinaryCondition.evaluateAny/evaluateAllFunction (track-3.md is correct). Lowerer never calls these, so no Track-3 risk; flag only so the Phase-4 design reconciliation does not re-derive it"}
  - {id: R4, sev: suggestion, loc: "track-3.md Plan-of-Work step 2 leaf throws; lowering throw-case test set", anchor: "### R4 ", cert: TEST-throwset, basis: "Many distinct throw branches (rid/json/arrayConcat/levelZero/any-all/multi-seg Var/inputParam/subquery/CASE); each needs its own parser input — achievable but the throw-set is the coverage long pole, worth an explicit per-shape checklist in the test step"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
flags: [CONTRACT_OK]
-->

## Findings

### R1 [should-fix]
**Certificate**: EXP-fold (Exposure — precedence-climbing fold, D12)
**Location**: `track-3.md` Plan of Work step 4 + Decision Log D12; mirrors `SQLMathExpression.iterateOnPriorities` / `calculateWithOpPriority`.
**Issue**: The precedence fold is the one real correctness risk in this greenfield track. The lowerer must reproduce — *structurally* — the exact reduction the AST runs at evaluate time: `iterateOnPriorities` reduces on `operatorsStack.peek().getPriority() <= nextOperator.getPriority()` (left-associative, tightest-binding-first), keyed on `Operator.getPriority()`. I traced both `calculateWithOpPriority` overloads and `iterateOnPriorities`; the algorithm is a textbook precedence-climbing reduction, so it is tractable. The risk is not the algorithm's difficulty but *where its bug surfaces*: D12's parity is a **value** property (`a + b*c` must yield the AST's `7`, not `9`; `a - b - c` must be `(a-b)-c`), and per the track's own Validation section the value assertion lives in Track 4's round-trip matrix, not here. So a fold mis-nesting (wrong associativity, or reducing loosest-first) compiles, passes any weak in-track shape test, and only fails two tracks later as a Track-4 round-trip mismatch — expensive to localize back to lowering. Likelihood: medium (the AST's own fold is non-obvious — it reduces from the *back* of the deque and re-iterates). Impact: medium (silent parity hole until Track 4).
**Proposed fix**: Decomposition should make the lowering test step assert the produced `BinaryOp` **tree shape** for at least: (a) one mixed-precedence input (`a + b * c` → `BinaryOp(PLUS, a, BinaryOp(STAR, b, c))`), and (b) one same-precedence left-assoc input (`a - b - c` → `BinaryOp(MINUS, BinaryOp(MINUS, a, b), c)`). These are structural assertions the track can make on its own (no evaluator needed), so the fold defect is caught in Track 3 rather than deferred to Track 4. The track already lists these in its Invariants; the finding is to make them concrete in-track test assertions, not just cite Track 4.

### R2 [should-fix]
**Certificate**: TEST-valuewalk (Testability — `SimpleNode.value` reachability, D14)
**Location**: `track-3.md` D14 + Plan of Work step 1 ("confirm whether `SimpleNode.value` is ever non-null on the modern parser path"); `SQLExpression.execute`.
**Issue**: D14 carries an explicit **Phase-A PSI verification note** that is still open: is the inherited `SimpleNode.value` field ever non-null on the modern parser path? I read `SQLExpression.execute(Result, CommandContext)`: the named-field checks (`isNull`, `rid`, `mathExpression`, `arrayConcatExpression`, `booleanExpression`, `json`, `booleanValue`, `literalValue`) run **first**, and only then does a `value instanceof …` chain run, guarded by the inline comments "only for old executor (manually replaced params)" and "from here it's old stuff, only for the old executor." This is strong evidence the `value`-backed shapes are a legacy fallback the modern parser path does not populate — i.e. on the modern path a parsed `SQLExpression` always has exactly one *named* field non-null. The design's safety argument holds either way (the field-walk throw-default degrades a `value`-only node to a throw, never a mis-read). The risk is **coverage, not correctness**: the throw-default's "unrecognized field" branch that exists to catch a `value`-only node may be **unreachable through the parser**, so the lowering test cannot construct an input that exercises it, and JaCoCo will report it as an uncovered branch — pressure on the 70% branch gate the CLAUDE.md coverage rule enforces. Likelihood: medium. Impact: low-medium (a coverage-gate scramble during Phase B, not a runtime defect).
**Proposed fix**: Resolve the Phase-A note during decomposition: confirm via PSI/parser that no modern parse produces a `value`-only `SQLExpression`, record the answer in the track's Surprises log, and have the decomposer pre-authorize a coverage exemption (or a `// unreachable on modern parser path` comment + targeted assertion) for the throw-default's value-only sub-branch so the Phase-B coverage gate is not surprised. If reachable, add a throw-case test for it.

### R3 [suggestion]
**Certificate**: ASM-anyall (Assumption — `any()`/`all()` evaluation branch naming, D18)
**Location**: `track-3.md` D18 vs `design.md:469`.
**Issue**: D18 in the track file states `any()`/`all()` carry property-iteration semantics in `SQLBinaryCondition`'s `evaluateAny` / `evaluateAllFunction` branches. PSI confirms `SQLBinaryCondition` has exactly `boolean evaluateAny(Result, CommandContext)` and `boolean evaluateAllFunction(Result, CommandContext)` — so **the track file is correct**. `design.md:469` instead names "`isFunctionAny` / `isFunctionAll` branches of `SQLBinaryCondition.evaluate(Result)`"; `isFunctionAny`/`isFunctionAll` are real methods but they live on `SQLExpression`/`SQLBaseExpression`/`SQLBaseIdentifier`/`SQLLevelZeroIdentifier` as *predicates*, not as the evaluation branches on `SQLBinaryCondition`. This is the same design.md:469 imprecision already logged as the Phase-2 consistency CR1 (deferred to Phase 4, design frozen). It is **not a Track-3 plan defect**: the lowerer never calls any of these methods — `levelZero`/`any()`/`all()` throw purely via the field-walk's exhaustive-or-throw default (D14), which I confirmed (`levelZero` matches no recognized field). Likelihood/impact on Track 3: none.
**Proposed fix**: No Track-3 action. Flagging only so the Phase-4 design-final reconciliation (already holding the CR1 deferral) uses the PSI-confirmed names: the evaluation branches are `SQLBinaryCondition.evaluateAny` / `evaluateAllFunction`; the per-node predicates are `isFunctionAny` / `isFunctionAll`.

### R4 [suggestion]
**Certificate**: TEST-throwset (Testability — throw-case coverage breadth, I2)
**Location**: `track-3.md` Plan of Work step 2 + Validation "Throw cases (I2)"; lowering throw-case tests.
**Issue**: I2 (no silent fallback) is the track's load-bearing invariant and it is verified entirely by throw-case tests. The throw set is wide: out-of-subset `SQLExpression` fields (`rid`, `arrayConcatExpression`, `json`), `SQLParenthesisExpression.statement` (subquery), `CaseExpression`, a `levelZero` identifier (and specifically `any()`/`all()`, `@this`, inline collection), a multi-segment `Var` (D6-R), and `inputParam` (bind param). Each needs a *distinct parser input* that produces the relevant AST shape. The test infra exists (`parseExpression(sql)` via `new YouTrackDBSql(new ByteArrayInputStream(...))` + `parser.Expression()` — established pattern across core executor tests), so this is ACHIEVABLE, not difficult. But it is the coverage long pole: each throw branch is one parse-and-assert, and missing any one leaves an I2 hole that no other test catches (greenfield — no consumer would notice a silent partial tree). Likelihood of a gap: medium if the test step is under-specified. Impact: low-medium (an I2 hole on an unconsumed substrate, but I2 is the contract S1+ relies on).
**Proposed fix**: Decomposition should give the lowering-test step an explicit per-shape throw checklist (one assertion per shape above), so the implementer cannot collapse the throw-case set to "a couple of representative throws." Pair each with the parsed-string input that produces it.

## Evidence base

#### EXP-fold (Exposure — precedence-climbing fold, D12)
- **Track claim**: The lowerer reproduces the AST's precedence-and-associativity nesting *structurally* (no value computation), keyed on `Operator.getPriority()` with `<=` left-associative reduction matching `iterateOnPriorities`.
- **Critical path trace** (the AST fold the lowerer must mirror, PSI-read `steroid_execute_code` method bodies):
  1. `SQLMathExpression.calculateWithOpPriority(Result, ctx)` — pushes `childExpressions.getFirst()`, then loops `operators`, reducing into `valuesStack`/`operatorsStack` when `operatorsStack.peek().getPriority() <= nextOperator.getPriority()`.
  2. `iterateOnPriorities(Deque values, Deque<Operator> operators)` — drains from the *back* (`removeLast`), reduces `operatorsStack.peek().getPriority() <= nextOperator.getPriority()`, calls `.apply(left, right)` per reduction, then re-iterates until one value remains.
  3. `Operator.getPriority()` returns the per-constant `priority` int (enum constants `STAR, SLASH, REM, PLUS, MINUS, …`; `STAR` binds tighter than `PLUS`).
  4. `unwrapIfNeeded()` collapses a single-child `SQLMathExpression` to its child (`childExpressions.size()==1`).
- **Blast radius**: nil to production — the AST fold is untouched (the lowerer reimplements a *structural* twin in the new `AnalyzedExprLowerer`, calling `new BinaryOp` where the AST calls `.apply`). A bug affects only the IR tree shape, which has no consumer in S0; it surfaces as a Track-4 round-trip value mismatch.
- **Existing safeguards**: the AST fold is the live, tested reference oracle (Track 4's round-trip matrix compares against it). The lowerer's structural fold is greenfield with no safeguard until that matrix runs.
- **Residual risk**: MEDIUM — correct algorithm but value-parity verification is deferred to Track 4; an in-track shape assertion closes the gap (R1).

#### EXP-blast (Exposure — production blast radius of the new lowerer)
- **Track claim**: `AnalyzedExprLowerer` is greenfield, ships behind no flag, no production consumer; it reads but does not modify the AST classes.
- **Critical path trace**: `AnalyzedExprLowerer` does not exist on the branch (`findClass` count = 0). The IR types it produces (`AnalyzedExpr` + 5 records, `BinaryOperator`, `UnaryOperator`, `UnsupportedAnalyzedNodeException`) are referenced only inside the `query/analyzed/` package and its test — `ReferencesSearch` (PSI): `AnalyzedExpr` 92 refs all in `AnalyzedExpr.java`/`AnalyzedExprTransform.java`/`AnalyzedExprVisitor.java`/`UnaryOperator.java`/`BinaryOperator.java`/`AnalyzedExprTest.java`; `BinaryOperator` 10 (impl+test only); `UnsupportedAnalyzedNodeException` 7 (impl+test only). Zero references from storage / WAL / tx / index / cache / executor production code.
- **Blast radius**: none on production. A lowering bug cannot reach any live read/write path.
- **Existing safeguards**: package isolation is the safeguard — the IR has no entry point from production.
- **Residual risk**: LOW — confirms the prompt's framing; classic blast-radius is genuinely nil.

#### EXP-astread (Exposure — reads existing AST classes, modifies none)
- **Track claim**: The lowerer reads `SQLExpression`, `SQLMathExpression`/`SQLBaseExpression`, `SQLBaseIdentifier`/`SQLLevelZeroIdentifier`/`SQLSuffixIdentifier`, `SQLParenthesisExpression`, `SQLNotBlock`, `SQLNumber`, `SQLModifier`; modifies none.
- **Critical path trace** (PSI field/shape confirmation): `SQLExpression` fields = `singleQuotes, doubleQuotes, isNull, rid, mathExpression, arrayConcatExpression, json, booleanExpression, booleanValue, literalValue` (exact match to track). `SQLBaseExpression extends SQLMathExpression`, fields `number, identifier, inputParam, string, modifier`. `SQLMathExpression` fields `childExpressions: List<SQLMathExpression>`, `operators: List<Operator>`. `SQLParenthesisExpression` fields `expression: SQLExpression`, `statement: SQLStatement`. `SQLNotBlock extends SQLBooleanExpression`, fields `sub: SQLBooleanExpression`, `negate: boolean`; `evaluate` returns `negate ? !result : result` (pass-through when `negate=false`) — confirms the planned `UnaryOp(NOT, lower(sub))` / pass-through lowering. `SQLBaseIdentifier` fields `levelZero: SQLLevelZeroIdentifier`, `suffix: SQLSuffixIdentifier`. `SQLLevelZeroIdentifier` fields `functionCall, self, collection`. `SQLSuffixIdentifier` fields `identifier, recordAttribute, star`.
- **Blast radius**: reading is non-mutating; no AST behavior changes. None.
- **Existing safeguards**: the AST classes are existing, tested production code.
- **Residual risk**: LOW — all PSI-confirmed shapes in the track match develop exactly.

#### ASM-anyall (Assumption — `any()`/`all()` evaluation branch naming, D18)
- **Track claim** (track-3.md D18): `any()`/`all()` carry property-iteration semantics in `SQLBinaryCondition`'s `evaluateAny` / `evaluateAllFunction` branches.
- **Evidence search**: PSI `steroid_execute_code` — enumerated `SQLBinaryCondition` methods matching any/all/evaluate.
- **Code evidence**: `SQLBinaryCondition` has `boolean evaluate(Result, ctx)`, `boolean evaluateAny(Result, ctx)`, `boolean evaluateAllFunction(Result, ctx)` — the track file's names are correct. `isFunctionAny`/`isFunctionAll` exist but are *predicates* on `SQLExpression`/`SQLBaseExpression`/`SQLBaseIdentifier`/`SQLLevelZeroIdentifier`, not the `SQLBinaryCondition` evaluation branches `design.md:469` attributes them to.
- **Verdict**: VALIDATED (for the track file; `design.md:469` is the imprecise statement — the Phase-2 CR1 deferral).
- **Detail**: The lowerer never invokes these methods; `levelZero`/`any()`/`all()` throw via the D14 field-walk default. Informational for Phase-4 reconciliation only (R3).

#### TEST-valuewalk (Testability — `SimpleNode.value` reachability, D14)
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: `SimpleNode.value` is a real inherited field (PSI-confirmed on `SimpleNode`; 86 references, several `value instanceof …` reads inside `SQLExpression.java`). `SQLExpression.execute` reads the named fields first, then the `value instanceof …` chain marked "only for old executor (manually replaced params)" / "from here it's old stuff, only for the old executor." If the modern parser never leaves a node with only `value` non-null, the lowerer's throw-default branch covering that case is unreachable through `parser.Expression()`, so no lowering test can hit it → a JaCoCo-uncovered branch pressuring the 70% gate.
- **Existing test infrastructure**: `parseExpression(sql)` pattern (`new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()))` + `parser.Expression()`), established across `core/.../sql/executor/*StepTest.java`; `AnalyzedExprTest` shows the JUnit-4 `assertThrows`/`assertEquals` style for the package.
- **Feasibility**: DIFFICULT for the `value`-only sub-branch specifically; ACHIEVABLE for everything else.
- **Detail**: Resolve the Phase-A reachability note (R2) and pre-authorize a coverage exemption for the unreachable value-only sub-branch, or add a test if reachable.

#### TEST-throwset (Testability — throw-case coverage breadth, I2)
- **Coverage target**: 85% line / 70% branch (plus I2 = every out-of-subset shape throws).
- **Difficulty assessment**: wide throw set — `rid`/`arrayConcatExpression`/`json`, subquery `statement`, `CaseExpression`, `levelZero` (incl. `any()`/`all()`, `@this`, inline collection), multi-segment `Var` (D6-R), `inputParam`. Each is one parse-and-assert; the difficulty is breadth/completeness, not per-case hardness.
- **Existing test infrastructure**: same `parseExpression`/`assertThrows` infra as above.
- **Feasibility**: ACHIEVABLE — given an explicit per-shape checklist in the test step.
- **Detail**: Under-specifying the throw-case step risks an I2 hole no other test catches (greenfield, no consumer). Make the checklist explicit (R4).

#### TEST-shape (Testability — precedence-fold structural assertions, D12)
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the produced `BinaryOp` tree shape is directly assertable from a parsed input without any evaluator (records have structural `equals`); coverage-cases listed in the track (arithmetic single + mixed-precedence, comparison, parenthesized grouping, single-segment `Var`, `Const`, method-call `FuncCall`, `UnaryOp(NOT)`) are all reachable via `parser.Expression()`.
- **Existing test infrastructure**: `parseExpression` infra + record `equals` (AnalyzedExpr variants are records, so deep structural equality is free).
- **Feasibility**: ACHIEVABLE.
- **Detail**: No gap; this certificate underpins R1's proposed in-track shape assertions being feasible without Track 4.
