# Inside the YouTrackDB Query Engine — Table of Contents

## Overview

Eighteen chapters across eight parts. The book takes a Java developer from "I have never opened the YouTrackDB source" to "I can extend the MATCH planner, diagnose a slow query from an EXPLAIN, and recognise the three optimisation layers the engine applies". Concepts build one at a time; each chapter depends only on the ones before it; optimisations appear only after the basic nested-loop pipeline is complete in the reader's head.

The source match-book contains all the facts the new book needs. The new book contains *none* of the source's section structure — it rearranges the material into a teaching narrative with on-ramps where the source makes leaps, and it introduces MATCH only after the reader has walked through a plain SELECT end-to-end.

---

## Symptoms index

If you arrived at this book to solve a specific problem, start here.

| Symptom | Chapters |
|---|---|
| Slow query, unsure where to start | 16 (EXPLAIN), 3 (pipeline) |
| Wrong root alias chosen | 9 (root selection), 16 (diagnosis), 8 (cost concepts) |
| Missing reversal on edge | 10 (scheduling + invertibility), 16 |
| Missing pre-filter / high fan-out loads | 14 (pre-filters), 16 |
| Hash-join explosion | 13 (hash joins), 16 |
| Disjoint-component Cartesian explosion | 10 (components), 15 (walkthrough) |
| WHILE / recursive query slow | 12 (traverser strategies), 13 (inverted-while hash join) |
| Need a class/method not in the book | 17 (reference) |
| Want to contribute an optimisation / where to start | 18 |

---

## Part I — Orientation

The reader arrives knowing SQL and Java. Part I gives them the mental map of YouTrackDB and its query pipeline — enough to make sense of everything that follows, without the graph vocabulary weighing them down yet.

**Chapter 1 — Why a Graph Database Has Its Own Query Engine**
Motivates the book. Opens with one business question ("find friends of friends who live in Berlin") and shows how the natural SQL for it becomes painful. Introduces the idea of a graph pattern match as the natural unit of work. Does *not* define any vocabulary yet — instead, it sets up the question the book answers: "why does this query engine look so different from a relational one?"
Draws lightly from: 02-what-is-match.md.

**Chapter 2 — A Tour of YouTrackDB Storage**
Teaches the data model the reader needs: records, RIDs, classes, clusters, vertices as records, edges as records *or* as embedded adjacency lists, and the key O(1) properties (lookup by RID, one-hop traversal). Ends with a picture of two vertices and the Knows edge between them, shown three ways: as records, as a graph, and as bytes on disk.
Draws from: 01-primer.md §§1.1–1.3.

**Chapter 3 — The Life of a Query: A Bird's-Eye Tour**
Follows one plain `SELECT FROM Person WHERE name = 'Alice'` through the four stages — parse, plan, execute, return rows — without ever mentioning MATCH. The reader sees the AST as a diagram, the execution plan as a short list of steps, and the pull-based `next()` loop in action. This is the mental map the rest of the book fills in.
Draws from: 01-primer.md §1.2; 03-architecture.md (reframed — the source is MATCH-first; this chapter is SELECT-first).

---

## Part II — From SQL Text to a Tree

With the pipeline in hand, Part II zooms into the first phase: the parser, and the AST it produces. Only at the end of Part II does MATCH formally appear — and when it does, the reader already understands what an AST is and what the planner consumes.

**Chapter 4 — The Parser and the AST**
Explains JavaCC at the level the reader needs (grammar rules become classes, visitors walk the tree). Traces `YouTrackDBSql.jjt` → `SQLMatchStatement` and shows the visitor pass. Introduces the non-obvious deep-copy on planner entry and why it exists. Reader should finish this chapter able to open the AST classes and navigate them.
Draws from: 04-parsing-and-ast.md.

**Chapter 5 — Meet MATCH: A Graph Pattern in Source Code**
Having seen an AST, the reader is ready for the MATCH AST specifically. Introduces pattern nodes, pattern edges, and aliases. Opens with a concrete query and shows its `SQLMatchStatement` tree. Introduces the three decisions that make MATCH harder than SELECT (root, direction, back-references) as a preview of Parts III–IV. Alias-keyed rows get an intuitive explanation — full mechanics come in Chapter 12.
Draws from: 02-what-is-match.md.

---

## Part III — Building a Pattern Graph

The AST is a linear list. The planner needs a graph. Part III covers the transformation and the eight-phase planner's high-level shape — so the cost-based chapters in Part IV can zoom in without getting lost.

**Chapter 6 — From Linear AST to Pattern Graph**
Documents `MatchExecutionPlanner`'s first planning pass: walking each match expression, materialising `PatternNode` and `PatternEdge` objects, and unifying repeated aliases into shared nodes. Uses a small worked example where the same alias appears in two expressions, and shows the pattern graph that results. This is where the reader first *sees* back-references as a structural property.
Draws from: 05-pattern-graph.md.

**Chapter 7 — The Eight Phases of the Planner**
A tour of `MatchExecutionPlanner.createExecutionPlan()` — each of the eight phases gets one paragraph. The reader leaves knowing the name of every phase, the artifact each produces, and the chapter in Part IV (or V) that opens that phase up. No deep dives here; this is the map.
Draws from: 06-planner-overview.md.

---

## Part IV — Cost-Based Planning

The heart of the book. Each chapter opens one of the hard planner phases. Chapter 8 introduces cost concepts in isolation; Chapters 9 and 10 apply them. By the end the reader can simulate the planner in their head for a small query.

**Chapter 8 — Counting Without Counting: Cardinality, Selectivity, and Fan-out**
Introduces the three numerical estimates the planner uses. Explains what `SelectivityEstimator` computes, what `EdgeFanOutEstimator` computes, and why both are allowed to be wrong. Covers the fallback constants and the "ranking signal, not truth" framing. The reader meets `CostModel` here as the shared language.
Draws from: 12-cost-model.md.

**Chapter 9 — Choosing Where to Start: Root Selection**
Opens phase 3 of the planner. Explains `estimateRootEntries()`, the MAX_VALUE trick that protects against picking an unresolvable root, and how ties are broken. Works two examples end-to-end: one where class selectivity dominates, one where an indexed WHERE predicate flips the choice.
Draws from: 07-root-selection.md.

**Chapter 10 — Scheduling the Walk: Order and Direction**
Opens phase 5. Covers the cost-guided DFS over edges, the invertibility check (when `.out` can be executed as `.in`), how the scheduler respects `$matched` dependencies, how it handles disjoint components, and how WHILE patterns constrain the choice. This is the chapter that closes the loop on "why does the planner pick *that* order?"
Draws from: 08-scheduling-edges.md; invertibility paragraphs from 02-what-is-match.md §2.3.4 and 10-runtime-traversal.md.

---

## Part V — From Plan to Execution

With a fully scheduled plan, the reader now sees runtime. Part V is the concrete machinery: the step pipeline and the per-edge traverser strategies.

**Chapter 11 — The Step Pipeline: How the Plan Becomes Code**
Tours the execution-step catalogue: `MatchPrefetchStep`, `MatchFirstStep`, `MatchStep`, `OptionalMatchStep`, `RemoveEmptyOptionalsStep`, `FilterNotMatchPatternStep`, and the four return-projection step variants. Focuses on the pull-based model and how each step grows the `MatchResultRow`. The alias-keyed row — previewed in Chapter 5 — gets its full mechanics here.
Draws from: 09-execution-steps.md; row-chain mechanics from 02-what-is-match.md §2.3.1.

**Chapter 12 — Traversers: Six Ways to Walk an Edge**
`MatchStep` delegates the actual edge walking to a traverser strategy. This chapter opens all six: standard forward, reverse, optional, field traversal, multi-step, and back-reference enforcement. Explains the context variables `$matched`, `$currentMatch`, `$current`, who writes them and who reads them.
Draws from: 10-runtime-traversal.md.

---

## Part VI — Optimisations

The basic engine is a nested-loop machine. Part VI covers the two optimisation layers that replace — or accelerate — parts of it. The reader needs everything from Parts I–V in place before this makes sense.

**Chapter 13 — When Nested Loops Aren't Enough: Hash Joins**
The three hash-join variants (`HashJoinMatchStep`, `CorrelatedOptionalHashJoinStep`, `InvertedWhileHashJoinStep`), the conditions under which the planner selects each, and the configuration knobs. Each variant is introduced by the problem it solves — `NOT` patterns, `OPTIONAL` with a back-reference, inverted `WHILE` — not by the class name.
Draws from: 13-hash-joins.md.

**Chapter 14 — Index-Assisted Traversal: Pre-Filtering Adjacency Lists**
The `RidFilterDescriptor` optimisation: attaching a pre-filter to an `EdgeTraversal` so the traverser can skip non-matching adjacency-list entries before loading the target record. Explains when the planner applies it, what indexes it can use, and what the runtime effect is.
Draws from: 14-index-assisted-traversal.md.

---

## Part VII — Putting It Together

Three chapters of synthesis.

**Chapter 15 — Nine Queries, From Trivial to Hairy**
A walkthrough of nine queries in ascending complexity. Each query adds exactly one feature; the reader sees which planner phase and which execution step lights up for each addition. This chapter is the book's "spaced repetition" pass — the reader re-meets every concept they have learned, in the order the engine deploys them.
Draws from: 11-walkthrough.md.

**Chapter 16 — Reading EXPLAIN: Diagnosing Plans in Practice**
How to read `EXPLAIN` output, how to tell which plan the planner chose and why, and a gallery of common pathologies: the wrong root, a missing reversal, a missing pre-filter, a disjoint-component cartesian explosion. Ends with a debugging checklist the reader can apply to their own slow MATCH queries. This is the only chapter with new synthesis material beyond the source; authors should still draw factual claims from the source chapters.
Draws from: 11-walkthrough.md; 12-cost-model.md; 13-hash-joins.md; 14-index-assisted-traversal.md.

**Chapter 17 — Reference: Files, Classes, Configuration, Glossary**
The reference appendix: file-layout index, configuration-knob table, end-to-end pipeline diagram, complete glossary. Cross-referenced back to the chapter where each term is taught.
Draws from: 15-reference.md.

---

## Part VIII — Where the Engine Goes Next

The engine you now understand works and ships — but it is generation one. This closing part turns from how the engine behaves to where it is headed, mapping the second-generation gap in each optimisation layer so a new contributor knows exactly which file to open first.

**Chapter 18 — Open Problems: A Contributor's Map**
Turns the finished engine into a set of on-ramps. Walks the next-generation gap in each optimisation layer — an enumerative (IDP) join-order search to replace the greedy DFS (§18.1), skew-aware cardinality estimation (§18.2), a single nanosecond cost currency to unify two incompatible cost models (§18.3), statistics-drift invalidation for the plan cache (§18.4), spill-to-disk for oversized hash joins (§18.5), and estimate-versus-actual observability in EXPLAIN (§18.6) — then lists a handful of afternoon-sized starter tasks (§18.7) and points at the issue tracker and the LDBC benchmark harness that gate the larger work (§18.8). The reader leaves knowing that every layer they studied is generation one, and that each gap comes with the exact file and line to open first.
Draws from: the engine's optimisation backlog and live-tree code inspection (no match-book source).

---

## Cross-reference matrix

| New chapter | Primary sources | Secondary sources |
|---|---|---|
| 1 | 02-what-is-match.md (motivating example only) | — |
| 2 | 01-primer.md §§1.1–1.3 | — |
| 3 | 01-primer.md §1.2; 03-architecture.md | — |
| 4 | 04-parsing-and-ast.md | — |
| 5 | 02-what-is-match.md | 05-pattern-graph.md (preview) |
| 6 | 05-pattern-graph.md | — |
| 7 | 06-planner-overview.md | — |
| 8 | 12-cost-model.md | 07-root-selection.md (for context) |
| 9 | 07-root-selection.md | 12-cost-model.md |
| 10 | 08-scheduling-edges.md | 02-what-is-match.md §2.3.4; 10-runtime-traversal.md |
| 11 | 09-execution-steps.md | 02-what-is-match.md §2.3.1 |
| 12 | 10-runtime-traversal.md | — |
| 13 | 13-hash-joins.md | 12-cost-model.md |
| 14 | 14-index-assisted-traversal.md | — |
| 15 | 11-walkthrough.md | all |
| 16 | 11-walkthrough.md; 12-cost-model.md; 13-hash-joins.md; 14-index-assisted-traversal.md | all |
| 17 | 15-reference.md | all |
| 18 | New — the engine's optimisation backlog (YTDB issue tracker) | 7, 8, 10, 13, 14, 16 |
