<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 4, suggestion: 1}
index:
  - {id: CR1, sev: should-fix, loc: "implementation-plan.md:611-627", anchor: "### CR1 ", cert: C22, basis: "Implementation state still reports Track 8 Phase C pending and omits Track 10 entirely; contradicts the [x] checkbox and the ledger"}
  - {id: CR2, sev: should-fix, loc: "plan/track-9.md:81", anchor: "### CR2 ", cert: C21, basis: "Track 8 DR-U4/DR-U1 and the shipped UnionStepRecogniser javadoc assign the post-union list-shaping relaxation to Track 9, which marks union out of scope"}
  - {id: CR3, sev: should-fix, loc: "plan/track-10.md:90", anchor: "### CR3 ", cert: C4, basis: "ExecutionStep.containsStepOfType does not exist; it is a private static helper in the test class"}
  - {id: CR4, sev: should-fix, loc: "implementation-plan.md:352-360", anchor: "### CR4 ", cert: C23, basis: "the query-metrics to boundary-step integration Track 10 exists to repair is in neither Integration Points nor the Component Map"}
  - {id: CR5, sev: suggestion,  loc: "plan/track-9.md:5,82", anchor: "### CR5 ", cert: C25, basis: "Track 9 never acknowledges Track 10 running immediately before it and still counts six prior tracks"}
evidence_base: {section: "## Evidence base", certs: 27, matches: 21}
cert_index:
  - {id: C1,  verdict: MATCHES,    anchor: "#### C1 "}
  - {id: C2,  verdict: MATCHES,    anchor: "#### C2 "}
  - {id: C3,  verdict: PARTIAL,    anchor: "#### C3 "}
  - {id: C4,  verdict: NOT FOUND,  anchor: "#### C4 "}
  - {id: C5,  verdict: MATCHES,    anchor: "#### C5 "}
  - {id: C6,  verdict: MATCHES,    anchor: "#### C6 "}
  - {id: C7,  verdict: MATCHES,    anchor: "#### C7 "}
  - {id: C8,  verdict: MATCHES,    anchor: "#### C8 "}
  - {id: C9,  verdict: MATCHES,    anchor: "#### C9 "}
  - {id: C10, verdict: MATCHES,    anchor: "#### C10 "}
  - {id: C11, verdict: MATCHES,    anchor: "#### C11 "}
  - {id: C12, verdict: MATCHES,    anchor: "#### C12 "}
  - {id: C13, verdict: MATCHES,    anchor: "#### C13 "}
  - {id: C14, verdict: MATCHES,    anchor: "#### C14 "}
  - {id: C15, verdict: MATCHES,    anchor: "#### C15 "}
  - {id: C16, verdict: MATCHES,    anchor: "#### C16 "}
  - {id: C17, verdict: MATCHES,    anchor: "#### C17 "}
  - {id: C18, verdict: MATCHES,    anchor: "#### C18 "}
  - {id: C19, verdict: MATCHES,    anchor: "#### C19 "}
  - {id: C20, verdict: MATCHES,    anchor: "#### C20 "}
  - {id: C21, verdict: MISMATCHES, anchor: "#### C21 "}
  - {id: C22, verdict: VIOLATED,   anchor: "#### C22 "}
  - {id: C23, verdict: NOT FOUND,  anchor: "#### C23 "}
  - {id: C24, verdict: MATCHES,    anchor: "#### C24 "}
  - {id: C25, verdict: MISMATCHES, anchor: "#### C25 "}
  - {id: C26, verdict: NOT FOUND,  anchor: "#### C26 "}
  - {id: C27, verdict: MISMATCHES, anchor: "#### C27 "}
flags: [CONTRACT_OK]
-->

# Consistency review — iteration 1 (2026-08-01 re-validation, 10-track plan after the Track 8 inline replan)

Five findings, none blocking. Four surfaces changed since the 2026-07-27
re-validation: the Track 10 checklist entry, `plan/track-10.md`, the Track 8
entry's completion episode, and the plan's `## Implementation state`. Every
symbol Tracks 10 and 9 name resolves in the codebase except one
(`ExecutionStep.containsStepOfType`, CR3). The substantive findings are
bookkeeping the replan left behind (CR1, CR4, CR5) and one obligation Track 8
hands to Track 9 that Track 9 does not carry (CR2).

**Reference-accuracy caveat — applies to every certificate below.**
mcp-steroid was reachable and the youtrackdb project was open at the working
tree, but PSI (`steroid_execute_code`) timed out on the first query, matching
the behaviour recorded in earlier sessions on this repo (cold kotlinc compile
exceeds the ~60 s MCP HTTP limit). All symbol facts rest on grep plus direct
source reads plus `unzip -l` on the resolved TinkerPop fork jar. Negative
results (C4, C10, C23, C26) are therefore grep-negatives: a symbol reachable
only through polymorphic dispatch, a Javadoc `{@link}`, or a string literal
could have been missed. C4 is the only negative that drives a finding, and it
was confirmed by reading the declaring file end-to-end rather than by search
alone.

**Intent-axis pre-screen.** Tracks 10 and 9 are both `[ ]`. Their
`## Purpose / Big Picture`, `## Plan of Work`, and `## Interfaces and
Dependencies` prose is target-state and was not scored against current code —
`BoundaryOutputType.LIST` absent from the enum, no terminator recognisers
registered, and `MatchFirstStep` not overriding `getSubSteps()` are all the
expected pre-implementation state. `## Context and Orientation` in both files
was scored as current-state and matches the code throughout. The
`**Signatures:**` lines were scored as current-state because they name
pre-existing symbols the track will touch rather than symbols it will create;
that is what surfaces CR3.

**Frozen-design lag — recorded, not a finding.** `design.md` was frozen at
Phase 1 and still shows `MultiPlanMatchStep extends YTDBMatchPlanStep`, heads
its list-shaping section "(Track 6)", and describes `fold` as a
`processNextStart`-internal `ArrayList` drain rather than a `ListShapingOp`
stage. The 2026-07-15 and 2026-07-27 re-validations already deferred this
class of divergence to the Phase 4 `design-final.md`; per the consistency
prompt a revised plan diverging from a frozen design is the expected state.
C26 and C27 record the specific lags for the Phase 4 reconciler.

## Findings

### CR1 [should-fix]
**Certificate**: C22
**Location**: `implementation-plan.md` `## Implementation state` (lines 611-627) — narrative paragraph, status table row 8, and the decision-conformance paragraph
**Issue**: The section still describes the pre-replan world. It says "Tracks 1-7 are executed and complete; Track 8 Phase B is complete (Phase C pending); Track 9 is not started"; the table's row 8 reads `Phase B done` with "Phase C pending"; the decision-conformance paragraph says "Track 8 Phase C still open". Track 10 appears nowhere in the section — not in the prose, not as a table row.
**Evidence**: The Checklist marks Track 8 `[x]` (line 546) with a completion episode describing three Phase C iterations over 42 findings, and `_workflow/phase-ledger.md` line 29 records `phase=C track=8 substate=track-complete` at 2026-08-01T05:23Z. Commit `3f3f1b7372` is "Complete Track 8: union via MultiPlanMatchStep". Three signals say complete; the Implementation-state section says Phase C pending. Separately, the plan's only mention of Track 10 anywhere is the Checklist entry at line 573 (`grep -n "Track 10" implementation-plan.md` returns exactly one hit).
**Proposed fix**: Update the narrative to "Tracks 1-8 are executed and complete; Track 10 and Track 9 are not started, in that execution order", drop "(Phase C pending)" from row 8 and set it to `done`, add a Track 10 row above the Track 9 row (`| 10 | not started | query-metrics regression remediation — restore a green core unit-test run |`), and change the decision-conformance sentence to "D8 (union via `MultiPlanMatchStep`) is implemented in code across Tracks 7-8 and complete."
**Classification**: mechanical
**Justification**: Current-state claim about which tracks are done; one unambiguous correct rendering fixed by the checklist and the ledger; the plan's goals, scope, and architecture are unchanged.

### CR2 [should-fix]
**Certificate**: C21
**Location**: `plan/track-9.md` `## Interfaces and Dependencies` (line 81, "Out of scope") and `## Plan of Work` (lines 49-54); against `plan/track-8.md` DR-U4 (line 55), DR-U1 (line 52), and `## Surprises & Discoveries` (line 39)
**Issue**: Track 8 assigns Track 9 the job of relaxing the post-union suffix gate for list-shaping terminators, and Track 9 neither scopes the work nor acknowledges it. Track 9's `**Out of scope:**` line reads "`union` / `MultiPlanMatchStep` (Track 8)", which reads as a positive exclusion of exactly that work.
**Evidence**: `track-8.md` line 39 states the assignment flatly — "The recogniser now declines a non-exhausted cursor after the union; Track 9 relaxes this for the list-shaping terminators only (DR-U4)". DR-U4 itself (line 55) says "List-shaping terminators (`fold`/…) remain Track 9". DR-U1 (line 52) justifies choosing `MultipleExecutionStream` over the per-child reopen precisely so that "Track 9's `union().fold()`" folds the concatenation once rather than per child — the union machinery was shaped around this composition working. The shipped code agrees the work is pending: `UnionStepRecogniser`'s class Javadoc (lines 28-29) says "the list-shaping terminators (`fold` and friends) are not translated yet", and its post-union allow-list is `count` / `limit` / `dedup` only (line 126). Track 9's file mentions union twice, both times to exclude it, has no `union(...).fold()` line in `## Validation and Acceptance`, and carries no Decision Log entry on the subject. Track 8 line 185 hedges the same obligation as "Track 9 **may** relax the 'union is last step' rule", so the two statements inside Track 8 do not agree with each other either.
**Proposed fix**: The user picks one of two shapes. Either Track 9 absorbs the relaxation — add a Plan of Work item extending the post-union allow-list to the four terminators, a `union(...).fold()` / `union(...).unfold()` multiset-parity acceptance line, and narrow the out-of-scope entry to "union recogniser and `MultiPlanMatchStep` internals (Track 8); the post-union suffix allow-list is in scope" — or DR-U4 is amended to defer the relaxation to Phase 2, Track 9's out-of-scope line stands, and a note records that `union(...).fold()` declines in Phase 1.
**Classification**: design-decision
**Justification**: Contradiction between two tracks (Track 8 assigns the work to Track 9; Track 9 excludes it), and Track 8's own wording splits between "relaxes" and "may relax" — which reading holds is a scope call the user owns, and Track 9 is the last Phase 1 track, so nothing downstream recovers the gap.

### CR3 [should-fix]
**Certificate**: C4 (supported by C3)
**Location**: `plan/track-10.md` `## Interfaces and Dependencies`, `**Signatures:**` line (line 90); code at `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:1618`
**Issue**: The signatures list names `ExecutionStep.containsStepOfType`. No such member exists on `ExecutionStep` or anywhere in the production tree. `containsStepOfType` is a `private static` helper declared inside the test class itself.
**Evidence**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/ExecutionStep.java` declares `getName`, `getType`, `getDescription`, `getSubSteps`, `getCost`, and `toResult` — nothing else. Every occurrence of `containsStepOfType` under `core/src` is in `YTDBQueryMetricsStrategyTest.java` (call sites at lines 292, 295, 329; declaration at 1618). The helper recurses through `step.getSubSteps()` only (line 1623). Two consequences for the decomposer: the introspection change lands in a test-local helper plus a production override, not on the `ExecutionStep` interface; and of the two overrides step 3 offers as alternatives, only `getSubSteps()` would make the existing helper find a nested fetch step — `getSubExecutionPlans()` returns `List<ExecutionPlan>`, which the helper never walks, so overriding it alone leaves both scan tests red. `getSubExecutionPlans()` is also declared on `ExecutionStepInternal` (line 150), not on `ExecutionStep`, so the surrounding sentence "invisible to `ExecutionStep` introspection" is imprecise for that half of the pair.
**Proposed fix**: In the `**Signatures:**` line, replace `ExecutionStep.containsStepOfType` with `YTDBQueryMetricsStrategyTest.containsStepOfType` (private test helper, recurses via `getSubSteps()` only) and add `ExecutionStepInternal.getSubSteps()` / `getSubExecutionPlans()` (the default implementations `MatchFirstStep` inherits) so the two methods are attributed to their declaring type.
**Classification**: mechanical
**Justification**: Current-state claim about an existing symbol with a single unambiguous correct rendering (attribute the helper to its declaring class); the fix updates only the description and leaves Track 10's goals and scope untouched.

### CR4 [should-fix]
**Certificate**: C23
**Location**: `implementation-plan.md` `### Integration Points` (lines 352-360) and the `#### Component Map` diagram (lines 64-94)
**Issue**: The plan's Architecture Notes record no integration point between the translator's boundary step and the query-metrics capture path, and the Component Map has no node for it. That integration is the one Track 10 now exists to repair.
**Evidence**: `YTDBQueryMetricsStep.capturedExecutionPlan()` (`core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java:91-109`) reads the compiled plan by scanning the traversal for `YTDBMatchPlanStep` first, then `MultiPlanMatchStep` (taking the first child plan), then falling back to `YTDBGraphStep.getLastExecutionPlan()`. The translator's boundary step is a live consumer contract for the monitoring layer, and removing `YTDBGraphStep` from translated traversals in `6e657ce2b1` is what broke it. The plan's four Integration Points cover the strategy chain, the additive planner constructor, the count short-circuit, and the plan-cache invalidation hook; none mentions metrics. `grep -ni metric implementation-plan.md` returns three hits, all inside the Track 10 checklist entry.
**Proposed fix**: Add a fifth Integration Points bullet — "`YTDBQueryMetricsStep.capturedExecutionPlan()` reads the compiled plan off the boundary step (`YTDBMatchPlanStep`, then `MultiPlanMatchStep`'s first child plan), falling back to `YTDBGraphStep.getLastExecutionPlan()` for untranslated traversals; a change to what translates changes what the monitoring layer sees (Track 10)." Optionally add a `Metrics["YTDBQueryMetricsStep\n(plan capture)"]` node to the Component Map with an edge from `Boundary`.
**Classification**: mechanical
**Justification**: Current-state claim about an existing call path verified by reading the method; one unambiguous correct rendering; adding the bullet records an existing integration without changing plan goals or scope.

### CR5 [suggestion]
**Certificate**: C25
**Location**: `plan/track-9.md` `## Purpose / Big Picture` (line 5) and `## Interfaces and Dependencies`, `**Inter-track dependencies:**` (line 82)
**Issue**: The replan updated Track 10 and the plan checklist but left Track 9's cross-references pointing at the pre-replan track set. Track 9 lists its inter-track dependencies as Tracks 7 and 8 and never mentions Track 10, even though Track 10's whole ordering rationale is that Track 9's Cucumber-green and JMH-baseline gates need a green starting point.
**Evidence**: `grep -n "Track 10" plan/track-9.md` returns nothing. `plan/track-10.md` line 88 states the dependency from its side: "**Runs before Track 9** — Track 9's Cucumber-green and JMH-baseline goals both assume a green starting point." Track 9 line 5 also still says it "validates the whole feature across all six prior tracks", a count written before the insertion.
**Proposed fix**: Extend Track 9's `**Inter-track dependencies:**` line with "and on Track 10, which restores a green `core` unit-test run — Track 9's Cucumber and JMH gates read a red baseline as noise", and reword line 5's "all six prior tracks" to "all prior tracks" so it stops carrying a count the replan moves.
**Classification**: mechanical
**Justification**: Current-state claim about the plan's own track ordering, fixed by the ordering the checklist and Track 10 already record; one unambiguous rendering; Track 9's scope and goals are unchanged.

## Evidence base

**Plan ↔ Code — Track 10**

#### C1 Ref: `YTDBQueryMetricsStep.capturedExecutionPlan()`
- **Document claim**: `plan/track-10.md` `## Context and Orientation` — the method "originally read the execution plan off `YTDBGraphStep`"; `3d476357cc` "taught the capture to read the MATCH boundary".
- **Search performed**: grep for `capturedExecutionPlan|YTDBGraphStep|MatchPlan` in the declaring file, then Read of lines 78-117. PSI unavailable (see caveat).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java:91-109`.
- **Actual signature/role**: `@Nullable private ExecutionPlan capturedExecutionPlan()` — scans for `YTDBMatchPlanStep` via `TraversalHelper.getFirstStepOfAssignableClass` and returns `getPlan()`; otherwise scans for `MultiPlanMatchStep` and returns `getPlans().getFirst()` when non-empty; otherwise `YTDBGraphStep::getLastExecutionPlan`.
- **Verdict**: MATCHES
- **Detail**: The Javadoc at lines 83-90 documents exactly the ordering the track describes, including "For a multi-plan union the first child's plan is surfaced".

#### C2 Ref: `AbstractMatchPlanStep` lifecycle — `reset()` / `processNextStart()` / `CLOSED`
- **Document claim**: `plan/track-10.md` failure 1 — "`reset()` re-arms only from `OPEN`/`DRAINED` and `CLOSED` is terminal by design, so `processNextStart()` throws `FastNoSuchElementException`".
- **Search performed**: grep for `enum|OPEN|DRAINED|CLOSED|void reset|processNextStart|FastNoSuchElement` in the declaring file, then Read of lines 140-180.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:144-168` (State enum), `:237-240` (`processNextStart`), `:507-509` (`reset`).
- **Actual signature/role**: `private enum State { NEW, OPEN, DRAINED, REARMED, CLOSED }`; `processNextStart()` opens with `if (state == State.DRAINED || state == State.CLOSED) { … throw FastNoSuchElementException.instance(); }`; `reset()` guards with `if (state == State.OPEN || state == State.DRAINED)`. Line 165 documents CLOSED as "Terminal".
- **Verdict**: MATCHES
- **Detail**: The enum carries two states the track does not name (`NEW`, `REARMED`), neither reachable from `CLOSED`, so the claim holds as written.

#### C3 Ref: `MatchFirstStep.getSubSteps()` / `getSubExecutionPlans()`
- **Document claim**: `plan/track-10.md` failures 3-4 — "`MatchFirstStep` overrides neither `getSubSteps()` nor `getSubExecutionPlans()`, so a nested fetch plan is invisible to `ExecutionStep` introspection".
- **Search performed**: full Read of `MatchFirstStep.java`; grep for `getSubExecutionPlans` across `core/src/main/java` and `server/src/main/java`; Read of `ExecutionStepInternal.java:140-160`; full Read of `ExecutionStep.java`.
- **Code location**: `core/.../sql/executor/match/MatchFirstStep.java:39-161`; defaults at `core/.../sql/executor/ExecutionStepInternal.java:145` and `:150`; interface at `core/.../core/query/ExecutionStep.java`.
- **Actual signature/role**: `MatchFirstStep extends AbstractExecutionStep` overrides `reset`, `internalStart`, `canBeCached`, `prettyPrint`, and `copy` — neither introspection method. `AbstractExecutionStep implements ExecutionStepInternal`, whose defaults return `Collections.emptyList()` for both. `ExecutionStep` declares `getSubSteps()` but not `getSubExecutionPlans()`.
- **Verdict**: PARTIAL
- **Detail**: The override claim is exactly right and the "nested fetch plan is invisible" consequence follows. The imprecision is attribution: `getSubExecutionPlans()` lives on `ExecutionStepInternal`, not on `ExecutionStep`. Feeds CR3.

#### C4 Ref: `ExecutionStep.containsStepOfType`
- **Document claim**: `plan/track-10.md` `**Signatures:**` line lists `ExecutionStep.containsStepOfType` among the signatures the track touches.
- **Search performed**: full Read of `ExecutionStep.java`; `grep -rn "containsStepOfType" core/src --include=*.java`; Read of the helper declaration and body.
- **Code location**: NOT FOUND on `ExecutionStep`. The only declaration is `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:1618`.
- **Actual signature/role**: `private static boolean containsStepOfType(List<ExecutionStep> steps, Class<?> stepType)` — recurses through `step.getSubSteps()` only (line 1623). `ExecutionStep`'s full member list is `getName`, `getType`, `getDescription`, `getSubSteps`, `getCost`, `toResult`.
- **Verdict**: NOT FOUND
- **Detail**: A grep-negative, but confirmed by reading `ExecutionStep.java` end-to-end (43 lines), so a polymorphic or Javadoc miss is not possible for the interface half. Drives CR3.

#### C5 Ref: `StartStepRecogniser.normaliseIds`
- **Document claim**: `plan/track-10.md` failure 2 — "`g.V(rid)` now translates via `StartStepRecogniser.normaliseIds` and the plan is surfaced".
- **Search performed**: `grep -rn "normaliseIds" core/src --include=*.java`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java:167` (declaration), called at `:116` from the `graphStep.getIds()` path.
- **Actual signature/role**: `@Nullable private static List<RecordIdInternal> normaliseIds(Object[] ids)`.
- **Verdict**: MATCHES

#### C6 Ref: `YTDBQueryMetricsStrategyTest` — scenario count, category, and the four named failures
- **Document claim**: `plan/track-10.md` — the class is `@Category(SequentialTest)`; the four failures are `resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults`, `byIdLookupSurfacesNullPlan`, `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep`, and `indexedQuerySurfacesPlanWithFetchFromIndexStep`, with a sibling `resetWithoutTranslator_…`; `## Validation and Acceptance` requires "All 20 … scenarios pass".
- **Search performed**: grep for `@Test` with context, `@Category`, and the class declaration; `grep -c "^  @Test"`.
- **Code location**: `core/src/test/java/.../scenarios/YTDBQueryMetricsStrategyTest.java:43` (`@Category(SequentialTest.class)`), `:45` (class declaration), tests at `:274`, `:305`, `:338`, and the reset pair at `:397` / `:444`-region.
- **Actual signature/role**: exactly 20 `@Test` methods. All four named methods exist verbatim; the sibling is `resetWithoutTranslator_clearsPlanAndReIterationYieldsCorrectResults`.
- **Verdict**: MATCHES
- **Detail**: The "20 scenarios" acceptance figure is exact, not approximate.

#### C7 Ref: `core/pom.xml` `sequential-tests` execution
- **Document claim**: `plan/track-10.md` "Why it went unnoticed" — "`core/pom.xml` binds the `sequential-tests` surefire execution to the `test` phase, so a plain `./mvnw -pl core test` is red".
- **Search performed**: `grep -n -A12 "sequential-tests" core/pom.xml`; grep for the category interface declaration.
- **Code location**: `core/pom.xml:323-331`; `core/src/test/java/com/jetbrains/youtrackdb/internal/SequentialTest.java:18`.
- **Actual signature/role**: `<id>sequential-tests</id><phase>test</phase><goals><goal>test</goal></goals>` with `<groups>com.jetbrains.youtrackdb.internal.SequentialTest</groups>`.
- **Verdict**: MATCHES

#### C8 Ref: PR #1038 draft state
- **Document claim**: `plan/track-10.md` — "PR #1038 is a draft and every CI check reports `skipping`".
- **Search performed**: `gh pr view 1038 --json number,isDraft,title,state`.
- **Code location**: GitHub PR #1038, "YTDB-558: Gremlin-to-MATCH translator".
- **Actual signature/role**: `{"isDraft": true, "state": "OPEN"}`.
- **Verdict**: MATCHES
- **Detail**: Draft state confirmed. The "every CI check reports `skipping`" half was not re-verified this pass; it rests on the Track 8 Phase C investigation record.

#### C9 Ref: the four commits in the root-cause narrative
- **Document claim**: `plan/track-10.md` — `6e657ce2b1` ("Translate has/hasLabel/hasId and has(key) to MATCH", Track 4 era) broke five scenarios; `3d476357cc` ("Surface MATCH plans to query metrics; pin range semantics") repaired three and broke one; the non-compiling window was introduced by `6c3f474964` and removed by `bcd3b64c06`; failures date from 2026-07-16; the branch has been red for 117 commits.
- **Search performed**: `git show -s --format` on each SHA; `git rev-list --count 6e657ce2b1..HEAD`.
- **Code location**: branch `gremlin-to-match-translator-design`, HEAD `b3d386276b`.
- **Actual signature/role**: all four SHAs resolve with the quoted subjects. `6e657ce2b1` is dated 2026-07-16 15:12 +0200; `3d476357cc` is dated 2026-07-30. `git rev-list --count 6e657ce2b1..HEAD` returns 121.
- **Verdict**: MATCHES
- **Detail**: The 117 figure is an as-of-writing count; the four bookkeeping commits pushed since the investigation account for the difference. Historical counts are not restated as the plan moves, so this is not raised.

#### C10 Ref: "the only Gremlin-level index-usage assertion in the tree"
- **Document claim**: `plan/track-10.md` failures 3-4 — asserting on `FetchFromIndexStep` here "is the only Gremlin-level index-usage assertion in the tree".
- **Search performed**: `grep -rln FetchFromIndexStep` over `core/src/test`, `embedded/src/test`, `tests/src`; then `grep -rn` scoped to `core/src/test/java/.../core/gremlin/`.
- **Code location**: eleven test files reference `FetchFromIndexStep`; the only one under a `gremlin` package is `YTDBQueryMetricsStrategyTest.java`.
- **Actual signature/role**: the other ten are SQL-executor, security, and JUnit-5 index tests.
- **Verdict**: MATCHES
- **Detail**: Grep-negative on the "only" quantifier — an index-usage assertion phrased without naming `FetchFromIndexStep` (asserting on a `prettyPrint` substring, say) would not have been caught.

#### C11 Ref: `listener.planPrettyInCallback`
- **Document claim**: `plan/track-10.md` failures 3-4 — "`listener.planPrettyInCallback` would show it, but nothing asserts on it".
- **Search performed**: grep for `planPrettyInCallback|planStepsInCallback` in the test file.
- **Code location**: field declared at `:1579`, populated at `:1597` from `executionPlan.prettyPrint(0, 2)`, cleared at `:1610`; asserted only at `:512`, inside `executionPlanReadableInsideCallbackAfterResultSetClosed`.
- **Actual signature/role**: `String planPrettyInCallback;`
- **Verdict**: MATCHES
- **Detail**: The field exists and no index-usage assertion reads it, matching the track's framing of the open question.

#### C24 Flow: query-metrics plan capture over a translated traversal
- **Document claim**: `plan/track-10.md` — a translated `g.V().hasLabel("person")` no longer carries a `YTDBGraphStep`, so the capture reported `null` until `3d476357cc`; a MATCH plan now surfaces but hides its fetch steps from the test helper.
- **Trace**:
  1. `GremlinToMatchStrategy` replaces the whole step list with one boundary step — `YTDBMatchPlanStep` (`:34`) or `MultiPlanMatchStep` (`:97`), both `final`, both extending the Track 7 base. No `YTDBGraphStep` survives in a translated traversal.
  2. `YTDBQueryMetricsStep.capturedExecutionPlan()` @ `YTDBQueryMetricsStep.java:92` — finds `YTDBMatchPlanStep`, returns `getPlan()`.
  3. Test listener @ `YTDBQueryMetricsStrategyTest.java:1596` — `planStepsInCallback = executionPlan.getSteps()`.
  4. `containsStepOfType` @ `:1618` — walks `steps` and recurses only into `step.getSubSteps()`.
  5. `MatchFirstStep` @ `MatchFirstStep.java:39` — holds `InternalExecutionPlan executionPlan` privately and inherits `getSubSteps()` returning `Collections.emptyList()` from `ExecutionStepInternal:145`. Recursion stops; the nested scan or index step is unreachable.
- **Divergence point**: none — the code path matches the track's diagnosis at every hop.
- **Verdict**: MATCHES
- **Detail**: The trace also shows that only a `getSubSteps()` override closes the gap against the existing helper, which is the precision point folded into CR3.

**Plan ↔ Code — Track 9**

#### C12 Ref: `BoundaryOutputType` and the exhaustive `projectOrSkip` switch
- **Document claim**: `plan/track-9.md` trap 1 — "adding `BoundaryOutputType.LIST` breaks the compile-exhaustive `projectOrSkip` switch, which must gain a `LIST` case".
- **Search performed**: full Read of `BoundaryOutputType.java`; grep for `projectOrSkip` across `core/src/main/java`; Read of the method body.
- **Code location**: `core/.../translator/step/BoundaryOutputType.java:23-47`; `core/.../translator/step/AbstractMatchPlanStep.java:563-570`.
- **Actual signature/role**: the enum has four constants — `ELEMENT`, `MAP`, `SINGLE_VALUE`, `SCALAR`. `projectOrSkip` is `private Object projectOrSkip(Result row)` returning a `switch (outputType)` expression with exactly those four arms and no `default`, so it is compile-exhaustive.
- **Verdict**: MATCHES
- **Detail**: No `LIST` constant today, the expected state for a `[ ]` track. Track 7's file confirms the split of responsibility — its `## Interfaces and Dependencies` out-of-scope line names "the four terminator recognisers and `BoundaryOutputType.LIST` (Track 9)" — so the enum constant and the `ListShapingOp` drain stage are complementary by design, not competing mechanisms.

#### C13 Ref: `TailGlobalStepContract` / `TailGlobalStep` / `TailGlobalStepPlaceholder`
- **Document claim**: `plan/track-9.md` trap 2 — "`tail(n)` arrives as either `TailGlobalStep` or `TailGlobalStepPlaceholder`, both implementing `TailGlobalStepContract.getLimit()`".
- **Search performed**: `unzip -l` on the resolved fork jar (PSI unavailable, and these classes live in a binary dependency grep cannot reach in source form).
- **Code location**: `~/.m2/repository/io/youtrackdb/gremlin-core/3.8.1-67860f6-SNAPSHOT/gremlin-core-3.8.1-67860f6-SNAPSHOT.jar` — `org/apache/tinkerpop/gremlin/process/traversal/step/filter/{TailGlobalStepContract,TailGlobalStep,TailGlobalStepPlaceholder}.class`.
- **Actual signature/role**: all three classes present in the fork.
- **Verdict**: MATCHES
- **Detail**: Class presence confirmed by jar listing; the `getLimit()` member and the implements-relationship were not re-decompiled this pass and rest on the pre-split Phase A `javap` audit the track file already cites and schedules for PSI re-verification at decomposition.

#### C14 Ref: `FoldStep` / `UnfoldStep` / `ReverseStep`
- **Document claim**: `plan/track-9.md` `**Signatures:**` — `UnfoldStep.flatMap` / `ReverseStep.map` / `FoldStep` (TinkerPop reference semantics).
- **Search performed**: `unzip -l` on the same fork jar.
- **Code location**: `org/apache/tinkerpop/gremlin/process/traversal/step/map/{FoldStep,UnfoldStep,ReverseStep}.class`, plus `FoldStep$FoldBiOperator`.
- **Actual signature/role**: all three present.
- **Verdict**: MATCHES

#### C15 Ref: the `range` precedent — `RangeGlobalStep` plus placeholder both registered
- **Document claim**: `plan/track-9.md` trap 2 — "Track 6 already solved the identical shape for `range` by registering `RangeGlobalStep` and its placeholder".
- **Search performed**: `grep -rn "RangeGlobalStep|RangeGlobalStepPlaceholder" core/src/main/java`.
- **Code location**: `core/.../translator/strategy/GremlinStepWalker.java:163-164`; recogniser at `core/.../translator/strategy/RangeGlobalStepRecogniser.java:13`.
- **Actual signature/role**: two registry entries — `Map.entry(RangeGlobalStep.class, RangeGlobalStepRecogniser.INSTANCE)` and `Map.entry(RangeGlobalStepPlaceholder.class, RangeGlobalStepRecogniser.INSTANCE)` — with the recogniser keying on `RangeGlobalStepContract<?>` at `:25`.
- **Verdict**: MATCHES
- **Detail**: The precedent is exactly the shape the track prescribes for `tail`: two class keys, one recogniser, `instanceof` on the Contract interface.

#### C16 Ref: Cucumber runners `YTDBGraphFeatureTest` / `EmbeddedGraphFeatureTest`
- **Document claim**: `plan/track-9.md` — "the ~1900-scenario TinkerPop Cucumber suite (`YTDBGraphFeatureTest` in `core`, `EmbeddedGraphFeatureTest` in `embedded`)".
- **Search performed**: `find` by filename.
- **Code location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/YTDBGraphFeatureTest.java`; `embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedGraphFeatureTest.java`.
- **Verdict**: MATCHES
- **Detail**: Both runners exist in the named modules. The ~1900 scenario count was not recounted.

#### C17 Ref: the `jmh-ldbc` module
- **Document claim**: `plan/track-9.md` `**Signatures:**` — "the `jmh-ldbc` module (benchmark mirror template)".
- **Search performed**: `ls -d */`; `grep -n "<module>" pom.xml`.
- **Code location**: `jmh-ldbc/` at the repo root, declared at `pom.xml:54`.
- **Verdict**: MATCHES

#### C18 Ref: `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED`
- **Document claim**: `plan/track-9.md` — the strategy is "on by default since Track 2 via `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED`".
- **Search performed**: `grep -rn` across `core/src/main/java`.
- **Code location**: declared at `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java:1011`; read at `core/.../translator/strategy/GremlinToMatchStrategy.java:338`.
- **Verdict**: MATCHES
- **Detail**: The default value was not re-read; the "on by default" half rests on Track 2's completion record.

#### C19 Ref: the Track 7 ordered post-process carrier — `ListShapingOp` / `ResultShaping.listShapingOps()`
- **Document claim**: `plan/track-9.md` — the terminators "register ordered ops into the post-process carrier Track 7 built".
- **Search performed**: full Read of `ListShapingOp.java`; grep for `listShapingOps` in `ResultShaping.java`.
- **Code location**: `core/.../translator/step/ListShapingOp.java` (`@FunctionalInterface`, `Iterator<Object> apply(Iterator<Object> upstream)`); `core/.../translator/step/ResultShaping.java:45` (`@Nonnull List<ListShapingOp> listShapingOps`, defensive-copied at `:57`, `withListShapingOps` at `:103`).
- **Actual signature/role**: the carrier is an ordered `List` on the `ResultShaping` record and the op contract is a cardinality-changing stream stage, matching the track's description of `fold` / `unfold` / `reverse` / `tail` as drain / flat-map / one-to-one stages.
- **Verdict**: MATCHES
- **Detail**: `ListShapingOp`'s Javadoc names all four terminators and the fresh-iterator-per-`apply` contract Track 9's ops must honour, which `plan/track-9.md` does not restate. Track 7's episode already records it as an explicit cross-track hand-off, so it is covered by the plan and not raised.

#### C20 Ref: boundary-step hierarchy — `AbstractMatchPlanStep` / `YTDBMatchPlanStep` / `MultiPlanMatchStep`
- **Document claim**: plan `#### Component Map` and D8 revised — the Track 7 base is shared by the single-plan step and `MultiPlanMatchStep`.
- **Search performed**: grep for class declarations in all three files.
- **Code location**: `AbstractMatchPlanStep.java:89` (`public abstract class AbstractMatchPlanStep<S, E extends Element> extends AbstractStep<S, E>`); `YTDBMatchPlanStep.java:34` and `MultiPlanMatchStep.java:97`, both `public final class … extends AbstractMatchPlanStep<S, E>`.
- **Verdict**: MATCHES
- **Detail**: The plan's Component Map and D8 revised text match the as-built hierarchy. `MultiPlanMatchStep.getPlans()` at `:147` returns `List<InternalExecutionPlan>`, matching what `capturedExecutionPlan()` consumes.

#### C21 Ref: post-union suffix policy for list-shaping terminators
- **Document claim**: `plan/track-8.md` DR-U4 and line 39 assign the post-union list-shaping relaxation to Track 9; DR-U1 justifies the `MultipleExecutionStream` realization by "Track 9's design-sanctioned `union().fold()`". `plan/track-9.md` marks `union` / `MultiPlanMatchStep` out of scope and lists no such work.
- **Search performed**: Read of `track-8.md` lines 36-61 plus grep for `Track 10|DR-U4`; grep for `Track 10|union` in `track-9.md`; grep for `exhaust|postConcat|decline|Track 9|list-shap|fold` in `UnionStepRecogniser.java`.
- **Code location**: `core/.../translator/strategy/UnionStepRecogniser.java:28-29` (Javadoc: "the list-shaping terminators (`fold` and friends) are not translated yet") and `:126` ("Post-concat barriers (count / limit / dedup) may follow; every other suffix step declines").
- **Actual signature/role**: the shipped post-union allow-list is `count` / `limit` / `dedup`; the relaxation for the four terminators is unimplemented and no pending track scopes it.
- **Verdict**: MISMATCHES
- **Detail**: Track 8 states the assignment two ways that disagree — "Track 9 relaxes this" (line 39) versus "Track 9 **may** relax" (line 185) — and Track 9's file carries neither reading. Drives CR2.

**Plan ↔ Code — plan-level Architecture Notes**

#### C22 Invariant: the plan's `## Implementation state` reflects the checklist and the ledger
- **Document claim**: `implementation-plan.md:613` — "Tracks 1-7 are executed and complete; Track 8 Phase B is complete (Phase C pending); Track 9 is not started"; table row 8 `Phase B done`; `:627` "Track 8 Phase C still open".
- **Code evidence**: `implementation-plan.md:546` marks Track 8 `[x]` with a Phase C completion episode; `_workflow/phase-ledger.md:29` records `phase=C track=8 substate=track-complete` (2026-08-01T05:23Z); `git log` shows `3f3f1b7372` "Complete Track 8: union via MultiPlanMatchStep". `grep -n "Track 10" implementation-plan.md` returns one hit (line 573, the Checklist entry).
- **Mechanism**: no enforcement — the section is hand-maintained prose plus a table, updated by the orchestrator at track completion and after each inline replan.
- **Verdict**: VIOLATED
- **Detail**: Two stale claims (Track 8 Phase C pending, in three places) plus one omission (Track 10 absent from prose and table). Drives CR1.

#### C23 Ref: the query-metrics integration point in the plan's Architecture Notes
- **Document claim**: `implementation-plan.md` `### Integration Points` (`:352-360`) enumerates how new code connects to existing code; the `#### Component Map` (`:64-94`) enumerates the components the plan touches.
- **Search performed**: `grep -ni metric implementation-plan.md`; Read of both sections.
- **Code location**: NOT FOUND in the plan. The integration itself exists at `YTDBQueryMetricsStep.java:91-109` (see C1).
- **Actual signature/role**: the four Integration Points cover the strategy chain, the additive planner ctor, the count short-circuit, and the plan-cache invalidation hook. The Component Map has no monitoring or metrics node. Every "metric" occurrence in the plan sits inside the Track 10 checklist entry.
- **Verdict**: NOT FOUND
- **Detail**: The boundary step is a live consumer contract for the monitoring layer, and its unrecorded status is what let `6e657ce2b1` break the capture silently. Drives CR4.

#### C25 Ref: Track 9's inter-track dependencies after the Track 10 insertion
- **Document claim**: `plan/track-9.md:82` — "**Inter-track dependencies:** depends on Track 7 … and Track 8 … Last Phase 1 track"; `:5` — "validates the whole feature across all six prior tracks".
- **Search performed**: `grep -n "Track 10" plan/track-9.md` (no hits); Read of `plan/track-10.md:88`.
- **Code location**: `plan/track-10.md:88` states the dependency from the other side — "**Runs before Track 9** — Track 9's Cucumber-green and JMH-baseline goals both assume a green starting point."
- **Actual signature/role**: the dependency is recorded on Track 10 only; Track 9 is silent, and its prior-track count predates the insertion.
- **Verdict**: MISMATCHES
- **Detail**: Asymmetric cross-reference left by the replan. Drives CR5.

**Design ↔ Code and Design ↔ Plan**

#### C26 Ref: `design.md` § "Boundary-step lifecycle" coverage of `reset()` re-arming
- **Document claim**: the frozen design's `## Boundary-step lifecycle` (`design.md:1858-1941`) is the section covering the boundary step's lifetime; Track 10 step 1 must settle whether `reset()` re-arms from `CLOSED`.
- **Search performed**: grep of `design.md` for `reset()|lifecycle|CLOSED|DRAINED|QueryMetrics|getSubSteps|MatchFirstStep`; Read of lines 1858-1947.
- **Code location**: NOT FOUND — the section covers stream open (lazy on first `processNextStart`), the three close triggers, and `clone()` plan reuse. It says nothing about `reset()` re-arming or a terminal state.
- **Actual signature/role**: the as-built `State` machine (C2) is finer-grained than anything the design describes.
- **Verdict**: NOT FOUND
- **Detail**: Recorded for the Phase 4 `design-final.md`, not raised. `design.md` is frozen; Track 10 correctly carries the decision in its own `## Decision Log` under D7, and step 1 says the contract "needs a Decision Record either way", so nothing is decided behind the design's back.

#### C27 Ref: frozen-design lags on the boundary hierarchy and list-shaping ownership
- **Document claim**: `design.md:1860` — "`YTDBMatchPlanStep` (and its `MultiPlanMatchStep` subclass for `union`)"; `:1920` — "`MultiPlanMatchStep` extends `YTDBMatchPlanStep`"; `:576` — heading "## List-shaping terminators (Track 6)"; `:590-600` — `fold` implemented as an internal `ArrayList` drain inside `processNextStart`.
- **Search performed**: grep of `design.md` headings; Read of lines 576-600 and 1858-1947; compared against C19 and C20.
- **Code location**: as-built hierarchy at C20; as-built carrier at C19.
- **Actual signature/role**: both boundary steps extend `AbstractMatchPlanStep`; the list-shaping terminators belong to Track 9; `fold` will register a `ListShapingOp` drain stage rather than an inline `ArrayList` in `processNextStart`.
- **Verdict**: MISMATCHES
- **Detail**: Expected frozen-design lag, already deferred by the 2026-07-15 (CR1) and 2026-07-27 re-validations. Per the consistency prompt, a revised plan diverging from a frozen design is not a finding; the Phase 4 `design-final.md` reconciles it. Listed so the reconciler has the specific line references.

**Gaps**

#### Orphan check — codebase constructs the plan should reference
Two candidates, one gap. The query-metrics capture path is a real orphan and
is raised as CR4. The `ListShapingOp` fresh-iterator-per-`apply` contract
(C19) is the second — Track 9's ops must honour it or a re-armed or
multi-plan traversal replays stale output — but Track 7's episode already
records it as an explicit cross-track hand-off to Track 9, so the plan covers
it even though `plan/track-9.md` does not restate it. Not raised.

#### Design coverage of pending-track work
Track 9's terminator work has design coverage at `design.md:576`, modulo the
frozen-state lags in C27. Track 10 has no design coverage at all, the expected
shape for a remediation track inserted after the design freeze — the
correction routes to Phase 4, not to the plan. Not raised.
