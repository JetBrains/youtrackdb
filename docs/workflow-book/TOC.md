# Running the YouTrackDB development workflow — Table of Contents

This file is a placeholder. The book has no chapters yet, so this table of contents is empty. The first production run builds it from scratch, and later evolution runs edit it; see the START prompt in [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) Step 2.

The table of contents is a living artifact owned by the book, not a frozen list. A run adds, reorders, or splits chapters as the source workflow under `.claude/workflow/`, `.claude/skills/`, and `.claude/agents/` changes, and updates the cross-reference matrix below to match. The two sections a run fills are the chapter map and the cross-reference matrix, described next.

## Overview

A run writes this section: a short paragraph naming how many chapters across how many parts, and the arc the book takes a new engineer through — from "I have never run this project's workflow" to "I can pick a tier, write a plan that passes review, decompose a track, drive the implement-test-commit loop, and read a review report". Concepts build one at a time per the voice rules in [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md): concrete before abstract, one concept per section, a minimal change run end to end before any phase is opened in depth.

## Chapter map

A run writes this section. Each chapter is one teaching moment, grouped into parts, with a per-chapter brief naming the chapter's teaching goal, what earlier chapter it builds on, and the source files it draws from. The chapter map is empty until a run builds it.

The shape a run fills, one block per chapter:

- **Part `<roman>` — `<part title>`.** One paragraph framing the part.
- **Chapter `<arabic>` — `<chapter title>`.** The teaching goal in one or two sentences, what it builds on, and the one or two mental models the reader leaves with.
  Draws from: `<source files under .claude/workflow/, .claude/skills/, .claude/agents/>`.

The candidate scope the brief names, for the run to decompose into chapters, is the workflow a new engineer runs: phases 0 through 4, the change tiers (`full` / `lite` / `minimal`), tracks and steps and episodes, the dimensional review agents, and drift and migration. The run decides the chapter boundaries; this list is the subject, not the chapter list.

## Cross-reference matrix

A run writes this section. The matrix maps each chapter to the source files it draws from, so an evolution run can find which chapters a source change touches. It is empty until a run builds the chapter map.

The shape a run fills, one row per chapter:

| Chapter | Primary sources | Secondary sources |
|---|---|---|
| _(none yet — the book has not been produced)_ | — | — |

When a run edits the chapter map, it updates this matrix in the same pass, so the matrix never drifts from the chapters it indexes.
