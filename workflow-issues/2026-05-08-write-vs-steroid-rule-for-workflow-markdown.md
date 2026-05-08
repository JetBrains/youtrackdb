---
severity: low
phase: phase-a
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Native `Write` vs `steroid_execute_code` rule conflict for `_workflow/` markdown files

## Symptom

During Track 20 Phase A sub-step 2c, the orchestrator needed to
create the step file `docs/adr/unit-test-coverage/_workflow/tracks/track-20.md`.
Two doc rules pulled in opposite directions:

1. **`track-review.md` § What You Do sub-step 2c** prescribes
   creating the step file "in a single Write call" (atomic, single
   on-disk write). This implies the native `Write` tool.

2. **Project `CLAUDE.md` § MCP Steroid** mandates "always route
   file edits through MCP Steroid, even when Edit looks cheaper on
   tokens. The native `Edit` tool writes to disk bypassing
   IntelliJ. VFS + PSI + search indices go stale, and the next
   semantic operation … returns inconsistent results until
   something forces a refresh."

The CLAUDE.md rationale is VFS / PSI / search-index coherence.
That rationale **does not apply to markdown documentation under
`_workflow/`** — it's not IDE-indexed source code, it does not
participate in PSI, and stale search results on prose are not a
correctness concern. But the orchestrator paused to weigh the
conflict before defaulting to native `Write` per `track-review.md`.

The same conflict surfaces every time the orchestrator (or an
implementer) edits a markdown file under
`docs/adr/<dir>/_workflow/`: the step file (every Phase A, B, C),
the implementation-plan checklist (every track completion, every
strategy refresh, every Phase 4 gate), the implementation-backlog
(every Phase A or skip), the design.md / design-mutations.md (via
the `edit-design` skill), and the workflow-issues files (every
end-of-session reflection that produces output).

## Reproduction context

- Phase: phase-a (sub-step 2c step file creation), but also fires
  in Phase B episode writes, Phase C episode + plan checklist
  edits, strategy refresh appends, Phase 4 final-artifacts
  authoring.
- Workflow doc(s) involved:
  - `.claude/workflow/track-review.md` § What You Do sub-step 2c
    ("in a single Write call"), and the parallel sub-step 6
    commit-and-push step.
  - Project root `CLAUDE.md` § MCP Steroid — IntelliJ IDE Control
    ("always route file edits through MCP Steroid").
- Tool / sub-agent involved: orchestrator (and implementer for
  episode writes in Phase B / Phase C).
- ADR directory at the time: `docs/adr/unit-test-coverage/`.
- Trigger condition: any operation that creates or edits a
  markdown file under `docs/adr/<dir>/_workflow/` or
  `workflow-issues/`.

## Why it's a problem

Future agents with stricter CLAUDE.md interpretation will route
every step-file edit through `steroid_execute_code` with VfsUtil,
costing 1.5–2.5× the tokens (per the recipe's own self-cost
estimate in CLAUDE.md) for zero coherence benefit — markdown is
not in the PSI index. Conversely, agents who default to native
`Write` for everything (per `track-review.md` 2c) may extend the
pattern to source files where it actually breaks PSI.

The rule conflict is a recurring pause every Phase A / B / C /
session-end edits markdown under `_workflow/` — many edits per
track, many tracks per project. Even a 5-second pause adds up.

The deeper issue: the CLAUDE.md rule is correct for source code
but over-broad. It uses "every file edit, including 1–3 line
changes" framing without carving out `_workflow/` markdown.

## Proposed fix

Two options, not mutually exclusive:

1. **CLAUDE.md side (preferred):** Edit `CLAUDE.md` § MCP Steroid
   → "File edits: always through MCP Steroid …" to add an
   explicit carve-out:

   > **Carve-out: documentation files outside the PSI index.**
   > Markdown under `docs/adr/<dir>/_workflow/`, `workflow-issues/`,
   > and other non-source directories does not participate in PSI
   > / VFS coherence concerns and may use native `Write` / `Edit`
   > tools directly. The carve-out applies to files outside
   > `core/src/{main,test}/`, `server/src/...`, and other modules
   > with Java / Kotlin sources. Source code edits MUST still route
   > through MCP Steroid.

2. **`track-review.md` side:** Add a one-line note at sub-step 2c
   explicitly authorising native `Write` for the step file:

   > Use the native `Write` tool — `_workflow/` markdown is outside
   > the PSI index, so the project CLAUDE.md § MCP Steroid
   > "always route through Steroid" rule does not apply (see
   > carve-out in CLAUDE.md).

   Same in sub-step 6 (commit) and in
   `step-implementation.md` / `track-code-review.md` for episode
   writes.

Doing both is cheap and fully closes the loop: anyone reading
either doc gets a clear rule.

## Acceptance criteria

- `CLAUDE.md` § MCP Steroid → "File edits" subsection contains a
  named "documentation files outside the PSI index" carve-out
  with explicit path examples (`_workflow/`, `workflow-issues/`).
- `track-review.md` sub-step 2c (or a single shared rule the
  workflow docs link to) explicitly authorises native `Write` /
  `Edit` for `_workflow/` markdown.
- A future Phase A session on a fresh track (Track 21 in this
  plan) creates the step file via native `Write` without
  pausing to weigh the rule conflict.
- Regression check: source-code edits still route through MCP
  Steroid. The carve-out lists explicit non-source paths; it
  does NOT exempt `core/src/`, `server/src/`, or any module
  with Java / Kotlin sources.
