---
severity: medium
phase: phase-b
source-session: 2026-05-07 /execute-tracks read-cache-concurrency-bug
---

# render-slim-plan.py writes to the wrong PID

## Symptom

`.claude/scripts/render-slim-plan.py` is supposed to write the slim plan
snapshot to `/tmp/claude-code-plan-slim-<orchestrator-PID>.md` so that
sub-agents spawned by the orchestrator can read it via the canonical
`plan_slim_path: /tmp/claude-code-plan-slim-{PPID}.md` input. The
script computes the path via `os.getppid()` at line 395 — but when the
orchestrator runs the script via the Bash tool, the script's parent is
the bash shell that ran it, not the orchestrator process. So the file
lands at `/tmp/claude-code-plan-slim-<bash-PID>.md` and the canonical
path stays empty. Every Phase B / Phase C session that follows
`step-implementation.md` §Phase B Startup item 2 has to manually `cp`
the file to the right path before spawning the implementer or any
review sub-agent. This session repeated the workaround once at the
start of Phase B.

Concrete repro: orchestrator PPID was `251708`; the script wrote to
`/tmp/claude-code-plan-slim-254734.md` (bash shell PID). The
orchestrator had to run `cp /tmp/claude-code-plan-slim-254734.md
/tmp/claude-code-plan-slim-251708.md` before sub-agents could find
the snapshot.

## Reproduction context

- Phase: phase-b (also affects phase-a, phase-c, phase-4 — any session
  that calls the script)
- Workflow doc(s) involved:
  - `.claude/workflow/step-implementation.md` §Phase B Startup item 2
  - `.claude/workflow/track-code-review.md` (similar invocation)
  - `.claude/workflow/plan-slim-rendering.md` (defines the path
    convention)
- Script involved: `.claude/scripts/render-slim-plan.py` line 395
  (`out_path = f"/tmp/claude-code-plan-slim-{os.getppid()}.md"`)
- ADR directory at the time: `docs/adr/read-cache-concurrency-bug/`
- Trigger condition: any `/execute-tracks` session that runs the
  render-slim-plan script via Bash without an explicit `--out` flag

## Why it's a problem

The path mismatch is silent — the script reports success on stdout
("slim plan: 6 tracks ... -> /tmp/claude-code-plan-slim-254734.md")
but the canonical path stays empty. Sub-agents that read the path
specified in their prompt (`/tmp/claude-code-plan-slim-{PPID}.md`)
will silently see a missing file and fall back to reading the full
plan, defeating the whole purpose of the slim-plan optimization
(orchestrator-context savings on multi-agent fan-out). The
orchestrator must notice the mismatch, recover via `cp`, and only
then spawn sub-agents — costing one extra turn per session and
relying on the orchestrator knowing its own PID via `$PPID`. Most
sessions burn dim-review fan-outs through 9+ sub-agents; a missed
recovery would land a stale or full-plan context in every spawn.

## Proposed fix

Three options, ordered by simplicity:

1. **Document the explicit `--out` invocation.** Update
   `.claude/workflow/step-implementation.md` §Phase B Startup item 2
   to always pass `--out /tmp/claude-code-plan-slim-$PPID.md`
   explicitly so the script never relies on `os.getppid()` matching
   the orchestrator. The shell variable `$PPID` resolves to the
   orchestrator PID inside the bash invocation, so the path is
   correct by construction. Pro: one-line doc change. Con: the
   default-path fallback in the script remains broken.

2. **Fix the script's default to be orchestrator-aware.** Replace
   `os.getppid()` with a small helper that walks `/proc/<pid>/status`
   upward to find the first non-shell ancestor (looking for `bash` /
   `sh` parents and skipping them). Pro: the script "just works"
   without doc-side ceremony. Con: brittle on non-Linux (the workflow
   targets Linux per the project), but more importantly, the helper
   has to know what counts as "the orchestrator" — fragile.

3. **Remove the default-path behavior and require `--out`
   explicitly.** Update the script so it errors when `--out` is
   omitted, with a help message naming the canonical path. Update
   the workflow docs in lockstep. Pro: forces a single correct
   invocation; no silent path drift. Con: breaks any pre-existing
   invocation that relied on the default.

Recommended: option 1 (doc-side fix, minimal blast radius) plus
option 3 (script enforcement) as a follow-up so the silent failure
mode is removed.

## Acceptance criteria

- The script either (a) writes to the orchestrator's PPID by default
  or (b) refuses to run without an explicit `--out` argument naming
  the orchestrator's PPID.
- `.claude/workflow/step-implementation.md` §Phase B Startup item 2
  and `.claude/workflow/track-code-review.md` (or wherever the
  invocation is documented) show the canonical command, including
  `--out` if option 3 is taken.
- A fresh Phase B / Phase C session can run the documented command
  and immediately spawn sub-agents that read the slim plan from
  `/tmp/claude-code-plan-slim-$PPID.md` without any `cp` workaround.
- Regression check: `grep -rn 'os.getppid' .claude/scripts/` returns
  no hits if option 2 or 3 is taken; if option 1 is taken,
  `grep -rn 'render-slim-plan.py' .claude/workflow/` shows the
  `--out` flag in every documented invocation.
