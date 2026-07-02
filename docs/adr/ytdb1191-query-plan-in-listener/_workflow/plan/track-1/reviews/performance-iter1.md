<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 3, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. The track's O(1)/off-row-path performance invariant holds as claimed; every candidate issue investigated below was refuted at realistic scale.

## Evidence base

#### C1 Capture is O(1) and outside the row-streaming loop — REFUTED (no issue)
Candidate concern: the plan capture added to `YTDBGraphStep.elements()` runs per row, or allocates, on the `g.V()`/`g.E()` hot path.

Trace. The capture is a single statement at `YTDBGraphStep.java:157`:
`this.lastExecutionPlan = resultSet.getExecutionPlan();`. It sits after `query.execute(session)` (line 152) and **before** `resultSet.stream()` (line 159), so it executes once per source-step execution, not once per row. `resultSet.getExecutionPlan()` is a trivial field-return getter on every concrete result-set type the query path can return — `ExecutionResultSet.getExecutionPlan()` returns `plan` (ExecutionResultSet.java:67-70), `LocalResultSet` returns `executionPlan` (LocalResultSet.java:133-137), `LocalResultSetLifecycleDecorator` delegates (line 64-66), `CachedResultSetView` and `InternalResultSet` likewise return a stored field. The plan object already exists (built at execute time), so the statement is a reference read plus a reference write: O(1), zero allocation.

COST TRACE: OPERATION = one getter + one field assignment; COMPLEXITY = O(1) per query execution; ALLOCATIONS = 0; not in any loop. SCALE CHECK: at 100 / 100K / 1M queries the added cost is one field write each — negligible at every scale. Matches the track's "O(1), one nullable field read/write, outside the row-streaming loop" invariant. VERDICT: no issue.

#### C2 Reporting-time plan resolution is off the row-streaming path — REFUTED (no issue)
Candidate concern: `YTDBQueryMetricsStep.capturedExecutionPlan()` (using `TraversalHelper.getFirstStepOfAssignableClass`) runs per row or per traverser.

Trace. `capturedExecutionPlan()` (YTDBQueryMetricsStep.java:85-89) is called only from the anonymous `QueryDetails.getExecutionPlan()` (line 161-163), which the listener invokes from inside `close()` (line 130-171). `close()` runs once per traversal close, not per row — the per-row work is confined to `hasNext()`/`next()`, which this change does not touch. `getFirstStepOfAssignableClass` scans the root traversal's direct steps (bounded by traversal length, typically well under ~20 steps, independent of result-set size) and allocates ~2 `Optional`s plus a method-ref; all of this is once per `queryFinished` callback.

COST TRACE: COMPLEXITY = O(k) in root-step count k (small, bounded, data-independent) per query close; not per row. SCALE CHECK: negligible at all scales. Minor note (not a finding): unlike `getQuery()`, which memoizes its expensive Groovy translation in `cachedQuery`, `getExecutionPlan()` re-walks the steps on each call; a listener that calls it repeatedly re-resolves each time. The walk is cheap and listener calls are few, so this is below suggestion threshold. Reference-accuracy caveat: mcp-steroid was unreachable, so the "only caller is `close()`" claim rests on grep over the changed files plus the visible call graph, not a PSI find-usages.

#### C3 Retained plan reference does not bloat retention at scale — REFUTED (no issue)
Candidate concern: holding `lastExecutionPlan` on the source step past the query's natural lifetime retains heavyweight state (buffers, cursors, materialized batches) and grows with data.

Trace. The step retains exactly one `ExecutionPlan` reference. Its footprint is the plan's `ExecutionStep` list, whose size is bounded by query structure, not by result-set cardinality — a source-step scan/index-fetch plan does not buffer rows. The plan is already live during execution; the change extends its reachability only until the next `reset()` (which nulls it, YTDBGraphStep.java:240), the next execution (which overwrites it, line 157), or GC of the traversal. In embedded Gremlin, source-step traversals are short-lived per query, and TinkerPop calls `reset()` before re-iteration, so the retention window is short and does not scale with data.

SCALE CHECK (retention): AT 100 records = negligible; AT 100K = negligible (footprint tracks plan shape, not row count); AT 1M = negligible. VERDICT: MATTERS = NEGLIGIBLE; no issue. Reference-accuracy caveat: the "reset() precedes re-iteration / traversals are short-lived" reasoning is from reading the step and the TinkerPop `GraphStep` contract, not a PSI caller trace of every `reset()` invocation.
