# `workflow-issues/` — Pending-Triage Buffer

Each file in this directory describes one workflow-process problem
encountered while running the YouTrackDB engineering workflow
(`/create-plan`, `/execute-tracks`, `/review-plan`, etc.). Files are
produced by the **self-improvement reflection** step that runs at the
end of every `/execute-tracks` session — see
[`.claude/workflow/self-improvement-reflection.md`](../.claude/workflow/self-improvement-reflection.md)
for the protocol.

This directory is a **branch-local pending-triage buffer**, not a
durable backlog. The implementer working on the branch is responsible
for moving each file into the project's real issue tracker (YouTrack)
and deleting it from the worktree. The directory must be empty before
the PR is merged — files are not intended to land on `develop`.

## Scope — what belongs here

- Ambiguous, contradictory, or missing workflow rules.
- Brittle automation (hooks, scripts, sub-agent prompts).
- Recurring frictions that cost the agent turns every session.
- Tooling gaps where a recipe should exist but does not.

What does **not** belong here:

- Code-quality findings — those land in step / track episodes via
  the dimensional review loop.
- Plan flaws — those go through inline replanning or
  `/review-plan`.
- General feature ideas unrelated to the workflow.

## File format

Filename: `<YYYY-MM-DD>-<short-slug>.md`. Frontmatter and section
template are documented in
[`self-improvement-reflection.md`](../.claude/workflow/self-improvement-reflection.md)
§"Issue file format".

## Triage procedure (implementer)

The implementer triages files at their own pace during the branch's
lifetime. For each file:

1. Read it. The frontmatter (`severity`, `phase`,
   `source-session`) plus the **Symptom**, **Reproduction context**,
   **Why it's a problem**, and **Proposed fix** sections are
   structured so the body can be pasted into a YouTrack issue
   description with minimal editing.
2. File a YouTrack issue. Include the workflow file path(s) named
   in **Reproduction context** and the **Proposed fix** verbatim
   (or refined as the implementer sees fit).
3. Delete the local file: `git rm
   workflow-issues/<YYYY-MM-DD>-<slug>.md`. Commit the deletion
   with a message such as `Triage <slug> to <YT-XXXX>`.

### Before merging the PR

Sweep the directory and confirm it is empty (or contains only files
the implementer has explicitly decided to defer to a follow-up
branch). Phase 4 of `/execute-tracks` reminds the user of this
sweep at the end of the final-artifacts session — but the
responsibility is the implementer's, not the workflow's.
