<!-- MANIFEST
overall: PASS
verdicts:
  - {id: A1, prior_sev: should-fix, verdict: VERIFIED, loc: "track-2.md:9,47,62,97", note: "Re-worded to 10 preamble prompts + design-review.md content edit; grep confirms exactly 10 carry the preamble, design-review.md none; file set stays 11"}
  - {id: A2, prior_sev: should-fix, verdict: VERIFIED, loc: "track-2.md:62,63,68,78", note: "Count-flip scoped to the five-slug preamble sentence; design-review.md excluded from step 1; invariant + Validation assert 'five Human-reader rules' stays five"}
  - {id: A3, prior_sev: should-fix, verdict: VERIFIED, loc: "track-2.md:9,49,64,65,66,79,98", note: "Step 3 names the 4 enumerating skills; ai-tells gets catalogue row (D2-2), readability-feedback gets read-list/STEP-4/grep (D2-3); grep confirms 4 carry the blockquote, ai-tells catalogue has 6 rows 2 outside subset, readability has no enumeration"}
  - {id: A4, prior_sev: suggestion, verdict: VERIFIED, loc: "track-2.md:23-27,63", note: "D2-1 names a single block (### Prose AI-tell additions); Orientation precedent and target=tracks reach confirmed at design-review.md:207/:189-195; sync-map router at readability-feedback:47 corroborates"}
  - {id: A5, prior_sev: suggestion, verdict: VERIFIED, loc: "track-2.md:51-57,66,68,81", note: "Pinned command byte-identical to conventions.md:572 (diff = IDENTICAL); anchoring caveat carried as prose after the fence; step 3 flags non-uniform blockquote wrapping as per-site edit"}
findings: 0
flags: [CONTRACT_OK]
-->

# Adversarial gate verification — Track 2 (iteration 2)

**Overall: PASS.** All five iteration-1 adversarial findings (A1, A2 should-fix; A3 should-fix; A4, A5 suggestion) are resolved in the updated `track-2.md`, and each fix is grounded against the real `.claude/workflow/prompts/` and `.claude/skills/` files. No fix introduced a regression. No new finding. The opt-out (`§1.7(k)`) re-points references to the workflow-prose lens; mcp-steroid is unreachable, which is correct for this all-Markdown track (no Java symbols in scope), so the symbol-audit caveat does not bite.

## Verification certificates

#### Verify A1: preamble miscount (11 prompts each carry it vs. only 10)
- **Original issue**: the track claimed all 11 prompts carry the five-slug preamble and the slug-add applies "in each of the 11 prompts"; design-review.md carries no preamble, so the slug-add target is 10 plus a structurally different edit.
- **Fix applied**: track-2.md re-worded across Purpose (`:9`), Context and Orientation (`:47`), Plan of Work step 1 (`:62`), and Interfaces (`:97`) to "10 of the 11 prompts carry a five-slug preamble (the 11th, design-review.md, has no preamble)"; design-review.md routed to the D2-1 content edit. The in-scope file set stays 11.
- **Re-check**:
  - Track-file location: `:9` ("10 of the 11 prompts carry a five-slug preamble to flip (the 11th, `design-review.md`, has no preamble)"), `:47` ("10 of the 11 workflow prompts … carry a house-style preamble"), `:62` ("each of the 10 prompts that carry it (every prompt except `design-review.md`)"), `:97` ("10 of these get the preamble slug add; `design-review.md` carries no preamble and gets only the cold-read content edit").
  - Codebase ground truth: `grep -rln 'five AI-tell subset section slugs to apply are' .claude/workflow/prompts/` returns exactly 10 files (adversarial-review, consistency-gate-verification, consistency-review, create-final-design, dimensional-review-gate-check, review-gate-verification, risk-review, structural-gate-verification, structural-review, technical-review). `grep` for the preamble in design-review.md exits 1 (no match). Disk has 11 prompt files total. The count and the routing both match the fixed prose.
  - Criteria met: scope/sizing accuracy — the decomposer will no longer plan a preamble slug-add at design-review.md.
- **Regression check**: checked the Interfaces in-scope set (still 11 prompts, `:97`) and the §1.7(k) grep derivation note (`:104`, reconciled via `grep -rln 'Banned analysis patterns'` = 11+6). The file footprint stays 11 prompts; only the edit-kind split changed (10 slug + 1 content). Clean.
- **Verdict**: VERIFIED

#### Verify A2: mechanical five→six flip corrupts design-review.md's "five Human-reader rules"
- **Original issue**: step 1's "flip any numeric five→six" applied to design-review.md (in scope) would corrupt its only two "five" tokens, which name "the five Human-reader rules" (a different set), breaking the cross-reference to the five Human-reader-rule bullets.
- **Fix applied**: step 1 scopes the count-flip to the five-slug preamble sentence only and excludes design-review.md; step 2 forbids touching the "five Human-reader rules" count; the invariant (`:68`) and Validation (`:78`) assert the Human-reader count stays five.
- **Re-check**:
  - Track-file location: `:62` ("Flip the count word in that one preamble sentence … The flip is scoped to the subset-enumeration sentence only — `design-review.md` is not in this step, and no other 'five' in any file is touched"); `:63` ("Do NOT touch … the 'five Human-reader rules' count at `:181` / `:458`"); `:68` ("`design-review.md`'s 'five Human-reader rules' count stays five"); `:78` Validation ("The 'five Human-reader rules' count at `:181` / `:458` is unchanged (still five)").
  - Codebase ground truth: `grep -n 'five' design-review.md` returns exactly two lines — `:181` ("for these five rules") and `:458` ("the five Human-reader rules"). The five-rule block at `:175-179` lists exactly five Human-reader rules (audience-fit, glossary-introduction, why-before-what, navigability, explanatory register). No five-slug AI-tell count word exists in the file to flip. The fix's exclusion is therefore both necessary and correctly placed.
  - Criteria met: invariant accuracy — the count-flip can no longer corrupt the Human-reader-rule count.
- **Regression check**: confirmed the line numbers cited in the fix (`:181`, `:458`, the five-rule block) are accurate against the live file; the explanatory-register rule was added as a fifth Human-reader rule, so "five" is correct (not four). The track names all five correctly at `:25`/`:47`. Clean.
- **Verdict**: VERIFIED

#### Verify A3: skill miscount (6 skills each carry it vs. only 4)
- **Original issue**: step 3 said "each of the 6 skills" carries a five-slug enumeration; only 4 do (create-plan, execute-tracks, review-plan, review-workflow-pr). ai-tells uses a catalogue map, readability-feedback has no enumeration.
- **Fix applied**: step 3 names the 4 enumerating skills; ai-tells gets a new catalogue-row decision (D2-2), readability-feedback gets read-list/STEP-4/grep edits (D2-3). Context and Orientation (`:49`), Interfaces (`:98`), and Validation (`:79-81`) updated.
- **Re-check**:
  - Track-file location: `:9` ("4 of the 6 skills carry a five-slug blockquote to extend"); `:64` (step 3 names the four: create-plan, execute-tracks, review-plan, review-workflow-pr); `:65` (step 4 = ai-tells catalogue row, D2-2); `:66` (step 5 = readability-feedback read-list + STEP-4 + grep, D2-3); `:79-81` Validation; `:98` Interfaces.
  - Codebase ground truth: `grep -rln 'Banned analysis patterns' .claude/skills/` returns exactly 6 (ai-tells, create-plan, execute-tracks, readability-feedback, review-plan, review-workflow-pr) — confirming the "6 skills" in-scope set. The five-slug "House style for chat-scale prose" blockquote is present at create-plan:23, execute-tracks:23, review-plan:31, review-workflow-pr:42-44 — exactly the 4 named. ai-tells/SKILL.md `## Catalogue lookups` has 6 rows, 2 outside the subset (`§ Structural rules`, `§ Punctuation and typography`) — matches the track's claim and has no five-slug preamble. readability-feedback has no preamble enumeration (its subset touchpoints are the STEP-1 read-list `:70`, STEP-4 classification `:76`, and the grep `:54`).
  - Criteria met: scope/sizing accuracy — the decomposer no longer plans a slug-add for ai-tells or readability-feedback.
- **Regression check**: D2-2 and D2-3 are new Decision Records; verified each cites a real #1142 precedent (below). The "6 skills" framing is the in-scope subset, not all 16 SKILL.md files on disk — the Interfaces line `:98` and the §1.7(k) reconciliation `:104` both make the subset explicit. Clean.
- **Verdict**: VERIFIED

#### Verify A4: D2-1 conflated two design-review.md blocks
- **Original issue**: D2-1 and step 2 pointed at "the cold-read Human-reader rules / Prose AI-tell additions list," conflating two distinct named blocks; the cited Orientation precedent actually lives in the Prose AI-tell block, contradicting the "Human-reader rules" half.
- **Fix applied**: D2-1 now names a single block — `### Prose AI-tell additions` — with three grounded reasons (the #1142 Orientation precedent landed there; the block runs on `target=tracks`; the readability-feedback sync-map router routes a clarity rule there). Step 2 names the same block and forbids touching the Human-reader block.
- **Re-check**:
  - Track-file location: D2-1 (`:23-27`) names `### Prose AI-tell additions` (`design-review.md:186-217`) as chosen, with the Human-reader block listed only as a rejected alternative; step 2 (`:63`) targets the Prose AI-tell block and says "Do NOT touch the `### Human-reader cold-read additions` block."
  - Codebase ground truth: design-review.md:207 checks too-terse prose against `§ Orientation` inside the `### Prose AI-tell additions` block; its applies-to set names `target=tracks` (`:189-195`); the Human-reader block runs on design kinds only (`:172`). readability-feedback/SKILL.md:47 explicitly routes "a rule on prose density or terseness (the kind `## Orientation` and the over-dense AI-tells cover)" into "the `### Prose AI-tell additions` block." The TOC summary for the block sits at `:23` (D2-1's `:23` reference is accurate). All three reasons are factually grounded.
  - Criteria met: the target block is now named unambiguously and the cited precedent matches the chosen block.
- **Regression check**: confirmed the `:458` "second exception" evidence clause already exists and references `§ Prose AI-tell additions` checks (so D2-1's `:458` edit lands on a real clause). The "five Human-reader rules" exclusion (A2) is consistent with D2-1's untouched-family note (`:26`). Clean.
- **Verdict**: VERIFIED

#### Verify A5: "copy verbatim" underspecified; per-site enumeration non-uniform
- **Original issue**: "copy verbatim" did not specify whether the precision-caveat sentence travels into the SKILL.md block, and the per-skill enumeration wrapping is non-uniform, so the slug-add is per-site, not one sweep.
- **Fix applied**: the grep target is pinned byte-identical in a fenced block (`:53-55`) with the anchoring caveat carried as prose right after the fence (`:57`); step 5 (`:66`) and the invariant (`:68`) pin "byte-identical to `conventions.md:572`"; step 3 (`:64`) flags the non-uniform blockquote wrapping as a per-site edit.
- **Re-check**:
  - Track-file location: `:51-57` (pinned command + two-change description + caveat-as-prose), `:64` ("blockquote wrapping is non-uniform … per-site edit, not one find-replace"), `:66` (step 5 grep sync to "byte-identical six-alternative `grep -rnE` form … with the anchoring caveat as prose after the fence"), `:68` (invariant), `:81` (Validation).
  - Codebase ground truth: a string-equality diff of the command extracted from conventions.md (`grep -oE "grep -rnE '[^']*' \.claude/ CLAUDE\.md"`) against `track-2.md:54` returns IDENTICAL. conventions.md:572 carries the trailing caveat ("`Orientation` and `Plain language` are common words, so the scan matches them only in their `##` / `§` heading-pointer form …"); the track mirrors that caveat at `:57`. readability-feedback/SKILL.md:54 currently holds the stale `grep -rn 'Banned vocabulary\|…'` BRE four-name form — confirming the divergence the sync targets, and confirming the "two changes" framing (`grep -rn` → `grep -rnE`, four bare → eight alternatives).
  - Criteria met: "matches" is now defined (byte-identical command + caveat sentence as prose), and the per-site nature of the enumeration edits is explicit.
- **Regression check**: confirmed the pinned command is the ERE (`-rnE`, `|`) form, not the stale BRE; the eight-alternative count is correct (2 common words × 2 forms + 4 bare = 8). Clean.
- **Verdict**: VERIFIED

## Findings

(none — all five prior findings VERIFIED, no regressions, no new findings)
