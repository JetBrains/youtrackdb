<!-- MANIFEST
findings: 10   severity: {blocker: 1, should-fix: 8, suggestion: 1}
index:
  - {id: T1, sev: blocker,    loc: MatchPatternBuilder.java:121-158 / SQLMatchPathItem.java:22,87-162, anchor: "### T1 ", cert: P3+P4+P5, basis: "edge filtering rides on a nonexistent edgeAlias param and a target-vertex filter slot; outE.has.inV cannot be expressed by the current single-path-item addEdge"}
  - {id: T2, sev: should-fix, loc: track-3.md:83 / MatchPatternBuilder.java:121-128, anchor: "### T2 ", cert: P3, basis: "documented addEdge signature is 8-arg with edgeAlias; actual is 7-arg without it"}
  - {id: T3, sev: should-fix, loc: track-3.md:47 / design.md:1570-1583 / StartStepRecogniser.java:148-160, anchor: "### T3 ", cert: P4+P15, basis: "chain-target @class='V' narrowing on bare out(label) under polymorphic=false reintroduces BC2 subclass undercount"}
  - {id: T4, sev: should-fix, loc: track-3.md:48,52 / GremlinStepWalker.java:79,109-139 / StepRecogniser.java, anchor: "### T4 ", cert: P7+P8, basis: "index-driven refactor under-specified: MAX_RECOGNISED_STEPS=1 gate, for-each re-dispatch of consumed steps, boolean contract has no consumed-count channel"}
  - {id: T5, sev: should-fix, loc: track-3.md:47 / GremlinStepWalker.java:168-192 / StartStepRecogniser.java:169-171, anchor: "### T5 ", cert: P9, basis: "chain hops must replace returnItems + re-pin boundaryAlias; walker builds from ctx.returnItems as-is and start recogniser appends one"}
  - {id: T6, sev: should-fix, loc: track-3.md:47-48 / plan D9, anchor: "### T6 ", cert: P10+P12, basis: "D9 exact-class dispatch: folded out(L) is produced as VertexStepPlaceholder; concrete-vs-placeholder class at provider-strategy time unconfirmed"}
  - {id: T7, sev: should-fix, loc: track-3.md:48 / design.md:1213-1216, anchor: "### T7 ", cert: P13, basis: "otherV() closing step is EdgeOtherVertexStep, not a VertexStep; peek matcher must key on it"}
  - {id: T8, sev: should-fix, loc: track-3.md Plan of Work / design.md:1627-1635 / GremlinStepWalker.java:102-127, anchor: "### T8 ", cert: P14, basis: "reserved-$ user-label pre-flight deferred from Track 2 is absent from the walker and unlisted in Track 3 scope, yet Track 3 is first to mint $g2m_ aliases"}
  - {id: T9, sev: should-fix, loc: track-3.md:48-49 / design.md:1209-1216, anchor: "### T9 ", cert: P5b, basis: "LazyBarrierStrategy may inject NoOpBarrierStep between edge step and closing hop; edge peek-ahead must skip interleaved barriers or it declines incorrectly"}
  - {id: T10, sev: suggestion, loc: track-3.md:9,82, anchor: "### T10 ", cert: P6, basis: "dependency line claims anon-alias generator from Track 2 (deferred to Track 3); Purpose lists polymorphic as a new WalkerContext field though it already exists"}
evidence_base: {section: "## Evidence base", certs: 16, matches: 9}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: WRONG, anchor: "#### P3 "}
  - {id: P4, verdict: WRONG, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P5b, verdict: PARTIAL, anchor: "#### P5b "}
  - {id: P6, verdict: WRONG, anchor: "#### P6 "}
  - {id: P7, verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: P8, verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: P9, verdict: CONFIRMED, anchor: "#### P9 "}
  - {id: P10, verdict: CONFIRMED, anchor: "#### P10 "}
  - {id: P11, verdict: CONFIRMED, anchor: "#### P11 "}
  - {id: P12, verdict: PARTIAL, anchor: "#### P12 "}
  - {id: P13, verdict: CONFIRMED, anchor: "#### P13 "}
  - {id: P14, verdict: CONFIRMED, anchor: "#### P14 "}
  - {id: P15, verdict: CONFIRMED, anchor: "#### P15 "}
flags: [CONTRACT_OK]
-->

# Track 3 — Technical review (iteration 1)

Track 3's folded-edge half (`out`/`in`/`both` via `VertexStepRecogniser`) is
feasible on the existing builder, but its headline feature — non-adjacent edge
filtering — rests on an IR mechanism that does not exist. `MatchPatternBuilder.addEdge`
has no `edgeAlias` parameter, and `SQLMatchPathItem.filter` filters the *target
vertex*, not the edge, so `outE(L).has(...).inV()` cannot be expressed by the
single-path-item `addEdge` the track and design both name. One blocker, eight
should-fix, one suggestion. PSI/`execute_code` timed out every attempt this
session; class existence and signatures were verified by reading source and the
`io.youtrackdb:gremlin-core` fork jar bytecode, with a reference-accuracy caveat
noted on the two dispatch-class findings (T6).

## Findings

### T1 [blocker]
**Certificate**: P3 (addEdge signature) + P4 (SQLMatchPathItem.filter is a target-vertex filter) + P5 (edge-node form exists in the executor)
**Location**: track-3.md `## Context and Orientation` (lines 31, 83) and `## Plan of Work` item 2 (line 48); design.md §"Edge filtering in non-adjacent chains" lines 1217-1221; `MatchPatternBuilder.java:121-158`; `SQLMatchPathItem.java:22, 87-162`
**Issue**: The track's central claim is that edge filtering "needs no executor or planner change — only translator-side peek-ahead. `MatchExecutionPlanner` consumes `addEdge(from, to, dir, label, edgeAlias, edgeFilter, …)` output unchanged" (line 31), with design step 4 adding "The builder parks the edge alias and filter on the `SQLMatchPathItem.filter` slot — the IR already supports edge-side filters." The code contradicts this on every clause:
- The existing `addEdge` (7 params: `fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth`) has **no `edgeAlias` parameter** (P3).
- Its `edgeFilter` argument is attached to the **target vertex**, not the edge: `toFilter = SQLMatchFilter.fromAliasAndClass(toAlias, null); toFilter.setFilter(edgeFilter); pathItem.setFilter(toFilter)`. The `addEdge` Javadoc states this verbatim ("`edgeFilter` is attached as the path item's target-vertex filter").
- `SQLMatchPathItem.filter` is a `SQLMatchFilter` describing the **target node** of the hop (its `alias`, `className`, WHERE). `executeTraversal` runs `method.execute(startingPoint, …)` — an `out(L)` hop that returns adjacent *vertices* — then filters those vertices by `oClass`/`filter`. There is no edge-property predicate anywhere on an `out(L)` path item (P4).

A single `out(L)` path-item fundamentally cannot filter edge properties. Native MATCH filters an edge by node-izing it: `.outE(L){as: e, where: (…)}.inV(){as: v}` — two path items, the first with method `outE` (returns edges) carrying the edge's own `SQLMatchFilter`, the second with method `inV` reaching the target vertex. `SQLMethodCall` supports `outE`/`inE`/`bothE`/`outV`/`inV`/`bothV` (P5), so the *executor* can run this form — but the *builder* cannot emit it, and `SQLMatchFilter` has no edge slot to hijack (P4). The consequence: an `EdgeStepRecogniser` built as planned would put the `has(...)` predicate on the target vertex's WHERE, filtering the wrong element — `EdgeTraversalEquivalenceTest`'s `outE(L).has(edgeProp).inV()` cases would return a wrong multiset (target vertices generally lack the edge property → empty or divergent result). The track fails during execution on its headline feature.
**Proposed fix**: Reframe the edge-filter mechanism around the edge-as-node form and correct both the track and (via replanning, since design records are immutable) the design:
1. Add a builder capability that emits the two-path-item `outE(L){as: $g2m_edge_N, where: <edge WHERE>}.inV(){as: $g2m_anon_M}` shape — either a new `MatchPatternBuilder` method or the in-scope `GremlinPatternAssembler` using lower-level `Pattern`/`SQLMatchExpression`/`SQLMatchPathItem` APIs (two `SQLMatchPathItem`s, first `outPath`-style but with method name `outE` and an edge-alias `SQLMatchFilter`, second `inV`). The "no builder change / addEdge output unchanged" framing is false and must be dropped.
2. Confirm `MatchExecutionPlanner` plans a pattern with an edge-aliased *intermediate* node (source → edge-node → target). The executor supports the method calls, but the translated planning path for an edge-aliased node is unverified; add a premise test before relying on it. If the planner does not handle the edge-node form, edge filtering is out of scope for Track 3 and must be deferred.
3. The folded `out(L)` half (no edge filter) is unaffected — the existing 7-arg `addEdge(from, to, dir, label, null, null, null)` plus `addNode(target, "V", …)` works for it (see T5 for the return re-pinning it still needs).

### T2 [should-fix]
**Certificate**: P3
**Location**: track-3.md `## Interfaces and Dependencies` → Signatures (line 83); `MatchPatternBuilder.java:121-128`
**Issue**: The Signatures line documents `MatchPatternBuilder.addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)` — an 8-arg signature with `edgeAlias`. The actual method is 7-arg: `addEdge(fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth)`, no `edgeAlias`. Track 1's Step 3 episode already recorded the real signature; the track file drifted. This is the surface symptom of T1, but it is also a standalone NAMED-REFERENCE inaccuracy: `addEdge` calls written against the documented 8-arg form will not compile.
**Proposed fix**: Either correct the Signatures line to the real 7-arg shape (if edge filtering is descoped) or, under T1's fix, document the new edge-node builder method's real signature. Do not carry an 8-arg `addEdge` that does not exist.

### T3 [should-fix]
**Certificate**: P4 + P15
**Location**: track-3.md `## Plan of Work` item 1 (line 47); design.md §"Schema polymorphism" lines 1570-1583; `StartStepRecogniser.java:148-160`
**Issue**: Item 1 has `VertexStepRecogniser` "apply the same `@class = '<className>'` … narrowing as the start recogniser via the shared `MatchClassFilters` helper, so chain targets inherit non-polymorphic narrowing," and design lines 1570-1583 instruct the same for each `out(label)`/`in(label)` hop under `polymorphic=false`. Two problems:
- The start recogniser applies **no** `@class` narrowing (the BC2 fix removed it — `StartStepRecogniser.java:157-160` roots at class `V` polymorphically and comments "Emitting `@class = 'V'` would wrongly exclude subclass instances"). "The same narrowing as the start recogniser" is therefore *no narrowing*; the wording describes behavior that does not exist.
- Worse, if implemented literally: a bare `out(label)` hop introduces a target with the generic root class `"V"` (item 1: `addNode($g2m_anon_M, "V", null, false)`). Narrowing that target to `@class = 'V'` under `polymorphic=false` excludes subclass instances (Person/Place) exactly as BC2 did on the start node. Native `out(label)` applies no class filter to the edge-hop target, so any `@class` narrowing on a bare hop undercounts versus native — the `polymorphic=false` gate is *when* the undercount fires, not a guard against it. The planned `EdgeTraversalEquivalenceTest` case "polymorphic=false narrows chain-target nodes … no result-set discrepancy" would fail on a Person-subclass-of-V fixture.
**Proposed fix**: Restrict `@class` narrowing to hops carrying an *explicit* user-named class (the Track 4 `hasLabel` case), never a bare `out(label)` whose target class is the generic `"V"`. State this in the track (and reconcile design §"Schema polymorphism" via replanning): bare chain-hop targets root at `"V"` polymorphically like the start node, with no `@class` filter regardless of the polymorphic flag. Add an equivalence case with a subclassed vertex schema (Person extends V) run under `polymorphic=false` to pin it.

### T4 [should-fix]
**Certificate**: P7 + P8
**Location**: track-3.md `## Plan of Work` items 2 and 6 (lines 48, 52); `GremlinStepWalker.java:79, 109-139`; `StepRecogniser.java`
**Issue**: The D10 "for-each → index-driven" refactor is named but three concrete obstacles are unstated:
- `GremlinStepWalker.MAX_RECOGNISED_STEPS = 1` (line 79) declines any traversal with more than one step *before any recogniser runs* (line 110). Every `g.V().out(...)` is ≥2 steps, so unless this bound is raised/removed Track 3 translates nothing.
- The current loop is a `for (Step step : steps)` that dispatches every step and does `ctx.stepIndex++` after each (lines 129-138). A multi-step recogniser keyed on the first step (`outE`) that also "consumes" the following `has`/`inV` cannot prevent the for-each from re-dispatching those same steps next iteration (they would hit no recogniser → decline). Index-driven iteration (`while (i < steps.size())`) is mandatory, not cosmetic.
- `StepRecogniser.recognize` returns `boolean` with no channel to report how many steps were consumed, and the walker — not the recogniser — currently owns the advance. Track 3 must change the contract (return a consumed-count, or have recognisers set `ctx.stepIndex` and drop the walker's `++`) and update `StartStepRecogniser` (single-step, currently relies on the walker's `++`) to the new contract.
**Proposed fix**: Add a decomposition step that (a) raises/removes `MAX_RECOGNISED_STEPS`, (b) converts the loop to index-driven, (c) picks and documents the consumed-count contract on `StepRecogniser`, and (d) updates `StartStepRecogniser` and any walker invariant asserts to match. Keep the all-or-nothing decline and no-mutation-on-decline guarantees intact across the rewrite.

### T5 [should-fix]
**Certificate**: P9
**Location**: track-3.md `## Plan of Work` item 1 (line 47); `GremlinStepWalker.java:168-192` (`buildResult`); `StartStepRecogniser.java:148-171`
**Issue**: For `g.V().out("knows")` the result is the *target* vertices, so `boundaryAlias` and the RETURN projection must point at the last hop's target (`$g2m_anon_M`), not the start node. But `StartStepRecogniser` pins `boundaryAlias = $g2m_v0` and **appends** one entry to `ctx.returnItems`/`returnAliases`/`returnNestedProjections` (lines 149, 169-171), and `buildResult` packages `ctx.returnItems` as-is. If `VertexStepRecogniser` appends a second return item for `$g2m_anon_M`, the plan emits two columns and the single-alias boundary step reads the wrong/extra column; if it appends nothing, the plan returns the start vertex. Neither matches native. The track and design do not state that a chain hop must *replace* the return projection and re-pin `boundaryAlias`.
**Proposed fix**: Specify that each terminator-advancing recogniser clears/replaces the single return item (and `boundaryAlias`) to name its new target alias, so exactly one RETURN column survives, keyed on the final hop's alias. Add a walker/recogniser test asserting a two-hop traversal yields one return column pointing at the last target.

### T6 [should-fix]
**Certificate**: P10 + P12
**Location**: track-3.md `## Plan of Work` items 1-2 (lines 47-48); plan D9 (class-keyed dispatch)
**Issue**: D9 dispatches on the step's **exact** runtime class. `IncidentToAdjacentStrategy.optimizeSteps` replaces `outE(L).inV()` with a `VertexStepPlaceholder` (a `GValueHolder`), not a plain `VertexStep` (P10). Whether that placeholder is reduced to a concrete `VertexStep` before `GremlinToMatchStrategy` (a `ProviderOptimizationStrategy`) runs is unconfirmed: `GValueReductionStrategy` is in the *optimization* category and `ProviderGValueReductionStrategy` in the *provider* category — the very existence of a provider-category reducer means placeholders can still be live when provider strategies run, and the ordering of our strategy versus that reducer is unverified (P12). The track file never states which class `VertexStepRecogniser`/`EdgeStepRecogniser` key on. This is the same class-key trap that produced Track 2's blocker T1/A1 (keying on `YTDBGraphStep` would have declined everything). I could not settle it empirically — `execute_code` timed out on every attempt this session.
**Proposed fix**: During decomposition, empirically print the post-`applyStrategies()` step classes for `g.V().out(L)`, `g.V().outE(L).has(...).inV()`, and `g.V().bothE(L).otherV()` against a real YTDB graph, and register recognisers under the observed classes. Defensively, register both `VertexStep` and `VertexStepPlaceholder` (both implement `VertexStepContract`), or add `ProviderGValueReductionStrategy` to `GremlinToMatchStrategy.applyPrior()` so placeholders are always concrete at our strategy's time. Add a regression test that fails if the registry key stops matching the real folded class.

### T7 [should-fix]
**Certificate**: P13
**Location**: track-3.md `## Context and Orientation` (line 48) and `## Plan of Work` item 2 (line 48); design.md lines 1213-1216
**Issue**: The track and design call the closing step of the `both` shape an "`otherV`-form `VertexStep`" / "`VertexStep(otherV)`". `otherV()` produces `EdgeOtherVertexStep` (confirmed in the fork jar), a distinct class from `VertexStep` and from `EdgeVertexStep` (which backs `inV`/`outV`). Under exact-class dispatch (D9, and the peek-ahead's closing-step match), mislabeling it as `VertexStep` would make the peek fail to recognise the `both` chain's closing hop.
**Proposed fix**: Correct the peek-ahead's closing-step matcher to key on `EdgeVertexStep` (out/in) and `EdgeOtherVertexStep` (both). Fix the "VertexStep(otherV)" wording in the track (and design via replanning).

### T8 [should-fix]
**Certificate**: P14
**Location**: track-3.md `## Plan of Work` (no item covers it); design.md §"Anonymous alias generation" lines 1627-1635; `GremlinStepWalker.java:102-127`
**Issue**: Design specifies a walker pre-flight that scans every step's `getLabels()` and declines the whole traversal if any user label starts with `$`, protecting the minted `$g2m_edge_N`/`$g2m_anon_M` namespace from collision. Track 2 explicitly deferred "the generator and its reserved-`$`-label collision pre-flight" to Track 3. The current walker has no such scan (only the size gate and polymorphism resolution, lines 102-127), and Track 3's Plan of Work never lists it — yet Track 3 is the first track to mint `$`-prefixed aliases, so the collision surface opens here. A user `as("$g2m_anon_0")` would collide silently.
**Proposed fix**: Add the reserved-`$` user-label pre-flight to the walker as part of the index-driven refactor step, run before recogniser dispatch, declining (not throwing) on any `$`-prefixed user label, per design §"Anonymous alias generation". List it in Track 3's scope and cover it with a decline test.

### T9 [should-fix]
**Certificate**: P5b
**Location**: track-3.md `## Plan of Work` items 2-3 (lines 48-49); design.md §"Edge filtering" lines 1209-1216
**Issue**: `NoOpBarrierStep` is injected by `LazyBarrierStrategy`, which runs before our strategy (plan Constraints). Track 3 adds a top-level `NoOpBarrierRecogniser` for barriers *between* recognised steps, but the `EdgeStepRecogniser` peek-ahead (design steps 2-3: "for each adjacent `HasStep` … stops when it sees `EdgeVertexStep`") does not account for a `NoOpBarrierStep` landing *inside* the `outE … has … inV` chain. If a barrier interleaves between the `has` and the closing `inV`, the peek sees a non-`HasStep`, non-closing step and declines the whole traversal (a false decline), losing the optimization on exactly the LDBC-IC2-style shape the track targets.
**Proposed fix**: Make the peek-ahead skip `NoOpBarrierStep` while scanning for `HasStep`/closing hops (treat it as transparent inside the chain), consistent with the top-level `NoOpBarrierRecogniser`. Add a peek test with an interleaved `NoOpBarrierStep` (or confirm empirically that `LazyBarrierStrategy` never injects inside this chain, and document that).

### T10 [suggestion]
**Certificate**: P6
**Location**: track-3.md `## Purpose / Big Picture` (line 9) and `## Interfaces and Dependencies` (line 82)
**Issue**: Two stale-plan wordings. (1) The dependency line "depends on Track 2 (walker, registry, boundary, anon-alias generator)" implies the anon-alias generator exists from Track 2; Track 2 deferred it (its Decision Log scope-down) and Track 3 builds it. (2) Purpose (line 9) and Plan item 6 list `polymorphic` as a *new* `WalkerContext` field, but `WalkerContext.polymorphic` already exists (`WalkerContext.java:86`, added in Track 2 after the BC2 reconciliation); Track 3 only adds the chain-target *read*.
**Proposed fix**: Reword to "Track 3 introduces the anon-alias generator (deferred from Track 2)" and "reads the existing `WalkerContext.polymorphic` for chain targets; adds `edgeFilters` and the anon-edge-alias counter as new fields." Documentation-only; no scope change.

## Evidence base

#### P1 Named production classes resolve
- **Track claim**: The track names `MatchPatternBuilder`, `SQLMatchPathItem`, `MatchExecutionPlanner`, `GremlinStepWalker`, `WalkerContext`, `YTDBMatchPlanStep`, `StepRecogniser`, `StartStepRecogniser`, `MatchPlanInputs`, `YTDBStrategyUtil`, `BoundaryOutputType`, `IncidentToAdjacentStrategy`, and TinkerPop `VertexStep`/`HasStep`/`EdgeVertexStep`/`NoOpBarrierStep`/`LazyBarrierStrategy`.
- **Search performed**: `find -name '<Class>.java'` for YTDB source; `unzip -l` + `javap` on `io.youtrackdb:gremlin-core-3.8.1-af9db90-SNAPSHOT.jar` for fork classes. (PSI `findClass` timed out every attempt — grep/jar fallback with reference-accuracy caveat.)
- **Code location**: all YTDB classes under `core/src/main/java/.../sql/executor/match/`, `.../gremlin/translator/{strategy,step}/`, `.../gremlin/traversal/strategy/`; fork classes under `org/apache/tinkerpop/gremlin/process/traversal/{step,strategy}/` in the jar.
- **Actual behavior**: every named class located exactly once in the main tree / fork jar (duplicate hits were only `.claude/worktrees/*` copies).
- **Verdict**: CONFIRMED
- **Detail**: single unambiguous match per name; packages match the reconstructed FQNs.

#### P2 Planned-new classes are absent (created by this track)
- **Track claim**: `## Interfaces and Dependencies` marks `VertexStepRecogniser`, `EdgeStepRecogniser`, `NoOpBarrierRecogniser`, `MatchClassFilters`, `GremlinPatternAssembler`, `GremlinPredicateAdapter`, `EdgeTraversalEquivalenceTest` as in-scope new.
- **Search performed**: `find -name '<Class>.java'`.
- **Code location**: NOT FOUND (as expected).
- **Actual behavior**: none present in the main tree. `AnonAliasGenerator` and `GremlinPlanCache` are also absent (deferred from Track 2 / to Track 4).
- **Verdict**: CONFIRMED
- **Detail**: planned by this track; no name collision with existing code.

#### P3 MatchPatternBuilder.addEdge signature
- **Track claim**: `addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)` — 8 params incl. `edgeAlias`.
- **Search performed**: Read `MatchPatternBuilder.java`.
- **Code location**: `MatchPatternBuilder.java:121-128`.
- **Actual behavior**: `public MatchPatternBuilder addEdge(@Nonnull String fromAlias, @Nonnull String toAlias, @Nonnull Direction dir, String edgeLabel, SQLWhereClause edgeFilter, SQLWhereClause whileCondition, Integer maxDepth)` — 7 params, no `edgeAlias`. `whileCondition`/`maxDepth` non-null → `UnsupportedOperationException`.
- **Verdict**: WRONG
- **Detail**: no `edgeAlias`; the documented 8-arg call would not compile. Backs T1, T2.

#### P4 SQLMatchPathItem.filter is a target-vertex filter, not an edge filter
- **Track claim**: "The MATCH IR already supports edge-side filters via `SQLMatchPathItem.filter`" (line 31); design "parks the edge alias and filter on the `SQLMatchPathItem.filter` slot."
- **Search performed**: Read `MatchPatternBuilder.addEdge`, `SQLMatchPathItem` (fields + `executeTraversal`), `SQLMatchFilter` fields.
- **Code location**: `SQLMatchPathItem.java:22` (`protected SQLMatchFilter filter`), `87-162` (`executeTraversal`); `MatchPatternBuilder.java:136-150`; `SQLMatchFilter.java:14-268`.
- **Actual behavior**: `addEdge` sets the target `SQLMatchFilter` (`toFilter.setFilter(edgeFilter); pathItem.setFilter(toFilter)`). `executeTraversal` runs `method.execute(startingPoint,…)` (the `out(L)` hop → adjacent vertices) then filters those vertices by `oClass`/`filter`. `SQLMatchFilter` fields are `alias`/`filter`/`className`/`whileCondition`/`maxDepth`/`optional` — no edge slot. `SQLMatchPathItem.filter` describes the node reached by the hop.
- **Verdict**: WRONG
- **Detail**: a single `out(L)` path item has no edge-property predicate; the filter slot is the target vertex's WHERE. Backs T1, T3.

#### P5 The edge-as-node form is supported by the executor (SQLMethodCall)
- **Track claim**: implicit — edge filtering relies on MATCH's edge traversal.
- **Search performed**: grep `SQLMethodCall.java` for edge method names.
- **Code location**: `SQLMethodCall.java:30` — `Arrays.asList("out","in","both","outE","inE","bothE","bothV","outV","inV")`; grammar `MultiMatchPathItem` (YouTrackDBSql.jjt:3458-3474).
- **Actual behavior**: `outE`/`inE`/`bothE`/`outV`/`inV`/`bothV` are recognised method names, and the grammar chains multiple path items — so `.outE(L){as:e,where}.inV()` is expressible and executable in native MATCH.
- **Verdict**: CONFIRMED
- **Detail**: the executor supports edge-node traversal; the gap is builder-side (P3/P4), and the *translated planner* path for an edge-aliased intermediate node still needs a premise test (T1 fix step 2).

#### P5b NoOpBarrierStep / LazyBarrierStrategy exist and may interleave
- **Track claim**: `NoOpBarrierRecogniser` claims `NoOpBarrierStep` injected by `LazyBarrierStrategy`.
- **Search performed**: `unzip -l` on the fork jar.
- **Code location**: `.../step/map/NoOpBarrierStep.class`, `.../strategy/optimization/LazyBarrierStrategy.class`.
- **Actual behavior**: both classes present; `LazyBarrierStrategy` is an optimization-category strategy that runs before provider strategies (plan Constraints). Whether it injects *inside* an `outE…has…inV` chain was not exercised.
- **Verdict**: PARTIAL
- **Detail**: existence confirmed; interleaving inside the peeked chain is an untested interaction. Backs T9.

#### P6 WalkerContext.polymorphic already exists
- **Track claim**: Purpose/Plan list `polymorphic` as a new `WalkerContext` field.
- **Search performed**: Read `WalkerContext.java`.
- **Code location**: `WalkerContext.java:86` (`final boolean polymorphic`), `93-96` (ctor), resolved by `GremlinStepWalker.java:121-127`.
- **Actual behavior**: the field exists, resolved once by the walker from `YTDBStrategyUtil.isPolymorphic` and passed to the ctor; a null resolution declines the walk.
- **Verdict**: WRONG
- **Detail**: not new; Track 3 adds only the chain-target read. Backs T10.

#### P7 GremlinStepWalker is for-each with a size-1 gate
- **Track claim**: D10 for-each → index-driven refactor.
- **Search performed**: Read `GremlinStepWalker.java`.
- **Code location**: `GremlinStepWalker.java:79` (`MAX_RECOGNISED_STEPS = 1`), `110` (decline if `size > MAX_RECOGNISED_STEPS`), `129-139` (`for (Step step : steps) { … ctx.stepIndex++; }`).
- **Actual behavior**: a hard size-1 gate rejects multi-step traversals up front; the loop is for-each and the walker owns the per-step `++`.
- **Verdict**: CONFIRMED
- **Detail**: the refactor must raise the gate and change the loop, else nothing multi-step translates. Backs T4.

#### P8 StepRecogniser.recognize returns boolean; walker owns the advance
- **Track claim**: recognisers "advance `ctx.stepIndex` past every consumed step."
- **Search performed**: Read `StepRecogniser.java`, `GremlinStepWalker.java`, `StartStepRecogniser.java`.
- **Code location**: `StepRecogniser.java` (`boolean recognize(Step, WalkerContext)`); walker `ctx.stepIndex++` at `GremlinStepWalker.java:138`; `StartStepRecogniser` reads `ctx.stepIndex` (line 111) but never advances it.
- **Actual behavior**: no consumed-count return channel; the walker, not the recogniser, advances the index.
- **Verdict**: CONFIRMED
- **Detail**: multi-step claims require a contract change (return count or recogniser-owned advance) and a `StartStepRecogniser` update. Backs T4.

#### P9 StartStepRecogniser appends returnItems and pins boundaryAlias to the start node
- **Track claim**: chain hops emit the target vertex through the boundary.
- **Search performed**: Read `StartStepRecogniser.java`, `GremlinStepWalker.buildResult`.
- **Code location**: `StartStepRecogniser.java:148-171`; `GremlinStepWalker.java:168-192`.
- **Actual behavior**: sets `boundaryAlias = $g2m_v0`, appends one entry to each of `returnItems`/`returnAliases`/`returnNestedProjections`; `buildResult` packages `ctx.returnItems` unchanged.
- **Verdict**: CONFIRMED
- **Detail**: a chain hop that appends (rather than replaces) a return item yields a two-column plan; boundaryAlias is not re-pinned. Backs T5.

#### P10 IncidentToAdjacentStrategy fold contract
- **Track claim**: folds adjacent `outE(L).inV()` etc. to `out(L)` before our strategy; a `has(...)` between breaks the fold.
- **Search performed**: `javap -p -c` on `IncidentToAdjacentStrategy.class` (fork jar).
- **Code location**: `IncidentToAdjacentStrategy.isOptimizable` / `optimizeSteps`.
- **Actual behavior**: `isOptimizable` requires the current step to be a `VertexStepContract` with `returnsEdge()==true` and **empty `getLabels()`**, and the next step to be an `EdgeVertexStep` (out/in) or `EdgeOtherVertexStep` (both) of matching/opposite direction; `optimizeSteps` replaces the pair with a `VertexStepPlaceholder`. `INVALIDATING_STEP_CLASSES` present recursively skips the optimization.
- **Verdict**: CONFIRMED
- **Detail**: empty-labels requirement matches the track's decline-on-`as(label)` rule; the produced step is a placeholder (see P12).

#### P11 A has() between the edge and vertex steps prevents the fold
- **Track claim**: `outE(L).has(...).inV()` arrives as separate steps.
- **Search performed**: P10 `isOptimizable` logic + design lines 1189-1194.
- **Code location**: `IncidentToAdjacentStrategy.isOptimizable` (next-step `instanceof EdgeVertexStep/EdgeOtherVertexStep` check).
- **Actual behavior**: with a `HasStep` between the edge step and the closing hop, the next step is not an `EdgeVertexStep`/`EdgeOtherVertexStep`, so `isOptimizable` returns false and no fold fires; the steps survive as `[VertexStep(outE,L), HasStep, EdgeVertexStep(inV)]`.
- **Verdict**: CONFIRMED
- **Detail**: the track's non-adjacent-shape premise holds.

#### P12 Folded step is a VertexStepPlaceholder; reduction timing unconfirmed
- **Track claim**: the recogniser sees the folded `VertexStep`.
- **Search performed**: `javap -p` on `VertexStepPlaceholder`/`VertexStep`; `unzip -l` for `GValueReductionStrategy`/`ProviderGValueReductionStrategy`; `javap -p` on both reducers. `execute_code` empirical run timed out.
- **Code location**: `VertexStepPlaceholder` (`implements GValueHolder, VertexStepContract`, `asConcreteStep(): VertexStep`); `GValueReductionStrategy` (`implements OptimizationStrategy`, `apply` calls `GValueHolder.reduce()`, has `applyPost()`); `ProviderGValueReductionStrategy` (`implements ProviderOptimizationStrategy`).
- **Actual behavior**: the fold emits a `VertexStepPlaceholder`. A reducer exists in the optimization category and another in the provider category. Static analysis suggests reduction to a concrete `VertexStep` before provider strategies, but the ordering of `GremlinToMatchStrategy` versus `ProviderGValueReductionStrategy` (and hence the exact class at our strategy's time) is not proven.
- **Verdict**: PARTIAL
- **Detail**: D9 exact-class dispatch makes this load-bearing; must be settled empirically. Backs T6.

#### P13 otherV() is EdgeOtherVertexStep, not VertexStep
- **Track claim**: closing `both` step is an "`otherV`-form `VertexStep`".
- **Search performed**: `unzip -l` on the fork jar; P10 bytecode (both-branch `instanceof EdgeOtherVertexStep`).
- **Code location**: `.../step/map/EdgeOtherVertexStep.class`; `.../step/map/EdgeVertexStep.class`.
- **Actual behavior**: `otherV()` → `EdgeOtherVertexStep`; `inV`/`outV` → `EdgeVertexStep`. Both distinct from `VertexStep`.
- **Verdict**: CONFIRMED
- **Detail**: the peek-ahead closing-step matcher must key on `EdgeOtherVertexStep` for `both`. Backs T7.

#### P14 The reserved-$ user-label pre-flight is absent from the walker
- **Track claim (design)**: a walker pre-flight scans `getLabels()` and declines on any `$`-prefixed user label.
- **Search performed**: Read `GremlinStepWalker.walk`, `StartStepRecogniser`.
- **Code location**: `GremlinStepWalker.java:102-127` — size gate + polymorphism resolution only; no label scan anywhere.
- **Actual behavior**: no reserved-`$` scan exists; Track 2 deferred it here, and Track 3's Plan of Work does not list it.
- **Verdict**: CONFIRMED
- **Detail**: Track 3 is the first track to mint `$g2m_` aliases, so the collision guard is needed now. Backs T8.

#### P15 Design instructs @class narrowing on chain targets under polymorphic=false
- **Track claim**: design §"Schema polymorphism" — chain-target nodes inherit the same `@class = '<className>'` narrowing.
- **Search performed**: Read design.md lines 1545-1600; `StartStepRecogniser.java:148-160`; `YTDBStrategyUtil.isPolymorphic`.
- **Code location**: design.md:1570-1583; `StartStepRecogniser.java:157-160`; `YTDBStrategyUtil.java:52-69`.
- **Actual behavior**: design directs `VertexStepRecogniser` to narrow each `out(label)` target under `polymorphic=false`; the start recogniser applies no narrowing (BC2 fix), and a bare hop's target class is the generic `"V"`. `isPolymorphic` returns the `polymorphicQuery` option / session default.
- **Verdict**: CONFIRMED
- **Detail**: narrowing a generic-`"V"` chain target to `@class = 'V'` reproduces the BC2 subclass undercount. Backs T3.
