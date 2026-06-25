<!-- MANIFEST
findings: 1   severity: {Minor: 1}
index:
  - {id: WC1, sev: Minor, loc: "docs/adr/harding-readability-audit/_workflow/staged-workflow/.claude/skills/readability-feedback/SKILL.md:33", anchor: "### WC1 ", cert: n/a, basis: "partition value (~200 window / ~6 cap / ##-# Part boundaries) restated literally in both readability-feedback step 2 and edit-design Step 4; both use 'canonical' language and point at each other"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### WC1 [Minor] Partition value has two literal homes that each claim "canonical"

- **File:** `docs/adr/harding-readability-audit/_workflow/staged-workflow/.claude/skills/readability-feedback/SKILL.md` (line 33), cross-checked against `edit-design/SKILL.md` (lines 688-699) and `design-document-rules.md` (lines 355-359)
- **Axis:** cross-file rule restatement
- **Cost:** mild source-of-truth ambiguity for the partition value (window size, cap, boundary set); a future edit to the window size or cap in one file is guarded against drift only by the prose "keep the two in sync" note, not by a single home.
- **Issue:** The partition *value* — `~200-line` window, `~6` cap, `##` / `# Part` boundaries — appears as a full literal statement in two files at once:
  - `readability-feedback/SKILL.md:33` states the value and labels itself "the **canonical** partition **value** (window size, boundary set, cap) that the in-loop `readability-auditor` fan-out in `edit-design/SKILL.md` § Step 4 reuses."
  - `edit-design/SKILL.md:688-699` (§ Step 4, "Deterministic design-path slice partition") restates the same `~200-line window` and `~6-window cap` value and says it "is the same partition `/readability-feedback` Procedure step 2 carries (the proven rule this ports)."

  A third file, `design-document-rules.md:355-359`, then names `edit-design/SKILL.md` § Step 4 as where "the canonical deterministic slice partition (the ~200-line window ..., the ~6-window cap, and the whole-doc floor) lives." So readability-feedback claims to be the canonical home of the *value* while design-document-rules treats edit-design Step 4 as the canonical home of the *partition*, and the two skill files each restate the numbers and point at each other. Referent: both `readability-feedback/SKILL.md:33` and `edit-design/SKILL.md:688-699` resolve as live, mutually-citing canonical statements of the same window/cap value. This is the value-coupling D2 deliberately chose (couple the value only; the standalone tool and the in-loop path each need it locally), and the mutual "keep the two in sync" note is the documented drift guard — so it is not a broken reference and not accidental drift. It is a minor labeling ambiguity over which file is the single source of truth for the shared number.
- **Suggestion:** Pick one file as the value's source-of-truth and have the other defer to it by name for the value (keeping each file's own application of the value). For example, drop the word "canonical" from `readability-feedback/SKILL.md:33` so only `edit-design/SKILL.md` § Step 4 (the home `design-document-rules.md` already names) carries the canonical label, and have readability-feedback read "the partition value `edit-design/SKILL.md` § Step 4 names canonical." If the chosen design is genuinely two co-equal homes with a sync note (a valid lightness call), align the language so neither file calls itself "canonical" and both say "kept in sync with the other." No functional change either way — the values are currently identical across all three files.

## Evidence base
