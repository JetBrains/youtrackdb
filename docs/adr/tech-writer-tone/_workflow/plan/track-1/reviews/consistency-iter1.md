<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: CR1, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/design.md:30", anchor: "### CR1 ", cert: C1, basis: "frozen design.md Overview claims all four consumers 'restate no rule' and deletion 'propagates to all of them at once', but the dsc-ai-tell checker holds its own regexes and ai-tells hard-codes rule names — both need explicit edits (design.md's own later sections say so)"}
  - {id: CR2, sev: should-fix, loc: ".claude/agents/review-workflow-writing-style.md:185", anchor: "### CR2 ", cert: C15, basis: "track cites drop-site line 28 (BLUF-lead, a kept/hardened rule) and omits line 185 (a live negative-parallelism restatement) — the exact R4 leftover-site failure mode"}
  - {id: CR3, sev: suggestion, loc: ".claude/scripts/tests/test_dsc_ai_tell.py:138", anchor: "### CR3 ", cert: C16, basis: "track cites identifier `ANCHORED`; the actual symbol is `ANCHORED_REGRESSION_CASES`"}
evidence_base: {section: "## Evidence base", certs: 27, matches: 24}
cert_index:
  - {id: C1, verdict: MISMATCHES, anchor: "#### C1 "}
  - {id: C15, verdict: MISMATCHES, anchor: "#### C15 "}
  - {id: C16, verdict: PARTIAL, anchor: "#### C16 "}
flags: [CONTRACT_OK]
-->

## Findings

### CR1 [should-fix]
**Certificate**: C1 (Design ↔ Code)
**Location**: `design.md` `## Overview` (¶ beginning "`house-style.md` is the single source of truth", lines 28-33). Code: `.claude/scripts/design-mechanical-checks.py` and `.claude/skills/ai-tells/SKILL.md`.
**Issue**: The Overview claims the four named consumers "already cite it by section and **restate no rule**" and that, because "those consumers hold no copy of their own, deleting a rule at the source **propagates the removal to all of them at once**." That is inaccurate for two of the four: the `dsc-ai-tell` regex checker holds its own hard-coded regexes (a copy of the rule), and the `ai-tells` skill hard-codes three rule names in its always-loaded `description:`. Deleting a rule in `house-style.md` does NOT auto-remove either.
**Evidence**: `design-mechanical-checks.py` declares `NEGATIVE_PARALLELISM_RE` (:103), `NEGATIVE_PARALLELISM_TRAILING_RE` (:127), `HYPHENATED_PAIR_CLUSTER_RE` (:191), and `_title_case_violation` (:1734) — the checker restates the patterns mechanically and keeps firing until each regex is deleted. `ai-tells/SKILL.md:3` names "negative parallelism", "Title Case headings", and "closing phrases" in the frontmatter. design.md contradicts its own Overview here: §"Removing the disguise-only style rules" (lines 265-276) says the regex "and its fixture line and assertion" must be deleted "in one change" and that the `ai-tells` description "must be edited"; §"Class Design" (line 99, 127-130) labels the `HS → DSC` edge "regex subset" and calls `DSC → TEST` "the coupling that makes a removal a two-file edit". The current track-1 `## Context and Orientation` (line 124) states the accurate version — only `design-review.md` inherits the deletion for free; the other three take explicit same-change edits — so the track side is correct and only the frozen design.md Overview lags. (Reference-accuracy caveat: grep + Read against the live tree; mcp-steroid unreachable, but these are Python identifiers and Markdown prose, so grep is authoritative.)
**Proposed fix**: In `design.md` § Overview, narrow the "restate no rule / propagates at once" claim to the single consumer it holds for (`prompts/design-review.md`, which cites `house-style.md` by section and restates no rule); state that the `dsc-ai-tell` checker and the `ai-tells` description hold their own copies and take explicit same-change edits, matching §"Class Design", §"Removing the disguise-only style rules", and track-1 § Context. **design.md-scoped — defer to Phase 4** (`design-final.md` reconciles the as-built design; the frozen `design.md` is not edited during execution). No track edit needed — the track already carries the correct statement.
**Classification**: mechanical
**Justification**: Current-state design↔code claim with a single unambiguous correct rendering (the one design.md already gives in its own later sections and the track echoes); the fix corrects the description only, preserving intent. Recorded per the classification rule "a finding whose only correct rendering touches the frozen design.md must still be recorded, deferred to Phase 4."

### CR2 [should-fix]
**Certificate**: C15 (Track ↔ Code)
**Location**: `track-1.md` `## Plan of Work` (¶ beginning "Edit the agent roster (D3/D6)"), `## Interfaces and Dependencies` item 6, and the D1/D7/R4 references — all cite the develop-state drop sites in `.claude/agents/review-workflow-writing-style.md` as "lines 28, 29, 34, 71, 188".
**Issue**: The cited five-site line list is wrong at one position: it names line 28 (not a removed-rule site) and omits line 185 (a removed-rule site). R4's own wording — the drop "must reach all of them, or the Phase-C reviewer keeps enforcing a rule house-style no longer bans" — makes the enumeration load-bearing.
**Evidence**: On the live develop-state file the removed-rule restatement sites are lines **29** (negative parallelism / roundabout negation / closing phrases), **34** ("No Title Case headings — sentence case"), **71** ("It's not X — it's Y" / "In conclusion"), **185** ("It's not X — it's Y" anti-pattern), and **188** (banned sentence pattern / Title Case headings). Line **28** is `- **BLUF lead** — first sentence states the conclusion, not background.` — a KEPT rule that D10 *hardens* (item 6 also says "add the D10 BLUF criteria"), not a removed rule. Line **185** (a genuine negative-parallelism restatement) is absent from the cited list. An implementer following the enumeration literally would (a) look for a removed rule to drop at line 28 and instead sit on the D10 BLUF-lead line, and (b) leave the live negative-parallelism restatement at line 185 — precisely the R4 leftover the track warns against. An exhaustive grep of the file for all six removed-rule names returns exactly {29, 34, 71, 185, 188} — exactly five sites, matching the track's "five sites" count, so the fix is a one-position correction, not a recount. (Reference-accuracy caveat: grep + exact-line Read against the live tree; mcp-steroid unreachable, but these are Markdown text anchors, not polymorphic symbols, so grep is authoritative.)
**Proposed fix**: Change the cited drop-site list from "28, 29, 34, 71, 188" to **"29, 34, 71, 185, 188"** in `## Plan of Work`, `## Interfaces and Dependencies` item 6, and any R4/acceptance restatement that repeats the numbers. Keep line 28 as the D10 BLUF-lead *addition* site ("add the D10 BLUF criteria"), not on the drop list.
**Classification**: mechanical
**Justification**: Current-state claim about a live file's line contents with exactly one unambiguous correct rendering (the grep-derived site set); the fix corrects the description only and preserves the track's intent (drop the removed rules at every restatement site).

### CR3 [suggestion]
**Certificate**: C16 (Track ↔ Code)
**Location**: `track-1.md` `## Plan of Work` (¶ beginning "Start with the removal"), `## Validation and Acceptance` (first bullet), `## Invariants & Constraints` (Removal-completeness bullet) — each cites "the `ANCHORED` and `NEGATIVE_RANGES` line lists".
**Issue**: The backtick-wrapped code identifier `` `ANCHORED` `` does not exist verbatim in `test_dsc_ai_tell.py`; the actual symbol is `ANCHORED_REGRESSION_CASES`. `NEGATIVE_RANGES` is exact.
**Evidence**: `test_dsc_ai_tell.py:138` declares `ANCHORED_REGRESSION_CASES: List[Tuple[int, str, str]] = [...]` and `:115` declares `NEGATIVE_RANGES: List[Tuple[int, int, str]] = [...]`. Both are line-anchored assertion lists that shift when fixture lines are removed, so the track's characterization ("line-anchored assertions ... line lists") is correct; only the abbreviated name is imprecise. A substring grep for `ANCHORED` matches the real symbol, so the risk is low, but the precise-citation rule wants the exact identifier. (Reference-accuracy caveat: grep-verified against the live test file; mcp-steroid unreachable, but this is a Python identifier match, not a polymorphic call site.)
**Proposed fix**: Replace `` `ANCHORED` `` with `` `ANCHORED_REGRESSION_CASES` `` at the three cited sites (or, on first use, write "`ANCHORED_REGRESSION_CASES` (hereafter `ANCHORED`)" if the abbreviation is wanted for brevity).
**Classification**: mechanical
**Justification**: Current-state claim about an existing code symbol with a single unambiguous correct rendering (the full identifier); the fix updates the reference only.

## Evidence base

**Design ↔ Code**

#### C1 Ref: design.md Overview consumer-propagation claim
- **Document claim**: § Overview — the four named consumers "already cite it by section and restate no rule … deleting a rule at the source propagates the removal to all of them at once."
- **Search performed**: Read design.md:28-33; grep regex identifiers in `design-mechanical-checks.py`; Read `ai-tells/SKILL.md:3`.
- **Code location**: `design-mechanical-checks.py:103,127,191,1734` (own regexes); `ai-tells/SKILL.md:3` (hard-coded rule names).
- **Actual signature/role**: The dsc checker holds its own regex copies and the ai-tells description hard-codes three rule names; neither auto-updates from a house-style edit. design.md's own §"Removing…" (265-276) and §"Class Design" (99,127-130) say so, and track-1 § Context (124) states the accurate version.
- **Verdict**: MISMATCHES
- **Detail**: "restate no rule / propagates at once" is true only for `design-review.md`; false for the dsc checker and the ai-tells description. Frozen-design inaccuracy → defer to Phase 4.

#### C2 Ref: house-style.md writer persona (`house-style.md:42`)
- **Document claim**: design.md — "The writer persona is a senior engineer (`house-style.md:42`)".
- **Search performed**: Read `.claude/output-styles/house-style.md:42`; grep headings.
- **Code location**: `:40` (`## Voice and tone`), `:42` ("Match a senior engineer writing to peers…").
- **Actual signature/role**: Senior-engineer persona sentence under § Voice and tone.
- **Verdict**: MATCHES

#### C3 Ref: house-conversation.md chat reader (`house-conversation.md:7`)
- **Document claim**: design.md — "the chat reader a senior engineer (`house-conversation.md:7`)"; track #2 — currently senior engineer.
- **Search performed**: Read `.claude/output-styles/house-conversation.md:1-12`.
- **Code location**: `:7`.
- **Actual signature/role**: "You are answering in a terminal for a senior YouTrackDB engineer."
- **Verdict**: MATCHES

#### C4 Ref: house-style.md mid-level reader floor (`house-style.md:6`)
- **Document claim**: design.md §"The technical-writer voice" edge case — the mid-level reader floor at `house-style.md:6` does not change.
- **Search performed**: Read `.claude/output-styles/house-style.md:6`.
- **Code location**: `:6`.
- **Actual signature/role**: "…a YouTrackDB contributor with mid-level Java and database fluency…" — reader-calibration line.
- **Verdict**: MATCHES

#### C5 Ref: dsc-ai-tell checker regex/line anchors
- **Document claim**: design.md D1 + track — `NEGATIVE_PARALLELISM_RE` (:103), `NEGATIVE_PARALLELISM_TRAILING_RE` (:127), `HYPHENATED_PAIR_CLUSTER_RE` (:191), `_title_case_violation` (:1734), `section_has_tldr` (:708), `SHAPE_EXEMPT_SECTION_NAMES` (:54 entry), `MANDATORY_OR_FORM_SUBHEADINGS` (:62).
- **Search performed**: `grep -nE` per identifier in `design-mechanical-checks.py`.
- **Code location**: 103, 127, 191, 1734, 708, 49/54, 62.
- **Actual signature/role**: All present at/within one line of the cited anchors; SHAPE_EXEMPT `"TL;DR"` entry at 54; MANDATORY `"tl;dr"` at 63.
- **Verdict**: MATCHES

#### C6 Ref: set-compare cases for SHAPE_EXEMPT vs MANDATORY (T1 premise)
- **Document claim**: track #7 / D4 / T1 — SHAPE_EXEMPT compares raw titles (needs display-case `"Summary"`); MANDATORY compares lowercased (needs `"summary"`).
- **Search performed**: Read lines 736-737 and 1335.
- **Code location**: `:737` (`section["title"] in SHAPE_EXEMPT_SECTION_NAMES`), `:1335` (`sub.lower() not in MANDATORY_OR_FORM_SUBHEADINGS`).
- **Actual signature/role**: SHAPE_EXEMPT compares raw title; MANDATORY compares `sub.lower()`. Current contents match those compare cases.
- **Verdict**: MATCHES

#### C7 Ref: design-review.md cites house-style by section, restates no rule
- **Document claim**: track Context / D1 — the cold-read prompt cites house-style by section and restates no rule ("inherits the deletion for free").
- **Search performed**: grep house-style + removed-rule names in `.claude/workflow/prompts/design-review.md`.
- **Code location**: `:178,:180,:319-322` cite `§ Navigability / § Audience-fit / § Edge cases / § References footer / § Same-shape sibling consolidation / § Overview concept-first`; no removed-rule restatement.
- **Actual signature/role**: Section-citation only.
- **Verdict**: MATCHES

#### C8 Ref: review-roster model pins
- **Document claim**: track — auditor/comprehension currently Opus; absorption/fidelity already Sonnet; author Opus.
- **Search performed**: `grep -iE '^model:'` across the five agent files.
- **Code location**: readability-auditor / comprehension-review / design-author = `opus`; absorption-check / fidelity-check = `sonnet`.
- **Actual signature/role**: Exactly as stated.
- **Verdict**: MATCHES

#### C9 Ref: edit-design phase1-creation dual-clean loop drives the two readers
- **Document claim**: track Context — the two readers are driven by the `phase1-creation` dual-clean loop.
- **Search performed**: grep `phase1-creation` / reader names in `.claude/skills/edit-design/SKILL.md`.
- **Code location**: `:165-170` — creation kinds run the dual-clean loop; Step 4 spawns the per-round `readability-auditor` plus the warm absorption check and the cold comprehension gate.
- **Actual signature/role**: Loop membership as described.
- **Verdict**: MATCHES

#### C10 Ref: create-final-design.md Step-4 promotion mechanics (D12 premise)
- **Document claim**: D12 / Idempotence — `cp -r "$STAGED_DIR/.claude/." .claude/`, four-prefix `git add`, four-prefix divergence check; output-styles cp'd not committed, root CLAUDE.md neither.
- **Search performed**: grep `cp -r` / `git add` / `git log` in `create-final-design.md`.
- **Code location**: `:547` cp; `:548` `git add .claude/workflow .claude/skills .claude/agents .claude/scripts`; `:541` four-prefix divergence `git log`.
- **Actual signature/role**: Exactly the commands D12 quotes; four-prefix scope confirms the gap D12 addresses.
- **Verdict**: MATCHES

#### C11 Ref: conventions.md §1.7(a) four-prefix surface + subsection labels
- **Document claim**: D12 — §1.7(a) says "No other prefixes participate"; standard surface is the four prefixes; §1.7(a)–(f) exist.
- **Search performed**: grep "No other prefixes participate" and `^### \([a-l]\) ` in `conventions.md`.
- **Code location**: `:991-994`; subsections `### (a)`…`### (l)` at 968, 1005, 1064, 1108, 1154, 1207, ….
- **Actual signature/role**: Four-prefix surface + clause present; (a)–(f) exist with the roles D12 describes.
- **Verdict**: MATCHES

**Design ↔ Track**

#### C12 Note: D12 supersedes the frozen design §"Staging and promotion under §1.7"
- **Document claim**: D12 extends §1.7 to `.claude/output-styles/**` + root `CLAUDE.md`; the frozen design assumed the four-prefix surface sufficed.
- **Search performed**: Read design.md:725-764 and track D12.
- **Code location**: design.md §"Staging and promotion under §1.7" (no D-record); track D12 + `## Full design: superseded`.
- **Actual signature/role**: Expected inline-replan divergence; track records the supersession and defers to Phase 4.
- **Verdict**: MATCHES (carve-out — no finding per "a revised Decision Record diverging from the frozen design is expected").

#### C13 Ref: track D-records D1–D10 align with design.md sections
- **Document claim**: Each D-record's `**Full design**` points at a design.md section describing the same mechanism.
- **Search performed**: Cross-read D1↔§"Removing…", D2/D6↔§"Persona readers", D3↔§"Reader-proxy…", D4↔§"Renaming TL;DR", D5↔§"The technical-writer voice", D7↔§"Removing…", D8↔§"Dual-register…", D9↔§"Transferring…", D10↔§"Hardening…".
- **Code location**: All design.md sections present with matching content.
- **Actual signature/role**: Alignment holds; the only intended divergence is D12 (C12).
- **Verdict**: MATCHES

**Track ↔ Code**

#### C14 Ref: all 21 in-scope files + fixtures + precedent test exist
- **Document claim**: `## Interfaces and Dependencies` lists 21 in-scope files; Plan of Work cites the fixture and `test_design_mechanical_checks_d11.py`.
- **Search performed**: `test -f` over all 21 files plus fixture, absorption/fidelity agents, conventions-execution.md.
- **Code location**: all present.
- **Actual signature/role**: No phantom file path.
- **Verdict**: MATCHES

#### C15 Ref: review-workflow-writing-style.md removed-rule drop sites
- **Document claim**: track cites the develop-state drop sites as "lines 28, 29, 34, 71, 188".
- **Search performed**: grep all six removed-rule names; exact Read of lines 26-35 and 183-189.
- **Code location**: restatements at `:29,:34,:71,:185,:188`; `:28` is the BLUF-lead (kept/hardened) rule.
- **Actual signature/role**: Correct site set is {29, 34, 71, 185, 188}; the track lists 28 (wrong) and omits 185.
- **Verdict**: MISMATCHES
- **Detail**: Off-by-one enumeration 28→185; line 28 is the D10-hardened BLUF-lead rule, line 185 is a live negative-parallelism restatement — the R4 leftover-site risk.

#### C16 Ref: `ANCHORED` line-list identifier
- **Document claim**: track cites the `` `ANCHORED` `` and `` `NEGATIVE_RANGES` `` line lists in `test_dsc_ai_tell.py`.
- **Search performed**: `grep -nE 'ANCHORED|NEGATIVE_RANGES'` in `test_dsc_ai_tell.py`.
- **Code location**: `:138` `ANCHORED_REGRESSION_CASES`; `:115` `NEGATIVE_RANGES`.
- **Actual signature/role**: `NEGATIVE_RANGES` exact; the other symbol is `ANCHORED_REGRESSION_CASES`, not `ANCHORED`.
- **Verdict**: PARTIAL
- **Detail**: Truncated identifier; substring grep resolves it, so risk is low, but the citation is imprecise.

#### C17 Ref: REPO_ROOT / parents[3] and NEGATIVE_RANGES (R1/A3 + R3 premise)
- **Document claim**: track — `REPO_ROOT` = `Path(__file__).resolve().parents[3]`, pointing at `staged-workflow/` while staged; `NEGATIVE_RANGES` is a line-anchored list.
- **Search performed**: grep in `test_dsc_ai_tell.py`.
- **Code location**: `:59` `REPO_ROOT = Path(__file__).resolve().parents[3]`; `:60` SCRIPT; `:61` FIXTURE; `:65-67` corpus ADRs under `REPO_ROOT/docs/adr`.
- **Actual signature/role**: `parents[3]` confirmed; fixture path is `__file__`-relative (green while staged), corpus path is `REPO_ROOT`-relative (dangles while staged) — the R1/A3 rationale.
- **Verdict**: MATCHES

#### C18 Ref: ai-tells/SKILL.md always-loaded description
- **Document claim**: track #11 / D1 / Validation — the `description:` names negative parallelism, Title Case headings, closing phrases; "adjective triads" remains.
- **Search performed**: Read `.claude/skills/ai-tells/SKILL.md:1-20`; grep "adjective triad".
- **Code location**: `:3`.
- **Actual signature/role**: Names all three removed rules plus "adjective triads".
- **Verdict**: MATCHES

#### C19 Ref: house-conversation.md lists banned patterns by name
- **Document claim**: track Context — house-conversation lists the banned patterns by name in its AI-tell subset.
- **Search performed**: grep in `.claude/output-styles/house-conversation.md`.
- **Code location**: `:23` — "negative parallelism ('not X, but Y'), roundabout negation, throat-clearing, closing connectives, trailing hedges, prompt-restating".
- **Actual signature/role**: Named list present.
- **Verdict**: MATCHES

#### C20 Ref: design-document-rules.md dsc-ai-tell catalogue row
- **Document claim**: track #12 / D1 — a `dsc-ai-tell` catalogue row naming removed rules.
- **Search performed**: grep + Read `.claude/workflow/design-document-rules.md:289`.
- **Code location**: `:289` — "`dsc-ai-tell` AI-tell detection | Detects seven house-style.md patterns … negative parallelism … Title Case H2+ headings … hyphenated-pair clusters …".
- **Actual signature/role**: Catalogue row present, names the removed rules.
- **Verdict**: MATCHES

#### C21 Ref: CLAUDE.md § Writing Style negative-parallelism parenthetical
- **Document claim**: track #19 / D1 — root CLAUDE.md § Writing Style carries the parenthetical.
- **Search performed**: grep in `CLAUDE.md`.
- **Code location**: `:93` — "…full of \"AI tells\" (\"It's not X — it's Y\" negative parallelism, throat-clearing, padding analysis)".
- **Actual signature/role**: Parenthetical present.
- **Verdict**: MATCHES

#### C22 Ref: implementer-rules.md pre-commit gate + write-routing (D12 premise)
- **Document claim**: track #20 / D12 — a write-routing rule and a pre-commit `git diff --cached --name-only` live-path gate scoped to the four prefixes.
- **Search performed**: grep in `.claude/workflow/implementer-rules.md`.
- **Code location**: `:393` "Pre-commit gate, live-workflow-path check"; `:412` `git diff --cached --name-only -- .claude/workflow/ .claude/skills/ .claude/agents/ .claude/scripts/`; `:286-289` staged write-routing.
- **Actual signature/role**: Gate scoped to the four prefixes (omits output-styles + root CLAUDE.md) — the surface D12 extends.
- **Verdict**: MATCHES

#### C23 Ref: create-plan Step-4b bounded inline-gate return contract (A4 premise)
- **Document claim**: track A4 / item 21 — Step-4b pins a bounded inline-gate return the D2 hybrid would contradict.
- **Search performed**: Read `.claude/skills/create-plan/SKILL.md:933-962`.
- **Code location**: `:951-956` — "its return is the bounded comprehension verdict plus a summary-shaped `## Structural findings` list … so the inline return stays small".
- **Actual signature/role**: Bounded-return contract present; the D2 hybrid narrative would widen it — A4's current-state premise holds.
- **Verdict**: MATCHES

#### C24 Ref: test_design_mechanical_checks_d11.py both-spellings precedent
- **Document claim**: track D4 — model new shape tests on the References→Decisions & invariants both-spellings precedent.
- **Search performed**: grep in `.claude/scripts/tests/test_design_mechanical_checks_d11.py`.
- **Code location**: `:5-18` — footer rename `### References` → `### Decisions & invariants`, both spellings accepted; five cases pin both sides.
- **Actual signature/role**: The both-spellings precedent test.
- **Verdict**: MATCHES

#### C25 Ref: comprehension-review.md TL;DR structural-finding site
- **Document claim**: track #4 / design.md D4 (`comprehension-review.md:34`) — a TL;DR site in the structural-findings list.
- **Search performed**: Read `.claude/agents/comprehension-review.md:34`.
- **Code location**: `:34` — "…References footer, sibling consolidation, TL;DR, length budgets…".
- **Actual signature/role**: TL;DR named.
- **Verdict**: MATCHES

#### C26 Ref: TL;DR rename targets reachable
- **Document claim**: track D4 / items 12-18 rename the `TL;DR` spelling across the named files.
- **Search performed**: `grep -icE 'TL;DR'` over each rename-target file.
- **Code location**: planning.md=1, create-final-design.md=1, review-workflow-pr=1, conventions.md=1, edit-design=5, design-document-rules.md=18, design-review.md=7, comprehension-review.md=1, house-style.md=4.
- **Actual signature/role**: Every target carries at least one TL;DR occurrence; edit-design "~5 sites" matches (5).
- **Verdict**: MATCHES

**Gaps**

#### C27 Gap: orphan removed-rule surfaces outside the 21-file scope
- **Document claim**: D1 — the mirrored consumers are "enumerated by grep over the removed pattern names"; the 21-file list is complete.
- **Search performed**: `grep -rilE '<removed-rule names>' .claude/ CLAUDE.md`, excluding `_workflow` and `docs/adr`.
- **Code location**: 9 files name a removed rule: review-workflow-writing-style.md, CLAUDE.md, house-conversation.md, house-style.md, design-mechanical-checks.py, dsc-ai-tell-fixture.md, test_dsc_ai_tell.py, ai-tells/SKILL.md, design-document-rules.md.
- **Actual signature/role**: All 9 are already in the 21-file in-scope list. No orphan surface outside scope.
- **Verdict**: MATCHES (no gap)
