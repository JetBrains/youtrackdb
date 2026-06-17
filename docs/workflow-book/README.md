# Running the YouTrackDB development workflow

A teaching book for new engineers who want to run the YouTrackDB development workflow end to end: pick the right change tier, write a plan that passes review, decompose a track into steps, drive the implement-test-commit loop, and read a review report.

This tree is the book target. The chapters, the table of contents, and the diagram sources are produced by the machinery at [`../../workflow-book-builder/`](../../workflow-book-builder/), not written by hand. Open [`TOC.md`](TOC.md) for the chapter map, or read [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) to refresh the book against a newer tree.

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

The three committed-SVG figures (`fig-tier-gate`, `fig-phase-state-machine`, `fig-track-step-episode`) have their `.d2` sources under `assets/diagrams/` but are not yet rendered: the initial production run had no network access to install the one-time `d2` binary. Run `../../workflow-book-builder/scripts/render-diagrams.sh` once `d2` is installed to produce the `fig-*.svg` files the chapters embed. The render changes no chapter prose.

### Evolution history

One row per production run: the date, the SHA range the run covered, and which chapters it touched. The first row records the initial production run that builds the book from the empty baseline above; an evolution run appends one row each.

| Date | SHA range | Chapters touched |
|---|---|---|
| 2026-06-16 | empty baseline → `f31e961c6a` (initial production) | All 16 (Chapters 1–16, built from scratch) |

## How this book is produced

The book follows a prose-prompt production model: a brief fixes the voice and audience, a single hand-driven pipeline drives the role waves, and a table of contents carries the chapter map plus a cross-reference matrix. Diagrams are ASCII by default with a small committed-SVG set, with the convention recorded in [`../../workflow-book-builder/DIAGRAMS.md`](../../workflow-book-builder/DIAGRAMS.md); mermaid is not used because it renders inconsistently across viewers while ASCII and committed SVG render the same everywhere.

An operator produces or refreshes the book by pasting one block, the START prompt at the top of [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md), into a fresh agent session. That session computes the drift window against the baseline above, classifies impact, edits the table of contents for any new or restructured chapters, runs the author, technical-reviewer, copy-editor, and beta-reader waves over the touched chapters, renders any committed-SVG figures, and bumps the baseline. Initial production is the case where the baseline is empty, so the window is the whole corpus and every chapter is built.

The four production roles, the voice rules, and the conventions are fixed in [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md).

## Contents

This is the layout a production run fills.

- [`TOC.md`](TOC.md) — the chapter map and the cross-reference matrix (chapter to source files). A living artifact a run edits.
- [`chapters/`](chapters/) — the chapter files, named `<NN>-<slug>.md`. Chapters 1 through 16.
- [`assets/diagrams/`](assets/diagrams/) — the committed `fig-<name>.svg` figures and their `.d2` sidecars. The three `.d2` sources are present; the `.svg` files are pending the one-time `d2` render (see the baseline note above).
- [`maintenance/`](maintenance/) — the per-run drift notes, named `drift-<short-SHA>.md`, that an evolution run writes when it computes a drift window. Empty until an evolution run writes one (an empty baseline writes no drift file).

## Start here

1. Readers — open [`TOC.md`](TOC.md) and start at Chapter 1; the book is designed to be read cover to cover. A reader who just needs to ship one small change can follow the short route Chapter 1 names: the minimal run in Chapter 2 and the tier gate in Chapter 3.
2. Operators producing the book — start with [`../../workflow-book-builder/BOOK_BRIEF.md`](../../workflow-book-builder/BOOK_BRIEF.md) for the voice rules, then paste the START prompt from [`../../workflow-book-builder/PIPELINE.md`](../../workflow-book-builder/PIPELINE.md) into a fresh session.
