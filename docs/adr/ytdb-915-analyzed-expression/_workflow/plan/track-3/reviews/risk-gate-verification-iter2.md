<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: REJECTED}
  - {id: R4, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 3 risk-review gate verification — iteration 2

Re-check of the four risk findings from the Phase A risk review of Track 3
(lowering pass) after fixes were applied to the working-tree track file. All
fixes are track-file documentation edits (Validation, Plan of Work, Decision
Log, Surprises); no production code exists yet. PSI facts cited by the fixes
were confirmed against the live IDE index (`analyzed-expression` open, path
matches the working tree).

## Verification certificates

#### Verify R1: in-track tree-shape assertion
- **Original issue**: the precedence fold's value parity was verified only in
  Track 4's round-trip matrix, so a mis-nesting bug would surface two tracks
  later.
- **Fix applied**: `## Validation and Acceptance` now carries a **"Tree-shape
  assertions (R1)"** bullet (track-3.md:342–348) that asserts the concrete
  `BinaryOp` tree *in-track* via the records' structural `equals`, with no
  evaluator: mixed-precedence `a + b * c` → `BinaryOp(PLUS, Var a,
  BinaryOp(STAR, Var b, Var c))`, and same-precedence left-assoc `a - b - c`
  → `BinaryOp(MINUS, BinaryOp(MINUS, Var a, Var b), Var c)`.
- **Re-check**:
  - Location: track-3.md `## Validation and Acceptance`, "Tree-shape
    assertions (R1)".
  - Current state: the in-track assertion is present and the parenthetical
    keeps the value-parity assertion in Track 4 — the two are now distinct, so
    a mis-nesting bug surfaces in Track 3's own test rather than two tracks on.
  - Soundness of the cited mechanism (PSI-confirmed): `AnalyzedExpr` is a
    sealed interface with five **records** — `Var(path)`, `Const(value)`,
    `BinaryOp(op, left, right)`, `UnaryOp(op, operand)`,
    `FuncCall(name, args)`; none overrides `equals`, so each carries Java's
    compiler-generated component-wise structural `equals`. `BinaryOp.left` /
    `.right` are typed `AnalyzedExpr`, so structural equality recurses through
    the nested tree. The `BinaryOperator` enum holds exactly
    `[PLUS, MINUS, STAR, SLASH, EQ, NE, LT, LE, GT, GE]`, so the constants used
    in the assertions (`PLUS`, `STAR`, `MINUS`) are all valid. The "records
    with structural `equals`, no evaluator needed" claim is correct.
  - Criteria met: a tree-shape (not value) defect is now caught inside Track 3.
- **Regression check**: checked the Validation section and the I1/Left-assoc
  invariants — the value-parity-in-Track-4 statements were preserved, not
  duplicated as a now-conflicting in-track value claim. Clean.
- **Verdict**: VERIFIED

#### Verify R2: D14 `SimpleNode.value` reachability + coverage
- **Original issue**: D14 carried an unresolved Phase-A verification note —
  whether the inherited `SimpleNode.value` field is a reachable dispatch path
  the field-walk must handle, which if mishandled would either leave an
  unreachable throw-branch (coverage gap) or throw on every valid expression.
- **Fix applied**: the resolved verdict is recorded in three places —
  **Plan of Work step 1** (track-3.md:262–270), **D14 Risks/Caveats**
  (track-3.md:136–145), and **Surprises** (track-3.md:50–57): `value` IS
  non-null on the modern parser path (the generated `Expression()` mirrors the
  chosen typed field into the inherited `value`) but is never set in isolation
  — a recognized typed field is always co-present, so `value` is **not a
  dispatch key**; the walk keys on the recognized typed field and the
  throw-default fires only on the genuinely out-of-subset typed fields (`rid`,
  `arrayConcatExpression`, `json`), which the throw-cases exercise.
- **Re-check**:
  - Location: track-3.md Plan of Work step 1, D14 Risks/Caveats, Surprises.
  - Current state: PSI confirms `SimpleNode.value` exists (declared in
    `SimpleNode`, type `Object`), consistent with the resolution's premise.
    The resolution is internally consistent across all three locations and
    removes the open-verification flag — the throw-default's reachability is no
    longer in question, closing the unreachable-branch coverage concern.
  - Criteria met: no unreachable throw-branch (the default fires on real
    out-of-subset typed fields the checklist covers); no false-throw on valid
    input (`value` excluded as a dispatch key).
- **Regression check**: checked D14's "Implemented in" note still flags the
  Phase-A verification as performed, and that the design.md "if dead, ignore /
  if reachable, throw" superseded wording is explicitly routed to the Phase-4
  reconciliation rather than left dangling. Clean.
- **Verdict**: VERIFIED

#### Verify R3 (REJECTED): design.md:469 `isFunctionAny`/`isFunctionAll` vs real `evaluateAny`/`evaluateAllFunction`
- **Rejection reason**: this is the known Phase-2 CR1 deferral against the
  frozen design.md. No Track-3 action is warranted: the track files already
  carry the correct method names (`evaluateAny` / `evaluateAllFunction`,
  present in D18's Rationale, track-3.md:166–168), and the lowerer never calls
  these methods — it only throws on `levelZero` shapes, so the names are
  expository, not load-bearing for any Track-3 code path.
- **Downstream check**: leaving design.md unfixed introduces no Track-3 issue
  — the wording correction is recorded in **Surprises** (track-3.md:58–64) as a
  Phase-4 `design-final` reconciliation item, mirroring Track 2's D17 handling.
  PSI confirms no `isFunctionAny`/`isFunctionAll` symbols exist and the
  `SQLBinaryCompareOperator` family is as the track describes (15 concrete
  subtypes: 7 in-subset, 8 thrown), so no downstream spec dependency drifts.
- **Verdict**: REJECTED (no action needed)

#### Verify R4: explicit per-shape throw-case checklist
- **Original issue**: the throw cases were stated only as a prose summary, with
  no per-shape enumeration tying each out-of-subset shape to an asserted throw
  — a gap could go unnoticed.
- **Fix applied**: `## Validation and Acceptance` now carries **"Throw cases
  (I2) — explicit per-shape checklist (R4)"** (track-3.md:349–359) enumerating
  every throw shape: out-of-subset `SQLExpression` fields (`rid`,
  `arrayConcatExpression`, `json`); out-of-subset arithmetic operator on an
  in-subset `mathExpression` (`%`/`REM` plus a shift/bitwise op); out-of-subset
  comparison operator (`SQLInOperator`, `SQLLikeOperator`) and a non-comparison
  boolean shape (`AND`/`OR`); subquery `statement` + `CaseExpression`;
  `levelZero` identifier (incl. `any()`/`all()`, `@this`, inline collection);
  multi-segment `Var`; bind parameter (`inputParam`).
- **Re-check**:
  - Location: track-3.md `## Validation and Acceptance`, "Throw cases (I2)".
  - Current state: every out-of-subset shape named in the Plan of Work
    (steps 1–5), the Decision Log (D10/D14/D18/D6-R), and Interfaces &
    Dependencies has a matching checklist entry. The operator-level cases
    (arithmetic `%` and an out-of-subset comparison op) are explicitly called
    out as reaching the in-subset field and therefore *not* covered by the
    field-walk default — the gap R4 worried about. PSI-confirmed: the
    arithmetic case is real (`SQLMathExpression.Operator` has 12 constants vs
    the IR's 4 arithmetic), and the comparison case is real (15
    `SQLBinaryCompareOperator` subtypes vs 7 in-subset).
  - Criteria met: each throw shape is now an explicit, individually assertable
    test case.
- **Regression check**: cross-checked the checklist against the field-level
  (D14) and operator-level (step 4/step 5) throw mechanisms — no shape is both
  claimed in-subset and listed as a throw case, and no Plan-of-Work throw is
  missing from the checklist. Clean.
- **Verdict**: VERIFIED

## Findings

(none — no new issue surfaced)

## Summary

PASS. R1, R2, R4 VERIFIED; R3 REJECTED (known Phase-2 CR1 deferral, correctly
routed to Phase-4 reconciliation, no Track-3 action). All cited PSI facts
(IR record/enum shapes, `SimpleNode.value`, `SQLMathExpression.Operator`'s 12
constants, `SQLBinaryCompareOperator`'s 15 subtypes) confirmed against the live
IDE index. The applied edits are mutually consistent and introduce no
regression.
