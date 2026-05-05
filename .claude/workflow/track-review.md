# Track Execution — Phase A: Review + Decomposition

## Overview

This document covers Phase A only. A track goes through three sub-phases,
each executed in a **separate session**:

1. **Phase A: Review + Decomposition** — this document (current session)
2. **Phase B: Step Implementation** — see
   [`step-implementation.md`](step-implementation.md) (next session)
3. **Phase C: Code Review + Track Completion** — see
   [`track-code-review.md`](track-code-review.md) (session after Phase B)

Phase C includes both the track-level code review and track completion
(episode compilation, plan corrections, user approval) in a single session.

---

## Phase A: Review + Decomposition

> **In this phase, you are a reviewer and planner, not an implementer. You
> NEVER edit source code, test files, or build files. You explore the
> codebase (read-only) to validate the track's approach and decompose it
> into steps. The only files you write are: the step file
> (`tracks/track-N.md`) and review files (`reviews/track-N-*.md`).**

### Tooling — PSI is required for symbol audits in Phase A

Phase A's outputs (review findings, scope-indicator validation, step
decomposition with risk tags) ride on reference-accuracy facts —
"this method's callers", "this interface's implementations", "no
existing consumer for this slot". When mcp-steroid is reachable per
the SessionStart hook, those facts MUST come from PSI find-usages
rather than grep — see [`conventions.md`](conventions.md) §1.4
*Tooling discipline* for the full rule (preflight via
`steroid_list_projects`, cwd-mismatch handling, fallback when
unreachable). Run the preflight once before the first symbol audit;
do not re-probe.

The Phase A review sub-agents you spawn (technical, risk, adversarial)
all default to grep unless their prompts explicitly route them to
PSI. The canonical prompts under `prompts/` already include this
instruction — keep it intact when customising.

### What You Do

1. **Assess track complexity** to determine which reviews to run (see
   §Complexity Assessment below).

2. **Move the track description into the step file** (Phase A's first
   on-disk write — this extends today's "create the step file early"
   pattern so reviews can update the Reviews section and Phase B/C
   sub-agents find the description in the file they already read).

   a. **Read the plan file** for strategic context (Goals, Architecture
      Notes, Decision Records, Component Map) and the current track's
      intro paragraph.

   b. **Read Track N's section from the backlog**
      (`docs/adr/<dir-name>/_workflow/implementation-backlog.md`). Read the
      track's intro paragraph from the plan-file entry and the
      `**What/How/Constraints/Interactions**` subsections + any
      track-level `mermaid` block from the backlog's `## Track N:
      <title>` section. The section body is defined by the "Backlog
      section body extraction rule" in `conventions-execution.md`
      §2.1.

   c. **Create the step file atomically** at
      `docs/adr/<dir-name>/_workflow/tracks/track-N.md` in a single Write call
      that contains: `## Description` (populated with the copied intro
      + `**What/How/Constraints/Interactions**` subsections + any
      track-level diagram from sub-step (b)), `## Progress` (with all
      three entries as `[ ]`: `Review + decomposition`, `Step
      implementation`, `Track-level code review`), an empty
      `## Reviews completed`, and an empty `## Steps` placeholder.
      Items 4–5 below populate `## Steps`; Phase B writes
      `## Base commit` at its session start. Do NOT create an empty
      shell and append the description in a second Write — the single
      atomic Write closes the window in which the description has been
      pulled out of its source but has not yet landed on disk.
      Subsequent phases may Edit the file normally; only the initial
      creation must be atomic.

   d. **Remove Track N's section from the backlog.** Delete per the
      "Backlog section body extraction rule" in
      `conventions-execution.md` §2.1 — that rule states the
      header-boundary algorithm and the line-count-deletion
      prohibition once as the single authoritative source. Preserve
      the backlog's opening `# <Feature> — Track Details` header.

      When the last remaining `## Track M:` section is removed, leave
      the backlog file on disk with only its header. The whole
      `_workflow/` directory (including the empty backlog) is deleted
      by the Phase 4 cleanup commit; no per-track removal is needed.

3. **Run track-scoped reviews** as sub-agents (technical, risk, adversarial
   as warranted). After each review completes:
   - Write the review file to `docs/adr/<dir-name>/_workflow/reviews/track-N-<type>.md`
   - Update the **Reviews completed** section in the step file
     (created atomically in sub-step 2c).
   - These files persist on disk between sessions — the next session can
     skip completed reviews and only re-run missing ones.
   - **Context consumption check** (mandatory after each review, except
     after the last action of the phase): run
     `cat /tmp/claude-code-context-usage-$PPID.txt`. If the level is
     `warning` (≥25%) or `critical` (≥40%), do NOT start the next review
     or decomposition. Save all work and ask the user for a session
     refresh (see workflow.md §Context Consumption Check). If the level
     is `safe`/`info`, continue. If the file does not exist or the
     command fails, this is **not an error** — treat as `safe` and
     continue.
4. **Decompose scope indicators** into concrete steps. For each step,
   assign a **risk tag** (`low` / `medium` / `high`) per the criteria
   in [`risk-tagging.md`](risk-tagging.md) — load that file at the
   start of decomposition. The tag controls whether Phase B runs
   step-level dimensional review for the step.
5. **Write decomposed steps** to the step file's `## Steps` section as
   `[ ]` items, each with its `**Risk:**` line in the description
   blockquote. Mark `Review + decomposition` as `[x]` in the Progress
   section.

6. **Commit and push the Phase A workflow updates.** All of Phase A's
   on-disk writes — the step file (created in 2c, populated in step 5),
   review files (written in step 3), and the backlog edit (the
   Track N section removal in 2d) — must be committed before Phase B
   spawns the first implementer for this track. The implementer's
   revert path uses `git reset --hard HEAD`, which would otherwise
   discard the uncommitted decomposition.

   ```bash
   git add docs/adr/<dir-name>/_workflow/tracks/track-<N>.md \
           docs/adr/<dir-name>/_workflow/reviews/track-<N>-*.md \
           docs/adr/<dir-name>/_workflow/implementation-backlog.md
   git commit -m "Phase A review and decomposition for <track>"
   git push
   ```

   This is a single Workflow update commit per the table in
   `commit-conventions.md` § Commit type prefixes. Stage explicit
   paths only — never `git add -A` — so unrelated files in the
   working tree (e.g., scratch logs from prior debugging) don't get
   pulled in. If a particular file does not exist (no review files
   ran, or the backlog had no Track N section to begin with), drop
   that path from the `git add` command rather than letting the
   missing-path error stop the commit.

### Complexity Assessment and Which Reviews to Run

Complexity determines which pre-execution reviews to run, not user
interaction level — all tracks execute autonomously after review.
All tracks get track-level code review (Phase C) regardless of
complexity. Step-level dimensional review (Phase B sub-step 4) runs
only for steps tagged `risk: high` per
[`risk-tagging.md`](risk-tagging.md); `medium` and `low` steps rely on
tests plus track-level review.

| Track complexity | Review pipeline |
|---|---|
| Simple (1-2 steps) | Technical review only — even if track characteristics suggest Risk or Adversarial, skip them for 1-2 step tracks. |
| Moderate (3-5 steps) | Technical review as baseline. Risk and/or Adversarial reviews are added when track characteristics warrant them (see table below). |
| Complex (6-7 steps, or critical path / high-risk) | Full: Technical + Risk + Adversarial. |

Specific characteristics that upgrade Moderate tracks:

| Track characteristics | Reviews to run |
|---|---|
| Simple (1-2 steps) — any characteristics | Technical only (skip Risk/Adversarial) |
| Moderate (3-5 steps) | Technical (always) |
| Moderate + critical paths or performance constraints | Technical + Risk |
| Moderate + major architectural decisions or non-obvious scope | Technical + Adversarial |
| Complex (6-7 steps, or critical path / high-risk) | Technical + Risk + Adversarial |

### Inputs passed to Phase A review sub-agents

All four Phase A review sub-agents — the track-scoped technical, risk,
and adversarial reviews, plus the review gate verification — receive
the same shared set of inputs. The mini-sections below describe only
what each review *does* with those inputs; the inputs themselves are
enumerated here once so the mini-sections can point here by reference
instead of restating them.

| Input | Value |
|---|---|
| `plan_path` | Absolute path to `docs/adr/<dir-name>/_workflow/implementation-plan.md` — the strategic context (Goals, Constraints, Architecture Notes, Decision Records, Component Map). |
| `step_file_path` | Absolute path to `docs/adr/<dir-name>/_workflow/tracks/track-N.md` — once Phase A has written the step file, its `## Description` section is the authoritative source for the track's `**What/How/Constraints/Interactions**` subsections and any track-level diagram (per the lifecycle table in `conventions-execution.md` §2.1). |
| `track_name` | The track heading as it appears in the plan file's checklist (e.g., `"Track 2: Execution workflow edits"`). |
| `codebase_path` | Absolute path to the repository root — the sub-agent may Read any file under this path to validate code references. |
| `prior_episodes` | Summary of track episodes from already-completed tracks. The episodes themselves also appear in the slim plan snapshot pointed at by `plan_path`, but they are passed as a **separate** value so each review prompt's `{prior_episodes}` placeholder resolves without forcing the sub-agent to re-parse the plan. Used for cross-track consistency checks. |
| `previous_findings` | Findings from earlier iterations of the same review type (empty on iteration 1; populated on iterations 2–3). Used to avoid re-surfacing already-accepted/deferred findings and to verify that review-fix commits resolved prior findings. |

Phase A orchestration always passes both `plan_path` and
`step_file_path` to each sub-agent. Prompts that read the track
description from the plan-file entry use `plan_path`; prompts that
read it from the step file's `## Description` section use
`step_file_path`. The Inputs block and the per-review mini-sections
below do not need to change when an individual prompt switches sources
— only the prompt file itself is edited.

**Gate-verification-specific inputs.** The review gate verification
sub-agent additionally receives `findings` (the current iteration's
findings under re-check — semantically distinct from `previous_findings`,
which carries finalised findings from earlier iterations) and a
`review_type` value identifying which review produced the findings
(`technical` / `risk` / `adversarial`). These two are not part of the
shared set because the track-scoped reviews do not consume them; the
gate-verification mini-section below notes their role.

### Track-scoped technical review

Spawn a sub-agent with the technical review prompt. Inputs: the shared
set defined in §Inputs passed to Phase A review sub-agents above.

**Prompt file:** [`prompts/technical-review.md`](prompts/technical-review.md)

### Track-scoped risk review

Spawn a sub-agent with the risk review prompt. Inputs: the shared set
defined in §Inputs passed to Phase A review sub-agents above.

**Prompt file:** [`prompts/risk-review.md`](prompts/risk-review.md)

### Track-scoped adversarial review

Spawn a sub-agent with the adversarial review prompt. Inputs: the
shared set defined in §Inputs passed to Phase A review sub-agents
above.

**Prompt file:** [`prompts/adversarial-review.md`](prompts/adversarial-review.md)

### Review gate verification

After fixes are applied, spawn a sub-agent to verify. Inputs: the
shared set defined in §Inputs passed to Phase A review sub-agents
above, **plus** the gate-verification-specific `findings` (the
iteration's findings under re-check) and `review_type`
(`technical` / `risk` / `adversarial`).

**Prompt file:** [`prompts/review-gate-verification.md`](prompts/review-gate-verification.md)

### Review iteration protocol

Max 3 iterations per review type, findings cumulative. Full iteration
limits, finding ID prefixes, finding format, and gate verification output
are in [`review-iteration.md`](review-iteration.md) — load that file when
running the review loop. If blockers persist after 3 iterations, note them
and proceed with caution — the step implementation phase will surface
concrete issues if they exist.

### Step Decomposition

After track review passes, decompose scope indicators into concrete steps.
Decompose **all steps at once** — tracks are capped at ~5-7 steps, making
full upfront decomposition feasible.

#### Inputs for decomposition

- Track description, scope indicators, component diagram, and relevant
  Decision Records
- Track episodes from all completed tracks
- Codebase knowledge gained from track review

#### Decomposition rules

- Each step = one commit.
- Each step = fully tested, self-contained change with 85% line / 70%
  branch coverage.
- If a step touches more than ~3 files or does unrelated things, split it.
- If a step feels trivial (single import, single rename), merge it into
  a neighbor.
- Note **cross-cutting concerns** (shared types, refactors) as separate
  steps rather than embedding them inside feature steps.

#### Risk tagging

Assign a risk tag — `low`, `medium`, or `high` — to each decomposed
step. The tag controls whether Phase B runs step-level dimensional
review (`high` runs the full review loop; `medium` and `low` skip it
and rely on tests plus track-level review). Track-level review at
Phase C always runs against the cumulative track diff regardless of
the per-step distribution.

Apply the criteria in [`risk-tagging.md`](risk-tagging.md) — load that
file at the start of decomposition. Six HIGH categories (concurrency,
crash-safety/durability, public API, security, architecture,
performance hot path), one MEDIUM band (multi-file logic, test
infrastructure, build config, observability changes), and a LOW
default for refactoring / new tests / docs / isolated bug fixes. When
in doubt, mark `high` — over-tagging costs an extra review, but
missing a real high-risk step ships bugs.

Write the tag inline in each step's description blockquote:

```markdown
- [ ] Step: <description>
  > **Risk:** <level> — <category, "default", or "override: <reason>">
```

The tag stays in place through Phase B (where it gates
`step-implementation.md` sub-step 4) and Phase C (where `medium` and
`high` are treated as focal points by the track-level reviewers).
Once a step is implemented, the tag is locked.

#### Parallel step annotation

During decomposition, you may identify independent steps within the
track — steps that don't depend on each other and don't modify the same
files. Annotate them with `*(parallel with Step N.M)*` in the step file.
Must not modify the same files.

#### Output

Write decomposed steps to the **step file**
(`docs/adr/<dir-name>/_workflow/tracks/track-N.md`), creating it if it doesn't exist.
Scope indicators in the plan file are NOT replaced — step details live only
in the step file.

The scope indicators serve as a starting point, not a binding contract. You
may produce more or fewer steps than the indicator suggested, or cover
different aspects, based on what is actually needed.

### Phase A Resume — Description-move recovery

The description-move in §What You Do sub-steps (b)-(d) performs two
on-disk mutations: the atomic step-file write in (c) and the
backlog-section removal in (d). Either operation may be interrupted by
a session termination. When `/execute-tracks` auto-resumes into Phase A
(the Startup Protocol's State C `Review + decomposition is [ ]` row
routes here), the main agent observes two states — the step file's
`## Description` section and the backlog's `## Track N:` section — and
picks the resume action from the decision table below.

The table is **idempotent**: running the indicated action produces the
steady-state even if the table is re-entered multiple times. The
**non-re-copy rule** (never overwrite a non-empty `## Description`
from the plan + backlog) is what protects any post-Phase-A edits to
the step file's description — e.g., a later inline-replan that
revises a mid-execution track — from being silently undone if Phase A
is re-entered later (most commonly because an earlier Phase A session
was interrupted and State C resumed here).

| Step file state | Backlog state | Resume action |
|---|---|---|
| Missing | `## Track N:` section present | Fresh Phase A: run §What You Do sub-steps (a)-(d) from the top. Sub-step (c)'s single-Write rule rules out an on-disk step file with an empty `## Description`, so this row cannot represent a partial (c); it only represents an interruption at or before (b). |
| Present, `## Description` populated | `## Track N:` section still present | Partial interruption after (c), before (d) completed. Run sub-step (d) **verbatim** and remove the backlog section using the "Backlog section body extraction rule" in `conventions-execution.md` §2.1. Do NOT re-copy into the step file's `## Description`. |
| Present, `## Description` populated | No `## Track N:` section | Steady state. No description mutation needed. Resume from the next incomplete Phase A activity (reviews not yet run, decomposition not yet written). |

After the resume action completes, Phase A continues normally — the
§Complexity Assessment, review loop, and §Step Decomposition proceed
exactly as on a fresh start (skipping any reviews already recorded in
the step file's `## Reviews completed` section, and reusing already-
decomposed steps if the `## Steps` section is non-empty).

---

## Phase A Completion — MANDATORY SESSION BOUNDARY

> **Do NOT proceed to Phase B in the same session.** Phase A always ends
> with a session boundary. The user clears context and re-runs
> `/execute-tracks` to begin Phase B with fresh context.

After writing the step file with all decomposed steps:

1. **Verify the step file** on disk has:
   - `Review + decomposition` marked `[x]` in Progress
   - All reviews recorded in Reviews completed
   - All steps listed as `[ ]` items
2. **Verify the Phase A commit landed.** Run `git status --porcelain`;
   the working tree must be clean. Run `git log -1 --oneline` and
   confirm the tip is `Phase A review and decomposition for <track>`.
   If the commit is missing (e.g., the session was interrupted
   between step 5 and step 6 of §What You Do), run step 6 now —
   the implementer's `git reset --hard HEAD` would otherwise
   discard the decomposition.
3. **Inform the user** that Phase A is complete:
   - How many steps were decomposed
   - Which reviews were run and key findings
   - Any concerns or risks noted during review
   - Instruct: "Clear session and re-run `/execute-tracks` to start
     Phase B (step implementation)."
4. **End the session.** Do not proceed to Phase B in the same session.

**Why:** Phase A is exploratory (reading code, validating assumptions).
That "reviewer mindset" context is not helpful during implementation —
it dilutes focus and carries stale exploratory context. The step file
bridges everything the implementation phase needs.
