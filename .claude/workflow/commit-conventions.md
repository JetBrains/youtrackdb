# Commit Message Conventions — Workflow

Extends the project's base commit conventions (see `CLAUDE.md` §Git
Conventions). These conventions apply during Phase 3 execution and enable
reliable session resume by making commit types identifiable in `git log`.

During execution both **code changes** and **workflow-file changes**
under `docs/adr/<dir-name>/_workflow/` (the plan, backlog, design,
step files, review files, design-mutations log) are committed.
Workflow files are tracked under `_workflow/` for the branch lifetime
and are removed in the Phase 4 cleanup commit before the PR is
merged. See `conventions.md` §1.2 for the directory layout and
`workflow.md` § Final Artifacts for the cleanup procedure.

## Push every commit

After every commit on the branch — code commits, workflow-file
commits, episode commits, review-fix commits, revert commits, the
Phase 4 final-artifacts and cleanup commits — run `git push`
immediately. The branch carries a draft PR (created at the end of
Phase 1 by `/create-plan`); pushing every commit keeps the draft
in sync with local state for two reasons:

1. **Team visibility.** The draft PR is how teammates see the
   feature's progress in real time.
2. **Disk-loss backup.** Every committed state lives on GitHub, so
   a local crash or worktree loss never destroys progress.

Use `git push` (or `git push -u origin <branch>` on the first push
of a session). Use `git push --force-with-lease` if a rebase or
amend has rewritten history that was already pushed — never plain
`--force`. CI does not run on draft PRs, so the push frequency
carries no CI cost.

---

## Commit type prefixes

| Commit type | Message pattern | Example |
|---|---|---|
| **Step implementation** | Imperative summary of the change | `Add histogram header to leaf page` |
| **Review fix** | `Review fix:` prefix | `Review fix: extract validation to helper method` |
| **Step rollback** | `Revert step:` prefix | `Revert step: add histogram header to leaf page` |
| **Workflow update** | Imperative summary of the workflow-file change (no special prefix; commit only touches paths under `_workflow/`) | `Add initial implementation plan and design`, `Phase A review and decomposition for histogram track`, `Record Phase B base commit for histogram track`, `Record episode for histogram leaf write step`, `Apply plan corrections from histogram-leaf review`, `Mark histogram-leaf track complete`, `Inline replan after Track 2` |
| **Phase 4 final** | `Add final design and ADR` (the standard final-artifacts commit) | (defined verbatim in `prompts/create-final-design.md` § Step 4) |
| **Phase 4 cleanup** | `Remove workflow scaffolding` — single commit that runs `git rm -r docs/adr/<dir>/_workflow/` after the final-artifacts commit | (see `workflow.md` § Final Artifacts) |

**Step rollback (`Revert step:`) commits** are produced **only by the
Phase B orchestrator** when a `FIX_REVIEW_FINDINGS` respawn returns a
non-`SUCCESS` result. The orchestrator runs
`git revert -n {step_base_commit}..HEAD` to stage the reversal of all
step-related commits (the original implementer commit plus any prior
`Review fix:` commits from the same dim-review loop), then commits
once with the `Revert step:` prefix.

The body is load-bearing for Phase B Resume. The **first non-empty
body line MUST be** `reason: <slug>` where `<slug>` is one of:

| Slug | Trigger |
|---|---|
| `failed-review-fix` | The review-fix respawn returned `RESULT: FAILED` |
| `late-design-decision` | The review-fix respawn returned `RESULT: DESIGN_DECISION_NEEDED` |
| `late-risk-upgrade` | The review-fix respawn returned `RESULT: RISK_UPGRADE_REQUESTED` |

A blank line separates the slug from a one-sentence prose explanation
drawn from the relevant `fix_result.{FAILURE|DESIGN_DECISION|RISK_UPGRADE}`
field. The implementer never produces this commit type — it is
exclusively an orchestrator-side operation. See
[`step-implementation.md`](step-implementation.md) §Post-Commit
Handlers for the full procedure and resume-dispatch table.

**Branch-only commit messages may cite workflow-internal identifiers.**
Individual commit messages on the development branch (`Track N`,
`Step N`, `Track N Step M`, review finding IDs like `CQ33`, review
iteration counters, named plan invariants) are squashed away on
merge — the squashed commit message is built from the PR title and
body, not from the individual commit messages, so these references
do not survive into `develop`. The Ephemeral identifier rule in
[`conventions-execution.md`](conventions-execution.md) §2.3 still
applies to durable content (source code, tests, PR title and body,
`design-final.md`, `adr.md`). For `Review fix:` commits, prefer
describing the fix by what changed (behavior, file, or class) over
citing the finding ID — but a finding-ID reference on the branch
is permitted when it makes the commit log easier to follow.

## How these are used on resume

When Phase B resumes and detects orphan code commits (committed but
the matching episode commit is missing), it scans
`git log --oneline {base_commit}..HEAD` and uses these patterns:

1. **`Revert step:` commits** — the previous session rolled the step
   back after a non-`SUCCESS` review-fix attempt. The next `[ ]` step
   was already cleanly returned to its pre-implementation state by
   the revert. Read the `reason: <slug>` line in the body and
   dispatch per the table in
   [`step-implementation.md`](step-implementation.md) §Phase B
   Resume — the bookkeeping differs by slug (write `[!]` for
   `failed-review-fix`; respawn-and-rederive for
   `late-design-decision`; verify-or-apply-upgrade for
   `late-risk-upgrade`).
2. **Episode commits** (`Record episode for …`, Workflow update
   touching only `_workflow/tracks/track-<N>.md`) — mark the
   boundary between completed and in-progress steps. The most
   recent episode commit is the last fully-finished step.
3. **`Review fix:` commits** — indicate the dim-review loop already
   ran for the step they belong to. When an orphan `Review fix:`
   commit appears after the last episode commit, resume from
   episode production.
4. **Implementer code commits** — touch code (paths outside
   `_workflow/`); subject is the imperative summary of the step's
   change. When an orphan implementer commit appears after the
   last episode commit without any `Review fix:` siblings, resume
   from the dimensional review loop.
5. **Other Workflow update commits** — touch only `_workflow/`
   but are not episode commits (Phase 1 init, Phase A
   decomposition, Phase B base-commit recording, plan-corrections
   application, track-completion mark, inline-replanning update).
   They are scaffolding and **not** orphans regardless of
   position.

The step file on disk is the source of truth for which steps are complete
(have episodes). Any implementer or `Review fix:` code commits beyond
the last episode commit are orphans for the next `[ ]` step. A
`Revert step:` commit cancels the implementer + `Review fix:` commits
it reverts — together they form a self-contained "attempted and rolled
back" group that does not count toward any `[x]` step's expected
commits.
