<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 2, suggestion: 3}
index:
  - {id: A1, sev: should-fix, loc: "DatabaseSessionEmbedded.java:1408", anchor: "### A1 ", cert: C1, basis: "tx-result cache hit returns CachedResultSetView with a plan already nulled on the populating entry's close, so a repeated Gremlin query captures a null plan and DNQ silently misses the scan"}
  - {id: A2, sev: should-fix, loc: "YTDBQueryMetricsStep.java:88 (draft capturedExecutionPlan)", anchor: "### A2 ", cert: C2, basis: "getFirstStepOfAssignableClass searches only root-traversal direct steps; a plan-backed scan inside a child traversal (where/union/subquery) is never captured, a false no-scan for the ticket's detect-full-scan goal"}
  - {id: A3, sev: suggestion, loc: "YTDBQueryMetricsStep.java:117 (draft close)", anchor: "### A3 ", cert: C3, basis: "zero-row via short-circuit downstream (limit(0)) leaves the source supplier un-run so lastExecutionPlan is null though queryFinished fires; item-2 claim 'supplier still runs' is inaccurate for that case"}
  - {id: A4, sev: suggestion, loc: "YTDBGraphStep.java:222 (draft, no reset override)", anchor: "### A4 ", cert: C4, basis: "no reset() clear plus by-id path never nulling lastExecutionPlan means a reused traversal that switches to the by-id branch reports a stale query plan"}
  - {id: A5, sev: suggestion, loc: "QueryMetricsListener.java:33", anchor: "### A5 ", cert: C5, basis: "Decision 1 survives: internal ExecutionPlan/ExecutionStep coupling equals the existing usedIndexes() coupling and invocation is embedded-only, so no new coupling and no serialization concern"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index:
  - {id: C1, verdict: WEAK, anchor: "#### C1 "}
  - {id: C2, verdict: WEAK, anchor: "#### C2 "}
  - {id: C3, verdict: WEAK, anchor: "#### C3 "}
  - {id: C4, verdict: WEAK, anchor: "#### C4 "}
  - {id: C5, verdict: YES, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

# Adversarial review — research log YTDB-1191 (iter 1)

Verdict: **NEEDS REVISION**. Two `should-fix` findings gate: the tx-result
cache path (A1) and nested/non-root traversals (A2) both defeat the ticket's
"detect unindexed full scans" purpose on real query shapes, and the research
log's item-2 / item-1 "resolved" analyses do not account for either. Both
are code-grounded, not speculative. The three remaining findings are
suggestions; Decision 1 survives cleanly.

Reference-accuracy caveat: mcp-steroid was unreachable this session, so every
symbol claim below rests on ripgrep, not PSI. Grep can miss polymorphic call
sites, generic dispatch, and reflective wiring. The claims most exposed to
that gap are A1 (which query shapes route through the tx-result cache) and A2
(whether `YTDBGraphStepStrategy` converts child-traversal graph steps) — both
are flagged inline.

## Findings

### A1 [should-fix]
**Certificate**: C1 (Challenge: Decision 2 — capture the executed plan)
**Target**: Decision "Capture the executed query's plan, not a second EXPLAIN"; Surprises item 2 "last-plan-wins resolved"
**Challenge**: The item-2 analysis reasons only about the uncached path — "`elements()` runs `query.execute()`, which builds `LocalResultSet(session, executionPlan)` with the plan attached." It never considers that `transaction.query(...)` can return a `CachedResultSetView` from the transaction-level result cache. That view carries `entry.getPlan()` (`DatabaseSessionEmbedded.java:1408`), and `CachedEntry` **nulls its `plan` on close** (`CachedEntry.java:486`, `491`; the field is `@Nullable`, "released on close", javadoc lines 322-326). The draft captures `resultSet.getExecutionPlan()` synchronously right after `execute()` (draft `YTDBGraphStep.elements()`). So when a repeated identical Gremlin query hits the cache *after the populating entry has already been closed* (its stream fully consumed), `getExecutionPlan()` returns `null` — `lastExecutionPlan` is `null`, the listener reports no plan, and DNQ silently fails to flag the unindexed scan for exactly the repeated queries a per-type lookup loop produces. This is the ticket's core purpose failing on a common shape.
**Evidence**: `CachedResultSetView.getExecutionPlan()` returns the nullable `executionPlan` field (`CachedResultSetView.java:581-582`); it is fed `entry.getPlan()` at construction (`DatabaseSessionEmbedded.java:1402`, `1408`); `CachedEntry.getPlan()` returns a field set to `null` on `close()` (`CachedEntry.java:326`, `486`). The uncached `LocalResultSet` always has a live plan (`LocalResultSet.java:35-48`, `133-137`), which is why the item-2 analysis looked sound — it just never traced the cache branch.
**Proposed fix**: Resolve into the Decision Log one of: (a) confirm via a test whether `YTDBGraphQueryBuilder` SELECT queries are tx-result-cacheable and, if so, capture the plan through a path that survives a cache hit (e.g. read `getInternalExecutionPlan()`/`getPlan()` before the entry can close, or fall back to the source step's own plan reference); or (b) if the cache is off for these queries or the plan reliably outlives capture, record that finding with the code citation so the "zero extra cost, always available" claim is grounded on the *cached* path too, not only `LocalResultSet`.

### A2 [should-fix]
**Certificate**: C2 (Challenge: item 1 — traversal locality)
**Target**: Surprises item 1 "traversal locality resolved: assumption holds for the standard case"; Decision 3 (draft `capturedExecutionPlan`)
**Challenge**: `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` searches only the **direct** steps of the metrics step's root traversal; it does not recurse into child traversals. A plan-backed scan can live inside a child traversal — `g.V().where(__.V().has('x', v))`, `union(__.V()...)`, or any subquery whose source is a nested `V()`/`E()`. When the root's own source is a non-plan step (e.g. `g.inject(seed).union(__.V().has(...))`) the root has no `YTDBGraphStep` at all, so `capturedExecutionPlan()` returns `null` even though an unindexed scan genuinely ran in the child. That is a *false* "no plan / no scan" — the opposite of what a full-scan detector must never emit. The log resolved item 1 "for the standard case" and waved nested cases off as "rare and documentable," but it never wrote the limitation down as a scoped non-goal, so a future DNQ query shape regresses silently.
**Evidence**: `YTDBGraphStepStrategy.rebuildTraversal` converts each `GraphStep` in a traversal to `YTDBGraphStep` (`YTDBGraphStepStrategy.java:106-118`), and `YTDBQueryMetricsStrategy` wraps only the **root** traversal (`YTDBQueryMetricsStrategy.java:25`, `44`). A child traversal's `YTDBGraphStep` therefore holds a real `lastExecutionPlan` that no metrics step ever reads. Reference-accuracy caveat: I confirmed by grep that `getFirstStepOfAssignableClass` is a TinkerPop helper (no in-repo definition) and that the strategy rebuilds per-traversal; I could not PSI-verify that child traversals are rebuilt by the same strategy pass, so the "child scan really produces a YTDBGraphStep" leg is grep-level.
**Proposed fix**: Add an explicit `## Decision Log` entry (or Non-Goal) stating the captured plan is the root source step's plan only — nested/subquery scans and non-`V()`/`E()`-rooted traversals are out of scope — and confirm DNQ's actual query shapes are flat root lookups. If nested detection is in scope, the first-step approach is insufficient and needs re-decision (walk all `YTDBGraphStep`s reachable from the root, or capture at a different layer).

### A3 [suggestion]
**Certificate**: C3 (Assumption test: source supplier always runs when the query is iterated)
**Target**: Surprises item 2 — "the supplier still runs, even for a zero-row result"
**Challenge**: The item-2 claim that the source step's supplier always runs before `queryFinished` is inaccurate for a downstream step that short-circuits without pulling. `g.V().limit(0)` iterated once sets the metrics step's `hasStarted=true` (so `close()` does *not* early-return and `queryFinished` fires), yet `RangeGlobalStep(0,0)` returns end-of-stream without ever pulling the source `YTDBGraphStep`, so `elements()` never runs and `lastExecutionPlan` stays `null`. The reported plan is `null` for a query that "ran." Harmless (a `null` plan is a documented outcome) but the log's stated reasoning is wrong on the mechanism.
**Evidence**: `YTDBQueryMetricsStep.close()` early-returns only on `!hasStarted` (`YTDBQueryMetricsStep.java:117-119`); `hasStarted` is set on the first `hasNext`/`next` regardless of whether upstream is pulled (`queryHasStarted`, lines 154-166). The zero-row-via-empty-result case *does* run the supplier (item 2 is right there); the zero-row-via-short-circuit case does not.
**Proposed fix**: Reword item 2 to scope the "supplier always runs" claim to cases where a downstream step actually pulls the source, and note short-circuiting steps (`limit(0)`, early `FastNoSuchElementException`) as a benign `null`-plan case.

### A4 [suggestion]
**Certificate**: C4 (Assumption test: retained plan is always current for the reported execution)
**Target**: Surprises item 3 — retention decision and the "optional" reset() clear
**Challenge**: The draft does not override `reset()`, and the by-id branch of `elements()` (`YTDBGraphStep.java:101-130`) never assigns `lastExecutionPlan`. A compiled traversal that is reset and re-iterated, taking the query branch on run 1 and the by-id branch on run 2, reports run 1's stale `SelectExecutionPlan` for run 2 — a by-id lookup that should report `null`. Exotic (requires traversal reuse plus a branch switch), but it is a correctness issue, not only the retention-pinning concern the log framed the reset() clear around.
**Evidence**: by-id path returns without touching `lastExecutionPlan` (`YTDBGraphStep.java:117-130`); the draft adds no `reset()`; `SelectExecutionPlan.reset` clears steps but not this field, which lives on the Gremlin step, not the plan.
**Proposed fix**: Promote the "clear `lastExecutionPlan` on `reset()`" refinement from optional to required, and reset it to `null` at the top of `elements()` so every execution reports its own plan (or `null`) rather than a stale one.

### A5 [suggestion]
**Certificate**: C5 (Challenge: Decision 1 — keep the internal QueryDetails surface)
**Target**: Decision "Keep the QueryMetricsListener.QueryDetails surface"
**Challenge**: The strongest case against reusing the internal surface — that returning internal `ExecutionPlan`/`ExecutionStep` forces the consumer to couple to internal step classes (`FetchFromIndexStep`, `GlobalLetQueryStep`) to classify scans — turns out to be no *new* coupling: `YTDBGraphQuery.usedIndexes()` already walks exactly those internal step types on the `explain()` plan (`YTDBGraphQuery.java:44-62`). And the listener only ever fires in embedded mode (`YTDBQueryMetricsStrategy.java:29-33` gates on `instanceof YTDBGraph`), so the plan is always a live in-JVM object — the `Serializable` supertype is never exercised across a wire. Decision 1 holds.
**Evidence**: `usedIndexes()` already `instanceof FetchFromIndexStep` / `GlobalLetQueryStep` on internal `getSteps()` (`YTDBGraphQuery.java:44-62`); strategy is embedded-only (`YTDBQueryMetricsStrategy.java:29-33`); the default method addition is source/binary-compatible and the existing anonymous `QueryDetails` in `YTDBQueryMetricsStep` overrides it (draft).
**Proposed fix**: None required. Optionally note in the Decision Log that the coupling is identical to `usedIndexes()` and invocation is embedded-only, to close the challenge on the record.

## Evidence base

#### C1 Challenge: Decision D2 — capture the executed query's plan, not a second EXPLAIN
- **Chosen approach**: In `elements()`, `lastExecutionPlan = resultSet.getExecutionPlan()` on the `ResultSet` from `query.execute(session)`, relying on the plan being attached "at zero extra cost, before streaming."
- **Best rejected alternative**: capture through a path that survives the tx-result cache (read the internal plan before the entry can close), or fall back to `explain()` when the executed plan is unavailable.
- **Counterargument trace**:
  1. In the repeated-query scenario (DNQ runs the same per-type lookup many times in one transaction), `transaction.query(...)` returns a `CachedResultSetView` on the second call (`DatabaseSessionEmbedded.java:1408`).
  2. That view's plan is `entry.getPlan()`, and `CachedEntry` nulls its `plan` field on `close()` (`CachedEntry.java:486`, `491`; `@Nullable`, released on close per javadoc 322-326). The populating entry closed after the first query's stream was consumed.
  3. `resultSet.getExecutionPlan()` returns `null` (`CachedResultSetView.java:581-582`) → `lastExecutionPlan = null` → listener reports no plan → DNQ misses the scan for the repeated query.
- **Codebase evidence**: `DatabaseSessionEmbedded.java:1402,1408`; `CachedResultSetView.java:581-582`; `CachedEntry.java:326,486,491`; contrast `LocalResultSet.java:35-48,133-137` (uncached always-live plan).
- **Survival test**: WEAK — the decision is right for the uncached path but the log's rationale omits the cache path entirely; needs a grounded resolution before the "always available" claim holds.

#### C2 Challenge: item 1 — traversal-locality / first-step capture
- **Chosen approach**: `getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` on the metrics step's root traversal reliably finds *the* source step.
- **Best rejected alternative**: capture all `YTDBGraphStep` plans reachable from the root (including child traversals), or capture at the query layer rather than the root Gremlin step.
- **Counterargument trace**:
  1. For `g.inject(seed).union(__.V().has('x', v))` (or `where(__.V()...)`), the root's first step is not a `YTDBGraphStep`.
  2. `getFirstStepOfAssignableClass` searches root direct steps only, not child traversals → returns empty → `capturedExecutionPlan()` is `null`.
  3. Yet the child `__.V().has(...)` ran a real (possibly unindexed) scan whose plan sits on the child `YTDBGraphStep` — a false "no scan."
- **Codebase evidence**: `YTDBQueryMetricsStrategy.java:25,44` (root-only wrap); `YTDBGraphStepStrategy.java:106-118` (per-traversal graph-step conversion). Reference-accuracy caveat: grep-level; PSI unavailable to confirm child-traversal rebuild and helper recursion.
- **Survival test**: WEAK for the standard flat-lookup case (holds), but the nested/non-root case is unhandled and undocumented; the log must scope it explicitly.

#### C3 Assumption test: the source supplier always runs when the query is iterated
- **Claim**: item 2 — "the source step runs before the metrics step's queryFinished fires at close, even for a zero-row result (the supplier still runs)."
- **Stress scenario**: `g.V().limit(0)` iterated once. `RangeGlobalStep(0,0)` returns end-of-stream without pulling upstream; the metrics step still saw a `hasNext` so `hasStarted=true` and `queryFinished` fires.
- **Code evidence**: `YTDBQueryMetricsStep.java:117-119` (close early-returns only on `!hasStarted`), `154-166` (`hasStarted` set independent of upstream pull). The source `elements()` never runs → `lastExecutionPlan` null.
- **Verdict**: FRAGILE — holds for empty-result queries (the supplier does run) but breaks for short-circuiting downstream steps; the log's stated mechanism is inaccurate. Consequence is benign (documented `null`).

#### C4 Assumption test: the retained plan is always current for the reported execution
- **Claim**: item 3 — retaining `lastExecutionPlan` is safe and per-execution; the reset() clear is an optional refinement.
- **Stress scenario**: a compiled traversal reset and re-iterated, query branch on run 1, by-id branch on run 2.
- **Code evidence**: by-id branch never assigns `lastExecutionPlan` (`YTDBGraphStep.java:101-130`); draft adds no `reset()` override → run 2 reports run 1's stale plan instead of `null`.
- **Verdict**: FRAGILE — correctness (not just retention) issue under traversal reuse; exotic but cheap to close by clearing on `reset()` / at `elements()` entry.

#### C5 Challenge: Decision D1 — keep the internal QueryDetails surface
- **Chosen approach**: add `@Nullable default ExecutionPlan getExecutionPlan()` to the internal `QueryDetails`, returning the internal `ExecutionPlan`.
- **Best rejected alternative**: a `com.jetbrains.youtrackdb.api` read-only plan view with a mapping layer.
- **Counterargument trace**:
  1. The alternative's motivation is decoupling the consumer from internal step classes.
  2. But `usedIndexes()` already couples DNQ to internal `FetchFromIndexStep`/`GlobalLetQueryStep` via `getSteps()` (`YTDBGraphQuery.java:44-62`), so returning internal `ExecutionPlan` adds no new coupling.
  3. Invocation is embedded-only (`YTDBQueryMetricsStrategy.java:29-33`), so the plan is a live object and `Serializable` is never exercised across a wire.
- **Codebase evidence**: `YTDBGraphQuery.java:44-62`; `YTDBQueryMetricsStrategy.java:29-33`; `QueryMetricsListener.java:22-33` (interface, default-method-safe).
- **Survival test**: YES — rationale holds; a public-API wrapper would be over-engineering for an internal-only, embedded-only consumer.
