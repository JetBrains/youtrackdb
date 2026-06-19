<!-- workflow-sha: c99af024a00cbe1e4741d4d88e600b6f007c9199 -->
<!-- MANIFEST
role: reviewer-technical
phase: 3A
track: "Track 1: Shrink house-style to comprehension-serving rules"
iteration: 1
verdict: PASS
findings: 5
blockers: 0
index:
  - id: T1
    sev: should-fix
    anchor: "### T1 [should-fix]"
    loc: "house-style.md:359, house-style.md:485 (Self-check item 7)"
    cert: "Premise: house-style.md retains in-file refs to its own removed section"
    basis: read
  - id: T2
    sev: should-fix
    anchor: "### T2 [should-fix]"
    loc: "house-conversation.md:21, hooks/house-style-write-reminder.sh:262"
    cert: "Premise: bootstrap-slug carriers hard-code the count word six"
    basis: read
  - id: T3
    sev: should-fix
    anchor: "### T3 [should-fix]"
    loc: "design-document-rules.md:287"
    cert: "Integration: design-document-rules.md dsc-ai-tell row mirrors the checker docstring"
    basis: read
  - id: T4
    sev: suggestion
    anchor: "### T4 [suggestion]"
    loc: "track-1.md Move 1; house-style.md:131, :123"
    cert: "Premise: the em-dash in-file cross-ref the track names does not sit in the claimed section"
    basis: read
  - id: T5
    sev: suggestion
    anchor: "### T5 [suggestion]"
    loc: "track-1.md Move 6; readability-auditor.md, prompts/design-review.md"
    cert: "Integration: two named prose consumers carry no removed-section reference to re-point"
    basis: read
evidence_base:
  premises: 11
  edge_cases: 0
  integrations: 4
  confirmed: 12
  not_confirmed: 3
-->

# Technical review — Track 1 (iteration 1)

PASS. No blockers. Every named workflow path, line-anchor, and count the track
asserts resolves against the live repo: the eight in-scope file paths exist, the
`conventions.md:621` / `:626` anchors are exact, the bootstrap-slug grep returns
exactly 47 files across `agents` / `workflow` / `skills`, the `check_dsc_ai_tell`
docstring lists eleven patterns with #1 / #3 / #5 / #6 mapping to the four removed
rules as claimed, and the four-survivor KEEP/REMOVE split (DR2 / DR8) matches the
actual section headings. The five findings are completeness gaps in the
plan-of-work, not correctness errors: two phantom self-references the source file
keeps after its own section is removed, two stale count words, one mirrored
pattern-count edit the consumer move under-specifies, and two suggestion-level
framing imprecisions. None blocks execution; all are reproducible by a Phase B
implementer following DR7's grep-then-scan acceptance contract.

This is a `§1.7(k)` prose-rule opt-out branch (ledger line 1: `s17=opt-out`), so
the five prose criteria supersede the Java criteria. No Java symbol is named in
the track; no `findClass` was run. mcp-steroid was not needed.

## Findings

### T1 [should-fix]
**Certificate**: Premise — house-style.md retains in-file refs to its own removed `## Banned vocabulary` section
**Location**: Track Move 1 (the in-file-reference fix-up list); `house-style.md:359` (`### Padding-based finding criterion`) and `house-style.md:485` (Self-check item 7, `**Structure.**`)
**Issue**: Move 1 enumerates the Self-check edits it will make (items 1, 2, 4, 5, 6) and removes the `§ Plain language` "Reconciliation with § Banned vocabulary" subsection (line 92, covered by DR6), but it does not touch two other in-file references to the section being deleted, both inside KEPT sections:
- `house-style.md:359`, `### Padding-based finding criterion` (under `## Structural rules`): "...a finding only when it also contains one or more padding patterns: **a banned term from § Banned vocabulary**, a pattern from § Banned sentence patterns, or restatement...".
- `house-style.md:485`, Self-check **item 7** (the `**Structure.**` line): "...a >200-word unit is a finding only when it also contains padding — **a banned term from § Banned vocabulary**, a pattern from § Banned sentence patterns, or restatement per § Elegant variation."

After `## Banned vocabulary` is deleted, both lines point at a section that no longer exists — the exact phantom-reference failure DR7 names, except in the source file itself. DR7's manual paraphrase scan covers the *named prose consumers* (other files), and the §1.5 rename-safety grep enumerates pointer sites under `.claude/` + `CLAUDE.md` (which includes `house-style.md`), but Move 1's enumerated edit list does not call these two out, so an implementer working from Move 1 alone would miss them. Move 1 also lists only Self-check items 1/2/4/5/6 by number, skipping item 7.
**Proposed fix**: Add to Move 1: re-point the `### Padding-based finding criterion` rule (line 359) and Self-check item 7 (line 485) so the padding-pattern definition no longer cites `§ Banned vocabulary`. The padding criterion's surviving sources of a "padding pattern" are `§ Banned sentence patterns` and `§ Elegant variation` restatement; drop the banned-vocabulary clause from both sites. Decompose this into the same step that edits `house-style.md`.

### T2 [should-fix]
**Certificate**: Premise — bootstrap-slug carriers hard-code the count word "six"
**Location**: Track Moves 5 and 6; `house-conversation.md:21` and `hooks/house-style-write-reminder.sh:262`
**Issue**: Two always-loaded carriers state the count word "six" next to the slug list the track shrinks to four:
- `house-conversation.md:21` (the default output style, loaded every session): "Apply these **six** sections of `.claude/output-styles/house-style.md`...". Move 5 enumerates dropping the two list bullets (lines 23, 26) and editing lines 24/25, but never says to change "six" → "four" on line 21. The list would then read four bullets under a "six sections" lead — a live self-contradiction in an always-on file.
- `hooks/house-style-write-reminder.sh:262` (the Tier-B reminder body): "...**the six sections** in .claude/output-styles/house-style.md: § Orientation, § Plain language, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline." Move 6 lists the hook as a consumer to re-point, but does not call out the "six" count word, only the slug enumeration.

The track's Invariant on the §1.5 subset says it must "enumerate exactly the four surviving sections," which the slug edits satisfy, but the prose count word is uncaptured in both files.
**Proposed fix**: Add to Move 5 the `house-conversation.md:21` "six" → "four" edit, and to Move 6 the `hooks/house-style-write-reminder.sh:262` "six" → "four" edit, alongside the slug-list trims already named for each file. (Check `conventions.md:624` "The six Tier-B section names are stable headings" for the same count-word drift — it is in scope under Move 2's §1.5 edit and should drop to "four" there too.)

### T3 [should-fix]
**Certificate**: Integration — design-document-rules.md `dsc-ai-tell` row mirrors the checker docstring
**Location**: Track Move 6 (named-prose-consumer re-point); `design-document-rules.md:287`
**Issue**: `design-document-rules.md:287` is the design-doc-facing description of the `dsc-ai-tell` check. It hard-codes the count word "Detects **eleven** `house-style.md` patterns" and spells out all eleven, including the four removed: "Tier-1 vocabulary base words (`§ Tier 1`)", "em-dash density at 3+... (`§ Em-dash discipline`)", "signposting openers (`§ Signposting`)", "copula-led sentences... (`§ Copula avoidance`)". This is the prose mirror of the checker docstring that Move 4 updates ("update the docstring count from eleven to the survivor count"). The track lists `design-document-rules.md` as a named consumer and Move 6 says "re-point every reference to a removed section," but it does not flag that this file carries the *same* eleven-count word and the four removed pattern descriptions that must drop in lockstep with the checker — this is a substantive description edit, not a one-line pointer re-point. Leaving it stale would put the design-doc rule reference out of sync with the shrunk checker (the checker fires seven patterns; the doc would still advertise eleven).
**Proposed fix**: In Move 6, explicitly name `design-document-rules.md:287`: drop the four removed-pattern clauses (`§ Tier 1`, `§ Em-dash discipline`, `§ Signposting`, `§ Copula avoidance`) and change "eleven" to the survivor count, matching the Move 4 checker-docstring edit. Decompose Move 4 and this `design-document-rules.md` edit into the same step (or adjacent steps) so the count stays coherent.

### T4 [suggestion]
**Certificate**: Premise — the em-dash in-file cross-ref the track names does not sit in the claimed section
**Location**: Track Move 1, last sentence; `house-style.md:131` and `:123`
**Issue**: Move 1 says it will "Fix the in-file references the removal would break: **the `§ Banned sentence patterns` note that points at `§ Punctuation and typography` for em dashes**, and any Tier-4 cross-references into the removed sections." There is no such note in `§ Banned sentence patterns`. The only "See § Punctuation and typography" em-dash pointer is at `house-style.md:131`, inside `## Banned vocabulary` Tier 4 ("Em dashes at every clause boundary... See § Punctuation and typography."). Since Tier 4 is part of the whole-section delete (DR2), that pointer vanishes with its container — no separate fix is needed. (Likewise `:123` "It's not X, it's Y." See § Banned sentence patterns" self-deletes with Tier 4.) The Move 1 instruction describes a fix-up target that does not exist where it says, which could send a Phase B implementer hunting for a nonexistent note. Not a correctness risk — the self-deleting refs are handled by the section delete itself — but the plan text is inaccurate.
**Proposed fix**: Reword Move 1's in-file-reference sentence: the em-dash and negative-parallelism cross-refs are inside the removed Tier-4 block and self-delete with the section; the in-file refs that actually need fixing are the ones inside KEPT sections (line 92 reconciliation, and the line 359 / 485 sites from T1). Drop the false "§ Banned sentence patterns note" phrasing.

### T5 [suggestion]
**Certificate**: Integration — two named prose consumers carry no removed-section reference to re-point
**Location**: Track Move 6 file list and `## Context and Orientation` named-consumer list; `agents/readability-auditor.md`, `prompts/design-review.md`
**Issue**: Move 6 says of the named prose consumers (which include `agents/readability-auditor.md` and `prompts/design-review.md`): "re-point every reference to a removed section." Verified by grep + read, neither file carries a removed-section reference:
- `readability-auditor.md` deliberately hard-codes no rule list — line 71: "The auditor reads the live `house-style.md`, so any house-style rule absorbs into your judgment whenever it lands — no rule list is hard-coded here." Its only `§` citations are `§ Orientation` and `§ Plain language` (both KEPT, lines 35-36). Its actual Move-6 work is the *additive* part-3 ownership note (mechanical rules → checker; auditor → judgment), which Move 6 does state separately — so the file is correctly in scope, but the "re-point a removed section" framing does not apply to it.
- `prompts/design-review.md` cites only KEPT document-shape sections (`§ Navigability`, `§ Audience-fit`, `§ Edge cases sub-section required`, `§ References footer shape`, `§ Same-shape sibling consolidation`) and the generic "fetch house-style on demand" pattern. It has nothing to re-point.

DR7's manual scan would correctly classify both as confirm-benign, so no breakage ships. The issue is plan precision: the re-point edit set is over-stated, which can cause wasted edit attempts or a spurious "edit needed" expectation during decomposition.
**Proposed fix**: In Move 6, split the named consumers into two groups: (a) files with an actual removed-section reference to re-point (`skills/ai-tells/SKILL.md`, `agents/design-author.md:71`, `agents/review-workflow-writing-style.md`, `design-document-rules.md`, root `CLAUDE.md` paraphrase refs), and (b) `readability-auditor.md` (additive ownership note only) and `prompts/design-review.md` (confirm-benign, no edit). This also sharpens what the DR7 manual scan must confirm versus edit.

## Evidence base

Twelve of fifteen certificates CONFIRMED; three NOT CONFIRMED (PARTIAL/WRONG) produced T1–T5. All searches via Read + grep on the live repo (`§1.7(k)` opt-out — live files, no staged copy). No PSI / `findClass` (no Java symbols in this track).

#### Premise: the eight in-scope file paths exist
- **Track claim**: `house-style.md`, `house-conversation.md`, `conventions.md`, `ai-tells/SKILL.md`, the hook, `design-mechanical-checks.py`, `test_dsc_ai_tell.py`, the fixture, plus the named prose consumers all exist.
- **Search performed**: `for f in ...; do test -f`.
- **Code location**: all present; `house-style.md` is 492 lines (matches the track's "492 lines" claim).
- **Verdict**: CONFIRMED.

#### Premise: KEEP/REMOVE section split (DR2/DR8)
- **Track claim**: remove `## Banned vocabulary` (whole), `### Em-dash discipline`, `### Signposting`, `### Copula avoidance`, plus the sycophantic-openers and knowledge-cutoff bullets under `§ Banned sentence patterns`; keep four survivors (`§ Orientation`, `§ Plain language`, `§ Banned sentence patterns`, `§ Banned analysis patterns`).
- **Search performed**: `grep -nE '^## |^### '` on house-style.md.
- **Code location**: `## Banned vocabulary` @ :98; `### Em-dash discipline` @ :326 (under `## Punctuation and typography` @ :324); `### Signposting` @ :291 and `### Copula avoidance` @ :164 (both under `## Banned analysis patterns` @ :149); sycophantic-openers bullet @ :142, knowledge-cutoff bullet @ :147 (both under `## Banned sentence patterns` @ :136); survivors `## Orientation` @ :54, `## Plain language` @ :78.
- **Verdict**: CONFIRMED. The surgical-bullet-vs-whole-section distinction in DR2 is accurate.

#### Premise: conventions.md §1.5 anchors `:621` and `:626`
- **Track claim**: Tier-B table row at `:621`, rename-safety grep at `:626`.
- **Search performed**: `nl -ba conventions.md | sed -n '610,640p'`.
- **Code location**: `:621` is the `*.java`, `*.kt` row listing the six sections; `:626` is the `grep -rnE '...'` rename-safety command.
- **Verdict**: CONFIRMED. (Note: `:626` grep alternation already restricts `Orientation`/`Plain language` to `##`/`§` heading form and matches the other four bare — Move 2's "drop the two removed slugs from its alternation" is the correct edit.)

#### Premise: bootstrap-slug grep count (~47 files)
- **Track claim**: ~47 files carry the six-slug line (~21 review agents, ~21 workflow, ~5 skills).
- **Search performed**: `grep -rl 'Em-dash discipline' .claude/agents .claude/workflow .claude/skills | wc -l` plus per-dir.
- **Code location**: exactly 47 total — 20 agents, 22 workflow, 5 skills.
- **Verdict**: CONFIRMED (the `~` hedge covers the 21→20 / 21→22 per-dir drift; the total is exact). Whole-`.claude` count is 53 (adds output-styles, the hook, conventions itself); the track's 47 is scoped to the three slug-carrier dirs, which matches its grep definition.

#### Premise: check_dsc_ai_tell docstring lists eleven patterns; #1/#3/#5/#6 are the removed rules
- **Track claim**: docstring enumerates eleven patterns; patterns 1 (Tier-1 vocab), 3 (em-dash density), 5 (signposting), 6 (copula) map to removed rules.
- **Search performed**: Read `design-mechanical-checks.py:1830-1865`.
- **Code location**: docstring @ :1837-1854. "Eleven patterns fire": #1 Tier-1 banned vocabulary, #3 Em-dash density, #5 Signposting openers, #6 Copula avoidance — exactly the four removed. Survivors: #2/#10 negative parallelism, #4 Title Case, #7 persuasive authority, #8 hyphenated-pair, #9 fragmented header, #11 inflated-abstraction (seven distinct surviving descriptions).
- **Verdict**: CONFIRMED. (Move 4 leaves the new count unstated — "to the survivor count" — correctly, since #2 and #10 are both negative-parallelism; the survivor count is seven.)

#### Premise: test + fixture carry blocks for the four removed patterns
- **Track claim**: `test_dsc_ai_tell.py` and `dsc-ai-tell-fixture.md` need editing to pass against the shrunk checker.
- **Search performed**: grep for tier1/em-dash/signpost/copula in both.
- **Code location**: test EXPECTED groupings @ :75-86 (tier1-vocab, em-dash-density, signposting, copula); plus a heading-scan regression case @ :150-151 (Tier-1 vocab inside an H3). Fixture blocks @ :40 (Tier 1), :55 (Em-dash density), :71 (Signposting), :78 (Copula), and :170 (`### Delve into the holistic foster pattern` H3 — a Tier-1 fixture block).
- **Verdict**: CONFIRMED. Move 4's "drop the cases for the removed patterns" covers the extra heading-scan case (:150) and the H3 fixture block (:170) under its general phrasing; an implementer should be told these two are removed-pattern sites too (orientation note, not a finding).

#### Premise: ai-tells/SKILL.md:3 description hard-codes removed tells
- **Track claim** (DR9): line-3 description names "delve", "foster", "em dash overuse", "knowledge-cutoff disclaimers" (removed) alongside negative parallelism + Title Case (kept).
- **Search performed**: Read SKILL.md:1-35.
- **Code location**: `:3` description contains "delve, ... foster" (vocabulary), "em dash overuse, knowledge-cutoff disclaimers" (punctuation), and "negative parallelism like \"It's not X, it's Y\", ... Title Case headings" (kept).
- **Verdict**: CONFIRMED.

#### Premise: ai-tells SKILL body pointers (`SKILL.md:23`, `:25`, `:26`, `:29`)
- **Track claim** (Move 6): `:23` and the `:29` clause point at removed `§ Banned vocabulary`; `:25` names sycophantic openers (removed bullet); `:26` `§ Punctuation and typography` stays.
- **Search performed**: Read SKILL.md:19-30.
- **Code location**: `:23` "→ `house-style.md § Banned vocabulary`"; `:25` tone fingerprints "(sycophantic openers, throat-clearing, closing phrases)"; `:26` "→ `house-style.md § Punctuation and typography`"; `:29` "stays distinct from `§ Banned vocabulary`".
- **Verdict**: CONFIRMED. (`:25` is a re-point not a delete — throat-clearing and closing phrases survive; `:26` parent section survives, only its em-dash subsection leaves — the track states both correctly.)

#### Premise: house-conversation.md AI-tell-subset line anchors (23/24/25/26)
- **Track claim** (Move 5): drop `## Banned vocabulary` (:23) and `### Em-dash discipline` (:26); re-point copula+signposting on the `## Banned analysis patterns` line (:25) and sycophantic on the `## Banned sentence patterns` line (:24).
- **Search performed**: Read house-conversation.md full.
- **Code location**: :23 `## Banned vocabulary`; :24 `## Banned sentence patterns` (names sycophantic openers); :25 `## Banned analysis patterns` (names copula avoidance, signposting); :26 `### Em-dash discipline`; :27 `## Orientation` (keep); :28 `## Plain language` (keep).
- **Verdict**: CONFIRMED. (Count-word "six" @ :21 uncaptured — see T2.)

#### Premise: house-conversation.md:15 inline chat-only carrier (DR3)
- **Track claim** (DR3/Move 5): line 15 is the inline no-preamble carrier; "make it explicit there if the current wording does not name both" sycophantic openers and signposting.
- **Search performed**: Read house-conversation.md:15.
- **Code location**: :15 "No preamble, no postamble. Skip the \"Great question\" / \"Sure, I can help\" opener and the \"let me know if you need anything else\" closer."
- **Verdict**: CONFIRMED. Line 15 names sycophantic openers but NOT signposting ("Let's dive in"); the Move-5 condition ("if the current wording does not name both") IS met, and Move 5 correctly instructs making it explicit. DR3's risk is carried — no finding.

#### Premise: review-workflow-writing-style.md line anchors (29/30/34/70-71/78)
- **Track claim** (Move 6): em-dash-cap lens @ :30, banned-vocabulary @ :29 and :70-71 and :78, knowledge-cutoff @ :34 are dropped or re-pointed.
- **Search performed**: `nl -ba` :28-36 and :68-80.
- **Code location**: :29 "Banned vocabulary" lens; :30 "Em-dash cap"; :34 "No knowledge-cutoff disclaimers"; :70-71 "Banned vocabulary sweep"; :78 scope-guard referencing `## Banned vocabulary`.
- **Verdict**: CONFIRMED. (Line 73 also names sycophantic openers "Great question!", "I'd be happy to help" — covered by Move 6's general "re-point every reference," though not enumerated; orientation note, not a finding.)

#### Premise: hook tier_b_body @ :262 lists the six sections
- **Track claim** (Context): hook line 262 lists the six section names.
- **Search performed**: `nl -ba` :258-266.
- **Code location**: :262 `tier_b_body=` enumerates "§ Orientation, § Plain language, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline" and "the six sections".
- **Verdict**: CONFIRMED (count word "six" uncaptured — see T2).

#### Integration: design-document-rules.md:287 dsc-ai-tell row
- **Plan claim**: a named prose consumer to re-point.
- **Actual entry point**: `design-document-rules.md:287` — "Detects **eleven** ... patterns" enumerating all four removed rules by `§`-name.
- **Caller analysis**: this is documentation prose, not invoked code; read-only consumer of the rule set.
- **Breaking change risk**: stale count + stale pattern descriptions if Move 6 treats it as a one-line pointer re-point.
- **Verdict**: MISMATCHES (under-specified by the track) → T3.

#### Integration: root CLAUDE.md paraphrase references
- **Plan claim** (named prose consumer for the manual scan): CLAUDE.md references removed rules.
- **Actual entry point**: `CLAUDE.md:93` ("delve, leverage, robust, \"It's not X — it's Y\", em-dash overuse") and `:102` ("banned-vocabulary list, em-dash cap"; cites `§ Structural rules` "Padding-based finding criterion").
- **Caller analysis**: always-loaded project guide; paraphrase refs, not `§`-anchored pointers, so partly invisible to a bare-name grep.
- **Breaking change risk**: handled — the track correctly routes CLAUDE.md through DR7's manual paraphrase scan, which is the right tool for these non-anchored refs.
- **Verdict**: MATCHES (correctly in scope).

#### Integration: readability-auditor.md and prompts/design-review.md
- **Plan claim** (Move 6): re-point every reference to a removed section in these named consumers.
- **Actual entry point**: `readability-auditor.md:71` ("no rule list is hard-coded here"; only `§ Orientation`/`§ Plain language` cited @ :35-36); `design-review.md` cites only KEPT document-shape sections (`§ Navigability`, `§ Audience-fit`, `§ Edge cases`, `§ References footer shape`, `§ Same-shape sibling consolidation`).
- **Caller analysis**: read-only prose consumers; neither holds a removed-section reference.
- **Breaking change risk**: none — DR7's manual scan classifies both confirm-benign; the only real `readability-auditor` work is the additive ownership note (separately stated).
- **Verdict**: MISMATCHES (over-stated re-point set) → T5.
