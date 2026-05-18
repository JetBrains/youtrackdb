# Track 3: Refactor `prompts/design-review.md` to verification-only

## Purpose / Big Picture
After this track lands, the cold-read sub-agent prompt at `.claude/workflow/prompts/design-review.md` is ≤200 lines and contains no declarative rule statements — every verification entry names the rule it checks and cites `house-style.md § <Section>` rather than restating the rule's content.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Strip declarative rule statements from `prompts/design-review.md § Human-reader cold-read additions` (audience-fit, glossary-introduction, why-before-what, navigability) and from `§ Structural findings` (Overview concept-first, References footer, Edge cases, same-shape siblings). Each verification entry must reference the rule by name (e.g., "Verify audience-fit per house-style.md § Audience-fit"). Keep Q1-Q7 comprehension questions, plan-deviation surfacing for `phase4-creation`, and the mutation-kind-specific instructions. Final file must be ≤ 200 lines.

## Progress
- [x] Review + decomposition
- [x] 2026-05-18T09:01Z [ctx=safe] Review + decomposition complete
- [x] Step implementation
- [x] 2026-05-18T09:24Z [ctx=safe] Step 1 complete (commit d0045927e1)
- [x] 2026-05-18T09:39Z [ctx=safe] Step 2 complete (commit ec2e39ca13)
- [x] Track-level code review
- [x] 2026-05-18T11:24Z [ctx=info] Track-level code review iteration 1 complete (1/3 iterations)
- [x] Track completion
- [x] 2026-05-18T11:33Z [ctx=info] Track complete

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

- [x] Technical: PASS at iteration 2 (4 findings, 4 accepted — T1/T2 should-fix, T3/T4 suggestion; gate-check VERIFIED all four)
- [x] Track-level code review iteration 1: PASS (5 should-fix findings synthesised across 5 reviewer dimensions, all VERIFIED at gate-check — 4 cross-file consistency / instruction-completeness issues in `design-review.md` + 1 instant-axis read-scoping rule for `house-style.md`; no new findings; no regressions)
- [x] Track complete: 2 Phase B steps, 0 failed, 1 Phase C review-fix iteration. File `.claude/workflow/prompts/design-review.md` finalised at 199 lines under the 200-line cap with all eight `house-style.md § <heading>` cross-references resolving verbatim.

## Context and Orientation

Starting state of `.claude/workflow/prompts/design-review.md` (346 lines, surveyed during research):

- **Lines 1-44 — Header, Inputs, Mutation-kind specific instructions.** Keep wholesale. These describe the prompt's interface (the sub-agent's inputs) and the per-mutation-kind variations (`phase1-creation`, `design-sync`, `phase4-creation`). Trimming these would break callers.
- **Lines 45-89 — Mutation-kind specific instructions for `phase1-creation`, `design-sync`, `phase4-creation`** (continued). Keep wholesale. The `phase4-creation` plan-deviation block (lines 75-89) is explicitly preserved per the issue acceptance criteria.
- **Lines 90-183 — Human-reader cold-read additions.** This is the largest block to strip. Sub-sections (a) Audience-fit (lines 119-129), (b) Glossary-introduction (lines 131-148), (c) Why-before-what (lines 150-162), (d) Navigability (lines 164-172) currently each restate the rule body. Replace each with a one-line verification statement that names the rule and cites `house-style.md § <Section>`. The "Reviewer tone" prose (lines 174-183) — exception to the one-sentence-answers guidance — stays as procedural meta.
- **Lines 185-199 — Reading rules.** Keep wholesale. Procedural.
- **Lines 200-225 — Comprehension questions (Q1-Q7).** Keep wholesale per the issue acceptance criteria. Q1-Q7 are the cold-read's load-bearing content.
- **Lines 227-256 — Structural findings (always check).** Ten bullets currently restate rule bodies. Four have a `house-style.md` mapping and become one-line "Verify <rule> per `.claude/output-styles/house-style.md § <full H3 heading>`" form — cite the H3 heading verbatim, not a shortened form: **Edge cases / Gotchas** → `§ Edge cases sub-section required`, **References footer** → `§ References footer shape`, **Same-shape sibling check** → `§ Same-shape sibling consolidation`, **Overview is concept-first** → `§ Overview concept-first`. The other six bullets have no `house-style.md` mapping and either stay-as-is or cite `design-document-rules.md § Mechanical checks` / the `design-mechanical-checks.py` script as their authority: **TL;DR present**, **Mechanism overview prose ≤300 lines**, **`Mechanics:` link target**, **Length budget**, **Core Concepts current and complete** (whole-doc only), **`**Full design**` refs** (whole-doc only).
- **Lines 258-308 — Output format.** Keep wholesale. The exact Markdown output shape is the sub-agent's contract with the calling mutation action.
- **Lines 310-329 — Severity rubric.** Keep wholesale. Procedural — describes how the sub-agent classifies findings.
- **Lines 330-346 — Tone and depth.** Keep wholesale. Procedural — meta-rules for the sub-agent's writing.

Estimated trim impact:
- Lines 1-89 (Mutation-kind block): keep ~89 lines.
- Lines 90-183 (Human-reader additions): trim from ~93 lines to ~25 lines.
- Lines 185-199 (Reading rules): keep ~14 lines.
- Lines 200-225 (Comprehension questions): keep ~25 lines.
- Lines 227-256 (Structural findings): trim from ~30 lines to ~18 lines (four cite-`house-style.md` one-liners + six stay-as-is or `design-document-rules.md`-cited bullets).
- Lines 258-346 (Output format + Severity + Tone): keep ~89 lines.

Estimated final length: ~157-170 lines, comfortably under the 200-line cap.

## Plan of Work

The approach trims the two declarative-content blocks (Human-reader additions, Structural findings) in place, leaving the surrounding scaffold (mutation-kind instructions, comprehension questions, output format, severity rubric, tone meta) untouched. Order:

1. **Strip the Human-reader cold-read additions to verification-only form.** Replace each of the four sub-sections — (a) Audience-fit, (b) Glossary-introduction, (c) Why-before-what, (d) Navigability — with a single-bullet "Verify X per house-style.md § Y" statement. Keep the severity guidance ("blocker if the reader cannot follow the Overview's main argument; should-fix otherwise") since severity is a per-prompt decision, not a declarative writing rule. Keep the "Reviewer tone exception" prose since it's procedural meta about how the sub-agent should write findings.
2. **Strip the Structural findings block to verification-only form.** Of the ten bullets currently in the block, four have a `house-style.md` mapping and become one-line "Verify <rule> per `.claude/output-styles/house-style.md § <full H3 heading>`" form — cite the H3 heading verbatim:
   - **Edge cases / Gotchas** → `§ Edge cases sub-section required`
   - **References footer** → `§ References footer shape`
   - **Same-shape sibling check** → `§ Same-shape sibling consolidation`
   - **Overview is concept-first** → `§ Overview concept-first`

   The remaining six bullets have no `house-style.md` mapping and stay as-is (cross-file mechanics or `design-document-rules.md § Mechanical checks` territory enforced by `design-mechanical-checks.py`): **TL;DR present** (mechanical: `dsc-tldr-shape`), **Mechanism overview prose ≤300 lines** (mechanical: `dsc-mechanism-length`), **`Mechanics:` link target** (cross-file mechanics — link resolution), **Length budget** (mechanical: `dsc-length-budget`), **Core Concepts current and complete** — whole-doc only (mechanical: `dsc-core-concepts-current`), **`**Full design**` refs** — whole-doc only (cross-file mechanics — link resolution). Do NOT invent `house-style.md` cross-references for these.

   Acceptance check (after the trim): `grep -nE "house-style\.md § " .claude/workflow/prompts/design-review.md | awk -F'§ ' '{print $2}' | sort -u` returns only section names that exist in `.claude/output-styles/house-style.md`. Run `grep -nE "^### " .claude/output-styles/house-style.md` to confirm each cited name matches an on-disk H3 heading verbatim.
3. **Verify ≤200 lines.** Run `wc -l .claude/workflow/prompts/design-review.md` and confirm ≤200. If over, identify which block is over-budget and trim further; the natural candidates for additional trimming are the Comprehension questions (Q1-Q7 — these are non-negotiable per acceptance criteria, so this is last resort) or the Tone and depth section (procedural prose that could be tightened).

Invariants to preserve:
- Q1-Q7 stay intact (acceptance criterion).
- `phase4-creation` plan-deviation surfacing instructions stay intact (acceptance criterion).
- Mutation-kind-specific instructions (`phase1-creation`, `design-sync`, `phase4-creation` blocks) stay intact (acceptance criterion).
- Output format remains the exact Markdown shape the calling mutation action expects.
- Cross-references cite section names that exist in `house-style.md` as defined in Track 1.

## Concrete Steps

1. Trim the Human-reader cold-read additions block (lines 90-183) to verification-only form: replace each of the four sub-sections (Audience-fit, Glossary-introduction, Why-before-what, Navigability) with a single bullet of the form `Verify <rule> per .claude/output-styles/house-style.md § <full H3 heading verbatim>`; trim the block's three-paragraph rule-rationale intro (lines 92-115) down to a one-paragraph "what this block does" preamble (the *why* now lives in house-style.md); preserve the "Reviewer tone exception" prose (lines 174-183) wholesale. Acceptance: `grep -nE "house-style\.md § " .claude/workflow/prompts/design-review.md` returns the four cited H3 names from the cleared Human-reader block, and each cited name appears verbatim in the output of `grep -nE "^### " .claude/output-styles/house-style.md`. — risk: low (default: workflow-prompt markdown edit; no production code, no API, no symbols)  [x] commit: d0045927e1
2. Trim the Structural findings block (lines 227-256) to verification-only form per the four-cite / six-stay-as-is split in `## Plan of Work` Step 2, and bring the file to ≤200 lines: the two block trims alone leave the file at ~260 lines, so additional compression is required against the procedural-prose fallback named in `## Plan of Work` Step 3 (tighten `## Tone and depth` lines 330-346; if still over, compress the Severity rubric or Reading rules prose; **never** touch Q1-Q7, the `phase4-creation` plan-deviation block, the mutation-kind instructions, or the Output format — these are acceptance-criteria-preserved blocks). Acceptance: `wc -l .claude/workflow/prompts/design-review.md` returns ≤ 200; the four cite-house-style.md H3 names in the trimmed Structural block all match on-disk H3 headings verbatim (same `grep -nE "^### "` check as Step 1); the four preserved blocks listed above are byte-identical to their pre-Track-3 state (`git diff` shows zero changes inside their line ranges); cross-references from `design-document-rules.md:374`, `edit-design/SKILL.md:252`, `:530`, `create-final-design.md:173`, `house-style.md:20` still resolve. — risk: low (default: workflow-prompt markdown edit; no production code, no API, no symbols)  [x] commit: ec2e39ca13

## Episodes

### Step 2 — commit ec2e39ca13, 2026-05-18T09:39Z [ctx=safe]
**What was done:** Trimmed the `## Structural findings (always check)` block in `.claude/workflow/prompts/design-review.md` to verification-only form: four cite-house-style.md bullets (Edge cases sub-section required, References footer shape, Same-shape sibling consolidation, Overview concept-first) and six bullets citing `design-mechanical-checks.py` rule IDs (`dsc-tldr-shape`, `dsc-mechanism-length`, `dsc-length-budget`, `dsc-core-concepts-current`) plus cross-file link mechanics (`Mechanics:` link target, `**Full design**` refs). Brought the file to **198 lines** (from 281 at the end of the prior step, under the 200-line cap) by tightening procedural-prose fallback targets named in the plan: `## Tone and depth`, `## Severity rubric`, `## Reading rules`, the file intro, `## Inputs`, the Human-reader cold-read additions intro and Reviewer tone block, and the Comprehension questions preamble.

**What was discovered:** The Output format markdown block (preserved verbatim per acceptance) still carries an orphan reference to "(a) Audience-fit" using the old (a)(b)(c)(d) label scheme that no longer matches the trimmed Human-reader bullets. The Reviewer tone block in the Human-reader section was rewritten in this step so its labels now match. Cleaning the Output format example reference would require touching a preserved block and is out of scope for this track.

**What changed from the plan:** none

**Key files:**
- `.claude/workflow/prompts/design-review.md` (modified)

**Critical context:** All four preserved blocks (mutation-kind instructions, Q1-Q7 comprehension questions, `phase4-creation` plan-deviation block, Output format markdown block) verified byte-identical to base_commit c352677aef. All eight cited house-style.md section names appear verbatim as `### ` headings in `.claude/output-styles/house-style.md`. Cross-references at `design-document-rules.md:374` (cites `§ Human-reader cold-read additions` — heading preserved), `edit-design/SKILL.md:252` and `:530`, `create-final-design.md:173`, and `house-style.md:20` still resolve. Track-level acceptance criteria (≤200 lines + cross-refs resolve) now satisfied; a manual "no declarative rule body remains" pass through the file should happen at track completion.

### Step 1 — commit d0045927e1, 2026-05-18T09:24Z [ctx=safe]
**What was done:** Trimmed the `### Human-reader cold-read additions` block in `.claude/workflow/prompts/design-review.md` to verification-only form. Replaced the three-paragraph rule-rationale intro (former lines 92-115) with a one-paragraph "what this block does" preamble pointing readers at `.claude/output-styles/house-style.md` for the rule bodies. Replaced the four declarative sub-sections (Audience-fit, Glossary-introduction, Why-before-what, Navigability) with four single-bullet `Verify <rule> per .claude/output-styles/house-style.md § <section>` entries. Preserved the "Reviewer tone" exception prose wholesale per the plan. Net delta: +10 / −75 lines; file size 346 → 281 lines.

**What was discovered:** The "Reviewer tone" prose preserved wholesale still cites "(a), (b), (c), and (d)" as orphan label references — those labels no longer exist after the trim, but the bullet topic labels (audience-fit, glossary-introduction, why-before-what, navigability) reconstruct the intent unambiguously. The Output format section at line ~226 has a similar orphan `(a) Audience-fit` example reference. The next step trims the Structural findings block but does not touch the Output format section per the acceptance-criteria-preserved list; cleaning these orphan label refs would be a separate follow-up edit outside this track's scope.

**What changed from the plan:** none

**Key files:**
- `.claude/workflow/prompts/design-review.md` (modified)

**Critical context:** Acceptance check ran clean — `grep -nE "house-style\.md § "` returns the four cited names at lines 104-107; each cited name matches an on-disk `### ` heading in `house-style.md` verbatim (lines 315, 319, 329, 335). File is at 281 lines; the next step must bring it under 200 per the track-level acceptance criterion.

## Validation and Acceptance

Track-level acceptance: `wc -l .claude/workflow/prompts/design-review.md` returns ≤200 AND every cross-reference resolves to an existing section in `house-style.md` AND a manual pass through the file confirms no declarative rule body remains (each rule is named-and-cited only).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/prompts/design-review.md` (in-place edit of two content blocks; surrounding scaffold preserved).

**Out-of-scope files** (inbound references to `prompts/design-review.md` — all survive the refactor):
- `.claude/output-styles/house-style.md` — path-only mention at line 20 (Track 1 owns its content; this track only adds inbound references).
- `.claude/workflow/design-document-rules.md` — path-only at lines 153 and 277. **Line 374 cites `§ Human-reader cold-read additions` by H3 heading name — Track 3 MUST preserve that exact H3 heading text verbatim; only the body inside that H3 changes.**
- `.claude/workflow/prompts/create-final-design.md` — path-only mention at line 173.
- `.claude/skills/edit-design/SKILL.md` — line 252 says "the full content of `.claude/workflow/prompts/design-review.md`" (substitution-style — change-tolerant), line 530 is path-only.

**Inter-track dependencies:**
- **Depends on:** Track 1. Verification entries cite `house-style.md § <Section>` names that Track 1 fixes.

**Library / function signatures:** none. Markdown editing.

**Caller contract:**
- The `edit-design` skill invokes this prompt with the inputs listed at the top of the file (`design_path`, `design_mechanics_path`, `scope`, `mutation_kind`, `plan_path`, `plan_dir`). The skill's invocation surface is unchanged; only the prompt's internal content changes.
- The prompt's output format (the exact Markdown structure at lines 258-308) is the sub-agent's return contract. Any deviation breaks the calling skill's parsing.

## Base commit

c352677aef36310e2fe03343f7b6fdc4d2c8afed
