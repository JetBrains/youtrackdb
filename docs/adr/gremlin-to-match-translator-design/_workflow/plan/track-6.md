<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 6: Result shaping — labels + dedup, projections, order/pagination, aggregations

## Purpose / Big Picture
After this track, the four result-producing step families translate: step labels + dedup, projections (`select` / `values` / `valueMap` / `elementMap` / `project`), order/pagination, and aggregations (`count` / `sum` / `min` / `max` / `mean` / `group` / `groupCount`) — pinning the boundary output type per terminal step.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Merges the four result-producing step families. Adds `as(label)` propagation and `DedupStep` recognition; `GremlinProjectionAssembler` using `EntityImpl.hasProperty(key)` to distinguish absent from null-valued (the load-bearing "Track 5 commitment"); `OrderGlobalStep` + `RangeGlobalStep`; and aggregation recognition mapped to `SQLProjection` aggregates + `SQLGroupBy`, with the count short-circuit factored out of `SelectExecutionPlanner` and the `dropNullRows` / `dropOnAbsent` flags for empty-input and absent-vs-null semantics. Shares one `ByModulatorTranslator` across order/select/dedup/group/project.

## Progress
- [x] Review + decomposition (1 iteration: iter1 PASS — Technical + Risk + Adversarial; 0 blockers)
- [x] Step implementation
- [x] Track-level code review (iter1 deeper FAIL → SF1/SF2 fixed; awaiting Approve)
- [ ] Track completion

- [x] 2026-07-22T10:30Z [ctx=info] Review + decomposition complete (strategic trio: Technical PASS iter1, Risk PASS iter1, Adversarial PASS iter1; 7 steps, reconciled tag `high`)
- [x] 2026-07-22T12:12Z [ctx=info] Step 1 complete (tip cf4698732d; WalkerContextResultShapingTest + GremlinToMatchStrategyTest + GremlinStepWalkerTest green)
- [x] 2026-07-22T12:24Z [ctx=info] Step 2 complete (tip 0597b30ef6; DedupGlobalStepRecogniserTest + GremlinStepWalkerTest green)
- [x] 2026-07-22T12:38Z [ctx=info] Step 3 complete (tip d56953c3d1; GremlinProjectionRecogniserTest + GremlinStepWalkerTest green)
- [x] 2026-07-22T12:56Z [ctx=info] Step 4 complete (tip bd45c5a6b6; ByModulatorTranslatorTest + GremlinProjectionRecogniserTest + DedupGlobalStepRecogniserTest green)
- [x] 2026-07-22T13:20Z [ctx=info] Step 5 complete (tip 6ea1e4fb7d; OrderRangeStepRecogniserTest + GremlinStepWalkerTest green)
- [x] 2026-07-22T13:51Z [ctx=info] Step 6 complete (tip b660e7527e; GremlinAggregateRecogniserTest + GremlinStepWalkerTest + smoke green)
- [x] 2026-07-27T12:15Z [ctx=info] Step 6b complete (tip ecb16e3ea9; MatchProjectionBuilderTest + GremlinAggregateRecogniserTest green)
- [x] 2026-07-22T14:50Z [ctx=info] Step 7 complete (tip 985a14e1e8; boundary MAP/SINGLE_VALUE/SCALAR + ProjectionEquivalenceTest green)
- [x] 2026-07-22T15:55Z [ctx=info] Phase C shallow PASS withdrawn (tip 1d17dc04eb)
- [x] 2026-07-22 [ctx=info] Phase C deeper re-audit found SF1/SF2; named/by dedup fix + regression tests green

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- 2026-07-22 (Phase A, T2): `GremlinStepWalker.buildResult` does not yet wire `returnDistinct` / `groupBy` / `orderBy` / `limit` / `skip` into `MatchPlanInputs` even though the record supports them — foundation step must extend `WalkerContext` and `buildResult` together.
- 2026-07-22 (Phase A, R2): `handleHardwiredCountOnClass*` lives only on `SelectExecutionPlanner` (private static); `MatchExecutionPlanner` has no count short-circuit hook yet — extraction is a real new seam, not a re-export.
- 2026-07-22 (Phase A, T4): Frozen `design.md` §empty-input still tags projection work as "Track 5" and conflates `values` with `dropNullRows`; track plan's `dropOnAbsent` is correct — Phase-4 reconciliation only.
- 2026-07-22 (Step 7): Post-RETURN rows drop the matched entity — `values`/`valueMap`/`elementMap` must RETURN the boundary entity for `EntityImpl.hasProperty`. Single-key `select` must unwrap (native SelectOne). SQL `sum` over absent-valued matched rows can yield `0`, so empty-aggregate fixtures use zero-match filters.
- 2026-07-22 (post-Step 7): `GremlinPlanFingerprint` omitted Track 6 result-shaping (`order`/`limit`/`skip`/`groupBy`/`distinct`) — collision risk for `limit(2)` vs `limit(5)`; fixed in the same session as the Parameter-binding reconciliation Decision Log entries.
- 2026-07-22 (Phase C startup): High steps 1/3/4/6/7 skipped Phase B sub-step 4 (`review-bugs`); remediated with retroactive per-step `review-bugs` plus track-level Phase C. Phase B base recording was missing — Phase C uses `d7dd3f8171`.

## Decision Log
<!-- Continuous-log. -->
- 2026-07-22 (Phase A, A1): **Aggregate-over-`values` re-pointing uses walker state, not cursor rewind.** `PropertiesStepRecogniser` records the last single-key field-access `SQLExpression` on `WalkerContext`; aggregate recognisers consume it for `mean`/`sum`/etc. Decline when the prefix is not exactly one property key. Avoids a second pass over the step list.
- 2026-07-22 (Phase A, T1/R1): **`as(label)` + absent-vs-null are independent load-bearing seams.** Alias propagation must surface user labels for `select`/`dedup`/`where(P)`; `GremlinProjectionAssembler` must use `EntityImpl.hasProperty` at boundary iteration for `valueMap`/`values` — `Result.getProperty` alone is insufficient.
- 2026-07-22 (Phase A, R2): **Count short-circuit: extract `handleHardwiredCountOnClass*` to a package-visible helper invoked from `MatchExecutionPlanner` after pattern build; decline shapes fall through to `YTDBGraphCountStrategy` (already ordered after `GremlinToMatchStrategy`).**
- 2026-07-22 (Phase A, R3): **Value-side `by(__.count())` / `by(__.fold())` routes through the Track 5 sub-walker (`walkChild` + capture adapter), not a fresh `WalkerContext`.**
- 2026-07-22 (post-Step 7, cache congruence): **`GremlinPlanFingerprint` must include result-shaping clauses (`groupBy` / `orderBy` / `limit` / `skip` / `returnDistinct`).** Limit/skip must use `toString` (not `toGenericStatement`) — `SQLNumber` collapses every integer to `?`, which would still collide `limit(2)` with `limit(5)`. Group/order keep `toGenericStatement` (property names / directions). Extends the Track 5 “full post-walk `MatchPlanInputs`” rule past the projection-only sections Step 5 shipped.
- 2026-07-22 (post-Step 7, Parameter-binding reconciliation): **As-built wins over frozen `design.md` §Parameter binding on three points; sync into `design-final` / design prose in Phase 4, do not reverse the code or edit frozen `design.md` in Phase 3.** (1) RID-bearing walks (`g.V(id)` / `hasId`) inline RIDs via `MatchLiteralBuilder.toLiteral` and **bypass** `GremlinPlanCache` (`markRidBearing`) — they are not positional `?` “out of the key”. (2) The cache key is a hand-built post-walk `MatchPlanInputs` fingerprint, not “normalised traversal bytecode”. (3) `toLiteral` remains for RIDs and the null-`ParamSink` test path; production predicate values use `bindParam`. `bindParam` does not type-switch/`Supplier`-decline today — optional hardening deferred.
- 2026-07-22 (Phase C deeper): **Named / modulated `dedup` must not rewrite RETURN under `ELEMENT`.** Accept only anonymous `dedup()` and named labels that all resolve to the current boundary alias (`returnDistinct` only). Decline `by(...)` and prior-hop named labels to native — MATCH `DISTINCT` cannot express unique-by-key / emit-current.
- 2026-07-27 (post-Step 6, aggregate IR cleanup): **Aggregate RETURN expressions should be built as AST, not reparsed from SQL text.** `GremlinAggregateAssembler` now routes `count(*)`, `list($currentMatch)`, and property aggregates through `MatchProjectionBuilder` / `ProjectionExpressionFactories` so Gremlin stays on the translator's IR-first path and shares one projection-construction surface with other MATCH front-ends.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->
**Phase A (2026-07-22, iter1).** Strategic trio against the result-shaping track (predicted tag `high` → Technical + Risk + Adversarial). mcp-steroid was not reachable this session; symbol audits used codebase reads with reference-accuracy caveats in each review file.

Iteration 1 (findings in `reviews/{technical,risk,adversarial}-iter1.md`):
- Technical: **PASS** — 4 findings (2 should-fix + 2 suggestions, no blockers). T1/T2 pin `as(label)` API and `buildResult`/`MatchPlanInputs` wiring gaps.
- Risk: **PASS** — 5 findings (3 should-fix + 2 suggestions, no blockers). R1 entity-layer presence; R2 count short-circuit extraction; R3 sub-walker reuse for `by` value-side.
- Adversarial: **PASS** — 3 findings (2 should-fix + 1 suggestion, no blockers). A1 aggregate/values re-point via walker state; A2 named dedup depends on Step 2 alias propagation.

**Track Pre-Flight (look-back Track 5 → Track 6): ADJUST / CONTINUE.** Track 5 delivered the sub-walker and D5 cache; Track 6 inherits: (1) `walkChild` for `ByModulatorTranslator` value-side accumulators; (2) `PropertiesStep` recogniser unblocks no new `hasNot` concern; (3) extend `GremlinPlanFingerprint` before any positive `matchExpressions` writer (Track 5 BG1); (4) reserved-`$` `as(...)` guard when child labels become aliases (Track 5 BG2). No ESCALATE — scope and dependencies unchanged.

**Gate verdict iteration 1: PASS.** Reconciled track tag: `high`. Seven steps, strictly ordered 1→7.

**Phase C (2026-07-22, iter1 — main-session remediation + deeper re-audit).** Task subagent fan-out failed (account usage limit). Shallow main-session PASS (0 should-fix) was wrong: named/`by` dedup rewrote RETURN under `ELEMENT` and emitted null payloads (SF1); equivalence suite never covered those shapes (SF2). Deeper re-audit **FAIL** → fix: accept only anonymous `dedup()` and named labels on the current boundary (distinct, no RETURN rewrite); decline prior-hop named dedup and `by(...)` to native. Details in `reviews/phase-c-iter1.md`.

## Context and Orientation
By Track 6 the boundary step emits `ELEMENT` (vertex hops). This track adds the remaining four output types: `MAP` (`select` multi / `valueMap` / `elementMap` / `project` / `group` / `groupCount`), `SINGLE_VALUE` (`values` single-key), and `SCALAR` (`count` / `sum` / `min` / `max` / `mean`). Each terminal-step recogniser pins the type on the boundary at translation time.

Two semantic hazards dominate this track:
- **Absent vs null-valued (load-bearing).** YTDB's record layer separates *absent* from *present-with-null*; `Result.getProperty` collapses them, but native Gremlin keeps them distinct. `valueMap` / `elementMap` must query the entity via `EntityImpl.hasProperty(key)` — absent → omit the key, present (incl. null) → include it. `values(key)` sets a new boundary `dropOnAbsent` flag (drop absent rows, keep present-null rows) — distinct from the existing value-checking `dropNullRows`. Design §"Track 5 commitment" gives the full truth table.
- **Aggregate empty-input divergence.** TinkerPop `count` of empty emits `0L`; `sum`/`min`/`max`/`mean` of empty emit **nothing**; MATCH emits a null cell. The boundary's `dropNullRows` flag (recogniser-set per output type) closes the gap: `true` for `sum`/`min`/`max`/`mean` (`SCALAR`) and `values` single-key (`SINGLE_VALUE`); `false` for `count` / `group` / `groupCount` / `ELEMENT` / `MAP`.

`count()` is unified: it translates to `RETURN count(*)` like any aggregate and rides a **shared engine count short-circuit** factored from `SelectExecutionPlanner.handleHardwiredCountOnClass` / `handleHardwiredCountOnClassUsingIndex` and invoked by `MatchExecutionPlanner` after `buildPatterns`. Single-class polymorphic class-count routes to `CountFromClassStep`; a single indexed-equality filter to `CountFromIndexWithKeyStep`. Multi-label and non-polymorphic counts decline to the reordered `YTDBGraphCountStrategy` fallback. `by(...)` is the uniform modulator across `order` / `select` / `dedup` / `group` / `project`, so its shape-resolution lives in one shared `ByModulatorTranslator` (design §"by-modulator translation").

## Plan of Work
1. **`as(label)` + dedup:** propagate `as(label)` to the most recent `SQLMatchFilter.alias` via `MatchPatternBuilder.alias(...)`; `DedupGlobalStep` → `info.distinct = true` (no labels) or projection-over-labels + DISTINCT (named labels; declines if a named label is not surfaced by the projection). `OptionalStep` declines (D3 / Phase 2).
2. **`GremlinProjectionAssembler`** for `select(label)` / `select(l1,…)` / `values(keys…)` / `valueMap(keys…)` / `elementMap()` / `project(keys…).by(…)`, with the `hasProperty(key)` absent-vs-null classification and `dropOnAbsent` wiring.
3. **`ByModulatorTranslator`** in the shared `match/builder/` package: key-side shapes (`by("k")`, `by(T.id)`, `by(T.label)`, the `__.values/id/label` unwraps, `by(Order.asc/desc)`) and value-side accumulators (`by(__.count())`, `by(__.fold())`, `by(__.values(k).count())`, …); declines edges/aggregates/lambdas/`Order.shuffle`/per-label-count-mismatch.
4. **`OrderGlobalStep`** → `SQLOrderBy` (`Order.shuffle` declines); **`RangeGlobalStep`** → `SQLSkip(low) + SQLLimit(high-low)` (drops `SQLLimit` for unbounded high = `skip(n)`).
5. **Aggregations:** recognisers for `count` / `sum` / `min` / `max` / `mean` / `group` / `groupCount` → `SQLProjection` aggregate items + `SQLGroupBy`; the `dropNullRows` flag per output type; walker post-processing that re-points an aggregate at a preceding `PropertiesStep`'s field-access (`g.V().values("age").mean()`).
6. **Shared count short-circuit:** factor `handleHardwiredCountOnClass*` into a helper invoked by `MatchExecutionPlanner` (edits to `MatchExecutionPlanner` + `SelectExecutionPlanner`); `CountFromClassStep.canBeCached()==false` keeps these plans uncached, as SELECT already does.
7. **Tests:** parity / projection / absent-vs-null (map with `foo:null` vs map without `foo`) / aggregate-equivalence (incl. empty-input `count`=0 vs `mean`=nothing) / order / pagination, extending `EdgeTraversalEquivalenceTest`.

## Concrete Steps
1. **Walker foundation + `BoundaryOutputType` expansion** — add `MAP` / `SINGLE_VALUE` / `SCALAR` to `BoundaryOutputType`; extend `WalkerContext` / `RecognitionContext` with `returnDistinct`, `groupBy`, `orderBy`, `limit`, `skip`, `dropNullRows`, `dropOnAbsent`, and `lastPropertyProjection` (A1); wire all fields through `GremlinStepWalker.buildResult` → `MatchPlanInputs` and `TranslationResult` → `YTDBMatchPlanStep` constructor (T2). Extend `YTDBMatchPlanStep` skeleton to branch on output type (element path unchanged). Detail: Plan of Work items 5–6 (flags), Decision Log A1. — `risk: high`  [x] commit: cf4698732d
2. **`as(label)` propagation + `DedupGlobalStep`** — propagate `as(label)` to the most recent pattern node's alias metadata (`MatchPatternBuilder` extension — T1); `DedupGlobalStep` → `returnDistinct` (no labels) or projection-over-labels + DISTINCT (named labels; decline when a label is not surfaced — A2). Detail: Plan of Work item 1. — `risk: medium` *(depends on Step 1)*  [x] commit: 0597b30ef6
3. **`GremlinProjectionAssembler` + projection recognisers** — `PropertiesStep` (`values`), `PropertyMapStep` (`valueMap`/`elementMap`), `SelectStep`, `ProjectStep`; `EntityImpl.hasProperty(key)` absent-vs-null classification (R1); `dropOnAbsent` for single-key `values`; pin boundary output type per terminal (`MAP` / `SINGLE_VALUE`). Accept `PropertyType.VALUE` and `PropertyType.PROPERTY` on `PropertiesStep` (T3). Detail: Plan of Work item 2, Decision Log T1/R1. — `risk: high` *(depends on Steps 1–2)*  [x] commit: d56953c3d1
4. **`ByModulatorTranslator`** (shared `match/builder/`) — key-side (`by("k")`, `by(T.id)`, `by(T.label)`, `__.values/id/label` unwraps, `Order.asc/desc`) and value-side (`by(__.count())`, `by(__.fold())`, …) via sub-walker capture (R3); declines edges/aggregates/lambdas/`Order.shuffle`/per-label-count-mismatch. Detail: Plan of Work item 3. — `risk: high` *(depends on Step 1)*  [x] commit: bd45c5a6b6
5. **`OrderGlobalStep` + `RangeGlobalStep`** — `SQLOrderBy` (`Order.shuffle` declines); `SQLSkip` + `SQLLimit` (`range` → `limit = high - low`, unbounded high → skip-only). Detail: Plan of Work item 4. — `risk: medium` *(depends on Steps 1, 4)*  [x] commit: 6ea1e4fb7d
6. **Aggregate recognisers + count short-circuit** — `count`/`sum`/`min`/`max`/`mean`/`group`/`groupCount` → `SQLProjection` + `SQLGroupBy`; `dropNullRows` per output type; consume `lastPropertyProjection` for `values("age").mean()` (A1); extract `handleHardwiredCountOnClass*` to shared helper, invoke from `MatchExecutionPlanner` (R2). Multi-label / non-polymorphic counts decline to `YTDBGraphCountStrategy`. Detail: Plan of Work items 5–6. — `risk: high` *(depends on Steps 1, 3, 4)*  [x] commit: b660e7527e
7. **Boundary projection + parity tests** — complete `YTDBMatchPlanStep` `MAP` / `SINGLE_VALUE` / `SCALAR` payload emission; `dropNullRows` row loop + `dropOnAbsent` entity check (R5); `group`/`groupCount` MAP accumulation; extend `EdgeTraversalEquivalenceTest` / new projection-equivalence tests for absent-vs-null, empty-input aggregates, order, pagination, dedup. Detail: Validation and Acceptance. — `risk: high` *(depends on Steps 1–6)*  [x]

**Step sequencing.** Strictly ordered 1→7 — Step 1 is the shared foundation; projection recognisers (3) before boundary completion (7); `ByModulatorTranslator` (4) before order/group `by` shapes (5–6). Reconciled track tag: `high`.

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

### Step 1 — commit cf4698732d, 2026-07-22T12:12Z [ctx=info]
**What was done:** Expanded `BoundaryOutputType` with `MAP`, `SINGLE_VALUE`, and `SCALAR`. Extended `WalkerContext` / `RecognitionContext` with result-shaping fields (`returnDistinct`, `groupBy`, `orderBy`, `limit`, `skip`, `dropNullRows`, `dropOnAbsent`, `lastPropertyProjection`). Wired all fields through `GremlinStepWalker.buildResult` → `MatchPlanInputs` and `TranslationResult` → `YTDBMatchPlanStep` (drop flags stored; `ELEMENT` path unchanged). `SubTraversalPredicateAdapter` swallows result-shaping setters on combinator sub-walks. Added `WalkerContextResultShapingTest`.

**What was discovered:** `buildResult` previously omitted `returnDistinct` / `groupBy` / `orderBy` / `limit` / `skip` even though `MatchPlanInputs` already carried them — foundation step had to extend walker and `buildResult` together (Phase A T2).

**What changed from plan:** `MAP` / `SINGLE_VALUE` / `SCALAR` projection in `YTDBMatchPlanStep.project()` throws `UnsupportedOperationException` until Step 7; Step 1 only stores flags and enum values.

**Key files:**
- `BoundaryOutputType.java`, `WalkerContext.java`, `RecognitionContext.java`, `SubTraversalPredicateAdapter.java`
- `GremlinStepWalker.java`, `GremlinToMatchTranslator.java`, `GremlinToMatchStrategy.java`, `YTDBMatchPlanStep.java`
- `WalkerContextResultShapingTest.java`

**Critical context:** Later steps set recogniser-side flags and RETURN clauses on the walker; Step 7 completes boundary payload shaping and drop loops. `lastPropertyProjection` is written by Step 3 `PropertiesStepRecogniser` and consumed by Step 6 aggregates.

### Step 2 — commit 0597b30ef6, 2026-07-22T12:24Z [ctx=info]
**What was done:** Propagated Gremlin `as(label)` from `StartStepRecogniser` and folded hop steps into `WalkerContext.userLabelToAlias` via `bindStepLabels`, with `MatchPatternBuilder.registerUserLabel` retaining display metadata. Added `DedupGlobalStepRecogniser`: anonymous `dedup()` sets `returnDistinct`; named `dedup(labels…)` projects bound labels then sets distinct; unbound labels and `by(...)` modulators decline. Switched `PRODUCTION_RECOGNISERS` to `Map.ofEntries` (11 entries exceeds `Map.of` arity). Added `DedupGlobalStepRecogniserTest` and extended `GremlinStepWalkerTest`.

**What was discovered:** `Map.of` caps at 10 key-value pairs — the dedup recogniser was the 11th registry entry.

**What changed from plan:** `MatchPatternBuilder.registerUserLabel` stores metadata only; `build()` does not yet consume it (planner wiring deferred). Combinator sub-walks swallow label binding on the capture adapter.

**Key files:**
- `GremlinStepLabels.java`, `DedupGlobalStepRecogniser.java`, `WalkerContext.java`, `RecognitionContext.java`
- `StartStepRecogniser.java`, `GremlinPatternAssembler.java`, `MatchPatternBuilder.java`, `GremlinStepWalker.java`
- `DedupGlobalStepRecogniserTest.java`, `GremlinStepWalkerTest.java`

**Critical context:** Step 3 projection recognisers read `resolveUserLabel`; named dedup already exercises multi-column RETURN. `dedup().by(...)` declines until Step 4 `ByModulatorTranslator`.

### Step 3 — commit d56953c3d1, 2026-07-22T12:38Z [ctx=info]
**What was done:** Added `GremlinProjectionAssembler` and recognisers for terminal `PropertiesStep` (`SINGLE_VALUE` + `dropOnAbsent` + `lastPropertyProjection`), `SelectOneStep` / `SelectStep` (`MAP` over bound labels), `PropertyMapStep` (`valueMap`), and `ElementMapStep` (`elementMap` with id/label tokens). `ProjectStepRecogniser` declines until Step 4. Extended `RecognitionContext` with `clearReturnProjection` / `appendReturnColumn`.

**What was discovered:** TinkerPop splits `select("a")` → `SelectOneStep` vs `select("a","b")` → `SelectStep`, and `elementMap()` → `ElementMapStep` (not `PropertyMapStep` with tokens). Registry now has 17 entries.

**What changed from plan:** Entity-layer `hasProperty` absent-vs-null classification is recogniser-flag + RETURN wiring only here; `YTDBMatchPlanStep` MAP/SINGLE_VALUE payload emission remains Step 7.

**Key files:**
- `GremlinProjectionAssembler.java`, `PropertiesStepRecogniser.java`, `SelectOneStepRecogniser.java`, `SelectStepRecogniser.java`, `PropertyMapStepRecogniser.java`, `ElementMapStepRecogniser.java`, `ProjectStepRecogniser.java`
- `GremlinProjectionRecogniserTest.java`, extended `GremlinStepWalkerTest.java`

**Critical context:** Step 4 `ByModulatorTranslator` unblocks `project().by(...)`, `select().by(...)`, and `dedup().by(...)`. Step 6 aggregates consume `lastPropertyProjection` from single-key `values`.

### Step 4 — commit bd45c5a6b6, 2026-07-22T12:56Z [ctx=info]
**What was done:** Added shared `ByModulatorTranslator` in `match/builder/` for key-side field access (`ValueTraversal`, `TokenTraversal`, `PropertiesStep`/`IdStep`/`LabelStep` unwraps), value-side accumulators (`CountGlobalStep`, `FoldStep`, property aggregates), and `Order.asc/desc` sort-direction parsing. Wired into `ProjectStepRecogniser`, `SelectStepRecogniser`, `SelectOneStepRecogniser`, and `DedupGlobalStepRecogniser`. `GremlinProjectionAssembler` delegates field-access SQL building to the translator.

**What was discovered:** TinkerPop stores string/token `by(...)` modulators as empty-step `ValueTraversal`/`TokenTraversal` lambdas in `TraversalRing`/`getLocalChildren`, not as inline step lists — only sub-traversal forms (`__.values(k)`, `__.count()`, …) carry steps.

**What changed from plan:** Value-side resolution pattern-matches modulator step lists directly; full sub-walker capture for group/order wiring lands in Steps 5–6 recognisers that consume `ValueAccumulator`.

**Key files:**
- `ByModulatorTranslator.java`, `ByModulatorTranslatorTest.java`
- `ProjectStepRecogniser.java`, `SelectStepRecogniser.java`, `SelectOneStepRecogniser.java`, `DedupGlobalStepRecogniser.java`

**Critical context:** Step 5 `OrderGlobalStepRecogniser` consumes `translateKeyModulator` + `parseSortDirection`. Step 6 group recognisers consume `translateValueModulator`.

### Step 5 — commit 6ea1e4fb7d, 2026-07-22T13:20Z [ctx=info]
**What was done:** Added `OrderGlobalStepRecogniser` (`order()` / `order().by(...)` → `SQLOrderBy`; bare/identity sorts by `@rid`; `Order.shuffle` declines) and `RangeGlobalStepRecogniser` (`limit`/`skip`/`range` → `SQLSkip`/`SQLLimit`). Registered both plus `RangeGlobalStepPlaceholder` in the production walker.

**What was discovered:** TinkerPop encodes `skip(n)` as `RangeGlobalStep(n, -1)` (not `Long.MAX_VALUE` as the design draft guessed). Both unbounded sentinels are accepted.

**What changed from plan:** Second `order`/`range` on the same walk declines rather than composing — Phase 1 restriction.

**Key files:**
- `OrderGlobalStepRecogniser.java`, `RangeGlobalStepRecogniser.java`, `GremlinStepWalker.java`
- `OrderRangeStepRecogniserTest.java`, extended `GremlinStepWalkerTest.java`

**Critical context:** Step 6 aggregates + count short-circuit; Step 7 completes boundary MAP/SINGLE_VALUE/SCALAR emission and parity tests.

### Step 6 — commit b660e7527e, 2026-07-22T13:51Z [ctx=info]
**What was done:** Added `GremlinAggregateAssembler` and recognisers for `count` (SCALAR; non-polymorphic declines), property aggregates (`sum`/`min`/`max`/`mean` over `lastPropertyProjection` with `dropNullRows`), and `group`/`groupCount` (MAP + `SQLGroupBy`). Extracted `HardwiredCountOptimizations` from `SelectExecutionPlanner`; `MatchExecutionPlanner` short-circuits single-node unfiltered `count(*)` to `CountFromClassStep` after `buildPatterns`.

**What was discovered:** Filtered MATCH counts stay on the generic aggregate path for Phase 1 — index short-circuit still needs `QueryPlanningInfo` WHERE flattening (SELECT path unchanged).

**What changed from plan:** Indexed MATCH count short-circuit deferred; bare class-count path is live for Gremlin/GQL/SQL.

**Key files:**
- `GremlinAggregateAssembler.java`, `CountGlobalStepRecogniser.java`, `PropertyAggregateStepRecogniser.java`, `GroupStepRecogniser.java`, `GroupCountStepRecogniser.java`
- `HardwiredCountOptimizations.java`, `SelectExecutionPlanner.java`, `MatchExecutionPlanner.java`
- `GremlinAggregateRecogniserTest.java`

**Critical context:** Step 7 completes `YTDBMatchPlanStep` MAP/SINGLE_VALUE/SCALAR projection and parity tests (including empty-input aggregates and absent-vs-null). Step 6b later removes the temporary SQL-text aggregate parsing helper without changing those semantics.

### Step 6b — 2026-07-27 [ctx=info]
**What was done:** Refactored Gremlin aggregate projection building to avoid SQL-text parsing round-trips. Introduced `MatchProjectionBuilder` (MATCH `RETURN` projection factories) and `ProjectionExpressionFactories` (parser-level AST factories), removed `parseAggregate(String)` from `GremlinAggregateAssembler`, and added `MatchProjectionBuilderTest` to pin the constructed AST shapes for `count(*)`, `list($currentMatch)`, and property aggregates (e.g. `mean(age)`).

**What was discovered:** `SQLFunctionCall` AST nodes can be constructed directly (including the `count(*)` star argument) while keeping output parity with the parser-emitted shapes.

**Key files:**
- `GremlinAggregateAssembler.java`
- `MatchProjectionBuilder.java`, `ProjectionExpressionFactories.java`
- `MatchProjectionBuilderTest.java`

### Step 7 — commit 985a14e1e8, 2026-07-22T14:50Z [ctx=info]
**What was done:** Completed `YTDBMatchPlanStep` projection for `MAP` / `SINGLE_VALUE` / `SCALAR` with `dropOnAbsent` / `dropNullRows` row loops and `group`/`groupCount` map accumulation. Assemblers now RETURN the boundary entity for presence checks; valueMap wraps property values in singleton lists; elementMap emits `T.id`/`T.label` keys; single-key `select` unwraps to the column value (native SelectOne shape). Added `ProjectionEquivalenceTest` for absent-vs-null, empty aggregates, order/limit, dedup, groupCount.

**What was discovered:** Post-RETURN MATCH rows lack the entity — presence checks require an explicit entity RETURN column. Native `select("a")` emits the value, not a one-entry map. SQL `sum(alias.age)` over a matched vertex with absent `age` can yield `0`, so empty-input aggregate parity uses a zero-match filter (`has` that matches nothing), not “vertex present, property absent”.

**What changed from plan:** Added walker flags `presencePropertyKeys`, `wrapMapValuesInLists`, `accumulateMap`, `unwrapSingletonMap`, `elementMapTokens` rather than inferring all shaping from RETURN column names alone.

**Key files:**
- `YTDBMatchPlanStep.java`, `GremlinProjectionAssembler.java`, `GremlinAggregateAssembler.java`
- `GremlinToMatchTranslator.java`, `WalkerContext.java`, `RecognitionContext.java`
- `ProjectionEquivalenceTest.java`

**Critical context:** Track 6 step implementation is complete; next is track-level code review + track completion checkboxes.

## Validation and Acceptance
- `select` / `values` / `valueMap` / `elementMap` / `project` translate and match native multisets, with the correct boundary output type per terminal step.
- A vertex with `foo` set to null surfaces as a map with a `foo: null` entry; a vertex with `foo` absent surfaces as a map without `foo` (native parity). `values("foo")` emits a null traverser for present-null and no traverser for absent.
- `order().by(...)` (single + multi-key, asc/desc) matches native; `Order.shuffle` declines. `limit` / `skip` / `range` match native.
- `count()` returns the same value as native and routes single-class shapes through `CountFromClassStep`; multi-label / non-polymorphic counts decline to `YTDBGraphCountStrategy`. `sum`/`min`/`max`/`mean` match native including the empty-input case (no traverser); `count` of empty emits `0L`.
- `group` / `groupCount` (with recognized key-side and value-side `by`) match native maps.
- `dedup()` and `dedup(labels…)` match native; an unaddressable dedup label declines.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope (new):** `GremlinProjectionAssembler`; `ByModulatorTranslator` (shared `match/builder/`); recognisers for `SelectStep`, `PropertiesStep` (`values`), `PropertyMapStep` (`valueMap`/`elementMap`), `ProjectStep`, `OrderGlobalStep`, `RangeGlobalStep`, `DedupGlobalStep`, aggregate steps (`count`/`sum`/`min`/`max`/`mean`/`group`/`groupCount`); the shared count short-circuit helper; `dropOnAbsent` boundary flag; projection / aggregate / absent-vs-null tests.
**In scope (modified):** `WalkerContext` (return items/aliases/nested projections, groupBy/orderBy/limit/skip, output type, `dropNullRows`/`dropOnAbsent`); `YTDBMatchPlanStep` (`MAP` / `SINGLE_VALUE` / `SCALAR` projection + row-level drop logic); `MatchExecutionPlanner` + `SelectExecutionPlanner` (extract + invoke the count short-circuit); `MatchPatternBuilder` (`alias(...)`).
**Out of scope:** union + list-shaping terminators (Track 7); approximate count (Phase 2); edge property extraction / edge-side labels (Phase 2 — design §"Out of scope").
**Inter-track dependencies:** depends on Track 4 (predicate algebra for `by`-value resolution), new Track 5 (the sub-walker its `by(__.count())` / `by(__.fold())` value-side accumulators run over), and Track 1 (`hasProperty` presence primitive shared with `IS DEFINED`). Supplies all five output types and the projection logic that Track 7's list-shaping terminators post-process.
**Signatures:** `EntityImpl.hasProperty(key)`; `Result.getProperty`; `SelectExecutionPlanner.handleHardwiredCountOnClass` / `handleHardwiredCountOnClassUsingIndex`; `session.countClass(name, polymorphic)`; `CountFromClassStep` / `CountFromIndexWithKeyStep`.

## Invariants & Constraints
<!-- Combined per-track invariants + constraints (conventions-execution.md §2.1 §14).
Added by workflow migration (#1145). Strategic invariants/constraints for this track remain
in implementation-plan.md § High-level plan (Architecture Notes) and this track's ## Decision
Log — the conservative migration retained the plan Architecture Notes rather than folding them here. -->

## Base commit
<!-- Phase B records the HEAD SHA here at session start; Phase C reads it to compute the
cumulative track diff (conventions-execution.md §2.1 §15). Added by workflow migration (#1145). -->
ee8d63e1ce

Note: recorded base `ee8d63e1ce` is reachable but pre-dates Track 5 completion + Track 6 Phase A; Phase B never wrote a `Record Phase B base commit for Track 6` commit. Using actual Phase B start `d7dd3f8171` (tip after Phase A / parent of Step 1) for Phase C. Retroactive step-level `review-bugs` also runs for skipped high steps 1, 3, 4, 6, 7.
