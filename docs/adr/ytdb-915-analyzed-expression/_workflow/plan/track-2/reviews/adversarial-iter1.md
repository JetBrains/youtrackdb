<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
<!-- review-file schema: conventions-execution.md §2.5 -->

# Adversarial review — Track 2: NumericOps whole-enum extraction (iter1)

<!--MANIFEST
role: reviewer-adversarial
phase: 3A
track: 2
iteration: 1
verdict: should-fix
findings: 5
blockers: 0
should_fix: 3
suggestions: 2
index:
  - id: A1
    sev: should-fix
    anchor: "#a1-should-fix"
    loc: "core/.../sql/parser/SQLMathExpression.java:82-87,568-580 (super.apply chain)"
    cert: "Violation: No new virtual indirection on the hot path"
    basis: psi
  - id: A2
    sev: should-fix
    anchor: "#a2-should-fix"
    loc: "MathExpressionTest.java:48-65 (direct typed-overload calls on Operator)"
    cert: "Assumption: D17 typed-overload boundary is free to move overloads off the enum"
    basis: psi
  - id: A3
    sev: should-fix
    anchor: "#a3-should-fix"
    loc: "core/.../sql/parser/SQLMathExpression.java (JJTree header; pom.xml:640 Spotless exclude)"
    cert: "Assumption: editing Operator.apply is an ordinary src-tree edit"
    basis: psi
  - id: A4
    sev: suggestion
    anchor: "#a4-suggestion"
    loc: "plan/track-2.md (D5/D5-R independence claim) vs plan/track-1.md episode"
    cert: "Assumption: Track 2 is independent of Track 1's substrate"
    basis: psi
  - id: A5
    sev: suggestion
    anchor: "#a5-suggestion"
    loc: "plan/track-2.md §Interfaces (sizing justification, ~3 files)"
    cert: "Challenge: D13 keep-separate vs fold-into-Track-3"
    basis: reasoning
evidence_base: >
  Five certificates. Two invariant violation scenarios (math semantics =
  INFEASIBLE/survives; no-new-indirection = the stated two-hop chain is
  actually three hops for 8 of 12 constants via super.apply, so the diagram
  is wrong but the perf claim survives). Three assumption tests (D17
  boundary constrained by a direct-call test = FRAGILE; parser-dir/Spotless
  edit zone = FRAGILE; Track-1 independence = HOLDS). One scope challenge
  (D13 keep-separate survives).
MANIFEST-->

## Findings

### A1 [should-fix]
**Certificate**: Violation scenario — "No new virtual indirection on the hot path"
**Target**: Invariant ("No new virtual indirection on the hot path") and D17 dispatch-chain rationale
**Challenge**: D17 and the track invariant describe the existing dispatch as a
**two-hop** chain — `operator.apply(Object,Object)` → `apply(Number,Operator,Number)`
widening entry → typed overload — and promise the lift preserves "the two-hop
dispatch shape." That description is wrong for 8 of the 12 constants. STAR, SLASH,
REM, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, and BIT_OR override
`apply(Object,Object)` only to null-check and then call `super.apply(left, right)`
(`SQLMathExpression.java:86, 127, 162, 306, 344, 382, 418, 505`). `super.apply`
resolves to the **base** enum `apply(Object,Object)` at `:568-580`, which then calls
`apply((Number)left, this, (Number)right)` at `:576`. So the real chain for those
constants is **three hops**: constant `apply(Object,Object)` → base `apply(Object,Object)`
(via `super`) → widening entry → typed overload. The whole-enum lift cannot carry a
`super` call into an all-static `final class NumericOps` (no superclass to call).
Step 1 of Plan of Work ("Lift the promotion engine into it") and the invariant both
omit this entirely. Implementation will discover mid-step that the base
`apply(Object,Object)` must become a separate static method and every constant's
`super.apply(...)` must be rewritten to call it — execution churn the track did not
budget.
**Evidence**: PSI ref search on `apply(Object,Object)` returned 24 sites; 9 of the
in-`SQLMathExpression` sites are the `super.apply`/recursive `apply` calls listed
above. Base `apply(Object,Object)` body at `SQLMathExpression.java:568-580`.
**Survival test**: The **perf** claim survives — moving a three-hop chain wholesale
into `NumericOps` with a monomorphic static delegator in front adds no new *virtual*
dispatch (the `super.apply` resolves statically at compile time; rewriting it to a
static call is perf-neutral or faster). But the *invariant text and D17 diagram are
factually wrong* (they say two-hop), and Step 1's one-line "lift the engine" hides a
non-trivial `super`-elimination rewrite. **Proposed fix**: Correct the invariant and
D17 to state the real three-hop-for-eight-constants shape, and add an explicit Plan-of-Work
sub-step: "rewrite each constant's `super.apply(...)` to call the lifted static base
helper `NumericOps.applyObject(Operator, Object, Object)` (or equivalent); the base
enum `apply(Object,Object)` becomes that static helper." Without it the "two-hop
preserved" acceptance phrasing cannot even be checked, because the premise is false.

### A2 [should-fix]
**Certificate**: Assumption test — "D17 typed-overload boundary is free to move overloads off the enum"
**Target**: D17 (the open typed-overload-boundary choice) and Plan-of-Work step 2
**Challenge**: D17 hands the track an open choice: the five typed `apply` overloads
"either move into `NumericOps`, or stay on the enum with `NumericOps` calling back."
Option A (move the typed overloads into `NumericOps`, removing them from `Operator`)
**breaks the acceptance gate itself**. `MathExpressionTest.testTypes`
(`MathExpressionTest.java:48-65`) calls the typed overloads directly on enum
constants — `op.apply(1, 1)`, `op.apply(1L, 1L)`, `op.apply(1f, 1f)`,
`op.apply(1d, 1d)`, `op.apply(BigDecimal.ONE, BigDecimal.ONE)`, plus
`PLUS.apply(Integer.MAX_VALUE, 1)` and `MINUS.apply(Integer.MIN_VALUE, 1)` at
`:69, :71`. These are compile-time-bound to `Operator`'s public abstract
`apply(Integer,Integer)` etc. If those overloads are *removed* from the enum, the
test stops compiling — so the "math tests stay green" gate cannot run, and a naive
implementer might "fix" it by editing the test, silently weakening the gate. The
typed overloads are therefore **not free to move**; D17 presents two options as
equivalent when one is constrained by the very test that defines acceptance.
**Evidence**: PSI ReferencesSearch on each typed `apply` overload returned exactly
one EXTERNAL caller each — `MathExpressionTest.java:48/52/53/54/55` — all on
`Operator` constants, all compile-time-bound to the enum's abstract signatures.
**Survival test**: D17 survives as a *decision to make a decision*, but the option
space is asymmetric and the track does not say so. **Proposed fix**: State in D17 /
step 2 that the public typed overloads must remain reachable on `Operator` (the enum
keeps them, delegating into `NumericOps`, OR they move to `NumericOps` *and*
`MathExpressionTest` is intentionally re-pointed to `NumericOps` as a documented
gate change). Pick the enum-keeps-overloads form as the default so the existing
acceptance gate compiles unchanged; flag the test-re-point form as a gate-weakening
edit requiring explicit justification. Otherwise the "self-verifying" gate is not
self-verifying under option A.

### A3 [should-fix]
**Certificate**: Assumption test — "editing Operator.apply is an ordinary src-tree edit"
**Target**: Assumption (the track treats `SQLMathExpression` as a normal editable class)
**Challenge**: `SQLMathExpression.java` lives under `core/.../sql/parser/` — the one
package the project marks off-limits. CLAUDE.md tip #3: "Don't edit files in
`core/.../sql/parser/` — they are generated from `YouTrackDBSql.jjt`." The file
carries the JJTree header (`Generated By:JJTree`) and the `OriginalChecksum` footer,
the package is excluded from Spotless (`pom.xml:640
<exclude>**/internal/core/sql/parser/**</exclude>`), from ErrorProne
(`pom.xml:124 -XepExcludedPaths:...|.*/internal/core/sql/parser/.*`), and from
JaCoCo. The track's Context section never mentions any of this — it presents
`SQLMathExpression` as the single existing class to modify, as if it were ordinary
source. Two concrete consequences the track does not budget for: (1) the modified
`Operator.apply` delegator gets **no Spotless formatting and no ErrorProne checks**,
so the team convention "run `spotless:apply` before commit" is a silent no-op here —
the diff must be hand-formatted to the 2-space/100-col style or it diverges from the
rest of the file; (2) a reviewer applying the literal CLAUDE.md rule will flag the
edit as forbidden.
**Evidence**: JJTree header at `SQLMathExpression.java:1`; checksum footer at
`:1400`; Spotless/ErrorProne/JaCoCo excludes at `pom.xml:640, 124, 1077, 1097`;
javacc-maven-plugin writes into `src/main/java` with `interimDirectory ==
outputDirectory` (`core/pom.xml:235-236`).
**Survival test**: The edit itself is **safe from regeneration** — verified: with
`MULTI=true` (`YouTrackDBSql.jjt:23`) JJTree generates a node class only when the
file is absent, and 192 of 220 parser files carry the JJTree header yet are
hand-edited and committed (the whole `Operator` engine is hand-written), so a
`clean package` will not clobber the delegator. So the assumption "the edit
persists" HOLDS. But the assumption "this is an ordinary edit subject to the normal
format/lint pipeline" is FRAGILE. **Proposed fix**: Add one Context sentence:
"`SQLMathExpression` is in the Spotless/ErrorProne-excluded `sql/parser/**` zone and
carries the JJTree header; editing the hand-written `Operator` body is the
established pattern (192 hand-edited node files), but the delegator change must be
hand-formatted to house style because Spotless will not touch it, and the CLAUDE.md
'do not edit parser dir' tip is overridden here by the file being a hand-maintained
node class." This pre-empts a reviewer false-positive and the unformatted-diff trap.

### A4 [suggestion]
**Certificate**: Assumption test — "Track 2 is independent of Track 1's substrate"
**Target**: D5 / D5-R / Context independence claim
**Challenge**: The track asserts three times that it is "AST-side only," has "no
dependency on the `AnalyzedExpr` substrate (Track 1)," and "does not read or produce
IR." If false, the dependency graph and the parallel-execution assumption are wrong.
**Evidence**: Verified against Track 1's realized output. The Track 1 episode
(`implementation-plan.md:69-71`, `plan/track-1.md`) shipped the IR into
`core/.../query/analyzed/` (sealed `AnalyzedExpr` + 5 nested records, visitor/transform,
`UnsupportedAnalyzedNodeException`). Track 2's only new file is in
`core/.../sql/util/` (PSI: package absent, `NumericOps` absent, no existing
`internal.core.sql.util` reference anywhere) and its only modified file is
`SQLMathExpression` in `sql/parser/`. Neither path imports nor is imported by
`query/analyzed/`. The Component-Map edge `EV --> NO` runs from Track 4 to
`NumericOps`, i.e., the consumer depends on Track 2, not the reverse — consistent
with the claim.
**Survival test**: HOLDS. The independence claim is true: Track 2 consumes nothing
from the Track 1 substrate, and Track 1's only recorded forward-constraint
(`FuncCall.args()` read-only convention, for Tracks 3/4) does not touch arithmetic
promotion. **Proposed fix**: None required — the claim is correct. Recorded as a
confirmed assumption so a later reader does not re-challenge it. (Severity
suggestion: the certificate strengthens rationale rather than changing the plan.)

### A5 [suggestion]
**Certificate**: Challenge — D13 keep-separate vs fold-into-Track-3
**Target**: D13 sizing justification (~3-file track, under the merge floor)
**Chosen approach**: Keep `NumericOps` extraction as its own ~3-file track despite
being under the ~12-file merge floor.
**Best rejected alternative**: Fold it into Track 3 (lowering) or Track 4
(evaluator, the actual `NumericOps` consumer).
**Counterargument trace**: (1) The track is under-floor (~3 files: new `NumericOps`,
the `SQLMathExpression.Operator` edit, the math-test run). The planner's own merge
rule says an under-floor track "that folds into a neighbor is a merge candidate."
(2) Track 4 is the *only* consumer of `NumericOps` (Component-Map edge `EV --> NO`,
`implementation-plan.md:56-57`); folding the extraction into Track 4 would co-locate
the shared helper with its sole user. (3) Outcome difference: one fewer PR in the
stacked series.
**Evidence**: `implementation-plan.md` Checklist scope tags — T2 ~3 files, T3 ~4,
T4 ~4; T4 "Depends on: Track 1, Track 2, Track 3."
**Survival test**: YES — the keep-separate decision survives. The track's sizing
justification is sound: it has a distinct review surface (an AST-side refactor of
live production code in the parser zone) and a distinct self-verifying acceptance
gate (existing math tests green) that is orthogonal to the new-IR review surface of
Tracks 3/4. Folding into Track 4 would bundle an AST-side refactor that touches the
hot arithmetic path with brand-new IR-evaluator code — two unrelated review surfaces
and two unrelated failure modes in one diff, exactly the coupling D13 cites. The
under-floor exemption is the intended use of the "written justification" clause.
**Proposed fix**: None — keep as is. Optional: the justification could add one line
noting Track 4 is the sole consumer yet still kept separate *because* the extraction
modifies live production code (the parser-zone edit) that must be reviewable and
revertable independently of the greenfield IR evaluator.

## Evidence base

#### Violation scenario: No new virtual indirection on the hot path (A1)
- **Invariant claim**: The added `NumericOps` delegation is a monomorphic static
  call that inlines; the existing **two-hop** `operator.apply` → typed `apply`
  re-dispatch stays intact.
- **Violation construction**:
  1. Start state: develop `SQLMathExpression.Operator` on the AST arithmetic path
     (`execute` → `calculateWithOpPriority` → `iterateOnPriorities` →
     `operatorsStack.poll().apply(left, right)`, `SQLMathExpression.java:700, 721,
     745, 775, 811, 822`).
  2. Action sequence: evaluate `a * b` with two `Integer`s. Dispatch enters STAR's
     `apply(Object,Object)` (`:82`), which null-checks then calls
     `super.apply(left, right)` (`:86`) → base `apply(Object,Object)` (`:568`) →
     `apply((Number)left, this, (Number)right)` widening entry (`:576`) → typed
     `apply(Integer,Integer)` (`:589` site, STAR body `:56`).
  3. Intermediate state: the live chain is **three hops** for STAR/SLASH/REM/
     LSHIFT/RSHIFT/RUNSIGNEDSHIFT/BIT_AND/BIT_OR, not two.
  4. Violation point: the invariant *text* and D17 *diagram* claim two hops
     (`track-2.md:96-98, 234-235`). The lift also cannot carry the `super.apply`
     call into an all-static class.
  5. Observable consequence: not a runtime bug — the perf claim still holds — but
     the stated invariant is unverifiable as written (false premise), and Plan-of-Work
     step 1 hides a `super`-elimination rewrite.
- **Feasibility**: CONSTRUCTIBLE (the chain is exercised on every mixed/typed AST
  multiply; the mis-description is in the plan text).

#### Violation scenario: Math semantics preserved exactly (context — survives)
- **Invariant claim**: integer-divide widening, null propagation, `Date ± Long`,
  `String` concat behave identically after delegation.
- **Violation construction attempt**: a lift-and-shift that copies the exact bodies
  (e.g. SLASH `left % right == 0` branch `:92-95`; PLUS overflow-to-Long `:168-174`;
  PLUS `Date`/`String`/null arms `:199-217`; `toLong` `:541-549`) changes no
  semantics; the existing `MathExpressionTest` (`testTypes`, plus the priority/
  evaluation tests) pins the result *types* and overflow upgrades.
- **Feasibility**: INFEASIBLE to violate *silently* — any promotion divergence
  surfaces as a `MathExpressionTest` failure (the self-verifying gate). The only way
  to violate-and-pass is to also edit the test (see A2). Invariant survives.

#### Assumption test: D17 typed-overload boundary is free to move (A2)
- **Claim**: the five typed overloads may move into `NumericOps` or stay on the enum,
  equivalently.
- **Stress scenario**: choose "move into `NumericOps`," remove the abstract
  overloads from `Operator`.
- **Code evidence**: `MathExpressionTest.java:48-65, 69, 71` call
  `op.apply(1,1)` / `op.apply(1L,1L)` / `op.apply(1f,1f)` / `op.apply(1d,1d)` /
  `op.apply(BigDecimal.ONE, BigDecimal.ONE)` directly on `Operator` constants;
  PSI shows each typed overload has exactly one external caller, this test.
- **Verdict**: FRAGILE — option A breaks compilation of the acceptance test; the two
  options are not equivalent.

#### Assumption test: editing Operator.apply is an ordinary src-tree edit (A3)
- **Claim**: modifying `SQLMathExpression` is a normal source edit.
- **Stress scenario**: run the standard pre-commit pipeline (`spotless:apply`,
  ErrorProne) and apply the CLAUDE.md "do not edit parser dir" rule literally.
- **Code evidence**: JJTree header `:1` + checksum `:1400`; Spotless exclude
  `pom.xml:640`; ErrorProne exclude `pom.xml:124`; JaCoCo excludes `pom.xml:1077,
  1097`; javacc plugin out-dir = `src/main/java` (`core/pom.xml:235-236`);
  `MULTI=true` (`YouTrackDBSql.jjt:23`); 192/220 parser files hand-edited with the
  JJTree header.
- **Verdict**: FRAGILE — the edit persists (no regen clobber) but escapes the
  format/lint pipeline and trips the literal "do not edit parser dir" tip; the track
  is silent on both.

#### Assumption test: Track 2 independent of Track 1 (A4)
- **Claim**: AST-side only; no dependency on the `AnalyzedExpr` substrate.
- **Stress scenario**: look for any `query/analyzed` <-> `sql/util` / `sql/parser`
  coupling introduced by Track 2.
- **Code evidence**: `sql.util` package + `NumericOps` absent (PSI); no existing
  `internal.core.sql.util` reference (grep); Track 1 shipped to `query/analyzed/`
  (`implementation-plan.md:69-71`); Component-Map consumer edge `EV(T4) --> NO(T2)`.
- **Verdict**: HOLDS.

#### Challenge: D13 keep-separate vs fold-into-Track-3/4 (A5)
- **Chosen**: keep ~3-file extraction as its own track.
- **Best rejected alternative**: fold into Track 4 (sole consumer of `NumericOps`).
- **Counterargument**: under merge floor; sole consumer is T4; one fewer PR if folded.
- **Codebase evidence**: scope tags `implementation-plan.md` Checklist; T4 deps line.
- **Survival test**: YES — distinct review surface (live parser-zone refactor) and a
  distinct, orthogonal acceptance gate (math tests) justify the under-floor
  exemption; folding would couple an AST hot-path refactor with greenfield IR-eval
  code.
