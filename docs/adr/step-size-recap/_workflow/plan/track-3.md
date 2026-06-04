<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 3: File-footprint scope indicators

## Purpose / Big Picture
After this track, the plan-checklist scope indicator reports a planned file footprint (`~N files covering X, Y, Z`) instead of a step count, and structural and consistency review's sizing check keys off that footprint instead of a pre-decomposition step count.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Rewrite the scope indicator from `~N steps` to `~N files` (D8). A step count pre-judges Phase A decomposition; a planned file footprint is knowable at plan time and rides the same `~12`/`~5` thresholds the sizing rules use. Edits the convention spec (`conventions.md` §Scope indicators / §1.1 / §1.2), the writers (`create-plan/SKILL.md`, `planning.md`), the checkers (`structural-review.md`, `consistency-review.md`, the Phase A review-prompt glossaries, `implementation-review.md`), and the renderers (`plan-slim-rendering.md`, `inline-replanning.md`, `review-workflow-consistency.md`).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

All edit targets are workflow `.md` files under `.claude/workflow/**` and `.claude/skills/**`. This plan is workflow-modifying, so every edit routes to a staged copy under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...` per `conventions.md §1.7`; the live `.claude/**` tree stays at develop's state until the Phase 4 promotion. On first touch of a file, copy the live version into the staged path verbatim, then edit the staged copy (§1.7(e)).

The scope indicator is defined once and consumed in many places. Line numbers are against `cb5eec65`/develop's live tree; verify on first touch of each staged copy.

- **Spec** — `conventions.md` §Scope indicators (required) (`:276`; format `~N steps covering X, Y, Z` at `:285`; the three purposes at `:287`, where purpose #1 is structural review's sizing check), the §1.1 Glossary "Scope indicator" row (`:72`), and the §1.2 Checklist examples (`:203`, `:206`).
- **Writers** — `create-plan/SKILL.md` (`:210`, `:329`, `:333`) and `planning.md` (§Scope indicators `:455`, the `**Scope:**` caller-tree estimate-refinement note `:554`).
- **Checkers (read the step count)** — `structural-review.md` (sizing check `:128` "describing 8 distinct changes but claiming ~2 steps is suspect"; format issues `:344`; implausible-scope `:367`), `consistency-review.md` (`:237`), the Phase A review-prompt glossaries `technical-review.md` / `risk-review.md` / `adversarial-review.md` (each defines "Scope indicator" with the `~N steps` format), and `implementation-review.md` (`:205`, `:255`, `:356`).
- **Renderers / other readers** — `plan-slim-rendering.md` (`:166`, example `~6 steps`), `inline-replanning.md` (`:235`, `:271` `**Scope:**` mentions), and `review-workflow-consistency.md` (`:86`, the glossary-term consistency check).

Non-obvious terminology (full gloss in design.md §"Scope indicators measure file footprint, not steps"): **file-footprint scope indicator** (the `~N files covering X, Y, Z` form), **size-versus-norm check** (the rekeyed structural/consistency sizing check), **plan-file-only** (the check reads only the plan-checklist `**Scope:**` line, no track-file read).

Concrete deliverables: a rewritten §Scope indicators spec stating the `~N files` format with the rekeyed purpose #1; an updated §1.1 glossary row and §1.2 examples; writer updates in `create-plan/SKILL.md` and `planning.md`; checker updates in `structural-review.md`, `consistency-review.md`, the three Phase A review-prompt glossaries, and `implementation-review.md`; and renderer updates in `plan-slim-rendering.md`, `inline-replanning.md`, and `review-workflow-consistency.md`.

## Plan of Work

The work is four coherent edits along a spec → writers → checkers → renderers axis. The ordering below is a sensible default; the spec edit should land first so the writer/checker/renderer edits can cite the new format.

1. **Rewrite the convention spec** (D8) in `conventions.md`: change §Scope indicators (required) to the `~N files covering X, Y, Z` format, rekey purpose #1 (structural sizing check) to footprint-vs-track-size, keep the "estimates, not exact counts" rule; update the §1.1 Glossary "Scope indicator" row and the §1.2 Checklist examples.
2. **Update the writers** (D8): `create-plan/SKILL.md` and `planning.md` — the scope-indicator format the planner emits and the caller-tree estimate-refinement note.
3. **Update the checkers** (D8): the `structural-review.md` and `consistency-review.md` sizing checks (claimed-vs-described → size-vs-norm, plan-file-only); the Phase A review-prompt glossaries (`technical-review.md`, `risk-review.md`, `adversarial-review.md`); and the `implementation-review.md` scope-indicator mentions.
4. **Update the renderers** (D8): the `plan-slim-rendering.md` example, the `inline-replanning.md` `**Scope:**` mentions, and the `review-workflow-consistency.md` glossary-term reference.

Invariants to preserve: the scope indicator stays a required, plan-file-only signal; the "estimates, not exact counts" rule stays; structural review keeps reading scope indicators plan-file-only (no track-file read introduced); the `~12`/`~5` thresholds are unchanged (Track 1 owns those values; this track only points the check at them); this branch's own `implementation-plan.md` scope lines stay `~N steps` under the live convention.

<!-- Phase A appends a per-step sequencing summary referencing the Concrete Steps roster. -->

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance (Phase A turns these into per-step EARS/Gherkin lines):

- `conventions.md` §Scope indicators (required) states the `~N files covering X, Y, Z` format; the §1.1 Glossary "Scope indicator" row and the §1.2 Checklist examples match; the "estimates, not exact counts" rule survives.
- Structural and consistency review's sizing check reads in file-footprint terms (size-vs-norm) and stays plan-file-only; no `~N steps`/step-count phrasing remains in `structural-review.md` or `consistency-review.md`.
- The Phase A review-prompt glossaries (`technical-review.md`, `risk-review.md`, `adversarial-review.md`) define the scope indicator in the `~N files` form.
- `create-plan/SKILL.md` and `planning.md` instruct the planner to emit `~N files`.
- `plan-slim-rendering.md`, `inline-replanning.md`, and `review-workflow-consistency.md` carry no stale `~N steps` scope-indicator references.
- Every edit lives under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...`; the live `.claude/**` tree is byte-unchanged from develop; this branch's `implementation-plan.md` scope lines remain `~N steps`.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (staged copies under `_workflow/staged-workflow/.claude/...`):**
- `.claude/workflow/conventions.md` — §Scope indicators (required), the §1.1 Glossary "Scope indicator" row, the §1.2 Checklist examples.
- `.claude/skills/create-plan/SKILL.md` — the scope-indicator writer instruction and examples.
- `.claude/workflow/planning.md` — §Scope indicators and the `**Scope:**` estimate-refinement note.
- `.claude/workflow/prompts/structural-review.md`, `.claude/workflow/prompts/consistency-review.md` — the sizing-check rekey.
- `.claude/workflow/prompts/technical-review.md`, `.claude/workflow/prompts/risk-review.md`, `.claude/workflow/prompts/adversarial-review.md` — the Phase A "Scope indicator" glossary definitions.
- `.claude/workflow/implementation-review.md` — the scope-indicator mentions.
- `.claude/workflow/plan-slim-rendering.md` — the `~N steps` rendering example.
- `.claude/workflow/inline-replanning.md` — the `**Scope:**` new-track / revise-track mentions.
- `.claude/agents/review-workflow-consistency.md` — the glossary-term reference.

**Out-of-scope (owned by other tracks or deliberately not edited):**
- `conventions.md §1.1 "Step" row`, `track-review.md`, `risk-tagging.md` — Track 1; `review-agent-selection.md`, `step-implementation.md`, `track-code-review.md`, `code-review/SKILL.md` — Track 2.
- The `~12`/`~5` threshold values themselves — Track 1 owns the sizing thresholds; this track only points the scope-indicator check at them.
- This branch's own `implementation-plan.md` scope lines — they stay `~N steps` (live convention) until the Phase 4 promotion, if migrated at all.

**Dependencies:**
- **Independent track** — no dependency on Track 1 or Track 2, and neither depends on it.
- **Cross-track file:** `conventions.md` is also touched by Track 1 (the §1.1 "Step" row), disjoint from this track's §1.2 / §Scope indicators / §1.1 "Scope indicator" row. Under §1.7 staging the staged copy accumulates both tracks' edits; each track's Phase C review delta-scopes to its own sections.

**Staging contract:** workflow-modifying marker present in `implementation-plan.md` §Constraints; writes route to the staged subtree; the staged-vs-live delta gets the Phase C `§1.7(h)` review, delta-scoped to the live-vs-staged diff (D5 convention), not the whole-file staged copy.
