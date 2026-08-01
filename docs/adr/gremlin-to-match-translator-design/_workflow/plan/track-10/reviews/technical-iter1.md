<!-- MANIFEST
findings: 8   severity: {blocker: 2, should-fix: 3, suggestion: 3}
index:
  - {id: T1, sev: blocker,    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:2089", anchor: "### T1 ", cert: C12, basis: "Item 3's fix targets the wrong step: at MODERN cardinalities the fetch lives under MatchPrefetchStep and MatchFirstStep's sub-plan is null, so the override leaves both scan tests red"}
  - {id: T2, sev: blocker,    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/AbstractExecutionStep.java:102", anchor: "### T2 ", cert: C15, basis: "Re-arming from CLOSED by relaxing the state machine restarts a closed plan whose step close-guard is sticky; cursors leak and the mocked-plan unit harness cannot see it"}
  - {id: T3, sev: should-fix, loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:274", anchor: "### T3 ", cert: C23, basis: "Three plan-capture scenarios do not pin the translator kill-switch, so whichever contract Phase A picks is re-pointed silently if the default flips"}
  - {id: T4, sev: should-fix, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/HashJoinMatchStep.java:411", anchor: "### T4 ", cert: C17, basis: "Five MATCH steps already override getSubSteps to expose a nested plan, so option (a) is convention conformance rather than a new engine-surface exception"}
  - {id: T5, sev: should-fix, loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStepTest.java:711", anchor: "### T5 ", cert: E5, basis: "No test covers close-then-reset at unit or scenario level; the one reset scenario that does exist passes on a zero-row second run"}
  - {id: T6, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:86", anchor: "### T6 ", cert: C24, basis: "~5 files understates the realistic 7-10 file footprint once the prefetch step and both boundary subclasses are counted"}
  - {id: T7, sev: suggestion, loc: ".github/workflows/maven-pipeline.yml:3", anchor: "### T7 ", cert: C22, basis: "workflow_dispatch already exists, giving item 4 a third option the track does not list"}
  - {id: T8, sev: suggestion, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:497", anchor: "### T8 ", cert: C2, basis: "Three Javadoc blocks state CLOSED is terminal by design; a product-side item 1 must revise them in the same commit"}
evidence_base: {section: "## Evidence base", certs: 30, matches: 26}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9, verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
  - {id: C12, verdict: WRONG, anchor: "#### C12 "}
  - {id: C13, verdict: CONFIRMED, anchor: "#### C13 "}
  - {id: C14, verdict: CONFIRMED, anchor: "#### C14 "}
  - {id: C15, verdict: CONFIRMED, anchor: "#### C15 "}
  - {id: C16, verdict: CONFIRMED, anchor: "#### C16 "}
  - {id: C17, verdict: CONFIRMED, anchor: "#### C17 "}
  - {id: C18, verdict: CONFIRMED, anchor: "#### C18 "}
  - {id: C19, verdict: CONFIRMED, anchor: "#### C19 "}
  - {id: C20, verdict: CONFIRMED, anchor: "#### C20 "}
  - {id: C21, verdict: CONFIRMED, anchor: "#### C21 "}
  - {id: C22, verdict: CONFIRMED, anchor: "#### C22 "}
  - {id: C23, verdict: PARTIAL, anchor: "#### C23 "}
  - {id: C24, verdict: PARTIAL, anchor: "#### C24 "}
  - {id: E1, verdict: PARTIAL, anchor: "#### E1 "}
  - {id: E2, verdict: CONFIRMED, anchor: "#### E2 "}
  - {id: E3, verdict: CONFIRMED, anchor: "#### E3 "}
  - {id: E4, verdict: CONFIRMED, anchor: "#### E4 "}
  - {id: E5, verdict: CONFIRMED, anchor: "#### E5 "}
  - {id: I1, verdict: MATCHES, anchor: "#### I1 "}
flags: [CONTRACT_OK]
-->

# Track 10 — technical review, iteration 1

Two of the track's four plan-of-work items rest on a premise the code contradicts. Item 3's product-side fix targets `MatchFirstStep`, but at the cardinalities the failing tests run at the fetch step lives under `MatchPrefetchStep` and `MatchFirstStep` is built with a null sub-plan — the override would leave both scan tests red. Item 1's product-side option cannot be realized by relaxing the boundary step's state machine, because a `SelectExecutionPlan` closed once cannot be restarted: `AbstractExecutionStep.alreadyClosed` is sticky and `SelectExecutionPlan.reset` does not clear it. Both are fixable with a scope amendment rather than a redesign, and the review supplies the missing evidence for the `Engine surface is preserved` decision the track hands Phase A: five MATCH execution steps already override `getSubSteps()` to expose a nested plan, so option (a) is conformance to an in-package convention rather than a new exception.

**Reference-accuracy caveat.** mcp-steroid answered `steroid_list_projects` (project open, path matches the working tree) but `steroid_execute_code` timed out at 55 s on the first PSI query — the known cold-kotlinc timeout for this repo. Every symbol result below comes from grep plus direct source reads. Negative results (`X overrides neither getSubSteps nor getSubExecutionPlans`, `no test pins close-then-reset`) carry the usual grep risk of missing a polymorphic or renamed site; each was cross-checked by reading the declaring file end to end, which bounds that risk for these specific classes but does not eliminate it repo-wide.

## Findings

### T1 [blocker]
**Certificate**: C12 (WRONG), supported by C13, C14, C19, C20, C21

**Location**: track-10.md `## Context and Orientation` items 3–4 and `## Plan of Work` item 3; `MatchExecutionPlanner.java:2089` and `:4745`; `MatchPrefetchStep.java`

**Issue**: The track reasons that `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep` and `indexedQuerySurfacesPlanWithFetchFromIndexStep` fail because "`MatchFirstStep` overrides neither `getSubSteps()` nor `getSubExecutionPlans()`, so a nested fetch plan is invisible". The first clause is true. The inference is not: in both failing scenarios `MatchFirstStep` holds no nested plan at all.

`MatchExecutionPlanner` prefetches every alias whose estimated cardinality is below `THRESHOLD = 100` (`MatchExecutionPlanner.java:347`, `:601-606`). For a prefetched alias, `createPlanForPattern` takes the three-argument `MatchFirstStep` constructor, which passes `subPlan = null` (`:2089`); the class scan moves into a `MatchPrefetchStep` built at `:4750` with `prefetchStm.createExecutionPlan(...)`. The MODERN graph has four `person` vertices and the indexed test inserts one `IndexedThing`, so both aliases fall far below the threshold, and a positional-parameter predicate does not disqualify them — `filterDependsOnContext` keys only on `refersToParent()` and the literal `$matched.` (`:967-976`).

`GqlMatchStatementPlanPrettyPrintTest.prettyPrint_singleNodeAnonymous` pins the resulting shape against a real one-vertex class: `+ PREFETCH $c0`, then `+ FETCH FROM CLASS PlanMatchA`, then a bare `+ SET`. `MatchFirstStep.prettyPrint` emits the nested `AS` block only when `executionPlan != null` (`MatchFirstStep.java:134-139`), so the bare `+ SET` is direct evidence that the step carries no sub-plan on this path. `MatchPrefetchStep` overrides neither introspection method either (read in full, 131 lines).

The translator reaches the same code: the additive `MatchExecutionPlanner(MatchPlanInputs)` constructor only seeds fields and then runs the shared `createExecutionPlan`, prefetch phase included (`:500-533`).

Consequence: implementing item 3 exactly as written — override `getSubSteps()` on `MatchFirstStep` — leaves both scan tests red, and a decomposer that verifies the fix only against a large class (over 100 records, no prefetch) would see it pass and ship a fix that does not hold at the test's own cardinality.

**Proposed fix**: Amend `## Plan of Work` item 3 and `## Interfaces and Dependencies` to name `MatchPrefetchStep` as the primary target and `MatchFirstStep` as the secondary one (the sub-plan arm is still reachable above the threshold, and the union / multi-node patterns Track 8 built do use it). Re-frame the index-usage sub-question as "does the `+ PREFETCH` sub-plan contain a `FetchFromIndexStep`" — that is where the answer lives, and `listener.planPrettyInCallback` already renders it because `MatchPrefetchStep.prettyPrint` inlines the sub-plan. Add a step-level assertion that the fix is exercised at both cardinalities, or state in the step why only the prefetched shape is covered.

### T2 [blocker]
**Certificate**: E2, supported by C2, C15, C16, C3, E3

**Location**: track-10.md `## Plan of Work` item 1 and `## Interfaces and Dependencies` (In scope); `AbstractMatchPlanStep.java:494-512`; `AbstractExecutionStep.java:102-118`; `SelectExecutionPlan.java:74-108`

**Issue**: Item 1 offers "re-arm from `CLOSED` (matching `YTDBGraphStep`, which re-executes fine)" as the product-side option. The state-machine half of that is one line — extend `reset()` to map `CLOSED` to `REARMED`. The plan half does not work.

`AbstractMatchPlanStep.close()` sets `state = CLOSED` and calls `closePlan()`, which for `YTDBMatchPlanStep` is `plan.close()` (`YTDBMatchPlanStep.java:144-146`). `SelectExecutionPlan.close()` delegates to `lastStep.close()`, and `AbstractExecutionStep.close()` sets a private `alreadyClosed` flag that propagates backward and is never cleared: `AbstractExecutionStep` declares no `reset()` override, and `SelectExecutionPlan.reset(ctx)` only calls `ExecutionStepInternal::reset` on each step, whose default is a no-op. So `rewindPlan(ctx)` followed by `startPlanStream()` on a closed plan re-runs every step's `internalStart` while every subsequent `close()` short-circuits — the cursor leak the base class's own Javadoc already warns about ("a closed `SelectExecutionPlan` cannot be cleanly restarted (its steps' close guard is sticky, so a re-run's cursors would leak)", `AbstractMatchPlanStep.java:66-69`).

The existing unit harness cannot catch this. `YTDBMatchPlanStepTest` mocks `InternalExecutionPlan`, so a mocked `plan.start()` after `plan.close()` returns a fresh stream and the test goes green while production leaks.

The viable product-side realization is to give the re-arm a fresh plan rather than restart the closed one: `InternalExecutionPlan.copy(ctx)` deep-copies the step chain (`SelectExecutionPlan.java:238-272`), so a copy starts with `alreadyClosed == false`. That is safe against the plan cache, which already hands each traversal a private copy (`GremlinPlanCache.getInternal` returns `result.copy(ctx)`). It needs a fifth plan-seam hook on `AbstractMatchPlanStep` and an implementation in both concrete subclasses — `MultiPlanMatchStep.closePlan()` closes every child, so its re-arm must copy all N children against fresh isolated contexts, mirroring its `clone()`.

Neither `YTDBMatchPlanStep` nor `MultiPlanMatchStep` appears in the track's In-scope list, which names only `AbstractMatchPlanStep`.

**Proposed fix**: Record in the item-1 Decision Record that the product-side option means *re-create the plan on re-arm*, not *restart the closed plan*, and cite the sticky close guard as the reason. Add `YTDBMatchPlanStep` and `MultiPlanMatchStep` to `## Interfaces and Dependencies` In scope. Require the step's test to assert the no-restart property against something a Mockito plan cannot fake — verify `plan.copy(...)` is called and `plan.start()` is never re-invoked on the original — since the mocked-plan harness is otherwise blind to the leak.

### T3 [should-fix]
**Certificate**: C23 (PARTIAL)

**Location**: track-10.md `## Plan of Work` items 2 and 3; `YTDBQueryMetricsStrategyTest.java:274`, `:305`, `:338`

**Issue**: The three scenarios whose contracts Phase A is about to settle — `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep`, `indexedQuerySurfacesPlanWithFetchFromIndexStep`, `byIdLookupSurfacesNullPlan` — never touch the translator kill-switch. They run against whatever `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to.

The same file already establishes the opposite convention, and documents why. `cacheHitReplayUnderTranslator_keepsCompiledPlan` and `cacheHitReplayWithoutTranslator_surfacesNullPlan` were deliberately split rather than branched, with the comment: "the translator is on by default, so the half-measure leg would never run and a regression that stopped translating would silently move the assertion instead of failing it" (`:519-527`). `resetUnderTranslator_` / `resetWithoutTranslator_` follow the same pattern via `setTranslatorEnabled(...)` (`:710-720`).

This matters for the contract choice, not just for hygiene. `byIdLookupSurfacesNullPlan`'s stated rationale — "a by-id lookup takes the branch that runs no query" — is still true on the half-measure path (`YTDBGraphStep` with pinned ids) and false on the translated path (`g.V(rid)` normalizes through `StartStepRecogniser.normaliseIds` and a plan is built). Rewriting the assertion to `isNotNull()` without pinning the path would discard a correct half-measure contract instead of adding the translated one.

**Proposed fix**: Extend items 2 and 3 to require each of the three scenarios to pin the kill-switch, splitting into `…UnderTranslator_` / `…WithoutTranslator_` pairs where both paths have a real contract (certainly `byIdLookup`). Reuse the existing `setTranslatorEnabled(boolean)` helper.

### T4 [should-fix]
**Certificate**: C17, supported by I1, C5, E4

**Location**: track-10.md `## Plan of Work` item 3 (the Phase-A decision paragraph) and `## Invariants & Constraints` third bullet

**Issue**: The track frames option (a) as "amending the Constraints bullet with a third exception, on the grounds that sub-step / sub-plan introspection adds no execution behaviour" — a novel carve-out needing justification. The codebase already settled the question. Five MATCH execution steps override `getSubSteps()` to expose a nested plan's steps:

- `HashJoinMatchStep.java:411` — `return List.copyOf(buildPlan.getSteps());`
- `FilterNotMatchPatternStep.java:112`
- `BackRefHashJoinStep.java:841`
- `InvertedWhileHashJoinStep.java:338`
- `CorrelatedOptionalHashJoinStep.java:211`

`MatchFirstStep` and `MatchPrefetchStep` are the outliers, not the precedent-setters. That reframes the decision: option (a) makes two steps conform to a convention their five siblings already follow, using the identical `List.copyOf(plan.getSteps())` shape.

The side-effect surface is small and enumerable, which the track's framing leaves open:

- `ExecutionStep.toResult()` recurses through `getSubSteps()` (`ExecutionStep.java:41-44`), so `EXPLAIN` result documents for MATCH plans gain nested `subSteps` entries. No test asserts on MATCH `subSteps` content — the six MATCH explain tests found all assert on `prettyPrint` strings, which are unchanged because both steps already inline their sub-plan there (`MatchFirstStep.java:134-139`, `MatchPrefetchStep.java:111-118`).
- `ExecutionStepInternal.basicSerialize` / `basicDeserialize` walk `getSubSteps()`, and `basicDeserialize` mutates it. Unreachable for both steps: neither overrides `serialize()` / `deserialize()`, so the interface defaults throw first. The five precedent steps return immutable `List.copyOf` and have the same property.
- The plan-shape scans in `DatabaseSessionEmbedded` (`:1130`, `:1231`, `:1295`, `:1331`) and `YTDBGraphQuery.usedIndexes` (`:44-62`) iterate top-level steps only and do not recurse — unaffected.
- Profiling reads `getCost()` per step and does not aggregate over sub-steps.

**Proposed fix**: Rewrite the item-3 decision paragraph to present option (a) as convention conformance with the five precedents named, and list the `toResult` / `EXPLAIN` change as the one observable side effect the step must cover with a test. That gives the Phase-A writer a decision they can make on evidence rather than on a reading of the Constraints bullet.

### T5 [should-fix]
**Certificate**: E5, supported by C2, E1

**Location**: track-10.md `## Validation and Acceptance`; `YTDBQueryMetricsStrategyTest.java:397-436`; `YTDBMatchPlanStepTest.java:701-800`

**Issue**: The reset coverage that exists cannot detect the item-1 defect, in either test layer.

At scenario level, `queryFinishedFiresAgainAfterResetAndReExecution` does exactly the failing sequence — iterate to exhaustion, `close()`, `admin.reset()`, iterate again — and passes today. It asserts only that the listener fired twice. Under the translator the second iteration yields zero rows, but `YTDBQueryMetricsStep.hasNext()` still calls `queryHasStarted()` before delegating, so `close()` fires the callback regardless of row count (`YTDBQueryMetricsStep.java:112-128`, `:150-158`). The scenario is green over a silently empty re-run.

At unit level, `YTDBMatchPlanStepTest` has four reset tests — `reset_thenProcessNextStart_reRunsPlanOnSameInstance`, `…rebindsSessionAgainBeforeSecondStart`, `reset_beforeFirstIteration_doesNotRewindPlanOnFirstOpen`, `reset_afterPartialConsume_deferStreamClose_thenReRunsKeepingPlan` — covering `NEW`, `OPEN` and `DRAINED`. None calls `close()` before `reset()`. A repo-wide grep for a close-then-reset contract test on the boundary steps returned nothing.

**Proposed fix**: Add to `## Validation and Acceptance`: (a) `queryFinishedFiresAgainAfterResetAndReExecution` gains a row-count assertion on the second execution, run on both source paths per T3; (b) `YTDBMatchPlanStepTest` and `MultiPlanMatchStepTest` each gain a `close() → reset() → re-iterate` case pinning whichever contract item 1 settles on, with the anti-mock verification from T2 if the product side wins.

### T6 [suggestion]
**Certificate**: C24 (PARTIAL)

**Location**: plan `## Checklist` Track 10 Scope line; track-10.md `## Interfaces and Dependencies`

**Issue**: The `~5 files` scope indicator understates the footprint once T1 and T2 land. A realistic in-scope set: `AbstractMatchPlanStep`, `YTDBMatchPlanStep`, `MultiPlanMatchStep` (item 1 product side), `MatchPrefetchStep` and `MatchFirstStep` (item 3 product side), `YTDBQueryMetricsStrategyTest`, `YTDBMatchPlanStepTest`, `MultiPlanMatchStepTest`, plus whichever artifact item 4 produces. That is 8–9, still well inside the ~12 merge-candidate bound and nowhere near the split ceiling, so nothing about the track's shape changes.

**Proposed fix**: Update the Scope line to `~8-10 files` when the Phase-A amendments for T1 and T2 are written, so the Phase-C review-burden check compares against a number that was not stale on arrival.

### T7 [suggestion]
**Certificate**: C22, supported by C11

**Location**: track-10.md `## Plan of Work` item 4

**Issue**: Item 4 weighs "undraft PR #1038, or add a cheap always-on check, or both". A third option already exists at zero cost. `maven-pipeline.yml` declares `workflow_dispatch` as its first trigger (`:2-3`), and the draft gate lives on one job — `detect-changes`, `if: github.event.pull_request.draft != true` (`:27`) — that every downstream job hangs off. A `workflow_dispatch` event carries no `github.event.pull_request`, so the condition evaluates true and the full pipeline runs on the branch without undrafting the PR.

That does not close the detection hole on its own (a manual dispatch is not a gate), but it is the cheapest way to get a green-or-red answer for this track's own acceptance criterion before deciding what permanent mechanism to add.

**Proposed fix**: Add manual `workflow_dispatch` to item 4's option list, marked as the immediate verification lever rather than the permanent fix.

### T8 [suggestion]
**Certificate**: C2

**Location**: `AbstractMatchPlanStep.java:79-81`, `:153-167`, `:493-505`, `:517-521`

**Issue**: `CLOSED` is terminal by documentation in four places, not one: the class-level Lifecycle list, the `State.CLOSED` enum constant Javadoc ("Terminal: `processNextStart()` ends immediately and `close()` is a no-op"), the `reset()` Javadoc ("a `CLOSED` step stays `CLOSED` (a plan closed for good is not revived by a reset)"), and the `close()` Javadoc's note on why the guard checks `CLOSED` rather than `DRAINED`. A product-side item 1 flips all four.

**Proposed fix**: Note in the item-1 step that the Javadoc revision is part of the same commit, per the CLAUDE.md keep-comments-in-sync rule. Stale terminal-state prose on a state machine is the kind of comment that misleads the next reader into re-introducing the bug.

## Evidence base

#### C1 Premise: `YTDBQueryMetricsStep.capturedExecutionPlan()` exists and reads the MATCH boundary
- **Track claim**: `## Interfaces and Dependencies` lists `YTDBQueryMetricsStep.capturedExecutionPlan()` as an in-scope signature.
- **Search performed**: grep for the file, full read (409 lines). PSI unavailable.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java:91-109`
- **Actual behavior**: `@Nullable private ExecutionPlan capturedExecutionPlan()` tries `YTDBMatchPlanStep` first (`getPlan()`), then `MultiPlanMatchStep` (`getPlans().getFirst()`, null when empty), then falls back to `YTDBGraphStep.getLastExecutionPlan()`. Invoked from the anonymous `QueryDetails.getExecutionPlan()` inside `close()`.
- **Verdict**: CONFIRMED

#### C2 Premise: `AbstractMatchPlanStep.reset()` re-arms only from `OPEN`/`DRAINED`; `CLOSED` is terminal
- **Track claim**: item 1 — "`reset()` re-arms only from `OPEN`/`DRAINED` and `CLOSED` is terminal by design, so `processNextStart()` throws `FastNoSuchElementException`".
- **Search performed**: full read of the file (812 lines).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java:144-170` (State enum), `:237-241` (processNextStart guard), `:506-512` (reset), `:523-541` (close)
- **Actual behavior**: `reset()` is `super.reset(); if (state == OPEN || state == DRAINED) state = REARMED;`. `processNextStart()` opens with `if (state == DRAINED || state == CLOSED) throw FastNoSuchElementException.instance();`. `close()` sets `state = CLOSED` and calls `closePlan()`. The terminality is documented in four Javadoc blocks (class Lifecycle at `:79-81`, enum constant at `:163-167`, `reset()` at `:493-505`, `close()` at `:517-521`).
- **Verdict**: CONFIRMED

#### C3 Premise: `MultiPlanMatchStep` extends `AbstractMatchPlanStep` and its `closePlan()` closes every child
- **Track claim**: implicit — the track lists only `AbstractMatchPlanStep` as in scope for item 1.
- **Search performed**: full read (465 lines).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java:97`, `:246-255` (rewindPlan), `:401-430` (closePlan)
- **Actual behavior**: `public final class MultiPlanMatchStep<S, E extends Element> extends AbstractMatchPlanStep<S, E>`. `rewindPlan` resets every child against its own context; `closePlan` closes every child including un-run ones, first failure primary, rest via `addSuppressed`.
- **Verdict**: CONFIRMED

#### C4 Premise: `MatchFirstStep` overrides neither `getSubSteps()` nor `getSubExecutionPlans()`
- **Track claim**: `## Context and Orientation` items 3–4.
- **Search performed**: full read (161 lines) plus a repo-wide grep for `public.*List<ExecutionStep> getSubSteps`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchFirstStep.java`
- **Actual behavior**: the class declares `reset`, `internalStart`, `canBeCached`, `prettyPrint`, `getAlias`, `copy`. Neither introspection method appears, and the file does not appear in the repo-wide override grep.
- **Verdict**: CONFIRMED (grep-based negative; the full read bounds the miss risk for this class)

#### C5 Premise: the `ExecutionStepInternal` defaults return empty lists
- **Track claim**: `## Interfaces and Dependencies` — "the default implementations `MatchFirstStep` inherits".
- **Search performed**: grep + targeted read.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/ExecutionStepInternal.java:142-152`
- **Actual behavior**: `default List<ExecutionStep> getSubSteps() { return Collections.emptyList(); }` and `default List<ExecutionPlan> getSubExecutionPlans() { return Collections.emptyList(); }`. `getSubSteps()` is declared `@Nonnull` on the `ExecutionStep` interface (`ExecutionStep.java:22-23`).
- **Verdict**: CONFIRMED

#### C6 Premise: `containsStepOfType` recurses through `getSubSteps()` only
- **Track claim**: `## Interfaces and Dependencies` — "a private test helper … it recurses through `getSubSteps()` only, so overriding `getSubExecutionPlans()` alone would not make it find a nested fetch step".
- **Search performed**: grep for the symbol, read of the enclosing block.
- **Code location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:1618-1628`
- **Actual behavior**: `private static boolean containsStepOfType(List<ExecutionStep> steps, Class<?> stepType)` loops `steps`, tests `stepType.isInstance(step)`, recurses on `step.getSubSteps()`. No `getSubExecutionPlans()` traversal.
- **Verdict**: CONFIRMED

#### C7 Premise: `StartStepRecogniser.normaliseIds` exists
- **Track claim**: `## Context and Orientation` item 2 and `## Interfaces and Dependencies` Signatures.
- **Search performed**: grep for the symbol.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/StartStepRecogniser.java:167`
- **Actual behavior**: `@Nullable private static List<RecordIdInternal> normaliseIds(Object[] ids)`, called at `:116` from the `graphStep.getIds()` path.
- **Verdict**: CONFIRMED

#### C8 Premise: `YTDBGraphStep.getLastExecutionPlan()` exists and `reset()` nulls it
- **Track claim**: item 1's comparison — "matching `YTDBGraphStep`, which re-executes fine"; test `resetWithoutTranslator_` asserts `reset()` clears the plan.
- **Search performed**: grep within the file.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/sideeffect/YTDBGraphStep.java:46`, `:157`, `:229-230`, `:234-240`
- **Actual behavior**: `@Nullable private ExecutionPlan lastExecutionPlan`, assigned from `resultSet.getExecutionPlan()` at `:157`, exposed at `:229`, nulled inside `reset()` at `:240`. The half-measure source re-issues its query per arming, so it has no closed-plan restart problem.
- **Verdict**: CONFIRMED

#### C9 Premise: `SelectExecutionPlan`, `FetchFromClassExecutionStep`, `FetchFromIndexStep` resolve
- **Track claim**: named in `## Context and Orientation` items 2–4.
- **Search performed**: `find -name`, plus the test's own imports.
- **Code location**: `core/src/main/java/.../sql/executor/SelectExecutionPlan.java`; imports at `YTDBQueryMetricsStrategyTest.java:20-21`
- **Actual behavior**: all three resolve in `com.jetbrains.youtrackdb.internal.core.sql.executor`; single match each, package matches the reconstructed FQN.
- **Verdict**: CONFIRMED

#### C10 Premise: `core/pom.xml` runs `SequentialTest` in the `test` phase
- **Track claim**: "the class is `@Category(SequentialTest)` and `core/pom.xml` binds the `sequential-tests` surefire execution to the `test` phase, so a plain `./mvnw -pl core test` is red".
- **Search performed**: grep in `core/pom.xml`.
- **Code location**: `core/pom.xml:323-331`
- **Actual behavior**: `<id>sequential-tests</id><phase>test</phase><goals><goal>test</goal></goals>` with `<groups>com.jetbrains.youtrackdb.internal.SequentialTest</groups>`. The test class carries `@Category(SequentialTest.class)` at `YTDBQueryMetricsStrategyTest.java:43`.
- **Verdict**: CONFIRMED

#### C11 Premise: a draft PR skips the whole Maven pipeline
- **Track claim**: "PR #1038 is a draft and every CI check reports `skipping`".
- **Search performed**: grep for `draft` across `.github/workflows/`.
- **Code location**: `.github/workflows/maven-pipeline.yml:25-27`
- **Actual behavior**: `detect-changes` carries `if: github.event.pull_request.draft != true`; every build/test job hangs off it, and `ci-status` at `:726` carries the same guard. `block-merge-commits.yml`, `pr-title-prefix.yml`, and `workflow-toc-check.yml` gate the same way.
- **Verdict**: CONFIRMED

#### C12 Premise: the failing scan tests' fetch step sits under `MatchFirstStep`
- **Track claim**: item 3 — "If `MatchFirstStep` should expose its nested plan through `getSubSteps()` / `getSubExecutionPlans()`, that fixes both scan tests".
- **Search performed**: grep for `MatchFirstStep` construction sites in `MatchExecutionPlanner`, read of each; read of `MatchPrefetchStep` in full; cross-check against the GQL plan pretty-print test.
- **Code location**: `MatchExecutionPlanner.java:2085-2101` (single isolated node), `:4745-4756` (addPrefetchSteps); `MatchFirstStep.java:51-55` (the null-subPlan constructor), `:134-139` (prettyPrint gate)
- **Actual behavior**: when the alias is prefetched, `plan.chain(new MatchFirstStep(context, node, profilingEnabled))` builds the step with `subPlan = null`; the scan lives in the `MatchPrefetchStep` chained ahead of it. `MatchFirstStep.internalStart` then reads the context variable instead of starting a sub-plan (`:99-107`).
- **Verdict**: WRONG
- **Detail**: the premise holds only above the prefetch threshold. At the cardinalities the two failing tests run at (4 `person` vertices, 1 `IndexedThing`), the alias is always prefetched, so the fix as described has no effect on either test.

#### C13 Premise: the prefetch threshold and estimation path put MODERN aliases below it
- **Track claim**: none — this is the mechanism behind C12.
- **Search performed**: grep for `THRESHOLD`, read of the prefetch filter and `estimateRootEntries`.
- **Code location**: `MatchExecutionPlanner.java:347` (`private static final long THRESHOLD = 100`), `:601-606` (prefetch filter), `:5395-5443` (estimateRootEntries)
- **Actual behavior**: aliases with `estimate < 100` and no `$matched` dependency are prefetched. An unfiltered class alias estimates at `classCount + 1`; a filtered one at `Math.min(filter.estimate(oClass, THRESHOLD, ctx), classCount)`. MODERN has four `person` vertices; the indexed test inserts one `IndexedThing`.
- **Verdict**: CONFIRMED

#### C14 Premise: a real single-node MATCH over a tiny class renders PREFETCH + FETCH + bare SET
- **Track claim**: none — independent confirmation of C12 against executed code rather than planner reading.
- **Search performed**: grep for `PREFETCH` in tests, read of the matching test class.
- **Code location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatementPlanPrettyPrintTest.java:28-48`
- **Actual behavior**: for a class holding one vertex, `prettyPrint(0, 2)` contains `+ PREFETCH $c0`, `+ FETCH FROM CLASS PlanMatchA`, `+ SET`, `+ CALCULATE PROJECTIONS`. `MatchFirstStep.prettyPrint` appends the nested `AS` block only when `executionPlan != null`, so the bare `+ SET` shows the step holds no sub-plan on this path. Three sibling tests (`:54-80`, `:86-110`, `:116-151`) show the same shape with filters and under a cartesian product.
- **Verdict**: CONFIRMED

#### C15 Premise: a closed `SelectExecutionPlan` can be restarted after `reset`
- **Track claim**: implicit in item 1's "re-arm from `CLOSED`" option.
- **Search performed**: grep for `close`/`reset` in `SelectExecutionPlan` and `AbstractExecutionStep`, targeted reads.
- **Code location**: `SelectExecutionPlan.java:74-78` (close), `:105-109` (reset); `AbstractExecutionStep.java:101-118` (the guard); `ExecutionStepInternal.java:154-162` (default no-op reset)
- **Actual behavior**: `close()` delegates to `lastStep.close()`; `AbstractExecutionStep.close()` sets `alreadyClosed = true` and propagates backward. `reset(ctx)` calls `ExecutionStepInternal::reset` on each step, whose interface default does nothing, and `AbstractExecutionStep` declares no `reset()` override — the flag survives. The base boundary class states the consequence directly at `AbstractMatchPlanStep.java:66-69`.
- **Verdict**: CONFIRMED (the guard is sticky; the track's option is not realizable as a state-machine relaxation)

#### C16 Premise: `InternalExecutionPlan.copy(ctx)` yields a restartable plan
- **Track claim**: none — this is the viable realization for item 1.
- **Search performed**: grep for `copy(` in `SelectExecutionPlan`.
- **Code location**: `SelectExecutionPlan.java:238-272`
- **Actual behavior**: `copy(CommandContext ctx)` builds a new plan and chains `(ExecutionStepInternal) step.copy(ctx)` per step. Fresh step instances start with `alreadyClosed == false`.
- **Verdict**: CONFIRMED

#### C17 Premise: overriding `getSubSteps()` on a MATCH execution step is unprecedented
- **Track claim**: item 3 — the product-side option "collides with the plan's `### Constraints` 'Engine surface is preserved' bullet … `MatchFirstStep` is a MATCH execution step".
- **Search performed**: repo-wide grep `public.*List<ExecutionStep> getSubSteps` across all modules, excluding worktrees and build output.
- **Code location**: `HashJoinMatchStep.java:411`, `FilterNotMatchPatternStep.java:112`, `BackRefHashJoinStep.java:841`, `InvertedWhileHashJoinStep.java:338`, `CorrelatedOptionalHashJoinStep.java:211`, plus `InfoExecutionStep.java:38`
- **Actual behavior**: five steps in `sql/executor/match/` already expose a nested plan through `getSubSteps()`; `HashJoinMatchStep` uses exactly `return List.copyOf(buildPlan.getSteps());`. Three more steps (`ParallelExecStep:190`, `LetQueryStep:162`, `GlobalLetQueryStep:152`) expose sub-plans through `getSubExecutionPlans()`.
- **Verdict**: CONFIRMED
- **Detail**: the constraint's literal reading still forbids the edit; what changes is the justification available to the Phase-A writer. The proposed change conforms two outliers to a convention five siblings in the same package already follow.

#### C18 Premise: the Gremlin plan cache hands each traversal a private plan copy
- **Track claim**: none — relevant to whether item 1's copy-on-re-arm can corrupt a shared cached plan.
- **Search performed**: grep in `GremlinPlanCache`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPlanCache.java:83-87`, `:101-105`
- **Actual behavior**: `putInternal` stores `internal.copy(copyCtx)`; `getInternal` returns `result.copy(ctx)`. The boundary step never holds the cached instance itself.
- **Verdict**: CONFIRMED

#### C19 Premise: the translator's additive planner constructor reaches the same prefetch phase
- **Track claim**: none — needed to carry C12 from the SQL/GQL path onto the Gremlin path.
- **Search performed**: read of the `MatchPlanInputs` constructor and the shared `createExecutionPlan`.
- **Code location**: `MatchExecutionPlanner.java:500-533` (ctor), `:534-630` (createExecutionPlan phases 1–4)
- **Actual behavior**: the constructor only seeds fields (pattern, alias maps, AST lists, flags) and sets `promoteFilterRidsOnBuild = true`; planning runs through the same `createExecutionPlan`, whose Phase 4 calls `addPrefetchSteps` unconditionally. The only translator-specific requirement is `useCache = false`.
- **Verdict**: CONFIRMED

#### C20 Premise: a positional-parameter predicate disqualifies an alias from prefetch
- **Track claim**: none — checked because Track 5 binds predicate values as `?` slots, which could have made the indexed test behave differently from the unindexed one.
- **Search performed**: grep for `filterDependsOnContext`, read of the body.
- **Code location**: `MatchExecutionPlanner.java:964-976`
- **Actual behavior**: the check is `where.refersToParent()` or `where.toString().toLowerCase(ROOT).contains("$matched.")`. A positional parameter triggers neither, so `code = ?` stays prefetch-eligible.
- **Verdict**: CONFIRMED (the premise is false; both scan tests take the same prefetch path)

#### C21 Premise: `MatchPrefetchStep` holds the fetch sub-plan and hides it from introspection
- **Track claim**: none — `MatchPrefetchStep` is not named anywhere in the track file.
- **Search performed**: full read (131 lines).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchPrefetchStep.java:60-73`, `:105-118`
- **Actual behavior**: holds `private final InternalExecutionPlan prefetchExecutionPlan`, runs it in `internalStart` and stores the drained rows under `PREFETCHED_MATCH_ALIAS_PREFIX + alias`. Overrides `reset`, `internalStart`, `canBeCached`, `prettyPrint`, `copy` — neither `getSubSteps()` nor `getSubExecutionPlans()`. `prettyPrint` does inline the sub-plan, which is why `listener.planPrettyInCallback` would already show whether an index is used.
- **Verdict**: CONFIRMED

#### C22 Premise: the Maven pipeline can only run on a non-draft PR
- **Track claim**: item 4's option list implies undrafting or new machinery are the levers.
- **Search performed**: read of the workflow's trigger block and job list.
- **Code location**: `.github/workflows/maven-pipeline.yml:2-9`, `:25-27`
- **Actual behavior**: triggers are `workflow_dispatch`, `push` on `develop`, `pull_request`, `merge_group`. The draft guard sits on the `detect-changes` job's `if`, keyed on `github.event.pull_request.draft`, which is absent on a `workflow_dispatch` event.
- **Verdict**: CONFIRMED (a manual dispatch runs the full pipeline on the branch without undrafting)

#### C23 Premise: the failing plan-capture scenarios pin the translator kill-switch
- **Track claim**: implicit in items 2 and 3 — the contract questions are posed as if each test targets one known path.
- **Search performed**: read of each test body plus the `setTranslatorEnabled` helper and its call sites.
- **Code location**: `YTDBQueryMetricsStrategyTest.java:272-298`, `:303-332`, `:336-360`; helper at `:705-720`; existing pinned pairs at `:519-594` and `:596-668`
- **Actual behavior**: the three scenarios open a monitored transaction and run the traversal with no kill-switch call. Two test pairs in the same file pin it explicitly, with a comment explaining that branching on the installed step would make both contracts self-fulfilling.
- **Verdict**: PARTIAL
- **Detail**: the file establishes the pin-the-path convention and documents its rationale; these three scenarios predate it and do not follow it.

#### C24 Premise: the track's `~5 files` footprint covers the work
- **Track claim**: plan `## Checklist` — "**Scope:** ~5 files".
- **Search performed**: enumeration against the amended scope implied by T1 and T2.
- **Code location**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:86`
- **Actual behavior**: the In-scope list names four surfaces plus the CI gap. With `MatchPrefetchStep` (T1), `YTDBMatchPlanStep` and `MultiPlanMatchStep` (T2), and the two boundary unit-test classes (T5), the realistic set is 8–9 files.
- **Verdict**: PARTIAL
- **Detail**: still inside the ~12 merge-candidate bound, so the track's shape is unaffected; only the number is stale.

#### E1 Edge case: `toList()` on a translated traversal drives the boundary step to `CLOSED`
- **Trigger**: `traversal.toList()` on a recognised traversal, followed by `admin.reset()` and a second `toList()`.
- **Code path trace**:
  1. Entry: `Traversal.toList()` → `fill(...)`, which per the fork's contract closes the traversal in a `finally` (`CloseableIterator.closeIterator`).
  2. `Traversal.close()` closes every `AutoCloseable` step → `AbstractMatchPlanStep.close()` @ `AbstractMatchPlanStep.java:523`.
  3. `close()` sets `state = CLOSED` and, having already drained, calls `closePlan()` @ `:531-538` → `plan.close()`.
  4. `admin.reset()` → `AbstractMatchPlanStep.reset()` @ `:506` — `CLOSED` matches neither `OPEN` nor `DRAINED`, so the state is unchanged.
  5. Second `toList()` → `processNextStart()` @ `:238` sees `CLOSED` and throws `FastNoSuchElementException` immediately.
- **Outcome**: the second run yields `[]`, matching the reported `expected 4 rows, got []`.
- **Track coverage**: yes — item 1 states exactly this chain.
- **Verdict**: PARTIAL
- **Detail**: steps 2–5 are read directly from the source. Step 1 rests on the class-level Javadoc at `AbstractMatchPlanStep.java:66-78` describing TinkerPop's close-on-exhaustion behaviour, because the `io.youtrackdb` TinkerPop fork's `Traversal.java` is not present in the working tree or as a local sources jar. The observed test failure is consistent with it.

#### E2 Edge case: naive `CLOSED → REARMED` re-arm leaks the plan's cursors
- **Trigger**: item 1's product-side option implemented by extending `reset()` to accept `CLOSED`.
- **Code path trace**:
  1. `close()` already ran: `plan.close()` → `SelectExecutionPlan.close()` @ `SelectExecutionPlan.java:76` → `lastStep.close()` → `AbstractExecutionStep.close()` @ `:109` sets `alreadyClosed = true` on every step, propagating backward.
  2. `reset()` maps `CLOSED → REARMED` (hypothetical edit).
  3. `processNextStart()` @ `AbstractMatchPlanStep.java:242` calls `openArming()`.
  4. `openArming()` @ `:427-429` calls `rewindPlan(ctx)` → `plan.reset(ctx)` → `SelectExecutionPlan.reset` @ `:107` → per-step `ExecutionStepInternal.reset()`, default no-op; `alreadyClosed` is untouched.
  5. `startPlanStream()` → `plan.start()` → `lastStep.start(ctx)` re-runs every `internalStart`, re-acquiring cursors.
  6. The next `close()` short-circuits at every step's `alreadyClosed` guard.
- **Outcome**: the second run's cursors are never released. Silent resource leak, not a visible failure.
- **Track coverage**: no. Item 1 presents "re-arm from `CLOSED`" without naming the plan-restart problem, and `## Interfaces and Dependencies` lists only `AbstractMatchPlanStep`.
- **Verdict**: CONFIRMED (see T2)

#### E3 Edge case: union re-arm after close
- **Trigger**: the same fix applied to a `union(...)` traversal.
- **Code path trace**:
  1. `MultiPlanMatchStep.closePlan()` @ `MultiPlanMatchStep.java:402-430` closed all N child plans.
  2. A `CLOSED → REARMED` transition would call `rewindPlan` @ `:247-255`, resetting each child — again without clearing any child's sticky guard.
  3. `startPlanStream()` @ `:258` re-opens each child through the lazy producer.
- **Outcome**: the leak multiplies by child count. A correct copy-on-re-arm must rebuild all N children against fresh isolated contexts, mirroring `clone()` @ `:157-212` including its template-context invariant assert.
- **Track coverage**: no — `MultiPlanMatchStep` is absent from the In-scope list.
- **Verdict**: CONFIRMED (folded into T2)

#### E4 Edge case: an immutable `getSubSteps()` breaks step deserialization
- **Trigger**: `ExecutionStepInternal.basicDeserialize` calls `step.getSubSteps().add(subStep)` @ `ExecutionStepInternal.java:230`.
- **Code path trace**:
  1. `basicDeserialize` is reached only from a concrete `deserialize(...)` override.
  2. `MatchFirstStep` and `MatchPrefetchStep` override neither `serialize()` nor `deserialize()`, so the interface defaults throw `UnsupportedOperationException` first (`:172-186`).
- **Outcome**: unreachable. The five precedent MATCH steps return immutable lists and have the same property.
- **Track coverage**: not applicable — noted so the Phase-A decision is not blocked on a phantom risk.
- **Verdict**: CONFIRMED (unreachable for both target steps)

#### E5 Edge case: the existing reset scenario passes over a zero-row second run
- **Trigger**: `queryFinishedFiresAgainAfterResetAndReExecution` under the translator.
- **Code path trace**:
  1. First execution drains and closes; listener count 1.
  2. `admin.reset()` — the boundary stays `CLOSED` per E1; `YTDBQueryMetricsStep.reset()` @ `YTDBQueryMetricsStep.java:198-209` clears `hasStarted` / `closed`.
  3. Second loop: `traversal.hasNext()` → `YTDBQueryMetricsStep.hasNext()` @ `:112` calls `queryHasStarted()` before `super.hasNext()`, so `hasStarted = true` even though the boundary yields nothing.
  4. `traversal.close()` → `YTDBQueryMetricsStep.close()` @ `:150-158` passes the `!hasStarted || closed` guard and fires the listener; count 2.
- **Outcome**: `assertThat(invocationCount.get()).isEqualTo(2)` holds while the second execution returned zero rows. The scenario is green over the exact defect item 1 exists to fix.
- **Track coverage**: no — `## Validation and Acceptance` requires all 20 scenarios to pass but does not require this one to gain a row-count assertion.
- **Verdict**: CONFIRMED (see T5)

#### I1 Integration: `getSubSteps()` overrides and the plan-introspection consumers
- **Plan claim**: item 3 treats a `getSubSteps()` override as a pure-introspection change with no execution behaviour.
- **Actual entry point**: `ExecutionStep.toResult()` @ `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/ExecutionStep.java:35-47`, reached from `SelectExecutionPlan.toResult` @ `:179-189` and `ExplainResultSet` @ `sql/parser/ExplainResultSet.java:56`.
- **Caller analysis** (grep-based; PSI unavailable): recursive consumers are `ExecutionStep.toResult` and `ExecutionStepInternal.basicSerialize` / `basicDeserialize`. Non-recursive consumers that would be unaffected: the tx-result-cache splice scans in `DatabaseSessionEmbedded` (`:1130`, `:1231`, `:1295`, `:1331`), `YTDBGraphQuery.usedIndexes` (`:44-62`, top-level plus one `GlobalLetQueryStep` hop), and `SelectExecutionPlanner:1749` / `:3130`. No test asserts on MATCH `subSteps` content — the six MATCH explain test classes all assert on `prettyPrint` strings, which do not change because both target steps already inline their sub-plan there.
- **Breaking change risk**: low. `EXPLAIN` result documents for MATCH plans gain nested `subSteps` entries; `prettyPrint` output is byte-identical.
- **Verdict**: MATCHES
