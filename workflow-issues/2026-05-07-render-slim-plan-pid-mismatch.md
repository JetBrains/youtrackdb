---
severity: low
phase: phase-a
source-session: 2026-05-07 /execute-tracks unit-test-coverage
---

# `render-slim-plan.py` writes under the bash-subshell PID instead of the orchestrator PID

## Symptom

During Track 19 Phase A, the orchestrator ran
`python3 .claude/scripts/render-slim-plan.py --plan-path …` via the
Bash tool to generate the slim plan snapshot for the technical-review
sub-agent. The script wrote the snapshot to
`/tmp/claude-code-plan-slim-302109.md` — but the orchestrator's
`$PPID` (the PID baked into the sub-agent prompt's
`{PPID}`-substituted snapshot path per `plan-slim-rendering.md`) was
`248597`. The snapshot landed at the wrong filename and the
orchestrator had to issue an extra `cp /tmp/claude-code-plan-slim-302109.md
/tmp/claude-code-plan-slim-$PPID.md` to fix the path before spawning
the review.

The mismatch is reproducible: the script uses `os.getppid()` to
choose its output filename, but when invoked via the Bash tool the
script's parent process is the bash subshell that the Bash tool
spawned, not the orchestrator agent. Bash-tool invocation
consistently produces a "wrong" PID.

## Reproduction context

- Phase: phase-a (also affects phase-b / phase-c whenever the
  orchestrator regenerates the slim plan).
- Workflow doc involved: `.claude/workflow/plan-slim-rendering.md`
  § "How the main agent generates it" — claims the script "writes
  `/tmp/claude-code-plan-slim-<ppid>.md` using its parent PID — i.e.,
  the orchestrator's PID — matching the snapshot path convention".
- Script involved: `.claude/scripts/render-slim-plan.py`.
- Tool involved: Bash tool (any `python3 …render-slim-plan.py …`
  invocation through Bash).
- ADR directory at the time: `docs/adr/unit-test-coverage/`.
- Trigger condition: any `/execute-tracks` Phase A/B/C session that
  spawns review sub-agents and therefore regenerates the slim plan
  snapshot via the Bash tool. Bash-subshell intermediation is the
  default invocation path.

## Why it's a problem

Each affected session burns one extra tool turn on a `cp` workaround
and one extra read of `ls -la /tmp/claude-code-plan-slim-*.md` to
discover what PID the script actually wrote under. With ~3 review
fan-outs per Phase B step plus track-level reviews per Phase C, this
is a low but non-zero per-session cost across every multi-step
track. It also leaks orchestration knowledge (PID resolution) into
the orchestrator's prompt that the docs claim is automatic.

## Proposed fix

Two reasonable options:

1. **Make the script accept an explicit output path.** The Bash
   command line becomes `python3 .claude/scripts/render-slim-plan.py
   --plan-path <plan> --out /tmp/claude-code-plan-slim-$PPID.md`.
   `$PPID` resolves correctly inside the bash subshell because
   `$PPID` is the bash subshell's parent — i.e., the orchestrator.
   `plan-slim-rendering.md` § "How the main agent generates it"
   would document the `--out $PPID` form.

2. **Have the script honor a `CLAUDE_ORCHESTRATOR_PID` environment
   variable** that the workflow exports before invoking the script.
   The Bash command becomes
   `CLAUDE_ORCHESTRATOR_PID=$PPID python3 .claude/scripts/render-slim-plan.py …`,
   the script reads `os.environ.get("CLAUDE_ORCHESTRATOR_PID")` and
   falls back to `os.getppid()` for direct-invocation use.

Option 1 is simpler and matches the existing CLI surface (`--out`
is a normal argparse option); option 2 is slightly more robust if
the script is ever called from a wrapping script.

## Acceptance criteria

- `render-slim-plan.py` accepts an `--out` path (option 1) or an
  `CLAUDE_ORCHESTRATOR_PID` env var (option 2).
- `plan-slim-rendering.md` § "How the main agent generates it" shows
  the new invocation form so future agents pass the right PID on
  the first call.
- Regression check: `grep -nE 'getppid|CLAUDE_ORCHESTRATOR_PID|--out'
  .claude/scripts/render-slim-plan.py` shows the explicit-PID path,
  and a fresh Phase A session writes the snapshot directly to
  `/tmp/claude-code-plan-slim-$PPID.md` without a follow-up `cp`.
