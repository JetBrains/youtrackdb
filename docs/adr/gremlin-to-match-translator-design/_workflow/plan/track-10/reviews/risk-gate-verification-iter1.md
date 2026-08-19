<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: R7, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:96", anchor: "### R7 ", cert: "Verify R3", basis: "Copy-on-re-arm reaches MultiPlanMatchStep, which carries the only -ea clone-isolation assert, but its re-arm coverage is mocked-only — the sole real-plan reset() in core/src/test drives the single-plan boundary"}
verdicts:
  - {id: R1, verdict: STILL OPEN}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: STILL OPEN}
  - {id: R5, verdict: STILL OPEN}
  - {id: R6, verdict: VERIFIED}
overall: FAIL
flags: [CONTRACT_OK]
-->

# Track 10 — risk review gate verification, iteration 1

Every fix landed and every technical claim in it checks out against the source. Four of the six landed only their diagnosis half. R2, R3 and R6 are closed. R1, R4 and R5 each gained a `## Plan of Work` paragraph naming the hazard and no `## Validation and Acceptance` criterion holding it, so the specific failure mode each finding named is still reachable: R1's partition between in-scope and out-of-scope failures is undefined, so "green" can now be declared on an enumerated suite split by implementer judgement; R4's `resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults` still asserts the surviving-plan contract in its name, message and comment block, and stays green on `isNotNull()` while copy-on-re-arm falsifies it; R5's item 4 can still be discharged by a config edit nobody ran. One new finding: copy-on-re-arm pulls in `MultiPlanMatchStep`, which owns the only executable guard on the R3 hazard, and nothing re-arms it against a real plan.

The R1 fix also left two statements behind it. `## Purpose / Big Picture` still promises `./mvnw -pl core test` passes unconditionally, and `## Context and Orientation` still opens "The four failures at the branch tip" as settled fact — the claim certificate A1 marked UNVALIDATED and step 0 exists to test. All the remaining edits are one to three sentences; none reopens a design question.

**Reference-accuracy caveat.** PSI was not attempted — `steroid_execute_code` hits the known cold-kotlinc timeout in this repo, which both iteration-1 reviewers already paid for. Every symbol result below is grep plus a direct read of the cited region. Most verifications here are positive-existence checks that grep resolves reliably. Two rest on negatives: "no test re-arms a `MultiPlanMatchStep` against a real plan" (R7) and "no `g.V(rid)` hint exists in `track-9.md`" (R2 residual). The first was cross-checked by reading `MultiPlanMatchStepTest`'s two reset tests and every `admin.reset()` call site in `core/src/test`, which bounds the miss to a re-arm reached through some path other than `reset()`.

## Findings

### R7 [should-fix]
**Certificate**: Verify R3

**Location**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:96` (`## Validation and Acceptance`); `MultiPlanMatchStep.java:180`; `MultiPlanMatchStepTest.java:372-394`; `YTDBQueryMetricsStrategyTest.java:602-633`, `:1128-1137`

**Issue**: The R3 fix directs Phase B to derive its own context for the copy path. The only executable check on that derivation lives on the multi-plan step, and no test can reach it.

`MultiPlanMatchStep.clone()` asserts that the template context carries no per-run state (`:180`), `core/pom.xml:36` puts `-ea` in the surefire `argLine`, and the track's `## Interfaces and Dependencies` pulls `MultiPlanMatchStep` into scope for copy-on-re-arm (T2). So an implementer who reuses the clone recipe at re-arm gets an `AssertionError` — but only if some test drives a real union plan through a second pass.

None does. `MultiPlanMatchStepTest`'s two re-arm tests (`reset_thenProcessNextStart_rewindsAndReRunsEveryChild` `:372`, `reset_beforeFirstIteration_doesNotRewindAnyChildOnFirstOpen` `:402`) run against Mockito plans and Mockito contexts — `verify(c1.plan, times(2)).start()` — so there is no `CommandContext` to be dirty and the assert is unreachable from that layer, exactly as TS1 argued. The one real-plan `reset()` in `core/src/test` is `YTDBQueryMetricsStrategyTest` (three call sites: `:423`, `:618`, `:654`), and all three drive `g().V().hasLabel("person")` through the single-plan `YTDBMatchPlanStep`. That path covers the R3 hazard for the single-plan boundary — MODERN's six vertices sit far under `MatchExecutionPlanner.THRESHOLD`, so it is a real prefetched plan under `-ea`, and acceptance line 91 keeps it green. The union scenarios in the same class (`:1128-1137`) never call `reset()`.

The asymmetry is what makes this worth a criterion rather than a note: the single-plan path has no assert and real coverage, the multi-plan path has the assert and no real coverage.

**Proposed fix**: Add one `## Validation and Acceptance` line — if step 1 lands on the product side, a real-plan re-arm of a **union** traversal (`toList()` → `admin.reset()` → `toList()`, asserting rows) runs under assertions, so `MultiPlanMatchStep`'s clone-isolation guard is actually exercised. `YTDBQueryMetricsStrategyTest` already hosts both halves — the union shapes and the reset harness — so the home costs nothing to find.

## Verification certificates

#### Verify R1: the acceptance criterion accepts against an unenumerated suite
- **Original issue**: the track scoped itself to four scenarios and accepted on `./mvnw -pl core test`, which the `sequential-tests` execution widens to the core Cucumber runner plus thirteen other `gremlintest` classes; no artifact enumerated the real failure set, and the one enumeration on record was wrong.
- **Fix applied**: new `## Plan of Work` item 0 (`:65`) requiring a full-suite run recorded as an artifact before any fix lands, with an ESCALATE trigger; two `## Validation and Acceptance` lines rewritten (`:89`, `:90`).
- **Re-check**:
  - Track-file location: `track-10.md:65`, `:89-90`.
  - Current state: the enumeration half is solid. Item 0 restates the `sequential-tests`-has-no-`**/gremlintest/**`-exclusion mechanism, and acceptance line 89 makes the artifact a precondition. Line 90 closes the headline hole in as many words: "Green is never declared on an unenumerated suite."
  - The exit condition did not land. R1 asked for a three-way triage rule (metrics-class failure in scope; Cucumber or `YTDBProcessTest` routed to Track 9's bucket; anything else escalates). What the track carries instead is two undefined terms pointing in different directions. Item 0 says an ESCALATE fires when the set is "materially larger than the four known scenarios"; line 90 says out-of-scope failures are "recorded with its disposition (deferred, escalated, or split into a follow-up track)" and the track still passes. An implementer holding four metrics failures plus twelve Cucumber failures can read either, and nothing in the file defines "this track's scope" for a *test* — `## Interfaces and Dependencies` names files and classes, and `YTDBGraphFeatureTest` appears in neither list. Choosing among deferred, escalated and split is left to the judgement R1 asked to have decided in advance. The escape hatch is the hole one layer down: green cannot be declared on an unenumerated suite, but it can be declared on an enumerated suite partitioned to taste.
  - Item 4's R5 paragraph does supply a definition — "this track's responsibility (the `core` unit failures it exists to fix)" — but it is not cross-referenced from item 0 and it arrives at step 4, after steps 1-3 have been sized against step 0's list.
  - Criteria met: enumeration-before-fix, yes. Determinate pass condition, no.
- **Regression check**: checked numbering, cross-references and the surrounding prose. Inserting the new item as **0** rather than renumbering 1-4 is correct — every "item 3" / "step 1" reference in `## Context and Orientation` (`:48`), item 2 (`:74`), `## Validation and Acceptance` and `## Interfaces and Dependencies` (`:107`) still resolves, which matters on a track whose iteration-2 gate already caught one mis-numbered step reference. Two statements are now stale against the fix. `## Purpose / Big Picture` (`:5`) promises "After this track, `./mvnw -pl core test` passes on this branch" with no conditional, which line 90 explicitly qualifies. `## Context and Orientation` (`:42`) still presents "The four failures at the branch tip" as established, which is the A1 claim step 0 exists to test, and it is the section a Phase B implementer reads before reaching item 0. `## Idempotence and Recovery` (`:101`) describes every step as "a self-contained test-or-product fix", which step 0 is not; cosmetic.
- **Verdict**: STILL OPEN — the enumeration requirement closes the headline hole; the disposition rule that makes the exit condition determinate is missing, and the Purpose and Orientation text now contradicts the rewritten acceptance.

#### Verify R2: `g.V(rid)` is a performance question, not a test contract
- **Original issue**: item 2 framed the failing assertion as a contract question; underneath it, RID-bearing walks bypass the plan cache, so every by-id lookup compiles a throwaway MATCH plan where the native path ran no query — and one of item 2's two options deletes the only signal of that.
- **Fix applied**: a paragraph in item 2 (`track-10.md:74`) naming `cacheEligible=false`, the uncached compile, the signal-deletion hazard, and an escalation route.
- **Re-check**:
  - Track-file location: `track-10.md:74`.
  - Codebase re-check of every mechanism the paragraph asserts: `StartStepRecogniser.recognise` calls `ctx.markRidBearing()` for a non-empty id list at `:132`, inside the block whose comment documents the `WHERE @rid IN [...]` filter and the `promoteStaticRidsFromFilters` collapse; `GremlinToMatchTranslator.java:145` sets `cacheEligible = false`, with the parameter Javadoc at `:87` reading "`false` when the walk is RID-bearing and must bypass the plan cache"; `GremlinToMatchStrategy.java:419-420` routes `!translation.cacheEligible()` straight to `buildPlanUncached`. Every link in the chain holds.
  - Current state: the paragraph states the cost, marks the suppress option as the one that deletes the signal, and routes an unacceptable cost to an escalation rather than a test edit. Item 2 can no longer be discharged as an assertion update.
  - Criteria met: the framing error R2 named is corrected at the place Phase B reads.
- **Regression check**: R2's third proposed element did not land — grep across `track-9.md` for `g.V(rid)` / `g.V(ids)` returns nothing, and item 7's JMH shape list still names only "verified-recognised shapes … asserting boundary-step installation". So the perf question has no measurement lever inside the plan. The escalation route the paragraph adds is a valid alternative discharge (raise it rather than size it), which is why this is a residual rather than a reopening. No new issue introduced.
- **Verdict**: VERIFIED

#### Verify R3: `clone()`'s isolation recipe is unusable at re-arm
- **Original issue**: item 1 settled on copy-on-re-arm without saying which `CommandContext` the copy runs against, and the obvious in-file precedent — `clone()`'s isolated child context parented to the template — is invalid at re-arm because a completed pass has written into that template.
- **Fix applied**: first half of the "Two hazards specific to the copy shape (R3, R4)" paragraph (`track-10.md:69`).
- **Re-check**:
  - Track-file location: `track-10.md:69`.
  - Codebase re-check: `MultiPlanMatchStep.clone()` builds `new BasicCommandContext()`, calls `setParentWithoutOverridingChild(templateContext)` and copies against it, guarded by `assert (templateVariables == null || templateVariables.isEmpty()) && seededSystemVariable(templateContext) < 0` at `:180`. The comment above it states the propagation mechanism the isolation depends on and calls out that it covers every system-variable slot. `core/pom.xml:36` opens the surefire `argLine` with `-ea`, so the guard is live in every core run. The track's one-sentence compression of this — "a completed pass leaves exactly the per-run context state `MultiPlanMatchStep.clone()`'s `-ea` assert forbids" — is accurate.
  - Current state: item 1 now names the hazard and requires the copy path to derive its own context. Phase B owes a Decision Record for item 1 regardless ("the decision needs a Decision Record either way"), so the question is now on the record the DR must answer.
  - Criteria met: the unstated-context gap is closed at plan level; R3's own proposed fix put the explicit context choice in the Phase B DR, which is where this lands it.
- **Regression check**: the acceptance half of R3's proposed fix did not land, and checking what the existing criteria already cover narrowed rather than dismissed the gap. Acceptance line 91 forces all 20 metrics scenarios green, and `resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults` drives a real prefetched plan through `reset()` and a second `toList()` under `-ea`, so the single-plan path is covered. The multi-plan path, which carries the only assert, is not — raised as R7 rather than held against this fix, since it is a different step, a different test class and a criterion R3 did not itself name.
- **Verdict**: VERIFIED

#### Verify R4: the two re-arm paths diverge and the metrics capture reads the difference
- **Original issue**: three components. Copy-on-re-arm leaves `DRAINED` rewinding in place and `CLOSED` installing a copy behind one `reset()`, so `getPlan()` returns a different object per path and the monitoring layer sees the difference. The scenario test item 1 exists to fix asserts the surviving-plan contract in its name, its `as(...)` message and the comment block above the pair, and stays green under copy-on-re-arm only because the assertion is `isNotNull()`. And the in-place path restarts a prefetch sub-plan whose sticky close guard `reset()` cannot clear.
- **Fix applied**: second half of the paragraph at `track-10.md:69`.
- **Re-check**:
  - Track-file location: `track-10.md:69`.
  - Codebase re-check: `AbstractMatchPlanStep.reset()` (`:507-510`) re-arms from `OPEN`/`DRAINED` only; `openArming()` calls `rewindPlan(ctx)` when `state == State.REARMED` (`:427-428`). `YTDBQueryMetricsStep.capturedExecutionPlan()` reads `matchPlan.get().getPlan()` at `:95`. The divergence claim holds.
  - Current state: component one is fixed. The paragraph names both paths, states that `getPlan()` returns a different object depending on which ran, and instructs Phase B to pin which object the capture sees.
  - Components two and three are untouched — the paragraph never mentions the test or the prefetch sub-plan. Reading `YTDBQueryMetricsStrategyTest.java:596-633` confirms R4's characterisation exactly: the comment above the pair states "the MATCH boundary rewinds and keeps its compiled plan, while the half-measure source drops it", the method name is `resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults`, the post-`reset()` message reads "the MATCH boundary keeps its plan across reset()", and all three plan assertions are `isNotNull()`. Under copy-on-re-arm from `CLOSED` — the state `toList()` leaves the step in, which is the whole reason this scenario fails today — the boundary does not keep its plan, and every one of those three assertions still passes against the copy. Acceptance line 91 ("all 20 scenarios pass") is satisfied by a test whose name and comment then document the opposite of the shipped behaviour.
  - Criteria met: partially. The silent-green failure mode R4 named is the one left open, and nothing in the acceptance set forces the correction — unlike T8's Javadoc sweep at `:97`, which got its own criterion for the same keep-comments-in-sync reason.
- **Regression check**: the added text does not conflict with item 1's existing T2 paragraph or with `## Interfaces and Dependencies`. Minor imprecision worth noting rather than fixing: `capturedExecutionPlan()` reads `getPlan()` on the single-plan boundary but `getPlans().getFirst()` on `MultiPlanMatchStep` (`YTDBQueryMetricsStep.java:97-104`), so "reads `getPlan()`" understates the surface by one accessor. The instruction to pin which object the capture sees covers both.
- **Verdict**: STILL OPEN — the `getPlan()` divergence is pinned; the stale test contract and the prefetch sub-plan restart are not. One acceptance line conditioned on step 1 landing product-side, requiring the test's name, message and comment block to be updated in the same commit, closes it.

#### Verify R5: closing the detection hole fires every deferred gate at once
- **Original issue**: item 4 weighed three mechanisms, said nothing about what they would find, treated them as equivalent in blast radius, and had no acceptance criterion.
- **Fix applied**: a paragraph in item 4 (`track-10.md:80`) naming the blast radius and requiring a triage rule before the switch flips.
- **Re-check**:
  - Track-file location: `track-10.md:80`.
  - Codebase re-check of the blast-radius claims against `.github/workflows/maven-pipeline.yml`: `detect-changes` carries `if: github.event.pull_request.draft != true` (`:27`) and every downstream job hangs off it; `test-linux` (`:157`) runs three matrix legs with the amd64 JDK 21 leg adding `-Dyoutrackdb.test.env=ci -P docker-images,coverage` (`:173`); `test-windows` (`:275`), `test-macos` (`:329`), `test-small-cache-linux` (`:399`), `coverage-gate` (`:485`) and `test-count-gate` (`:568`) all sit behind the same gate. "All modules, three OS legs, the disk-storage env, and the coverage gate over eight tracks of accumulated diff" is accurate.
  - Current state: the what-will-it-find half landed. The triage rule is now a stated precondition, and it supplies the definition of this track's responsibility that item 0 lacks.
  - Two halves did not. Item 4 still lists undrafting, a cheap always-on check and `workflow_dispatch` as co-equal and closes with "Any combination is admissible" — R5's point was that `workflow_dispatch` has the same discovery scope without putting the PR's merge state in play, which makes it the verification lever and undrafting the permanent mechanism to land afterwards. And `## Validation and Acceptance` still carries nothing for item 4, so the step's own last sentence — "Without that rule the step has no exit condition" — describes a problem the section does not solve. R5's named failure mode, "closed the hole" satisfied by a config edit nobody exercised, is reachable as written.
  - Criteria met: partially.
- **Regression check**: the paragraph's triage rule and item 0's ESCALATE trigger now describe overlapping obligations at two different points in the track without cross-referencing each other; a single rule stated once in item 0 and referenced from item 4 would serve both. Recorded here rather than as a separate finding because the R1 fix owns the same text. Nothing else in the file conflicts.
- **Verdict**: STILL OPEN — one acceptance line stating what item 4 must demonstrate (a pipeline run whose conclusion is visible on the PR) plus a sentence ranking `workflow_dispatch` as the lever closes it.

#### Verify R6: the introspection override's one observable effect has no criterion
- **Original issue**: item 3 identified `EXPLAIN` documents gaining nested `subSteps` as its only observable effect, and none of the seven acceptance criteria pinned it, while two repo-wide index-count helpers recurse the same accessor.
- **Fix applied**: new `## Validation and Acceptance` line at `track-10.md:92`.
- **Re-check**:
  - Track-file location: `track-10.md:92`.
  - Current state: "If step 3 lands on the product side, a test pins the one observable effect — `EXPLAIN` result documents gaining nested `subSteps` — so the change is not left to the two SELECT-only index-count helpers that recurse the same accessor (R6)." The criterion is conditioned on the right step (step 3 is the introspection question), names the observable, and carries the reason.
  - Criteria met: the missing criterion exists and is owned.
- **Regression check**: TS2 rated the test ACHIEVABLE against an established in-file pattern, so the criterion adds no risk to a track already sized at ~7-10 files. Two of R6's three proposed elements did not land — unit tests for `MatchPrefetchStep.getSubSteps()` / `MatchFirstStep.getSubSteps()` in `MatchStepUnitTest` beside the five precedents, and an item-3 Decision Record note that `BaseDBJUnit5Test.indexesUsed` derives index counts from the same recursion. The first is subsumed in practice: a test pinning the `EXPLAIN` document for a prefetched MATCH plan exercises both accessors through `ExecutionStep.toResult`. The second leaves the future-MATCH-caller hazard undocumented, which is what R6's suggestion severity already priced.
- **Verdict**: VERIFIED

## Summary

**FAIL.** Three verified (R2, R3, R6), three still open (R1, R4, R5), one new finding (R7). Every open item is a missing `## Validation and Acceptance` line or a disposition rule, not a design question, and the R1 items include two now-stale statements in `## Purpose / Big Picture` and `## Context and Orientation`.
