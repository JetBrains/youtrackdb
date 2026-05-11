# Beta Reader 2 — Cycle 2 Re-read

## Gap 1 — histogram bucket interpolation (Ch 8)

**Verdict: closed. Some minor qualifications remain.**

The new sub-section "Inside the histogram tier — bucket-level interpolation" is the
real thing. It opens with the bucket structure — equi-depth semantics, the
`boundaries` / `frequencies` / `distinctCounts` parallel arrays, precise line citations
— before moving to the interpolation formula. The three-step derivation (find bucket,
compute `fraction`, sum partial bucket plus tail buckets) is traced with source
references at each step. Table 8.2 works through a four-bucket example numerically.
The single-value-bucket short-circuit via `fractionOf` / `FractionMode` is mentioned
and explained. The `Math.max(..., 0)` guard for negative frequencies from incremental
drift is noted honestly as an edge case. The section ends with a clear statement of the
uniform-within-bucket assumption and a candid worked illustration of how much it can
err when the real distribution skews toward one boundary.

What cycle-1 asked for is here: the formula, the boundary case (query bound in the
middle of a bucket), and an honest acknowledgement of the approximation quality.

One remaining qualification: the section explains `estimateGreaterThan` in detail but
does not show the equality (`estimateEquals`) code path. Equality estimation is
meaningfully different — it hits the MCV list first and then falls through to a
per-bucket distinct-count probe — and a reader who wants to understand the histogram
tier fully will have to go to the source for that half. The omission is defensible
(range predicates are the hard case; equality is comparatively straightforward), but
the text does not flag the asymmetry. A one-paragraph note that "equality estimation
follows a different path through `fractionOf`" would close that last sliver.

**Placement**: mid-chapter, directly after the three-tier summary table. Ideal. The
reader has the vocabulary from the table before encountering the formula.

**Verdict**: substantive and honest.

---

## Gap 2 — LazyRecursiveTraversalStream (Ch 12)

**Verdict: fully closed.**

Section 12.7.1 is the most complete of the three additions. It covers every item
cycle-1 identified as missing: DFS traversal order (pre-order, explicit stack, not Java
call stack), the `RidSet` deduplication mechanism with precise `add`-return-value
semantics, the `pathAlias` opt-out with the code snippet showing `dedupVisited = null`,
and the depth-bounding via `maxDepth` and `whileCondition` through `shouldExpand`. The
termination risk when `pathAlias` is active and the graph is cyclic is called out
plainly, with a specific "always pair `pathAlias` with a finite `maxDepth`" rule. The
worked two-level stack trace makes the pre-order DFS concrete and the deduplication
footnote within it is well placed. The closing paragraph on why WHILE edges are not
invertible ties the section back to the scheduler (Chapter 10) and forward to Chapter
13, which is exactly the connective tissue a veteran reader wants.

One small observation: the section says the deduplication is per-*vertex* (each
reachable vertex emitted at most once) and correctly ties this to RID identity. It does
not address the cost of `RidSet` growth for very deep or very wide traversals — a
dense graph with a large reachability closure will accumulate a large visited set in
memory. This is not a content gap the cycle-1 report raised, but now that the
mechanism is explained it is the natural next question for a reader thinking about
production behaviour.

**Placement**: 12.7.1 sits under the §12.7 `MatchMultiEdgeTraverser` subsection, which
is the natural parent because the multi-edge traverser is where the recursive/non-
recursive branch diverges. The preceding paragraphs set up the delegation (`LazyRecursiveTraversalStream`
mentioned twice at lines 133 and 354), so the new section lands with prepared context.

**Verdict**: substantive, honest, and complete.

---

## Gap 3 — plan/statement cache (Ch 7 §7.9)

**Verdict: closed, with one placement concern.**

The section answers every question cycle-1 raised: both caches are named and their
roles distinguished (AST cache vs. execution-plan cache), the cache key is explained
and the no-normalisation implication is spelled out (literal-value queries each get
their own slot), the copy-on-read contract is explained precisely (template stored
closed, caller gets a fresh copy via `result.copy(ctx)`), and the four bypass
conditions are enumerated including the concurrent-schema-change timestamp check. The
whole-cache invalidation on any schema event is described, and the `COMMAND_TIMEOUT`
runtime-change guard is an honest detail that the section did not have to include but
does. The practical advice at the end (warm the cache after a migration) is useful.

The section also correctly distinguishes which cache is bypassed for SQL input
parameters: the execution-plan cache is skipped, but the statement cache still applies.
That is a nuanced and accurate statement; I verified it against the source.

One factual note worth tracking: the text references `SQLMatchStatement.executinPlanCanBeCached()`
with the typo spelling. That spelling matches the actual production source (`grep`
confirms it appears identically in `MatchExecutionPlanner.java`, `SelectExecutionPlanner.java`,
and the statement classes). The book is therefore accurate, but the typo in the source
is now documented in the book. If the source is ever corrected the book reference will
need updating.

**The Ch 4 vs. Ch 7 placement question.**

The author's argument — that plan lifecycle questions naturally arise in Ch 7 — is
correct in isolation. Chapter 7 is where the planner's eight phases and their artifacts
are introduced; asking "how long does the plan live after it is assembled?" is a natural
epilogue to "what does the planner produce?" That logic is sound.

The problem is that Ch 4 does not tell the reader that the full answer is coming. The
Ch 4 deep-copy section ends with: "This is the one place where knowing about the
statement cache matters for understanding the parser and planner boundary." That phrasing
actively signals *closure* — it implies the cache discussion is now complete. A reader
who absorbs that sentence has no reason to expect §7.9 to double back to the cache with
significantly more depth. There is no forward-reference: no "the execution-plan cache
and concurrent sharing are covered in §7.9" or even "more about both caches in Chapter
7."

The consequence is a reader who finishes Chapter 4 believing they understand the cache
story, then encounters §7.9 three chapters later without having been primed to look for
it. Ch 4 should be amended to add one sentence: "The second cache — `YqlExecutionPlanCache`
— stores the assembled execution plan rather than the parsed AST, and is described in
§7.9 alongside the thread-safety contract and invalidation rules." That one sentence
turns an abrupt surprise into a promised pay-off.

**Verdict for §7.9**: substantive and complete. The linkage back to Ch 4 is the one
remaining fix needed.

---

## Any new observations

**Asymmetry in the "non-cacheable plan" bypass explanation.** Section 7.9 lists four
bypass conditions. The third (`result.canBeCached()`) and fourth (schema-changed
timestamp) use the same line citation — `MatchExecutionPlanner.java:627–631` — for
both. That is correct (both checks occur in the same block), but a reader who looks up
those lines will see both conditions interleaved. The prose separates them as if they
were independent guards; the code treats them as a single compound condition. The
section could add one sentence noting that the two checks are `&&`-combined in the
same `if` block.

**The `isCacheable()` / `executinPlanCanBeCached()` naming asymmetry.** The section
references both method names without explaining their relationship. `isCacheable()` is
described as living on "AST nodes"; `executinPlanCanBeCached()` is described as a
method on `SQLMatchStatement` that "walks every expression and path item." These are
the same check seen from two different levels — the AST root delegates to the node-level
predicate — but a reader who goes to the source looking for `isCacheable()` will not
find it under that name in the match planner. A parenthetical clarifying the delegation
chain would save confusion.

**No mention of eviction policy beyond capacity.** The section says both caches are
sized by `STATEMENT_CACHE_SIZE` and that LRU eviction applies (`YqlStatementCache`'s
Guava cache uses `maximumSize`, which is LRU). `YqlExecutionPlanCache`'s eviction
policy is not stated. If it is also LRU the book should say so; if it differs the
discrepancy matters for practitioners reasoning about cache thrash under large query
template vocabularies.

---

## Overall verdict

Two of the three cycle-1 gaps are fully closed. The histogram interpolation section
delivers the formula, the boundary-case worked example, and an honest account of the
linear-within-bucket approximation's failure mode — a veteran reader can now evaluate
the estimator's quality. The `LazyRecursiveTraversalStream` section is the strongest of
the three additions: DFS mechanics, deduplication, `pathAlias` opt-out, and
non-invertibility all receive precise treatment with code citations. The plan cache
section earns its keep — both caches explained, copy-on-read contract precise, bypass
conditions enumerated — but it needs a single forward-reference sentence inserted into
the Ch 4 deep-copy paragraph before the placement works cleanly; without it, Ch 4
signals closure on a topic the author intends to continue three chapters later. With
that one sentence added, the book's treatment of the three previously flagged topics
earns the "deep technical" positioning it claims.
