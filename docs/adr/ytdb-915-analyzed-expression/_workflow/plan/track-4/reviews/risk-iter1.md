<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: R1, sev: should-fix, loc: "track-4.md:251-270 (Plan of Work steps 1-2); NumericOps.java:251", anchor: "### R1 ", cert: "Exposure: arithmetic delegation; Assumption: NumericOps.apply(Number,BinaryOperator,Number)", basis: "design class-diagram signature does not exist; naive NumericOps.apply route drops null/Date+Long/String-concat parity"}
  - {id: R2, sev: should-fix, loc: "track-4.md:254-258 (step 1, visitFuncCall); track-4.md:297-311 (matrix)", anchor: "### R2 ", cert: "Testability: visitFuncCall round-trip parity", basis: "FuncCall path is required by I3 but has no matrix row; untested method-call parity + coverage gap on changed lines"}
  - {id: R3, sev: should-fix, loc: "track-4.md:263-269 (step 3); BinaryOperator.java:11", anchor: "### R3 ", cert: "Assumption: same SQLBinaryCompareOperator instance the AST holds", basis: "IR carries only the BinaryOperator enum; operator instance discarded at lowering, must be reconstructed from the enum"}
  - {id: R4, sev: suggestion, loc: "track-4.md:263-269 (step 3); operator INSTANCE PSI scan", anchor: "### R4 ", cert: "Assumption: operator instance reconstruction is uniform", basis: "INSTANCE exists on EQ/LT/LE/GT only; NE/NEQ/GE have none — uniform INSTANCE use breaks at compile time"}
  - {id: R5, sev: suggestion, loc: "track-4.md:271-276 (steps 5-6); SQLSuffixIdentifier.getCollate", anchor: "### R5 ", cert: "Exposure: collate-resolution helper; Assumption: result.asEntity() chain", basis: "getImmutableSchemaClass lives on EntityImpl not the Entity interface; helper must cast + isEntity-guard or it NPEs/CCEs"}
  - {id: R6, sev: suggestion, loc: "track-4.md:106-117, 343-349 (D15 framing)", anchor: "### R6 ", cert: "Assumption: ~12 evaluate(Identifiable) production callers", basis: "PSI: 0 direct callers of SQLBinaryCondition.evaluate(Identifiable); the 12 are on the base abstract method — framing nuance, not S0 risk"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 10}
cert_index: []
flags: [CONTRACT_OK]
-->

# Track 4 risk review (iteration 1)

Track 4 is greenfield, slow-path-only, and gated solely by round-trip parity against
the AST oracle — there is no live executor consumer and no storage / WAL / transaction /
index / cache surface, so the blast radius of any bug is confined to the new evaluator
class and its own test suite. No blocker. The risks are integration-shape mismatches
between the frozen design text and the actual Track 1-3 deliverables (the arithmetic
delegation target, the discarded operator instance) plus one testability gap
(`visitFuncCall` has no parity row). All findings are decomposition-time mitigations the
step roster can absorb, not re-plans.

## Findings

### R1 [should-fix]
**Certificate**: Exposure — arithmetic delegation to NumericOps; Assumption — `NumericOps.apply(Number, BinaryOperator, Number)`
**Location**: `track-4.md:251-270` (Plan of Work steps 1-2), `design.md:138-141` (class diagram), `NumericOps.java:251`
**Issue**: The design class diagram and the Plan of Work both say the evaluator "delegates
to the shared `NumericOps`" for `+ - * /`. Two concrete shapes make a naive reading of that
instruction break parity:

1. The signature the design class diagram names — `NumericOps.apply(Number, BinaryOperator, Number)`
   — does not exist. The PSI-confirmed `NumericOps` surface takes the AST's
   `SQLMathExpression.Operator` (not the IR `BinaryOperator`) and operates over `Number`
   (not `Object`): `apply(Number, Operator, Number) -> Number`. So the evaluator must first
   map the IR `BinaryOperator` (`PLUS/MINUS/STAR/SLASH`) to the AST `Operator`.
2. The parity-faithful entry is **not** `NumericOps.apply(Number, Operator, Number)`. That
   widening entry throws `IllegalArgumentException` on a null operand and handles only
   `Number` pairs — it skips null propagation (`null + x = x`), `Date ± Long`, and `String`
   concatenation. Those semantics live in the per-operator object-level bodies
   (`NumericOps.plusObject` / `minusObject` / `applyObject`), which the AST reaches through
   `SQLMathExpression.Operator.apply(Object, Object)` (confirmed: `Operator.PLUS.apply(Object,Object)`
   → `NumericOps.plusObject`, `STAR` → guarded `applyObject`). The track's own § Validation
   matrix names null-propagation and a `Date + Long` row as parity obligations, so a direct
   `NumericOps.apply(Number,Operator,Number)` call would fail exactly those rows.

The parity-correct route is to call the AST `Operator.apply(Object, Object)` instance method
(after mapping the IR enum to the AST `Operator`), which itself delegates to `NumericOps` and
carries the null / `Date+Long` / `String`-concat behavior for free. Likelihood of the bug if
unmitigated: medium-high — "delegate to NumericOps" reads as "call a NumericOps method," and
the static surface is the wrong door. Impact: the null and `Date+Long` parity rows go red.
**Proposed fix**: In the step roster, make step 2 (Arithmetic) explicit: the evaluator maps
the IR `BinaryOperator` arithmetic constant to `SQLMathExpression.Operator` and calls that
constant's `apply(Object, Object)` (the object-level entry that routes through
`NumericOps.plusObject`/`minusObject`/`applyObject`), **not** `NumericOps.apply(Number, Operator, Number)`
directly. Carry a null-propagation row and the `Date + Long` row in the suite (the track already
flags both as required). Flag the `design.md:138-141` class-diagram signature
(`apply(Number, BinaryOperator, Number)`) as a Phase-4 `design-final` reconciliation note —
it is design-frozen, so do not edit it now, but the step that touches arithmetic should not
treat it as the literal API.

### R2 [should-fix]
**Certificate**: Testability — `visitFuncCall` round-trip parity
**Location**: `track-4.md:254-258` (step 1, `visitFuncCall`), `track-4.md:297-311` and `design.md:848-858` (the test matrix)
**Issue**: `visitFuncCall` is a mandatory visitor method — invariant I3 forces the evaluator
to implement all five `visitX`, and the Track 3 lowerer genuinely produces `FuncCall` for
method-call coercions (`name.asInteger()`), confirmed in `AnalyzedExprLowerer.lowerWithOptionalModifier`.
Yet the § Validation matrix has **no** `FuncCall` / method-call row — every row pins
precedence, parenthesis, collation, or the EQ/NE session difference. Two consequences:

1. Coverage: `visitFuncCall` is changed code with a real branch (arg-zero target plus
   parameter args). With no fragment exercising it, the 85% line / 70% branch gate on the
   evaluator class is at risk on that method, and an empty/throwing `visitFuncCall` could ship
   green against the listed matrix.
2. Parity correctness: reproducing the AST's method-call result is non-trivial. The AST
   resolves a method via `SQLEngine.getMethod(name)` and runs `SQLMethod.execute(target, args, ctx, …)`
   against the target value (`SQLMethodCall.execute`). The IR `FuncCall` carries only the
   method **name** (a `String`) and the lowered args (arg[0] is the base). The evaluator must
   evaluate arg[0] as the target, look up the method by name, evaluate the rest as params, and
   call `SQLMethod.execute`. The step-1 phrasing "evaluates the wrapped method call … coercing
   the result to the column type" under-specifies this and the "coercing to the column type"
   clause is misleading — the method call (`.asInteger()`) *is* the coercion; there is no
   separate column-type coercion step in the AST path.

Likelihood: medium — exhaustiveness forces the method to exist, so the implementer will write
*something*, but without a parity row there is no oracle to catch a wrong implementation.
Impact: an untested coercion path is the kind of latent S1 parity bug the round-trip suite
exists to prevent.
**Proposed fix**: Add a `FuncCall` row to the suite — e.g. a method-call coercion fragment
(`name.asInteger()` against a string column, or `n.asLong()`) asserting
`lower(parse).evaluate` equals `parse.execute`. In the step roster, replace the step-1
`visitFuncCall` one-liner with the explicit AST-parity sequence (evaluate arg[0] as target →
`SQLEngine.getMethod(name)` → evaluate remaining args → `SQLMethod.execute`), and drop the
"coercing the result to the column type" phrasing or restate it as "the method call performs
its own coercion."

### R3 [should-fix]
**Certificate**: Assumption — "delegate to the parser's own `SQLBinaryCompareOperator` instance — the same operator object the AST holds"
**Location**: `track-4.md:263-269` (step 3), `track-4.md:268` and `design.md:680-681`, `BinaryOperator.java:11`
**Issue**: D11 and the step-3 plan repeatedly say the evaluator delegates to "the same operator
object the AST holds." That is no longer literally available. The Track 1 IR `BinaryOp` carries
only the IR's own `BinaryOperator` enum constant (`EQ/NE/LT/LE/GT/GE`), and the Track 3 lowerer
(`AnalyzedExprLowerer.toComparisonOperator`) maps the AST `SQLBinaryCompareOperator` to that enum
constant and **discards the operator instance** — no operator reference is carried on the IR node.
So the evaluator cannot reuse "the same object"; it must reconstruct an `SQLBinaryCompareOperator`
instance from the `BinaryOperator` enum constant (`EQ → SQLEqualsOperator`, `NE → SQLNeOperator`,
`LT → SQLLtOperator`, …). The operators are stateless (PSI-confirmed: no comparison-affecting
instance fields; `copy()` returns `this`/`INSTANCE`), so a reconstructed instance is behaviorally
identical and parity holds — but the plan text papers over the reconstruction step, and the EQ/NE
session-threading nuance the whole of D11 turns on depends on dispatching to the *right* concrete
class, which only the reconstruction makes happen. Likelihood: low that it produces a wrong answer
(operators are stateless), high that the step is written against a contract that no longer holds.
**Proposed fix**: In the step-3 roster entry, state the reconstruction explicitly: the evaluator
maps the IR `BinaryOperator` comparison constant to a freshly built (or `INSTANCE`-shared, see R4)
`SQLBinaryCompareOperator` of the matching concrete class, then calls `execute(session, left, right)`.
Note in the step that the operators are stateless so a reconstructed instance preserves the EQ/NE
session difference by class identity. (This is a Track-4-internal mechanism, not a design change;
D11's intent is preserved.)

### R4 [suggestion]
**Certificate**: Assumption — operator-instance reconstruction can use a uniform `INSTANCE` accessor
**Location**: `track-4.md:263-269` (step 3); PSI operator-class scan
**Issue**: A natural way to reconstruct the operator (per R3) is to reach for a shared
`INSTANCE` static. PSI shows `INSTANCE` is **not** uniform across the six S0 comparison operator
classes: `SQLEqualsOperator`, `SQLLtOperator`, `SQLLeOperator`, `SQLGtOperator` declare a static
`INSTANCE`; `SQLNeOperator`, `SQLNeqOperator`, `SQLGeOperator` do **not**. An implementer who
writes `SQLNeOperator.INSTANCE` will not compile, and one who writes a uniform mapping table keyed
on `INSTANCE` will hit three missing fields. Likelihood: low-medium (a compile error, caught fast),
impact: trivial (a few minutes), so suggestion severity — but worth pre-empting in the step note.
**Proposed fix**: In the step-3 roster entry, instruct the evaluator to construct operators via the
public `new SQLXxxOperator(-1)` constructor (present on all six, PSI-confirmed) rather than a
shared `INSTANCE` field, since `INSTANCE` is absent on `SQLNeOperator`/`SQLNeqOperator`/`SQLGeOperator`.

### R5 [suggestion]
**Certificate**: Exposure — collate-resolution helper trace; Assumption — `result.asEntity()` exposes `getImmutableSchemaClass`
**Location**: `track-4.md:271-276` (steps 5-6), `design.md:730-734`, reference `SQLSuffixIdentifier.getCollate`
**Issue**: The collate helper (step 5) replicates `result.asEntity()` →
`getImmutableSchemaClass(session)` → `getProperty(name)` → `getCollate()`. PSI shows
`getImmutableSchemaClass(DatabaseSessionEmbedded)` is declared on `EntityImpl` /
`EntityHookAbstract`, **not** on the public `Entity` interface that `Result.asEntity()` returns.
The reference AST code (`SQLSuffixIdentifier.getCollate`) guards with `currentResult.isEntity()`
and casts: `(EntityImpl) currentResult.asEntity()`. A helper that calls `asEntity()` and then
`getImmutableSchemaClass` directly will not compile (method not on `Entity`); one that casts
without the `isEntity()` / null guard risks a `ClassCastException` or NPE when the `Result` is not
entity-backed (a row built from a projection rather than a stored record). Likelihood: low (the
reference is right there to copy), impact: a compile error or a narrow NPE on non-entity rows.
**Proposed fix**: In step 5, instruct the helper to mirror `SQLSuffixIdentifier.getCollate` exactly:
guard on `result.isEntity()`, cast `asEntity()` to the internal entity type that exposes
`getImmutableSchemaClass(session)`, null-check the schema class and the property, and return `null`
on any miss (and for any non-`Var` operand, per D6-R). Cite `SQLSuffixIdentifier.getCollate` as the
reference in the step.

### R6 [suggestion]
**Certificate**: Assumption — "`evaluate(Identifiable)` has ~12 production callers"
**Location**: `track-4.md:106-117` (D15 Risks/Caveats), `track-4.md:343-349` (Relevant shapes)
**Issue**: D15 states `evaluate(Identifiable)` "has ~12 production callers, including
`SQLWhereClause` and `SecurityEngine`." PSI `ReferencesSearch` shows the concrete
`SQLBinaryCondition.evaluate(Identifiable, CommandContext)` has **0** direct callers; the 12
callers are on the **base abstract** `SQLBooleanExpression.evaluate(Identifiable, CommandContext)`
(PSI: refCount 12), reached polymorphically. The "~12" number is therefore correct but attached to
the base dispatch surface, not the concrete override — the D15 claim is sound, just imprecise about
which method the count belongs to. This changes nothing for S0: D15 is explicitly an S1/S7
forward-validation obligation, and S0's round-trip suite exercises only the `Result` overload, so
the `Identifiable`-path convergence is not Track 4 work. Recording it so the S1/S7 implementer who
re-verifies the caller set searches the base method, not the override (where they would find zero
and wrongly conclude the convergence is dead).
**Proposed fix**: No S0 action. When this track records the convergence (per the "Implemented in"
note), annotate that the ~12 callers are on the base `SQLBooleanExpression.evaluate(Identifiable)`
dispatch surface so the S1/S7 (YTDB-916 / YTDB-922) validation searches the right method. Optionally
soften "Relevant shapes" to say the dual overloads sit on the base type.

## Evidence base

#### Exposure: Track 4 touches no critical system path (storage / WAL / tx / index / cache)
- **Track claim**: Track 4 adds a new `AnalyzedExprEvaluator` and a round-trip parity test suite;
  it ships behind no flag with no live executor consumer (design Overview, track Purpose).
- **Critical path trace**: Entry `AnalyzedExprEvaluator.evaluate(AnalyzedExpr, Result, CommandContext)`
  (new, `core/.../query/analyzed/`) → `AnalyzedExpr.dispatch` (Track 1) → `visitX` →
  `SQLMathExpression.Operator.apply` / `SQLBinaryCompareOperator.execute` (existing AST, reused
  read-only). No write reaches storage, WAL, the page cache, the index engine, or a transaction —
  the evaluator reads a `Result` row and returns a value.
- **Blast radius**: confined to the new evaluator class and its own test suite. No existing test is
  modified (design acceptance: "with no existing test changed"). PSI: `SQLBinaryCondition.evaluate(Result,…)`
  has 1 direct caller and the IR package is greenfield, so a bug cannot regress a production query path
  in S0.
- **Existing safeguards**: the AST is the parity oracle (I1); `DbTestBase` (in-memory) provides full
  schema/session/Result infrastructure to assert parity; the lowerer (Track 3) is already pinned by
  46/46 tests.
- **Residual risk**: LOW. No durable or irreversible state; a wrong evaluator value fails its own test,
  nothing else.

#### Exposure: arithmetic delegation to the shared NumericOps
- **Track claim**: `visitBinaryOp` for `+ - * /` "delegates to the shared `NumericOps` (Track 2)"
  (track step 2); design class diagram lists `NumericOps.apply(Number, BinaryOperator, Number) -> Number`.
- **Critical path trace**: faithful AST path is `SQLMathExpression.Operator.<CONST>.apply(Object, Object)`
  → (PLUS) `NumericOps.plusObject` / (MINUS) `NumericOps.minusObject` / (typed) guarded
  `NumericOps.applyObject(Operator, Object, Object)` → `NumericOps.apply(Number, Operator, Number)`
  widening entry (`NumericOps.java:251`). The widening entry throws `IllegalArgumentException` on null
  and handles only `Number` pairs.
- **Blast radius**: the null-propagation rows and the `Date + Long` row of the parity matrix (both
  named obligations) if the evaluator calls the widening entry directly instead of the object-level
  `Operator.apply(Object,Object)`.
- **Existing safeguards**: `Operator.apply(Object, Object)` is public (confirmed in
  `MathExpressionTest`/`SQLMathExpression`); `NumericOpsTest` (24 tests) pins the promotion semantics
  on the AST side, so the shared engine itself is trusted.
- **Residual risk**: MEDIUM — produces R1; the design class-diagram signature does not exist, and the
  obvious static entry skips null/Date/String semantics.

#### Assumption: NumericOps exposes `apply(Number, BinaryOperator, Number)`
- **Track claim**: design class diagram, `design.md:141`.
- **Evidence search**: PSI `findClass(NumericOps)` + method enumeration (steroid_execute_code).
- **Code evidence**: `NumericOps` methods are `apply(Operator, Integer, Integer)`,
  `apply(Operator, Long, Long)`, …, `apply(Number, Operator, Number)`, plus
  `applyObject(Operator, Object, Object)`, `plusObject/minusObject/xorObject/bitOrObject/nullCoalescingObject(Object, Object)`.
  No overload takes the IR `BinaryOperator`; none takes the `(Number, BinaryOperator, Number)` shape.
- **Verdict**: CONTRADICTED
- **Detail**: the evaluator must map IR `BinaryOperator` → AST `Operator` and route through the
  object-level entry. Produces R1.

#### Assumption: the evaluator delegates to "the same `SQLBinaryCompareOperator` instance the AST holds"
- **Track claim**: D11 (`track-4.md:268`, `design.md:680-681`), step 3.
- **Evidence search**: Read `AnalyzedExprLowerer.toComparisonOperator` + `BinaryOperator` enum + `BinaryOp` record.
- **Code evidence**: `toComparisonOperator(SQLBinaryCompareOperator) -> BinaryOperator` maps to the
  IR enum and returns; the operator instance is not stored. `BinaryOp(BinaryOperator op, …)` carries
  only the enum. The IR holds no parse-node reference (D6 / Track 1 convention).
- **Verdict**: CONTRADICTED (literally) / VALIDATED (in spirit)
- **Detail**: the evaluator must reconstruct an operator instance from the enum constant; operators are
  stateless so behavior is preserved. Produces R3.

#### Assumption: operator instances can be reached uniformly via a static INSTANCE
- **Track claim**: implicit in "the parser's own instance" framing (step 3).
- **Evidence search**: PSI field enumeration over the six/seven S0 comparison operator classes.
- **Code evidence**: `INSTANCE` static present on `SQLEqualsOperator`, `SQLLtOperator`, `SQLLeOperator`,
  `SQLGtOperator`; absent on `SQLNeOperator`, `SQLNeqOperator`, `SQLGeOperator`. All seven have public
  `(int)` and `(YouTrackDBSql,int)` constructors.
- **Verdict**: UNVALIDATED
- **Detail**: a uniform `INSTANCE` mapping fails to compile on three classes; use `new SQLXxxOperator(-1)`.
  Produces R4.

#### Assumption: operators are stateless so a reconstructed instance preserves the EQ/NE nuance
- **Track claim**: D11 — EQ passes the real session, NE passes `null` (different concrete classes).
- **Evidence search**: Read `SQLEqualsOperator.execute`, `SQLNeOperator.execute`; PSI instance-field scan.
- **Code evidence**: `SQLEqualsOperator.execute` → `QueryOperatorEquals.equals(session, l, r)`;
  `SQLNeOperator.execute` → `!QueryOperatorEquals.equals(null, l, r)`. Only instance field across the
  set is `SQLEqualsOperator.doubleEquals` (boolean, affects `toString` only, not `execute`). `copy()`
  returns `this`/`INSTANCE`.
- **Verdict**: VALIDATED
- **Detail**: the EQ/NE session difference is encoded by class identity, not instance state, so a freshly
  constructed operator of the right class reproduces D11 exactly. Backs R3 (low correctness risk).

#### Assumption: the collate-resolution chain `result.asEntity().getImmutableSchemaClass(session).getProperty(name).getCollate()` is callable as written
- **Track claim**: design.md:730-734, track step 5 / D6-R.
- **Evidence search**: PSI method lookup on `Entity` / `Result` / `SchemaClass` / `SchemaProperty`; Read
  `SQLSuffixIdentifier.getCollate`.
- **Code evidence**: `Result.asEntity() -> Entity`; `getImmutableSchemaClass` is NOT on `Entity` (PSI:
  "none") — it is on `EntityImpl`/`EntityHookAbstract`. `SchemaClass.getProperty(String) -> SchemaProperty`;
  `SchemaProperty.getCollate() -> Collate` exist. `SQLSuffixIdentifier.getCollate` guards `currentResult.isEntity()`
  then casts `(EntityImpl) currentResult.asEntity()`.
- **Verdict**: UNVALIDATED (as literally written) / VALIDATED via the cast
- **Detail**: the helper must `isEntity()`-guard and cast to the internal entity type, mirroring
  `SQLSuffixIdentifier.getCollate`. Produces R5.

#### Assumption: `evaluate(Identifiable)` has ~12 production callers (D15)
- **Track claim**: `track-4.md:106-117`, `design.md:802-806`.
- **Evidence search**: PSI `ReferencesSearch` on `SQLBinaryCondition.evaluate(Identifiable,…)` and on the
  base `SQLBooleanExpression.evaluate(Identifiable,…)`.
- **Code evidence**: concrete `SQLBinaryCondition.evaluate(Identifiable,CommandContext)` refCount = 0;
  base abstract `SQLBooleanExpression.evaluate(Identifiable,CommandContext)` refCount = 12.
- **Verdict**: VALIDATED (count) / imprecise (attribution)
- **Detail**: the 12 are on the base dispatch surface, not the override. Sound for D15's S1/S7 obligation;
  produces R6 (annotate which method to re-search). No S0 impact.

#### Testability: round-trip parity suite (collation + EQ/NE rows) needs a DB-backed test base
- **Coverage target**: 85% line / 70% branch on the evaluator class.
- **Difficulty assessment**: the comparison path needs `ctx.getDatabaseSession()` returning a real
  `DatabaseSessionEmbedded` (for `operator.execute`), a schema class with a `ci`-collated property, and an
  `EntityImpl`-backed `Result` row — heavier than the Track 3 lowerer test, which parsed pure syntax with
  no DB. The EQ/NE mixed-type row needs `PropertyTypeInternal.convert` to actually run, which needs a session.
- **Existing test infrastructure**: `DbTestBase` and `BaseMemoryInternalDatabase` provide an in-memory
  `DatabaseSessionEmbedded session` with full create/schema support; `CommandContext.getDatabaseSession()`
  returns `DatabaseSessionEmbedded` (PSI-confirmed); executor-step tests already build entity-backed
  `Result` rows + `CommandContext`. The AST oracle `parse(sql).execute(row, ctx)` and the lowerer
  `parser(sql).Expression()/BinaryCondition()` helpers (Track 3 test) are reusable.
- **Feasibility**: ACHIEVABLE
- **Detail**: the suite should extend `DbTestBase` (not the parser-only pattern), define a `ci`-collated
  column for the collation row, and reuse the Track 3 parse helpers. The only real gap is the missing
  `FuncCall` row (R2) and the heavier setup — both are roster mitigations, not infeasibility.

#### Testability: `visitFuncCall` has no parity row
- **Coverage target**: 85% line / 70% branch on the evaluator class.
- **Difficulty assessment**: `visitFuncCall` is forced to exist by I3 and is genuinely reachable (the
  lowerer produces `FuncCall` for method-call coercions), but the matrix names no method-call fragment, so
  the method has no oracle and its branch may stay uncovered.
- **Existing test infrastructure**: same DB-backed base as above; `SQLEngine.getMethod` + `SQLMethod.execute`
  are the AST's own method-dispatch path the evaluator reproduces and the oracle exercises.
- **Feasibility**: ACHIEVABLE (with an added row)
- **Detail**: add a method-call coercion fragment to the suite; without it the evaluator's `visitFuncCall`
  is both a coverage hole and a parity blind spot. Produces R2.
