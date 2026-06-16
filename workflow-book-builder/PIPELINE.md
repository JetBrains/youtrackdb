# Pipeline — producing and evolving the workflow book

This document drives one production run of the workflow book. A run either produces the book from an empty baseline (initial production) or refreshes it against a newer source tree (evolution). Both cases run the same pipeline; the only difference is the size of the drift window. The from-scratch case is the evolution case where the baseline is empty, so the drift window is everything and the table of contents is built rather than edited.

The operator starts a run by pasting one block, the START prompt in the next section, into a fresh agent session. That session orchestrates the four role waves itself, spawning author / technical-reviewer / copy-editor / beta-reader agents per [`BOOK_BRIEF.md`](BOOK_BRIEF.md) and the prompts under [`prompts/`](prompts/). The operator pastes exactly one thing; the rest of this file is context the START prompt re-reads on its own.

The book lives at `docs/workflow-book/`; the machinery lives here in `workflow-book-builder/`. The two trees are kept in sync by the baseline workflow-SHA the book pins (recorded in `docs/workflow-book/README.md`) and the cross-reference matrix in `docs/workflow-book/TOC.md`.

---

## START prompt (copy-paste this block into a fresh agent session)

> You are the producer of *Running the YouTrackDB development workflow*, a teaching book for new engineers at `docs/workflow-book/` in this repository. The machinery that drives production lives at `workflow-book-builder/`. Your job in this session is to run one production cycle: produce the book from scratch if it is empty, or refresh it against the current source tree if it already exists. The two cases are the same pipeline branched on whether a baseline exists.
>
> ### Step 0 — read the machinery and establish the baseline
>
> 1. Read `workflow-book-builder/BOOK_BRIEF.md` (audience, voice, conventions, the four roles), `workflow-book-builder/DIAGRAMS.md` (the ASCII-default + committed-SVG diagram convention and the enumerated figure set), and the four role prompts under `workflow-book-builder/prompts/`.
> 2. Read `docs/workflow-book/README.md` and note the recorded baseline workflow-SHA (call it `BOOK_SHA`). Note the current `HEAD` SHA of this repository (call it `NEW_SHA`).
> 3. Decide which branch you are on:
>    - **Empty baseline (initial production).** `docs/workflow-book/TOC.md` has no chapters yet and `docs/workflow-book/chapters/` is empty (or holds only a `.gitkeep`). Treat `BOOK_SHA` as empty: the drift window is the entire source corpus, the table of contents is built from scratch, and every chapter goes through the full role waves. Skip to Step 2.
>    - **Non-empty baseline (evolution).** `docs/workflow-book/TOC.md` lists chapters and `chapters/` holds them. `BOOK_SHA` is a real commit. Compute the drift window in Step 1, then refresh only the touched chapters.
>
> ### Step 1 — compute the drift window (evolution branch only; skip on an empty baseline)
>
> 1. Walk the commits that changed the source corpus since the baseline:
>
>    ```
>    git log BOOK_SHA..NEW_SHA --name-only -- .claude/workflow/ .claude/skills/ .claude/agents/
>    ```
>
> 2. Save the commit summary and the changed-file list to `docs/workflow-book/maintenance/drift-<NEW_SHA_SHORT>.md`. Group the changed files by workflow concept (phases, tiers, tracks/steps/episodes, review agents, drift/migration, conventions). On an empty baseline this file is not written; the drift window is implicitly the whole corpus.
>
> ### Step 2 — classify impact and edit the table of contents
>
> 1. **Empty baseline:** build `docs/workflow-book/TOC.md` from scratch. Produce the chapter map (chapters across parts, each scoped to one teaching moment, concrete-before-abstract per `BOOK_BRIEF.md`) and the cross-reference matrix (chapter → source files). Every chapter is in the new-or-restructure band, because none exists yet.
> 2. **Non-empty baseline:** for each changed source file, find the chapters that cite it using the cross-reference matrix in `docs/workflow-book/TOC.md`. Classify each chapter into one of four bands and record the classification in the drift file:
>    - **clean** — nothing the change forces; skip the chapter.
>    - **sweep** — a citation moved (a section was renamed, a file moved) but no claim changed; a citation sweep fixes it.
>    - **rewrite** — a claim the chapter makes is now wrong (a gate's threshold changed, a phase's outputs changed); an author rewrites the affected sections.
>    - **new-or-restructure** — the source gained a concept with no chapter home, or reorganised so the chapter boundaries no longer fit; the chapter map itself must change.
> 3. Edit `docs/workflow-book/TOC.md` only for the new-or-restructure band: add, reorder, or split chapters and update the cross-reference matrix so it still maps every chapter to its sources. The TOC is a living artifact owned by the book; an evolution run is allowed to restructure it.
>
> ### Step 3 — author wave
>
> Spawn author agents per `workflow-book-builder/prompts/author.md`, one per chapter, batched in parallel waves over non-overlapping chapter sets.
>
> - **Empty baseline:** every chapter gets an author.
> - **Non-empty baseline:** only rewrite and new-or-restructure chapters get an author. Sweep chapters get a citation sweep instead (one agent re-verifies each source citation against the tree at `NEW_SHA` and updates the anchor where it moved, changing no claim). Clean chapters get nothing.
>
> Give each author its chapter brief from `TOC.md`, the source files that brief names, `BOOK_BRIEF.md`, and `DIAGRAMS.md`. The author writes to `docs/workflow-book/chapters/`.
>
> ### Step 4 — technical-reviewer wave
>
> Spawn technical-reviewer agents per `workflow-book-builder/prompts/technical-reviewer.md`, one per roughly three touched chapters, in parallel. Each reviewer verifies every source citation, file reference, and factual claim in its chapters against the tree at `NEW_SHA`, and writes a report under `workflow-book-builder/reviews/`. Apply blockers and important fixes in a short revision pass. Only touched chapters (authored or swept) are reviewed; clean chapters are not.
>
> ### Step 5 — copy-editor wave
>
> Spawn copy-editor agents per `workflow-book-builder/prompts/copy-editor.md`, one per roughly five chapters, in parallel, over the chapters an author substantially rewrote. A surgical citation sweep does not need a copy edit. Each editor passes for voice consistency, pacing, and the house style, changing no factual claim.
>
> ### Step 6 — beta-reader wave (gated)
>
> Spawn beta-reader agents per `workflow-book-builder/prompts/beta-reader.md`.
>
> - **Empty baseline:** run all three personas over the whole book in order.
> - **Non-empty baseline:** run beta readers only if Step 2 touched five or more chapters; run one persona (the target reader) over the touched chapters in context. If four or fewer chapters were touched, skip this step — the cost does not pay off.
>
> Each beta reader writes a report under `workflow-book-builder/beta-feedback/`. Apply the top issues they raise in a revision pass.
>
> ### Step 7 — render diagrams
>
> If any touched chapter added or changed a figure in the enumerated SVG set (see `workflow-book-builder/DIAGRAMS.md`), run `workflow-book-builder/scripts/render-diagrams.sh`. The script renders each `.d2` sidecar under `docs/workflow-book/assets/diagrams/` to a committed `fig-N.svg`. If `d2` is not installed, the script prints the one-time install command; install it once per `BOOK_BRIEF.md` and re-run. ASCII figures need no render step.
>
> ### Step 8 — bump the baseline
>
> Update `docs/workflow-book/README.md`: set the **Source-tree baseline** table to `NEW_SHA` (full SHA, short SHA, date, subject, branch), and append one row to the **Evolution history** sub-section recording the date, the SHA range, and which chapters were touched. On an empty baseline this is the first baseline and the first evolution-history row.
>
> ### Rules that apply to the whole run
>
> 1. **Every citation is verified against the tree at `NEW_SHA`.** Never trust a drift report's anchors as final; open the source file and confirm.
> 2. **The voice rules in `BOOK_BRIEF.md` are non-negotiable**, including during a refresh. A revision that fixes a citation but introduces a bullet-dump or a name-first opening must be rewritten.
> 3. **Terminology is canonical.** The book picks one name per concept and uses it consistently. If the source renames a concept, raise it as a drift note and reconcile in favour of the book's terminology, or rename across every chapter that uses it.
> 4. **Removed features.** If a chapter documents a workflow procedure that no longer exists, do not delete the chapter silently. Flag it in the drift file and ask the operator whether to remove it, rewrite it as a historical note, or replace it with the successor procedure.
> 5. **New features.** If the drift window added a genuine new concept (a new phase, a new tier, a new review dimension) with no chapter home, that is new-or-restructure work: edit the TOC in Step 2 and author the new chapter. This is the explicit difference from a line-only sweep, which can never add a chapter.
>
> Work in `mode: plan` first to preview the scope, then execute. Parallelise author and reviewer agents across non-overlapping chapter sets. Track the steps as you go.

---

## Context for the human operator

### The two cases are one pipeline

The book's subject, `.claude/workflow/`, keeps changing. A run must be able to add and restructure chapters, because a new phase or a new tier gate needs a new or rewritten chapter that a line-number sweep can never produce. That is why the pipeline uses the full role set (author → technical-reviewer → copy-editor → beta-reader) for evolution, the same set initial production uses.

Folding initial production and ongoing evolution into one pipeline keeps one copy of the wave-orchestration prose and one fewer artifact to keep in sync. The framing is evolution from an empty baseline: a run always computes a drift window, classifies impact, edits the TOC for the new-or-restructure band, runs the waves on the touched chapters, and bumps the baseline. Initial production is the case where the baseline is empty, so the window is everything, the TOC is built from scratch, and every chapter is touched.

### The branch is explicit, because the control flow genuinely differs

"One pipeline" means one document and one role set, not identical control flow. The from-scratch case and the incremental case diverge at three points, and the START prompt branches explicitly at each:

| Step | Empty baseline (initial production) | Non-empty baseline (evolution) |
|---|---|---|
| Step 1 — drift window | Skipped; the window is the whole corpus | `git log BOOK_SHA..NEW_SHA` over the source paths, grouped into a drift file |
| Step 2 — TOC | Built from scratch; every chapter is new-or-restructure | Edited only for the new-or-restructure band; clean/sweep chapters leave the TOC unchanged |
| Step 3 — authors | One author per chapter | Authors only for rewrite and new-or-restructure chapters; sweep chapters get a citation sweep; clean chapters get nothing |
| Step 6 — beta read | All three personas over the whole book | Gated: one persona over the touched chapters, only if five or more chapters were touched |

**Table P.1 — where the from-scratch and incremental cases differ.** The other steps (technical review, copy edit, render, baseline bump) run the same in both cases, over whichever chapters the branch above selected.

### When to run an evolution cycle

- The `.claude/workflow` tree has moved by enough commits that spot-checks show a chapter's claims drifting from the source.
- A specific concept area (a phase, the tier gate, the review agents) has been substantially reworked.
- Before sharing the book, to confirm `HEAD` matches the recorded baseline.

### Rough scoping guide

| Situation | Expected scope |
|---|---|
| `git log BOOK_SHA..HEAD --oneline -- .claude/workflow/` returns under 20 commits | Sweep-only refresh; a citation sweep over a few chapters |
| 20 to 60 commits, mostly outside a single concept | Sweep plus a small number of rewrite chapters |
| 60+ commits, or any commit that renames a phase or restructures the tier gate | Closer to a fresh cycle; expect new-or-restructure work |
| Any commit that adds a new phase, tier, or review dimension | New-or-restructure: edit the TOC and author the new chapter |

### Files the START prompt reads first

- `docs/workflow-book/README.md` — the baseline workflow-SHA and the evolution history.
- `docs/workflow-book/TOC.md` — the chapter map and the cross-reference matrix.
- `workflow-book-builder/BOOK_BRIEF.md` — the voice rules and the four role definitions.
- `workflow-book-builder/DIAGRAMS.md` — the diagram convention and the enumerated SVG figure set.
- The four prompts under `workflow-book-builder/prompts/`.

### What this pipeline deliberately does not do

- It does not assume beta readers run every cycle. The beta wave is gated behind five or more touched chapters on an evolution run.
- It does not re-run the copy edit on a surgical citation sweep. Copy edit runs only where an author substantially rewrote a chapter.
- It does not render diagrams it does not need. The render step runs only when a touched chapter added or changed a figure in the enumerated SVG set; ASCII figures never render.
- It does not pick the mode up front. The operator pastes one block; the run decides empty-versus-non-empty from the state of `docs/workflow-book/` on its own.
