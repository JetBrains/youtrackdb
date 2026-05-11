# Inside the YouTrackDB Query Engine

A teaching book for Java developers who want to understand how YouTrackDB's query engine compiles and runs MATCH queries — from SQL text to result rows, through parser, pattern graph, cost-based planner, execution steps, traversers, and two optimisation layers.

Produced by reorganising the reference-style `match-book/` material into a narrative with gradual ramp-up, then running it through a multi-agent production pipeline.

## Source-tree baseline

Every `file:line` citation in the book refers to the YouTrackDB source at this commit:

| Field | Value |
|---|---|
| Commit SHA | `cca739f215debc26bb82422ed9aaff3566d2e590` |
| Short SHA | `cca739f215` |
| Date | 2026-04-22 |
| Subject | `YTDB-650: Back-reference hash join for MATCH patterns (#946)` |
| Branch | `develop` |

When the book is refreshed against a newer tree, bump this table and re-run the refresh procedure described in [`MAINTENANCE_PROMPT.md`](MAINTENANCE_PROMPT.md).


## Contents

- [`TOC.md`](TOC.md) — table of contents, symptoms index, and per-chapter briefs.
- [`BOOK_BRIEF.md`](BOOK_BRIEF.md) — voice rules, conventions, and production principles.
- [`chapters/`](chapters/) — the 17 chapters (01–17).
- [`reviews/`](reviews/) — technical-review reports from five reviewers.
- [`beta-feedback/`](beta-feedback/) — three beta-reader reports plus a revision-plan synthesis.

## Start here

1. Readers — open [`TOC.md`](TOC.md) and pick your entry: cover-to-cover from Chapter 1, the symptoms index at the top of the TOC, or the reference appendix in Chapter 17.
2. Maintainers — start with [`BOOK_BRIEF.md`](BOOK_BRIEF.md) before adding or rewriting any chapter. The voice rules are non-negotiable, and every later cycle assumes them.

## Production record

Two full cycles of the pipeline have been completed. Final body: 17 chapters, ~6 900 lines.

### Cycle 1 — initial draft

1. **TOC + briefs** — 17 chapters across 7 parts, each scoped to one teaching moment, with concrete-before-abstract pacing.
2. **Author wave** — 17 author agents (batched in 5 waves) drafted chapters in parallel from the source match-book plus live-tree code inspection.
3. **Technical review** — 5 reviewers split the 17 chapters, verified every code citation, line number, and factual claim against the live tree. Reports in [`reviews/`](reviews/). Blockers found: 3 (all fixed in a fix pass).
4. **Copy edit** — 3 editors pass for voice consistency, pacing, bullet-dump conversion, and transition polish.
5. **Beta read** — 3 readers with distinct personas (target reader / skeptical veteran / time-constrained practitioner) produced independent reports in [`beta-feedback/`](beta-feedback/).
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

## Where to go from here

The book is internally consistent, cross-referenced, and validated by two reader personas end-to-end. A next cycle could add:

- a *performance tuning* coda (tying Chapters 13–14 and 16 into an operational playbook),
- worked EXPLAIN outputs captured from real queries to replace the illustrative outputs in Chapter 16,
- a short chapter on the statement-cache invalidation pathway from the storage side, if that depth is wanted.

None of these are needed to read or use the book as it stands.
