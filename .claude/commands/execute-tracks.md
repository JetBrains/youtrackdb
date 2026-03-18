Read and follow the workflow for Phase 3 (Execution).

Read these workflow documents in order before starting:
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, episode formats, complexity tiers,
   checklist decomposition rules, review iteration protocol
2. `.claude/workflow/workflow.md` — session lifecycle,
   startup protocol (auto-resume), strategy refresh, cross-track impact
   monitoring, session boundary rules, failure handling, inline replanning
   (ESCALATE), track completion protocol
3. `.claude/workflow/track-execution.md` — track execution
   within a session: review + decomposition (Phase A), step implementation
   (Phase B), track-level code review (Phase C), episode production

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

The implementation plan is at: docs/adr/<dir-name>/implementation-plan.md

Follow the startup protocol in `workflow.md`:

1. Read the plan file at `docs/adr/<dir-name>/implementation-plan.md`.
2. Identify all tracks and their status (`[ ]` not started, `[x]` completed,
   `[~]` skipped).
3. Determine session state and auto-resume:
   - **State A** (track just completed, needs strategy refresh): perform
     strategy refresh, then proceed to next track.
   - **State B** (fresh start): identify first uncompleted track, begin
     execution.
   - **State C** (mid-track resume): read step file, report progress, resume
     from next incomplete step.
4. Inform the user of the auto-resume decision. The user can override, but
   by default proceed without waiting for confirmation.
5. Execute the track following `track-execution.md`:
   Phase A (review + decomposition) → Phase B (step implementation) →
   Phase C (track-level code review).
6. After track completion, present results to the user (workflow.md §Track
   Completion Protocol). End session after user approval.
7. Strategy refresh for this track happens at the start of the NEXT session.

User interaction happens at specific points:
- Session start: auto-resume decision (confirm or override)
- Strategy refresh: accept recommendation or override
- Cross-track impact detected: continue, pause, or escalate
- Track complete: approve, request fixes, or request rework
- Step failure (2nd attempt): retry, adjust, or escalate

Everything within a track executes autonomously: track reviews (as
sub-agents), step decomposition, step implementation, code review iterations
(code-reviewer sub-agent), track-level code review (sub-agent), episode
production, and within-track adaptation.
