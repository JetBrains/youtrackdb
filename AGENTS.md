# AGENTS.md

All agent instructions are in [`CLAUDE.md`](CLAUDE.md). Read that file first.

# Slate-pi development (this worktree's purpose)

This worktree hosts the implementation of the Slate thread-weaving agent
architecture as a pi extension. When working on anything under
`slate-dev/` or `.pi/extensions/slate/`:

1. Read `slate-dev/EXECPLAN.md` first and follow it. It is a living ExecPlan
   maintained per the methodology in `slate-dev/reference/codex-exec-plans.md`.
2. Consult `slate-dev/RESEARCH_LOG.md` (frozen — never edit) for the
   authoritative initial request, design decisions, and rationale.
3. The Slate architecture source is archived at
   `slate-dev/reference/slate-blog.md` (the original URL is JS-walled; use the
   local copy).
4. Record all new decisions, discoveries, and progress in the ExecPlan's
   living sections — never in the research log.

## Context budget protocol

The `handoff-guard` extension writes a context-usage snapshot after every
turn to `${TMPDIR:-/tmp}/pi-context/<pi-pid>.json`. The agent's bash tool
runs as a direct child of the pi process, so self-check with:

    cat ${TMPDIR:-/tmp}/pi-context/$PPID.json

**Check it periodically** — at every milestone boundary, before starting any
large task, and every handful of turns during long work stretches.
If `overThreshold` is true (≥ 40%): **pause** — finish only the current step,
update `slate-dev/EXECPLAN.md` living sections (Progress, Decision Log,
Surprises & Discoveries), and **ask the user to run `/handoff`**.
Do not start new work past the threshold.
