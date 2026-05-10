---
severity: medium
phase: phase-b
source-session: 2026-05-10 /execute-tracks unit-test-coverage
---

# Bash harness auto-classifies foreground commands as background; implementers self-flag false rulebook violation

## Symptom

The implementer rulebook
(`.claude/workflow/implementer-rules.md` §"Pacing long-running
tasks — foreground only") forbids the implementer from setting
`run_in_background: true` on Bash invocations of long-running
Maven runs (full coverage profile, full module test suite, etc.):

> The implementer:
> - MUST NOT call `ScheduleWakeup`.
> - MUST NOT start Maven invocations with Bash
>   `run_in_background: true`.

Across the Track 22a Phase B session, three implementer spawns
(Steps 6, 7, 10) reported in their TOOLING_NOTES that the Bash
harness *itself* "routed the long coverage build through its
background channel despite no `run_in_background` flag". Sample
notes from Step 6's return:

> "The harness silently routed the long coverage build through
> its background channel despite no `run_in_background` flag;
> treated as foreground per its completion semantics."

The implementers self-flagged this as a rulebook deviation,
worried they had violated §"Pacing long-running tasks — foreground
only", and added end-of-session reflection notes asking for a fix.
But the rule the rulebook prohibits is the implementer **setting**
the flag. The implementers did not set it; the harness made the
routing decision. So no deviation actually occurred.

The recurring self-flagged "rulebook violation" notes pollute
EPISODE_DRAFTs with false alarms and risk a future implementer
treating the harness behavior as a hard error and bailing out of a
successful build.

## Reproduction context

- Phase: phase-b (also expected in phase-c track-level fix
  iterations that run a full coverage build)
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` §"Pacing long-running
    tasks — foreground only"
- Tool / sub-agent involved: per-step / per-iteration implementer
  sub-agents (general-purpose opus); Claude Code Bash tool
- ADR directory at the time:
  `docs/adr/unit-test-coverage/_workflow/`
- Trigger condition: any Bash invocation that approaches the
  10-minute foreground budget (full coverage profile build is the
  canonical case; integration tests on slow hosts also). The
  Bash harness's runtime classifier may move the command to a
  background channel for completion notification while still
  honoring synchronous waits semantically.

## Why it's a problem

Recurring across at least 3 of 9 Phase B implementer spawns and
likely to recur in every Phase B / Phase C session that runs the
coverage profile build. Each occurrence costs the implementer a
few sentences of self-doubt in its return block, pollutes the
episode with a false rulebook concern, and risks one of two
worse outcomes:

1. A future, more cautious implementer treats the harness routing
   as a hard violation and aborts the build — wasting the entire
   coverage cycle.
2. A future implementer copies the self-flag pattern into
   `EPISODE_DRAFT.what_changed_from_plan` and pollutes the
   downstream Phase C / Phase 4 review with a non-issue.

## Proposed fix

Edit `.claude/workflow/implementer-rules.md` §"Pacing long-running
tasks — foreground only" to clarify the rule's scope. Replace the
current "MUST NOT start Maven invocations with Bash
`run_in_background: true`" bullet with something like:

> - MUST NOT explicitly set Bash `run_in_background: true` on
>   Maven invocations.
>
>   **Note**: the Bash harness may auto-classify a long-running
>   foreground command as background for the purposes of its
>   completion-notification channel while still preserving the
>   synchronous-wait semantics the rule depends on. This is fine
>   and is NOT a rulebook violation. Do not flag the auto-route
>   in `EPISODE_DRAFT.what_changed_from_plan`. The rule binds the
>   implementer's explicit `run_in_background: true` choice; the
>   harness's runtime routing is out of scope.

If the harness auto-classification is itself a known harness bug
or misconfiguration, file a separate report against the harness
team rather than the workflow rulebook.

## Acceptance criteria

- `.claude/workflow/implementer-rules.md` §"Pacing long-running
  tasks — foreground only" explicitly distinguishes
  implementer-set `run_in_background: true` (forbidden) from
  Bash-harness auto-routing (allowed).
- A subsequent Phase B / Phase C session does not produce
  TOOLING_NOTES `notes:` lines that self-flag the harness's
  auto-routing as a rulebook deviation.
- (If applicable) a separate harness-team issue tracks the
  underlying auto-routing behavior, so the rulebook can stay
  silent on the implementation detail once the behavior is
  documented at harness level.
