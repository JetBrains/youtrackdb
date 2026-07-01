<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 5: Result shaping — labels + dedup, projections, order/pagination, aggregations

## Purpose / Big Picture
After this track, the four result-producing step families translate: step labels + dedup, projections (`select` / `values` / `valueMap` / `elementMap` / `project`), order/pagination, and aggregations (`count` / `sum` / `min` / `max` / `mean` / `group` / `groupCount`) — pinning the boundary output type per terminal step.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Merges the four result-producing step families. Adds `as(label)` propagation and `DedupStep` recognition; `GremlinProjectionAssembler` using `EntityImpl.hasProperty(key)` to distinguish absent from null-valued (the load-bearing "Track 5 commitment"); `OrderGlobalStep` + `RangeGlobalStep`; and aggregation recognition mapped to `SQLProjection` aggregates + `SQLGroupBy`, with the count short-circuit factored out of `SelectExecutionPlanner` and the `dropNullRows` / `dropOnAbsent` flags for empty-input and absent-vs-null semantics. Shares one `ByModulatorTranslator` across order/select/dedup/group/project.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

## Context and Orientation
By Track 5 the boundary step emits `ELEMENT` (vertex hops). This track adds the remaining four output types: `MAP` (`select` multi / `valueMap` / `elementMap` / `project` / `group` / `groupCount`), `SINGLE_VALUE` (`values` single-key), and `SCALAR` (`count` / `sum` / `min` / `max` / `mean`). Each terminal-step recogniser pins the type on the boundary at translation time.

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
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

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
**Out of scope:** union + list-shaping terminators (Track 6); approximate count (Phase 2); edge property extraction / edge-side labels (Phase 2 — design §"Out of scope").
**Inter-track dependencies:** depends on Track 4 (predicate algebra for `by`-value resolution) and Track 1 (`hasProperty` presence primitive shared with `IS DEFINED`). Supplies all five output types and the projection logic that Track 6's list-shaping terminators post-process.
**Signatures:** `EntityImpl.hasProperty(key)`; `Result.getProperty`; `SelectExecutionPlanner.handleHardwiredCountOnClass` / `handleHardwiredCountOnClassUsingIndex`; `session.countClass(name, polymorphic)`; `CountFromClassStep` / `CountFromIndexWithKeyStep`.

## Invariants & Constraints
<!-- Combined per-track invariants + constraints (conventions-execution.md §2.1 §14).
Added by workflow migration (#1145). Strategic invariants/constraints for this track remain
in implementation-plan.md § High-level plan (Architecture Notes) and this track's ## Decision
Log — the conservative migration retained the plan Architecture Notes rather than folding them here. -->

## Base commit
<!-- Phase B records the HEAD SHA here at session start; Phase C reads it to compute the
cumulative track diff (conventions-execution.md §2.1 §15). Added by workflow migration (#1145). -->
