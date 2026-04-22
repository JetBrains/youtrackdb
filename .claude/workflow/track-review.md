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

1. **Assess track complexity** to determine which reviews to run
2. **Run track-scoped reviews** as sub-agents (technical, risk, adversarial
   as warranted). After each review completes:
   - Write the review file to `docs/adr/<dir-name>/reviews/track-N-<type>.md`
   - Update the **Reviews completed** section in the step file (create the
     step file early with just the Progress and Reviews sections if it
     doesn't exist yet)
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
3. **Decompose scope indicators** into concrete steps
4. **Write the step file** to `docs/adr/<dir-name>/tracks/track-N.md` with
   all steps as `[ ]` items. Mark `Review + decomposition` as `[x]` in the
   Progress section.

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

### Track-scoped technical review

Spawn a sub-agent with the technical review prompt.

**Prompt file:** [`prompts/technical-review.md`](prompts/technical-review.md)

### Track-scoped risk review

Spawn a sub-agent with the risk review prompt.

**Prompt file:** [`prompts/risk-review.md`](prompts/risk-review.md)

### Track-scoped adversarial review

Spawn a sub-agent with the adversarial review prompt.

**Prompt file:** [`prompts/adversarial-review.md`](prompts/adversarial-review.md)

### Review gate verification

After fixes are applied, spawn a sub-agent to verify.

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
