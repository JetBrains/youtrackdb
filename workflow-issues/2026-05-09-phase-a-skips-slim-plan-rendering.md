---
severity: medium
phase: phase-a
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Phase A reviews don't use the slim plan snapshot

## Symptom

`plan-slim-rendering.md` § "When the main agent generates it" lists
**Phase B startup** and **Phase C startup** as the two triggers for
generating `/tmp/claude-code-plan-slim-$PPID.md`. Phase A is not
listed. As a result, Phase A's three track-scoped review sub-agents
(technical / risk / adversarial) each receive `{plan_path}` pointing
at the full on-disk `implementation-plan.md` — which can be ~2,000
lines / ~50K tokens once a plan accumulates many completed-track
episodes (Track 22a Phase A: plan was 1,995 lines).

The full episodes are not load-bearing for a Phase A review — the
review prompt's Inputs block names "Architecture Notes, Decision
Records, Component Map" as the plan-side context. The slim version
preserves all of those plus the current track's full entry verbatim,
and strips completed-track step-file pointers / Scope / Depends-on
lines.

## Reproduction context

- Phase: phase-a
- Workflow doc(s) involved: `.claude/workflow/plan-slim-rendering.md` § "When the main agent generates it"; `.claude/workflow/track-review.md` § "Inputs passed to Phase A review sub-agents" (the row for `plan_path`)
- Tool / sub-agent involved: technical-review / risk-review / adversarial-review prompt files under `.claude/workflow/prompts/`
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase A run on a plan with multiple completed tracks (so the slim transform actually shrinks the file)

## Why it's a problem

Three sub-agents × ~50K plan tokens = ~150K tokens of plan reading
per Phase A track, even though the completed-track episode prose
none of the three reviewers need. On large plans (24 tracks, dozens
of completed episodes), this materially shrinks each sub-agent's
remaining context budget for codebase exploration and PSI evidence
certificates. Phase B/C already get the slim treatment for the same
reason; Phase A is the asymmetric outlier.

The asymmetry has no documented rationale — the slim renderer's
output is structurally compatible with Phase A prompt expectations
(it keeps the pre-Checklist content verbatim and the current track
entry verbatim). The miss appears to be that Phase A was simply not
considered when the rendering rule was added.

## Proposed fix

Edit `.claude/workflow/plan-slim-rendering.md` § "When the main
agent generates it" to add a third bullet:

> - **Phase A startup** — once, before the first track-scoped
>   review (technical / risk / adversarial). Same trigger as Phase
>   B/C: the agent generates the snapshot before spawning the first
>   sub-agent of the phase.

And edit `.claude/workflow/track-review.md` § "Inputs passed to
Phase A review sub-agents" to clarify that `plan_path` resolves to
the slim-rendered snapshot when present, falling back to the
on-disk plan otherwise — matching the Phase B/C convention.

The Phase A prompt files (`prompts/technical-review.md`,
`prompts/risk-review.md`, `prompts/adversarial-review.md`) already
say "Inputs: Plan file: {plan_path} (strategic context — Architecture
Notes, Decision Records, Component Map)" — they don't need to
change; only the orchestrator's resolution of `{plan_path}` does.

## Acceptance criteria

- `.claude/workflow/plan-slim-rendering.md` § "When the main agent
  generates it" lists Phase A as a trigger.
- `.claude/workflow/track-review.md` (or its §Inputs subsection)
  documents that `plan_path` resolves to the slim snapshot when
  available.
- A Phase A run on a plan with multiple completed tracks shows the
  three review sub-agents reading the slim snapshot rather than the
  full plan (verifiable from the orchestrator's tool-call log).
- Regression check: `grep -n "Phase A startup" .claude/workflow/plan-slim-rendering.md` matches the new trigger bullet.
