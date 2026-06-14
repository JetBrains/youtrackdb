<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "design-review.md:169-184,458", anchor: "### T1 ", cert: P-DR, basis: "track points the Plain-language rule at the wrong design-review block (Human-reader rules), against the project's own sync-map authority"}
  - {id: T2, sev: suggestion, loc: ".claude/workflow/prompts/design-review.md", anchor: "### T2 ", cert: P-PREAMBLE, basis: "design-review carries no five-slug preamble; the '11 prompts' edit is really 10 preamble flips plus 1 special edit"}
  - {id: T3, sev: suggestion, loc: "readability-feedback/SKILL.md:54", anchor: "### T3 ", cert: P-GREP, basis: "pin the verbatim target (ERE -rnE, 8 alternatives) so the sync is exact"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 5}
cert_index:
  - {id: P-INSCOPE, verdict: CONFIRMED, anchor: "#### P-INSCOPE "}
  - {id: P-PREAMBLE, verdict: PARTIAL,  anchor: "#### P-PREAMBLE "}
  - {id: P-DR,       verdict: WRONG,    anchor: "#### P-DR "}
  - {id: P-DR458,    verdict: WRONG,    anchor: "#### P-DR458 "}
  - {id: P-SKILLS,   verdict: CONFIRMED, anchor: "#### P-SKILLS "}
  - {id: P-AITELLS,  verdict: CONFIRMED, anchor: "#### P-AITELLS "}
  - {id: P-GREP,     verdict: PARTIAL,  anchor: "#### P-GREP "}
  - {id: P-SYNCMAP,  verdict: CONFIRMED, anchor: "#### P-SYNCMAP "}
  - {id: P-COUNT,    verdict: CONFIRMED, anchor: "#### P-COUNT "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: P-DR, P-DR458, P-SYNCMAP
**Location**: track-2.md `## Context and Orientation` (line 35), `## Decision Log` D2-1 (lines 23-27), `## Plan of Work` step 2 (line 45); source `design-review.md:169-184` (`### Human-reader cold-read additions`), `:186-217` (`### Prose AI-tell additions`), `:458` (`§ Tone and depth`).

**Issue**: The track points the new `## Plain language` cold-read check at the wrong block of `design-review.md`. Three places in the track name the Human-reader rules as the target:
- `## Context and Orientation` (line 35): "it holds the cold-read Human-reader rules that the rule must join (`design-review.md:458` references 'the five Human-reader rules' / 'the § Prose AI-tell additions')."
- D2-1 rationale (line 25): "A new always-on clarity rule belongs in the rules the cold-read applies, the same way `## Orientation` was added there in #1142."
- Plan of Work step 2 (line 45): "add the rule to the cold-read Human-reader rules / Prose AI-tell additions list."

The "five Human-reader rules" at `design-review.md:458` are audience-fit, glossary-introduction, why-before-what, navigability, and explanatory register (`design-review.md:175-179`). They are a distinct rule family from the AI-tell subset. `## Plain language` is a lexical-and-syntactic clarity rule — prefer the common word, short sentences, no idioms (plan D5 frames it as the lexical/syntactic complement to `## Orientation`). The block that already checks against `§ Orientation` and `§ Banned analysis patterns` is `### Prose AI-tell additions` (`design-review.md:197-210`), not the Human-reader block.

The project's own authority confirms this. `readability-feedback/SKILL.md:47` (in this track's scope) already states the rule: "A rule on prose density or terseness (the kind `## Orientation` and the over-dense AI-tells cover) instead joins the `### Prose AI-tell additions` block, which scans both `target=design` and `target=tracks` and carries its own `§ Tone and depth` evidence clause." D2-1 as written contradicts this sync-map rule.

The D2-1 precedent claim is also off. The +62-line `## Orientation` change in #1142 added the too-terse check to the `### Prose AI-tell additions` block — `design-review.md:207` checks "Too-terse … against `§ Orientation`" inside that block, and the TOC summary at `:23` names Orientation as a Prose-AI-tell-additions concern. So "the same way `## Orientation` was added there" actually argues for the Prose AI-tell additions block, the opposite of what D2-1 concludes.

This is not a blocker — the track's intent (a real cold-read check, not a slug-only add) is sound, and the right block exists. But left unfixed, the step would add the rule to the wrong list, miscount "five Human-reader rules" → six at `:181` and `:458`, and conflate two rule families. The cold-read would also not run the plain-language check on `target=tracks` (the Human-reader block is design-kinds-only per `:172`; the Prose AI-tell additions block also covers Step-4b track prose per `:189-195`), which silently narrows the reach the track wants.

**Proposed fix**: Redirect step 2 and D2-1 to the `### Prose AI-tell additions` block. Concretely: add a `## Plain language` lens to the `### Prose AI-tell additions` block (`design-review.md:186-217`), update that block's TOC summary at `:23`, and extend the `§ Tone and depth` "second exception" clause at `:458` (which already names "§ Banned analysis patterns, § Mechanism traces…, or § Orientation") to include the plain-language rule. Do NOT touch the "five Human-reader rules" at `:181`/`:458` or the Human-reader block at `:169-184`. Restate D2-1's precedent sentence to cite the Orientation too-terse check's home (the Prose AI-tell additions block), and align the D2-1 "Implemented in" / step-2 wording with `readability-feedback/SKILL.md:47`.

### T2 [suggestion]
**Certificate**: P-PREAMBLE, P-COUNT
**Location**: track-2.md `## Purpose / Big Picture` (line 9), `## Context and Orientation` (line 35), `## Plan of Work` step 1 (line 44), `## Interfaces and Dependencies` (line 77).

**Issue**: The track counts `design-review.md` among the "11 `.claude/workflow/prompts/*.md` enumerations" that get the five-slug preamble flip, and `## Context and Orientation` line 35 calls it "the exception in kind: besides the preamble, it holds the cold-read Human-reader rules." But `design-review.md` carries **no** five-slug preamble sentence at all. Of the 11 prompts that match `Banned analysis patterns`, only 10 carry the "the five AI-tell subset section slugs to apply are …" preamble; `design-review.md` matches the grep solely through its `### Prose AI-tell additions` block and TOC summary. So the real shape is 10 preamble flips (each with a literal "five" → "six" in the same sentence, per step 1) plus 1 structurally-different content edit on `design-review.md` (T1's block edit), not 11 uniform preamble flips plus an extra.

This will not fail execution — T1's fix lands the design-review edit in the right place anyway — but the "11 prompts" framing invites the decomposer to apply a preamble flip to `design-review.md` that has no preamble to flip, producing an empty or wrong edit.

**Proposed fix**: Reword the in-scope description to "10 prompts carry the five-slug preamble (flip its 'five' → 'six' and append `## Plain language`); `design-review.md` carries no preamble and instead gets the `### Prose AI-tell additions` content edit (T1/D2-1)." Adjust the line-35 "besides the preamble" phrasing — `design-review.md` has the cold-read blocks *instead of*, not *besides*, the preamble. The "~17 files" total is unaffected (10 + 1 + 6 = 17).

### T3 [suggestion]
**Certificate**: P-GREP
**Location**: track-2.md `## Context and Orientation` (line 39), `## Plan of Work` step 4 (line 47); source `readability-feedback/SKILL.md:54`, live target `conventions.md:572`.

**Issue**: Step 4 says "copy the live `conventions.md` helper verbatim." The verbatim target should be pinned so the sync is exact and the next reviewer can check it by string match. The live helper at `conventions.md:572` is:

`grep -rnE '## Orientation|## Plain language|§ Orientation|§ Plain language|Banned vocabulary|Banned sentence patterns|Banned analysis patterns|Em-dash discipline' .claude/ CLAUDE.md`

The `SKILL.md:54` copy is:

`grep -rn 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md`

The sync is two changes, not one: (a) `grep -rn` with BRE alternation `\|` → `grep -rnE` with ERE alternation `|`; (b) the four bare names → eight alternatives (the two common-word names `Orientation` / `Plain language` each anchored in both `##` and `§` heading-pointer form, the other four bare). The track's "old four" characterization (line 39) is correct, and the sync is achievable as a verbatim copy.

**Proposed fix**: In step 4 (and the validation line at track-2.md:61), state the exact target string so the step and its acceptance check are unambiguous: the `SKILL.md:54` block must become byte-identical to `conventions.md:572`'s ERE form (eight alternatives, `grep -rnE`). Keep the surrounding "find every pointer … in the same commit" framing at `:51`-`:55` intact.

## Evidence base

#### P-INSCOPE: the canonical in-scope grep yields exactly 11 prompts + 6 skills
- **Track claim**: "Exact in-scope set is derived by `grep -rln 'Banned analysis patterns' .claude/workflow/prompts/ .claude/skills/` at Phase A" (track-2.md:84); in-scope set = 11 prompts + 6 skills (track-2.md:77-78).
- **Search performed**: `grep -rln 'Banned analysis patterns' .claude/workflow/prompts/ .claude/skills/` (mcp-steroid unreachable; Markdown text grep is the correct tool — no Java symbols).
- **Code location**: 17 files — 11 under `prompts/` (technical-review, consistency-review, structural-gate-verification, review-gate-verification, adversarial-review, risk-review, create-final-design, consistency-gate-verification, dimensional-review-gate-check, structural-review, design-review), 6 under `skills/` (ai-tells, create-plan, execute-tracks, readability-feedback, review-plan, review-workflow-pr).
- **Actual behavior**: matches the track's in-scope set exactly; the named filename list in `## Interfaces and Dependencies` (lines 77-78) is complete and accurate.
- **Verdict**: CONFIRMED
- **Detail**: The "~17 files" figure resolves to exactly 17 once the `ai-tells` catalogue question (P-AITELLS) is decided.

#### P-PREAMBLE: 10 of 11 prompts carry the five-slug preamble; design-review.md does not
- **Track claim**: "the 11 `.claude/workflow/prompts/*.md` enumerations" each carry the five-slug preamble; design-review.md is "the exception in kind: besides the preamble, it holds the cold-read Human-reader rules" (track-2.md:9, :35).
- **Search performed**: `grep -rn 'AI-tell subset section slugs to apply' .claude/workflow/prompts/`; per-file `grep -c` on design-review.md.
- **Code location**: preamble present in 10 files (adversarial-review:43, consistency-gate-verification:25, consistency-review:96, create-final-design:16, dimensional-review-gate-check:30, review-gate-verification:24, risk-review:31, structural-gate-verification:25, structural-review:39, technical-review:31); design-review.md preamble count = 0.
- **Actual behavior**: design-review.md carries no preamble; it matches `Banned analysis patterns` only via `### Prose AI-tell additions` (`:198`) and the TOC summary (`:23`). Each of the 10 preambles contains the literal "the **five** AI-tell subset section slugs to apply are …", so each carries a numeric "five" to flip (step 1's "Flip any numeric 'five' → 'six'" applies to all 10).
- **Verdict**: PARTIAL
- **Detail**: The "11 prompts" / "besides the preamble" framing overcounts the preamble class by one and mischaracterizes design-review.md's shape. Produced T2.

#### P-DR: the rule's correct home in design-review.md is the Prose AI-tell additions block, not the Human-reader block
- **Track claim**: D2-1 + step 2: add `## Plain language` to "the cold-read Human-reader rules" (track-2.md:25, :35, :45).
- **Search performed**: Read design-review.md:120-217 (cold-read invocation + both addition blocks) and :455-462 (§ Tone and depth).
- **Code location**: `### Human-reader cold-read additions` @ design-review.md:169-184; `### Prose AI-tell additions` @ :186-217.
- **Actual behavior**: The Human-reader block (`:175-179`) checks audience-fit / glossary-introduction / why-before-what / navigability / explanatory register and runs on design kinds only (`:172`). The Prose AI-tell additions block (`:197-210`) checks over-dense vs `§ Banned analysis patterns` and too-terse vs `§ Orientation`, and runs on both `target=design` and `target=tracks` (`:189-195`). `## Plain language` is a clarity/word-choice rule in the same family as `§ Orientation`, so its home is the Prose AI-tell additions block.
- **Verdict**: WRONG
- **Detail**: The track targets the wrong block. The Human-reader block's design-kinds-only reach would also drop the `target=tracks` coverage the rule should have. Produced T1.

#### P-DR458: "the five Human-reader rules" at :458 are not the AI-tell subset
- **Track claim**: "design-review.md:458 references 'the five Human-reader rules'" as the rule's join site (track-2.md:35).
- **Search performed**: Read design-review.md:455-462; cross-read :169-184.
- **Code location**: design-review.md:458 (§ Tone and depth, first exception) and :181 (Reviewer tone note).
- **Actual behavior**: ":458" names "the five Human-reader rules" = audience-fit, glossary-introduction, why-before-what, navigability, explanatory register (the five bullets at :175-179). It is NOT the five-slug AI-tell subset. The same line's "second exception" already covers the Prose AI-tell additions checks and names "§ Banned analysis patterns, § Mechanism traces…, or § Orientation" — the correct extension point for a plain-language check.
- **Verdict**: WRONG
- **Detail**: Joining the Human-reader rules would force "five" → "six" at :181 and :458 for the wrong rule family. The plain-language extension belongs in the :458 "second exception" clause. Supports T1.

#### P-SKILLS: create-plan / execute-tracks / review-plan / review-workflow-pr carry a single clean subset enumeration with no numeric count word
- **Track claim**: "The 6 skills … cite the subset in their startup read-lists or house-style notes (create-plan/SKILL.md:23, execute-tracks/SKILL.md:23, review-plan/SKILL.md:31, review-workflow-pr/SKILL.md:45 …)" (track-2.md:37).
- **Search performed**: Read each skill's enumeration line; `grep -n 'AI-tell subset of'`.
- **Code location**: create-plan:23, execute-tracks:23, review-plan:31 (single-line); review-workflow-pr:42-45 (wrapped across four lines).
- **Actual behavior**: Each lists exactly the five slugs (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`) in a "follows the AI-tell subset of `house-style.md`: …" sentence with NO numeric "five" word — so step 1's count-flip clause does not apply to skills, only the slug append does. review-workflow-pr's "six sections" at :195/:214 are handoff-file sections, unrelated to the subset.
- **Verdict**: CONFIRMED
- **Detail**: These four are clean slug-append targets; line numbers in the track match (review-workflow-pr's :45 is the line carrying `### Em-dash discipline` / `## Orientation`, within the :42-45 block).

#### P-AITELLS: ai-tells/SKILL.md has a Catalogue lookups table, not a five-slug enumeration
- **Track claim**: "ai-tells/SKILL.md:20–:28 … cites the subset sections as a fingerprint→section catalogue (`## Catalogue lookups`, six sections, two outside the subset), not the five-slug preamble … confirm at Phase A whether the catalogue should gain a `## Plain language` row" (track-2.md:37; CR1 in plan, implementation-plan.md:137).
- **Search performed**: Read ai-tells/SKILL.md:19-38.
- **Code location**: `## Catalogue lookups` @ ai-tells/SKILL.md:19; rows at :23-:28.
- **Actual behavior**: Five mapping rows: Vocabulary→Banned vocabulary (:23), Structural→Structural rules + Banned sentence patterns (:24), Tone→Banned sentence patterns sub-bullets (:25), Content/analysis→Banned analysis patterns (:27), Too-terse/under-orientation→Orientation (:28). It maps fingerprints to sections (two outside the subset: Structural rules, and the Tone row's Banned sentence patterns is in-subset) and is not a five-slug preamble. There is no "five" count to flip.
- **Verdict**: CONFIRMED
- **Detail**: Phase-A confirmation question (CR1) stands. A `## Plain language` row keyed to a "plain-language / mid-level-English fingerprint" would parallel the Too-terse→Orientation row and is consistent with the subset flip; that is a decomposer/implementer judgment, recorded here as the open question the plan flagged, not a blocker.

#### P-GREP: the readability-feedback grep copy diverges from the live conventions.md §1.5 helper in two ways
- **Track claim**: "`readability-feedback/SKILL.md:54` … still lists the old four bare names, so the two helpers now diverge; this track syncs the copy to match … copy the live `conventions.md` helper verbatim" (track-2.md:39, :47).
- **Search performed**: Read readability-feedback/SKILL.md:51-55; `grep -n` for the live helper in conventions.md.
- **Code location**: copy @ readability-feedback/SKILL.md:54; live @ conventions.md:572.
- **Actual behavior**: Copy = `grep -rn 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` (BRE `\|`, four bare names). Live = `grep -rnE '## Orientation|## Plain language|§ Orientation|§ Plain language|Banned vocabulary|Banned sentence patterns|Banned analysis patterns|Em-dash discipline' .claude/ CLAUDE.md` (ERE `-rnE` + `|`, eight alternatives). Divergence is (a) BRE→ERE flag/syntax and (b) four→eight alternatives.
- **Verdict**: PARTIAL
- **Detail**: The track's "old four" claim is correct; the sync is verbatim-copyable. Pinning the exact target string in step 4 and the acceptance line makes the edit and its check unambiguous. Produced T3.

#### P-SYNCMAP: readability-feedback's own rule-sync map already routes density/clarity rules to the Prose AI-tell additions block
- **Track claim**: (implicit) D2-1 routes the plain-language rule to the Human-reader cold-read additions (track-2.md:25).
- **Search performed**: Read readability-feedback/SKILL.md:41-55 (`## Rule sync map`).
- **Code location**: readability-feedback/SKILL.md:47.
- **Actual behavior**: ":47" states: "`design-review.md` — a `### Human-reader cold-read additions` bullet … only if the rule is a design-doc-shape rule the cold-read reviewer must verify. A rule on prose density or terseness (the kind `## Orientation` and the over-dense AI-tells cover) instead joins the `### Prose AI-tell additions` block, which scans both `target=design` and `target=tracks` and carries its own `§ Tone and depth` evidence clause." This is the project's own authority and it routes a clarity rule away from the Human-reader block.
- **Verdict**: CONFIRMED
- **Detail**: Directly contradicts D2-1's chosen target. The same file this track edits already documents the correct placement. Reinforces T1.

#### P-COUNT: the file-count arithmetic resolves to ~17
- **Track claim**: "~17 files covering 11 workflow prompts and 6 skills" (implementation-plan.md:126; track-2.md:84).
- **Search performed**: Derived from P-INSCOPE, P-PREAMBLE, P-AITELLS.
- **Code location**: n/a (arithmetic over the in-scope set).
- **Actual behavior**: 17 edited files = 10 preamble-flip prompts + 1 design-review content edit + 6 skills. Within the 6 skills: 4 clean slug-appends (create-plan, execute-tracks, review-plan, review-workflow-pr), 1 catalogue-row question + grep sync (readability-feedback carries both the slug-family question via P-SYNCMAP routing and the grep sync), 1 catalogue-row question (ai-tells). Note readability-feedback has no five-slug preamble either; its in-scope edit is the :54 grep sync (T3) plus, if T1 is adopted, no change at :47 (it is already correct) — confirm whether :47's wording needs the `## Plain language` name added as an example.
- **Verdict**: CONFIRMED
- **Detail**: The "~17" figure is accurate; the per-class breakdown differs from the track's uniform "11 prompts / 6 skills" framing (see T2). No file is missing from or extra in the in-scope set.
