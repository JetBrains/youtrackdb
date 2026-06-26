<!-- MANIFEST
review_type: technical
iteration: 2
mode: verdict-producer
overall: FAIL
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
verdicts:
  - {id: T1, prior_sev: should-fix, verdict: VERIFIED, cert: "#### Verify T1 ", basis: "Validation section now splits pinned (null-prop via DocValidationTest.testSyntaxNullIn{Plus,Minus,Multiply,Divide}, String-concat via testSyntaxStringConcatenation — both confirmed runtime YQL tests routing through SQLMathExpression.execute -> Operator.apply) vs not-pinned (divide-widening; Date tests use separate QueryOperatorPlus/Minus that do NOT reference SQLMathExpression); Plan-of-Work step 4 adds characterization tests for all four families"}
  - {id: T2, prior_sev: should-fix, verdict: VERIFIED, cert: "#### Verify T2 ", basis: "Spotless exclude pom.xml:640, ErrorProne -XepExcludedPaths pom.xml:124, JaCoCo excludes pom.xml:1077/1097 all confirmed; sql.util confirmed absent (faces all three); track adds parser-zone paragraph + build gates (spotless:check clean, ErrorProne-clean NumericOps, 85/70 coverage) + hand-format step + Surprises entry"}
  - {id: T3, prior_sev: suggestion, verdict: VERIFIED, cert: "#### Verify T3 ", basis: "Three-hop chain stated in C&O/Invariants; step 2 adds super.apply -> NumericOps.applyObject rewrite; design.md D17 'two-hop' wording fix correctly deferred to Phase-4 design-final and recorded (Track-1 D1 precedent). NOTE: fix carries an undercount of the super-caller set — see new T5"}
  - {id: T4, prior_sev: suggestion, verdict: VERIFIED, cert: "#### Verify T4 ", basis: "PSI confirms apply(Object,Object) overridden on all 12 constants; inventory corrected in C&O/Interfaces; enum keeps public typed + apply(Object,Object) signatures as delegators (MathExpressionTest binds to them); D5-R wording deferred to design-final and recorded"}
index:
  - {id: T5, sev: should-fix, loc: "docs/adr/ytdb-915-analyzed-expression/_workflow/plan/track-2.md (## Plan of Work step 2, ## Context and Orientation, ## Invariants & Constraints)", anchor: "### T5 ", basis: "T3 fix lists 8 super.apply callers but PSI call-walk finds 9 — PLUS routes its Number+Number branch through super.apply and is mis-grouped as 'no super'; literal step-2 follow leaves PLUS's super.apply dangling (no superclass after base goes static in NumericOps) -> compile break"}
evidence_base: {certs: 5, psi_confirmed: true}
flags: [PSI_REACHABLE, NEW_FINDING]
-->

## Findings

### T5 [should-fix]
**Certificate**: V-T3 / V-T5 (PSI `JavaRecursiveElementVisitor` super-call walk over all 12 enum-constant bodies).
**Location**: Track 2 `## Plan of Work` step 2, `## Context and Orientation`, `## Invariants & Constraints` (the amended-file super-caller enumeration).
**Issue**: The T3 fix repaired the two-hop→three-hop chain wording, but it carried the iter1 finding's own miscount of the `super.apply` caller set. The amended track file states the `super.apply` callbacks to rewrite are exactly eight — "STAR, SLASH, REM, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, BIT_OR" — and explicitly puts PLUS in the no-`super` group ("PLUS, MINUS, XOR, NULL_COALESCING reach the widening entry / typed overloads directly from their own body (no `super`)").

A PSI call-expression walk over every constant body proves there are **nine** `super.apply` callers, not eight: STAR, SLASH, REM, **PLUS**, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, BIT_OR. PLUS's `apply(Object, Object)` routes its Number+Number branch through `super.apply(left, right)`:

```java
if (left instanceof Number && right instanceof Number) {
  return super.apply(left, right);   // PLUS, line ~178
}
```

Only MINUS, XOR, and NULL_COALESCING have zero `super.apply` calls (MINUS/XOR call `apply((Number) left, this, (Number) right)` directly; NULL_COALESCING is a ternary).

Step 2 is a literal rewrite list ("The eight `super.apply(...)` callbacks ... are rewritten to call `NumericOps.applyObject(this, left, right)`"). An implementer who follows it verbatim rewrites the eight named constants and leaves PLUS's `super.apply(left, right)` untouched. Once the base `apply(Object, Object)` is lifted into the all-static `NumericOps` (step 1), the enum no longer has a superclass `apply` to resolve, so PLUS's surviving `super.apply` is a compile error — and the parser-zone file is ErrorProne/Spotless-excluded, so nothing but `javac` catches it, late. This is the precise failure mode T2 warns about (the runtime math-test gate cannot see a compile-shape regression).
**Proposed fix**: Correct the super-caller enumeration to nine and add PLUS to step 2's rewrite list (and to the C&O / Invariants "three hops for N of 12" statements — it is 9 of 12, with PLUS's `super` confined to its Number+Number branch). Re-group PLUS out of the "no `super`" sentence. The deferred design-final D17 note inherits the same count, so the design-final reconciliation item should read "9 of 12" too.

## Verification certificates

#### Verify T1: acceptance gate now pins the four invariant families correctly
- **Original issue**: The track claimed the existing math-test suite pins divide-widening, `+ - * /` null propagation, `Date ± Long`, and `String` concat; iter1 traces (E1/E2/E3) showed the suite pins none of the four edge cases.
- **Fix applied**: `## Validation and Acceptance` rewritten to split "what the existing suite already pins (verified)" — null propagation and String concat — from "what it does NOT pin (verified)" — divide-widening and Date arithmetic. `## Plan of Work` step 4 adds characterization tests for all four families (write-red on develop, stay-green after delegation). `## Invariants & Constraints` and `## Purpose` updated to attribute divide-widening + Date to the new tests.
- **Re-check**:
  - Track location: `## Validation and Acceptance` lines ~258-278; `## Plan of Work` step 4 lines ~227-234.
  - Current state: null-prop pinned by `DocValidationTest.testSyntaxNullIn{Plus,Minus,Multiply,Divide}` and String-concat by `testSyntaxStringConcatenation` — PSI-confirmed these exist and (read) are runtime YQL tests (`SELECT v + n`, `'a' + 1 + 2 = "a12"`) that execute through `SQLMathExpression.execute → Operator.apply(Object,Object)`, so green there genuinely pins those two. The "does NOT pin" claim for Date is correct: `QueryOperatorPlus`/`QueryOperatorMinus` (PSI-confirmed at `...sql.operator.math.`) contain **no** reference to `SQLMathExpression`, so the `QueryOperator*Test` Date tests exercise a separate operator — the "false comfort" framing is accurate.
  - Criteria met: the gate no longer over-claims; the four families are now covered by an explicit decomposition step.
- **Regression check**: Checked the new step-4 wording against the build-gate section (T2) — consistent (the characterization tests double as the NumericOps coverage source for the 85/70 gate). Clean.
- **Verdict**: VERIFIED

#### Verify T2: parser-zone exclusions and the NumericOps build-gate asymmetry are now acknowledged
- **Original issue**: The track edited `SQLMathExpression.java` (under build-excluded `sql/parser/`) without acknowledging it is Spotless/ErrorProne/JaCoCo-excluded while the new `NumericOps` in `sql/util/` is not, and without a hand-format step or ErrorProne/coverage gate.
- **Fix applied**: New `## Context and Orientation` parser-zone paragraph; `## Validation and Acceptance` build gates (spotless:check clean, ErrorProne-clean NumericOps, 85/70 coverage); `## Plan of Work` step 3 hand-format; step 1 "modernize as you lift"; `## Surprises & Discoveries` entry.
- **Re-check**:
  - Track location: `## Context and Orientation` lines ~155-167; `## Validation and Acceptance` build-gates bullet ~274-278; Surprises ~34-44.
  - Current state: confirmed against `pom.xml` — Spotless exclude `**/internal/core/sql/parser/**` at `:640`; ErrorProne `-XepExcludedPaths` includes `.*/internal/core/sql/parser/.*` at `:124`; JaCoCo excludes `sql/parser/*.class` at `:1077` and `:1097`. The ERROR-level `Xep` set the track names (OperatorPrecedence, ReferenceEquality, MixedMutabilityReturnType, PatternMatchingInstanceof, StatementSwitchToExpressionSwitch) is all present and `:ERROR`. `sql.util` confirmed absent via PSI, so `NumericOps` faces all three tools first-time. The cited line numbers in the track match the live pom.
  - Criteria met: asymmetry recorded, hand-format step present, ErrorProne + coverage gates added.
- **Regression check**: Checked that the track does not over-claim ErrorProne coverage of the characterization tests — ErrorProne also excludes `.*/src/test/.*`, and the track only asserts `NumericOps` (main source) faces ErrorProne. Consistent. Clean.
- **Verdict**: VERIFIED

#### Verify T3: dispatch chain corrected to three-hop with super-callback rewrite; D17 wording deferral recorded
- **Original issue**: D17 framed the hot path as a two-hop chain; iter1 (P5) showed it is three-level with per-constant first hop and per-constant `super.apply` callbacks.
- **Fix applied**: `## Context and Orientation` states the real chain ("three hops for 8 of the 12 constants"); `## Plan of Work` step 2 adds the `super.apply → NumericOps.applyObject` rewrite; `## Invariants & Constraints` reworded; `## Surprises & Discoveries` records the correction and defers the design.md D17 "two-hop" wording fix to Phase-4 design-final (design frozen — Track-1 D1 precedent).
- **Re-check**:
  - Track location: C&O lines ~176-180; Plan step 2 ~214-222; Invariants ~341-347; Surprises ~45-53.
  - Current state: the chain shape is now correct (per-constant `apply(Object,Object)` → `super.apply` → base → widening → typed). The deferral is properly recorded with the frozen-design rationale, matching the spawn note that design-final items are deferred-not-unaddressed.
  - Criteria met: chain shape and perf-neutrality rest on the real chain; deferral discipline followed.
- **Regression check**: PSI super-call walk over all 12 constants — the fix's **count** is wrong: 9 constants call `super.apply` (PLUS included), the track says 8 and excludes PLUS. New issue raised as **T5**.
- **Verdict**: VERIFIED (chain/deferral correct) — but the fix introduced a count error captured in T5.

#### Verify T4: moving-surface inventory corrected; enum retains public signatures
- **Original issue**: Inventory said "five typed overloads + one fallback `apply(Object,Object)`"; iter1 (P6) showed `apply(Object,Object)` is overridden on all 12 constants.
- **Fix applied**: `## Context and Orientation` + `## Interfaces and Dependencies` give the per-constant inventory; D17's open boundary resolved — the enum keeps the typed + `apply(Object,Object)` signatures as delegators (Plan step 2); Surprises entry defers the D5-R wording fix to design-final.
- **Re-check**:
  - Track location: C&O ~169-191; Interfaces ~290-316; Surprises ~54-66.
  - Current state: PSI confirms all 12 constants override `apply(Object,Object)` plus the five typed overloads; the base has `apply(Object,Object)` + `apply(Number,Operator,Number)` + private static `toLong` + `getPriority`. The track now states the per-constant override surface and that the enum must keep the public signatures because `MathExpressionTest` (PSI-confirmed at `...sql.parser.MathExpressionTest`) binds to them. D5-R deferral recorded.
  - Criteria met: inventory accurate; signature-preservation invariant stated; deferral discipline followed.
- **Regression check**: Checked that the "enum keeps public surface" resolution does not contradict the perf-neutrality claim (T3) — consistent (delegators are monomorphic static calls). Clean.
- **Verdict**: VERIFIED

## Summary

FAIL. The four prior findings (T1, T2, T3, T4) are all VERIFIED — the fixes are substantively applied, the parser-zone facts and the validation pinned/not-pinned split are PSI/pom-confirmed accurate, and the two design-final deferrals (D17 two-hop, D5-R inventory) are correctly recorded rather than left unaddressed. One new should-fix surfaced: the T3 fix enumerates eight `super.apply` callers and groups PLUS as a no-`super` constant, but PSI proves nine callers including PLUS (its Number+Number branch routes through `super.apply`). Following step 2 literally would leave PLUS's `super.apply` un-rewritten and break compilation once the base logic moves to the static `NumericOps`. The correction is mechanical (re-count to nine, add PLUS to the rewrite list, propagate to the deferred design-final note), but it must land before decomposition treats step 2 as authoritative.
