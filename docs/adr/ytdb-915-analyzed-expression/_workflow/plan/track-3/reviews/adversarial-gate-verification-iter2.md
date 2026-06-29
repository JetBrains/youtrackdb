<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Adversarial gate verification — Track 3: Lowering pass (iter2)

```yaml
review_type: adversarial
phase: 3A
role: reviewer-adversarial
track: "Track 3: Lowering pass"
iteration: 2
overall: PASS
findings: 0
verdicts:
  - id: A1
    title: precedence-fold operand-coverage gap (arithmetic-operator subset)
    severity: should-fix
    verdict: VERIFIED
  - id: A2
    title: comparison lowering missing from decomposition
    severity: should-fix
    verdict: VERIFIED
  - id: A3
    title: Track-1 outputs + Track-2 independence both hold
    severity: suggestion
    verdict: VERIFIED
  - id: A4
    title: mathExpression polymorphic dispatch order unpinned
    severity: suggestion
    verdict: VERIFIED
```

## Verdicts

#### Verify A1: precedence-fold operand-coverage gap (≡ technical T1)
- **Original issue**: the IR `BinaryOperator` enum models only four of the AST
  `SQLMathExpression.Operator`'s twelve arithmetic-side constants. The other eight (`REM`,
  three shifts, three bitwise, `NULL_COALESCING`) ride on the **in-subset** `mathExpression`
  field, so the D14 field-walk throw-default never sees them; the original Plan of Work
  folded the flat list to `BinaryOp` without an operator-level gate, so `a % b` would NPE or
  silently mis-map and break I2.
- **Fix applied**: Plan of Work step 4 is now "**Precedence-climbing fold (D12) +
  arithmetic-operator-subset gate (T1/A1)**." It maps the four arithmetic operators
  `PLUS`/`MINUS`/`STAR`/`SLASH` to their IR constants and throws
  `UnsupportedAnalyzedNodeException` on the eight out-of-subset operators, naming all eight
  with their symbols (`REM` `%`, `LSHIFT` `<<`, `RSHIFT` `>>`, `RUNSIGNEDSHIFT` `>>>`,
  `BIT_AND` `&`, `XOR` `^`, `BIT_OR` `|`, `NULL_COALESCING` `??`). Validation's throw-case
  checklist adds "an out-of-subset arithmetic operator on an in-subset `mathExpression`: at
  least `%` (`REM`) and one shift/bitwise op." The Invariants/Plan-of-Work closing paragraph
  now states I2 "spans both the field level (D14) and the operator level (the
  arithmetic-operator-subset gate in step 4 …)." The Surprises log records the gap and its
  resolution.
- **Re-check**:
  - Track-file location: Plan of Work step 4 (lines 288–302); Validation throw-cases
    (lines 354–356); Invariants paragraph (lines 325–329); Surprises (lines 29–39);
    Interfaces "Relevant shapes" (lines 400–403).
  - Current state: the eight out-of-subset operators are explicitly enumerated and routed to
    the throw; the four arithmetic operators are explicitly mapped. The gate is labelled "the
    operator-level analog of D14's field-level exhaustive-or-throw," which is the correct
    framing of the original gap.
  - Criteria met: operand coverage is now complete at the operator level; I2 is preserved on
    in-subset-field-carrying out-of-subset operators; an explicit throw-test pins it.
  - Faithfulness to frozen design: design.md §"NumericOps" (line 606–608) enumerates the same
    12-constant enum — `STAR, SLASH, REM, PLUS, MINUS, three shifts, BIT_AND, XOR, BIT_OR,
    NULL_COALESCING` — and its Edge-cases bullet (lines 638–641) scopes "the eight operators
    outside the S0 IR subset (`REM`, shifts, bitwise, `NULL_COALESCING`) … have no S0 IR
    consumer." The track's four-in / eight-out split and operator names match the design
    exactly; the design pre-existing scope-out makes the track's throw the faithful S0
    behavior (no design edit needed).
- **Regression check**: checked the fold's structural-only contract (D12) — step 4 still
  says "The fold determines nesting only — no value computation," so the added operator gate
  does not pull value semantics into the lowerer; checked that the four mapped operators do
  not collide with the six comparison constants (disjoint: arithmetic in step 4, comparison
  in step 5). Clean.
- **Verdict**: VERIFIED

#### Verify A2: comparison lowering missing from decomposition
- **Original issue**: comparison is in the S0 subset (IR carries the six comparison
  constants; design.md §"Comparison" covers the mechanism; Validation listed comparison as a
  coverage case), but the original six Plan-of-Work steps described only field-walk /
  math-leaf / paren / fold / `NOT` — no step lowered `SQLBinaryCondition`, so the
  decomposition could not produce a `BinaryOp(<cmp>, …)` and the coverage case had no step
  backing it.
- **Fix applied**: Plan of Work step 5 is now "**Boolean-expression dispatch — comparison +
  `NOT` (exhaustive-or-throw, A2)**." On `booleanExpression` it dispatches:
  `SQLBinaryCondition` (fields `left`/`operator`/`right`) → `BinaryOp(<cmp>, lower(left),
  lower(right))` with the subtype→constant mapping (`SQLEqualsOperator`→EQ; `SQLNeqOperator`
  **and** `SQLNeOperator`→NE; `SQLLtOperator`→LT; `SQLLeOperator`→LE; `SQLGtOperator`→GT;
  `SQLGeOperator`→GE), throwing on eight named out-of-subset subtypes; `SQLNotBlock` →
  `UnaryOp(NOT, …)` with `negate=false` pass-through; any other boolean shape → throw.
  Interfaces "Reads but does not modify" and "Relevant shapes" now list `SQLBinaryCondition`
  and `SQLBinaryCompareOperator` with the seven in-subset subtypes; Validation adds the
  comparison coverage case and the comparison/boolean throw-cases. Step 5 explicitly defers
  collation and EQ/NE session replication to Track 4 (D11).
- **Re-check**:
  - Track-file location: Plan of Work step 5 (lines 303–322); Interfaces "Reads but does not
    modify" (lines 380–387) and "Relevant shapes" (lines 404–407); Validation coverage
    (lines 338–341) and throw-cases (lines 357–358); Surprises (lines 40–49).
  - Current state: comparison lowering is now a first-class decomposition step; the
    structure-only boundary (lowerer builds the `BinaryOp`, evaluator owns collation/session)
    is stated; both `!=` spellings collapse to one `NE` constant.
  - Count consistency: seven in-subset subtypes map to six IR constants (the two `!=`
    spellings `SQLNeqOperator`/`SQLNeOperator` collapse to `NE`); eight out-of-subset
    subtypes throw; 7 + 8 = 15, matching the pre-established PSI fact of 15
    `SQLBinaryCompareOperator` subtypes. Internally consistent across step 5, "Relevant
    shapes," and the Surprises log.
  - Faithfulness to frozen design: design.md §"Comparison: replicate the AST sequence"
    (lines 673–727) places collation and EQ/NE session threading in the *evaluator* (it
    delegates to the parser's `SQLBinaryCompareOperator` instance), and §IR-types names "the
    six comparisons `= != < <= > >=`" (line 262). The track's lowerer-builds-structure /
    evaluator-owns-semantics split matches the design's division of labor; the six-constant
    target matches.
  - Criteria met: comparison coverage is now backed by a step; I2 extends to the
    comparison-operator level (step 5 throws on the eight out-of-subset subtypes and on
    non-comparison boolean shapes).
- **Regression check**: checked that step 5 does not duplicate or contradict the D18
  `levelZero`/`any()`/`all()` throw (D18 throws those at the identifier leaf, step 5 throws
  non-comparison boolean shapes — disjoint surfaces); checked the `NOT` `negate=false`
  pass-through does not create an infinite recurse (it recurses on `sub`, a strictly smaller
  node). Clean.
- **Verdict**: VERIFIED

#### Verify A3: Track-1 outputs + Track-2 independence both hold (survived)
- **Original issue**: none — the original finding was a suggestion that the asserted Track-1
  IR dependency and Track-2 non-dependency both hold; re-verify the track still states them.
- **Fix applied**: none required.
- **Re-check**:
  - Track-file location: Context and Orientation (lines 227–230); Interfaces "Inter-track
    dependencies" (lines 409–410) and "Out of scope" (lines 389–391).
  - Current state: "This track depends on Track 1 for the IR types (`AnalyzedExpr` and its
    five variants, the operator enums, `UnsupportedAnalyzedNodeException`). It does not depend
    on Track 2 (`NumericOps`) or Track 4 — lowering only builds the tree's *structure*."
    Interfaces restates "Track 3 depends on **Track 1** (IR types)" and lists `NumericOps` as
    out of scope ("Track 2 — lowering does no arithmetic"). The A1/A2 fixes did not disturb
    this — the operator-subset gate and comparison dispatch are pure structure-building, with
    value semantics still deferred to Track 2/Track 4, so the Track-2 independence is in fact
    reinforced.
- **Verdict**: VERIFIED

#### Verify A4: `mathExpression` polymorphic dispatch order unpinned (step 1)
- **Original issue**: the field-walk's dispatch order over `SQLExpression`'s typed fields
  was unpinned; if dispatch keyed partly on the inherited `value`, order could matter.
- **Fix applied**: Plan of Work step 1's "D14 Phase-A verification note — resolved (T2/R2)"
  paragraph (lines 262–270) and D14's Risks/Caveats (lines 136–145) now establish that
  `SimpleNode.value` IS non-null on the modern parser path but never set in isolation — a
  recognized typed field is always co-present — so the walk keys on the recognized typed
  field and `value` is **not a dispatch key**. The union-style guarantee (exactly one typed
  field non-null per parsed expression, Context lines 219–221 and D14 Rationale) makes order
  among the typed fields immaterial.
- **Re-check**:
  - Track-file location: Plan of Work step 1 (lines 258–270); D14 Decision Log (lines
    119–148); Surprises (lines 50–57); Context union-style statement (lines 218–225).
  - Current state: the concern is resolved, not merely deferred — because exactly one typed
    field is non-null and `value` is excluded as a dispatch key, dispatch order among the
    typed fields cannot change the result. No residual.
  - Faithfulness: the deferred design.md §"Field-walk" "if dead, ignore / if reachable,
    throw" wording is correctly carried as a Phase-4 `design-final` reconciliation item
    (Surprises lines 58–64, D14 lines 143–145), so the frozen-design divergence is logged
    rather than silently applied.
- **Regression check**: checked that excluding `value` as a dispatch key does not lose
  coverage — the throw-default still fires on the genuinely out-of-subset typed fields
  (`rid`, `arrayConcatExpression`, `json`), which the throw-case checklist exercises
  (Validation lines 350–351). Clean.
- **Verdict**: VERIFIED

## Findings

<!-- No new findings surfaced by this verification pass. -->

## Summary

PASS. All four prior adversarial findings VERIFIED. The A1 arithmetic-operator-subset gate
(step 4) and the A2 comparison/boolean dispatch (step 5) close the two should-fix coverage
gaps at the operator level, are internally consistent (the 7-in / 8-out comparison split sums
to the 15 pre-established `SQLBinaryCompareOperator` subtypes; the 4-in / 8-out arithmetic
split matches the 12-constant `Operator` enum), and are faithful to the frozen design
(design.md §"NumericOps" pre-scopes the eight arithmetic operators out of S0; §"Comparison"
keeps collation/session semantics in the evaluator, matching the track's structure-only
lowerer). A3 and A4 survive: Track-1 dependency / Track-2 independence still hold and are
reinforced, and the D14 `value` resolution removes the dispatch-order concern entirely. No
new issues introduced.
