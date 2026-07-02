<!-- workflow-sha: 03eac656fa115a8e6af3f53d8805d8f16f3bad50 -->
# Track 1: Expose the finished query's execution plan on the metrics listener

## Purpose / Big Picture
A `QueryMetricsListener` sees the execution plan of the Gremlin query it was notified about, so a consumer — DNQ, the JetBrains Kotlin entity-store layer that runs Gremlin queries against YouTrackDB — can walk the plan's steps and detect an unindexed full scan.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

YTDB-1191 asks for a way for database clients to inspect the execution plan of a finished Gremlin query. Query monitoring already fires a per-query `queryFinished` callback carrying a `QueryDetails` object; this track adds the plan to that object. The plan is produced anyway during query execution, so the track captures the already-built plan on the query's source step and hands it to the listener at reporting time — no second `EXPLAIN` round-trip and no new client-facing API surface. A draft of this change exists as a saved patch (`/workspaces/notes/claude/workspaces/youtrackdb/develop/logs/ytdb-1191-draft.patch`); this track reproduces its mechanism plus the correctness fixes the research surfaced.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Track-canonical live decision carrier (D7). Seeded from the research log
(design_gate=no). -->

#### D1: Expose the plan through the internal `QueryDetails` listener surface, not a new public-API type
- **Alternatives considered**: (a) a new read-only plan view under `com.jetbrains.youtrackdb.api`; (b) a separate accessor off the transaction.
- **Rationale**: `QueryMetricsListener`, its `QueryDetails` inner interface, and the `YTDBTransaction.withQueryListener(...)` registration are all in `internal.*`. Any consumer implementing the listener already depends on internal packages, so returning an internal `ExecutionPlan` adds no coupling it does not already have. The existing `YTDBGraphQuery.usedIndexes()` already couples to internal `ExecutionPlan`/`ExecutionStep`, so this is a matched, not a new, dependency. Invocation is embedded-only — no wire serialization crosses a client boundary.
- **Risks/Caveats**: Consumers touch internal types (`ExecutionPlan`, `ExecutionStep`). Acceptable because the whole monitoring surface is already internal. The new method is a `@Nullable default` returning `null`, so no existing `QueryDetails` implementer breaks.
- **Implemented in**: this track (step references added during execution).

#### D2: Capture the executed query's plan, not a second EXPLAIN
- **Alternatives considered**: reuse `YTDBGraphQuery.explain()`, which runs a separate `EXPLAIN <query>` and reads its plan (the path `usedIndexes()` takes).
- **Rationale**: A normal SELECT returns a `LocalResultSet` constructed with the fully-built `InternalExecutionPlan` (`SQLSelectStatement.execute` builds `createExecutionPlan(...)` before returning), so `resultSet.getExecutionPlan()` is populated at execute time, before any row streams, at near-zero extra cost. That holds on the populating path — the cache-miss run that actually builds and executes the plan; see D5 for the cache-hit case. Capturing here yields the plan that actually ran — what the ticket wants — and avoids the second-round-trip cost and the re-planning of a separate `EXPLAIN`.
- **Risks/Caveats**: A cache-hit replay surfaces `null` — see D5. A downstream short-circuit that never pulls from the source (e.g. `limit(0)`) leaves the plan `null`, which is correct (the source query did not execute).
- **Implemented in**: this track.

#### D3: Capture only the root source step's plan (documented locality contract)
- **Alternatives considered**: recurse into child traversals via `TraversalHelper.getStepsOfAssignableClassRecursively` to capture plans from `where`/`union`/`local` subqueries too.
- **Rationale**: `YTDBQueryMetricsStrategy` appends the metrics step to the **root** traversal, and a root `V()`/`E()` becomes the `YTDBGraphStep` at that root's start; `getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` iterates the root's direct steps only. Capturing the root source step's plan matches the ticket's use case (detect the primary query's full scan). Recursing raises "which plan / how to merge multiple plans" with no consumer that needs it yet.
- **Risks/Caveats**: A plan-backed scan inside a child traversal (`where`/`union`/`local`), or a non-`V()`/`E()`-rooted root traversal (e.g. `g.inject(...)`), is not captured — the former surfaces the root source's plan or `null`, the latter `null`. Record this in the accessor javadoc. Broadening later reuses the existing recursive helper.
- **Implemented in**: this track.

#### D4: Clear the retained plan on `reset()` and leave it null on the by-id path
- **Alternatives considered**: assign `lastExecutionPlan` only on the query path and never clear it (the draft's original shape).
- **Rationale**: The by-id branch of `YTDBGraphStep.elements()` runs no query and never assigns the field; there is no `reset()` clear. A reused or re-iterated traversal that switches from the query branch to the by-id branch would otherwise report a **stale** plan from the earlier run. Clearing in `reset()` and keeping the by-id path null-valued removes the stale-plan hazard for one cheap line, and keeps the step ignorant of monitoring.
- **Risks/Caveats**: None material; `reset()` already runs on traversal reuse.
- **Implemented in**: this track.

#### D5: Accept the transaction-result-cache replay limitation (document + test)
- **Alternatives considered**: retain the plan across the cache entry's close so cache-hit replays still surface it.
- **Rationale**: `transaction.query(...)` may hit the per-transaction result cache. `DatabaseSessionEmbedded.buildView()` builds a `CachedResultSetView` from `entry.getPlan()`, but `CachedEntry.close()` sets `plan = null` once the populating stream closes, so a cache-hit replay of the same query in the same transaction captures `null`. Because the cache is per-transaction, the **first** execution of any query shape is always a plan-capturing populating (cache-miss) run, and a cache-hit replay re-serves cached rows rather than re-executing a plan — so a `null` plan on replay is semantically defensible. Retaining the plan across cache close is a deeper cache change with no consumer that needs it.
- **Risks/Caveats**: A consumer that inspects only a repeated identical query within one transaction sees `null` on the repeats. DNQ's full-scan detection is a per-query-shape property observable on the populating run. Pin the behavior with a test.
- **Implemented in**: this track.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation
The change lives entirely in `core`, across the query-monitoring machinery and the Gremlin graph source step. No public API, storage, WAL, or schema surface is touched. Terminology and current state:

- **`QueryMetricsListener`** (`internal.common.profiler.monitoring`) — a transaction-level listener with a single `queryFinished(QueryDetails, startedAtMillis, executionTimeNanos)` callback. Its `QueryDetails` inner interface today exposes `getQuery()`, `getQuerySummary()`, and `getTransactionTrackingId()`. Registered on a transaction via `YTDBTransaction.withQueryListener(...)`; `NO_OP` is the default.
- **`YTDBQueryMetricsStep`** (same package) — a transparent TinkerPop `AbstractStep` that times the traversal and calls `queryFinished` when the traversal closes. It is appended to the **root** traversal by `YTDBQueryMetricsStrategy` (a `FinalizationStrategy`) only when query metrics are enabled and the graph is embedded.
- **`YTDBGraphStep`** (`internal.core.gremlin.traversal.step.sideeffect`) — the YTDB replacement for TinkerPop's source `GraphStep` (produced by `g.V()` / `g.E()`). Its `elements()` method has two branches: a **by-id** branch (`this.ids` non-empty) that filters elements by id and runs no query, and a **query** branch that builds a `YTDBGraphQuery`, calls `query.execute(session)`, and streams the results. `elements()` is the step's `iteratorSupplier`, invoked once per step execution.
- **`ExecutionPlan`** (`internal.core.query`) — a `Serializable` interface: `getSteps()` returns `List<ExecutionStep>` (internal), `prettyPrint(int, int)` returns a `String` with no session, and `toResult(DatabaseSessionEmbedded)` needs a live session. `getSteps()` and `prettyPrint()` are the read-only, session-free inspection surface a listener uses; `toResult` is unsafe from a listener after the query closes.
- **`ResultSet.getExecutionPlan()`** (`internal.core.query`) — `@Nullable`; for a normal SELECT the concrete `LocalResultSet` / `ExecutionResultSet` returns the plan built at execute time. `SelectExecutionPlan.close()` only propagates `close()` through the steps; it does not null the `steps` list, so `getSteps()`/`prettyPrint()` stay valid after the result set closes.
- **Prior art**: `YTDBGraphQuery.usedIndexes()` already walks an `ExecutionPlan`'s steps for `FetchFromIndexStep` to count index usage — the same shape of step-walk a scan detector uses.

**Deliverables:**
1. A `@Nullable default ExecutionPlan getExecutionPlan()` on `QueryDetails`.
2. Plan capture, accessor, and reset-clear on `YTDBGraphStep`.
3. Wiring in `YTDBQueryMetricsStep` that pulls the source step's plan into the `QueryDetails` it builds.
4. Regression tests.

## Plan of Work
The change is small and cohesive; the natural ordering is bottom-up so each edit compiles against the one below it.

1. **`QueryMetricsListener.QueryDetails`** — add `@Nullable default ExecutionPlan getExecutionPlan()` returning `null`. The default keeps every existing implementer compiling and makes the accessor opt-in. Javadoc states the read-only contract (inspect `getSteps()`/`prettyPrint()`; do not call `toResult(session)` from the listener) and that `null` means no plan was captured.
2. **`YTDBGraphStep`** — add a `@Nullable ExecutionPlan lastExecutionPlan` field; in the query branch of `elements()`, hold the `ResultSet` and assign `lastExecutionPlan = resultSet.getExecutionPlan()` before streaming; expose `@Nullable ExecutionPlan getLastExecutionPlan()`; clear the field in `reset()` (D4); leave it untouched (null) on the by-id branch (D4). Capture is unconditional — the step carries no knowledge of whether monitoring is enabled.
3. **`YTDBQueryMetricsStep`** — add `capturedExecutionPlan()` that resolves the source step via `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` and reads `getLastExecutionPlan()`, returning `null` when absent; override `getExecutionPlan()` in the anonymous `QueryDetails` it builds to return it.
4. **Tests** — add regression coverage (see `## Validation and Acceptance`).

Ordering constraint: step 3 depends on the accessor from step 2 and the interface method from step 1. Invariant to preserve: `YTDBGraphStep` and the query-execution path stay ignorant of monitoring — capture is a plain field write, and nothing reads the plan unless a listener is registered.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B appends one block per completed step. Empty at Phase 1. -->

## Validation and Acceptance
The track lands when a registered `QueryMetricsListener` can inspect the plan of the query it was notified about, and the documented limitations hold. Behavioral acceptance criteria:

- A plan-backed scan query (`g.V().has(unindexedProp, ...)`) surfaces a **non-null** plan to the listener whose steps contain no `FetchFromIndexStep` (a full scan).
- An indexed query surfaces a non-null plan whose steps contain a `FetchFromIndexStep`.
- A by-id lookup (`g.V(id)`) surfaces a **null** plan (the by-id branch runs no query).
- The plan is readable inside the `queryFinished` callback: `getSteps()` and `prettyPrint()` return the plan structure after the query's result set has closed.
- A cache-hit replay of the same query in the same transaction surfaces a **null** plan (documented limitation, D5).
- After `YTDBGraphStep.reset()`, `getLastExecutionPlan()` returns `null` — no stale plan leaks across traversal reuse (D4).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths. -->

## Artifacts and Notes
Draft patch (prior art, not in the working tree): `/workspaces/notes/claude/workspaces/youtrackdb/develop/logs/ytdb-1191-draft.patch`.

## Interfaces and Dependencies
**In scope:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryMetricsListener.java`
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/sideeffect/YTDBGraphStep.java`
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStep.java`
- One or both of `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBTransactionMetricsListenerTest.java` and `YTDBQueryMetricsStrategyTest.java` (regression tests).

**Out of scope:**
- Any `com.jetbrains.youtrackdb.api.*` type (no public API change).
- The transaction result cache (`CachedEntry` / `CachedResultSetView`) — the replay-null behavior is accepted, not fixed (D5).
- Child-traversal / non-graph-rooted plan capture (D3).

**Dependencies:** none — no prior track supplies prerequisites, no downstream track consumes this one (single-track change).

**Relevant signatures:**
- `QueryMetricsListener.QueryDetails` — add `@Nullable default ExecutionPlan getExecutionPlan()`.
- `YTDBGraphStep.getLastExecutionPlan()` → `@Nullable ExecutionPlan`; `YTDBGraphStep.reset()` clears the field.
- `TraversalHelper.getFirstStepOfAssignableClass(Class, Traversal.Admin)` → `Optional<S>` (root direct steps only).
- `ResultSet.getExecutionPlan()` → `@Nullable ExecutionPlan`.

## Invariants & Constraints
- A registered listener receives a non-null `ExecutionPlan` for a plan-backed root scan/index query and `null` for a by-id lookup — verified by the scan / indexed / by-id acceptance tests.
- `getSteps()` and `prettyPrint()` on the retained plan succeed after the query's result set closes; `toResult(session)` is not called from the listener — verified by the post-close-readable test and enforced by the accessor javadoc contract.
- A cache-hit replay of the same query in the same transaction yields a `null` plan — verified by the cached-query replay test (D5).
- `getLastExecutionPlan()` returns `null` after `reset()` — verified by the reset test (D4).
- Plan capture is independent of whether monitoring is enabled: `YTDBGraphStep` holds no reference to the monitoring machinery, and the capture is a single field write — process constraint, verified by inspection (no monitoring import in `YTDBGraphStep`).
- The capture adds only O(1) work (one nullable field read/write) on the source-step execution path, outside the row-streaming loop — performance constraint, paired with the existing `QueryMetricsOverheadBenchmark`.
