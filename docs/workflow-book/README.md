# Running the YouTrackDB development workflow

A teaching book for new engineers who want to run the YouTrackDB development workflow end to end: pick the right change tier, write a plan that passes review, decompose a track into steps, drive the implement-test-commit loop, and read a review report.

This tree is the book target. It is empty right now: the chapters, the table of contents, and the diagrams are produced by the machinery at [`../../workflow-book-builder/`](../../workflow-book-builder/), not committed by hand. The layout below is stamped so a production run has a home to write into; running that machinery is what fills it. Read [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) to produce or refresh the book.

## Source-tree baseline

Every claim and citation in the book is true of the `.claude/workflow` tree at this commit. A production run records the commit it built against here; an evolution run walks the commits since this baseline to find what drifted.

| Field | Value |
|---|---|
| Commit SHA | `3e9c22298dfe68d2980646704850c781f8af88d5` |
| Short SHA | `3e9c22298d` |
| Date | 2026-06-15 |
| Subject | `[YTDB-1124] Keep the research log opaque to the user during Phase 0 (#1144)` |
| Branch | `develop` |

The book is not yet produced, so this is the pinned starting baseline, not a built-against commit. The first production run reads this value as its empty-baseline marker (the chapters do not exist yet), builds the table of contents and every chapter from scratch, then bumps this table to the commit it built against and records the run in the evolution history below. When the book is later refreshed against a newer tree, the refresh bumps this table and appends another evolution-history row. Both procedures are in [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md).

### Evolution history

One row per production run: the date, the SHA range the run covered, and which chapters it touched. The first row records the initial production run that builds the book from the empty baseline above; an evolution run appends one row each.

| Date | SHA range | Chapters touched |
|---|---|---|
| _(none yet — the book has not been produced)_ | — | — |

## How this book is produced

The book follows the prose-prompt production model proven by the YouTrackDB internals book at `../docs-ytdb-internals-book/docs/ytdb-internals-book/`: a brief fixes the voice and audience, a single hand-driven pipeline drives the role waves, and a table of contents carries the chapter map plus a cross-reference matrix. The one departure is diagrams — ASCII by default with a small committed-SVG set, instead of the inline mermaid the model ships — recorded in [`../../workflow-book-builder/DIAGRAMS.md`](../../workflow-book-builder/DIAGRAMS.md).

An operator produces or refreshes the book by pasting one block, the START prompt at the top of [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md), into a fresh agent session. That session computes the drift window against the baseline above, classifies impact, edits the table of contents for any new or restructured chapters, runs the author, technical-reviewer, copy-editor, and beta-reader waves over the touched chapters, renders any committed-SVG figures, and bumps the baseline. Initial production is the case where the baseline is empty, so the window is the whole corpus and every chapter is built.

The four production roles, the voice rules, and the conventions are fixed in [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md).

## Contents

This is the layout a production run fills. Everything except this README and the placeholder table of contents is produced by a run.

- [`TOC.md`](TOC.md) — the chapter map and the cross-reference matrix (chapter to source files). A living artifact a run edits; currently a placeholder.
- [`chapters/`](chapters/) — the chapter files, named `<NN>-<slug>.md`. Empty until a run writes them.
- [`assets/diagrams/`](assets/diagrams/) — the committed `fig-N.svg` figures and their `.d2` sidecars. Empty until a run renders a figure from the enumerated SVG set.
- [`maintenance/`](maintenance/) — the per-run drift notes, named `drift-<short-SHA>.md`, that an evolution run writes when it computes a drift window. Empty until an evolution run writes one.

## Start here

1. Readers — there is nothing to read yet. Once a production run has filled the layout, open [`TOC.md`](TOC.md) and pick an entry; the book is designed to be read cover to cover from Chapter 1.
2. Operators producing the book — start with [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md) for the voice rules, then paste the START prompt from [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) into a fresh session.
