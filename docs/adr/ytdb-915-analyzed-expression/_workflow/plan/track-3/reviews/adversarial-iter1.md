<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "track-3.md:240 (Plan of Work step 4)", anchor: "### A1 ", cert: "Violation V1", basis: "8 AST Operator constants (REM, 3 shifts, 3 bitwise, NULL_COALESCING) have no IR BinaryOperator; the fold has no stated throw, so an in-subset mathExpression field carrying % or << reaches an unmapped operator"}
  - {id: A2, sev: should-fix, loc: "track-3.md:214-249 (Plan of Work)", anchor: "### A2 ", cert: "Assumption AS1", basis: "comparison lowering (booleanExpression->SQLBinaryCondition->BinaryOp(op)) plus the ~15->6 compare-operator mapping and ~19-subtype boolean dispatch is in the mermaid + Validation but is not a numbered Plan-of-Work step"}
  - {id: A3, sev: suggestion, loc: "track-3.md:186-189 (Context and Orientation)", anchor: "### A3 ", cert: "Assumption AS2", basis: "Track-1 IR types + Track-2 independence claim both hold under PSI audit; the no-Track-2-dependency claim survives because the fold is structural"}
  - {id: A4, sev: suggestion, loc: "track-3.md:217-220 (Plan of Work step 1)", anchor: "### A4 ", cert: "Assumption AS3", basis: "SQLMathExpression is polymorphic (4 subtypes: FirstLevelExpression, ParenthesisExpression, CaseExpression, BaseExpression); step 1 names 3, the throw-default covers FirstLevelExpression, but the instanceof dispatch order should be pinned"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index:
  - {id: "Challenge C1", verdict: SURVIVES, anchor: "#### Challenge C1 "}
  - {id: "Violation V1", verdict: CONSTRUCTIBLE, anchor: "#### Violation V1 "}
  - {id: "Assumption AS1", verdict: FRAGILE, anchor: "#### Assumption AS1 "}
  - {id: "Assumption AS2", verdict: HOLDS, anchor: "#### Assumption AS2 "}
  - {id: "Assumption AS3", verdict: HOLDS, anchor: "#### Assumption AS3 "}
flags: [CONTRACT_OK]
-->

# Track 3 (Lowering pass) — adversarial review, iteration 1

Verdict: **PASS with two should-fix decomposition gaps.** Track 3's design
decisions (D10/D12/D14/D18/D6-R) survive — they were vetted at the research-log
gate and the codebase confirms their factual claims. The two should-fix findings
are **track-realization** gaps the forthcoming step roster must close, not design
reversals: the precedence fold and the comparison path each touch an AST operator
set wider than the IR can name, and the track text leaves the throw-on-unmapped
behavior implicit. Both are constructible I2 (no-silent-fallback) exposures if a
step is written from the Plan of Work as it reads today. No blocker; the IR
substrate (Track 1) and the `NumericOps`-independence claim (Track 2) both hold
under PSI audit.

## Findings

### A1 [should-fix]
**Certificate**: Violation V1 (operand-coverage gap in the precedence fold)
**Target**: D12 (precedence fold) / I2 (no silent fallback) — Plan of Work step 4
**Challenge**: The IR `BinaryOperator` enum carries only four arithmetic constants
(`PLUS, MINUS, STAR, SLASH`), but the AST `SQLMathExpression.Operator` it folds over
carries **twelve**: `STAR(10), SLASH(10), REM(10), PLUS(20), MINUS(20), LSHIFT(30),
RSHIFT(30), RUNSIGNEDSHIFT(30), BIT_AND(40), XOR(50), BIT_OR(60), NULL_COALESCING(25)`
(PSI-confirmed in `SQLMathExpression.java`). Eight operators — `REM`, the three
shifts, the three bitwise ops, and `NULL_COALESCING` — have **no IR mapping**. These
arrive on the *in-subset* `mathExpression` field, so the field-walk's throw-default
(D14) does **not** catch them: `a % b` is a perfectly valid `SQLMathExpression` whose
`operators` list is `[REM]`. The Plan of Work step 4 says the fold is "keyed on
`Operator.getPriority()` with `<=` left-associative reduction … no value computation"
and never states that an operator outside `{PLUS,MINUS,STAR,SLASH}` must throw
`UnsupportedAnalyzedNodeException`. A step written verbatim from step 4 either NPEs on
a missing enum mapping or — worse — silently coerces, violating I2.
**Evidence**: `core/.../sql/parser/SQLMathExpression.java` Operator enum (12 constants,
PSI-dumped); IR `BinaryOperator` = `{PLUS,MINUS,STAR,SLASH,EQ,NE,LT,LE,GT,GE}` (PSI-dumped
from `core/.../query/analyzed/BinaryOperator.java`). Design §"NumericOps" line 638
acknowledges "the eight operators outside the S0 IR subset (`REM`, shifts, bitwise,
`NULL_COALESCING`)" exist — but that note is about the `NumericOps` *evaluator* extraction
(Track 2/4), not about the **lowering** throw, which is Track 3's responsibility and is
unstated here.
**Proposed fix**: Add to Plan of Work step 4 (and to the Validation throw-cases) an
explicit clause: the fold maps each AST `Operator` to its IR `BinaryOperator` and throws
`UnsupportedAnalyzedNodeException(node.getClass())` on any operator with no IR constant
(`REM`, `LSHIFT`, `RSHIFT`, `RUNSIGNEDSHIFT`, `BIT_AND`, `XOR`, `BIT_OR`,
`NULL_COALESCING`). Add a throw-case test for at least `a % b` and `a << b`. This keeps
I2 robust by construction and mirrors D14's defaulting discipline.

### A2 [should-fix]
**Certificate**: Assumption AS1 (comparison lowering is decomposed)
**Target**: Plan of Work decomposition (steps 1-6) / D11-adjacent lowering mechanism
**Challenge**: The IR `BinaryOperator` carries six comparison constants
(`EQ,NE,LT,LE,GT,GE`), so lowering a `name < 5` comparison into `BinaryOp(LT, Var, Const)`
is **Track 3's structural job** (Track 4 only *evaluates* the produced `BinaryOp`). But
the comparison-lowering mechanism is missing from the numbered Plan of Work. Step 5 covers
only boolean `NOT`; steps 1-4 cover the field-walk, the math-leaf descent, paren recursion,
and the arithmetic fold. The mermaid edge `FW -->|booleanExpression / NOT| LEAF` and the
Validation "comparison" coverage case imply the intent, but the *mechanism* is undescribed:
(a) the `booleanExpression` field reaches `SQLBooleanExpression`, which has **~19 direct
subtypes** (PSI: `SQLBinaryCondition, SQLAndBlock, SQLOrBlock, SQLBetweenCondition,
SQLInCondition, SQLInstanceofCondition, SQLIsNullCondition, SQLContainsCondition, …` plus
`SQLNotBlock`) — only `SQLBinaryCondition` and `SQLNotBlock` are in-subset; (b) a
`SQLBinaryCondition` carries `operator: SQLBinaryCompareOperator`, which is abstract with
**~15 concrete subtypes** (PSI: `SQLEqualsOperator, SQLNeqOperator, SQLNeOperator,
SQLLtOperator, SQLLeOperator, SQLGtOperator, SQLGeOperator` map to the six IR ops; the other
~9 — `SQLLikeOperator, SQLContainsValueOperator, SQLInOperator, SQLNearOperator,
SQLLuceneOperator, SQLWithinOperator, SQLContainsKeyOperator, SQLScAndOperator,
SQLContainsValueOperator` — must throw). Two distinct dispatch tables (boolean-subtype and
compare-operator-subtype), each exhaustive-or-throw, are an entire mechanism on par with the
precedence fold, yet they get no step.
**Evidence**: PSI `DirectClassInheritorsSearch(SQLBooleanExpression)` → 19 subtypes;
`ClassInheritorsSearch(SQLBinaryCompareOperator, deep=true)` → 15 subtypes; `SQLBinaryCondition`
fields `{left: SQLExpression, operator: SQLBinaryCompareOperator, right: SQLExpression}`. Note
the **two** `!=` spellings (`SQLNeqOperator` and `SQLNeOperator`) — both must map to IR `NE`,
an easy single-mapping miss.
**Proposed fix**: Add a Plan of Work step (between current 4 and 5) for comparison lowering:
dispatch the `booleanExpression` field over the `SQLBooleanExpression` subtypes
(recognize `SQLBinaryCondition` → `BinaryOp(<cmp>, lower(left), lower(right))`, `SQLNotBlock`
→ `UnaryOp(NOT, …)`, throw on the rest), and map the `SQLBinaryCompareOperator` concrete
class to the IR comparison constant (both `SQLNeqOperator`/`SQLNeOperator` → `NE`), throwing
on every unmapped compare operator. Reflect both throw families in the Validation throw-cases.

### A3 [suggestion]
**Certificate**: Assumption AS2 (Track-1 outputs exist; Track-2 is not a dependency)
**Target**: Assumption — Context and Orientation "depends on Track 1 … does not depend on Track 2"
**Challenge**: Probed whether any lowering step secretly needs `NumericOps` arithmetic value
semantics. It does not. The fold is purely structural (it builds `BinaryOp` nesting, computes
no value), so the no-Track-2-dependency claim is sound. And every Track-1 output this track
names exists with the assumed shape.
**Evidence**: PSI-confirmed shipped by Track 1: `AnalyzedExpr` (sealed) with nested records
`Var(List<String> path)`, `Const(Object value)`, `BinaryOp(BinaryOperator op, AnalyzedExpr
left, AnalyzedExpr right)`, `UnaryOp(UnaryOperator op, AnalyzedExpr operand)`, `FuncCall(String
name, List<AnalyzedExpr> args)`; enums `BinaryOperator` and `UnaryOperator{NOT}`;
`UnsupportedAnalyzedNodeException(Class<?> astNodeClass)` — the `Class`-arg constructor the
Plan of Work's `UnsupportedAnalyzedNodeException(node.getClass())` calls. The fold reads only
`SQLMathExpression.{childExpressions, operators}` and `Operator.getPriority()`; no arithmetic
value crosses into it.
**Proposed fix**: None — the assumption holds. Recorded as a survived challenge that
strengthens the Track-2-independence rationale. (The A1/A2 operator-coverage gaps are the *one*
place where the IR-vs-AST operator-set mismatch bites, and they live in the structural fold and
comparison dispatch, not in any value semantics — so Track 2 stays correctly out of scope.)

### A4 [suggestion]
**Certificate**: Assumption AS3 (the `mathExpression` field's static type is enough to dispatch)
**Target**: Assumption — Plan of Work step 1 / D14 field-walk
**Challenge**: The `mathExpression` field is typed `SQLMathExpression`, which is **polymorphic**:
PSI shows four direct subtypes — `SQLFirstLevelExpression`, `SQLParenthesisExpression`,
`SQLCaseExpression`, `SQLBaseExpression`. The Plan of Work names `SQLBaseExpression` (leaf,
step 2), `SQLParenthesisExpression` (paren, step 3), the flat n-ary `SQLMathExpression` itself
(fold, step 4), and `SQLCaseExpression` (throw, D10). `SQLFirstLevelExpression` is unnamed. It
is almost certainly collapsed by the grammar's `unwrapIfNeeded()`, and even if reached it hits
the throw-default and degrades to a clean throw, not a wrong value — so this is a suggestion, not
a blocker. But the instanceof dispatch order on the `mathExpression` subtype is itself a small
mechanism the steps should pin, so a reader knows the lowerer branches on concrete subtype rather
than reading a discriminator field.
**Evidence**: PSI `DirectClassInheritorsSearch(SQLMathExpression)` → `{SQLFirstLevelExpression,
SQLParenthesisExpression, SQLCaseExpression, SQLBaseExpression}`. `SQLBaseExpression extends
SQLMathExpression`; `SQLParenthesisExpression extends SQLMathExpression` — so the `mathExpression`
field can hold any of these at runtime and the walk must `instanceof`-dispatch.
**Proposed fix**: In step 1 (or the new step from A2/A1), state the `mathExpression` dispatch as
an explicit `instanceof` cascade over the concrete subtypes, with the exhaustive-or-throw default
covering `SQLFirstLevelExpression` and any future subtype. One sentence; keeps the field-walk's
D14 completeness claim honest against the real polymorphic shape.

## Evidence base

#### Challenge C1: D12 — structural precedence fold reproduces the AST's `<=` left-associative reduction
- **Chosen approach**: The lowerer reruns the AST's precedence-climbing reduction structurally,
  keyed on `Operator.getPriority()` with `<=` left-associative pop, to build a nested `BinaryOp`
  tree; value semantics stay in `NumericOps`.
- **Best rejected alternative**: A generic shared fold parameterized by a combiner lambda (one
  fold, two combiners). Already vetted and rejected at the research-log gate for the bimorphic
  call-site / JIT-inlining reason.
- **Counterargument trace**:
  1. The track claims reproducing the AST nesting is "a textbook precedence-climbing reduction
     (low risk)." The actual AST algorithm (`iterateOnPriorities`, PSI-dumped body) is **not** a
     textbook left-to-right precedence climb: it scans operators right-to-left via `removeLast()`,
     pushes onto an operator stack, and pops when `operatorsStack.peek().getPriority() <=
     nextOperator.getPriority()`. The `<=` (not `<`) is what makes equal-priority operators
     left-associative (`a - b - c` → `(a - b) - c`).
  2. A naive structural copy that climbs left-to-right, or uses `<` instead of `<=`, would build
     right-associative trees for same-priority chains and diverge on `a - b - c` and `a / b / c`.
  3. Outcome: I1 round-trip parity fails on same-priority left-assoc chains. The track *does* name
     this risk (line 326, the `<=` invariant) and Track 4's matrix pins it — so the decision holds.
- **Codebase evidence**: `SQLMathExpression.iterateOnPriorities` body (PSI-dumped): the `<=`
  comparison and the right-to-left `removeLast()` scan.
- **Survival test**: **SURVIVES** — the track explicitly carries the `<=` left-assoc invariant and
  defers value-parity to Track 4's matrix. The decision is sound; the realization detail (match the
  exact stack discipline, not a generic climb) is implicit in "matching the AST's
  `iterateOnPriorities`" and is adequately pinned by the Track-4 matrix. No finding raised beyond
  noting the realization is subtler than "textbook."

#### Violation V1: I2 (no silent fallback) — unmapped AST math operator in the fold
- **Invariant claim**: Lowering an unsupported AST shape throws `UnsupportedAnalyzedNodeException`;
  it never returns a placeholder or partial tree (I2).
- **Violation construction**:
  1. Start state: a parsed `SQLExpression` for `a % b` (or `a << b`, `a & b`, `a ?? b`).
  2. Action sequence: the field-walk (step 1) sees the in-subset `mathExpression` field non-null
     and descends (no throw — `mathExpression` is recognized). The fold (step 4) reads
     `operators = [REM]` (PSI: `REM` is a real `Operator` constant, priority 10).
  3. Intermediate state: the fold must produce `BinaryOp(<IR-op>, lower(a), lower(b))`, but there
     is no `BinaryOperator` constant for `REM`.
  4. Violation point: Plan of Work step 4 (`track-3.md:240`) states no throw for unmapped
     operators. A literal implementation either dereferences a missing map entry (NPE — not the
     contracted exception) or, if the mapping uses `valueOf`/name matching, silently mis-maps.
  5. Observable consequence: I2 broken — either a raw NPE instead of `UnsupportedAnalyzedNodeException`,
     or a wrong/partial tree for `%`/shift/bitwise/`??` inputs.
- **Feasibility**: **CONSTRUCTIBLE** — `REM` and the seven others are live enum constants on the
  AST math node; `a % b` is ordinary YQL the grammar produces as a flat math node. The only thing
  standing between this and a real bug is whether the implementer *adds* the throw the track text
  omits. Mitigation is cheap (see A1).

#### Assumption AS1: comparison lowering is a decomposed mechanism
- **Claim**: The Plan of Work's six steps cover every in-subset shape Track 3 must lower.
- **Stress scenario**: Lower `name = 'Foo'` (a top-level comparison). It arrives as
  `SQLExpression.booleanExpression` → `SQLBinaryCondition{left, operator: SQLEqualsOperator, right}`.
  Producing `BinaryOp(EQ, Var(name), Const('Foo'))` requires: dispatching the `booleanExpression`
  field over ~19 `SQLBooleanExpression` subtypes, recognizing `SQLBinaryCondition`, and mapping
  `SQLEqualsOperator` → IR `EQ`.
- **Code evidence**: PSI — `SQLBooleanExpression` has 19 direct subtypes; `SQLBinaryCompareOperator`
  has 15 concrete subtypes; only 7 classes (6 distinct IR ops, with two `!=` spellings) map. None of
  this dispatch appears in steps 1-6; step 5 is `NOT`-only. The Validation section lists "comparison"
  as a coverage case, confirming intent, but no step describes the two dispatch tables.
- **Verdict**: **FRAGILE** — the intent is present (mermaid + Validation) but the mechanism is
  undescribed, so a step roster derived from the Plan of Work could omit it or under-handle the
  compare-operator mapping. The A2 fix promotes it to an explicit step.

#### Assumption AS2: Track-1 IR types exist as assumed; Track 2 is not a dependency
- **Claim**: Track 3 depends only on Track 1 for IR types and does not need `NumericOps`.
- **Stress scenario**: Search for any lowering operation that needs an arithmetic *value*
  (promotion, null sentinel, `Date + Long`) rather than just tree structure.
- **Code evidence**: PSI — all five record variants, both operator enums, and the `Class`-arg
  `UnsupportedAnalyzedNodeException` constructor exist exactly as the Plan of Work uses them. The
  fold reads only `childExpressions`, `operators`, and `getPriority()`; no value computation. The
  one IR-vs-AST mismatch (operator-set width) bites in structure (A1/A2), not in value semantics.
- **Verdict**: **HOLDS** — Track 1 outputs are real; Track 2 independence is genuine.

#### Assumption AS3: `mathExpression` dispatch is over a known closed set
- **Claim**: The field-walk and leaf descent cover the `mathExpression` subtypes.
- **Stress scenario**: Enumerate the runtime types the `SQLExpression.mathExpression` field can hold.
- **Code evidence**: PSI — `SQLMathExpression` has four direct subtypes; the track names three plus
  the `CaseExpression` throw, leaving `SQLFirstLevelExpression` unnamed. The throw-default (D14)
  covers it, so any gap degrades to a throw, never a wrong value.
- **Verdict**: **HOLDS** (degrades to a clean throw) — recorded as A4 suggestion to pin the
  instanceof dispatch order explicitly.
