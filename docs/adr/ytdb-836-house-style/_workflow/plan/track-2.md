# Track 2: Trim `ai-tells/SKILL.md` to procedural overlay

## Purpose / Big Picture
After this track lands, `.claude/skills/ai-tells/SKILL.md` is a ≤80-line procedural overlay — it tells a user how to run an audit/rewrite, but every catalogue lookup (which words are banned, which structural patterns are banned, which tone is banned) routes through `house-style.md` by section name.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Strip the static catalogue lists (vocabulary tiers, structural tells, tone tells, punctuation tells, content tells, era-specific tells) from `ai-tells/SKILL.md`. Keep the audit/rewrite mode toggle, the "if the sentence collapses without the opener, delete the whole sentence" diagnostic, and the before/after rewrite examples. Add cross-references to `house-style.md § <Section>` for the catalogue lookups, so a user invoking the skill is directed to the consolidated declarative source. Final file must be ≤ 80 lines.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-18T06:32Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-18T08:22Z [ctx=safe] Step 1 complete (commit d507c7318e)

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion summary at Phase C. -->
- [x] Technical: PASS at iteration 3 (6 findings, 5 accepted). T1 (should-fix) — frontmatter described as `name`/`description`/`argument-hint` triple at track-2.md:28; actual file has only the `name`/`description` pair. T2 (suggestion) — "Replace lines 19-124" boundary made explicit ("inclusive — drop the `## The catalogue` H2 at :19 and all six `###` sub-sections through the blank line at :124"; pre-`## Workflow` blank line preserved). T3 (suggestion) — collapse diagnostic at SKILL.md:85 keyed off the dropped tone-tells list; re-anchored to `house-style.md § Banned sentence patterns` with stand-alone rewording. T4 (suggestion) — line-budget buffer warning rejected; step 2's `wc -l` gate is self-healing. T5 (suggestion) — appended mechanical grep test for invariant 3 to `## Validation and Acceptance`. T6 (should-fix, new at iter-2) — same root cause as T1; invariant 1 at track-2.md:74 still said "triple" after iter-1 fix; corrected at iter-2 to `name`/`description` pair. Iter-3 gate verification confirmed all fixes landed with no regressions.

## Context and Orientation

Starting state of `.claude/skills/ai-tells/SKILL.md` (156 lines, surveyed during research):

- **Frontmatter (lines 1-4):** the `name` / `description` pair (no `argument-hint` field is present today). The `description` enumerates the skill's trigger phrases ("humanize this", "de-AI this", "edit this draft"). Keep as-is — the trigger surface is unchanged.
- **Modes section (lines 9-18):** Audit vs. Rewrite mode toggle, default behavior. Keep.
- **The catalogue (lines 19-124):** Vocabulary tier 1/2/3, Structural tells, Tone and opener tells, Punctuation tells, Content and analysis tells, Era-specific tells. This is the ~70% catalogue overlap with `concise-doc.md` / `house-style.md` — strip and replace with cross-references.
- **The collapse diagnostic (lines 85-86):** "Test: if the sentence still works without the opener phrase, delete the phrase. If the sentence collapses without it, the sentence is filler, so delete the whole sentence." Keep — this is a procedural rule, not a declarative one.
- **Workflow (lines 126-131):** 4-pass procedure (read fully, flag, score, rewrite, self-audit). Keep.
- **Output format (lines 133-143):** 3-block return shape (Audit, Density score, Clean rewrite). Keep.
- **What this skill does not do (lines 145-149):** scope-limit prose. Keep — it's procedural meta.
- **Source and credit (lines 151-156):** Wikipedia + Kyle Balmer + MIT license attribution. Keep — license obligation.

After the trim:
- Header (frontmatter + brief intro): ~5 lines
- Modes: ~10 lines
- Trimmed catalogue (cross-references only): ~15 lines
- Workflow: ~10 lines
- Output format: ~10 lines
- Scope-limit prose: ~5 lines
- Source / credit: ~10 lines
- Cross-reference block to `house-style.md`: ~10 lines

Estimated final length: ~75 lines, comfortably under the 80-line cap.

## Plan of Work

The approach replaces the catalogue body wholesale; the surrounding scaffold (modes, workflow, output format, attribution) stays. Order:

1. **Trim the catalogue + insert cross-references.** Replace lines 19-124 inclusive — drop the `## The catalogue` H2 at :19 and all six `###` sub-sections through the blank line at :124 — with a "Catalogue lookups" H2 block:

   ```markdown
   ## Catalogue lookups

   Every category previously enumerated here lives in `house-style.md`. Walk these sections during Pass 1:

   - Vocabulary fingerprints → `house-style.md § Banned vocabulary` (Tier 1 / 2 / 3 / Era-specific)
   - Structural fingerprints → `house-style.md § Structural rules` and `§ Banned sentence patterns`
   - Tone fingerprints → `house-style.md § Banned sentence patterns` (sycophantic openers, throat-clearing, closing phrases)
   - Punctuation fingerprints → `house-style.md § Punctuation and typography`
   - Content and analysis tells → `house-style.md § Banned analysis patterns` (includes the 12 humanizer gap patterns)
   ```

   The replacement leaves the existing blank line above `## Workflow` intact.

   Keep the collapse-without-opener diagnostic — it's procedural, so attach it to the workflow section rather than the catalogue. The diagnostic at SKILL.md:85 currently keys off "the opener phrase" (its anchor was the dropped Tone-and-opener-tells bullet list). When moved into the Workflow section, reword it to stand alone — for example: "Test: if a sentence still reads cleanly without its opener phrase, delete the phrase; if the sentence collapses without the opener (sycophantic / throat-clearing / closing — see `house-style.md § Banned sentence patterns`), delete the whole sentence."

2. **Verify ≤80 lines.** Run `wc -l .claude/skills/ai-tells/SKILL.md` and confirm ≤80. If the result is over, identify which block is over-budget and trim further (the Modes section can shed prose without losing meaning; the Workflow section's 4-pass procedure can collapse to 3 passes if needed).

Invariants to preserve:
- The skill's frontmatter `name` / `description` pair stays intact. Trigger surface is unchanged.
- The audit/rewrite mode toggle, the collapse diagnostic, and the 3-block output format are kept verbatim — these are what the skill *does*, not what it knows.
- Every cross-reference cites a section that exists in `house-style.md` as defined in Track 1. If Track 1 renames any of the cited sections, this track must update the cross-references in lockstep.

## Concrete Steps

1. Trim `.claude/skills/ai-tells/SKILL.md` to a procedural overlay. Replace lines 19-124 inclusive — drop the `## The catalogue` H2 at :19 and all six `###` sub-sections (Vocabulary tiers, Structural tells, Tone and opener tells, Punctuation tells, Content and analysis tells, Era-specific tells) through the blank line at :124 — with a single `## Catalogue lookups` H2 carrying the five cross-references to `house-style.md` (Banned vocabulary, Structural rules + Banned sentence patterns, Banned sentence patterns, Punctuation and typography, Banned analysis patterns). Leave the existing blank line above `## Workflow` intact. Relocate the collapse-without-opener diagnostic out of the deleted Tone-tells section into the `## Workflow` section, rewording it to stand alone — for example: "Test: if a sentence still reads cleanly without its opener phrase, delete the phrase; if the sentence collapses without the opener (sycophantic / throat-clearing / closing — see `house-style.md § Banned sentence patterns`), delete the whole sentence." Keep verbatim: the frontmatter `name` / `description` pair (lines 1-4), the `## Modes` section (audit/rewrite toggle), the 4-pass `## Workflow` procedure, the 3-block `## Output format` section, `## What this skill does not do`, and the `## Source and credit` attribution. Acceptance gates (must pass before commit): (a) `wc -l .claude/skills/ai-tells/SKILL.md` returns ≤80; (b) the cross-reference grep loop from `## Validation and Acceptance` emits empty output (all five section names resolve in `house-style.md`); (c) the diagnostic now lives under `## Workflow` and reads as a self-contained sentence; (d) the frontmatter is byte-identical to its pre-edit form. If gate (a) fails, trim the Modes section's prose or collapse the 4-pass Workflow to 3 passes before re-running. — risk: low (default: single-file docs rewrite; no logic, no project-behavior change; cross-reference targets are stable Track 1 H2 names)  commit: d507c7318e  [x]

## Episodes

### Step 1 — commit d507c7318e, 2026-05-18T08:22Z [ctx=safe]
**What was done:** Replaced lines 19-124 of `.claude/skills/ai-tells/SKILL.md` (the `## The catalogue` H2 plus six `###` sub-sections — Vocabulary tiers, Structural tells, Tone and opener tells, Punctuation tells, Content and analysis tells, Era-specific tells) with a single `## Catalogue lookups` H2 carrying five cross-references to `house-style.md` (Banned vocabulary; Structural rules + Banned sentence patterns; Banned sentence patterns; Punctuation and typography; Banned analysis patterns). Relocated the collapse-without-opener diagnostic from the dropped Tone-tells section into `## Workflow`, reworded as a stand-alone sentence anchored to `house-style.md § Banned sentence patterns`. Frontmatter `name`/`description` pair, Modes toggle, 4-pass Workflow, 3-block Output format, "What this skill does not do", and Source-and-credit attribution preserved verbatim. File is now 62 lines (≤80-line cap, with 18 lines of headroom).

**Key files:**
- `.claude/skills/ai-tells/SKILL.md` (modified)

## Validation and Acceptance

Track-level acceptance: `wc -l .claude/skills/ai-tells/SKILL.md` returns ≤80 AND every cross-reference in the file resolves to an existing section in `house-style.md` AND the procedural content (modes, collapse diagnostic, workflow, output format) is intact.

Mechanical test for the cross-reference invariant — each of the five section names below must match an `^## ` H2 in `house-style.md`:

```bash
for s in "Banned vocabulary" "Structural rules" "Banned sentence patterns" \
         "Punctuation and typography" "Banned analysis patterns"; do
  grep -q "^## $s$" .claude/output-styles/house-style.md || echo "MISSING: $s"
done
```

Empty output ⇒ all five resolve. Any non-empty line names a section that needs to be re-resolved against the on-disk `house-style.md` headings before commit.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files:**
- `.claude/skills/ai-tells/SKILL.md` (full rewrite of catalogue section; surrounding scaffold preserved).

**Out-of-scope files:**
- `house-style.md` — Track 1 owns its content; this track only adds inbound references.
- Any other skill or agent under `.claude/skills/` or `.claude/agents/`.

**Inter-track dependencies:**
- **Depends on:** Track 1. Cross-references to `house-style.md § <Section>` are only valid once Track 1 has fixed the section names in the consolidated file.

**Library / function signatures:** none. Markdown editing.

**Cross-reference contract:**
- The cross-references in step 1 must cite sections by the names defined in `design.md § Internal layout of house-style.md`. If Track 1 deviates from the design's section list, that deviation must propagate to this track's cross-references.

## Base commit

c82e0ecf1c5edb2aa8d8a5d0e65bb095213bb376
