# Track 6: Drift gate at /create-plan startup

## Purpose / Big Picture
After this track lands, `/create-plan` re-detects workflow drift at session start, so a re-invocation between planning sessions catches post-rebase drift before any research investment.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add a session-start invocation of the SHA-aware drift gate (rewritten in Track 3) to the `/create-plan` SKILL, between Step 1 (read workflow docs) and Step 1a (handoff scan). Without this gate, a re-invocation after the user rebases the branch to pick up critical workflow changes from develop would silently mutate `_workflow/**` artifacts on top of the drifted shape: stamps still point at the pre-rebase workflow tip, but HEAD's history has advanced to include the newly-imported workflow commits. The gate's `BASE_SHA..HEAD` walk surfaces those imported commits (D10) and routes the user through migration first. Three resolutions stay symmetric with `/execute-tracks` (Migrate now / Defer / Suppress). `/edit-design` runs only inside parent skills, so transitive coverage holds; no separate wiring there.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

`.claude/workflow/workflow-drift-check.md` is the gate `/execute-tracks` invokes in its turn-1 startup, between the Branch Divergence Check and the handoff scan. After Track 3 lands, the Detection logic reads per-artifact stamps in the active plan's `_workflow/` (D13) and the Migrate-now resolution wording points users at an in-branch `/migrate-workflow`. Skip conditions (active plan's `_workflow/` doesn't exist; active plan complete plus Phase 4 active; empty diff) carry over with scopes tightened by Track 3.

`.claude/skills/create-plan/SKILL.md` (the slash command implementing `/create-plan`) runs:
- Step 1: read workflow docs (`conventions.md`, `research.md`)
- Step 1a: handoff scan (resume protocol if `handoff-*.md` exists)
- Step 1b: create `_workflow/` directory
- Step 2: ask user for the aim
- Step 3-5: research → planning → commit + push

Two re-invocation cases the current flow misses:
- The user runs `/create-plan` Session A, hits the context-warning threshold, runs `/clear`, then re-invokes `/create-plan` to continue. The branch may have rebased onto a newer develop between sessions.
- The user proactively rebases the branch onto a newer develop between Session A and Session B to pull in critical workflow changes ASAP. After the rebase, HEAD's history contains imported workflow commits, but the branch's `_workflow/**` artifacts still carry pre-rebase stamps. Without a gate at `/create-plan` startup, Session B writes new-format sections atop drifted artifacts.

The SHA-stamp design (Track 1) and the SHA-aware gate (Track 3) handle the rebase case natively: the stamp records the workflow-SHA the artifact was synced to, the range `BASE_SHA..HEAD` walks every workflow commit reachable from HEAD since that stamp, and rebase-imported commits show up in that walk (D10). Track 6 wires `/create-plan` to invoke that gate at startup.

## Plan of Work

Add a new step to `.claude/skills/create-plan/SKILL.md`, between Step 1 (Read workflow documents) and Step 1a (Handoff check). Call it Step 1.5 — Workflow drift check. The step instructs the agent to invoke the gate defined in `.claude/workflow/workflow-drift-check.md`: run the detection block, present the three-resolution prompt if drift surfaces, end the session on Migrate now, continue silently on no-drift or on Defer/Suppress.

Update `.claude/workflow/workflow-drift-check.md`'s intro paragraph to name both callers. Today's single mention of `/execute-tracks` becomes a small list covering `/execute-tracks` (turn 1) and `/create-plan` (between Step 1 and Step 1a). The rest of the file (Detection, Skip conditions, Resolutions, After the choice) stays caller-agnostic and needs no change beyond Track 3's existing rewrite.

Verify the gate's skip-#1 condition ("Active plan's `_workflow/` doesn't exist") catches the brand-new `/create-plan` case. On a fresh `/create-plan` invocation, the resolved `docs/adr/<dir-name>/_workflow/` doesn't exist yet, so the `ls -d "$PLAN_DIR/_workflow/"` check returns nothing, the gate skips silently, and Step 1b (the `mkdir`) runs after. Order matters: Step 1.5 must run before Step 1b so the skip-#1 check sees the pre-creation state.

Mirror the Migrate-now wording from Track 3's rewrite in `/create-plan`'s Step 1.5 instructions: end the session; user runs `/migrate-workflow` in this worktree, then re-invokes `/create-plan`. Both copies point at the in-branch flow.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 6 lands:

- `/create-plan` SKILL contains a Step 1.5 between Step 1 (Read workflow documents) and Step 1a (Handoff check) that invokes the drift gate defined in `workflow-drift-check.md`.
- On a branch with `_workflow/**` artifacts whose stamps lie behind workflow commits reachable from HEAD (because HEAD was advanced via rebase or merge since the artifacts were last migrated), invoking `/create-plan` triggers the three-resolution prompt with no default before any research begins.
- On a fresh `/create-plan` invocation where the resolved `docs/adr/<dir-name>/_workflow/` doesn't exist yet, the gate skips silently and proceeds to Step 1a.
- Step 1.5 runs before Step 1b (mkdir) so the skip-#1 check sees the pre-creation state.
- `workflow-drift-check.md`'s intro paragraph names both `/execute-tracks` and `/create-plan` as callers.
- Migrate-now resolution wording in both `/create-plan` SKILL and `workflow-drift-check.md` points at in-branch `/migrate-workflow` re-invocation; neither mentions `develop` worktree or `cd ../develop`.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/skills/create-plan/SKILL.md` (new Step 1.5 between Step 1 and Step 1a; Migrate-now wording mirroring Track 3)
- `.claude/workflow/workflow-drift-check.md` (intro paragraph generalization to name both callers; Detection / Skip conditions / Resolutions / After the choice unchanged here — Track 3 owns those)

**Out-of-scope files:**
- `.claude/skills/edit-design/SKILL.md` — `/edit-design` is reached only through `/create-plan` and `/execute-tracks`; transitive coverage holds.
- `.claude/skills/migrate-workflow/SKILL.md` (Tracks 4a and 4b)
- `.claude/workflow/conventions.md` (Track 1)

**Inter-track dependencies:**
- **Depends on:** Track 3. Track 6 wires `/create-plan` to invoke the gate; the Detection logic and in-branch Migrate-now wording come from Track 3. Without Track 3, the gate's Migrate-now resolution still mentions `develop` worktree, inconsistent with the in-branch theme.
- Transitive dependencies on Tracks 1 (stamp format) and 2 (stamp writers) flow through Track 3.

**External interfaces:**
- The Step 1.5 invocation runs the same bash detection block as the `/execute-tracks` turn-1 gate. No new external commands.
