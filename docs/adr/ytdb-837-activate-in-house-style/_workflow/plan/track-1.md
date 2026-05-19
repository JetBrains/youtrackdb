# Track 1: conventions.md § Writing style anchor + CLAUDE.md scope broadening

## Purpose / Big Picture
After this track lands, `.claude/workflow/conventions.md` carries a canonical § "Writing style for Markdown and prose artifacts" section that every later pointer (Tracks 3-5) and the PreToolUse hook (Track 2) cross-reference, and the project `CLAUDE.md § Writing Style` block names "all Markdown files" instead of the narrow original 4-surface list.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Adds the canonical § "Writing style for Markdown and prose artifacts" section to `.claude/workflow/conventions.md` naming `house-style.md` as the rule source, listing the tier mapping (full house-style on Markdown, AI-tell subset on Java/Kotlin), and giving the citation every other pointer cross-refs. Also broadens the project `CLAUDE.md § Writing Style` block from the narrow 4-surface list to "all Markdown files" so project-level guidance mirrors the hook's Tier-A glob.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-19T13:33Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-19T13:42Z [ctx=safe] Step 1 complete (commit 9ca6e8e78e)
- [x] 2026-05-19T13:46Z [ctx=safe] Step 2 complete (commit dbfbfb83b5)
- [x] 2026-05-19T13:46Z [ctx=safe] Step implementation complete
- [x] 2026-05-19T14:01Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- 2026-05-19T13:42Z — Stable cross-references for downstream tracks: heading slug `## 1.5 Writing style for Markdown and prose artifacts` and relative path `../output-styles/house-style.md` (from conventions.md). Tracks 2-5 cite these exact strings. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (4 findings, 3 accepted, 1 deferred). T1/T2 reworded §1.4 shape claim + dropped misattributed §1.2 token-cap citation; T3 folded into T1 (H3-nesting hint for `Em-dash discipline`); T4 deferred — cosmetic phrasing alignment between Track 1's Downstream description ("relative path + anchor") and Tracks 3-5's Upstream wording ("section heading") to be cleaned up opportunistically if those tracks amend the relevant text during their own Phase A.
- Risk: skipped (Track 1 is Simple, 1-2 steps; complexity table mandates Technical only).
- Adversarial: skipped (same reason).

## Context and Orientation

The repository already carries the rule file `.claude/output-styles/house-style.md` from YTDB-836 and a project-level `CLAUDE.md § Writing Style for Design Docs and Issues` block (lines 91-102) that names house-style.md as the rule source for four surfaces: ADR / design docs under `docs/adr/**`, GitHub issue bodies (`issue-*.md` scratch files), PR titles and descriptions, and YouTrack issue bodies. The narrower 4-surface scope predates the user's broader Markdown stance, which expanded mid-research: "we use *.md files for project documentation in wide patterns so we should apply to all *.md files."

`.claude/workflow/conventions.md` is loaded at every `/execute-tracks` startup, so its content is paid for by every Phase A/B/C session. It already follows the pattern of carrying a project-anchor section that other workflow files cross-reference — §1.4 *Tooling discipline* is the canonical anchor for PSI / grep / Maven / refactoring routing rules, with a short preamble, three to five tight subsections, and a single tail table (the recipes table). The new § "Writing style for Markdown and prose artifacts" follows the same shape: BLUF-led, citation-first, one tier-mapping table that names the AI-tell subset sections by heading (with `Em-dash discipline` flagged as an H3 nested under `Punctuation and typography`).

This track produces two concrete deliverables: (1) a new §1.5 (or §1.6 depending on numbering — confirm during Phase A decomposition) in `conventions.md` carrying the canonical pointer, the Tier-A/Tier-B mapping, and the path-glob list; (2) a rewrite of `CLAUDE.md § Writing Style for Design Docs and Issues` to broaden its surface list from the four bullets to "all Markdown files in the repo" plus the supplementary list of non-Markdown surfaces (PR descriptions, commit message bodies, YouTrack issue bodies).

## Plan of Work

The track delivers in two passes. First pass adds the new conventions.md section as a self-contained anchor, with no prose dependencies on the older `CLAUDE.md` block; the wording must stand on its own because Tracks 3-5 will cross-reference this section, not `CLAUDE.md`. Second pass updates the project `CLAUDE.md` block to mirror the broader scope, and removes any drift between the two files. The order matters because Tracks 3-5 cross-reference the conventions.md anchor; landing the anchor first makes Track 2's hook text drafting (which cites the conventions.md anchor in its `additionalContext` message) trivial.

Ordering constraints: the conventions.md section's name and slug must be stable before Track 2 drafts the hook's additionalContext text — Track 2's stored reminder string includes the heading anchor. The CLAUDE.md update must not narrow any existing scope; it only broadens.

Invariants to preserve: the YTDB-836 rule file at `.claude/output-styles/house-style.md` stays unchanged. The `dsc-ai-tell` regex rule in `scripts/design-mechanical-checks.py` stays unchanged. The existing § references in `CLAUDE.md` to the rule file by path (`.claude/output-styles/house-style.md`) and by name ("House Style") stay intact and resolvable.

Phase A sequencing summary: Step 1 lands the new conventions.md anchor section so Tracks 2-5 have a stable heading slug to cross-reference; Step 2 broadens the project CLAUDE.md § Writing Style block to match. Both steps are markdown-only, low-risk, independently revertable.

## Concrete Steps
1. Add § "Writing style for Markdown and prose artifacts" to `.claude/workflow/conventions.md` — risk: low (default: docs/ change). The new section follows the §1.4 shape (short preamble, citation-first, one tier-mapping table) and names `.claude/output-styles/house-style.md` as the rule source; the table lists Markdown → full house-style, Java/Kotlin → AI-tell subset (§ Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline H3-nested under § Punctuation and typography), other → none. Section stays at or below ~200 words.  [x] commit: 9ca6e8e78e
2. Broaden `CLAUDE.md § Writing Style for Design Docs and Issues` from the 4-surface bullet list to "all Markdown files" plus the non-Markdown surfaces (PR titles/descriptions, commit-message bodies, YouTrack issue bodies) — risk: low (default: docs/ change). Adds a back-reference to the new conventions.md anchor from Step 1. Additive only; no existing scope narrows.  [x] commit: dbfbfb83b5

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. -->

### Step 1 — commit 9ca6e8e78e, 2026-05-19T13:42Z [ctx=safe]

**What was done:**
Added §1.5 "Writing style for Markdown and prose artifacts" to `.claude/workflow/conventions.md` directly after §1.4 *Tooling discipline*. The new section names `.claude/output-styles/house-style.md` as the rule source and carries one tier-mapping table: Markdown → full house-style; Java/Kotlin → AI-tell subset (Banned vocabulary, Banned sentence patterns, Banned analysis patterns, Em-dash discipline H3-nested under Punctuation and typography); other extensions → silent. Body length is 199 words, inside the ~200-word target. Section shape matches §1.4: BLUF lead, citation-first, single tail table.

**What was discovered:**
The stable heading slug downstream tracks must cite is `## 1.5 Writing style for Markdown and prose artifacts`. The repo-relative path from `.claude/workflow/conventions.md` to the rule source is `../output-styles/house-style.md`. Tracks 2-5 should use these exact strings when drafting hook reminder text (Track 2) and one-line pointers (Tracks 3-5).

**What changed from the plan:** none.

**Key files:**
- `.claude/workflow/conventions.md` (modified)

**Critical context:**
The heading slug is now durable; do not rename without sweeping every downstream pointer Tracks 2-5 will add.

### Step 2 — commit dbfbfb83b5, 2026-05-19T13:46Z [ctx=safe]

**What was done:**
Broadened `CLAUDE.md § Writing Style for Design Docs and Issues` from the four-surface bullet list (ADR/design docs, `issue-*.md` scratch files, PR titles/descriptions, YouTrack issue bodies) to "all Markdown files in the repo" plus three explicit non-Markdown surfaces — PR titles/descriptions, commit-message bodies (newly named), and YouTrack issue bodies. Each prior bullet absorbs into the broader scope; nothing narrows. Added a back-reference to the new `.claude/workflow/conventions.md § 1.5 Writing style for Markdown and prose artifacts` anchor and noted the Java/Kotlin AI-tell subset for code-comment scale so CLAUDE.md mirrors the conventions.md tier table. Preserved the existing `.claude/output-styles/house-style.md` pointer verbatim. Acceptance grep `grep 'all Markdown files' CLAUDE.md` returns a hit.

**What was discovered:**
The Step 2 acceptance grep is case-sensitive on the literal "all Markdown files" (lowercase `a`). An initial draft used "All Markdown files" as a bullet header (capital `A`); the wording was adjusted so the literal-case string lands inside the § Writing Style block.

**What changed from the plan:** none.

**Key files:**
- `CLAUDE.md` (modified)

**Critical context:**
Tracks 3-5 can now cite either the conventions.md §1.5 anchor (canonical workflow anchor, primary citation target) or `CLAUDE.md § Writing Style for Design Docs and Issues` (project-level mirror) — both name house-style.md as the rule source. Prefer the conventions.md anchor in workflow files (loaded at every `/execute-tracks` startup) and the CLAUDE.md mirror when speaking to the project-level reader.

## Validation and Acceptance

- `.claude/workflow/conventions.md` contains a § "Writing style for Markdown and prose artifacts" section (exact wording to be settled in Phase A) that names `.claude/output-styles/house-style.md` as the rule source.
- The new section lists the tier mapping (Markdown → full house-style; Java/Kotlin → AI-tell subset; other → none) and the source-section names that constitute the AI-tell subset (Banned vocabulary, Banned sentence patterns, Banned analysis patterns, Em-dash discipline).
- `CLAUDE.md § Writing Style for Design Docs and Issues` is rewritten so its surface list reads "all Markdown files" plus the non-Markdown surfaces (PR descriptions, commit-message bodies, YouTrack issue bodies) and the YouTrack issue creation hint.
- Cross-references between `CLAUDE.md`, `conventions.md`, and `house-style.md` resolve (`grep -n 'house-style.md' CLAUDE.md .claude/workflow/conventions.md` returns at least one hit per file).
- The new conventions.md section stays at or below ~200 words, consistent with the house-style structural rule and matching the size of §1.4 *Tooling discipline*'s peer subsections.

Per-step acceptance:

- **Step 1 (conventions.md anchor):** When the orchestrator next reads `.claude/workflow/conventions.md`, the file contains exactly one new `## ` section titled "Writing style for Markdown and prose artifacts" placed adjacent to §1.4 *Tooling discipline*; the section names `.claude/output-styles/house-style.md` as the rule source, contains the three-row tier-mapping table, and lists the four AI-tell subset section headings (`Banned vocabulary`, `Banned sentence patterns`, `Banned analysis patterns`, `Em-dash discipline`) with the H3-nesting note for the last. `grep -c '^## 1\.5 Writing style for Markdown and prose artifacts$' .claude/workflow/conventions.md` returns `1`.
- **Step 2 (CLAUDE.md broadening):** When a reader opens `CLAUDE.md § Writing Style for Design Docs and Issues`, the surface list reads "all Markdown files" plus the non-Markdown surfaces (PR titles/descriptions, commit-message bodies, YouTrack issue bodies); the existing pointer to `.claude/output-styles/house-style.md` is preserved verbatim; and a one-line cross-reference to the new conventions.md anchor is present. No bullet from the prior 4-surface list disappears — each is absorbed into the broader scope. `grep 'all Markdown files' CLAUDE.md` returns at least one hit inside the § Writing Style block.

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery
- **Step 1 (conventions.md anchor):** idempotent by re-running the edit — `steroid_apply_patch` validates `old_string` uniqueness before applying. Recovery from a partial write: `git checkout HEAD -- .claude/workflow/conventions.md` reverts to the pre-step baseline; re-run Step 1.
- **Step 2 (CLAUDE.md broadening):** idempotent by re-running the edit on the same hunks. Recovery from a partial write: `git checkout HEAD -- CLAUDE.md` reverts to baseline; re-run Step 2. Because Step 2 only adds back-references to Step 1's anchor (and broadens scope), it can be re-attempted any number of times without state divergence.

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

## Base commit
ac8f7cb2e86448ab894d91e9b5ac7e87c3f097f3
