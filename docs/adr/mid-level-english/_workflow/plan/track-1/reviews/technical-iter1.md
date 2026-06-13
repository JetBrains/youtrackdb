<!-- MANIFEST
review_type: technical
phase: 3A
track: "Track 1: Author the rule and update the canonical homes, core docs, hook, and CLAUDE.md"
iteration: 1
findings: 2
blockers: 0
verdict: PASS_WITH_FINDINGS
index:
  - id: T1
    sev: should-fix
    anchor: "T1"
    loc: ".claude/hooks/house-style-write-reminder.sh:262 + .claude/scripts/tests/test_house_style_hook.py:798"
    cert: "Edge case: hook tier_b_body six-slug + carve note vs 500-char cap"
    basis: "tier_b_body=449 chars now; cap=500 enforced by test_18; slug add fits (468) but D3-mandated carve note (~80 chars) overshoots to ~548"
  - id: T2
    sev: suggestion
    anchor: "T2"
    loc: "track-1.md ## Context and Orientation; .claude/scripts/design-mechanical-checks.py"
    cert: "Premise: completeness of the in-scope exclusion set"
    basis: "reconciliation grep surfaces design-mechanical-checks.py; track names only design-document-rules.md (CR2) as a considered exclusion"
evidence_base:
  premises: 9
  edge_cases: 1
  integrations: 0
  confirmed: 8
  not_confirmed: 2
-->

## Findings

### T1 [should-fix]
**Certificate**: Edge case: hook `tier_b_body` six-slug + carve note vs 500-char cap
**Location**: Track `## Plan of Work` step 5 and D3 risk note (`track-1.md:92`, `:38`); `.claude/hooks/house-style-write-reminder.sh:262`; `.claude/scripts/tests/test_house_style_hook.py:798` (`PER_BODY_CHAR_CAP = 500`), `:833` (`test_18_reminder_body_length_budget`).
**Issue**: The track tells the implementer to "add `§ Plain language` to `tier_b_body` **with a carve note**, flip 'five' → 'six'" (step 5), and D3 makes the carve note mandatory ("the hook reminder carries the matching carve note"). The current `tier_b_body` string is exactly 449 chars, and the test enforces a 500-char per-body cap (`test_18_reminder_body_length_budget`, `PER_BODY_CHAR_CAP = 500`). The bare slug add (`, § Plain language` ≈ 19 chars; the "five"→"six" flip is net-zero) lands at ~468 and fits. But the D3-required carve note — the shortest faithful form is roughly "Plain language drops the short-sentence / clause-nesting move at comment scale." (~80 chars) — pushes the body to ~548, over the 500 cap. The track's `## Validation and Acceptance` asserts `test_house_style_hook.py` passes, yet the track has an unstated collision between two of its own constraints: adding the carve note as D3 demands fails `test_18`, while trimming to satisfy the cap silently drops the mandated carve note. The implementer hits this with no guidance on which constraint yields.
**Proposed fix**: In step 5 (or D3's risk note), tell the implementer that adding the carve note requires trimming existing `tier_b_body` text to stay under the 500-char cap that `test_18_reminder_body_length_budget` enforces — e.g., fold the existing Orientation carve ("§ Orientation bans out-of-file assumptions in code comments, not in-file terseness.") and the new Plain-language carve into one shorter combined sentence, or shorten the lead clause, so the six-slug body plus both carves stays ≤500. Add the per-body cap to the track's invariant line alongside "every count reads six". Reference: the cap is documented in the hook comment at `:255` and machine-checked at `test_house_style_hook.py:833`.

### T2 [suggestion]
**Certificate**: Premise: completeness of the in-scope exclusion set
**Location**: Track `## Context and Orientation` (`track-1.md:77`, the CR2 exclusion paragraph) and `## Interfaces and Dependencies` reconciliation grep (`track-1.md:144`); `.claude/scripts/design-mechanical-checks.py`.
**Issue**: The track's Phase-A reconciliation grep (`grep -rln 'Banned analysis patterns\|five AI-tell\|five Tier-B\|five sections' .claude/ CLAUDE.md`) returns, after excluding the Track-2/3 surfaces (`prompts/`, `skills/`, `agents/`), exactly seventeen files: the track's sixteen in-scope files plus `design-document-rules.md` (which the track explicitly excludes via CR2) and `design-mechanical-checks.py` (which the track does not name at all). `design-mechanical-checks.py` matches the grep only because the `dsc-ai-tell` regex implementation cites individual section names (`§ Banned analysis patterns` at `:182`/`:1854`, `§ Em-dash discipline` at `:1844`/`:2067`, `## Orientation` at `:202`); it carries no five-slug AI-tell-subset enumeration and no numeric "five" subset count. Under judgment-only D2 (`## Plain language` adds no mechanical pattern), it has nothing to flip — the identical logic CR2 applies to `design-document-rules.md`. The exclusion is correct, but it is silent: a Phase-A implementer running the reconciliation grep will see the `.py` in the results and has no written note explaining why it is out of scope, inviting a wrong "missed site" edit or a needless escalation.
**Proposed fix**: Add one line to the CR2 exclusion paragraph in `## Context and Orientation` naming `design-mechanical-checks.py` as a second judgment-only-D2 exclusion (its `dsc-ai-tell` rule references individual section names but carries no subset enumeration to flip), so the reconciliation grep's two non-in-scope hits are both accounted for in the track text. No scope change — purely a note to close the completeness gap for the implementer.

## Evidence base

#### Premise: `house-style.md:20` carries a numeric "five" subset count
- **Track claim**: `track-1.md:71` — "The line-20 sentence ('reuses the five AI-tell sections') carries a numeric count." Plan-of-Work step 1 flips `:20` to "six".
- **Search performed**: `grep -nE 'five' .claude/output-styles/house-style.md`; Read `:20`.
- **Code location**: `.claude/output-styles/house-style.md:20`
- **Actual behavior**: Line 20 reads "...the default conversation style `house-conversation.md`, which reuses the five AI-tell sections for terminal replies...". Numeric "five" present at the cited line.
- **Verdict**: CONFIRMED
- **Detail**: Exact content at exact line.

#### Premise: `house-conversation.md:21` carries the count and `:23`–`:27` list exactly five subset slugs
- **Track claim**: `track-1.md:73` — "Lines 21-27 list the five subset sections as bullets under 'Apply these five sections'." Plan-of-Work step 2 adds a sixth bullet and flips `:21`.
- **Search performed**: full Read of `house-conversation.md` (35 lines).
- **Code location**: `.claude/output-styles/house-conversation.md:21`–`:27`
- **Actual behavior**: `:21` = "Apply these five sections of `.claude/output-styles/house-style.md`..."; bullets `:23`–`:27` enumerate `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation` — exactly five. The five→six flip is well-defined.
- **Verdict**: CONFIRMED

#### Premise: `conventions.md §1.5` Tier-B cell (`:567`), count (`:570`), and Orientation restatement (`:574`–`:581`)
- **Track claim**: `track-1.md:75` — table at `:567` lists the five slugs; `:570` says "The five Tier-B section names"; `:574`–`:581` is the Orientation code-comment restatement.
- **Search performed**: `grep -nE '^## 1\.5|Orientation.*Banned vocabulary|five Tier-B section names|out-of-file assumptions'`; Read `:545`–`:585`.
- **Code location**: `.claude/workflow/conventions.md:547` (§1.5 heading), `:567` (Tier-B table row), `:570` (count), `:574`–`:581` (restatement)
- **Actual behavior**: `:567` row lists `§ Orientation, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline` — five. `:570` = "The five Tier-B section names are stable headings after YTDB-836". `:574` opens the restatement paragraph; `:580` carries "This bans out-of-file assumptions, not in-file terseness". All three sub-sites confirmed.
- **Verdict**: CONFIRMED

#### Premise: the four full-enumeration core docs each carry a numeric "five" and a five-slug enumeration
- **Track claim**: `track-1.md:77`, `:91` — `commit-conventions.md:191`, `step-implementation.md:1038` carry numeric counts; `implementer-rules.md:1100`, `episode-format-reference.md:47` carry enumerations.
- **Search performed**: per-file `grep -nE 'five'` + `grep -nE 'Banned analysis patterns'`; Read each enumeration block.
- **Code location**: `commit-conventions.md:191` (count) `+ :191`–`:194` (enum); `step-implementation.md:1038` (count) `+ :1038`–`:1041` (enum); `implementer-rules.md:1102` (count) `+ :1102`–`:1106` (enum); `episode-format-reference.md:44` (count) `+ :44`–`:47` (enum)
- **Actual behavior**: Each block has both "The five AI-tell subset section slugs..."/"the five section slugs..." and the five-slug list (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`). Line numbers drift slightly from the track's hints (`implementer-rules` enum at `:1102` vs cited `:1100`; `episode-format` at `:44`–`:47` vs cited `:47`), but content is present where claimed — within the track's stated "treat the line number as a hint" tolerance.
- **Verdict**: CONFIRMED

#### Premise: the six house-style-declaration core docs each carry a five-slug enumeration at the cited line
- **Track claim**: `track-1.md:77` — `workflow.md:53`, `review-iteration.md:30`, `design-decision-escalation.md:19`, `inline-replanning.md:18`, `mid-phase-handoff.md:34`, `review-mode.md:41` each cite the subset by a house-style declaration line.
- **Search performed**: Read `±3` lines around each cited line.
- **Code location**: the six cited lines, each verified.
- **Actual behavior**: Each is the identical "House style for chat-scale prose" blockquote listing `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation` — five slugs. Line numbers exact at all six.
- **Verdict**: CONFIRMED
- **Detail**: These six carry the enumeration but no numeric "five", so they get the slug add only — consistent with Plan-of-Work step 4 ("add the sixth slug to each enumeration and flip any numeric 'five'"). Count = 4 enum-with-count + 6 declaration + conventions.md = 11 core docs, matching the track's "11 core workflow docs".

#### Premise: `CLAUDE.md:104` is a four-item parenthetical that omits Orientation
- **Track claim**: `track-1.md:81`, D6 — "`CLAUDE.md:104` names the subset as a four-item parenthetical and omits Orientation (a pre-existing lag)."
- **Search performed**: Read `CLAUDE.md:100`–`:108`.
- **Code location**: `CLAUDE.md:104`
- **Actual behavior**: Line 104 ("Two layers, two files...") contains "applies the AI-tell subset (banned vocabulary, banned sentence patterns, banned analysis patterns, em-dash discipline) to every reply" — exactly four items, Orientation absent. Confirms both the location and the lag.
- **Verdict**: CONFIRMED

#### Premise: hook `tier_b_body` (`:262`) enumerates five `§ ` slugs + numeric "five"; comment `:256` says "five Tier-B"
- **Track claim**: `track-1.md:79` — the hook's `tier_b_body` at `:262` enumerates the five slugs plus the numeric "five" and the Orientation carve; `:256` comment also says "five Tier-B".
- **Search performed**: `grep -nE 'five|tier_b_body'`; Read `:250`–`:270`.
- **Code location**: `.claude/hooks/house-style-write-reminder.sh:256` (comment), `:262` (`tier_b_body`)
- **Actual behavior**: `:256` = "names the five Tier-B section headings". `:262` body = "...and the five sections in ...house-style.md: § Orientation, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline ... § Orientation bans out-of-file assumptions in code comments, not in-file terseness." Five `§ ` slugs + numeric "five" + Orientation carve, all present.
- **Verdict**: CONFIRMED

#### Premise: test pins five `## ` slugs in `TIER_B_HEADINGS`; `test_16_section_name_guard` asserts each exists in `house-style.md`
- **Track claim**: `track-1.md:79` — `TIER_B_HEADINGS` pins five `## ` slugs and `test_16_section_name_guard` asserts each exists in `house-style.md`. Plan-of-Work ordering constraint: section must exist before the test change.
- **Search performed**: `grep -nE 'TIER_B_HEADINGS|test_16'`; Read `:693`–`:725`.
- **Code location**: `.claude/scripts/tests/test_house_style_hook.py:693`–`:698` (`TIER_B_HEADINGS`), `:702`–`:735` (`test_16`), `:865` (registry)
- **Actual behavior**: `TIER_B_HEADINGS` = `["## Orientation", "## Banned vocabulary", "## Banned sentence patterns", "## Banned analysis patterns", "### Em-dash discipline"]` — five. `test_16_section_name_guard` compiles `^<heading>\s*$` (MULTILINE) per heading and asserts it matches `HOUSE_STYLE_MD`; it is in the `TESTS` registry (`:865`). The anchored match means adding `## Plain language` to `TIER_B_HEADINGS` requires a literal start-of-line `## Plain language` heading in `house-style.md` — which Plan-of-Work step 1 authors. The ordering constraint (step 1 before step 5) is correctly stated and necessary.
- **Verdict**: CONFIRMED

#### Premise (CR2): `design-document-rules.md` has no five-slug subset enumeration or numeric "five" subset count to flip
- **Track claim**: `track-1.md:77` — its only house-style touchpoint is the `dsc-ai-tell` regex-rule row, which gains no pattern under judgment-only D2, so nothing to flip.
- **Search performed**: `grep -nE 'five|Banned analysis patterns|dsc-ai-tell|AI-tell subset'`.
- **Code location**: `.claude/workflow/design-document-rules.md:277` ("all five required elements" = Overview elements), `:284` (the `dsc-ai-tell` row enumerating *eleven* patterns by `§ Section`)
- **Actual behavior**: Line 277's "five" is the `## Overview` required-element count, unrelated to the AI-tell subset. Line 284 is the `dsc-ai-tell` row citing eleven mechanical patterns by individual `§ Section` name — not a five-slug subset enumeration. No subset enumeration and no numeric subset count exist. CR2 exclusion is correct.
- **Verdict**: CONFIRMED

#### Premise: the track's 16-file in-scope set is complete for core workflow docs
- **Track claim**: `track-1.md:144` — "the exact in-scope set is derived by `grep -rln 'Banned analysis patterns\|five AI-tell\|five Tier-B\|five sections' .claude/ CLAUDE.md` at Phase A and reconciled against this list (~16 files)."
- **Search performed**: ran the exact grep; filtered out `prompts/`, `skills/`, `agents/` (Tracks 2/3); iterated every `.claude/workflow/*.md` for the enumeration signature.
- **Code location**: 17 non-Track-2/3 enumeration-matching files.
- **Actual behavior**: The 17 = the track's 16 in-scope files + `design-document-rules.md` (CR2-excluded) + `design-mechanical-checks.py` (unnamed, excluded by the same D2 logic — carries individual `§ Section` references for the `dsc-ai-tell` regex but no five-slug subset enumeration). No core workflow `.md` enumeration site is missed. The numeric-"five" sweep surfaces only unrelated counts elsewhere (five verdicts, five-key JSON, five prose lenses, five Overview elements, five numbered sub-steps) — none is the AI-tell subset count.
- **Verdict**: PARTIAL
- **Detail**: The set is complete and correct, but `design-mechanical-checks.py` is an unnamed exclusion the reconciliation grep will surface — see T2 (suggestion). No missed in-scope file.

#### Edge case: six-slug `tier_b_body` plus the D3 carve note vs the 500-char per-body cap
- **Trigger**: implementer applies Plan-of-Work step 5 (add `§ Plain language` + the D3-mandated carve note + "five"→"six") to `tier_b_body`.
- **Code path trace**:
  1. Current `tier_b_body` string value @ `house-style-write-reminder.sh:262` — measured 449 chars.
  2. Slug add `, § Plain language` (~19 chars) + net-zero "five"→"six" → ~468 chars. Under cap.
  3. D3 carve note (`track-1.md:38`, `:92`; plan D3 `:76`) is mandatory; shortest faithful form ~80 chars → ~548 chars.
  4. `test_18_reminder_body_length_budget` @ `test_house_style_hook.py:833` checks `len(body) > PER_BODY_CHAR_CAP` (`:798`, 500) and fails.
- **Outcome**: Test failure on the per-body cap if the carve note is added as D3 requires; or a silently-dropped D3 carve note if the implementer trims to fit. The track's two constraints (D3 carve note + passing test suite) collide with no stated resolution.
- **Track coverage**: no — neither step 5 nor D3 mentions the 500-char cap or instructs trimming. See T1 (should-fix).
