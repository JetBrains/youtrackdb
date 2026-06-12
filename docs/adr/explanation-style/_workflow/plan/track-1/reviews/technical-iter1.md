# Technical review — Track 1 (iteration 1)

- **role**: reviewer-technical
- **phase**: 3A
- **track**: Track 1: Conventions opt-out, Orientation rule, and the atomic subset sync
- **verdict**: PASS
- **findings**: 3
- **blockers**: 0

## Manifest

| id | sev | anchor | loc | cert | basis |
|---|---|---|---|---|---|
| T1 | should-fix | #t1-should-fix | `conventions.md:568` (§1.5 count word) | Premise: §1.5 "four Tier-B section names" count | A numeric "four" count sits inside §1.5 adjacent to the Tier-B table; the in-scope roster names the table row + the line-570 grep but not this count sentence, so a table-row-only flip leaves intra-§1.5 four-vs-five drift. |
| T2 | suggestion | #t2-suggestion | `ai-tells/SKILL.md:19-27` | Premise: ai-tells catalogue shape | The catalogue is a fingerprint→section map, not a closed-set four-name subset enumeration; classifying it as a "closed-set enumeration" flip site is imprecise (an Orientation row is coherent, but it is not subset-enumeration drift D1 governs). |
| T3 | suggestion | #t3-suggestion | `conventions.md` §1.5 Tier-B table row | Premise: §1.5 Tier-B row is a table cell | D2's multi-sentence code-comment restatement ("no out-of-file assumptions; gloss the project-specific entity") has no natural home inside a table-cell "Sections that apply" list; decomposition should place it in §1.5 prose adjacent to the table, not in the cell. |

## evidence_base

All load-bearing technical claims verified directly against the live `.claude/**` tree (Markdown / shell / Python paths, section anchors, line numbers, unique literals — full reference accuracy, no Java symbols, so grep + Read suffice; no PSI caveat applies). The §1.7 opt-out posture (D6) is honored: this plan is treated as workflow-modifying for criteria purposes, the five prose criteria supersede the Java/WAL/crash lenses, and the absent staging marker is the acknowledged D6 posture, not a finding. Core inventory (54 governance-grep files, 30 narrow blurbs, 11 chat blurbs, the two narrow-grep-miss sites, the three individual-section non-flip files), every file:line anchor in `## Interfaces and Dependencies`, all three criteria-switch blocks, the §1.7(b) forfeiture framing, and D3's three reconciliation targets in `house-style.md` all CONFIRMED. The three findings are precision/completeness gaps in the flip roster, not feasibility blockers.

## Evidence base

#### Premise: governance grep returns 54 files; sub-counts hold at 30/11
- **Track claim**: "the governance grep ... returns 54 files ... 30 files carry the 'four banned-section heading slugs' blurb; 11 carry the chat 'follows the AI-tell subset of' blurb" (`## Context and Orientation`, D1).
- **Search performed**: `grep -rln 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md | wc -l`; `grep -rln 'banned-section heading slugs' ... | wc -l`; `grep -rln 'AI-tell subset of' ... | wc -l`.
- **Code location**: live tree.
- **Actual behavior**: governance grep = 54; narrow `banned-section heading slugs` = 30; chat `AI-tell subset of` = 11. Remainder = 13 files (54 − 30 − 11), enumerated below.
- **Verdict**: CONFIRMED

#### Premise: the 13-file remainder decomposes exactly as the track claims
- **Track claim**: remainder = three canonical sites (`house-style.md`, `house-conversation.md`, `conventions.md §1.5`), the hook, `test_house_style_hook.py`, the two governance greps, the `ai-tells` catalogue, the two narrow-grep-miss sites (`commit-conventions.md`, `implementer-rules.md`), and three files that cite individual `§` sections not the closed set (`review-workflow-writing-style.md`, `design-mechanical-checks.py`, `design-document-rules.md`).
- **Search performed**: `comm -23` of the governance-grep file set against the union of the narrow-30 and chat-11 sets.
- **Code location**: the 13 remainder files are `review-workflow-writing-style.md`, `house-style-write-reminder.sh`, `house-conversation.md`, `house-style.md`, `design-mechanical-checks.py`, `test_dsc_ai_tell.py`, `test_house_style_hook.py`, `ai-tells/SKILL.md`, `readability-feedback/SKILL.md`, `commit-conventions.md`, `conventions.md`, `design-document-rules.md`, `implementer-rules.md`.
- **Actual behavior**: every remainder file the track names is present. `review-workflow-writing-style.md` (cites `§ Banned vocabulary` / `§ Banned sentence patterns` / `§ Elegant variation` individually, lines 29/69/85/187), `design-mechanical-checks.py` (cites `§ Banned sentence patterns` / `§ Em-dash discipline` per-pattern, lines 1775/1776/1954), and `design-document-rules.md:284` (the `dsc-ai-tell` row cites `§ <Section>` per pattern) all cite individual sections, never the four-name closed set — correctly excluded as flip sites. `test_dsc_ai_tell.py` is a Track-2-adjacent test that matches the grep via regex fixtures; it is out of Track 1 scope (the track scopes `test_dsc_ai_tell.py` to Track 2) and is not a closed-set enumeration.
- **Verdict**: CONFIRMED

#### Premise: the two narrow-grep-miss sites are four-name closed-set enumerations
- **Track claim**: `commit-conventions.md:191-194` (line-wraps the literal) and `implementer-rules.md:1102-1105` (variant phrasing "the four section slugs that make up the Tier-B AI-tell subset") are four-name closed sets the narrow grep misses; `review-workflow-pr/SKILL.md:44-45` hard-wraps the chat-blurb find string.
- **Search performed**: Read of all three ranges; `grep -ln 'banned-section heading slugs'` against `commit-conventions.md` and `implementer-rules.md` (both NOT matched → confirms "misses").
- **Code location**: `commit-conventions.md:191-194`, `implementer-rules.md:1102-1105`, `review-workflow-pr/SKILL.md:44-48`.
- **Actual behavior**: `commit-conventions.md` reads "The four banned-section / heading slugs to apply are `## Banned vocabulary`, / `## Banned sentence patterns`, `## Banned analysis patterns`, and / `### Em-dash discipline`." — a four-name closed set hard-wrapped across lines, missed by the single-line narrow grep. `implementer-rules.md` reads "the four section slugs that make up / the Tier-B AI-tell subset are `## Banned vocabulary`, ..." — variant phrasing, four-name closed set. `review-workflow-pr/SKILL.md:44-45` reads "follows the AI-tell subset of / `house-style.md`: `## Banned vocabulary`, ..." — the chat-blurb literal wrapped across the line break. All three match the track's characterization.
- **Verdict**: CONFIRMED

#### Premise: `house-style.md` line anchors for D3's three reconciliations
- **Track claim**: `## Banned analysis patterns` at 105, `### Mechanism traces` at 360, `## Document-shape rules` at 377 with scoping at 379, `### Why-before-what` at 403, `### Explanatory register` at 427, `## Self-check` at 433; line ~20 carries the "Four readers" / "four AI-tell sections" count.
- **Search performed**: `sed -n` reads of each line; Read of 403-449.
- **Code location**: `house-style.md` lines 20, 105, 360, 377, 379, 403, 427, 431, 433, 444.
- **Actual behavior**: every anchor lands exactly. Line 379 scopes the document-shape family to "the BLUF rule alone" for issue/PR/status prose (D3's reconciliation (1) target — confirmed). Line 431 inside `### Explanatory register` marks too-terse prose "a finding under § Why-before-what" — the design-only finding category D3 reconciliation (2) must replace with Orientation's own. `## Self-check` item 8 (line 444) is bracketed "Document shape (design/ADR only)" and lists "explanatory register" — the bracket D3 reconciliation (3) must move the Orientation entry out of. `### Explanatory register` (427-431) carries the mechanism-overview nuance and "completeness bar is the mid-level reader" content D3 keeps. All three reconciliation targets exist as described.
- **Verdict**: CONFIRMED

#### Premise: the three criteria-switch blocks resolve at the claimed lines
- **Track claim**: `technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282` each carry a "Workflow-machinery criteria (workflow-modifying plans)" block gated on the `§1.7(b)` marker, with a separate "Staged-read precedence" block above; only the criteria block gets the opt-out-marker OR-extension.
- **Search performed**: `grep -n` for both block headers across the three prompts; `sed -n` reads.
- **Code location**: `technical-review.md` :111 (staged-read) / :113 (criteria); `risk-review.md` :108 / :110; `adversarial-review.md` :280 / :282.
- **Actual behavior**: all six blocks present at the exact claimed lines, byte-identical wording across the three prompts. The staged-read block reads "taking the staged copy under `_workflow/staged-workflow/` when present and the live file otherwise" — under the opt-out (no staging) this already defaults to live, so leaving it gated on the workflow-modifying marker is correct, as the track states. The criteria block is the one that re-points the prose lenses and is the correct OR-extension target.
- **Verdict**: CONFIRMED

#### Premise: §1.7(b) calls marker-omission "forfeiture", validating the need for a distinct opt-out marker (D5/D6)
- **Track claim** (D5): "unamended `§1.7(b)` calls marker-omission *forfeiture*, not an opt-out"; (D6) shape (ii) carries a distinct opt-out marker so the staging consumers default to live with no edits and no bootstrap deadlock.
- **Search performed**: Read of `conventions.md` §1.7(b) 891-931 and §1.7(c) 933-966.
- **Code location**: `conventions.md:923-927` and `:958-962`.
- **Actual behavior**: §1.7(b) lines 923-927 read "Plans that touch `.claude/workflow/**` ... but omit the marker **forfeit the staging mechanism**: the implementer enforcement gate stays inactive, writes land on live paths, and the drift gate flags the branch's own authoring." Confirms the forfeiture framing. §1.7(c) lines 958-962 confirm the inverse risk D6 sidesteps: a workflow-modifying marker present (even with no staged content) keeps "the gate active throughout execution" and routes writes into the staged subtree — exactly the routing D6's distinct opt-out marker avoids by NOT carrying the workflow-modifying marker. The two-signal detection (enforcement gate + Phase-4 promotion guard) confirms D6's claim that with the workflow-modifying marker absent, every staging consumer defaults to live (write live, no staged delta, promotion guard finds no `.claude/` under `staged-workflow/` and skips). The "two roles" grouping in D6 (staging mechanism vs reviewer-criteria re-pointing) is internally coherent: §1.7 itself names two staging signals, and the criteria-switch is a third consumer living in the review prompts keyed off the same marker; D6 groups the first three under "the staging mechanism" and treats criteria-switch as the second role — a defensible grouping, no contradiction.
- **Verdict**: CONFIRMED

#### Premise: `track-code-review.md:250-260` is staging-delta prep, inert without the marker (D6 A15)
- **Track claim** (D6): "Note `track-code-review.md:250-260` is staging-delta **prep** (inert without the marker), not a criteria switch — Phase-C dimensional prose coverage is diff-keyed via `review-agent-selection.md`, so it needs no marker."
- **Search performed**: `sed -n '248,262p'`; `grep -n 'workflow-modifying\|staged-workflow\|§1.7'`.
- **Code location**: `track-code-review.md:250-271` (step 8) and `:427` (a separate Staged-read precedence block).
- **Actual behavior**: step 8 is "Stage a `diff <live> <staged>` delta for freshly-created staged copies (workflow-modifying plans)" gated on the `§1.7(b)` marker — it enumerates new-file adds under the anchored staged prefix and diffs against the live counterpart. With the workflow-modifying marker absent there are no staged copies, so the step is inert. It is delta-prep, not a criteria re-point. `track-code-review.md:427` carries a Staged-read precedence block (same shape as the three review prompts) that also stays gated on the workflow-modifying marker — consistent with the track's "criteria block only" extension scope. Confirms A15.
- **Verdict**: CONFIRMED

#### Premise: design.md cross-reference anchors resolve (deferred-count posture)
- **Track claim**: each D-record's `**Full design**` points at a design.md section; the Plan Review note defers the `~50`→54 count reconciliation to Phase 4 and leaves the `§"Subset sync across ~50 sites"` anchor verbatim.
- **Search performed**: `grep -n 'Subset sync across\|The Orientation rule\|The §1.7 opt-out\|Over-dense prose'` against design.md.
- **Code location**: `design.md` `## The Orientation rule` :141, `## Over-dense prose enforcement` :195, `## Subset sync across ~50 sites` :249, `## The §1.7 opt-out` :293.
- **Actual behavior**: all four section titles exist and match the anchors quoted in the track/plan. The verbatim `~50` anchor correctly matches the frozen design.md title; deferring the as-built `54` count to `design-final.md` is the right posture (design.md is frozen during execution). No broken cross-reference.
- **Verdict**: CONFIRMED

#### Premise: the hook `tier_b_body` and `test_house_style_hook.py` are simple subset-list carriers
- **Track claim**: the hook `tier_b_body` flips four→five + gains the code-comment restatement; `test_house_style_hook.py:694-697` updates its pinned subset list.
- **Search performed**: `grep -n 'tier_b_body\|TIER_B\|Orientation'`; reads of both.
- **Code location**: `house-style-write-reminder.sh:262`; `test_house_style_hook.py:693-698` (`TIER_B_HEADINGS` list).
- **Actual behavior**: `tier_b_body` (line 262) reads "... the **four** sections in ... house-style.md: § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline (H3 nested ...)." — carries both the four-name enumeration AND the count word "four", both of which the track's roster captures ("tier_b_body four→five"). `TIER_B_HEADINGS` (test, :694-697) is a 4-element list asserted present in `tier_b_body` line-by-line; bumping it to five and adding `## Orientation` to the hook keeps the test passing. The code-comment restatement is additional prose that does not break the per-heading-present assertion.
- **Verdict**: CONFIRMED

#### Premise: `house-conversation.md` carries the chat four→five count
- **Track claim**: "house-conversation.md's 'four → five' count bumps".
- **Search performed**: `grep -n 'four\|Banned\|Em-dash\|AI-tell subset\|Orientation'`.
- **Code location**: `house-conversation.md:21`.
- **Actual behavior**: line 21 reads "Apply these **four** sections of `.claude/output-styles/house-style.md`" then lists the four (lines 23-26). The track names this count in step 4 and the in-scope roster ("chat register four→five + the 'four → five' count"). Confirmed and captured.
- **Verdict**: CONFIRMED

#### Premise: governance-grep numeric "four" count words across the whole tree (completeness audit)
- **Track claim** (implicit, via the atomic-flip invariant): "no site reads four-of-five after Phase C."
- **Search performed**: `grep -rn 'four AI-tell\|four banned\|four section slugs\|four Tier-B\|four-of-five\|reuses the four\|four → five'` over `.claude/` + `CLAUDE.md`, excluding `_workflow/`.
- **Code location**: numeric "four" count words live at `house-style.md:20` ("four AI-tell sections"), `house-conversation.md:21` ("four sections"), `house-style-write-reminder.sh:262` ("four sections"), `conventions.md:568` ("The four Tier-B section names"), plus the 30 narrow-blurb sites (each says "four banned-section heading slugs" / "four section slugs") which the canonical reworded sentence replaces, and `step-implementation.md:1038` (a narrow-30 member — confirmed via `grep -ln`).
- **Actual behavior**: three of the four standalone count words are explicitly in the roster (`house-style.md:20` "line-~20 count"; `house-conversation.md:21` "four → five count"; hook "tier_b_body four→five"). The 30 blurb count words are subsumed by the canonical-reword. `step-implementation.md` is confirmed inside the narrow-30 set, so its "four" is covered. The one count word NOT separately named in the roster is `conventions.md:568` "The four Tier-B section names are stable headings" — see T1.
- **Verdict**: PARTIAL
- **Detail**: see T1; the `conventions.md:568` count is the only numeric "four" the in-scope roster does not call out explicitly.

#### Premise: the `ai-tells` catalogue is a fingerprint→section map, not a closed-set enumeration
- **Track claim**: the `ai-tells` catalogue is among "every closed-set enumeration" and "gains an Orientation row" (acceptance + Plan of Work step 4).
- **Search performed**: Read of `ai-tells/SKILL.md:1-45`; `grep -n 'banned-section\|AI-tell subset\|four section\|four banned'` (no closed-set phrasing present).
- **Code location**: `ai-tells/SKILL.md:19-27` (`## Catalogue lookups`).
- **Actual behavior**: the catalogue maps fingerprint *categories* to house-style sections ("Vocabulary fingerprints → `§ Banned vocabulary`", "Content and analysis tells → `§ Banned analysis patterns`"), referencing the four banned sections individually (and also `§ Structural rules`, `§ Punctuation and typography` — sections outside the four-name subset). It does not enumerate the four as a closed AI-tell-subset set. It matches the governance grep only because it cites the individual section names. Adding an Orientation row ("too-terse fingerprints → `§ Orientation`") is coherent, but classifying it as a closed-set subset enumeration is imprecise — see T2.
- **Verdict**: PARTIAL
- **Detail**: see T2.

## Findings

### T1 [should-fix]
**Certificate**: Premise "governance-grep numeric 'four' count words across the whole tree (completeness audit)"; Premise "`house-style.md` line anchors for D3's three reconciliations".
**Location**: Track `## Interfaces and Dependencies` conventions.md in-scope line ("`§1.5` Tier-B row + code-comment restatement + the line-570 governance grep") and `## Plan of Work` step 3; source `conventions.md:568`.
**Issue**: §1.5 carries a numeric count sentence at line 568 — "The **four** Tier-B section names are stable headings after YTDB-836; a future rename ... requires updating every pointer in the same commit." This count word sits inside §1.5, between the Tier-B table row (which the flip bumps to five sections) and the line-570 governance grep (which the flip extends with `Orientation`). The track's conventions.md in-scope item names the table row and the line-570 grep but not the ":568 'four Tier-B section names'" count. A literal table-row-only edit leaves line 568 reading "four Tier-B section names" while the row above it lists five — an intra-§1.5 four-vs-five inconsistency, exactly the drift D1 forbids and the kind `review-workflow-consistency` flags. Three of the four standalone count words in the tree (`house-style.md:20`, `house-conversation.md:21`, the hook `tier_b_body`) ARE explicitly named in the roster; `conventions.md:568` is the gap.
**Proposed fix**: Add `conventions.md:568` "four Tier-B section names → five" to the track's conventions.md in-scope item and to Plan-of-Work step 3 (the §1.5 edits), so decomposition bumps the count word alongside the table row and the governance grep. One-token edit ("four" → "five"); the rest of the sentence is unaffected.

### T2 [suggestion]
**Certificate**: Premise "the `ai-tells` catalogue is a fingerprint→section map, not a closed-set enumeration".
**Location**: Track `## Validation and Acceptance` (first bullet, "the `ai-tells` catalogue" listed among "every closed-set enumeration") and `## Plan of Work` step 4 ("the `ai-tells` catalogue gains an Orientation row").
**Issue**: `ai-tells/SKILL.md:19-27` is a fingerprint-category→house-style-section catalogue, not a closed-set enumeration of the four-name AI-tell subset. It references the four banned sections individually and also references `§ Structural rules` and `§ Punctuation and typography`, which are not subset members. Listing it under "every closed-set enumeration ... names `## Orientation`" in the acceptance check conflates two different site kinds: closed-set subset enumerations (which D1's atomic flip and `review-workflow-consistency` govern) and reference catalogues (which carry no four-vs-five count to drift). Adding an Orientation row to the catalogue is sound and worth doing — too-terse prose is a real fingerprint the `ai-tells` audit should catch — but it is not part of the subset-enumeration-drift class.
**Proposed fix**: In the acceptance check, separate the `ai-tells` catalogue row from the "every closed-set enumeration names Orientation" assertion — phrase it as "the `ai-tells` catalogue gains a too-terse-fingerprint → `§ Orientation` row" rather than treating it as a four→five subset flip. No mechanical-count assertion applies to it (it never said "four"). This keeps the `review-workflow-consistency` acceptance bullet honest about which sites carry a countable subset enumeration.

### T3 [suggestion]
**Certificate**: Premise "the three criteria-switch blocks resolve at the claimed lines" (adjacent — §1.5 structure); Premise "governance-grep numeric 'four' count words" (the §1.5 table shape).
**Location**: Track `## Plan of Work` step 3 ("Add `## Orientation` to the `§1.5` Tier-B row ... with the code-comment restatement") and `## Interfaces and Dependencies` conventions.md item ("`§1.5` Tier-B row + code-comment restatement").
**Issue**: §1.5's Tier-B membership is a Markdown table row whose "Sections that apply" cell is a comma-separated list of `§` names (`conventions.md:565`). D2's code-comment restatement is multi-sentence prose ("rationale comments must not assume context *outside the file* ... and must gloss the project-specific entity the rationale turns on"). A table cell is the wrong home for that prose — it would either bloat the cell or force the restatement to be lost. The track says the restatement goes "to the `§1.5` Tier-B row," which is underspecified for decomposition.
**Proposed fix**: Decomposition should place the code-comment restatement in §1.5 *prose adjacent to the table* (a sentence or short paragraph after the table, near the existing "four Tier-B section names are stable" note), and add only `§ Orientation` to the table cell's section list. Name this split in the track's conventions.md in-scope item so the step author does not try to cram the restatement into the cell.
