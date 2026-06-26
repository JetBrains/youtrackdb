<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
<!-- review-file schema: conventions-execution.md §2.5 (verdict-producer variant) -->

# Adversarial gate verification — Track 2: NumericOps whole-enum extraction (iter2)

<!--MANIFEST
role: reviewer-adversarial
phase: 3A
track: 2
iteration: 2
kind: verdict-producer
overall: PASS
findings: 0
blockers: 0
should_fix: 0
suggestions: 0
verdicts:
  - id: A1
    sev: should-fix
    verdict: VERIFIED
    cert: "super.apply confirmed at 8 constants (+1 NULL_COALESCING variant); base apply(Object,Object) at :568-580, widening at :576 — three-hop chain real; track now states it + step 2 rewrites super.apply to NumericOps.applyObject; perf decision survives"
    basis: psi
  - id: A2
    sev: should-fix
    verdict: VERIFIED
    cert: "MathExpressionTest.testTypes (L34) binds typed overloads directly on Operator constants (L48-65,L69,L71); D17 resolved enum-keeps-public-surface in C&O + Plan step 2; gate compiles unchanged"
    basis: psi
  - id: A3
    sev: should-fix
    verdict: VERIFIED
    cert: "JJTree header at SQLMathExpression.java:1; parser-zone excludes confirmed pom.xml:124(ErrorProne)/640(Spotless)/1077,1097(JaCoCo); track C&O parser-zone paragraph + step 3 hand-format + Validation build gates added"
    basis: psi
  - id: A4
    sev: suggestion
    verdict: VERIFIED
    cert: "PSI-confirmed independence recorded in C&O (no query/analyzed <-> sql/util|sql/parser coupling); affirming, no action required"
    basis: psi
  - id: A5
    sev: suggestion
    verdict: VERIFIED
    cert: "Sizing justification adds the sole-consumer-yet-separate line (Interfaces §); D13 keep-separate survives; affirming"
    basis: reasoning
evidence_base: >
  Five iter1 findings re-checked, all VERIFIED. Three should-fix (A1/A2/A3) had
  fixes applied across C&O / Plan of Work / Invariants / Surprises / Validation,
  each re-grounded against live code via PSI: super.apply count + base
  apply(Object,Object) body, MathExpressionTest typed-call sites, JJTree header +
  pom exclusions. Two suggestions (A4/A5) were affirming-only and are recorded.
  No regressions introduced by the fixes; no new findings. Overall PASS.
MANIFEST-->

## Verdicts

#### Verify A1: D17 "two-hop" is three-hop for 8 of 12; lift must eliminate super.apply
- **Original issue**: D17 + the track invariant described the existing dispatch as two-hop and promised "the two-hop dispatch shape" is preserved. False for STAR/SLASH/REM/LSHIFT/RSHIFT/RUNSIGNEDSHIFT/BIT_AND/BIT_OR, which route per-constant `apply(Object,Object)` → `super.apply` → base `apply(Object,Object)` → widening → typed overload (three hops). The lift into an all-static class cannot carry a `super` call; Plan step 1's one-line "lift the engine" hid a `super`-elimination rewrite.
- **Fix applied**: `## Context and Orientation` states the real chain (three hops for 8 of 12, two for PLUS/MINUS/XOR/NULL_COALESCING). `## Plan of Work` step 2 explicitly rewrites the eight `super.apply(...)` callbacks to `NumericOps.applyObject(this, left, right)`. `## Invariants & Constraints` reworded to the per-constant-first-hop + static-base shape. `## Surprises & Discoveries` records it and defers the design.md D17 wording fix to Phase-4 design-final (design frozen).
- **Re-check**:
  - Track-file location: C&O ¶3 (lines 169-185), Plan step 2 (218-222), Invariant 2 (341-347), Surprise entry (45-53).
  - Codebase (PSI): `SQLMathExpression.java` — `super.apply(` appears 9× at lines 86/127/162/210/306/344/382/418 (the eight plain `super.apply(left, right)` callbacks) plus 505 (the NULL_COALESCING null-defaulting variant). Base `apply(Object,Object)` body at :568-580, with the widening re-dispatch `apply((Number)left, this, (Number)right)` at :576. The three-hop description and the `super`-elimination requirement are both correct.
  - Criteria met: the invariant is now checkable (true premise); the perf decision survives unchanged (the `super` call resolves statically, so converting it to a static `NumericOps.applyObject` call is perf-neutral — no new virtual dispatch), and the survival test the iter1 finding ran is honored.
- **Regression check**: Checked that the rewrite does not contradict A2's "enum keeps public surface" resolution — it does not; the per-constant `apply(Object,Object)` overrides stay on the enum as delegators, only the `super`-target base logic becomes the static `applyObject`. Clean.
- **Verdict**: VERIFIED

#### Verify A2: typed overloads not free to move — tests bind to the enum
- **Original issue**: D17 presented "move overloads into NumericOps" and "keep on enum" as equivalent; the move arm breaks compilation of `MathExpressionTest.testTypes`, which calls the typed overloads directly on `Operator` constants, silently weakening the acceptance gate.
- **Fix applied**: D17 resolved in `## Plan of Work` step 2 — the enum keeps the public typed and `apply(Object,Object)` signatures as delegators; the "move overloads off the enum" arm is rejected for S0 as a gate-weakening edit. `## Context and Orientation` notes the `MathExpressionTest` direct binding. `## Surprises & Discoveries` records the resolution.
- **Re-check**:
  - Track-file location: Plan step 2 (216-222), C&O ¶4 (187-191), Interfaces in-scope note (296-298), Surprise entry (61-66).
  - Codebase (PSI): `MathExpressionTest.testTypes` at L34; direct typed-overload calls on enum constants at L48/50/52/53/54/55/57-65 (`op.apply(1,1)`, `op.apply(1L,1L)`, …, `op.apply(BigDecimal.ONE, BigDecimal.ONE)`) and `PLUS.apply(Integer.MAX_VALUE, 1)` / `MINUS.apply(Integer.MIN_VALUE, 1)` at L69/L71. All compile-time-bound to `Operator`'s public abstract overloads — confirming the move arm would break the gate.
  - Criteria met: the chosen enum-keeps-overloads form leaves the gate compiling unchanged; the asymmetric option space is now stated.
- **Regression check**: Checked consistency with A1 (delegators) and D5-R (whole-enum lift) — the engine bodies move to `NumericOps` while the enum retains delegating signatures; no contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify A3: parser-zone edit escapes format/lint; edit is safe-but-special
- **Original issue**: `SQLMathExpression` lives under `sql/parser/`, the build-excluded zone (Spotless/ErrorProne/JaCoCo), carries the JJTree header, and the literal CLAUDE.md "do not edit parser dir" tip applies. The track presented it as ordinary source — the delegator edit gets no auto-format/lint and a reviewer may false-flag it.
- **Fix applied**: `## Context and Orientation` parser-zone paragraph (hand-maintained node class, safe + established to edit, must hand-format, CLAUDE.md tip overridden for these node classes; the new `NumericOps` in `sql/util/` IS subject to ErrorProne + the coverage gate). `## Plan of Work` step 3 hand-format requirement. `## Validation and Acceptance` adds the Spotless-check / ErrorProne-clean / 85-70-coverage build gates. `## Surprises & Discoveries` entry.
- **Re-check**:
  - Track-file location: C&O ¶2 (155-167), Plan step 3 (223-226) + step 1 ErrorProne modernization (209-213), Validation build-gates bullet (274-278), Invariant 3 (348-350), Surprise entry (34-44).
  - Codebase: `SQLMathExpression.java:1` = `/* Generated By:JJTree: Do not edit this line. … */`; exclusions confirmed at `pom.xml:124` (ErrorProne `-XepExcludedPaths …/internal/core/sql/parser/…`), `pom.xml:640` (Spotless `**/internal/core/sql/parser/**`), `pom.xml:1077` and `:1097` (JaCoCo). All four cited lines accurate.
  - Criteria met: the safe-but-special nature, the hand-format requirement, and the `NumericOps`-is-checked asymmetry are all now stated; reviewer false-positive pre-empted.
- **Regression check**: Checked the added ErrorProne-clean requirement against the perf invariant (A1) — modernizing `instanceof`+cast to pattern-matching `instanceof` is behavior-preserving and does not alter the dispatch shape. Clean.
- **Verdict**: VERIFIED

#### Verify A4: Track-2 independence from Track-1 — affirming
- **Original issue**: None — the independence claim was challenged and HELD. Recorded so a later reader does not re-challenge it.
- **Fix applied**: `## Context and Orientation` ¶5 records the PSI-confirmed independence (neither `sql/util/` nor `sql/parser/` imports or is imported by `query/analyzed/`; Track 4 is the downstream consumer that depends on this track, not the reverse).
- **Re-check**:
  - Track-file location: C&O ¶5 (193-197); echoed in Purpose (24-25), Interfaces inter-track dependencies (318-319).
  - Current state: independence is recorded as a confirmed assumption, consistent with iter1's HOLDS verdict.
  - Criteria met: affirming finding is captured; no plan change was required.
- **Regression check**: n/a (no plan mutation). Clean.
- **Verdict**: VERIFIED

#### Verify A5: D13 keep-separate sizing — affirming
- **Original issue**: None — the keep-separate decision survived; an optional one-line strengthening was offered.
- **Fix applied**: `## Interfaces and Dependencies` sizing justification adds the line: Track 4 is the sole consumer yet the extraction is kept separate *because* it modifies live production code on the AST arithmetic hot path (the parser-zone edit), which must be reviewable and revertable independently of the greenfield IR evaluator.
- **Re-check**:
  - Track-file location: Sizing justification (321-331), specifically the "sole consumer … yet … kept separate" sentence (327-330).
  - Current state: the optional strengthening is recorded; D13 keep-separate rationale unchanged and sound.
  - Criteria met: affirming finding captured.
- **Regression check**: n/a (rationale-only addition). Clean.
- **Verdict**: VERIFIED

## Findings

<!-- No new findings surfaced by this verification pass. -->

## Summary

PASS. All five iter1 adversarial findings VERIFIED — the three should-fix fixes (A1/A2/A3) are applied correctly across the track's C&O / Plan of Work / Invariants / Validation / Surprises sections and re-grounded against live code via PSI (super.apply three-hop chain, MathExpressionTest typed bindings, JJTree header + pom exclusions all confirmed). The two affirming suggestions (A4/A5) are recorded as intended. No fix introduced a regression; no new findings.
