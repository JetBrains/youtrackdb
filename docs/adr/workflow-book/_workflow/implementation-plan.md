<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Workflow Book Builder

## High-level plan

**Change tier:** minimal — matched categories: none

## Checklist
- [x] Track 1: Workflow-book builder machinery
  > This track builds the generator that produces a book about the YouTrackDB
  > development workflow, not the book itself. The workflow being documented is
  > the set of prose procedures under `.claude/workflow/` (phases 0–4, change
  > tiers, tracks/steps, review agents, drift and migration). The generator is a
  > top-level `workflow-book-builder/` directory of copy-paste prose prompts and
  > briefs that an operator pastes and drives by hand, modeled on the existing
  > `docs-ytdb-internals-book/` production pipeline. The book it will later emit
  > lands in a separate `docs/workflow-book/` tree, whose layout this track
  > stamps out empty. The one departure from the model: diagrams are ASCII by
  > default with a small set of committed D2-rendered SVGs, replacing the
  > mermaid the model uses, because mermaid renders unreliably across viewers.
  >
  > **Track episode:**
  > Built the `workflow-book-builder/` generator (10 files: the brief, the
  > unified evolution-aware pipeline with an embedded copy-paste START prompt,
  > the hybrid ASCII/D2-SVG diagram convention, four role prompts, a never-run
  > render script, and two empty run-output dirs) and stamped the empty
  > `docs/workflow-book/` book-target layout (5 files: a README pinning baseline
  > workflow-SHA `3e9c22298d`, a living TOC placeholder, and three `.gitkeep`
  > dirs). Both steps were `low` risk and landed first-spawn with no step-level
  > review. Phase C track-level review passed at iteration 1 with 0 blockers:
  > the diff is prose plus one never-run operator script, so the four code/test
  > baselines and context-budget were skipped (nothing is always-loaded) and
  > five workflow-dimension reviewers ran; 12 findings (5 should-fix, 7
  > suggestion) were applied in one fix iteration (`873d36e581`) and all 12
  > VERIFIED at gate-check. The should-fixes hardened `PIPELINE.md` — a
  > technical-review re-verification loop with a two-iteration cap and operator
  > escalation, an author-flag reconcile step — and aligned the SVG figure
  > naming on the `fig-<name>.svg` stem across four files. A track-completion
  > review-mode pass then removed the unpublished internals-book reference from
  > the three book deliverables (`08789eecc3`). No findings deferred, no plan
  > corrections, no cross-track impact (single-track plan).
  >
  > **Track file:** `plan/track-1.md` (2 steps, 0 failed)

## Plan Review
- [x] Plan review (consistency; structural dropped under `minimal` tier) — passed at iteration 1

**Auto-fixed (mechanical)**: none. The consistency review (track-vs-code, the only pass `minimal` runs) returned 0 findings; all 14 verification certificates were MATCHES (corpus counts, baseline SHA, `d2`/`mmdc`/`node` tooling presence, and the sibling internals-book's structure all verified against the real filesystem). Record: `plan/track-1/reviews/consistency-iter1.md`.

**Escalated (design decisions)**: none.

## Final Artifacts
- [ ] Phase 4: Final artifacts (PR-description verdict summary; no `docs/adr/` entry — Gate 2 is the durable-ADR boundary)
