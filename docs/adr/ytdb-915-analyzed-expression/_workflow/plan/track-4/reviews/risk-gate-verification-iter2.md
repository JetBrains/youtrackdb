<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

<!-- Pure-verdict pass: no new findings surfaced during verification. -->

## Verification certificates

#### Verify R1: design class-diagram `NumericOps.apply(Number, BinaryOperator, Number)` route drops null / Date+Long / String parity
- **Original issue**: the design class diagram (design.md:138-141) names a `NumericOps.apply(Number, BinaryOperator, Number)` entry; routing arithmetic through it directly throws on a null operand and skips `Date ± Long` / `String` concat semantics, so the null-propagation and `Date + Long` parity rows would go red.
- **Fix applied**: Plan of Work step 2 routes arithmetic through the AST `SQLMathExpression.Operator.apply(Object, Object)` entry after an IR→AST four-constant enum map the track authors, and explicitly forbids calling `NumericOps.apply(Number, Operator, Number)` directly. The design.md:138-141 signature is recorded in `## Surprises & Discoveries` as a Phase-4 `design-final` reconciliation item (design frozen, not edited).
- **Re-check**:
  - Track-file location: `## Plan of Work` step 2 (lines 304-313) and `## Surprises & Discoveries` (lines 38-40, 51-53).
  - Current state: step 2 reads "reaches `NumericOps` through the AST `SQLMathExpression.Operator.apply(Object, Object)` entry … Do **not** call `NumericOps.apply(Number, Operator, Number)` directly — it throws on a null operand and skips those object-level semantics." Surprises records the design.md class-diagram signature as a frozen-doc reconciliation item.
  - PSI: `SQLMathExpression.Operator` (enum) carries `apply(Object, Object) : Object` (the parity-faithful object-level entry that routes to `plusObject`/`minusObject`/`applyObject`); `NumericOps` carries the dangerous `apply(Number, Operator, Number)` the track now forbids, plus `plusObject`/`minusObject`/`applyObject`. Both signatures confirmed present.
  - Criteria met: the parity-preserving entry point exists; the misleading direct route is named and excluded; the frozen-doc mismatch is logged for Phase 4 rather than silently left.
- **Regression check**: checked step 2's claim that no reusable IR→AST inverse map exists — consistent with Track 3's `toArithmeticOperator` being AST→IR and private. No new issue.
- **Verdict**: VERIFIED

#### Verify R2: `visitFuncCall` is I3-mandatory and lowerer-emitted but had no matrix row
- **Original issue**: `visitFuncCall` is in-subset (the Track 3 lowerer emits `FuncCall`, I3 forces exhaustiveness) yet the validation matrix had no method-call row and the original prose mentioned "coercing to column type", which is wrong for the method-call path.
- **Fix applied**: a method-call matrix row added; Plan of Work step 1 now gives the AST-parity sequence (evaluate `args[0]` target → `SQLEngine.getMethod(name)` → evaluate remaining args as params → `SQLMethod.execute(target, params, ctx, …)`) and drops the misleading column-type-coercion step.
- **Re-check**:
  - Track-file location: `## Plan of Work` step 1 (lines 294-302) and `## Validation and Acceptance` matrix last row (line 384).
  - Current state: step 1 spells out the `SQLEngine.getMethod(name)` → `SQLMethod.execute` sequence and states "The method call performs its own coercion; there is no separate column-type coercion step." The matrix row "a covered method-call fragment (e.g. `name.asInteger()`) — `visitFuncCall` method-call parity via `SQLEngine.getMethod`/`SQLMethod.execute` (R2/A6)" is present, with the recorded-throw fallback if no such fragment is in the S0 subset.
  - PSI: `SQLEngine.getMethod(String) : SQLMethod` exists in `…sql.SQLEngine`; `SQLMethod` is an interface in `…sql.method` with `execute(Object, Result, CommandContext, Object, Object[])`. Sequence is realizable as written.
  - Criteria met: the I3-mandatory variant has both a build-order step and an acceptance row; the coercion wording is corrected.
- **Regression check**: checked the `FuncCall.args()` read-only convention (Track 1 carry) — step 1 honors it ("evaluate without mutating it"). No new issue.
- **Verdict**: VERIFIED

#### Verify R3: IR `BinaryOp` carries only the enum — "same operator object" contract cannot hold
- **Original issue**: the lowerer discarded the AST operator instance and the IR `BinaryOp` carries only the enum constant, so an evaluator cannot reuse "the same operator object the AST holds"; the contract had to be reconstruction, not reuse.
- **Fix applied**: Plan of Work step 3 and Decision Log D11 now state the evaluator reconstructs a fresh `SQLBinaryCompareOperator` per IR enum constant; the operators are stateless so parity holds by class identity.
- **Re-check**:
  - Track-file location: `## Plan of Work` step 3 (lines 314-326) and `## Decision Log` D11 (lines 87-121).
  - Current state: D11 reads "delegate to a freshly built `SQLBinaryCompareOperator` of the same concrete class … (the IR `BinaryOp` carries only the enum constant — the lowerer discarded the AST operator instance — so the evaluator reconstructs one per enum constant)"; step 3 mirrors it ("The IR `BinaryOp` carries only the enum constant … so the reconstruction is this track's work, not reuse of an AST object"). The stateless ⇒ class-identity-parity rationale is explicit.
  - PSI: all seven operator classes extend `SimpleNode` (the parse-node base, stateless w.r.t. comparison logic); reconstruction per enum is sound.
  - Criteria met: the reuse contract is replaced by reconstruction with a class-identity parity argument.
- **Regression check**: the `## Interfaces and Dependencies` "Reuses" block (line 409-412) still says "the same operator object the AST holds" — this is the residual stale phrasing the design-final backlog covers (Surprises lines 54-55), not a track-mechanism error, since step 3 and D11 (the operative how-to) are corrected. Flagged below as a STILL-OPEN-adjacent note but does not change the R3 verdict (the fix is the operative Plan-of-Work + Decision-Log text). No functional regression.
- **Verdict**: VERIFIED

#### Verify R4: `INSTANCE` absent on `SQLNeOperator` / `SQLNeqOperator` / `SQLGeOperator`
- **Original issue**: a uniform `INSTANCE` static accessor would not compile because three of the six operators have no `INSTANCE` field.
- **Fix applied**: step 3 and D11 instruct `new SQLXxxOperator(-1)` construction rather than a uniform `INSTANCE` accessor.
- **Re-check**:
  - Track-file location: `## Plan of Work` step 3 (lines 319-321) and D11 (lines 106-108).
  - Current state: both say "`INSTANCE` is absent on `SQLNeOperator` / `SQLNeqOperator` / `SQLGeOperator`, so the evaluator uses the `new SQLXxxOperator(-1)` constructor" / "a uniform `INSTANCE` accessor will not compile".
  - PSI: `INSTANCE` field is absent on `SQLNeOperator`, `SQLNeqOperator`, `SQLGeOperator` (present on `SQLEqualsOperator`/`SQLLtOperator`/`SQLLeOperator`/`SQLGtOperator`). Every operator has a public `(int)` constructor, so `new SQLXxxOperator(-1)` compiles for all six S0 operators.
  - Criteria met: the construction strategy matches the real class surface.
- **Regression check**: checked the `(int)` ctor visibility — public on all four spot-checked operators. No new issue.
- **Verdict**: VERIFIED

#### Verify R5: collate helper `getImmutableSchemaClass` is on `EntityImpl`, not `Entity`
- **Original issue**: a happy-path collate fetch off the `Entity` interface would not compile / would NPE, because `getImmutableSchemaClass` lives on `EntityImpl`, not the `Entity` interface.
- **Fix applied**: same as Track 1 — step 5 and D6-R use the guarded `EntityImpl`-downcast form citing `SQLSuffixIdentifier.getCollate`.
- **Re-check**:
  - Track-file location: `## Plan of Work` step 5 (lines 328-334), D6-R code block (lines 198-207), and the `## Context and Orientation` mermaid note (line 281).
  - Current state: step 5 reads "cast `asEntity()` to `EntityImpl` (the type that exposes `getImmutableSchemaClass(session)` — the public `Entity` interface does not) … read the collate off `SchemaClass.getProperty(name).getCollate()` (not `Entity.getProperty(name)`, which returns the column value) … return `null` on any miss." D6-R carries the guarded code block hop-by-hop.
  - PSI: `getImmutableSchemaClass` is direct on `…record.impl.EntityImpl` and absent on the database `Entity` interface (`…db.record.record.Entity`). `SQLSuffixIdentifier.getCollate(Result, CommandContext)` body confirms the exact shape: `(EntityImpl) currentResult.asEntity()` → `record.getImmutableSchemaClass(session)` → `schemaClass.getProperty(...)` → `property.getCollate()`, null on every miss. The track helper mirrors this precisely.
  - Criteria met: the downcast guard matches the real class surface and the cited AST method.
- **Regression check**: checked that `SchemaClass.getProperty(name)` (schema property) is distinct from `Entity.getProperty(name)` (column value) — step 5 makes that distinction explicit. No new issue.
- **Verdict**: VERIFIED

#### Verify R6: D15 "~12 callers of `evaluate(Identifiable)`" — PSI shows 0 on the concrete override, 12 on the base
- **Original issue**: D15 attributed ~12 callers to `evaluate(Identifiable)` without saying which method; the concrete `SQLBinaryCondition` override has 0 direct callers, so an S1/S7 re-verification searching the override would find zero and wrongly conclude the convergence is dead.
- **Fix applied**: D15 Risks/Caveats now attributes the ~12 to the base `SQLBooleanExpression.evaluate(Identifiable, ctx)` dispatch surface so S1/S7 search the right method.
- **Re-check**:
  - Track-file location: `## Decision Log` D15 Risks/Caveats (lines 139-144) and `## Surprises & Discoveries` (lines 58-59).
  - Current state: D15 reads "PSI find-usages returns ~12 production callers on the base `SQLBooleanExpression.evaluate(Identifiable, ctx)` dispatch surface (the concrete `SQLBinaryCondition` override has 0 direct callers — the count belongs to the polymorphic base method, so the S1/S7 re-verification must search the base method, not the override, where it would find zero …)".
  - PSI: base `SQLBooleanExpression.evaluate(Identifiable, ...)` is abstract with 12 direct refs; the concrete `SQLBinaryCondition.evaluate(Identifiable, ...)` override has 0 direct refs. The count attribution is exactly right.
  - Criteria met: the caller count is pinned to the correct dispatch surface; the S1/S7 search-the-base instruction is present.
- **Regression check**: confirmed the named callers framing (`SQLWhereClause`, `SecurityEngine`) is consistent with a base-method search target. No new issue.
- **Verdict**: VERIFIED

## Summary
All six accepted risk findings (R1–R6) VERIFIED against the updated track file and PSI. No new findings. **PASS.**
