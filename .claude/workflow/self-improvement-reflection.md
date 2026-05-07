# Self-Improvement Reflection

A mandatory final step at the end of every `/execute-tracks` session. The
session-running agent reflects on what it just did and proposes 0..N
workflow-improvement issues. The user gates which proposals become files
under `workflow-issues/`.

`workflow-issues/` is a **branch-local pending-triage buffer**, not a
durable backlog. The implementer triages each file into the project's
real issue tracker (YouTrack), deletes the file as it is filed, and
ensures the directory is empty before merging the PR — files are not
intended to land on `develop`. See `workflow-issues/README.md` for the
triage procedure.

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

Reflection runs on every session that reached at least one phase
step (State 0, A, B, C, or 4) or that invoked the auto-resume /
strategy refresh logic. The friction that triggered an early exit
(context-window warning, ESCALATE, two-failure rule, designed-in
user gate at strategy refresh or State 0) is itself often the most
valuable finding. On a designed-in user gate the agent should
default to N=0 — unless the gate fired because the docs gave no
rule for the situation, in which case the gap is exactly what
reflection should record.

Reflection is skipped only when the auto-resume protocol could not
start any session work because of a missing prerequisite — e.g.,
the plan file does not exist, MCP cwd does not match, or the user
cancels at the startup prompt. In those cases there is no session
content to reflect on.

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

The bar is: *the user, looking only at the issue file, should be able
to file a tracker issue from it without re-deriving the context.* If
the agent cannot describe the reproduction context, the finding is
not yet ready to record — drop it or sharpen it.

---

## Per-session cap

At most **3** issues per session. If reflection turns up more than
three, keep the three highest-impact ones (highest severity, most
frequent, or blocking the most downstream work) and discard the rest.
Quality of the buffer matters more than completeness.

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

2. **Check filename collisions only.** List the `workflow-issues/`
   directory to find existing filenames so the new file's date+slug
   does not collide. Do **not** content-dedupe against existing files. If
   the same friction has already been filed and is still pending
   triage, file it again — the implementer dedupes at triage time.
   Cheap text-match dedup is fragile and not worth the protocol
   complexity.

3. **Draft proposals.** For each candidate issue (after capping at
   3), draft the title and a one-line summary. Do not write the
   file yet.

4. **Present to the user.** Single message, format:

   ```
   ## Self-improvement reflection

   N proposed issues:

   1. <title> — <one-line summary>
   2. <title> — <one-line summary>
   ...

   Reply with the issue numbers to write (e.g. "1,3"), "all", or
   "none". I will write the chosen issue files, commit, and end
   the session.
   ```

   If N=0, present:

   ```
   ## Self-improvement reflection

   No improvements proposed.
   ```

   and end the session.

5. **Write the chosen issues** as files under `workflow-issues/` (see
   format below). Stage and commit them in a single Workflow update
   commit — see "Commit format" below.

6. **End the session** per the phase's normal end-of-session
   instructions.

---

## Issue file format

Filename: `workflow-issues/<YYYY-MM-DD>-<short-slug>.md`. The date is
the session date. The slug is 3–8 lowercase words separated by dashes
that capture the friction (e.g.,
`phase-c-review-fix-loop-stalls-on-spotless`,
`risk-tagging-rule-misses-generated-code`). If the date+slug already
exists, append `-2`, `-3`, … rather than overwriting.

File contents:

```markdown
---
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

A bullet list the implementer (or whoever fixes the underlying
workflow problem in a future branch) can use to verify the issue
is closed:

- <Workflow change visible at <path>>
- <Reproduction context no longer triggers the symptom>
- <If applicable: regression check — e.g., grep that the bad pattern
  is gone>
```

Severity guide:

- `low` — annoyance, costs the agent a turn or two; workflow still
  produces correct output.
  - *Example*: an `mcp-steroid` recipe is referenced in two
    different docs but neither links to the canonical
    `.claude/docs/mcp-steroid/recipes.md` index, so the agent
    looks it up twice in one phase.
- `medium` — recurring friction or ambiguity that costs multiple
  turns per occurrence, or causes occasional wrong outputs that
  the user catches.
  - *Example*: a Phase A review sub-agent (technical, risk, or
    adversarial) repeatedly returns the same low-value finding
    that the orchestrator must override every iteration; no
    upstream filter exists.
- `high` — blocks a phase, causes silent wrong outputs, or pushes
  the agent into an unrecoverable state. A `high` finding should
  be rare and almost always points to a missing rule or a
  contradiction.
  - *Example*: the startup-protocol resume table fails to cover
    a real intermediate state (e.g., orphan implementer commit
    with no episode), and the agent re-runs the step instead of
    routing to the recovery procedure.

---

## Commit format

When the agent writes one or more issue files, stage and commit them
in **one** Workflow update commit, then push. The commit message follows the
imperative-summary form defined in `commit-conventions.md` § Commit
type prefixes (Workflow update row) — no `[workflow]` prefix, no
special tag:

```
Self-improvement reflection from <phase> of <adr-dir>

- workflow-issues/<file>.md (new)
```

Note that `commit-conventions.md` describes Workflow update commits
as touching paths under `_workflow/`; the reflection commit is the
only Workflow update commit that touches paths **outside**
`_workflow/` (it touches `workflow-issues/` at the repo root). Treat
it as a Workflow update commit for resume / orphan-detection
purposes.

Push the commit before ending the session so the draft PR carries
the new buffer state.

---

## What the agent must not do

- **Do not** auto-create or auto-edit issue files without user
  confirmation. Every file written or modified by reflection passes
  through the user gate in §step 4.
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
- **Do not** content-dedupe against existing `workflow-issues/`
  files. Filename collisions are the only thing the agent checks
  for; the implementer handles content dedup at triage time.
