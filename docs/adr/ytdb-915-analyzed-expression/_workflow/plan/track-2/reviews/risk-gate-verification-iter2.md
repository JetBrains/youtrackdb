<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
verdicts:
  - {id: R1, prior_sev: should-fix, verdict: VERIFIED, loc: "track-2.md Plan of Work step 1 / Validation / Invariants / C&O / Surprises", note: "ErrorProne-clean modernization step + clean-compile gate now on record; sql/util confirmed outside XepExcludedPaths (pom.xml:124)"}
  - {id: R2, prior_sev: should-fix, verdict: VERIFIED, loc: "track-2.md Plan of Work step 4 / Validation / Invariants", note: "characterization-test step + 85/70 changed-code coverage gate now on record; sql/util confirmed outside JaCoCo excludes (pom.xml:1077/1097)"}
  - {id: R3, prior_sev: should-fix, verdict: VERIFIED, loc: "track-2.md Validation and Acceptance / Plan of Work step 4 / Invariants", note: "Validation reworded to name pinned (null-prop, String-concat) vs unpinned (divide-widening, Date) families; characterization tests close the gap"}
  - {id: R4, prior_sev: suggestion, verdict: VERIFIED, loc: "track-2.md Plan of Work step 2 / Surprises / Interfaces", note: "D17 resolved: enum keeps public typed + apply(Object,Object) as delegators; PSI confirms MathExpressionTest binds to all five typed overloads + apply(Object,Object) on the enum"}
  - {id: R5, prior_sev: suggestion, verdict: VERIFIED, loc: "track-2.md Invariants & Constraints / D17", note: "Affirming finding; deferral recorded, step code review must confirm static monomorphic delegation; PSI confirms apply(Object,Object) has no production caller outside SQLMathExpression.java"}
overall: PASS
index: []
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
flags: [CONTRACT_OK]
-->

## Findings

(No new findings. Pure verdict pass — all five iteration-1 risk findings VERIFIED; overall PASS.)

## Evidence base

#### Verify R1: static-analysis gate boundary crossed by the lift
- **Original issue**: lifting the engine out of the ErrorProne-excluded `sql/parser/` zone into `sql/util/` subjects it to the full `Xep:*:ERROR` set for the first time; the old-style `instanceof`+cast idiom is a `PatternMatchingInstanceof:ERROR` candidate -> a compile break the "math tests stay green" runtime gate cannot see.
- **Fix applied**:
  - `## Plan of Work` step 1 now ends with "**Modernize as you lift:** `sql/util/` is subject to ErrorProne … rewrite the old-style `instanceof`-plus-cast sites to pattern-matching `instanceof` and clear any other `Xep:*:ERROR` (e.g. OperatorPrecedence, MixedMutabilityReturnType) before the step is done — a `Xep` error is a compile failure the math-test gate cannot see (R1)."
  - `## Validation and Acceptance` adds the build gate: "`NumericOps` compiles clean under ErrorProne (no `Xep:*:ERROR`) … compile- and coverage-time gates the green-math-tests runtime gate structurally cannot detect (R1/R2)."
  - `## Invariants & Constraints` adds "**`NumericOps` clears the build gates** … compiles clean under ErrorProne (no `Xep:*:ERROR`) … verified at the build, not by the runtime math tests."
  - `## Context and Orientation` + `## Surprises & Discoveries` record the zone crossing (new file outside all three exclusions; the parser-zone delegator edit is Spotless/ErrorProne-exempt and must be hand-formatted).
- **Re-check**:
  - Location: track-2.md Plan of Work step 1, Validation, Invariants, C&O, Surprises.
  - Current state: the track now treats a clean ErrorProne compile as part of "done" and prescribes the concrete fix (pattern-matching `instanceof`); the original blind spot (runtime gate can't see a compile-time static-analysis break) is closed.
  - Criteria met: the exclusion-boundary substrate is independently confirmed — `pom.xml:124` `-XepExcludedPaths` lists `.*/internal/core/sql/parser/.*` (and `target/generated-sources`, `src/test`, `generated-test-sources`) but **no `sql/util` entry**; the ERROR set includes `PatternMatchingInstanceof`, `OperatorPrecedence`, `MixedMutabilityReturnType` (grep over pom.xml). So the lift genuinely crosses the boundary and the named checks genuinely apply.
- **Regression check**: the "modernize while lifting" instruction changes source text from the verbatim original, which slightly raises the bar for the "lift-and-shift keeps math tests green = self-verifying" claim — but R3's added characterization tests and the retained existing suite cover the behavior, so modernization is gated by tests, not by visual diff. No new issue.
- **Verdict**: VERIFIED

#### Verify R2: JaCoCo coverage exclusion crossed by the lift
- **Original issue**: `sql/parser/*.class` is JaCoCo-excluded (pom.xml:1077/1097) but `sql/util` is not; `NumericOps` is an all-new file (all changed lines) so the 85/70 changed-code gate applies to its full branch surface (divide-widening else, Date branches, shift/bitwise null-return overloads) while the track declined new tests -> likely coverage-gate failure.
- **Fix applied**:
  - `## Plan of Work` step 4 adds "**Add characterization tests** … These tests also cover the new `NumericOps` branch surface so the file clears the 85/70 changed-code coverage gate (R2)."
  - `## Validation and Acceptance` adds "changed code clears the 85/70 line/branch coverage gate."
  - `## Invariants & Constraints` adds the same coverage clause to the build-gates invariant.
- **Re-check**:
  - Location: track-2.md Plan of Work step 4, Validation, Invariants.
  - Current state: the track no longer asserts the gate is satisfied by behavior alone; it adds tests that double as branch-coverage for the new file and names the 85/70 gate as acceptance.
  - Criteria met: JaCoCo `<excludes>` at `pom.xml:1077` and `:1097` both name `**/com/jetbrains/youtrackdb/internal/core/sql/parser/*.class` only — `sql/util` is absent (grep confirms). So a new `sql/util/NumericOps.java` is coverage-subject under the changed-code gate, and the added tests are the right remedy. The track chose the "add tests" arm (not the "extend the exclusion" arm), consistent with R3 closing the regression gap at the same time.
- **Regression check**: the added tests are pure-function, no-DB unit tests against an all-static helper (the iter-1 Testability cert rated this LOW difficulty); they do not introduce DB/session coupling. No new issue.
- **Verdict**: VERIFIED

#### Verify R3: existing suite pins only null-prop + String concat, not divide-widening/Date
- **Original issue**: the acceptance claim "any divergence in all four families surfaces as a math-test failure" was only half true — null-prop and String-concat are pinned via `DocValidationTest` through `SQLMathExpression.execute`, but integer-divide widening (`testTypes` uses only `1/1`) and `Date` arithmetic (driven by the separate `QueryOperator*` classes) are not pinned through the lifted code -> a dropped divide/Date branch could ship green.
- **Fix applied**: `## Validation and Acceptance` rewritten into three explicit bullets — "What the existing suite already pins (verified): null propagation and `String` concatenation"; "What it does NOT pin (verified): integer-divide widening … and `Date` arithmetic — the `QueryOperator*Test` Date tests drive `QueryOperatorPlus`/`QueryOperatorMinus`, a **separate** operator implementation … (false comfort)"; "Therefore the gate requires the new characterization tests (Plan of Work step 4)." Same remedy as technical T1.
- **Re-check**:
  - Location: track-2.md Validation and Acceptance, Plan of Work step 4, Invariants & Constraints (math-semantics bullet now reads "the existing suite alone pins only null propagation and `String` concat; the characterization tests cover divide-widening and `Date` arithmetic").
  - Current state: the track states exactly which families are/are not pinned and adds the tests that close the unpinned two; the over-claim is gone.
  - Criteria met: the new wording matches the iter-1 PSI evidence (DocValidationTest path for null/String; QueryOperator* as a distinct implementation for Date; `testTypes` only `1/1` for SLASH). No contradiction with R2's coverage framing — one test step satisfies both.
- **Regression check**: characterization tests written red-first against develop (step 4) then kept green — a sound order that proves they exercise the lifted engine rather than a parallel path. No new issue.
- **Verdict**: VERIFIED

#### Verify R4: D17 typed-overload boundary not free — tests bind to enum overloads
- **Original issue**: D17 framed "move typed overloads into NumericOps vs keep on enum" as a free choice, but `MathExpressionTest.testTypes` calls the typed overloads directly on the enum constant, so the "move off" arm breaks the tests unless the enum keeps delegators.
- **Fix applied**: `## Plan of Work` step 2 resolves D17 — "**the enum keeps the public surface.** The five typed `apply` overloads and the per-constant `apply(Object, Object)` stay declared on `Operator` … but their bodies become thin delegators into `NumericOps`." Recorded in `## Surprises & Discoveries` (A2/R4 bullet: "the enum keeps the public surface … moving them off … would break `MathExpressionTest`'s direct calls (the acceptance gate itself)") and `## Interfaces and Dependencies`.
- **Re-check**:
  - Location: track-2.md Plan of Work step 2, Surprises bullet 4, Interfaces (in-scope `NumericOps` bullet).
  - Current state: the open D17 fork is closed in favor of the lower-friction arm; the constraint the iter-1 finding surfaced (test binds to enum) is now the stated reason.
  - Criteria met: PSI re-check (this iteration) confirms the binding precisely — `ReferencesSearch` on each `apply` overload declared on `Operator` reports `MathExpressionTest.java` as a caller file for all five typed overloads `apply(Integer,Integer)`/`(Long,Long)`/`(Float,Float)`/`(Double,Double)`/`(BigDecimal,BigDecimal)` and for `apply(Object,Object)`. So moving any of these off the enum would break the test; the resolution is correct.
- **Regression check**: keeping the public surface on the enum is the conservative arm; it adds no new indirection beyond the planned delegation and does not weaken the test gate. No new issue.
- **Verdict**: VERIFIED

#### Verify R5: hot path; S1 JMH deferral is sound (affirming)
- **Original issue**: none requiring a structural change — the finding affirmed that deferring runtime JMH to S1 is the right call and asked only that the step's code review confirm the delegation is a static monomorphic call before recording the perf-neutral claim as met.
- **Fix applied**: `## Invariants & Constraints` ("No new virtual indirection on the hot path") states the former `super`/base hops become static calls into `NumericOps.applyObject(...)`, "No new *virtual* dispatch enters the path; verified by code review of the delegation shape, runtime measurement deferred to S1's LDBC JMH gate." `#### D17` and Plan of Work step 2 carry the same: "the former `super`/widening hops become static monomorphic calls into `NumericOps` … no new virtual indirection enters the hot path."
- **Re-check**:
  - Location: track-2.md Invariants & Constraints (hot-path bullet), D17, Plan of Work step 2.
  - Current state: the deferral and the code-review obligation are on record; no structural change was asked and none was made.
  - Criteria met: PSI re-check confirms the bounded-blast-radius premise — `apply(Object,Object)` (24 refs) and the widening entry `apply(Number,Operator,Number)` (6 refs) have only `SQLMathExpression.java` (and, for the public `apply` surface, `MathExpressionTest.java`) as caller files; no external production caller exists, so the hot path is one file's evaluation loop, exactly as the deferral argument assumes.
- **Regression check**: affirming finding, no fix to regress. The recorded code-review obligation (confirm static monomorphic call, not an interface/`Function` field) is carried into the step's acceptance, which is the correct home for it. No new issue.
- **Verdict**: VERIFIED (affirming — no action was required)
