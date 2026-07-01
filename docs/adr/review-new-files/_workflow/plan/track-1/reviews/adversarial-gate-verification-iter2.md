<!-- workflow-sha: 38bd7a0b1539ec1b3529e077fa0fba57df312574 -->
# Adversarial gate verification — Track 1 (iter2)

- **Track:** Track 1: Cover genuinely-new staged files in Phase B/C review scope
- **Scope:** Phase 3A gate re-check of the five iter1 adversarial findings (A1–A5) after fixes were applied to the track file. Verdict-producer pass: per-prior-finding verdicts plus one overall PASS/FAIL, no fresh severity-graded finding set.
- **Tooling:** mcp-steroid reachable; `review-new-files` project open and matches the working tree. This track names no Java symbols — every reference re-check is a workflow file path / `§`-anchor / prose-content grep + Read, which grep resolves exactly, so the verification certificates carry no grep reference-accuracy caveat.

## Verification certificates

#### Verify A1: context-block outer file-level non-empty gate must be removed, not merely softened
- **Original issue**: The context block's OUTER gate ("when that file is non-empty, scope your findings to the delta") would route a NEW-only delta file (non-empty yet zero delta lines) into "scope to nothing", re-expressing the unreviewed-file bug. Plan of Work (d) and Inv 2 under-emphasized removing the outer gate.
- **Fix applied**: Plan of Work (d), Inv 2, D1 "Why", `## Context and Orientation` item 1, and the Validation context-block bullet were amended to call out removal of the outer file-level non-empty gate as the load-bearing part.
- **Re-check**:
  - Track-file location: D1 "Why" (line 33), Plan of Work (d) (line 97), Inv 2 (line 151), Context & Orientation item 1 (line 63), Validation (line 115).
  - Current state:
    - D1 "Why" now describes "an outer 'when that file is non-empty, scope your findings to the delta' gate, followed by an inner 'the rest is out of scope' justification" and states "the rewrite must dismantle the file-level non-empty gate, not just soften the 'out of scope' sentence."
    - Plan of Work (d) reads "Replace the block's file-level routing — the outer 'when that file is non-empty, scope your findings to the delta' gate together with its inner … justification — with a per-entry, mutually-exclusive distinction keyed on the marker … Removing the outer non-empty gate is the load-bearing part."
    - Inv 2 is renamed "separates the two cases and drops the file-level non-empty gate" and its check is "confirm routing is per-marker, not per-file-non-empty."
    - Context & Orientation item 1 describes the outer/inner two-part gate structure.
    - Validation states the outer routing gate "is gone — not merely softened."
    All five locations now unambiguously require removing the outer gate, not softening the inner sentence.
  - Criteria met: invariant-violation (Inv 2 strengthened to cover the non-empty gate); scope (per-marker routing named as load-bearing).
  - Wording match: the quoted outer-gate text "When that file is non-empty, scope your findings to the delta" and the inner "the rest … is out of scope" are byte-accurate against `track-code-review.md:461-463` and `step-implementation.md:617-619` (both verified against the live files).
- **Regression check**: Checked that the "per-marker / per-entry" framing is consistent across D1, Plan (d), Inv 2, Context, and Validation, and does not contradict the empty-file clause (which all locations preserve as "review the diff as usual"). Clean.
- **Verdict**: VERIFIED

#### Verify A2: reviewer dispatch on a NEW staged file already works; two-file footprint is complete
- **Original issue**: The fix relies on Step-5b staged-path normalization in a third file the track treats as out of scope; the track needed a recorded rationale that dispatch already works so the two-file footprint is provably complete.
- **Fix applied**: D1 "Dispatch already works (A2)" bullet added; `## Interfaces and Dependencies` out-of-scope names the Step-5b normalization as a verified pure prefix-strip with no live-existence check.
- **Re-check**:
  - Track-file location: D1 bullet (line 36), Interfaces Out-of-scope (line 137).
  - Live-file verification: `review-agent-selection.md:355-361` — "a path matching the anchored prefix `docs/adr/<any-dir>/_workflow/staged-workflow/(\.claude/…)` is replaced by its captured `.claude/…` remainder … A path that does not match this exact anchored prefix passes through unchanged." `code-review/SKILL.md:246` carries the same rule. This is a pure regex prefix-strip; there is no `[ -f "$live" ]`-style live-existence check anywhere in either block. A NEW staged file therefore normalizes and matches the workflow-review globs regardless of whether its live counterpart exists — reviewers launch.
  - Current state: the track's claim ("pure prefix-strip with no live-existence check, so a NEW staged file matches the workflow-review globs and the reviewers launch — no edit needed for a NEW file to reach a reviewer") is accurate against the live files. The footprint-completeness rationale is recorded in both D1 and the Interfaces Out-of-scope section.
  - Criteria met: assumption test (dispatch HOLDS, now recorded); scope (two-file footprint justified).
- **Regression check**: Checked that naming the third file as out-of-scope does not conflict with the in-scope list (it does not — Interfaces cleanly separates the two normalization files as out-of-scope from the two setup files as in-scope). Clean.
- **Verdict**: VERIFIED

#### Verify A3: burden-measure prose brought in scope and qualified for the NEW case
- **Original issue**: The step-9 burden-measure prose ("the `diff <live> <staged>` delta … is the truer measure") is false for a NEW file, and adjacent prose D1's own standard would flag it; a parallel line exists in `step-implementation.md`.
- **Fix applied**: Brought in scope across six locations — D1 (ten-edit count), `## Context and Orientation` fifth-passage paragraph, Plan of Work (e), Interfaces in-scope, new Inv 6, Validation burden-measure bullet.
- **Re-check**:
  - Track-file location: D1 (line 32), Context & Orientation (line 68), Plan of Work (e) (line 99), Interfaces in-scope (line 135), Inv 6 (line 155), Validation (line 117).
  - Consistency across the six locations: all six now distinguish a copy-of-live file (line count inflated; the step-8 `diff <live> <staged>` delta is the truer measure) from a NEW file (whole-file content is the real review surface; no such delta exists). Consistent framing throughout.
  - Cited line ranges: `track-code-review.md` ~340-342 is accurate — the live burden-measure prose spans lines 340-342 ("a whole-file staged copy inflates the line count without adding proportional review surface (the `diff <live> <staged>` delta from step 8 is the truer measure of what a reviewer reads)"). `step-implementation.md` ~862-863 is accurate — the live parallel line spans 862-863 ("a freshly-staged whole-file copy inflates the line count without adding review surface"). Note the two live lines are NOT byte-identical (the step-implementation.md line lacks the parenthetical "truer measure" clause); the track correctly handles this by naming Plan (e) as applying "the parallel qualifier" rather than an identical edit, and Inv 6 checks each file's line independently rather than asserting cross-file identity.
  - "Ten edits" internal consistency: D1 line 32 says "eight scoping edits total" and "Ten edits across the two files in total (eight scoping + two burden-measure)"; Context & Orientation line 70 says "ten edits — four scoping locations plus one burden-measure qualifier per file"; D3 Consequence line 49 says "the ten edit points". 4 scoping × 2 files = 8, plus 1 burden × 2 files = 2, total 10 — arithmetically consistent everywhere it appears. No stale "eight edit points" survives as a total.
  - Criteria met: invariant-violation (Inv 6 added, closes the Inv 3/Inv 5 neighbor gap); scope (burden-measure dispositioned in scope, not left as an out-of-scope neighbor).
- **Regression check**: Checked whether bringing the burden-measure line in scope collides with Inv 3 (cross-file consistency scoped only to the else branch and context block) — it does not, because Inv 3 explicitly limits its check to those two regions and Inv 6 covers the burden-measure line separately per file. Clean.
- **Verdict**: VERIFIED

#### Verify A4: single-commit decomposition survival rationale recorded
- **Original issue**: A suggestion that the single-step decomposition survives the split test; recording the rationale strengthens why an eight-edit-point change is one commit.
- **Fix applied**: D1 "Single commit (A4 survival test)" bullet added.
- **Re-check**:
  - Track-file location: D1 bullet (line 37).
  - Current state: the bullet records that the scoping edits ship as one commit because any split creates a committed intermediate where the prose contradicts the code, and points at `## Concrete Steps` for the single-step decomposition. Rationale is recorded.
  - Criteria met: scope/sizing (single-step justification recorded).
- **Regression check**: The bullet frames the survival test around "the eight scoping edits" while the total is ten (scoping + burden-measure). This is not an inconsistency: the burden-measure edits ship in the same single commit, and the survival argument (partial states are internally contradictory) is driven by the scoping chain, which is the load-bearing set. The framing is precise, not stale. Clean.
- **Verdict**: VERIFIED

#### Verify A5 (REJECTED): Inv 1 stage-then-delete edge — theoretical, conservative over-review
- **Rejection reason**: The stage-a-copy-then-delete-original edge is off-pattern per the `§1.7` staging discipline (staged copy mirrors the live path; the live file is not concurrently deleted), and its failure direction is conservative over-review, not under-review. No change was requested (NO CHANGE).
- **Downstream check**: Leaving Inv 1 unchanged introduces no downstream issue — the else branch keys on working-tree existence of the derived live path, and the rare stage+delete case reviews in full (safe). The track file's Inv 1 (line 150) is unchanged and still correct; no other section depends on this edge.
- **Verdict**: REJECTED (no action needed)

## Regression check (whole-track coherence)

The track's own thesis (D1) is that editing a subset of related prose leaves a contradiction; the iter1 amendments were extensive, so I read the whole track file end-to-end for count and consistency coherence.

- **Count coherence**: Every edit-count reference resolves to the same arithmetic — "eight scoping" (4 locations × 2 files), "two burden-measure" (1 × 2 files), "ten edits" total. Checked D1 (line 32), Context & Orientation (line 70), D3 Consequence (line 49), Interfaces in-scope (line 135), A4 bullet (line 37). No stale "eight edit points" as a total and no "four locations" used where "four locations per file" is meant.
- **Outer-gate framing coherence**: The "remove the outer file-level non-empty gate / per-marker routing" language is consistent across D1, Plan (d), Inv 2, Context item 1, and Validation. No section still frames the fix as merely softening the inner "out of scope" sentence.
- **Cross-file-consistency framing coherence**: Inv 3 scopes cross-file identity to the else branch and context block only; Inv 6 checks the burden-measure line per file (not cross-file identical). This correctly matches the live files, where the two burden-measure lines differ in wording. No self-contradiction between Inv 3 and Inv 6.
- **Dangling references**: All cited anchors resolve — `§1.7(k)`, `## Concrete Steps`, `## Context and Orientation`, Plan of Work (b)/(d)/(e), D1/D2/D3, Inv 1–6. No dangling reference introduced.
- **No new contradiction**: The amendments did not introduce their own internal contradiction. The empty-file clause survives intact in every location that discusses the context block; the per-marker rewrite and the empty-file "review the diff as usual" case are mutually consistent.

No new finding surfaced (numbering would continue at A6 if one had).

## Manifest

```yaml
review_type: adversarial
phase: 3A
kind: verdict-producer
overall: PASS
findings: 0
verdicts:
  - id: A1
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
    loc: "track-code-review.md:461; step-implementation.md:617"
  - id: A2
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
    loc: "review-agent-selection.md:355-361; code-review/SKILL.md:246"
  - id: A3
    prior_sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
    loc: "track-code-review.md:340-342; step-implementation.md:862-863"
  - id: A4
    prior_sev: suggestion
    disposition: ACCEPTED
    verdict: VERIFIED
    loc: "track-1.md D1"
  - id: A5
    prior_sev: suggestion
    disposition: REJECTED
    verdict: REJECTED
    loc: "track-1.md Inv 1"
regression_check: clean
```

## Findings
<!-- No new findings surfaced by this verification pass. -->
