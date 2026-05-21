# Track 1: Wire workflow-drift detection gate into `/execute-tracks` startup

## Purpose / Big Picture

After this track lands, every `/execute-tracks` invocation detects workflow-format drift on the migration branch in turn 1 and forces the user to pick migrate / defer / suppress before any phase work begins.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Create the new `workflow-drift-check.md` gate file mirroring the branch-divergence pattern (detection, three-resolution, after-the-choice, skip rules). Wire it into `workflow.md` § Startup Protocol as Step 3a, append the session-end residue clause for deferred drift, add the on-demand reference list entry, add the conventions §1.1 glossary entry plus §1.2 pointer, and add a one-line cross-reference in the `migrate-workflow` skill preamble.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-21T09:09Z [ctx=info] Review + decomposition complete (technical review PASS at iteration 2; 4 steps decomposed, 1 medium + 3 low)
- [x] 2026-05-21T09:27Z [ctx=safe] Step 1 complete (commit 8f56f1919dde2f78ef20be9cf8a43db70a80d9a7)
- [x] 2026-05-21T09:32Z [ctx=safe] Step 2 complete (commit 39a52e299c81d184fd68c411b144650d748fe741)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- 2026-05-21T09:27Z Triple-quoted Kotlin string literals inside `steroid_execute_code` keep host-script indentation; future docs-track steps that write a fresh markdown file via `VfsUtil.saveText` should use `buildString { appendLine(...) }` to decouple file content from the host script. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (5 findings, 2 accepted — T1 should-fix on Remote-authoritative re-entry contract, T5 suggestion on skip-section shape; T2/T3/T4 deferred as low-impact wording polish). Risk and Adversarial skipped per complexity assessment (Moderate docs-only track, no critical path, no architectural surprises).

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

1. **Create the new gate file** `.claude/workflow/workflow-drift-check.md`. Mirror the shape of `branch-divergence-check.md`: a preamble naming when the gate runs and why, a `## Detection` section with the bash commands, a dedicated `## Skip conditions` section matching design.md's shape (no `_workflow/` → plan-complete-plus-Phase-4-active → empty diff, in that order), a `## Resolutions` section with three sub-sections (Migrate now, Defer, Suppress), and an `## After the choice` section explaining the handoff back to the startup protocol. The `## After the choice` prose MUST address Remote-authoritative divergence re-entry: when Step 3 (Branch Divergence Check) ends with a `git reset --hard origin/<branch>`, the fork point may shift and drift must be re-evaluated against the new HEAD. State that Step 3a re-fires on Remote-authoritative re-entry, or otherwise document the contract for the post-reset code path. The pre-existing stale "step 3" label in `branch-divergence-check.md` lines 86-90 stays out of scope per the file boundaries below.

2. **Wire Step 3a into `workflow.md` § Startup Protocol.** Insert one paragraph between the existing Step 3 (Branch Divergence Check) and Step 4 (handoff scan), modeled on Step 3's shape: a one-paragraph rationale and a `Load [workflow-drift-check.md] and follow it` instruction. Renumbering of subsequent steps is not required; the existing steps stay as 4, 5, 6.

3. **Append the session-end residue clause** to `workflow.md` § What to do before ending a session. One short sentence stating that if the drift gate was deferred this session, the session-end summary includes the count and an instruction to switch to a `develop` worktree (e.g., `cd ../develop`) and run `/migrate-workflow <branch>`. The sentence sits near the existing "Report unpushed commits" bullet.

4. **Add the on-demand reference entry** to `workflow.md` § Conventions. One-line entry naming `workflow-drift-check.md` and its trigger (loaded by the Startup Protocol step 3a).

5. **Update `conventions.md`.** Add the glossary entry "Workflow drift" to §1.1 (one-row addition to the table). Add a one-line pointer in §1.2 stating that drift may shift the on-disk shape of `_workflow/**` between sessions and naming `workflow-drift-check.md` as the resolution gate.

6. **Update `.claude/skills/migrate-workflow/SKILL.md`** with a one-line preamble note (between the frontmatter and the existing first paragraph) stating that auto-detection runs in `/execute-tracks` startup via `workflow-drift-check.md`; manual invocation stays as the migration entry point.

Ordering constraints: steps 2, 3, and 4 all edit `workflow.md`, so they can be combined or split during Phase A decomposition based on commit shape preference. Step 1 must land before step 2 (Step 3a references the new file). Step 5 and step 6 are independent of the others and of each other.

Invariants to preserve:

- The detection command in the new file must match the prose in design.md exactly: `git log --oneline FORK..develop -- .claude/workflow .claude/skills` (no fetch, no other pathspecs).
- The three resolutions must be presented with no silent default; the gate must force an explicit pick.
- The skill at `.claude/skills/migrate-workflow/SKILL.md` must continue to work when invoked manually from a develop worktree; the one-line preamble change must not alter the existing instruction flow.

Phase A step sequencing: the six conceptual actions above bundle into four commits (see `## Concrete Steps` below). Action 1 is one commit (the new gate file). Actions 2, 3, and 4 are one combined commit because all three edits reference the new gate file by name and live in adjacent `workflow.md` sections. Action 5 is one commit (both `conventions.md` edits). Action 6 is one commit (the `SKILL.md` preamble note).

## Concrete Steps

1. Create `.claude/workflow/workflow-drift-check.md` with Detection, Skip conditions, Resolutions (Migrate / Defer / Suppress), and After the choice sections per `## Plan of Work` action 1, including the Remote-authoritative re-entry contract — risk: low (default: new markdown file not yet referenced by `workflow.md`; no behavioral change until Step 2 wires it)  [x] commit: 8f56f1919dde2f78ef20be9cf8a43db70a80d9a7
2. Wire the gate into `.claude/workflow/workflow.md`: insert Step 3a in `## Startup Protocol`, append the session-end residue clause in `## What to do before ending a session`, and add the on-demand reference entry in `## Conventions`. Bundled because all three edits reference the new gate file by name — risk: medium (multi-section workflow-machinery change that activates new turn-1 gate behavior in every `/execute-tracks` session)  [x] commit: 39a52e299c81d184fd68c411b144650d748fe741
3. Update `.claude/workflow/conventions.md`: add the "Workflow drift" glossary row to §1.1 and the one-line pointer to §1.2 naming the gate file as the resolution mechanism — risk: low (default: documentation update; glossary row and cross-reference only)  [ ]
4. Update `.claude/skills/migrate-workflow/SKILL.md` with the one-line preamble note cross-referencing `workflow-drift-check.md` as the auto-detection entry point; manual invocation unchanged — risk: low (default: one-line documentation update; no behavioral change to the skill)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 8f56f1919dde2f78ef20be9cf8a43db70a80d9a7, 2026-05-21T09:27Z [ctx=safe]
**What was done:** Created `.claude/workflow/workflow-drift-check.md` (180 lines) mirroring `branch-divergence-check.md`. The file owns turn-1 detection (`git log --oneline FORK..develop -- .claude/workflow .claude/skills`), the three skip conditions in fail-fast order (no `_workflow/` subtree, plan complete plus Phase 4 active, empty diff), the three resolutions (Migrate now / Defer / Suppress) with a no-default contract, and the After-the-choice section that documents the Remote-authoritative re-entry contract per the Phase A T1 should-fix finding.

**What was discovered:** Triple-quoted Kotlin string literals in `steroid_execute_code` preserve their host-script indentation, so writing a markdown file via `val content = """..."""` produced 4-space leading whitespace on every line and would have rendered the body as one code block. Switched to `buildString { appendLine(...) }` to decouple file content from the host script's indentation. The hazard applies to any future docs-track step that writes a fresh markdown file through IDE-routed `VfsUtil.saveText`.

**Key files:**
- `.claude/workflow/workflow-drift-check.md` (new)

### Step 2 — commit 39a52e299c81d184fd68c411b144650d748fe741, 2026-05-21T09:32Z [ctx=safe]
**What was done:** Wired the gate into `.claude/workflow/workflow.md` with three additions in one commit. Step 3a inserted between the Branch Divergence Check (Step 3) and the handoff scan (Step 4); the new paragraph mirrors Step 3's shape and documents the post-fetch ordering, the pre-handoff ordering, and the Remote-authoritative re-entry contract from the Phase A T1 finding. Session-end residue clause appended next to the unpushed-commit bullet under `## What to do before ending a session`, naming the deferred drift count and the `cd ../develop` plus `/migrate-workflow <branch>` instruction for the Defer path. On-demand reference entry added alongside `branch-divergence-check.md` under `## Conventions`. All three hunks landed atomically via `steroid_apply_patch`.

**Key files:**
- `.claude/workflow/workflow.md` (modified)

## Validation and Acceptance

Track-level acceptance criteria (verified manually since the change is docs-only):

- Running `/execute-tracks` on a branch with at least one workflow-format commit on `develop` since the branch's fork point produces the drift prompt in turn 1 (after the divergence check, before the handoff scan) and forces the user to choose between Migrate now, Defer, and Suppress.
- Running `/execute-tracks` on a freshly-forked branch (or one already in sync with `develop`) prints no drift prose and proceeds straight to Step 4 (handoff scan).
- Running `/execute-tracks` on a branch with no `_workflow/` subtree skips the gate silently.
- Running `/execute-tracks` on a branch with all tracks complete and Phase 4 marker `[>]` or `[x]` skips the gate silently.
- Choosing Migrate now ends the current `/execute-tracks` session and the session-end message tells the user to switch to a `develop` worktree (e.g., `cd ../develop`) and run `/migrate-workflow <branch>` there.
- Choosing Defer continues the session; the session-end summary includes the deferred drift count and the same command.
- Choosing Suppress continues the session; the session-end summary does not mention drift.
- After a Remote-authoritative resolution in Step 3 (`git reset --hard origin/<branch>`), Step 3a re-evaluates drift against the new HEAD before proceeding to Step 4. Verify by running `/execute-tracks` on a branch that has been force-rebased onto a newer `develop`, picking Remote-authoritative when divergence surfaces, then confirming the drift prompt fires on the post-reset HEAD if format-relevant commits exist in the new range.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Per-step idempotence and recovery paths. All four steps are docs-only edits; the recovery shape is "read the file, see what landed, re-run the missing edits".

1. **Step 1 (new gate file).** Authoring a new file is idempotent at the file-content level: re-running the writer with the same target content produces the same bytes. If the session dies after the file is on disk but before the commit, `git status` reports the file as untracked; resume by re-running the authoring step (the existing on-disk content is overwritten with identical bytes) or by staging and committing what is already on disk. If the session dies mid-write leaving a partial file, delete the partial file and re-run the authoring step.

2. **Step 2 (workflow.md edits).** Three `Edit` calls against one file, each with a unique anchor. Each `Edit` is idempotent at the call level: re-running an `Edit` whose `new_string` is already in the file fails the anchor lookup, so accidental double-application is not possible. Recovery: read `workflow.md` and grep for `workflow-drift-check.md`. Three hits across `## Startup Protocol`, `## What to do before ending a session`, and `## Conventions` means all three edits landed; fewer hits names which edits to re-run.

3. **Step 3 (conventions.md edits).** Two `Edit` calls against one file (glossary row + §1.2 pointer). Same shape: `grep -c "Workflow drift" .claude/workflow/conventions.md` returns 2 when both edits landed (one in the §1.1 table, one in the §1.2 pointer).

4. **Step 4 (SKILL.md preamble).** One `Edit` call. Recovery: `grep -c "workflow-drift-check" .claude/skills/migrate-workflow/SKILL.md` returns 1 when the edit landed.

Mid-step crash recovery for any step: `git status` shows uncommitted edits, `git diff` shows their content. The implementer's revert path is `git reset --hard HEAD`, which discards uncommitted edits and returns to the prior commit; re-spawn from `mode=INITIAL` starts the step over.

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

## Base commit

a008d4866849f2c19872f14bde0769ed401de84a
