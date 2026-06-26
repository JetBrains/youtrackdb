# design-author params — Step 4b track authoring, round 1

## Inputs
- target: tracks
- output_path / plan_dir: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- design_path (frozen seed, full tier): /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- plan_path (Component Map + checklist already written; mirror its intro paragraphs into each track's Purpose): /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/implementation-plan.md
- round: 1
- flagged_passages: (none — round 1 grounds the whole set)

## Your job

Write **four** track files — `plan/track-1.md` through `plan/track-4.md` — to the
template at the end of this file. The decomposition, the per-track decision-record
assignment, the section homes, the scope, and the dependencies are already settled
below (the orchestrator's items 1–8). You supply the cold-readable prose for a reader
who has only the finished track file plus the plan Component Map. Ground every track in
the frozen `design.md` seed (the D-records, the Class/Workflow diagrams, the four Parts)
and the research log, and verify cited symbols/packages through PSI (the
`analyzed-expression` IntelliJ project is open and matches this working tree). This is
S0 of YTDB-901; the substrate ships behind no flag with no live executor consumer, so
round-trip parity against the AST is the only acceptance bar.

Seed each track's `## Decision Log` from the frozen `design.md` D-records assigned to it
(the track file is the live authority; the design seed is historical provenance — add an
optional `**Full design**: design.md §"<section>"` line per record). Keep every record
faithful to its design seed. The four-bullet form is mandatory (Alternatives considered,
Rationale, Risks/Caveats, Implemented in: this track).

## Settled decomposition (frozen — do not re-litigate; from research-log D13)

Four tracks, dependency-ordered. D13 resolved this split and rejected folding T2 into
T3 and folding T4's evaluator into T3.

### Track 1 — Substrate + framework
- **Deliverables (greenfield `core/.../query/analyzed/`):** sealed `AnalyzedExpr`
  interface + five record variants (`Var(List<String> path)`, `Const(Object value)`,
  `BinaryOp`, `UnaryOp`, `FuncCall`); the IR's own `BinaryOperator` (`+ - * /` and the six
  comparisons) and `UnaryOperator` (`NOT`) enums; `AnalyzedExprVisitor<T>` + the static
  `AnalyzedExpr.dispatch`; `AnalyzedExprTransform` + the static
  `AnalyzedExpr.transformChildren`; `UnsupportedAnalyzedNodeException`; a substrate unit
  test (dispatch exhaustiveness, transformChildren structural sharing by reference identity).
- **Decision Log seeds:** D1 (sealed IR, five record variants), D2 (visitor interface;
  static `switch` dispatch, no `accept`), D4 (`Cast` dropped from S0), D6 (`Var` carries a
  `List<String>` name path), D7 (`UnsupportedAnalyzedNodeException extends
  CommandExecutionException`), D8 (`AnalyzedExprTransform` with structural sharing),
  D9 (defaults on `AnalyzedExprTransform`, none on the base visitor).
- **Invariants & Constraints:** I3 (exhaustive dispatch — adding a sixth variant is a
  compile-time break across the dispatcher and every base-visitor implementer), verified
  by the substrate unit test; first sealed-type use in the codebase.
- **Depends on:** nothing.
- **Sizing justification (write this into the track file — argumentation gate):** ~10
  files sits near the merge floor, but T1 is kept separate per D13 — it is the greenfield
  IR-types-and-framework PR the lowering pass (T3) and evaluator (T4) both build on, a
  clean dependency boundary, and bundling the data-only substrate with the AST-reading
  lowerer would mix two distinct review surfaces in one diff.

### Track 2 — NumericOps whole-enum extraction
- **Deliverables:** new `core/.../sql/util/NumericOps.java` (`final`, private
  constructor, all-static) holding the whole promotion engine lifted unchanged from
  `SQLMathExpression.Operator` — the five abstract typed-pair `apply` overloads (`Integer`,
  `Long`, `Float`, `Double`, `BigDecimal`), the fallback `apply(Object, Object)`, the
  shared widening entry `apply(Number, Operator, Number)`, the private `toLong`;
  `SQLMathExpression.Operator.apply` becomes a thin delegator. State explicitly whether the
  typed `apply` overloads stay on the enum (with `NumericOps` calling back) or move with it
  (D17 boundary). The eight non-`+ - * /` operators (`REM`, shifts, bitwise,
  `NULL_COALESCING`) get extracted but have no S0 IR consumer — intended.
- **Decision Log seeds:** D5 (`NumericOps` at neutral `core/.../sql/util/`; placement fork
  rejected `query/analyzed/` and `sql/method/`), D5-R (whole-enum lift-and-shift, not
  narrow `+ - * /` extraction), D17 (touches the live AST arithmetic hot path;
  perf-neutrality rests on the two-hop `operator.apply` → typed `operation.apply`
  re-dispatch staying intact; runtime measurement deferred to S1's LDBC JMH gate).
- **Invariants & Constraints:** existing AST math-test suite (e.g. `MathExpressionTest`)
  stays green after `Operator.apply` becomes a delegator (the self-verifying acceptance
  gate); no new virtual indirection on the hot path — the added `NumericOps` delegation is
  monomorphic and inlines. The math semantics that must be preserved exactly (integer-divide
  widening, null propagation, `Date + Long`, `String` concat) are listed in design Part 3.
- **Depends on:** nothing (AST-side only; independent of T1).
- **Sizing justification (write into the track file):** ~3 files is under the merge floor,
  but D13 keeps it separate — an AST-side refactor with its own review surface and its own
  acceptance gate (math tests green), separately mergeable from the new IR work; folding it
  into T3 would couple an AST refactor with new IR lowering.

### Track 3 — Lowering pass
- **Deliverables:** new `AnalyzedExprLowerer` in `query/analyzed/` converting the covered
  `SQLExpression` AST subset to `AnalyzedExpr`; a lowering unit test (coverage + the
  throw cases). Reads but does not modify the AST classes (`SQLExpression`,
  `SQLMathExpression`/`SQLBaseExpression`, `SQLBaseIdentifier`,
  `SQLParenthesisExpression`, `SQLNotBlock`, `SQLBooleanExpression`). Owns the three
  non-obvious mechanisms: the exhaustive-or-throw field walk over the union-style
  `SQLExpression`, transparent recursion through parenthesis grouping, and the structural
  precedence-climbing fold.
- **Decision Log seeds:** D10 (`SQLParenthesisExpression`: recurse on `expression`, throw
  on `statement`/CASE; no `Paren` IR variant), D12 (structural precedence-climbing fold —
  reproduces only nesting; value semantics come from shared `NumericOps`), D14 (field-walk
  is exhaustive-or-throw; the `value` field is flagged for Phase-A PSI verification),
  D18 (`SQLBaseIdentifier.levelZero` form — top-level function calls incl. `any()`/`all()`,
  `@this`, inline collections — out of S0 subset and throws; `FuncCall` comes only from
  method-call modifiers), D6-R (S0 lowers single-segment `Var` only; multi-segment paths
  throw, deferred to S1+ — this is one logical decision shared with T4; record it here as
  the lowering-throw behavior and cross-reference T4).
- **Invariants & Constraints:** I2 (no silent fallback — lowering throws
  `UnsupportedAnalyzedNodeException` or returns a complete tree; never a partial tree),
  verified by the lowering throw-case tests; left-associative reduction must match the
  AST's `<=` reduction (`a - b - c` is `(a - b) - c`), backed by the T4 round-trip matrix.
- **Depends on:** Track 1 (IR types).
- **Sizing justification (write into the track file):** ~4 files is under the merge floor,
  but D13 keeps lowering separate from T4's evaluator — they are distinct visitor-side and
  evaluation-side concerns with distinct review surfaces, and the stacked-diff series lands
  each as an independently reviewable PR.

### Track 4 — Evaluator + round-trip parity
- **Deliverables:** new `AnalyzedExprEvaluator` implementing `AnalyzedExprVisitor<Object>`
  directly (so it enumerates every variant) — arithmetic delegated to `NumericOps`,
  comparison by replicating `SQLBinaryCondition.evaluate(Result, ctx)`'s exact sequence
  (resolve the single-property collate left-then-right, `collate.transform` both operands,
  delegate to the AST's own `SQLBinaryCompareOperator` instance); the round-trip parity
  test suite asserting the design Part 4 matrix.
- **Decision Log seeds:** D3 (single `evaluate(Result, CommandContext)` overload; an
  `Identifiable`-only S1+ caller wraps in a synthetic entity-backed `Result`), D11 (IR
  comparison replicates `SQLBinaryCondition.evaluate` — collate transform plus the
  parser-operator instance, not bare statics; reproduces the EQ live-session / NE
  null-session difference by construction), D15 (the AST `evaluate(Identifiable)` collation
  skip is a deliberate AST inconsistency the analyzed layer unifies via the single `Result`
  overload — an observable S1+/S7 convergence across ~12 callers incl. WHERE /
  `SecurityEngine`), D16 ("fast path need not mirror" is scoped to S0; the S1+ evaluator
  must reproduce the AST evaluation fast paths on the hot path), D6-R (collate fetch pinned
  to single-property resolution; multi-segment `Var` throws — the same logical decision as
  in T3, recorded here as the comparison-evaluator constraint), D19 (every functional slice
  under YTDB-901 extends per-functionality JMH benchmark coverage on top of LDBC neutrality,
  blanket S1–S7; S0 itself stays on its correctness gate, having no live consumer to measure
  — record here as the verification-and-delivery context).
- **Invariants & Constraints:** I1 (round-trip parity — for every covered SQL fragment,
  `lower(parse(sql)).evaluate(row, ctx)` is `Objects.equals` to `parse(sql).execute(row,
  ctx)`, including null and type-coercion outcomes; the AST is the oracle, a divergence is a
  real bug). Test matrix in design Part 4: precedence-fold rows, left-associativity,
  integer/double divide, parenthesis recursion, `ci`-collation comparison, type-coercing
  `!=`, plus null-propagation and `Date + Long` rows.
- **Depends on:** Track 1 (IR types), Track 2 (`NumericOps`), Track 3 (lowering produces
  trees to evaluate).
- **Sizing justification (write into the track file):** ~4 files is under the merge floor,
  but D13 keeps the evaluator + round-trip suite separate from T3 — the round-trip suite is
  the T4 deliverable that needs both lowering and evaluation present, and evaluation is a
  distinct visitor implementation and review surface.

## Section-home guidance (where content goes in each track file)
- `## Purpose / Big Picture` — one-line BLUF (the user-visible behavior gained after this
  track lands) + the intro paragraph restated from the plan checklist entry (keep them
  consistent). Leave the Move-2 ADDED/MODIFIED/REMOVED triad placeholder empty.
- `## Context and Orientation` — codebase state at the start of the track (greenfield
  packages confirmed absent on develop; the union-style `SQLExpression`; the AST classes the
  track reads). T3/T4 may add a track-level Mermaid diagram here if 3+ internal components
  interact non-trivially (≤10 nodes).
- `## Plan of Work` — the prose sequence of edits/additions, ordering constraints, and
  invariants to preserve. No per-step decomposition (deferred to Phase A); leave
  `## Concrete Steps` and `## Idempotence and Recovery` as Phase-A placeholders.
- `## Interfaces and Dependencies` — in-scope vs out-of-scope file boundaries, the
  inter-track dependencies above, relevant signatures, and the **sizing justification** for
  this track (per D13, see each track's justification above).
- `## Decision Log` — the assigned four-bullet DRs (see each track above).
- `## Invariants & Constraints` — the assigned invariants/constraints (see each track), each
  a property backed by a test.
- `## Validation and Acceptance` — track-level behavioral acceptance criteria (per-step
  EARS/Gherkin is a Phase-A placeholder; leave the Move-3 placeholder empty).
- Continuous-log sections (`## Surprises & Discoveries`, `## Outcomes & Retrospective`,
  `## Episodes`, `## Artifacts and Notes`) stay empty at Phase 1.

## Cross-track note on D6-R (one logical decision in two tracks)
D6-R is the single decision "S0 lowers single-segment `Var` only; multi-segment paths
throw, deferred to S1+." It lands in T3 (the lowering throw) and T4 (the single-property
collate resolution). Record it in both, faithful to the same `design.md` seed, and note in
each that it is the same logical decision carried in the sibling track (the track-canonical
cross-track propagation duty).

## Stamp contract (load-bearing)
Begin **every** track file with this exact line as line 1, then the H1 on line 2:

```
<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
```

Do not alter or move the stamp. The orchestrator verifies all four stamps before commit.

## Return contract
Write the four files to `plan_dir`. Return **only a thin summary** (what you drafted, per
track, and any open question) — never the drafted track content. The orchestrator's
context stays bounded only if you return a summary.

## Track-file template (write each track to this shape; stamp on line 1)

````markdown
<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Track N: <title>

## Purpose / Big Picture
<One-line BLUF stating the user-visible behavior gained after this track lands.>

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

<Intro paragraph from the plan checklist entry, restated here so the file
is self-sufficient — Phase B/C sub-agents that don't read the root plan
see it.>

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Full inline Decision Records this track owns (four-bullet form). One block per decision: -->

#### D<N>: <Decision title>
- **Alternatives considered**: <what else was on the table>
- **Rationale**: <why this option won — trade-offs, constraints>
- **Risks/Caveats**: <known downsides or things to watch>
- **Implemented in**: this track (step references added during execution)
<!-- Optional: `**Full design**: design.md §"<section>"` — historical provenance. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation
<Codebase state at the start of this track. Optional track-level Mermaid (≤10 nodes).>

## Plan of Work
<Prose sequence of edits and additions — approach, ordering constraints, invariants to
preserve. References the Concrete Steps roster below.>

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the numbered roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
<Track-level behavioral acceptance criteria.>

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
<In-scope and out-of-scope file boundaries, compatibility requirements, inter-track
dependencies, library/function signatures, and the sizing justification for this track.>

## Invariants & Constraints
<!-- Per-track testable constraints and invariants; each a property backed by a test. -->
- <Invariant or constraint that must hold> — verified by <test>.
````
