<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
---
review_type: technical-gate-verification
phase: 3A
iteration: 2
track: "Track 3: Lowering pass"
overall: PASS
findings: 0
verdicts:
  - id: T1
    title: arithmetic-operator-subset gate
    verdict: VERIFIED
  - id: T2
    title: D14 SimpleNode.value verdict
    verdict: VERIFIED
  - id: T3
    title: number to Const via getValue()
    verdict: VERIFIED
---

# Track 3 — Technical gate-verification (iteration 2)

Overall: **PASS**. All three accepted findings are fixed correctly in
`track-3.md`; the edits across Plan of Work, Validation, Invariants, Decision
Log, Surprises, and Interfaces stay mutually consistent and contradict neither
the frozen design nor each other. No new technical finding surfaced. PSI
spot-checks against the live `analyzed-expression` project confirmed every
load-bearing claim the fixes rest on.

## Verdicts

#### Verify T1: arithmetic-operator-subset gate
- **Original issue**: the IR `BinaryOperator` models only `{PLUS,MINUS,STAR,SLASH}`
  + 6 comparisons, but the AST `Operator`'s other 8 (`REM`, three shifts, three
  bitwise, `NULL_COALESCING`) arrive on the in-subset `mathExpression` field, so
  D14's field-walk throw-default never catches them — an I2 hole.
- **Fix applied**: Plan of Work step 4 (track-3.md:288–302) now maps the four
  arithmetic ops and throws `UnsupportedAnalyzedNodeException` on the eight others,
  naming each with its symbol; Validation throw-cases (track-3.md:354–355) add `%`
  (`REM`) plus a shift/bitwise op; Invariants (track-3.md:326–329) extend I2 to the
  operator level; Relevant shapes (track-3.md:400–403) lists the 12-constant
  `Operator` set and tags the eight as out-of-subset; the Surprises log
  (track-3.md:29–39) records T1/A1.
- **Re-check**:
  - Track-file location: Plan of Work step 4 + Validation + Invariants + Relevant
    shapes.
  - Current state: the eight out-of-subset operators in the track file are exactly
    `REM, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, XOR, BIT_OR, NULL_COALESCING`.
    PSI (`SQLMathExpression.Operator`, 12 constants:
    `STAR, SLASH, REM, PLUS, MINUS, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, XOR,
    BIT_OR, NULL_COALESCING`) minus the IR `BinaryOperator` arithmetic set
    (`PLUS, MINUS, STAR, SLASH`) = exactly those eight. The set partition the track
    asserts is correct.
  - Criteria met: I2 is now closed at the operator level; the gap was a missing
    operator-level throw, and step 4 supplies it as the operator-level analog of
    D14's field-level exhaustive-or-throw.
- **Regression check**: checked the Plan-of-Work / Validation / Invariants /
  Relevant-shapes / Surprises edits for mutual consistency — the eight-operator
  list and the four-mapped list are stated identically in all locations; no
  contradiction with the frozen design (design.md §"NumericOps" already scopes the
  eight out of the S0 IR subset). Clean.
- **Verdict**: VERIFIED

#### Verify T2: D14 `SimpleNode.value` verdict
- **Original issue**: D14 left an open Phase-A verification note on whether the
  inherited `SimpleNode.value` field is a dispatch key the field-walk must handle.
- **Fix applied**: Plan of Work step 1 (track-3.md:262–270) and the D14
  Decision-Log Risks/Caveats (track-3.md:136–145) record the resolved verdict —
  `value` IS non-null on the modern parser path (the generated `Expression()`
  mirrors the chosen typed field into the inherited `value`) but never in
  isolation, so the walk keys on the recognized typed field and `value` is NOT a
  dispatch key; the throw-default fires only on out-of-subset typed fields (`rid`,
  `arrayConcatExpression`, `json`). design.md wording deferred to Phase 4
  (track-3.md:58–64, R3).
- **Re-check**:
  - Track-file location: Plan of Work step 1 + D14 Decision Log + Surprises log.
  - Current state: both the step and the decision record now carry the resolved
    verdict identically; the open "if dead, ignore / if reachable, throw" framing
    is explicitly superseded and its design.md counterpart routed to the Phase-4
    `design-final` reconciliation (consistent with Track 2's D17 deferral handling).
  - Criteria met: D14's exhaustive-or-throw stays sound — keying on the recognized
    typed field, not on `value`, avoids both a throw-on-every-valid-expression bug
    and a missed-dispatch bug; the throw-cases (`rid`, `arrayConcatExpression`,
    `json`) exercise the genuine out-of-subset default. No unreachable-branch
    coverage problem remains.
- **Regression check**: the resolved verdict is the load-bearing premise of D18
  too (`levelZero` reaching the field-walk default) and of step 1's throw-default
  wording — both remain consistent with the resolved D14 text. Clean.
- **Verdict**: VERIFIED

#### Verify T3: number → `Const` via `getValue()`
- **Original issue**: the original step 2 number bullet did not specify reading the
  value through the concrete subclass; base `SQLNumber.getValue()` returns null, so
  a base-level read would yield a null `Const`.
- **Fix applied**: Plan of Work step 2 number bullet (track-3.md:276–278) now lowers
  via the concrete subclass's `getValue()` and states that the `sign` flag is folded
  into the value by `SQLInteger` / `SQLFloatingPoint`, so a negative literal arrives
  as one negative `Const`, and that base `SQLNumber.getValue()` returns null.
- **Re-check**:
  - Track-file location: Plan of Work step 2, number bullet.
  - Current state: matches PSI exactly — base `SQLNumber.getValue()` body is
    `{ return null; }`; `SQLInteger.getValue()` returns the typed `value` field
    (sign folded at parse time); `SQLFloatingPoint.getValue()` folds `sign`
    explicitly (`* sign` in each parse branch). Reading through the concrete
    subclass is therefore required and correct.
  - Criteria met: the number-leaf lowering now produces a correct non-null,
    sign-correct `Const` for both integer and floating-point literals.
- **Regression check**: checked against the Validation coverage case for `Const`
  and the tree-shape assertions — no conflict; the negative-literal-as-one-`Const`
  statement is consistent with the round-trip parity matrix (Track 4) that backs
  arithmetic value parity. Clean.
- **Verdict**: VERIFIED

## Findings

(none — pure verdict pass)
