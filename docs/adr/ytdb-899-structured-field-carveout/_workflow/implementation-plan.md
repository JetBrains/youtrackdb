# Carve ExecPlan structured-field episodes out of the section length cap

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Rewrite the `house-style.md § Structural rules` "Section length cap" rule as a soft cap with two complements:

1. A categorical exemption list naming template-bound content shapes where every paragraph is load-bearing (ExecPlan structured-field paragraph blocks under `## Episodes`, edit-list subsections, full state-machine tables, file:line citation blocks, multi-step derivations under `design-mechanics.md`).
2. A padding-based finding criterion for free-form prose that exceeds 200 words: flag only when the section also contains banned vocabulary, banned sentence patterns, or restatement. Length alone is not a finding.

Propagate the wording to all other declarative restatements: `house-style.md § Self-check` step 7; four sites in `review-workflow-writing-style.md` (frontmatter `description:`, key rules list, review criteria, output-format template); `CLAUDE.md` line 102 (always-loaded house-style activation paragraph); and `.claude/skills/code-review/SKILL.md` line 313 (the `/code-review` agent-dispatch list, loaded on every Phase C track-level review). Add back-references from `episode-format-reference.md § Episode length rule` and `conventions-execution.md §2.2 Episode Formats` to the new house-style exemption.

This eliminates the recurring Phase C track-level writing-style DROP that fires on every track with substantive multi-paragraph episodes. Source: YTDB-899, observed on Track 2 of the `ytdb-837-activate-in-house-style` ADR, three findings DROPped at 578 / 489 / 496 words.

### Constraints

- The reviewer agent reads `house-style.md` once at review start. Its frontmatter `description:` is also loaded into every system reminder, and the dispatcher restatements in `CLAUDE.md` and `.claude/skills/code-review/SKILL.md` are loaded transitively whenever they're in scope. If the rule body in `house-style.md` says "soft cap with exemption" but any reviewer-agent or dispatcher restatement still says "200-word section cap," the reviewer drifts toward the always-loaded text. All declarative sites must move in the same change.
- House-style applies to authored prose surfaces under `_workflow/`. The new exemption is text-and-prompt only; no mechanical-check script changes.
- The mechanical-check script (`design-mechanical-checks.py`) enforces a different cap (lines per `##` section on design.md only) that this change does not touch.
- No HTML comment markers or visible "length-cap-exempt" annotations in authored prose. The exemption is structural (template-bound categories) plus reviewer judgment (padding-based finding criterion).

### Architecture Notes

#### Component Map

```mermaid
flowchart LR
    HS["house-style.md<br/>(rule source)"]
    RW["review-workflow-writing-style.md<br/>(reviewer agent)"]
    CL["CLAUDE.md<br/>(always-loaded)"]
    CR[".claude/skills/code-review/SKILL.md<br/>(dispatcher)"]
    EFR["episode-format-reference.md<br/>(episode template)"]
    CE22["conventions-execution.md §2.2<br/>(template pointer)"]

    HS -->|read at review start| RW
    HS -->|always-loaded restatement| RW
    HS -->|dispatcher restatement| CL
    HS -->|dispatcher restatement| CR
    EFR -.->|new back-reference| HS
    CE22 -.->|new back-reference| HS
    CE22 -->|points at| EFR
```

- **`.claude/output-styles/house-style.md`** — Single declarative source for the section length cap. The rule body (line 262 in `## Structural rules`) and the self-check (line 363 in `## Self-check`, step 7) both restate it. The exemption clause and the soft-cap rewording land here.
- **`.claude/agents/review-workflow-writing-style.md`** — The Phase C track-level writing-style review agent. Restates the cap in four places: frontmatter `description:` (line 3, always loaded into every system reminder), key rules list (line 18), review criteria (lines 69-71, under the `### Section length` heading), and the output-format template (line 121, the `Recommended` finding shape the agent fills in). All four rewrite to match the new house-style wording.
- **`CLAUDE.md`** (project root) — Line 102 declares the house-style rule set as mandatory and enumerates it inline, including a `200-word section cap` token. Always loaded into every session reminder, so it carries the same drift risk as the agent frontmatter. One-token rewrite.
- **`.claude/skills/code-review/SKILL.md`** — Line 313 describes the `review-workflow-writing-style` agent in the `/code-review` dispatcher list, including a `200-word section cap` token. Loaded whenever `/code-review` runs (i.e., every Phase C track-level review). One-token rewrite.
- **`.claude/workflow/episode-format-reference.md` § Episode length rule** — Already states "no hard line limit" for episodes, which is the contradiction YTDB-899 names. Gains a one-line back-reference to the new house-style exemption.
- **`.claude/workflow/conventions-execution.md` §2.2 Episode Formats** — Forwards to `episode-format-reference.md` for the template. Gains a back-reference to the house-style exemption next to the field list.

#### D1: Soft cap with categorical exemption (flavor 1)

- **Alternatives considered**: (A) Strict carveout: enumerate the seven labels named in YTDB-899 (and three more from the full template) as named exceptions in `house-style.md`. (B) Reasoning-required marker: every >200-word section opens with a prose or HTML-comment marker naming the reason. (C) Padding-based reframe: recast the rule as "no padding," using 200 words only as a heuristic trigger for closer review.
- **Rationale**: Flavor 1 (categorical exemption + soft cap default with padding-based judgment for non-exempt prose) covers more legitimate cases than (A). Without naming labels the rule captures template-bound content broadly (edit lists, state-machine tables, file:line citations, design-mechanics derivations) that would otherwise re-litigate as separate DROPs. It avoids the in-prose pollution of (B): markers either visually clutter authored content (visible prose) or rely on HTML-comment parsing reliability (invisible comments), and shift the discipline from "write tight prose" to "remember marker syntax." It keeps the deterministic structural check that (C) loses: the reviewer answers "is this section in an exempt category?" before falling back to judgment.
- **Risks/Caveats**: The exemption list is non-exhaustive — future template additions either match an existing category name or land an explicit addition. The padding-based judgment for non-template prose is an LLM call and may drift; mitigated by keeping the trigger threshold (200 words) explicit and naming the padding patterns the reviewer must look for (banned vocabulary, banned sentence patterns, restatement).
- **Implemented in**: Track 1
- **Full design**: design.md §"Exemption categories" (covers the five exempt content shapes and the structural-check criterion that picks them out)

#### D2: Land the rule in `house-style.md`, with back-references from template docs

- **Alternatives considered**: (A) Land the rule in `episode-format-reference.md` only and have `house-style.md` reference it. (B) Land in `conventions-execution.md §2.2` and back-reference from house-style.
- **Rationale**: The reviewer agent reads `house-style.md` once at review start; the rule must be readable from there or the reviewer never fires the exemption. Other docs (episode-format-reference, conventions-execution) are read on demand by execution-time code, not by the reviewer. Landing in house-style means the rule is present where it's applied, with back-references serving readers approaching from the template side.
- **Risks/Caveats**: House-style.md is the single declarative source for writing rules; the file already runs ~370 lines. The exemption clause is ~6 lines, well within tolerance.
- **Implemented in**: Track 1

#### D3: Update all declarative sites in one atomic change

- **Alternatives considered**: Stagger the rewrite — rule body first, restatements in a follow-up commit.
- **Rationale**: The reviewer agent's frontmatter `description:` is loaded into every system reminder ("...and 200-word-section cap per the house-style output style"). The same drift applies to `CLAUDE.md` line 102 (loaded every session) and `.claude/skills/code-review/SKILL.md` line 313 (loaded on every `/code-review` dispatch). If the rule body says "soft cap with exemption" but any always-loaded or dispatcher restatement still says "200-word section cap," reviewer behavior drifts toward the restatements because those are what the agent always sees. The same logic applies to the agent's key rules list (line 18), review criteria (lines 69-71), and output-format template (line 121) — any one of them not updated re-introduces the bug. Atomic change forces consistency.
- **Risks/Caveats**: Larger commit touching four files (house-style.md, review-workflow-writing-style.md, CLAUDE.md, code-review/SKILL.md). Mitigated by the change being mechanical (rule rewording) and reviewable as a single diff.
- **Implemented in**: Track 1

### Invariants

- After the change, `grep "Section length cap exception" .claude/output-styles/house-style.md` returns the new clause.
- All declarative sites (two in `house-style.md`, four in `review-workflow-writing-style.md`, one in `CLAUDE.md`, one in `.claude/skills/code-review/SKILL.md`) carry consistent wording about the soft cap and the exemption.
- A Phase C track-level writing-style review on a track with a `## Episodes` block whose structured-field paragraphs exceed 200 words does not produce a section-length finding against those paragraphs.

### Integration Points

- The writing-style reviewer agent reads `.claude/output-styles/house-style.md` at review start (see `review-workflow-writing-style.md § Process` step 1). The exemption clause lands inside `## Structural rules`, so the agent picks it up via the same read.
- `episode-format-reference.md § Episode length rule` (line 346) and `conventions-execution.md §2.2 Episode Formats` (line 284) are the back-reference landing sites for readers approaching the rule from the template side.

### Non-Goals

- The cap for non-template free-form prose stays at ~200 words. The default is not raised.
- No changes to the episode template field set (`What was done`, `What was discovered`, etc.) — only the length rule.
- No changes to `.claude/scripts/design-mechanical-checks.py`'s per-`##`-section length cap on design.md (a separate, line-based rule that operates during mutation, not review).
- No HTML comment markers, visible prose markers, or other in-document annotations to flag exemptions.
- No changes to the `ai-tells` skill, which audits a different cross-section of house-style rules (vocabulary, sentence patterns, openers/closers, not section length).

## Checklist
- [x] Track 1: Rewrite the section length cap and propagate
  > Rewrite `house-style.md § Structural rules` "Section length cap" as a soft cap with a categorical exemption (template-bound content) plus a padding-based finding criterion for free-form prose. Propagate the wording to all other declarative restatements (`house-style.md § Self-check` step 7; four sites in `review-workflow-writing-style.md`; `CLAUDE.md` line 102; `.claude/skills/code-review/SKILL.md` line 313). Add back-references from `episode-format-reference.md § Episode length rule` and `conventions-execution.md §2.2`.
  >
  > **Track episode:** Rewrote the canonical Section length cap rule in `.claude/output-styles/house-style.md § Structural rules` as a soft cap with a 5-category template-bound exemption and a padding-based finding criterion for non-exempt prose. The new framing propagated to 6 declarative restatements across 4 files in Step 2 (the reviewer-agent's frontmatter description, key-rules bullet, review-criteria block, and output-format template; the always-loaded paragraph in `CLAUDE.md`; the `/code-review` dispatcher entry in `code-review/SKILL.md`) and gained 2 back-references in Step 3 (`episode-format-reference.md § Episode length rule`, `conventions-execution.md §2.2`).
  >
  > Phase C track-level review across five workflow dimensions surfaced seven cross-step findings, all fixed in a single `Review fix:` commit (`b9ca6afbd4`). Two are worth flagging: (a) the Phase A deferral rationale for T6 was factually incorrect — all four Tier-A pointer sites in `commit-conventions.md`, `episode-format-reference.md`, `step-implementation.md`, and `implementer-rules.md` carried the verbatim "≤200-word section cap" phrase (not just labeling it by name), and the gate-check picked it up; (b) the unit-of-evaluation for the new exemption clause needed a tie-breaker for mixed `## Episodes` parents containing both exempt structured-field blocks and non-exempt free-form prose, added as one sentence at `house-style.md`. The other five fixes were mechanical wording cleanups (`CLAUDE.md:102` back to a brief always-loaded paragraph pointing at the canonical clause; the two new back-references reframed from hard-cap to soft-cap; the two duplicate restatements in `code-review/SKILL.md:313` and `review-workflow-writing-style.md:18` collapsed to sibling-shape pointers; the agent's `## Process` step 3 rewritten to point at the new three-step section-length decision).
  >
  > No cross-track impact (single-track plan). No design-decision escalations, no implementer failures, no plan corrections. After this track lands, the next Phase C track-level review on a track with substantive multi-paragraph episodes should not produce a section-length finding against structured-field paragraph blocks — the rule, the always-loaded restatements, the dispatcher entries, and the Tier-A pointer sites all carry consistent soft-cap framing.
  >
  > **Track file:** `plan/track-1.md` (3 steps, 0 failed)

## Plan Review
- [x] Plan review (consistency + structural) — passed at iteration 2

**Auto-fixed (mechanical)**: CR1 — line range for the `### Section length` review-criteria block in `review-workflow-writing-style.md` cited as "70-72" in Component Map and track Context and Orientation; corrected to "69-71" (heading at line 69, bullets at 70-71). CR2 — same residual "70-72" citation in D3 Rationale; corrected to "69-71". S1 — Track 1 plan-checklist intro paragraph ran 4 sentences (trailing pointer sentence pushed it past the 1-3 sentence budget); dropped the trailing "Detailed description in `plan/track-1.md`." pointer.

**Escalated (design decisions)**: none.

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
