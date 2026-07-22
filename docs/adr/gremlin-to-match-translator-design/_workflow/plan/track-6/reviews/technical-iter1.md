<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
gate: PASS
-->

## Findings

### T1 [should-fix]
**Location**: track-6.md Plan of Work item 1; `MatchPatternBuilder.java`; `PatternNode.java`
**Issue**: `as(label)` propagation is specified as updating `SQLMatchFilter.alias` via `MatchPatternBuilder.alias(...)`, but the builder today has no `alias(...)` method and pattern nodes carry alias only as the map key in `Pattern.aliasToNode`. The decomposer must pin whether alias propagation is (a) a new fluent `alias(String)` on the most recently added node/edge, or (b) re-registration of the same alias key with an updated display alias — and how that interacts with `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX` rows vs user labels.
**Proposed fix**: Step 1 pins the concrete API: add `MatchPatternBuilder.setAlias(String alias, String label)` or track a parallel `Map<String,String> aliasLabels` on `WalkerContext`, wired into `MatchPlanInputs` only if the planner consumes it; verify against `ReturnMatchElementsStep` / `$matched` resolution in PSI or source read before writing the step body.

### T2 [should-fix]
**Location**: track-6.md; `GremlinStepWalker.buildResult` (:321-355); `MatchPlanInputs` builder
**Issue**: `buildResult` today sets only pattern, alias classes/filters, notMatchExpressions, and the three return lists — it does **not** pass `returnDistinct`, `groupBy`, `orderBy`, `limit`, or `skip`. `WalkerContext` has no fields for those yet. Step 1 must extend both sides together or later recognisers have nowhere to write.
**Proposed fix**: Foundation step explicitly lists: new `WalkerContext` fields + `RecognitionContext` accessors + `buildResult` `.returnDistinct(...)` / `.groupBy(...)` / `.orderBy(...)` / `.limit(...)` / `.skip(...)` wiring, verified against `MatchExecutionPlanner` ctor that copies `MatchPlanInputs`.

### T3 [suggestion]
**Location**: track-6.md Interfaces — `SelectStep`, `PropertiesStep`, etc.
**Issue**: TinkerPop step classes live in the fork JAR (`org.apache.tinkerpop.gremlin.process.traversal.step.map.*`), not under `core/`. Registry keys must use the exact runtime class (`PropertiesStep.class` for `values()`, `PropertyMapStep` for `valueMap`/`elementMap`). `TraversalFilterStepRecogniser` already documents the `values→properties` optimizer rewrite — projection recognisers must accept both return types on `PropertiesStep` children the way `NotStepRecogniser` does for `hasNot`.
**Proposed fix**: Note in Step 3 decomposition: register `PropertiesStep.class`; branch `PropertyType.VALUE` vs `PropertyType.PROPERTY` like presence recognisers.

### T4 [suggestion]
**Location**: design.md §"Track 5 commitment" vs track-6.md `dropNullRows` for `values`
**Issue**: Frozen design §Aggregation empty-input (lines 1811-1814) says `values(key)` → `dropNullRows = true`, but the Track 5 commitment and track-6 Context say absent-vs-null uses a separate `dropOnAbsent` flag (entity-layer `hasProperty`), not value-null dropping. Track 6 plan is correct; design numbering is stale (still says "Track 5" for projection work now in Track 6).
**Proposed fix**: Phase-4 reconciliation only; no plan change. Step 3/7 tests pin `dropOnAbsent` for absent `values("foo")` and present-null traverser emission separately from aggregate `dropNullRows`.

## Gate verdict
**PASS** — 0 blockers; T1/T2 are decomposition pins, not architectural blockers.
