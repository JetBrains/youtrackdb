<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: CR4, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/plan/track-1.md:38, anchor: "### CR4 ", cert: "verify-CR3", basis: "D1 Rationale still asserts all four consumers hold no copy and removal propagates for free, contradicting the CR3-corrected Context and D1's own follow-up list"}
verdicts:
  - {id: CR3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Consistency gate-verification — Track 1, iteration 3

CR3 is VERIFIED: the Purpose and Context rewrites correctly drop the false "inherits for free" claim for `ai-tells/SKILL.md` and `house-conversation.md`, and the new one-free / three-copy classification matches the live files. The re-scan surfaces one new should-fix finding (CR4): the same false-propagation claim CR3 removed from Purpose and Context still lives verbatim in the D1 Decision Record's Rationale, so the track file now contradicts itself. No blockers — overall PASS.

#### Verify CR3: consumer classification in Purpose and Context (ai-tells / house-conversation no longer "inherit for free")
- **Original issue**: The CR1 fix carved `dsc-ai-tell` out of the "inherit for free" group in `## Purpose` and `## Context`, but left `ai-tells/SKILL.md` and `house-conversation.md` inside it, wrongly implying they inherit a D1 rule deletion for free.
- **Fix applied**: Native edits to `track-1.md`. `## Purpose` (:15) now says the docs are governed by `house-style.md` "plus a handful of surfaces that name or mirror individual rules" and the track "deletes the disguise-only rules at `house-style.md` and at every surface that names or mirrors them." `## Context` (:111) now says of the four named consumers "only one inherits a rule deletion for free" (`design-review.md`), while "the other three name or mirror specific rules and take an explicit same-change edit," enumerating the `dsc-ai-tell` checker, `ai-tells/SKILL.md`, and `house-conversation.md`.
- **Re-check**:
  - Search/trace performed: Grep over the live tree (workflow-machinery change; Read/Grep, no PSI, no grep caveat per the spawn note). Confirmed which surfaces cite by pointer vs. hold a named copy.
  - Code location: same references as the finding.
    - `.claude/workflow/prompts/design-review.md` — cites `house-style.md` only by section heading (`§ Navigability`, `§ Audience-fit`, `§ Edge cases`, `§ References footer shape`, `§ Same-shape sibling consolidation`, `§ Overview concept-first`); grep for every removed-rule name returned no match (exit 1). It restates no removed rule → genuinely inherits a D1 deletion for free.
    - `.claude/skills/ai-tells/SKILL.md:3` — `description:` names "negative parallelism", "Title Case headings", and "closing phrases" (removed) plus "adjective triads" (kept); `:24` catalogue names "closing phrases." Holds a named copy → needs an explicit edit.
    - `.claude/output-styles/house-conversation.md:23` — AI-tell subset lists "negative parallelism", "roundabout negation", and "closing connectives" by name. Holds a named copy → needs an explicit edit.
    - `.claude/scripts/design-mechanical-checks.py` — `NEGATIVE_PARALLELISM_RE` (:103), `NEGATIVE_PARALLELISM_TRAILING_RE` (:127), `HYPHENATED_PAIR_CLUSTER_RE` (:191), and the Title-Case check (`_title_case_violation`, :1734) are hard-coded regexes for removed rules → needs an explicit edit.
  - Current state: `## Purpose` and `## Context` no longer claim `ai-tells/SKILL.md` or `house-conversation.md` inherit a D1 deletion for free (Verify item 1 — pass). The classification is factually correct against the live files: exactly one pointer-citer (`design-review.md`) plus three named/mirrored-copy consumers (Verify item 2 — pass).
- **Regression check**: The rewrite's consumer accounting is internally coherent — one free-inheritor + three named-copy consumers + three further copy-holders (`review-workflow-writing-style.md`, `design-document-rules.md`, `CLAUDE.md`) — and it agrees with the in-scope list (items 2, 6, 7, 8, 9, 11, 12, 13, 19; note item 13 edits `design-review.md` for D4/D2 only, consistent with "free" scoped to D1 deletions), the Plan of Work (:124), and Validation (:150). It does **not** agree with the D1 Decision Record's Rationale (:38), which still carries the pre-fix false claim — surfaced below as CR4 rather than reverting the correct rewrite.
- **Verdict**: VERIFIED

## Findings

### CR4 [should-fix] D1 Rationale still asserts free propagation to all four consumers, contradicting the CR3-corrected Context
- **Location**: `docs/adr/tech-writer-tone/_workflow/plan/track-1.md:38` (`#### D1` → **Rationale**, first sentence); secondary lower-confidence site at `:17` (the `> **Scope:**` blockquote's "its four mirrored consumers").
- **Classification**: mechanical
- **Justification**: The Rationale asserts a factual claim about which surfaces hold copies. It is contradicted three ways inside the same track file and once by the live tree, so it is a stale fact to reconcile, not a design choice — it passes the intent-axis pre-screen as a mechanical inconsistency.
- **Issue**: D1's Rationale (:38) still reads: "because the four citing consumers hold no copy of their own, deleting a rule at the source propagates to all of them." That is the exact false-propagation claim CR3 removed from Purpose and Context. It now contradicts:
  1. the CR3-corrected `## Context` (:111), which says only `design-review.md` inherits a deletion for free and the other three hold copies requiring an explicit edit;
  2. D1's own next sentence, which lists `dsc-ai-tell`, `ai-tells/SKILL.md`, `house-conversation.md`, `review-workflow-writing-style.md`, `design-document-rules.md`, and `CLAUDE.md` as "mirrored consumers touched in the same change" — i.e., copies that must be edited, not free inheritors;
  3. D1's own Risks/Caveats (:39), "The `ai-tells/SKILL.md` description is loaded into every session, so three of the six removals keep costing context until it is edited";
  4. the live files (grep evidence in the CR3 certificate above).
  The `:17` Scope blockquote's "its four mirrored consumers" applies the same all-four-uniform framing (it collectively labels `design-review.md`, the pointer-citer, as "mirrored"), so it drifts from the corrected Context's precise use of "mirror" = holds-a-copy.
- **Root cause and disposition**: The claim originates in the FROZEN `design.md` Overview (:28-33), which is itself already contradicted by `design.md`'s own Class Design table (:251-259) that lists these consumers as copies each removal must "land" in. The `design.md` correction is a Phase-4 reconciliation item (it aligns with the previously-deferred CR2) and is not a Phase-2 regression. But `track-1.md` is a mutable Phase-2 artifact: D1's Rationale first sentence (and, optionally, the "mirrored" wording at :17) should be corrected now so the track file is internally consistent with its own CR3-corrected Context, D1's follow-up list, and D1's Risks. Correcting the track copy introduces no new design↔track divergence beyond the one CR1/CR3 already created for the Context section and CR2 already owns for `design.md`.
- **Severity rationale (why should-fix, not blocker)**: The actionable surfaces an implementer follows — the in-scope file list (items 7, 8, 9, 11 list ai-tells / dsc / house-conversation as edited), the Plan of Work, and Validation — are all correct, and D1's own follow-up list plus Risks contradict the stale sentence within the same record, so implementation is not misdirected. The defect is a self-contradiction in the track-canonical decision carrier (D7), a comprehension/consistency wart rather than an execution hazard.

## Summary

PASS. CR3 VERIFIED — the Purpose and Context rewrites correctly drop the false "inherit for free" claim for `ai-tells/SKILL.md` and `house-conversation.md`, and the one-free / three-copy classification is factually accurate against the live files. One new should-fix finding (CR4): the identical false-propagation claim survives verbatim in D1's Rationale (`:38`), contradicting the corrected Context, D1's own follow-up list and Risks, and the live tree; its root in frozen `design.md` (:28-33) is a Phase-4 reconciliation item, but the mutable track-1.md copy should be corrected in Phase 2. No blockers.
