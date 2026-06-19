<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: CR1, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Verification certificates

#### Verify CR1: SKILL.md Plan-of-Work move 6 misdescribed the body edit
- **Original issue**: Move 6 said "rewrite the body and the line-3 `description` to drop the removed-tell names" for `skills/ai-tells/SKILL.md`. The removed-tell names appear only at `SKILL.md:3` (the description). The skill body carries no tell names; it carries catalogue-lookup pointers to house-style sections — two `§ Banned vocabulary` pointers that go phantom after removal, and a tone-fingerprints parenthetical naming a removed bullet.
- **Fix applied**: Move 6 in `track-1.md` was split. The `description` clause keeps "drop the removed-tell names from the line-3 `description`". A new body clause re-points the removed-section pointers (the two `§ Banned vocabulary` pointers at `SKILL.md:23` and the line-29 clause; the line-25 sycophantic-openers parenthetical) and notes the `§ Punctuation and typography` pointer at `SKILL.md:26` stays.
- **Re-check**:
  - Search/trace performed: `Read` of `track-1.md` lines 391-401 (reworded move 6) and full `Read` of live `.claude/skills/ai-tells/SKILL.md`; `Grep` over `track-1.md` for `SKILL.md` / `DR9` references. Non-Java workflow files — no PSI, no reference-accuracy caveat.
  - Code location: `track-1.md:391-401`; `.claude/skills/ai-tells/SKILL.md:3,23,25,26,29`.
  - Current state: Move 6 now distinguishes the `description`-only name-drop from the body re-pointing. Every pinned line number matches the live file:
    - `SKILL.md:3` = `description:` frontmatter (names removed + kept tells) — matches.
    - `SKILL.md:23` = `Vocabulary fingerprints → house-style.md § Banned vocabulary (Tier 1 / 2 / 3 / 4)` — matches the "`§ Banned vocabulary` pointer".
    - `SKILL.md:25` = `Tone fingerprints → … (sycophantic openers, throat-clearing, closing phrases)` — matches the named parenthetical.
    - `SKILL.md:26` = `Punctuation fingerprints → house-style.md § Punctuation and typography` — matches; the move correctly notes this pointer survives (only the `### Em-dash discipline` subsection leaves, and line 26 references the parent section, not that subsection).
    - `SKILL.md:29` = `… so it stays distinct from § Banned vocabulary` — matches the "stays distinct from `§ Banned vocabulary`" clause.
- **Regression check**: Checked Decision Log DR9 (`track-1.md:254-270`) and the `## Invariants & Constraints` line on the SKILL.md:3 description (`track-1.md:524-526`). DR9 scopes the in-scope edit to the always-loaded line-3 description (drop removed names, keep kept ones); the reworded move 6 stays consistent — it does not move the name-drop off line 3 or contradict the "keep kept ones" clause. The body re-pointing the move now describes is a separate, non-conflicting edit not governed by DR9 (DR9 is description-only). No newly-pinned line number is wrong; no broken cross-reference introduced. Clean.
- **Verdict**: VERIFIED

## Findings
