<!-- MANIFEST
role: reviewer-plan
phase: 2
iteration: 1
tier: full
artifacts: [implementation-plan.md, plan/track-1.md, plan/track-2.md, plan/track-3.md, plan/track-4.md, design.md]
findings: 1
verdict: changes-requested
index:
  - id: CR1
    sev: should-fix
    anchor: "CR1"
    loc: "design.md:469 (§Field-walk: exhaustive-or-throw over the union AST)"
    cert: "Ref: SQLBinaryCondition ANY/ALL branch methods"
    basis: design-code
    classification: mechanical
evidence_base:
  tool: "mcp-steroid PSI (project analyzed-expression-5p7llp6k)"
  refs_verified: 24
  refs_matched: 23
  refs_mismatched: 1
  flows_traced: 2
  flows_matched: 2
  invariants_checked: 3
  notes: "Greenfield packages query.analyzed + sql.util confirmed ABSENT (target-state, not flagged). All cited pre-existing AST/exception/schema symbols verified present with matching shape. Sole mismatch: design.md names the AST's ANY/ALL branch methods isFunctionAny/isFunctionAll, which do not exist; the actual methods are evaluateAny / evaluateAllFunction (the names the track files already use)."
-->

## Findings

### CR1 [should-fix]
**Certificate**: Ref: SQLBinaryCondition ANY/ALL branch methods (Design ↔ Code)
**Location**: `design.md:469`, Part 2 §"Field-walk: exhaustive-or-throw over the union AST". The sentence reads: "`any()` / `all()` must throw specifically: they carry property-iteration semantics (`SQLBinaryCondition.evaluate(Result)`'s `isFunctionAny` / `isFunctionAll` branches) that the IR comparison evaluator … does not reproduce."
**Issue**: `isFunctionAny` and `isFunctionAll` are phantom references — neither exists on `SQLBinaryCondition` (nor anywhere else) as a method or field. This is a current-state claim about the AST's existing ANY/ALL branch handling, so the discrepancy is a real Design ↔ Code mismatch, not a target-state artifact.
**Evidence**: PSI on `SQLBinaryCondition` (`core/.../sql/parser/SQLBinaryCondition.java`) returns these branch methods: `evaluateAny(Result, CommandContext)` and `evaluateAllFunction(Result, CommandContext)`. A field/member scan for any name containing `Any`/`All`/`Function` returns `evaluateAny`, `evaluateAllFunction`, and the unrelated indexed-function helpers — no `isFunctionAny`/`isFunctionAll`. The fields of `SQLBinaryCondition` are only `left`, `operator`, `right`. The track files already cite the correct names: `track-3.md:124-125` ("`SQLBinaryCondition`'s `evaluateAny` / `evaluateAllFunction` branches, PSI-confirmed present") and `track-4.md:361` ("plus `evaluateAny`/`evaluateAllFunction`"). So the design is the lone outlier and the correct rendering is unambiguous.
**Proposed fix**: In `design.md:469`, replace `isFunctionAny` / `isFunctionAll` with `evaluateAny` / `evaluateAllFunction` (the methods the track files use), so the design names the branches that exist: "(`SQLBinaryCondition.evaluate(Result)`'s `evaluateAny` / `evaluateAllFunction` branches)".
**Classification**: mechanical
**Justification**: Current-state claim that passed the intent-axis pre-screen (the AST's ANY/ALL handling exists today), with exactly one unambiguous correct rendering — the names the track files already carry — and the fix only corrects the description, leaving the design's intent (ANY/ALL must throw because property-iteration is not reproduced) unchanged. Severity should-fix, not blocker: the surrounding lowering behavior (`levelZero` → throw via the field-walk default) is correctly specified and executable regardless of the branch-method names, so the execution agent is not misled into wrong code; the wrong names only weaken the diagnostic citation a maintainer would follow.

## Evidence base

Tool for every symbol query below: mcp-steroid PSI (`steroid_execute_code` against the IDE, project `analyzed-expression-5p7llp6k`, which `steroid_list_projects` confirmed open and matching the working tree). Grep used only to locate the design/track Markdown line numbers and to scan the docs for the ANY/ALL name strings.

### Design ↔ Code

#### Ref: package `core/.../query/analyzed/`
- **Document claim**: design.md Part 1 / Component Map / track-1 §Context — greenfield package, "confirmed absent on develop".
- **Search performed**: PSI `JavaPsiFacade.findPackage("com.jetbrains.youtrackdb.internal.core.query.analyzed")`.
- **Code location**: NOT FOUND (package ABSENT).
- **Actual signature/role**: no such package.
- **Verdict**: MATCHES (target-state — documents claim absence; intent-axis pre-screen: the `[ ]` tracks create it, so absence is expected, not a finding).

#### Ref: package `core/.../sql/util/`
- **Document claim**: design.md §NumericOps / track-2 D5 — neutral location "confirmed absent on develop".
- **Search performed**: PSI `findPackage("com.jetbrains.youtrackdb.internal.core.sql.util")`.
- **Code location**: NOT FOUND (package ABSENT).
- **Actual signature/role**: no such package.
- **Verdict**: MATCHES (target-state; Track 2 creates it).

#### Ref: `SQLExpression` field set
- **Document claim**: design.md:426 + track-3.md:179-180/296-298 — fields `singleQuotes`, `doubleQuotes`, `isNull`, `rid`, `mathExpression`, `arrayConcatExpression`, `json`, `booleanExpression`, `booleanValue`, `literalValue`.
- **Search performed**: PSI field dump of `SQLExpression`.
- **Code location**: `core/.../sql/parser/SQLExpression.java`.
- **Actual signature/role**: `Boolean singleQuotes`, `Boolean doubleQuotes`, `boolean isNull`, `SQLRid rid`, `SQLMathExpression mathExpression`, `SQLArrayConcatExpression arrayConcatExpression`, `SQLJson json`, `SQLBooleanExpression booleanExpression`, `Boolean booleanValue`, `Object literalValue`.
- **Verdict**: MATCHES (exact field set, same order).

#### Ref: `SQLBaseExpression` field set
- **Document claim**: design.md:442 + track-3.md:299 — fields `number`, `identifier`, `inputParam`, `string`, `modifier`.
- **Search performed**: PSI field dump.
- **Code location**: `core/.../sql/parser/SQLBaseExpression.java`.
- **Actual signature/role**: `SQLNumber number`, `SQLBaseIdentifier identifier`, `SQLInputParameter inputParam`, `String string`, `SQLModifier modifier`.
- **Verdict**: MATCHES.

#### Ref: `SQLBaseIdentifier` field set (`levelZero` / `suffix`)
- **Document claim**: design.md:457 + track-3.md D18 — exactly one of `levelZero` (`SQLLevelZeroIdentifier`) or `suffix` (`SQLSuffixIdentifier`).
- **Search performed**: PSI field dump.
- **Code location**: `core/.../sql/parser/SQLBaseIdentifier.java`.
- **Actual signature/role**: `SQLLevelZeroIdentifier levelZero`, `SQLSuffixIdentifier suffix`.
- **Verdict**: MATCHES.

#### Ref: `SQLLevelZeroIdentifier` payloads (`functionCall`, `self`/@this, `collection`)
- **Document claim**: design.md:463-466 + track-3.md D18 — a `SQLLevelZeroIdentifier` carries a top-level `functionCall` (incl. `any()`/`all()`), the `self` reference (`@this`), or an inline `collection` (`[..]`).
- **Search performed**: PSI field dump.
- **Code location**: `core/.../sql/parser/SQLLevelZeroIdentifier.java`.
- **Actual signature/role**: `SQLFunctionCall functionCall`, `Boolean self`, `SQLCollection collection`.
- **Verdict**: MATCHES.

#### Ref: `SQLParenthesisExpression` payloads (`expression` / `statement`)
- **Document claim**: design.md §Parenthesis + track-3.md D10 — two mutually-exclusive payloads `expression: SQLExpression` (grouping) and `statement: SQLStatement` (subquery).
- **Search performed**: PSI field dump.
- **Code location**: `core/.../sql/parser/SQLParenthesisExpression.java`.
- **Actual signature/role**: `SQLExpression expression`, `SQLStatement statement`.
- **Verdict**: MATCHES.

#### Ref: `SQLNotBlock` fields (`sub`, `negate`)
- **Document claim**: design.md:472 + track-3.md:301 — fields `sub`, `negate`.
- **Search performed**: PSI field dump.
- **Code location**: `core/.../sql/parser/SQLNotBlock.java`.
- **Actual signature/role**: `SQLBooleanExpression sub`, `boolean negate`.
- **Verdict**: MATCHES.

#### Ref: `SQLMathExpression` shape (`childExpressions`, `operators`)
- **Document claim**: design.md §Precedence-fold + track-2.md:146-148 + track-3.md:181-183/302-303 — flat n-ary list `childExpressions: List<SQLMathExpression>`, `operators: List<Operator>`, precedence resolved at evaluate time.
- **Search performed**: PSI field dump of `SQLMathExpression`.
- **Code location**: `core/.../sql/parser/SQLMathExpression.java`.
- **Actual signature/role**: `Object NULL_VALUE`, `List<SQLMathExpression> childExpressions`, `List<Operator> operators`.
- **Verdict**: MATCHES.

#### Ref: `SQLMathExpression.Operator` inner enum (12 constants + apply family + getPriority + toLong)
- **Document claim**: design.md §NumericOps + track-2.md D5-R/D17 — inner enum, 12 constants (`STAR`, `SLASH`, `REM`, `PLUS`, `MINUS`, three shifts `LSHIFT`/`RSHIFT`/`RUNSIGNEDSHIFT`, `BIT_AND`, `XOR`, `BIT_OR`, `NULL_COALESCING`); five abstract typed-pair `apply` overloads (`Integer`,`Long`,`Float`,`Double`,`BigDecimal`); fallback `apply(Object, Object)`; shared widening `apply(Number, Operator, Number)`; private static `toLong`; `getPriority()`.
- **Search performed**: PSI inner-class scan + enum-constant + method dump.
- **Code location**: `SQLMathExpression$Operator`.
- **Actual signature/role**: isEnum=true; constants(12) = `STAR, SLASH, REM, PLUS, MINUS, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, XOR, BIT_OR, NULL_COALESCING`; methods `Number apply(Integer,Integer)[abstract]`, `apply(Long,Long)[abstract]`, `apply(Float,Float)[abstract]`, `apply(Double,Double)[abstract]`, `apply(BigDecimal,BigDecimal)[abstract]`, `Object apply(Object,Object)`, `Number apply(Number, Operator, Number)`, `int getPriority()`, `Long toLong(Object)[static][private]`.
- **Verdict**: MATCHES (every constant, overload, modifier, and the private static `toLong` align with track-2's PSI-confirmed signature block).

#### Ref: `SQLMathExpression` precedence-fold methods
- **Document claim**: design.md §Precedence-fold + track-2.md:148 + track-3.md D12 — `calculateWithOpPriority` → `iterateOnPriorities(Deque, Deque<Operator>)`; `unwrapIfNeeded()`.
- **Search performed**: PSI `findMethodsByName`.
- **Code location**: `SQLMathExpression`.
- **Actual signature/role**: `Object calculateWithOpPriority(Result, CommandContext)` and `(Identifiable, CommandContext)`; `Object iterateOnPriorities(Deque, Deque<Operator>)`; `SQLMathExpression unwrapIfNeeded()`.
- **Verdict**: MATCHES.

#### Ref: `SQLBinaryCondition.evaluate` overloads + `tryInPlaceComparison`
- **Document claim**: design.md §Comparison + track-4.md D3/D11/D16/§I&D — both `evaluate(Identifiable, ctx)` and `evaluate(Result, ctx)`; a slow path + an in-place fast path `tryInPlaceComparison`.
- **Search performed**: PSI method dump of `SQLBinaryCondition`.
- **Code location**: `core/.../sql/parser/SQLBinaryCondition.java`.
- **Actual signature/role**: `boolean evaluate(Identifiable currentRecord, CommandContext ctx)`, `boolean evaluate(Result currentRecord, CommandContext ctx)`, `Boolean tryInPlaceComparison(EntityImpl, String propName, Object rightVal)`.
- **Verdict**: MATCHES.

#### Ref: `SQLBinaryCondition` ANY/ALL branch methods
- **Document claim (design)**: design.md:469 — "`SQLBinaryCondition.evaluate(Result)`'s `isFunctionAny` / `isFunctionAll` branches".
- **Document claim (tracks)**: track-3.md:124-125 — "`SQLBinaryCondition`'s `evaluateAny` / `evaluateAllFunction` branches, PSI-confirmed present"; track-4.md:361 — "plus `evaluateAny`/`evaluateAllFunction`".
- **Search performed**: PSI method dump of `SQLBinaryCondition` + a name scan over all members for `Any`/`All`/`Function`.
- **Code location**: `core/.../sql/parser/SQLBinaryCondition.java`.
- **Actual signature/role**: `boolean evaluateAny(Result, CommandContext)`, `boolean evaluateAllFunction(Result, CommandContext)`. No member named `isFunctionAny` or `isFunctionAll` exists (member scan returned `evaluateAny`, `evaluateAllFunction`, and unrelated indexed-function helpers only).
- **Verdict**: MISMATCHES (design names two methods that do not exist; the track files carry the correct names). → CR1.

#### Ref: comparison operator classes + `execute`/`doCompare`
- **Document claim**: design.md §Comparison + track-4.md §Context/§I&D — six S0 comparison operators present (`SQLEqualsOperator`, `SQLNeOperator`/`SQLNeqOperator`, `SQLLtOperator`, `SQLLeOperator`, `SQLGtOperator`, `SQLGeOperator`); the shared `SQLBinaryCompareOperator` exposes `execute(session, left, right)` and `doCompare(l, r)`.
- **Search performed**: PSI class existence + method dump.
- **Code location**: `core/.../sql/parser/`.
- **Actual signature/role**: all seven operator classes FOUND; `SQLBinaryCompareOperator.execute(DatabaseSessionEmbedded, Object, Object)` and `Integer doCompare(Object, Object)`.
- **Verdict**: MATCHES.

#### Ref: EQ vs NE session threading (the D11 nuance)
- **Document claim**: design.md:704-710 + track-4.md D11:78-83 — `SQLEqualsOperator.execute` calls `QueryOperatorEquals.equals(session, …)` with the real session; `SQLNeOperator.execute` calls `!QueryOperatorEquals.equals(null, …)` with a null session.
- **Search performed**: PSI method-body read of both `execute` methods.
- **Code location**: `SQLEqualsOperator.execute` / `SQLNeOperator.execute`.
- **Actual signature/role**: EQ body `return QueryOperatorEquals.equals(session, iLeft, iRight);`; NE body `return !QueryOperatorEquals.equals(null, left, right);`.
- **Verdict**: MATCHES (the real-session/null-session asymmetry is exactly as the design and D11 describe).

#### Ref: `QueryOperatorEquals.equals` (the coercion entry the session feeds)
- **Document claim**: D11 — `QueryOperatorEquals.equals(session, left, right)`; mixed-type operands run through `PropertyTypeInternal.convert`, which consults the session.
- **Search performed**: PSI method dump.
- **Code location**: `QueryOperatorEquals`.
- **Actual signature/role**: `boolean equals(DatabaseSessionEmbedded, Object, Object)` and `boolean equals(DatabaseSessionEmbedded, Object, Object, PropertyTypeInternal)`.
- **Verdict**: MATCHES (the session-typed overload exists; the `PropertyTypeInternal` overload corroborates the coercion claim).

#### Ref: `SQLBaseExpression.getCollate`
- **Document claim**: design.md §Comparison — the AST resolves the collate via `left.getCollate(currentRecord, ctx)`.
- **Search performed**: PSI `findMethodsByName("getCollate")`.
- **Code location**: `SQLBaseExpression`.
- **Actual signature/role**: `Collate getCollate(Result, CommandContext)`.
- **Verdict**: MATCHES.

#### Ref: single-property collate-resolution chain (`asEntity` → `getImmutableSchemaClass` → `getProperty` → `getCollate` → `transform`)
- **Document claim**: design.md:716-718 + D6-R (track-4.md:178-183) — `result.asEntity()` → `getImmutableSchemaClass(session)` → `getProperty(name)` → `property.getCollate()`, then `collate.transform(...)`.
- **Search performed**: PSI class/method lookup (`PsiShortNamesCache` for `Collate`/`SchemaClass`/`SchemaProperty`; `EntityImpl` method dump).
- **Code location**: `metadata/schema/schema/{Collate,SchemaClass,SchemaProperty}.java`; `record/impl/EntityImpl.java`.
- **Actual signature/role**: `EntityImpl.getImmutableSchemaClass(DatabaseSessionEmbedded)`; `SchemaClass.getProperty(String)`; `SchemaProperty.getCollate()`; `Collate.transform(Object)`. Every link exists.
- **Verdict**: MATCHES (each hop in the re-implemented chain resolves; this is target-state evaluator behavior built on current-state symbols, all present).

#### Ref: `EntityImpl.isPropertyEqualTo` / `comparePropertyTo` (D16, S1+ citation)
- **Document claim**: track-4.md D16:149-150 — `tryInPlaceComparison` → `EntityImpl.isPropertyEqualTo` / `comparePropertyTo`.
- **Search performed**: PSI method dump.
- **Code location**: `EntityImpl`.
- **Actual signature/role**: `InPlaceResult isPropertyEqualTo(String, Object)`, `OptionalInt comparePropertyTo(String, Object)`.
- **Verdict**: MATCHES (cited as an S1+ forward reference; the named methods exist today).

#### Ref: `CommandExecutionException` (parent of `UnsupportedAnalyzedNodeException`)
- **Document claim**: design.md §Lowering-failures + track-1.md D7 — `UnsupportedAnalyzedNodeException extends CommandExecutionException` at `com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException`.
- **Search performed**: PSI class existence.
- **Code location**: `core/.../exception/CommandExecutionException.java`.
- **Actual signature/role**: class FOUND at the cited FQN.
- **Verdict**: MATCHES (the new exception extends it — target-state — but the parent exists today).

#### Ref: `SQLModifier.methodCall` (the `FuncCall` source)
- **Document claim**: design.md:471 + track-3.md D18 + track-1.md D4 — `FuncCall` comes from `SQLModifier.methodCall`; method-call coercion (`.asInteger()`) lowers through `FuncCall`.
- **Search performed**: PSI field dump of `SQLModifier`.
- **Code location**: `SQLModifier`.
- **Actual signature/role**: `SQLMethodCall methodCall` (alongside `squareBrackets`, `arrayRange`, `condition`, `arraySingleValues`, `rightBinaryCondition`, `suffix`, `next`).
- **Verdict**: MATCHES.

### Plan ↔ Code

The plan file's own content (Component Map, Checklist) carries no code references beyond the component names verified above. The Component Map bullets describe target-state new artifacts (Tracks 1-4 IR/lowerer/evaluator/NumericOps) and one current-state anchor — the `SQLExpression AST (existing parse tree)` node and the `SQLMathExpression.Operator` thin-delegator target — both verified present (see Design ↔ Code refs). No Integration Points, no Invariants section in the plan file itself (invariants live per-track). No phantom plan-level reference found.

#### Invariant: I3 — exhaustive visitor dispatch
- **Document claim**: track-1.md §I&C / design.md Part 1 — adding a sixth variant is a compile-time break across the sealed `switch` and every base-visitor implementer.
- **Code evidence**: target-state (the sealed type and dispatcher are created by Track 1; greenfield package ABSENT today).
- **Mechanism**: enforced by the Java 21 sealed `switch` exhaustiveness (compiler) once Track 1 lands; backed by the substrate unit test.
- **Verdict**: ASPIRATIONAL — but with an implementing track (Track 1). Intent-axis pre-screen: target-state with a named track and reachable from current code (Java 21 sealed interfaces are available; JDK 21 is the project baseline). Not a finding.

#### Invariant: I2 — no silent fallback (lowering throws or returns a complete tree)
- **Document claim**: track-3.md §I&C — every lowering path returns a complete tree or throws `UnsupportedAnalyzedNodeException`.
- **Code evidence**: target-state (Track 3 creates the lowerer + the throw sites; the field-walk default = throw).
- **Mechanism**: the exhaustive-or-throw field walk (D14) over the verified `SQLExpression`/`SQLBaseExpression` field sets; throw type extends the verified `CommandExecutionException`.
- **Verdict**: ASPIRATIONAL — implementing track Track 3, reachable (the union-AST field sets and the throw-default mechanism are confirmed). Not a finding.

#### Invariant: I1 — round-trip parity
- **Document claim**: track-4.md §I&C — `lower(parse(sql)).evaluate(row, ctx)` is `Objects.equals` to `parse(sql).execute(row, ctx)`.
- **Code evidence**: target-state (Track 4's round-trip suite). The oracle (`SQLMathExpression`/`SQLBinaryCondition` execute paths) and the reuse points (`NumericOps` promotion lifted from the verified `Operator.apply` family; the parser `SQLBinaryCompareOperator` instance) are all confirmed present.
- **Mechanism**: the suite asserts the matrix; the AST `execute` is the reference.
- **Verdict**: ASPIRATIONAL — implementing track Track 4, reachable. Not a finding.

### Design ↔ Plan

- **Class diagram ↔ Component Map + Decision Records**: design.md's `classDiagram` (the sealed `AnalyzedExpr`, five records, `AnalyzedExprVisitor<T>`, `AnalyzedExprTransform`, `AnalyzedExprEvaluator`, `NumericOps`, `UnsupportedAnalyzedNodeException`) maps one-to-one onto the Component Map's four tracks and onto track-1 D1/D2/D4/D6/D7/D8/D9, track-2 D5/D5-R/D17, track-3 D10/D12/D14/D18/D6-R, track-4 D3/D11/D15/D16/D6-R/D19. Every design Decision (D1-D19, S1-S7 invariants) has a corresponding inline DR in exactly one owning track (D6-R intentionally carried in both Track 3 and Track 4 as one logical decision — stated as such in both files). Verdict: MATCHES.
- **Workflow diagrams ↔ track descriptions**: the design Workflow flowchart + sequenceDiagram (parse → lower → evaluate; the comparison reuse of `NumericOps` and `SQLBinaryCompareOperator`) align with track-3's lowering flowchart and track-4's comparison sequenceDiagram. The track-4 sequenceDiagram's collate-resolution note (`result.asEntity() → schemaClass.getProperty(name) → property.getCollate()`) matches the design §Comparison chain and the D6-R chain — all symbols verified present. Verdict: MATCHES.
- **Scope indicators ↔ design complexity**: design assigns the lowerer "the heaviest piece" (three mechanisms); the plan sizes Track 3 ~4 files with a written sizing justification, and Tracks 1/2/4 at ~10/~3/~4 files each with sizing justifications (all four below the merge floor or near it, each carrying the D13 argumentation-gate note). Consistent with the design's per-Part complexity. Verdict: MATCHES.

### Gaps

- **Plan parts with no design coverage**: none. Every track's mechanisms trace to a design Part (T1→Part 1, T2→Part 3 §NumericOps, T3→Part 2, T4→Part 3 §Comparison + §Evaluator-interface + Part 4).
- **Design parts no track covers**: none. Part 1 → T1; Part 2 → T3; Part 3 §NumericOps → T2; Part 3 §Comparison/§Evaluator-interface → T4; Part 4 → T4 (round-trip suite). D17/D19 verification context lands in T2/T4 respectively.
- **Orphan codebase constructs the documents should reference but don't**: none material. The documents correctly anchor on the union-AST shape, the `Operator` enum, the `SQLBinaryCondition` slow/fast paths, the comparison-operator classes, the collate chain, and `CommandExecutionException`. The one stale citation (the ANY/ALL branch method names) is captured in CR1, not here, because the construct *is* referenced — just under a wrong name.
