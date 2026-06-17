# Running the YouTrackDB development workflow

A teaching book for new engineers who want to run the YouTrackDB development workflow end to end: pick the right change tier, write a plan that passes review, decompose a track into steps, drive the implement-test-commit loop, and read a review report.

This tree is the book target. The chapters and the table of contents are produced by the machinery at [`../../workflow-book-builder/`](../../workflow-book-builder/), not written by hand. Open [`TOC.md`](TOC.md) for the chapter map, or read [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) to refresh the book against a newer tree.

## Source-tree baseline

Every claim and citation in the book is true of the `.claude/workflow` tree at this commit. A production run records the commit it built against here; an evolution run walks the commits since this baseline to find what drifted.

| Field | Value |
|---|---|
| Commit SHA | `f31e961c6a32a10710b293c181d153fa83142a65` |
| Short SHA | `f31e961c6a` |
| Date | 2026-06-16 |
| Subject | `Workflow book builder machinery (#1149)` |
| Branch | `user-workflow-book` |

Every chapter in this edition is true of the `.claude/workflow` tree at the commit above. The initial production run built the table of contents and all 16 chapters from the empty baseline, then bumped this table to the commit it built against. A later refresh walks the commits since this baseline, refreshes only the touched chapters, then bumps this table and appends another evolution-history row. Both procedures are in [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md).

### Evolution history

One row per production run: the date, the SHA range the run covered, and which chapters it touched. The first row records the initial production run that builds the book from the empty baseline above; an evolution run appends one row each.

| Date | SHA range | Chapters touched |
|---|---|---|
| 2026-06-16 | empty baseline → `f31e961c6a` (initial production) | All 16 (Chapters 1–16, built from scratch) |

## How this book is produced

The book follows a prose-prompt production model: a brief fixes the voice and audience, a single hand-driven pipeline drives the role waves, and a table of contents carries the chapter map plus a cross-reference matrix. Every diagram is an inline Mermaid fenced block placed where the prose leans on it, with the convention recorded in [`../../workflow-book-builder/DIAGRAMS.md`](../../workflow-book-builder/DIAGRAMS.md); Mermaid renders natively on GitHub, matches the convention the workflow's own design documents already use, and needs no build step or committed binaries.

An operator produces or refreshes the book by pasting one block, the START prompt at the top of [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md), into a fresh agent session. That session computes the drift window against the baseline above, classifies impact, edits the table of contents for any new or restructured chapters, runs the author, technical-reviewer, copy-editor, and beta-reader waves over the touched chapters, and bumps the baseline. Initial production is the case where the baseline is empty, so the window is the whole corpus and every chapter is built.

The four production roles, the voice rules, and the conventions are fixed in [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md).

## Contents

This is the layout a production run fills.

- [`TOC.md`](TOC.md) — the chapter map and the cross-reference matrix (chapter to source files). A living artifact a run edits.
- [`chapters/`](chapters/) — the chapter files, named `<NN>-<slug>.md`. Chapters 1 through 16. Diagrams are inline Mermaid fenced blocks within these chapters; there are no committed figure files.
- [`maintenance/`](maintenance/) — the per-run drift notes, named `drift-<short-SHA>.md`, that an evolution run writes when it computes a drift window. Empty until an evolution run writes one (an empty baseline writes no drift file).

## Start here

1. Readers — open [`TOC.md`](TOC.md) and start at [Chapter 1](chapters/01-workflow-at-a-glance.md); the book is designed to be read cover to cover. A reader who just needs to ship one small change can follow the short route [Chapter 1](chapters/01-workflow-at-a-glance.md) names: the minimal run in [Chapter 2](chapters/02-minimal-change-end-to-end.md) and the tier gate in [Chapter 3](chapters/03-tiers-and-the-tier-gate.md).
2. Operators producing the book — start with [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md) for the voice rules, then paste the START prompt from [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) into a fresh session.
