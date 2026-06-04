<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 9: Explain and Profile integration for Gremlin steps

## Purpose / Big Picture

After this track lands, two operator-facing surfaces expose the SQL execution plans that back Gremlin traversals. Calling `toString()` on a Gremlin traversal renders every YTDB-side SQL step with a `[plan: <one-line-summary>]` suffix derived from the underlying `ExecutionPlan.prettyPrint(2, 80)`. Adding `.profile()` to the traversal makes each YTDB-side SQL step prepend `PROFILE ` to its generated SQL, capture per-operator timings from the resulting `SQLProfiler` output, and push those timings to the following TinkerPop `ProfileStep.getMetrics()` so `.profile().next()` renders the YTDB plan as nested metrics in the standard TinkerPop visualizer surface. The integration covers both Path A (post-PR-1038 `YTDBMatchPlanStep` translated traversals) and Path B (fallback `YTDBGraphStep` / `YTDBClassCountStep` chain).

The Explain side is unconditional (always available via `toString()`) but lazy (one `EXPLAIN` query per step instance, cached in `private volatile String explainCache`). The Profile side is opt-in via `.profile()` in the traversal; absent that step, `YTDBQueryMetricsStrategy` never flips `profilingEnabled` and no PROFILE prefix lands on the SQL. The two surfaces compose with the OTel emission pillar without coupling: a profiled traversal emits one OTel span per public Gremlin call carrying `db.youtrackdb.profile.enabled = true` (low-cardinality boolean) so operators can correlate the span with the corresponding TinkerPop `Metrics` tree.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Covers D45 (Explain/Profile integration: `toString()` plan rendering on `YTDBGraphStep` / `YTDBClassCountStep` / `YTDBMatchPlanStep`, TinkerPop `ProfileStep` detection at strategy time, `PROFILE ` keyword injection on SELECT and MATCH, per-operator `Metrics` handoff at `step.close()` via `ExecutionPlanMetricsAdapter`, low-cardinality `db.youtrackdb.profile.enabled` OTel span attribute).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

The track owns code under `core/src/main/java/com/jetbrains/youtrackdb/internal/core/profiler/monitoring/tinkerpop_profile/` plus targeted edits to the three Gremlin step classes that perform SQL execution. Track 1 provides the `QueryDetails.getProfileEnabled(): Optional<Boolean>` accessor consumed by `OTelQueryMetricsListener` in Track 3. PR #1038 provides `YTDBMatchPlanStep` (boundary step for translated traversals); Track 1's existing extension already adds a `Classification` field and `getClassification()` accessor to that step, so this track extends the same class with the new `explainCache` slot, `profilingEnabled` slot, `setProfilingEnabled(boolean)` setter, and `toString()` override. The `YTDBQueryMetricsStrategy.apply(...)` walk that already injects `YTDBQueryMetricsStep` extends to detect `ProfileStep` presence and flip the flag on each YTDB-side SQL step.

Concrete deliverables:

1. `private volatile String explainCache` slot plus `toString()` override on `YTDBGraphStep`, `YTDBClassCountStep`, and `YTDBMatchPlanStep`. The override calls `super.toString()` first to preserve the standard TinkerPop step rendering, then appends ` [plan: <one-line-summary>]` where the summary is derived from `ExecutionPlan.prettyPrint(2, 80)` collapsed to one line by replacing `\n` with ` | `. The `EXPLAIN`-prefixed query runs once per step instance against the bound session; if no session is bound (debugger inspection on an unbound step), `toString()` falls back to the standard rendering without the suffix and leaves `explainCache` at `null` so a later invocation produces the suffix once the session is bound. The `volatile` modifier protects against benign first-write races (two threads computing the same deterministic `EXPLAIN` result and racing to write the cache).
2. `private boolean profilingEnabled` slot plus `setProfilingEnabled(boolean)` package-private setter on the same three step classes. The setter is called from `YTDBQueryMetricsStrategy.apply(...)`; no public host surface exposes the flag because TinkerPop's `.profile()` is the contract.
3. `YTDBQueryMetricsStrategy.apply(...)` walk extension: after the existing step-list walk that injects `YTDBQueryMetricsStep`, do a second walk to detect any `ProfileStep` instance. If found, call `setProfilingEnabled(true)` on every `YTDBGraphStep` / `YTDBClassCountStep` / `YTDBMatchPlanStep` in the same traversal AND record the index of each `ProfileStep` in a `Map<Integer, ProfileStep> profileStepFollowers` field on each YTDB-side step so `step.close()` can resolve its immediately following `ProfileStep` without re-walking the list. The `Map` slot is package-private and populated only when at least one `ProfileStep` is present.
4. `PROFILE ` keyword injection at SQL-string construction time on each step. The step's SQL builder (inline in `YTDBGraphStep` / `YTDBClassCountStep` for Path B, inside `YTDBMatchPlanStep`'s MATCH-statement builder for Path A) checks `profilingEnabled && (statement instanceof SQLSelectStatement || statement instanceof SQLMatchStatement)` and prepends `PROFILE ` to the rendered SQL when both conditions hold. Non-SELECT shapes (theoretical for Path B writes; Path A is always read) treat the flag as a no-op because `PROFILE` only applies to SELECT and MATCH at parse time.
5. `ExecutionPlanMetricsAdapter.toTinkerPopMetrics(ExecutionPlan)` new static utility in `core/.../profiler/monitoring/tinkerpop_profile/ExecutionPlanMetricsAdapter.java`. Maps each `SQLExecutionPlanOperator` in the YTDB plan to a nested `MutableMetrics` instance with operator name, ID, and duration (in milliseconds via `TimeUnit.MILLISECONDS.convert(plan.getCost(), TimeUnit.NANOSECONDS)`). YTDB-specific fields (`indexUsed`, `rowsExamined`, `rowsReturned`) attach to each nested `MutableMetrics` via the `annotations` map. The adapter is pure: input is the YTDB `ExecutionPlan`, output is one root `MutableMetrics`; no I/O, no global state.
6. `step.close()` extension on each of the three Gremlin step classes: when `profilingEnabled` is true AND the resolved `profileStepFollowers` map contains an entry for this step, call `ExecutionPlanMetricsAdapter.toTinkerPopMetrics(plan)` and append the result via `profileStepFollower.getMetrics().addNested(metrics)` (TinkerPop's standard `MutableMetrics.addNested(...)` API). The append runs inside the existing listener-fire isolation wrapper (`Exception | LinkageError | AssertionError`) so an adapter throw is caught, logged at WARN, and does not unwind the consumer's `.profile().next()` iteration.
7. `QueryDetails.getProfileEnabled(): Optional<Boolean>` default accessor on the SPI (added by Track 1 alongside the other accessors; this track populates it on the Gremlin fire side). `YTDBQueryMetricsStep.close()` reads `step.isProfilingEnabled()` on the immediately preceding YTDB-side SQL step (resolved by walking back through the step list once; cached on the metrics step at injection time) and surfaces the value through `getProfileEnabled()`. `OTelQueryMetricsListener.queryFinished` sets `db.youtrackdb.profile.enabled = true` as a span attribute when the accessor returns `Optional.of(true)`; the attribute is omitted otherwise to preserve the low-cardinality cost shape.

## Plan of Work

Six edits.

The first edit adds the `private volatile String explainCache` slot, the `toString()` override producing the `[plan: ...]` suffix, and the lazy EXPLAIN-query caching path on each of `YTDBGraphStep`, `YTDBClassCountStep`, and `YTDBMatchPlanStep`. The `toString()` override calls `super.toString()` to preserve TinkerPop's step rendering, then concatenates the suffix only when the cache is populated or computable. JUnit test (`YTDBExplainToStringTest` in the existing `core` test tree): a bound-session step produces the suffix on first `toString()` and identical output on subsequent calls; an unbound step produces only the standard rendering and leaves the cache `null`; concurrent `toString()` from two threads against an unbound-then-bound step lands a single cached value with no NullPointerException (benign first-write race verified via `Thread.startVirtualThread(...)` pair-up).

The second edit adds the `private boolean profilingEnabled` slot, the `setProfilingEnabled(boolean)` package-private setter, the `Map<Integer, ProfileStep> profileStepFollowers` slot on each step, and the strategy-time walk extension in `YTDBQueryMetricsStrategy.apply(...)` that detects `ProfileStep` injection and flips the flag plus records the follower index. The strategy walk runs once after the existing `YTDBQueryMetricsStep` injection so TinkerPop's category boundary still guarantees the metrics-step injection runs after PR #1038's translator strategy (per §"Strategy ordering: metrics step after translator"). JUnit test (`YTDBProfileStrategyDetectionTest`): a traversal with `.profile()` flips `profilingEnabled = true` on every YTDB-side SQL step; a traversal without `.profile()` leaves the flag at `false`; the follower-index map populates correctly for both single-step (Path A) and multi-step (Path B) traversal shapes.

The third edit wires PROFILE-keyword injection at SQL-string construction time. For `YTDBGraphStep` / `YTDBClassCountStep` the SQL builder runs inside the step's `processNextStart()` execution loop; the check `profilingEnabled && (statement instanceof SQLSelectStatement || statement instanceof SQLMatchStatement)` prepends `PROFILE ` to the constructed SQL string when true. For `YTDBMatchPlanStep` the MATCH-statement builder uses the same guard. JUnit test (`YTDBProfileKeywordInjectionTest`): a SELECT step with `profilingEnabled = true` runs `PROFILE SELECT FROM ...`; a MATCH step with the flag set runs `PROFILE MATCH ...`; a synthetic non-SELECT step (kept for the defensive guard verification) treats the flag as a no-op and runs its original SQL.

The fourth edit adds `ExecutionPlanMetricsAdapter.toTinkerPopMetrics(ExecutionPlan)` plus the `step.close()` extension on each of the three step classes that appends the resulting `Metrics` to the recorded `ProfileStep` follower. The adapter mapping is unit-tested separately (`ExecutionPlanMetricsAdapterTest`): a single-operator plan produces one nested `MutableMetrics` with the operator's name, ID, and duration; a multi-operator plan produces nested metrics in plan order; YTDB-specific fields land in the `annotations` map without overflowing the per-metric attribute budget. Integration test (`YTDBProfileMetricsHandoffTest`): a `.profile().next()` call on a Path A translated traversal produces a `Metrics` tree with one MATCH-plan branch under the top-level traversal node; a Path B fallback produces multiple per-step branches under the top-level node; assertions on `metrics.getNested().size()`, `metrics.getDuration(TimeUnit.MILLISECONDS)`, and `metrics.getAnnotation("indexUsed")` cover the mapping fidelity.

The fifth edit adds `QueryDetails.getProfileEnabled()` population on the Gremlin fire side (`YTDBQueryMetricsStep` reads `isProfilingEnabled()` on its preceding YTDB-side SQL step and surfaces the value through the accessor) plus the `OTelQueryMetricsListener` attribute-set path that reads `details.getProfileEnabled().orElse(false)` and sets `db.youtrackdb.profile.enabled = true` on the span when true. JUnit test (`OTelProfileSpanAttributeTest` in Track 6a-aligned test tree): a profiled traversal emits a span with the attribute present and value `true`; an unprofiled traversal emits a span with the attribute absent (omission preserves the low-cardinality budget).

The sixth edit adds two JUnit tests covering the OTel-correlation path end-to-end and the multi-statement-script asymmetry: `OTelProfiledTraversalCorrelationTest` runs a profiled traversal under an in-memory OTel exporter plus a `.profile().next()` call on the same traversal, asserts the OTel span carries `db.youtrackdb.profile.enabled = true`, and asserts the TinkerPop `Metrics` tree carries the expected per-operator durations. `YTDBProfileScriptAsymmetryTest` runs `db.executeSqlScript("PROFILE SELECT FROM User")` directly and asserts no `ProfileStep` is involved (scripts route through `DatabaseSessionEmbedded.computeScript(...)` outside the TinkerPop pipeline; the asymmetry is the documented behaviour, not a bug).

Ordering: edits 1 + 2 are independent and can land in either order. Edit 3 depends on edit 2 (PROFILE injection consumes the `profilingEnabled` flag). Edit 4 depends on edits 2 + 3 (the metrics handoff needs both the follower-index map and the per-operator timings produced by the PROFILE-prefixed query). Edits 5 + 6 depend on the prior edits but can be drafted in parallel with edit 4's implementation work.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

<!-- Phase B placeholder — implementation logs land here once decomposition completes. -->

## Out of Scope / Inter-Track Dependencies

In scope:
- `core/src/main/java/.../profiler/monitoring/tinkerpop_profile/ExecutionPlanMetricsAdapter.java` (new static utility mapping YTDB `ExecutionPlan` to TinkerPop `MutableMetrics` with nested per-operator metrics + `annotations` map).
- `core/src/main/java/.../profiler/monitoring/YTDBQueryMetricsStrategy.java` (extension: second walk after the existing `YTDBQueryMetricsStep` injection that detects `ProfileStep` presence and flips `profilingEnabled` on each YTDB-side SQL step plus records follower indices).
- `core/src/main/java/.../sql/parser/YTDBGraphStep.java` (new `explainCache`, `profilingEnabled`, `profileStepFollowers` slots; `toString()` override; `setProfilingEnabled(...)` setter; PROFILE-keyword injection in the SQL builder; `step.close()` extension calling `ExecutionPlanMetricsAdapter` and `MutableMetrics.addNested(...)`).
- `core/src/main/java/.../sql/parser/YTDBClassCountStep.java` (same surface as `YTDBGraphStep`).
- `core/src/main/java/.../sql/parser/YTDBMatchPlanStep.java` (extended with the same surface; the Track 1 `Classification` field and `getClassification()` accessor already exist on this class, so this track adds the explain/profile slots alongside them).
- `core/src/main/java/.../profiler/monitoring/YTDBQueryMetricsStep.java` (extension: `getProfileEnabled()` population reading the preceding YTDB-side SQL step's `isProfilingEnabled()`).
- `youtrackdb-opentelemetry/src/main/java/.../OTelQueryMetricsListener.java` (extension: `db.youtrackdb.profile.enabled` span attribute set when `details.getProfileEnabled().orElse(false)` is true).
- `core/src/test/java/.../YTDBExplainToStringTest.java` (new test).
- `core/src/test/java/.../YTDBProfileStrategyDetectionTest.java` (new test).
- `core/src/test/java/.../YTDBProfileKeywordInjectionTest.java` (new test).
- `core/src/test/java/.../ExecutionPlanMetricsAdapterTest.java` (new test).
- `core/src/test/java/.../YTDBProfileMetricsHandoffTest.java` (new test).
- `core/src/test/java/.../YTDBProfileScriptAsymmetryTest.java` (new test).
- `youtrackdb-opentelemetry/src/test/java/.../OTelProfileSpanAttributeTest.java` (new test).
- `youtrackdb-opentelemetry/src/test/java/.../OTelProfiledTraversalCorrelationTest.java` (new test).

Out of scope:
- `QueryDetails.getProfileEnabled(): Optional<Boolean>` accessor declaration (Track 1 owns the SPI extension; this track populates it on the Gremlin fire side and consumes it on the OTel side).
- PROFILE-keyword injection for direct `db.command(...)` / `db.query(...)` / `db.execute(...)` SQL calls (those calls already accept `PROFILE` syntactically; this track owns only the Gremlin-strategy-driven injection that the spec's `g.V()....profile()` invocation requires).
- TinkerPop `ProfileStep` injection itself (TinkerPop's `ProfileStrategy` injects the step when `.profile()` is in the traversal; this track only detects the resulting injection at strategy time).
- Multi-statement script profile detection (scripts route through `DatabaseSessionEmbedded.computeScript(...)` outside the TinkerPop strategy pipeline; per-script-statement profile timing surfaces via `SQLProfiler` output directly, documented as the script-vs-traversal asymmetry).
- Per-plan-operator OTel span events (the per-operator timings surface via TinkerPop `Metrics` only; the OTel span carries the low-cardinality boolean attribute that correlates to the `Metrics` tree, not the timings themselves — preserves the "Per-operator execution-plan timing inside a query span" Non-Goal carve-out).

Inter-track dependencies:
- Depends on Track 1 (`QueryDetails.getProfileEnabled(): Optional<Boolean>` accessor declared alongside the other accessors).
- Depends on Track 3 (`OTelQueryMetricsListener` exists and reads `QueryDetails` accessors to build the OTel span).
- Depends on PR #1038 on `develop` (the `YTDBMatchPlanStep` boundary step exists with the Track 1 `Classification` field already in place; Track 9 adds the explain/profile slots to the same class).
- Provides for nothing downstream that is not already owned by other tracks. Track 6a / 6c test infrastructure does not need to extend to cover Track 9; tests in the new test classes listed above cover the integration end-to-end.
