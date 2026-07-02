<!-- MANIFEST
findings: 7   severity: {blocker: 1, should-fix: 4, suggestion: 2}
index:
  - {id: A1, sev: blocker,    loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:168", anchor: "### A1 ", cert: V1, basis: "output-styles files sit outside every §1.7 staging surface; edits either leak live or silently drop at promotion"}
  - {id: A2, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:190", anchor: "### A2 ", cert: V2, basis: "CLAUDE.md has no staged mirror path; the track's blanket staging instruction is unrealizable for item 19"}
  - {id: A3, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:147", anchor: "### A3 ", cert: V3, basis: "staged dsc test suite is structurally red: REPO_ROOT resolves to staged-workflow/, which holds no docs/adr calibration files"}
  - {id: A4, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:184", anchor: "### A4 ", cert: C4, basis: "D2 hybrid output has producer edits planned but no consumer-side edit; create-plan Step-4b pins a bounded inline gate return the hybrid contradicts"}
  - {id: A5, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:178", anchor: "### A5 ", cert: V5, basis: "SHAPE_EXEMPT_SECTION_NAMES matches section titles exact-case; a lowercase \"summary\" entry silently fails to exempt Part-level ## Summary"}
  - {id: A6, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:194", anchor: "### A6 ", cert: C6, basis: "single-track sizing survives challenge; A1 remedy may add implementer-rules.md, keeping the roster under the ceiling"}
  - {id: A7, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:205", anchor: "### A7 ", cert: V7, basis: "S4 single-owner and the D1 consumer enumeration both survive independent verification; assert checks claims, not behavior"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 7}
cert_index:
  - {id: V1, verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2, verdict: CONSTRUCTIBLE, anchor: "#### V2 "}
  - {id: V3, verdict: CONSTRUCTIBLE, anchor: "#### V3 "}
  - {id: C4, verdict: WEAK, anchor: "#### C4 "}
  - {id: V5, verdict: CONSTRUCTIBLE, anchor: "#### V5 "}
  - {id: C6, verdict: SURVIVES, anchor: "#### C6 "}
  - {id: V7, verdict: THEORETICAL, anchor: "#### V7 "}
  - {id: T8, verdict: HOLDS, anchor: "#### T8 "}
  - {id: T9, verdict: HOLDS, anchor: "#### T9 "}
  - {id: T10, verdict: HOLDS, anchor: "#### T10 "}
flags: [CONTRACT_OK]
-->

# Adversarial review — Track 1, iteration 1 (Phase 3A)

Reviewer: reviewer-adversarial. Narrowed per D9 to track realization only: scope-and-sizing plus invariant-violation challenges. D1–D10 merits are settled by the Phase 0→1 gate and were not re-challenged. Workflow-machinery prose criteria applied (the branch is §1.7(b) staged; `s17=staged` in the phase ledger). No staged subtree exists yet, so every read below resolved to the live file per §1.7(d).

Reference-accuracy caveat: the in-scope surface is Markdown and Python, so PSI `findClass` does not apply; all reference checks below used grep plus targeted reads. Grep-based enumeration can miss a paraphrased (non-verbatim) mention of a removed rule; the enumeration checks in T8 matched on rule names and their common variants.

## Findings

### A1 [blocker]
**Certificate**: V1 (violation scenario — live-tree isolation invariant vs. `output-styles`)
**Target**: Invariant "Live-tree isolation (§1.7 / I6)" + the `## Interfaces and Dependencies` preamble ("All implementation edits land as staged copies under `_workflow/staged-workflow/.claude/**`") + in-scope files 1 and 2
**Challenge**: `.claude/output-styles/**` sits outside the §1.7 staging boundary. `conventions.md` §1.7(a) (`conventions.md:991-994`) says workflow files outside the four prefixes (`workflow/`, `skills/`, `agents/`, `scripts/`) "are not stageable under this convention". Every staging-aware surface enumerates the same four prefixes: the implementer write-routing (`implementer-rules.md:284`), the pre-commit gate (`conventions.md:1193-1201`), the Phase-4 promotion `git add .claude/workflow .claude/skills .claude/agents .claude/scripts` (`create-final-design.md:548`), and the pre-promotion divergence check (`conventions.md:1211-1218`). The track's two most important files — `house-style.md`, the declared source of truth of the whole change, and `house-conversation.md` — are items 1 and 2. Whichever way the implementer resolves the ambiguity, an invariant breaks: a live write activates the new rules mid-branch (the always-on hook `house-style-write-reminder.sh`, wired at `settings.json:49`, reads live `house-style.md` on every Markdown write), and a staged copy is laid on disk by the promotion `cp -r "$STAGED_DIR/.claude/." .claude/` (`create-final-design.md:547`) but never committed, because the `git add` on the next line omits `output-styles` — the core edit of the branch silently drops out of the promotion commit.
**Evidence**: V1 trace below; `conventions.md:991-994`, `implementer-rules.md:284`, `create-final-design.md:547-548`. No `.claude/workflow/**` or prompt file mentions staging for `output-styles` (repo grep, zero hits outside house-style prose references).
**Proposed fix**: The track must name an explicit mechanism for items 1 and 2 before decomposition. Two workable shapes: (a) extend the §1.7 machinery inside this track's scope — add `output-styles` to the prefix enumerations in `conventions.md` §1.7 (item 18, already in scope), the promotion `git add` in `create-final-design.md` (item 16, already in scope), and `implementer-rules.md` (new in-scope file), relying on §1.7(d) staged-first reads to make the extension govern this branch's own later steps; or (b) declare the two files Phase-4 hand-edits applied immediately after the promotion commit, the pattern §1.7(e) already blesses for deletions (`conventions.md:1186-1188`), with the edit content carried in the staged tree as reference. Either way, reword the live-tree-isolation invariant to say what holds until Phase 4 versus at Phase 4.

### A2 [should-fix]
**Certificate**: V2 (violation scenario — item 19 has no staging home)
**Target**: In-scope file 19 (`CLAUDE.md` § Writing Style) + the `## Interfaces and Dependencies` preamble
**Challenge**: The preamble says all edits land under `_workflow/staged-workflow/.claude/**` "mirroring the live paths below". `CLAUDE.md` lives at the repo root, so no mirror path under `staged-workflow/.claude/**` exists for it; the promotion `cp -r` copies only the `.claude/` subtree (`create-final-design.md:547`) and can never promote a staged root-level file. The instruction is unrealizable for item 19, and each escape hatch is silent: a copy staged at `staged-workflow/CLAUDE.md` is deleted unpromoted by the Phase-4 `_workflow/` cleanup sweep, while a live edit passes both the pre-commit gate and the track's own I6 assert (both scoped to `.claude/**`), putting the parenthetical removal live mid-branch.
**Evidence**: V2 trace below; `conventions.md:975-994` (layout covers `.claude/`-prefixed mirrors only), `create-final-design.md:547`.
**Proposed fix**: Give item 19 an explicit disposition in the track file. The cheap one: declare the `CLAUDE.md` parenthetical a Phase-4 hand-edit (same commit window as the A1 option (b) edits). If instead a live mid-branch edit is deliberately acceptable (the parenthetical is descriptive, and `CLAUDE.md` is outside §1.7's subject matter), say so explicitly so the implementer does not improvise and reviewers do not flag it.

### A3 [should-fix]
**Certificate**: V3 (violation scenario — removal-completeness assert cannot run in staged mode)
**Target**: Invariant "Removal completeness" (assert: "run the test suite") + Validation criterion 1 + in-scope files 8 and 10
**Challenge**: The staged copy of the dsc test suite is structurally red for reasons unrelated to the removals. `test_dsc_ai_tell.py:58` computes `REPO_ROOT = Path(__file__).resolve().parents[3]`; for the staged copy at `_workflow/staged-workflow/.claude/scripts/tests/test_dsc_ai_tell.py`, `parents[3]` is `staged-workflow/`, so `SCRIPT` and `FIXTURE` correctly resolve to the staged checker and fixture, but `CALIBRATION_ADRS` (`test_dsc_ai_tell.py:63-67`) resolves to `staged-workflow/docs/adr/...`, which does not exist — the staged tree carries only `.claude/`. Running the live suite instead validates nothing: the live checker still carries the three regexes. So the invariant's assert has no executable form during Phase B as written. The trap generalizes: `test_design_mechanical_checks_d11.py:69`, the named model for the new shape tests (item 10), also reaches into `docs/adr/plan-slimization/` via `FROZEN_DESIGN`.
**Evidence**: V3 trace below; `test_dsc_ai_tell.py:58-67`, `test_design_mechanical_checks_d11.py:61-69`.
**Proposed fix**: Define the staged-mode run recipe in the track (Phase A decomposition inherits it). Cheapest: since item 8 already edits `test_dsc_ai_tell.py`, make the calibration-ADR paths resolve against the enclosing git repo root (walk up to `.git`) as part of the same staged edit, and have the new shape tests (item 10) use self-contained fixtures under `scripts/tests/fixtures/` with no `docs/adr` reach. Add the chosen recipe to `## Validation and Acceptance` so the green-suite criterion is executable.

### A4 [should-fix]
**Certificate**: C4 (challenge — D2 hybrid output realization: producers planned, consumers not)
**Target**: In-scope files 4, 13, 14 (D2 realization) + `## Plan of Work` paragraph 3
**Challenge**: The track plans the hybrid narrative-plus-findings output at its two producer sites — `comprehension-review.md` (item 4) and the `design-review.md` output contract (item 13) — and plans nothing at the consumer sites. `comprehension-review` runs on two paths: the `edit-design` design path, whose Step 6 seeds author rework from gate findings and says nothing about routing a narrative (D2's stated consumer is "the orchestrator's gate verdict plus the author's rework seed"), and the `create-plan` Step-4b track path, which forwards the same `prompts/design-review.md` inputs (`create-plan/SKILL.md:944-947`) and explicitly pins a bounded inline return: "the bounded comprehension verdict plus a summary-shaped `## Structural findings` list ... the inline return stays small even on a wide `full` surface" (`create-plan/SKILL.md:952-956`). An unconditional hybrid contract in `design-review.md` makes the gate return a reading narrative on the track path too, contradicting that bounded-return text and re-creating the no-consumer narrative cost D2's own rationale rejects for the sliced auditor. `create-plan/SKILL.md` is outside the 19-file list; item 14's `edit-design` changes cover only TL;DR sites and the link-staleness reminder.
**Evidence**: C4 trace below; `create-plan/SKILL.md:944-956`, `edit-design/SKILL.md:447-478` (gate spawn and finding routing, no narrative handling), design.md §"Persona readers" (narrative consumer claim).
**Proposed fix**: Decide the hybrid's path scope and plan the consumer edits. Either scope the narrative to the design path (`target=design`) inside the item-13 `design-review.md` edit and state that the Step-4b track gate stays findings-only, or add `create-plan/SKILL.md` (Step-4b gate contract) and the `edit-design` gate-return/rework-seed text to the in-scope list. One sentence in the track file settles it; silence leaves post-promotion prose contradicting agent behavior.

### A5 [should-fix]
**Certificate**: V5 (violation scenario — exact-case set membership for the Part-level exemption)
**Target**: In-scope file 7 ("`SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS` gain `"summary"`") + Invariant "Both-spellings acceptance" / "`MANDATORY_OR_FORM_SUBHEADINGS` contains `"summary"`"
**Challenge**: The two sets compare differently, and the track hands the implementer the same lowercase token for both. `MANDATORY_OR_FORM_SUBHEADINGS` is compared lowercased (`design-mechanical-checks.py:1335`, `sub.lower() not in ...`), so `"summary"` is right there. `SHAPE_EXEMPT_SECTION_NAMES` is matched against the raw section title (`design-mechanical-checks.py:736-737`, `section["title"] in SHAPE_EXEMPT_SECTION_NAMES`; the existing entry is `"TL;DR"` exact-case, `design-mechanical-checks.py:54`). A lowercase `"summary"` entry never matches a `## Summary` Part-level section title, so after promotion every design carrying a Part-level `## Summary` section gets shape-checked as an ordinary section and false-positives (no summary block inside the summary, no footer). The planned tests miss this: the named assert covers the multi-`### Summary` sibling check, and no Part-level exemption case is listed.
**Evidence**: V5 trace below; `design-mechanical-checks.py:49-54,62-63,736-737,1335`.
**Proposed fix**: In item 7 name the exact-case entry (`"Summary"` in `SHAPE_EXEMPT_SECTION_NAMES`, `"summary"` in `MANDATORY_OR_FORM_SUBHEADINGS`) and add a Part-level `## Summary` exemption case to the item-10 shape tests.

### A6 [suggestion]
**Certificate**: C6 (challenge — scope and sizing; survival test YES)
**Target**: `## Interfaces and Dependencies` sizing paragraph (single track, ~19 files)
**Challenge**: Argued for a split (regex/test surgery vs. prose/persona work) and against the enumeration's completeness. Both challenges fail. A split breaks D1's same-change coupling (source rule, regex, assertion, fixture are one unit spanning both halves), so the single track is the coherent shape, and the whole-change justification satisfies the single-track gate. Independent re-greps over the live tree found zero TL;DR sites and zero removed-rule consumers outside the 19-file list (T8); the two extra grep hits (`workflow-reindex.py:285`, `ephemeral-identifier-rule.md:157`) are unrelated uses of "hyphenated". Staged TOC validation is already handled by the live machinery (T10), so no reindex work needs adding.
**Evidence**: C6 and T8/T10 below.
**Proposed fix**: None required. If A1 resolves via option (a), `implementer-rules.md` joins the roster (~20 files, still inside the ~20–25 ceiling); the sizing paragraph should be updated with the final count at reconciliation.

### A7 [suggestion]
**Certificate**: V7 (violation scenario — S4 single-owner; feasibility THEORETICAL)
**Target**: Invariant "Single prose-axis owner (S4)"
**Challenge**: Tried to construct an S4 violation through the persona recast. Ownership does not move (the target reader keeps the axis; `edit-design/SKILL.md:461-478` already carries the design-sync carve keeping the auditor the single prose owner), and the six files that mention the axis stay accurate because none of them changes who owns it. The only residual is that the track's assert (grep over staged agent definitions for axis claims) verifies declarations, and behavior can drift without a declaration: a time-constrained-reviewer narrative that editorializes on banned-pattern prose would run the axis de facto while the grep stays green. The persona contract text is the real guard.
**Evidence**: V7 below; axis mentions enumerated at `readability-auditor.md`, `comprehension-review.md`, `edit-design/SKILL.md`, `create-plan/SKILL.md`, `design-document-rules.md`, `design-review.md` (grep, live tree).
**Proposed fix**: Optional hardening at decomposition: have the item-4 persona text state the exclusion positively ("report comprehension stumbles; do not report style or AI-tell findings — that axis belongs to the target reader"), which makes the behavioral boundary greppable as well as declared.

## Evidence base

#### V1 Violation scenario: live-tree isolation (§1.7 / I6) — `output-styles` edits
- **Invariant claim**: The branch diff touches no live `.claude/**` path; every edit is under `_workflow/staged-workflow/.claude/**`, and the live tree changes only at the Phase-4 promotion commit.
- **Violation construction**:
  1. Start state: branch in §1.7(b) staged mode (`s17=staged`), no staged subtree yet. Step covering items 1–2 begins.
  2. Action sequence, path (i) — routing followed literally: implementer reads the write-routing rule (`implementer-rules.md:284`: routes paths beginning with the four prefixes; `.claude/output-styles/` matches none) → writes live `.claude/output-styles/house-style.md`. The pre-commit gate (`conventions.md:1193-1201`) checks the same four prefixes → commit accepted.
  3. Intermediate state: live `house-style.md` carries the removals mid-branch. The `PostToolUse` hook `house-style-write-reminder.sh` (wired `settings.json:49`) and every output-style load now serve the new rules to the very sessions that must author this branch's remaining `_workflow/` documents under the old rules (the track's own bootstrap constraint at `track-1.md:119`).
  4. Violation point: the track's I6 assert (`git diff --name-only` shows a live `.claude/**` change) fails at track review — after the work is done.
  5. Action sequence, path (ii) — staging improvised: implementer stages at `staged-workflow/.claude/output-styles/house-style.md`. Phase 4 runs `cp -r "$STAGED_DIR/.claude/." .claude/` (`create-final-design.md:547`) → file lands on disk; `git add .claude/workflow .claude/skills .claude/agents .claude/scripts` (`:548`) omits it → promotion commit excludes the change; the cleanup commit sweeps `_workflow/` away.
  6. Observable consequence: path (i) breaks live-tree isolation silently until track review; path (ii) silently drops the branch's central edit from the promoted history, leaving an uncommitted working-tree modification for someone to discover or discard.
- **Feasibility**: CONSTRUCTIBLE (both paths follow the live machinery as written; no step requires an unlikely condition).

#### V2 Violation scenario: item 19 (`CLAUDE.md`) has no staged mirror
- **Invariant claim**: same I6 claim plus the track preamble "All implementation edits land as staged copies under `_workflow/staged-workflow/.claude/**` mirroring the live paths below".
- **Violation construction**:
  1. Start state: step covering item 19 begins. `CLAUDE.md` lives at the repo root; §1.7(a)'s layout (`conventions.md:975-988`) defines mirrors only for `.claude/`-prefixed paths.
  2. Action sequence: implementer either (i) stages at `staged-workflow/CLAUDE.md` — outside the `.claude/` subtree the promotion `cp -r` copies (`create-final-design.md:547`) and outside the `.claude/` presence check that triggers promotion (`conventions.md:1085-1094`); or (ii) edits live `CLAUDE.md`.
  3. Intermediate state: (i) a staged file no mechanism will ever promote; (ii) a live doc change mid-branch.
  4. Violation point: (i) the Phase-4 cleanup `git rm -rf _workflow/` deletes the staged copy unpromoted — the edit vanishes with no error; (ii) the pre-commit gate and the track's I6 assert are both scoped to `.claude/**` and stay silent, so the leak is invisible to every check the track names.
  5. Observable consequence: (i) silent loss of the item-19 edit; (ii) an undetected live change that contradicts the track's stated staging discipline.
- **Feasibility**: CONSTRUCTIBLE.

#### V3 Violation scenario: removal-completeness assert has no executable staged-mode form
- **Invariant claim**: "After the triple deletion ... `test_dsc_ai_tell.py` is green. (Assert: run the test suite; ...)"
- **Violation construction**:
  1. Start state: items 7–9 staged (checker, test, fixture edited under `staged-workflow/.claude/scripts/...`). Implementer runs the assert.
  2. Action sequence: `python3 docs/adr/tech-writer-tone/_workflow/staged-workflow/.claude/scripts/tests/test_dsc_ai_tell.py`. The runner computes `REPO_ROOT = Path(__file__).resolve().parents[3]` (`test_dsc_ai_tell.py:58`) = `.../_workflow/staged-workflow`.
  3. Intermediate state: `SCRIPT` → staged checker (exists), `FIXTURE` → staged fixture (exists), `CALIBRATION_ADRS` → `staged-workflow/docs/adr/{persist-visible-count,index-gc,non-durable-wow}/adr.md` (`test_dsc_ai_tell.py:63-67`) — none exists; the staged tree carries only `.claude/`.
  4. Violation point: the calibration-ADR subprocess calls fail on missing files → suite red regardless of whether the triple deletion is correct. The fallback (running the live suite) exercises the unedited live checker and validates nothing about the change.
  5. Observable consequence: the invariant's assert and Validation criterion 1 cannot distinguish a correct triple deletion from a broken one during Phase B; the first honest signal arrives post-promotion.
- **Feasibility**: CONSTRUCTIBLE. Same reach exists in the item-10 model: `test_design_mechanical_checks_d11.py:69` resolves `FROZEN_DESIGN` under `docs/adr/plan-slimization/`.

#### C4 Challenge: D2 track realization — hybrid output consumer wiring
- **Chosen approach**: Add the hybrid narrative-plus-findings contract at `comprehension-review.md` (item 4) and `prompts/design-review.md` (item 13); change no consumer file (item 14 touches `edit-design` only for TL;DR sites and the link reminder; `create-plan/SKILL.md` is out of scope).
- **Best rejected alternative**: Plan the consumer edits alongside the producers (or scope the narrative to the design path explicitly).
- **Counterargument trace**:
  1. In the Step-4b track path, `create-plan/SKILL.md:944-947` forwards `prompts/design-review.md` inputs to the same `comprehension-review` agent with `target=tracks`, and `create-plan/SKILL.md:952-956` pins the return: bounded verdict plus a summary-shaped findings list, "the inline return stays small even on a wide `full` surface".
  2. An unconditional hybrid contract in `design-review.md` makes that return carry a reading narrative on the track path too — the exact per-slice/no-consumer cost D2's rationale uses to deny the narrative to the sliced auditor.
  3. On the design path, `edit-design/SKILL.md` Step 6 routes gate findings into the fix loop and never mentions a narrative; D2 names the author's rework seed as a narrative consumer, so the seed-routing is an edit this track owes and does not plan.
- **Codebase evidence**: `create-plan/SKILL.md:944-956`; `edit-design/SKILL.md:447-478`; design.md §"Persona readers: target reader and time-constrained reviewer".
- **Survival test**: WEAK — the D2 decision itself stands (settled at the log gate); the track's realization of it is incomplete on the consumer side and needs one explicit scoping sentence plus, depending on the choice, one or two consumer-file additions.

#### V5 Violation scenario: Part-level `## Summary` misses the shape exemption
- **Invariant claim**: "`MANDATORY_OR_FORM_SUBHEADINGS` contains `"summary"`" / both-spellings acceptance — a well-formed post-rename design passes the shape checks.
- **Violation construction**:
  1. Start state: implementer executes item 7 as written: "`SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS` gain `"summary"`" — one lowercase token for both sets.
  2. Action sequence: `MANDATORY_OR_FORM_SUBHEADINGS` gains `"summary"` — correct, membership is checked lowercased (`design-mechanical-checks.py:1335`). `SHAPE_EXEMPT_SECTION_NAMES` gains `"summary"` — wrong case: membership is checked against the raw title (`design-mechanical-checks.py:736-737`), and the existing sibling entry is exact-case `"TL;DR"` (`:54`).
  3. Intermediate state: all planned tests pass — the named asserts cover `### Summary` sub-heading acceptance and the multi-`### Summary` sibling check, none feeds a Part-level `## Summary` section.
  4. Violation point: post-promotion, a design with a Part-level `## Summary` section (the renamed form of today's Part-level `## TL;DR`) fails `is_shape_exempt` → the checker demands a summary block and footer inside the summary section itself.
  5. Observable consequence: false-positive shape findings on every well-formed post-rename `# Part`-structured design; silent until the first such design is authored.
- **Feasibility**: CONSTRUCTIBLE.

#### C6 Challenge: scope and sizing — is one ~19-file track the right shape?
- **Chosen approach**: Single track, ~19 in-scope files, justified as "this is the whole change".
- **Best rejected alternative**: Split into a mechanical track (checker + tests + fixture, items 7–10) and a prose/persona track (the rest).
- **Counterargument trace**:
  1. D1 makes a removal a same-change unit spanning `house-style.md` (prose track) and the regex/test/fixture triple (mechanical track); a split forces either a cross-track atomic change (forbidden by the stacked-diff model) or a window where the source rule and its regex disagree.
  2. 19 files sits inside the two-sided bound (floor ~12, ceiling ~20–25, `planning.md` §Track descriptions); the written justification satisfies the single-track gate.
  3. Completeness checks (T8, T10) found nothing missing that would push the roster over the ceiling; the A1 remedy adds at most `implementer-rules.md` (~20 files).
- **Codebase evidence**: repo-wide greps in T8; `workflow-reindex.py:118-160` in T10.
- **Survival test**: YES — the single-track shape survives; only the final file count may drift by one or two with the A1/A4 remedies.

#### V7 Violation scenario: S4 single prose-axis owner under the persona recast
- **Invariant claim**: Exactly one staged agent definition claims the prose AI-tell axis (the target reader / `readability-auditor`).
- **Violation construction**:
  1. Start state: items 3–4 recast both readers; item 6 rewrites the Phase-C style reviewer's criteria.
  2. Action sequence: attempted constructions — (i) the recast moves or duplicates an axis claim: fails, no planned edit moves ownership, and the live carve at `edit-design/SKILL.md:461-478` already names the auditor as single owner including the design-sync path; (ii) an out-of-scope file contradicts the recast: fails, the six axis-mentioning files either are in scope or describe ownership that does not change; (iii) behavioral drift: the time-constrained reviewer's narrative editorializes on banned-pattern prose, running the axis de facto while declaring nothing.
  3. Violation point (iii only): the track's assert greps declarations, so a de-facto second axis runner stays green.
  4. Observable consequence: duplicated style findings across the two readers, thrashing the dual-clean loop — visible in loop behavior, invisible to the named assert.
- **Feasibility**: THEORETICAL — requires the persona text to be written loosely enough to invite editorializing; the D2/D6 contract language targets exactly this, and the proposed one-line positive exclusion closes it.

#### T8 Assumption test: the D1/D4 consumer enumerations are complete
- **Claim**: The 19-file list covers every live surface that hard-codes a `TL;DR` spelling or names a removed rule.
- **Stress scenario**: Independent repo-wide greps for `TL;DR` and for all six removed-rule names (with variants) across `.claude/**` and `CLAUDE.md`, diffed against the track's list.
- **Code evidence**: TL;DR hits beyond the list are only the four `d11-*` fixtures (legacy spellings stay accepted, no edit needed) and `dsc-ai-tell-fixture.md` (item 9). Removed-rule hits beyond the list are `workflow-reindex.py:285` and `ephemeral-identifier-rule.md:157-158`, both unrelated uses of "hyphenated" (role tokens, finding-ID shapes). The always-on hook `house-style-write-reminder.sh` names only surviving section groups (`:260-262`), and `readability-feedback/SKILL.md` cites the source without restating rules.
- **Verdict**: HOLDS. (Caveat: grep matches names, so a paraphrased mention would escape; none was found in the files read.)

#### T9 Assumption test: frontmatter model pins take effect (D3 realization)
- **Claim**: Editing `model:` in the two agent frontmatters is sufficient; no spawn site overrides it.
- **Stress scenario**: A spawn-site `model:` field on the Agent tool takes precedence over frontmatter, which would silently defeat the Sonnet pin.
- **Code evidence**: The reader spawns in both loops pass `subagent_type` and description only (`create-plan/SKILL.md:852, 942`; `edit-design/SKILL.md` § Step 4). The one nearby `model: opus` (`create-plan/SKILL.md:486`) pins the adversarial-reviewer spawn, a different role. Current frontmatters read `model: opus` for all three agents (`readability-auditor.md:5`, `comprehension-review.md:5`, `design-author.md:5`).
- **Verdict**: HOLDS.

#### T10 Assumption test: staged copies of workflow/skills/agents files stay validated during the branch
- **Claim**: Editing the staged copies does not create a toc-check blind spot that first fires at the Phase-4 promotion PR.
- **Stress scenario**: The YTDB-1123 precedent — annotation-schema findings surfacing only at CI after the PR flip.
- **Code evidence**: `workflow-reindex.py` `IN_SCOPE_GLOBS` includes `docs/adr/*/_workflow/staged-workflow/.claude/workflow/**/*.md` and `.../skills/**/SKILL.md` (`workflow-reindex.py:149-150`), and staged agents are discovered and partitioned into the citing-scope rules (`workflow-reindex.py:151-160` comment block). Staged `output-styles` files carry no TOC region, so they add no blind spot.
- **Verdict**: HOLDS.
