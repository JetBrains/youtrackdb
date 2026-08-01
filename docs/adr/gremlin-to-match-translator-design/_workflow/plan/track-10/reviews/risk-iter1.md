<!-- MANIFEST
findings: 6   severity: {blocker: 1, should-fix: 4, suggestion: 1}
index:
  - {id: R1, sev: blocker,    loc: "core/pom.xml:323", anchor: "### R1 ", cert: A1, basis: "The track's acceptance criterion is a full core run, but nobody has enumerated the full failure set; the sequential surefire execution also runs the core Cucumber runner and 13 other gremlintest classes, so 'green' may be unreachable at this track's size"}
  - {id: R2, sev: should-fix, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java:132", anchor: "### R2 ", cert: X4, basis: "Item 2 treats g.V(rid) as a test-contract question, but the underlying change makes every by-id lookup compile an uncached MATCH plan where the native path ran no query"}
  - {id: R3, sev: should-fix, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java:180", anchor: "### R3 ", cert: A4, basis: "Copy-on-re-arm cannot reuse clone()'s isolation recipe: at re-arm time the template context carries the per-run state clone()'s own assert forbids"}
  - {id: R4, sev: should-fix, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:506", anchor: "### R4 ", cert: X1, basis: "After a product-side item 1 the two re-arm paths diverge — DRAINED rewinds in place, CLOSED installs a copy — and getPlan(), which the metrics capture reads, returns a different object in each"}
  - {id: R5, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:71", anchor: "### R5 ", cert: X3, basis: "Closing the detection hole fires every deferred gate on the branch at once — all modules, three OS legs, disk-storage env, the coverage gate over eight tracks of diff — with no triage rule for what it finds"}
  - {id: R6, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:87", anchor: "### R6 ", cert: TS2, basis: "The introspection override's one observable effect (EXPLAIN subSteps) has no acceptance criterion and no test, while two repo-wide index-count helpers recurse the same accessor"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 9}
cert_index:
  - {id: X1, verdict: MEDIUM, anchor: "#### X1 "}
  - {id: X2, verdict: LOW, anchor: "#### X2 "}
  - {id: X3, verdict: HIGH, anchor: "#### X3 "}
  - {id: X4, verdict: MEDIUM, anchor: "#### X4 "}
  - {id: A1, verdict: UNVALIDATED, anchor: "#### A1 "}
  - {id: A2, verdict: VALIDATED, anchor: "#### A2 "}
  - {id: A3, verdict: VALIDATED, anchor: "#### A3 "}
  - {id: A4, verdict: CONTRADICTED, anchor: "#### A4 "}
  - {id: A5, verdict: VALIDATED, anchor: "#### A5 "}
  - {id: A6, verdict: VALIDATED, anchor: "#### A6 "}
  - {id: TS1, verdict: DIFFICULT, anchor: "#### TS1 "}
  - {id: TS2, verdict: ACHIEVABLE, anchor: "#### TS2 "}
  - {id: TS3, verdict: DIFFICULT, anchor: "#### TS3 "}
flags: [CONTRACT_OK]
-->

# Track 10 — risk review, iteration 1

The track's largest risk is its own acceptance criterion. `./mvnw -pl core test` does not mean "the four metrics scenarios plus the unit tests": `core/pom.xml`'s `sequential-tests` execution carries no `**/gremlintest/**` exclusion, so the same run drives the core half of the ~1900-scenario TinkerPop Cucumber suite (`YTDBGraphFeatureTest`, `@Category(SequentialTest)`) and thirteen other `gremlintest` classes — the whole-feature gate Track 9 believes it still owns. Nobody has published the full failure list for that run, and the one enumeration on record was wrong (Track 8: "the track base carries six problems, not four, and a different set"). Everything else here is smaller. The copy-on-re-arm blast radius is real but well fenced by the four equivalence suites plus the core Cucumber run, and it has one sharp edge: the in-file precedent for plan copying — `clone()`'s isolated child context — cannot be reused at re-arm time, because a completed pass leaves exactly the per-run context state `MultiPlanMatchStep.clone()`'s own assert exists to forbid. The introspection override is safe: five sibling precedents, no test on MATCH `subSteps`, and the two repo helpers that recurse `getSubSteps()` to count index usage are SELECT-only today. The engine-surface amendment is correctly scoped, not erosion — it names two files and one behaviour-neutral accessor rather than opening a general exception.

**Reference-accuracy caveat.** PSI was not attempted: `steroid_execute_code` hits the known cold-kotlinc timeout in this repo, which the iteration-1 technical reviewer already paid for at 55 s. Every symbol result below is grep plus a full read of the declaring file. The negative results that matter — "no test asserts on MATCH `subSteps`", "no MATCH query reaches the index-count helpers", "no scenario re-arms a translated traversal without closing it" — carry the usual grep risk of a missed polymorphic or renamed site; each was cross-checked by reading the helper and its callers, which bounds the risk for those helpers without eliminating it repo-wide.

## Findings

### R1 [blocker]
**Certificate**: A1 (UNVALIDATED), supported by A2, X3

**Location**: track-10.md `## Validation and Acceptance` first bullet; `core/pom.xml:298-331`; `core/src/test/java/.../gremlin/gremlintest/YTDBGraphFeatureTest.java:17-18`

**Issue**: The track scopes itself to four named scenarios and then accepts on a criterion that covers far more than four. `core/pom.xml` binds two surefire executions to the `test` phase. `default-test` (`:298`) excludes both the `SequentialTest` group (`:307`) and `**/gremlintest/**` (`:309`). `sequential-tests` (`:323`) selects the `SequentialTest` group (`:329`) with **no** path exclusion and no `classpathDependencyExcludes`, so it runs the whole `gremlintest` tree: `YTDBQueryMetricsStrategyTest`, `YTDBProcessTest`, and `YTDBGraphFeatureTest` — the Cucumber runner Track 9's plan names as one of the two homes of the ~1900-scenario suite (`track-9.md:36`).

No artifact in `_workflow/` records a full-suite failure list. The Track 8 evidence describes bisection by "green/red brackets from real test runs", and the track file's own `How to run the class` section gives a single-class command (`GREMLIN_TESTS=…YTDBQueryMetricsStrategyTest ./mvnw -pl core -o test -Dtest=YTDBProcessTest`), which is what a bisection over 117 commits would realistically use. So the four-failure figure is a claim about one class, promoted to a claim about the module.

The enumeration has already failed once on this exact question. Track 8's `## Surprises & Discoveries` (2026-08-01): "The track base actually carries **six** problems, not four, and a different set — the matching count was a coincidence." Those two extra problems are named nowhere.

Two failure modes, both expensive at this point in the track. If the core Cucumber runner is red, Track 10 cannot meet its headline criterion at ~7-10 files and silently inherits Track 9's cross-track mistranslation triage — a bucket Track 9's own Scope line marks "unsized until the first run". If it is green, the track has been carrying an unnecessary unknown into three product-side decisions.

**Proposed fix**: Make the first concrete step a full `./mvnw -pl core test` on the branch tip that records the complete failure list (class + method + assertion) into `## Surprises & Discoveries` before any code changes. Add a triage rule to `## Plan of Work`: a failure inside `YTDBQueryMetricsStrategyTest` is in scope; a Cucumber or `YTDBProcessTest` failure is recorded and routed to Track 9's triage bucket unless it is a regression this track's own edits cause; anything else escalates. Restate the acceptance criterion against the recorded baseline ("no failure outside the recorded pre-existing set") rather than against unqualified green, so a large pre-existing Cucumber bucket does not silently redefine the track.

### R2 [should-fix]
**Certificate**: X4, supported by A5

**Location**: track-10.md `## Plan of Work` item 2; `StartStepRecogniser.java:116`, `:132`; `GremlinToMatchTranslator.java:145`; `GremlinToMatchStrategy.java:419`

**Issue**: Item 2 asks which contract `byIdLookupSurfacesNullPlan` should assert. The question underneath it is not about the assertion. The test's comment states the old behaviour exactly: "a by-id lookup takes the branch that runs no query" (`YTDBQueryMetricsStrategyTest.java:334`). Under the translator `g.V(rid)` now normalises through `StartStepRecogniser.normaliseIds` (`:116`), marks the walk RID-bearing (`:132`), and RID-bearing walks set `cacheEligible = false` (`GremlinToMatchTranslator.java:145`), which routes the build to `buildPlanUncached` (`GremlinToMatchStrategy.java:419`) with no fingerprint and no `GremlinPlanCache` probe. So every by-id lookup now runs the planner from scratch and executes a `SelectExecutionPlan`, where the native path did a direct record load. The pinned-RID promotion makes execution cheap; compilation is the new per-call cost, and it is paid on every call because the cache is bypassed by design.

The plan surfacing to the metrics listener is the *symptom* that made this visible. Item 2 offers two options, and one of them — "suppress the capture for that shape" — removes the symptom while leaving the cost, deleting the only signal in the tree that a by-id lookup compiles a query. The other option (update the contract) is defensible but should not be recorded as a test fix.

The JMH baseline that would size this lands in Track 9, after this track, and `track-9.md:82` does not name `g.V(rid)` among the benchmark shapes.

**Proposed fix**: Rule out the suppress-the-capture option explicitly in the item-2 Decision Record, and record the performance dimension: by-id lookup translates, bypasses the plan cache, and pays a planner pass per call. Add a cross-track hint to `track-9.md` `## Interfaces and Dependencies` putting `g.V(rid)` / `g.V(ids)` into the on-vs-off JMH shape list. If the DR concludes the cost is unacceptable, that is an ESCALATE under the track's own first invariant, not a step.

### R3 [should-fix]
**Certificate**: A4 (CONTRADICTED), supported by X1

**Location**: track-10.md `## Plan of Work` item 1 (the T2 paragraph); `YTDBMatchPlanStep.java:94-124`; `MultiPlanMatchStep.java:156-215`; `MatchPrefetchStep.java:100`; `core/pom.xml:36`

**Issue**: Item 1 settles on copy-on-re-arm via `InternalExecutionPlan.copy` but does not say which `CommandContext` the copy runs against. The obvious answer is the one already in the file — both `clone()` implementations build a fresh `BasicCommandContext` and parent it to the plan's own context with `setParentWithoutOverridingChild`. That recipe does not transfer, and the code says so itself.

Both `clone()` bodies document the invariant the isolation depends on: "the parent (template) context must stay free of per-run variables", because `BasicCommandContext.setVariable` propagates a write upward only for a key the parent already holds. `MultiPlanMatchStep.clone()` turns it into an executable check (`:180`): `assert (templateVariables == null || templateVariables.isEmpty()) && seededSystemVariable(templateContext) < 0`. It holds at clone time because cloning precedes iteration.

At re-arm time it does not hold. A completed pass writes into the plan's own context. `MatchPrefetchStep.internalStart` stores the drained alias rows under `PREFETCHED_MATCH_ALIAS_PREFIX + alias` (`MatchPrefetchStep.java:100`) on the context `SelectExecutionPlan.start()` threads through (`SelectExecutionPlan.java:85`), and the MATCH element path writes the `$current` / `$matched` / `$current_match` system slots the same assert enumerates. So a re-arm that reuses the clone recipe either trips that assert — `core/pom.xml:36` puts `-ea` in the surefire `argLine`, so it fires as an `AssertionError` in every core test run — or, on the single-plan step where no assert exists, silently parents the fresh copy to a dirty template whose values resolve through on lookup.

**Proposed fix**: Have item 1's Decision Record state the context explicitly: the re-arm copy runs against a clean `BasicCommandContext` seeded only with the session and the input parameters, not one parented to the used context. Add a `## Validation and Acceptance` line requiring the re-arm test to run against a real prefetched plan with assertions enabled, so the `MultiPlanMatchStep` guard is actually exercised — the mocked-plan harness (T2) constructs no context and cannot reach it.

### R4 [should-fix]
**Certificate**: X1, supported by TS1

**Location**: track-10.md `## Plan of Work` item 1 and `## Validation and Acceptance`; `AbstractMatchPlanStep.java:427-429`, `:506-512`; `YTDBQueryMetricsStrategyTest.java:596-633`

**Issue**: A product-side item 1 leaves the base with two different re-arm semantics behind one `reset()`. From `OPEN` / `DRAINED` the step keeps its plan and `openArming()` calls `rewindPlan(ctx)` (`:427-429`) — the plan object survives. From `CLOSED` the step installs a copy and the original is discarded. `YTDBMatchPlanStep.getPlan()` is the accessor `YTDBQueryMetricsStep.capturedExecutionPlan()` reads, so the monitoring layer observes one object in the first case and a different one in the second, and observes the closed original in the window between `reset()` and the next `processNextStart()`.

The scenario test that item 1 exists to fix asserts the surviving-plan contract in its name and in its message: `resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults`, "the MATCH boundary keeps its plan across reset()" (`:617-620`), and the comment above the pair states it as the contract that distinguishes the two source paths (`:596-599`). Under copy-on-re-arm the boundary does not keep its plan on the path the test drives; the assertion stays green only because it is `isNotNull()`.

The in-place path has its own soft spot worth deciding on rather than inheriting. `MatchPrefetchStep.internalStart` closes its sub-plan on every start (`MatchPrefetchStep.java:104`), and its `reset()` forwards to `prefetchExecutionPlan.reset(ctx)`, which cannot clear the sub-plan's sticky per-step close guard. The re-run works because the release that matters happens through the explicit `ExecutionStream.close(ctx)` rather than the step chain, but the in-place path is restarting a closed sub-plan in exactly the sense T2 ruled out one level up.

**Proposed fix**: Pin one contract in the item-1 Decision Record: either both re-arm paths copy (uniform, and it retires the in-place restart of a closed prefetch sub-plan), or `CLOSED` alone copies and the DR states why the asymmetry is acceptable and what `getPlan()` means after each. Either way, update `resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults` — its name, its `as(...)` message, and the comment block above the pair — in the same commit, per the keep-comments-in-sync rule the track already applies to the `AbstractMatchPlanStep` Javadoc.

### R5 [should-fix]
**Certificate**: X3 (HIGH), supported by TS3

**Location**: track-10.md `## Plan of Work` item 4 and `## Validation and Acceptance`; `.github/workflows/maven-pipeline.yml:157-200`, `:399-426`, `:485-555`, `:568-624`

**Issue**: Item 4 weighs three mechanisms for closing the detection hole and says nothing about what they will find. The mechanisms are not equivalent in blast radius, and the track's acceptance criteria cover none of it.

`test-linux` runs `./mvnw clean package` across every module, on three OS legs; the amd64 JDK 21 leg adds `-Dyoutrackdb.test.env=ci` (disk storage) plus `-P docker-images,coverage` (`:173`, `:200`). The branch's verification to date is in-memory `core` only. Downstream of that sit `test-small-cache-linux` (`:426`), the coverage gate against `origin/develop` (`:485-555`) and the test-count gate (`:568-624`). Track 8's own retrospective records the coverage gate as never run in any of three review iterations, with the 85% / 70% thresholds unmeasured — and the gate compares the whole branch diff, which is now eight tracks deep.

So undrafting PR #1038 fires every deferred gate on this branch simultaneously, inside a remediation track sized at ~7-10 files whose acceptance criterion mentions only `core`. The `workflow_dispatch` option (T7) has the same discovery scope but keeps the PR's merge state out of it, which is the difference that matters here.

**Proposed fix**: Mark `workflow_dispatch` as item 4's verification lever and undrafting as the permanent mechanism to land only after the dispatch comes back readable. Add the same triage rule R1 asks for, extended to non-`core` modules and to the two gates: a failure outside `core` is recorded and routed, not absorbed; a coverage-gate or test-count-gate failure over accumulated branch debt is a plan-level escalation, not a Track 10 step. Add one acceptance line stating what item 4 must demonstrate — a pipeline run whose result is visible on the PR — so "closed the hole" is not satisfied by a config edit nobody exercised.

### R6 [suggestion]
**Certificate**: TS2, supported by X2, A6

**Location**: track-10.md `## Validation and Acceptance`; `ExecutionStep.java:35-47`; `tests/src/test/java/com/jetbrains/youtrackdb/junit/BaseDBJUnit5Test.java:607-623`; `core/src/test/java/.../sql/CommandExecutorSQLSelectTest.java:1963-1980`

**Issue**: Item 3 identifies its one observable effect — `EXPLAIN` result documents gaining nested `subSteps` through `ExecutionStep.toResult` — and `## Validation and Acceptance` does not carry it. The seven criteria cover the suite, the index-usage answer, the close-then-reset case, the Mockito-proof assertion, the Javadoc sweep and the plan-file amendment; nothing pins the accessor's contract or the EXPLAIN shape. `MatchStepUnitTest` already hosts exactly this test for two of the five precedents (`testHashJoinGetSubSteps`, `testFilterNotMatchPatternStepGetSubSteps`), so the home costs nothing to find.

The reason to bother is that two helpers in the tree derive assertions from this recursion, in a module the track does not run. `BaseDBJUnit5Test.indexesUsed` (`tests` module) and `CommandExecutorSQLSelectTest.indexUsages` both walk `getSubSteps()` and `getSubExecutionPlans()` to count `FetchFromIndexStep`s, and their callers assert exact counts (`SQLSelectIndexReuseTest` asserts `0` in three places and `1` in fifteen). Every caller found issues SQL `SELECT`, so no MATCH plan reaches them today and the override is safe — but that safety is a property of the current call sites, not of anything asserted. A MATCH-bearing caller added later changes counts silently.

**Proposed fix**: Add one acceptance line: `MatchPrefetchStep.getSubSteps()` and `MatchFirstStep.getSubSteps()` are unit-tested in `MatchStepUnitTest` alongside the five precedents, and one test pins the `EXPLAIN` result document for a prefetched MATCH plan carrying its nested `subSteps`. Note in the item-3 Decision Record that `getSubSteps()` recursion is also how `BaseDBJUnit5Test.indexesUsed` counts index usage, so a future MATCH caller of that helper inherits the new visibility.

## Evidence base

#### X1 Exposure: the boundary-base lifecycle, on the path every translated traversal takes
- **Track claim**: item 1's product side adds copy-on-re-arm to `AbstractMatchPlanStep` plus both concrete subclasses.
- **Critical path trace**:
  1. Entry: `AbstractMatchPlanStep.processNextStart()` @ `AbstractMatchPlanStep.java:237` — every row of every recognised traversal since Track 2 comes through here.
  2. `openArming()` @ `:394` — closes a stale cursor, resolves the graph, rebinds `planContext()` to the iteration-thread session, and calls `rewindPlan(ctx)` when `state == REARMED` (`:427-429`).
  3. `startPlanStream()` @ `:431` → `YTDBMatchPlanStep.plan.start()` (`YTDBMatchPlanStep.java:140`) or `MultiPlanMatchStep`'s `MultipleExecutionStream` over N children (`MultiPlanMatchStep.java:258`).
  4. `close()` @ `:523` sets `CLOSED` and calls `closePlan()`; `reset()` @ `:506` re-arms from `OPEN` / `DRAINED` only.
- **Blast radius**: the base is the sole execution seam for Tracks 2-8's entire delivered surface. A regression in arming, rewind, or close reaches every recognised shape — element, projection, aggregate, union — not just the metrics scenarios. The two subclasses' non-final `plan` / `plans` fields (documented at `YTDBMatchPlanStep.java:36-40` and `MultiPlanMatchStep.java:97-101` as non-final for `clone()`'s pre-publication write) become mutable at a second point in the lifecycle, this time after publication.
- **Existing safeguards**: four end-to-end equivalence suites in `core` compare translator-on against translator-off over real plans — `EdgeTraversalEquivalenceTest`, `PredicateTraversalEquivalenceTest`, `ProjectionEquivalenceTest`, `UnionTraversalEquivalenceTest` — plus `GremlinToMatchSmokeTest`, the fourteen `gremlintest` classes, and the core Cucumber runner, all inside `./mvnw -pl core test` (A2). Unit level: `YTDBMatchPlanStepTest` (four reset tests over `NEW` / `OPEN` / `DRAINED`) and `MultiPlanMatchStepTest` (close tests and reset tests, no close-then-reset — confirmed by the iteration-1 technical gate).
- **Residual risk**: MEDIUM. Single-pass behaviour is well pinned; the re-arm path is not. No suite exercises a second pass over a real plan, so the copy's context, the swapped `plan` reference, and the re-run of a prefetched sub-plan are all first exercised by whatever test this track writes (see TS1, R3, R4).

#### X2 Exposure: `getSubSteps()` on `MatchPrefetchStep` / `MatchFirstStep` and the plan-introspection consumers
- **Track claim**: item 3 overrides `getSubSteps()` on both steps; the only observable effect is `EXPLAIN` documents gaining nested `subSteps`.
- **Critical path trace**:
  1. Entry: `ExecutionStep.toResult(session)` @ `core/.../query/ExecutionStep.java:35-47` — calls `getSubSteps()` twice (once bare at `:41`, once mapped at `:44`).
  2. Reached from `SelectExecutionPlan.toResult` and `ExplainResultSet`, i.e. YQL `EXPLAIN` result documents.
  3. Independently reached by `ExecutionStepInternal.basicSerialize` / `basicDeserialize` (`:197`, `:230`), unreachable for both targets because neither overrides `serialize` / `deserialize`.
  4. Test-side consumers that recurse the same accessor: `YTDBQueryMetricsStrategyTest.containsStepOfType` (`:1618-1628`, the helper the two failing scans key on), `BaseDBJUnit5Test.indexesUsed` (`tests` module, `:607-623`), `CommandExecutorSQLSelectTest.indexUsages` (`:1963-1980`).
- **Blast radius**: `EXPLAIN` payloads for MATCH plans grow by the nested chain; index-count helpers would see nested `FetchFromIndexStep`s if a MATCH plan ever reached them.
- **Existing safeguards**: five sibling MATCH steps already do this with the identical `List.copyOf(plan.getSteps())` shape, so the accessor contract is exercised (`MatchStepUnitTest.testHashJoinGetSubSteps`, `testFilterNotMatchPatternStepGetSubSteps`). No production consumer in `server/src/main` reads execution plans at all (grep for `getExecutionPlan` / `toResult` returned nothing). The monitoring surface reaches the plan only through `QueryMetricsListener.QueryDetails.getExecutionPlan()`, whose consumers are `YTDBQueryMetricsStep` and `YTDBTransaction` — neither serialises it.
- **Residual risk**: LOW. The change is additive on an accessor whose default is an empty list, and every recursive consumer is either a test helper or `EXPLAIN`.

#### X3 Exposure: item 4 turns on a CI surface that has been dark for 117 commits
- **Track claim**: item 4 closes the detection hole by undrafting PR #1038, adding a cheap always-on check, dispatching `maven-pipeline.yml`, or any combination.
- **Critical path trace**:
  1. `detect-changes` @ `maven-pipeline.yml:25-27` carries `if: github.event.pull_request.draft != true`; every downstream job hangs off it.
  2. `test-linux` @ `:157-200` runs `./mvnw clean package` on three matrix legs; the amd64 JDK 21 leg adds `-Dyoutrackdb.test.env=ci` and `-P docker-images,coverage` (`:173`).
  3. `test-windows` @ `:275-301` and `test-macos` @ `:329-366` run the same `clean package` without the ci env.
  4. `test-small-cache-linux` @ `:399-426` runs `-pl core -am verify -P small-cache-it`.
  5. `coverage-gate` @ `:485-555` runs `coverage-gate.py` against `origin/develop` over the whole branch diff; `test-count-gate` @ `:568-624` compares test counts against a git-notes baseline.
- **Blast radius**: every module (`server`, `embedded` with its own Cucumber suite, `tests` with the JUnit 5 `EmbeddedTestSuite`), two storage modes, three OSes, and two branch-wide gates, none of which has run since 2026-07-16. Track 8's retrospective records the coverage gate as never run and its thresholds unmeasured for that track's code.
- **Existing safeguards**: none — that is the finding item 4 exists to address.
- **Residual risk**: HIGH, and it is discovery risk rather than defect risk. The likely outcome is a large, unsorted failure and gate-violation set arriving inside a track sized for four scenarios.

#### X4 Exposure: `g.V(rid)` compiles an uncached plan where the native path ran no query
- **Track claim**: item 2 — `g.V(rid)` surfacing a plan is "plausibly an improvement; the develop-era contract was simply never updated".
- **Critical path trace**:
  1. `StartStepRecogniser.recognise` normalises the start step's ids @ `StartStepRecogniser.java:116`.
  2. Non-empty ids → `ctx.markRidBearing()` @ `:132` plus a `WHERE @rid IN [...]` alias filter.
  3. `GremlinToMatchTranslator` sets `cacheEligible = false` for a RID-bearing walk @ `:145` (documented at `RecognitionContext.java:177-178`: "RID-bearing shapes bypass the plan cache because their fingerprint would vary per id set").
  4. `GremlinToMatchStrategy.buildPlan` @ `:419` takes `buildPlanUncached` — no fingerprint, no `GremlinPlanCache.get`, planner runs.
  5. Execution is cheap: `promoteStaticRidsFromFilters` collapses a size-1 `IN` to a pinned RID and the `SELECT FROM #X:Y` fast path (`StartStepRecogniser.java:126-131`).
- **Blast radius**: every by-id lookup on a translated session pays a planner pass per call. The native path (`YTDBGraphImplAbstract.elements`) issued no query at all, which is what `byIdLookupSurfacesNullPlan`'s comment records.
- **Existing safeguards**: none measured. The on-vs-off JMH baseline is Track 9 (`track-9.md:55`), after this track, and its shape list (`:82`) does not name `g.V(rid)`.
- **Residual risk**: MEDIUM. Correctness is fine — the metrics assertion is the only thing failing. The cost is unmeasured on the most common lookup shape in a graph workload.

#### A1 Assumption: the branch's `core` failure set is the four `YTDBQueryMetricsStrategyTest` scenarios
- **Track claim**: `## Context and Orientation` — "The four failures at the branch tip"; `## Validation and Acceptance` — "`./mvnw -pl core test` passes on the branch".
- **Evidence search**: grep across `_workflow/**` for a recorded full-suite run (`Tests run:`, `BUILD FAILURE`, `mvnw -pl core test`); read of Track 8's `## Surprises & Discoveries` and `## Outcomes & Retrospective`; read of `core/pom.xml`'s surefire executions. Grep, not PSI.
- **Code evidence**: no artifact records a full-suite failure list. Track 8 `## Surprises & Discoveries` (2026-08-01) states the opposite for the track base: "The track base actually carries six problems, not four, and a different set — the matching count was a coincidence", and names neither extra problem. The track's own run recipe (`track-10.md:53-56`) is single-class.
- **Verdict**: UNVALIDATED
- **Detail**: the four-failure figure is well supported for `YTDBQueryMetricsStrategyTest` and unsupported for the module. Given A2, the module includes the core Cucumber runner.

#### A2 Assumption: `./mvnw -pl core test` runs only unit tests and the metrics scenarios
- **Track claim**: implicit in `## Validation and Acceptance` treating a green core run as the four scenarios plus units.
- **Evidence search**: read of `core/pom.xml:290-331`; `find` + read of `YTDBGraphFeatureTest`; count of `gremlintest` test classes.
- **Code evidence**: `default-test` (`:298`) sets `<excludedGroups>…SequentialTest</excludedGroups>` (`:307`) and `<exclude>**/gremlintest/**</exclude>` (`:309`). `sequential-tests` (`:323`) sets `<groups>…SequentialTest</groups>` (`:329`) with no exclusion. `YTDBGraphFeatureTest` carries `@Category(SequentialTest.class)` and `@RunWith(Cucumber.class)` (`:17-18`). Fourteen test classes live under `core/src/test/.../gremlintest/`.
- **Verdict**: VALIDATED (the assumption is false; the run is much wider)
- **Detail**: `track-9.md:36` names `YTDBGraphFeatureTest` in `core` as one of the two homes of the ~1900-scenario Cucumber suite, so Track 10's acceptance criterion overlaps Track 9's whole-feature gate.

#### A3 Assumption: the engine-surface amendment is correctly scoped rather than constraint erosion
- **Track claim**: `## Invariants & Constraints` third bullet and item 3's decision paragraph — the plan's "Engine surface is preserved" bullet gains a third exception naming the two introspection overrides as behaviour-neutral.
- **Evidence search**: read of `implementation-plan.md:40-44`; repo-wide grep for `getSubSteps` overrides; read of `ExecutionStep.java` and both target steps.
- **Code evidence**: the plan bullet freezes "the execution steps … except the two new string-predicate AST nodes in D-TEXT-OPS and the count short-circuit refactor" (`implementation-plan.md:40-44`) — both existing exceptions are behaviour-changing, and the proposed third is not. The amendment names two files and one accessor whose default is an empty list; it does not license a general "behaviour-neutral engine edits are fine" clause.
- **Verdict**: VALIDATED
- **Detail**: the five-sibling precedent (T4) answers a different question — whether the shape is conventional — rather than whether the branch now modifies engine steps. It does, and the amendment says so. Scoped to two named overrides, with T10's acceptance criterion owning the edit, this reads as an honest exception rather than erosion. No finding.

#### A4 Assumption: copy-on-re-arm can reuse `clone()`'s isolated-child-context recipe
- **Track claim**: item 1 — "copy-on-re-arm via `InternalExecutionPlan.copy`", with no statement of which context the copy runs against.
- **Evidence search**: full read of both `clone()` implementations; read of `BasicCommandContext.getVariables` / `setVariable`; read of `MatchPrefetchStep.internalStart`; grep for `-ea` in `core/pom.xml`.
- **Code evidence**: `YTDBMatchPlanStep.clone()` (`:113-117`) and `MultiPlanMatchStep.clone()` (`:176-205`) both build a fresh `BasicCommandContext`, call `setParentWithoutOverridingChild(templateContext)`, and copy against it. Both document the invariant: "the parent (template) context must stay free of per-run variables". `MultiPlanMatchStep` asserts it at `:180`, covering every system-variable slot plus `getVariables().isEmpty()`. `MatchPrefetchStep.internalStart` writes `ctx.setVariable(PREFETCHED_MATCH_ALIAS_PREFIX + alias, prefetched)` (`:100`) on the context `SelectExecutionPlan.start()` passes (`SelectExecutionPlan.java:85`). `core/pom.xml:36` enables `-ea`.
- **Verdict**: CONTRADICTED
- **Detail**: the recipe is valid only before the plan has run. At re-arm the template is exactly the context the previous pass wrote into, so the `MultiPlanMatchStep` guard fires and the single-plan copy parents onto dirty state. See R3.

#### A5 Assumption: RID-bearing shapes bypass the plan cache
- **Track claim**: none — needed to size item 2's cost.
- **Evidence search**: grep for `markRidBearing` / `cacheEligible` across `gremlin/translator/`; reads of the three sites.
- **Code evidence**: `StartStepRecogniser.java:132` marks the walk; `RecognitionContext.java:177-178` documents the reason; `GremlinToMatchTranslator.java:145` sets `cacheEligible = false`; `GremlinToMatchStrategy.java:419` routes to `buildPlanUncached`.
- **Verdict**: VALIDATED

#### A6 Assumption: no consumer of `getSubSteps()` recursion asserts against a MATCH plan
- **Track claim**: item 3 — "no test asserts on MATCH `subSteps`".
- **Evidence search**: repo-wide grep for `getSubSteps()` across `core`, `server`, `embedded`, `tests`; read of the two index-count helpers and a scan of their callers for MATCH query text.
- **Code evidence**: the recursive consumers are `ExecutionStep.toResult` (`:41-44`), `ExecutionStepInternal.basicSerialize` / `basicDeserialize` (`:197`, `:230`), `YTDBQueryMetricsStrategyTest.containsStepOfType` (`:1618-1628`), `BaseDBJUnit5Test.indexesUsed` (`tests`, `:607-623`) and `CommandExecutorSQLSelectTest.indexUsages` (`:1963-1980`). The last two assert exact index counts (`SQLSelectIndexReuseTest`: `assertEquals(0, …)` three times, `assertEquals(1, …)` fifteen times), and a grep for `MATCH ` across those files returned zero hits — every caller issues SQL `SELECT`. `MatchStepUnitTest`'s `getSubSteps` assertions target `FilterNotMatchPatternStep` and `HashJoinMatchStep`, not the two targets.
- **Verdict**: VALIDATED (grep-based negative; the two helpers and their call sites were read, which bounds the miss risk for them)

#### TS1 Testability: item 1's product-side re-arm
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: the existing unit harness cannot witness the property. `YTDBMatchPlanStepTest` mocks `InternalExecutionPlan`, so a mocked `copy(...)` returns whatever the test stubs, a mocked `start()` after `close()` returns a fresh stream, and neither the sticky close guard nor the context invariant exists. T2's `verify(copy)` / `never()` assertions close the restart question but not the context question (R3): a mock has no `CommandContext`, so `MultiPlanMatchStep`'s clone-isolation assert is unreachable from that layer.
- **Existing test infrastructure**: `YTDBMatchPlanStepTest` and `MultiPlanMatchStepTest` (mocked plans, four and four lifecycle tests); the four real-plan equivalence suites; `YTDBQueryMetricsStrategyTest`'s scenario harness with `setTranslatorEnabled` (`:710-720`), which drives real plans end to end.
- **Feasibility**: DIFFICULT
- **Detail**: achievable, but only if the coverage comes from both layers — the mocked layer for the no-restart property, and one real-plan scenario re-arm (a `toList()` → `admin.reset()` → `toList()` on a prefetched shape, asserting rows) for the context and prefetch-sub-plan behaviour. Coverage percentages alone would be met by the mocked tests while leaving the risky half unexercised.

#### TS2 Testability: item 3's introspection overrides
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: trivial. Two one-line accessors with an established test pattern in the same file as the precedents.
- **Existing test infrastructure**: `MatchStepUnitTest` (`testHashJoinGetSubSteps` @ `:3222-3231`, `testFilterNotMatchPatternStepGetSubSteps` @ `:1929-1953`) plus `MatchStatementExecutionHeavyTest` and `MatchStaticRidPromotionIntegrationTest`, which already build real prefetched plans; `GqlMatchStatementPlanPrettyPrintTest` pins the `+ PREFETCH` shape.
- **Feasibility**: ACHIEVABLE
- **Detail**: the gap is the acceptance criterion, not the difficulty — see R6.

#### TS3 Testability: item 4's detection-hole closure
- **Coverage target**: not applicable — a CI/workflow change carries no line coverage.
- **Difficulty assessment**: the only verification is running the thing, and the run is the discovery event X3 describes. A `workflow_dispatch` proves the pipeline executes on the branch but proves nothing about the permanent gate; a permanent gate cannot be proven until a later red run is caught by it.
- **Existing test infrastructure**: none. `.github/workflows/` has no self-test; the repo's workflow-machinery reviewers check hook and script correctness by reading.
- **Feasibility**: DIFFICULT
- **Detail**: the realistic acceptance shape is evidential rather than assertive — a linked pipeline run whose conclusion is visible on the PR, plus the mechanism diff. R5 asks for exactly that.
