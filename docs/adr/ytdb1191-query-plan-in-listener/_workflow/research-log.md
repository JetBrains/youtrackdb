# Research Log — YTDB-1191

## Initial request

YTDB-1191 "Make Gremlin query's ExecutionPlan available to the client":
Add a way for database clients to inspect the execution plan of a finished
Gremlin query, so consumers (e.g. DNQ) can detect unindexed full scans.

A draft implementation already exists in the working tree (authored by
another agent). The user wants to use it as the base for the planned change.
Three files touched:
- `QueryMetricsListener.java` — `@Nullable default ExecutionPlan getExecutionPlan()` on `QueryDetails`.
- `YTDBGraphStep.java` — captures `resultSet.getExecutionPlan()` into `lastExecutionPlan`, exposed via `getLastExecutionPlan()`.
- `YTDBQueryMetricsStep.java` — `capturedExecutionPlan()` pulls the plan from the first `YTDBGraphStep` in the root traversal and overrides `getExecutionPlan()`.

## Decision Log

### 2026-07-02 — Keep the QueryMetricsListener.QueryDetails surface [ctx=safe]
**Decision:** Expose the plan through `QueryDetails.getExecutionPlan()` (the drafted approach), not a new public-API type.
**Why:** The entire `QueryMetricsListener` surface is already `internal` (`internal.common.profiler.monitoring`), registered via the internal `YTDBTransaction.withQueryListener(...)`. Any consumer (DNQ) implementing this listener already depends on internal packages, so returning an internal `ExecutionPlan` introduces no new coupling. A public-API wrapper type would be over-engineering for an internal-only consumer.
**Alternatives rejected:** (a) New `com.jetbrains.youtrackdb.api` read-only plan view — adds a type + mapping layer with no consumer that needs it, since DNQ is inside internal already. (b) Separate accessor off the transaction — duplicates the listener's query-finished timing that already exists.

### 2026-07-02 — Capture the executed query's plan, not a second EXPLAIN [ctx=safe]
**Decision:** Capture `resultSet.getExecutionPlan()` from the actually-executed query (draft's `YTDBGraphStep` approach).
**Why:** A normal SELECT returns `LocalResultSet(session, executionPlan)` with the full plan attached at execute time (`SQLSelectStatement.execute` builds `createExecutionPlan(...)` before returning), so the plan is available immediately, before streaming, at near-zero extra cost on the cache-miss (populating) path. This is the plan that actually ran — exactly what the ticket wants — and avoids the second-roundtrip cost of `YTDBGraphQuery.explain()` (the `EXPLAIN %s` query used by `usedIndexes()`). Caveat (see A1 below): a cache-hit replay of the same query in the same transaction surfaces a null plan; capture is reliable on the populating run.
**Alternatives rejected:** Reuse `explain()` (second `EXPLAIN` query) — extra roundtrip and returns a re-planned plan, not the executed one.

### 2026-07-02 — Adversarial gate revisions (iter 1: A1/A2 should-fix, A3/A4 suggestions) [ctx=safe]
The Phase-0→1 adversarial gate surfaced two confirmed should-fix gaps and refinements. Recording the strengthened rationale here:

**A1 — tx-result cache nulls the plan on replay (confirmed).** `YTDBGraphQuery.execute()` runs `transaction.query(...)`, which may hit the transaction result cache. `DatabaseSessionEmbedded.buildView()` constructs a `CachedResultSetView` with `entry.getPlan()`, but `CachedEntry.close()` sets `plan = null` (lines 486, 504) once the populating stream closes and pins release. So a **cache-hit replay** of a cacheable query in the same transaction captures a `null` plan, even before streaming. **Decision:** accept as a documented limitation, not a mechanism change. Rationale: a cache-hit replay does not re-execute a plan — it replays cached rows — so a null plan on replay is semantically defensible; the scan happens (and is captured) only on the populating cache-miss run. DNQ's full-scan detection is a per-query-shape property observable on first execution. **Required:** a test pinning cached-query behavior (populating run surfaces the plan; replay surfaces null). Retaining the plan across cache close for replay is out of scope (deeper cache change).

**A2 — only the root source step's plan is captured (confirmed).** `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` iterates only the root traversal's direct steps (no recursion). So a plan-backed scan inside a **child traversal** (`where`/`union`/`local` subquery) or a **non-`V()`/`E()`-rooted** root traversal is not captured — the latter surfaces null, the former surfaces the root source's plan (or null). **Decision:** document explicitly as the captured contract — the feature exposes the **primary (root source) query's** execution plan, matching the ticket's use case (detect the main query's full scan). Broadening via the existing `getStepsOfAssignableClassRecursively` helper is possible but raises which-plan / merge-plans questions and is out of scope. Record the limitation in the accessor javadoc and the track.

**A3 — correction (suggestion).** Prior item-2 note claimed "the supplier always runs even for zero rows." Inaccurate: a downstream short-circuit (e.g. `limit(0)`) can prevent the source supplier from running, leaving `lastExecutionPlan` null. Benign (null plan, not a wrong plan), but the stated mechanism was wrong. Corrected: the plan is captured whenever the source step's query actually executes; a downstream short-circuit that never pulls from the source yields a null plan, which is correct.

**A4 — promote reset-clear to required + null on by-id path (suggestion, adopted).** The by-id branch (`elements()` line 101) never assigns `lastExecutionPlan`, and there is no `reset()` clear. A reused/re-iterated traversal that switches from the query branch to the by-id branch would report a **stale** plan from the earlier run. **Decision (upgraded from optional to required):** clear `lastExecutionPlan = null` in `YTDBGraphStep.reset()`, and ensure the by-id branch leaves it null. Cheap correctness fix that removes the stale-plan hazard and keeps the step ignorant of monitoring.

**A5 — Decision 1 survives (suggestion, no change).** Internal `ExecutionPlan`/`ExecutionStep` coupling equals the existing `usedIndexes()` coupling; invocation is embedded-only (no wire serialization). Confirmed no change.

## Surprises & Discoveries

- 2026-07-02 [ctx=safe] `ExecutionPlan` is `internal.core.query`; its API is `getSteps()` (returns internal `ExecutionStep`), `prettyPrint(int,int)` (String, session-free), and `toResult(DatabaseSessionEmbedded)` (needs a live session). Read-only inspection for "detect full scans" wants `getSteps()`/`prettyPrint`; `toResult` is unsafe from a listener after the query closes.
- 2026-07-02 [ctx=safe] Existing `YTDBGraphQuery.explain()` runs a separate `EXPLAIN <query>`; `usedIndexes()` walks `FetchFromIndexStep` in that plan. This is prior art for how a consumer detects (un)indexed scans by walking plan steps.
- 2026-07-02 [ctx=safe] `QueryMetricsListener` referenced by: `YTDBTransaction` (registration), `YTDBQueryMetricsStep` (invocation), `QueryMonitoringMode`, plus 3 tests (`YTDBTransactionMetricsListenerTest`, `YTDBQueryMetricsStrategyTest`, `YTDBTransactionCoverageTest`). These tests are the regression-coverage home for the new accessor.

### 2026-07-02 — Item 1 (traversal locality) resolved: assumption holds for the standard case [ctx=safe]
`YTDBQueryMetricsStrategy` applies only to **root** traversals (`traversal.isRoot()`, line 25) and appends the metrics step to that root traversal (`traversal.addStep(metricsStep)`, line 44), passing the root traversal into the step constructor. A source `V()`/`E()` becomes the `YTDBGraphStep` at the **start** of that same root traversal; mid-traversal steps (`out()`, `both()`) are VertexSteps, not GraphSteps. So `getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` on the metrics step's (root) traversal reliably finds the source step. **Still grep-based (mcp-steroid unreachable) — confirm with a test, but the code path is unambiguous.**

### 2026-07-02 — Item 2 (last-plan-wins) resolved: one execution → one plan; edge cases are rare and semantically defensible [ctx=safe]
Within one traversal execution the source step runs its query exactly once: `elements()` is the step's `iteratorSupplier` (line 48), invoked once, and the query-execution block (line 131 else-branch) runs once, assigning `lastExecutionPlan` once. Timing is safe — the source step runs before the metrics step's `queryFinished` fires at close, even for a zero-row result (the supplier still runs). The by-id path (line 101) runs no `query.execute()`, so its plan is correctly null. Edge cases, all rare and documentable rather than blocking: (a) a mid-traversal second `V()`/`E()` would create a second `YTDBGraphStep` whose plan is not captured — `getFirstStepOfAssignableClass` returns the source; capturing the source step's plan is a defensible "primary plan" semantic. (b) Re-iterating a compiled traversal overwrites `lastExecutionPlan` with the latest run — consistent with per-execution reporting. `repeat(...)` loops do not re-run the source step (loop body is a child traversal).

### 2026-07-02 — Item 3 (plan lifetime) resolved: read-only access is safe post-close; context retention is bounded, not a new unbounded leak [ctx=safe]
Two facts settle this:

**Read-only access survives close.** `SelectExecutionPlan.close()` only calls `lastStep.close()` (backward propagation with an `alreadyClosed` guard); it does not null `steps` or `ctx`. `prettyPrint(depth,indent)` (lines 94-99) and `getSteps()` (line 138) read only the `steps` list and each step's `prettyPrint()` — no session/ctx access. So a listener can call `getSteps()`/`prettyPrint()` on the retained plan after the `ResultSet` (and thus the plan) has been closed. This is exactly the read-only inspection the ticket wants (walk steps for `FetchFromIndexStep`, mirroring `usedIndexes()`). `toResult(session)` (line 179) does need a live session and must not be called from the listener post-close — the draft's javadoc already warns this. Confirmed correct.

**Context retention is bounded to the traversal lifetime.** `SelectExecutionPlan` holds `CommandContext ctx` (line 51) and every `AbstractExecutionStep` holds `public final CommandContext ctx` (line 62); the context transitively holds the `DatabaseSessionEmbedded`, input params, and variables. Retaining `lastExecutionPlan` on the step therefore pins that object graph — but only for the `YTDBGraphStep`'s lifetime, i.e. the compiled Gremlin traversal's lifetime. Gremlin traversals are single-use and short-lived (created per query, discarded after), so this is bounded retention, not a new unbounded leak. The executed plan is a per-execution `copy()` (interface lifecycle note), so retaining it does not pin the shared plan cache. The retention is heavier than before (the step previously held no plan), and would matter only if traversals were long-lived / cached / reused.

**Decision:** Accept the retention (option a). Document the read-only + session-detached-methods-only contract (already in the draft javadoc). Optional refinement to weigh during implementation: clear `lastExecutionPlan` on step `reset()` to release the context promptly on any traversal reuse — cheap, and keeps the "independent of monitoring" separation intact. A snapshot-instead-of-live-plan approach (store `prettyPrint()` string only) is rejected: DNQ needs to walk `getSteps()` to classify scans, so a String is insufficient and a session-detached deep copy of steps is not worth the complexity given retention is already bounded.

## Open Questions

(none open — items 1-3 resolved; gate findings A1/A2/A4 folded into Decision Log as documented limitations / required fixes)

## Adversarial gate record

### Adversarial review of this log (2026-07-02) — NEEDS REVISION: 0 blocker, 2 should-fix, 3 suggestion
Iteration 1. Review file: `reviews/research-log-adversarial-iter1.md`. Findings A1 (tx-result cache nulls plan on replay) and A2 (only root source step's plan captured) confirmed against the code; folded into the Decision Log as documented limitations plus a required cached-query test. A4 (reset-clear + by-id null) adopted as a required fix. A3 corrected. A5 no change.

### Adversarial review of this log (2026-07-02) — PASS
Iteration 2 (verdict-producer). Review file: `reviews/research-log-adversarial-iter2.md`. All iter-1 findings VERIFIED (A1/A2 should-fix cleared as legitimately-documented limitations, confirmed against `FrontendTransactionImpl`/`QueryResultCache` that the per-transaction cache always makes the first execution a plan-capturing populating run; A3/A4/A5 addressed). One new non-gating suggestion A6 (prose back-reference) applied. Gate cleared — no blocker, no open should-fix.
