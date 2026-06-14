<!-- workflow-sha: f74ef47e943f3bf1900f1f5ab42740d63fe3e588 -->
# Track 1: Author the rule and update the canonical homes, core docs, hook, and CLAUDE.md

## Purpose / Big Picture
After this track lands, the house style has a `## Plain language` rule and every canonical home of the AI-tell subset names it.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the new `## Plain language` section to `house-style.md` (right after `## Orientation`) with its boundary clause and a self-check item, then updates each canonical home and core-doc enumeration: the `house-conversation.md` chat subset, the `conventions.md §1.5` tier table plus its Tier-B code-comment restatement, the 11 core workflow docs, the hook reminder and its pin test (five→six), and the `CLAUDE.md` de-enumeration. It defines the rule that Tracks 2 and 3 propagate, so it lands first.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [x] Track completion

- [x] 2026-06-13T17:03Z [ctx=info] Review + decomposition complete
- [x] 2026-06-13T19:01Z [ctx=safe] Step 1 complete (commit 9c968cc81f)
- [x] 2026-06-13T19:08Z [ctx=safe] Step 2 complete (commit 0365429691)
- [x] 2026-06-13T19:16Z [ctx=safe] Step 3 complete (commit 870b9e7846)
- [x] 2026-06-14T04:07Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-14T04:15Z [ctx=safe] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

**SD1 (Phase A review, A1/T1) — the hook `tier_b_body` has a hard 500-char cap.** `test_house_style_hook.py` `test_18_reminder_body_length_budget` enforces `PER_BODY_CHAR_CAP = 500` per body and `CONCAT_CHAR_CAP = 1500` for both bodies. The live `tier_b_body` is 441 chars. Adding the sixth slug plus the D3 plain-language carve note as a second sentence overshoots (~535). Resolution: keep the cap at 500 (no threshold change — D2 syncs existing checks, it does not re-tune them) and fit the body by combining the Orientation and Plain-language carves into one clause and dropping the secondary "(H3 nested under § Punctuation and typography)" parenthetical from the Em-dash slug. A measured candidate at 479 chars proves a faithful ≤500 body exists. The test's own failure message sanctions raising the cap as a fallback ("trim the body or revise the documented cap"); not needed here.

**SD2 (Phase A review, A2/A3) — `implementer-rules.md:1102` carries a count, not just an enumeration.** The sentence at `:1100`–`:1104` reads "the **five** section slugs that make up the Tier-B AI-tell subset are …". It is both an enumeration (the five slugs) and a numeric count ("five"). The narrow reconciliation grep pattern `five AI-tell|five Tier-B|five sections` does **not** match "five section slugs", so the count site would be missed if the grep were the only mechanism. The file is already in the in-scope set (it matches on `Banned analysis patterns`), so no file is missed — but the inline "five"→"six" flip at `:1102` must be called out as a count site so the implementer does not flip the enumeration and leave the count at "five".

**SD3 (Phase A review, A3) — two coincidental "five" sites must NOT be touched.** A broad `five (section|slug|AI-tell|Tier-B|subset)` grep surfaces two matches unrelated to the AI-tell subset: `workflow-startup-precheck.sh:1348` ("one of the five slug" — the State-C substate slugs) and `conventions.md:86` ("Five sections" — the research-log's five sections). Neither is a subset count. The reconciliation must exclude them by hand; a wider grep raises false positives, so the in-scope set stays the explicit list reconciled against the grep, not the raw grep output.

**SD4 (Phase A review, T2) — `design-mechanical-checks.py` is a considered exclusion, like `design-document-rules.md`.** The reconciliation grep surfaces `design-mechanical-checks.py` because its `dsc-ai-tell` rule names `## Banned analysis patterns`. Under D2 (judgment-only) the `dsc-ai-tell` regex gains no pattern from `## Plain language`, so the file has nothing to flip — the same reasoning that excludes `design-document-rules.md` per CR2. Both are out of scope and recorded as such so a later reader does not read the exclusion as an oversight.

**SD5 (Step 1) — the `conventions.md §1.5` rename-detection grep at `:572` lists four headings, not the full subset.** The helper `grep -rn 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` predates #1142 and never gained `## Orientation`; Step 1 likewise did not add `## Plain language`, since the plan scoped the §1.5 edit to the Tier-B cell, the `:570` count, and the restatement paragraph. The grep is a rename aid, not a subset count, so the six-slug invariant does not bind it. Phase C track-level review should decide whether to complete the helper to all six headings. See Episodes §Step 1.

## Decision Log
<!-- Track-canonical live decisions (D7). Phase 1 seeds the full inline records this track owns. -->

#### D1: Plain-language target, not a graded band
- **Alternatives considered**: a CEFR B1–B2 anchor; a reading-grade band (Flesch-Kincaid 8–10); plain-language clarity moves with no number (chosen).
- **Rationale**: a band or grade implies measurement the project will not run. Plain-language moves are teachable and reviewable by eye, which fits the judgment-only enforcement in D2.
- **Risks/Caveats**: "plain" stays a judgment call with no threshold; the reviewer lens carries the load, as it already does for `## Voice and tone` and `## Orientation`.
- **Implemented in**: this track.

#### D2: Judgment guidance only; no new mechanical enforcement
- **Alternatives considered**: wire measurable triggers into `design-mechanical-checks.py` + new tests; add a `dsc-` rule for the new section; judgment-only (chosen).
- **Rationale**: a plain-language regex produces false positives (a short sentence can still be unclear; a long one can be clear) and the maintenance burden the em-dash counter already shows is touchy.
- **Risks/Caveats**: the flip still syncs the *existing* hook reminder enumeration (`house-style-write-reminder.sh:256`, `:262`) and its *existing* pin test (`test_house_style_hook.py` `TIER_B_HEADINGS`) from five slugs to six. That is enumeration sync of pre-existing checks, not a new check, so D2's intent holds. Only `test_16_section_name_guard` keys on the slug list; the Tier-B path tests match on a prefix, so no new test logic is needed.
- **Implemented in**: this track (hook + test sync). The review-lens prose half lands in Tracks 2 and 3.

#### D3: Join the always-on subset (five→six) with a Tier-B code-comment restatement
- **Alternatives considered**: confine the rule to chat + docs + issues and exclude code comments (needs a §1.5 Tier-B carve-out); join the subset with natural reach to chat + Markdown + `*.java`/`*.kt` comments (chosen).
- **Rationale**: the aim names conversation, which only the subset reaches; matching the Orientation precedent avoids a special-case carve-out. Plain rationale comments help every reader.
- **Risks/Caveats**: "same reach as Orientation" is not "no per-surface text". Orientation carries a Tier-B restatement at `conventions.md:574-581` because its literal test does not transfer to a file-open reader. Plain language has the same gap, so §1.5 Tier-B gains a parallel paragraph: at comment scale the common-word, acronym-expansion, and no-idiom moves apply; the short-sentence / clause-nesting move does not. The new section states this carve in one line, and the hook reminder carries the matching carve note.
- **Implemented in**: this track (the §1.5 Tier-B paragraph, the section's one-line carve, the hook reminder carve). The slug additions to agents/prompts/skills land in Tracks 2 and 3 (see plan D3).

#### D5: New `## Plain language` section after `## Orientation`, with a boundary clause
- **Alternatives considered**: fold the moves into `## Voice and tone` and `## Banned vocabulary`; a dedicated `## Plain language` section (chosen); name it "Mid-level English".
- **Rationale**: a dedicated section parallels `## Orientation` (structural clarity) as its lexical and syntactic complement, so neither duplicates the other. The name "Plain language" is itself plain and does not collide with the "mid-level Java/database" reader phrase at `house-style.md:6`/`:42`.
- **Risks/Caveats**: move (a) (prefer the common word) overlaps `## Banned vocabulary`. The section states the precedence: Banned vocabulary owns the closed AI-tell list (`leverage→use`; `utilize` is not on it); Plain language owns general-English word choice outside that list and never re-bans a tier word. It also states the reconciliation with `## Voice and tone` "bias toward less text": plain language reduces word count and shares Orientation's anti-padding stance, never a license to add tutorial text.
- **Implemented in**: this track.

The five plain-language moves the section codifies:

1. Prefer the common general-English word where it means the same.
2. Keep sentences short, one idea each. Prefer a period over a stacked subordinate clause.
3. Avoid idioms, metaphors, and ambiguous phrasal verbs. Use the literal verb.
4. Expand a non-floor acronym or abbreviation on first use.
5. Keep grammar explicit: the active subject-verb-object the Passive-voice rule already favors.

Plus the boundary clause: plain language governs general English only. It never simplifies technical content and never re-teaches the mid-level Java/database floor.

#### D6: De-enumerate `CLAUDE.md`, do not grow its count
- **Alternatives considered**: leave it at four (the current lag); grow it to six; de-enumerate it to a pointer (chosen).
- **Rationale**: `CLAUDE.md:104` lists the subset as a four-item parenthetical and already lags (it omits Orientation; #1142 never touched it). Re-enumerating to six re-arms the same drift. A pointer to the canonical list (`house-conversation.md` / `conventions.md §1.5`) fixes the lag and removes `CLAUDE.md` from every future flip's blast radius.
- **Risks/Caveats**: a reader loses the inline four-word example and follows one pointer hop instead. Accepted; the adversarial gate challenged this and it survived.
- **Implemented in**: this track. `CLAUDE.md` sits at repo root, outside the stamp pathspec, so the edit does not advance the stamp base.

<!-- The §1.7(k) opt-out (plan D4) is branch-level: the marker is in the plan's `### Constraints`; all three tracks edit live. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review outcomes and the track-completion summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (2 findings — T1 should-fix, T2 suggestion; both accepted and applied: SD1 hook char-cap + SD4 design-mechanical-checks.py exclusion, with Plan-of-Work / Validation / Interfaces edits). Gate-check VERIFIED both.
- [x] Adversarial: PASS at iteration 2 (3 findings — A1 blocker, A2 should-fix, A3 suggestion; all accepted and applied: SD1/SD2/SD3 + Plan-of-Work step 4–5 / Validation / Interfaces edits). Gate-check VERIFIED A1/A2/A3 and surfaced one new suggestion A4 (the reconciliation grep does not match `CLAUDE.md`), applied to the In-scope reconciliation paragraph.
- [x] Track-level code review (Phase C): PASS at 1 fix iteration. Diff was workflow-only, so the four code baselines skipped (baseline-skip override) and five workflow reviewers ran (consistency, context-budget, writing-style, instruction-completeness, hook-safety; prompt-design did not fire — no skills/agents/prompts in scope). Three dimensions clean. Consistency (should-fix) and instruction-completeness (suggestion) independently flagged the open SD5 item: the `conventions.md §1.5` rename-detection grep at `:572` enumerated four of six Tier-B headings, so a rename of `## Orientation` or `## Plain language` would silently find zero pointer sites. Resolved in `Review fix:` `7b8ad4f424` — completed the helper to six, anchoring the two common-word names to their `##`/`§` heading-pointer form (empirical: bare matching produced ~109 false positives from `## Context and Orientation`; anchored yields 122 clean pointer-site lines, 0 false positives). Gate-check VERIFIED both. This closes SD5.
- **Plan correction (deferred finding):** the same rename grep has a verbatim copy at `readability-feedback/SKILL.md:54`, already in Track 2's slug-propagation scope. Folded into Track 2 (`ea8f1152c8`) — Track 2 now also syncs that copy to the six-heading form. Track 3 unaffected.
- **Track-completion summary:** 3 steps, 0 failed. The `## Plain language` rule and the five→six subset flip landed across all Track-1 scope; the branch self-applies the rule. Remaining branch work: Track 2 (prompts + skills, incl. the SKILL.md:54 grep sync) and Track 3 (review agents), then Phase 4. The §1.7(k) opt-out edits live; the drift gate will fire each session and is suppressed per the plan, with a single `/migrate-workflow` after the branch-final pathspec-touching commit.

## Context and Orientation
The AI-tell subset is the part of the house style that applies to every prose surface — chat, durable Markdown, and `*.java`/`*.kt` code comments — as opposed to the full rule set, which applies only to durable Markdown. Today the subset is five sections. The merged #1142 flip (PR `f74ef47e94`) added `## Orientation` as a four→five flip and is the direct precedent for this work, including its §1.7(k) opt-out.

`house-style.md` (471 lines) is the single declarative source of the rules. `## Orientation` sits at `:54`; the new section goes right after it. The line-20 sentence ("reuses the five AI-tell sections") carries a numeric count. The `## Self-check` section (`:455`) lists per-rule checks; item 8 is the Orientation check.

`house-conversation.md` (35 lines) is the chat register. Lines 21-27 list the five subset sections as bullets under "Apply these five sections".

`conventions.md §1.5` (`:547`–`:582`) is the tier mapping. The table at `:567` has the Java/Kotlin Tier-B row whose "Sections that apply" cell lists the five slugs; `:570` says "The five Tier-B section names"; `:574`–`:581` is the Orientation code-comment restatement ("bans out-of-file assumptions, not in-file terseness").

The 11 core workflow docs each cite the subset, some by full enumeration (`commit-conventions.md:191`, `step-implementation.md:1038`, `implementer-rules.md:1100`, `episode-format-reference.md:47`, `conventions.md`), some by a house-style declaration line (`workflow.md:53`, `review-iteration.md:30`, `design-decision-escalation.md:19`, `inline-replanning.md:18`, `mid-phase-handoff.md:34`, `review-mode.md:41`). `design-document-rules.md` is not in this set: its only house-style touchpoint is the `dsc-ai-tell` regex-rule row, which gains no pattern from `## Plain language` (D2 is judgment-only), so it has nothing to flip (CR2).

`house-style-write-reminder.sh` is a PreToolUse hook that prints a house-style reminder on Write/Edit. Its `tier_b_body` string (`:262`) enumerates the five `§ ` slugs plus the numeric "five" and the Orientation code-comment carve; the comment at `:256` also says "five Tier-B". `test_house_style_hook.py` pins the slug list in `TIER_B_HEADINGS` (five `## ` slugs) and asserts each exists in `house-style.md` (`test_16_section_name_guard`), with a docstring telling the author to update the hook and the test together.

`CLAUDE.md:104` names the subset as a four-item parenthetical and omits Orientation (a pre-existing lag).

Deliverables: the new section in `house-style.md`; the canonical-home updates; the core-doc slug/count flips; the hook + test sync; the `CLAUDE.md` de-enumeration.

## Plan of Work
The approach, in order:

1. Author `## Plain language` in `house-style.md` right after `## Orientation`: the five moves (a)–(e), the boundary clause, the `## Banned vocabulary` and `## Voice and tone` reconciliations, and a one-line Tier-B code-comment carve. Write the section itself in plain language (self-application). Add a self-check item (item 8a or a new item) and flip the `:20` count to "six".
2. Update `house-conversation.md`: add a sixth bullet for `## Plain language` and flip "these five sections" → "six".
3. Update `conventions.md §1.5`: add `## Plain language` to the Tier-B "Sections that apply" cell (`:567`), flip "five Tier-B" → "six" (`:570`), and add a parallel plain-language paragraph to the Tier-B restatement (`:574`–`:581`) stating which moves apply at comment scale and which do not.
4. Update the 11 core workflow docs: add the sixth slug to each enumeration and flip any numeric "five" → "six". The known count sites are `commit-conventions.md:191` ("five AI-tell subset"), `step-implementation.md:1038` ("five AI-tell subset section slugs"), `conventions.md:570` ("five Tier-B section names"), `house-style.md:20` ("five AI-tell sections"), `house-conversation.md:21` ("these five sections"), and `implementer-rules.md:1102` ("five section slugs" — SD2; this one the narrow grep misses, so flip it explicitly). Do **not** flip the two coincidental "five" sites at `workflow-startup-precheck.sh:1348` and `conventions.md:86` (SD3) — neither names the subset.
5. Sync the hook and test: add `§ Plain language` to `tier_b_body` and flip "five" → "six" at `:256`/`:262`. The body has a hard 500-char cap (`test_18`, SD1), so fold the Orientation and Plain-language carve notes into one clause and drop the "(H3 nested under …)" parenthetical from the Em-dash slug to stay ≤500 — keep the cap unchanged. Add `## Plain language` to `TIER_B_HEADINGS` in the test (`test_16_section_name_guard` then asserts all six headings exist in `house-style.md`).
6. De-enumerate `CLAUDE.md:104`: replace the four-item parenthetical with a pointer to the canonical subset list.

Ordering constraint: step 1 (the section exists) must precede step 5 (the test asserts the section exists). Invariant to preserve: every subset enumeration in this track ends at exactly six slugs and every numeric count reads "six".

## Concrete Steps

1. Author the `## Plain language` section in `house-style.md` right after `## Orientation` — the five moves, the boundary clause, the `## Banned vocabulary` and `## Voice and tone` reconciliations, and the one-line Tier-B code-comment carve — plus the `:20` count flip ("five"→"six") and a `## Self-check` item; then update the two other canonical homes: `house-conversation.md` (sixth bullet + `:21` count) and `conventions.md §1.5` (the Tier-B "Sections that apply" cell, the `:570` count, and a parallel Tier-B code-comment restatement paragraph naming which plain-language moves apply at comment scale). Write all new prose in plain language (self-application, D5). — risk: medium (bounded behavioral workflow edit: adds a cross-referenced house-style section that changes agent-observable prose behavior) — size: ~3 files; reason (a): the only remaining low work is the coherent core-doc propagation (Step 2, ~11 files), and merging it in would total ~14 and trip the overblown line  [x] commit: 9c968cc81f
2. Propagate the sixth slug `## Plain language` into the 10 remaining core-doc enumerations and flip every numeric "five"→"six": `commit-conventions.md` (:191 count), `step-implementation.md` (:1038 count), `implementer-rules.md` (:1100 enum **and** :1102 count, SD2), `workflow.md`, `review-iteration.md`, `design-decision-escalation.md`, `inline-replanning.md`, `mid-phase-handoff.md`, `review-mode.md`, `episode-format-reference.md`; then de-enumerate `CLAUDE.md` (:104/:106) to a pointer at the canonical list (D6). Leave the two coincidental "five" sites untouched: `workflow-startup-precheck.sh:1348` and `conventions.md:86` (SD3). — risk: low (prose-only workflow edit: meaning-preserving cross-reference sync and the CLAUDE.md pointer swap; no hook/gate/schema change)  [x] commit: 0365429691
3. Sync the hook reminder and its pin test (depends on Step 1 — the section and §1.5 must already exist): add `§ Plain language` to `tier_b_body` in `house-style-write-reminder.sh` with the Orientation and Plain-language carves folded into one clause and the "(H3 nested under § Punctuation and typography)" parenthetical dropped so the body stays ≤ 500 chars (`test_18`, SD1), flip "five"→"six" at `:256`/`:262`, and add `## Plain language` to `TIER_B_HEADINGS` in `test_house_style_hook.py`. Run `python3 .claude/scripts/tests/test_house_style_hook.py` to green (`test_16_section_name_guard` finds all six headings; `test_18_reminder_body_length_budget` confirms `tier_b_body` ≤ 500 and the concat ≤ 1500). — risk: high (workflow machinery: edits an auto-running PreToolUse hook)  [x] commit: 870b9e7846

## Episodes
<!-- Continuous-log. Phase B appends one block per completed step. Empty at Phase 1. -->

### Step 1 — commit 9c968cc81ff603bed99255a134f1769b5eb8a343, 2026-06-13T19:01Z [ctx=safe]
**What was done:** Added the `## Plain language` section to `house-style.md` right after `## Orientation`: the five moves (prefer the common word, short sentences, no idioms, expand a non-floor acronym, explicit grammar), the boundary clause (general English only; never simplifies technical content; never re-teaches the mid-level Java and database floor), the `## Banned vocabulary` precedence reconciliation, the `## Voice and tone` anti-padding reconciliation, and the one-line Tier-B code-comment carve. Flipped the `:20` subset count five→six and added `## Self-check` item 8a. Updated the two other canonical homes: `house-conversation.md` gained a sixth bullet and the count flip, and `conventions.md §1.5` gained `## Plain language` in the Tier-B "Sections that apply" cell, the `:570` count flip, and a parallel Tier-B restatement paragraph naming which moves carry to comment scale and which does not (D3). All new prose self-applies plain language (D5).

**What was discovered:** The `conventions.md §1.5` rename-detection grep at `:572` lists four representative headings, not the full subset. It already left out `## Orientation` after #1142, so this step left `## Plain language` out of it too, which keeps the existing pattern. Whether that helper should list all six headings is a track-level consistency call for Phase C, not a Step-1 scope change (recorded as SD5). The Tier-B carve wording added here (common-word, acronym-expansion, and no-idiom apply; short-sentence and clause-nesting does not) is the wording Step 3's hook-reminder carve note must match, so producer and consumer agree.

**Key files:**
- `.claude/output-styles/house-style.md` (modified)
- `.claude/output-styles/house-conversation.md` (modified)
- `.claude/workflow/conventions.md` (modified)

### Step 2 — commit 0365429691, 2026-06-13T19:08Z [ctx=safe]
**What was done:** Propagated the sixth slug `## Plain language` into the ten remaining core workflow-doc enumerations and de-enumerated `CLAUDE.md` (D6). Four full-enumeration sites (`commit-conventions.md`, `step-implementation.md` at `:1038`, `implementer-rules.md` at `:1100` enum plus the `:1102` same-sentence count per SD2, and `episode-format-reference.md`) gained the slug at the end of the list and had their numeric count flipped five→six. Six house-style declaration lines (`workflow.md`, `review-iteration.md`, `design-decision-escalation.md`, `inline-replanning.md`, `mid-phase-handoff.md`, `review-mode.md`) gained the slug; none carried a numeric count, so no count flip applied. `CLAUDE.md:104` lost its four-item parenthetical in favor of a pointer to the canonical homes (`house-conversation.md`, `conventions.md §1.5`).

**What was discovered:** The six declaration lines enumerate the slugs inline but carry no numeric "five", so each needed only the slug add, not a count flip. `CLAUDE.md` had exactly one four-item parenthetical (at `:104`); the planning-era `:106` site held no separate enumeration, so one de-enumeration covered it. Verified by grep: no in-scope doc still names "five" of the subset, all ten docs list `## Plain language`, and the two SD3 sites (`workflow-startup-precheck.sh:1348`, `conventions.md:86`) still read "five".

**Key files:**
- `.claude/workflow/commit-conventions.md` (modified)
- `.claude/workflow/step-implementation.md` (modified)
- `.claude/workflow/implementer-rules.md` (modified)
- `.claude/workflow/episode-format-reference.md` (modified)
- `.claude/workflow/workflow.md` (modified)
- `.claude/workflow/review-iteration.md` (modified)
- `.claude/workflow/design-decision-escalation.md` (modified)
- `.claude/workflow/inline-replanning.md` (modified)
- `.claude/workflow/mid-phase-handoff.md` (modified)
- `.claude/workflow/review-mode.md` (modified)
- `CLAUDE.md` (modified)

### Step 3 — commit 870b9e7846, 2026-06-13T19:16Z [ctx=safe]
**What was done:** Synced the house-style hook reminder and its pin test to the six-section AI-tell subset. In `house-style-write-reminder.sh`: added `§ Plain language` to `tier_b_body` after `§ Orientation`, flipped the comment and body counts five→six, dropped the "(H3 nested under § Punctuation and typography)" parenthetical, and folded the Orientation and Plain-language carves into one clause. The carve reads "§ Plain language is word-choice only (common word, expand acronyms, no idioms)", which matches the `conventions.md §1.5` Tier-B restatement from Step 1, so producer and consumer agree. In `test_house_style_hook.py`: added `## Plain language` to `TIER_B_HEADINGS` after `## Orientation`. Ran the full test file: 18/18 pass. The step-level hook-safety dimensional review ran with 0 findings.

**What was discovered:** The final `tier_b_body` is 491 chars, under the 500 cap (SD1 proved a 479-char candidate; 491 keeps the original lead text intact while naming the three carrying moves). The concatenated bodies are 857 chars, under 1500. The cap constants (`PER_BODY_CHAR_CAP = 500`, `CONCAT_CHAR_CAP = 1500`) stay unchanged per D2. SD5 (the `conventions.md §1.5` rename grep at `:572` lists only four headings) is untouched here and stays an open track-level consistency call for Phase C.

**Key files:**
- `.claude/hooks/house-style-write-reminder.sh` (modified)
- `.claude/scripts/tests/test_house_style_hook.py` (modified)

## Validation and Acceptance
- The `## Plain language` section exists in `house-style.md` immediately after `## Orientation`, states the five moves, the boundary clause, the two reconciliations, and the Tier-B carve.
- `test_house_style_hook.py` passes in full: `test_16_section_name_guard` finds all six headings (with `## Plain language` added to `TIER_B_HEADINGS`), and `test_18_reminder_body_length_budget` still passes (`tier_b_body` ≤ 500 chars, both bodies concatenated ≤ 1500) after the six-slug + carve edit (SD1).
- Every subset enumeration and numeric count this track touches reads six / "six", including the `implementer-rules.md:1102` count (SD2).
- The two coincidental "five" sites — `workflow-startup-precheck.sh:1348` and `conventions.md:86` — are left unchanged (SD3).
- `CLAUDE.md:104` no longer enumerates the subset; it points to the canonical list.
- The new section and every edited prose surface read in plain language (self-application).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In scope (this track):**
- `.claude/output-styles/house-style.md` — author `## Plain language`; flip `:20` count; add self-check item.
- `.claude/output-styles/house-conversation.md` — sixth bullet; flip `:21` count.
- `.claude/workflow/conventions.md` — §1.5 Tier-B cell (`:567`); `:570` count; Tier-B restatement paragraph (`:574`–`:581`).
- `.claude/workflow/commit-conventions.md` — enumeration + `:191` count.
- `.claude/workflow/step-implementation.md` — enumeration + `:1038` count.
- `.claude/workflow/implementer-rules.md` — enumeration (`:1100`) **and** the "five section slugs" count in the same sentence (`:1102`, SD2).
- `.claude/workflow/workflow.md` — house-style declaration (`:53`).
- `.claude/workflow/review-iteration.md` — house-style declaration (`:30`).
- `.claude/workflow/design-decision-escalation.md` — house-style declaration (`:19`).
- `.claude/workflow/inline-replanning.md` — house-style declaration (`:18`).
- `.claude/workflow/mid-phase-handoff.md` — house-style declaration (`:34`).
- `.claude/workflow/review-mode.md` — house-style declaration (`:41`).
- `.claude/workflow/episode-format-reference.md` — enumeration (`:47`).
- `CLAUDE.md` — de-enumerate `:104` (D6).
- `.claude/hooks/house-style-write-reminder.sh` — `tier_b_body` + `:256`/`:262` count + carve note.
- `.claude/scripts/tests/test_house_style_hook.py` — `TIER_B_HEADINGS` slug add.

**Out of scope (other tracks):** the 11 workflow prompts and 6 skills (Track 2); the 20 review agents (Track 3).

**Considered exclusions (in scope of the grep, deliberately not edited):**
- `.claude/workflow/design-document-rules.md` — only touchpoint is the `dsc-ai-tell` regex-rule row, which gains no pattern under judgment-only D2 (CR2).
- `.claude/scripts/design-mechanical-checks.py` — the `dsc-ai-tell` regex names `## Banned analysis patterns` but gains no new pattern under D2; nothing to flip (SD4).
- `.claude/scripts/workflow-startup-precheck.sh:1348` and `.claude/workflow/conventions.md:86` — coincidental "five" matches (State-C substate slugs; research-log sections), not subset counts (SD3).

**Dependencies:** none upstream. Tracks 2 and 3 depend on this track (the canonical rule and §1.5 must exist before their enumerations name it).

**In-scope set reconciliation (DONE at Phase A).** The set above was reconciled against `grep -rln 'Banned analysis patterns\|five AI-tell\|five Tier-B\|five sections' .claude/ CLAUDE.md`, restricted to non-`prompts/`, non-`skills/`, non-`agents/` paths (those belong to Tracks 2 and 3). The grep returns 15 of the 16 in-scope files plus the four considered exclusions above. The 16th in-scope file, `CLAUDE.md`, does **not** match the grep: its subset enumeration is a lowercase four-item parenthetical ("banned vocabulary, banned sentence patterns, banned analysis patterns, em-dash discipline") that omits Orientation, so the case-sensitive six-literal pattern skips it — that lowercase, Orientation-omitting lag is exactly what D6 removes by de-enumerating to a pointer. `CLAUDE.md` is therefore reached from D6, not the grep; the union of (grep ∪ D6) misses no in-scope file. The grep pattern is also enumeration-oriented (`Banned analysis patterns` is in every other enumeration) and so catches every in-scope file *except* `CLAUDE.md`, but it misses the `implementer-rules.md:1102` *count* ("five section slugs"), which is why SD2 flags that count explicitly. The figure "~16 files" is now exact: 16.

## Base commit
89f4ad6c61ab37b3bc960a68943373971c0a881b
