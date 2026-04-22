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

   b. **Detect the description source** for Track N based on the
      on-disk presence of
      `docs/adr/<dir-name>/implementation-backlog.md`:

      - **(i) New-format** — the backlog file exists **and** contains a
        `## Track N: <title>` section. Read the track's intro paragraph
        from the plan-file entry and the
        `**What/How/Constraints/Interactions**` subsections + any
        track-level `mermaid` block from the backlog section. The
        section body is defined by the "Backlog section body
        extraction rule" in `conventions-execution.md` §2.1.
      - **(ii) Mid-migration fallback** — the backlog file exists
        **but** has no `## Track N:` section (e.g., a partial
        hand-migration). Read the full track description from the
        plan-file entry's checklist block, and do NOT attempt a
        backlog-section removal in sub-step (e) below (there is no
        section to remove).
      - **(iii) Legacy** — the backlog file is absent. Read the full
        track description from the plan-file entry's checklist block.

   c. **Mid-migration drift crosscheck (safety valve).** If the plan
      entry still carries `**What/How/Constraints/Interactions**`
      subsections **and** the backlog has a `## Track N:` section, the
      description has drifted into two live locations simultaneously.
      Flag to the user and pause — do not auto-resolve, and do not
      proceed to sub-steps (d)-(e) until the user has reconciled the
      two descriptions manually and re-run `/execute-tracks`. This
      check is a safety valve against hand-edits gone wrong, not a
      routine code path.

   d. **Create the step file atomically** at
      `docs/adr/<dir-name>/tracks/track-N.md` in a single Write call
      that already contains the full initial shell — `## Description`
      (populated with the copied intro + `**What/How/Constraints/Interactions**`
      subsections + any track-level diagram from sub-step (b)),
      `## Progress` (with `[ ] Review + decomposition` and the other
      Progress entries), and an empty `## Reviews completed`. Do NOT
      create an empty shell and append the description in a second
      Write — the single atomic Write closes the window in which the
      description has been pulled out of its source but has not yet
      landed on disk. Subsequent phases may Edit the file normally;
      only the initial creation must be atomic.

   e. **Remove Track N's section from the backlog** (skip this sub-step
      for the mid-migration and legacy branches — the former has
      nothing to remove, the latter has no backlog file to edit).
      Before removing, **re-check** that
      `docs/adr/<dir-name>/implementation-backlog.md` still exists on
      disk: the file-exists test is cheap, and running it again here
      instead of assuming the result from sub-step (b) handles the
      rare case where the backlog materialises mid-session (e.g., a
      concurrent `/create-plan` run in another worktree).

      Delete by **header boundary**: remove from the line matching
      `## Track N: <title>` through the line immediately before the
      next `## Track M: <title>` header, or through EOF if Track N is
      the last section. Preserve the backlog's opening
      `# <Feature> — Track Details` header and its
      `<!-- DO NOT DELETE ... -->` HTML comment. Do NOT use line-count
      deletion — that approach breaks when track-level `mermaid`
      diagrams change a section's line count.

      When the last remaining `## Track M:` section is removed, leave
      the backlog file on disk with only its header and HTML comment.
      The file's continued presence — even when empty — is what signals
      "new-format plan" to downstream operations (late `track-skip`,
      slim plan rendering, the `review-plan` orchestrator). Deleting
      it mid-execution would flip those operations into legacy mode.
      Natural cleanup happens when the branch is deleted after PR
      merge, like every other working file.

3. **Run track-scoped reviews** as sub-agents (technical, risk, adversarial
   as warranted). After each review completes:
   - Write the review file to `docs/adr/<dir-name>/reviews/track-N-<type>.md`
   - Update the **Reviews completed** section in the step file
     (created atomically in step 2d).
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
4. **Decompose scope indicators** into concrete steps.
5. **Write decomposed steps** to the step file's `## Steps` section as
   `[ ]` items. Mark `Review + decomposition` as `[x]` in the Progress
   section.

### Complexity Assessment and Which Reviews to Run

Complexity determines which pre-execution reviews to run, not user
interaction level — all tracks execute autonomously after review.
All tracks get both step-level and track-level code review regardless of
complexity.

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
| `plan_path` | Absolute path to `docs/adr/<dir-name>/implementation-plan.md` — the strategic context (Goals, Constraints, Architecture Notes, Decision Records, Component Map). |
| `step_file_path` | Absolute path to `docs/adr/<dir-name>/tracks/track-N.md` — after the description-move in §What You Do sub-step (d), the step file's `## Description` section is the authoritative source for the track's `**What/How/Constraints/Interactions**` subsections and any track-level diagram. |
| `track_name` | The track heading as it appears in the plan file's checklist (e.g., `"Track 2: Execution workflow edits"`). |
| `codebase_path` | Absolute path to the repository root — the sub-agent may Read any file under this path to validate code references. |
| `prior_episodes` | Summary of track episodes from already-completed tracks (pre-rendered into the slim plan snapshot read via `plan_path`) — used for cross-track consistency checks. |
| `previous_findings` | Findings from earlier iterations of the same review type (empty on iteration 1; populated on iterations 2–3). Used to avoid re-surfacing already-accepted/deferred findings and to verify that review-fix commits resolved prior findings. |

During the transition while the downstream review prompts still read
the track description from the plan-file entry, Phase A orchestration
passes both `plan_path` and `step_file_path` to each sub-agent — so
today's prompts keep working. When the prompts later switch to reading
description + track-level diagram from the step file, only the prompt
files are edited; this Inputs block and the per-review mini-sections
below do not need to change.

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
above.

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

#### Parallel step annotation

During decomposition, you may identify independent steps within the
track — steps that don't depend on each other and don't modify the same
files. Annotate them with `*(parallel with Step N.M)*` in the step file.
Must not modify the same files.

#### Output

Write decomposed steps to the **step file**
(`docs/adr/<dir-name>/tracks/track-N.md`), creating it if it doesn't exist.
Scope indicators in the plan file are NOT replaced — step details live only
in the step file.

The scope indicators serve as a starting point, not a binding contract. You
may produce more or fewer steps than the indicator suggested, or cover
different aspects, based on what is actually needed.

### Phase A Resume — Description-move recovery

The description-move in §What You Do sub-steps (b)-(e) performs two
on-disk mutations: the atomic step-file write in (d) and the
backlog-section removal in (e). Either operation may be interrupted by
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
| Missing | `## Track N:` section present | Fresh Phase A: run §What You Do sub-steps (a)-(e) from the top. Sub-step (d)'s single-Write rule rules out an on-disk step file with an empty `## Description`, so this row cannot represent a partial (d); it only represents an interruption at or before (c). |
| Missing | No `## Track N:` section (mid-migration) or no backlog file at all (legacy) | Fresh Phase A: read the full description from the plan-file entry per §What You Do branch (ii)/(iii); skip sub-step (e). |
| Present, `## Description` populated | `## Track N:` section still present | Partial interruption after (d), before (e) completed. Run sub-step (e) **verbatim** — including its file-exists re-check preamble — and remove the backlog section using the "Backlog section body extraction rule" in `conventions-execution.md` §2.1. Do NOT re-copy into the step file's `## Description`. |
| Present, `## Description` populated | No `## Track N:` section / no backlog file | Steady state. No description mutation needed. Resume from the next incomplete Phase A activity (reviews not yet run, decomposition not yet written). |
| Present WITHOUT `## Description` section (step file predates the Phase A description-move — it was created by Phase A before sub-steps (b)-(e) were added) | Any of the above — the backlog state is irrelevant for this row | Leave the step file's Description state untouched. Phase A review prompts detect the missing `## Description` and fall back to reading the description from the plan-file entry. Resume from the next incomplete Phase A activity. |

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
2. **Inform the user** that Phase A is complete:
   - How many steps were decomposed
   - Which reviews were run and key findings
   - Any concerns or risks noted during review
   - Instruct: "Clear session and re-run `/execute-tracks` to start
     Phase B (step implementation)."
3. **End the session.** Do not proceed to Phase B in the same session.

**Why:** Phase A is exploratory (reading code, validating assumptions).
That "reviewer mindset" context is not helpful during implementation —
it dilutes focus and carries stale exploratory context. The step file
bridges everything the implementation phase needs.
