# Track 1: conventions.md § Writing style anchor + CLAUDE.md scope broadening

## Purpose / Big Picture
After this track lands, `.claude/workflow/conventions.md` carries a canonical § "Writing style for Markdown and prose artifacts" section that every later pointer (Tracks 3-5) and the PreToolUse hook (Track 2) cross-reference, and the project `CLAUDE.md § Writing Style` block names "all Markdown files" instead of the narrow original 4-surface list.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Adds the canonical § "Writing style for Markdown and prose artifacts" section to `.claude/workflow/conventions.md` naming `house-style.md` as the rule source, listing the tier mapping (full house-style on Markdown, AI-tell subset on Java/Kotlin), and giving the citation every other pointer cross-refs. Also broadens the project `CLAUDE.md § Writing Style` block from the narrow 4-surface list to "all Markdown files" so project-level guidance mirrors the hook's Tier-A glob.

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

The repository already carries the rule file `.claude/output-styles/house-style.md` from YTDB-836 and a project-level `CLAUDE.md § Writing Style for Design Docs and Issues` block (lines 91-102) that names house-style.md as the rule source for four surfaces: ADR / design docs under `docs/adr/**`, GitHub issue bodies (`issue-*.md` scratch files), PR titles and descriptions, and YouTrack issue bodies. The narrower 4-surface scope predates the user's broader Markdown stance, which expanded mid-research: "we use *.md files for project documentation in wide patterns so we should apply to all *.md files."

`.claude/workflow/conventions.md` is loaded at every `/execute-tracks` startup, so its content is paid for by every Phase A/B/C session. It already follows the pattern of carrying a project-anchor section that other workflow files cross-reference — §1.4 *Tooling discipline* is the canonical anchor for PSI / grep / Maven / refactoring routing rules, with one paragraph per topic plus three sub-tables (recipes, hook routing, Mermaid). The new § "Writing style for Markdown and prose artifacts" follows the same shape: BLUF-led, citation-first, tier-mapping table, recipe table for which sections of house-style.md cover which audience.

This track produces two concrete deliverables: (1) a new §1.5 (or §1.6 depending on numbering — confirm during Phase A decomposition) in `conventions.md` carrying the canonical pointer, the Tier-A/Tier-B mapping, and the path-glob list; (2) a rewrite of `CLAUDE.md § Writing Style for Design Docs and Issues` to broaden its surface list from the four bullets to "all Markdown files in the repo" plus the supplementary list of non-Markdown surfaces (PR descriptions, commit message bodies, YouTrack issue bodies).

## Plan of Work

The track delivers in two passes. First pass adds the new conventions.md section as a self-contained anchor, with no prose dependencies on the older `CLAUDE.md` block; the wording must stand on its own because Tracks 3-5 will cross-reference this section, not `CLAUDE.md`. Second pass updates the project `CLAUDE.md` block to mirror the broader scope, and removes any drift between the two files. The order matters because Tracks 3-5 cross-reference the conventions.md anchor; landing the anchor first makes Track 2's hook text drafting (which cites the conventions.md anchor in its `additionalContext` message) trivial.

Ordering constraints: the conventions.md section's name and slug must be stable before Track 2 drafts the hook's additionalContext text — Track 2's stored reminder string includes the heading anchor. The CLAUDE.md update must not narrow any existing scope; it only broadens.

Invariants to preserve: the YTDB-836 rule file at `.claude/output-styles/house-style.md` stays unchanged. The `dsc-ai-tell` regex rule in `scripts/design-mechanical-checks.py` stays unchanged. The existing § references in `CLAUDE.md` to the rule file by path (`.claude/output-styles/house-style.md`) and by name ("House Style") stay intact and resolvable.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. -->

## Validation and Acceptance

- `.claude/workflow/conventions.md` contains a § "Writing style for Markdown and prose artifacts" section (exact wording to be settled in Phase A) that names `.claude/output-styles/house-style.md` as the rule source.
- The new section lists the tier mapping (Markdown → full house-style; Java/Kotlin → AI-tell subset; other → none) and the source-section names that constitute the AI-tell subset (Banned vocabulary, Banned sentence patterns, Banned analysis patterns, Em-dash discipline).
- `CLAUDE.md § Writing Style for Design Docs and Issues` is rewritten so its surface list reads "all Markdown files" plus the non-Markdown surfaces (PR descriptions, commit-message bodies, YouTrack issue bodies) and the YouTrack issue creation hint.
- Cross-references between `CLAUDE.md`, `conventions.md`, and `house-style.md` resolve in both directions (markdown link checker pass).
- The new conventions.md section fits within the file's existing voice and length budget (no section pushes the file over the ~30 K-token soft cap named in `conventions.md` §1.2).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong to one specific step. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/conventions.md` (add new section)
- `CLAUDE.md` (broaden § Writing Style surface list)

**Out-of-scope files:**
- `.claude/output-styles/house-style.md` (no rule changes per YTDB-837 non-goals)
- `.claude/scripts/design-mechanical-checks.py` (no enforcement changes)
- Every file modified by Tracks 2-5 (cross-referencing this track's anchor; not edited by this track)

**Inter-track dependencies:**
- **Upstream**: none.
- **Downstream**: Track 2 reads this track's new conventions.md section heading verbatim when drafting the hook's stored reminder strings. Tracks 3, 4, 5 cross-reference the new conventions.md section by relative path + anchor when adding their one-line pointers.

**Compatibility requirements:** The existing `CLAUDE.md § Writing Style` block is durable user-facing content (lands in the project README's CLAUDE.md surface). Broadening it is additive; narrowing any existing scope would be a regression.

**Library / function signatures relevant to this track:** none — this is a pure-documentation track.
