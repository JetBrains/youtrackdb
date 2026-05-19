# Track 4: Pointers in implementer + commit-convention files (Tier A + Tier B split)

## Purpose / Big Picture
After this track lands, the implementer rulebook, the per-step orchestrator protocol, the commit-message convention, and the episode-format reference all name house-style — Tier-A for the durable log / commit / PR / episode artifacts they produce, Tier-B for any code comments the implementer adds.

<!-- Reserved for Move 2. -->

Adds Tier-A pointer to `implementer-rules.md § Tooling discipline` for log / commit / PR prose, and Tier-B pointer for code-comment prose. Adds matching pointers in `step-implementation.md` (continuous-log + step-episode writes — Tier A), `commit-conventions.md` (message-body discipline — Tier A), and `episode-format-reference.md` (episode prose — Tier A).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Empty at Phase 1. -->

## Decision Log
<!-- Empty at Phase 1. -->

<!-- Reserved for Move 1. -->

## Outcomes & Retrospective
<!-- Empty at Phase 1. -->

## Context and Orientation

The implementer's prose surfaces span two distinct tiers, and `implementer-rules.md` is the only file in scope that needs both pointers in one location:

- **Tier-A surfaces** the implementer produces: commit message bodies (long-form `why`), episode-draft fields (`what_was_done`, `what_was_discovered`, `what_changed_from_plan`, `critical_context`), fix-notes (`what_was_fixed`, `what_was_skipped`, `what_was_discovered`), and CROSS_TRACK_HINTS prose. All these surfaces land in durable git-tracked artifacts.
- **Tier-B surfaces** the implementer produces: code comments, Javadoc bodies, test method names and descriptions where prose creeps in.

`implementer-rules.md § Tooling discipline` (lines ~911-936 in the file read in research) is the existing pointer block for cross-references to other discipline files. The new house-style pointer follows the same shape:

> - **House-style for prose**: `.claude/output-styles/house-style.md` is the rule set. Tier A (full house-style: BLUF lead, banned vocabulary, em-dash discipline, ≤200-word section cap, structural rules) applies to commit message bodies, episode-draft / fix-notes prose, CROSS_TRACK_HINTS, and any other durable artifact text. Tier B (AI-tell subset: Banned vocabulary, Banned sentence patterns, Banned analysis patterns, Em-dash discipline — structural rules do not apply) applies to code comments and Javadoc bodies. See `.claude/workflow/conventions.md § Writing style for Markdown and prose artifacts` for the workflow-level pointer.

`step-implementation.md` is the per-step orchestration protocol; its prose-producing sub-steps (sub-step 5 cross-track impact check, sub-step 6 episode synthesis) produce track-file episode entries that land in Markdown. Needs a Tier-A pointer.

`commit-conventions.md` is the canonical commit-message discipline file. Its content names the imperative summary, the body's WHY discipline, the Push-every-commit rule, and the `Review fix:` prefix convention. Needs a Tier-A pointer in the section that defines body discipline.

`episode-format-reference.md` is the canonical episode-format reference file. Episodes are prose entries the orchestrator writes into track files. Needs a Tier-A pointer.

## Plan of Work

The track delivers in two steps:

Step 1 — Add the Tier-A + Tier-B pointer block to `implementer-rules.md § Tooling discipline`, and add the Tier-A pointer to `episode-format-reference.md`. These two files share the strongest writer-of-episode-prose relationship; the pointer wording is the same in both modulo file-specific scope language.

Step 2 — Add the Tier-A pointer to `step-implementation.md` and `commit-conventions.md`. These cover the orchestrator-side episode synthesis and the commit-body discipline.

Ordering constraints: Track 1 must complete first; pointer text cites the conventions.md anchor by name. Tracks 2, 3, 5 are independent.

Invariants to preserve: every modified file's existing § headings stay intact. The pointer block in `implementer-rules.md § Tooling discipline` reads as one bullet (matching the existing bullets in that section), not as a new section. No file gets more than one pointer block.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Empty at Phase 1. -->

## Validation and Acceptance

- `implementer-rules.md § Tooling discipline` carries both the Tier-A and the Tier-B pointer in one bullet (verified by `grep -A2 'house-style' implementer-rules.md` showing both tier references).
- `step-implementation.md`, `commit-conventions.md`, and `episode-format-reference.md` each carry one Tier-A pointer (verified by `grep -l 'house-style'` returning all four files).
- The pointer wording is consistent across the four files (modulo file-specific scope language).
- YTDB-837 acceptance bullet 4 holds: "Implementer files name both tiers correctly: Tier-A for log / commit / PR, Tier-B for comments."

<!-- Phase A placeholder. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/implementer-rules.md` (add Tier-A + Tier-B pointer block in § Tooling discipline)
- `.claude/workflow/step-implementation.md` (add Tier-A pointer)
- `.claude/workflow/commit-conventions.md` (add Tier-A pointer in body-discipline section)
- `.claude/workflow/episode-format-reference.md` (add Tier-A pointer)

**Out-of-scope files:**
- All other workflow files (covered by Tracks 3, 5 or out of scope per YTDB-837).

**Inter-track dependencies:**
- **Upstream**: Track 1 (cross-references the new conventions.md section heading).
- **Downstream**: none.

**Compatibility requirements:**
- `implementer-rules.md` is read by the implementer sub-agent on every spawn. Adding one bullet under § Tooling discipline does not change the sub-agent contract.
- `commit-conventions.md` is read at every commit time by both the implementer and the orchestrator. The pointer is additive to existing rules.

**Library / function signatures relevant to this track:** none — pure documentation edits.
