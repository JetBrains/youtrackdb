<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 6: Result shaping — labels + dedup, projections, order/pagination, aggregations

## Purpose / Big Picture
After this track, the four result-producing step families translate: step labels + dedup, projections (`select` / `values` / `valueMap` / `elementMap` / `project`), order/pagination, and aggregations (`count` / `sum` / `min` / `max` / `mean` / `group` / `groupCount`) — pinning the boundary output type per terminal step.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Merges the four result-producing step families. Adds `as(label)` propagation and `DedupStep` recognition; `GremlinProjectionAssembler` using `EntityImpl.hasProperty(key)` to distinguish absent from null-valued (the load-bearing "Track 5 commitment"); `OrderGlobalStep` + `RangeGlobalStep`; and aggregation recognition mapped to `SQLProjection` aggregates + `SQLGroupBy`, with the count short-circuit factored out of `SelectExecutionPlanner` and the `dropNullRows` / `dropOnAbsent` flags for empty-input and absent-vs-null semantics. Shares one `ByModulatorTranslator` across order/select/dedup/group/project.

## Progress
- [x] Review + decomposition (1 iteration: iter1 PASS — Technical + Risk + Adversarial; 0 blockers)
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-07-22T10:30Z [ctx=info] Review + decomposition complete (strategic trio: Technical PASS iter1, Risk PASS iter1, Adversarial PASS iter1; 7 steps, reconciled tag `high`)
- [x] 2026-07-22T12:12Z [ctx=info] Step 1 complete (tip cf4698732d; WalkerContextResultShapingTest + GremlinToMatchStrategyTest + GremlinStepWalkerTest green)
- [x] 2026-07-22T12:24Z [ctx=info] Step 2 complete (tip 0597b30ef6; DedupGlobalStepRecogniserTest + GremlinStepWalkerTest green)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- 2026-07-22 (Phase A, T2): `GremlinStepWalker.buildResult` does not yet wire `returnDistinct` / `groupBy` / `orderBy` / `limit` / `skip` into `MatchPlanInputs` even though the record supports them — foundation step must extend `WalkerContext` and `buildResult` together.
- 2026-07-22 (Phase A, R2): `handleHardwiredCountOnClass*` lives only on `SelectExecutionPlanner` (private static); `MatchExecutionPlanner` has no count short-circuit hook yet — extraction is a real new seam, not a re-export.
- 2026-07-22 (Phase A, T4): Frozen `design.md` §empty-input still tags projection work as "Track 5" and conflates `values` with `dropNullRows`; track plan's `dropOnAbsent` is correct — Phase-4 reconciliation only.

## Decision Log
<!-- Continuous-log. -->
- 2026-07-22 (Phase A, A1): **Aggregate-over-`values` re-pointing uses walker state, not cursor rewind.** `PropertiesStepRecogniser` records the last single-key field-access `SQLExpression` on `WalkerContext`; aggregate recognisers consume it for `mean`/`sum`/etc. Decline when the prefix is not exactly one property key. Avoids a second pass over the step list.
- 2026-07-22 (Phase A, T1/R1): **`as(label)` + absent-vs-null are independent load-bearing seams.** Alias propagation must surface user labels for `select`/`dedup`/`where(P)`; `GremlinProjectionAssembler` must use `EntityImpl.hasProperty` at boundary iteration for `valueMap`/`values` — `Result.getProperty` alone is insufficient.
- 2026-07-22 (Phase A, R2): **Count short-circuit: extract `handleHardwiredCountOnClass*` to a package-visible helper invoked from `MatchExecutionPlanner` after pattern build; decline shapes fall through to `YTDBGraphCountStrategy` (already ordered after `GremlinToMatchStrategy`).**
- 2026-07-22 (Phase A, R3): **Value-side `by(__.count())` / `by(__.fold())` routes through the Track 5 sub-walker (`walkChild` + capture adapter), not a fresh `WalkerContext`.**

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
3. **`GremlinProjectionAssembler` + projection recognisers** — `PropertiesStep` (`values`), `PropertyMapStep` (`valueMap`/`elementMap`), `SelectStep`, `ProjectStep`; `EntityImpl.hasProperty(key)` absent-vs-null classification (R1); `dropOnAbsent` for single-key `values`; pin boundary output type per terminal (`MAP` / `SINGLE_VALUE`). Accept `PropertyType.VALUE` and `PropertyType.PROPERTY` on `PropertiesStep` (T3). Detail: Plan of Work item 2, Decision Log T1/R1. — `risk: high` *(depends on Steps 1–2)*
4. **`ByModulatorTranslator`** (shared `match/builder/`) — key-side (`by("k")`, `by(T.id)`, `by(T.label)`, `__.values/id/label` unwraps, `Order.asc/desc`) and value-side (`by(__.count())`, `by(__.fold())`, …) via sub-walker capture (R3); declines edges/aggregates/lambdas/`Order.shuffle`/per-label-count-mismatch. Detail: Plan of Work item 3. — `risk: high` *(depends on Step 1)*
5. **`OrderGlobalStep` + `RangeGlobalStep`** — `SQLOrderBy` (`Order.shuffle` declines); `SQLSkip` + `SQLLimit` (`range` → `limit = high - low`, unbounded high → skip-only). Detail: Plan of Work item 4. — `risk: medium` *(depends on Steps 1, 4)*
6. **Aggregate recognisers + count short-circuit** — `count`/`sum`/`min`/`max`/`mean`/`group`/`groupCount` → `SQLProjection` + `SQLGroupBy`; `dropNullRows` per output type; consume `lastPropertyProjection` for `values("age").mean()` (A1); extract `handleHardwiredCountOnClass*` to shared helper, invoke from `MatchExecutionPlanner` (R2). Multi-label / non-polymorphic counts decline to `YTDBGraphCountStrategy`. Detail: Plan of Work items 5–6. — `risk: high` *(depends on Steps 1, 3, 4)*
7. **Boundary projection + parity tests** — complete `YTDBMatchPlanStep` `MAP` / `SINGLE_VALUE` / `SCALAR` payload emission; `dropNullRows` row loop + `dropOnAbsent` entity check (R5); `group`/`groupCount` MAP accumulation; extend `EdgeTraversalEquivalenceTest` / new projection-equivalence tests for absent-vs-null, empty-input aggregates, order, pagination, dedup. Detail: Validation and Acceptance. — `risk: high` *(depends on Steps 1–6)*

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
