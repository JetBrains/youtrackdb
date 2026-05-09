---
severity: medium
phase: phase-b
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Phase B verification-step backlog/baseline writes contradict the implementer rulebook

## Symptom

Track 21 Phase B Step 7 (the "Verification + top-up + `track-21-baseline.md`
+ track episode prep" step) was scoped by Phase A decomposition to do two
things only the implementer could realistically do:

1. Create `docs/adr/unit-test-coverage/_workflow/track-21-baseline.md`
   (post-track per-package coverage table + gate-result block).
2. Append a "Track 22 absorption" block to
   `docs/adr/unit-test-coverage/_workflow/implementation-backlog.md`
   recording the deferred items surfaced during Phase B (3 deletion
   lockstep groups, 1 WHEN-FIXED pin, 2 IT-expansion gap notes, 1
   convention codification).

But `implementer-rules.md` §242-249 says verbatim:

> The implementer **MUST NOT** modify the step file, the plan file, or
> the backlog. At `level=step` all step-file mutations … are the Phase B
> orchestrator's responsibility.

The two requirements are in direct contradiction at `level=step`:
- Step 7's task description tells the implementer to write the backlog.
- The rulebook tells the implementer they MUST NOT write the backlog.

This session's orchestrator worked around the contradiction by inlining
a one-off paragraph in the Step 7 spawn prompt:

> Step 7 is allowed to commit the new
> `docs/adr/unit-test-coverage/_workflow/track-21-baseline.md` and
> amendments to
> `docs/adr/unit-test-coverage/_workflow/implementation-backlog.md`
> because those are not the step file.

The implementer complied and the work landed cleanly (commit
`350e642fd5`). But the workaround is ad-hoc — every future track's
verification step will hit the same contradiction unless the rulebook
is amended, and a less attentive orchestrator might either (a) refuse
the work and stall, or (b) author the baseline + backlog edits itself
and re-introduce the per-step Maven traffic Phase B's split was
designed to keep out of orchestrator context.

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` § What you do (the
    rulebook prohibition at §242-249)
  - `.claude/workflow/step-implementation.md` § Implementer Prompt
    Template (which references the rulebook)
- Tool / sub-agent involved: per-step implementer (general-purpose,
  sonnet/opus, level=step, mode=INITIAL)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase B step whose task description requires
  writing files outside `<module>/src/test/...` — specifically the
  per-track verification step that produces `track-<N>-baseline.md`
  and updates `implementation-backlog.md`. Every track in this plan
  has such a step (Track 19 step 6, Track 20 step 6, Track 21 step 7,
  and Track 22 will too); the contradiction has been silently
  papered over each time by orchestrator-side prompt patches.

## Why it's a problem

Three concrete impacts:

1. **Doc-level inconsistency.** Two authoritative workflow files give
   contradictory instructions for the same agent in the same phase.
   A future agent reading either file in isolation will reach the
   wrong conclusion.
2. **Per-orchestrator workaround drift.** Each session's orchestrator
   reinvents the exception text. Wording drifts, allowed-path lists
   diverge, and the Phase A decomposition cannot rely on a stable
   contract when planning verification steps.
3. **Failure mode for stricter implementers.** If a future
   implementer's prompt-discipline tightens (e.g. via the proposed
   path-guard hook in
   `2026-05-08-phase-c-implementer-modified-step-file-progress.md`)
   the guard will reject the verification step's backlog write — the
   guard list and the rulebook prohibition share the same forbidden
   path, so the implementer would correctly refuse a legitimate task.

## Proposed fix

Edit `.claude/workflow/implementer-rules.md` § What you do (around
lines 242-249) to enumerate the verification-step exception:

> The implementer **MUST NOT** modify the step file, the plan file, or
> the backlog. At `level=step` all step-file mutations … are the Phase B
> orchestrator's responsibility. At `level=track` all plan/backlog
> mutations … are the Phase C orchestrator's responsibility.
>
> **Verification-step exception.** When the step description explicitly
> scopes the implementer to produce a per-track baseline file or to
> append a per-track absorption block to
> `implementation-backlog.md` (typical for the final "Verification +
> baseline" step of each track), the implementer is permitted to
> create / commit:
>
> - `docs/adr/<dir-name>/_workflow/track-<N>-baseline.md` (new file)
> - Append-only edits to
>   `docs/adr/<dir-name>/_workflow/implementation-backlog.md` under
>   the section the step description names (e.g., a Track-22
>   absorption block).
>
> The implementation-plan, design files, and tracks/track-*.md
> remain forbidden in this exception. The orchestrator does NOT
> need to inline a per-spawn exception when this rulebook clause
> covers the case — the prompt template's standard prohibition
> already routes through this exception by reference.

Optionally also update `.claude/workflow/step-implementation.md`
§ Implementer Prompt Template to cross-reference the exception so
spawn-time prompts don't need ad-hoc workarounds.

If the path-guard hook from
`2026-05-08-phase-c-implementer-modified-step-file-progress.md` is
implemented, the guard's allow-list must include
`track-<N>-baseline.md` and (append-only) `implementation-backlog.md`
for steps marked verification-style, gated by an env var the
orchestrator sets at spawn time (e.g.
`IMPL_ALLOWED_PATHS=baseline,backlog-append`).

## Acceptance criteria

- `implementer-rules.md` §242-249 contains a "Verification-step
  exception" subsection naming `track-<N>-baseline.md` and append-only
  `implementation-backlog.md` writes as permitted, with the
  implementation-plan / design / tracks-step-file paths still forbidden.
- `step-implementation.md` § Implementer Prompt Template no longer
  needs an inline per-spawn exception paragraph for verification
  steps; an audit of recent verification-step prompts shows they
  rely on the rulebook clause, not on inline patches.
- A regression check: a Phase B verification-step spawn whose
  task description scopes the implementer to write
  `track-<N>-baseline.md` + append to `implementation-backlog.md`
  succeeds without the orchestrator adding any spawn-time exception
  text beyond the standard prohibition.
- If the path-guard hook from
  `2026-05-08-phase-c-implementer-modified-step-file-progress.md`
  exists at the time of fix, its allow-list (or env-var gate) is
  updated in the same commit so the guard does not reject
  legitimate verification writes.
