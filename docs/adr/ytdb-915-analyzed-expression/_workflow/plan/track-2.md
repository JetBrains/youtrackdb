<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Track 2: NumericOps whole-enum extraction

## Purpose / Big Picture
After this track lands, the whole numeric-promotion engine lives in one neutral place —
`core/.../sql/util/NumericOps.java` — that both the AST and the new IR evaluator can
depend on, so the two evaluators cannot drift on arithmetic edge cases. No user-visible
behavior changes: the AST still arithmetic-evaluates exactly as before, now through a
delegating `Operator.apply`.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 2 moves the whole numeric-promotion engine out of `SQLMathExpression.Operator`
into a neutral `final class NumericOps` under `core/.../sql/util/`, leaving
`Operator.apply` a thin delegator so the AST and the new IR evaluator share one
promotion home. It touches the live AST arithmetic path; its acceptance gate is the
existing math-test suite staying green.

The promotion engine is the code that decides the result type and value of a binary
arithmetic operation across mixed operand types — integer-vs-double divide widening, null
propagation, `Date + Long`, `String` concatenation. Concretely, the engine is the inner
enum `SQLMathExpression.Operator`'s `apply` family; this track lifts that whole family
into `NumericOps` and rewires it. The change is AST-side only — it adds no IR and does not
depend on the `AnalyzedExpr` substrate — but Track 4's IR evaluator is the downstream
consumer the shared home exists for.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Full inline Decision Records this track owns (four-bullet form). One block per decision: -->

#### D5: Extract `NumericOps` to a neutral `core/.../sql/util/` location
- **Alternatives considered**: duplicate the promotion logic in the IR evaluator
  (guarantees drift); place `NumericOps` inside `query/analyzed/` (forces a backward
  dependency from the AST package to a sibling); place it under `sql/method/` (that package
  already means typed method dispatch).
- **Rationale**: the numeric-promotion engine moves out of `SQLMathExpression.Operator`
  into a neutral `final class NumericOps` (private constructor, all-static) at
  `core/.../sql/util/NumericOps.java`. If the AST and IR evaluators each held their own
  promotion logic, they would drift on edge cases — integer-vs-double divide, null
  propagation, `Date + Long`. A single shared helper makes divergence structurally
  impossible. `sql/util/` (confirmed absent on develop) is a fresh neutral location both
  layers can depend on, with no backward dependency and no overloaded meaning.
- **Risks/Caveats**: a new package and a new team convention, but no Spotless / `pom.xml` /
  `--add-opens` changes (same `core` module). D5-R supersedes only D5's *scope* half
  (whole-enum vs. narrow extraction, below); the placement fork resolved here stays live.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"NumericOps: one shared promotion engine" -->

#### D5-R: `NumericOps` extraction is a whole-enum lift-and-shift, not a narrow `+ - * /` extraction
- **Alternatives considered**: narrow extraction of only the `+ - * /` promotion paths the
  IR evaluator needs (leaves the shared `apply(Number, Operator, Number)` widening helper
  split across `NumericOps` and `Operator` — an unclean seam, since the other eight
  operators invoke the same widening); deferring the boundary to Phase A (the boundary is
  now understood, no reason to defer). Both were open options at re-validation; the user
  chose whole-enum.
- **Rationale**: `SQLMathExpression.Operator` is an inner enum with 12 constants (the
  numeric argument on each is its precedence priority, confirmed via PSI). All 12 share one
  promotion engine — the five typed-pair `apply` overloads, the `apply(Object, Object)`
  fallback, the shared `apply(Number, Operator, Number)` widening entry, and the private
  `toLong` helper (inventoried in `## Context and Orientation`). The whole engine moves to
  `NumericOps`, and `SQLMathExpression.Operator.apply` becomes a thin delegator. A clean single-home boundary beats a partial extraction whose
  shared widening helper still straddles two classes — with all promotion logic in one
  place, AST/IR drift is structurally impossible and there is no ambiguous "who owns the
  shared helper" seam. The larger diff is acceptable because every existing AST math test
  is the acceptance gate: a lift-and-shift that keeps all math tests green is
  self-verifying.
- **Risks/Caveats**: the whole-enum lift touches more of `SQLMathExpression` than the
  narrow option — the regression surface is the full existing math-test suite, which must
  stay green after the delegation. The eight operators outside the S0 IR subset (`REM`, shifts, bitwise,
  `NULL_COALESCING`) get extracted but have no S0 IR consumer; that is intended — they keep
  working through the AST delegator, and the only cost is the larger set of AST-side call
  sites the extraction touches.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"NumericOps: one shared promotion engine" -->

#### D17: The extraction touches the live AST arithmetic hot path; perf-neutrality basis recorded, runtime measurement deferred to S1's LDBC JMH gate
- **Alternatives considered**: add a standalone LDBC JMH run as an S0/T2 acceptance gate
  (rejected — S1's gate already covers this low-risk change, and an S0 run would benchmark
  before any hot consumer exists, so it would measure nothing the S1 gate does not, at the
  cost of a heavyweight Hetzner run); claim neutrality with no recorded basis (rejected —
  the inlining argument should be on record).
- **Rationale**: the `NumericOps` extraction is the only S0 change that modifies live
  production code. `Operator.apply` is called by `iterateOnPriorities` — the per-evaluation
  precedence walk that folds the flat operator list — on every AST arithmetic evaluation
  today, so it is a hot path. Perf-neutrality rests on leaving the existing two-hop dispatch
  chain intact: the chain (`operator.apply(Object,Object)` → shared widening entry
  `apply(Number,Operator,Number)` → typed `apply` overload) has two virtual dispatches on the
  enum constant with a plain widening call between them. The whole-enum lift-and-shift moves
  the entire chain into the all-static `NumericOps` and adds only a monomorphic, inlinable
  `NumericOps` delegation in front of it — no new virtual indirection. S0's acceptance gate is
  the existing math-test suite staying green after the delegation (correctness); runtime
  perf-neutrality is verified at S1 against the LDBC JMH suite (YTDB-916's "neutral on CCX33"
  and YTDB-901's umbrella JMH-neutrality requirement) — the first slice with a live consumer
  to measure, not a standalone S0 Hetzner run. The full PSI-confirmed dispatch-chain diagram
  is in `design.md §"NumericOps: one shared promotion engine"`.
- **Risks/Caveats**: this track **must state explicitly** whether the five typed `apply`
  overloads stay on the enum (with `NumericOps` calling back) or move with it into
  `NumericOps` — that boundary is the one open implementation choice the design hands to
  this track, and either form must preserve the two-hop dispatch shape so no new virtual
  indirection enters the hot path. S0 itself runs no JMH, so the perf claim stays
  unverified until S1 measures it against a live consumer.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"NumericOps: one shared promotion engine" / Part 4 -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation
The target package `core/.../sql/util/`
(`com.jetbrains.youtrackdb.internal.core.sql.util`) does not exist on develop — confirmed
absent via PSI. The new `NumericOps` class is greenfield; the only existing class this
track modifies is `SQLMathExpression` (`core/.../sql/parser/SQLMathExpression.java`), whose
inner enum `Operator` holds the promotion engine.

The engine as it stands on develop (PSI-confirmed): `SQLMathExpression.Operator` is an
inner enum with the 12 constants above, each declaring abstract typed-pair `apply`
overloads (`apply(Integer,Integer)`, `apply(Long,Long)`, `apply(Float,Float)`,
`apply(Double,Double)`, `apply(BigDecimal,BigDecimal)`), a fallback `apply(Object,Object)`,
and a shared `apply(Number,Operator,Number)` widening entry; plus a `getPriority()` and a
private `toLong`. `SQLMathExpression` itself stores arithmetic as a flat n-ary list
(`childExpressions: List<SQLMathExpression>`, `operators: List<Operator>`) and resolves
precedence at evaluate time via `calculateWithOpPriority` / `iterateOnPriorities`. This
track touches only the promotion engine; the precedence fold and the flat-list shape are
left untouched (Track 3 reads them, Track 3 does not modify them either).

This track is AST-side only. It has no dependency on the `AnalyzedExpr` substrate (Track 1)
and does not read or produce IR. Track 4's evaluator is the downstream consumer that will
delegate `+ - * /` to the same `NumericOps`, but Track 4 depends on this track, not the
reverse.

## Plan of Work
The extraction is a lift-and-shift, sequenced so the AST keeps working throughout:

1. Create `core/.../sql/util/NumericOps.java` as a `final` class with a private
   constructor and an all-static surface. Lift the promotion engine into it: the shared
   widening entry `apply(Number, Operator, Number)`, the per-operator
   value logic, the typed-pair overloads, the fallback `apply(Object, Object)`, and the
   `toLong` helper.
2. Decide and state the typed-overload boundary (D17): either the five typed `apply`
   overloads move into `NumericOps`, or they stay on the enum with `NumericOps` calling
   back. Whichever is chosen, the two-hop dispatch shape must be preserved so no new virtual
   indirection enters the hot path.
3. Rewrite `SQLMathExpression.Operator.apply(...)` as a thin delegator into `NumericOps`,
   keeping the per-constant value semantics observable exactly as before.
4. Run the existing math-test suite (e.g. `MathExpressionTest`) and confirm it stays green
   — this is the self-verifying acceptance gate.

Invariants to preserve while extracting (the math semantics existing AST tests pin, which
must be identical after the delegation):

- Integer-divide returns the integer type when the remainder is zero, else widens to
  `Double`.
- Null propagation: `null + x = x`, `null - x = 0 - x`, `null * x = null`,
  `null / x = null`.
- `Date + Long` and `Long + Date` produce a `Date`; `Date - Long` produces a `Date`
  (`Date - Date` is not handled by the AST and is out of scope).
- `+` does `String` concatenation when either operand is a `String` (the other operand is
  `toString()`-ed).

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the numbered roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
The acceptance gate is self-verifying and behavioral, not a new test:

- The existing AST math-test suite (e.g. `MathExpressionTest`) stays green after
  `Operator.apply` becomes a delegator. Because the extraction is a lift-and-shift with no
  intended behavior change, any divergence in promotion semantics — integer-divide
  widening, null propagation, `Date + Long`, `String` concat — surfaces as a math-test
  failure.
- The five math-semantics properties listed under Plan of Work hold identically before and
  after the extraction; they are pinned by the existing suite.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope:**
- New `core/.../sql/util/NumericOps.java` — `final`, private constructor, all-static. Holds
  the whole promotion engine: the shared widening entry `apply(Number, op, Number)`, the
  five typed-pair `apply` overloads, the fallback `apply(Object, Object)`, and the private
  `toLong` (subject to the D17 typed-overload-boundary decision).
- Modify `SQLMathExpression.Operator.apply` (`core/.../sql/parser/SQLMathExpression.java`)
  to delegate into `NumericOps`.
- Math-test verification (the existing suite, run as the acceptance gate).

**Out of scope:** the `SQLMathExpression` precedence fold (`calculateWithOpPriority` /
`iterateOnPriorities`) and the flat `childExpressions`/`operators` list stay untouched;
the IR substrate (Track 1), the lowering pass (Track 3), and the evaluator (Track 4) are
other tracks. The eight non-`+ - * /` operators are extracted (they share the engine) but
have no S0 IR consumer — intended.

**Relevant signatures (PSI-confirmed on develop):**
- `SQLMathExpression.Operator` enum: 12 constants; `abstract Number apply(Integer,Integer)`
  and the four sibling typed overloads; `Object apply(Object, Object)`; `Number
  apply(Number, Operator, Number)`; `getPriority()`; private `toLong`.

**Inter-track dependencies:** Track 2 depends on nothing (independent of Track 1 — it is
AST-side only). Track 4's evaluator depends on this track for the shared `NumericOps`.

**Sizing justification (argumentation gate).** This track is ~3 files, which is under the
merge floor, but D13 keeps it separate: it is an AST-side refactor with its own review
surface and its own acceptance gate (the math tests staying green), separately mergeable
from the new IR work. Folding it into Track 3 would couple an AST refactor with new IR
lowering — two unrelated review surfaces in one diff. The stacked-diff series lands the
extraction as its own reviewable PR.

## Invariants & Constraints
<!-- Per-track testable constraints and invariants; each a property backed by a test. -->
- **Math semantics preserved exactly.** Integer-divide widening, null propagation, `Date +
  Long`/`Long + Date`/`Date - Long`, and `String` concatenation behave identically after
  `Operator.apply` delegates to `NumericOps` — verified by the existing AST math-test suite
  (e.g. `MathExpressionTest`) staying green (the self-verifying acceptance gate).
- **No new virtual indirection on the hot path.** The added `NumericOps` delegation is a
  monomorphic static call that inlines; the existing two-hop `operator.apply` → typed
  `operation.apply` re-dispatch stays intact (D17) — verified by code review of the
  delegation shape; runtime measurement is deferred to S1's LDBC JMH gate.
