<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 3, suggestion: 1}
index:
  - {id: PF1, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:5448, anchor: "### PF1 ", cert: C1, basis: "addStepsFor prefers class over pinned RIDs, so a RID-bearing edge pattern with >=100 ids still compiles a full V scan"}
  - {id: PF2, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java:5573, anchor: "### PF2 ", cert: C2, basis: "retained @rid IN post-filter makes a promoted N-RID fetch O(N^2) in comparisons and in throwaway SQLRid evaluations"}
  - {id: PF3, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java:507, anchor: "### PF3 ", cert: C3, basis: "RID-bearing plans bypass GremlinPlanCache; DR-M4's own 5.7x-at-one-vertex figure is that recompile, and step 5 does not change it"}
  - {id: PF4, sev: suggestion,  loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLSuffixIdentifier.java:191, anchor: "### PF4 ", cert: C4, basis: "isProjection() does not stop record dispatch; ResultInternal.hasProperty can issue a per-row lazy record load"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 4}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: PLAUSIBLE, anchor: "#### C4 "}
  - {id: C5,  verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6,  verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7,  verdict: REFUTED,   anchor: "#### C7 "}
  - {id: C8,  verdict: REFUTED,   anchor: "#### C8 "}
  - {id: C9,  verdict: REFUTED,   anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED,   anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [should-fix] A RID-bearing edge pattern with 100 or more ids still compiles a full `V` scan

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (lines 5441-5453, the test at 5448)

**Issue.** Step 5's promotion fires for every RID arity, but the planner discards it at one of the three places that consume `aliasPinnedRids`. `g.V(id1 … id150).out("knows")` still plans a `FetchFromClassExecutionStep` over the whole polymorphic `V` hierarchy with an `@rid IN [...]` post-filter — the exact shape DR-M4 measured at 2002x the native path.

The chain has five links.

`promoteStaticRidsFromFilters` puts N `SQLRid` entries in `aliasPinnedRids` for the boundary alias. `estimateRootEntries` then returns `ridList.size()` for that alias (`:6240`), so the estimate is N. The prefetch filter keeps only aliases whose estimate is below `THRESHOLD`, which is 100 (`:630`), so an alias carrying 100 or more pinned RIDs is never prefetched. The scheduler still picks that alias as the pattern root, because N is far below the neighbour node's class count. `addStepsFor` therefore reaches its else branch (`:5441`), which builds the root scan by hand and tests the class before the RIDs: `if (clazz != null) … else if (pinnedRids != null)`. `StartStepRecogniser` always records `V` for the boundary alias (`WalkerContext.VERTEX_ROOT_CLASS`, `:219`), so `clazz` is never null and the pinned list is dropped.

The two sibling call sites do the opposite. `createSelectStatement` tests `targetRids` first (`:5576`), and both `addPrefetchSteps` (`:5553`) and the edge-free branch of `createPlanForPattern` (`:2144`) route through it. `addStepsFor`'s hand-rolled copy of that builder is the only place with the reversed precedence, which is why the defect survives only on the edge-pattern root.

**Evidence.** COST TRACE and SCALE CHECK in `#### C1`.

**Impact.** Latency and throughput on any bulk-id traversal. On a 1M-vertex graph, `g.V(<150 ids>).out(...)` reads 1M records to reach 150 — the same order of regression DR-M4 recorded before the fix, on a shape the fix does not reach.

**Suggestion.** Replace lines 5442-5453 with a call to the shared builder: `createSelectStatement(clazz, pinnedRids, where == null ? null : where.copy())`. That gives the branch rid-first precedence and removes the duplicated statement assembly, which is what let the two precedences drift apart. Then add a smoke test at an arity above `THRESHOLD` with an edge step. Every plan-shape test step 5 added uses one or two ids and no edge, so the whole above-threshold region is untested.

### PF2 [should-fix] The promoted fetch keeps its `@rid IN` post-filter, so an N-RID lookup costs O(N^2)

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` (line 5573, `createSelectStatement`)

**Issue.** A promoted alias plans as `SELECT FROM [#a, #b, …] WHERE @rid IN [#a, #b, …]`. The RID target already enforces membership, so the retained `WHERE` is pure redundancy, and it is evaluated per fetched record against a list that is rebuilt per fetched record.

`promoteStaticRidsFromFilters` leaves the `@rid` term in the alias filter on purpose; its Javadoc states the reason at `:5672`. `createSelectStatement` then copies that filter onto the synthetic `SELECT` (`:5573`). `SelectExecutionPlanner` turns the RID target into a `FetchFromRidsStep` (`:1443`) and chains the `WHERE` as a plain `FilterStep`; a RID target carries no class, so no index path can absorb the predicate.

Two costs stack per fetched record. `SQLInCondition.evaluate` calls `evaluateRight` (`:75`), which re-executes the literal collection expression and materializes a fresh N-element list of evaluated RIDs. `evaluateExpression` then walks that list with `QueryOperatorEquals.equals` (`:169`). The right side is a `List`, so the `Set.contains` fast path at `:156` never applies.

**Evidence.** COST TRACE and SCALE CHECK in `#### C2`.

**Impact.** CPU and GC pressure, both quadratic in the id-list length. At N = 10,000 with no edge step, the fetch does about 10^8 equality comparisons and allocates about 10^8 short-lived `SQLRid` values for what should be 10,000 record loads. This is unfinished work rather than a regression: the pre-fix plan paid `|V|` x N for the same predicate.

**Suggestion.** Strip the term where the target already enforces it. Inside `createSelectStatement`, when the `targetRids` branch is taken, drop the promoted `@rid` condition from the filter it attaches; at that point the fetch target and the predicate are the same list. If the term must survive for the case the promotion Javadoc names — an alias that does not end up as the fetch target — then make the predicate cheap instead: evaluate the right side once per plan rather than once per row, and hold it as a `Set` so `evaluateExpression`'s `:156` branch applies.

### PF3 [should-fix] By-id lookups still recompile their plan per call, and that recompile is the whole remaining gap

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (line 507)

**Issue.** `GremlinStepWalker` derives `cacheEligible` from `!ctx.ridBearing()`, and both `StartStepRecogniser` (`:134`) and `HasStepRecogniser` (`:142`) call `markRidBearing()`. `GremlinToMatchStrategy.buildPlan` short-circuits on that flag (`:419`) straight to `buildPlanUncached`, with no cache read and no cache write. `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to `true` (`GlobalConfiguration.java:1028`), so this is the default path for `g.V(rid)`.

DR-M4's own numbers say the recompile is what is left. At one vertex the translated path measured 5.7x native. At one vertex a class scan of `V` reads a single record, so the scan contributes almost nothing and the 5.7x is fixed cost. The cache-eligible control measured 12%, and that control still pays the walk, the fingerprint, and a plan copy. The gap between the two figures is the plan build. Step 5 changes what the plan contains and leaves the build count at one per call, so a post-fix by-id lookup should still sit near 5.7x native at small graph sizes.

The flag cannot simply be flipped. `StartStepRecogniser.buildRidInExpression` (`:257`) embeds each RID as a literal through `MatchLiteralBuilder.toLiteral`, and `GremlinPlanFingerprint`'s class Javadoc states that RIDs render verbatim into the key. Caching as-is would be correct and would mint one LRU entry per RID looked up. Making the shape cacheable means binding the RIDs as positional parameters, which conflicts with `toPromotedSqlRidList` reading their values at plan time (`:5772`).

**Evidence.** COST TRACE and SCALE CHECK in `#### C3`.

**Impact.** A per-call constant, so it scales with request rate rather than with graph size. A by-id lookup is the highest-frequency graph operation in an OLTP workload, which makes a ~5x constant on it the largest remaining item on the translator's cost sheet.

**Suggestion.** Record the residual as a rostered follow-up with DR-M4's figures attached, rather than leaving it implicit in the step-5 episode; Track 9's JMH baseline will otherwise bake it in exactly as the track warns the scan would have. Two remedies are on the table. Declining translation for a start step whose only content is an id list restores the native O(1) path immediately; the track already names this option and gates it as an ESCALATE. Binding the RIDs as positional parameters and moving the promotion behind a per-execution pinned-RID slot that `FetchFromRidsStep` reads at start time keeps the translation and makes the shape cacheable, at the cost of a new execution-time seam.

### PF4 [suggestion] `isProjection()` does not stop the record dispatch its comment claims to prevent

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLSuffixIdentifier.java` (line 191)

**Issue.** The comment at lines 186-189 says the `isProjection()` gate keeps a record-backed `Result` from dispatching to the record for a `$`-prefixed name, and calls out the cost it avoids: a storage read for a guaranteed miss on a lazily loaded RID-only `Result`. The gate does not give that guarantee.

`isProjection()` returns `content != null` (`ResultInternal.java:757`). A `Result` can hold both a `content` map and an `identifiable`. On such a row, `hasProperty` misses in `content` and falls through (`ResultInternal.java:622-630`): it calls `entity.hasProperty(name)` when the identifiable is already materialized, or `loadLazyAndHasProperty` (`:639`) when it is a bare RID. `loadLazyAndHasProperty` issues `transaction.load(identifiable)` — the storage read the comment says the gate rules out.

**Evidence.** COST TRACE and SCALE CHECK in `#### C4`. The path is latent rather than live: I found no step on the MATCH read path that puts `content` on a record-backed row.

**Impact.** None measurable today. The cost, once a caller reaches the shape, is one record load per row per unresolved `$`-name.

**Suggestion.** Make the gate match the comment by testing the projection map directly instead of going through `hasProperty`. A `hasProjectionColumn(String)` on `ResultInternal` that reads only `content` would do it in one line at each end, and would keep the comment true for the next reader who leans on it.

## Evidence base

Phase-4 Scale Validation. A claim that survived the refutation check is compressed to one line; a refuted or unproven claim is written out, because the reasoning is the part worth keeping.

**Reference-accuracy caveat (grep-only).** `steroid_list_projects` confirmed the open project matches this working tree, but `steroid_execute_code` exceeded the 60-second MCP timeout on the first PSI query, as the repository's known behaviour predicts. Every caller set below comes from grep over `*/src/main`, so a polymorphic call site or a reflective dispatch could be missing. The claims most exposed to that are C5 and C9, both of which turn on "no production caller reads `getSubSteps()` during execution".

#### C1 Edge-pattern root with 100+ pinned RIDs — CONFIRMED

At 1M vertices, `g.V(<150 ids>).out("knows")` plans `FetchFromClassExecutionStep` over `V` (1M reads) plus an N-element `@rid IN` post-filter per record, against 150 direct loads on the native path; negligible at 100 vertices, severe at 100K and above; the branch is reachable for every id list of 100 or more with at least one edge step, and no test in the diff covers it.

#### C2 Retained `@rid IN` post-filter on a promoted fetch — CONFIRMED

N records x (one N-element list materialization plus up to N `QueryOperatorEquals.equals` calls): about 9,801 comparisons at the prefetched ceiling of 99 RIDs, which is small next to 99 record loads, and about 10^8 comparisons plus 10^8 short-lived objects at N = 10,000 on the non-prefetched edge-free path, which is seconds of CPU and hundreds of MB of garbage.

#### C3 Uncached recompile for every RID-bearing walk — CONFIRMED

DR-M4 measured 5.7x native at one vertex where the scan reads one record, against a 12% control that pays the walk, the fingerprint, and a plan copy but not the build; step 5 leaves the build count at one per call, so the constant survives the fix and grows with request rate on the highest-frequency graph operation.

#### C4 Per-row record load behind the `isProjection()` gate — PLAUSIBLE, not demonstrated

PREMISE. `SQLSuffixIdentifier.execute(Result, ctx)` is a per-row path: projection and filter evaluation call it once per row per identifier.

COST TRACE. For a `$`-prefixed name on a row where `content != null`, the new code runs `content.containsKey` and, on a miss, falls through inside `hasProperty` to the record. On a bare-RID identifiable that is `transaction.load` — one storage read per row per unresolved `$`-name. On a materialized `Entity` it is a map lookup, which is cheap.

REFUTATION ATTEMPT. The finding needs a row that is both a projection and record-backed, reached by an expression naming a `$` identifier the projection does not carry. I looked for a step that adds `content` to an upstream record-backed row. `LetExpressionStep.mapResult` uses `setMetadata`, not `setProperty`, so a per-record `LET` leaves `content` null and `isProjection()` false — the gate skips the probe and the old behaviour stands. Grep over `core/.../sql/executor` finds exactly two sites that call `setProperty` on an upstream row: `RemoveEmptyOptionalsStep:45`, which operates on MATCH rows that are already projections with no identifiable, and `InsertValuesStep:78`, which is a write path and not a per-row read loop. MATCH read rows are built fresh by `MatchFirstStep` and the projection step, with `content` set and `identifiable` null, so `hasProperty` returns false from the first branch without touching a record.

VERDICT. No reachable production shape found, so this is not a measurable cost today. It is reported at suggestion severity because the code carries a comment asserting the guarantee, and the next caller to add a `setProperty` on a record-backed row inherits a per-row storage read with a comment saying it cannot happen.

#### C5 `List.copyOf` snapshots in `getSubSteps()` — REFUTED

PREMISE UNDER TEST. Steps 2 and 3 added `List.copyOf` to `MatchPrefetchStep.getSubSteps()` (`:130`) and `MatchFirstStep.getSubSteps()` (`:166`). The question is whether either sits on a per-query or per-row path.

CALLER SET. Grep over every module's `src/main` finds three production readers of the accessor, all in the same two files: `ExecutionStep.toResult` (`:41` and `:44`), which builds an `EXPLAIN` / `PROFILE` result document, and `ExecutionStepInternal.basicSerialize` (`:197`, `:199`) with its `basicDeserialize` counterpart (`:230`), which serialize a plan for transmission. `YTDBGraphQuery.usedIndexes` walks top-level `getSteps()` plus `getSubExecutionPlans()` and never reaches this accessor, which the step-3 episode already records.

QUERY PATH CHECK. The metrics listener does not touch it either. `YTDBQueryMetricsStep.capturedExecutionPlan()` returns the plan reference and the `QueryDetails.getExecutionPlan()` override is lazy, so a listener that ignores the plan pays nothing and a listener that reads it pays one reference read.

COST TRACE. One `List.copyOf` per introspected step, over a sub-plan of a handful of steps, on an operator-initiated `EXPLAIN`.

VERDICT. NEGLIGIBLE. No finding.

#### C6 Full plan copy on re-arm after close — REFUTED

PREMISE UNDER TEST. `replaceClosedPlanWithCopy` calls `InternalExecutionPlan.copy` — for `MultiPlanMatchStep`, once per child (`MultiPlanMatchStep.java:265-305`). A deep copy of a step chain per invocation would matter if the hook fired often.

TRIGGER FREQUENCY. The hook fires only from `openArming()` in state `REARMED_AFTER_CLOSE`, which is reached only by `close()` followed by `reset()` followed by another pull. In TinkerPop that is an explicit re-iteration of a root traversal: `toList(); admin.reset(); toList()`. The two other reset sources do not reach it. `AbstractStep.clone()` calls `reset()` on the clone, and the diff defers the copy to the next open precisely so a clone does not mint one. A `repeat()` or `local()` body cannot host the boundary at all: `GremlinToMatchStrategy` declines any traversal with no attached `YTDBGraph`, which covers every anonymous child traversal, so the boundary only ever appears as a root traversal's sole step.

COMPARATIVE CHECK. Where the hook does fire, the alternative is recompiling the plan. A deep copy is the cheaper of the two, and it is the only correct one, since a closed chain cannot be revived.

VERDICT. NEGLIGIBLE. No finding.

#### C7 `HashSet<Long>` dedupe in `toPromotedSqlRidList` — REFUTED

PREMISE UNDER TEST. `toPromotedSqlRidList` (`MatchExecutionPlanner.java:5786-5801`) allocates an `ArrayList` and a `HashSet<Long>` per promotion, with one boxed `Long` per RID.

COST TRACE. O(N) time and O(N) allocation at plan time, N being the id-list length. At the prefetched ceiling of 99 RIDs that is 99 boxed longs and one small hash table.

COMPARATIVE CHECK. The dedupe is load-bearing rather than optional: without it, a repeated id in the promoted list fetches the same record twice and changes the result multiset, which the code comment at `:5781` explains. Any cheaper structure (a sorted long array, a primitive-keyed set) saves boxing but not the O(N) pass.

SCALE CHECK. Even at N = 10,000 this is one linear pass with 10,000 boxed longs, which is four orders of magnitude below the O(N^2) post-filter that C2 traces on the very same plan. Optimizing it before C2 would be measuring the wrong thing.

VERDICT. NEGLIGIBLE. No finding.

#### C8 `g.V().hasId(x)` left unfixed — REFUTED

PREMISE UNDER TEST. The track's `## Surprises & Discoveries` inferred, without measuring, that `hasId` shares the scan defect because `HasStepRecogniser:142` calls `markRidBearing()` on the same path. If step 5's leaf branch reached only the `g.V(rid)` shape, `hasId` would still scan.

PATH TRACE. `HasStepRecogniser.translateHasId` delegates to `StartStepRecogniser.buildRidInExpression` (`HasStepRecogniser.java:222`), so both entry points install the identical `SQLInCondition` with an `SQLRecordAttribute` left side. When `hasId` is the traversal's only predicate, `MatchWhereBuilder.and` returns the lone operand unwrapped, which is exactly the leaf shape the new branch handles. When it is combined with other predicates, the clause is an `SQLAndBlock` and the rewritten AND loop recurses into each sub-term until the leaf branch applies the extractor, so the term is still found.

TEST COVERAGE. `GremlinToMatchSmokeTest.translatedHasIdLookupFetchesByRid` pins the shape structurally, asserting a `FetchFromRidsStep` and the absence of a `FetchFromClassExecutionStep`.

VERDICT. The fix reaches `hasId`. The residual gap is arity, not entry point, and C1 owns it.

#### C9 `ExecutionStep.toResult` calls `getSubSteps()` twice — REFUTED

PREMISE UNDER TEST. `ExecutionStep.java:41` calls `getSubSteps()` and discards the result, then `:44` calls it again. With the new `List.copyOf` overrides that is one wasted defensive copy per introspected step.

SCALE CHECK. The bare call is pre-existing and the path is `EXPLAIN` only, per C5's caller set. One extra copy of a handful of step references, once per operator-initiated plan render.

VERDICT. NEGLIGIBLE as a performance matter. The dead statement is worth deleting as a tidy-up, which belongs to a quality review rather than this one.

#### C10 Skipping the sub-plan build for a prefetched root — REFUTED as a cost

PREMISE UNDER TEST. `addStepsFor` gained a branch that hands a prefetched root alias the sub-plan-free `MatchFirstStep` constructor (`MatchExecutionPlanner.java:5435-5440`). Any new branch on a planning path is worth a cost trace.

COST TRACE. The branch removes work rather than adding it. The old code called `select.createExecutionPlan(subContxt, profilingEnabled)` for every root, which compiles a full `SELECT` plan; for a prefetched alias `MatchFirstStep.internalStart` reads the prefetch cache and never starts that plan. The branch is one `Set.contains` against `prefetchedAliases`, and it saves a whole nested plan compile plus a `BasicCommandContext` allocation for every prefetched root.

VERDICT. A planning-time improvement. No finding.
