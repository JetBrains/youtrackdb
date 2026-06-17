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
> 1. Read `workflow-book-builder/BOOK_BRIEF.md` (audience, voice, conventions, the four roles), `workflow-book-builder/DIAGRAMS.md` (the inline-Mermaid diagram convention), and the four role prompts under `workflow-book-builder/prompts/`.
> 2. Read `docs/workflow-book/README.md` and note the recorded baseline workflow-SHA (call it `BOOK_SHA`). Note the current `HEAD` SHA of this repository (call it `NEW_SHA`).
> 3. Create the run scratch directory in an OS temp dir: `RUN_TMP="$(mktemp -d "${TMPDIR:-/tmp}/workflow-book-run-XXXXXX")"`. Print the path so the operator knows where this run's intermediate reports land. Every intermediate the run produces — technical-review reports, beta-feedback reports, the author-flag reconciliation, any handoff note — is written under `$RUN_TMP`, never inside the repository. Only the durable book artifacts under `docs/workflow-book/` are written in-tree.
> 4. Decide which branch you are on:
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
> When the wave finishes, reconcile the flags the authors raised (homeless concepts, wrong earlier chapters) before moving to Step 4 — see "Reconcile author flags before Step 4" under the whole-run rules below.
>
> ### Step 4 — technical-reviewer wave
>
> Spawn technical-reviewer agents per `workflow-book-builder/prompts/technical-reviewer.md`, one per roughly three touched chapters, in parallel. Each reviewer verifies every source citation, file reference, and factual claim in its chapters against the tree at `NEW_SHA`, and writes a report under `$RUN_TMP/reviews/` (the run scratch directory from Step 0, an OS temp dir outside the repository). Apply blockers and important fixes in a revision pass (see "How the producer applies a fix" under the whole-run rules below). Only touched chapters (authored or swept) are reviewed; clean chapters are not.
>
> A chapter that carried a blocker does not advance until the blocker is cleared. After the revision pass, re-run the technical reviewer over only the chapters that carried a blocker; the chapter is clean only when its re-review returns no blocker. Cap the apply-then-re-review loop at two iterations; if a chapter still carries a blocker after the second re-review, stop the run and escalate the unresolved blocker to the operator rather than carrying it forward. A chapter does not flow into Step 5 or Step 7 with an open blocker.
>
> ### Step 5 — copy-editor wave
>
> Spawn copy-editor agents per `workflow-book-builder/prompts/copy-editor.md`, one per roughly five chapters, in parallel, over every `rewrite` and `new-or-restructure` chapter (the chapters Step 3 gave a full author). `sweep` chapters get no copy edit — a surgical citation sweep changed no prose to align. Each editor passes for voice consistency, pacing, and the house style, changing no factual claim.
>
> ### Step 6 — beta-reader wave (gated)
>
> Spawn beta-reader agents per `workflow-book-builder/prompts/beta-reader.md`.
>
> - **Empty baseline:** run all three personas, each over the whole book in order. Each persona's set is the entire book — three full cover-to-cover passes, not a partition of the chapters across the three readers.
> - **Non-empty baseline:** run beta readers only if Step 2 put five or more chapters in the `rewrite` or `new-or-restructure` bands (the chapters that got a full author — `sweep` chapters do not count toward the five); run one persona (the target reader) over those chapters in context. If four or fewer such chapters were touched, skip this step — the cost does not pay off.
>
> Each beta reader writes a report under `$RUN_TMP/beta-feedback/` (the run scratch directory from Step 0). Apply the top issues they raise in a revision pass (see "How the producer applies a fix" under the whole-run rules below).
>
> ### Step 7 — bump the baseline
>
> This step runs only when every touched chapter's technical-reviewer verdict is clean (Step 4's gate). If any chapter still carries an open blocker, the run does not reach this step; it escalated to the operator at Step 4 instead.
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
> 6. **How the producer applies a fix.** Both reviewer roles write findings and forbid self-editing; the producer (this session) owns the apply. Apply a fix that changes prose a reader sees, such as a reworded explanation, a restructured paragraph, or a new sentence, by re-spawning an author over the affected chapter per `prompts/author.md`, so the edit clears the voice and pacing rules. Apply a pure citation or anchor correction (a path that moved, a section anchor that drifted, no claim and no prose changed) inline. Either way the chapter re-clears the voice rules before the run ends; a voice-bearing edit never lands outside the author discipline.
> 7. **Reconcile author flags before Step 4.** An author raises two flags to the producer mid-wave: a concept it needed but no chapter owns yet, and an earlier chapter its work revealed to be wrong or incomplete (see `prompts/author.md`). After the author wave (Step 3) and before technical review (Step 4), collect these flags. For each homeless-concept flag, assign the concept to an existing chapter, open a new-or-restructure TOC edit for it (re-entering Step 2 for that chapter), or record it as out of scope for this run in the drift file. For each wrong-earlier-chapter flag, reclassify that chapter into the rewrite band and author it in this run, or record the deferral in the drift file. A flag is never dropped silently.
> 8. **Intermediate reports live outside the repository.** Every working file a run produces other than the book itself — technical-review reports, beta-feedback reports, the author-flag reconciliation, any handoff or pause note — is written under the run scratch directory `$RUN_TMP` from Step 0, an OS temp directory. Run scratch is never written under `docs/workflow-book/` or `workflow-book-builder/`, so the book content and the machinery stay uncluttered by run artifacts. The durable drift file (`docs/workflow-book/maintenance/drift-<SHA>.md`) is a book artifact, not run scratch, and stays in-tree.
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
| Step 6 — beta read | All three personas, each over the whole book | Gated: one persona over the rewrite/new-or-restructure chapters, only if five or more of them were touched |

**Table P.1 — where the from-scratch and incremental cases differ.** The other steps (technical review, copy edit, baseline bump) run the same in both cases, over whichever chapters the branch above selected.

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
- `workflow-book-builder/DIAGRAMS.md` — the inline-Mermaid diagram convention.
- The four prompts under `workflow-book-builder/prompts/`.

### What this pipeline deliberately does not do

- It does not assume beta readers run every cycle. The beta wave is gated behind five or more `rewrite`/`new-or-restructure` chapters on an evolution run.
- It does not re-run the copy edit on a surgical citation sweep. Copy edit runs over the `rewrite` and `new-or-restructure` chapters only — the chapters Step 3 gave a full author.
- It does not pick the mode up front. The operator pastes one block; the run decides empty-versus-non-empty from the state of `docs/workflow-book/` on its own.
