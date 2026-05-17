# Track 1: Rename `concise-doc.md` → `house-style.md` and consolidate declarative rules

## Purpose / Big Picture
After this track lands, the project has one declarative source of writing-style rules (`.claude/output-styles/house-style.md`) and every existing reference to the old name has been updated, so the acceptance gate `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md` returns zero matches.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Move `concise-doc.md` to `house-style.md` via `git mv`, rewrite its content per `design.md § Internal layout of house-style.md` (absorbing ai-tells Tier-3 vocab + extra rules, design-review.md's Human-reader cold-read additions, design-review.md's Structural findings, and the 12 humanizer-gap patterns with inline before/after examples), update the frontmatter `name:` and `description:` to name the broader scope, and find-and-replace the 14 string references to `concise-doc` / `Concise Doc` across `CLAUDE.md`, `.claude/skills/code-review/SKILL.md`, `.claude/agents/review-workflow-consistency.md`, `.claude/agents/review-workflow-writing-style.md`, and the renamed source file's own frontmatter. The acceptance check is `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md` returning zero matches.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was discovered" when the finding affects future steps or other tracks. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices, scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion summary at Phase C. -->

## Context and Orientation

This track touches the project's writing-style infrastructure under `.claude/`. The starting state was surveyed during research (see § Find-and-replace surface in `design.md`):

- **Source file (to be renamed):** `.claude/output-styles/concise-doc.md` (92 lines). Frontmatter `name: Concise Doc`. Carries BLUF + Tier-1/2 vocab + banned sentence patterns + em-dash discipline + structural rules + self-check.
- **Companion files (rules to absorb):**
  - `.claude/skills/ai-tells/SKILL.md` lines 27-156 carry Tier-1/2/3 vocab, structural tells, tone tells, punctuation tells, content tells. Tier-3 promotional vocab, vague-attribution rule, knowledge-cutoff disclaimer ban, Title Case heading rule, inline-header-list rule, curly→straight quotes rule, and excessive-boldface cap are *new to house-style.md* (not in concise-doc.md today).
  - `.claude/workflow/prompts/design-review.md` lines 90-172 carry the Human-reader cold-read additions (audience-fit, glossary-introduction, why-before-what, navigability rules) and lines 228-256 carry the Structural findings (Overview concept-first, References footer shape, Edge cases sub-section, same-shape sibling consolidation).
- **String references to update (14 total across 5 files):** `CLAUDE.md:93,102` (2), `.claude/skills/code-review/SKILL.md:313` (1), `.claude/agents/review-workflow-consistency.md:72` (1), `.claude/agents/review-workflow-writing-style.md:3,7,9,11,13,26,62,102,137` (9), and the source file itself `concise-doc.md:2` (1, frontmatter `name:`). Full table in `design.md § Rename: every reference site across the repo`.
- **Humanizer gap patterns (12 new):** drawn from the gap analysis against [blader/humanizer](https://github.com/blader/humanizer). Each needs an inline before/after example block (~5 lines per pattern, ~60 lines total). The 12: superficial -ing analyses, copula avoidance, passive voice and subjectless fragments, filler phrases (`In order to`, `At this point in time`, `Due to the fact that`), excessive hedging (`could potentially possibly`), generic positive conclusions (`the future looks bright`), persuasive authority tropes (`at its core`, `fundamentally`), signposting (`Let's dive in`, `Here's what you need`), fragmented headers, hyphenated word-pair overuse, elegant variation, false ranges.

Concrete deliverables this track produces:

1. New file `.claude/output-styles/house-style.md` — the consolidated declarative source. ~400-500 lines.
2. Removal of `.claude/output-styles/concise-doc.md` (via the `git mv` operation in step 1).
3. Updated `CLAUDE.md § Writing Style for Design Docs and Issues` block referencing the new filename and slash-command name.
4. Updated string references — 14 occurrences total — across the four companion files listed above plus the renamed source file's frontmatter.
5. Verified acceptance criterion: `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md` returns zero matches.

## Plan of Work

The approach moves from file rename to content rewrite to reference sweep, in that order, because the content rewrite needs the new file path to exist and the reference sweep needs to know which files now reference the new name.

1. **`git mv` + frontmatter.** Rename the file in one commit. Update frontmatter `name: Concise Doc` → `name: House Style` and rewrite the `description:` to name the broader scope (design / plan / track / issue / PR / commit-body / comment / status prose). No other content changes in this step — the rename and frontmatter update is the smallest reviewable unit.
2. **Content rewrite — full consolidated section structure.** Rewrite `house-style.md` to match `design.md § Internal layout of house-style.md`. Insert the absorbed rules from `ai-tells` (Tier-3 vocab, knowledge-cutoff disclaimer ban, inline-header-list rule, curly→straight quotes, excessive-boldface cap), from `design-review.md § Human-reader cold-read additions` (audience-fit, glossary-introduction, why-before-what, navigability), from `design-review.md § Structural findings` (Overview concept-first, References footer shape, Edge cases sub-section, same-shape sibling consolidation), and the 12 humanizer-gap patterns each with an inline before/after example block. Update the self-check to reference the new sections.
3. **Find-and-replace sweep.** Update the remaining 13 string references across `CLAUDE.md` (2), `.claude/skills/code-review/SKILL.md` (1), `.claude/agents/review-workflow-consistency.md` (1), and `.claude/agents/review-workflow-writing-style.md` (9) — the 14th reference (the source file's frontmatter `name:`) is covered by step 1. Each is a single-line edit; use the table in `design.md § Rename: every reference site across the repo` as the authoritative checklist. Where the source text says "concise-doc style" or "**concise-doc**" with markdown emphasis, preserve the emphasis style on the replacement.
4. **End-of-track grep verification.** Run `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md` and confirm zero matches. Any remaining match is a step-back to the FRR sweep.

Ordering constraints:
- Step 1 must precede step 2 (the file must exist at the new path before content goes in).
- Steps 2 and 3 are independent of each other once step 1 lands; the natural order is step 2 then step 3 so the content is in place before references point at it.
- Step 4 is the acceptance gate; it runs last and blocks track completion until it passes.

Invariants to preserve through every step:
- The `/output-style` slash command reads frontmatter `name:`. Step 1's frontmatter update must keep `name:` valid (matches `house-style` after kebab-case normalization).
- The `description:` frontmatter must enumerate the full surface list per the issue's scope split — design / plan / track / issue / PR / commit-body / comment / status prose.
- Cross-references in Tracks 2, 3, 4 will point at `house-style.md § <Section>` by name. The section headings created in step 2 must use the names enumerated in `design.md § Internal layout of house-style.md`; renames after this track lands would break the readers.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here: one entry per step with description, `risk:` tag, and a `[ ]` status checkbox. Per-step episodes do NOT live here; they live in `## Episodes` below. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step, identified by step number + commit SHA. Empty at Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level acceptance: `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md` returns zero matches AND `house-style.md` contains every section listed in `design.md § Internal layout of house-style.md` AND each of the 12 humanizer-gap patterns under § Banned analysis patterns has an inline before/after example.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong to one specific step. Per-step episode content lives in `## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/output-styles/concise-doc.md` → `.claude/output-styles/house-style.md` (rename + rewrite)
- `CLAUDE.md` (§ Writing Style block — lines 93, 102)
- `.claude/skills/code-review/SKILL.md` (line 313)
- `.claude/agents/review-workflow-consistency.md` (line 72)
- `.claude/agents/review-workflow-writing-style.md` (lines 3, 7, 9, 11, 13, 26, 62, 102, 137)

**Out-of-scope files (deferred to YTDB-837):**
- `.claude/workflow/conventions.md`
- `.claude/workflow/prompts/*.md` other than `design-review.md` (Track 3 handles that one)
- Implementer / orchestrator files under `.claude/workflow/`
- `.claude/agents/review-*.md` other than `review-workflow-consistency.md` and `review-workflow-writing-style.md`
- Any new pointer expansion — only *existing* references are touched here.

**Inter-track dependencies:**
- This track is a prerequisite for Tracks 2, 3, and 4. They all cross-reference `house-style.md § <Section>` by name; the file must exist and its sections must be stable before they land.
- This track has no upstream dependency on other tracks within YTDB-836.

**Library / function signatures:** none. This track is markdown editing.

**Cross-reference contract (downstream):**
- Tracks 2, 3, 4 will cite section names from `house-style.md`. The Phase 1 design fixes those names in `design.md § Internal layout of house-style.md`. Any section rename mid-track invalidates the downstream cross-references; if step 2 needs to deviate from the design's section list, it must propagate the new name to `design.md` first via an `edit-design` mutation.
