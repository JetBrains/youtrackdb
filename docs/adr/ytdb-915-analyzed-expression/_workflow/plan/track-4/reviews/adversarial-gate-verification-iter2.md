<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate-verification — Track 4: Evaluator + round-trip parity (iter2)

Re-check of six ACCEPTED Phase A adversarial findings (1 was wrong-by-construction,
3 were API-shape mismatches, 2 were under-pinned test seams). All fixes landed in the
updated track file and are PSI-confirmed correct against `analyzed-expression-5p7llp6k`.
No new findings; no regressions. Overall PASS.

## Verdicts

#### Verify A1: arithmetic needs an IR→AST enum map (none exists)
- **Original issue**: arithmetic needed an IR→AST enum map to reach `NumericOps`, but only
  the AST→IR private `toArithmeticOperator` existed; calling `NumericOps.apply(Number,
  Operator, Number)` directly throws on null and skips `Date ± Long` / `String` concat.
- **Fix applied**: Plan of Work step 2 (track-4.md:303-313) names the authored four-constant
  IR→AST map and routes through `SQLMathExpression.Operator.apply(Object, Object)`, with an
  explicit "Do **not** call `NumericOps.apply(Number, Operator, Number)` directly" warning.
- **Re-check**:
  - Track-file location: Plan of Work step 2; Surprises § Arithmetic (lines 36-39); Decision
    Log via the precedence-fold reuse.
  - Current state (PSI): `SQLMathExpression.Operator.apply(Object, Object)` is `@Nullable
    public`, returns `Object` — exists. `NumericOps.apply(Number, Operator, Number)` is
    `public static` returning `Number` — exists and is the direct-throw path the step warns
    against. The object-level seams `plusObject`/`minusObject`/`applyObject(Operator, Object,
    Object)` are present. Exactly one `toArithmeticOperator` method exists project-wide —
    `AnalyzedExprLowerer#toArithmeticOperator(Operator) -> BinaryOperator`, `private static`,
    AST→IR direction (Operator in, BinaryOperator out) — so no reusable IR→AST inverse map
    exists; the track must author the four-constant map as stated.
  - Criteria met: route is constructible, the dangerous direct call is fenced off, the
    "author the map" claim is grounded (no pre-existing inverse map).
- **Regression check**: checked the arithmetic Plan-of-Work text against the D5 NumericOps
  contract from Track 2 — `Operator.apply(Object, Object)` is the same entry the AST uses, so
  no AST/IR drift introduced. Clean.
- **Verdict**: VERIFIED

#### Verify A2: "same operator object the AST holds" is not constructible
- **Original issue**: the IR holds no parse node, so the evaluator cannot reuse "the same
  operator object the AST holds"; it must build a fresh instance.
- **Fix applied**: Plan of Work step 3 (lines 314-326), Decision Log D11 rationale (lines
  87-113), and the Context-and-Orientation mermaid Note (line 284) now say "a fresh instance
  of the same operator class" via `new SQLXxxOperator(-1)`; the superseded `design.md`
  passages (≈679/712/714) are recorded in Surprises as a Phase-4 reconciliation item
  (lines 53-55).
- **Re-check**:
  - Track-file location: step 3, D11, mermaid Note, Surprises § Comparison + § Phase-4
    reconciliation.
  - Current state (PSI): `INSTANCE` is present on `SQLEqualsOperator`, `SQLLtOperator`,
    `SQLLeOperator`, `SQLGtOperator` but **absent** on `SQLNeOperator`, `SQLNeqOperator`,
    `SQLGeOperator` — exactly the three the track names as forcing the constructor route. A
    public `(int)` constructor exists on all six operator classes (e.g. `SQLEqualsOperator:
    ctor(int) public`), so `new SQLXxxOperator(-1)` compiles. The track text uses "a fresh
    instance of the same operator class," not the impossible "same operator object."
  - Criteria met: the reconstruction route is real and compiles; the asymmetric-`INSTANCE`
    justification for not using a uniform accessor is PSI-true; the frozen-design wording
    correction is parked as a Phase-4 item rather than silently dropped.
- **Regression check**: checked D11/D15/Interfaces for the residual stale phrase. The
  Interfaces "Reuses" bullet (line 410) still says "the same operator object the AST holds" in
  the reuse list — but the operative Plan of Work, D11 rationale, and mermaid Note are all
  corrected, and Surprises records the reconciliation. The residual phrase is a non-operative
  summary line, not the build instruction; flagging as observation only, below should-fix
  (does not change what the implementer builds). Clean otherwise.
- **Verdict**: VERIFIED

#### Verify A3: collate chain type-inaccurate
- **Original issue**: the collate chain was given off the `Entity` interface
  (`getImmutableSchemaClass`), and collate read off `Entity.getProperty` (the value) rather
  than `SchemaClass.getProperty`.
- **Fix applied**: Plan of Work step 5 (lines 328-334) and D6-R's code block (lines 198-207)
  use the guarded `EntityImpl` downcast, `getImmutableSchemaClass(session)`, and collate off
  `SchemaClass.getProperty(name).getCollate()`, with inline comments marking why each hop is
  on `EntityImpl`/`SchemaClass` and not the `Entity` interface; `design.md` (≈717/732)
  recorded in Surprises (lines 56-57) as a Phase-4 reconciliation item.
- **Re-check**:
  - Track-file location: step 5, D6-R code block + Risks/Caveats, Surprises § Collate +
    § Phase-4 reconciliation, mermaid Note (line 281).
  - Current state (PSI): `getImmutableSchemaClass` is declared **only** on `EntityImpl` (a
    class, `interface=false`); the YouTrackDB `Entity` interface does not declare it (own or
    inherited). `SchemaClass.getProperty(String) -> SchemaProperty` exists on the `SchemaClass`
    interface (returns the schema property, not the value). The mirror source
    `SQLSuffixIdentifier.getCollate(Result, CommandContext)` exists. The track's downcast and
    `SchemaClass.getProperty` read are type-accurate.
  - Criteria met: every hop in the D6-R block resolves to a real symbol on the right type; the
    guard-on-`isEntity` / null-on-miss parity rationale stands.
- **Regression check**: checked D6-R Risks/Caveats and the § Validation matrix row pinning the
  schemaless/absent-column null path (line 382, tagged T1/A3) — the guarded form is exercised
  by a test row, so the fix is not unverified prose. Clean.
- **Verdict**: VERIFIED

#### Verify A4: operand cross-type coercion seam under-pinned
- **Original issue**: the matrix had no row exercising a column-vs-literal cross-type coercion
  through `visitVar`/`visitConst` value-type fidelity against the AST's `left.execute`/
  `right.execute`.
- **Fix applied**: a `longCol != 1` (Long column vs Integer literal) matrix row was added.
- **Re-check**:
  - Track-file location: § Validation and Acceptance matrix (line 381).
  - Current state: the row `| longCol != 1 (Long column vs Integer literal) | Operand
    cross-type coercion: visitVar/visitConst value-type fidelity vs the AST's left.execute/
    right.execute (A4) |` is present and tagged `(A4)`.
  - Criteria met: the coercion seam is now pinned by a concrete matrix row; it complements the
    existing "type-coercing `!=`" NE/EQ session-difference row (line 380) by adding the
    Long-vs-Integer operand-type axis.
- **Regression check**: the row is `Objects.equals`-checkable under the I1 oracle like every
  other matrix row; no harness change implied. Clean.
- **Verdict**: VERIFIED

#### Verify A5: S1+ Identifiable adapter is unexercised dead code in S0
- **Original issue**: the `Identifiable`→synthetic-`Result` adapter (S1+ seam) would ship
  untested in S0, leaving the D15 collation-convergence behavior unexercised and the coverage
  gate uncovered.
- **Fix applied**: Plan of Work step 6 now requires the adapter to carry its own focused unit
  test (option (b): keep + test).
- **Re-check**:
  - Track-file location: Plan of Work step 6 (lines 335-339).
  - Current state: step 6 reads "the adapter carries its own focused unit test (wrap an
    `Identifiable`, evaluate a `ci`-collated comparison, assert the convergence) so the
    coverage gate covers it and the D15 behavior change is exercised rather than shipping
    unverified." The Interfaces "In scope" list (line 405) keeps the adapter as a deliverable.
  - Criteria met: dead-code-in-S0 concern resolved by a named, behavior-asserting test that
    exercises the exact D15 convergence (ci-collation now applying on the `Identifiable` path).
- **Regression check**: checked D3/D15 — the adapter and its convergence test are consistent
  with the single-`Result`-overload decision (D3) and the recorded S1+ obligation (D15); no
  scope creep into the S1/S7 cross-caller validation, which stays out of scope (line 418).
  Clean.
- **Verdict**: VERIFIED

#### Verify A6: round-trip suite must reach package-visible lowerBoolean; visitFuncCall unpinned
- **Original issue**: the suite needed to call the package-visible `lowerBoolean` for the
  comparison/`NOT` rows (the suite location was unstated), and `visitFuncCall` had no matrix
  row pinning the method-call path.
- **Fix applied**: step 7 (lines 340-346) states the suite lives in
  `core/.../query/analyzed/` so it can call package-visible `lowerBoolean`; a FuncCall matrix
  row was added with a recorded-throw fallback when no FuncCall fragment is in the S0 subset.
- **Re-check**:
  - Track-file location: step 7, Surprises § visitFuncCall (lines 45-46), § Validation matrix
    (line 384), step 1 visitFuncCall detail (lines 298-302).
  - Current state (PSI): `AnalyzedExprLowerer.lowerBoolean(SQLBooleanExpression)` is
    package-visible (`static`, no access modifier — confirmed not `public`/`private`), while
    `lower` is `public static` and `lowerComparison` is `private static` — matching the
    track's "`lower` is public, `lowerComparison` private" claim, so a same-package test is the
    correct placement to reach `lowerBoolean`. `SQLEngine.getMethod(String) -> SQLMethod` is
    `public static`; `SQLMethod.execute(Object, Result, CommandContext, Object, Object[])`
    exists — the `visitFuncCall` sequence is constructible. The matrix row `a covered
    method-call fragment (e.g. name.asInteger())` with the "if no such fragment is in the S0
    subset, visitFuncCall becomes a recorded throw" fallback is present and tagged R2/A6.
  - Criteria met: suite placement is correct for package-visibility, and the FuncCall path is
    both pinned by a matrix row and given a defined fallback.
- **Regression check**: checked the Track 3 contract carried in the plan Checklist
  (`lowerBoolean` package-visible for boolean round-trips) — consistent with what Track 3
  shipped; the suite's same-package placement does not require widening `lowerBoolean`'s
  visibility. Clean.
- **Verdict**: VERIFIED

## Findings

(No new findings.)

## Summary

PASS. All six ACCEPTED adversarial findings (A1–A6) are VERIFIED: every fix landed in the
updated `track-4.md` and is PSI-confirmed correct against the live codebase. No regressions
surfaced. One sub-should-fix observation (the Interfaces "Reuses" bullet at line 410 still
carries the superseded "the same operator object the AST holds" phrase that A2 corrected
elsewhere) is recorded for awareness only — it is a non-operative summary line, the build
instructions (Plan of Work, D11, mermaid Note) are all corrected, and Surprises logs the
reconciliation — so it does not gate the verdict.
