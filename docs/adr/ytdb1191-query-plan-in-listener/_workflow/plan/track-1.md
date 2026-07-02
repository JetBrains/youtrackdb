<!-- workflow-sha: 03eac656fa115a8e6af3f53d8805d8f16f3bad50 -->
# Track 1: Expose the finished query's execution plan on the metrics listener

## Purpose / Big Picture
A `QueryMetricsListener` sees the execution plan of the Gremlin query it was notified about, so a consumer — DNQ, the JetBrains Kotlin entity-store layer that runs Gremlin queries against YouTrackDB — can walk the plan's steps and detect an unindexed full scan.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

YTDB-1191 asks for a way for database clients to inspect the execution plan of a finished Gremlin query. Query monitoring already fires a per-query `queryFinished` callback carrying a `QueryDetails` object; this track adds the plan to that object. The plan is produced anyway during query execution, so the track captures the already-built plan on the query's source step and hands it to the listener at reporting time — no second `EXPLAIN` round-trip and no new client-facing API surface. A draft of this change exists as a saved patch (`/workspaces/notes/claude/workspaces/youtrackdb/develop/logs/ytdb-1191-draft.patch`); this track reproduces its mechanism plus the correctness fixes the research surfaced.

## Base commit
5b073a9f6c14682b50842822fee3cb9982d164a0

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-07-02T14:12Z [ctx=unknown] Review + decomposition complete
- [x] 2026-07-02T15:06Z [ctx=unknown] Step 1 complete (commit 48177a5ea79bf1831ab78cf736fb7720e03a43e2)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- The cache-hit-replay→null behavior (D5) is conditional on `GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED`, which is off by default. With the cache off, a repeated identical query re-executes and surfaces a fresh non-null plan; the replay-null contract holds only with the cache enabled. The regression test enables the flag for its transaction. See Episodes §Step 1 and D5.

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

#### D4: Clear the retained plan on `reset()` (calling `super.reset()` first) and leave it null on the by-id path
- **Alternatives considered**: assign `lastExecutionPlan` only on the query path and never clear it (the draft's original shape).
- **Rationale**: The by-id branch of `YTDBGraphStep.elements()` runs no query and never assigns the field. A `YTDBGraphStep` instance's branch is fixed at construction (`ids` is set once and never changes), so a single instance never switches between the query and by-id branches — the staleness case is **re-iteration**: if the compiled traversal is `reset()` and re-run and the later run does not pull the source (or the source produces no plan), the field would otherwise still hold the earlier run's plan. Overriding `reset()` to call `super.reset()` **then** clear `lastExecutionPlan` removes that hazard; the by-id branch leaving the field null keeps a by-id-only step's plan correctly null. This keeps the step ignorant of monitoring.
- **Risks/Caveats**: The `reset()` override MUST call `super.reset()` first — omitting it breaks `GraphStep` re-iteration (the base class re-arms its element iterator in `reset()`). A test asserting only `getLastExecutionPlan() == null` after reset would pass even with a broken super-less override, so the acceptance must also cover re-iteration correctness (see `## Validation and Acceptance`).
- **Implemented in**: this track.

#### D5: Accept the transaction-result-cache replay limitation (document + test)
- **Alternatives considered**: retain the plan across the cache entry's close so cache-hit replays still surface it.
- **Rationale**: `transaction.query(...)` may hit the per-transaction result cache. `DatabaseSessionEmbedded.buildView()` builds a `CachedResultSetView` from `entry.getPlan()`, but `CachedEntry.close()` sets `plan = null` once the populating stream closes, so a cache-hit replay of the same query in the same transaction captures `null`. Because the cache is per-transaction, the **first** execution of any query shape is always a plan-capturing populating (cache-miss) run, and a cache-hit replay re-serves cached rows rather than re-executing a plan — so a `null` plan on replay is semantically defensible. Retaining the plan across cache close is a deeper cache change with no consumer that needs it.
- **Risks/Caveats**: A consumer that inspects only a repeated identical query within one transaction sees `null` on the repeats. DNQ's full-scan detection is a per-query-shape property observable on the populating run. Pin the behavior with a test — the test MUST fully iterate (drain) the first, populating query before repeating it, since `CachedEntry.close()` nulls the plan only on stream drain; a first query left un-drained would not yet have nulled the entry's plan and the replay-null assertion would not hold. **Precondition (found during execution):** the whole replay-null path is reached only when `GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED` is on, which is off by default — with the cache off `tx.getQueryResultCache()` returns `null`, the replay re-executes, and a fresh non-null plan surfaces. The regression test enables the flag on its transaction and restores it in a `finally` block. So the replay-null contract is "when the tx result cache is enabled," not unconditional.
- **Implemented in**: this track.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (2 findings, 2 accepted) — T1 (D4 rationale wrongly cited a query→by-id branch switch; reworded to the re-iteration staleness case) and T2 (cache-replay test must drain the first query first) folded into D4/D5 and the acceptance criteria.
- [x] Adversarial: PASS at iteration 1 (2 findings, 2 accepted) — A1 should-fix (mandate `super.reset()` before clearing + a re-iteration correctness test, not a bare post-reset-null assertion) and A2 suggestion (accessor valid only synchronously inside `queryFinished`; pinned in the step-1 javadoc contract). Grep-based (mcp-steroid unreachable); reference-accuracy caveats recorded in the review files.

## Context and Orientation
The change lives entirely in `core`, across the query-monitoring machinery and the Gremlin graph source step. No public API, storage, WAL, or schema surface is touched. Terminology and current state:

- **`QueryMetricsListener`** (`internal.common.profiler.monitoring`) — a transaction-level listener with a single `queryFinished(QueryDetails, startedAtMillis, executionTimeNanos)` callback. Its `QueryDetails` inner interface today exposes `getQuery()`, `getQuerySummary()`, and `getTransactionTrackingId()`. Registered on a transaction via `YTDBTransaction.withQueryListener(...)`; `NO_OP` is the default.
- **`YTDBQueryMetricsStep`** (same package) — a transparent TinkerPop `AbstractStep` that times the traversal and calls `queryFinished` when the traversal closes. It is appended to the **root** traversal by `YTDBQueryMetricsStrategy` (a `FinalizationStrategy`) only when query metrics are enabled and the graph is embedded.
- **`YTDBGraphStep`** (`internal.core.gremlin.traversal.step.sideeffect`) — the YTDB replacement for TinkerPop's source `GraphStep` (produced by `g.V()` / `g.E()`). Its `elements()` method has two branches: a **by-id** branch (`this.ids` non-empty) that filters elements by id and runs no query, and a **query** branch that builds a `YTDBGraphQuery`, calls `query.execute(session)`, and streams the results. `elements()` is the private helper that `vertices()`/`edges()` delegate to; the step's iterator supplier — the constructor-set lambda `() -> vertices()/edges()` — invokes it once per step execution.
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

1. **`QueryMetricsListener.QueryDetails`** — add `@Nullable default ExecutionPlan getExecutionPlan()` returning `null`. The default keeps every existing implementer compiling and makes the accessor opt-in. Javadoc states the read-only contract (inspect `getSteps()`/`prettyPrint()`; do not call `toResult(session)` from the listener), that `null` means no plan was captured, and that the returned plan is valid **only synchronously within the `queryFinished` callback** — the source step resolves it lazily and `reset()` clears it (the same synchronous-validity window the existing lazy `getQuery()` accessor has), so a listener must not retain the plan past the callback.
2. **`YTDBGraphStep`** — add a `@Nullable ExecutionPlan lastExecutionPlan` field; in the query branch of `elements()`, hold the `ResultSet` and assign `lastExecutionPlan = resultSet.getExecutionPlan()` before streaming; expose `@Nullable ExecutionPlan getLastExecutionPlan()`; override `reset()` to call `super.reset()` **first** and then clear `lastExecutionPlan` (D4 — omitting `super.reset()` breaks `GraphStep` re-iteration); leave the field untouched (null) on the by-id branch (D4). Capture is unconditional — the step carries no knowledge of whether monitoring is enabled.
3. **`YTDBQueryMetricsStep`** — add `capturedExecutionPlan()` that resolves the source step via `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` and reads `getLastExecutionPlan()`, returning `null` when absent; override `getExecutionPlan()` in the anonymous `QueryDetails` it builds to return it.
4. **Tests** — add regression coverage (see `## Validation and Acceptance`).

Ordering constraint: step 3 depends on the accessor from step 2 and the interface method from step 1. Invariant to preserve: `YTDBGraphStep` and the query-execution path stay ignorant of monitoring — capture is a plain field write, and nothing reads the plan unless a listener is registered.

## Concrete Steps
1. Expose the finished query's execution plan on the metrics listener: add `@Nullable default ExecutionPlan getExecutionPlan()` to `QueryMetricsListener.QueryDetails` (read-only + synchronous-validity javadoc); on `YTDBGraphStep` add the `lastExecutionPlan` field, capture `resultSet.getExecutionPlan()` in the query branch of `elements()`, add `getLastExecutionPlan()`, and override `reset()` to call `super.reset()` then clear the field (by-id branch leaves it null); wire `YTDBQueryMetricsStep.capturedExecutionPlan()` (via `getFirstStepOfAssignableClass`) into the anonymous `QueryDetails`. Add regression tests: scan → non-null plan (no `FetchFromIndexStep`); indexed → plan with `FetchFromIndexStep`; by-id → null; plan readable (`getSteps()`/`prettyPrint()`) inside `queryFinished` post-close; cache-hit replay → null (drain the first query first); re-iteration correctness (reset + re-run yields correct results and latest-run plan). — risk: medium (multi-file logic / observability) — size: ~5 files; no further mergeable work (whole single-track change)  [x] commit: 48177a5ea79bf1831ab78cf736fb7720e03a43e2

## Episodes
<!-- Continuous-log. Phase B appends one block per completed step. Empty at Phase 1. -->

### Step 1 — commit 48177a5ea79bf1831ab78cf736fb7720e03a43e2, 2026-07-02T15:06Z [ctx=unknown]

**What was done:** Added `@Nullable default ExecutionPlan getExecutionPlan()` to `QueryMetricsListener.QueryDetails`, returning `null` by default, with javadoc stating the read-only contract (inspect `getSteps()`/`prettyPrint()`, never `toResult(session)`) and the synchronous-validity window (valid only inside `queryFinished`). `YTDBGraphStep` gained a `@Nullable ExecutionPlan lastExecutionPlan` field, captures `resultSet.getExecutionPlan()` in the query branch of `elements()` before streaming, exposes `getLastExecutionPlan()`, and overrides `reset()` to call `super.reset()` first and then clear the field; the by-id branch leaves it `null`. `YTDBQueryMetricsStep.capturedExecutionPlan()` resolves the root source step via `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` and wires it into the anonymous `QueryDetails`. Seven regression tests landed in `YTDBQueryMetricsStrategyTest`: scan→non-null plan without `FetchFromIndexStep`, indexed→plan with `FetchFromIndexStep`, by-id→null, post-close readability, cache-replay→null, reset+re-iteration correctness, and the interface-default-null contract.

**What was discovered:** The cache-hit-replay→null behavior holds only when `GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED` is on, which it is not by default — with the cache off, `tx.getQueryResultCache()` returns `null`, a replay re-executes, and a fresh non-null plan surfaces. The cache-replay test therefore enables the flag on the transaction and restores it in a `finally` block (matching the existing `AggregateCacheEquivalenceTest` toggle pattern). This refines D5, whose prose implied the null-on-replay behavior held unconditionally. Separately: the gremlintest scenario classes are not runnable through a plain `-Dtest=` invocation (the parallel `default-test` execution excludes the gremlin-test jar, and `-Dtest` overrides the path-exclude, yielding `NoClassDefFound AbstractGremlinTest`); the correct invocation is the suite entry `YTDBProcessTest` through the `sequential-tests` execution, filtered by the `GREMLIN_TESTS` env var with exact comma-separated FQCNs.

**What changed from the plan:** All tests landed in `YTDBQueryMetricsStrategyTest`; the plan allowed either that file or `YTDBTransactionMetricsListenerTest`, and the latter was used only as an unmodified regression check. The cache-replay test additionally toggles `QUERY_TX_RESULT_CACHE_ENABLED`, which D5 did not note was required. D5's Risks/Caveats is updated below to record the cache-enabled precondition.

**Key files:** `QueryMetricsListener.java`, `YTDBGraphStep.java`, `YTDBQueryMetricsStep.java`, `YTDBQueryMetricsStrategyTest.java` (all in `core`).

## Validation and Acceptance
The track lands when a registered `QueryMetricsListener` can inspect the plan of the query it was notified about, and the documented limitations hold. Behavioral acceptance criteria:

- A plan-backed scan query (`g.V().has(unindexedProp, ...)`) surfaces a **non-null** plan to the listener whose steps contain no `FetchFromIndexStep` (a full scan).
- An indexed query surfaces a non-null plan whose steps contain a `FetchFromIndexStep`.
- A by-id lookup (`g.V(id)`) surfaces a **null** plan (the by-id branch runs no query).
- The plan is readable inside the `queryFinished` callback: `getSteps()` and `prettyPrint()` return the plan structure after the query's result set has closed.
- A cache-hit replay of the same query in the same transaction surfaces a **null** plan — the test fully drains the first (populating) query before repeating it, since the plan is nulled only on stream drain (documented limitation, D5, T2).
- After `YTDBGraphStep.reset()`, `getLastExecutionPlan()` returns `null` — no stale plan leaks across traversal reuse (D4).
- **Re-iteration correctness (A1):** a traversal that is reset and re-executed still produces correct results (the `reset()` override calls `super.reset()`, so `GraphStep` re-arms its iterator) *and* `getLastExecutionPlan()` reflects only the latest run. This must be a distinct test that re-runs the traversal and asserts results — a bare post-reset-null assertion would pass even if the override wrongly omitted `super.reset()`.

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
- `YTDBGraphStep.getLastExecutionPlan()` → `@Nullable ExecutionPlan`; `YTDBGraphStep.reset()` calls `super.reset()` then clears the field.
- `TraversalHelper.getFirstStepOfAssignableClass(Class, Traversal.Admin)` → `Optional<S>` (root direct steps only).
- `ResultSet.getExecutionPlan()` → `@Nullable ExecutionPlan`.

## Invariants & Constraints
- A registered listener receives a non-null `ExecutionPlan` for a plan-backed root scan/index query and `null` for a by-id lookup — verified by the scan / indexed / by-id acceptance tests.
- `getSteps()` and `prettyPrint()` on the retained plan succeed after the query's result set closes; `toResult(session)` is not called from the listener — verified by the post-close-readable test and enforced by the accessor javadoc contract.
- A cache-hit replay of the same query in the same transaction yields a `null` plan — verified by the cached-query replay test, which drains the first populating query before repeating (D5, T2).
- After `reset()`, `getLastExecutionPlan()` returns `null` **and** re-iteration still yields correct results (the override calls `super.reset()`) — verified by the re-iteration test, not a bare post-reset-null assertion (D4, A1).
- Plan capture is independent of whether monitoring is enabled: `YTDBGraphStep` holds no reference to the monitoring machinery, and the capture is a single field write — process constraint, verified by inspection (no monitoring import in `YTDBGraphStep`).
- The capture adds only O(1) work (one nullable field read/write) on the source-step execution path, outside the row-streaming loop — performance constraint, paired with the existing `QueryMetricsOverheadBenchmark`.
