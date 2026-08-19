<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: PF1, sev: should-fix, loc: ByModulatorPresence.java:54, anchor: "### PF1 ", cert: C1, basis: "the presence conjunct is the only filter on the alias it lands on, and the MATCH cost model reads any filter as a narrowing — classCount/2 against an unfiltered classCount+1 — so a predicate that matches nearly every row reorders the root schedule"}
  - {id: PF2, sev: suggestion, loc: GremlinProjectionAssembler.java:108, anchor: "### PF2 ", cert: C2, basis: "a DECLINE aborts the whole walk, so withdrawing keyless elementMap / valueMap(true) and count-after-grouping withdraws every traversal that ends in one of them, prefix included"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 2}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: REFUTED,   anchor: "#### C3 "}
  - {id: C4,  verdict: REFUTED,   anchor: "#### C4 "}
  - {id: C5,  verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6,  verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [should-fix] A presence-only alias filter reads as a 2× narrowing the planner never gets

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/ByModulatorPresence.java` (line 54). Contributing call sites: `OrderGlobalStepRecogniser.java:71`, `SelectStepRecogniser.java:61`, `SelectOneStepRecogniser.java:59`, `GremlinAggregateAssembler.java:143`, `:233`, `:238`. Cost model that consumes it: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (lines 6408-6419, 2260-2266, 2970-2982) and `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java` (lines 86-100).

**Issue**: The `IS DEFINED` conjunct is correct and has to stay. What is new is where it lands — on the boundary alias of a traversal that carries no user-written filter there. `g.V().out("knows").order().by("name")` now produces an alias filter on the last node of the chain and nothing on the first.

`estimateRootEntries` is built on the premise that a filter means a narrowing. An unfiltered alias is scored `classCount + 1`, and the `+ 1` is deliberate: the comment at line 6417 says it exists "so that a filtered node with the same class count is preferred". A filtered alias is scored `min(filter.estimate(...), classCount)`. `SQLWhereClause.estimate` has no estimator for `IS DEFINED` — it is not index-aware (`SQLIsDefinedCondition.isIndexAware` returns `false`), no histogram path recognises it, and `getEqualityOperations` skips it — so every route through that method falls back to the same `classCount / 2` heuristic at line 93.

The root schedule sorts those numbers ascending (`MatchExecutionPlanner.java:2260-2266`) and starts its depth-first pass from the cheapest. So `|V| / 2` beats `|V| + 1` and the chain is scheduled from the tail backwards. The same number feeds `applyClassSelectivity`, which for a lone presence conjunct gets `-1.0` from `estimateFilterSelectivity` (the clause is not an `SQLBinaryCondition`) and falls through to `targetEstimate / classCount` — 0.5 — into the per-edge row forecast that drives the `BUILD_EAGER` versus `DEFERRED_WITH_NET` decision.

The estimator's blind spot predates this step: `has(key)` has always mapped to `IS DEFINED` (`TraversalFilterStepRecogniser.java:59`). The difference is that a user who writes `has("age")` is asking for a narrowing and usually gets one, while a user who writes `order().by("age")` is asking for a sort and, on a schema-declared property, gets a predicate that matches every row.

**Evidence** (`#### C1`, `#### C3`, `#### C6`, `#### C10`):

COST TRACE for the added conjunct, per query:

- OPERATION (compile): one `SQLIsDefinedCondition` plus one `SQLWhereClause` per modulated alias, then an AND-merge into any existing clause. Walk-time, not row-time (C6).
- OPERATION (execution, intended): one `expression.isDefinedFor(...)` per candidate row on that alias. This is the correctness price and it is cheap — a property-presence read on a row already in hand, no I/O.
- OPERATION (execution, unintended): the alias's root estimate drops from `classCount + 1` to `classCount / 2`, and its edge-cost selectivity from 1.0 to 0.5.
- PLAN EFFECT: the sorted root list can reorder. `Collections.sort` on `PairLongObject` compares the estimate only and is stable, so a tie preserves map order, but this is not a tie — the two values differ by construction.
- I/O: unchanged for the two-node case (a chain walked forward or backward crosses the same edge set). Changed for three or more nodes, where intermediate cardinality depends on direction.

SCALE CHECK:

- AT SMALL SCALE (100 records): negligible. Either direction reads the whole class.
- AT MEDIUM SCALE (100K records), start alias carries an indexed `has()`: negligible. The index estimate is far below `|V| / 2`, so the start alias stays root and the schedule is unchanged.
- AT MEDIUM SCALE, start alias carries only `hasLabel(L)`: the start alias is scored `|L| + 1` and the boundary `|V| / 2`. On a graph where class `L` holds more than half the vertices — a single-label graph, or `Person` in a person-dominated dataset — the boundary wins and the chain reverses.
- AT PRODUCTION SCALE (1M+), start alias carries a non-index-aware filter such as `containing(...)` or `endsWith(...)`: both aliases score `classCount / 2` and the tie breaks on hash order of the `$g2m_` alias names. Before this step the start alias won outright. A tie that resolves the wrong way applies the text filter after the expansion instead of before it.
- VERDICT: MATTERS AT SCALE.

**Impact**: Plan-shape change — root selection, join direction and the per-edge row forecast — on `order().by(key)`, `select(...).by(key)`, `group()` / `groupCount().by(key)` and `values(k).sum()` / `.mean()` / `.min()` / `.max()`. That is most of the result-shaping surface this step exists to fix. The direction of the change is workload-dependent: on a symmetric two-node pattern it is neutral, on a skewed multi-hop chain it can go either way by a large factor. I reasoned this from the estimator and scheduler code and did not measure it; no benchmark in the branch covers a translated `order().by(key)` over a multi-hop pattern.

**Suggestion**: The fix belongs in the estimator; the conjunct should stay as written. Two options, both executor-side and both outside this diff, in the same bucket as step 3's PF2:

1. Narrow and safe — teach `MatchExecutionPlanner.estimateFilterSelectivity` and `SQLWhereClause.estimate` to recognise `SQLIsDefinedCondition` and `SQLIsNotDefinedCondition` as presence predicates with selectivity near 1.0, and to skip a presence-only clause when deciding whether an alias is filtered at all. This leaves every other filter shape alone.
2. Broader — have `SQLWhereClause.estimate` return `classCount` rather than `classCount / 2` when no flattened condition was estimable. That still keeps the intended "filtered beats unfiltered" bias, since `classCount < classCount + 1`, without inventing a narrowing. It touches every unestimable filter, so it needs its own measurement.

Until one of those lands, record in the track's coverage ledger that translated `order().by(key)` and friends attach a presence-only filter to the boundary alias, so a later plan-shape surprise has a written cause.

### PF2 [suggestion] Four keyless shapes leave MATCH entirely, prefix included

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinProjectionAssembler.java` (lines 108-114) and `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinAggregateAssembler.java` (lines 75-77)

**Issue**: Both fixes are implemented as declines, and a decline is traversal-wide. `GremlinStepWalker.dispatchAll` returns `false` on the first declining recogniser and `walk` returns `null`; there is no partial-splice path that keeps a recognised prefix (established as C1 in `performance-step7-iter1.md`, re-verified here as C2). So `g.V().has("name", x).out("knows").elementMap()` no longer compiles to MATCH at all — the `has`, the hop and the projection all run on the native traverser pipeline.

Four shapes move. Keyless `elementMap()` and keyless `elementMap()` with tokens both used to pass the old guard, because `isElementMap` was derived as `elementMapTokens != 0` and the empty-key decline was gated on `!isElementMap`. `valueMap(true)` and `valueMap().with(WithOptions.tokens)` passed for the same reason. `count()` after `group()` or `groupCount()` used to be accepted and clobber the `GROUP BY`. All four returned wrong answers, so withdrawing them is right; the cost is coverage, and `elementMap()` with no key list is a shape people actually write.

Bare `valueMap()` is not in the set — it declined before this step too.

**Evidence** (`#### C2`):

COST TRACE for a withdrawn shape, per query execution:

- COMPILE COST: falls. The walk aborts at the projection or terminator step instead of building a `MatchPlanInputs` AST.
- EXECUTION COST: rises where the traversal has a body. Filters and hops run as per-traverser steps rather than inside one MATCH plan.
- I/O: a leading `has()` keeps index-backed access on the native side. `YTDBGraphStepStrategy` folds a directly-following `HasStep` into `YTDBGraphStep`, which runs a plan-backed query (established as C2 in `performance-step7-iter1.md`). Non-leading predicates lose that and filter in memory.
- ALLOCATIONS: unmeasured on both sides.

SCALE CHECK:

- AT SMALL SCALE (100 records): negligible.
- AT MEDIUM SCALE (100K records), bare `g.V().elementMap()`: negligible. There is nothing for a MATCH plan to optimise.
- AT PRODUCTION SCALE (1M+), a filtered multi-hop traversal ending in one of the four: noticeable. The hop chain runs per traverser and non-leading predicates filter in memory.
- VERDICT: MATTERS AT SCALE.

**Impact**: Higher latency on filtered multi-hop traversals that end in a keyless map projection or in `count()` after a grouping terminator. Compile cost drops for the same shapes. Magnitude unmeasured; the worst case checked for — losing index access on a leading filter — does not apply.

**Suggestion**: Record the four withdrawn shapes in the track's coverage ledger so the plan's stated coverage stays accurate. The keyless map family is recoverable once schema-driven all-property projection lands, which the code comment at `GremlinProjectionAssembler.java:110-113` already names as the blocker; sequencing it ahead of other projection work would return `elementMap()` to MATCH. Count-after-grouping needs a different fix — a count over the grouped output rather than over the rows that fed it — and is worth less, since the shape is rarer.

## Evidence base

#### C1 The presence conjunct is the alias's only filter, and the cost model reads that as a narrowing — CONFIRMED

`ByModulatorPresence.requireProperty` (line 54) writes an `IS DEFINED` clause through `putAliasFilter`; `estimateRootEntries` (`MatchExecutionPlanner.java:6408-6419`) scores a filtered alias `min(estimate, classCount)` against an unfiltered `classCount + 1`, and `SQLWhereClause.estimate` line 93 returns `classCount / 2` for a predicate it cannot estimate. Raised as PF1.

#### C2 The four withdrawn shapes take their whole traversal off MATCH — CONFIRMED

Verified against the pre-change assembler: `isElementMap = elementMapTokens != 0` skipped the empty-key decline for every token-bearing call, so keyless `elementMap()`, `valueMap(true)` and `valueMap().with(WithOptions.tokens)` were all accepted before this step; `recognizeCount` had no `groupBy()` guard. Traversal-wide abort re-confirmed at `GremlinStepWalker.dispatchAll`. Raised as PF2.

#### C3 The AND-merge hides an index-usable equality from the planner — REFUTED

CLAIM: `putAliasFilter` composes a second contribution as `and(existing, new)` rather than flattening, so on `g.V().has("name", "marko").order().by("age")` the index-usable equality ends up one level down inside a nested `SQLAndBlock` and the planner's index matcher, which reads `condition.subBlocks` directly, no longer sees it. That would turn an index seek into a class scan — an order-of-magnitude regression on the exact shape the step targets.

REFUTATION: the nesting is flattened before any matcher reads it. `SQLAndBlock.flatten` recurses into each sub-block and merges the returned `subBlocks` into one flat block, so `and(and(a, b), c)` reaches `getEqualityOperations` as a three-element flat block. `SQLWhereClause.estimate` calls `flatten` before its index loop, and `MatchExecutionPlanner.estimateCompoundAndSelectivity` recurses through `estimateSubExpression` for the same reason.

The second half of the claim also fails. `estimateCompoundAndSelectivity` multiplies only the sub-expressions it can estimate and ignores the rest — a sub-expression returning `-1.0` is skipped, not propagated — so `name = 'marko' AND age IS DEFINED` estimates as `sel(name = 'marko')`. Adding the presence conjunct to an alias that already carries an estimable filter costs nothing in the estimate. That is exactly why PF1 is scoped to aliases where the presence conjunct is the *only* filter.

SCALE CHECK: no cost at any scale. Not reported.

#### C4 `SQLFunctionMean` is a per-row cost regression over the `avg` it replaces — REFUTED

CLAIM: a new aggregate on the per-row execution path is new per-row cost.

REFUTATION: `mean` replaces `avg` on this path (`PropertyAggregateStepRecogniser.java:25-31` changed the emitted function name), so the baseline for comparison is what `avg` cost on the same rows. The two are the same shape. Both accumulate through `PropertyTypeInternal.increment`, which boxes one `Number` per contributing row in both cases. Both call `getResult()` at the end of every `execute()` invocation, so both divide per row and discard all but the last result — `SQLFunctionMean.java:82` mirrors `SQLFunctionAverage.java:69` verbatim.

The one delta I could name: `computeMean` always returns a boxed `Double`, while `computeAverage` over an integer column returns a boxed `Integer` that can hit the `-128..127` cache. That is at most one extra 16-byte young-gen object per row, on a row that already pays a property read, an `increment` box and a result materialisation. I reasoned this from the two sources side by side rather than measuring it; a JMH run would be needed to separate it from noise, which is itself the argument that it does not matter.

SCALE CHECK: negligible at every scale. Not reported.

#### C5 `SQLFunctionMean` buffers its input instead of accumulating in constant space — REFUTED

CLAIM: the new aggregate holds its contributors, so a mean over a large column is a memory risk.

REFUTATION: state is two fields, `Number sum` and `int total` (`SQLFunctionMean.java:51-52`). Nothing else is retained. The single-argument branch adds one value per call; the multi-value branch iterates `MultiValue.getMultiValueIterable` and adds each element without materialising a copy. Peak footprint is one boxed `Number`, independent of row count. The `BigDecimal` branch of `computeMean` allocates `new BigDecimal(iTotal)` per `getResult()` call, which is per row for the same inherited reason as C4, and is the same allocation `computeAverage` makes on the same branch.

SCALE CHECK: constant space at every scale. Not reported.

#### C6 `ByModulatorPresence` is evaluated per row rather than once per plan — REFUTED

CLAIM: the step 3 precedent — `SQLMatchFilter.getClassName` allocating an `SQLIdentifier` on a path `executeTraversal` runs once per upstream row per hop — repeats here, with `WHERE.wrap(WHERE.isDefined(key))` allocating two AST nodes per row.

REFUTATION: every caller is a `StepRecogniser.recognize` body or an assembler reached from one, and recognisers run inside `GremlinStepWalker.walk` — once per compilation, over the traversal's step list, with no access to a row. The six call sites (`OrderGlobalStepRecogniser:71`, `SelectStepRecogniser:61`, `SelectOneStepRecogniser:59`, `GremlinAggregateAssembler:143`, `:233`, `:238`) each run at most once per matched step. The output is an AST node stored in `aliasFilters`, which the planner reads once and the executor evaluates per row without rebuilding.

The three supporting allocations are all per compilation: the `RecognitionContext.PropertyProjection` record is one per `values(key)` step, the `MatchWhereBuilder WHERE` field is a shared static over a builder whose own Javadoc states it holds no state and returns a fresh node per call, and `ProductiveByStrategy`'s key set is resolved once per walk at `GremlinStepWalker.java:302-309` rather than once per modulator, which the code comment says explicitly.

The one place per-row cost does appear is the evaluated conjunct itself, and that is the correctness price PF1 accepts.

SCALE CHECK: compile-time cost does not scale with graph size. Not reported.

#### C7 The projection rework adds a pass over the result set — REFUTED

CLAIM: separating the token flag from the element-map flag makes the shaper walk the row twice, or emits more columns than before.

REFUTATION: column count is unchanged. The old code emitted the id and label columns when `elementMapTokens != 0`; the new code emits them under the same condition, now spelled `tokens != 0` (lines 118-125). What changed is which flag drives list wrapping — `withWrapMapValuesInLists(!isElementMap)` now reads the step identity rather than the token bits — and that is a boolean field on `ResultShaping`, read once per row inside the existing single shaping pass. No loop was added, no column was added, no second traversal of the row.

`GremlinAggregateAssembler.recognizePropertyAggregate` does move work, and in the cheaper direction: it replaces the row-shaping `dropOnAbsent` filter that `configureSingleProperty` had installed with a pattern conjunct, so absent-property rows are dropped inside the MATCH plan instead of after projection. Fewer rows reach the aggregate.

SCALE CHECK: no added pass at any scale. Not reported.

#### C8 The union fork path loses the productive-key resolution, so each arm pays spurious presence conjuncts — REFUTED

CLAIM: `walk` resolves `ProductiveByStrategy`'s key set from `traversal.getStrategies()`, but `UnionForkHostImpl.walkFork` builds a fresh `DefaultGraphTraversal`. If the strategies do not travel with it, `byModulatorIsProductive` returns `false` on every arm and each arm gains an `IS DEFINED` conjunct the parent would have skipped — N arms times the PF1 cost.

REFUTATION: the strategies travel. `walkFork` calls `forked.setStrategies(parent.getStrategies())` before adding any step and then re-enters `GremlinStepWalker.production().walk(forked)`, which re-runs the same `getStrategy(ProductiveByStrategy.class)` lookup against the copied list. `SubTraversalPredicateAdapter.byModulatorIsProductive` delegates to its parent for the same reason, stated in its own comment. Arms and sub-walks see the same answer as the top-level walk.

SCALE CHECK: no divergence, so no cost at any scale. Not reported.

#### C9 Registering `mean` outside the cache-determinism classification disables plan caching for mean queries — REFUTED

CLAIM: a builtin the cache does not know about is treated conservatively and blocks the tx-result cache, so every `mean()` query re-plans.

REFUTATION: `NonDeterministicQueryDetector`'s list is a denylist and fails open, as `FunctionDeterminismEnumerationTest`'s own Javadoc states. A name absent from it is cacheable. `mean` is absent from the denylist and was added to the test's `KNOWN_DETERMINISTIC` set in this same commit, which is the enumeration guard rather than the production classifier. Caching behaviour for `mean` matches `avg`.

SCALE CHECK: no cost at any scale. Not reported.

#### C10 The halved estimate flips aliases into eager prefetch — REFUTED

CLAIM: `estimateRootEntries` feeds a prefetch decision at `MatchExecutionPlanner.java:635-641` that materialises every alias scoring below `THRESHOLD`, so halving an estimate pulls aliases into eager materialisation that were previously streamed.

REFUTATION: the flip band is too narrow and too small to matter. `THRESHOLD` is 100. An unfiltered alias scores `classCount + 1`, so it prefetched already whenever `classCount <= 98`. With the presence conjunct it scores `classCount / 2`, so it prefetches whenever `classCount <= 199`. The shapes that change behaviour are classes holding between 99 and 199 records, and eagerly materialising at most 199 records costs a few tens of kilobytes.

This is the one sub-claim of PF1 that does not survive. The root-ordering and edge-forecast consequences do, which is what PF1 reports.

SCALE CHECK: negligible at every scale. Not reported.

METHOD CAVEAT (applies to every cert above): mcp-steroid PSI times out in this repository, so caller and override searches ran through grep with each returned site read end to end. Two conclusions rest on a caller search and would flip if grep missed a reference. C6's claim that no `ByModulatorPresence` call site sits on a per-row path rests on the six grep hits for `requireProperty` and `requireModulatedProperty` across `core/src/main/java`, each read in context; the class is package-private and `final` with a private constructor, so a polymorphic call site is not available to hide one. C1's claim that `estimateRootEntries` is the sole consumer of the filtered-versus-unfiltered distinction rests on the ten grep hits for `estimatedRootEntries` inside `MatchExecutionPlanner`, of which three were read in full. Everything else in this review rests on reading files end to end rather than on a reference search.
