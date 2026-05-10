---
severity: medium
phase: phase-b
source-session: 2026-05-10 /execute-tracks unit-test-coverage
---

# `mcp_steroid` field in TOOLING_NOTES has ambiguous semantics

## Symptom

The implementer return contract in
`.claude/workflow/implementer-rules.md` §Return contract defines:

```
TOOLING_NOTES:
  mcp_steroid: reachable | NOT_reachable
```

without documenting whether the field reports (a) the IDE/server
reachability state at the time of the spawn (a *status* field) or
(b) whether mcp-steroid was *used* during the spawn (a *usage*
field).

Across Track 22a Phase B's 9 implementer spawns, the field was
filled inconsistently:

- Steps 2, 4, 5, 6, 8, 9, 10 reported `reachable` — these spawns
  did use mcp-steroid (PSI find-usages, ReferencesSearch, or
  `steroid_apply_patch`).
- Step 3 reported `NOT_reachable` even though
  `steroid_list_projects` had succeeded for the orchestrator at
  session start and the IDE was open and matched the working
  tree. The Step 3 implementer's `notes` field clarified
  "mcp-steroid not used in this spawn — work was test-additive
  across 9 small files, no symbol refactors involved", indicating
  it was treating the field as a usage flag, not a status flag.

The orchestrator therefore could not rely on the field to detect
"IDE actually went down mid-track" vs "IDE was up but unused this
spawn".

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` §Return contract
    (the section that lists the `TOOLING_NOTES` block fields)
  - `.claude/workflow/step-implementation.md` §Implementer Prompt
    Template
- Tool / sub-agent involved: per-step implementer sub-agents
  (general-purpose opus)
- ADR directory at the time:
  `docs/adr/unit-test-coverage/_workflow/`
- Trigger condition: any Phase B / Phase C implementer spawn that
  did not invoke an mcp-steroid tool. The field is ambiguous in
  that case; reachable-but-unused looks the same as
  unreachable-and-skipped.

## Why it's a problem

The orchestrator uses `mcp_steroid` to decide whether subsequent
review prompts must include the
`"use PSI find-usages, not grep"` annotation versus the
`"grep-based; reference-accuracy caveat"` fallback. If a spawn
incorrectly reports `NOT_reachable`, the orchestrator may
downgrade subsequent reviews to grep-only on a still-functional
IDE — silently lowering audit quality. Conversely, if `reachable`
is reported when the IDE actually crashed mid-spawn (and the
implementer never tried it), a load-bearing audit might assume
PSI when it was never run.

Across the Track 22a session this caused no functional harm
because the orchestrator did not actually use the field. But
future sessions that auto-route reviews on `mcp_steroid` will
break in subtle ways.

## Proposed fix

Edit `.claude/workflow/implementer-rules.md` §Return contract to
document the field's semantics explicitly. Recommended choice:
**status semantics** (matches the `mcp-steroid: reachable / NOT
reachable` SessionStart hook output). Add wording such as:

> `mcp_steroid: reachable | NOT_reachable` — the IDE
> reachability state observed during the spawn. `reachable` means
> the implementer either used mcp-steroid successfully or
> confirmed the IDE/project alignment via `steroid_list_projects`.
> `NOT_reachable` means the implementer attempted an mcp-steroid
> tool and got an error/timeout, OR the SessionStart hook had
> already reported NOT reachable and the implementer did not
> re-probe. **Do not use this field to report whether mcp-steroid
> was used by the spawn — the `psi_audits` count and the `notes`
> line cover that.**

Optionally add a third value `not_probed` for spawns that neither
attempted an mcp-steroid call nor confirmed alignment, so the
field strictly reflects what the implementer observed.

## Acceptance criteria

- `.claude/workflow/implementer-rules.md` §Return contract has an
  explicit one-paragraph definition of `mcp_steroid` semantics.
- The implementer prompt template in
  `.claude/workflow/step-implementation.md` (§Implementer Prompt
  Template, the `## Workflow Context (static)` block) either
  cites the rulebook subsection or restates the rule.
- A subsequent Phase B / Phase C session does not produce
  `mcp_steroid: NOT_reachable` from a spawn whose IDE was actually
  reachable.
- (If the third value `not_probed` is adopted) the rulebook lists
  it and the orchestrator's review-routing logic handles it as
  equivalent to `reachable` for routing purposes (defensively
  routes through PSI on the next spawn).
