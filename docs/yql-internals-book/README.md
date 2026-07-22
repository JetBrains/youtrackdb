# Inside the YouTrackDB Query Engine

A teaching book for Java developers who want to understand how YouTrackDB's query engine compiles and runs MATCH queries — from SQL text to result rows, through parser, pattern graph, cost-based planner, execution steps, traversers, and two optimisation layers.

Produced by reorganising the reference-style `match-book/` material into a narrative with gradual ramp-up, then running it through a multi-agent production pipeline.

This tree is the book target. The production machinery — the book brief, the maintenance/refresh pipeline, and the technical-review and beta-reader artifacts — lives in a separate tree at [`../../yql-internals-book-builder/`](../../yql-internals-book-builder/).

## Source-tree baseline

Every `file:line` citation in the book refers to the YouTrackDB source at this commit:

| Field | Value |
|---|---|
| Commit SHA | `a9b05e3f56128de5dbf314c40d5be38ff10b5050` |
| Short SHA | `a9b05e3f56` |
| Date | 2026-07-21 |
| Subject | `Bump anthropics/claude-code-base-action from 4bae506e99a9c8e88eee3e8e19199266ad7f1b32 to 9698ea332dcbc603bba3ebca8b676031a5e424ba (#1235)` |
| Branch | `develop` |

When the book is refreshed against a newer tree, bump this table and re-run the refresh procedure described in [`../../yql-internals-book-builder/MAINTENANCE_PROMPT.md`](../../yql-internals-book-builder/MAINTENANCE_PROMPT.md).

### Refresh history

- **Cycle 3 (2026-07-21)** — drift refresh against `a9b05e3f56`. Semantic rewrites: Ch 14 (pre-filter two-path admission model, post-YTDB-651), Ch 17 (reference tables: split pre-filter knobs + new tx-result-cache knobs), Ch 16 (EXPLAIN pathology). New content: Ch 7 §7.9 new subsection “A third cache — results, not plans” (transaction query-result cache). Full line-number re-sync sweep across Ch 3–13, 15–17. Ch 9 §9.1 corrected (root-entry RID-pin single→list). First drift report: `maintenance/drift-a9b05e3f56.md`.


## Contents

- [`TOC.md`](TOC.md) — table of contents, symptoms index, and per-chapter briefs.
- [`chapters/`](chapters/) — the 18 chapters (01–18).
- [`maintenance/`](maintenance/) — per-refresh drift reports written by the maintenance pipeline.

The production artifacts live under [`../../yql-internals-book-builder/`](../../yql-internals-book-builder/):

- [`../../yql-internals-book-builder/BOOK_BRIEF.md`](../../yql-internals-book-builder/BOOK_BRIEF.md) — voice rules, conventions, and production principles.
- [`../../yql-internals-book-builder/MAINTENANCE_PROMPT.md`](../../yql-internals-book-builder/MAINTENANCE_PROMPT.md) — the drift-aware refresh pipeline.
- [`../../yql-internals-book-builder/reviews/`](../../yql-internals-book-builder/reviews/) — technical-review reports from five reviewers.
- [`../../yql-internals-book-builder/beta-feedback/`](../../yql-internals-book-builder/beta-feedback/) — three beta-reader reports plus a revision-plan synthesis.

## Start here

1. Readers — open [`TOC.md`](TOC.md) and pick your entry: cover-to-cover from Chapter 1, the symptoms index at the top of the TOC, or the reference appendix in Chapter 17.
2. Maintainers — start with [`../../yql-internals-book-builder/BOOK_BRIEF.md`](../../yql-internals-book-builder/BOOK_BRIEF.md) for the voice rules before adding or rewriting any chapter, then run the drift-aware refresh procedure in [`../../yql-internals-book-builder/MAINTENANCE_PROMPT.md`](../../yql-internals-book-builder/MAINTENANCE_PROMPT.md) to realign the book against a newer source tree. The voice rules are non-negotiable, and every later cycle assumes them.

## Production record

Two full cycles of the pipeline have been completed. Final body: 18 chapters, ~7 500 lines.

### Cycle 1 — initial draft

1. **TOC + briefs** — 17 chapters across 7 parts, each scoped to one teaching moment, with concrete-before-abstract pacing.
2. **Author wave** — 17 author agents (batched in 5 waves) drafted chapters in parallel from the source match-book plus live-tree code inspection.
3. **Technical review** — 5 reviewers split the 17 chapters, verified every code citation, line number, and factual claim against the live tree. Reports in [`../../yql-internals-book-builder/reviews/`](../../yql-internals-book-builder/reviews/). Blockers found: 3 (all fixed in a fix pass).
4. **Copy edit** — 3 editors pass for voice consistency, pacing, bullet-dump conversion, and transition polish.
5. **Beta read** — 3 readers with distinct personas (target reader / skeptical veteran / time-constrained practitioner) produced independent reports in [`../../yql-internals-book-builder/beta-feedback/`](../../yql-internals-book-builder/beta-feedback/).
6. **Revision** — 3 revisors applied 8 high-value beta fixes (selectivity-tier table, Phase 5 sub-list, hash-join variant bridge, cluster→collection sweep, greedy-DFS→hash-join connection, PROFILE signposting, intersection glossary, TOC symptoms index); 3 substantive additions deferred to cycle 2.

### Cycle 2 — deferred additions + final polish

1. **Authored the three deferred additions in parallel:**
   - Chapter 8 — bucket-level interpolation arithmetic for `EquiDepthHistogram`, with a worked example (Rule 3 detail) and the `FractionMode`/`scalarize` prerequisites.
   - Chapter 12 §12.7.1 — `LazyRecursiveTraversalStream` explained: explicit-stack DFS, RID-set deduplication, the `pathAlias` opt-out, depth bounds, the no-cycle-guard warning, and the `$matchPath` result-layer exposure.
   - Chapter 7 §7.9 — plan/statement cache lifecycle: two-cache design (`YqlStatementCache` + `YqlExecutionPlanCache`), `STATEMENT_CACHE_SIZE`, copy-on-read contract, the four bypass conditions, and schema-event invalidation. Chapter 4's deep-copy closing now forward-references §7.9.
2. **Cycle-2 technical review** — 2 blockers (Ch 12 uncertainty reframed as a definitive usage constraint; Ch 17 BFS/DFS inconsistency) and 3 fixes applied.
3. **Cycle-2 copy edit** — voice consistency across the three new sections.
4. **Cycle-2 beta re-read** — the veteran confirmed all three previously-flagged gaps are closed; the target reader flagged 3 small tightening items (handled).
5. **Final polish** — five surgical fixes addressed all remaining feedback.

### Cycle 3 — drift refresh

1. **Drift-aware maintenance pipeline** run against develop `a9b05e3f56`, covering 13 commits / 62 files of sql-layer drift since the previous baseline.
2. **Semantic rewrites** — Chapters 14, 16, and 17 realigned to the YTDB-651 pre-filter selectivity change: Ch 14 recast around the two-path pre-filter admission model, Ch 17 split its reference tables into distinct pre-filter and tx-result-cache knob groups, Ch 16 updated the EXPLAIN pathology discussion.
3. **New content** — Ch 7 §7.9 gained a new subsection, “A third cache — results, not plans”, covering the transaction query-result cache.
4. **Semantic fix** — the sweep surfaced a Ch 9 §9.1 error (root-entry RID-pin is a list, not a single RID), now corrected.
5. **Full citation re-sync sweep** across Chapters 3–13 and 15–17 to realign every `file:line` reference to the new tree.
6. **Three fresh-thread reviewers** (citation-accuracy, voice, consistency) plus a verification gate signed off; the first drift report landed in [`maintenance/`](maintenance/).
7. **New chapter** — added Chapter 18, “Open Problems: A Contributor's Map” (new Part VIII): a contributor onboarding map built from the engine's optimisation backlog, pairing design directions in prose with pointers to the YTDB tracker and the `jmh-ldbc` LDBC harness. Verified by the same fresh-thread citation-accuracy / voice / consistency reviewers plus a gate.

## Where to go from here

The book is internally consistent, cross-referenced, and validated by two reader personas end-to-end. A next cycle could add:

- a *performance tuning* coda (tying Chapters 13–14 and 16 into an operational playbook),
- worked EXPLAIN outputs captured from real queries to replace the illustrative outputs in Chapter 16,
- a short chapter on the statement-cache invalidation pathway from the storage side, if that depth is wanted.

None of these are needed to read or use the book as it stands.
