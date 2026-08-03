<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: PF1, sev: should-fix, loc: MatchPatternBuilder.java:391, anchor: "### PF1 ", cert: C1, basis: "the NOT site binds whatever aliasClasses holds, and for a hop with no hasLabel that is the generic V the walker site deliberately skips; the executor then loses its no-filter fast return and runs a class check per candidate that excludes nothing"}
  - {id: PF2, sev: suggestion, loc: MatchEdgeTraverser.java:392, anchor: "### PF2 ", cert: C2, basis: "SQLMatchFilter.getClassName allocates a fresh SQLIdentifier per call and executeTraversal calls it once per upstream row per hop; this step is what first puts a class on Gremlin hop path items"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED,   anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED,   anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED,   anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED,   anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED,   anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [should-fix] The NOT site binds the generic `V` class the walker site skips

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java` (lines 391-393); the sibling decision it contradicts is at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (lines 632-636)

**Issue**: `mergedTargetFilter` binds `aliasClasses.get(alias)` with no filtering. On the Gremlin NOT path that value is the generic vertex root `V` whenever the sub-traversal's hop carries no `hasLabel`. `GremlinPatternAssembler.appendFoldedHop` registers every hop target as `WalkerContext.VERTEX_ROOT_CLASS` (lines 54 and 99), `SubTraversalPredicateAdapter.addNode` forwards that into the capture builder's `aliasClasses` (line 247), and `NotStepRecogniser` then calls `buildNotExpression` on that builder (lines 89-93). So `g.V().not(__.out())` and `g.V().not(__.out("knows"))` now emit a NOT path item carrying `class: V`.

The walker site rejects exactly this binding, and says why in its own comment: "binding it would cost a class check per candidate row for no narrowing". The two sites disagree, and the disagreement is silent — nothing in the diff or the tests reads a class off a NOT item built without a `hasLabel`. `NotStepRecogniserTest.edgeBearingChildWithTargetLabel_bindsTargetClassOnNotItem` pins the labelled case; the unlabelled one has no counterpart.

**Evidence** (`#### C1`, `#### C3`):

COST TRACE for `MatchEdgeTraverser.executeTraversal` on a NOT item, per upstream row:

- OPERATION: with `className` non-null the method loses its early return at line 409 (`filter == null && className == null && targetRid == null` no longer holds) and instead builds `queryResult.filter(...)`.
- ALLOCATIONS added per upstream row: one `SQLIdentifier` from `getClassName` (see PF2), one capturing lambda over three finals, one `FilterExecutionStream`.
- PER CANDIDATE added: the `filter` body at lines 449-472 — two `getSystemVariable` reads, `getStartingPointAlias()`, a `sourceRecord.getProperty` plus `matched.setProperty` pair, two `setSystemVariable` writes, then `matchesClassCached(ctx, "V", next)`. The class check itself is cheap after the first candidate in a cluster (the `(className, collectionId)` memo at lines 526-528), but the surrounding stream and context churn is paid per candidate regardless.
- INVOCATION FREQUENCY: `MatchStep.internalStart` flat-maps one `MatchEdgeTraverser` per upstream row (line 92, `createTraverser` at 108), and `hasNext` runs `init` once, which runs `executeTraversal` once. On the nested-loop NOT path `FilterNotMatchPatternStep.matchesPattern` rebuilds and runs a sub-plan per upstream row (lines 86-108), so the multiplier is (upstream rows × NOT hops).

SCALE CHECK:

- AT SMALL SCALE (100 records): negligible.
- AT MEDIUM SCALE (100K records): `canUseHashJoin` is likely to hold, so `buildNotPatternPlan` materialises the build side once and every candidate in it takes the class check. At 100K origins and a fan-out of five that is 500K wasted `filter` bodies. Noticeable, not severe.
- AT PRODUCTION SCALE (1M+ records): the estimate crosses `getHashJoinThreshold()` and the plan falls back to `FilterNotMatchPatternStep`, so the cost becomes per-upstream-row rather than per-build-row. The added work is a few percent of the per-row sub-plan construction that path already pays, on top of a million extra short-lived allocations.
- VERDICT: MATTERS AT SCALE. The magnitude is modest; the reason to fix is that it is pure waste, contradicts a decision the sibling site states explicitly, and the fix is one condition.

**Impact**: A constant-factor slowdown and extra young-gen churn on every translated `not(<edge-bearing sub-traversal>)` that does not name a target label — the common form. No effect on plan shape.

**Suggestion**: Skip the generic root at the NOT site the way the walker site does. `WalkerContext.VERTEX_ROOT_CLASS` is package-private in the Gremlin strategy package and `MatchPatternBuilder` lives under `sql.executor.match.builder`, so the constant cannot be read directly. The smallest fix that keeps the two sites from drifting again is to let the caller say which class name means "no narrowing" — an overload of `buildNotExpression` taking that name (or a `Predicate<String>`), passed by `NotStepRecogniser`, with `mergedTargetFilter` applying it before the `filter.setClassName` call. Whichever shape is chosen, the reason belongs in a comment at both sites; the walker's version already carries it and the NOT version should point at it.

Do not fix this by dropping the `V` registration in the capture builder. `NotStepRecogniser.edgeBearingNotCapturesUnsupportedOriginConstraints` (line 144) and `WalkerContext.boundaryClass` (line 327) both read `registeredAliasClasses()`, so removing the entry changes decline behaviour.

### PF2 [suggestion] `getClassName` allocates once per upstream row per hop

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchFilter.java` (lines 141-160); hot call site at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchEdgeTraverser.java` (line 392, via `targetClassName` at 480-482)

**Issue**: `SQLMatchFilter.getClassName` resolves a class name by allocating. For a filter built through `setClassName` (the new method at lines 117-128) or through `fromAliasAndClass`, the `className` field is a `SQLExpression` wrapping a `SQLIdentifier`, so `item.className.value` is null and the method falls through to the third arm, `item.className.getDefaultAlias().getStringValue()`. `SQLExpression.getDefaultAlias` (lines 278-292) constructs a fresh `SQLIdentifier` on every call, and `SQLIdentifier.getStringValue` (lines 93-101) scans the value for a backtick.

`MatchEdgeTraverser.executeTraversal` calls this once per invocation, and it is invoked once per upstream row per hop. Before this step, Gremlin hop path items carried no class at all, so `getClassName` walked the item list and returned null without allocating. Now every `hasLabel`-typed hop target pays the allocation on every row.

The cost is pre-existing in the executor and is shared with SQL `MATCH` patterns that write `{class: X}` on a path item. What the step changes is the traffic: the Gremlin translator becomes a high-volume consumer of a per-row allocation it previously never triggered.

**Evidence** (`#### C2`):

COST TRACE for `executeTraversal` on a class-bearing positive hop item:

- OPERATION: `targetClassName(item, iCommandContext)` at line 392, unconditional whenever `item.getFilter() != null` — which is always true for translator-built items, since `MatchPatternBuilder.addEdge` attaches a `toFilter` at line 161.
- ALLOCATIONS: one `SQLIdentifier` (plus its `setStringValue` scan) and one `String.contains` scan per call.
- FREQUENCY: once per upstream row per hop, by the same `MatchStep` flat-map path traced in PF1.
- The class check itself is not waste here. `hasLabel` under polymorphic mode contributes no `WHERE` term, so the class slot is the constraint's only carrier and the per-candidate check is what makes the answer correct. Only the re-resolution of a constant string is waste.

SCALE CHECK:

- AT SMALL SCALE (100 records): negligible.
- AT MEDIUM SCALE (100K records), two hops: 200K short-lived objects per query. Measurable in allocation profiles, unlikely to show in latency.
- AT PRODUCTION SCALE (1M+ records), two hops: roughly 2M `SQLIdentifier` allocations for one query, on a step whose steady-state allocation should be dominated by result rows. Enough young-gen churn to matter on a query that is otherwise streaming.
- VERDICT: MATTERS AT SCALE.

**Impact**: GC pressure proportional to rows × hops on every translated traversal whose hops carry a class. No latency cliff, no plan change.

**Suggestion**: Resolve the class name once per traverser rather than once per row. `MatchEdgeTraverser` already has the pattern for this — `cachedSchema` at lines 530-535 is lazily resolved and reused across hops — so a nullable `resolvedClassName` field with a resolved-flag beside it fits the existing shape. Memoising inside `SQLMatchFilter.getClassName` would fix every caller at once, but that field is mutable through `setClassName` and `copy`, so the invalidation is the harder half; the traverser-local memo is the safer cut. Either way this is executor work, not a change to this diff, and it is worth a separate issue rather than a hold on this step.

## Evidence base

#### C1 The NOT site binds the generic `V` root class — CONFIRMED

`mergedTargetFilter` reads `aliasClasses.get(alias)` with no filtering (`MatchPatternBuilder.java:381`, bound at 391-393) and the Gremlin capture builder registers every unlabelled hop target as `V` (`GremlinPatternAssembler.java:54`, `SubTraversalPredicateAdapter.java:247`), so `not(out())` emits `class: V` on the NOT item. Raised as PF1.

#### C2 `getClassName` allocates per call on a per-row path — CONFIRMED

`SQLExpression.getDefaultAlias` constructs a new `SQLIdentifier` on every call (`SQLExpression.java:278-292`), `SQLMatchFilter.getClassName` reaches it for both `setClassName`- and `fromAliasAndClass`-built filters (`SQLMatchFilter.java:152-153`), and `MatchEdgeTraverser.executeTraversal` calls it once per upstream row per hop (`MatchEdgeTraverser.java:392`, via `MatchStep.java:92`). Raised as PF2.

#### C3 The post-`build()` pass is itself a per-compilation cost worth flagging — REFUTED

CLAIM: `bindPathItemConstraints` runs on every translated traversal, walks the whole assembled pattern, and does not short-circuit when both alias maps are empty — which is the common case for an unfiltered multi-hop walk. That makes it a per-query tax on the plan-cache hit path.

The first half of the claim holds and is worth stating precisely, because it is not obvious. `GremlinPlanCache` keys on a fingerprint computed *after* the walk (`GremlinToMatchStrategy.java:276` then 439-455), so the cache saves planning, never walking. Every execution of a translated traversal runs the pass.

REFUTATION: the pass short-circuits, and the walk is bounded by the traversal, not by the data.

- Per path item the pass does two `HashMap.get` calls and one `String.equals` before deciding. When the item's target alias has no filter and its class is the generic root, `className` is nulled at line 635 and the `continue` at line 638 fires with zero allocation. For an unfiltered multi-hop walk that is the outcome on every item.
- The loop is over `pattern.aliasToNode.values()` and each node's `out` set — one visit per pattern edge, and pattern edges come from traversal hops. A Gremlin traversal that reaches the translator has single-digit hops; nothing here scales with graph size.
- Each edge is visited once. Only `node.out` is walked, so no edge is reached twice through its target node.
- When a bind does happen the cost is one `SQLMatchFilter.copy()` (a stream over a two- or three-element list) plus a `setClassName` / `setFilter` pair. That is a handful of allocations per constrained hop per compilation, against a walk that has just built the entire `MatchPlanInputs` AST.

SCALE CHECK: the pass reads in-memory maps sized by the traversal, so the verdict is the same at 100 and at 1M records: negligible. Not reported.

#### C4 The A3 `w AND w` double composition reaches this implementation — REFUTED

CLAIM: the track's Phase-A finding A3 warned that a pattern walk composing `finalAliasFilters` onto path items would leave a filtered alias carrying `w AND w`, evaluated twice per row. The step ships that walk, so the shape should be present.

REFUTATION on two independent grounds.

First, the site chosen avoids the ordering A3 described. A3 was about placing the walk at `MatchExecutionPlanner.rebindFilters` (`:2064`), where on the SQL path `aliasFilters` is non-empty and the pattern's items are the statement AST, so a merge running after the existing overwrite would compose the consolidated clause onto the one just installed. The implementer put the walk in `GremlinStepWalker.buildResult` instead, on the Gremlin-built pattern only, before the planner ever sees it. The planner's own `rebindFilters` iterates `matchExpressions`, which the additive `MatchPlanInputs` constructor leaves empty (`MatchExecutionPlanner.java:550`), so its loop runs zero times on this path and cannot overwrite or re-compose anything.

Second, the AND-compose branch inside the new pass (`GremlinStepWalker.java:648-651`) is unreachable from any traversal the translator currently accepts, so nothing can be composed twice even in principle. The reason is that a positive path item never carries a `WHERE` of its own on an alias that also appears in `finalAliasFilters`:

- `WalkerContext.addNode` passes `where = null` (line 411) and `WalkerContext.addEdge` passes `edgeFilter = null` (line 420); `SubTraversalPredicateAdapter` does the same (lines 247, 256). So the pattern builder's own `aliasFilters` is always empty on the Gremlin path and `ir.aliasFilters()` contributes nothing to the merge — `finalAliasFilters` is `ctx.aliasFilters` alone.
- The only positive item that carries a `WHERE` is the edge-alias item produced by `addEdgeAsNode`. Every `putAliasFilter` call in the strategy package is keyed on a vertex boundary or on a child's captured vertex aliases (`HasStepRecogniser:169`, `WherePredicateStepRecogniser:55`, `OrStepRecogniser:45`, `NotStepRecogniser:54,73`, `TraversalFilterStepRecogniser:59`, `StartStepRecogniser:135`, `ConnectiveStepSupport:57,71`); edge aliases only ever reach `putEdgeFilter` (`EdgeHopRecogniser:139`). So an edge-alias item's target alias is never a key in `finalAliasFilters`, and the compose branch never fires.

The step's own Javadoc says as much ("the translator cannot currently build a traversal that reaches them") and the three preservation cases are covered by direct unit tests rather than by traversals, which is the right call.

RESIDUE: the branch has no de-duplication, so the day a recogniser starts registering an edge-alias predicate in `ctx.aliasFilters` — or a future site composes the same clause into both maps — the compose will fire with no guard. That is a latent shape, not a cost today.

SCALE CHECK: no cost at any scale under the current recogniser set. Not reported.

#### C5 The bound `WHERE` pollutes the Gremlin plan-cache key with literal values — REFUTED

CLAIM: `GremlinPlanFingerprint.appendPathItemStructural` renders a path item with `item.toString(NO_PARAMS, sb)` (line 97), the value-bearing rendering, whereas `appendAliasFilters` uses the value-independent `toGenericStatement` (line 114). Putting the `WHERE` on the path item therefore drags predicate values into the cache key, so every distinct value would miss the cache and force a full `MatchExecutionPlanner` run — and would evict other entries while doing it.

REFUTATION: predicate values are not literals in the emitted AST. `GremlinPredicateAdapter.valueExpression` (line 437) routes values through the `ParamSink`, which mints a `SQLPositionalParameter`; `MatchLiteralBuilder.toLiteral` is the fallback only when no sink is supplied, and `HasStepRecogniser` always supplies one (line 131). `SQLPositionalParameter.toString(params, builder)` calls `bindFromInputParams(params)`, and with `NO_PARAMS` being `Collections.emptyMap()` the lookup misses and `toParsedTree(null)` returns an `isNull` `SQLExpression`, which renders the same text for every slot and every value. The key stays value-independent.

The one family that renders verbatim is RIDs, which `ParamSink`'s own Javadoc keeps inline as structural tokens. Those traversals do not reach the cache at all: `buildResult` passes `!ctx.ridBearing()` as the cache-eligibility flag (`GremlinStepWalker.java:550`).

RESIDUE, quantified and dismissed: the same `WHERE` is now rendered twice per fingerprint — once through `appendPathItemStructural`, once through `appendAliasFilters` — and each positional parameter allocates a throwaway `SQLExpression` inside `toParsedTree`. The fingerprint is computed on every execution, before the cache lookup, so this is per-query work on the hit path. It amounts to one extra scratch `StringBuilder`, one `SQLMatchFilter.toString` and a couple of small objects, against a hit path that also deep-copies the whole execution plan through `InternalExecutionPlan.copy`.

SCALE CHECK: does not grow with graph size; sub-microsecond against a microsecond-scale plan copy. Negligible. Not reported.

#### C6 The per-candidate class check duplicates the collection-ID pre-filter — REFUTED

CLAIM: `MatchExecutionPlanner.stampEdgeMetadata` already stamps each edge with the accepted collection IDs for the target alias's class (lines 3051-3061), and `MatchEdgeTraverser.applyPreFilter` uses them to drop non-matching RIDs from the link bag with zero I/O (line 729). Binding the class onto the path item therefore makes the executor re-derive, per surviving candidate, a fact the link-bag filter has already enforced.

REFUTATION: the duplication is real but conditional, and the conditional arm is what makes the item binding load-bearing.

- `withClassFilter` only runs when the raw traversal result is a `PreFilterableLinkBagIterable` (line 723). Any other result shape — and the equivalence tests that failed before this fix are evidence such shapes occur — reaches no collection-ID filter at all.
- The stamp is skipped when `schema.getClassInternal(className)` returns null, and `applyPreFilter` returns early when `edge` is null (the raw-item traverser constructor at `MatchEdgeTraverser.java:174`).
- Where both do apply, the redundant work is `matchesClassCached`, which memoises on `(className, collectionId)` and collapses to two field comparisons after the first candidate in a cluster (lines 526-528). The `filter` body's context churn around it would be paid anyway whenever the item also carries a `WHERE`, which is the case for every hop that has a predicate.

So the binding cannot be dropped, and what remains is a memoised comparison on candidates that were going to be streamed through a filter regardless.

SCALE CHECK: at 1M candidates the duplicated arm is a memo hit per row. Negligible against the link-bag iteration it rides on. Not reported.

#### C7 The binding degrades root selection, prefetch, or NOT cardinality estimation — REFUTED

CLAIM: pushing a class and a `WHERE` onto path items changes what the planner sees, so root selection, the prefetch set, or the hash-anti-join threshold decision could flip to a worse plan.

REFUTATION, taken one consumer at a time.

Root selection and prefetch read the alias maps, not the items. `estimateRootEntries` (`MatchExecutionPlanner.java:624`) and the `aliasesToPrefetch` filter (lines 636-641) both consume `aliasClasses` and `aliasFilters`. The diff does not touch how `finalAliasFilters` is built — the merge loop above the new call is pre-existing — so both are byte-identical to before the change. Whatever prefetch behaviour these shapes had, they still have.

Reverse-scheduled edges ignore the binding entirely. `MatchReverseEdgeTraverser` overrides `targetClassName`, `targetRid` and `getTargetFilter` to read `EdgeTraversal`'s left-side constraints (lines 52-68), so a hop the scheduler decides to walk backwards never reads the item's newly bound class or `WHERE`. That is a correctness question for another dimension; here it means the binding adds no cost on those edges.

NOT cardinality estimation does change, and the direction is neutral to favourable. `estimateNotPatternCardinality` advances `currentClass` from each item's class (lines 1116-1118). Before the change a Gremlin NOT item carried none, so a second hop's fan-out was estimated against the origin's class; now it is estimated against the hop target's actual class, or against `V` when the hop is unlabelled. Both are more faithful than the origin class. Where it lands on `V`, `count(V)` is the largest plausible source count, so the fan-out ratio shrinks, the estimate shrinks, and `canUseHashJoin` becomes *more* likely to pick the materialised anti-join over the per-row nested loop. That is the cheaper plan.

The two `filter.getClassName(context)` calls in that loop (lines 1116-1117 evaluate it twice) allocate at plan time, once per item, on a path whose result is cached. Not worth reporting.

SCALE CHECK: no plan-quality regression identified at any scale. Not reported.
