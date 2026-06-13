<!-- MANIFEST
review_type: consistency
phase: 2
iter: 1
tier: lite
design_present: false
findings: 2
blockers: 0
should_fix: 2
suggestions: 0
verdict: pass-with-findings
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1 "
    loc: "track-2.md:37 (## Context and Orientation); plan-implementation.md:34 (Component Map SK row)"
    cert: "Ref: ai-tells/SKILL.md subset citation"
    basis: current-state
    class: mechanical
  - id: CR2
    sev: should-fix
    anchor: "### CR2 "
    loc: "implementation-plan.md:33 (Component Map CD row) + track-1.md:106 (Validation) vs design-document-rules.md:284"
    cert: "Ref: design-document-rules.md:284 dsc-ai-tell row"
    basis: current-state
    class: design-decision
evidence_base: "22 Ref certificates over plan + 3 track files + live .claude/** files. 20 MATCHES, 2 PARTIAL. Tier line present (lite). All line numbers, counts (11 prompts, 6 skills, 19+1=20 agents, 12 core docs), spans (house-style.md 471 / :54 / :455; house-conversation.md 35 / :21-27; conventions.md §1.5 :547-582 / :567 / :570 / :574-581), the hook tier_b_body :256/:262, TIER_B_HEADINGS five-slug pin, CLAUDE.md:104 four-item lag, and the uniformly-five current state all verified. Target ## Plain language section confirmed absent everywhere (reachable). Two PARTIALs are imprecise current-state characterizations of citation-shape, not wrong line numbers."
-->

## Findings

### CR1 [should-fix]
**Certificate**: Ref: ai-tells/SKILL.md subset citation
**Location**: `track-2.md:37` (`## Context and Orientation`), echoed in the plan Component Map SK row (`implementation-plan.md:34`) and Track 2 Plan-of-Work step 3 (`track-2.md:44`).
**Issue**: Track 2's Context describes `ai-tells/SKILL.md` as a site that "cites only 'Banned vocabulary' by name." The live file cites several house-style sections, not one, and it is not a five-slug subset-enumeration site at all.
**Evidence**: `ai-tells/SKILL.md` has a `## Catalogue lookups` section (`:20`–`:28`) that maps each AI-tell fingerprint category to a house-style section: `§ Banned vocabulary` (`:23`), `§ Structural rules` and `§ Banned sentence patterns` (`:24`), `§ Banned sentence patterns` sub-bullets (`:25`), `§ Punctuation and typography` (`:26`), `§ Banned analysis patterns` (`:27`), and `§ Orientation` (`:28`). It cites six sections, two of which (`§ Structural rules`, `§ Punctuation and typography`) are outside the five-slug subset. It carries no "the five AI-tell subset section slugs are …" enumeration and no "five" count. So the current-state claim "cites only 'Banned vocabulary' by name" is wrong on two points: it cites more than one section, and the citation shape is a fingerprint→section catalogue, not a subset enumeration. Track 2 already flags the ai-tells edit as an open Phase-A question ("it may name only 'Banned vocabulary'; confirm at Phase A"), so the executor will re-derive the truth, but the Context sentence states the inaccurate characterization as fact rather than as the question it is.
**Proposed fix**: In `track-2.md:37`, change the parenthetical from "the last cites only 'Banned vocabulary' by name, so it may need no slug edit; confirm at Phase A" to a phrasing that matches the file: e.g. "the last cites the subset sections as a fingerprint→section catalogue (`## Catalogue lookups`, not the five-slug preamble), so it has no five-slug enumeration to extend; confirm at Phase A whether the catalogue should gain a `## Plain language` row." No change to scope or goals.
**Classification**: mechanical
**Justification**: current-state claim about an existing file's citation shape, single unambiguous correct rendering (the catalogue cites six sections, not one), fix updates only the description and preserves the plan's already-deferred Phase-A decision.

### CR2 [should-fix]
**Certificate**: Ref: design-document-rules.md:284 dsc-ai-tell row
**Location**: plan Component Map CD row (`implementation-plan.md:33`, "12 core workflow docs — subset enumeration / count") and Track 1 Validation (`track-1.md:106`, "Every subset enumeration and numeric count this track touches reads six"), against `design-document-rules.md:284`. Track 1 lists this file in scope at `track-1.md:136` as "house-style reference (`:284`)".
**Issue**: `design-document-rules.md:284` is grouped with the other eleven core docs as a "subset enumeration / count" site, but it is neither. It is the `dsc-ai-tell` regex-rule table row; it cites individual house-style sections as the rationale for each regex pattern and carries no five-slug subset enumeration and no "five" count. What Track 1 is supposed to edit there is undefined: there is no slug list to extend, and `## Plain language` has no regex fingerprint to add (plan D2 is judgment-only).
**Evidence**: `design-document-rules.md:284` reads "`dsc-ai-tell` AI-tell detection | Detects eleven `house-style.md` patterns …" and cites `§ Tier 1`, `§ Banned sentence patterns`, `§ Em-dash discipline`, `§ Title Case headings forbidden`, `§ Signposting`, `§ Copula avoidance`, `§ Persuasive authority tropes`, `§ Hyphenated word-pair overuse`, `§ Fragmented headers`, `§ Banned analysis patterns` — eleven regex patterns, not the five-slug subset. A `grep -n 'five AI-tell\|five Tier-B\|AI-tell subset section slugs\|five sections'` over the whole file returns nothing: there is no subset enumeration or "five" count anywhere in `design-document-rules.md`. By contrast, Track 1's Plan-of-Work step 4 (`track-1.md:91`) names only `commit-conventions.md:191` and `step-implementation.md:1038` as the "five → six" count sites, which is internally consistent. The looseness is confined to the Component Map label and the Validation bullet, which sweep `design-document-rules.md:284` into "subset enumeration / count" when its real role is a house-style-section reference with no enumeration to flip. Because `## Plain language` is judgment-only with no regex fingerprint (D2), it is genuinely unclear whether Track 1 should touch `:284` at all; if not, including it among the twelve overstates the edit set by one.
**Proposed fix**: Either (a) keep `design-document-rules.md:284` in scope but state explicitly in Track 1 that the edit there is a no-op for this flip (the `dsc-ai-tell` row stays at eleven regex patterns because Plain language has no fingerprint per D2), and add the carve to the Validation bullet so "every enumeration reads six" does not falsely cover it; or (b) drop `design-document-rules.md:284` from the twelve and from Track 1 in-scope, reducing the count to eleven core docs, since it has nothing to flip. Picking between (a) and (b) is a planner call (it changes the stated edit set), so escalate.
**Classification**: design-decision
**Justification**: multiple plausible fix renderings (keep-with-no-op-note vs drop-from-scope), and the choice changes the plan's stated edit set / file count — the orchestrator cannot pick without a planner decision (Classification rules: "Multiple plausible fix renderings exist … escalate").

## Evidence base

### Plan ↔ Code certificates (current-state claims about live files)

#### Ref: implementation-plan.md tier line (D18)
- **Document claim**: `implementation-plan.md:6` reads "**Change tier:** lite — matched categories: none".
- **Search performed**: Read `implementation-plan.md:6`.
- **Code location**: `implementation-plan.md:6`.
- **Actual signature/role**: "**Change tier:** lite — matched categories: none".
- **Verdict**: MATCHES
- **Detail**: Tier-line-presence check passes; tier confirmed `lite`; design-presence guard applied (no `design.md` on disk; DESIGN axes skipped).

#### Ref: house-style.md mid-level reader phrase (:6, :42)
- **Document claim**: plan/track-1 say the style assumes a "mid-level Java/database reader" at `house-style.md:6` and `:42`.
- **Search performed**: Read `house-style.md:6`, `:42`.
- **Code location**: `house-style.md:6`, `:42`.
- **Actual signature/role**: `:6` "mid-level Java and database fluency"; `:42` "Calibrate assumed knowledge to a mid-level Java database developer".
- **Verdict**: MATCHES
- **Detail**: Both lines carry the mid-level-Java/database concept the plan paraphrases. The plan's "reader" wording is a paraphrase of the two anchor lines, not a literal-string claim; the cited lines are correct.

#### Ref: house-style.md:20 "reuses the five AI-tell sections" count
- **Document claim**: track-1 `:71` says line-20 carries a numeric count ("reuses the five AI-tell sections"); plan will flip it to six.
- **Search performed**: Read `house-style.md:20`.
- **Code location**: `house-style.md:20`.
- **Actual signature/role**: "… the default conversation style `house-conversation.md`, which reuses the five AI-tell sections for terminal replies …".
- **Verdict**: MATCHES
- **Detail**: Current-state "five" count present at `:20`; target flip to six is reachable.

#### Ref: house-style.md ## Orientation location (:54)
- **Document claim**: `## Orientation` sits at `:54`; the new section goes after it.
- **Search performed**: Read `house-style.md:54`.
- **Code location**: `house-style.md:54`.
- **Actual signature/role**: `:54` is `## Orientation`.
- **Verdict**: MATCHES

#### Ref: house-style.md ## Self-check location (:455) + item 8
- **Document claim**: `## Self-check` at `:455`; item 8 is the Orientation check.
- **Search performed**: Read `house-style.md:448`–`:471`.
- **Code location**: `house-style.md:455` (`## Self-check`), `:466` (item 8).
- **Actual signature/role**: `:455` `## Self-check`; item 8 (`:466`) "**Orientation.** Every prose surface …".
- **Verdict**: MATCHES

#### Ref: house-style.md total line count (471)
- **Document claim**: track-1 `:71` says house-style.md is 471 lines.
- **Search performed**: `wc -l .claude/output-styles/house-style.md`.
- **Code location**: file end.
- **Actual signature/role**: 471 lines.
- **Verdict**: MATCHES

#### Ref: house-conversation.md total lines (35) + subset bullets (:21-:27)
- **Document claim**: track-1 `:73` says house-conversation.md is 35 lines with the five subset bullets at `:21`–`:27` under "Apply these five sections".
- **Search performed**: Read full file; `wc -l`.
- **Code location**: `house-conversation.md:19`–`:29`.
- **Actual signature/role**: 35 lines; `:19` "## AI-tell subset (applies to every chat reply)"; `:21` "Apply these five sections …"; `:22`–`:27` enumerate `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`.
- **Verdict**: MATCHES

#### Ref: conventions.md §1.5 span (:547-:582)
- **Document claim**: §1.5 spans `:547`–`:582`.
- **Search performed**: Read `conventions.md:545`–`:589`.
- **Code location**: `:547` (`## 1.5 Writing style …`) through `:581`, separator `---` at `:583`.
- **Actual signature/role**: section heading at `:547`; last content line `:581`; `---` at `:583`.
- **Verdict**: MATCHES
- **Detail**: The `:582` upper bound is the blank line before the `---`; the span is accurate.

#### Ref: conventions.md §1.5 Tier-B table row (:567)
- **Document claim**: the `*.java`/`*.kt` Tier-B row at `:567` lists the five slugs in its "Sections that apply" cell.
- **Search performed**: Read `conventions.md:563`–`:568`.
- **Code location**: `conventions.md:567`.
- **Actual signature/role**: "… AI-tell subset | `§ Orientation`, `§ Banned vocabulary`, `§ Banned sentence patterns`, `§ Banned analysis patterns`, `§ Em-dash discipline` (H3 nested under `§ Punctuation and typography`)".
- **Verdict**: MATCHES

#### Ref: conventions.md §1.5 "five Tier-B section names" (:570)
- **Document claim**: `:570` says "The five Tier-B section names".
- **Search performed**: Read `conventions.md:570`.
- **Code location**: `conventions.md:570`.
- **Actual signature/role**: "The five Tier-B section names are stable headings after YTDB-836 …".
- **Verdict**: MATCHES

#### Ref: conventions.md §1.5 Orientation Tier-B restatement (:574-:581)
- **Document claim**: `:574`–`:581` is the Orientation code-comment restatement; D3 adds a parallel plain-language paragraph here.
- **Search performed**: Read `conventions.md:574`–`:581`.
- **Code location**: `conventions.md:574`–`:581`.
- **Actual signature/role**: "For the `*.java` / `*.kt` Tier-B surface, the `§ Orientation` floor is restated … This bans out-of-file assumptions, not in-file terseness; it is not a license to add tutorial comments."
- **Verdict**: MATCHES

#### Ref: commit-conventions.md:191 (enumeration + count)
- **Document claim**: `:191` carries the subset enumeration / "five" count.
- **Search performed**: Read `commit-conventions.md:188`–`:196`.
- **Code location**: `:191` ("The five AI-tell subset"), slugs `:192`–`:194`.
- **Actual signature/role**: five-slug list present; count "five" at `:191`.
- **Verdict**: MATCHES

#### Ref: step-implementation.md:1038 (enumeration + count)
- **Document claim**: `:1038` carries the subset enumeration / "five" count.
- **Search performed**: Read `step-implementation.md:1038`.
- **Code location**: `:1038` "The five AI-tell subset section slugs to apply are".
- **Actual signature/role**: count "five" + enumeration starts here.
- **Verdict**: MATCHES

#### Ref: implementer-rules.md:1100 (enumeration)
- **Document claim**: `:1100` is an enumeration site.
- **Search performed**: Read `implementer-rules.md:1096`–`:1106`.
- **Code location**: paragraph at `:1100`; "five section slugs" at `:1102`, slugs `:1103`–`:1105`.
- **Actual signature/role**: "Tier B (AI-tell subset …) … the five section slugs that make up the Tier-B AI-tell subset are `## Banned vocabulary` … and `## Orientation`."
- **Verdict**: MATCHES
- **Detail**: `:1100` anchors the paragraph; the "five" count and five-slug list sit at `:1102`–`:1105`. Reference is to the right paragraph; enumeration present and currently five.

#### Ref: episode-format-reference.md:47 (enumeration)
- **Document claim**: `:47` is an enumeration site.
- **Search performed**: Read `episode-format-reference.md:42`–`:48`.
- **Code location**: enumeration `:44`–`:47`; "The five" at `:44`, slugs `:45`–`:47`.
- **Actual signature/role**: five-slug list, last line `:47` (`### Em-dash discipline`, and `## Orientation`).
- **Verdict**: MATCHES

#### Ref: workflow.md:53 / review-iteration.md:30 / design-decision-escalation.md:19 / inline-replanning.md:18 / mid-phase-handoff.md:34 / review-mode.md:41 (house-style declaration lines)
- **Document claim**: each cited line carries the five-slug house-style declaration ("the AI-tell subset … `## Banned vocabulary` … `## Orientation`").
- **Search performed**: `sed -n` of each cited line.
- **Code location**: each at the cited line.
- **Actual signature/role**: all six carry the identical "House style for chat-scale prose … `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, and `## Orientation`" declaration block.
- **Verdict**: MATCHES

#### Ref: design-document-rules.md:284 (dsc-ai-tell row)
- **Document claim**: track-1 lists it as "house-style reference (`:284`)"; plan Component Map groups it among "12 core workflow docs — subset enumeration / count".
- **Search performed**: Read `design-document-rules.md:284`; `grep -n 'five AI-tell\|AI-tell subset section slugs\|five sections'` over the whole file.
- **Code location**: `:284`.
- **Actual signature/role**: `dsc-ai-tell` regex-rule table row, "Detects eleven `house-style.md` patterns …", citing eleven individual `§` sections as per-pattern rationale. No five-slug subset enumeration and no "five" count anywhere in the file.
- **Verdict**: PARTIAL
- **Detail**: The reference exists and cites house-style as claimed, but the site is not a "subset enumeration / count" — it is a regex-rule description. Drives CR2. (Track 1's in-scope label "house-style reference" is accurate; the Component Map / Validation grouping is the imprecise part.)

#### Ref: house-style-write-reminder.sh tier_b_body (:262) + comment (:256)
- **Document claim**: `tier_b_body` near `:262` enumerates five `§ ` slugs + numeric "five" + Orientation carve; comment near `:256` says "five Tier-B".
- **Search performed**: Read `house-style-write-reminder.sh:248`–`:277`.
- **Code location**: `:256` (comment), `:262` (`tier_b_body`).
- **Actual signature/role**: `:256` "names the five Tier-B section headings"; `:262` "the five sections in .claude/output-styles/house-style.md: § Orientation, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline … § Orientation bans out-of-file assumptions in code comments, not in-file terseness."
- **Verdict**: MATCHES
- **Detail**: Five `§` slugs, numeric "five", and the Orientation carve all present as claimed.

#### Ref: test_house_style_hook.py TIER_B_HEADINGS + test_16_section_name_guard + prefix-match path tests
- **Document claim**: `TIER_B_HEADINGS` holds five `## ` slugs; `test_16_section_name_guard` keys on the slug list; the Tier-B path tests match on a prefix so no new test logic is needed for the flip.
- **Search performed**: Read `test_house_style_hook.py:53`–`:63`, `:693`–`:727`; grep `startswith`/`TIER_B_PREFIX`.
- **Code location**: `:693`–`:699` (`TIER_B_HEADINGS`), `:702`–`:727` (`test_16`), `:60`–`:63` (`TIER_B_PREFIX`), path tests `:208`/`:231`/`:254`/etc.
- **Actual signature/role**: `TIER_B_HEADINGS` = `## Orientation`, `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline` (five entries). `test_16` iterates `TIER_B_HEADINGS` and asserts each exists in house-style.md. Path tests assert `body.startswith(TIER_B_PREFIX)` where `TIER_B_PREFIX` is the stable lead string ("House style AI-tell subset applies to code comments and Javadoc on this Java/Kotlin surface."), not the slug enumeration.
- **Verdict**: MATCHES
- **Detail**: Adding a sixth slug touches only `TIER_B_HEADINGS` (test_16) and the `tier_a_body`/`tier_b_body` source-capture test (`:800`–`:824`); path tests are prefix-keyed and unaffected. Confirms D2's "no new test logic is needed" claim.

#### Ref: CLAUDE.md:104 four-item subset parenthetical (D6 lag)
- **Document claim**: `CLAUDE.md:104` lists the subset as a four-item parenthetical that omits `## Orientation` (pre-existing lag D6 fixes).
- **Search performed**: Read `CLAUDE.md:100`–`:105`.
- **Code location**: `CLAUDE.md:104`.
- **Actual signature/role**: "… applies the AI-tell subset (banned vocabulary, banned sentence patterns, banned analysis patterns, em-dash discipline) to every reply …".
- **Verdict**: MATCHES
- **Detail**: Four items, omits Orientation — confirms the lag. This is the one site that diverges from the otherwise-uniform "five" state, exactly as D6 records.

### Track 2 certificates

#### Ref: 11 workflow prompts count + subset enumeration
- **Document claim**: 11 prompts under `.claude/workflow/prompts/` each carry the five-slug preamble.
- **Search performed**: `ls .claude/workflow/prompts/*.md | wc -l`; `grep -rln 'Banned analysis patterns' .claude/workflow/prompts/*.md`.
- **Code location**: `.claude/workflow/prompts/`.
- **Actual signature/role**: exactly 11 `.md` files, all 11 enumerate the five-slug subset; the set matches track-2's in-scope list one-for-one.
- **Verdict**: MATCHES

#### Ref: adversarial-review.md:43 / technical-review.md:31 / consistency-review.md:96 (cited example preambles)
- **Document claim**: these three carry the "five AI-tell subset section slugs to apply are …" preamble.
- **Search performed**: `sed -n` of each cited line.
- **Code location**: each at the cited line.
- **Actual signature/role**: identical five-slug preamble sentence on each.
- **Verdict**: MATCHES

#### Ref: design-review.md:458 (cold-read Human-reader / Prose AI-tell references)
- **Document claim**: `:458` references "the five Human-reader rules" / "§ Prose AI-tell additions" in the cold-read.
- **Search performed**: Read `design-review.md:455`–`:462`; grep Human-reader / Prose AI-tell additions.
- **Code location**: `:458`; `### Human-reader cold-read additions` at `:169`; `### Prose AI-tell additions` at `:186`.
- **Actual signature/role**: `:458` "the five Human-reader rules require evidence … the § Prose AI-tell additions checks require evidence too …". Target sections for the D2-1 content edit exist.
- **Verdict**: MATCHES
- **Detail**: The "five" here is the Human-reader cold-read additions, a distinct set from the AI-tell subset; track-2 D2-1 correctly targets these cold-read sections, not the preamble. Target edit reachable.

#### Ref: 6 skills count + subset citation
- **Document claim**: 6 skills cite the subset (create-plan:23, execute-tracks:23, review-plan:31, review-workflow-pr:45, readability-feedback, ai-tells:23).
- **Search performed**: `grep -rln 'Banned analysis patterns' .claude/skills/*/SKILL.md`; per-line `sed`.
- **Code location**: `.claude/skills/`.
- **Actual signature/role**: exactly 6 skills reference the subset sections (the named set); no other skill in the 16-skill dir does. create-plan:23 / execute-tracks:23 / review-plan:31 carry the canonical five-slug preamble; review-workflow-pr:45 is the enumeration tail; readability-feedback names the sections in a STEP-1 read list (`:70`); ai-tells uses the `## Catalogue lookups` form.
- **Verdict**: MATCHES
- **Detail**: Count (6) and in-scope set both correct and complete.

#### Ref: ai-tells/SKILL.md:23 citation shape
- **Document claim**: track-2 `:37` says ai-tells "cites only 'Banned vocabulary' by name".
- **Search performed**: Read `ai-tells/SKILL.md:18`–`:40`; grep `Banned analysis patterns`/`Orientation`.
- **Code location**: `:20`–`:28` (`## Catalogue lookups`).
- **Actual signature/role**: catalogue maps six house-style sections (`§ Banned vocabulary` :23, `§ Structural rules` + `§ Banned sentence patterns` :24, `§ Banned sentence patterns` :25, `§ Punctuation and typography` :26, `§ Banned analysis patterns` :27, `§ Orientation` :28). No five-slug subset enumeration; no "five" count.
- **Verdict**: PARTIAL
- **Detail**: ai-tells cites six sections (two outside the subset), not "only Banned vocabulary", and is not a subset-enumeration site. Drives CR1. (Track 2 already defers the ai-tells edit decision to Phase A, so the inaccuracy is in the Context characterization, not the work plan.)

### Track 3 certificates

#### Ref: 20 review agents count + 19/1 split
- **Document claim**: 20 agents under `.claude/agents/`; 19 enumerate the five-slug subset; the 20th, `review-workflow-writing-style.md`, does not.
- **Search performed**: `ls .claude/agents/*.md | wc -l`; `grep -rln 'Banned analysis patterns'`; `comm` of full vs enumerating sets.
- **Code location**: `.claude/agents/`.
- **Actual signature/role**: 20 `.md` files; 19 match the five-slug enumeration; the single non-matching file is `review-workflow-writing-style.md`. The 19 match track-3's in-scope list one-for-one.
- **Verdict**: MATCHES

#### Ref: code-reviewer.md preamble (representative of the 19) + dr-audit.md:22 (variant phrasing)
- **Document claim**: 19 carry the "five AI-tell subset section slugs to apply are …" preamble (line ~20); `dr-audit.md:22` phrases it "slightly differently" but is one of the 19.
- **Search performed**: `grep -n` code-reviewer.md preamble; Read `dr-audit.md:20`–`:24`.
- **Code location**: `code-reviewer.md:20`; `dr-audit.md:22`.
- **Actual signature/role**: code-reviewer.md:20 "… the five AI-tell subset section slugs to apply are `## Banned vocabulary` … and `## Orientation`." dr-audit.md:22 "> **House style for chat-scale prose.** Output produced from this file follows the AI-tell subset of … `## Banned vocabulary`, … and `## Orientation`." (different framing, no "five" numeric word, but the same five slugs).
- **Verdict**: MATCHES
- **Detail**: dr-audit.md:22 carries the five slugs in a different sentence shape with no "five" count word; track-3's "phrases it slightly differently" note is accurate. Its slug list is present to extend.

#### Ref: review-workflow-writing-style.md "Key rules to enforce" (:26-:39) + checks section (:69+)
- **Document claim**: `review-workflow-writing-style.md` does not enumerate the five slugs but has a "Key rules to enforce" list at `:26`–`:39` and a checks section at `:69`+; D3-1 adds a Plain-language check to both.
- **Search performed**: Read `:24`–`:42`; grep `^## Review criteria` / `^### `.
- **Code location**: `:26` ("… Key rules to enforce:"), bullets `:28`–`:35`; `## Review criteria` at `:67`, first subsection `### Banned vocabulary sweep` at `:69`.
- **Actual signature/role**: enforce-list intro at `:26`, eight bullets `:28`–`:35` (BLUF lead, Banned vocabulary, Em-dash cap, Section length, Repo-anchored voice, knowledge-cutoff, bullet-everything, Title Case); checks subsections from `:69` (Banned vocabulary sweep, Em-dash overuse, BLUF lead, …).
- **Verdict**: MATCHES
- **Detail**: The list's `:26`–`:39` range is slightly loose (bullets end at `:35`; `:37` is `## Tooling`), but the "Key rules to enforce" list and the `:69`+ checks section are both present where claimed; the D3-1 target edits are reachable. Not a finding (loose range, content correct).

### Gaps certificate (orphan-codebase-construct bullet)

#### Ref: target ## Plain language section absence (reachability)
- **Document claim**: the `## Plain language` section, the sixth-slug additions, and the five→six flips are target-state the tracks create.
- **Search performed**: `grep -rn '^## Plain language\|^### Plain language' .claude/ CLAUDE.md docs` (excluding `_workflow/`); `grep -rln 'six AI-tell subset\|six Tier-B'`.
- **Code location**: none outside `_workflow/`.
- **Actual signature/role**: no `## Plain language` heading exists anywhere outside the `_workflow/` planning docs (the `design-document-rules.md` "plain language" matches are incidental prose, not the heading). No site already reads "six". The codebase is uniformly at five today (CLAUDE.md the documented four-item exception).
- **Verdict**: MATCHES
- **Detail**: Target is genuinely absent and reachable; no orphan construct the plan should reference but omits. Cross-track count reconciliation holds: uniform five today, target six after all three tracks, with CLAUDE.md's four-item lag the single divergence D6 already records.
