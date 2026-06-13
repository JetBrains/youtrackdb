<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Technical gate-verification (iter 2) — Track 1

Re-check of the two ACCEPTED Phase-A technical findings. Both fixes landed in the track file and are sound. No new findings.

## Verdicts

#### Verify T1: hook `tier_b_body` 500-char cap
- **Original issue**: adding the sixth slug `§ Plain language` plus the D3 plain-language carve note to `tier_b_body` overshoots the 500-char per-body cap that `test_house_style_hook.py::test_18_reminder_body_length_budget` enforces (`PER_BODY_CHAR_CAP = 500`; live body 441 chars; a naive add ~535).
- **Fix applied**: the track records the constraint and approach in three places — `## Surprises & Discoveries` SD1, `## Plan of Work` step 5, and `## Validation and Acceptance`. Approach: keep the cap at 500 (no threshold change — D2 syncs existing checks, does not re-tune them), fold the Orientation and Plain-language carves into one clause, and drop the "(H3 nested under § Punctuation and typography)" parenthetical from the Em-dash slug.
- **Re-check**:
  - Track-file locations: SD1 (`:20`), Plan of Work step 5 (`:100`), Validation bullet (`:113`). All three present and consistent.
  - Live-source confirmation: `.claude/hooks/house-style-write-reminder.sh:262` `tier_b_body` measures exactly 441 chars (matches SD1's stated baseline). `.claude/scripts/tests/test_house_style_hook.py:798-799` confirms `PER_BODY_CHAR_CAP = 500` and `CONCAT_CHAR_CAP = 1500`.
  - Independent measurement: constructed a faithful six-slug candidate — `§ Plain language` added as the sixth slug, "five"→"six" flipped, the "(H3 nested …)" parenthetical dropped, the two carves folded into one closing clause that names both `§ Orientation` (out-of-file assumptions, not in-file terseness) and `§ Plain language` (common-word and acronym-expansion moves, not short sentences). Measured 499 chars, which is ≤ 500. The track cites the orchestrator's 479-char candidate; either way a faithful ≤500 body exists. The CONCAT cap (1500) is not at risk: tier_a body is unchanged and the combined total stays well under 1500.
  - Criteria met: track records constraint + approach + a Validation criterion that `test_18` passes; an independent faithful ≤500 body is demonstrably writable.
- **Regression check**: checked the cap-unchanged decision against D2 (judgment-only — syncs existing checks, does not re-tune). Dropping the Em-dash parenthetical loses only the "(H3 nested under § Punctuation and typography)" nesting hint, not the slug itself, so the enumeration stays faithful and `test_16`'s `TIER_B_HEADINGS` guard is unaffected (it keys on `## ` slugs in `house-style.md`, not on the hook body). Clean.
- **Verdict**: VERIFIED

#### Verify T2: `design-mechanical-checks.py` exclusion undocumented
- **Original issue**: the reconciliation grep surfaces `.claude/scripts/design-mechanical-checks.py` (its `dsc-ai-tell` rule names `## Banned analysis patterns`); the track had documented only `design-document-rules.md` (CR2) as a considered exclusion, leaving this file looking like an oversight.
- **Fix applied**: `## Surprises & Discoveries` SD4 plus a "Considered exclusions" bullet list under `## Interfaces and Dependencies`.
- **Re-check**:
  - Track-file locations: SD4 (`:26`) and the "Considered exclusions" list (`:151-153`). Both present; both tie the exclusion to D2 (judgment-only) and to the same reasoning that excludes `design-document-rules.md`.
  - Live-source confirmation: the `dsc-ai-tell` rule in `design-mechanical-checks.py` is a set of curated regex patterns (`INFLATED_ABSTRACTION_LABEL_RE` and siblings, lines ~182-215, ~1840-1870), keyed on a closed pattern set for `§ Banned analysis patterns`. It does not key on a list of section slugs that a new section would extend. Under D2 (judgment-only) `## Plain language` contributes no regex, so the file has nothing to flip — the reasoning in SD4 is sound and matches the source.
  - Criteria met: `design-mechanical-checks.py` is now recorded as a deliberate exclusion under D2, parallel to `design-document-rules.md`.
- **Regression check**: confirmed the file is listed only as a considered exclusion, not added to the in-scope edit set, so no spurious edit is introduced. The in-scope-set reconciliation note (`:158`) still claims "16 in-scope files plus the four considered exclusions" — `design-mechanical-checks.py` is one of those four, consistent with the list. Clean.
- **Verdict**: VERIFIED

## Findings

(none)

## Summary

PASS — both ACCEPTED technical findings VERIFIED; no new findings.
