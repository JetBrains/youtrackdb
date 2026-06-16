<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Workflow Book Builder

## High-level plan

**Change tier:** minimal — matched categories: none

## Checklist
- [ ] Track 1: Workflow-book builder machinery
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
  > **Scope:** ~13 files covering the `workflow-book-builder/` machinery (brief,
  > unified pipeline, diagram convention, four role prompts, render script, two
  > empty run-output dirs) and the empty `docs/workflow-book/` book-target layout
  > (README, TOC, empty chapters and diagram-asset dirs).

## Plan Review
- [x] Plan review (consistency; structural dropped under `minimal` tier) — passed at iteration 1

**Auto-fixed (mechanical)**: none. The consistency review (track-vs-code, the only pass `minimal` runs) returned 0 findings; all 14 verification certificates were MATCHES (corpus counts, baseline SHA, `d2`/`mmdc`/`node` tooling presence, and the sibling internals-book's structure all verified against the real filesystem). Record: `plan/track-1/reviews/consistency-iter1.md`.

**Escalated (design decisions)**: none.

## Final Artifacts
- [ ] Phase 4: Final artifacts (PR-description verdict summary; no `docs/adr/` entry — Gate 2 is the durable-ADR boundary)
