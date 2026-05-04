# Commit Message Conventions — Workflow

Extends the project's base commit conventions (see `CLAUDE.md` §Git
Conventions). These conventions apply during Phase 3 execution and enable
reliable session resume by making commit types identifiable in `git log`.

Only **code changes** are committed during execution. Workflow working files
(step files, review files, plan file) are never committed — they persist on
disk between sessions. See `conventions.md` §1.2 for the tracking model.

---

## Commit type prefixes

| Commit type | Message pattern | Example |
|---|---|---|
| **Step implementation** | Imperative summary of the change | `Add histogram header to leaf page` |
| **Review fix** | `Review fix:` prefix | `Review fix: extract validation to helper method` |
| **Step rollback** | `Revert step:` prefix | `Revert step: add histogram header to leaf page` |

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

**Commit messages must not cite workflow-internal identifiers**
(`Track N`, `Step N`, `Track N Step M`, review finding IDs like `CQ33`,
review iteration counters, named-only plan invariants). These live in
untracked files that are deleted with the branch. See
[`conventions-execution.md`](conventions-execution.md) §2.3 for the full
rule. For `Review fix:` commits, describe the fix by what changed
(behavior, file, or class) — not by the finding ID that triggered it.

## How these are used on resume

When Phase B resumes and detects orphan commits (code committed but episode
not written to the step file on disk), it scans
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
2. **`Review fix:` commits** — indicate the dim-review loop already
   ran for that step. Resume from episode production.
3. **Implementation commits** — anything else → dim-review loop has
   not run. Resume from review.

The step file on disk is the source of truth for which steps are complete
(have episodes). Any code commits beyond the last completed step's work
are orphans for the next `[ ]` step. A `Revert step:` commit cancels
the implementer + `Review fix:` commits it reverts — together they form
a self-contained "attempted and rolled back" group that does not count
toward any `[x]` step's expected commits.
