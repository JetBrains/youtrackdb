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

## How these are used on resume

When Phase B resumes and detects orphan commits (code committed but episode
not written to the step file on disk), it scans
`git log --oneline {base_commit}..HEAD` and uses these patterns:

1. **Review fix commits** — contain `Review fix:` → indicate the code
   review loop already ran for that step. Resume from episode production.
2. **Implementation commits** — anything else → code review loop has not
   run. Resume from code review.

The step file on disk is the source of truth for which steps are complete
(have episodes). Any code commits beyond the last completed step's work
are orphans for the next `[ ]` step.
