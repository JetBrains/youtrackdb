<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: R1, sev: should-fix, loc: "track-7.md:50,62 (Plan-of-Work item 1 / Validation); YTDBMatchPlanStepTest.java:854,288,308,332,348", anchor: "### R1 ", cert: A2, basis: "Validation promises existing suites stay green with no assertion changes, but item 1 moves the plan/lifecycle into an abstract superclass; YTDBMatchPlanStepTest:854 reads YTDBMatchPlanStep.class.getDeclaredField(\"plan\") which does not see inherited fields, so moving plan to the base fails the test, and package-private projectElement (called at :288/:308/:332/:348) forces the base into the same package."}
  - {id: R2, sev: should-fix, loc: "track-7.md:32,50,80 (Purpose / Plan-of-Work item 1 / Interfaces)", anchor: "### R2 ", cert: A1, basis: "The track frames 'the ExecutionStream open/drain/close lifecycle' as one liftable unit the base supplies to Track 8, but the pre-split risk R3 checklist shows MultiPlanMatchStep needs the primitives recomposed (close-on-advance, stop-on-first-exception, clone N plans, reset-to-plan[0]) — the single-plan State machine baked into the base at the wrong granularity leaves Track 8 overriding everything and re-opening the R3 leak/double-close hazards."}
  - {id: R3, sev: suggestion, loc: "track-7.md:52,64 (Plan-of-Work item 3 / Validation)", anchor: "### R3 ", cert: T1, basis: "Track lands the carrier verified with 'placeholder / no-op ops'; if those ops are parameterless the framework never proves it can carry a per-op parameter, but C9 established a TAIL op must carry its own n — verify the ordered stage with a parameter-bearing placeholder so Track 9's tail is not blocked at the foundation."}
evidence_base: {section: "## Evidence base", certs: 7, matches: 5}
cert_index:
  - {id: E1, verdict: MEDIUM,       anchor: "#### E1 "}
  - {id: E2, verdict: LOW,          anchor: "#### E2 "}
  - {id: E3, verdict: LOW,          anchor: "#### E3 "}
  - {id: A1, verdict: UNVALIDATED,  anchor: "#### A1 "}
  - {id: A2, verdict: CONTRADICTED, anchor: "#### A2 "}
  - {id: T1, verdict: ACHIEVABLE,   anchor: "#### T1 "}
  - {id: T2, verdict: ACHIEVABLE,   anchor: "#### T2 "}
flags: [CONTRACT_OK]
-->

# Track 7 risk review — iteration 1 (post-split)

No blocker, no skip. The split already retired the pre-split compile blocker, and the technical review CONFIRMED feasibility, so the residual risk is in how the base extraction interacts with the guard suites the track leans on and with what Track 8 must inherit — not in whether the refactor can be built. The production blast radius is tiny and transparent (only two `GremlinToMatchStrategy` sites reach `YTDBMatchPlanStep`), and the single-plan lifecycle is comprehensively test-guarded, which caps most of the leak/regression exposure. Two should-fixes remain: the "no assertion changes" acceptance breaks against a specific reflective lifecycle test under the abstract-base reading (R1), and the base's lifecycle surface has to match the N-plan checklist Track 8 needs rather than the single-plan shape it is extracted from (R2). One suggestion pins the placeholder-op parameter shape so Track 9's `tail` is not stranded (R3). These build on technical T1–T4 rather than repeating them: T1 framed the base-shape decision, T2 the two emission paths, T3 the producer-rewire trigger, T4 ctor preservation; R1/R2 add the concrete test-suite and forward-dependency consequences those decisions carry.

**Tooling note.** mcp-steroid PSI was reachable but non-functional this session (the kotlinc cold-compile cycle exceeds the ~60s MCP HTTP limit — the technical reviewer confirmed this after retries, and all three pre-split sessions hit it too), so every symbol claim below rests on direct `Read` of source and `grep`/`find`, not PSI find-usages. Class-shape and field/method-declaration facts are exact declaration reads with no reference-accuracy exposure. The one reference-accuracy claim — that `GremlinToMatchStrategy.java:343,437` are the only production references to `YTDBMatchPlanStep` (E2) — carries a grep caveat recorded on that certificate.

## Findings

### R1 [should-fix]
**Certificate**: A2 (Assumption — existing suites stay green with no assertion changes), supported by E1 and E3
**Location**: `track-7.md` `## Plan of Work` item 1 (line 50: "move the row projection, the `ResultShaping` read, and the `ExecutionStream` open/drain/close lifecycle into an abstract superclass"), `## Validation and Acceptance` (line 62: "the existing projection / aggregate / equivalence suites stay green with no assertion changes"). Source: `YTDBMatchPlanStepTest.java:854` (declared-field reflection), `:288,308,332,348` (package-private `projectElement` calls); `YTDBMatchPlanStep.java:106` (the `plan` field).
**Issue**: The track's behavior-neutrality signal is that the existing suites stay green unmodified — and technical T4 folds `YTDBMatchPlanStepTest` into that guard surface. One of its tests breaks that promise under the abstract-base reading item 1 leans toward. `planField_isNonFinal_soCloneAssignsWithoutReflection` (`YTDBMatchPlanStepTest.java:854`) does `YTDBMatchPlanStep.class.getDeclaredField("plan")`, and `getDeclaredField` returns only fields declared on the class itself, never inherited ones. The `plan` field IS the lifecycle field item 1 moves into the base; if it moves, this test throws `NoSuchFieldException` and fails — a behavior-neutral refactor breaking a green test and forcing exactly the assertion/structure change the acceptance line forbids. Likelihood: high under the abstract-base + shared-lifecycle reading (which technical T1 leans toward for Track 8 reuse); zero under the composed-projector reading (the `plan` field stays on the concrete class). Second mechanism: `projectElement` is package-private (`YTDBMatchPlanStep.java:711`) and four tests call it directly on a `YTDBMatchPlanStep` instance (`:288,308,332,348`); if projection moves to the base, the base must live in package `...translator.step` or those call sites lose package access and break too. Impact: the "no assertion changes" acceptance is silently false for the lifecycle unit suite, so a decomposer reading it literally either believes the refactor is behavior-neutral when a test is red, or is forced into an unplanned test edit that muddies the guard signal.
**Proposed fix**: In decomposition, (a) require the extracted base to live in package `...translator.step` so package-private `projectElement` stays test-reachable; (b) enumerate which `YTDBMatchPlanStepTest` cases key on declared members (the `getDeclaredField("plan")` test at :854; the clone tests that assert on the `plan` copy) and decide up front whether the lifecycle fields stay on `YTDBMatchPlanStep` (test stays valid) or move to the base (test knowingly updated); (c) if any move, carve those specific tests out of the "no assertion changes" acceptance line so the green-suite signal stays honest — the other ~19 lifecycle tests and the equivalence suites remain the genuine behavior-neutral guard.

### R2 [should-fix]
**Certificate**: A1 (Assumption — the base supplies what Track 8's `MultiPlanMatchStep` needs), supported by E3
**Location**: `track-7.md` `## Purpose / Big Picture` (line 32), `## Plan of Work` item 1 (line 50), `## Interfaces and Dependencies` (line 80: "Supplies the boundary base that Track 8's `MultiPlanMatchStep` extends"). Source: `YTDBMatchPlanStep.java:138-164,260-305,340-517`; pre-split `risk-iter1.md` R3 (N-plan lifecycle checklist).
**Issue**: The track names "the `ExecutionStream` open/drain/close lifecycle" as a single reusable unit the base lifts out of `YTDBMatchPlanStep` and hands to Track 8. The lifecycle is not one liftable unit for the N-plan step. The current machinery is written end-to-end against a single `plan` field and one NEW→OPEN→DRAINED→REARMED→CLOSED sequence for one stream: `processNextStart` opens `plans[0]` implicitly (`:265-269`), `DRAINED` closes the stream but keeps the one plan open for a possible reset (`:147-151,277-278`), `close` closes that one plan (`:465-482`), `clone` deep-copies one plan into one isolated context (`:485-517`). Pre-split risk R3 already enumerated how the N-plan step diverges: close plan[i] fully on advance to plan[i+1] so only one stream is ever live (not DRAINED-keep-open); on a child exception never open plans[i+1..] and do not double-close plans[0..i-1]; `clone()` N deep copies into N isolated contexts; `reset()` rewinds to plan[0]. If Track 7 lifts the single-plan orchestration into the base as concrete logic, `MultiPlanMatchStep` must override `processNextStart` + `close` + `clone` + the State transitions wholesale — no reuse, and it re-opens exactly the leak / double-close / clone-isolation hazards the split was meant to retire. Technical T1 flagged the opposite failure (a composed projector that shares too little); this is the over-scope failure (an abstract base that hard-codes the single-plan shape). The genuinely shared surface is the per-stream primitives (open one stream, drain it, release it), projection, the `ResultShaping` read, the ordered post-process stage, and `AutoCloseable` (so both steps are closed by `Traversal.close()`) — not the single-vs-N orchestration. Likelihood the track's "the lifecycle" framing under-specifies the seam: medium; the base surface is frozen in Track 7, so a wrong granularity is expensive to correct in Track 8. Impact: Track 8 either blocks on re-cutting the base or ships a `MultiPlanMatchStep` that reimplements the R3 lifecycle from scratch.
**Proposed fix**: In decomposition, validate the base surface against the pre-split risk R3 N-plan checklist explicitly — the base should own per-stream open/drain/close primitives + projection + shaping + ordered post-process + `AutoCloseable`, with the single-plan State-machine orchestration (which plan, when to advance, NEW→…→CLOSED) staying in `YTDBMatchPlanStep` and left for `MultiPlanMatchStep` to supply its own N-plan orchestration (a `protected` advance-to-next-stream seam, per technical T1's fix, is the right shape). State in the track file that "the `ExecutionStream` lifecycle" reused by the base is the per-stream primitives, not the single-plan advance/State logic.

### R3 [suggestion]
**Certificate**: T1 (Testability — placeholder-op parameter shape)
**Location**: `track-7.md` `## Plan of Work` item 3 (line 52: "verified with placeholder / no-op ops"), `## Validation and Acceptance` (line 64). Source: technical `C9` (a TAIL op carries its own `n`).
**Issue**: Track 7 lands only the carrier + declared-order application framework, "verified with placeholder / no-op ops," and defers the real transform stages to Track 9. Technical C9 established that the ordered-`List` design works precisely because a `TAIL` op carries its own `n` inline (that is what retires the pre-split `tailLimit` unset-sentinel hazard). If the placeholder ops used to verify declared-order application are parameterless marker ops, the framework proves ordering is recorded distinctly (`reverse`-then-`unfold` vs `unfold`-then-`reverse`) but never proves the op representation can carry a per-op parameter. Track 9 then discovers at implementation time whether the op interface can hold `tail`'s `n` — a foundation gap surfacing one track late. Likelihood: low (the design intent is captured in the Decision Log); impact if it bites: Track 9 reworks the op type Track 7 froze.
**Proposed fix**: Verify the declared-order framework with at least one parameter-bearing placeholder op (a marker carrying an `int`, mimicking `TAIL`'s `n`) rather than only parameterless markers, so the op representation's ability to carry a per-op parameter is pinned at the foundation. This is a one-line adjustment to the placeholder used in the item-3 unit test, not new scope.

## Evidence base

Certificates grouped by review criterion. PSI was non-functional this session (kotlinc compile-cycle timeout on every `steroid_execute_code` call); symbol evidence is direct `Read` of source and `grep`/`find`. Declaration and field/method-shape facts are exact reads; the one reference-accuracy claim (E2, sole production references) carries a grep caveat.

### Critical path exposure

#### E1 Exposure: base-extraction blast radius into the lifecycle test suite
- **Track claim**: item 1 (line 50) moves the row projection + `ResultShaping` read + `ExecutionStream` lifecycle into an abstract superclass; Validation (line 62) says the existing suites stay green with no assertion changes; technical T4 folds `YTDBMatchPlanStepTest` into the behavior-neutral guard "green unmodified."
- **Critical path trace**:
  1. `planField_isNonFinal_soCloneAssignsWithoutReflection` @ `YTDBMatchPlanStepTest.java:854` calls `YTDBMatchPlanStep.class.getDeclaredField("plan")`.
  2. `Class.getDeclaredField` (JDK contract) returns only fields declared on the class itself — inherited fields throw `NoSuchFieldException`.
  3. The `plan` field is declared at `YTDBMatchPlanStep.java:106` and is the lifecycle field item 1 moves into the base.
  4. `projectElement` is package-private (`YTDBMatchPlanStep.java:711`), called directly at `YTDBMatchPlanStepTest.java:288,308,332,348` on a `YTDBMatchPlanStep` instance — inherited package-private access holds only if the base is in the same package.
- **Blast radius**: under the abstract-base + shared-lifecycle reading, moving `plan` fails one lifecycle test; a cross-package base additionally breaks four `projectElement` call sites. Both are the guard suite the track relies on to prove behavior-neutrality.
- **Existing safeguards**: the composed-projector reading (lifecycle stays on the concrete class) avoids the `plan`-field breakage entirely; the base being same-package avoids the `projectElement` breakage. Both are decomposition choices, not yet made.
- **Residual risk**: MEDIUM — a real, concrete test breakage under one of the two open base-shape readings, with an obvious mitigation once the reading is pinned. → R1

#### E2 Exposure: production reference surface of `YTDBMatchPlanStep`
- **Track claim**: item 2 (line 51) — `GremlinToMatchStrategy` (`replaceAllStepsWithBoundary`) is the sole construction site; the base extraction is behavior-neutral.
- **Critical path trace**:
  1. `grep -rn` over `core server embedded tests` for `getPlan()`/`getBoundaryAlias()`/`getOutputType()`/`getReturnClass()`/`instanceof YTDBMatchPlanStep`/`new YTDBMatchPlanStep`/`extends YTDBMatchPlanStep`.
  2. Production hits on `YTDBMatchPlanStep`: `GremlinToMatchStrategy.java:343` (`instanceof YTDBMatchPlanStep<?, ?>` idempotency scan) and `:437` (`new YTDBMatchPlanStep`). No production caller invokes any getter (the `getPlan`/`getReturnClass` hits in `DatabaseSessionEmbedded`/`YTDBGraphStep`/`GremlinToMatchSmokeTest` are unrelated classes).
  3. Every getter and every `instanceof YTDBMatchPlanStep` caller is in test code, all keyed to the concrete class the track keeps constructed (C1/C3/C10).
- **Blast radius**: bounded to `core`; production is transparent to the base extraction because the concrete `YTDBMatchPlanStep` type still resolves and the `instanceof` scan still matches it (technical C10 CONFIRMED).
- **Existing safeguards**: the class remains the sole constructed boundary type (C3), so `instanceof YTDBMatchPlanStep` and the getters keep resolving through inheritance.
- **Residual risk**: LOW — no production finding. Reference-accuracy caveat: grep, not PSI, so a reflective/star-imported factory or a line-split `new` could be missed; the full textual enumeration of `YTDBMatchPlanStep` in `core/src` shows one `new`, one `instanceof`, and test-only getters, which bounds the risk (mirrors technical C3).

#### E3 Exposure: `ExecutionStream` open/drain/close lifecycle correctness across the base boundary
- **Track claim**: item 1 moves the open/drain/close lifecycle into the base with byte-for-byte identical behavior.
- **Critical path trace**:
  1. `openArming` (`:340`) resolves the graph, rebinds the thread session, rewinds iff REARMED, starts the plan (with a partial-start release at `:379-388`).
  2. `releaseStream` (`:397`) closes the stream, keeps the plan (normal exhaustion / DRAINED); `releaseStreamAndClosePlan` (`:412`) closes stream then plan with `addSuppressed` discipline (exception + `close()`).
  3. `close` (`:465`) gates on CLOSED (not DRAINED, deliberately `:459-462`), uses the `started` flag (`:471`) and the `openStream != null` check to pick the release path; idempotent.
  4. `clone` (`:485`) deep-copies one plan into a child `BasicCommandContext` with the parent-template invariant documented at `:494-503`.
- **Blast radius**: a botched split of `close`/`releaseStreamAndClosePlan`/`clone` across base and subclass leaks a cursor or double-closes a plan — invisible to result-multiset assertions.
- **Existing safeguards**: strong — `YTDBMatchPlanStepTest` covers `close_isIdempotent` (:383), `close_earlyTermination` (:360), `close_streamCloseThrows_planStillClosed` (:407), `close_bothStreamAndPlanCloseThrow_...suppressed` (:434), all three exception paths (:465,487,516), `clone_copiesPlanAgainstIsolatedChildContext` (:567), `clone_twoClonesDrivenConcurrently_eachRunsOwnPlanCopy` (:625), and the four `reset_*` cases (:706-800). A close/clone split that regresses is caught here.
- **Residual risk**: LOW — the lifecycle is well-guarded; the only residual is that the guard suite itself is exposed to the field-move in E1. → supports R1, R2

### Unknowns & assumptions

#### A1 Assumption: the extracted base gives Track 8's `MultiPlanMatchStep` the lifecycle surface it needs
- **Track claim**: Interfaces (line 80) — the base "Supplies the boundary base that Track 8's `MultiPlanMatchStep` extends"; Purpose (line 32) / item 1 (line 50) frame "the `ExecutionStream` open/drain/close lifecycle" as a single reusable unit lifted from `YTDBMatchPlanStep`.
- **Evidence search**: `Read` of `YTDBMatchPlanStep.java` lifecycle members (`:138-164,260-305,340-517`); cross-read of pre-split `risk-iter1.md` R3 (N-plan lifecycle enumeration) and D8 revised (plan `:230-239`). PSI unavailable.
- **Code evidence**: the single-plan lifecycle is written against one `plan` field and the DRAINED-keep-open-for-reset semantics (`:147-151,277-278,465-482`); pre-split R3 enumerated the N-plan divergences (close-on-advance, stop-on-first-exception, clone-N, reset-to-plan[0]) that the single-plan State machine does not provide.
- **Verdict**: UNVALIDATED
- **Detail**: The track treats "the lifecycle" as one liftable unit, but the N-plan step reuses only the per-stream primitives + projection + shaping + `AutoCloseable`, not the single-vs-N orchestration. The base surface is frozen in Track 7, so the granularity has to match the R3 checklist now. → R2

#### A2 Assumption: the existing suites stay green with no assertion changes
- **Track claim**: Validation (line 62) — "the existing projection / aggregate / equivalence suites stay green with no assertion changes"; technical T4 extends this to `YTDBMatchPlanStepTest` "green unmodified."
- **Evidence search**: `grep` for `getDeclaredField`/`getDeclaredMethod` in `YTDBMatchPlanStepTest.java` (two hits: `:854` `plan`, `:879` unrelated `fastPathEntity`); `Read` of the reflective test and the `projectElement` call sites.
- **Code evidence**: `YTDBMatchPlanStepTest.java:854` reads `YTDBMatchPlanStep.class.getDeclaredField("plan")`; the `plan` field is what item 1 moves to the base; `getDeclaredField` does not see inherited fields.
- **Verdict**: CONTRADICTED (under the abstract-base + shared-lifecycle reading)
- **Detail**: Moving `plan` to the base fails this test with `NoSuchFieldException`; the "no assertion changes" claim is then false for the lifecycle unit suite. Zero exposure under the composed-projector reading. → R1

### Testability & coverage

#### T1 Testability: verifying the ordered post-process framework can carry a per-op parameter
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: item 3 verifies the carrier "with placeholder / no-op ops." Declared-order recording (distinct sequences for `reverse().unfold()` vs `unfold().reverse()`) is trivially unit-testable with marker ops. The gap is the parameter-carrying capability: technical C9 established a `TAIL` op must carry its own `n` inline (that is what retires the pre-split sentinel hazard), and a parameterless placeholder never exercises it.
- **Existing test infrastructure**: `YTDBMatchPlanStepTest` and the `ResultShaping` unit surface provide the templates; the acceptance line (`:64`) already unit-tests ordering.
- **Feasibility**: ACHIEVABLE — a one-op adjustment (a marker carrying an `int`) pins the parameter shape at the foundation.
- **Detail**: Low-probability forward gap; recorded so decomposition uses a parameter-bearing placeholder. → R3

#### T2 Testability: behavior-neutrality across the four output paths and the group barrier
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: item 4 proves neutrality over element / MAP / SINGLE_VALUE / SCALAR. For Track 7 the ordered op list is empty, so the post-process stage is a no-op on every path; the concern is whether the no-op is exercised on both emission paths (per-row loop and the `accumulateMap` group barrier `:272-273,311-327`).
- **Existing test infrastructure**: `ProjectionEquivalenceTest`, `PredicateTraversalEquivalenceTest`, `EdgeTraversalEquivalenceTest` (translator-on-vs-off multiset equality, each detecting the boundary via `instanceof YTDBMatchPlanStep`), plus Track 6's aggregate-equivalence coverage that drives the group-barrier path.
- **Feasibility**: ACHIEVABLE — the no-op case on both paths is already covered by the existing per-row and aggregate equivalence suites, so a Track-7 regression that is not a genuine no-op shows red. The non-empty-op-list placement is Track 9's forward concern and is owned by technical T2, not re-found here.
- **Detail**: Documented as a safeguard for the behavior-neutrality claim; no finding. The forward placement risk (ordered stage must reach the group barrier once real ops exist) is technical T2.
