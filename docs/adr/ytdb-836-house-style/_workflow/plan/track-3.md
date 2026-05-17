# Track 3: Refactor `prompts/design-review.md` to verification-only

## Purpose / Big Picture
After this track lands, the cold-read sub-agent prompt at `.claude/workflow/prompts/design-review.md` is ≤200 lines and contains no declarative rule statements — every verification entry names the rule it checks and cites `house-style.md § <Section>` rather than restating the rule's content.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Strip declarative rule statements from `prompts/design-review.md § Human-reader cold-read additions` (audience-fit, glossary-introduction, why-before-what, navigability) and from `§ Structural findings` (Overview concept-first, References footer, Edge cases, same-shape siblings). Each verification entry must reference the rule by name (e.g., "Verify audience-fit per house-style.md § Audience-fit"). Keep Q1-Q7 comprehension questions, plan-deviation surfacing for `phase4-creation`, and the mutation-kind-specific instructions. Final file must be ≤ 200 lines.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

Starting state of `.claude/workflow/prompts/design-review.md` (346 lines, surveyed during research):

- **Lines 1-44 — Header, Inputs, Mutation-kind specific instructions.** Keep wholesale. These describe the prompt's interface (the sub-agent's inputs) and the per-mutation-kind variations (`phase1-creation`, `design-sync`, `phase4-creation`). Trimming these would break callers.
- **Lines 45-89 — Mutation-kind specific instructions for `phase1-creation`, `design-sync`, `phase4-creation`** (continued). Keep wholesale. The `phase4-creation` plan-deviation block (lines 75-89) is explicitly preserved per the issue acceptance criteria.
- **Lines 90-183 — Human-reader cold-read additions.** This is the largest block to strip. Sub-sections (a) Audience-fit (lines 119-129), (b) Glossary-introduction (lines 131-148), (c) Why-before-what (lines 150-162), (d) Navigability (lines 164-172) currently each restate the rule body. Replace each with a one-line verification statement that names the rule and cites `house-style.md § <Section>`. The "Reviewer tone" prose (lines 174-183) — exception to the one-sentence-answers guidance — stays as procedural meta.
- **Lines 185-199 — Reading rules.** Keep wholesale. Procedural.
- **Lines 200-225 — Comprehension questions (Q1-Q7).** Keep wholesale per the issue acceptance criteria. Q1-Q7 are the cold-read's load-bearing content.
- **Lines 227-256 — Structural findings (always check).** Each bullet currently restates the rule body. Same pattern as the Human-reader block: replace with a one-line "Verify <rule> per house-style.md § <Section>" form. Edge cases / Gotchas, References footer, same-shape sibling, length budget, Overview concept-first (whole-doc only), Core Concepts current (whole-doc only), `**Full design**` refs (whole-doc only) — all move to one-liners.
- **Lines 258-308 — Output format.** Keep wholesale. The exact Markdown output shape is the sub-agent's contract with the calling mutation action.
- **Lines 310-329 — Severity rubric.** Keep wholesale. Procedural — describes how the sub-agent classifies findings.
- **Lines 331-346 — Tone and depth.** Keep wholesale. Procedural — meta-rules for the sub-agent's writing.

Estimated trim impact:
- Lines 1-89 (Mutation-kind block): keep ~89 lines.
- Lines 90-183 (Human-reader additions): trim from ~93 lines to ~25 lines.
- Lines 185-199 (Reading rules): keep ~14 lines.
- Lines 200-225 (Comprehension questions): keep ~25 lines.
- Lines 227-256 (Structural findings): trim from ~30 lines to ~15 lines.
- Lines 258-346 (Output format + Severity + Tone): keep ~89 lines.

Estimated final length: ~157-170 lines, comfortably under the 200-line cap.

## Plan of Work

The approach trims the two declarative-content blocks (Human-reader additions, Structural findings) in place, leaving the surrounding scaffold (mutation-kind instructions, comprehension questions, output format, severity rubric, tone meta) untouched. Order:

1. **Strip the Human-reader cold-read additions to verification-only form.** Replace each of the four sub-sections — (a) Audience-fit, (b) Glossary-introduction, (c) Why-before-what, (d) Navigability — with a single-bullet "Verify X per house-style.md § Y" statement. Keep the severity guidance ("blocker if the reader cannot follow the Overview's main argument; should-fix otherwise") since severity is a per-prompt decision, not a declarative writing rule. Keep the "Reviewer tone exception" prose since it's procedural meta about how the sub-agent should write findings.
2. **Strip the Structural findings block to verification-only form.** Same pattern: each bullet becomes a one-liner naming the rule and citing `house-style.md § <Section>`. Bullets that have no `house-style.md` equivalent (e.g., the `Mechanics:` link target check, the `**Full design**` link resolution check) stay as-is — those are about cross-file mechanics, not about writing style.
3. **Verify ≤200 lines.** Run `wc -l .claude/workflow/prompts/design-review.md` and confirm ≤200. If over, identify which block is over-budget and trim further; the natural candidates for additional trimming are the Comprehension questions (Q1-Q7 — these are non-negotiable per acceptance criteria, so this is last resort) or the Tone and depth section (procedural prose that could be tightened).

Invariants to preserve:
- Q1-Q7 stay intact (acceptance criterion).
- `phase4-creation` plan-deviation surfacing instructions stay intact (acceptance criterion).
- Mutation-kind-specific instructions (`phase1-creation`, `design-sync`, `phase4-creation` blocks) stay intact (acceptance criterion).
- Output format remains the exact Markdown shape the calling mutation action expects.
- Cross-references cite section names that exist in `house-style.md` as defined in Track 1.

## Concrete Steps

## Episodes

## Validation and Acceptance

Track-level acceptance: `wc -l .claude/workflow/prompts/design-review.md` returns ≤200 AND every cross-reference resolves to an existing section in `house-style.md` AND a manual pass through the file confirms no declarative rule body remains (each rule is named-and-cited only).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/prompts/design-review.md` (in-place edit of two content blocks; surrounding scaffold preserved).

**Out-of-scope files:**
- `house-style.md` — Track 1 owns its content; this track only adds inbound references.
- `.claude/workflow/design-document-rules.md` — references `design-review.md` at lines 153, 277, 374 but those references survive the refactor (the file's path and existence don't change, only its internal content).
- `.claude/skills/edit-design/SKILL.md` — references `design-review.md` at lines 252, 530 but those references survive the refactor too.

**Inter-track dependencies:**
- **Depends on:** Track 1. Verification entries cite `house-style.md § <Section>` names that Track 1 fixes.

**Library / function signatures:** none. Markdown editing.

**Caller contract:**
- The `edit-design` skill invokes this prompt with the inputs listed at the top of the file (`design_path`, `design_mechanics_path`, `scope`, `mutation_kind`, `plan_path`, `plan_dir`). The skill's invocation surface is unchanged; only the prompt's internal content changes.
- The prompt's output format (the exact Markdown structure at lines 258-308) is the sub-agent's return contract. Any deviation breaks the calling skill's parsing.
