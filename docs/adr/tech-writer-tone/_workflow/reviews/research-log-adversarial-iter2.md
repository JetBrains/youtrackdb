<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: A10, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:21, anchor: "### A10 ", cert: C15, basis: "D1's added grep-provenance claim fails against the live tree: the stated grep hits design-document-rules.md:289 (dsc-ai-tell catalogue row naming four removed patterns) and CLAUDE.md:93, neither in D1's consumer list"}
  - {id: A11, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:45, anchor: "### A11 ", cert: C16, basis: "D7's adjective-triads disposition rests on a false identity — the rule is a distinct vague-adjective-stack rule that lands in keep under D7's own criteria, and no hyphenated-pair language exists at either consumer site"}
  - {id: A12, sev: suggestion, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:37, anchor: "### A12 ", cert: C17, basis: "D5 carve names 8 of 15 track-file sections; the register of the two plan-at-start prose sections an author writes (Validation and Acceptance, Idempotence and Recovery) follows only from the word 'only'"}
verdicts: {verified: 9, still_open: 0, rejected: 0}
verdict_index:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
  - {id: A8, verdict: VERIFIED}
  - {id: A9, verdict: VERIFIED}
evidence_base: {section: "## Evidence base", certs: 3, matches: 1}
cert_index:
  - {id: C15, verdict: FAILS, anchor: "#### C15 "}
  - {id: C16, verdict: WEAK, anchor: "#### C16 "}
  - {id: C17, verdict: HOLDS, anchor: "#### C17 "}
overall: NEEDS REVISION
flags: [CONTRACT_OK]
-->

## Verdicts

### A1 — VERIFIED
D6 now states the target reader keeps sole ownership of the prose AI-tell judgment axis (the S4 invariant) and that its findings retain the house-style `§`-citation obligation for the kept judgment rules — exactly the one-sentence resolution A1 demanded. The claim matches the live enforcement structure: axis ownership at `readability-auditor.md:68`, the judgment-vs-regex split at `:70`, the `§`-citation output rule at `:103`. The rejected "persona-without-checklist" alternative records A1's failure scenario by name.

### A2 — VERIFIED
D1 now names `.claude/skills/ai-tells/SKILL.md` (with the always-loaded `description:` note and the three hard-coded removals) and `test_dsc_ai_tell.py` + `dsc-ai-tell-fixture.md` (with the build-breaks rationale), and the 08:52Z baseline surface list gained both. The demanded items landed; the grep-provenance sentence added alongside them is itself inaccurate against the live tree, raised separately as A10 rather than reopening A2.

### A3 — VERIFIED
D4's site list now carries `MANDATORY_OR_FORM_SUBHEADINGS` with the false-positive mechanism spelled out, and the live set (`design-mechanical-checks.py:62-73`) lists only `"tl;dr"`, confirming the need for the both-spellings addition. All four previously missing files verified at their cited lines: `prompts/create-final-design.md:187`, `skills/review-workflow-pr/SKILL.md:114`, `conventions.md:149`, `agents/comprehension-review.md:34`.

### A4 — VERIFIED
D2 (refined 06:40Z) splits the contract per role as demanded: hybrid narrative + findings on the whole-doc comprehension/time-constrained reviewer (whose narrative has named consumers — gate verdict, author rework seed), persona-voiced structured findings only on the range-sliced auditor, with the whole-read-property reasoning stated and the uniform-hybrid variant added to the rejected list.

### A5 — VERIFIED
D9 now ranks both named collisions: worked-example openers become a template-bound exempt category under the section-length cap (the live exemption list at `house-style.md:284` is explicitly non-exhaustive with an explicit-addition path, so the extension is well-formed), with anti-padding still applying inside the example; sibling consolidation yields to one-concept-per-section for concept-teaching siblings and stays for same-shape reference material. Any consistent ranking clears the finding; this one is consistent and grounded.

### A6 — VERIFIED
D3 now names the false-clean risk and the direction distinction, gives three mitigations (the persona contract makes failure-to-follow introspectively reportable; the gate's answers-plus-citations output makes failure visible; Opus readers carry the opposite-direction calibration error this change exists to fix), lists the remaining backstops, and sets a residual-risk revisit trigger. This is the persona-output-visibility reasoning C8 said would settle the assumption.

### A7 — VERIFIED
D5 gained the demanded D8-style carve: three voice-governed prose sections, five registry-terse structured surfaces. All eight section names match the live 15-section template (`conventions-execution.md:83-248`). The seven sections in neither list are a residual explicitness gap raised as suggestion A12, not a reopen — the demanded carve landed.

### A8 — VERIFIED
The closure default is stated ("any rule not named in the remove list keeps its current disposition"); trailing hedges and persuasive authority tropes now sit explicitly in the keep list (matching `house-style.md:104` and `:229`); adjective triads received a disposition; and the negative-parallelism-vs-throat-clearing boundary is argued (additive filler vs near-zero-length reframing, plus the false-positive enforcement cost of the intensifier discriminator). The adjective-triads disposition itself misidentifies the rule — that is a new defect introduced by the revision, raised as A11, not an incompleteness of the enumeration A8 demanded.

### A9 — VERIFIED
D9 adopts both halves of the proposed hardening: forward/backward links cite the neighbor's section heading verbatim (greppable), and the `edit-design` mutation discipline gains a re-read reminder on section-move/section-remove/section-rename.

## Findings

### A10 [should-fix]
**Certificate**: C15 (Assumption test: D1's grep-provenance claim)
**Target**: Decision D1 (revision strengthened per gate A2) — the consumer-list enumeration claim
**Challenge**: D1 now claims the consumer list was "enumerated by grep over the removed pattern names … re-derivable the same way". Running exactly that grep (`negative parallel|title case|hyphenated|curly|closing phrases|roundabout negation`, case-insensitive, over `.claude/` + `CLAUDE.md`) returns two real consumers absent from D1's list. `design-document-rules.md:289` is the dsc-ai-tell check-catalogue row that documents, by name, four of the patterns whose regexes D1 deletes (negative parallelism `not X, but Y`, Title Case H2+ headings, hyphenated-pair clusters at 3+, the trailing-negation intensifier variant); after the removal it describes checks that no longer exist. `CLAUDE.md:93` hard-codes the "It's not X — it's Y" negative-parallelism exemplar in the Writing Style section. Both files appear in the 08:52Z baseline list, but under other decisions' reasons (the D4 rename; the generic writing-style pointer) — nothing routes a D1 implementer to the catalogue row, and the re-derivability sentence is falsified by its own derivation. The grep also returns two false hits (`workflow-reindex.py:285`, `ephemeral-identifier-rule.md:157` — "hyphenated" in unrelated senses) a re-deriver needs to know to filter.
**Evidence**: grep output over the stated pattern names; `design-document-rules.md:289`; `CLAUDE.md:93`.
**Proposed fix**: Add both files to D1's consumer list at row/section granularity (the dsc-ai-tell catalogue row; the CLAUDE.md exemplar), and note the two false-positive hits so the grep claim becomes true as stated.

### A11 [should-fix]
**Certificate**: C16 (Challenge: Decision D7 revision — adjective-triads classification)
**Target**: Decision D7 (revision strengthened per gate A8) — "the 'adjective triads' mention … is the hyphenated-word-pair rule's consumer echo … and is removed at those sites with it"
**Challenge**: The identity claim has no textual basis at either consumer site, and the disposition it licenses deletes a keep-class rule. The live rule at `review-workflow-writing-style.md:111-112` flags "'comprehensive, robust, scalable'-style adjective stacks. One concrete adjective beats three vague ones." — its own example contains zero hyphenated pairs, while the hyphenated rule (`house-style.md:271`) triggers on three-plus *distinct hyphenated* pairs in adjectival position: different fingerprint, different trigger. The `ai-tells` skill carries "adjective triads" only in its frontmatter description, listed as its own structural fingerprint, and the word "hyphenated" appears nowhere in that file (so "the `ai-tells` catalogue" echo site does not exist either). Checked against D7's own criteria: the rule deletes text (drop two vague adjectives) and forces substance (one concrete adjective) — the keep criterion verbatim — and the agent's own template lists adjective triads under trim opportunities (`:191`) and as a finding axis (`:200`). An implementer executing D7 as written removes an understandability-bearing rule on a false ground.
**Evidence**: `review-workflow-writing-style.md:111-112,191,200`; `house-style.md:271`; grep for `triad|hyphenat` over `.claude/skills/ai-tells/SKILL.md` (one hit, frontmatter description only).
**Proposed fix**: Reclassify. Either keep the adjective-triads rule at its two consumer sites (consistent with D7's keep criterion; optionally give it a house-style section so D1's remove-at-source procedure has a source to act on), or argue removal on the actual rule's merits rather than an identity with the hyphenated-pair rule.

### A12 [suggestion]
**Certificate**: C17 (Challenge: Decision D5 revision — carve enumeration coverage)
**Target**: Decision D5 (revision strengthened per gate A7) — the track-file anchor carve
**Challenge**: The carve dispositions 8 of 15 track-file sections: three voice-governed prose sections, five registry-terse structured surfaces. Seven sections sit in neither list (`conventions-execution.md:89-248`: Progress, Surprises & Discoveries, Outcomes & Retrospective, Validation and Acceptance, Idempotence and Recovery, Artifacts and Notes, Base commit). Five of those are continuous-log or housekeeping sections the Phase-1 author never seeds in prose, but `## Validation and Acceptance` (`:194`) and `## Idempotence and Recovery` (`:200`) are plan-at-start prose an authoring agent writes at Phase 1 / Phase A; their register currently follows only from the word "only" in the voice clause (not named → not voice-governed → current register). D7's own revision demonstrates the fix pattern: one closure sentence removes the inference. The decision holds; this is explicitness hardening, the same class as A9.
**Evidence**: `conventions-execution.md:83-248` (the 15 sections with their plan-at-start vs continuous-log roles); D5's carve text at research-log.md:37.
**Proposed fix**: Add a closure sentence to D5's carve: "any track-file section not named in the voice list keeps its current register."

## Evidence base

**Assumption tests**

#### C15 Assumption test: D1's consumer enumeration is re-derivable by the stated grep
- **Claim**: D1 (revised) — the mirrored-consumer list was "enumerated by grep over the removed pattern names — 'negative parallelism', 'Title Case', 'hyphenated', 'curly', 'closing phrases', 'roundabout negation' — re-derivable the same way".
- **Stress scenario**: run the grep as stated and diff against D1's list.
- **Result**: 11 files hit. Listed in D1 and hit: `design-mechanical-checks.py`, `test_dsc_ai_tell.py`, `dsc-ai-tell-fixture.md`, `ai-tells/SKILL.md`, `review-workflow-writing-style.md`, `house-conversation.md` (plus the source `house-style.md`). Hit and NOT listed: `design-document-rules.md:289` (real consumer — the dsc-ai-tell catalogue row naming four removed patterns), `CLAUDE.md:93` (real consumer — the negative-parallelism exemplar). False hits a re-deriver must filter: `workflow-reindex.py:285`, `ephemeral-identifier-rule.md:157`.
- **Verdict**: FAILS — the enumeration and its provenance claim disagree with the derivation they cite; two real consumers missing.

**Decision challenges**

#### C16 Challenge: Decision D7 revision — adjective triads as hyphenated-pair consumer echo
- **Chosen approach**: remove the adjective-triads mentions at `review-workflow-writing-style.md` and the `ai-tells` skill as the hyphenated-word-pair rule's consumer echo.
- **Best rejected alternative** (unlisted in the log): keep the rule at its consumer sites under D7's own keep criterion.
- **Counterargument trace**:
  1. `review-workflow-writing-style.md:112` — "'comprehensive, robust, scalable'-style adjective stacks. One concrete adjective beats three vague ones." No hyphenated pair in the rule's own example; the rationale forces concreteness (substance), which is D7's keep criterion verbatim.
  2. `house-style.md:271` — the hyphenated rule triggers on "Three or more *distinct* hyphenated pairs in the same paragraph, in adjectival position". Distinct fingerprint from a vague-adjective stack.
  3. `ai-tells/SKILL.md` — "adjective triads" appears once, in the description frontmatter, listed as its own structural fingerprint beside (not under) other patterns; "hyphenated" appears nowhere in the file. The claimed "catalogue" echo site does not exist.
  4. The agent's report template treats the rule as live and independent: trim-opportunities mention at `:191`, finding-axis entry at `:200`.
- **Codebase evidence**: file:line citations above.
- **Survival test**: WEAK — the disposition needs either a keep reclassification or an argument addressed to the actual rule.

#### C17 Challenge: Decision D5 revision — carve enumeration coverage
- **Chosen approach**: closed three-section voice list ("the voice governs the prose sections only") plus a five-surface registry-terse list.
- **Best rejected alternative**: same carve plus a one-sentence closure default (the D7 pattern).
- **Counterargument trace**:
  1. The 15-section template (`conventions-execution.md:83-248`) leaves seven sections unnamed by the carve.
  2. Five unnamed sections are continuous-log/housekeeping (orchestrator-written during execution); two — `## Validation and Acceptance` (`:194`) and `## Idempotence and Recovery` (`:200`) — are plan-at-start prose an author writes.
  3. The "only" in the voice clause closes the voice list, so the unnamed sections keep their current register by inference; the inference is sound but implicit, and the derived design copies the carve verbatim.
- **Codebase evidence**: `conventions-execution.md:83-248`.
- **Survival test**: HOLDS — the carve is correct as written; the closure sentence is hardening, so suggestion severity.
