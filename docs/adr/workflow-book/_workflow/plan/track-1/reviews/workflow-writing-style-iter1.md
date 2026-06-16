<!-- MANIFEST
findings: 2
severity: { blocker: 0, should-fix: 1, suggestion: 1 }
index:
  - id: WS1
    sev: should-fix
    loc: docs/workflow-book/README.md:31
    anchor: "#ws1-should-fix-two-em-dashes-in-one-prose-paragraph"
    cert: C1
    basis: judgment
  - id: WS2
    sev: suggestion
    loc: workflow-book-builder/DIAGRAMS.md:7
    anchor: "#ws2-suggestion-tier-2-robustness-in-a-disclaiming-clause"
    cert: C2
    basis: judgment
evidence_base: present
cert_index: [C1, C2]
flags: []
-->

## Findings

### WS1 [should-fix] Two em dashes in one prose paragraph

Location: `docs/workflow-book/README.md:31` (the "How this book is produced" opening paragraph).

Issue: violates `house-style.md § Em-dash discipline` ("At most one em dash per paragraph"). The clause "The one departure is diagrams — ASCII by default with a small committed-SVG set, instead of the inline mermaid the model ships — recorded in [`DIAGRAMS.md`]" uses an em-dash pair to bracket a parenthetical inside one blank-line-bounded prose paragraph. This is the only prose paragraph in the deliverable that carries two em dashes; every other multi-em hit in the files is a bullet list or table row, where one dash anchors each separate item and the rule does not fire.

Proposed fix: drop the parenthetical dashes and split the sentence, or move the parenthetical into commas:

> The one departure is diagrams. The book uses ASCII by default with a small committed-SVG set, instead of the inline mermaid the model ships, and records the convention in [`../../workflow-book-builder/DIAGRAMS.md`](../../workflow-book-builder/DIAGRAMS.md).

### WS2 [suggestion] Tier-2 "robustness" in a disclaiming clause

Location: `workflow-book-builder/DIAGRAMS.md:7` ("It is a design preference, not a measured robustness claim.").

Issue: "robust" / "robustness" is on the Tier-2 strongly-avoid list in `house-style.md § Banned vocabulary`. The use here is borderline-allowed: the sentence disclaims the word's marketing sense ("not a measured robustness claim"), naming what kind of claim is absent rather than asserting one, which is closer to literal than to AI-tell usage. Flagging as a suggestion so the author decides; the rule asks for a one-line justification when a Tier-2 word survives the self-check.

Proposed fix (optional): replace with the concrete property being disclaimed, e.g. "It is a design preference, not a measured reliability claim." or "not a benchmarked claim." If "robustness" stays, add the one-line justification the Tier-2 rule requires.

## Evidence base

#### C1

`house-style.md § Em-dash discipline` caps em dashes at one per blank-line-bounded paragraph outside fenced code. The `grep -P '—.*—'` sweep across all four prose files, filtered to remove bullet and table lines, returns exactly one prose paragraph: `README.md:31`, where the em-dash pair brackets the "ASCII by default ... the model ships" parenthetical. The other paragraphs the raw per-paragraph counter flagged (BOOK_BRIEF role list, PIPELINE START prompt, the bullet lists in README "Contents" and "Start here") are bullet lists or Markdown tables carrying one em dash per item, which the rule does not treat as a single-paragraph stack. Confirmed finding.

#### C2

`house-style.md § Banned vocabulary` Tier 2 lists "robust". The banned-vocabulary sweep hit `DIAGRAMS.md:7`. The hit reads "not a measured robustness claim" — the word sits inside a negation that disclaims the marketing sense rather than asserting robustness, so the literal-meaning carve-out in the Tier-2 rule plausibly applies. Low-confidence finding: a reader could keep the word with the required one-line justification, or swap it for the concrete property. Surfaced as a suggestion, not a confirmed violation.
