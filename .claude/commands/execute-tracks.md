Read and follow the workflow for Phase 3 (Execution).

Read these workflow documents in order before starting:
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, episode formats, execution log format,
   complexity tiers, checklist decomposition rules, review iteration protocol
2. `.claude/workflow/execution-orchestrator.md` — your
   role as execution orchestrator: startup protocol, spawning track
   orchestrators, user interaction model, cross-track impact monitoring,
   strategy refresh, parallel track management, inline replanning (ESCALATE)
3. `.claude/workflow/track-orchestrator.md` — track
   orchestrator role (used when spawning track orchestrator teammates): track
   review, step decomposition, step executor spawning, episode synthesis,
   episode capture, track completion, message protocol

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

The implementation plan is at: adr/<dir-name>/implementation-plan.md

You are the **execution orchestrator** — the team lead agent for Phase 3.
Follow the startup protocol in `execution-orchestrator.md`:

1. Read the plan file at `adr/<dir-name>/implementation-plan.md`.
2. Identify all tracks and their status (`[ ]` not started, `[x]` completed,
   `[~]` skipped). Read track episodes from completed tracks if resuming.
3. Build the dependency graph from track descriptions.
4. Create the team (once per plan execution) using `TeamCreate` with the
   directory name as the team name. Skip if resuming and the team already
   exists.
5. Identify the next track(s) to execute. If independent tracks exist with
   no pending dependencies, propose parallel execution.
6. Wait for user confirmation. The user may confirm, reorder, skip, or
   override parallel/sequential recommendation.
7. Spawn track orchestrator(s) as named teammates (in worktrees for parallel
   tracks). Each track orchestrator receives:
   - Track description from the plan
   - Track episodes from all completed tracks
   - Relevant decision records and architecture notes
   - Step file path (`adr/<dir-name>/tracks/track-N.md`)
   - The full track-orchestrator.md instructions
8. Monitor step episodes from track orchestrators for cross-track impact.
9. Present track results to the user at track boundaries.
10. Run strategy refresh after each completed and user-approved track.
11. Handle ESCALATE inline — no separate `/replan` command.

User interaction happens **only** at track boundaries:
- Track proposal: confirm, reorder, or skip
- Track complete: approve, request fixes, or request rework
- Strategy refresh: accept recommendation or override
- Cross-track impact detected: continue, pause, or escalate
- Failure (unrecoverable): retry, adjust, or escalate

Everything within a track executes autonomously: track reviews, step
decomposition, step implementation, code review iterations, episode synthesis,
and within-track adaptation.
