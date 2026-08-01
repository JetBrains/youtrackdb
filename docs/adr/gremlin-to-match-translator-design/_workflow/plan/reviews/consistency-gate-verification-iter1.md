<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: CR6, sev: should-fix, loc: "plan/track-9.md:52", anchor: "### CR6 ", cert: C28, basis: "the CR2 fix names UnionStepRecogniser as the home of the post-union suffix allow-list in four places; the allow-list is GremlinStepWalker.POST_UNION_RECOGNISERS"}
  - {id: CR7, sev: suggestion,  loc: "implementation-plan.md:617", anchor: "### CR7 ", cert: C29, basis: "the CR5 fix reached plan/track-9.md but not the checklist entry, whose Depends-on line still reads Tracks 7 and 8"}
verdicts:
  - {id: CR1, verdict: VERIFIED}
  - {id: CR2, verdict: REGRESSION}
  - {id: CR3, verdict: VERIFIED}
  - {id: CR4, verdict: VERIFIED}
  - {id: CR5, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 2, matches: 0}
cert_index:
  - {id: C28, verdict: MISMATCHES, anchor: "#### C28 "}
  - {id: C29, verdict: MISMATCHES, anchor: "#### C29 "}
flags: [CONTRACT_OK]
-->

# Consistency gate verification — iteration 1 (2026-08-01, 10-track plan)

All five iteration-1 fixes landed in the text. CR1, CR3, CR4 and CR5 are clean
at their flagged locations and in the sections around them. The CR2 fix closes
the scope contradiction — Track 9 now owns the post-union relaxation in its
Plan of Work, acceptance lines, scope lists and the plan checklist — but it
attributes the allow-list to `UnionStepRecogniser` in four places, and the
allow-list is `GremlinStepWalker.POST_UNION_RECOGNISERS` (CR6, should-fix). The
CR5 fix reached `plan/track-9.md` but not the plan checklist's Track 9
`**Depends on:**` line, which still omits Track 10 (CR7, suggestion). No
blockers, so the gate is PASS.

`design.md` is untouched by the fix set — `git status` shows four modified
files, none of them the design — so the freeze held.

**Reference-accuracy caveat.** mcp-steroid PSI (`steroid_execute_code`) times
out in this repo, because cold kotlinc exceeds the ~60 s MCP limit; the
iteration-1 reviewer hit the same wall. Every symbol fact below rests on grep
plus direct source reads. The one negative result that drives a verdict (no
`containsStepOfType` on `ExecutionStep`) was confirmed by reading the 45-line
interface end-to-end, so a polymorphic or Javadoc miss is not possible there.
CR6's positive result — the allow-list is a field on `GremlinStepWalker` —
rests on reading the declaration and both of its two read sites, not on search
alone.

## Verification certificates

#### Verify CR1: Implementation state stale on Track 8, silent on Track 10
- **Original issue**: `## Implementation state` said "Tracks 1-7 are executed and complete; Track 8 Phase B is complete (Phase C pending)", table row 8 read `Phase B done`, the decision-conformance paragraph said "Track 8 Phase C still open", and Track 10 appeared nowhere in the section.
- **Fix applied**: narrative rewritten to "Tracks 1–8 are executed and complete; Track 10 and Track 9 are not started, in that execution order", with the Track 8 sentence de-scoped from "Phase B delivered" to "delivered" and a Track 10 sentence appended; row 8 set to `done` with the "Phase C pending" note dropped; a `| 10 | not started | … |` row inserted above row 9; the conformance sentence changed to "…across Tracks 7–8 and complete."
- **Re-check**:
  - Search/trace performed: Read of `implementation-plan.md:619-638`; `grep -n "Track 10\|Track 9"` over the plan; `grep -n "Phase C"` over the plan; `grep -n "^- \[.\] Track 8"`; `tail` of `_workflow/phase-ledger.md`. Grep and Read only — PSI unavailable, and none of this is a Java symbol question.
  - Code location: `implementation-plan.md:621` (narrative), `:632-634` (table rows 8, 10, 9), `:636` (conformance).
  - Current state: the three signals the finding cited now agree. Checklist line 551 marks Track 8 `[x]`; `phase-ledger.md` records `phase=C track=8 substate=track-complete` at 2026-08-01T05:23Z; the table says `done`. Track 10 has both a narrative sentence and a table row, placed above Track 9 to match execution order.
- **Regression check**: checked the five remaining "Phase C" hits in the plan (lines 459, 477, 503, 566, 593) — all are completed-track episode prose or Track 10's rationale, none a status claim. Checked for stale track-count phrasings (`grep` for "nine track", "ten track", "10-track", "eight track") — no hits. The table keeps three columns throughout. Clean.
- **Verdict**: VERIFIED

#### Verify CR2: Track 8 assigns the post-union relaxation to Track 9, which excluded it
- **Original issue**: `track-8.md` DR-U4 and its Surprises entry hand Track 9 the job of relaxing the post-union suffix gate for the list-shaping terminators; `track-9.md` listed `union` / `MultiPlanMatchStep` as out of scope, carried no matching Plan-of-Work item, and had no `union(...).fold()` acceptance line.
- **Fix applied** (user resolution: Track 9 absorbs the relaxation): new Plan-of-Work item 4 citing DR-U4 and DR-U1, old items 4–6 renumbered 5–7; a `union(...)` multiset-parity acceptance line; `**Out of scope:**` narrowed with an explicit carve-out; `**In scope (modified):**` and `**Signatures:**` extended; the plan checklist gained a relaxation sentence and its scope figure moved ~14–20 → ~15–21 files.
- **Re-check**:
  - Search/trace performed: `git diff -U1` on all three edited files; Read of `plan/track-9.md` end-to-end; Read of `UnionStepRecogniser.java:18-62` and `:112-139`; `grep -rn "postUnionSuffixTranslatable\|POST_UNION"` over the translator strategy package; Read of `GremlinStepWalker.java:183-197`, `:312-329`, `:455-467`; Read of `GremlinToMatchStrategy.java:538-558`; Read of `WalkerContext.pinBoundary`.
  - Code location: `plan/track-9.md:52` (item 4), `:66` (acceptance), `:82-83` (scope), `:85` (signatures); `implementation-plan.md:605-616` (checklist).
  - Current state: the scope contradiction is gone. Track 9 scopes the relaxation, states the multiset obligation ("`fold()` after a union yields one list over the concatenated child streams, not one list per child"), and its out-of-scope line carves the allow-list back in instead of excluding it. Item 4's mechanism claim checks out against shipped code: `buildResult` passes `ctx.shaping()` into the multi-plan `TranslationResult` (`GremlinStepWalker.java:466`) and the strategy hands it to `MultiPlanMatchStep` (`GremlinToMatchStrategy.java:554`), so the list-shaping ops Track 9 registers do ride the same carrier over a union, applied once over the `MultipleExecutionStream` concatenation. `pinBoundary` is an unguarded three-field assignment, so a post-union `fold` can re-pin `outputType` to `LIST` — the relaxation is implementable as written.
  - Where it breaks: the four new text sites all name `UnionStepRecogniser` as the allow-list's home. The allow-list is `private static final Set<StepRecogniser> POST_UNION_RECOGNISERS` on `GremlinStepWalker` (`:193-197`), read by `dispatchAll` (`:322`) and by `postUnionSuffixTranslatable` (`:370-380`). `UnionStepRecogniser` only calls through the `UnionForkHost` seam (`:69`). See C28 and CR6.
- **Regression check**: checked `track-8.md`'s two statements of the assignment. Line 39 ("Track 9 relaxes this … (DR-U4)") is now correct. The Step 3 episode's "Track 9 **may** relax the 'union is last step' rule" understates a now-committed scope item, but Track 8 is `[x]` and that line is a completed track's historical record; "may" is weaker than reality without contradicting it. Not raised. Checked `track-9.md`'s Purpose and Context sections — neither claims union is out of scope. Checked whether the relaxation forces a `MultiPlanMatchStep` edit that the out-of-scope line forbids: it does not, because shaping already flows through the shared base.
- **Verdict**: REGRESSION — the original contradiction is resolved, and the fix text introduced a mechanical misattribution (CR6, should-fix).

#### Verify CR3: `ExecutionStep.containsStepOfType` does not exist
- **Original issue**: Track 10's `**Signatures:**` line named `ExecutionStep.containsStepOfType`; the symbol is a `private static` helper in the test class, and `getSubExecutionPlans()` was attributed to `ExecutionStep` rather than `ExecutionStepInternal`.
- **Fix applied**: the signatures line now reads `ExecutionStepInternal.getSubSteps()` / `getSubExecutionPlans()` ("the default implementations `MatchFirstStep` inherits") plus `YTDBQueryMetricsStrategyTest.containsStepOfType`, annotated as a private test helper that recurses through `getSubSteps()` only.
- **Re-check**:
  - Search/trace performed: `grep -n "containsStepOfType"` over `YTDBQueryMetricsStrategyTest.java`; `grep -n "getSubSteps\|getSubExecutionPlans"` over `ExecutionStepInternal.java`; member listing of `ExecutionStep.java`. Grep plus Read; PSI unavailable.
  - Code location: helper declared at `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:1618`, recursing at `:1623` through `step.getSubSteps()`, called at `:292`, `:295`, `:329`. Defaults at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/ExecutionStepInternal.java:145` and `:150`.
  - Current state: every symbol the line now names resolves to its declaring type. `ExecutionStep` declares `getName`, `getType`, `getDescription`, `getSubSteps`, `getCost` and `toResult` — no `containsStepOfType`, no `getSubExecutionPlans`.
- **Regression check**: checked the surrounding prose the finding called imprecise. Track 10's failure 3–4 narrative (`:43`) still says a nested fetch plan is "invisible to `ExecutionStep` introspection", which holds as written now that the signatures line pins where each method lives. Concrete step 3 (`:65`) still offers `getSubSteps()` / `getSubExecutionPlans()` as a pair, and the signatures line in the same file now carries the caveat that only a `getSubSteps()` override reaches the existing helper, so the decomposer gets the precision point from the file it is already reading. Clean.
- **Verdict**: VERIFIED

#### Verify CR4: query-metrics integration point unrecorded in the plan
- **Original issue**: neither `### Integration Points` nor the Component Map recorded the boundary-step → query-metrics integration that Track 10 exists to repair.
- **Fix applied**: a fifth Integration Points bullet describing `YTDBQueryMetricsStep.capturedExecutionPlan()`. The optional Component Map node was not added.
- **Re-check**:
  - Search/trace performed: Read of `implementation-plan.md:352-365` and of the Component Map at `:62-118`; Read of `YTDBQueryMetricsStep.java:82-109`.
  - Code location: `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java:91-109`.
  - Current state: the bullet matches the method hop for hop — `YTDBMatchPlanStep` → `getPlan()` (`:92-96`), then `MultiPlanMatchStep` → first non-empty child plan (`:97-105`), then `YTDBGraphStep::getLastExecutionPlan` (`:106-108`). The Track 10 attribution is right.
  - Component Map, on the orchestrator's question: not a gap. The map's bullet legend scopes it to what the plan builds or refactors — new TinkerPop-side classes, the shared builder package, the preserved MATCH engine — plus the two existing consumers the translator hands work to (`Half`, `GQL`). The metrics step is neither built by this plan nor handed anything by it; it reads the boundary step opportunistically. A node for it would put a read-only observer into a producer graph, and the Integration Points bullet already gives Track 10's decomposer the call path and the file. The original finding marked the node "optional", and the reason it was optional still holds.
- **Regression check**: checked the four pre-existing Integration Points bullets for overlap or contradiction with the new one — none; the new bullet is the only mention of monitoring anywhere in the plan outside Track 10's entry. Formatting and line width match its neighbours. Clean.
- **Verdict**: VERIFIED

#### Verify CR5: Track 9 silent about Track 10 and still counting six prior tracks
- **Original issue**: `plan/track-9.md` listed only Tracks 7 and 8 as inter-track dependencies, never mentioned Track 10, and described itself as validating "all six prior tracks"; the plan checklist carried the parallel "all six tracks' recognisers".
- **Fix applied**: the `**Inter-track dependencies:**` line now names Track 10 with its rationale; line 5 reads "all prior tracks"; the checklist reads "every prior track's recognisers".
- **Re-check**:
  - Search/trace performed: `grep -rn "six\|all prior tracks\|every prior track"` over the plan and both pending track files; Read of `plan/track-9.md:5,84` and `plan/track-10.md:88`; Read of `implementation-plan.md:608-609`.
  - Code location: `plan/track-9.md:5` and `:84`; `implementation-plan.md:609`.
  - Current state: both directions of the dependency are recorded. Track 10 states "**Runs before Track 9**" (`track-10.md:88`); Track 9 states it depends on Track 10, "which restores a green `core` unit-test run". The only surviving "six" in either file is Track 10's Surprises entry about six failing problems at the track base, unrelated to track counts. "Last Phase 1 track" on `track-9.md:84` still holds, since Track 10 runs before it.
- **Regression check**: checked the plan checklist for the same asymmetry and found it — Track 9's `**Depends on:** Tracks 7 and 8.` (`:617`) was not updated alongside the track file. Raised as CR7. The rest of both entries is consistent: Track 10's entry states its own ordering and `**Depends on:** Track 8.`
- **Verdict**: VERIFIED

## Findings

### CR6 [should-fix]
**Certificate**: C28
**Location**: `plan/track-9.md:52` (Plan of Work item 4), `:82` (`**In scope (modified):**`), `:85` (`**Signatures:**`); `implementation-plan.md:606` (Track 9 checklist entry). Code at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:193-197`
**Issue**: The CR2 fix places the post-union suffix allow-list on `UnionStepRecogniser` in all four new text sites. The allow-list is a private field on `GremlinStepWalker`. A decomposer building Track 9's file roster from `**In scope (modified):**` opens the wrong class, and the scope figure counts the wrong file.
**Evidence**: `GremlinStepWalker.java:193-197` declares `private static final Set<StepRecogniser> POST_UNION_RECOGNISERS = Set.of(CountGlobalStepRecogniser.INSTANCE, RangeGlobalStepRecogniser.INSTANCE, DedupGlobalStepRecogniser.INSTANCE)`. Its Javadoc (`:188-191`) states the set "is read from two places, and both must read this one field": `dispatchAll`'s per-step gate at `:322` (`if (ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser)) return false;`) and the `postUnionSuffixTranslatable` look-ahead at `:370-380`. `UnionStepRecogniser`'s only involvement is the call at `:69` (`if (!host.postUnionSuffixTranslatable())`), which `UnionForkHostImpl:68-69` forwards to the walker. Two secondary corrections ride along. The set holds recognisers rather than step classes, and `RangeGlobalStepRecogniser` covers `limit` / `range` / `skip`, so "currently `count` / `limit` / `dedup`" understates it — it mirrors the shorthand in the code's own comment at `UnionStepRecogniser.java:126`, while DR-U4 carries the full list. And `GremlinStepWalker` is already an unavoidable edit for item 3, because a new recogniser needs a registry entry there (Track 6's `range` precedent is `Map.entry(RangeGlobalStep.class, …)` at `:163-164`), yet the walker appears nowhere in Track 9's modified-scope list.
**Proposed fix**: In all four sites, name `GremlinStepWalker.POST_UNION_RECOGNISERS` as the allow-list and `GremlinStepWalker` as the modified file — item 4 becomes something like "**Relax the post-union suffix allow-list** — add the four terminator recognisers to `GremlinStepWalker.POST_UNION_RECOGNISERS`, alongside the count / range / dedup recognisers it already holds (Track 8 DR-U4)". Add `GremlinStepWalker` (recogniser registry entries plus `POST_UNION_RECOGNISERS`) to `**In scope (modified):**`, and note that `UnionStepRecogniser`'s class Javadoc at `:24-29`, which says the list-shaping terminators "are not translated yet", must be updated in the same change.
**Classification**: mechanical
**Justification**: Current-state claim about where an existing symbol lives, with one unambiguous correct rendering read off the declaration and both of its call sites. The work Track 9 must do is unchanged; only the file and field it names change.

### CR7 [suggestion]
**Certificate**: C29
**Location**: `implementation-plan.md:617` — the Track 9 checklist entry's `**Depends on:**` line
**Issue**: The CR5 fix updated `plan/track-9.md`'s inter-track dependencies to name Track 10 but left the checklist entry's dependency line reading "Tracks 7 and 8". The asymmetry CR5 flagged now sits one level up, in the surface the orchestrator's track walk reads first.
**Evidence**: `implementation-plan.md:617` reads `> **Depends on:** Tracks 7 and 8.` while `plan/track-9.md:84` names Tracks 7, 8 and 10. Track 10's own entry records the ordering from its side (`:579` "**Runs before Track 9**", `:591` `**Depends on:** Track 8.`), so the plan states the ordering twice and the dependency list once.
**Proposed fix**: Change line 617 to `> **Depends on:** Tracks 7, 8, and 10 (green `core` baseline).`
**Classification**: mechanical
**Justification**: Current-state claim about the plan's own track ordering, already settled by the checklist's entry order and by both track files. One unambiguous rendering; no scope or goal changes.

## Evidence base

#### C28 Ref: the post-union suffix allow-list's declaring class
- **Document claim**: `plan/track-9.md:52`, `:82`, `:85` and `implementation-plan.md:606` locate the post-union suffix allow-list on `UnionStepRecogniser`.
- **Search performed**: `grep -rn "postUnionSuffixTranslatable\|POST_UNION\|postUnion"` over `core/.../gremlin/translator/strategy/`; Read of `GremlinStepWalker.java:183-197` and `:312-329`; Read of `UnionStepRecogniser.java:18-62`, `:112-139`; Read of `UnionForkHostImpl.java:68-69`. PSI unavailable (see caveat); the positive result rests on reading the declaration and both read sites.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:193-197`; read sites at `:322` and `:370-380`; seam at `UnionForkHostImpl.java:68`, reached from `UnionStepRecogniser.java:69`.
- **Actual signature/role**: `private static final Set<StepRecogniser> POST_UNION_RECOGNISERS` holding `CountGlobalStepRecogniser.INSTANCE`, `RangeGlobalStepRecogniser.INSTANCE`, `DedupGlobalStepRecogniser.INSTANCE`. `UnionStepRecogniser` holds no allow-list; it consults the walker's through `UnionForkHost` and describes the policy in its class Javadoc (`:24-35`).
- **Verdict**: MISMATCHES
- **Detail**: Drives CR6. The relaxation mechanism itself is sound — `buildResult` (`:466`) carries `ctx.shaping()` into the multi-plan result and `GremlinToMatchStrategy:554` hands it to `MultiPlanMatchStep`, so terminator ops apply once over the concatenation, and `WalkerContext.pinBoundary` (`:489-494`) permits the `outputType` re-pin a post-union `fold` needs.

#### C29 Ref: Track 9's dependency list in the plan checklist
- **Document claim**: `implementation-plan.md:617` — `**Depends on:** Tracks 7 and 8.`
- **Search performed**: `grep -n "Depends on:"` over `implementation-plan.md`; Read of the Track 9 and Track 10 checklist entries (`:578-617`); Read of `plan/track-9.md:84`.
- **Code location**: `implementation-plan.md:617`, against `plan/track-9.md:84`.
- **Actual signature/role**: the track file names Tracks 7, 8 and 10; the checklist entry names Tracks 7 and 8.
- **Verdict**: MISMATCHES
- **Detail**: Drives CR7. A residual from the CR5 fix rather than a fresh inconsistency — the ordering itself is recorded correctly in three other places.

## Summary

**PASS.** Four fixes verified clean (CR1, CR3, CR4, CR5). CR2's fix resolves the
scope contradiction it was raised for and introduces one mechanical
misattribution (CR6, should-fix). One residual from CR5 remains in the plan
checklist (CR7, suggestion). No blockers, so Phase 2 can close; both new
findings are text edits the orchestrator can apply without a further design
decision.
