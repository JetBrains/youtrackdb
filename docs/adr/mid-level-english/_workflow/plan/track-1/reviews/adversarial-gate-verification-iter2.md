<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: A4, sev: suggestion, loc: "track-1.md:158 (## Interfaces and Dependencies — In-scope set reconciliation)", anchor: "### A4 ", cert: "re-ran the track's stated reconciliation grep; CLAUDE.md does not match it", basis: "grep+Read"}
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate-verification — Track 1, iteration 2

Workflow-prose track (§1.7(k) opt-out in the plan's `### Constraints`). References verified as workflow file paths and `§`-anchors via grep+Read; mcp-steroid not reachable, grep+Read is the correct tool for prose refs (no caveat needed).

## Verification certificates

#### Verify A1: hook `tier_b_body` 500-char per-body cap
- **Original issue**: adding the sixth slug + the D3 carve note as a second sentence to the live 441-char `tier_b_body` overshoots `PER_BODY_CHAR_CAP=500` (`test_house_style_hook.py::test_18`), so execution-as-planned would fail the runner.
- **Fix applied**: SD1 records the cap and the resolution (keep cap at 500, fold the Orientation + Plain-language carves into one clause, drop the "(H3 nested under § Punctuation and typography)" parenthetical from the Em-dash slug); `## Plan of Work` step 5 carries the same approach; `## Validation and Acceptance` adds a criterion that `test_18` still passes (`tier_b_body` ≤ 500, concat ≤ 1500) after the six-slug + carve edit.
- **Re-check**:
  - Track-file location: SD1 (`:20`), step 5 (`:100`), Validation criterion (`:113`).
  - Codebase: `test_house_style_hook.py:798` `PER_BODY_CHAR_CAP = 500`, `:799` `CONCAT_CHAR_CAP = 1500`; live `tier_b_body` independently measured at **441 chars**, `tier_a_body` at 366 (matches SD1's 441 claim).
  - Independent construction: I built a faithful six-slug body that adds `§ Plain language`, flips "five"→"six", folds both carves into one clause, and drops the H3 parenthetical. It measures **487 chars** (≤ 500); concatenated with `tier_a_body` (366) = 853 (≤ 1500). The track cites a 479-char candidate; mine at 487 carries a slightly fuller carve clause — both are under cap, so a faithful ≤500 six-slug body provably exists.
  - Criteria met: the track now accounts for the cap with a concrete approach and a Validation criterion; the blocker is resolved at the plan level — execution-as-planned no longer fails `test_18`.
- **Regression check**: checked that the carve fold does not drop required content — the candidate still names all six slugs, the §1.5 anchor, the Orientation out-of-file-assumption carve, and the Plain-language comment-scale carve (common word / acronym expansion / no-idiom). Concat cap holds. Clean.
- **Verdict**: VERIFIED

#### Verify A2: `implementer-rules.md:1102` count omitted from the count-site list
- **Original issue**: the sentence at `implementer-rules.md:1100`–`:1105` ("the **five** section slugs that make up the Tier-B AI-tell subset are …") is both an enumeration and a numeric count, but step 4 named only `commit-conventions.md:191` and `step-implementation.md:1038` as count sites, and the deferred grep (`five AI-tell|five Tier-B|five sections`) does not match "five section slugs".
- **Fix applied**: SD2 records the count site; `## Plan of Work` step 4 now lists `implementer-rules.md:1102` ("five section slugs" — SD2; this one the narrow grep misses, so flip it explicitly) alongside the other count sites; the `## Interfaces and Dependencies` `implementer-rules.md` entry notes the `:1102` count; a Validation criterion names it.
- **Re-check**:
  - Codebase: Read `implementer-rules.md:1100`–`:1105` — confirms "the five section slugs that make up the Tier-B AI-tell subset are `## Banned vocabulary`, …, and `## Orientation`." Both an enumeration (five slugs at `:1103`–`:1105`) and a count ("five" at `:1102`).
  - Step-4 count-site list re-verified against the files: `commit-conventions.md:191` = "The five AI-tell subset" ✓; `step-implementation.md:1038` = "The five AI-tell subset section slugs to apply are" ✓; `conventions.md:570` = "The five Tier-B section names" ✓; `house-style.md:20` = "reuses the five AI-tell sections" ✓; `house-conversation.md:21` = "Apply these five sections" ✓; `implementer-rules.md:1102` = "the five section slugs" ✓. All six count sites are accurate.
  - Criteria met: `:1102` is now an explicit count site in step 4, SD2, the Interfaces entry, and Validation. The count-site list is complete and accurate.
- **Regression check**: confirmed no spurious count site was added — each listed site genuinely carries a numeric "five" that names the subset. Clean.
- **Verdict**: VERIFIED

#### Verify A3: deferred grep misses variant wording / could miss sites
- **Original issue**: the grep pattern misses "five section slugs" and any future variant, raising the risk of a missed count or a missed in-scope file.
- **Fix applied**: SD2 / SD3 plus the `## Interfaces and Dependencies` "In-scope set reconciliation (DONE at Phase A)" paragraph state that reconciliation ran at Phase A: the grep catches every in-scope FILE but misses the `:1102` COUNT (SD2); two coincidental "five" sites are excluded by hand (SD3); the in-scope set is the explicit 16-file list reconciled against the grep, not the raw grep output.
- **Re-check**:
  - Reconciliation paragraph present at `track-1.md:158`. ✓
  - Two excluded "five" sites Read and confirmed NOT subset counts: `workflow-startup-precheck.sh:1348` = "one of the five slug strings" → the State-C sub-state slugs (a comment computing `C_SUBSTATE`), unrelated to the AI-tell subset; `conventions.md:86` = "Five sections: `## Initial request`, `## Decision Log`, …" → the research log's five sections, not the subset. Both correctly excluded.
  - Independent re-run of the stated grep, filtered to non-`prompts/`/`skills/`/`agents/`: returns 17 files = **15 in-scope core-doc files + 2 considered exclusions** (`design-mechanical-checks.py`, `design-document-rules.md`). Set-differenced against the track's 16-item in-scope list: every grep match is in the list; the only in-scope file not in the grep is `CLAUDE.md` (correctly so — it is brought in by D6, not the grep). No in-scope core-doc file outside `prompts/`/`skills/`/`agents/` is missed.
  - Criteria met: the reconciliation paragraph is present; the excluded sites genuinely are not subset counts; no edit-needing file is missed.
- **Regression check**: the fix is sound. One accuracy defect in the reconciliation paragraph's count arithmetic surfaced and is filed as new finding A4 (suggestion) — it does not reopen A3, whose three concrete sub-claims all verify.
- **Verdict**: VERIFIED

## Findings

### A4 — reconciliation paragraph overstates that the grep returns CLAUDE.md
- **Severity**: suggestion
- **Location**: `track-1.md:158` (`## Interfaces and Dependencies` → "In-scope set reconciliation (DONE at Phase A)")
- **Issue**: the paragraph states "The grep returns exactly these **16** in-scope files plus the four considered exclusions above — no in-scope file is missed." Re-running the stated grep (`grep -rln 'Banned analysis patterns\|five AI-tell\|five Tier-B\|five sections' .claude/ CLAUDE.md`, filtered to non-`prompts`/`skills`/`agents`) returns **15** of the 16 in-scope files. `CLAUDE.md` does **not** match the pattern: its subset parenthetical at `:104` is lowercase prose ("banned vocabulary, banned sentence patterns, banned analysis patterns, em-dash discipline"), not the `## Banned analysis patterns` slug and not a "five …" count. `CLAUDE.md` enters scope via D6 (de-enumeration), not via the grep. Also, of the four "considered exclusions," only two (`design-mechanical-checks.py`, `design-document-rules.md`) match this narrower grep; the other two (`workflow-startup-precheck.sh:1348`, `conventions.md:86`) match only the *wider* SD3 grep — so "the four considered exclusions" are not all in this grep's output either.
- **Why suggestion, not should-fix**: the in-scope SET is correct and complete — the 16-file list = {15 grep matches} ∪ {CLAUDE.md}, with no edit-needing file missed and no spurious file added; execution-as-planned edits every file it must. The defect is purely in the paragraph's *description of how the grep populates the set* (it credits the grep with returning CLAUDE.md when D6 does). It does not change any deliverable, fail any test, or drop any count site. The track elsewhere is explicit that CLAUDE.md is a D6 edit "outside the stamp pathspec," so the correct provenance is already on record.
- **Suggested fix**: reword the reconciliation paragraph to "the grep returns 15 of the 16 in-scope files; CLAUDE.md (16th) enters scope via D6, not the grep, and its `:104` parenthetical does not match the pattern," and scope "the four considered exclusions" to the two that this grep actually surfaces (the other two surface only under the wider SD3 grep).
- **Basis**: grep+Read (re-ran the stated grep and set-differenced against the in-scope list; Read CLAUDE.md:100–104).

## Summary
PASS. A1, A2, A3 all VERIFIED — fixes landed in the track file and are sound; the ≤500 six-slug body provably exists (independently measured 487 chars), the `:1102` count site is now explicit and the count-site list is accurate, and the reconciliation's two excluded "five" sites are genuinely not subset counts with no in-scope file missed. One new suggestion (A4): the reconciliation paragraph's count arithmetic credits the grep with returning CLAUDE.md when D6 brings it in — cosmetic, the set itself is correct.
