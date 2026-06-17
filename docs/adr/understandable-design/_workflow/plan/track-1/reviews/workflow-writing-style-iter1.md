<!--
MANIFEST
dimension: workflow-writing-style
target: track-1 (4d3962c97441218d8a78272e92f18b83955bef37..HEAD)
verdict: CHANGES-REQUESTED
findings_total: 7
findings_by_sev: {should-fix: 3, suggestion: 4}
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3, C4, C5, C6, C7]
flags: []
index:
  - id: WS1
    sev: should-fix
    anchor: "#ws1-should-fix-em-dash-overuse-in-the-s3-freeze-order-gate-paragraph"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md:531-553"
    cert: C1
    basis: "em dashes per blank-line-bounded paragraph counted directly"
  - id: WS2
    sev: should-fix
    anchor: "#ws2-should-fix-em-dash-overuse-in-the-de-warm-review-order-paragraph"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/workflow/design-document-rules.md:417-438"
    cert: C2
    basis: "em dashes per blank-line-bounded paragraph counted directly"
  - id: WS3
    sev: should-fix
    anchor: "#ws3-should-fix-triple-clause-em-dash-cadence-in-one-bullet-readability-auditor"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/readability-auditor.md:56"
    cert: C3
    basis: "three em dashes inside one list item; X — Y — Z cadence"
  - id: WS4
    sev: suggestion
    anchor: "#ws4-suggestion-triple-clause-em-dash-cadence-in-one-bullet-design-author"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/design-author.md:44"
    cert: C4
    basis: "two em dashes inside one list item; X — Y — but cadence"
  - id: WS5
    sev: suggestion
    anchor: "#ws5-suggestion-paired-parenthetical-em-dashes-in-one-bullet-skill"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md:169"
    cert: C5
    basis: "two em dashes inside one list item (paired parenthetical)"
  - id: WS6
    sev: suggestion
    anchor: "#ws6-suggestion-paired-parenthetical-em-dashes-in-the-de-warm-summary-paragraph"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/workflow/prompts/design-review.md:52-60"
    cert: C6
    basis: "two em dashes in one blank-line-bounded paragraph (paired parenthetical)"
  - id: WS7
    sev: suggestion
    anchor: "#ws7-suggestion-tier-1-banned-word-intricate-retained-on-a-rewritten-line"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md:984"
    cert: C7
    basis: "Tier-1 banned-vocabulary grep hit on a line the delta rewrote"
-->

## Findings

### WS1 [should-fix] em-dash overuse in the S3 freeze-order gate paragraph

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (lines 531-553)
- Axis: em-dash overuse
- Cost: four em dashes in one blank-line-bounded paragraph on a file held to the full house-style bar (the branch's own machinery)
- Issue: violates `house-style.md § Em-dash discipline` (at most one em dash per paragraph). The paragraph that opens `**Block the comprehension gate while a log-adversarial entry is open...**` carries four em dashes: a paired parenthetical at lines 534-535 (`So for \`phase1-creation\` — after the inner loop reports dual-clean and before spawning the comprehension gate — read the research log's...`), one at line 543 (`that is the S3 invariant — a \`design.md\` draft cannot reach...`), and one at line 552 (`not for the reviewer's own sake — the author and the absorption check are...`).
- Suggestion: convert the paired parenthetical to a comma pair or recast as a separate sentence, and replace the two standalone dashes with colons or sentence breaks. For line 534-535: `So for \`phase1-creation\`, after the inner loop reports dual-clean and before spawning the comprehension gate, read the research log's \`## Adversarial gate record\` section`. For line 543: `...that is the S3 invariant: a \`design.md\` draft cannot reach...`. For line 552: `...not for the reviewer's own sake. The author and the absorption check are the log readers the gate protects.`

### WS2 [should-fix] em-dash overuse in the de-warm review-order paragraph

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/workflow/design-document-rules.md` (lines 417-438)
- Axis: em-dash overuse
- Cost: four em dashes in one blank-line-bounded paragraph of newly-authored prose
- Issue: violates `house-style.md § Em-dash discipline`. The paragraph opening `So the Phase 1.1 \`phase1-creation\` review runs the dual-clean inner loop...` carries two paired-parenthetical dash pairs: lines 423-425 (`The ordering is load-bearing for the same reason it always was — the cold comprehension gate must not assess the readability of a design whose decisions have not yet survived challenge — but the challenge now happens on the log...`) and lines 427-429 (`A load-bearing decision surfaced while authoring the design — appended by the author, or surfaced by the absorption check as a draft record with no log basis (A6) — is appended to the log...`).
- Suggestion: turn each parenthetical into its own sentence. For the first: `The ordering is load-bearing for the same reason it always was: the cold comprehension gate must not assess the readability of a design whose decisions have not yet survived challenge. The difference is that the challenge now happens on the log, ahead of authoring, rather than in a local pass the gate waits on.` For the second: `A load-bearing decision surfaced while authoring the design (appended by the author, or surfaced by the absorption check as a draft record with no log basis, A6) is appended to the log and re-challenged at the gate before the comprehension gate runs.`

### WS3 [should-fix] triple-clause em-dash cadence in one bullet (readability-auditor)

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/readability-auditor.md` (line 56)
- Axis: em-dash overuse
- Cost: three em dashes in a single list item — the `X — Y — Z` cadence the house style names as always a finding — in a fully-in-scope new agent definition
- Issue: violates `house-style.md § Em-dash discipline` ("Never use the `X — Y — Z` triple-clause cadence"). Line 56: `- **The prose half of audience-fit** per \`§ Audience-fit\` — whether the prose itself reads for the named audience. (The *structural* half of audience-fit — "does the Overview name a reader at all" — is the whole-doc comprehension reviewer's, not yours.)`
- Suggestion: replace the lead dash with a period and the inner pair with commas: `- **The prose half of audience-fit** per \`§ Audience-fit\`: whether the prose itself reads for the named audience. (The *structural* half of audience-fit, "does the Overview name a reader at all", is the whole-doc comprehension reviewer's, not yours.)`

### WS4 [suggestion] triple-clause em-dash cadence in one bullet (design-author)

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/design-author.md` (line 44)
- Axis: em-dash overuse
- Cost: two em dashes bracketing a mid-clause insertion in a single list item (`X — Y — but` cadence) in a fully-in-scope new agent definition
- Issue: violates `house-style.md § Em-dash discipline` (one em dash per paragraph; the bullet is the unit). Line 44: `A name like "Dekker gate" stays — it teaches the unaware reader — but it carries a one-clause plain-language gloss the first time it appears...`
- Suggestion: drop the parenthetical dashes and fold the reason into a clause: `A name like "Dekker gate" stays, because it teaches the unaware reader, but it carries a one-clause plain-language gloss the first time it appears, so a reader who does not know the name is not blocked.`

### WS5 [suggestion] paired-parenthetical em-dashes in one bullet (SKILL)

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 169)
- Axis: em-dash overuse
- Cost: two em dashes inside a single list item (paired parenthetical) in newly-authored prose
- Issue: trips `house-style.md § Em-dash discipline` (one per paragraph/unit). Line 167-170: `...Step 4 spawns the per-round readability-auditor plus its second per-round check (the warm absorption check at \`phase1-creation\`, the fidelity check at \`phase4-creation\`), then — after the inner loop converges — the cold comprehension gate; Step 6 is the bounded dual-clean inner loop.`
- Suggestion: drop the dashes: `...then, after the inner loop converges, the cold comprehension gate; Step 6 is the bounded dual-clean inner loop.`

### WS6 [suggestion] paired-parenthetical em-dashes in the de-warm summary paragraph

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/workflow/prompts/design-review.md` (lines 52-60)
- Axis: em-dash overuse
- Cost: two em dashes in one blank-line-bounded paragraph (paired parenthetical) in newly-authored prose
- Issue: trips `house-style.md § Em-dash discipline`. Lines 53-54: `The **absorption-completeness** cross-check that used to warm it — confirming every load-bearing log decision appears as a seed/carrier record — moved to the warm \`absorption-check\` agent...`
- Suggestion: comma-bound the parenthetical: `The **absorption-completeness** cross-check that used to warm it, confirming every load-bearing log decision appears as a seed/carrier record, moved to the warm \`absorption-check\` agent...`

### WS7 [suggestion] Tier-1 banned word "intricate" retained on a rewritten line

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 984)
- Axis: banned vocabulary
- Cost: a Tier-1 banned word survives on a line the delta actively rewrote, on a file held to the full house-style bar
- Issue: violates `house-style.md § Banned vocabulary` Tier 1 (`intricate` is a hard ban). The live line read `Two intricate cases worth showing concretely.`; the branch rewrote it to `Two intricate interactive-kind cases worth showing concretely.` (delta lines 754-756 → 984-990), touching the line but keeping the banned word. Because the author edited this exact line, the carried-over word is now in scope.
- Suggestion: replace with a plain adjective: `Two interactive-kind cases worth showing concretely.` (the qualifier `intricate` adds nothing the surrounding text does not already convey).

## Evidence base

#### C1
Confirmed. `sed -n '531,553p' | grep -o '—' | wc -l` returns 4; the span 531-553 is one blank-line-bounded paragraph (no intervening blank line). Section-length three-step not reached — em-dash cap is an independent always-on rule. New delta prose (the live file's S3-gate text was a different, single-block paragraph).

#### C2
Confirmed. `awk` paragraph scan over `design-document-rules.md` reports the 417-438 paragraph at 5 em dashes including one in a parenthetical that spans; direct `grep -o '—'` on 417-438 isolates 4 in running prose (lines 423, 425, 427, 429). The delta confirms the whole paragraph is new (live opened `So the Phase 1.1 phase1-creation review is **cold-read only**`).

#### C3
Confirmed. `sed -n '56p' | grep -o '—' | wc -l` returns 3 on a single list item; matches the `X — Y — Z` cadence the rule names as always-a-finding. Fully-in-scope new agent definition.

#### C4
Confirmed. `sed -n '44p' | grep -o '—' | wc -l` returns 2 on a single list item, both bracketing one mid-clause insertion. Over the one-per-unit cap; lower severity than WS3 because it is a single insertion pair, not a three-clause cadence.

#### C5
Confirmed. Line 169 carries the only two em dashes in the bullet spanning 165-173 (`then — after the inner loop converges —`); a paired parenthetical, over the one-per-unit cap. New delta prose.

#### C6
Confirmed. `grep -o '—'` on the 52-60 paragraph isolates 2, both on lines 53-54 as a single paired parenthetical. New delta prose. Lowest-friction of the em-dash set (one parenthetical, easily comma-bound).

#### C7
Confirmed. `grep -niE '\bintricate\b'` hits SKILL.md:984; `git show develop:.claude/skills/edit-design/SKILL.md | grep intricate` confirms the live line also held it, and the delta (live 754-756 → staged) shows the line was rewritten to add `interactive-kind` while keeping `intricate`. Reported as suggestion rather than higher because the word is carried-over, not freshly introduced; but the line was touched, so it is in scope.
