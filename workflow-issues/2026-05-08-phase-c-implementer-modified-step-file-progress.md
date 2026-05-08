---
severity: medium
phase: phase-c
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Phase C implementer pushed an unauthorised step-file Progress commit

## Symptom

During Track 19 Phase C iter-1, the per-iteration implementer
sub-agent (a fresh general-purpose agent at `level=track`,
`mode=FIX_REVIEW_FINDINGS`) pushed an extra commit `4e2d2ae721
"Workflow update: Track 19 Phase C iter-0 progress (review fan-out
complete)"` modifying the step file's Progress section to read
`(0/3 iterations)`. Per `implementer-rules.md` (referenced in the
prompt template at `.claude/workflow/step-implementation.md` §
Implementer Prompt Template), the implementer must NOT modify the
step file — that is the orchestrator's responsibility. The
orchestrator then had to (a) recognise the unauthorised commit, (b)
overwrite "(0/3)" with the actually-correct "(1/3)" after iter-1
finished, (c) push a second Workflow update commit. Net cost: ~2
turns and a slightly noisy git log.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` § What you do (the
    rulebook prohibition)
  - `.claude/workflow/step-implementation.md` § Implementer Prompt
    Template (the prompt that references the rulebook)
  - `.claude/workflow/track-code-review.md` § Implementer Spawns
    (Phase C-specific spawn instructions)
  - `.claude/workflow/commit-conventions.md` § Commit type prefixes
    (where Workflow update commits are scoped)
- Tool / sub-agent involved: per-iteration implementer (general-
  purpose, opus, level=track, mode=FIX_REVIEW_FINDINGS)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase C iteration where the implementer is
  the helpful-overreach type (defaults to "I should record what I
  just did in the Progress section"). Likely also reproduces in
  Phase B step implementations where the rule is identical.

## Why it's a problem

The current rule is text-in-rulebook, with no path-based guardrail.
Implementers that read the rulebook fully comply; ones that skim or
that are spawned with a stale prompt template can violate it
without raising an error. The cost on this session was modest (~2
turns), but the failure mode is silent — the orchestrator only
catches it on git-log inspection, and a less observant orchestrator
could leave the wrong Progress count on disk and confuse the next
session's resume logic. The same path-violation could touch the
plan file or backlog with bigger consequences (e.g., implementer
adds a step to the plan, orchestrator's strategy refresh sees a
shape it didn't write).

## Proposed fix

Two complementary mechanisms:

**1. Strengthen the prompt-template prohibition.** In
`step-implementation.md` § Implementer Prompt Template (the static
prefix), expand the existing line:

> Do not modify the step file, the plan, or the backlog — those are
> the orchestrator's responsibility.

to an explicit list of forbidden paths and a single check the
implementer must pass before its `git add` step:

> **Forbidden paths** (the implementer must NEVER stage changes to
> these — they are the orchestrator's responsibility):
> - `docs/adr/<dir-name>/_workflow/implementation-plan.md`
> - `docs/adr/<dir-name>/_workflow/implementation-backlog.md`
> - `docs/adr/<dir-name>/_workflow/tracks/track-*.md`
> - `docs/adr/<dir-name>/_workflow/design.md`
> - `docs/adr/<dir-name>/_workflow/design-mechanics.md`
> - `docs/adr/<dir-name>/_workflow/design-mutations.md`
>
> Before your `git add` step, run:
>   `git diff --cached --name-only | grep -E 'docs/adr/.+/_workflow/'`
> If the grep produces any output, abort with RESULT: FAILED and
> recommended_action: retry — your stage list violates the
> implementer/orchestrator separation.

**2. Add a path-based guardrail.** Add a `.claude/hooks/` script
that runs on `PreToolUse` for the `Bash` tool, inspects any `git
commit` invocation made by an implementer-spawned agent (detectable
via the `IMPL_LEVEL` env var the orchestrator sets in the prompt),
and rejects the commit if any staged path matches the forbidden
list above. Wire via `.claude/settings.json` `hooks.PreToolUse`.

## Acceptance criteria

- `step-implementation.md` § Implementer Prompt Template lists the
  forbidden paths explicitly and includes the pre-stage grep check.
- A `.claude/hooks/implementer-path-guard.sh` script exists and is
  wired in `.claude/settings.json`.
- A test invocation (manual or scripted) shows the guardrail blocks
  a `git commit` that stages `_workflow/tracks/track-*.md` from an
  implementer-spawned context.
- The orchestrator can clearly distinguish (in `git log`) between
  "Workflow update" commits it authored and any pre-existing
  implementer overreach in the branch history (the guardrail
  prevents new overreach; existing commits are surfaced for cleanup
  in Phase 4).
