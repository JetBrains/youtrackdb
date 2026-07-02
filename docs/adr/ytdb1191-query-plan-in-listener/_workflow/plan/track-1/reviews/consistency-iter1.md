<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: CR1, sev: suggestion, loc: track-1.md:65, anchor: "### CR1 ", cert: R-elements, basis: "Context and Orientation calls elements() the iteratorSupplier; the actual supplier is the vertices()/edges() lambda that delegates to a private 2-arg elements(...) helper"}
evidence_base: {section: "## Evidence base", certs: 15, matches: 14}
cert_index:
  - {id: R-elements, verdict: PARTIAL, anchor: "#### R-elements "}
flags: [CONTRACT_OK]
-->

## Scope and method

Axis configuration: `design_gate=no` (no `design.md`), single-track (no `implementation-plan.md`). Only the **Track ↔ Code** check ran — the track's inline Decision Records (D1–D5), `## Context and Orientation`, `## Plan of Work`, `## Interfaces and Dependencies`, and `## Invariants & Constraints` verified against real constructs in `core/`.

**Reference-accuracy caveat.** mcp-steroid was NOT reachable this session; all symbol verification used `grep`/`find` plus direct file reads, and `TraversalHelper` was read from an extracted TinkerPop-fork source tree (`/home/dev/.claude/jobs/.../thsrc/...`), not the live dependency jar. Grep-based verification can miss polymorphic call sites, generic dispatch, Javadoc/comment matches, and recently-renamed symbols. The findings below that hinge on a symbol search carry this caveat; none were phantom-reference findings, so the risk of a spurious "NOT FOUND" is low.

Intent-axis pre-screen applied: target-state additions the track will create (the new `getExecutionPlan()` on `QueryDetails`, the `lastExecutionPlan` field / accessor / `reset()` override on `YTDBGraphStep`, the `capturedExecutionPlan()` on `YTDBQueryMetricsStep`) are NOT treated as findings — the current code naturally lacks them and each is reachable from the existing constructs. `## Context and Orientation` claims are treated as current-state per the carve-out and verified normally.

## Findings

### CR1 [suggestion]
**Certificate**: R-elements
**Location**: track-1.md `## Context and Orientation`, line 65 (`YTDBGraphStep` bullet)
**Issue**: The track states "`elements()` is the step's `iteratorSupplier`, invoked once per step execution." The step's `iteratorSupplier` is not `elements()`.
**Evidence**: `YTDBGraphStep` sets its iterator supplier at construction (`YTDBGraphStep.java:48-49`) to the lambda `() -> isVertexStep() ? this.vertices() : this.edges()`. There is no no-arg `elements()`; the two-branch method is the private generic helper `elements(BiFunction getElementsByIds, Function getElement)` (`YTDBGraphStep.java:92-160`), which `vertices()` (line 59) and `edges()` (line 78) call. `vertices()` may additionally wrap the `elements(...)` result in a `MultiIterator` alongside a schema-class iterator (`YTDBGraphStep.java:63-73`). So the supplier delegates to `vertices()`/`edges()`, which delegate to the `elements(...)` helper — the helper is not itself the supplier. The two-branch description (by-id branch at line 101; query branch building `YTDBGraphQueryBuilder`, calling `query.execute(session)` at line 144, and streaming) is otherwise accurate.
**Proposed fix**: Reword the last sentence of the bullet to: "The two-branch logic lives in the private `elements(...)` helper, which the step's `iteratorSupplier` reaches via `vertices()`/`edges()` (set in the constructor); the helper runs once per supplier invocation." The `## Plan of Work` step-2 phrase "in the query branch of `elements()`" is unambiguous and needs no change — the helper's query branch is the correct capture site.
**Classification**: mechanical
**Justification**: current-state claim in `## Context and Orientation` (pre-screen treats it as current-state), single unambiguous correct rendering, fix updates only the description and preserves the plan's capture-site intent.

## Evidence base

Track ↔ Code certificates. All in-scope files from `## Interfaces and Dependencies` exist at the stated paths (verified by `find`): `QueryMetricsListener.java`, `YTDBGraphStep.java`, `YTDBQueryMetricsStep.java` (all `core/src/main/java/...`), plus both regression-test files `YTDBTransactionMetricsListenerTest.java` and `YTDBQueryMetricsStrategyTest.java` under `.../gremlin/gremlintest/scenarios/`.

#### R-QueryDetails: QueryMetricsListener.QueryDetails and queryFinished
- **Document claim**: `QueryMetricsListener` has a single `queryFinished(QueryDetails, startedAtMillis, executionTimeNanos)` callback; `QueryDetails` today exposes `getQuery()`, `getQuerySummary()`, `getTransactionTrackingId()`; `NO_OP` default; no `getExecutionPlan()` yet (D1, Context, Plan step 1).
- **Search performed**: Read `QueryMetricsListener.java` in full (grep unavailable via PSI — direct read).
- **Code location**: `QueryMetricsListener.java:15` (queryFinished), `:18` (NO_OP), `:22-33` (QueryDetails with the three methods).
- **Actual signature/role**: `void queryFinished(QueryDetails queryDetails, long startedAtMillis, long executionTimeNanos)`; `QueryDetails` has exactly `String getQuery()`, `@Nullable String getQuerySummary()`, `String getTransactionTrackingId()`. No `getExecutionPlan()`.
- **Verdict**: MATCHES
- **Detail**: The absent `getExecutionPlan()` is the target-state addition (Plan step 1) — not a finding. The `@Nullable default` shape claimed keeps existing implementers compiling; consistent with the interface having no other default.

#### R-metricsstep: YTDBQueryMetricsStep builds the anonymous QueryDetails
- **Document claim**: `YTDBQueryMetricsStep` is a transparent `AbstractStep` that calls `queryFinished` on close, building an anonymous `QueryDetails`; the track overrides `getExecutionPlan()` in that anonymous class (Context, Plan step 3).
- **Search performed**: Read `YTDBQueryMetricsStep.java` in full.
- **Code location**: `YTDBQueryMetricsStep.java:42` (`extends AbstractStep<S,S> implements AutoCloseable`), `:116-152` (`close()` builds `new QueryDetails() {...}` and calls `ytdbTx.getQueryMetricsListener().queryFinished(...)`).
- **Actual signature/role**: Matches; the anonymous `QueryDetails` implements the three current methods, so adding a `getExecutionPlan()` override is a clean target-state extension.
- **Verdict**: MATCHES

#### R-strategy: YTDBQueryMetricsStrategy appends to the root traversal
- **Document claim**: `YTDBQueryMetricsStrategy` is a `FinalizationStrategy` that appends the metrics step to the **root** traversal when metrics are enabled and the graph is embedded (D3, Context).
- **Search performed**: Read `YTDBQueryMetricsStrategy.java` in full.
- **Code location**: `YTDBQueryMetricsStrategy.java:13-15` (`AbstractTraversalStrategy<FinalizationStrategy> implements FinalizationStrategy`), `:25` (`if (!traversal.isRoot() ...) return;`), `:36` (`isQueryMetricsEnabled()` gate), `:44` (`traversal.addStep(metricsStep)`).
- **Actual signature/role**: The step is added only to a root traversal; the embedded-graph guard is at `:29-33`. Matches D3's basis for capturing only the root source step.
- **Verdict**: MATCHES

#### R-elements: YTDBGraphStep.elements() branches and the iteratorSupplier
- **Document claim**: `elements()` has a by-id branch (`this.ids` non-empty, no query) and a query branch (builds `YTDBGraphQuery`, calls `query.execute(session)`, streams); "`elements()` is the step's `iteratorSupplier`, invoked once per step execution" (Context, Plan step 2).
- **Search performed**: Read `YTDBGraphStep.java` in full; grep for `iteratorSupplier`/`reset`.
- **Code location**: `YTDBGraphStep.java:48-49` (iteratorSupplier lambda), `:92-160` (private `elements(BiFunction, Function)` helper), by-id branch `:101-130`, query branch `:131-159` (`query.execute(session)` at `:144`).
- **Actual signature/role**: The two-branch logic and `query.execute(session)` call are exactly as claimed. But the iteratorSupplier is the lambda `() -> vertices()/edges()`, which delegates to the private generic `elements(...)` helper; `elements()` is not itself the supplier, and `vertices()` can wrap it in a `MultiIterator` (`:63-73`).
- **Verdict**: PARTIAL
- **Detail**: Feeds CR1. Query-branch capture site (Plan step 2) is unaffected and correct.

#### R-reset: YTDBGraphStep.reset() clear (D4)
- **Document claim**: `reset()` already runs on traversal reuse; the track clears `lastExecutionPlan` in an override; the by-id branch never assigns the field (D4).
- **Search performed**: grep `reset` in `YTDBGraphStep.java`.
- **Code location**: No `reset()` override in `YTDBGraphStep` today (inherited from TinkerPop `AbstractStep`/`GraphStep`).
- **Actual signature/role**: Adding a `reset()` override that calls `super.reset()` and nulls the field is a reachable target-state change; `AbstractStep.reset()` exists in the fork to override. The by-id branch (`:101-130`) indeed makes no field assignment.
- **Verdict**: MATCHES (target-state; reachable). Reference-accuracy caveat: `AbstractStep.reset()` presence verified against the fork API by convention, not PSI.

#### R-ExecutionPlan: ExecutionPlan interface surface
- **Document claim**: `ExecutionPlan` (`internal.core.query`) is `Serializable`; `getSteps()` returns `List<ExecutionStep>`; `prettyPrint(int, int)` returns `String` with no session; `toResult(DatabaseSessionEmbedded)` needs a live session (Context, D-refs).
- **Search performed**: Read `ExecutionPlan.java` in full.
- **Code location**: `ExecutionPlan.java:10` (`extends Serializable`), `:12-13` (`@Nonnull List<ExecutionStep> getSteps()`), `:15-16` (`@Nonnull String prettyPrint(int depth, int indent)`), `:18-19` (`@Nonnull BasicResult toResult(@Nullable DatabaseSessionEmbedded session)`).
- **Actual signature/role**: Matches. Minor nuance: `toResult`'s session parameter is `@Nullable`, so the API does not *require* non-null, but the track's "needs a live session" is a semantic (post-close-unsafe) claim, not a signature claim — not a finding.
- **Verdict**: MATCHES

#### R-ResultSetGetPlan: ResultSet.getExecutionPlan()
- **Document claim**: `ResultSet.getExecutionPlan()` is `@Nullable ExecutionPlan` (Context, Relevant signatures).
- **Search performed**: grep `getExecutionPlan` in `ResultSet.java`.
- **Code location**: `ResultSet.java:354` (`@Nullable ExecutionPlan getExecutionPlan();`).
- **Verdict**: MATCHES

#### R-LocalResultSet: LocalResultSet constructed with the built plan
- **Document claim**: A normal SELECT returns a `LocalResultSet` constructed with the fully-built `InternalExecutionPlan`, so `getExecutionPlan()` is populated at execute time before rows stream (D2).
- **Search performed**: grep constructor/`getExecutionPlan` in `LocalResultSet.java`.
- **Code location**: `LocalResultSet.java:20` (`implements ResultSet`), `:35` (`LocalResultSet(DatabaseSessionEmbedded, InternalExecutionPlan)`), `:133` (`@Nullable ExecutionPlan getExecutionPlan()`).
- **Verdict**: MATCHES

#### R-SelectStmt: SQLSelectStatement builds the plan before returning the result set
- **Document claim**: `SQLSelectStatement.execute` builds `createExecutionPlan(...)` before returning (D2).
- **Search performed**: grep `createExecutionPlan`/`new LocalResultSet` in `SQLSelectStatement.java`/`SQLStatement.java`.
- **Code location**: `SQLSelectStatement.java:306` (`createExecutionPlan(ctx, false)`), `:311` (`return new LocalResultSet(session, executionPlan)`); `SQLStatement.java:100-116` (`createExecutionPlan` overloads).
- **Verdict**: MATCHES

#### R-SelectPlanClose: SelectExecutionPlan.close()/getSteps()/prettyPrint()
- **Document claim**: `SelectExecutionPlan.close()` only propagates `close()` through the steps and does not null the `steps` list, so `getSteps()`/`prettyPrint()` stay valid after the result set closes (Context, Invariants).
- **Search performed**: read `SelectExecutionPlan.java:54,76-108,138-139`.
- **Code location**: `:54` (`protected List<ExecutionStepInternal> steps`), `:76-78` (`close()` = `lastStep.close();`), `:94-99` (`prettyPrint` iterates `steps`), `:138-139` (`getSteps()` returns `(List) steps`).
- **Actual signature/role**: `close()` does not clear/null `steps`; the list remains readable. Matches the post-close-readable invariant.
- **Verdict**: MATCHES

#### R-YTDBGraphQuery: execute / explain / usedIndexes prior art
- **Document claim**: `YTDBGraphQuery.execute(session)` returns a `ResultSet`; `explain()` runs a separate `EXPLAIN <query>`; `usedIndexes()` walks the plan's steps for `FetchFromIndexStep` (D2, Context prior-art).
- **Search performed**: grep + read `YTDBGraphQuery.java:22-61`.
- **Code location**: `:23-25` (`ResultSet execute(...)` = `transaction.query(this.query, this.params)`), `:29-33` (`explain(...)` runs `String.format("EXPLAIN %s", query)` and returns `resultSet.getExecutionPlan()`), `:37-61` (`usedIndexes(...)` calls `explain()` then filters steps `instanceof FetchFromIndexStep`).
- **Verdict**: MATCHES

#### R-TraversalHelper: getFirstStepOfAssignableClass (root direct steps only)
- **Document claim**: `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` iterates the root's direct steps only → `Optional<S>`; the recursive alternative `getStepsOfAssignableClassRecursively` exists (D3, Relevant signatures).
- **Search performed**: `find` + read the fork source at `.../thsrc/.../TraversalHelper.java:405-427,428-498`.
- **Code location**: `TraversalHelper.java:412` (`Optional<S> getFirstStepOfAssignableClass(Class<S>, Traversal.Admin)`; body iterates `traversal.getSteps()` with no recursion), `:428/441/470` (`getStepsOfAssignableClassRecursively` overloads recursing into local/global children).
- **Actual signature/role**: `getFirstStepOfAssignableClass` scans direct steps only and returns `Optional`. Matches D3's locality rationale (root `V()`/`E()` → root's start step). Reference-accuracy caveat: verified against extracted fork source, not the live jar.
- **Verdict**: MATCHES

#### R-CachedEntry: CachedEntry.close() nulls the plan (D5)
- **Document claim**: `CachedEntry.close()` sets `plan = null` once the populating stream closes; `getPlan()` is `@Nullable` (D5).
- **Search performed**: grep `close`/`plan`/`getPlan` in `CachedEntry.java`.
- **Code location**: `CachedEntry.java:105` (`@Nullable InternalExecutionPlan plan`), `:326-327` (`@Nullable InternalExecutionPlan getPlan()`), `:483-504` (`close()` closes the plan and sets `plan = null` at `:486` and `:504`).
- **Verdict**: MATCHES

#### R-buildView: DatabaseSessionEmbedded.buildView() from entry.getPlan() (D5)
- **Document claim**: `DatabaseSessionEmbedded.buildView()` builds a `CachedResultSetView` from `entry.getPlan()`; a cache-hit replay after close therefore surfaces `null` (D5).
- **Search performed**: grep `buildView`/`getPlan`/`CachedResultSetView` in `DatabaseSessionEmbedded.java`.
- **Code location**: `DatabaseSessionEmbedded.java:1387` (`private ResultSet buildView(...)`), `:1402` / `:1408` (`new CachedResultSetView(entry, delta, this, tx, entry.getPlan(), ctx)`).
- **Verdict**: MATCHES

#### R-CachedView: CachedResultSetView.getExecutionPlan() returns the held plan (D5 crux)
- **Document claim**: The populating (cache-miss) run captures a non-null plan; the cache-hit replay captures `null` because the entry's plan was nulled on close (D5).
- **Search performed**: grep `getExecutionPlan`/`ExecutionPlan`/constructor in `CachedResultSetView.java`.
- **Code location**: `CachedResultSetView.java:111` (`@Nullable InternalExecutionPlan executionPlan`), `:157/175/186` (constructors accepting `@Nullable InternalExecutionPlan`), `:581` (`ExecutionPlan getExecutionPlan()`).
- **Actual signature/role**: The view holds the plan passed at build time (`entry.getPlan()`) and returns it from `getExecutionPlan()`. On the populating run the entry's plan is still live (close not yet fired), so capture-at-execute-time sees non-null; on a hit after close, `entry.getPlan()` is null → view returns null. Grounds D5's mechanism end-to-end.
- **Verdict**: MATCHES

#### R-withListener: YTDBTransaction registration and gates
- **Document claim**: A listener is registered via `YTDBTransaction.withQueryListener(...)`; `YTDBQueryMetricsStep` reads it via `getQueryMetricsListener()`; the strategy gates on `isQueryMetricsEnabled()` (Context, D1).
- **Search performed**: grep in `YTDBTransaction.java`.
- **Code location**: `YTDBTransaction.java:248` (`withQueryListener(@Nonnull QueryMetricsListener)`), `:254` (`isQueryMetricsEnabled()`), `:267` (`getQueryMetricsListener()`).
- **Verdict**: MATCHES
