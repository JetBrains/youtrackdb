<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: CR1, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/design.md:31", anchor: "### CR1 ", cert: C1, basis: "Overview + track-1 Context claim all four consumers inherit rule deletion at once, but the dsc-ai-tell checker holds independent regexes needing explicit deletion"}
  - {id: CR2, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/design.md:630", anchor: "### CR2 ", cert: C7, basis: "design names the dsc test suite as an existing TL;DR-shape-pinning site, but no existing test pins the TL;DR shape; track-1 correctly plans a new test"}
evidence_base: {section: "## Evidence base", certs: 20, matches: 17}
cert_index:
  - {id: C1, verdict: MISMATCHES, anchor: "#### C1 "}
  - {id: C7, verdict: PARTIAL, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

### CR1 [should-fix]
**Certificate**: C1 (with C18 corroboration in the plan)
**Location**: `design.md` § Overview (lines 28-33), echoed in `track-1.md` § Purpose / Big Picture (line 15) and § Context and Orientation (line 111); code at `.claude/scripts/design-mechanical-checks.py`.
**Issue**: The design's Overview claims that all four `house-style.md` consumers — explicitly including "the `dsc-ai-tell` regex checker" — "cite it by section and restate no rule," so "deleting a rule at the source propagates the removal to all of them at once." That is accurate for the three prose consumers (`ai-tells/SKILL.md`, `prompts/design-review.md`, `house-conversation.md`) but false for the `dsc-ai-tell` checker: `design-mechanical-checks.py` holds the rules as independent hard-coded regexes (`NEGATIVE_PARALLELISM_RE`, `NEGATIVE_PARALLELISM_TRAILING_RE`, `HYPHENATED_PAIR_CLUSTER_RE`, `_title_case_violation`). Deleting the prose rule in `house-style.md` does not touch those regexes; they must be deleted explicitly along with their assertions in `test_dsc_ai_tell.py` and lines in `dsc-ai-tell-fixture.md`. The design contradicts itself: § Class Design (lines 118, 129-131) correctly labels the `HS → DSC` edge a "regex subset" and calls the removal "a two-file edit," and § "Removing the disguise-only style rules" (lines 251-270) devotes a paragraph to the regex/test/fixture-are-one-unit coupling. `track-1.md`'s D1, Plan of Work (line 124), Validation, and Invariants all correctly require the explicit dsc deletion, so the operative instructions are right; only the high-level "inherit at once / for free" framing in the Overview and the track's Purpose/Context is inaccurate.
**Evidence**: `house-style.md § What this style governs` (line 20) is the source of the simplification ("Four readers consume these rules without restating them: ... the `dsc-ai-tell` rule ... (regex detection)"), and the design echoes it verbatim. But the dsc regexes are code realizations, verified present and independent in `design-mechanical-checks.py` (C3), and are pinned by `test_dsc_ai_tell.py` `PATTERN_SIGNATURES` + `dsc-ai-tell-fixture.md` positive-case blocks (C9). No mechanism exists for a `house-style.md` text deletion to remove a Python regex.
**Proposed fix**: In the design Overview (and track-1 Purpose/Context), split the consumer set: the three prose consumers inherit a rule-text deletion by citation, while the `dsc-ai-tell` checker realizes a regex *subset* and requires an explicit same-change deletion of the regex, its assertion, and its fixture line. (design.md is frozen, so if the Overview is left as an accepted high-level simplification, record the correction as a Phase-4 `design-final.md` reconciliation item; the track's operative sections already carry the correct scope.)
**Classification**: design-decision
**Justification**: Design ↔ code mismatch on a frozen-design current-state claim where the fix has more than one plausible rendering (correct the Overview vs. accept the simplification since Class Design carries the nuance) and touches the frozen `design.md`; per §Classification rules "Multiple plausible fix renderings … escalate" and "when in doubt, choose design-decision."

### CR2 [suggestion]
**Certificate**: C7
**Location**: `design.md` § "Renaming TL;DR to Summary" (line 630), the TL;DR rename-site list.
**Issue**: The rename-site list is introduced as "every site that hard-codes the `TL;DR` spelling, enumerated by grep over `.claude/`" and its other entries are existing line-numbered sites. The final entry, "the `dsc` test suite where it pins the TL;DR shapes," reads as an existing site to edit, but no existing test pins the section TL;DR-shape acceptance: `section_has_tldr`, `SHAPE_EXEMPT_SECTION_NAMES`, and `MANDATORY_OR_FORM_SUBHEADINGS` have no dedicated test. The `test_design_mechanical_checks_d11.py` fixtures merely *use* `**TL;DR.**` as valid section content (a precondition, not a shape assertion), and the `dsc-ai-tell-fixture.md` uses "TL;DR:" as prose. `track-1.md` item 10 handles the real work correctly — it plans a *new* shape test ("new file or an extension; Phase A decides placement") — so the practical risk is low.
**Evidence**: A repo-wide grep of `.claude/scripts/tests/` for `section_has_tldr` / `SHAPE_EXEMPT` / `MANDATORY_OR_FORM` / TL;DR-shape assertions returns no test that pins the shape; the only TL;DR occurrences in tests are legacy-spelling fixture content (C7).
**Proposed fix**: Reword the design entry to forward-looking phrasing ("the `dsc` shape test to be added, which will pin the `### Summary` / `TL;DR` shapes") to match `track-1.md` item 10, or drop the "where it pins the TL;DR shapes" existing-site framing; alternatively record as a Phase-4 reconciliation note since track-1 already covers the actual test work.
**Classification**: design-decision
**Justification**: Design current-state imprecision on the frozen `design.md`; the correction is a judgment call (reword vs. leave as covered by the plan) rather than a single unambiguous rename, so per §Classification rules "when in doubt, choose design-decision."

## Evidence base

Tooling note: this is a workflow-machinery change — the "code" cross-checked is Markdown and Python under `.claude/**`, so every reference was verified with Read/Grep against the live tree (per §1.7(d) the staged subtree `_workflow/staged-workflow/` does not exist yet, so all `.claude/**` reads resolve to the live file). No Java symbols are involved; no PSI/grep reference-accuracy caveat applies. Ledger confirms `design_gate=yes` (both design-half axes run) and `s17=staged`; the plan is a single track with no `implementation-plan.md`, so the PLAN ↔ CODE axis lightens to the track-vs-code reference check.

### DESIGN ↔ CODE

#### C1 — house-style.md → consumers propagation relationship
- **Document claim**: design.md:28-33 — the four consumers (incl. the `dsc-ai-tell` regex checker) "cite it by section and restate no rule," so "deleting a rule at the source propagates the removal to all of them at once."
- **Search performed**: Read house-style.md § What this style governs (lines 8-20); Read design-mechanical-checks.py symbol region; Read design.md § Class Design (118-133) and § Removing (251-276).
- **Code location**: `house-style.md:20` (source of the framing); `design-mechanical-checks.py:103,127,191,1734` (independent regexes).
- **Actual signature/role**: The dsc checker holds the rules as hard-coded Python regexes; a `house-style.md` prose deletion cannot alter them. design.md's own Class Design labels the `HS → DSC` edge a "regex subset" and the removal "a two-file edit."
- **Verdict**: MISMATCHES
- **Detail**: The Overview lumps the regex-realizing dsc checker with the by-citation consumers; contradicts Class Design + Removing sections. → CR1.

#### C2 — house-style.md line-cited anchors
- **Document claim**: design.md cites writer persona `:42`, reader floor `:6`, § Orientation exemplar `:70-74`, § Banned sentence patterns `:100/:101/:103`, § Hyphenated word-pair overuse `:262`, § Curly quotes `:273`, § Title Case headings forbidden `:304`, § Navigability, § BLUF lead.
- **Search performed**: `sed -n` on each cited line; `grep -nE '^#{1,3} '` for headings.
- **Code location**: house-style.md — all lines/headings as cited.
- **Actual signature/role**: :42 "Match a senior engineer writing to peers"; :6 mid-level reader floor; :70-74 before/after orientation exemplar; :100 Negative parallelism; :101 Roundabout negation; :103 Closing phrases; :262/:273/:304 the three named `###` sections; § BLUF lead at :22; § Navigability at :379.
- **Verdict**: MATCHES

#### C3 — design-mechanical-checks.py symbols and line numbers
- **Document claim**: `NEGATIVE_PARALLELISM_RE:103`, `NEGATIVE_PARALLELISM_TRAILING_RE:127`, `HYPHENATED_PAIR_CLUSTER_RE:191`, `_title_case_violation:1734`, `section_has_tldr:708`, `SHAPE_EXEMPT_SECTION_NAMES:54` (Part-level `"TL;DR"` entry), `MANDATORY_OR_FORM_SUBHEADINGS:62`.
- **Search performed**: `grep -nE` for each symbol; Read lines 49-73 and 708-737.
- **Code location**: all seven at the cited lines; `SHAPE_EXEMPT` set opens at :49 with `"TL;DR"` at :54; `MANDATORY_OR_FORM` opens at :62 containing `"tl;dr"` at :63.
- **Actual signature/role**: matches. section_has_tldr currently accepts `**TL;DR.**` and `### TL;DR` (gaining `### Summary` is target-state, not flagged).
- **Verdict**: MATCHES

#### C4 — agent model pins (current state)
- **Document claim**: design.md roster — design-author/readability-auditor/comprehension-review on Opus (before), absorption-check/fidelity-check on Sonnet (unchanged).
- **Search performed**: `grep -iE '^model:'` on the five agent files.
- **Code location**: `.claude/agents/{design-author,readability-auditor,comprehension-review}.md` = `opus`; `{absorption-check,fidelity-check}.md` = `sonnet`.
- **Verdict**: MATCHES
- **Detail**: The Sonnet target for the two readers is target-state (the track's change) — correctly NOT flagged per the intent-axis pre-screen.

#### C5 — edit-design phase1-creation loop (Workflow sequenceDiagram)
- **Document claim**: design.md § Workflow — author drafts, dual-clean inner loop (readability-auditor + absorption-check) per round, comprehension-review gate once at end.
- **Search performed**: `grep -niE 'phase1-creation|readability-auditor|comprehension-review|absorption-check|design-author'` on edit-design/SKILL.md.
- **Code location**: edit-design/SKILL.md:452-475, 507-509.
- **Actual signature/role**: creation kinds run readability-auditor + the round's second check (absorption-check for phase1-creation), then the cold comprehension-review gate once — matches the diagram's flow structure.
- **Verdict**: MATCHES

#### C6 — Class Design mermaid nodes exist
- **Document claim**: the flowchart edits house-style.md, house-conversation.md, ai-tells/SKILL.md, prompts/design-review.md, design-mechanical-checks.py, review-workflow-writing-style.md, test_dsc_ai_tell.py + fixture, design-document-rules.md, CLAUDE.md.
- **Search performed**: `[ -f ]` existence check on all nodes.
- **Code location**: all present.
- **Verdict**: MATCHES

#### C7 — "dsc test suite where it pins the TL;DR shapes"
- **Document claim**: design.md:630 lists the dsc test suite among existing sites that hard-code the TL;DR spelling.
- **Search performed**: `grep -rniE 'tl;dr|section_has_tldr|mandatory_or_form|shape_exempt|### summary'` across `.claude/scripts/tests/`.
- **Code location**: no test asserts the section TL;DR-shape acceptance; only legacy `**TL;DR.**` fixture content (d11 fixtures) and one "TL;DR:" prose line in dsc-ai-tell-fixture.md.
- **Verdict**: PARTIAL
- **Detail**: The shape check is untested today; track-1 item 10 correctly plans a new test. → CR2.

### PLAN ↔ CODE (single-track: track-vs-code reference check only)

#### C8 — all track-1 in-scope files exist
- **Document claim**: track-1.md § Interfaces and Dependencies lists 19 in-scope items (item 10 is a to-be-created test).
- **Search performed**: `[ -f ]` on all named live paths.
- **Code location**: all 20 existing files present (item 10 is a new file — target-state, correctly not a phantom).
- **Verdict**: MATCHES

#### C9 — dsc regex/test/fixture "one unit" coupling
- **Document claim**: track-1 D1 + Plan of Work + Validation — the three removed regexes each have assertions in test_dsc_ai_tell.py and lines in dsc-ai-tell-fixture.md.
- **Search performed**: Read test_dsc_ai_tell.py PATTERN_SIGNATURES (70-93); grep fixture positive-case headings.
- **Code location**: PATTERN_SIGNATURES asserts negative-parallelism, negative-parallelism-trailing, title-case-heading, hyphenated-pair-cluster; fixture has "### Negative parallelism" (39), "### Title Case Demo Heading" (46), "### Hyphenated pair cluster" (60), trailing-negation positive case (69).
- **Verdict**: MATCHES

#### C10 — TL;DR line-cited rename sites
- **Document claim**: comprehension-review.md:34, planning.md:774, create-final-design.md:187, review-workflow-pr/SKILL.md:114, conventions.md:149, edit-design ~5 sites, design-document-rules.md shapes/templates, design-review.md structural finding + TOC row.
- **Search performed**: `sed -n` per cited line; `grep -niE 'tl;dr'` per file.
- **Code location**: every cited line carries TL;DR; edit-design has exactly 5 TL;DR sites (223,306,372,634,1239); design-review.md has TL;DR in TOC row (26,308) and structural checks (323,407); design-document-rules.md has 18 TL;DR references incl. per-section shape (284) and templates (67).
- **Verdict**: MATCHES

#### C11 — review-workflow-writing-style.md current criteria
- **Document claim**: track-1 item 6 — currently carries removed-rule criteria (negative parallelism, roundabout, closing phrases, Title/sentence case) + BLUF criteria + adjective-triads axis.
- **Search performed**: `grep -niE` for the removed rules, BLUF, adjective triads.
- **Code location**: removed rules at :29,:34,:71,:89,:188; BLUF at :28,:78,:119; adjective triads at :200.
- **Verdict**: MATCHES

#### C12 — house-conversation.md mirrored disguise lines + reader
- **Document claim**: track-1 item 2 — mirrors negative parallelism / roundabout / closing phrases; chat reader is a senior engineer.
- **Search performed**: `grep -niE` disguise-rule names; `sed -n '7p'`.
- **Code location**: :23 lists "negative parallelism, roundabout negation, throat-clearing, closing connectives, trailing hedges, prompt-restating"; :7 "senior YouTrackDB engineer". No hyphenated/curly/Title-Case mirror — consistent with the design removal table.
- **Verdict**: MATCHES

#### C13 — ai-tells/SKILL.md always-loaded description
- **Document claim**: track-1 acceptance — description names negative parallelism, Title Case headings, closing phrases; "adjective triads" also present (stays).
- **Search performed**: Read frontmatter (lines 1-4); grep body.
- **Code location**: description line 3 names all three removed rules + "adjective triads"; body catalogue tone row at :24.
- **Verdict**: MATCHES

#### C14 — CLAUDE.md § Writing Style parenthetical
- **Document claim**: track-1 item 19 — the § Writing Style negative-parallelism parenthetical.
- **Search performed**: `grep -niE 'negative parallelism' CLAUDE.md`.
- **Code location**: CLAUDE.md:93 — the "It's not X — it's Y" negative-parallelism parenthetical.
- **Verdict**: MATCHES

#### C15 — test_design_mechanical_checks_d11.py as both-spellings model
- **Document claim**: track-1 item 10 / D4 — new shape tests "modeled on `test_design_mechanical_checks_d11.py`" (the D11 both-spellings precedent).
- **Search performed**: Read the test docstring (lines 1-40).
- **Code location**: the file validates the References→Decisions & invariants footer rename with both spellings accepted (backward-compatible) — the exact precedent D4 invokes.
- **Verdict**: MATCHES

#### C16 — ExecPlan 15-section template reference
- **Document claim**: track-1:113 / design.md:320 — the "15-section per-track execution-plan template defined in `conventions-execution.md §2.1`".
- **Search performed**: `grep -nE` in conventions-execution.md.
- **Code location**: conventions-execution.md:28 § 2.1; TOC rows at :7,:9 confirm the 15-section shape (12 ExecPlan + Invariants & Constraints + Episodes + Base commit).
- **Verdict**: MATCHES

#### C17 — house-style self-check BLUF item (by-name anchor)
- **Document claim**: design.md:698-699 / track-1 item 1 — the self-check item requiring the first paragraph to state the decision or symptom directly (anchored by name because the removals renumber the list).
- **Search performed**: Read § Self-check (401-end).
- **Code location**: house-style.md self-check item 9 "BLUF. The first paragraph states the decision or symptom directly."
- **Verdict**: MATCHES

### DESIGN ↔ PLAN

#### C18 — consumer-propagation claim recurs in the plan
- **Document claim**: track-1 § Purpose (15) "the four citing consumers inherit the deletion for free"; § Context (111) "deleting a rule at the source removes it from all four at once."
- **Search performed**: Read track-1 lines 5-15, 111.
- **Code location**: track-1.md:15, :111.
- **Verdict**: MISMATCHES
- **Detail**: The plan faithfully echoes the design Overview's imprecision (C1); the dsc checker is not a for-free-inheritance consumer. Corroborates CR1; the plan and design agree with each other but both diverge from the code architecture, which is why the finding is filed at the design source rather than as a design↔plan disagreement.

#### C19 — Decision Log "Full design" section references resolve
- **Document claim**: track-1 D1-D10 each cite a `design.md §"..."` section.
- **Search performed**: cross-checked each cited section title against design.md `## ` headings.
- **Code location**: all cited sections exist — "Removing the disguise-only style rules", "Persona readers…", "Reader-proxy model pins…", "Renaming TL;DR to Summary", "The technical-writer voice", "Dual-register design document", "Transferring the internals-book voice rules", "Hardening the section BLUF lead".
- **Verdict**: MATCHES

### GAPS

#### C20 — orphan constructs / coverage
- **Document claim**: implicit — the five-agent roster and the four house-style consumers plus three direct-copy holders are the full surface.
- **Search performed**: cross-checked the roster (5 agent files), the 4 consumers (house-style § What this style governs), and the 3 copy-holders (review-workflow-writing-style, design-document-rules, CLAUDE.md) against the track's in-scope list.
- **Code location**: every referenced construct is covered by a track-1 in-scope item; no orphaned codebase construct that the plan/design should reference but omits.
- **Verdict**: MATCHES
