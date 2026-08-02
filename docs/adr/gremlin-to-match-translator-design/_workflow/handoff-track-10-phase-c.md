<!-- workflow-sha: c2f43f01ec -->
# Handoff — Track 10, Phase C iteration 1

Paused at context `warning` (40%) with Phase C iteration 1 reviewed and **22 real test failures introduced by this track still open**. Resume with a fresh session; everything needed is on disk and committed.

## Where the track stands

Phase B is complete: six steps, all `[x]`, all committed. Phase C iteration 1 has run — five dimensional reviewers, 30 findings, no blockers. The review files are committed under `plan/track-10/reviews/`. The track is **not** closable as-is.

## The blocking problem

The full `core` suite at `a4fad97c1b` fails with 16 failures and 6 errors in the `sequential-tests` execution. The step-1 baseline (`plan/track-10/core-test-failure-inventory.md`) recorded exactly four failures, all `YTDBQueryMetricsStrategyTest` scenarios, and those four are now fixed. **These 22 are new and this track caused them.** The run also stopped at 965 of the execution's 2220 tests, so the list below is a floor, not a total.

Two clusters, matching two of the five steps.

**By-id and class scoping — step 5.** `YTDBHasLabelProcessTest.testByIdHasLabelSiblingClassDoesNotMatch`, five `HasTest` cases (`g_VX1X_out_hasXid_2X`, `..._hasXid_2_3X`, `..._inList`, and the two `AsString` variants), `PartitionStrategyProcessTest.shouldWriteToMultiplePartitions`, `SubgraphStrategyProcessTest.shouldFilterVertexCriterion`. Review finding **BG8** predicted exactly this and is confirmed by the failures: static-RID promotion replaces the alias's class target with the pinned RID list, so a translated `hasId` stops being class-scoped and diverges from native. `bugs-iter1.md`, anchor `### BG8 `.

**Property projection — step 6.** `MeanTest.g_V_age_mean`, `SumTest.g_V_foo_sum`, `OrderTest.g_V_name_order`, `GroupTest.g_V_group_byXageX`, `GroupCountTest`, `ElementMapTest.g_V_elementMap`, two `ValueMapTest` `withXtokensX` cases, two `PropertiesTest` cases, `SelectTest.g_V_asXaX_selectXaX_byXageX`, two `AndTest` cases, one `WhereTest` case. None involve RIDs, so step 5's promotion is not the obvious cause; the `SQLSuffixIdentifier` `$`-prefixed projection probe from step 6 is. Findings **PF4** and **TS10** both circle it — PF4 says `isProjection()` does not actually stop record dispatch and `ResultInternal.hasProperty` can issue a per-row lazy load; TS10 says the test that claims to pin the gate stays green when the gate is deleted.

## What the next session should do

1. Reproduce with `./mvnw -pl core test` and get the complete failure list — the aborted run under-reports.
2. Fix the step-6 projection cluster first. It is the larger group and the least understood; TS10 says its only guard is not load-bearing, so write a falsifiable test before changing code.
3. Fix BG8. The review file carries the mechanism and a suggested direction.
4. Re-run the full suite to a clean green before touching anything else.
5. Then work the remaining Phase C findings and re-run the gate check.

## Phase C findings, iteration 1

Thirty findings, no blockers, 17 should-fix. All bodies are anchored `### <ID> ` in the named files under `plan/track-10/reviews/`.

| File | Findings |
|---|---|
| `bugs-iter1.md` | BG8, BG9 should-fix; BG10 suggestion |
| `code-quality-iter1.md` | CQ1–CQ5 should-fix; CQ6–CQ8 suggestion |
| `test-quality-iter1.md` | TC1, TC2, TC3, TB1 should-fix; TC4, TC5, TB2, TB3, TC6 suggestion |
| `performance-iter1.md` | PF1, PF2, PF3 should-fix; PF4 suggestion |
| `test-structure-iter1.md` | TS10, TS11, TS12 should-fix; TS13, TS14, TS15 suggestion |

Two findings converge on the same defect from different dimensions and should be fixed together: **BG9** and **TC1** both say the duplicate-RID dedupe key in `toPromotedSqlRidList` folds a 96-bit RID into 64 bits, so two distinct RIDs can collide and the second is silently dropped. That is a defect in the fix committed as `a4fad97c1b`.

**PF1** is worth reading before any by-id work: `addStepsFor` prefers class over pinned RIDs, so a RID-bearing edge pattern with 100 or more ids still compiles a full `V` scan — step 5 did not close that path.

## State to carry forward

- Track base after the 2026-08-02 rebase: **`f007749249`**. The SHA in the track file's `## Base commit` (`fd9eb7635d`) is pre-rebase and stale; the discrepancy note is already recorded there.
- Branch is rebased onto `origin/develop` (`c2f43f01ec`) and **has diverged from its remote — not pushed since the rebase.** Pushing needs `--force-with-lease`.
- Plan of Work item 4 is withdrawn (DR-M5): the `maven-pipeline.yml` change was reverted at the user's request, so the CI detection hole this track was created partly to close is still open. `## Validation and Acceptance` no longer has a step covering it.
- `review-bugs` flagged `DIFF_STALE` on its run: the staged patch stopped one commit short and omitted `a4fad97c1b`, so the dedupe was reviewed from the working tree rather than the patch. Regenerate the staged diff before the next fan-out.
- PSI was unavailable throughout (`steroid_execute_code` exceeds the 60-second MCP timeout in this repository), so every symbol claim in the review files is grep-derived and caveated.

## One process note worth acting on

This session lost substantial time to the working tree being moved underneath it three times — onto `both-pre-filter-support` mid-step-2, onto `index-ordered-match` later, and an unrelated YTDB-820 stash popped into the checkout leaving three files with conflict markers that broke the build. A second agent or session was operating in the same directory. Track 10's remaining work should run in a dedicated worktree, or with the main checkout reserved.
