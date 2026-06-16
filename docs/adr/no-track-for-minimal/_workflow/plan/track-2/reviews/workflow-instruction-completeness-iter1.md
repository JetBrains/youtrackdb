<!--
MANIFEST
dimension: workflow-instruction-completeness
target: track-2 (full diff, all steps)
range: 01b13bfd642b48c498c85007cfd7014c370fd2d4..HEAD
verdict: should-fix
findings_total: 4
blocker: 0
should-fix: 2
suggestion: 2
evidence_base: "## Evidence base"
cert_index: 4
flags: none
index:
- id: WI1
  sev: should-fix
  anchor: "### WI1 [should-fix] consistency-review.md + implementation-review.md still frame minimal as a stub plan"
  loc: docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/prompts/consistency-review.md:68-75
  cert: C1
  basis: judgment
- id: WI2
  sev: should-fix
  anchor: "### WI2 [should-fix] workflow.md pause write-side names only the **PAUSED marker, not the ledger paused= event"
  loc: docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md:500-504
  cert: C2
  basis: judgment
- id: WI3
  sev: suggestion
  anchor: "### WI3 [suggestion] workflow.md resume cleanup says delete secondary PAUSED markers with no ledger-event carve-out"
  loc: docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md:272-273
  cert: C3
  basis: judgment
- id: WI4
  sev: suggestion
  anchor: "### WI4 [suggestion] inline-replanning escalation states no crash recovery between materialize and commit"
  loc: docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/inline-replanning.md:150-189
  cert: C4
  basis: judgment
-->

## Findings

### WI1 [should-fix] consistency-review.md + implementation-review.md still frame minimal as a stub plan

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/prompts/consistency-review.md` (lines 68-75); `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/implementation-review.md` (lines 213, 218-223)
- **Axis:** conditional branch coverage (the `minimal` Phase-2 review branch)
- **Cost:** the Phase-2 consistency reviewer is handed a `minimal` branch that names a plan file the design removed, with no rule for the actual no-plan state

Both files were re-pointed in this track (the tier read became ledger-first: `consistency-review.md` in Step 1, `implementation-review.md §Tier-driven pass selection` in Step 2), but their `minimal`-tier prose still describes `minimal` as carrying a "stub plan." `consistency-review.md:68-75` reads: "**`minimal`** (no design, **stub plan**): additionally skip the plan-content cross-check — the `minimal` aggregator plan is a **~10-line stub** with one checklist entry ... The **PLAN ↔ CODE** axis runs only its track-reference bullet ... Do not raise findings against **the stub plan's absent content**." `implementation-review.md:213/218-223` repeats it: the structural pass is dropped because "the **stub plan** has nothing to check," and the consistency narrowing is justified because "the `minimal` **stub plan** is ~10 lines with one checklist entry."

Track-1's `conventions.md` (the spec these consumers cite) is unambiguous that `minimal` has **no plan at all**: `§Per-tier artifact set` line 90 — "`minimal` drops the plan outright (D2)"; the plan-file-content section lines 266-267 — "`minimal` has no plan (D2)." So the two re-pointed consumers contradict the Track-1 spec.

The behavioral outcome table in `implementation-review.md:212` ("track + code only") is correct, and the structural pass is dropped under `minimal`, so the net effect may land right if the reviewer infers "no plan -> drop the plan axis entirely." But the instruction text leaves the no-plan case under-specified: the reviewer is told to run "the PLAN ↔ CODE axis ... track-reference bullet" and to suppress "the stub plan's absent content," and the design-presence guard that drives axis selection keys only on `design.md` (line 60-61), not on plan presence. Nothing tells the reviewer to drop the PLAN axis when there is no plan file — it only tells it to drop the *cross-check* against a stub that does not exist. This is the same within-file coherence the implementer fixed for co-resident marker reads in Step 1 and for the `structural-review.md` presence checks in Step 3, missed here.

**Suggestion:** re-point the `minimal` branch in both files from "stub plan / ~10-line stub / the stub plan's absent content" to the Track-1 no-plan reality: under `minimal` there is no `implementation-plan.md`, so the PLAN axis is **dropped entirely** (not narrowed to a track-reference bullet against a stub), and the only plan-vs-code obligation that survives is whatever reads the track files directly. Align the structural-pass-drop rationale at `implementation-review.md:213/221-223` to "`minimal` has no plan, so there is no plan-file shape to validate" rather than "the stub plan has nothing to check."

### WI2 [should-fix] workflow.md pause write-side names only the **PAUSED marker, not the ledger paused= event

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md` (lines 500-504)
- **Axis:** phase output -> next-phase input (the pause-time write that the resume scan must later consume)
- **Cost:** an orchestrator pausing at State 0 or Phase 4 from this section's instruction writes no defense-in-depth pointer, weakening D8's recovery for exactly the two phases D8 relocated

`workflow.md §When to end a session` step 2 still tells the orchestrator at pause time: "The handoff file goes under `.../handoff-<phase>.md`, **paired with a `**PAUSED ...**` marker in the natural progress-tracking file (skipped for Phase 0 / 1 — see `mid-phase-handoff.md` §Secondary marker)** and a cross-reference in `MEMORY.md`." Under D8 (the change this track lands), the State-0 (Phase 2) and Phase-4 pauses no longer write a `**PAUSED` marker — they write a ledger `paused=state0` / `paused=phase4` event, because their former in-plan hosts (`## Plan Review`, `## Final Artifacts`) are gone. The staged `mid-phase-handoff.md §Secondary marker` handles this correctly (delta lines 161-218). But this `workflow.md` instruction names only the `**PAUSED` marker and carves out only Phase 0/1 — it does not mention the ledger `paused=` event for State 0 / Phase 4, so it under-specifies the two cases D8 just created.

It does delegate to `mid-phase-handoff.md §Secondary marker` ("see ..."), which is authoritative and correct, so an orchestrator that follows the pointer reaches the right behavior. That delegation keeps this from being a hard strand, but the in-line text is wrong for the new cases and an orchestrator working from the summary alone (a `**PAUSED` marker in the progress file) would skip the ledger event for State 0 / Phase 4.

**Suggestion:** update the parenthetical to "(a track-Progress `**PAUSED` line for A/B/C; a ledger `paused=` event for State 0 / Phase 4; skipped for Phase 0 / 1 — see `mid-phase-handoff.md` §Secondary marker)", matching the D8 split.

### WI3 [suggestion] workflow.md resume cleanup says delete secondary PAUSED markers with no ledger-event carve-out

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md` (lines 272-273)
- **Axis:** cleanup and idempotency (resolved-pause cleanup on resume)
- **Cost:** the resume-cleanup summary directs deletion of a marker that does not exist for State 0 / Phase 4, where the clear is handoff-file deletion instead

`workflow.md §Startup Protocol` step 4 (handoff-resume) says: "follow its §Resume protocol: re-present the pending decision ..., **delete resolved handoff files and their secondary PAUSED markers**, then return to state routing." For a State-0 / Phase-4 pause there is no `**PAUSED` marker to delete — the pointer is an append-only ledger `paused=` event that cannot be removed; per the staged `mid-phase-handoff.md` clear-on-resolution rule (delta lines 480-491 and 475-482), handoff-file deletion *is* the clear and "No ledger edit is needed or possible." This is the inverse of WI2 on the read/cleanup side.

The same line delegates to `mid-phase-handoff.md` ("See `mid-phase-handoff.md` for the full resume contract"), which is the authoritative, correct version, so this is lower-severity than WI2 — an orchestrator following the pointer reaches the right behavior, and the cleanup of a non-existent marker is a harmless no-op rather than a strand.

**Suggestion:** soften "delete resolved handoff files and their secondary PAUSED markers" to "delete resolved handoff files and their secondary pause pointers (the track-Progress `**PAUSED` line for A/B/C; for State 0 / Phase 4 the handoff-file deletion is itself the clear — the ledger `paused=` event is append-only)."

### WI4 [suggestion] inline-replanning escalation states no crash recovery between materialize and commit

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/inline-replanning.md` (lines 150-189, escalation; lines 235-271, commit)
- **Axis:** error and recovery path (interrupted `minimal`->`lite`/`full` escalation)
- **Cost:** an escalation interrupted between materialize and the step-6 commit has no stated recovery rule, though the working-tree state is in fact recoverable

The D11 escalation lands the materialized `implementation-plan.md` (and `design.md` for `full`) in step 1 and the `--append-ledger --tier` in step 2, then commits both in the single step-6 commit. Neither artifact is committed until step 6, so a crash anywhere in steps 1-6 leaves an uncommitted working tree, and the documented implementer-spawn recovery (`git reset --hard HEAD`, lines 252-253) reverts the materialized artifacts and the ledger `tier` append together — restoring the pre-upgrade `minimal` state atomically. So the partial state *is* recoverable. But the spec never states this for the escalation case: lines 252-253 describe `git reset --hard HEAD` only as the reason to "commit and push immediately so the next implementer spawn doesn't lose them," not as the recovery for an interrupted escalation.

Secondarily, the "materialize-then-write order matters: appending the upgraded tier before the plan exists would route the next selector to read a plan that is not yet on disk" rationale (lines 175-177) is over-specified for the documented flow. The escalation ends the session and the next selector read is the *next* session, which sees both artifacts together (they land in one commit). Within the session there is no selector read between step 1 and step 6, and both are uncommitted until step 6, so the on-disk write order between materialize and append is not load-bearing for any reachable read. The ordering is harmless as defense-in-depth, but its stated justification names a read that cannot occur.

**Suggestion:** add one sentence to step 6 (or the escalation block) stating that an escalation interrupted before the commit reverts to pre-upgrade `minimal` via the standard `git reset --hard HEAD`, since neither the materialized artifacts nor the ledger `tier` append is committed until step 6. Optionally re-scope the ordering rationale to "so the same-commit set is internally consistent" rather than "the next selector would read a not-yet-on-disk plan."

## Evidence base

#### C1 — minimal stub-plan vs Track-1 no-plan spec (WI1)
Refuted the implementer's framing against the Track-1 spec. `conventions.md` (Track-1-canonical, consumed by both files): line 90 "`minimal` drops the plan outright (D2)"; lines 266-267 "`minimal` has no plan (D2)." Staged consumers still say otherwise: `consistency-review.md:68-75` ("no design, stub plan ... ~10-line stub ... PLAN ↔ CODE axis runs only its track-reference bullet ... the stub plan's absent content"); `implementation-review.md:213` ("the stub plan has nothing to check"), `:218-223` ("the `minimal` stub plan is ~10 lines with one checklist entry"). Complement check: the design-presence guard (`consistency-review.md:55-61`) keys axis selection on `design.md` presence only, never on plan presence, so the no-plan state has no rule that drops the PLAN axis — the only `minimal` instruction narrows the cross-check against a stub that does not exist. Confirmed gap.

#### C2 — pause write-side omits ledger event for State 0 / Phase 4 (WI2)
`workflow.md:500-504` instructs the pause-time write as a `**PAUSED` marker "skipped for Phase 0 / 1" only. The D8 design (this track) routes State-0 and Phase-4 pauses to a ledger `paused=` event because `## Plan Review` / `## Final Artifacts` are gone; staged `mid-phase-handoff.md` (delta 161-218) implements exactly this. The write-side summary in `workflow.md` was not extended to name the two ledger-event cases. Mitigation present: the line delegates to `mid-phase-handoff.md §Secondary marker`. Confirmed under-specification, mitigated by the pointer -> should-fix not blocker.

#### C3 — resume cleanup of a non-existent marker (WI3)
`workflow.md:272-273` directs "delete ... their secondary PAUSED markers." Staged `mid-phase-handoff.md` (delta 475-491) is explicit that a State-0 / Phase-4 pause has no deletable marker — the ledger event is append-only and handoff-file deletion is the clear. The cleanup of an absent marker is a no-op (not a strand), and the line delegates to `mid-phase-handoff.md`. Confirmed harmless-but-wrong -> suggestion.

#### C4 — escalation crash recovery recoverable but unstated (WI4)
Traced the materialize (step 1) / append (step 2) / commit (step 6) ordering in `inline-replanning.md:150-271`. Confirmed via the Track-1 precheck that `--append-ledger` is atomic (temp-file-plus-rename, lines 1507-1616 of the staged script) so the append itself cannot tear. Confirmed neither materialized artifact nor the ledger append is committed before step 6, so `git reset --hard HEAD` (lines 252-253) reverts them together to pre-upgrade `minimal`: the partial state is recoverable. Confirmed the escalation ends the session (step 6 "Resume or exit"; next read is next session), so the lines 175-177 ordering rationale names a same-session next-selector read that cannot occur. State is recoverable; the recovery rule and the ordering rationale are imprecise -> suggestion, not a strand.
