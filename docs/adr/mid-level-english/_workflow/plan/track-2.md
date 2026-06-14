<!-- workflow-sha: f74ef47e943f3bf1900f1f5ab42740d63fe3e588 -->
# Track 2: Propagate the slug to the workflow prompts and skills

## Purpose / Big Picture
After this track lands, every workflow prompt and skill that names the AI-tell subset lists the new `## Plain language` slug, and the design cold-read checks for it.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The file footprint is ~17 (11 prompts + 6 skills), but the edits are not uniform. 14 files get a slug add: 10 of the 11 prompts carry a five-slug preamble to flip (the 11th, `design-review.md`, has no preamble), and 4 of the 6 skills carry a five-slug blockquote to extend. Three files get a content edit instead, because they cite the subset in a richer form than a flat enumeration: `design-review.md` gains the rule in its cold-read `### Prose AI-tell additions` block (D2-1); `ai-tells/SKILL.md` gains a `## Plain language` row in its fingerprint catalogue (D2-2); and `readability-feedback/SKILL.md` gains the rule in its read-list and classification step, plus the grep sync (D2-3).

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [ ] Track completion

- [x] 2026-06-14T04:56Z [ctx=info] Review + decomposition complete
- [x] 2026-06-14T07:24Z [ctx=safe] Step 1 complete (commit 308dd68bd4)
- [x] 2026-06-14T07:30Z [ctx=safe] Step 2 complete (commit 1256d0d555)
- [x] 2026-06-14T08:13Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Track-canonical live decisions (D7). This track executes plan D3/D4; it owns one content-edit decision. -->

#### D2-1: The design cold-read gains a real Plain-language check in the `### Prose AI-tell additions` block
- **Alternatives considered**: add the `## Plain language` slug to `design-review.md` as a slug-only edit (but it has no preamble to extend); add the check to the `### Human-reader cold-read additions` block; add the check to the `### Prose AI-tell additions` block (chosen).
- **Rationale**: `design-review.md` does not merely cite the subset — it drives the cold-read that checks whether a fresh reader can follow a design doc, so the rule needs a real lens, not a slug. The right block is `### Prose AI-tell additions` (`design-review.md:186-217`), for three reasons. First, the `## Orientation` precedent landed there in #1142, not in the Human-reader block: `design-review.md:207` checks too-terse prose against `§ Orientation` inside the Prose AI-tell block, and the TOC summary at `:23` names Orientation as a Prose-AI-tell concern. Second, that block runs on `target=tracks` as well as `target=design` (`:189-195`), which is the reach a Plain-language rule needs; the Human-reader block runs on design kinds only (`:172`). Third, the project's own sync-map authority, `readability-feedback/SKILL.md:47` (in this track's scope), routes a prose-density-or-terseness rule into the Prose AI-tell block. The five Human-reader rules (audience-fit, glossary-introduction, why-before-what, navigability, explanatory register) are a different rule family and stay untouched.
- **Risks/Caveats**: keep the addition short so it does not bloat the prompt's context budget. Plain-language quality stays a judgment call (plan D2) — the cold-read reports it as a finding, no score. Do not touch the "five Human-reader rules" count at `:181` / `:458`; it names a different set and must stay five.
- **Implemented in**: this track (`design-review.md`): one `§ Plain language` lens in the `### Prose AI-tell additions` block, the matching TOC summary at `:23`, and the `§ Tone and depth` "second exception" evidence clause at `:458`.

#### D2-2: The `ai-tells` catalogue gains a `## Plain language` row
- **Alternatives considered**: leave `ai-tells/SKILL.md`'s `## Catalogue lookups` untouched (it has no five-slug preamble, so the slug-add does not reach it); add a `## Plain language` row to the catalogue (chosen).
- **Rationale**: the #1142 Orientation flip set the precedent — it added one catalogue row ("Too-terse / under-orientation fingerprints → `§ Orientation`") to this same `## Catalogue lookups` block. The de-AI audit the skill runs should walk the new clarity rule the same way it walks Orientation. Skipping it would leave the audit blind to the one always-on subset rule that ships with this branch.
- **Risks/Caveats**: the catalogue maps fingerprint categories to house-style sections, so the new row names a plain-language fingerprint (uncommon word where a common one fits, long tangled sentence, idiom or ambiguous phrasal verb) and points at `§ Plain language`. Word choice overlaps `§ Banned vocabulary`; the row stays distinct by covering general-English clarity outside the closed AI-tell list (plan D5 precedence).
- **Implemented in**: this track (`ai-tells/SKILL.md`).

#### D2-3: `readability-feedback` follows the #1142 Orientation precedent, plus the grep sync
- **Alternatives considered**: edit only the grep copy at `:54` (the cross-track correction); also add `§ Plain language` everywhere #1142 added `§ Orientation` (chosen).
- **Rationale**: #1142 touched `readability-feedback/SKILL.md` in two spots beyond any grep — it added `§ Orientation` to the STEP-1 "Read the authoritative ruleset … Note especially" list (`:70`) and a STEP-4 classification sentence ("a too-terse passage is `CAUGHT by § Orientation`, not a GAP"). The readability audit looks for hard-to-read paragraphs, and plain-language violations (jargon, long sentences, idioms) are exactly that, so the same two adds apply for `§ Plain language`. The grep sync at `:54` is the separate Track-1 Phase C cross-track correction.
- **Risks/Caveats**: the STEP-4 sentence is a judgment add parallel to the Orientation one; keep it one line.
- **Implemented in**: this track (`readability-feedback/SKILL.md`): STEP-1 read-list, STEP-4 classification sentence, and the `:54` grep sync (D2-3 grep half).

The remaining 14 files in this track are mechanical slug additions that execute plan D3 (natural reach) and plan D4 (the §1.7(k) opt-out edits them live): the 10 prompt preambles and the 4 skill blockquotes. They carry no track-local decision; the rationale is the plan's D3/D4. The three content-edit files above (`design-review.md`, `ai-tells/SKILL.md`, `readability-feedback/SKILL.md`) carry no preamble or blockquote, so they get only the content edits in D2-1/D2-2/D2-3.

## Outcomes & Retrospective
<!-- Continuous-log. -->
- [x] Technical: PASS at iteration 2 (3 findings — 1 should-fix, 2 suggestions — all accepted and applied)
- [x] Adversarial: PASS at iteration 2 (5 findings — 3 should-fix, 2 suggestions — all accepted and applied)
- Risk: not run — lite tier with no warranting characteristic (no critical path, no performance constraint, no major architectural decision), per the Phase-3A tier-driven review selection.
- [x] Phase C track-level code review: PASS at iteration 1. Workflow-only diff, so the baseline group skipped and 5 workflow reviewers ran (consistency, prompt-design, instruction-completeness, context-budget, writing-style; hook-safety did not fire — no hooks/scripts/settings touched). Consistency raised 3 findings and context-budget 1, all four against `design-review.md` and all from one root cause: Step 1 raised the cold-read Prose AI-tell block to three scan axes but left three downstream restatement/routing sites describing two, and the block summary overran the §1.8 120-char cap (failing `workflow-reindex.py --check`). All four fixed in `Review fix:` b47ad98135 and VERIFIED at gate-check. prompt-design, instruction-completeness, and writing-style were clean (writing-style confirmed the new prose meets the branch's own `## Plain language` rule).

The reviews converged on one substantive correction and several count fixes, all in the track file's own prose: the design-review cold-read edit was pointed at the wrong block (Human-reader rules instead of `### Prose AI-tell additions`, fixed in D2-1); `design-review.md` has no five-slug preamble so the prompt count is 10 not 11; only 4 of the 6 skills carry a blockquote enumeration; and the verbatim grep target was pinned byte-identical to `conventions.md:572`. The `ai-tells` catalogue question (plan CR1) and the `readability-feedback` multi-spot edits were resolved against the #1142 Orientation precedent (D2-2, D2-3). One factual phrase in the plan Component Map (line 54, the wrong block name) was corrected to match.

## Context and Orientation
10 of the 11 workflow prompts under `.claude/workflow/prompts/` carry a house-style preamble naming the five subset slugs (the "five AI-tell subset section slugs to apply are …" sentence, e.g. `adversarial-review.md:43`, `technical-review.md:31`, `consistency-review.md:96`). The 11th, `design-review.md`, carries no such preamble — its house-style wiring rides the writers, not this prompt (`design-review.md:217`, "AI-tell subset wiring on the writers, not by this block"). It cites the subset only in its cold-read blocks. The block the new rule must join is `### Prose AI-tell additions` (`design-review.md:186-217`), not the `### Human-reader cold-read additions` block (`:169-184`): the Prose AI-tell block already checks `§ Orientation` (`:207`), runs on `target=tracks` (`:189-195`), and is where the `readability-feedback/SKILL.md:47` sync map routes a clarity rule. The "five Human-reader rules" at `:181` / `:458` are a different family (audience-fit, glossary-introduction, why-before-what, navigability, explanatory register) and stay five.

Of the 6 skills under `.claude/skills/`, 4 carry the five-slug "House style for chat-scale prose" blockquote to extend: `create-plan/SKILL.md:23`, `execute-tracks/SKILL.md:23`, `review-plan/SKILL.md:31`, `review-workflow-pr/SKILL.md:42`. The other 2 cite the subset in a richer form and get a content edit, not a slug add: `ai-tells/SKILL.md:19`–`:29` holds a `## Catalogue lookups` fingerprint→section map (six rows, two sections outside the subset), so it gains a `## Plain language` row (D2-2, resolving the plan's CR1 question — confirmed by the #1142 precedent, which added a `§ Orientation` row to the same catalogue); `readability-feedback/SKILL.md` has no preamble at all — its subset touchpoints are the STEP-1 read-list (`:70`), the STEP-4 classification step, and the `:54` grep, all handled by D2-3.

`readability-feedback/SKILL.md:54` also holds a verbatim copy of the `conventions.md §1.5` rename-detection grep, introduced as the way to "find every pointer and update them in the same commit". Track 1's Phase C completed that helper in `conventions.md` from four Tier-B headings to six (the two common-word names `## Orientation` / `## Plain language` anchored to their `##`/`§` heading-pointer form, the other four bare, switched to `grep -rnE`). The `SKILL.md:54` copy still lists the old four bare names with `grep -rn` and BRE `\|` alternation, so the two helpers now diverge; this track syncs the copy to match. The exact target — byte-identical to the command at `conventions.md:572` — is:

```bash
grep -rnE '## Orientation|## Plain language|§ Orientation|§ Plain language|Banned vocabulary|Banned sentence patterns|Banned analysis patterns|Em-dash discipline' .claude/ CLAUDE.md
```

The sync is two changes, not one: `grep -rn` → `grep -rnE` (BRE `\|` → ERE `|`), and the four bare names → eight alternatives (the two common-word names each anchored in both `##` and `§` form). A one-sentence anchoring caveat goes as prose right after the fence (the two common words match only in heading-pointer form, the other four bare), mirroring the `conventions.md` caveat so the anchored-vs-bare split is self-explaining. This is a grep edit, not a slug add (Track 1 Phase C cross-track correction).

This track depends on Track 1: the `## Plain language` section and the §1.5 mapping must exist before these enumerations name the slug.

## Plan of Work
1. Add `## Plain language` to the five-slug preamble in each of the 10 prompts that carry it (every prompt except `design-review.md`), after `## Orientation`, matching the existing slug order. Flip the count word in that one preamble sentence from "five" to "six". The flip is scoped to the subset-enumeration sentence only — `design-review.md` is not in this step, and no other "five" in any file is touched.
2. In `design-review.md`, add a one-line `§ Plain language` lens to the `### Prose AI-tell additions` block (`:186-217`), update that block's TOC summary at `:23`, and extend the `§ Tone and depth` "second exception" evidence clause at `:458` to name the rule, so the cold-read checks plain language on both `target=design` and `target=tracks`, not just lists it (D2-1). Do NOT touch the `### Human-reader cold-read additions` block or the "five Human-reader rules" count at `:181` / `:458`.
3. Add `## Plain language` to the five-slug "House style for chat-scale prose" blockquote in each of the 4 skills that carry it: `create-plan`, `execute-tracks`, `review-plan`, `review-workflow-pr`. The blockquote wrapping is non-uniform across the four (lead-in and line-wrap differ), so this is a per-site edit, not one find-replace.
4. In `ai-tells/SKILL.md`, add a `## Plain language` row to the `## Catalogue lookups` block (D2-2), parallel to the `§ Orientation` row #1142 added — a plain-language fingerprint (uncommon word where a common one fits, long tangled sentence, idiom) pointing at `§ Plain language`.
5. In `readability-feedback/SKILL.md` (D2-3): add `§ Plain language` to the STEP-1 "Note especially …" read-list (`:70`); add a one-line STEP-4 classification sentence parallel to the Orientation one (a passage that is hard to read for uncommon words, long sentences, or idioms is `CAUGHT by § Plain language`); and sync the rename-detection grep copy at `:54` to the byte-identical six-alternative `grep -rnE` form shown in `## Context and Orientation` above, with the anchoring caveat as prose after the fence.

Invariant to preserve: every prompt and skill enumeration this track touches ends at exactly six slugs in the canonical order, and the one preamble-sentence count it touches reads "six". `design-review.md`'s "five Human-reader rules" count stays five. The `readability-feedback/SKILL.md:54` grep copy command is byte-identical to the live `conventions.md §1.5` helper.

## Concrete Steps

1. Edit the 11 prompt files under `.claude/workflow/prompts/`. In the 10 that carry the five-slug preamble (every prompt except `design-review.md`), append `## Plain language` after `## Orientation` and change the count word in that one sentence from "five" to "six". In `design-review.md` (which has no preamble), add a one-line `§ Plain language` lens to the `### Prose AI-tell additions` block, name the rule in that block's TOC summary at `:23`, and add it to the `:458` "second exception" evidence clause — leaving the `### Human-reader cold-read additions` block and its "five Human-reader rules" count untouched (D2-1). Write any new prose in plain language (self-application). — risk: medium (workflow machinery: the design-review cold-read lens is a bounded behavioral edit that changes agent-observable review behavior; the 10 preamble flips are prose-only)  [x] commit: 308dd68bd4
2. Edit the 6 skill files under `.claude/skills/`. Add `## Plain language` to the five-slug "House style for chat-scale prose" blockquote in the 4 that carry it (`create-plan`, `execute-tracks`, `review-plan`, `review-workflow-pr`) — a per-site edit, since the wrapping differs across the four. Add a `## Plain language` row to `ai-tells/SKILL.md`'s `## Catalogue lookups` block (D2-2). In `readability-feedback/SKILL.md` (D2-3), add `§ Plain language` to the STEP-1 "Note especially …" read-list, add a one-line STEP-4 classification sentence parallel to the Orientation one, and sync the `:54` grep copy to the byte-identical six-alternative `grep -rnE` form (BRE→ERE, four names→eight) with the anchoring caveat as prose after the fence. — risk: medium (workflow machinery: the ai-tells catalogue row and the readability classification lens are bounded behavioral edits; the 4 blockquote flips are prose-only) — size: ~6 files; reason (a): end of track — the only other low/medium work is Step 1's 11 prompt files, and merging it would total ~17 and trip the ~14 overblown line  [x] commit: 1256d0d555

## Episodes
<!-- Continuous-log. Phase B appends one block per completed step. Empty at Phase 1. -->

### Step 1 — commit 308dd68bd415f202e1943152146bdfdb9e72d2f6, 2026-06-14T07:24Z [ctx=safe]
**What was done:** Added `## Plain language` after `## Orientation` in the six-slug AI-tell subset preamble of the 10 workflow prompts that carry it, and changed that sentence's count word from "five" to "six". In `design-review.md`, which has no preamble, applied the D2-1 cold-read edit: a `§ Plain language` lens in the `### Prose AI-tell additions` block, the matching block summary and `:23` TOC row, and the rule named in the `§ Tone and depth` "second exception" evidence clause. The `### Human-reader cold-read additions` block and its "five Human-reader rules" count were left as they were.

**What was discovered:** The `### Prose AI-tell additions` block lists its scan targets on two named axes (over-dense, too-terse), so the natural home for the new lens was a third "hard-to-read" axis. That made three sync points, not the two the step named: the axis bullet, the "both axes" to "three axes" lead-in, and the block's own `<!-- summary= -->` comment, all kept in step with the `:23` TOC row. The result still reads as one short lens, as D2-1 intended.

**Key files:**
- `.claude/workflow/prompts/adversarial-review.md` (modified)
- `.claude/workflow/prompts/consistency-review.md` (modified)
- `.claude/workflow/prompts/consistency-gate-verification.md` (modified)
- `.claude/workflow/prompts/structural-review.md` (modified)
- `.claude/workflow/prompts/structural-gate-verification.md` (modified)
- `.claude/workflow/prompts/technical-review.md` (modified)
- `.claude/workflow/prompts/risk-review.md` (modified)
- `.claude/workflow/prompts/review-gate-verification.md` (modified)
- `.claude/workflow/prompts/dimensional-review-gate-check.md` (modified)
- `.claude/workflow/prompts/create-final-design.md` (modified)
- `.claude/workflow/prompts/design-review.md` (modified)

### Step 2 — commit 1256d0d555d0bab55c32ab6069cf2cb7206097d9, 2026-06-14T07:30Z [ctx=safe]
**What was done:** Added the `## Plain language` slug to the six skills that name the AI-tell subset. The four skills with the five-slug "House style for chat-scale prose" blockquote (`create-plan`, `execute-tracks`, `review-plan`, `review-workflow-pr`) gained `## Plain language` after `## Orientation`, done as four per-site edits because the blockquote wrapping differs across them. `ai-tells/SKILL.md` gained a `## Plain language` row in its `## Catalogue lookups` block (D2-2), naming a plain-language fingerprint and pointing at `§ Plain language`, kept distinct from the `§ Banned vocabulary` row by covering general-English clarity outside the closed word list. `readability-feedback/SKILL.md` got three edits (D2-3): `§ Plain language` added to the STEP-1 read-list, a parallel STEP-4 classification line (a passage hard to read for uncommon words, long sentences, or idioms is `CAUGHT by § Plain language`), and the rename-detection grep copy synced byte-identical to the `conventions.md:572` helper.

**What was discovered:** The grep target uses the U+00A7 section sign `§`, not an ASCII stand-in. A byte comparison confirmed the synced command equals `conventions.md:572` exactly, including the `§` characters and the BRE-to-ERE switch (`grep -rn` to `grep -rnE`, four bare names to eight anchored-or-bare alternatives).

**Key files:**
- `.claude/skills/create-plan/SKILL.md` (modified)
- `.claude/skills/execute-tracks/SKILL.md` (modified)
- `.claude/skills/review-plan/SKILL.md` (modified)
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)
- `.claude/skills/ai-tells/SKILL.md` (modified)
- `.claude/skills/readability-feedback/SKILL.md` (modified)

## Validation and Acceptance
- Each of the 10 preamble-carrying prompts lists `## Plain language` in its five-slug preamble (now six slugs); that sentence's count word reads "six".
- `design-review.md`'s `### Prose AI-tell additions` block (not the Human-reader block) includes the Plain-language check, its TOC summary at `:23` names it, and the `:458` "second exception" clause lists it. The "five Human-reader rules" count at `:181` / `:458` is unchanged (still five).
- Each of the 4 enumerating skills (`create-plan`, `execute-tracks`, `review-plan`, `review-workflow-pr`) lists `## Plain language` in its blockquote.
- `ai-tells/SKILL.md`'s `## Catalogue lookups` block has a `## Plain language` row.
- `readability-feedback/SKILL.md` lists `§ Plain language` in its STEP-1 read-list and STEP-4 classification step, and its `:54` grep command is byte-identical to `conventions.md:572`.
- All edited prose reads in plain language (self-application).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope (this track):**
- 11 prompts: `.claude/workflow/prompts/{technical-review,consistency-review,structural-gate-verification,review-gate-verification,adversarial-review,risk-review,create-final-design,consistency-gate-verification,dimensional-review-gate-check,structural-review,design-review}.md`. 10 of these get the preamble slug add; `design-review.md` carries no preamble and gets only the cold-read content edit (D2-1).
- 6 skills: `.claude/skills/{review-plan,create-plan,execute-tracks,review-workflow-pr}/SKILL.md` get the blockquote slug add; `ai-tells/SKILL.md` gets the catalogue-row content edit (D2-2) and `readability-feedback/SKILL.md` gets the read-list + STEP-4 + grep content edits (D2-3).

**Out of scope (other tracks):** `house-style.md`, `house-conversation.md`, `conventions.md`, the core workflow docs, the hook, the test, `CLAUDE.md` (Track 1); the 20 review agents (Track 3).

**Dependencies:** depends on Track 1 (the `## Plain language` section and §1.5 mapping must exist first). Independent of Track 3.

**Exact in-scope set, reconciled at Phase A** via `grep -rln 'Banned analysis patterns' .claude/workflow/prompts/ .claude/skills/`: exactly 11 prompt files and 6 skill files (17 total), matching the lists above. Edit-kind breakdown: 14 slug adds (10 prompt preambles + 4 skill blockquotes) and 3 content edits (`design-review.md`, `ai-tells/SKILL.md`, `readability-feedback/SKILL.md`). The `ai-tells/SKILL.md` catalogue question (plan CR1) is resolved in D2-2.

## Base commit
eccdad91dfa09d35b15732a5fe2811b6b6da1f63
