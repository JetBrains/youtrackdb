# Track 1: Wire workflow-drift detection gate into `/execute-tracks` startup

## Purpose / Big Picture

After this track lands, every `/execute-tracks` invocation detects workflow-format drift on the migration branch in turn 1 and forces the user to pick migrate / defer / suppress before any phase work begins.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Create the new `workflow-drift-check.md` gate file mirroring the branch-divergence pattern (detection, three-resolution, after-the-choice, skip rules). Wire it into `workflow.md` § Startup Protocol as Step 3a, append the session-end residue clause for deferred drift, add the on-demand reference list entry, add the conventions §1.1 glossary entry plus §1.2 pointer, and add a one-line cross-reference in the `migrate-workflow` skill preamble.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

The `/execute-tracks` startup protocol lives in `.claude/workflow/workflow.md` § Startup Protocol (lines ~124-242 at branch fork). The current numbered sequence is:

1. Read the plan file.
2. Identify all tracks and their status.
3. Branch Divergence Check (delegates to `.claude/workflow/branch-divergence-check.md`).
4. Handoff scan (loads `.claude/workflow/mid-phase-handoff.md` when handoffs exist).
5. State determination (plan-file and track-file driven; routes to Phase A/B/C/0/D).
6. Inform the user of the auto-resume decision.

The Branch Divergence Check at `.claude/workflow/branch-divergence-check.md` is the canonical template for the new gate: turn-1 detection bash, three resolutions with no silent default, and an "after the choice" handoff back to the rest of the startup protocol.

The `migrate-workflow` skill at `.claude/skills/migrate-workflow/SKILL.md` already implements per-commit replay logic. Its current invocation contract: run from a `develop` worktree against a separate worktree containing the migration branch. The skill targets exactly one `_workflow/` subtree and halts cleanly when none exists. This track does not modify the skill's behavior.

The `conventions.md` glossary at §1.1 collects shared terms; the plan-file structure block at §1.2 describes the on-disk layout under `_workflow/`. Both gain one short addition each in this track.

The session-end behavior lives in `workflow.md` § What to do before ending a session, which already reports the unpushed-commit count. The Defer resolution adds one more residue line alongside the unpushed-commit report.

Files in scope (final list of edited files for this track):

- `.claude/workflow/workflow-drift-check.md` (new file).
- `.claude/workflow/workflow.md` (Step 3a inserted; on-demand reference entry added; session-end residue clause appended).
- `.claude/workflow/conventions.md` (§1.1 glossary entry; §1.2 pointer).
- `.claude/skills/migrate-workflow/SKILL.md` (one-line preamble cross-reference).

## Plan of Work

The track produces one new file and three small additions to existing files. The natural ordering:

1. **Create the new gate file** `.claude/workflow/workflow-drift-check.md`. Mirror the shape of `branch-divergence-check.md`: a preamble naming when the gate runs and why, a `## Detection` section with the bash commands and skip rules, a `## Resolutions` section with three sub-sections (Migrate now, Defer, Suppress), and an `## After the choice` section explaining the handoff back to the startup protocol. Include the skip-rule order (no `_workflow/` → plan-complete-plus-Phase-4-active → empty diff) explicitly.

2. **Wire Step 3a into `workflow.md` § Startup Protocol.** Insert one paragraph between the existing Step 3 (Branch Divergence Check) and Step 4 (handoff scan), modeled on Step 3's shape: a one-paragraph rationale and a `Load [workflow-drift-check.md] and follow it` instruction. Renumbering of subsequent steps is not required; the existing steps stay as 4, 5, 6.

3. **Append the session-end residue clause** to `workflow.md` § What to do before ending a session. One short sentence stating that if the drift gate was deferred this session, the session-end summary includes the count and the `cd ../develop && /migrate-workflow <branch>` instruction. The sentence sits near the existing "Report unpushed commits" bullet.

4. **Add the on-demand reference entry** to `workflow.md` § Conventions. One-line entry naming `workflow-drift-check.md` and its trigger (loaded by the Startup Protocol step 3a).

5. **Update `conventions.md`.** Add the glossary entry "Workflow drift" to §1.1 (one-row addition to the table). Add a one-line pointer in §1.2 stating that drift may shift the on-disk shape of `_workflow/**` between sessions and naming `workflow-drift-check.md` as the resolution gate.

6. **Update `.claude/skills/migrate-workflow/SKILL.md`** with a one-line preamble note (between the frontmatter and the existing first paragraph) stating that auto-detection runs in `/execute-tracks` startup via `workflow-drift-check.md`; manual invocation stays as the migration entry point.

Ordering constraints: steps 2, 3, and 4 all edit `workflow.md`, so they can be combined or split during Phase A decomposition based on commit shape preference. Step 1 must land before step 2 (Step 3a references the new file). Step 5 and step 6 are independent of the others and of each other.

Invariants to preserve:

- The detection command in the new file must match the prose in design.md exactly: `git log --oneline FORK..develop -- .claude/workflow .claude/skills` (no fetch, no other pathspecs).
- The three resolutions must be presented with no silent default; the gate must force an explicit pick.
- The skill at `.claude/skills/migrate-workflow/SKILL.md` must continue to work when invoked manually from a develop worktree; the one-line preamble change must not alter the existing instruction flow.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level acceptance criteria (verified manually since the change is docs-only):

- Running `/execute-tracks` on a branch with at least one workflow-format commit on `develop` since the branch's fork point produces the drift prompt in turn 1 (after the divergence check, before the handoff scan) and forces the user to choose between Migrate now, Defer, and Suppress.
- Running `/execute-tracks` on a freshly-forked branch (or one already in sync with `develop`) prints no drift prose and proceeds straight to Step 4 (handoff scan).
- Running `/execute-tracks` on a branch with no `_workflow/` subtree skips the gate silently.
- Running `/execute-tracks` on a branch with all tracks complete and Phase 4 marker `[>]` or `[x]` skips the gate silently.
- Choosing Migrate now ends the current `/execute-tracks` session and the session-end message names the `cd ../develop && /migrate-workflow <branch>` command.
- Choosing Defer continues the session; the session-end summary includes the deferred drift count and the same command.
- Choosing Suppress continues the session; the session-end summary does not mention drift.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

In-scope file boundaries:

- `.claude/workflow/workflow-drift-check.md` (new file, owned by this track).
- `.claude/workflow/workflow.md` (Step 3a addition, session-end residue clause, on-demand list entry).
- `.claude/workflow/conventions.md` (§1.1 glossary entry; §1.2 pointer).
- `.claude/skills/migrate-workflow/SKILL.md` (one-line preamble note).

Out of scope:

- `.claude/skills/migrate-workflow/SKILL.md` per-commit replay logic, classification rules, worktree-resolution code (Option B from YTDB-936, deferred).
- `/create-plan` startup protocol (issue Open Question 2 rejected drift detection at create-plan start).
- Persistent "ignore for this branch" sentinel (issue Open Question 1 rejected).
- Pre-classification of commits inside the gate (issue Open Question 3 rejected).
- Any change to `.claude/workflow/branch-divergence-check.md`; the new gate mirrors it but does not modify it.

Inter-track dependencies: none. This is a single-track plan.

External dependencies the gate's bash relies on:

- `git rev-parse --verify refs/heads/develop` — must exist for the detection command to run; absence is handled as a skip path.
- `git merge-base develop HEAD` — used to compute the fork point. Identical to the migrate-workflow skill's Step 3 computation.
- `git log --oneline FORK..develop -- <pathspecs>` — the cheap detection command. Cost is proportional to the commit range; on a long-lived branch with thousands of develop commits this is still sub-second on typical hardware.

Library / function signatures relevant to this track: none beyond the bash above. The new file owns its prose; the existing workflow files own theirs.
