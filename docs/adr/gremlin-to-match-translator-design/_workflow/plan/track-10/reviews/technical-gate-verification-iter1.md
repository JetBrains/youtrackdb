<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 2, suggestion: 0}
index:
  - {id: T9,  sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:43", anchor: "### T9 ", cert: "Verify T1", basis: "Context and Orientation items 3-4 still carry the disproved MatchFirstStep root cause that Plan of Work item 3 now contradicts"}
  - {id: T10, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:82", anchor: "### T10 ", cert: "Verify T4", basis: "The deferred plan Constraints amendment is stated only in prose; no acceptance criterion owns it, so Phase B can drop it silently"}
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: T5, verdict: VERIFIED}
  - {id: T6, verdict: VERIFIED}
  - {id: T7, verdict: VERIFIED}
  - {id: T8, verdict: STILL OPEN}
overall: FAIL
flags: [CONTRACT_OK]
-->

# Track 10 — technical review gate verification, iteration 1

Both blockers are fixed and technically correct against the source. T8's fix landed with the wrong step number: the Javadoc-revision criterion is conditioned on step 3 (the introspection question) instead of step 1 (the `reset()`-from-`CLOSED` contract), so it never fires for the change it exists to guard. Two residuals the applied fixes did not reach: `## Context and Orientation` items 3–4 still state the root cause T1 disproved, and the plan-`### Constraints` amendment T4 deferred to Phase B has no acceptance criterion holding it. All three are one-to-three-sentence edits; nothing here reopens a design question.

**Reference-accuracy caveat.** PSI `steroid_execute_code` was not attempted — the iteration-1 reviewer hit the known cold-kotlinc timeout in this repo. Every symbol result below is grep plus a direct read of the cited region. The verifications here are positive-existence checks (this line says X, this method has this signature), which grep resolves reliably; no verdict rests on a negative "no other caller exists" claim.

## Findings

### T9 [should-fix]
**Location**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:43` (`## Context and Orientation`, items 3–4)

**Issue**: The T1 fix rewrote `## Plan of Work` item 3 but left the orientation section's diagnosis untouched. Line 43 still reads: "`MatchFirstStep` overrides neither `getSubSteps()` nor `getSubExecutionPlans()`, so a nested fetch plan is invisible to `ExecutionStep` introspection." The first clause is true; the inference is the one C12 marked WRONG. At the cardinalities both scan tests run at, `MatchFirstStep` is built through the three-argument constructor with `subPlan = null` (`MatchExecutionPlanner.java:2089`) and the fetch lives in the `MatchPrefetchStep` chained ahead of it (`:4750`). There is no nested plan for `MatchFirstStep` to hide.

Item 3 now states the corrected mechanism, so the track file disagrees with itself across two sections. A decomposer reads `## Context and Orientation` before `## Plan of Work`, and the two texts do not cross-reference each other — nothing in item 3 says the orientation entry is superseded.

**Proposed fix**: Rewrite the second sentence of items 3–4 to name the prefetch path: the aliases are below `MatchExecutionPlanner.THRESHOLD`, so the fetch sits under `MatchPrefetchStep`, which overrides neither introspection method; `MatchFirstStep` carries no sub-plan on this path. One sentence, same location.

### T10 [should-fix]
**Location**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-10.md:82` (`## Validation and Acceptance`), against `implementation-plan.md:40-44`

**Issue**: The T4 fix commits the track to option (a) and states twice — item 3's decision paragraph and the third `## Invariants & Constraints` bullet — that Phase B amends the plan's `### Constraints` bullet with a third exception. Neither statement is an acceptance criterion. `## Validation and Acceptance` lists seven items covering the test suite, the index-usage answer, the close-then-reset case, the Mockito-proof assertion, and the Javadoc sweep; the Constraints amendment is not among them.

Deferring the edit itself is safe (see `#### Verify T4`), but nothing gates its arrival. If Phase B ships the overrides and forgets the plan edit, `implementation-plan.md` keeps asserting that the MATCH execution steps are unmodified except for the D-TEXT-OPS AST nodes and the count short-circuit — a claim the shipped diff falsifies. The plan feeds `design-final.md` and `adr.md`, which survive the squash-merge, so the inconsistency outlives the branch.

**Proposed fix**: Add one line to `## Validation and Acceptance`: the plan's "Engine surface is preserved" bullet carries a third exception naming the `MatchPrefetchStep` / `MatchFirstStep` introspection overrides as behaviour-neutral. Unconditional, since option (a) is now decided rather than open.

## Verification certificates

#### Verify T1: item 3 targets the wrong step
- **Original issue**: Item 3 named `MatchFirstStep` as the introspection-override target, but at the failing tests' cardinalities the fetch lives under `MatchPrefetchStep` and `MatchFirstStep` holds a null sub-plan, so the override leaves both scan tests red.
- **Fix applied**: `## Plan of Work` item 3 rewritten (track-10.md:64); `MatchPrefetchStep` added to `## Interfaces and Dependencies` In-scope (`:91`) and Signatures (`:97`).
- **Re-check**:
  - Location: track-10.md:64, :91, :97.
  - Current state: item 3 opens with the cardinality argument ("far under `MatchExecutionPlanner.THRESHOLD` (100), so their aliases are **prefetched**"), names `MatchPrefetchStep` primary and `MatchFirstStep` secondary in bold, gives the secondary's reason (the sub-plan arm is reachable above the threshold and Track 8's union / multi-node patterns use it), re-frames the index sub-question as "does the `+ PREFETCH` sub-plan contain a `FetchFromIndexStep`", and closes with "Assert the fix at both cardinalities, or state in the step why only the prefetched shape is covered."
  - Source agreement: `THRESHOLD = 100` at `MatchExecutionPlanner.java:347`, prefetch filter `x.getValue() < THRESHOLD` at `:604`; the prefetched arm chains `new MatchFirstStep(context, node, profilingEnabled)` at `:2089` while the non-prefetched arm passes `select.createExecutionPlan(...)` at `:2096-2100`; `addPrefetchSteps` builds `new MatchPrefetchStep(context, prefetchStm.createExecutionPlan(...), alias, profilingEnabled)` at `:4750`. `MatchPrefetchStep` declares `reset`, `internalStart`, `canBeCached`, `prettyPrint`, `copy` and neither introspection method, so the "primary target" designation is correct.
  - Criteria met: the item now points at the step the failing tests actually route through, and the both-cardinalities clause blocks the decomposer failure mode C12 described (verifying only against a large class).
- **Regression check**: checked the Signatures line for internal consistency — it now reads "the default implementations `MatchPrefetchStep` and `MatchFirstStep` inherit, against the five sibling MATCH steps that override them", which matches the T4 edit. Checked `## Context and Orientation` items 3–4: **not** updated, still carrying the disproved inference (→ T9).
- **Verdict**: VERIFIED

#### Verify T2: the product-side option is copy-on-re-arm, not a state-machine relaxation
- **Original issue**: Item 1 offered "re-arm from `CLOSED`" without naming the plan-restart problem; a `CLOSED → REARMED` mapping restarts a plan whose per-step close guard is sticky, leaking cursors invisibly under the mocked-plan harness.
- **Fix applied**: A paragraph appended to item 1 (track-10.md:62); `YTDBMatchPlanStep` and `MultiPlanMatchStep` added to In-scope (`:91`); the sticky-guard pair and `InternalExecutionPlan.copy` added to Signatures (`:97`); a no-restart assertion added to `## Validation and Acceptance` (`:81`).
- **Re-check**:
  - Location: track-10.md:62, :81, :91, :97.
  - Current state: the paragraph states the one-line edit "does not work", cites `AbstractExecutionStep.alreadyClosed` (`:102`) as a private sticky guard set on `close()`, `ExecutionStepInternal.reset()` as a no-op default with no override in `AbstractExecutionStep`, and `SelectExecutionPlan.reset()` as forwarding to that no-op; concludes with copy-on-re-arm via `InternalExecutionPlan.copy` pulling both subclasses into scope, and requires the test to verify `copy(...)` is called and `start()` is never re-invoked on the original.
  - Source agreement, line by line: `private boolean alreadyClosed = false;` at `AbstractExecutionStep.java:102`, set to `true` at `:113` behind an early return at `:110`, with no `reset()` declared in the class; `default void reset() { // do nothing }` at `ExecutionStepInternal.java:160-162`; `public void reset(CommandContext ctx) { steps.forEach(ExecutionStepInternal::reset); }` at `SelectExecutionPlan.java:107-108`; `default InternalExecutionPlan copy(CommandContext ctx)` declared at `InternalExecutionPlan.java:75` and implemented at `SelectExecutionPlan.java:238-242` via `copyOn`. Every citation in the paragraph resolves, including the `:102` line number.
  - Criteria met: the Decision Record the item hands Phase A now carries the mechanism and the reason, the two subclasses the copy reaches are in scope, and the acceptance line pins the property a Mockito plan cannot fake.
- **Regression check**: verified the new Signatures entries do not contradict the older ones — `AbstractMatchPlanStep.reset()` / `processNextStart()` remain listed and are still the right seam. Checked that In-scope's phrasing gates the two subclasses on "if step 1 lands on the product side", which matches item 1 leaving the contract genuinely open. One gap, noted rather than raised: `MultiPlanMatchStepTest` exists at `core/src/test/java/.../translator/step/MultiPlanMatchStepTest.java` and is the only place a union re-arm can be pinned, but In-scope names only `YTDBMatchPlanStepTest`. The Phase-B coverage gate catches this if the product side lands, so it self-corrects.
- **Verdict**: VERIFIED

#### Verify T3: pin the translator kill-switch in the plan-capture scenarios
- **Original issue**: The three scenarios whose contracts Phase A settles run against whatever the kill-switch defaults to, while the same file establishes and documents the opposite convention.
- **Fix applied**: A clause appended to `## Plan of Work` item 2 (track-10.md:63).
- **Re-check**:
  - Location: track-10.md:63.
  - Current state: "Whichever contract is chosen, pin the translator kill-switch explicitly in the three plan-capture scenarios — none of them does today, so a flip of the default silently re-points the assertion (T3)."
  - Source agreement: re-read `YTDBQueryMetricsStrategyTest.java:265-362`. The three method declarations (`planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep`, `indexedQuerySurfacesPlanWithFetchFromIndexStep`, `byIdLookupSurfacesNullPlan`) contain no `setTranslatorEnabled` call; the helper's only call sites are `:604`, `:640`, `:696` and its declaration `:710`. The "none of them does today" claim holds.
  - Criteria met: the requirement covers all three scenarios and binds regardless of which contract item 2 picks, which is what T3 asked for.
- **Regression check**: T3's proposed fix asked for the requirement on items 2 and 3; it landed only on item 2. Not a gap — the sentence quantifies over "the three plan-capture scenarios", and the three resolve unambiguously against `## Context and Orientation` items 2 and 3–4, which enumerate exactly those names. Placing it once avoids a duplicated requirement drifting between two items.
- **Verdict**: VERIFIED

#### Verify T4: option (a) is convention conformance, and the deferred Constraints amendment
- **Original issue**: Item 3 framed the `getSubSteps()` override as a novel carve-out from the "Engine surface is preserved" constraint, when five sibling MATCH steps in the same package already override it.
- **Fix applied**: Item 3's decision paragraph rewritten to take option (a) (track-10.md:66); the third `## Invariants & Constraints` bullet rewritten to record the resolution (`:102`). The plan's `### Constraints` bullet deliberately left unamended.
- **Re-check**:
  - Location: track-10.md:66, :102; `implementation-plan.md:40-44`.
  - Current state: the paragraph concedes the literal reading forbids the override, then names all five precedents (`HashJoinMatchStep`, `FilterNotMatchPatternStep`, `BackRefHashJoinStep`, `InvertedWhileHashJoinStep`, `CorrelatedOptionalHashJoinStep`), states the two targets are the outliers, and identifies the single observable effect — `EXPLAIN` documents gaining nested `subSteps` via `ExecutionStep.toResult`, with `prettyPrint` byte-identical and no test asserting on MATCH `subSteps`. The Invariants bullet mirrors the resolution and adds "Reverting to the test side now requires a written trade, not a default", inverting the previous ESCALATE-on-defaulting phrasing.
  - Criteria met: the Phase-A writer now has a decision made on named evidence with the side-effect surface bounded, which is what T4 asked for.
- **Deferral safety (the orchestrator's explicit question)**: deferring the plan-side edit to Phase B is safe for the decision itself. The decomposer's authoritative source is the track file, which states the resolution in two independent sections and pre-empts the silent-default failure mode that motivated S4 in the plan-level structural review. The plan's bullet is the weaker signal of the two, and the track now overrides it explicitly rather than by implication. What the deferral does leave is an unowned edit: no acceptance criterion requires the amendment, so a Phase B that ships the overrides and skips the plan edit passes every gate while leaving `implementation-plan.md` asserting something the diff contradicts. Raised as T10.
- **Regression check**: confirmed the Invariants bullet no longer contradicts item 3 — both now say option (a) with the same justification. Confirmed `implementation-plan.md:40-44` is unchanged and still lists exactly two exceptions, matching what item 3 quotes.
- **Verdict**: VERIFIED

#### Verify T5: close-then-reset row assertion
- **Original issue**: No test covers close-then-reset at either layer, and the one reset scenario that exists passes over a zero-row second run.
- **Fix applied**: An acceptance bullet added (track-10.md:80); `YTDBMatchPlanStepTest` added to In-scope (`:91`).
- **Re-check**:
  - Location: track-10.md:80, :91.
  - Current state: "A close-then-reset case is covered at unit level, asserting **rows** rather than listener-invocation counts. `queryFinishedFiresAgainAfterResetAndReExecution` currently passes while its second run yields zero rows, so it cannot witness the item-1 defect (T5); either it gains a row assertion or a sibling test does."
  - Source agreement: `queryFinishedFiresAgainAfterResetAndReExecution` is declared at `YTDBQueryMetricsStrategyTest.java:397`. `MultiPlanMatchStepTest` carries `close_afterNormalDrain_closesEveryChildPlanOnce` (`:298`), `close_isIdempotent` (`:348`), `reset_thenProcessNextStart_rewindsAndReRunsEveryChild` (`:372`) and `reset_beforeFirstIteration_doesNotRewindAnyChildOnFirstOpen` (`:402`) — close tests and reset tests, no close-then-reset test. The gap E5 described is unchanged in the tree.
  - Criteria met: the criterion names the row-count property, names the scenario that currently masks it, and admits either remedy.
- **Regression check**: the "either it gains a row assertion or a sibling test does" disjunction is looser than T5's proposal, which asked for the scenario assertion *and* unit cases in both boundary test classes. The looseness is defensible — the acceptance line pins the property, not the file. Same `MultiPlanMatchStepTest` omission noted under T2; not re-raised.
- **Verdict**: VERIFIED

#### Verify T6: the scope figure
- **Original issue**: The plan checklist's `~5 files` understated the footprint once T1 and T2 landed.
- **Fix applied**: `implementation-plan.md:607` now reads `**Scope:** ~7–10 files`.
- **Re-check**:
  - Location: `implementation-plan.md:607`.
  - Current state: `~7–10 files`, covering the same five listed surfaces. T6's enumeration was 8–9 and its proposal `~8-10`; the applied range brackets both and stays well inside the ~12 merge-candidate bound, so the track's shape is unaffected either way.
  - Criteria met: the Phase-C review-burden check now compares against a live number.
- **Regression check**: the checklist prose after the Scope line still names "the `MatchFirstStep` introspection question" rather than the prefetch-first framing from T1. Cosmetic at plan-checklist altitude — the checklist entry is a pointer, the track file is authoritative, and the T1 edit is where a decomposer reads the target. Not raised. Separately: the slim-plan render at `/tmp/claude-code-plan-slim-305004.md` still carries `~5 files` (snapshot predates the fix), so a re-read of that file rather than the plan would see the stale figure.
- **Verdict**: VERIFIED

#### Verify T7: the `workflow_dispatch` option
- **Original issue**: Item 4 weighed undrafting against new machinery, missing the zero-cost manual dispatch that already exists.
- **Fix applied**: A third option added to item 4 (track-10.md:67).
- **Re-check**:
  - Location: track-10.md:67.
  - Current state: "or trigger the existing `workflow_dispatch` on `maven-pipeline.yml` — the draft gate is a single `if` on `detect-changes`, so a manual dispatch runs the full pipeline without undrafting (T7)."
  - Source agreement: `.github/workflows/maven-pipeline.yml:2-9` lists `workflow_dispatch` as the first trigger; `detect-changes` carries `if: github.event.pull_request.draft != true` at `:27`. A `workflow_dispatch` event carries no `github.event.pull_request`, so the condition evaluates true and the downstream jobs run.
  - Criteria met: the option is present and its mechanism is stated correctly.
- **Regression check**: item 4 closes with "Any combination is admissible", which was already there and still reads correctly with three options. The track does not label the dispatch as a verification lever rather than a permanent gate, as T7 suggested, but item 4's framing ("Close the detection hole… A red `core` unit-test run must not survive 117 commits again") makes a one-shot manual run visibly insufficient on its own. Clean.
- **Verdict**: VERIFIED

#### Verify T8: the Javadoc-revision criterion
- **Original issue**: `CLOSED` is documented as terminal in several `AbstractMatchPlanStep` Javadoc blocks; a product-side item 1 must revise them in the same commit, per the CLAUDE.md keep-comments-in-sync rule.
- **Fix applied**: An acceptance bullet added (track-10.md:82).
- **Re-check**:
  - Location: track-10.md:82.
  - Current state: "**If step 3 lands on the product side**, the three Javadoc blocks in `AbstractMatchPlanStep` stating that `CLOSED` is terminal by design are revised in the same commit (T8)."
  - The condition names the wrong step. Step 3 is the introspection question — `getSubSteps()` overrides on `MatchPrefetchStep` and `MatchFirstStep`, which are `sql/executor/match/` classes with no relationship to `AbstractMatchPlanStep`'s state machine. The trigger T8 identified is step 1, the `reset()`-from-`CLOSED` contract; the preceding bullet at `:81` correctly conditions its Mockito-proof assertion on "If step 1 lands on the product side".
  - Failure mode: step 1 lands product-side and step 3 lands test-side. The Javadoc criterion does not fire, the `CLOSED`-is-terminal prose survives against a state machine that now re-arms from `CLOSED`, and Phase C has nothing to check it against. The inverse also misfires — a product-side step 3 with a test-side step 1 makes the criterion demand a Javadoc revision that has no code change behind it.
- **Regression check**: also checked the count. `AbstractMatchPlanStep` states the terminality across four Javadoc regions: the class-level Lifecycle exhaustion bullet (`:64-69`, "a closed `SelectExecutionPlan` cannot be cleanly restarted (its steps' close guard is sticky…)"), the `CLOSED` enum constant (`:164-167`, "Terminal: `processNextStart()` ends immediately and `close()` is a no-op"), the `reset()` Javadoc (`:493-505`, "a CLOSED step stays CLOSED"), and the `close()` Javadoc (`:514-521`, "Idempotent via the CLOSED state. Gating entry on CLOSED rather than DRAINED…"). Three of the four flip under a product-side item 1; the `close()` block stays accurate because `close()` remains idempotent. "Three" is therefore defensible and arguably tighter than the finding's four — the count is not the problem, the condition is.
- **Verdict**: STILL OPEN — change "step 3" to "step 1" at track-10.md:82.

---

**Summary: FAIL.** Seven of eight findings VERIFIED, both blockers among them, with every code citation in the applied text confirmed against source. T8 is STILL OPEN on a one-word condition error that nullifies the criterion. Two new should-fix findings: T9 (the orientation section still carries the root cause T1 disproved) and T10 (the deferred plan-`### Constraints` amendment has no acceptance owner). No blocker remains and no fix introduced a regression; iteration 2 is three small edits.
