<!-- workflow-sha: 03eac656fa115a8e6af3f53d8805d8f16f3bad50 -->
# Track 1: Robust Phase-4 _workflow/ cleanup

## Purpose / Big Picture
Phase-4 cleanup deletes the whole `_workflow/` scaffolding tree even when it holds locally-modified tracked files and untracked remnants, so the final cleanup commit runs to completion instead of aborting. Phase 4 is the workflow's post-implementation wrap-up phase; `_workflow/` is the throwaway directory under `docs/adr/<dir-name>/` that holds a branch's planning artifacts (research log, design draft, plan, track files, review files) and is deleted at the end of Phase 4 so only the durable `design-final.md` / `adr.md` survive the squash-merge into `develop`.

> **Predicted complexity tag:** low (prose-only workflow-machinery edit; recomputed from the decomposed steps and reconciled to `max(step tags)` at the Phase A → C boundary).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Today the cleanup command is a bare `git rm -r docs/adr/<dir-name>/_workflow/`. `git rm -r` refuses to remove a tracked file that has uncommitted local modifications, and it never touches untracked files at all. Both cases occur at Phase-4 cleanup time on a full-tier run. The final-artifacts commit that runs just before cleanup stages **only** the top-level artifacts (`design-final.md`, `adr.md`) and deliberately leaves everything under `_workflow/` alone. So when cleanup runs, `design-mutations.md` — the review-log file the `edit-design phase4-creation` loop appends to in its Step 7 while authoring the final design — is a tracked-but-modified file. Three other file kinds are untracked: the phase4-creation per-round params files, the cold-read `output_path` files, and any `.pyc` caches under `staged-workflow/`. The bare `git rm -r` aborts on the modified file and ignores the untracked ones, so the documented cleanup cannot run as written. This is YTDB-1180 (and its four duplicates YTDB-868, YTDB-902, YTDB-1055, YTDB-1135).

This track fixes the two operative command sites (the actual `git rm` commands the Phase-4 orchestrator runs) and reconciles the descriptive prose that would otherwise contradict the fix. The change is prose-only: it edits `.claude/workflow/**` files, no code. Because it is a workflow-modifying branch in staged mode, the edits land under the staged subtree during Phase B and promote to the live files in Phase 4 (see `## Interfaces and Dependencies`).

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-07-01T13:37Z [ctx=safe] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Full inline Decision
Records, four-bullet form, authored from the research log (design gate = no). -->

### D1 — Robust cleanup command (`git rm -rf` + `rm -rf`)
- **Decision:** Change the cleanup command to `git rm -rf docs/adr/<dir-name>/_workflow/` followed by a `rm -rf docs/adr/<dir-name>/_workflow/`, rather than eliminating the sources of the dirty state.
- **Alternatives:** (a) the robust command, chosen; (b) drop the `edit-design phase4-creation` `design-mutations.md` log write so the file is never modified (the older YTDB-868 / YTDB-902 proposal); (c) commit `_workflow/` before cleanup so nothing is modified or untracked.
- **Rationale:** Only (a) also clears the *untracked* remnants — the phase4-creation cold-read `output_path` files, the per-round params files, and the `.pyc` caches under `staged-workflow/` — which exist independently of the `design-mutations.md` append and which (b) and (c) both leave behind. The `-f` force flag lets `git rm` delete the tracked-but-modified `design-mutations.md`; the follow-up `rm -rf` clears the untracked files `git rm` cannot reach. The fix is one to two lines at each of the two command sites and matches the union of the five duplicate issues (YTDB-868, YTDB-902, YTDB-1055, YTDB-1135, YTDB-1180).
- **Risks:** `git rm -rf` force-discards local modifications; that is safe here because the entire subtree is being deleted, so discarding uncommitted changes to a file that is about to be removed loses nothing. The `rm -rf` of untracked files runs inside the same cleanup step and adds no new commit, so the single-cleanup-commit contract holds. The `rm -rf docs/adr/<dir-name>/_workflow/` blast radius is bounded by the Phase-4 step ordering: cleanup (`create-final-design.md` § Step 6 / `workflow.md` § Final Artifacts) runs *after* the promote-staged-workflow step has already copied the staged subtree onto the live `.claude/workflow/**` tree and committed it, so the `rm -rf` discards only an already-promoted, already-committed copy under `_workflow/`; it never touches the live workflow files or the durable `design-final.md` / `adr.md`, which live at `docs/adr/<dir-name>/` outside `_workflow/`.
- **Implemented in:** this track.

### D2 — Reconcile every descriptive mention site, not only the two commands
- **Decision:** Fix the two operative command sites *and* correct the "the recursive `git rm` sweeps automatically" prose plus the descriptive mentions in the other three files, so no doc contradicts the fix.
- **Alternatives:** (a) fix the commands and reconcile the "sweeps automatically" prose and every descriptive mention, chosen; (b) fix only the two operative commands and leave the prose.
- **Rationale:** The current text at `create-final-design.md:617` and `workflow.md:769` asserts the recursive `git rm` "sweeps the review-file directories automatically." That is true only for *tracked* files; leaving it in place after the fix creates an internal contradiction a Phase-C consistency reviewer would flag. Reconciling the descriptive rows in `commit-conventions.md:153`, `conventions-execution.md:372` / `:747`, and `mid-phase-handoff.md:493` keeps every doc telling one story.
- **Risks:** Scope is bounded to five files. The sixth `git rm -r` grep match — `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33` — is illustrative test-fixture body text that exercises a count-validation regex, not a live instruction, so it stays out of scope and must not be touched.
- **Implemented in:** this track.

### D3 — §1.7 mode: stage, not the prose-rule opt-out
- **Decision:** This workflow-modifying branch uses §1.7 staged mode. Edits accumulate under the staged mirror subtree during Phase B and promote to the live files in Phase 4, rather than editing `.claude/workflow/**` live under the §1.7(k) opt-out.
- **Alternatives:** (a) staged mode, chosen; (b) the §1.7(k) prose-rule self-application opt-out (edit `.claude/workflow/**` live so the branch is held to its own changed rules while it runs).
- **Rationale:** §1.7(k) qualifies a plan only when *every* edited file's in-branch consumer is judgment-layer prose (style rules, review criteria, prompt blurbs); criterion 2 keeps a file a running phase reads as executable procedure staged even on an otherwise-qualifying plan. Both operative sites here — `create-final-design.md` § Step 6 and `workflow.md` § Final Artifacts — are Phase-4 execution procedure the orchestrator runs, not judgment-layer prose, so criterion 2 fails and the opt-out does not apply.
- **Risks/Caveats:** Staging means the fix does not self-apply to this branch's own Phase 4: staged edits go live only at the Phase-4 promote step, so a branch whose own Phase 4 runs still executes the pre-promotion (old) command. That staged-mode self-application gap is absent *for this branch specifically*. `design_gate` gates whether the change authors a `design.md`; `design_gate=no` here means no design-authoring loop runs. So none of the `_workflow/` state the bug needs is produced: there is no plan-marker flip (the `[>]`→`[x]` Phase-4 progress-marker flip on the *plan* file, which a single-track minimal branch has no plan file to carry) and no `edit-design phase4-creation` loop. So there is no modified `design-mutations.md` and no per-round params files. The gap is a pre-existing property of `design_gate=yes` staged branches.
- **Implemented in:** this track.

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 1 (1 findings, 1 accepted) — T1 (suggestion) accepted: three benign `git rm`/`rm` mentions logged as intentionally-untouched in `## Context and Orientation` to pre-empt a Phase-C consistency false-positive.
- [x] Adversarial: PASS at iteration 2 (3 findings, 2 accepted) — reconciliation-triggered pass, narrowed to track realization (Track-1 exception dropped the cross-track-episode challenge). A1 (should-fix) accepted: broadened the acceptance/invariant grep to `grep -rnE "git rm -r([^f]|$)" .claude/workflow` so a partial fix cannot pass while a descriptive site stays buggy. A3 (suggestion) accepted: added the `rm -rf` blast-radius/ordering note to D1 Risks. A2 (suggestion) rejected (sound): the `.pyc`-gitignored nuance does not change the outcome. Core fix (D1/D2/D3) survived adversarial scrutiny — 0 blockers. Gate-verification (iter2) VERIFIED A1 + A3, REJECTED A2, 0 new findings.
- Reconciliation (D5): decomposition tagged the single step `medium` (bounded behavioral workflow edit), above the predicted `low` — upward divergence, so the missed strategic reviewer (Adversarial) ran and passed. A3 confirmed the single-step decomposition is correct, so no re-decomposition was needed. Reconciliation fires at most once per Phase A; it has fired. **Reconciled tag governing Phase C = `medium`** (also the Phase-C focal-point floor).

## Context and Orientation
The bug is live and unchanged on this branch's HEAD; there is no partial fix on `develop`. An empirical git repro this session confirmed the three-part diagnosis: with a modified tracked file plus an untracked sibling under a directory, `git rm -r <dir>` exits 1 on the modified file; `git rm -rf <dir>` succeeds but leaves the untracked sibling; a follow-up `rm -rf <dir>` clears it.

`grep -rn "git rm" .claude/` maps the sites. There are two **operative** sites — the actual commands the Phase-4 orchestrator runs — and four **descriptive** sites that mention the command in prose.

Operative sites (both must change):
- `.claude/workflow/prompts/create-final-design.md` § Step 6, ~line 609 — the bare `git rm -r docs/adr/<dir-name>/_workflow/` command block.
- `.claude/workflow/workflow.md` § Final Artifacts, ~line 764 — the mirror command (the two must stay in step).

Descriptive sites (reconcile so none contradicts the fix):
- `create-final-design.md:617` and `workflow.md:769` — both assert the recursive `git rm` "sweeps the review-file directories automatically." True only for tracked files; false for the untracked cold-read output and params files.
- `commit-conventions.md:153` — the Phase-4-cleanup row of the commit-type table.
- `conventions-execution.md:372` and `:747` — two blanket-`git rm -r _workflow/` prose mentions.
- `mid-phase-handoff.md:493` — the descriptive "`git rm -r`s `_workflow/`" mention in the Phase-4 handoff-cleanup exception.

Verified-negative (out of scope): a sixth `git rm -r` match at `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33` is finding-body text inside a test fixture, not a real cleanup instruction — do not touch it.

Three further `git rm` / `rm` mentions are intentionally left unedited (Technical review T1): `workflow.md:695` and `workflow.md:769`-area `:757` are sequencing forward-pointers ("before the cleanup `git rm` runs", "the cleanup `git rm` below deletes the log") that stay accurate after the fix, and `conventions-execution.md:383` ("the Phase 4 cleanup is the blanket recursive `rm` above") is a `plan/*`-glob forward guard that reads consistently once its referenced line 372 is reconciled. None carries the bare-`-r` command shape or the false untracked-sweep claim, so editing them is out of scope; they are flagged here so a Phase-C `review-workflow-consistency` grep of `git rm` does not re-raise them.

Terminology a Phase-B/C reader needs. `edit-design phase4-creation` is the authoring loop that writes the final `design-final.md` during Phase 4; its Step 7 appends a review-log entry to `design-mutations.md`, which is what leaves that tracked file modified at cleanup time. A "cold-read `output_path` file" is the scratch file a fresh sub-agent writes its output to by reference (so the orchestrator's context stays bounded); these land under `_workflow/` untracked. "Staged mode" is the §1.7 workflow-modifying-branch rule that routes edits to a staged mirror subtree until Phase 4 (see `## Interfaces and Dependencies`).

Deliverable of this track: the five edited `.claude/workflow/**` files, staged, so that after Phase-4 promotion the two operative commands use `git rm -rf` plus a follow-up `rm -rf`, and no prose still claims the recursive `git rm` sweeps untracked files.

## Plan of Work
The edits split into a command fix at the two operative sites and a prose reconciliation at the descriptive sites. All edits are staged (see `## Interfaces and Dependencies`); none touches the live `.claude/workflow/**` tree during Phase B.

Command fix (both operative sites). At `create-final-design.md` § Step 6 and `workflow.md` § Final Artifacts, change the single `git rm -r docs/adr/<dir-name>/_workflow/` to `git rm -rf docs/adr/<dir-name>/_workflow/` and add a follow-up `rm -rf docs/adr/<dir-name>/_workflow/` on the next line, with a one-line rationale that names why both are needed: `-f` deletes the tracked-but-modified `design-mutations.md`, and the `rm -rf` clears the untracked cold-read output, per-round params, and `.pyc` remnants that `git rm` never reaches. Keep both sites in step — they are documented as mirrors, so a change to one without the other reintroduces a contradiction.

Preserve two existing contracts at the operative sites. First, the single-cleanup-commit contract: the added `rm -rf` operates on files that are untracked or already staged for deletion, so it introduces no second commit — the cleanup stays one commit. Second, keep the existing warning against a separate `plan/*`-globbing removal: a bare `plan/*` glob would catch the `plan/track-N.md` files, which the blanket recursive delete already handles. The fix leaves that reasoning unchanged.

Prose reconciliation (descriptive sites). At `create-final-design.md:617` and `workflow.md:769`, correct the "sweeps the review-file directories automatically" claim: the recursive `git rm -rf` sweeps *tracked* files, and the follow-up `rm -rf` removes the *untracked* remnants — state both so the prose matches the command. At `commit-conventions.md:153`, `conventions-execution.md:372` / `:747`, and `mid-phase-handoff.md:493`, update the descriptive `git rm -r` mentions to reflect the `-rf` + `rm -rf` shape so no doc still describes the buggy bare command. These are descriptive rows and prose, so the edits are word-level, not structural.

Ordering: the operative-command fix and the prose reconciliation are independent and can land in any order within the track; the acceptance grep in `## Validation and Acceptance` checks the union.

## Concrete Steps

1. Fix both operative Phase-4 cleanup command sites and reconcile every descriptive mention. At `create-final-design.md` § Step 6 and `workflow.md` § Final Artifacts, change `git rm -r docs/adr/<dir-name>/_workflow/` to `git rm -rf docs/adr/<dir-name>/_workflow/` and add a follow-up `rm -rf docs/adr/<dir-name>/_workflow/` (with a one-line rationale: `-f` deletes the tracked-modified `design-mutations.md`; the `rm -rf` clears the untracked cold-read output / per-round params / `.pyc` remnants `git rm` never reaches). Keep both operative sites in step (they are documented mirrors) and preserve the single-cleanup-commit contract and the existing `plan/*`-glob warning. Correct the "sweeps automatically" prose at `create-final-design.md:617` and `workflow.md:769`, and reconcile the descriptive mentions at `commit-conventions.md:153`, `conventions-execution.md:372` / `:747`, and `mid-phase-handoff.md:493` so no doc still claims the recursive `git rm` sweeps untracked files. All edits route to the §1.7 staged mirror (five files under `.claude/workflow/`). — risk: medium (bounded behavioral workflow edit: changes the Phase-4 cleanup command the orchestrator executes and adds a force-delete `rm -rf`; no gate/dispatch/schema change so not HIGH, not meaning-preserving so above the prose-only LOW cap) — size: ~5 files; (a) no mergeable low/medium work fits — this is the entire single-track change  [ ]

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
Acceptance is verified by grep over the edited files plus a documented Phase-4 dry-run check; there is no automated test for a workflow-prose change. Each check below maps 1:1 to an entry in `## Invariants & Constraints`.

- After the edits, `grep -rnE "git rm -r([^f]|$)" .claude/workflow` returns **no match** — no bare `git rm -r` (without `-f`) remains in any shape at any in-scope site. This pattern matches `git rm -r` followed by a space, a backtick, or end-of-line and excludes `git rm -rf`, so it spans all three bug shapes a narrow `docs/adr`-only grep misses: the operative `git rm -r docs/adr/<dir-name>/_workflow/`, the descriptive `git rm -r _workflow/` (`conventions-execution.md:372` / `:747`), and the descriptive `` `git rm -r`s `` (`mid-phase-handoff.md:493`) — plus the two operative sites and `create-final-design.md:617` / `commit-conventions.md:153`. The benign forward-pointer mentions (`workflow.md:695` / `:757`, `conventions-execution.md:383`) carry no `-r` and are correctly not matched; the out-of-scope fixture lives under `.claude/scripts/tests/`, outside the grep's `.claude/workflow` scope. (Verified by grep against the staged subtree during Phase B, and against the live tree after Phase-4 promotion.)
- Both operative command blocks carry `git rm -rf docs/adr/<dir-name>/_workflow/` followed by `rm -rf docs/adr/<dir-name>/_workflow/`, with a one-line rationale. (Verified by reading `create-final-design.md` § Step 6 and `workflow.md` § Final Artifacts.)
- No descriptive site still claims the recursive `git rm` sweeps untracked files: `create-final-design.md:617`, `workflow.md:769`, `commit-conventions.md:153`, `conventions-execution.md:372`, `conventions-execution.md:747`, and `mid-phase-handoff.md:493` each read consistently with the `-rf` + `rm -rf` shape. (Verified by reading each site.)
- On a Phase-4 run with a modified tracked file and untracked files under `_workflow/`, the cleanup completes with `git status` clean and no `_workflow/` content (tracked or untracked) left on disk. (Verified by a documented Phase-4 dry-run / acceptance check.)
- The single-cleanup-commit contract holds: the fix adds no second commit. (Verified by reading the command block — the `rm -rf` runs before the single `git commit`.)

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
In scope — five files, all under `.claude/workflow/`:
- `prompts/create-final-design.md` (operative § Step 6 command + descriptive line 617).
- `workflow.md` (operative § Final Artifacts command + descriptive line 769).
- `commit-conventions.md` (descriptive line 153).
- `conventions-execution.md` (descriptive lines 372 and 747).
- `mid-phase-handoff.md` (descriptive line 493).

Out of scope:
- `.claude/scripts/tests/fixtures/review-file-valid-strategic.md` — the sixth `git rm` grep match is illustrative fixture text, not a real instruction.
- No `edit-design` `SKILL.md` change — the robust-cleanup command supersedes the older "skip the `design-mutations.md` log write" alternative (D1 rejects it), so the SKILL.md logging behavior stays as is.

Inter-track dependencies: none. This is a single-track (minimal) change.

§1.7 staging routing (this is a workflow-modifying branch in staged mode; see D3 for why staged over the §1.7(k) opt-out). During Phase B, edits route to the staged mirror at `docs/adr/workflow-scaffolding-fix/_workflow/staged-workflow/.claude/workflow/...` (each edited file at its mirrored path, e.g. `.../staged-workflow/.claude/workflow/prompts/create-final-design.md`). The live `.claude/workflow/**` files stay at their `develop` state through Phase B and C. The Phase-4 promote-staged-workflow commit (Step 4 of `create-final-design.md`) copies the staged subtree onto the live tree, so the fix becomes live only at promotion. The staged-mode self-application consequence for this branch's own Phase 4 — and why it does not affect this branch — is in D3's Risks/Caveats.

Re-validation: re-check the five edit sites against `develop` after any rebase before Phase-4 promotion, in case a later `develop` commit moved or partially edited them.

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9). Per-track testable constraints and
invariants, each backed by a check. -->
- After the Phase-4 cleanup step runs, `git status` is clean and no `_workflow/` content — tracked or untracked — remains on disk — verified by a Phase-4 dry-run / documented acceptance check.
- No bare `git rm -r` (without `-f`) remains for the `_workflow/` cleanup at **any** in-scope site (operative or descriptive) — verified by `grep -rnE "git rm -r([^f]|$)" .claude/workflow` returning no match. This pattern spans every bug shape (`git rm -r docs/adr/…`, `git rm -r _workflow/`, `` `git rm -r`s ``) and excludes `git rm -rf`, so a partial fix that updated only the operative sites cannot pass while a descriptive site still carries the bare command.
- The single-cleanup-commit contract holds: the fix adds no extra commit; the `rm -rf` of untracked files runs before the single `git commit` — verified by reading the command block.
- The existing warning against a separate `plan/*`-globbing removal is preserved — verified by reading the § Step 6 / § Final Artifacts prose (the caution against globbing `plan/*` still stands).
