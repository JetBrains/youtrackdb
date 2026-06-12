<!-- MANIFEST
findings: 1   severity: {Critical: 0, Recommended: 1, Minor: 0}
index:
  - {id: WS1, sev: Recommended, loc: ".claude/workflow/conventions.md:1291-1298", anchor: "### WS1 ", cert: "C1", basis: "per-paragraph em-dash count = 2 in the §1.7(l) opening paragraph, over the one-per-paragraph cap in § Em-dash discipline"}
evidence_base: {section: "## Evidence base", certs: 1}
cert_index: [C1]
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [Recommended] §1.7(l) opening paragraph carries two em dashes, over the one-per-paragraph cap

- **File:** `.claude/workflow/conventions.md` (lines 1291-1298), new `### (l) Opt-out criteria-switch extension` section.
- **Axis:** em-dash overuse.
- **Cost:** two em dashes in one blank-line-bounded paragraph of durable rule prose; the same `X — appositive — verb` cadence the house style names as the strongest AI tell at scale.
- **Issue:** `house-style.md § Punctuation and typography → Em-dash discipline` caps em dashes at one per blank-line-bounded paragraph (outside fenced code) and states "more than one is a finding." This paragraph is durable rule prose in `.claude/workflow/**`, so full house-style applies (not the chat-scale AI-tell subset). The paragraph reads: "The three Phase-3A criteria-switch blocks — the \"Workflow-machinery criteria\" block in `technical-review.md`, `risk-review.md`, and `adversarial-review.md` — fire when the plan's `### Constraints` carries **either** the (b) workflow-modifying marker prefix **or** the (k) opt-out marker prefix." Both em dashes bracket one appositive naming the three prompt files; the verb "fire" then resumes the main clause. The parallel `§1.7(k)` stamp-advance paragraph (lines 1272-1286) was kept to a single em dash, so this is an isolated slip, not a section-wide pattern.
- **Suggestion:** replace the bracketing dash pair with parentheses, which keeps the appositive inline and removes both dashes: "The three Phase-3A criteria-switch blocks (the \"Workflow-machinery criteria\" block in `technical-review.md`, `risk-review.md`, and `adversarial-review.md`) fire when the plan's `### Constraints` carries **either** the (b) workflow-modifying marker prefix **or** the (k) opt-out marker prefix."

## Evidence base

#### C1 — em-dash count, §1.7(l) opening paragraph

Confirmed. Per-paragraph em-dash sweep over the new `### (k)` / `### (l)` block (Python, fenced code excised, blank-line paragraph split): exactly one paragraph returned a count ≥ 2 — the `§1.7(l)` opener at lines 1291-1298, count = 2. The `§1.7(k)` body, the `§1.5` code-comment restatement paragraph (lines 557-564, count = 0), the `## Orientation` rule prose (count = 0), and the byte-identical reworded subset blurb (count = 0) all passed the one-per-paragraph cap.
