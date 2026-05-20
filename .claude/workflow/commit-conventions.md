# Commit Message Conventions — Workflow

Extends the project's base commit conventions (see `CLAUDE.md` §Git
Conventions). These conventions apply during Phase 3 execution and enable
reliable session resume by making commit types identifiable in `git log`.

During execution both **code changes** and **workflow-file changes**
under `docs/adr/<dir-name>/_workflow/` (the plan, design,
track files, design-mutations log) are committed. Workflow files are
tracked under `_workflow/` for the branch lifetime and are removed in
the Phase 4 cleanup commit before the PR is merged. See
`conventions.md` §1.2 for the directory layout and `workflow.md`
§ Final Artifacts for the cleanup procedure.

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

### Push failure handling

After every per-commit `git push`, inspect the exit code and stderr.
Distinguish two failure shapes; do not silently treat them the same.

- **Non-fast-forward rejection** (`! [rejected] ... (non-fast-forward)`).
  The branch has diverged from `origin`. On the first occurrence
  in the session, load
  [`branch-divergence-check.md`](branch-divergence-check.md) and
  follow it; apply the user's chosen resolution and do not silently
  retry across subsequent commits. The harness git-safety protocol
  forbids unauthorised `--force-with-lease`, so the resolution must
  come from the user. If the session has already routed through the
  gate once (e.g., the user chose **Defer**), subsequent rejections
  are expected — record and continue.
- **Any other push failure** (network, auth, pre-receive hook,
  large-file rejection). Record the failure and continue with the
  next phase action; do not block phase progress on a transient
  push error. The session-end summary's unpushed-commit report
  (see `workflow.md` § What to do before ending a session) makes
  the residue visible.

A silent `git push` failure that goes unreported defeats all three
guarantees above — team visibility, disk-loss backup, and the
safety-net intent.

---

## Ephemeral-identifier pre-commit gate

Before issuing `git commit` on any change that touches paths
outside `docs/adr/*/_workflow/` and `.claude/workflow/`, run the
narrowed grep over the `+`-prefixed additions of the staged diff:

```bash
git diff --cached -- ':(exclude)docs/adr/*/_workflow/**' ':(exclude).claude/workflow/**' | grep -nE '^\+.*\b(Track|Step)[ ]?[0-9]+|^\+.*\b[A-Z]{1,3}-?[0-9]+\b'
```

The `.claude/workflow/**` exclusion mirrors the `_workflow/`
exclusion: the workflow rule docs themselves cite Track / Step /
finding-ID examples by necessity (see
[`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md)
§Forbidden), so the gate would always fire on docs-only edits in
that area.

If the grep returns matches that aren't allowed exceptions (issue
tracker IDs like `YTDB-NNN`, class / method / field names, file
paths; see
[`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md)
§Allowed), load that file and rewrite each genuine leak per its
§"How to rewrite a forbidden reference" section. Re-stage and
re-run the grep until only allowed exceptions remain.

The regex covers the three label-shaped forbidden classes only.
Ephemeral iteration counters (`iteration 1`, `round 2`) and
named-only plan invariants (`Single-authority invariant`,
`Load-bearing-file rule`) still require a manual eyeball pass
over the staged diff before commit; see
[`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md)
§Forbidden for the full list.

The implementer sub-agent wires this gate into sub-step 3 of
[`implementer-rules.md`](implementer-rules.md) (§"Pre-commit gate,
ephemeral-identifier check") so every `/execute-tracks` commit
runs it automatically. Manual commits outside the workflow (fix-up
edits, branch hygiene, ad-hoc tweaks) must run the same gate by
hand. Branch-only commit messages remain exempt regardless: they
are squashed away on merge (see "Branch-only commit messages may
cite workflow-internal identifiers" below in §"Commit type
prefixes").

---

## Commit type prefixes

| Commit type | Message pattern | Example |
|---|---|---|
| **Step implementation** | Imperative summary of the change | `Add histogram header to leaf page` |
| **Review fix** | `Review fix:` prefix — produced by the implementer at both `level=step` (Phase B dim-review fix) and `level=track` (Phase C track-level fix). Phase B vs Phase C is distinguished by **position** (Phase C fix commits appear strictly after the last episode commit). | `Review fix: extract validation to helper method` |
| **Step rollback** | `Revert step:` prefix | `Revert step: add histogram header to leaf page` |
| **Workflow update** | Imperative summary of the workflow-file change (no special prefix; commit only touches paths under `_workflow/`) | `Add initial implementation plan and design`, `Phase A review and decomposition for histogram track`, `Apply pre-Phase-A amendments to Track 2`, `Record Phase B base commit for histogram track`, `Record episode for histogram leaf write step`, `Apply plan corrections from histogram-leaf review`, `Mark histogram-leaf track complete`, `Inline replan after Track 2`, `Record Phase C iteration 1 for histogram track`, `Record Phase C iteration failure for histogram track` |
| **Phase 4 final** | `Add final design and ADR` (the standard final-artifacts commit) | (defined verbatim in `prompts/create-final-design.md` § Step 4) |
| **Phase 4 cleanup** | `Remove workflow scaffolding` — single commit that runs `git rm -r docs/adr/<dir>/_workflow/` after the final-artifacts commit | (see `workflow.md` § Final Artifacts) |

**Step rollback (`Revert step:`) commits** are produced **only by the
Phase B orchestrator** when a `FIX_REVIEW_FINDINGS` respawn at
`level=step` returns a non-`SUCCESS` result. The orchestrator runs
`git revert -n {step_base_commit}..HEAD` to stage the reversal of all
step-related commits (the original implementer commit plus any prior
`Review fix:` commits from the same dim-review loop), then commits
once with the `Revert step:` prefix. **Phase C never produces
`Revert step:` commits** — Phase C does not roll back across
iterations on a `FAILED` return; the failed iteration is treated as
a no-op and the loop exits with the unfixed findings surfaced to the
user at track completion (see
[`track-code-review.md`](track-code-review.md) §Phase C Implementer
Handlers).

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
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Post-Commit Handlers for the full procedure and resume-dispatch table.

Prose produced by this file follows the project house-style at
`.claude/output-styles/house-style.md`. Tier A (full house-style:
BLUF lead, banned vocabulary, em-dash discipline, ≤200-word section
cap, structural rules) applies to commit message bodies — the
long-form `why` block beneath the imperative subject line — and the
`reason:` slug body lines. The four banned-section heading slugs to
apply are `## Banned vocabulary`, `## Banned sentence patterns`,
`## Banned analysis patterns`, and `### Em-dash discipline`.
See [conventions.md §1.5 Writing style for Markdown and prose artifacts](conventions.md) for the workflow-level pointer.

**Branch-only commit messages may cite workflow-internal identifiers.**
Individual commit messages on the development branch (`Track N`,
`Step N`, `Track N Step M`, review finding IDs like `CQ33`, review
iteration counters, named plan invariants) are squashed away on
merge — the squashed commit message is built from the PR title and
body, not from the individual commit messages, so these references
do not survive into `develop`. The Ephemeral identifier rule (full
rule in
[`ephemeral-identifier-rule.md`](ephemeral-identifier-rule.md); stub
in [`conventions-execution.md`](conventions-execution.md) §2.3)
applies to durable content (source code, tests, PR title and body,
`design-final.md`, `adr.md`). For `Review fix:` commits, prefer
describing the fix by what changed (behavior, file, or class) over
citing the finding ID — but a finding-ID reference on the branch
is permitted when it makes the commit log easier to follow.

## How these are used on resume

The `git log` reading rules used by Phase B Resume — how to identify
orphan implementer commits, `Review fix:` commits, episode commits,
`Revert step:` commits, and scaffolding Workflow update commits —
live with the resume procedure itself in
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Resume-side commit-pattern reference. They are loaded only when
Phase B Resume runs.
