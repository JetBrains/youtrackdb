<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 10: Query-metrics regression remediation — restore a green `core` unit-test run

## Purpose / Big Picture
After this track, `./mvnw -pl core test` passes on this branch again. Four `YTDBQueryMetricsStrategyTest` scenarios have been failing since 2026-07-16, and the branch has carried a red core unit-test run for 117 commits without anyone noticing, because PR #1038 is a draft and every CI check reports `skipping`. The failures are not a test-harness artefact: one is a genuine behavioural divergence between the translated and native paths, one is an unrevised contract, and two report that a MATCH plan hides its fetch steps from `ExecutionStep` introspection — with the open question of whether the index is still used at all.

This track is inserted ahead of Track 9 by the inline replan of 2026-08-01. Track 9's Cucumber-green and JMH-baseline goals both assume a green starting point; running them against a red suite would blur real regressions into pre-existing noise.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

**Numbering note.** This track executes **before** Track 9 despite the higher number. Renumbering Track 9 was rejected: Track 8's file, its Decision Log (DR-U4), and its cross-track hints all name "Track 9" for the list-shaping terminators, and renumbering would falsify every one of those references. A cosmetic gap in execution order is cheaper than stale cross-references. The `## Checklist` entry is placed before Track 9's so the first-`[ ]` walk selects this track next.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

- **Discovered during Track 8 Phase C (2026-08-01), by independent verification rather than by the implementer that hit it.** Iteration 3 reported the four failures as "pre-existing at the track base, not caused by this iteration" and moved on. A verification run in a separate worktree returned `BRANCH-REGRESSION` and showed the report was factually wrong, not merely under-determined: the track base carries **six** problems, not four, and a different set; `byIdLookupSurfacesNullPlan` **passes** at the track base and was broken by Track 8's own `3d476357cc`. The matching count of four was a coincidence. Three of the tests are byte-identical on `develop` and on the branch, so the "revert the test file and re-run" check the implementer described could not have changed their outcome.
- **Second instance of the DR-U6 pattern on this branch.** The `GraphApiTest` rollback/count failure in Track 8 Step 2 had the same shape: reproduced at the track base, passed on `develop`, turned out branch-introduced and needed a real fix. "Reproduces at the track base" has now twice meant "branch-introduced, further upstream than you looked". Treat that phrase as a prompt to bisect against `develop`, not as an all-clear.

## Decision Log
<!-- Continuous-log. -->

- **DR-M1 — remediation is its own track, not a Track 8 iteration.** The root cause spans two tracks: `6e657ce2b1` (Track 4 era) broke five scenarios, and Track 8's `3d476357cc` repaired three and broke a fourth. Folding the fix into Track 8's Phase C would attribute Track 4's defect to Track 8's diff and leave the cumulative review scope misleading. A dedicated track keeps the attribution honest and gives the introspection question room for its own decomposition.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

`YTDBQueryMetricsStep.capturedExecutionPlan()` originally read the execution plan off `YTDBGraphStep`. Commit `6e657ce2b1` ("Translate has/hasLabel/hasId and has(key) to MATCH") made `g.V().hasLabel("person")` translate, so a translated traversal no longer carries a `YTDBGraphStep` and the capture began reporting `null`. That commit broke five scenarios and changed no test file. Track 8's `3d476357cc` ("Surface MATCH plans to query metrics; pin range semantics") then taught the capture to read the MATCH boundary, repairing three, and broke `byIdLookupSurfacesNullPlan` in the process.

The four failures at the branch tip, all assertion failures with no errors:

1. **`resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults`** — expected 4 rows, got `[]`. `toList()` closes the traversal (`fill()` closes in a `finally`), driving `AbstractMatchPlanStep` to `CLOSED`; `reset()` re-arms only from `OPEN`/`DRAINED` and `CLOSED` is terminal by design, so `processNextStart()` throws `FastNoSuchElementException`. The sibling `resetWithoutTranslator_…` passes, so this is a real divergence between the MATCH boundary step and `YTDBGraphStep`. Either the step's lifecycle or the test's expectation is wrong; deciding which is the substantive work.
2. **`byIdLookupSurfacesNullPlan`** — `expected: null but was: SelectExecutionPlan@…`. `g.V(rid)` now translates via `StartStepRecogniser.normaliseIds` and the plan is surfaced. Arguably the better behaviour; the develop-era contract was simply never updated.
3–4. **`planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep`** and **`indexedQuerySurfacesPlanWithFetchFromIndexStep`** — the plan is non-null and non-empty, but `containsStepOfType(…, FetchFromClassExecutionStep)` and `(…, FetchFromIndexStep)` both return false. `MatchFirstStep` overrides neither `getSubSteps()` nor `getSubExecutionPlans()`, so a nested fetch plan is invisible to `ExecutionStep` introspection. **Whether the index is still used was not established** — `listener.planPrettyInCallback` would show it, but nothing asserts on it, and this is the only Gremlin-level index-usage assertion in the tree.

**Why it went unnoticed.** The class is `@Category(SequentialTest)` and `core/pom.xml` binds the `sequential-tests` surefire execution to the `test` phase, so a plain `./mvnw -pl core test` is red. PR #1038 is a draft and every CI check reports `skipping`. Track 2's Step 5 record shows the gap directly: metrics were verified to "survive translation" by a *new* smoke test covering `getQuerySummary` / `getQuery` / count — never `getExecutionPlan()` — and the existing class was skipped as "cannot run standalone via `-Dtest` … needs full-suite context".

**How to run the class** (it has no standalone `-Dtest` entry point):

```
GREMLIN_TESTS=com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBQueryMetricsStrategyTest \
  ./mvnw -pl core -o test -Dtest=YTDBProcessTest -DfailIfNoTests=false
```

**Reference-accuracy note.** The bisection rests on green/red brackets from real test runs, not on symbol search. Commits #66–#130 of the branch do not compile (`MatchExecutionPlanner` assigns `this.aliasRids` with no field declaration, a rebase artefact introduced by `6c3f474964` and removed by `bcd3b64c06`); the bisection bracketed around that window, which is sound because #131 is green. The `3d476357cc` attribution rests on #286-passes / tip-fails plus that commit being the sole intervening change to `YTDBQueryMetricsStep.java`; that commit was not run in isolation.

## Plan of Work

Establish what the correct contract is before changing code — for three of the four failures the question "is the test wrong or the product wrong?" is genuinely open, and answering it wrongly bakes in the mistake. Then fix, then close the hole that let a red suite ride for 117 commits.

1. **Settle the `reset()` contract.** Decide whether `AbstractMatchPlanStep` should re-arm from `CLOSED` (matching `YTDBGraphStep`, which re-executes fine) or whether the test's expectation is wrong. This is the one genuine product defect; the decision needs a Decision Record either way.
2. **Settle the `byId` capture contract.** `g.V(rid)` surfacing a plan is plausibly an improvement; update the test to the intended contract, or suppress the capture for that shape.
3. **Decide the introspection question, and first answer whether the index is still used.** If `MatchFirstStep` should expose its nested plan through `getSubSteps()` / `getSubExecutionPlans()`, that fixes both scan tests and restores the only Gremlin-level index-usage assertion in the tree. If the index is *not* being used, this is a performance defect well beyond a test fix and needs its own escalation.

   **Phase A owes an explicit decision here, and the default is not the safe one.** The product-side option collides with the plan's `### Constraints` "Engine surface is preserved" bullet, which freezes "the execution steps" with only two named exceptions (D-TEXT-OPS AST nodes, the count short-circuit refactor) — and `MatchFirstStep` is a MATCH execution step. Read literally the constraint forbids the override, so a decomposer that treats it as binding picks the test side silently and the branch permanently loses the assertion Track 10 exists to restore. Phase A must choose in writing between (a) amending the Constraints bullet with a third exception, on the grounds that sub-step / sub-plan introspection adds no execution behaviour, and (b) closing the two scan tests test-side and recording why the freeze outweighs the assertion. Picking (b) by default, without recording the trade, is an ESCALATE.
4. **Close the detection hole.** A red `core` unit-test run must not survive 117 commits again. Options to weigh: undraft PR #1038, or add a cheap always-on check, or both.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- One block per completed step. Empty until Phase B. -->

## Validation and Acceptance
- `./mvnw -pl core test` passes on the branch.
- All 20 `YTDBQueryMetricsStrategyTest` scenarios pass, run both in isolation and in a multi-class run.
- `origin/develop` parity: no scenario that passes on develop fails here.
- The index-usage question from step 3 is answered explicitly, not left open.

## Idempotence and Recovery
Each step is a self-contained test-or-product fix; a failed step leaves the suite no worse than the current red state.

## Artifacts and Notes
Full investigation evidence — the commit-by-commit bracket table, the isolation-dependence disproof, and the CI-wiring analysis — is in Track 8's `## Surprises & Discoveries` entry dated 2026-08-01.

## Interfaces and Dependencies
**In scope:** `YTDBQueryMetricsStep.capturedExecutionPlan()`; `AbstractMatchPlanStep` lifecycle if step 1 lands on the product side; `MatchFirstStep` introspection overrides if step 3 lands on the product side; `YTDBQueryMetricsStrategyTest`; the CI/draft-PR detection gap.

**Out of scope:** union and list-shaping work (Tracks 8 and 9); the translator's recognition surface; any change to what translates.

**Inter-track dependencies:** depends on Track 8 (complete). **Runs before Track 9** — Track 9's Cucumber-green and JMH-baseline goals both assume a green starting point.

**Signatures:** `YTDBQueryMetricsStep.capturedExecutionPlan()`; `AbstractMatchPlanStep.reset()` / `processNextStart()`; `ExecutionStepInternal.getSubSteps()` / `getSubExecutionPlans()` (the default implementations `MatchFirstStep` inherits); `YTDBQueryMetricsStrategyTest.containsStepOfType` (a private test helper, not an `ExecutionStep` member — it recurses through `getSubSteps()` only, so overriding `getSubExecutionPlans()` alone would not make it find a nested fetch step); `StartStepRecogniser.normaliseIds`.

## Invariants & Constraints
- Do not change what translates in order to make a metrics test pass. If the correct fix would narrow translator coverage, that is an ESCALATE, not a step.
- Multiset equality with the native path stays the contract, as everywhere else on this branch.
- The plan's "Engine surface is preserved" constraint bears on step 3 and Phase A must resolve it explicitly rather than defaulting to the test side — see that step for the two admissible resolutions and the ESCALATE condition.

## Base commit
<!-- Phase B records the HEAD SHA here at session start; Phase C reads it to compute the
cumulative track diff (conventions-execution.md §2.1 §15). Added by workflow migration (#1145). -->
