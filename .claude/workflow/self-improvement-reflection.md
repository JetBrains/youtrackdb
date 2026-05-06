# Self-Improvement Reflection

A mandatory final step at the end of every `/execute-tracks` session. The
session-running agent reflects on what it just did and proposes 0..N
durable workflow-improvement issues. The user gates which proposals
become files under `workflow-issues/`. Future agents pick those files
up and fix the underlying problems, so the workflow itself improves
over time.

This is **process feedback**, not code review and not plan correction.
Code findings belong to the dimensional review loop. Plan flaws belong
to inline replanning or the plan-review pass. Reflection only captures
problems with the workflow itself: ambiguous instructions, missing
recipes, brittle automation, recurring frictions, gaps where a future
agent would have benefited from a rule that did not exist.

---

## When it runs

As the **last step** of every `/execute-tracks` session, immediately
before "End the session". Applies to:

- State 0 (autonomous plan review)
- Phase A (review + decomposition)
- Phase B (step implementation)
- Phase C (code review + track completion)
- Phase 4 (final artifacts)

Reflection runs even when the phase ended on a context-window
warning, an ESCALATE, or any other early-exit path — the friction
that triggered the early exit is itself often the most valuable
finding.

Reflection does **not** run if the session was a pure no-op (e.g., the
agent read the plan, decided the user must override, and stopped before
doing real work). In that case there is nothing to reflect on.

---

## What counts as a worth-recording issue

In scope (record):

- Workflow document was ambiguous, contradicted itself, or sent the
  agent down a dead end.
- A workflow rule was missing — the agent had to invent a one-off
  decision because the docs did not cover the case.
- Automation was brittle: a script timed out, a hook misfired, a tool
  routed the agent to a stale code path, a recipe failed in a way that
  required manual intervention.
- The agent had to repeat the same correction more than once in a
  session because no rule prevented it.
- A sub-agent prompt produced output that the orchestrator had to
  rewrite or reject in a recurring way.
- Tooling gap: the agent reached for a recipe that should exist
  (`mcp-steroid` recipe, build script, helper) and had to roll it
  manually.
- A reviewer (Phase A or Phase C sub-agent) repeatedly raised the
  same low-value finding the workflow could have prevented upstream.

Out of scope (do not record here):

- Code-quality findings — those belong to the dimensional review loop
  output and the step / track episodes.
- Plan flaws — those go through inline replanning or are surfaced in
  State 0 plan review.
- "Future feature" ideas that did not actually bite this session.
- One-off transient failures (CI flake, network blip) with no
  workflow-level fix.
- General "I think the project should …" opinions unrelated to the
  workflow that produced them.

The bar is: *another agent, looking only at the issue file, should be
able to reproduce the friction or at least find the spot in the
workflow that needs editing.* If the agent cannot describe the
reproduction context, the finding is not yet ready to record — drop
it or sharpen it.

---

## Per-session cap

At most **3** issues per session. If reflection turns up more than
three, keep the three highest-impact ones (highest severity, most
frequent, or blocking the most downstream work) and discard the rest.
Quality of the backlog matters more than completeness.

If reflection turns up zero, that is the expected outcome on a smooth
session. Say so explicitly to the user and end the session.

---

## Reflection procedure

1. **Scan the session.** Walk back through what was done in this
   phase: which workflow doc steps were followed, what the sub-agents
   returned, where the agent had to deviate, where automation
   misbehaved, where a decision had to be made without a rule. Two
   prompts to ask:
   - *"What was harder than it should have been?"*
   - *"What would I want a future agent in my exact position to know,
     that the current docs do not tell them?"*

2. **Read the existing backlog.** List `workflow-issues/*.md` and read
   the title + first paragraph of each open issue. If the friction the
   agent just hit is already filed, **do not duplicate** — instead,
   append a one-line "Recurrence:" note to the existing issue with
   the current date, phase, and ADR directory name. Recurrence
   appends are not gated by the user — they are evidence that the
   issue is still live.

3. **Draft proposals.** For each candidate issue (after dedup, after
   capping at 3), draft the title and a one-line summary. Do not
   write the file yet.

4. **Present to the user.** Single message, format:

   ```
   ## Self-improvement reflection

   N proposed issues:

   1. <title> — <one-line summary>
   2. <title> — <one-line summary>
   ...

   Recurrences logged: M existing issues touched.

   Reply with the issue numbers to write (e.g. "1,3"), "all", or
   "none". I will write the chosen issue files, commit, and end
   the session.
   ```

   If N=0 and M=0, present:

   ```
   ## Self-improvement reflection

   No improvements proposed.
   ```

   and end the session.

5. **Write the chosen issues** as files under `workflow-issues/` (see
   format below). Stage and commit them in a single workflow commit
   alongside any recurrence-note edits — see "Commit format" below.

6. **End the session** per the phase's normal end-of-session
   instructions.

---

## Issue file format

Filename: `workflow-issues/<YYYY-MM-DD>-<short-slug>.md`. The date is
the session date. The slug is 3–6 lowercase words separated by dashes
that capture the friction (e.g.,
`phase-c-review-fix-loop-stalls-on-spotless`,
`risk-tagging-rule-misses-generated-code`). If the date+slug already
exists, append `-2`, `-3`, … rather than overwriting.

File contents:

```markdown
---
status: open
severity: low|medium|high
phase: state-0|phase-a|phase-b|phase-c|phase-4
source-session: <YYYY-MM-DD> /execute-tracks <adr-dir-name>
---

# <Issue title>

## Symptom

What the agent observed during the session, in one short paragraph.
Concrete, not abstract — name the doc, the step, the tool, the
sub-agent.

## Reproduction context

- Phase: <state-0 / phase-a / phase-b / phase-c / phase-4>
- Workflow doc(s) involved: `path/to/doc.md` §Section
- Tool / sub-agent involved (if any): <name>
- ADR directory at the time: `docs/adr/<dir-name>/`
- Trigger condition: <what kicks this off — e.g., "any Phase B step
  whose implementer return value is non-SUCCESS">

## Why it's a problem

One short paragraph on the impact: wasted turns, wrong outputs,
silent failures, blocked sessions, recurring corrections.

## Proposed fix

A specific, actionable change. Edit `<file>` §<section> to <do X>.
Or add a new recipe in `<file>` for <Y>. Or split <doc> into <A> and
<B>. If multiple options exist, list them with one-line trade-offs.

## Acceptance criteria

A bullet list a future fixer can use to verify the issue is closed:

- <Workflow change visible at <path>>
- <Reproduction context no longer triggers the symptom>
- <If applicable: regression check — e.g., grep that the bad pattern
  is gone>

## Recurrences

(Empty on first creation. Future sessions append `- <YYYY-MM-DD>
phase=<x> adr=<dir>` lines when they hit the same friction.)
```

Severity guide:

- `low` — annoyance, costs the agent a turn or two; workflow still
  produces correct output.
- `medium` — recurring friction or ambiguity that costs multiple turns
  per occurrence, or causes occasional wrong outputs that the user
  catches.
- `high` — blocks a phase, causes silent wrong outputs, or pushes the
  agent into an unrecoverable state. A `high` finding should be rare
  and almost always points to a missing rule or a contradiction.

---

## Commit format

When the agent writes any issue files (new or recurrence-note edits),
commit them in **one** workflow commit with this message:

```
[workflow] Self-improvement reflection from <phase> of <adr-dir>

- workflow-issues/<file>.md (new)
- workflow-issues/<file>.md (recurrence note)
```

The commit follows the standard workflow-file commit convention from
`commit-conventions.md`. Push it before ending the session so the
draft PR carries the new backlog state.

---

## Lifecycle of a `workflow-issues/` file

1. **Open** — created by reflection. Anyone (a future
   `/execute-tracks` session, a manual workflow-improvement session,
   the user) can pick it up.
2. **In-progress** — set frontmatter `status: in-progress` when an
   agent starts working on the fix. The fixer references the issue
   file path in their commit message.
3. **Fixed** — set frontmatter `status: fixed` once the workflow
   change has landed and the acceptance criteria are met. The issue
   file is **not** deleted — it stays as a record of *why* a workflow
   rule exists. Periodic cleanup (every few months, by the user) can
   archive long-fixed files into `workflow-issues/archive/`.
4. **Wontfix / superseded** — set frontmatter `status: wontfix` or
   `status: superseded-by: <other-file>.md` with a one-line note in
   the body. Do not delete.

`workflow-issues/` files are versioned in git on the same branch as
the rest of the workflow scaffolding. Unlike files under
`docs/adr/<dir-name>/_workflow/`, they are **not** removed by the
Phase 4 cleanup commit — they are durable across branches and
survive merge into `develop`. This is intentional: a workflow issue
filed in one ADR is just as valid for the next ADR.

---

## What the agent must not do

- **Do not** auto-create issue files without user confirmation
  (recurrence-note edits on existing files are the only exception).
- **Do not** spawn a sub-agent for reflection. It is a single
  main-agent step; sub-agent overhead is not justified.
- **Do not** treat reflection as a place to dump code-review
  findings, plan corrections, or general project ideas. Stay on
  workflow-process problems.
- **Do not** exceed the 3-issue cap. If more bubble up, pick the
  top three and let the rest go — they will resurface naturally
  if they really matter.
- **Do not** skip reflection on early-exit sessions (context warning,
  ESCALATE, two-failure rule). The friction that caused the early
  exit is usually the highest-value input.
