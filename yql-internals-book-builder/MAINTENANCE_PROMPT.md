# Maintenance Prompt — Refreshing the Book Against a Newer Source Tree

Use this document when the YouTrackDB source tree has moved past the commit recorded in `docs/yql-internals-book/README.md` and the book needs to be realigned.

You can paste the "Prompt" section below into a fresh Claude Code session. The rest of this file is context — the prompt is self-contained and will re-read what it needs.

---

## Prompt (copy-paste this block into a new Claude Code session)

> You are the maintainer of *Inside the YouTrackDB Query Engine*, a Java-developer-facing book at `docs/yql-internals-book/` in this repository. The book's citations were verified against a specific YouTrackDB commit, recorded in `docs/yql-internals-book/README.md` under **Source-tree baseline**. The source tree has since moved, and the book needs to be refreshed.
>
> Your job in this session is to run a **drift-aware refresh cycle**. Do not rewrite chapters from scratch. Change only what the code change forces.
>
> ### Phase 0 — establish the drift window
>
> 1. Read `docs/yql-internals-book/README.md` and note the baseline SHA (call it `BOOK_SHA`). Note also the current `HEAD` SHA (call it `NEW_SHA`).
> 2. Compute the drift window: `git log BOOK_SHA..NEW_SHA --name-only -- core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/ core/src/main/grammar/YouTrackDBSql.jjt core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/`
> 3. Save the commit summary, along with the changed-file list, to `docs/yql-internals-book/maintenance/drift-<NEW_SHA_SHORT>.md`. Group the changed files by subsystem (parser, planner, executor, optimisations, cost model, configuration, index engine).
>
> ### Phase 1 — identify impacted chapters
>
> The chapter-to-source map is documented in `docs/yql-internals-book/TOC.md` under the "Cross-reference matrix" plus the per-chapter briefs. For each changed file, identify which chapters cite it. Produce an impact table in `docs/yql-internals-book/maintenance/drift-<NEW_SHA_SHORT>.md`:
>
> | Changed file | Chapters citing it | Risk | Reason |
> |---|---|---|---|
>
> Risk is one of: **structural** (renamed class, removed method, new phase), **numeric** (line numbers moved), **semantic** (same API, different behaviour). Structural and semantic changes require author attention; numeric-only changes can be handled by a citation sweep.
>
> Also scan `docs/yql-internals-book/chapters/17-reference.md` Tables 17.1 and 17.2 for any file path or configuration knob that has been renamed, removed, or whose default changed.
>
> ### Phase 2 — triage
>
> Classify each chapter as **clean** (nothing to do), **sweep** (line numbers only), or **review** (requires author re-reading).
>
> - **Clean chapters**: record them in the drift file with a `-- clean` note. Skip.
> - **Sweep chapters**: run a single revisor agent that reads the chapter, re-verifies each `file:line` citation, and updates the number where it has shifted. The sweep never changes factual claims — only line numbers.
> - **Review chapters**: spawn one author agent per chapter. Give it (a) the original chapter, (b) the drift report for the files it cites, (c) the book brief (`yql-internals-book-builder/BOOK_BRIEF.md`). Ask it to re-read the relevant code in the new tree and produce a revised chapter.
>
> ### Phase 3 — technical review of touched chapters
>
> Only the chapters that were swept or rewritten need to be reviewed. Batch them across 2–3 reviewer agents in parallel. Each reviewer verifies `file:line` citations against the new tree and flags blockers / fixes / nits in `yql-internals-book-builder/reviews/technical-refresh-<NEW_SHA_SHORT>-<range>.md`.
>
> Apply blockers and important fixes in a short revision pass.
>
> ### Phase 4 — beta re-read (optional, at your discretion)
>
> If Phase 2 affected five or more chapters, run one beta reader (the "target reader" persona from `yql-internals-book-builder/beta-feedback/beta-reader-1-target-reader.md`). Ask them to read only the touched chapters, in context, and flag anything that now reads as disconnected or confusing. Apply the top issues they raise.
>
> Skip Phase 4 if the refresh touched four or fewer chapters — the cost/benefit doesn't pay off at that size.
>
> ### Phase 5 — update the baseline
>
> Update `docs/yql-internals-book/README.md`'s **Source-tree baseline** table: replace the SHA, short SHA, date, and subject with the new commit. Append a one-line entry to a **Refresh history** sub-section (create it if absent) recording when this refresh was done and which chapters were touched.
>
> ### Rules that apply to the whole refresh
>
> 1. **Every new citation is verified against the current tree.** Use `sed -n 'Np' <file>` or the Read tool. Never trust the drift report's line ranges as final.
> 2. **Voice rules still apply.** `yql-internals-book-builder/BOOK_BRIEF.md` is non-negotiable even during a refresh. A revision that fixes a line number but introduces a bullet-dump or a class-name-first opening must be rewritten.
> 3. **Terminology is canonical.** If Chapter 2 uses "collection" and Chapter 14 uses "collection ID", a refresh must not silently re-introduce "cluster" because the new source uses that word — raise it as a drift note, then reconcile in favour of the book's terminology (or update Chapter 2 too if the source truly changed semantic meaning).
> 4. **Deprecated or removed features.** If a chapter documents a feature that no longer exists, do NOT delete the chapter silently. Flag it in the drift report; discuss with the user whether to remove, rewrite as "historical note", or replace with the successor mechanism.
> 5. **New features not in the book.** If the drift window added a genuine new feature (new planner phase, new traverser variant, new optimisation layer) that the book should cover, this is outside a refresh — log it in the drift report as "new content required" and stop. The user decides whether to start a new cycle like cycle 1 / cycle 2, or defer.
> 6. **Chapter 18 is a snapshot — re-audit it every cycle.** Chapter 18 ("Open Problems: A Contributor's Map", the sole chapter of Part VIII) is a point-in-time snapshot of a forward-looking optimisation backlog. Individual opportunities it describes may get implemented over time, which would make the chapter stale. Every refresh cycle must re-audit Chapter 18 against current issue and implementation status. If an opportunity it describes has since been implemented — partially or fully — treat it like a removed or changed feature (follow Rule 4's process): flag it in the drift report and discuss with the user whether to remove the section, rewrite it as "shipped in <version>", or update it to point at the next open gap.
>
> ### Deliverables
>
> At the end of the refresh session you must have:
>
> - `docs/yql-internals-book/maintenance/drift-<NEW_SHA_SHORT>.md` — the drift report.
> - Updated chapters under `docs/yql-internals-book/chapters/` (only those that changed).
> - New review reports under `yql-internals-book-builder/reviews/technical-refresh-<NEW_SHA_SHORT>-*.md`.
> - An updated **Source-tree baseline** and a new row in the **Refresh history** in `docs/yql-internals-book/README.md`.
>
> Work in isolation if possible (`mode: plan` first to preview the scope), then execute. Parallelise author and reviewer agents across non-overlapping chapter sets. Use the TaskCreate tool to track phases.

---

## Context for the human operator

### When to run this

- The tree has moved by enough commits that random spot-checks show drifted citations.
- A specific YouTrackDB feature area (MATCH planner, hash join, pre-filter) has had substantial refactoring.
- Before a public release of the book, to make sure `HEAD` matches the baseline.
- Every cycle, re-check Chapter 18's optimisation backlog against current implementation status (see Rule 6) — opportunities it lists may have shipped since the last refresh.

### Rough scoping guide

| Situation | Expected scope |
|---|---|
| `git log BOOK_SHA..HEAD --oneline -- core/.../sql/` returns < 20 commits | Sweep-only refresh, 1–2 hours of agent time |
| 20–60 commits, mostly non-planner | Sweep + a small number of review chapters, half-day |
| 60+ commits, or any commit that renames a step or planner method | Full review cycle, closer to a fresh cycle |
| Any commit that adds a new planner phase or a new traverser variant | **Not a refresh** — start a new cycle to add content |

### Files the refresh prompt reads first

- `docs/yql-internals-book/README.md` — for the baseline SHA.
- `docs/yql-internals-book/TOC.md` — for the chapter-to-source map.
- `yql-internals-book-builder/BOOK_BRIEF.md` — for voice rules that must survive a refresh.
- `docs/yql-internals-book/chapters/17-reference.md` — Tables 17.1 and 17.2 are the master index of source files and configuration knobs; they almost always need attention.

### What this prompt deliberately does NOT do

- It does not assume beta readers are available every time. Beta re-reads are gated behind Phase 2 affecting ≥ 5 chapters.
- It does not re-run the copy-edit pass by default. Copy edit runs only if an author substantially rewrote a chapter; surgical line-number sweeps do not need copy edit.
- It does not touch the source `match-book/`. That stays frozen as the original reference.
- It does not add new content. A refresh aligns to the current tree; adding a new feature is a separate cycle.
