<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

<!-- No new findings surfaced this pass. -->

## Evidence base

#### Verify T1: Plain-language rule pointed at the wrong design-review block
- **Original issue**: D2-1, `## Context and Orientation`, and Plan-of-Work step 2 routed the new `## Plain language` cold-read check into the `### Human-reader cold-read additions` block of `design-review.md`. The correct home is `### Prose AI-tell additions` (it already checks `§ Orientation`, runs on `target=tracks`, and is where `readability-feedback/SKILL.md:47`'s sync map routes a clarity rule). Left unfixed the step would add the rule to the wrong list, miscount "five Human-reader rules" → six, conflate two rule families, and drop `target=tracks` reach. The plan's Component-Map bullet also mislabeled the target as the "Human-reader list".
- **Fix applied**: D2-1 (track-2.md:23-27) rewritten to target the `### Prose AI-tell additions` block (`:186-217`), with rationale citing three grounds — the `## Orientation` precedent at `:207`, the `target=tracks` reach at `:189-195`, the `readability-feedback/SKILL.md:47` sync map. Plan-of-Work step 2 (track-2.md:63) redirects to that block + the `:23` TOC summary + the `:458` "second exception" clause, and explicitly says NOT to touch the Human-reader block or the "five Human-reader rules" count at `:181`/`:458`. C&O (track-2.md:47) reworded. Validation line (track-2.md:78) restated. Plan Component-Map bullet (implementation-plan.md:54) corrected to "the rule in the cold-read `### Prose AI-tell additions` block".
- **Re-check**:
  - Track-file/plan location: track-2.md:23-27 (D2-1), :47 (C&O), :63 (step 2), :78 (acceptance); implementation-plan.md:54 (Component Map).
  - Current state: every site now names `### Prose AI-tell additions` as the target; the Human-reader block and its count are explicitly excluded. Source facts cross-checked against the live file: `design-review.md:186-217` is the Prose AI-tell block (header at :186); `:207` checks "Too-terse … against `§ Orientation`"; the TOC summary at `:23` names Orientation as a Prose-AI-tell concern; `:189-195` sets the applies-to set to both `target=design` and `target=tracks`; `:458` carries the "A second exception" clause already naming `§ Banned analysis patterns, § Mechanism traces…, or § Orientation`; `readability-feedback/SKILL.md:47` routes a prose-density/terseness rule into the Prose AI-tell block. All citations resolve correctly.
  - Criteria met: correct-block placement, reference accuracy of every cited line, no two-rule-family conflation, `target=tracks` reach preserved, sync-map authority honored.
- **Regression check**: Verified the "five Human-reader rules" count is genuinely untouched — `design-review.md:181` still reads "for these five rules" and `:458` still reads "the five Human-reader rules" (live grep returned the literal at :458 unchanged; :181 wording intact). The track now states three times that this count stays five (track-2.md:26, :47, :78). No new issue introduced in the Human-reader block, the §458 clause, or the plan's Component Map.
- **Verdict**: VERIFIED

#### Verify T2: "11 prompts each carry a preamble" overcounted by one
- **Original issue**: The track counted `design-review.md` among the "11 prompts" that get the five-slug preamble flip and called it "the exception in kind: besides the preamble …", but `design-review.md` carries no preamble — only 10 of the 11 prompts do. The real shape is 10 preamble flips plus 1 structurally-different content edit.
- **Fix applied**: Purpose/Big Picture (track-2.md:9), C&O (track-2.md:47), Plan-of-Work step 1 (track-2.md:62), and Interfaces (track-2.md:97) reworded to "10 of the 11 prompts carry a five-slug preamble; the 11th, `design-review.md`, has no preamble" with the design-review edit framed as a separate content edit. The `## Interfaces and Dependencies` breakdown (track-2.md:104) now reads "14 slug adds (10 prompt preambles + 4 skill blockquotes) and 3 content edits".
- **Re-check**:
  - Track-file location: track-2.md:9, :41, :47, :62, :77, :97, :104.
  - Current state: every counting site uses "10 of the 11 prompts" + design-review as the no-preamble exception. The "~17 files" total is preserved (10 preamble prompts + 1 design-review content edit + 6 skills, breakdown stated as 14 slug adds + 3 content edits at :104). Cross-checked against iter-1 evidence P-PREAMBLE: preamble present in exactly 10 prompts, design-review.md preamble count 0 — the reworded text matches that ground. No stale "11 prompts each" / "11 uniform" / "besides the preamble" phrasing remains (live grep returned none).
  - Criteria met: accurate preamble class size, design-review correctly recategorized as a content edit, arithmetic total unchanged.
- **Regression check**: Confirmed the in-scope set is still 11 prompts + 6 skills (the count distinction is about edit-kind, not file membership; track-2.md:97-98 and :104 keep the full 17-file roster). No file dropped or added. No new issue.
- **Verdict**: VERIFIED

#### Verify T3: grep sync target not pinned to a verbatim string
- **Original issue**: Step 4 said "copy the live `conventions.md` helper verbatim" without pinning the exact target, so the sync and its acceptance check were not string-checkable. The sync is two changes: `grep -rn`/BRE → `grep -rnE`/ERE, and four bare names → eight alternatives.
- **Fix applied**: C&O (track-2.md:51-57) now shows the exact byte-identical `grep -rnE` command in a fenced block (eight alternatives) and spells out the BRE→ERE and four→eight changes plus the anchoring caveat. Plan-of-Work step 5 (track-2.md:66) and the Validation line (track-2.md:81) pin it ("byte-identical to `conventions.md:572`").
- **Re-check**:
  - Track-file location: track-2.md:54 (fenced command), :51-57 (surrounding prose), :66 (step 5), :68 (invariant), :81 (acceptance).
  - Current state: programmatic byte-compare of track-2.md:54 against the live command at `conventions.md:572` returns BYTE-IDENTICAL. Both read `grep -rnE '## Orientation|## Plain language|§ Orientation|§ Plain language|Banned vocabulary|Banned sentence patterns|Banned analysis patterns|Em-dash discipline' .claude/ CLAUDE.md`. The iter-1 divergence claim (P-GREP: `readability-feedback/SKILL.md:54` is still the BRE four-bare-name form) is re-confirmed against the live file — the SKILL.md copy is still the old form, which is exactly the unsynced state this step targets, so the pinned target correctly describes the desired post-edit string.
  - Criteria met: target string is now exact and string-checkable; acceptance line keys off byte-identity; the two-change description is accurate.
- **Regression check**: Verified the surrounding "find every pointer … in the same commit" framing (track-2.md:51, :57) is intact and the anchoring-caveat prose is preserved. The step still correctly identifies `readability-feedback/SKILL.md:54` as the edit site (live SKILL.md:54 confirmed as the stale BRE form). No new issue.
- **Verdict**: VERIFIED

Summary: PASS — all three accepted findings (T1 should-fix, T2 suggestion, T3 suggestion) verified as correctly applied with no regression. The track's source-line citations resolve against the live files, the "five Human-reader rules" count is untouched (still five at design-review.md:181/:458), and the pinned grep is byte-identical to conventions.md:572. No new findings. S4 count grep over track-2.md returns 0, matching findings: 0.
