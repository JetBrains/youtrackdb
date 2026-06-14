<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: T1, sev: suggestion, loc: "track-3.md:35", anchor: "### T1 ", cert: C8, basis: "enforce-list line citation :26-:39 overshoots into the ## Tooling section; list runs :28-:35"}
  - {id: T2, sev: suggestion, loc: "track-3.md:40", anchor: "### T2 ", cert: C2, basis: "dr-audit slug insertion needs an Oxford-comma adjustment the Plan of Work does not spell out"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 9}
cert_index:
  - {id: C2, verdict: PARTIAL, anchor: "#### C2 "}
  - {id: C8, verdict: PARTIAL, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [suggestion]
**Certificate**: Premise C8 (the writing-style reviewer's enforce-list and checks-section line ranges)
**Location**: `track-3.md:35` (`## Context and Orientation`), referencing `.claude/agents/review-workflow-writing-style.md`
**Issue**: The track cites the writing-style reviewer's "Key rules to enforce" list as `:26-:39` and its checks section as `:69+`. The checks-section cite is right (`## Review criteria` opens at :67, `### Banned vocabulary sweep` at :69). The enforce-list cite overshoots: the heading "Key rules to enforce:" sits at :26, the bullet list runs :28-:35, and :37-:39 is the next section ("## Tooling"). So the range `:26-:39` reaches past the list into Tooling. The two insertion surfaces the D3-1 edit needs both exist and are correctly identified — this is a line-range imprecision, not a missing target. No execution risk.
**Proposed fix**: At decomposition, tighten the citation to "Key rules to enforce" list (`:26`–`:35`) so the implementer inserts the Plain-language bullet inside the bullet list, not after the "## Tooling" heading. Optional; the named surface is unambiguous from the heading text alone.

### T2 [suggestion]
**Certificate**: Premise C2 (dr-audit.md enumeration shape)
**Location**: `track-3.md:40` (`## Plan of Work` step 1), referencing `.claude/agents/dr-audit.md`
**Issue**: 18 of the 19 enumerating agents end their slug list with "… `### Em-dash discipline`, and `## Orientation`." — adding the sixth slug there is a clean append ("… `## Orientation`, and `## Plain language`."). dr-audit.md (the 19th) phrases its list inline inside a "House style for chat-scale prose" blockquote and continues after the list with "Structural rules … do not apply at chat scale": "… `### Em-dash discipline`, and `## Orientation`. Structural rules …". Inserting Plain language there is not a tail-append; it requires moving the Oxford comma off Orientation and continuing the sentence ("… `### Em-dash discipline`, `## Orientation`, and `## Plain language`. Structural rules …"). Plan of Work step 1 says "after `## Orientation`, matching slug order", which is directionally correct but does not call out that one of the 19 has trailing prose, so a mechanical "append after the last slug" would corrupt the sentence boundary before "Structural rules".
**Proposed fix**: At decomposition, note that dr-audit.md's list is mid-sentence (followed by "Structural rules …"), so the sixth slug goes before the closing period and the Oxford comma shifts. The other 18 are uniform tail-appends.

## Evidence base

#### C1 Premise: 20 review agents exist under `.claude/agents/`, split 19 enumerating + 1 active reviewer
- **Track claim**: "The 20 review agents under `.claude/agents/` split two ways. 19 carry the same house-style preamble naming the five subset slugs … The 20th, `review-workflow-writing-style.md`, does not enumerate the five slugs."
- **Search performed**: `ls .claude/agents/*.md` (20 files); `grep -rln 'Banned analysis patterns' .claude/agents/` (19 files); set difference against the full 20.
- **Code location**: `.claude/agents/` (20 `.md` files)
- **Actual behavior**: 20 files total. 19 contain "Banned analysis patterns" (the planner's derivation grep). The exact complement is `review-workflow-writing-style.md`, which contains "Banned vocabulary" in its enforce list (:29) but no "Banned analysis patterns" enumeration line. The 19-vs-1 partition is clean and exhaustive — no agent falls outside both sets, none sits in both.
- **Verdict**: CONFIRMED

#### C2 Premise: dr-audit.md is one of the 19 but phrases the slug list differently
- **Track claim**: "`dr-audit.md` phrases the same five-slug list slightly differently (`:22`) but is one of the 19."
- **Search performed**: `grep -n 'Banned analysis patterns' .claude/agents/dr-audit.md` and Read of the line.
- **Code location**: `.claude/agents/dr-audit.md:22`
- **Actual behavior**: The line is a blockquote, "**House style for chat-scale prose.** Output produced from this file follows the AI-tell subset … : `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, and `## Orientation`. Structural rules (`§ BLUF lead`, …) do not apply at chat scale." It lists the same five slugs but in a different sentence frame than the standard 18 ("the five AI-tell subset section slugs to apply are …") and, unlike them, carries NO numeric "five" and continues with trailing prose after the list. The standard 18 lines end at the Orientation slug with a period.
- **Verdict**: PARTIAL — "is one of the 19" and "same five slugs" hold; the differences (no count word, trailing "Structural rules" prose) matter for the mechanical edit. See T2.

#### C3 Premise: the standard 18 agents carry the "five AI-tell subset section slugs to apply are" line ending at Orientation
- **Track claim**: "19 carry the same house-style preamble naming the five subset slugs (line ~20: 'the five AI-tell subset section slugs to apply are …')."
- **Search performed**: `grep -rln 'five AI-tell subset section slugs' .claude/agents/*.md` (18 files); per-file end-of-line check for `Orientation.{0,3}$`.
- **Code location**: e.g. `.claude/agents/code-reviewer.md:20`
- **Actual behavior**: 18 files (the 19 enumerating agents minus dr-audit) carry the verbatim "the five AI-tell subset section slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, and `## Orientation`." line. All 18 end at the Orientation slug on a single line — a clean tail-append target for the sixth slug + a "five"→"six" word flip. None wraps the list across two lines.
- **Verdict**: CONFIRMED (the track's "19" count includes dr-audit, which is the 18+1 split this cert and C2 together cover)

#### C4 Premise: `review-workflow-writing-style.md` does NOT enumerate the slug list, so the mechanical flip skips it
- **Track claim**: "The 20th, `review-workflow-writing-style.md`, does not enumerate the five slugs — it is the active writing-style reviewer … It needs a content edit, not a slug add (D3-1)."
- **Search performed**: `grep -c 'Banned analysis patterns' .claude/agents/review-workflow-writing-style.md` (0); full Read of the file.
- **Code location**: `.claude/agents/review-workflow-writing-style.md`
- **Actual behavior**: The file has no "AI-tell subset section slugs" enumeration line and no "Banned analysis patterns" string. It instead has a "Project context — house-style" section with a "Key rules to enforce" bullet list (BLUF, Banned vocabulary, Em-dash cap, Section length, Repo-anchored voice, …) and a "## Review criteria" section of `###` check headings (Banned vocabulary sweep, Em-dash overuse, BLUF lead, Section length, …). A pure slug flip would not touch it; a content edit is required, exactly as D3-1 states.
- **Verdict**: CONFIRMED

#### C5 Premise (D3-1 feasibility): the writing-style reviewer has both an enforce list and a checks section to extend
- **Track claim**: D3-1 / Plan of Work step 2 — "add a Plain-language enforcement check to the 'Key rules to enforce' list and a matching check in the checks section, parallel to the existing Banned-vocabulary and Em-dash checks."
- **Search performed**: Read of `.claude/agents/review-workflow-writing-style.md` :26-:35 (enforce list) and :67-:113 (## Review criteria checks).
- **Code location**: `.claude/agents/review-workflow-writing-style.md:26` (enforce-list heading), `:69` (`### Banned vocabulary sweep`), `:74` (`### Em-dash overuse`)
- **Actual behavior**: The "Key rules to enforce" bullet list at :28-:35 holds BLUF, Banned vocabulary, Em-dash cap, Section length, Repo-anchored voice, etc. — a Plain-language bullet fits parallel to these. The "## Review criteria" section at :67+ holds `### Banned vocabulary sweep` (:69), `### Em-dash overuse` (:74), `### BLUF lead` (:78), etc. — a `### Plain language` check fits parallel to these. Both insertion surfaces exist; the D3-1 approach is feasible against the real file.
- **Verdict**: CONFIRMED

#### C6 Premise: the canonical six-slug order ends with `## Plain language` after `## Orientation`
- **Track claim**: Plan of Work step 1 — "after `## Orientation`, matching slug order"; invariant — "ends at exactly six slugs in the canonical order".
- **Search performed**: `grep -n 'six AI-tell subset section slugs' .claude/workflow/prompts/technical-review.md` (Track 2 output); `grep -n` for the §1.5 Tier-B row in `conventions.md`.
- **Code location**: `.claude/workflow/prompts/technical-review.md:31`; `.claude/workflow/conventions.md:567`
- **Actual behavior**: The standard six-slug preamble Track 2 landed reads "`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`, and `## Plain language`." — Plain language is last, immediately after Orientation. The §1.5 Tier-B cell orders them differently for the code-comment surface but the always-on preamble order Track 3 must match is the technical-review.md one. Track 3's "after `## Orientation`, matching slug order" reproduces this exactly.
- **Verdict**: CONFIRMED

#### C7 Premise: Track 1's `## Plain language` section exists (dependency satisfied)
- **Track claim**: "This track depends on Track 1: the `## Plain language` section must exist before these agents name it or check it."
- **Search performed**: `grep -c '^## Plain language' .claude/output-styles/house-style.md`.
- **Code location**: `.claude/output-styles/house-style.md:78`
- **Actual behavior**: `## Plain language` exists at :78, after `## Orientation` (:54). Track 1 is complete (plan Checklist marks it `[x]`, episode recorded). The dependency holds — the agents can name and check a section that exists.
- **Verdict**: CONFIRMED

#### C8 Premise: the writing-style reviewer's enforce-list / checks-section line citations
- **Track claim**: `## Context and Orientation` — enforce list at "(`:26`–`:39`)" and checks section at "(`:69`+)".
- **Search performed**: Read of `.claude/agents/review-workflow-writing-style.md` :26-:39 and :67-:75.
- **Code location**: `.claude/agents/review-workflow-writing-style.md:26` (enforce-list heading), :28-:35 (bullets), :37 (`## Tooling`), :67 (`## Review criteria`), :69 (`### Banned vocabulary sweep`)
- **Actual behavior**: "Key rules to enforce:" is at :26; the bullets run :28-:35; :37 begins the next section "## Tooling". So the enforce-list cite `:26-:39` overshoots by ~4 lines into Tooling. The checks-section cite `:69+` is correct (criteria open at :67, first check at :69). The surfaces are correctly named; only the enforce-list line range is loose.
- **Verdict**: PARTIAL — checks-section cite correct; enforce-list range overshoots. See T1.

#### C9 Edge case: a 20th file outside the 19+1 set carries a subset reference and is silently missed
- **Trigger**: an agent enumerates or references the AI-tell subset but is not caught by the `Banned analysis patterns` derivation grep and is not `review-workflow-writing-style.md`, so Track 3 skips it and leaves a five-slug enumeration behind.
- **Code path trace**:
  1. Enumerate all subset-referencing agents: `grep -rln 'Banned vocabulary|## Orientation|AI-tell subset' .claude/agents/*.md`.
  2. Subtract the 19 (`Banned analysis patterns`): the only remainder is `review-workflow-writing-style.md`.
- **Outcome**: No silent miss. Every agent that references the subset is either in the 19 (slug add) or is the writing-style reviewer (content edit). The Validation coverage check `grep -rL 'Plain language' .claude/agents/*.md` therefore returns empty after the track only if review-workflow-writing-style.md's D3-1 edit puts the literal "Plain language" string in (which a check named "Plain language" does).
- **Track coverage**: yes — the Interfaces section's derivation grep + reconciliation, plus the Validation coverage check, cover this.

#### C10 Edge case: a test or script pins the agent slug enumeration and breaks on a count change
- **Trigger**: a hook/script/test asserts the agent enumeration text or the slug count, so adding the sixth slug / flipping "five"→"six" in the agents breaks it.
- **Code path trace**:
  1. `grep -rln 'agents/.*Banned analysis|agents/.*AI-tell subset|review-workflow-writing-style' .claude/scripts/ .claude/hooks/` → empty.
  2. The only pin in the plan (`test_house_style_hook.py` / `TIER_B_HEADINGS`) targets the hook reminder and §1.5, both Track 1 scope, not agent files.
- **Outcome**: No script or test pins the agent enumerations. Track 3 owns no test work; the plan's Invariant test (`test_16_section_name_guard`) belongs to Track 1. Correct scoping.
- **Track coverage**: yes — the track lists no test in its Interfaces; the test pin is correctly assigned to Track 1.

#### C11 Edge case: the §1.5 rename-detection grep drifts after agents gain a sixth pointer site
- **Trigger**: after Track 3 adds `## Plain language` to 20 agent files, the §1.5 rename-detection grep (`grep -rnE '## Orientation|## Plain language|…' .claude/ CLAUDE.md`) gains new pointer sites under `.claude/agents/` that must be in its match set.
- **Code path trace**:
  1. The §1.5 grep at `conventions.md:572` scans `.claude/` recursively (includes `.claude/agents/`), matching `## Plain language` and `## Orientation` only in their `##`/`§` heading-pointer form.
  2. The agent enumerations wrap slugs in backticks as `` `## Plain language` ``, which contains the literal `## Plain language`, so the heading-pointer-form grep matches them.
- **Outcome**: No drift. The agent enumerations Track 3 produces are valid pointer sites the §1.5 grep will find. Track 1's Phase C already completed the grep to six headings (anchored form), so the helper is ready for the new sites. No further grep edit is needed in agents.
- **Track coverage**: yes — implicitly; the agent enumerations match the existing six-heading grep without modification.
