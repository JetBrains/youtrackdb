<!-- workflow-sha: f74ef47e943f3bf1900f1f5ab42740d63fe3e588 -->
# Track 1: Author the rule and update the canonical homes, core docs, hook, and CLAUDE.md

## Purpose / Big Picture
After this track lands, the house style has a `## Plain language` rule and every canonical home of the AI-tell subset names it.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the new `## Plain language` section to `house-style.md` (right after `## Orientation`) with its boundary clause and a self-check item, then updates each canonical home and core-doc enumeration: the `house-conversation.md` chat subset, the `conventions.md §1.5` tier table plus its Tier-B code-comment restatement, the 12 core workflow docs, the hook reminder and its pin test (five→six), and the `CLAUDE.md` de-enumeration. It defines the rule that Tracks 2 and 3 propagate, so it lands first.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

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

## Context and Orientation
The AI-tell subset is the part of the house style that applies to every prose surface — chat, durable Markdown, and `*.java`/`*.kt` code comments — as opposed to the full rule set, which applies only to durable Markdown. Today the subset is five sections. The merged #1142 flip (PR `f74ef47e94`) added `## Orientation` as a four→five flip and is the direct precedent for this work, including its §1.7(k) opt-out.

`house-style.md` (471 lines) is the single declarative source of the rules. `## Orientation` sits at `:54`; the new section goes right after it. The line-20 sentence ("reuses the five AI-tell sections") carries a numeric count. The `## Self-check` section (`:455`) lists per-rule checks; item 8 is the Orientation check.

`house-conversation.md` (35 lines) is the chat register. Lines 21-27 list the five subset sections as bullets under "Apply these five sections".

`conventions.md §1.5` (`:547`–`:582`) is the tier mapping. The table at `:567` has the Java/Kotlin Tier-B row whose "Sections that apply" cell lists the five slugs; `:570` says "The five Tier-B section names"; `:574`–`:581` is the Orientation code-comment restatement ("bans out-of-file assumptions, not in-file terseness").

The 12 core workflow docs each cite the subset, some by full enumeration (`commit-conventions.md:191`, `step-implementation.md:1038`, `implementer-rules.md:1100`, `episode-format-reference.md:47`, `conventions.md`), some by a house-style declaration line (`workflow.md:53`, `review-iteration.md:30`, `design-decision-escalation.md:19`, `inline-replanning.md:18`, `mid-phase-handoff.md:34`, `review-mode.md:41`, `design-document-rules.md:284`).

`house-style-write-reminder.sh` is a PreToolUse hook that prints a house-style reminder on Write/Edit. Its `tier_b_body` string (`:262`) enumerates the five `§ ` slugs plus the numeric "five" and the Orientation code-comment carve; the comment at `:256` also says "five Tier-B". `test_house_style_hook.py` pins the slug list in `TIER_B_HEADINGS` (five `## ` slugs) and asserts each exists in `house-style.md` (`test_16_section_name_guard`), with a docstring telling the author to update the hook and the test together.

`CLAUDE.md:104` names the subset as a four-item parenthetical and omits Orientation (a pre-existing lag).

Deliverables: the new section in `house-style.md`; the canonical-home updates; the core-doc slug/count flips; the hook + test sync; the `CLAUDE.md` de-enumeration.

## Plan of Work
The approach, in order:

1. Author `## Plain language` in `house-style.md` right after `## Orientation`: the five moves (a)–(e), the boundary clause, the `## Banned vocabulary` and `## Voice and tone` reconciliations, and a one-line Tier-B code-comment carve. Write the section itself in plain language (self-application). Add a self-check item (item 8a or a new item) and flip the `:20` count to "six".
2. Update `house-conversation.md`: add a sixth bullet for `## Plain language` and flip "these five sections" → "six".
3. Update `conventions.md §1.5`: add `## Plain language` to the Tier-B "Sections that apply" cell (`:567`), flip "five Tier-B" → "six" (`:570`), and add a parallel plain-language paragraph to the Tier-B restatement (`:574`–`:581`) stating which moves apply at comment scale and which do not.
4. Update the 12 core workflow docs: add the sixth slug to each enumeration and flip any numeric "five" → "six" (the count sites are `commit-conventions.md:191`, `step-implementation.md:1038`).
5. Sync the hook and test: add `§ Plain language` to `tier_b_body` with a carve note, flip "five" → "six" at `:256`/`:262`; add `## Plain language` to `TIER_B_HEADINGS` in the test.
6. De-enumerate `CLAUDE.md:104`: replace the four-item parenthetical with a pointer to the canonical subset list.

Ordering constraint: step 1 (the section exists) must precede step 5 (the test asserts the section exists). Invariant to preserve: every subset enumeration in this track ends at exactly six slugs and every numeric count reads "six".

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B appends one block per completed step. Empty at Phase 1. -->

## Validation and Acceptance
- The `## Plain language` section exists in `house-style.md` immediately after `## Orientation`, states the five moves, the boundary clause, the two reconciliations, and the Tier-B carve.
- `test_house_style_hook.py` passes with `## Plain language` added to `TIER_B_HEADINGS` (the section-name guard finds all six headings).
- Every subset enumeration and numeric count this track touches reads six / "six".
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
- `.claude/workflow/implementer-rules.md` — enumeration (`:1100`).
- `.claude/workflow/workflow.md` — house-style declaration (`:53`).
- `.claude/workflow/review-iteration.md` — house-style declaration (`:30`).
- `.claude/workflow/design-decision-escalation.md` — house-style declaration (`:19`).
- `.claude/workflow/inline-replanning.md` — house-style declaration (`:18`).
- `.claude/workflow/mid-phase-handoff.md` — house-style declaration (`:34`).
- `.claude/workflow/review-mode.md` — house-style declaration (`:41`).
- `.claude/workflow/episode-format-reference.md` — enumeration (`:47`).
- `.claude/workflow/design-document-rules.md` — house-style reference (`:284`).
- `CLAUDE.md` — de-enumerate `:104` (D6).
- `.claude/hooks/house-style-write-reminder.sh` — `tier_b_body` + `:256`/`:262` count + carve note.
- `.claude/scripts/tests/test_house_style_hook.py` — `TIER_B_HEADINGS` slug add.

**Out of scope (other tracks):** the 11 workflow prompts and 6 skills (Track 2); the 20 review agents (Track 3).

**Dependencies:** none upstream. Tracks 2 and 3 depend on this track (the canonical rule and §1.5 must exist before their enumerations name it).

**Exact in-scope set is derived by `grep -rln 'Banned analysis patterns\|five AI-tell\|five Tier-B\|five sections' .claude/ CLAUDE.md` at Phase A and reconciled against this list (lite-tier requirement; figure ~17 is approximate).**
