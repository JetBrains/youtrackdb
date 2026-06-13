<!--MANIFEST
dimension: workflow-writing-style
prefix: WS
iteration: 1
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: WS1, sev: suggestion, loc: ".claude/skills/readability-feedback/SKILL.md:76", anchor: "#ws1-suggestion", cert: C1, basis: "third em dash added to a fenced-prompt paragraph; fence-excluded by the mechanical rule, so style-only"}
  - {id: WS2, sev: suggestion, loc: "docs/adr/explanation-style/_workflow/plan/track-2/reviews/hook-safety-iter1.md:25", anchor: "#ws2-suggestion", cert: C2, basis: "two em dashes in one bullet paragraph in a _workflow/** swept artifact"}
evidence_base: {section: "## Evidence base", certs: 2, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [suggestion] Third em dash in the dispatched audit-prompt paragraph (fence-excluded, style-only)

- File: `.claude/skills/readability-feedback/SKILL.md` (line 76)
- Axis: em-dash overuse
- Cost: a STEP 4 prompt paragraph dispatched verbatim to audit sub-agents now carries three em dashes after the added sentence; readable, and the mechanical em-dash check excludes fenced code, so this is style polish only
- Issue: house-style `§ Em-dash discipline` caps em dashes at one per blank-line-bounded paragraph but explicitly counts "outside fenced code." The STEP 4 line sits inside the ` ```text ` audit-prompt fence opened at line 67, so it is outside the mechanical-check surface. The added middle sentence ("A too-terse passage — prose that cannot be followed without opening the code, or a one-line assertion dropped with no motivation — is `CAUGHT by § Orientation`, not a GAP.") introduces a paired-parenthetical em-dash pair into a paragraph that, taken whole, reaches three em dashes. Two form a legitimate parenthetical; the construction is still dense for prose the skill ships to a sub-agent.
- Suggestion: replace the em-dash parenthetical with a colon and a period: "A too-terse passage is `CAUGHT by § Orientation`: prose that cannot be followed without opening the code, or a one-line assertion dropped with no motivation. Mark GAP only after checking every relevant section; …" No behavior change — the sub-agent's classification instruction is identical.

### WS2 [suggestion] Two em dashes in one bullet paragraph (working artifact, swept at Phase 4)

- File: `docs/adr/explanation-style/_workflow/plan/track-2/reviews/hook-safety-iter1.md` (line 25, the `- Suggestion:` bullet)
- Axis: em-dash overuse
- Cost: one bullet paragraph in a `_workflow/**` review artifact carries two em dashes; the file is removed by the Phase 4 cleanup commit before merge, so durability impact is nil
- Issue: house-style `§ Em-dash discipline` caps em dashes at one per blank-line-bounded paragraph. The `- Suggestion:` bullet is one such paragraph and uses two: "…`driving`) — these are the inflation-signalling participles…" and "…blockers alone (D4b) — a false positive degrades review signal…". Per the track-review scope, `_workflow/**` artifacts are reviewed for genuine readability defects only and such findings are marked `suggestion`.
- Suggestion: convert the first em dash to a colon or period — "…`driving`): these are the inflation-signalling participles, and the open arms add little beyond them" — leaving the second em dash as the paragraph's single legitimate one. Optional; the file does not survive merge.

## Evidence base

#### C1 readability-feedback/SKILL.md:76 em-dash density — CONFIRMED (fence-excluded)
Three-step section-length decision is not the axis here; this is a `§ Em-dash discipline` check. Step 1 (count): `grep -o '—'` on line 76 returns 3. Step 2 (fence check): line 67 opens a ` ```text ` block enclosing STEP 1-4; line 76 is inside it, and `§ Em-dash discipline` counts "per blank-line-bounded paragraph outside fenced code," so the mechanical rule does not fire. Step 3 (style judgment): the added sentence is what raised the count; two of the three em dashes are a legitimate paired parenthetical, leaving the finding at suggestion. Severity suggestion (not should-fix): fence exclusion + readable prose.

#### C2 hook-safety-iter1.md:25 em-dash density — CONFIRMED
`grep -o '—'` on the `- Suggestion:` bullet returns 2; the bullet is a single blank-line-bounded paragraph (no internal blank line), so both em dashes share one paragraph, exceeding the one-per-paragraph cap of `§ Em-dash discipline`. The two positions verified by probe: "`driving`) — these are" and "(D4b) — a false positive". Severity suggestion (not should-fix): the file is a `_workflow/**` review artifact swept by the Phase 4 cleanup commit, and the track-review scope marks working-artifact prose findings as suggestion.
