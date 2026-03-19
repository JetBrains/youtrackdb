# Commit Message Conventions — Workflow

Extends the project's base commit conventions (see `CLAUDE.md` §Git
Conventions). These additional conventions apply during Phase 3 execution
and enable reliable session resume by making commit types identifiable
in `git log`.

---

## Commit type prefixes

All commits follow the base `YTDB-NNN:` prefix rule. The **second part**
of the message (after the YTDB prefix) uses these conventions:

| Commit type | Message pattern | Example |
|---|---|---|
| **Step implementation** | Imperative summary of the change | `YTDB-605: Add histogram header to leaf page` |
| **Review fix** | `Review fix:` prefix | `YTDB-605: Review fix: extract validation to helper method` |
| **Episode** | `Write Track N episode` or `Write Step N episode` | `YTDB-605: Write Step 3 episode and mark complete` |
| **Step file update** | Describes the metadata change | `YTDB-605: Mark Track 1 Phase A complete` |

## How these are used on resume

When Phase B resumes and detects orphan commits (code committed but no
episode), it scans `git log --oneline {base_commit}..HEAD` and uses
these patterns:

1. **Episode commits** — contain `Write ... episode` → used to find the
   boundary of the last completed step.
2. **Review fix commits** — contain `Review fix:` → indicate the code
   review loop already ran. Resume from episode production (sub-step 5).
3. **Implementation commits** — anything else that's not a step
   file/episode update → code review loop has not run. Resume from code
   review (sub-step 4).
