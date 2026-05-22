# Track 2: Stamp writers

## Purpose / Big Picture
After this track lands, every newly created `_workflow/**` artifact carries a line-1 workflow-SHA stamp at the moment of creation — no manual step, no separate helper invocation.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Update `/create-plan` SKILL and `edit-design` SKILL to emit the stamp at every artifact-creation site. Four sites total: `implementation-plan.md`, `plan/track-N.md` (created in `/create-plan`); `design.md`, `design-mechanics.md` (created in `edit-design` under `phase1-creation` and `length-trigger-crossing` respectively). Direct mutations through `edit-design` leave the stamp untouched. `design-mutations.md` is deliberately excluded: append-only log, no replay, no stamp.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

Two SKILL files own all the artifact-creation sites:

- `.claude/skills/create-plan/SKILL.md` — Step 4 templates for `implementation-plan.md` and each `plan/track-N.md`. The templates are embedded markdown blocks within Step 4's prose; the writer is the agent invoking `Write` with the rendered template.
- `.claude/skills/edit-design/SKILL.md` — Step 1's `phase1-creation` branch creates `design.md` (and optionally `design-mechanics.md` when the design needs a companion); `length-trigger-crossing` creates `design-mechanics.md` mid-life when the file crosses the trigger. (`design-mutations.md` is created by Step 7's first-append branch but is deliberately not stamped; see the intro.)

The stamp computation one-liner — `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` — defined in §1.6 of `conventions.md` (Track 1) — is invoked once per `create-plan` session and once per `edit-design` invocation. Multiple artifacts created in the same session share the same SHA.

The stamp goes on line 1, immediately before the H1. For `implementation-plan.md` the line-2 H1 is `# <Feature Name>`; for `design.md` it is `# <Feature Name> — Design`; for `plan/track-N.md` it is `# Track N: <title>`. The template body in each SKILL needs a `<!-- workflow-sha: $SHA -->` line prepended.

## Plan of Work

Edit `create-plan/SKILL.md` first. In Step 4, update the `implementation-plan.md` template block to lead with `<!-- workflow-sha: $WORKFLOW_SHA -->\n` followed by `# <Feature Name>`. Add a one-line preamble to Step 4 instructing the agent to compute `$WORKFLOW_SHA` once via `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` and reuse it for every artifact created in this `/create-plan` session. Update the `plan/track-N.md` template block the same way (stamp on line 1, `# Track N: <title>` on line 2).

Then edit `edit-design/SKILL.md`. In Step 1's `phase1-creation` branch, instruct the agent to fetch `$WORKFLOW_SHA` (either reusing the value computed by the caller, typically `/create-plan`, or computing it fresh when the caller is something else) and prepend it to the seeded `design.md`. Do the same for `design-mechanics.md` when the kind is `phase1-creation` with `target=both` or when `length-trigger-crossing` creates the mechanics file. Step 7 (the design-mutations.md writer) is deliberately NOT touched: the file is excluded from stamping (see intro and Validation). Add a short note in Step 7's prose explaining the exclusion so future SKILL readers don't re-add a stamp out of mistaken uniformity.

Cross-cutting: add a one-paragraph "Stamp" note near the top of each SKILL explaining that stamps are written at creation only — direct-mutation kinds (`content-edit`, `section-add`, `section-remove`, etc.) leave the stamp untouched. This nails down invariant I4 from the plan.

Sanity-test: after the edits land, an integration probe (one Bash session) creates a tiny fake `_workflow/` plan directory through the SKILL flow and verifies every produced file has the stamp on line 1 matching the regex `^<!-- workflow-sha: [0-9a-f]{40} -->$`.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 2 lands:

- A fresh `/create-plan` session produces an `implementation-plan.md` and one or more `plan/track-N.md` files, each carrying `<!-- workflow-sha: <40-char SHA> -->` on line 1.
- A fresh `edit-design phase1-creation` invocation produces a `design.md` carrying the same stamp on line 1. When `target=both`, `design-mechanics.md` carries it too.
- `design-mutations.md` carries NO line-1 stamp after any number of `edit-design` mutations. `head -1 design-mutations.md` returns the H1 `# Design Mutations Log`, not a workflow-sha comment.
- A direct-mutation kind (e.g., `section-add`) on an already-stamped `design.md` leaves the line-1 stamp byte-for-byte identical (verifiable via `head -1` before and after).
- The `length-trigger-crossing` mutation, when it creates `design-mechanics.md`, prepends the stamp to the new file.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/skills/create-plan/SKILL.md` (Step 4 templates + Step 4 preamble)
- `.claude/skills/edit-design/SKILL.md` (Step 1 phase1-creation, Step 1 length-trigger-crossing, Step 7 first-append; plus a Stamp-discipline note)

**Out-of-scope files:**
- `.claude/workflow/conventions.md` (Track 1)
- `.claude/workflow/workflow-drift-check.md` (Track 3)
- `.claude/skills/migrate-workflow/SKILL.md` (Tracks 4a and 4b)
- `.claude/workflow/self-improvement-reflection.md` (Track 5)
- Phase 4 artifact creation in `.claude/workflow/prompts/create-final-design.md` — Non-Goal (D3).

**Inter-track dependencies:**
- **Depends on:** Track 1 (stamp format definition in `conventions.md` §1.6 — the SKILL bodies link there rather than restating the format).
- Tracks 3 and 4 read stamps from artifacts produced by this track. Until Track 2 lands, those readers fall back to fork-point semantics for every artifact (since none are stamped). Track 2 unlocks the SHA-aware behavior end-to-end.

**External interfaces:**
- `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` is the only new git invocation introduced. It runs at SKILL-invocation time, not at file-write time, so it is read-only with respect to the artifacts.
