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
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-06-26T16:39Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
- **Phase A review (2026-06-26): `SQLMathExpression` is in the build-excluded `sql/parser/`
  zone, and the lift crosses out of it.** The file is Spotless/ErrorProne/JaCoCo-excluded
  (`pom.xml:640/124/1077/1097`) and carries the JJTree header, but it is a hand-maintained
  node class the build does not regenerate (verified: `MULTI=true` generates only when
  absent; 192/220 parser files are hand-edited), so editing the `Operator` body is safe and
  established. The new `NumericOps` in `sql/util/` is outside all three exclusions, so the
  lifted engine faces ErrorProne (old-style `instanceof`+cast → likely `Xep` ERROR = compile
  break) and the 85/70 coverage gate for the first time — neither visible to the "math tests
  stay green" runtime gate. Plan of Work and Validation now require ErrorProne-clean
  modernization, a hand-formatted parser edit, characterization tests, and the coverage gate.
  (T2/R1/R2/A3.)
- **Phase A review (2026-06-26): D17's "two-hop dispatch chain" is factually three hops for
  9 of 12 constants.** STAR/SLASH/REM/PLUS/LSHIFT/RSHIFT/RUNSIGNEDSHIFT/BIT_AND/BIT_OR route
  per-constant `apply(Object,Object)` → `super.apply` → base `apply(Object,Object)`
  (`:568-580`) → widening → typed overload (PLUS via its numeric branch at `:210`; gate-verify
  T5 corrected the original count of 8, which mis-grouped PLUS as direct). The decision (perf-neutral, no new virtual
  indirection) survives — the `super` call resolves statically, so converting it to
  `NumericOps.applyObject(...)` is perf-neutral — but the lift must eliminate `super` into a
  static base helper. The track's Plan of Work / Invariants now state the real chain; the
  **`design.md` D17 "two-hop" wording correction is deferred to the Phase-4 `design-final`
  reconciliation** (design frozen), mirroring Track 1's D1 handling. (T3/A1.)
- **Phase A review (2026-06-26): the moving surface is wider than the original inventory.**
  `apply(Object,Object)` is overridden per-constant on all 12 constants (distinct
  null/Date/String handling), not a single shared fallback; the "five typed overloads + one
  fallback" inventory in D5-R / Interfaces undercounts. The enum must retain its public typed
  and `apply(Object,Object)` signatures because `MathExpressionTest` binds to them directly.
  The **D5-R inventory wording correction is deferred to `design-final`** (design frozen);
  the track's C&O / Plan of Work / Interfaces carry the accurate inventory. (T4.)
- **Phase A review (2026-06-26): D17's open typed-overload-boundary choice resolved — the
  enum keeps the public surface.** The five typed overloads and per-constant
  `apply(Object,Object)` stay declared on `Operator` as thin delegators into `NumericOps`,
  because moving them off the enum would break `MathExpressionTest`'s direct calls (the
  acceptance gate itself). The "move overloads into `NumericOps`" arm is rejected for S0 as a
  gate-weakening edit. (A2/R4.)

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
- [x] Technical: PASS at iteration 3 (5 findings, 5 accepted) — T1 (acceptance-gate gap) +
  T2 (parser-zone exclusions) should-fix and T3 (3-hop chain) + T4 (moving-surface
  under-count, enum keeps signatures) suggestions applied; gate-verify surfaced T5 should-fix
  (the `super.apply` set is 9 not 8 — PLUS at `:210`), corrected against source and re-verified.
- [x] Risk: PASS at iteration 2 (5 findings, 5 accepted) — R1 (ErrorProne gate crossing) +
  R2 (coverage gate crossing) + R3 (acceptance-gate gap) should-fix applied; R4 (D17 boundary
  not free) suggestion applied; R5 (S1 JMH deferral sound) affirming, recorded.
- [x] Adversarial: PASS at iteration 2 (5 findings, 5 accepted) — A1 (3-hop chain) + A2 (typed
  overloads bind to enum) + A3 (parser-zone edit special) should-fix applied; A4 (Track-2
  independence confirmed) + A5 (D13 sizing survives) affirming suggestions recorded. Ran on
  the session default (opus); D14's `full → Fable 5` pin degraded to opus (this host has no
  Fable), the documented degradation.

## Context and Orientation
The target package `core/.../sql/util/`
(`com.jetbrains.youtrackdb.internal.core.sql.util`) does not exist on develop — confirmed
absent via PSI. The new `NumericOps` class is greenfield; the only existing class this
track modifies is `SQLMathExpression` (`core/.../sql/parser/SQLMathExpression.java`), whose
inner enum `Operator` holds the promotion engine.

**`SQLMathExpression` is in the build's excluded `sql/parser/` zone (Phase A review).** The
file carries the JJTree node-class header and lives under `internal/core/sql/parser/`, which
`pom.xml` excludes from Spotless (`:640`), ErrorProne (`:124`), and JaCoCo (`:1077`,
`:1097`). It is nonetheless a hand-maintained node class the build does **not** regenerate
(verified: with `MULTI=true` JJTree generates a node only when the file is absent, and
192 of 220 parser files are hand-edited and committed), so editing the hand-written
`Operator` body is safe and established — the CLAUDE.md "do not edit `sql/parser/`" tip is
overridden for these hand-maintained node classes. Two consequences the extraction must
honor: the `Operator.apply` delegator edit gets **no** Spotless formatting and **no**
ErrorProne checking, so it must be hand-formatted to the 2-space / 100-col house style; and
the new `NumericOps.java` under `sql/util/` is outside all three exclusions, so it **is**
subject to the full ErrorProne ERROR set and the 85/70 changed-code coverage gate (see
`## Validation and Acceptance`).

The engine as it stands on develop (PSI-confirmed): `SQLMathExpression.Operator` is an
inner enum with the 12 constants above. Each constant overrides `apply(Object, Object)` with
its own per-constant body (null/Date/String handling differs per operator — it is **not** a
single shared fallback) and implements the five abstract typed-pair overloads
(`apply(Integer,Integer)`, `apply(Long,Long)`, `apply(Float,Float)`, `apply(Double,Double)`,
`apply(BigDecimal,BigDecimal)`). The shared engine underneath is the base
`apply(Object, Object)` (`:568-580`), the widening entry `apply(Number, Operator, Number)`,
and the private `toLong`. The real dispatch chain is **three hops for 9 of the 12
constants**: STAR, SLASH, REM, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, BIT_OR null-check in
their own `apply(Object,Object)` then call `super.apply(...)`, and PLUS does the same for its
numeric branch (`:210`) after handling `Date`/`String` operands in its own body — each
routing `super.apply(...)` → base `apply(Object,Object)` (`:568-580`) → widening entry →
typed overload. Only MINUS, XOR, NULL_COALESCING reach the widening entry / typed overloads
directly from their own body (no `super`). (Phase A gate-verify T5: the `super.apply` set is
9, not 8 — PLUS was originally mis-grouped as direct.) `SQLMathExpression` itself stores
arithmetic as a flat n-ary list (`childExpressions: List<SQLMathExpression>`,
`operators: List<Operator>`) and resolves precedence at evaluate time via
`calculateWithOpPriority` / `iterateOnPriorities`. This track touches only the promotion
engine; the precedence fold and the flat-list shape are left untouched (Track 3 reads them,
Track 3 does not modify them either).

`MathExpressionTest` binds directly to the enum: `testTypes` calls the public typed overloads
(`op.apply(1, 1)`, `op.apply(1L, 1L)`, …, `PLUS.apply(Integer.MAX_VALUE, 1)`) and
`apply(Object, Object)` on `Operator` constants. Whatever moves into `NumericOps`, the enum
must keep those public typed and `apply(Object, Object)` signatures reachable (delegating
bodies are fine), or those test call sites break at compile time.

This track is AST-side only. It has no dependency on the `AnalyzedExpr` substrate (Track 1)
and does not read or produce IR (Phase A adversarial PSI-confirmed: neither `sql/util/` nor
`sql/parser/` imports or is imported by `query/analyzed/`). Track 4's evaluator is the
downstream consumer that will delegate `+ - * /` to the same `NumericOps`, but Track 4
depends on this track, not the reverse.

## Plan of Work
The extraction is a lift-and-shift, sequenced so the AST keeps working throughout. Phase A
review (T1–T4, R1–R5, A1–A3) refined the original four steps; the boundary choices D17 left
open are resolved here:

1. Create `core/.../sql/util/NumericOps.java` as a `final` class with a private constructor
   and an all-static surface. Lift the shared promotion engine into it: a static base entry
   `applyObject(Operator, Object, Object)` (the lifted form of the enum's base
   `apply(Object, Object)` at `:568-580`), the widening entry
   `apply(Number, Operator, Number)`, the per-operator value logic, the typed-pair value
   methods, and the `toLong` helper. **Modernize as you lift:** `sql/util/` is subject to
   ErrorProne (unlike the `sql/parser/` source), so rewrite the old-style
   `instanceof`-plus-cast sites to pattern-matching `instanceof` and clear any other
   `Xep:*:ERROR` (e.g. OperatorPrecedence, MixedMutabilityReturnType) before the step is
   done — a `Xep` error is a compile failure the math-test gate cannot see (R1).
2. **Typed-overload boundary (D17), resolved: the enum keeps the public surface.** The five
   typed `apply` overloads and the per-constant `apply(Object, Object)` stay declared on
   `Operator` (the existing `MathExpressionTest` call sites bind to them — A2/R4), but their
   bodies become thin delegators into `NumericOps`. The nine `super.apply(...)` callbacks
   (STAR, SLASH, REM, PLUS, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, BIT_OR — PLUS delegates
   its numeric branch via `super` at `:210` after handling `Date`/`String`) are rewritten to
   call `NumericOps.applyObject(this, left, right)` in place of `super` — there is no
   superclass call once the base logic is static (A1/T3/T5). This keeps the dispatch perf-neutral: the
   per-constant first hop stays, the former `super`/widening hops become static monomorphic
   calls into `NumericOps`, and no new virtual indirection enters the hot path.
3. Rewrite `SQLMathExpression.Operator`'s `apply` family as thin delegators into
   `NumericOps`, keeping the per-constant value semantics observable exactly as before.
   Hand-format the edited `SQLMathExpression.java` to the 2-space / 100-col house style —
   Spotless will not touch the `sql/parser/` zone (A3/T2).
4. **Add characterization tests** for the promotion families the existing suite does not pin
   (T1/R3): integer-divide widening (`SLASH` with non-zero remainder → `Double`, zero
   remainder → the integer type), `+ - * /` null propagation (`null + x = x`,
   `null - x = 0 - x`, `null * x = null`, `null / x = null`), `Date + Long` / `Long + Date`
   / `Date - Long` → `Date`, and `String` concatenation via `PLUS`. Write them against
   `develop` first (red-confirms they exercise the engine), then keep them green after the
   delegation. These tests also cover the new `NumericOps` branch surface so the file clears
   the 85/70 changed-code coverage gate (R2).
5. Run the existing math-test suite plus the new characterization tests and confirm both
   stay green; confirm `./mvnw -pl core spotless:check` is clean and `NumericOps` compiles
   clean under ErrorProne (see `## Validation and Acceptance`).

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
1. Lift the whole `SQLMathExpression.Operator` promotion engine into a new all-static `NumericOps` (`core/.../sql/util/`), rewrite the enum's `apply` family — including the nine `super.apply` callbacks (STAR, SLASH, REM, PLUS, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, BIT_OR) → `NumericOps.applyObject` — as delegators keeping the enum's public typed + `apply(Object,Object)` signatures (D17), modernize `instanceof`+cast for ErrorProne, hand-format the `sql/parser/` edit, and add characterization tests for divide-widening / `+ - * /` null-propagation / `Date ± Long` / `String` concat; full sequence in `## Plan of Work`, gates in `## Validation and Acceptance`. — risk: high (performance hot path: modifies the AST arithmetic dispatch the precedence fold runs per evaluation)  [ ]

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
The acceptance gate is behavioral plus two build gates the runtime tests cannot see. Phase A
review (T1/R3) corrected the original "the existing suite pins all four families" claim:

- **What the existing suite already pins (verified):** null propagation and `String`
  concatenation — `DocValidationTest.testSyntaxNullInPlus/Minus/Multiply/Divide` and
  `testSyntaxStringConcatenation` drive these through `SQLMathExpression.execute` →
  `Operator.apply(Object, Object)`.
- **What it does NOT pin (verified):** integer-divide widening (`testTypes` exercises
  `SLASH` only with `1/1`, remainder zero) and `Date` arithmetic — the `QueryOperator*Test`
  Date tests drive `QueryOperatorPlus`/`QueryOperatorMinus`, a **separate** operator
  implementation that never touches `SQLMathExpression.Operator`, so green there says
  nothing about the lifted code (false comfort).
- **Therefore the gate requires the new characterization tests** (Plan of Work step 4)
  covering divide-widening, `+ - * /` null propagation, `Date ± Long`, and `String` concat;
  they stay green after the delegation, which makes the gate genuinely self-verifying for the
  four families the engine exists to centralize.
- **Build gates:** `./mvnw -pl core spotless:check` is clean (the hand-formatted
  `SQLMathExpression.java` parser-zone edit), `NumericOps` compiles clean under ErrorProne
  (no `Xep:*:ERROR`), and changed code clears the 85/70 line/branch coverage gate. These are
  compile- and coverage-time gates the green-math-tests runtime gate structurally cannot
  detect (R1/R2).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope:**
- New `core/.../sql/util/NumericOps.java` — `final`, private constructor, all-static. Holds
  the shared promotion engine: a static base entry `applyObject(Operator, Object, Object)`
  (the lifted base `apply(Object, Object)`), the widening entry `apply(Number, op, Number)`,
  the per-operator value logic, the typed-pair value methods, and the private `toLong`.
  (Phase A: `apply(Object, Object)` is per-constant on all 12 constants, not one shared
  fallback — T4; D17 resolved so the enum keeps its public typed + `apply(Object, Object)`
  signatures as delegators — A2/R4.)
- Modify `SQLMathExpression.Operator`'s `apply` family
  (`core/.../sql/parser/SQLMathExpression.java`) to delegate into `NumericOps`, rewriting the
  nine `super.apply(...)` callbacks (incl. PLUS's numeric branch at `:210` — T5) to
  `NumericOps.applyObject(...)`. Hand-formatted (the parser zone is Spotless-excluded — A3).
- Math-test verification plus new characterization tests for divide-widening, null
  propagation, `Date ± Long`, and `String` concat (T1/R3), and the ErrorProne + 85/70
  coverage build gates on `NumericOps` (R1/R2).

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

**Sizing justification (argumentation gate).** This track is ~3-4 files (new `NumericOps`,
the `SQLMathExpression.Operator` edit, and the characterization tests — folded into the
existing `MathExpressionTest` or a focused new test), which is under the merge floor, but
D13 keeps it separate: it is an AST-side refactor with its own review surface and its own
acceptance gate, separately mergeable from the new IR work. Folding it into Track 3 would
couple an AST refactor with new IR lowering — two unrelated review surfaces in one diff.
Track 4 is the **sole** consumer of `NumericOps`, yet the extraction is still kept separate
from Track 4 *because* it modifies live production code on the AST arithmetic hot path (the
parser-zone edit), which must be reviewable and revertable independently of the greenfield IR
evaluator (Phase A A5). The stacked-diff series lands the extraction as its own reviewable
PR.

## Invariants & Constraints
<!-- Per-track testable constraints and invariants; each a property backed by a test. -->
- **Math semantics preserved exactly.** Integer-divide widening, null propagation, `Date +
  Long`/`Long + Date`/`Date - Long`, and `String` concatenation behave identically after
  `Operator.apply` delegates to `NumericOps` — verified by the existing math-test suite plus
  the new characterization tests (Plan of Work step 4) staying green. (Phase A T1/R3: the
  existing suite alone pins only null propagation and `String` concat; the characterization
  tests cover divide-widening and `Date` arithmetic.)
- **No new virtual indirection on the hot path.** The lift adds only monomorphic static
  calls into `NumericOps` that inline. The existing dispatch — a per-constant first hop, then
  (for 9 of 12 constants) `super.apply` → base `apply(Object, Object)` → widening entry →
  typed overload — keeps its shape, with the former `super`/base hops becoming static calls
  into `NumericOps.applyObject(...)` (Phase A A1/T3/T5: the chain is three hops for those 9,
  not the two D17 originally stated). No new *virtual* dispatch enters the path; verified by code
  review of the delegation shape, runtime measurement deferred to S1's LDBC JMH gate.
- **`NumericOps` clears the build gates** the parser zone exempted the engine from: it
  compiles clean under ErrorProne (no `Xep:*:ERROR`) and changed code meets the 85/70
  coverage gate (Phase A R1/R2) — verified at the build, not by the runtime math tests.
