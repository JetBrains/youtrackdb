<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 5, suggestion: 1}
index:
  - {id: R1, sev: should-fix, loc: "YTDBMatchPlanStep.java:88", anchor: "### R1 ", cert: E1, basis: "boundary step is a final class with private lifecycle state; plan's 'MultiPlanMatchStep extends YTDBMatchPlanStep' will not compile"}
  - {id: R2, sev: should-fix, loc: "GremlinToMatchTranslator.java:74; GremlinStepWalker.java:375; GremlinToMatchStrategy.java:392", anchor: "### R2 ", cert: E3, basis: "single-plan TranslationResult/strategy pipeline has no multi-plan carrier and no per-child full-translation sub-walk seam for union"}
  - {id: R3, sev: should-fix, loc: "YTDBMatchPlanStep.java:138-517", anchor: "### R3 ", cert: E2, basis: "N-plan lifecycle (close-on-advance, exception-stops-advance, N-plan clone/reset, one shared ResultShaping) not provided by the single-plan state machine; needs dedicated leak/exception tests"}
  - {id: R4, sev: should-fix, loc: "GlobalConfiguration.java:1011; YTDBGraphFeatureTest.java", anchor: "### R4 ", cert: A3, basis: "translator on by default since T2 but full ~1900 Cucumber suite was never a per-track gate; T7 is first full green gate, concentrating six tracks' translation-correctness risk into one triage step"}
  - {id: R5, sev: should-fix, loc: "jmh-ldbc/.../LdbcQuerySql.java", anchor: "### R5 ", cert: A4, basis: "LDBC queries are SQL; most IC shapes are Phase-2 and decline, so a naively mirrored Gremlin harness measures native-vs-native (null signal)"}
  - {id: R6, sev: suggestion, loc: "YTDBMatchPlanStep.java:625-661; design.md:625-661", anchor: "### R6 ", cert: T2, basis: "reverse() is a per-traverser value transform, not stream-order reverse; a stream-reverse misread is a plausible trap Cucumber would catch late"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 8}
cert_index:
  - {id: E1, verdict: HIGH, anchor: "#### E1 "}
  - {id: E2, verdict: MEDIUM, anchor: "#### E2 "}
  - {id: E3, verdict: MEDIUM, anchor: "#### E3 "}
  - {id: A1, verdict: CONTRADICTED, anchor: "#### A1 "}
  - {id: A2, verdict: UNVALIDATED, anchor: "#### A2 "}
  - {id: A3, verdict: VALIDATED, anchor: "#### A3 "}
  - {id: A4, verdict: UNVALIDATED, anchor: "#### A4 "}
  - {id: T2, verdict: ACHIEVABLE, anchor: "#### T2 "}
flags: [CONTRACT_OK]
-->

## Findings

Track 7 is feasible and no finding recommends skipping it. The union path, however, is under-scoped in three compounding ways that all trace to one fact: the whole translator pipeline — from `TranslationResult` through the strategy build to the `YTDBMatchPlanStep` boundary — is built for exactly one plan, and the boundary step is a `final` class with private lifecycle state. The plan's `## Plan of Work` item 2 and the Track-7 Pre-Flight note both describe `MultiPlanMatchStep` as a subclass that "inherits the post-refactor ctor," which the code cannot support as written. Decomposition should treat R1/R2/R3 as one refactor budget, not three independent edits. The Cucumber and JMH findings (R4/R5) are about the hardening step measuring what it claims to measure.

### R1 [should-fix]
**Certificate**: E1 (Exposure: `MultiPlanMatchStep extends YTDBMatchPlanStep`), A1 (Assumption: inherits the post-refactor ctor)
**Location**: Track file `## Plan of Work` item 2 and `## Interfaces and Dependencies`; plan Pre-Flight ADJUST note; `core/.../gremlin/translator/step/YTDBMatchPlanStep.java:88`
**Issue**: `YTDBMatchPlanStep` is declared `public final class` (line 88) and every field the N-plan lifecycle would need to drive is `private`: the `plan` field, the `openStream` field, the `state` field, and the `State` enum itself (NEW/OPEN/DRAINED/REARMED/CLOSED). The projection surface is private too (`projectOrSkip`, `projectMap`, `projectSingleValue`, `projectScalar`; only `projectElement` is package-private). A class literally cannot `extend` a `final` class, so the plan text as written does not compile. Even setting `final` aside, the sole public constructor (line 200) sets a single `plan` and `state = NEW` — there is no seam for N plans. Likelihood of a naive implementation stalling: certain. Impact: the entire union step (D8) blocks on resolving this before any code lands. Recovery is obvious (extract a shared base or compose), so this is should-fix, not blocker — but decomposition must resolve it first.
**Proposed fix**: In decomposition, replace "extends `YTDBMatchPlanStep`" with an explicit refactor step: extract an abstract base (e.g. `AbstractMatchPlanStep`) that owns the projection surface (`projectOrSkip` + the four `project*` helpers + `convertValue`/`convertMapColumn`) and the per-plan lifecycle primitives (`openArming`/`releaseStream`/`releaseStreamAndClosePlan`), with `YTDBMatchPlanStep` (one plan) and `MultiPlanMatchStep` (N plans) as the two concrete subclasses. Add the base extraction and the visibility relaxations to the track's `## Interfaces and Dependencies` "In scope (modified)" list, which today names only `YTDBMatchPlanStep` LIST/flag edits. The projection logic MUST be shared, not duplicated: a union child that projects differently from the same shape standalone breaks the multiset-equality invariant and the Cucumber green bar.

### R2 [should-fix]
**Certificate**: E3 (Exposure: single-plan translation pipeline), A2 (Assumption: union recogniser builds one `SelectExecutionPlan` per child)
**Location**: `GremlinToMatchTranslator.TranslationResult` (`GremlinToMatchTranslator.java:74`); `GremlinStepWalker.buildResult` (`GremlinStepWalker.java:375`); `GremlinToMatchStrategy.buildPlan`/`replaceAllStepsWithBoundary` (`GremlinToMatchStrategy.java:392,432`); `WalkerContext.walkChild` (`WalkerContext.java:569`)
**Issue**: The Plan-of-Work item 1 says the union recogniser will "sub-walk each child against the registry, build one `SelectExecutionPlan` per child." No seam supports that today. (1) `TranslationResult` carries exactly one `MatchPlanInputs`; `buildResult` produces one; `buildPlan` compiles one; `replaceAllStepsWithBoundary` installs one boundary. There is no multi-plan carrier from walker to strategy. (2) Recognisers see only `RecognitionContext` — no `DatabaseSession`, no `MatchExecutionPlanner` — so a recogniser cannot compile a child plan; compilation lives in the strategy (`GremlinToMatchStrategy.buildPlan`, line 392). (3) The existing sub-walk seam `walkChild` returns a `SubTraversalPredicateAdapter` tuned for predicate composition (pure-filter vs edge-bearing), not a complete standalone per-child translation with its own boundary alias / output type / return items / shaping. (4) Each union child must reproduce the accumulated outer prefix (`g.V().has(...).union(a, b)` = prefix+a concatenated with prefix+b), so the recogniser must fork the in-progress `WalkerContext`/`MatchPatternBuilder` N times — `appendPattern`/`appendFrom` copy fragments but there is no "fork the whole accumulated context into N branches" operation. (5) Cache: `buildPlan` fingerprints one `MatchPlanInputs` (`GremlinPlanFingerprint.fingerprint(translation.inputs())`, line 399); a multi-plan translation has no single inputs to fingerprint, so the union path must either fingerprint the ordered child-inputs list or bypass the cache — and if it bypasses, the JMH "plan-cache-enabled path" (R5) does not cover union. Likelihood the current seams suffice: low. Impact: the union recogniser cannot be written against the existing walk/strategy contract.
**Proposed fix**: Decomposition should add explicit steps for (a) a multi-inputs carrier (`TranslationResult` variant or a `List<MatchPlanInputs>` field) threaded through `buildResult` → `buildPlan` → step install; (b) a new sub-walk mode that returns a full per-child translation (pattern + return + output type + shaping), distinct from the predicate adapter; (c) prefix-forking of the accumulated context into N children; (d) a decision on union cache-keying. Add `TranslationResult`, `GremlinStepWalker`, and `GremlinToMatchStrategy` to the track's "In scope (modified)" list.

### R3 [should-fix]
**Certificate**: E2 (Exposure: N-plan `ExecutionStream` lifecycle), T1 folded into E2
**Location**: `YTDBMatchPlanStep` state machine and lifecycle methods (`YTDBMatchPlanStep.java:138-517`); `SelectExecutionPlan.start/reset/close/copy` (`SelectExecutionPlan.java:76-238`)
**Issue**: This is the primary focus-area hazard and it is real, but the single-plan state machine does not provide the N-plan semantics the plan item 2 requires. Concrete gaps: (a) **Advance leak.** The single-plan `DRAINED` state closes the stream but keeps the plan OPEN for a possible `reset()` re-run (lines 145-151, `releaseStream` at 397). For N plans, on advancing plan[i] → plan[i+1] the drained plan[i] must be fully closed (stream AND plan) so only one plan is ever live; reusing `DRAINED` semantics would leave up to N plans open at once — the exact "one live `ExecutionStream` at a time" invariant the design pins. (b) **Exception path.** The plan requires "exception in plans[N] closes the current stream and re-throws without starting plans[N+1..]." The single-plan catch (lines 289-304) does `state = CLOSED; releaseStreamAndClosePlan()` on the one plan — the multi-plan version must additionally guarantee plans[i+1..] are never opened and that already-closed plans[0..i-1] are not double-closed. (c) **clone().** `clone()` (line 485) deep-copies exactly one `plan` into one isolated child context; union needs N deep copies into N isolated contexts, or a shared-context decision — the clone-isolation invariant in the Javadoc (lines 495-503) must be re-derived for N plans. (d) **reset().** must rewind to plan[0] and re-run all N. (e) **Shared shaping.** The boundary holds a single `ResultShaping` (line 119); union children that agree on `BoundaryOutputType` but differ on shaping (e.g. `union(values('a'), values('b'))` with different presence keys) cannot be represented by one shared shaping — so either children must agree on shaping too (tighten the type-agreement check beyond output type) or shaping becomes per-plan. Likelihood of a subtle leak or double-open if implemented by inheritance-with-flags: medium-high. Impact: resource leaks and partial-iteration bugs that unit result-multiset tests will not catch.
**Proposed fix**: Add explicit lifecycle test steps to the decomposition that assert, independent of results: exactly one open `ExecutionStream` at any point during multi-plan iteration; no plan leak when plan[i] throws mid-iteration (verify plans[i+1..] never opened, current stream+plan closed, exception primary with release failure as `addSuppressed`); `clone()` produces N independent plan copies; `reset()` re-runs from plan[0]; `close()` idempotent across the currently-open plan. Decide and document whether union children must agree on full `ResultShaping` or the step carries per-plan shaping.

### R4 [should-fix]
**Certificate**: A3 (Assumption: Cucumber green with the strategy registered)
**Location**: `GlobalConfiguration.java:1011` (default `true`); `core/.../gremlintest/YTDBGraphFeatureTest.java`, `embedded/.../EmbeddedGraphFeatureTest.java`; track file `## Plan of Work` item 5
**Issue**: `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to `true` (line 1018-1020) and the strategy has been registered by default since Track 2 (`YTDBGraphImplAbstract.java:79`). But the full ~1900-scenario Cucumber suite was never a per-track gate: Track 2's scope lists only "a Cucumber smoke check" (`track-2.md:504`), and Track 6's episode records no Cucumber run. So Track 7's item 5 ("run the full Cucumber suite ... and fix any cross-track regression") is the **first** full-suite green gate over the accumulated recognised set of Tracks 2-6. Two consequences: (1) a latent mistranslation in any prior track's recogniser (predicate layer T4, logical filters T5, result shaping T6) surfaces here for the first time, and "fix any cross-track regression" can root-cause into any prior track — the triage scope is unbounded and is not budgeted. (2) The new Track-7 shapes (union/fold/unfold/reverse/tail) that newly translate previously-native scenarios are the shapes most at risk; a wrong-but-non-throwing multiset shows red (Cucumber asserts exact results, so this is caught, which is good), but a shape that now translates and coincidentally matches the fixture while diverging on untested data is a silent coverage gap. Likelihood of surfacing at least some cross-track red: medium. Impact: schedule/scope, not correctness-of-approach.
**Proposed fix**: Sequence item 5 so the full suite runs **early** in the track, before the new union/list-shaping recognisers are added, to separate pre-existing cross-track regressions from Track-7-introduced ones and to size the triage. Budget explicit triage time for cross-track root-causing. Keep the per-step scenario catalogue as the artifact that maps each green scenario to the recogniser that claims it, so a future regression localizes fast. (Reference-accuracy caveat: the "full suite was never gated" claim rests on the track-2/track-6 file text and episodes, not an execution log; confirm with the user or CI history if the triage budget hinges on it.)

### R5 [should-fix]
**Certificate**: A4 (Assumption: Gremlin-on-vs-off JMH baseline measures the translator's value)
**Location**: `jmh-ldbc/.../LdbcQuerySql.java` (all LDBC queries are `.sql` resources); track file `## Plan of Work` item 5 and `## Context and Orientation`
**Issue**: The LDBC benchmark corpus is SQL (`IS1..IS7`, `IC1..IC13` loaded from `ldbc-queries/*.sql`); jmh-ldbc touches Gremlin only for data loading (`LdbcDatabaseTool`) and correctness checks. "Mirroring the LDBC SQL benchmarks" therefore means hand-writing Gremlin traversal equivalents. Most LDBC IC queries are Phase-2 shapes — variable-depth friend-of-friend (`repeat`/`times`), `optional`, OR over edge-bearing sub-traversals, path manipulation — all explicitly out of the Phase-1 recognised set (plan `## Non-Goals`). A Gremlin traversal containing any such step declines whole under D3 and runs native. If the mirrored traversals decline, the "on vs off" harness compares native-vs-native and measures **nothing** — yet the track calls this "the load-bearing measurement of the translator's value with the plan cache enabled." Likelihood a naive full-IC mirror measures a null delta: high. Impact: the baseline, the track's stated validation of the whole feature's value, is meaningless.
**Proposed fix**: Decomposition must pin the benchmarked traversals to shapes that are actually recognised (likely IS short-reads and/or synthetic single/double-hop + filter + projection + count shapes), and add an assertion step that each benchmarked traversal installs a `YTDBMatchPlanStep` (verify via `explain()` / the boundary-step-present check) with the translator on — so an accidentally-declining shape fails the harness setup rather than silently reporting a no-op delta. State the chosen shape set in the track file. Note the 16 existing benchmark classes: mirroring all of them pressures the ~20-file soft ceiling, so scope the mirror to the recognised subset rather than 1:1.

### R6 [suggestion]
**Certificate**: T2 (Testability: list-shaping terminator composition + `tail(n)` boundary cases)
**Location**: design `## List-shaping terminators` (`design.md:625-661`); `YTDBMatchPlanStep.projectScalar`/value paths; new recognisers `FoldStep`/`UnfoldStep`/`ReverseStep`/`TailGlobalStep`
**Issue**: The composition and boundary rules are well-specified and cheaply testable at the recogniser level: `n=0` emits nothing, `n<0` declines, `fold().tail(3)` / `fold().unfold()` decline (two terminators), `reverse().unfold()` / `unfold().reverse()` accepted (both flags). No risk there. Two implementation traps worth flagging: (1) **`reverse()` semantics.** TP 3.7+ `ReverseStep.map` operates on the value inside the current traverser (reverse a string, reverse a collection's elements), NOT stream order (design lines 625-640). An implementer who reverses the emission stream instead would pass shape but fail on value — Cucumber catches it, but late. (2) **Pending-emission buffer for `unfold`.** `processNextStart` today emits exactly one traverser per call (one row → one payload, or SKIP-and-continue; lines 275-286). `unfold` turns one upstream emission into N traversers, so the step must buffer a pending iterator across calls; combined with a drain-then-emit terminator (`fold` LIST / `tail` ring buffer) it becomes drain-then-unfold-each — a genuine restructure of the emit loop, not a flag read. This is more than "set `unfoldOutput = true`" as item 3 phrases it.
**Proposed fix**: Add a recogniser-level composition test matrix (accept/decline per pair) and a boundary-level test that `unfold` over a multi-valued projection emits one traverser per element across successive `processNextStart` calls. Pin a `reverse()`-on-a-string and `reverse()`-on-a-collection test that asserts value transform with stream order unchanged.

## Evidence base

#### E1 Exposure: MultiPlanMatchStep subclassing a final boundary step
- **Track claim**: `## Plan of Work` item 2 — "`MultiPlanMatchStep` extending `YTDBMatchPlanStep`"; Pre-Flight ADJUST note — "`MultiPlanMatchStep extends YTDBMatchPlanStep` inherits the post-refactor ctor."
- **Critical path trace**:
  1. `YTDBMatchPlanStep` declared `public final class ... extends AbstractStep` @ `YTDBMatchPlanStep.java:88` — a final class cannot be extended.
  2. Lifecycle state all private: `private InternalExecutionPlan plan` (106), `private ExecutionStream openStream` (127), `private State state` (164), `private enum State` (138).
  3. Projection surface private: `projectOrSkip` (523), `projectMap` (537), `projectSingleValue` (593), `projectScalar` (617); only `projectElement` package-private (711).
  4. Sole public ctor sets one `plan`, `state = NEW`, one `ResultShaping` (200-216) — no N-plan seam.
- **Blast radius**: the entire D8 union step blocks; any implementer following the plan literally hits a compile error at "extends".
- **Existing safeguards**: none — this is a plan-vs-code contradiction, not a runtime hazard.
- **Residual risk**: HIGH (certain compile failure on a literal read), but with an obvious refactor recovery, hence should-fix at finding level.

#### E2 Exposure: N-plan ExecutionStream lifecycle (one live stream, exception stops advance)
- **Track claim**: item 2 — "first `processNextStart` opens `plans[0].start()`, advances ... on exhaustion; one live stream at a time; exception in `plans[N]` closes the current stream and re-throws without starting `plans[N+1..]`."
- **Critical path trace**:
  1. `SelectExecutionPlan.start()` returns a fresh `ExecutionStream` per call (`SelectExecutionPlan.java:85`); `reset` re-runs steps (107); `close` closes `lastStep` (76); `copy` deep-copies (238).
  2. Single-plan `DRAINED` keeps the plan OPEN after stream close (`YTDBMatchPlanStep.java:145-151`, `releaseStream` 397) — for N plans this leaks up to N open plans if reused verbatim on advance.
  3. Exception catch does `state = CLOSED; releaseStreamAndClosePlan()` on the one plan (289-304) — multi-plan must also guarantee plans[i+1..] never open and plans[0..i-1] not double-closed.
  4. `clone()` copies one plan into one isolated child context (485-517); union needs N.
- **Blast radius**: cursor/resource leaks and partial-iteration bugs on union; invisible to result-multiset assertions.
- **Existing safeguards**: the single-plan step is thoroughly guarded (state enum, addSuppressed discipline, clone isolation) — a strong template to extend, but not the N-plan behavior itself.
- **Residual risk**: MEDIUM — implementable by extending the same discipline, but subtle; needs dedicated lifecycle tests.

#### E3 Exposure: single-plan translation pipeline has no multi-plan carrier
- **Track claim**: item 1 — union recogniser will "build one `SelectExecutionPlan` per child."
- **Critical path trace**:
  1. `TranslationResult` holds one `MatchPlanInputs inputs` (`GremlinToMatchTranslator.java:74-81`).
  2. `GremlinStepWalker.buildResult` builds one `MatchPlanInputs` from one `WalkerContext` (`GremlinStepWalker.java:375-416`).
  3. `GremlinToMatchStrategy.buildPlan` compiles one plan and fingerprints one inputs (`GremlinToMatchStrategy.java:392-414`); `replaceAllStepsWithBoundary` installs one boundary (432-447).
  4. Recognisers see only `RecognitionContext` (WalkerContext implements it) — no session/planner; `walkChild` returns a `SubTraversalPredicateAdapter` (WalkerContext.java:569), a predicate composer, not a full per-child translation.
- **Blast radius**: `UnionStepRecogniser` cannot express N standalone child plans against the current contract without new plumbing.
- **Existing safeguards**: none relevant.
- **Residual risk**: MEDIUM — the plumbing is additive and localized to the translator package, but omitted from the track's modified-scope list.

#### A1 Assumption: MultiPlanMatchStep inherits the post-refactor constructor
- **Track claim**: Pre-Flight ADJUST — "`MultiPlanMatchStep extends YTDBMatchPlanStep` inherits the post-refactor ctor."
- **Evidence search**: Read `YTDBMatchPlanStep.java` in full; grep `extends YTDBMatchPlanStep` (0 hits) and `new YTDBMatchPlanStep` (call sites) across `core/src` excluding worktrees. (Reference-accuracy caveat: PSI find-usages/inheritors timed out this session; the "no subclass / single production construction site at GremlinToMatchStrategy.java:437" claim rests on grep. The `final` modifier and member visibility are direct source reads, not reference searches, so they are not subject to the caveat.)
- **Code evidence**: `public final class YTDBMatchPlanStep` (line 88); sole public ctor sets one plan + `state = NEW` (200-216); no subclass exists.
- **Verdict**: CONTRADICTED
- **Detail**: A `final` class cannot be extended, and the single-plan ctor cannot initialize N plans. The inheritance model must be replaced (abstract-base extraction or composition).

#### A2 Assumption: union recogniser builds one SelectExecutionPlan per child via sub-walk
- **Track claim**: item 1 — "sub-walk each child against the registry, build one `SelectExecutionPlan` per child."
- **Evidence search**: Read `GremlinStepWalker.subWalk`/`walkChild`, `SubTraversalPredicateAdapter` role, `RecognitionContext` surface (via WalkerContext), and the strategy's compile path.
- **Code evidence**: `walkChild` → `SubTraversalPredicateAdapter` (WalkerContext.java:569, GremlinStepWalker.java:319-338); recognisers have no session/planner; compilation lives in `GremlinToMatchStrategy.buildPlan` (392).
- **Verdict**: UNVALIDATED
- **Detail**: The existing sub-walk captures predicate composition, not a full standalone child translation, and recognisers cannot compile plans. A new full-translation sub-walk mode plus a multi-plan carrier are prerequisites.

#### A3 Assumption: Cucumber green with the strategy registered
- **Track claim**: `## Context and Orientation` — "the ~1900-scenario TinkerPop Cucumber suite must be green with the strategy registered."
- **Evidence search**: Read `GlobalConfiguration` line 1011-1020 (default), grep strategy registration (`YTDBGraphImplAbstract.java:79`), grep Cucumber mentions in track-2/track-6 files.
- **Code evidence**: default `true` (1018-1020); registered since Track 2; track-2 scope lists only "a Cucumber smoke check" (`track-2.md:504`); track-6 episode has no Cucumber run.
- **Verdict**: VALIDATED (translator is on by default) with a scope caveat
- **Detail**: Because the full suite was not a per-track gate, Track 7 is the first full green run over Tracks 2-6's recognised set; cross-track mistranslations surface here and triage scope is unbudgeted (R4).

#### A4 Assumption: Gremlin-on-vs-off JMH baseline measures the translator's value
- **Track claim**: `## Context and Orientation` — "A Gremlin-on-vs-off JMH suite mirroring the existing LDBC SQL benchmarks is the load-bearing measurement of the translator's value with the plan cache enabled."
- **Evidence search**: grep jmh-ldbc for Gremlin traversal usage; read `LdbcQuerySql.java` (query source shapes); cross-check plan `## Non-Goals` against LDBC IC shape complexity.
- **Code evidence**: LDBC queries are SQL resources (`LdbcQuerySql.java:13-37`); Gremlin appears only in data-load/correctness helpers; IC shapes use variable-depth/optional/OR-over-edges — all Phase-2 non-goals.
- **Verdict**: UNVALIDATED
- **Detail**: A naive full-IC Gremlin mirror declines to native on/off alike, measuring a null delta. The harness must pin verified-recognised shapes and assert boundary-step installation.

#### T2 Testability: list-shaping terminator composition + tail(n) boundary cases
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: Composition accept/decline and `tail(n)` boundaries (n=0, n<0) are pure recogniser-level decisions, trivially unit-testable without a live graph. The `unfold` pending-emission buffer and `reverse` per-value (not stream-order) semantics need boundary-step tests with multi-valued/collection payloads.
- **Existing test infrastructure**: `YTDBMatchPlanStepTest` (projection + lifecycle harness, `core/.../step/`), the per-recogniser `*RecogniserTest` pattern, and `ProjectionEquivalenceTest` (translator-on-vs-off multiset equality) provide the templates.
- **Feasibility**: ACHIEVABLE
- **Detail**: Low risk; the only traps are the reverse-semantics misread and the cross-call unfold buffer (R6), both catchable with targeted boundary tests.
