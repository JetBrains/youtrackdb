<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: R1, sev: should-fix, loc: "pom.xml:124 / NumericOps.java (new)", anchor: "### R1 ", cert: "Exposure: static-analysis gate boundary crossed by the lift", basis: "Lifted engine leaves the ErrorProne-excluded sql/parser path; full Xep:*:ERROR set (PatternMatchingInstanceof, OperatorPrecedence, MixedMutabilityReturnType) applies for the first time -> likely compile break the math-test gate cannot catch"}
  - {id: R2, sev: should-fix, loc: "pom.xml:1077,1097 / NumericOps.java (new)", anchor: "### R2 ", cert: "Exposure: JaCoCo coverage exclusion crossed by the lift", basis: "sql/util is not in JaCoCo excludes (sql/parser/*.class is); NumericOps becomes coverage-subject under the 85/70 changed-code gate while the track declines new tests -> integer-divide widening, Date arithmetic, shift/bitwise null-return branches are uncovered"}
  - {id: R3, sev: should-fix, loc: "track-2.md Validation and Acceptance / MathExpressionTest.java", anchor: "### R3 ", cert: "Assumption: the existing math-test suite pins all four promotion families"}
  - {id: R4, sev: suggestion, loc: "track-2.md Plan of Work step 2 (D17) / MathExpressionTest.java:48-65", anchor: "### R4 ", cert: "Assumption: the typed-overload-boundary choice is free either way"}
  - {id: R5, sev: suggestion, loc: "track-2.md D17 / Plan of Work", anchor: "### R5 ", cert: "Exposure: AST arithmetic hot path; runtime JMH deferred to S1"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 8}
flags: [CONTRACT_OK]
-->

## Findings

### R1 [should-fix]
**Certificate**: Exposure: static-analysis gate boundary crossed by the lift
**Location**: `pom.xml:124` (ErrorProne `XepExcludedPaths`); new `core/.../sql/util/NumericOps.java`
**Issue**: The promotion engine currently lives in `SQLMathExpression.Operator`, inside
`internal/core/sql/parser/`, which is explicitly excluded from ErrorProne:
`-XepExcludedPaths:.*/target/generated-sources/.*|.*/internal/core/sql/parser/.*|...`
(pom.xml:124). Lifting that code verbatim into `core/.../sql/util/NumericOps.java` moves it
**out of the exempt path** — `sql/util` matches no exclusion (confirmed: `grep -nE "sql/util"
pom.xml core/pom.xml` returns nothing). The full ErrorProne check set (`Xep:...:ERROR`) then
runs against the lifted code for the first time. The engine as written trips several of those
checks: it uses old-style `instanceof` + cast throughout (`left instanceof Number` then
`(Number) left` — SQLMathExpression.java:209-216, 261-265, 456-464, 575-576), which
`-Xep:PatternMatchingInstanceof:ERROR` flags; the typed-overload value logic and the widening
ladder are candidates for `-Xep:OperatorPrecedence:ERROR` and `-Xep:MixedMutabilityReturnType:ERROR`.
Likelihood: medium-high that at least one ERROR-level check fires; impact: a **compile failure**,
not a test failure. This is the central blind spot in the track's framing — the "lift-and-shift
keeps the math tests green = self-verifying" gate (Validation and Acceptance) is a *runtime*
gate and cannot see a *compile-time* static-analysis break. (NullAway specifically is unlikely to
fire: it runs under `OnlyNullMarked=true` and `sql/util` would not be `@NullMarked` — only 3 files
in `core/src/main` carry that annotation and there is no module-wide marker — but the non-NullAway
`Xep` checks are not gated by `@NullMarked`.)
**Proposed fix**: Add an explicit step (or a noted sub-task on the create step) to compile the new
`NumericOps` under the project's ErrorProne config and resolve any `Xep` ERROR before claiming the
extraction done — e.g. modernize the `instanceof`-plus-cast sites to pattern-matching `instanceof`
during the lift. Alternatively, if the team wants a pure mechanical lift, record a deliberate
decision to extend the `XepExcludedPaths` to cover `sql/util` (less desirable — it permanently
exempts new shared code from static analysis). Either way the track must state that the acceptance
gate includes a clean ErrorProne compile, not only green math tests.

### R2 [should-fix]
**Certificate**: Exposure: JaCoCo coverage exclusion crossed by the lift
**Location**: `pom.xml:1077,1097` (JaCoCo `<excludes>`); new `core/.../sql/util/NumericOps.java`;
track-2.md `## Validation and Acceptance`
**Issue**: JaCoCo excludes `**/.../sql/parser/*.class` from coverage (pom.xml:1077 and 1097), so the
promotion engine is **coverage-exempt today**. `sql/util` is not excluded (same `grep` as R1 returns
nothing). After the lift, `NumericOps` is a brand-new file — every line is changed code — so the
coverage gate (85% line / 70% branch on changed code, run via `coverage-gate.py` against
`origin/develop`) applies to all of it. The engine has substantial branch surface the existing tests
do not reach (see R3 and the Testability certificate): the integer-divide widening `else` branch
(SQLMathExpression.java:92-95, 99-103), the `Date` branches of `apply(Object,Object)` for PLUS/MINUS
(lines 212-215, 263-266), and the `null`-returning Float/Double/BigDecimal overloads on the shift and
bitwise operators (lines 284-298, 322-336, 360-372, 432-446, 482-496). Lifting these into a
coverage-subject file with no new tests likely **fails the coverage gate**. Likelihood: high; impact:
CI coverage-gate failure that blocks the PR — directly contradicting the track's stance that the
gate is "self-verifying and behavioral, not a new test" (`## Validation and Acceptance`).
**Proposed fix**: Decompose a test step into the track: add a focused `NumericOps` unit test (or
extend `MathExpressionTest`) covering the divide-widening, Date-arithmetic, and shift/bitwise
null-return branches so the new file clears 85/70 on changed code. If the team prefers to keep the
extraction test-free, the alternative is to follow the existing exemption: add `sql/util/*.class` (or
specifically `NumericOps.class`) to the JaCoCo `<excludes>` and record the follow-or-exempt decision
(`conventions-execution.md §Coverage (S5)`) in the track. The track currently does neither and
assumes the gate is satisfied by behavior alone.

### R3 [should-fix]
**Certificate**: Assumption: the existing math-test suite pins all four promotion families
**Location**: track-2.md `## Validation and Acceptance` and `## Plan of Work` (invariants list);
`core/src/test/.../parser/MathExpressionTest.java`
**Issue**: The track's acceptance gate rests on the claim that any divergence in the four named
promotion families — "integer-divide widening, null propagation, `Date + Long`, `String` concat" —
"surfaces as a math-test failure." PSI + targeted test search show this is true for only two of the
four when the test path is restricted to the code actually being lifted
(`SQLMathExpression.Operator`):
- **Null propagation**: PINNED at SQL level. `DocValidationTest.testSyntaxNullInPlus/Minus/Multiply/Divide`
  execute `SELECT v + n` etc. through `SQLExpression.execute` -> `SQLMathExpression.execute` ->
  `Operator.apply(Object,Object)` (caller chain PSI-confirmed: SQLExpression.java:82,131,157 ->
  SQLMathExpression.execute -> apply).
- **String concat**: PINNED. `DocValidationTest.testSyntaxStringConcatenation` (`'a'+1+2='a12'`)
  routes through the same path (3-child -> the precedence fold -> `apply(Object,Object)`).
- **Integer-divide widening** (`7/2 -> 3.5` Double): **NOT pinned**. `MathExpressionTest.testTypes`
  exercises SLASH only with `1/1` (remainder zero -> Integer); the sole SQL divide test
  (`DocValidationTest:6350`, `WHERE total / 3 = 5`) does not assert a Double result. The widening
  `else` branch is uncovered.
- **`Date + Long` / `Date - Long`**: **NOT pinned through `SQLMathExpression`**. The `QueryOperator*Test`
  classes that do test Date arithmetic (`QueryOperatorPlusTest.testDatePlusLong`,
  `QueryOperatorMinusTest.testDateMinusLong`, etc.) drive `QueryOperatorPlus`/`QueryOperatorMinus`,
  which are a **separate operator implementation** — grep-confirmed those classes contain no reference
  to `SQLMathExpression` or `Operator`. They give false comfort: green there says nothing about the
  lifted code.
The "self-verifying gate" assumption is therefore only partially valid. A lift that silently dropped
the divide-widening branch or the Date branch of `apply(Object,Object)` would keep the entire existing
math suite green. Likelihood of a silent semantic regression slipping through: low for a careful
verbatim lift, but non-trivial if the typed overloads are restructured (R4) or if a `Date`/widening
line is dropped during the move; impact: a behavioral regression in production arithmetic that ships
green.
**Proposed fix**: Reword the `## Validation and Acceptance` claim to name exactly which families the
existing suite pins (null propagation, string concat) versus which it does not (integer-divide
widening, Date arithmetic) — do not assert all four are gated by green math tests. Pair with R2's
test step: the new `NumericOps` test should cover the two unpinned families, which closes both the
coverage gap and the regression gap at once.

### R4 [suggestion]
**Certificate**: Assumption: the typed-overload-boundary choice is free either way
**Location**: track-2.md `## Plan of Work` step 2 (D17 boundary decision);
`MathExpressionTest.java:48-65,68-71`
**Issue**: D17 leaves open whether the five typed `apply` overloads move into `NumericOps` or stay on
the enum with `NumericOps` calling back, framing it as a free choice constrained only by preserving
the two-hop dispatch shape. There is one additional constraint the track does not flag:
`MathExpressionTest.testTypes` calls the typed overloads **directly on the enum constant** —
`op.apply(1, 1)`, `op.apply(1L, 1L)`, `op.apply(BigDecimal.ONE, BigDecimal.ONE)`, and the
`Operator.PLUS.apply(Integer.MAX_VALUE, 1)` overflow check (lines 48-55, 68-71; PSI find-usages
confirms these resolve to the typed overloads declared on `Operator`). If the typed overloads are
**moved off** the enum into `NumericOps`, those call sites stop compiling unless the enum retains
typed methods (e.g. as delegators). So the "move them into NumericOps" arm of the D17 fork is not
free — it forces either a test rewrite or enum-side delegator overloads. Likelihood: certain if that
arm is chosen without accounting for the test; impact: a compile break in the test, caught at build
(low blast radius, but worth pre-empting in the decision).
**Proposed fix**: Note in the D17 decision that `MathExpressionTest` binds to the enum's typed
overloads, so the "keep typed overloads on the enum, NumericOps calls back" arm is the lower-friction
default; if the other arm is chosen, plan the corresponding test update or enum-side delegators in the
same step.

### R5 [suggestion]
**Certificate**: Exposure: AST arithmetic hot path; runtime JMH deferred to S1
**Location**: track-2.md `#### D17`; `## Plan of Work`
**Issue**: The track touches a genuine hot path — PSI confirms the only production callers of
`Operator.apply(Object,Object)` are inside `SQLMathExpression` itself (the 2-operand `execute`
short-circuit at lines 700/721 and the precedence folds `calculateWithOpPriority`/`iterateOnPriorities`
at 745/775/811/822); no external production caller exists, so blast radius is bounded to one file's
evaluation loop, which runs per arithmetic evaluation. The perf-neutrality argument (preserve the
two-hop dispatch, add only a monomorphic inlinable delegation) is structurally sound, and deferring
runtime JMH to S1 is the right call: an S0 JMH run would measure a path with no live IR consumer yet,
duplicating S1's LDBC gate at the cost of a Hetzner run. This finding records the assessment rather
than asking for action: the deferral is sound. One caveat worth a sentence in the episode — the
perf-neutrality claim is "verified by code review of the delegation shape" only until S1 measures it,
so the step's code review must actually confirm the delegation is a static monomorphic call (not, e.g.,
an interface or a `Function` field) before the perf-neutral claim is recorded as met.
**Proposed fix**: No structural change. At step implementation, have the step's review explicitly check
the delegation is a direct static call into `NumericOps` (inlinable, no new virtual indirection) and
record that check in the episode, so the deferred-to-S1 perf claim has a concrete code-shape basis on
record.

## Evidence base

#### Exposure: static-analysis gate boundary crossed by the lift
- **Track claim**: the extraction is a mechanical lift-and-shift whose only acceptance gate is the
  existing math-test suite staying green; "no Spotless / `pom.xml` / `--add-opens` changes (same `core`
  module)" (D5 Risks/Caveats).
- **Critical path trace** (build-time, not runtime):
  1. Today: `SQLMathExpression.Operator` lives in `core/.../sql/parser/SQLMathExpression.java`
     (package `...sql.parser`), git-tracked committed source (despite the JJTree header — the file is
     hand-maintained in `src/main`, not regenerated; the JavaCC plugin generates other parser files
     into `target/generated-sources`).
  2. `pom.xml:124` ErrorProne `-XepExcludedPaths` includes `.*/internal/core/sql/parser/.*` — the
     engine is exempt from all `Xep:...:ERROR` checks today.
  3. After lift: code resides in `core/.../sql/util/NumericOps.java` (package `...sql.util`), which
     matches no exclusion entry.
  4. ErrorProne now runs the full ERROR set against the lifted code at compile time.
- **Blast radius**: a compile failure blocks the whole `core` module build (and the PR). The engine's
  `instanceof`+cast idiom (SQLMathExpression.java:209-216, 261-265, 456-464, 575-576) is the prime
  `PatternMatchingInstanceof:ERROR` candidate; `OperatorPrecedence` / `MixedMutabilityReturnType` are
  secondary candidates.
- **Existing safeguards**: none that catch this — the math-test suite is a runtime gate; Spotless
  excludes `sql/parser` but the *new* file under `sql/util` is Spotless-subject (formatting only, not
  ErrorProne). No build step in the track plan compiles-with-ErrorProne as an explicit gate.
- **Residual risk**: MEDIUM-HIGH — confirmed exclusion boundary is crossed; whether a specific
  `Xep` fires depends on the exact lifted text, but at least one (`PatternMatchingInstanceof`) is a
  strong match against the current code.

#### Exposure: JaCoCo coverage exclusion crossed by the lift
- **Track claim**: acceptance is "self-verifying and behavioral, not a new test"
  (`## Validation and Acceptance`).
- **Critical path trace**: `pom.xml:1077` and `pom.xml:1097` exclude
  `**/com/jetbrains/youtrackdb/internal/core/sql/parser/*.class` from JaCoCo; `sql/util` is absent from
  the exclude list. `coverage-gate.py` compares changed lines against `origin/develop` at 85% line /
  70% branch; a new file is entirely changed lines.
- **Blast radius**: CI coverage-gate failure on the PR. Uncovered branches that move into the
  coverage-subject file: divide-widening `else` (lines 92-95, 99-103), Date branches (212-215,
  263-266), shift/bitwise `null`-return overloads (284-298, 322-336, 360-372, 432-446, 482-496).
- **Existing safeguards**: the follow-or-exempt rule (`conventions-execution.md §Coverage (S5)`) is the
  documented escape hatch but the track invokes neither arm.
- **Residual risk**: HIGH — new coverage-subject file plus an explicit no-new-tests stance.

#### Assumption: the existing math-test suite pins all four promotion families
- **Track claim**: "any divergence in promotion semantics — integer-divide widening, null
  propagation, `Date + Long`, `String` concat — surfaces as a math-test failure."
- **Evidence search**: PSI find-usages on all seven `Operator.apply` overloads + the widening helper
  (`steroid_execute_code`, `ReferencesSearch`); PSI caller trace from `SQLMathExpression.execute`
  back to `SQLExpression.execute`; delegated broad test search; grep confirmation that
  `QueryOperatorPlus/Minus/Divide` contain no `SQLMathExpression`/`Operator` reference.
- **Code evidence**:
  - Null propagation pinned: `DocValidationTest.testSyntaxNullInPlus/Minus/Multiply/Divide` (SQL
    `SELECT v +/-/*// n`) route through `SQLMathExpression.execute` -> `apply(Object,Object)`.
  - String concat pinned: `DocValidationTest.testSyntaxStringConcatenation` (`'a'+1+2`).
  - Divide widening NOT pinned: `MathExpressionTest.testTypes:48` only `1/1`; `DocValidationTest:6350`
    `WHERE total/3=5` asserts no Double.
  - Date arithmetic NOT pinned through this code: the Date tests live in `QueryOperator*Test`, which
    drive a different class (`QueryOperatorPlus`/`Minus`/`Divide`, package `sql/operator/math`).
- **Verdict**: PARTIALLY CONTRADICTED — 2 of 4 families pinned via DocValidationTest; integer-divide
  widening and `SQLMathExpression`-side Date arithmetic are not.

#### Assumption: the typed-overload-boundary choice is free either way
- **Track claim**: D17 hands the "typed overloads move vs. stay on the enum" choice to the track,
  constrained only by preserving the two-hop dispatch shape.
- **Evidence search**: PSI find-usages on the typed overloads (`apply(Integer,Integer)` etc.).
- **Code evidence**: `MathExpressionTest.java:48-55,68-71` call the typed overloads directly on the
  enum constant (`op.apply(1,1)`, `Operator.PLUS.apply(Integer.MAX_VALUE,1)`); these are the only
  external (test) callers of the typed overloads — the production widening helper at
  SQLMathExpression.java:589-641 is the other caller set.
- **Verdict**: UNVALIDATED — the "move off the enum" arm additionally requires test changes or
  enum-side delegators; the choice is not symmetric.

#### Exposure: AST arithmetic hot path (blast radius + caller confirmation)
- **Track claim**: `Operator.apply` is hot (called by `iterateOnPriorities`); perf-neutral via an
  inlinable monomorphic delegation; runtime JMH deferred to S1.
- **Critical path trace**: `SQLExpression.execute` (82/131/157) -> `SQLMathExpression.execute`
  (2-operand fast path 700/721, or `calculateWithOpPriority` -> `iterateOnPriorities`) ->
  `Operator.apply(Object,Object)` -> base `apply(Object,Object)` (line 568) -> widening
  `apply(Number,Operator,Number)` (582) -> typed overload.
- **Blast radius**: PSI confirms `apply(Object,Object)` has NO production caller outside
  `SQLMathExpression.java` (the 15 production usages are all in-file: 86/127/162/210/306/344/382/418/505
  are the per-constant `super.apply` hops; 700/721/745/775/811/822 are the execute + fold call sites).
  External surface is `MathExpressionTest` only. Blast radius is bounded to one file's evaluation loop.
- **Existing safeguards**: the two-operand and N-ary folds are pinned by `MathExpressionTest.testPriority*`
  (PSI-confirmed callers at MathExpressionTest.java:89/115/129/143/155/167/179/218).
- **Residual risk**: LOW (correctness) — bounded blast radius, in-file callers; perf claim itself stays
  code-review-only until S1, which the track states.

#### Exposure: source is committed, not build-generated (regeneration-clobber risk)
- **Track claim**: implicit — the track treats `SQLMathExpression.java` as an ordinary editable source
  file.
- **Evidence search**: `git ls-files` on the file; locate `.jjt`/`.jj`; inspect `core/pom.xml`
  javacc-maven-plugin config.
- **Code evidence**: `SQLMathExpression.java` is git-tracked in `core/src/main/java/.../sql/parser/`;
  the JavaCC/JJTree plugin (core/pom.xml:223-229) emits generated parser sources under
  `target/generated-sources`, not over this committed file.
- **Verdict**: VALIDATED — editing the file in place is safe; no regeneration clobbers it. (Removes a
  plausible-but-false risk: the JJTree header is misleading; the file is hand-maintained source.)

#### Testability: NumericOps unit coverage of the unpinned branches
- **Coverage target**: 85% line / 70% branch on the new file (changed-code gate).
- **Difficulty assessment**: LOW — `NumericOps` is all-static, pure-function arithmetic with no DB,
  session, or I/O dependency; each branch is reachable by a direct call with literal operands. The
  existing `MathExpressionTest` (constructs `SQLMathExpression` with `SQLBaseExpression` children, no
  DB) is the proven fixture pattern; a direct `NumericOps.apply(...)` unit test is even simpler.
- **Existing test infrastructure**: `MathExpressionTest.java` (JUnit 4, no DB);
  `DocValidationTest.java` for the SQL-level path.
- **Feasibility**: ACHIEVABLE — adding the divide-widening, Date-arithmetic, and shift/bitwise
  null-return cases is straightforward; the only reason coverage is at risk (R2) is the track's
  current no-new-tests stance, not any intrinsic test difficulty.

#### Testability: the lift itself under the static-analysis gate
- **Coverage target**: n/a (compile gate, not coverage).
- **Difficulty assessment**: the math-test gate cannot detect an ErrorProne ERROR; verification
  requires a clean `./mvnw -pl core compile` (or `test`) run under the project's ErrorProne config,
  which the standard pre-commit `./mvnw -pl core clean test` already exercises.
- **Existing test infrastructure**: the standard module build is the gate; no new harness needed.
- **Feasibility**: ACHIEVABLE — running the standard build surfaces R1; the risk is only that the
  track does not list a compile-clean-under-ErrorProne expectation as part of "done."
