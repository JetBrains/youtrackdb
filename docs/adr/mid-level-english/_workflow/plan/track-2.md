<!-- workflow-sha: f74ef47e943f3bf1900f1f5ab42740d63fe3e588 -->
# Track 2: Propagate the slug to the workflow prompts and skills

## Purpose / Big Picture
After this track lands, every workflow prompt and skill that names the AI-tell subset lists the new `## Plain language` slug, and the design cold-read checks for it.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the sixth section slug to the 11 `.claude/workflow/prompts/*.md` enumerations and the 6 `.claude/skills/*/SKILL.md` enumerations. One edit is more than a slug add: `design-review.md` runs the cold-read, so its Human-reader rules list gains the `## Plain language` rule as a real check.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Track-canonical live decisions (D7). This track executes plan D3/D4; it owns one content-edit decision. -->

#### D2-1: The design cold-read gains a real Plain-language check, not just a slug add
- **Alternatives considered**: add the `## Plain language` slug to `design-review.md`'s enumeration only (treat it like the other 10 prompts); also add it to the cold-read Human-reader rules so the cold-read verifies plain language in design docs (chosen).
- **Rationale**: `design-review.md` does not merely cite the subset — it drives the cold-read that checks whether a fresh reader can follow a design doc. A new always-on clarity rule belongs in the rules the cold-read applies, the same way `## Orientation` was added there in #1142 (the +62-line `design-review.md` change in that flip). A slug-only edit would list the rule but never check it.
- **Risks/Caveats**: the cold-read gains one more lens; keep the addition short so it does not bloat the prompt's context budget. Plain-language quality stays a judgment call (plan D2) — the cold-read reports it as a finding, no score.
- **Implemented in**: this track (`design-review.md`).

The other 16 files in this track are mechanical slug additions that execute plan D3 (natural reach) and plan D4 (the §1.7(k) opt-out edits them live). They carry no track-local decision; the rationale is the plan's D3/D4.

## Outcomes & Retrospective
<!-- Continuous-log. -->

## Context and Orientation
The 11 workflow prompts under `.claude/workflow/prompts/` each carry a house-style preamble naming the five subset slugs (the "five AI-tell subset section slugs to apply are …" sentence, e.g. `adversarial-review.md:43`, `technical-review.md:31`, `consistency-review.md:96`). `design-review.md` is the exception in kind: besides the preamble, it holds the cold-read Human-reader rules that the rule must join (`design-review.md:458` references "the five Human-reader rules" / "the § Prose AI-tell additions").

The 6 skills under `.claude/skills/` cite the subset in their startup read-lists or house-style notes (`create-plan/SKILL.md:23`, `execute-tracks/SKILL.md:23`, `review-plan/SKILL.md:31`, `review-workflow-pr/SKILL.md:45`, `readability-feedback/SKILL.md`, `ai-tells/SKILL.md:23` — the last cites only "Banned vocabulary" by name, so it may need no slug edit; confirm at Phase A).

This track depends on Track 1: the `## Plain language` section and the §1.5 mapping must exist before these enumerations name the slug.

## Plan of Work
1. Add `## Plain language` to the five-slug enumeration in each of the 11 prompts (after `### Em-dash discipline` / `## Orientation`, matching the existing slug order). Flip any numeric "five" → "six" in the same sentence.
2. In `design-review.md`, also add the rule to the cold-read Human-reader rules / Prose AI-tell additions list, so the cold-read checks plain language, not just lists it (D2-1).
3. Add `## Plain language` to the subset enumeration in each of the 6 skills. Confirm at Phase A whether `ai-tells/SKILL.md` needs an edit (it may name only "Banned vocabulary").

Invariant to preserve: every prompt and skill enumeration this track touches ends at exactly six slugs in the canonical order, and any numeric count reads "six".

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- Every one of the 11 prompts lists `## Plain language` in its subset enumeration; counts read "six".
- `design-review.md`'s cold-read Human-reader rules include the Plain-language check.
- Every one of the 6 skills that enumerates the subset lists `## Plain language` (or is confirmed not to enumerate it).
- All edited prose reads in plain language (self-application).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope (this track):**
- 11 prompts: `.claude/workflow/prompts/{technical-review,consistency-review,structural-gate-verification,review-gate-verification,adversarial-review,risk-review,create-final-design,consistency-gate-verification,dimensional-review-gate-check,structural-review,design-review}.md`. `design-review.md` also gets the cold-read content edit (D2-1).
- 6 skills: `.claude/skills/{ai-tells,review-plan,create-plan,execute-tracks,readability-feedback,review-workflow-pr}/SKILL.md`.

**Out of scope (other tracks):** `house-style.md`, `house-conversation.md`, `conventions.md`, the core workflow docs, the hook, the test, `CLAUDE.md` (Track 1); the 20 review agents (Track 3).

**Dependencies:** depends on Track 1 (the `## Plain language` section and §1.5 mapping must exist first). Independent of Track 3.

**Exact in-scope set is derived by `grep -rln 'Banned analysis patterns' .claude/workflow/prompts/ .claude/skills/` at Phase A and reconciled against this list (lite-tier requirement; figure ~17 is approximate, pending the `ai-tells/SKILL.md` confirmation).**
