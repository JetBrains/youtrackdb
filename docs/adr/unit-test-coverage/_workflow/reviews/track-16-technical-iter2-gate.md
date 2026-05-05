# Track 16 — Technical Review iter-2 Gate Verification

**Verdict:** PASS — 7 VERIFIED / 0 STILL OPEN / 0 REGRESSION (no new findings).

All 7 iter-1 findings (T1–T7) — 3 should-fix and 4 suggestion — were
addressed cleanly by the orchestrator's edits to the step file's
`## Description` section. PSI re-checks confirm the load-bearing
symbol claims (T1 dead-code, T3 proxy super-method-dispatch) still
hold, and the JaCoCo numbers cited under T2 match the current
`.coverage/reports/youtrackdb-core/jacoco.xml`. No regressions
introduced.

---

## Per-finding certificates

#### Verify T1: IndexConfigProperty dead-code pin
- **Original issue**: `core/metadata/schema/IndexConfigProperty` is a
  13-line, 0%-covered DTO whose only references are self-recursive
  `copy()` calls. The track's What/How did not surface this; a Phase
  B implementer would naturally try to "raise coverage" instead of
  pinning + forwarding to Track 22.
- **Fix applied**: Step file lines 45–63 (under **What** →
  `core/metadata/schema` → "Dead-code pins") explicitly enumerate
  `IndexConfigProperty` as a "fully orphaned class" with
  `IndexConfigPropertyDeadCodeTest` + lockstep delete forwarded to
  Track 22. Lines 174–181 (under **How**) reiterate the pin pattern
  and add `// WHEN-FIXED: Track 22 — delete X` markers.
- **Re-check**:
  - Step-file location: lines 45–63, 174–181.
  - Current state: `IndexConfigProperty` is named, classified as
    dead, and has a concrete pin shape + forward target. The fix
    also adds two cluster-selection dead-code pins
    (`Balanced`/`DefaultCollectionSelectionStrategy` +
    `CollectionSelectionFactory.getStrategy`) that go beyond the
    iter-1 ask but are consistent with the same convention.
  - PSI re-check (load-bearing): `ReferencesSearch` over
    `IndexConfigProperty` in all-scope returns exactly 2 refs, both
    inside `IndexConfigProperty.java:44,45` (self-recursive `copy()`).
    Per-method `MethodReferencesSearch`: ctor has 1 ref (the same
    self-recursion), all 5 getters and `copy()` have 0 refs.
    Confirmed: still 0 inbound external callers. Dead-code claim
    intact.
  - Criteria met: dead-code reframe convention applied; concrete
    pin class named; lockstep grouping with `META-INF/services`
    spelled out; Track 22 forwarding documented.
- **Regression check**: Checked the new cluster-selection dead-code
  pins added alongside T1's fix — they cite specific PSI evidence
  (`SchemaClassImpl:85` hard-codes `RoundRobinCollectionSelectionStrategy`,
  no public API switches strategy) and are consistent with the
  Track 14/15 lockstep-delete convention. Clean.
- **Verdict**: VERIFIED.

#### Verify T2: Function + DBSequence named (and existing-test cataloging)
- **Original issue**: The **What** named `FunctionLibraryImpl,
  DatabaseFunction` and `SequenceLibraryImpl, SequenceCached` —
  omitting the largest uncov class in each sub-package: `Function`
  (24 uncov) and `DBSequence` (31 uncov). The track also did not
  flag that significant test surface already exists
  (`DBSequenceTest` 26 @Test/967 LOC) and Track 16 is largely
  extension rather than new authoring.
- **Fix applied**: Step file lines 86–90 explicitly name `Function`
  (24 uncov, biggest in package, extends `IdentityWrapper`) under
  the function bullet. Lines 105–112 explicitly name `DBSequence`
  (31 uncov, biggest in package) under the sequence bullet, and
  lines 109–112 say "Carry-forward `DBSequenceTest` already has 26
  `@Test` methods at 967 LOC — Track 16 extends it rather than
  authoring new test classes."
- **Re-check**:
  - Step-file location: lines 86–90, 105–112.
  - Current state: Both classes are named with PSI-grounded
    annotations (`extends IdentityWrapper` for Function;
    `78.2% line — biggest in package` for DBSequence). The
    extension-vs-new-authoring guidance is also explicit.
  - JaCoCo re-check (load-bearing): fresh parse of
    `.coverage/reports/youtrackdb-core/jacoco.xml` (mtime
    2026-05-05 13:32) confirms `Function` 24 uncov / 74.7% line and
    `DBSequence` 31 uncov / 78.2% line — exactly matching the
    iter-1 figures.
  - Criteria met: named-class enumeration is now exhaustive for
    >5-uncov classes per sub-package; extension framing prevents
    fabricated parallel test files.
- **Regression check**: Step file does not introduce stale numbers
  elsewhere; the package-level uncov totals retain the **stale
  baseline — remeasure in Step 1** marker per T5. Clean.
- **Verdict**: VERIFIED.

#### Verify T3: Proxy super-method-dispatch warning
- **Original issue**: A naive PSI find-usages on
  `SchemaClassProxy.getName()` and ~60 sibling proxy methods
  returns 0 direct production callers because dispatch goes through
  the `SchemaClass`/`Schema`/`SchemaProperty`/`FunctionLibrary`/
  `SequenceLibrary` interface. Without an explicit warning, a Phase
  B implementer applying the carry-forward dead-code reframe
  convention may incorrectly classify proxy methods as dead.
- **Fix applied**: Step file lines 130–143 (under **How**) add an
  explicit warning: "naive PSI find-usages on
  `SchemaClassProxy.getName()` and the ~60 sibling proxy methods
  returns 0 direct production callers because dispatch flows through
  the `SchemaClass` / `SchemaProperty` / `Schema` / `FunctionLibrary`
  / `SequenceLibrary` interface (`SchemaClass.getName()` alone has
  161 prod callers via the interface). Tests MUST drive proxy
  methods via the public-API interface ... and Phase B implementers
  MUST check `findSuperMethods()` before pinning any proxy method
  as `*DeadCodeTest`." The text also routes proxy testing through
  `session.getMetadata().getSchema()...` (the public API) and cross-
  references Track 14's abstract-base trap to position this as the
  inverse case.
- **Re-check**:
  - Step-file location: lines 130–143.
  - Current state: warning is explicit, names the trap by mechanism
    (interface dispatch), names the public-API entry point, and
    instructs Phase B implementers to use `findSuperMethods()` as a
    pre-pin gate.
  - PSI re-check (load-bearing): `MethodReferencesSearch` on
    `SchemaClassProxy.getName()` in all-scope returns 0 refs;
    `findSuperMethods()` returns 1 super method
    (`SchemaClass.getName()` interface) with **399 refs** in all-
    scope. The trap mechanism is still active and the warning is
    correctly positioned. (Iter-1 reported 161 prod refs via a
    narrower scope/text-search. The current PSI all-scope count
    of 399 is even more emphatic — the warning understates the
    risk slightly but the fix's claim is qualitatively correct.)
  - Criteria met: methodology trap is codified; pre-pin gate is
    spelled out; cross-reference to Track 14 anchors the
    convention.
- **Regression check**: The warning does not contradict the
  dead-code pin pattern in T1 — `IndexConfigProperty` has 0 super
  methods (it's a concrete leaf class), so the `findSuperMethods()`
  gate would correctly pass it through to a `*DeadCodeTest` pin.
  Clean.
- **Verdict**: VERIFIED.

#### Verify T4: validation / schema-interface packages out-of-scope
- **Original issue**: `core/metadata/schema/schema` (interfaces,
  98.3%) and `core/metadata/schema/validation` (5
  Validation*Comparable wrappers, 100%) were not listed in the
  **What** but the **Constraints** said "In-scope: only the listed
  `core/metadata*` packages" — strict reading produced ambiguity.
- **Fix applied**: Step file lines 74–79 (under **What** →
  "Out-of-scope-by-design") explicitly state both packages are
  intentionally NOT targeted, with their coverage figures (98.3% /
  100%) and the rationale "already at near-full coverage and
  exercised transitively via Schema* consumer code."
- **Re-check**:
  - Step-file location: lines 74–79.
  - Current state: out-of-scope marker is explicit; coverage figures
    are quoted; rationale (transitive coverage) is given.
  - Criteria met: ambiguity removed; Phase B implementer cannot
    misread Constraints to demand new tests for these packages.
- **Regression check**: Constraints subsection (lines 191–198)
  unchanged from iter-1; out-of-scope marker in **What** + the
  positive in-scope list creates a coherent scope. Clean.
- **Verdict**: VERIFIED.

#### Verify T5: Step 1 baseline remeasurement note
- **Original issue**: All four cited per-package uncov numbers
  (1,278 / 74 / 75 / 18) had drifted by 3–46 lines since the plan
  was written. Per the carry-forward Step 1 baseline-remeasurement
  convention (Tracks 9, 10, 14 precedent), the track should
  explicitly call out that Step 1 will write `track-16-baseline.md`
  from a fresh JaCoCo run.
- **Fix applied**: Step file lines 161–171 (under **How**) state
  "Phase B Step 1 remeasures live coverage for all three target
  packages and writes `track-16-baseline.md` (precedent: Tracks 9,
  10, 14 baselines). The plan-cited `1,278 / 74 / 75 / 18` uncov
  figures are stale — concrete per-class uncov targets are derived
  from the remeasured XML." The fix also adds Step 1 inert-test
  spot-checks (Track 12 lesson) and notes the adversarial review
  iter-1 already cleared 5 existing test classes (A9). Each
  package mention in **What** also has a `**stale baseline —
  remeasure in Step 1**` marker (lines 22, 81, 105, 113).
- **Re-check**:
  - Step-file location: lines 22, 81, 105, 113, 161–171.
  - Current state: baseline remeasurement is explicit, the precedent
    is named, the staleness markers are inline next to every cited
    uncov figure.
  - Criteria met: convention is codified; future drift is preempted.
- **Regression check**: The stale-baseline markers are consistent
  across all four package mentions. Clean.
- **Verdict**: VERIFIED.

#### Verify T6: downgrade interactions reuse claim
- **Original issue**: The pre-review **Interactions** claim "Schema
  fixtures established here may be reused by Tracks 17, 18, and 22"
  was aspirational without a concrete reuse plan — Track 17 uses
  built-in OUser/ORole; Track 18 typically inlines
  `schema.createClass(...)`; Track 22 is heterogeneous; the
  `SchedulerTestFixtures` precedent (Track 11) is package-private
  and not cross-track reused.
- **Fix applied**: Step file lines 200–209 explicitly drop the
  cross-track reuse claim: "**No fixture extraction is committed at
  Track 16 time.** The pre-review claim 'Schema fixtures
  established here may be reused by Tracks 17, 18, and 22' is
  dropped per A5/R4: there is no anchoring step deliverable, no
  concrete `SchemaTestFixtures` class today, and Tracks 17/18/22
  owners have not committed to specific fixture consumption.
  Track 16 may discover patterns that later tracks adopt by
  analogy, but any DRY hoist for cross-track reuse goes to Track
  22's deferred-cleanup queue."
- **Re-check**:
  - Step-file location: lines 200–209.
  - Current state: claim is explicitly dropped (preferred fix
    option (a) per iter-1 proposal), with cross-references to A5
    (adversarial) and R4 (risk) reviews. Future cross-track DRY
    hoists are routed to Track 22's deferred-cleanup queue.
  - Criteria met: aspirational coupling removed; design pressure on
    Track 16 is reduced; downstream tracks remain free to adopt by
    analogy without binding.
- **Regression check**: The Track 22 forwarding mechanism is
  consistent with how other dead-code lockstep groups are routed.
  Clean.
- **Verdict**: VERIFIED.

#### Verify T7: function library two-concept distinction
- **Original issue**: The **What** described `core/metadata/function`
  as "function library" without distinguishing (1) the persistent
  function record (`Function extends IdentityWrapper`) from (2) the
  SQL function dispatcher (`DatabaseFunction implements
  SQLFunction`, registered via SPI). Testing strategies differ and
  the SPI-loop path overlaps with Track 6.
- **Fix applied**: Step file lines 80–103 (under **What**) split
  the function library into three explicitly-named concepts:
  - `FunctionLibraryImpl` / `FunctionLibraryProxy` /
    `FunctionLibrary` interface — persistent custom-function
    registry (driven via
    `session.getMetadata().getFunctionLibrary().createFunction(...)`).
  - `Function` — record round-trip + `Function.execute(args)` via
    stored function dispatch.
  - `DatabaseFunction` + `DatabaseFunctionFactory` — SPI dispatch
    path, reachable through `META-INF/services/SQLFunctionFactory:21`.
    To exercise live, register a stored function via
    `library.createFunction(...)` and invoke as `SELECT myFn(args)`;
    direct construction is test-only and bypasses the SPI loop.
    Cross-references the Track 6 (`CustomSQLFunctionFactory`)
    overlap with a Track 22 deferred-cleanup-queue routing for
    duplicate coverage.
- **Re-check**:
  - Step-file location: lines 80–103.
  - Current state: the dual-concept distinction is explicit, each
    concept has a concrete public-API entry point, and the
    Track 6 overlap is acknowledged with a routing hook.
  - Criteria met: testing strategy is unambiguous; SPI loop is
    distinguished from direct construction; cross-track overlap is
    routed.
- **Regression check**: The SPI-loop testing instruction is
  consistent with the Track 14/15 dead-code conventions for SPI
  service files (no proposal to delete the service file; live
  coverage of the loop is preserved). Clean.
- **Verdict**: VERIFIED.

---

## New findings

None. The fixes were targeted and did not introduce new structural
or technical issues.

---

## Summary

- **VERIFIED**: 7 (T1, T2, T3, T4, T5, T6, T7)
- **STILL OPEN**: 0
- **REGRESSION**: 0
- **New findings**: 0

**Verdict: PASS.** Track 16's Technical review reaches the iter-2
gate with all should-fix and suggestion findings closed, no
regressions, and the load-bearing PSI claims (T1 dead-code, T3
proxy super-method-dispatch) re-confirmed against the current
codebase.
