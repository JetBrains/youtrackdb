# `workflow-issues/` — Workflow Self-Improvement Backlog

Each file in this directory describes one problem encountered while
running the YouTrackDB engineering workflow (`/create-plan`,
`/execute-tracks`, `/review-plan`, etc.). Files are produced by the
**self-improvement reflection** step that runs at the end of every
`/execute-tracks` session — see
[`.claude/workflow/self-improvement-reflection.md`](../.claude/workflow/self-improvement-reflection.md)
for the protocol.

The directory is durable: unlike `docs/adr/<dir-name>/_workflow/`
files, `workflow-issues/` is **not** removed by the Phase 4 cleanup
commit and survives merge into `develop`. A workflow issue filed in
one ADR branch is just as valid for the next ADR.

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

## Status field

- `open` — filed, not yet picked up.
- `in-progress` — an agent is working on the fix.
- `fixed` — workflow change has landed; file kept as a record of
  *why* the rule exists.
- `wontfix` / `superseded-by: <other>.md` — closed without a fix,
  with a one-line reason.

Files are not deleted when they reach `fixed`. Periodic cleanup
(every few months, by the user) can archive long-fixed files into
`workflow-issues/archive/`.

## Picking up a fix

Any agent — a fresh `/execute-tracks` session that finishes early, a
dedicated workflow-improvement session, or the user themselves — can
pick up an `open` issue. The procedure:

1. Set frontmatter `status: in-progress`.
2. Implement the workflow change described in **Proposed fix**, on a
   branch named after the issue or as part of an existing branch
   that touches the same area.
3. Verify the **Acceptance criteria**.
4. Set frontmatter `status: fixed` in the same commit that lands the
   workflow change. Reference the issue file path in the commit
   message.
