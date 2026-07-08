<!-- MANIFEST
findings: 9   severity: {blocker: 0, should-fix: 5, suggestion: 4}
index:
  - {id: R1, sev: should-fix, loc: MatchPatternBuilder.java:121, anchor: "### R1 ", cert: E2, basis: "addEdge misroutes edgeFilter to target vertex and has no edgeAlias/outE-inV shape; headline IC2 edge filter would silently filter the wrong entity"}
  - {id: R2, sev: should-fix, loc: StartStepRecogniser.java:148, anchor: "### R2 ", cert: A4, basis: "chain-target @class narrowing per plan reintroduces BC2 subclass undercount / empty result (V is abstract)"}
  - {id: R3, sev: should-fix, loc: WalkerContext.java:68, anchor: "### R3 ", cert: E3, basis: "multi-hop must re-pin boundaryAlias + rebuild returnItems to target alias; rebindBoundaryProjection absent, else boundary returns source vertices or null"}
  - {id: R4, sev: should-fix, loc: GremlinStepWalker.java:129, anchor: "### R4 ", cert: E1, basis: "for-each loop + walker-owned ctx.stepIndex++ collides with recogniser-owned N-step advance; double-advance/skip; MAX_RECOGNISED_STEPS=1 gate must be raised"}
  - {id: R5, sev: should-fix, loc: track-3.md:79, anchor: "### R5 ", cert: A5, basis: "AnonAliasGenerator (vertex+edge) and reserved-$ pre-flight scan deferred from Track 2, required here, absent from In-scope(new) and Plan of Work"}
  - {id: R6, sev: suggestion, loc: track-3.md:48, anchor: "### R6 ", cert: T2, basis: "peek-ahead that mints from stateful ctx generators before confirming closing hop violates no-mutation-on-decline (pollutes shared counters)"}
  - {id: R7, sev: suggestion, loc: design.md:1226, anchor: "### R7 ", cert: T1, basis: "LazyBarrierStrategy NoOpBarrierStep injected mid-chain would trip the peek's decline-on-non-HasStep rule; must skip barriers and test empirically"}
  - {id: R8, sev: suggestion, loc: track-3.md:53, anchor: "### R8 ", cert: T3, basis: "both()/self-loop/parallel-edge multiset equivalence vs native is an easy-to-miss trap; equivalence fixture must seed these shapes"}
  - {id: R9, sev: suggestion, loc: track-3.md:9, anchor: "### R9 ", cert: A7, basis: "stale claim: Track 2 already wired boundary ELEMENT + projectVertex; Track 3 YTDBMatchPlanStep-modified line is vestigial and may mislead"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 13}
cert_index:
  - {id: E1, verdict: "residual HIGH", anchor: "#### E1 "}
  - {id: E2, verdict: "residual HIGH", anchor: "#### E2 "}
  - {id: E3, verdict: "residual MEDIUM", anchor: "#### E3 "}
  - {id: A1, verdict: VALIDATED, anchor: "#### A1 "}
  - {id: A2, verdict: CONTRADICTED, anchor: "#### A2 "}
  - {id: A3, verdict: VALIDATED, anchor: "#### A3 "}
  - {id: A4, verdict: CONTRADICTED, anchor: "#### A4 "}
  - {id: A5, verdict: CONTRADICTED, anchor: "#### A5 "}
  - {id: A6, verdict: VALIDATED, anchor: "#### A6 "}
  - {id: A7, verdict: CONTRADICTED, anchor: "#### A7 "}
  - {id: T1, verdict: ACHIEVABLE, anchor: "#### T1 "}
  - {id: T2, verdict: DIFFICULT, anchor: "#### T2 "}
  - {id: T3, verdict: ACHIEVABLE, anchor: "#### T3 "}
flags: [CONTRACT_OK]
-->

## Findings

### R1 [should-fix]
**Certificate**: E2 (Edge-side filter construction), A3 (MATCH edge-filter support)
**Location**: Track 3 Plan of Work step 2 + `## Interfaces and Dependencies`; `core/.../sql/executor/match/builder/MatchPatternBuilder.java:121-158`
**Issue**: Track 3's headline feature — non-adjacent edge filtering (`outE(L).has(...).inV()`, LDBC IC2) — depends on `MatchPatternBuilder.addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)` parking the filter on the **edge**. The `addEdge` Track 1 actually shipped is a 7-parameter method with **no `edgeAlias`**, and its `edgeFilter` is wired to `toFilter.setFilter(edgeFilter)` — the **target-vertex** `{…}` block (lines 136-150). `SQLMatchPathItem.executeTraversal` applies that filter to the result of `method.execute` (line 203); with the `out(L)` vertex-adjacent method the builder emits (line 146), the result is target *vertices*, so the filter filters the wrong entity. The correct edge-filter shape is two path items — `.outE('L'){as: e, where: (...)}.inV(){as: t}` — verified in `MatchEdgeMethodLdbcPatternTest:70-73,132-135`; the builder has no `outE`/`inV` construction path (`graphPath` hard-codes `out`/`in`/`both`, `SQLMatchPathItem.java:32-56`). Likelihood high (the plan says "addEdge(... edgeAlias, accumulatedEdgeFilter)" as if it exists); impact: silently filters the friend Person by the edge property instead of the KNOWS edge — wrong or empty multiset. The equivalence test catches it **only** if the fixture gives edges a filterable property the target vertex lacks. `MatchPatternBuilder` is Track-1 code and is **not** in Track 3's `## Interfaces and Dependencies` "In scope (modified)" list.
**Proposed fix**: Add an explicit decomposition step to extend `MatchPatternBuilder` — add the `edgeAlias` parameter and an edge-filtered construction path that emits the `outE('L'){as: edgeAlias, where}.inV()` two-path-item form (folded `out(L)` keeps the current single-item path). List `MatchPatternBuilder` in "In scope (modified)". Add a builder assertion that the edge filter lands on the edge path item, and seed `EdgeTraversalEquivalenceTest` so the edge carries a property the endpoint vertices do not.

### R2 [should-fix]
**Certificate**: A4 (chain-target narrowing "same as start recogniser")
**Location**: Track 3 Plan of Work step 1; `core/.../gremlin/translator/strategy/StartStepRecogniser.java:145-160`
**Issue**: Plan step 1 says `VertexStepRecogniser` "applies the same `@class = '<className>'` / `@class IN [...]` narrowing as the start recogniser via the shared `MatchClassFilters` helper." Two facts break this: (a) the start recogniser applies **no** `@class` narrowing at all — the BC2 fix removed it because a bare `g.V()` returns the full polymorphic set (StartStepRecogniser.java:154-160 comment + `addNode(..., null, false)` on line 148); (b) a bare `out(L)` hop names an edge label, not a target vertex class, so the target class is the root `"V"` (plan step 1: `addNode($g2m_anon_M, "V", null, false)`). Narrowing that target to `@class = 'V'` under `polymorphic=false` either undercounts subclasses (the exact BC2 bug the Track 2 episode says binds Track 3) or, because `V` is abstract and real vertices are concrete subclasses, matches **zero** rows. Likelihood medium-high (the plan text invites a literal `MatchClassFilters` call on every hop); impact: wrong/empty multiset under `polymorphic=false`.
**Proposed fix**: `MatchClassFilters` must emit narrowing **only** when a concrete target class is explicitly named (the Track 4 `hasLabel` case), never for a bare `out`/`in`/`both` hop whose target is the polymorphic root. The bare vertex-hop target stays unnarrowed, exactly like the BC2-fixed start node. Add an `EdgeTraversalEquivalenceTest` case that runs `g.V().out(L)` under `polymorphic=false` against a graph with subclass-typed target vertices and asserts multiset equality with native (non-empty, includes subclasses).

### R3 [should-fix]
**Certificate**: E3 (Multi-hop boundary projection)
**Location**: Track 3 Plan of Work steps 1-2; `core/.../gremlin/translator/strategy/WalkerContext.java:53-76`; `core/.../gremlin/translator/step/YTDBMatchPlanStep.java:476-481`
**Issue**: The boundary step pulls the emitted vertex with `row.getVertex(boundaryAlias)` (`YTDBMatchPlanStep.projectVertex`, line 477). For a multi-hop chain the terminator is the target vertex, so the recogniser owning the closing hop must (a) re-pin `ctx.boundaryAlias` to `$g2m_anon_M` **and** (b) replace `ctx.returnItems` / `returnAliases` / `returnNestedProjections` so the RETURN projects the target alias, not `$g2m_v0`. `StartStepRecogniser` has already appended `$g2m_v0 AS $g2m_v0` (lines 169-171); if a hop re-pins `boundaryAlias` but leaves the start-node projection, `row.getVertex($g2m_anon_M)` returns null (alias absent from projection) → `projectVertex` returns null → rows drop → empty result. If it leaves `boundaryAlias` at `$g2m_v0`, the boundary returns **source** vertices. The design's `rebindBoundaryProjection(alias)` (design.md Class Design, line 214) addresses this but **does not exist** on `WalkerContext`, and the Plan of Work never mentions the re-pin. Likelihood high for any implementer who only appends nodes; impact: wrong or empty multiset while the one-boundary-step engagement assertion still passes.
**Proposed fix**: Add `rebindBoundaryProjection(alias)` to `WalkerContext` (clears + rewrites the three return lists and re-pins `boundaryAlias`), and make it a named responsibility of the terminator hop in the decomposition. Add an equivalence test asserting the returned vertices are the **targets** of `g.V().out(L)`, not the sources.

### R4 [should-fix]
**Certificate**: E1 (Walker index-driven refactor), A1 (walker is currently for-each)
**Location**: Track 3 Plan of Work step 6; `core/.../gremlin/translator/strategy/GremlinStepWalker.java:79,110,129-139`
**Issue**: The walker refactor (D10) sits on the every-traversal critical path — every Gremlin query that reaches the strategy runs `walk()`. Today the loop is a `for (Step step : steps)` foreach that unconditionally does `ctx.stepIndex++` after each successful `recognize` (lines 129-138). D10 requires a recogniser to consume N steps in one claim by advancing the index by N. If the refactor keeps a separate loop cursor while a multi-step recogniser also mutates `ctx.stepIndex`, the two desync — double-advance (skips a step → spurious decline) or under-advance (re-dispatches an already-consumed `has`/`inV` → decline or corrupt IR). `StepRecogniser.recognize` returns a bare `boolean` with no consumed-count channel (`StepRecogniser.java:54`), so the ownership of the advance is ambiguous the moment a recogniser consumes more than one step. Separately, `MAX_RECOGNISED_STEPS = 1` (line 79) declines every multi-step traversal today and must be raised/removed for variable-length chains — not mentioned in the Plan of Work. Likelihood medium (the all-or-nothing count invariant and the boundary-pinned assert at lines 153-157 catch gross miscounts, not subtle ones); impact: "a defect breaks all recognisers" per the complexity flag.
**Proposed fix**: Make exactly one party own the advance. Either (a) convert the loop to `while (ctx.stepIndex < steps.size())` with the walker advancing by 1 only when a single-step recogniser claims and each multi-step recogniser setting `ctx.stepIndex` itself (walker does not `++`), or (b) have `recognize` return the consumed-step count. Pin it with a test that a 3-step `outE(L).has().inV()` chain leaves `ctx.stepIndex == 3` and each intermediate step is dispatched exactly once. Raise `MAX_RECOGNISED_STEPS` (or replace the constant with an unbounded gate) as part of the same step.

### R5 [should-fix]
**Certificate**: A5 (AnonAliasGenerator + reserved-$ pre-flight)
**Location**: Track 3 `## Interfaces and Dependencies` "In scope (new)"; Plan of Work step 6; design.md §"Anonymous alias generation" (lines 1602-1639)
**Issue**: Track 2 explicitly deferred `AnonAliasGenerator` **and** the reserved-`$`-label pre-flight scan to Track 3 (Track 2 Decision Log "scope-down"; the class does not exist — `find` returns nothing, and the walker has no `getLabels()` pre-flight). Track 3 mints `$g2m_anon_M` (target vertices) and `$g2m_edge_N` (edges), so it needs the generator (two instances: vertex + edge) and the collision pre-flight. Yet Track 3's "In scope (new)" lists only `VertexStepRecogniser` / `EdgeStepRecogniser` / `NoOpBarrierRecogniser` / `MatchClassFilters` / `GremlinPatternAssembler` / `GremlinPredicateAdapter` / tests — no `AnonAliasGenerator` — and Plan of Work step 6 names only `anonEdgeAliasGenerator`, omitting the vertex `anonAliasGenerator` (needed for `$g2m_anon_M`, plan step 1) and the pre-flight. Without the pre-flight, a user `as("$foo")` can collide with a minted alias. Likelihood high (scope omission); impact: minting is unsafe and the vertex-alias field is unbuilt.
**Proposed fix**: Add `AnonAliasGenerator` (design.md line 239: `next()` + static `isReserved`) and the walker pre-flight `$`-label scan to the decomposition and to "In scope (new)". Wire two generator instances on `WalkerContext` (`anonAliasGenerator` for `$g2m_anon_`, `anonEdgeAliasGenerator` for `$g2m_edge_`). Add a decline test for `g.V().as("$x").out(L)`.

### R6 [suggestion]
**Certificate**: T2 (no-mutation-on-decline for stateful generators in peek)
**Location**: Track 3 Plan of Work step 2; design.md §"Recogniser dispatch" no-mutation discipline (lines 405-418)
**Issue**: `EdgeStepRecogniser` peek-ahead accumulates edge filters and mints aliases from `WalkerContext`'s stateful counters and `patternBuilder`. If it mints `$g2m_edge_N` / `$g2m_anon_M` (advancing the shared counters) or calls `addEdge` **during** the peek and then hits a decline condition (non-`HasStep` mid-chain, no closing hop, `as(label)` on the edge — Plan step 2), the counters and builder carry a partial write. The walker does not roll back, and `MatchPatternBuilder` is one-shot, so a polluted builder cannot be reused. This is the concrete failure mode of the no-mutation-on-decline invariant for a multi-step recogniser.
**Proposed fix**: The recogniser must peek read-only into locals (candidate edge filter, planned aliases, consumed-step count), decide, and only then mint from the generators, append to `edgeFilters`, call `addEdge`, and advance `ctx.stepIndex`. Add a per-recogniser unit test: a decline mid-chain leaves `ctx` — including the generator counters and `patternBuilder` — byte-for-byte unmutated (the design's canonical `decline_doesNotCommitPartialStateToOuterContext` shape).

### R7 [suggestion]
**Certificate**: T1 (equivalence fixture must cover barrier-injected shapes), E1
**Location**: design.md §"Edge filtering" peek termination (lines 1226-1236); Constraints "Recognizers see post-fold shapes" (implementation-plan.md lines 46-49)
**Issue**: `LazyBarrierStrategy` injects `NoOpBarrierStep` between recognized steps (a documented plan constraint), and Track 3 adds `NoOpBarrierRecogniser` to claim them at the top-level walk. But the `EdgeStepRecogniser` peek-ahead is a **separate** scan from `ctx.stepIndex + 1` that declines on "a non-`HasStep` between the edge step and the closing vertex step." A `NoOpBarrierStep` injected between `outE(L)` and `has(...)`, or between `has(...)` and `inV()`, is a non-`HasStep` and would trip the decline — silently rejecting a valid IC2 chain. Whether TinkerPop injects a barrier at those exact positions is not verified in the design.
**Proposed fix**: The peek loop must skip `NoOpBarrierStep` (and any step the top-level `NoOpBarrierRecogniser` treats as transparent) rather than decline on it, and consume it in the step-count advance. Verify empirically: `EdgeTraversalEquivalenceTest` with the strategy registered must exercise `outE(L).has(...).inV()` through the real strategy chain (post-`LazyBarrierStrategy`), not a hand-built step list, and confirm it is `RECOGNIZED`.

### R8 [suggestion]
**Certificate**: T3 (both/self-loop/parallel-edge multiset equivalence)
**Location**: Track 3 Plan of Work step 7 (`EdgeTraversalEquivalenceTest`); implementation-plan.md Constraints "Multiset equality is the contract" (lines 33-38)
**Issue**: Multiset equality is the stated contract, and edge traversal has three shapes where MATCH and native Gremlin can diverge in multiplicity: `both(L)` on a vertex with edges in both directions, self-loops (`v -L-> v` — Gremlin `both` emits the neighbour twice), and parallel edges (two `L` edges between the same pair — native emits the target twice, a set-semantics MATCH traversal may emit once). If MATCH's `both`/`outE.inV` collapses any of these, the translator inherits the divergence. Easy to miss because a Person/Place graph seeded only with simple single-direction edges never exercises it.
**Proposed fix**: Seed the `EdgeTraversalEquivalenceTest` fixture with a self-loop, a parallel-edge pair, and a bidirectional `both` case, each asserting multiset (not set) equality against native. If any shape diverges, the recogniser should decline it under D3 rather than return a wrong multiset — decide and document which.

### R9 [suggestion]
**Certificate**: A7 (Track 2 already wired boundary ELEMENT), A2 (polymorphic already present)
**Location**: Track 3 `## Purpose` (line 9), `## Context` (line 33), `## Interfaces` "In scope (modified)" (line 80); `core/.../gremlin/translator/step/YTDBMatchPlanStep.java:436-481`
**Issue**: Track 3 states it "is the first track that wires a boundary step at all (`ELEMENT`)" and lists `YTDBMatchPlanStep (wire ELEMENT projection of Result → Vertex)` as in-scope-modified. Track 2 already delivered both: `YTDBMatchPlanStep.projectElement` → `projectVertex(row, graph)` is fully implemented (lines 436-481), the `ELEMENT` case is wired, and `g.V()` already emits vertices end to end (Track 2 Step 2 episode). Track 3 adds no new `BoundaryOutputType` (vertex hops stay `ELEMENT`), so `YTDBMatchPlanStep` needs **no** change. Likewise `WalkerContext.polymorphic` already exists (added post-Track-2, commit `c8579c485e`/`4fc1a40c4a`), yet Plan step 6 lists it as a field to add. Low impact, but the vestigial "modified" line could send the implementer to re-wire a working boundary.
**Proposed fix**: Drop the `YTDBMatchPlanStep`-modified line (or narrow it to "no change — ELEMENT already delivered by Track 2") and drop `polymorphic` from the Plan step 6 "new fields" list (keep only `edgeFilters` + the generators). Reconcile the design's "Boundary output types" Track-3-first-wires-ELEMENT wording (design.md lines 547-555) in Phase 4.

## Evidence base

#### E1 [Exposure] Walker index-driven refactor (D10) on the every-traversal critical path
- **Track claim**: Plan of Work step 6 — "Walker refactor to index-driven iteration"; design D10 — a recogniser advances `ctx.stepIndex` by N.
- **Critical path trace**:
  1. Entry: `GremlinToMatchStrategy.apply(traversal)` runs for **every** Gremlin traversal that passes the structural gate (Track 2 Step 5, strategy registered in `YTDBGraphImplAbstract.registerOptimizationStrategies`).
  2. `GremlinStepWalker.walk(traversal)` @ `GremlinStepWalker.java:102` — size gate `steps.size() > MAX_RECOGNISED_STEPS` (=1, line 79/110) declines multi-step today.
  3. `for (Step step : steps)` @ line 129 — foreach cursor; `recogniser.recognize(step, ctx)` @ 135; `ctx.stepIndex++` @ 138 owned by the walker.
  4. Invariant assert `boundaryAlias/outputType/returnClass != null` @ 153-157 — catches an unpinned boundary, not a subtle miscount.
- **Blast radius**: the loop is shared by all recognisers; a desync between the foreach cursor and a recogniser-owned `ctx.stepIndex` advance skips or re-dispatches steps for every multi-step shape (all edge chains). Bounded by the all-or-nothing decline (a corrupted walk usually declines, not mis-translates).
- **Existing safeguards**: all-or-nothing count invariant (line 46 comment), boundary-pinned assert under `-ea` (lines 153-154), throw-safety net in the strategy (Track 2 Step 3, rethrows `Error`/`AssertionError`). No safeguard distinguishes under-advance that yields a *valid-but-wrong* pattern.
- **Residual risk**: HIGH — the refactor changes the core loop contract; the interface (`boolean recognize`) has no consumed-count channel.

#### E2 [Exposure] Edge-side filter construction via MatchPatternBuilder.addEdge
- **Track claim**: `addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)` parks the edge alias + filter on `SQLMatchPathItem.filter`; "no executor or planner change needed."
- **Critical path trace**:
  1. `MatchPatternBuilder.addEdge(...)` @ `MatchPatternBuilder.java:121` — **7 params, no `edgeAlias`**.
  2. `toFilter.setFilter(edgeFilter)` @ 139 then `pathItem.setFilter(toFilter)` @ 150 — filter attaches to the **target-vertex** `{…}` block.
  3. `pathItem.outPath(edgeIdent)` @ 146 → `graphPath(..., "out")` @ `SQLMatchPathItem.java:54,32` — emits vertex-adjacent `out(L)`.
  4. `SQLMatchPathItem.executeTraversal` @ 87 → `traversePatternEdge` runs `method.execute` @ 203 (returns target vertices for `out`) → filter applied to `origin` (vertices) @ 120-128.
- **Blast radius**: the edge filter filters the target vertex, not the edge — wrong entity for every `outE(L).has(...).inV()` translation. The correct shape (`outE('L'){as,where}.inV()`, two path items) is supported by the executor (`MatchEdgeMethodLdbcPatternTest:70-73,132-135`) but not constructible by the current builder.
- **Existing safeguards**: `MatchEdgeMethod*Test` prove the executor path; `EdgeTraversalEquivalenceTest` would catch the misroute only if the fixture edge has a property the endpoints lack.
- **Residual risk**: HIGH — requires modifying Track-1 `MatchPatternBuilder` (unlisted scope) and the misleadingly-named `edgeFilter` param invites wiring it as-is.

#### E3 [Exposure] Multi-hop boundary projection (boundaryAlias → row.getVertex)
- **Track claim**: vertex hops emit `Vertex` through the boundary (`ELEMENT`).
- **Critical path trace**:
  1. `YTDBMatchPlanStep.project(row)` @ `YTDBMatchPlanStep.java:436` → `projectElement` @ 455 → `projectVertex` @ 476.
  2. `row.getVertex(boundaryAlias)` @ 477 — pulls the emitted vertex by the pinned alias; null → row dropped @ 478.
  3. `buildResult(ctx)` @ `GremlinStepWalker.java:168` builds `MatchPlanInputs` from `ctx.returnItems` (start recogniser pre-loaded `$g2m_v0`, `StartStepRecogniser.java:169-171`).
- **Blast radius**: a hop that re-pins `boundaryAlias=$g2m_anon_M` without rebuilding `returnItems` → `getVertex($g2m_anon_M)` null → empty result; a hop that leaves `boundaryAlias=$g2m_v0` → returns source vertices.
- **Existing safeguards**: the boundary-pinned assert only checks the fields are non-null, not that alias and projection agree. The `MatchPlanInputs` custom-RETURN branch requires the three return lists to stay parallel (Track 2 Step 4 episode).
- **Residual risk**: MEDIUM — deterministic once `rebindBoundaryProjection` exists and is called; caught by an equivalence test that checks target identity.

#### A1 [Assumption] Walker is currently for-each; D10 refactor genuinely pending
- **Track claim**: "The walker switches from for-each to index-driven iteration to support the first multi-step recogniser."
- **Evidence search**: Read `GremlinStepWalker.java` (whole file).
- **Code evidence**: `for (Step<?,?> step : steps)` @ line 129, `ctx.stepIndex++` @ 138 — a foreach that maintains `stepIndex` in parallel; single-step-per-recognise.
- **Verdict**: VALIDATED
- **Detail**: The refactor is real work despite `stepIndex` already existing; the loop shape must change so consumed steps are skipped.

#### A2 [Assumption] WalkerContext must gain the polymorphic flag
- **Track claim**: Plan of Work step 6 — WalkerContext "gains the polymorphic flag."
- **Evidence search**: Read `WalkerContext.java`; `git log` on the translator package.
- **Code evidence**: `final boolean polymorphic` already declared @ `WalkerContext.java:86`, resolved in `GremlinStepWalker.walk` @ 121-125 (commits `c8579c485e`, `4fc1a40c4a` landed after Track 2).
- **Verdict**: CONTRADICTED
- **Detail**: `polymorphic` is already present; Track 3 adds only its chain-target *read*, not the field. Prior-episode summary bullet 2 confirms.

#### A3 [Assumption] MATCH IR supports edge-side filters; no executor/planner change
- **Track claim**: `## Context` — "edge filtering needs no executor or planner change — only translator-side peek-ahead."
- **Evidence search**: Grep `outE`/`inV` in match executor + tests; read `SQLMatchPathItem.executeTraversal`; read `MatchEdgeMethodLdbcPatternTest`.
- **Code evidence**: `MatchEdgeMethodPreFilterTest`, `MatchEdgeMethodLdbcPatternTest`, `MatchEdgeMethodInferenceAndAbortTest` exercise `.outE('L'){where}.inV()`; `SQLMatchPathItem.executeTraversal:120-128` applies `filter` to `method.execute` results — an edge filter when the method is `outE`.
- **Verdict**: VALIDATED (executor/planner) — but see E2: the **builder** cannot construct the shape.
- **Detail**: The claim holds for the engine; the gap is entirely on the translator/builder side.

#### A4 [Assumption] Chain targets narrow @class "the same as the start recogniser"
- **Track claim**: Plan of Work step 1 — `VertexStepRecogniser` "applies the same `@class = '<className>'` / `@class IN [...]` narrowing as the start recogniser."
- **Evidence search**: Read `StartStepRecogniser.recognize`; design §"Schema polymorphism".
- **Code evidence**: `StartStepRecogniser.java:148` calls `addNode(BOUNDARY_ALIAS, "V", null, false)` — **no** where/`@class`; lines 154-160 document that narrowing to `@class='V'` would drop subclasses (BC2). A bare `out(L)` names no target class, so the target is root `"V"` (abstract).
- **Verdict**: CONTRADICTED
- **Detail**: "same as the start recogniser" = no narrowing. Narrowing a bare-hop target to `@class='V'` is the BC2 undercount or (V abstract) an empty result. Narrowing is legitimate only for named target classes (Track 4 `hasLabel`).

#### A5 [Assumption] AnonAliasGenerator + reserved-$ pre-flight exist / are in scope
- **Track claim**: Track 3 mints `$g2m_anon_M` / `$g2m_edge_N`; Plan step 6 lists `anonEdgeAliasGenerator`.
- **Evidence search**: `find` for `AnonAliasGenerator*`; grep `getLabels`/`anon`/`$g2m` in the translator package; Track 2 Decision Log.
- **Code evidence**: no `AnonAliasGenerator` file exists; the walker has no `getLabels()` pre-flight; `StartStepRecogniser` uses a `$g2m_v0` constant (line 93). Track 2 Decision Log "scope-down": "The generator and its reserved-`$`-label collision pre-flight land with the first multi-alias shape (Track 3 edge chains)."
- **Verdict**: CONTRADICTED
- **Detail**: Both are deferred-to-Track-3 and unbuilt, but neither the generator class nor the pre-flight is in Track 3's "In scope (new)"; Plan step 6 omits the vertex `anonAliasGenerator`.

#### A6 [Assumption] Modifying MatchPatternBuilder.addEdge is low blast radius
- **Track claim**: (implicit) extending `addEdge` for edge filters is safe.
- **Evidence search**: Grep `.addEdge(` across `core/src/main` and `core/src/test`; Track 1 Step 4 episode. (PSI find-usages timed out on the MCP HTTP limit — grep + episode used; reference-accuracy caveat: grep misses reflective/method-ref callers, none plausible for a new public instance method.)
- **Code evidence**: no `MatchPatternBuilder.addEdge` caller in `core/src/main` (the three `.addEdge(` hits are `Vertex`/`PatternNode`/`EdgeInternal`); GQL's assembler uses `addNode` only; Track 1 Step 4 episode "Edge construction (zero today in GQL)". Only `MatchPatternBuilderTest` exercises it.
- **Verdict**: VALIDATED
- **Detail**: Signature change touches only the builder's own unit test + Track 3's new callers — safe, but it is a Track-1 file absent from Track 3's modified-scope list (R1).

#### A7 [Assumption] Track 3 is the first track to wire the boundary ELEMENT projection
- **Track claim**: `## Purpose`/`## Context` — "Track 3 is the first track that wires a boundary step at all (`ELEMENT`)"; "In scope (modified): YTDBMatchPlanStep (wire ELEMENT projection)."
- **Evidence search**: Read `YTDBMatchPlanStep.java` projection block; Track 2 Step 2 episode; `StartStepRecogniser` output pinning.
- **Code evidence**: `YTDBMatchPlanStep.projectElement:455` → `projectVertex:476-481` already implemented; `StartStepRecogniser.java:150-151` already sets `outputType=ELEMENT`, `returnClass=Vertex`; Track 2 Step 2 episode "with only the ELEMENT case wired for Track 2."
- **Verdict**: CONTRADICTED
- **Detail**: Track 2 delivered `ELEMENT` end to end; Track 3 adds no new output type, so `YTDBMatchPlanStep` needs no change. The "modified" line is vestigial (R9).

#### T1 [Testability] EdgeTraversalEquivalenceTest translator-on/off + barrier shapes
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: Straightforward parity fixture (Track 2's `GremlinToMatchSmokeTest` is the template) — but must run through the **real** strategy chain so `LazyBarrierStrategy` barrier injection is exercised (R7), not a hand-built step list.
- **Existing test infrastructure**: `GremlinToMatchSmokeTest` (translator-on-vs-off parity), `GremlinStepWalkerTest` (fixture-registry unit tests), `MatchEdgeMethodLdbcPatternTest` (SQL-side edge-filter reference).
- **Feasibility**: ACHIEVABLE
- **Detail**: Requires seeding a Person/Place + Knows/Likes/Follows graph and asserting multiset (not set) equality plus boundary-step engagement per the plan's `RECOGNIZED`/`DECLINED` markers.

#### T2 [Testability] No-mutation-on-decline for stateful generators in peek-ahead
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: The decline branches (non-`HasStep` mid-chain, no closing hop, `as(label)` on the edge) must each be tested to assert `ctx` — generator counters, `edgeFilters`, `patternBuilder` — is unmutated. `MatchPatternBuilder` is one-shot, so a partial write cannot be reused; the test must inspect the counter state, which requires the generator to expose it or the recogniser to defer minting.
- **Existing test infrastructure**: design's canonical `decline_doesNotCommitPartialStateToOuterContext` pattern; per-recogniser walker-driven unit tests.
- **Feasibility**: DIFFICULT
- **Detail**: Achievable only if the recogniser defers all mutation until after the closing hop is confirmed (R6); otherwise the invariant is untestable-as-passing because the counters have already advanced.

#### T3 [Testability] both()/self-loop/parallel-edge multiset equivalence
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: Requires deliberately seeding self-loops, parallel edges, and bidirectional `both` cases — shapes a naive Person/Place fixture omits. The assertion must be multiset, not set.
- **Existing test infrastructure**: `EdgeTraversalEquivalenceTest` (new in this track), native-vs-translated comparison harness from Track 2.
- **Feasibility**: ACHIEVABLE
- **Detail**: The risk is omission, not infeasibility; if a shape diverges, decline it under D3 rather than emit a wrong multiset.
