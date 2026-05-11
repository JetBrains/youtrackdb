# Beta Reader 2 — The Skeptical Veteran

## Who I am

I have shipped a chunk of a query planner before — the join-ordering phase of an in-house
OLAP engine, and before that I spent two years staring at PostgreSQL planner code trying to
understand why it made infuriating decisions on star-schema joins. I read technical books
with a specific allergy: the surface-level tour dressed up as depth. I am here to find out
whether this book is the real thing or an elaborate annotation of source code.

---

## Depth ratings

### Part I — Orientation (Chapters 1–3)
Solid intro: the motivation chapter (Ch. 1) is genuinely honest about what makes graph
patterns hard, and the storage tour (Ch. 2) explains O(1) traversal in terms a veteran
actually needs — the LinkBag inline vs. B-tree split, the cluster-ID encoding in RIDs, the
position-map indirection. Chapter 3 is a well-executed on-ramp. Nothing here will surprise
an expert, but it earns its place as ground-truth vocabulary.

### Part II — From SQL Text to a Tree (Chapters 4–5)
Solid intro: Chapter 4 is better than most treatments of parser internals — it shows actual
generated class structure, explains why the AST is a list of one-field items (the grammar
alternation reason), and covers the deep-copy motivation with specificity. Chapter 5 is
clean orientation. Neither chapter breaks new ground for someone who has read JavaCC
documentation, but both are accurate and don't waste the reader's time.

### Part III — Building a Pattern Graph (Chapters 6–7)
Deep dive: Chapter 6 is the book's first unambiguously excellent chapter. The alias
unification via map lookup is explained in a way that makes the elegant simplicity
unmissable. The flood-fill connected-component split is shown with real code. Chapter 7 is
a useful map with precise artifact names — it does its job as a navigation chapter without
overstaying its welcome. The eight-phase structure is a strong organizing principle that
pays off across the rest of the book.

### Part IV — Cost-Based Planning (Chapters 8–10)
Excellent deep dive: this is the heart of the book and it largely delivers. Chapter 8 shows
the three-tier estimator (empty / uniform / histogram), the inclusion-exclusion composition
for OR predicates, the BOTH-direction fan-out correction for directed schemas, and the
`+1` bias trick in a way that makes the design reasoning visible. Chapter 9 traces the
MAX_VALUE inflation through two separate data structures with code line citations. Chapter
10's two-level DFS loop with dependency clearing is one of the cleaner algorithmic
explanations I have read in a technical book. The greedy-only limitation is called out
honestly in §10.11.

### Part V — From Plan to Execution (Chapters 11–12)
Deep dive: Chapter 11 is strong — the `MatchResultRow` chain-vs-copy trade-off is the kind
of implementation detail that most books hand-wave, and here it gets its own diagram and
the sentinel lifetime is tracked precisely through the pipeline. Chapter 12 covers all six
traverser variants with code and the back-reference enforcement two-liner is explained
correctly. The `$matched` / `$currentMatch` / `$current` distinction in §12.9 is handled
with appropriate care.

### Part VI — Optimisations (Chapters 13–14)
Deep dive: Chapter 13 is thorough — all four guards, all three join modes, the `JoinKey`
design rationale, the truncated-build fallback. Chapter 14 explains the two-layer
pre-filter (class filter + RID-set filter), the four descriptor types, and the runtime
ratio guard. Both chapters cite specific line numbers and explain the *why* behind design
choices, not just the *what*.

### Part VII — Putting It Together (Chapters 15–17)
Solid intro: Chapter 15 is a well-structured spaced-repetition pass and the composite
query in §15.9 is genuinely illustrative. Chapter 16 is the strongest practical chapter in
the book — the pathology gallery with named symptoms, causes, and fixes is something a
practitioner can use. Chapter 17 is a serviceable reference appendix. None of these
chapters adds new depth; they consolidate what came before. That is appropriate for Part
VII, but it means a reader who only wants diagnostic knowledge could skip to Chapter 16
directly.

---

## Where the book earns its positioning

**Chapter 6 — alias unification via map lookup.** The insight that back-references are not
a special case requiring a dedicated detection pass, but simply fall out of an alias-keyed
map, is presented in a way that makes the design feel inevitable. The worked example with
two expressions collapsing to two nodes is one of the best "aha" moments in the book.

**Chapter 9 — the MAX_VALUE trick.** This section earns its depth rating. The book
explains *why* a low-cardinality inferred class becomes a planning failure (non-invertible
edge, scheduler impasse), then shows exactly where the inflation fires and crucially notes
that it touches only `estimatedRootEntries` while leaving `aliasClasses` intact. That
distinction between "root priority" and "class membership" is the kind of nuance that
separates a book that describes code from one that explains it.

**Chapter 10 — dependency clearing before candidate evaluation (§10.5.2).** The invariant
that a node's removal from all dependency sets happens *before* the inner loop evaluates
its candidate edges — meaning the common case (depended-upon alias is the current DFS
root) requires no deferral at all — is a subtle correctness insight that most treatments
of topological ordering with constraints would miss entirely.

**Chapter 13 — INNER_JOIN memory weight and the tighter threshold.** The `threshold / 7`
guard for inner-join mode is something most hash-join treatments elide. Explaining it in
terms of "full `ResultInternal` rows vs. lightweight keys" and giving the constant a name
(`INNER_JOIN_MEMORY_WEIGHT`) that appears in the actual source is exactly the kind of
production-grade honesty the book's positioning promises.

---

## Where it falls short

**The histogram estimator gets a pass it doesn't deserve (Chapter 8).** Section 8.2
describes the three-tier selectivity strategy and mentions "bucket-level interpolation" for
the histogram tier. But how does the interpolation actually work? Is it linear? Is it
area-based? What does "the key matches the most common value" mean in terms of histogram
bucket structure — is this a MCV list separate from the bucket array, or is it a special
sentinel bucket? The reader is told the estimator exists and has three shortcuts; they are
not told enough to evaluate whether the interpolation is correct for skewed distributions,
which is exactly when it matters. A veteran reader will notice that range-predicate
estimation over an equi-depth histogram is non-trivial — the right boundary of the
matching bucket may be far from the query bound — and the book says nothing about this.

**The greedy DFS limitation is named but not quantified (§10.11).** The book honestly says
the scheduler does not backtrack globally and can commit to a locally cheap edge that is
globally expensive. But it stops there. What is the practical worst case? Is there any
adaptive mechanism — mid-execution plan switching, per-row cost feedback — that could
compensate? The answer appears to be "no, there is no adaptive execution loop" (stated
in Chapter 8 under "no runtime re-planning"), but the relationship between that design
choice and query performance on adversarial inputs is never addressed. A practitioner who
hits a slow query caused by a bad greedy choice is left with "raise the hash join
threshold" as the only lever, and the book does not explain why those are related.

**`LazyRecursiveTraversalStream` is mentioned but not explained (Chapter 12).** The
chapter correctly routes recursive WHILE edges through `LazyRecursiveTraversalStream` but
declines to explain it, pointing only to "Further reading." This is a significant gap. The
BFS vs. DFS choice for recursive traversal, cycle detection (visited-RID deduplication),
and the interaction between `depthAlias`/`pathAlias` and the pull model are all
non-trivial. A reader implementing a similar feature, or debugging a WHILE query that
produces unexpected duplicate rows, needs this.

**Statistics staleness is acknowledged but not diagnosed (Chapter 16, §16.5.1).** The
wrong-root pathology section says "statistics are stale" as a cause and "trigger a schema
refresh" as a fix. Neither the mechanism of statistics updates (when they run, what they
update, how long stale statistics persist), nor the query to check current statistics, nor
the manual refresh command is given. For a book that positions itself as enabling query
diagnosis in practice, "check your statistics" without telling the reader how to check
them is an unsatisfying landing.

---

## Cross-chapter coherence issues

**"Class" vs. "collection" vs. "cluster" terminology drift.** Chapter 2 carefully
establishes that the book uses "collection" to match the Java API, not the legacy term
"cluster." But Chapter 14 uses "cluster ID" throughout (§14.1: "its leading integer — the
*cluster ID*"), §14.2 ("cluster-to-class mapping"), and §14.4 ("collectionIdsForClass" in
code vs. "cluster" in prose). The decision to standardize on "collection" in Chapter 2 is
then silently abandoned when discussing RID internals in Chapter 14. A reader who absorbed
Chapter 2's distinction will be puzzled by the Chapter 14 prose.

**The "inferred class" concept is taught twice at different depths.** Chapter 9 (§9.3.1)
introduces class inference in the context of the MAX_VALUE inflation with reasonable depth.
Chapter 14 (§14.5) re-introduces it for the pre-filter context, but the two sections use
different notation for the same mechanism and neither cross-references the other. A reader
who encountered the MAX_VALUE inflation in Chapter 9 will not immediately recognize
"class inference enables both filters" in Chapter 14 as the same mechanism with the same
preconditions.

**`$matched` semantics shift between Chapter 11 and Chapter 12.** Chapter 11 (§11.2)
says "`$matched` is set to that row so that downstream WHERE clauses can reference
already-bound aliases." Chapter 12 (§12.9) adds that `$matched` is written by
`MatchEdgeTraverser.next` after a result is consumed by the caller, which means it
reflects the most recently *accepted* row rather than the row currently being tested.
The distinction matters for nested MATCH patterns where both `$matched` and
`$currentMatch` are in scope simultaneously, but the book introduces the variable in
Chapter 11 at the coarser level and then revises it in Chapter 12 without flagging the
refinement.

---

## What's not in the book that should be

**The statement cache and plan reuse.** Chapter 4 explains the deep-copy on planner entry
and correctly names `YqlStatementCache` as the motivation. But the cache itself — its size,
its eviction policy, whether plans are cached post-planning (just the AST) or whether the
full execution plan is ever cached, and what happens when the same statement is executed
concurrently — is never described. For a book that positions itself as enabling readers to
diagnose slow queries, knowing whether the first execution of a new query template is more
expensive than subsequent ones is directly relevant.

**Spill-to-disk and memory pressure.** The hash-join chapter (Ch. 13) establishes that
`HashJoinMatchStep` falls back to per-row evaluation when the build side overflows the
threshold. But for the inverted-WHILE step (§13.7) the fallback is a full per-row WHILE
traversal — not a partial materialisation. What happens to a query where the reachability
closure is genuinely large (a deep class hierarchy with thousands of members) and the
fallback fires? The book says the fallback exists; it doesn't say what the performance
cliff looks like or whether there is a tuning path short of "add a more selective WHERE
clause." There is no discussion of whether hash structures can spill to disk for very large
build sides.

**GQL/openCypher interoperability.** Chapter 7 (§7.5) mentions that the hash-join planner
and GQL integration can supply a pre-built `Pattern` to bypass graph construction. GQL is
mentioned exactly once in the entire book, in a parenthetical. For a database that is
presumably evolving toward GQL compliance, the relationship between the MATCH planner and
any GQL execution layer is a meaningful omission. A reader who is evaluating whether to
build on YouTrackDB for a GQL workload has no information.

**Parallel execution and concurrency.** The pull-based execution model is described
throughout as single-threaded. There is no discussion of whether any phase of planning or
execution is parallelisable, whether `CommandContext` is thread-safe, or whether concurrent
query execution shares any mutable state. For a database that may serve concurrent queries
against the same schema statistics, the absence of any discussion of locking, isolation, or
potential data races in the estimator reads as an oversight.

---

## Suggestions for the next revision

1. **Expand §8.2 (histogram tier) with a worked interpolation example.** Show one
   equi-depth bucket array, one range predicate, and trace the arithmetic. Name the
   boundary cases (query bound in the middle of a bucket, query bound equal to bucket
   boundary). This is two pages that transform "the estimator uses interpolation" into
   something a reader can verify and critique.

2. **Add a section on `LazyRecursiveTraversalStream` to Chapter 12.** Cover the BFS
   traversal order, the visited-RID set, the depth counter mechanics, and the interaction
   with `pathAlias` (which disables deduplication). This is the one significant execution
   mechanism that is acknowledged but not explained.

3. **Pick "collection" or "cluster" and use it consistently throughout.** The Chapter 2
   decision to standardize on "collection" is correct and should extend to all RID-internal
   discussions in Chapters 14 and 17. The terminology note in §2.3 should reference the
   places where legacy "cluster" terminology appears in source code, not just in
   documentation.

4. **Add a statistics reference section to Chapter 16 (or Chapter 17).** The debugging
   chapter tells readers that stale statistics cause wrong root selection but does not tell
   them how to inspect current statistics, what the refresh mechanism is, or how to tell
   whether statistics have ever been computed for a given class. Even a short "How to check
   your statistics" box would make §16.5.1 actionable.

5. **Explicitly connect §10.11 (greedy DFS limitation) to the hash-join mitigation.**
   The book tells the reader in Chapter 10 that the greedy scheduler can commit to a bad
   edge, and tells them in Chapter 13 that hash joins can replace expensive nested-loop
   steps after the schedule is built. These two facts are never explicitly connected with
   "and this is why hash joins help even when the schedule is suboptimal." Adding two
   sentences to §10.11 that forward-reference Chapter 13 as the partial answer to the
   greedy limitation would close a gap that is currently invisible.

6. **Add a chapter or extended section on the statement cache, plan lifetime, and
   concurrent execution.** Even 800 words covering cache key semantics, AST-level vs.
   plan-level caching, and the thread-safety guarantees of `CommandContext` would answer
   the questions a reader naturally asks after understanding the full planning pipeline.
   The current book ends at "the plan is assembled" without addressing "how long does it
   live and who else can see it?"
