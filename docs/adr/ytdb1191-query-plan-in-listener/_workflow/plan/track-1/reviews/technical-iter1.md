<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: T1, sev: suggestion, loc: "track-1.md:44-48 (D4)", anchor: "### T1 ", cert: "Edge case E4", basis: "D4 rationale cites a query->by-id branch switch a single YTDBGraphStep instance cannot perform; the reset-clear guard is still valid for a different staleness case"}
  - {id: T2, sev: suggestion, loc: "track-1.md:52,99,136 (D5)", anchor: "### T2 ", cert: "Edge case E1", basis: "cache-hit replay yields null only after the first run drains its stream; the replay test must fully iterate the first query before the repeat"}
evidence_base: {section: "## Evidence base", certs: 19, matches: 17}
cert_index:
  - {id: E1, verdict: PARTIAL, anchor: "#### E1 "}
  - {id: E4, verdict: PARTIAL, anchor: "#### E4 "}
flags: [CONTRACT_OK]
-->

## Verdict

APPROVE. The track's mechanism is technically sound and matches the live code: every named production type resolves, the capture point and accessor compile against the current signatures (confirmed against the saved draft patch), the post-close-readable and reset invariants hold, and no existing consumer breaks. Two suggestions refine decision-log prose (D4) and test sequencing (D5); neither blocks decomposition.

**Reference-accuracy caveat:** mcp-steroid was not reachable this session. Symbol-existence and signature certificates below rest on `find`, `grep`, `javap` against the pinned `gremlin-core-3.8.1-af9db90-SNAPSHOT` jar, and direct file reads — authoritative for declarations and signatures. The one negative-space claim (QueryDetails has a single implementer) rests on grep and carries its own caveat in cert P13.

## Findings

### T1 [suggestion]
**Certificate**: Edge case E4
**Location**: `track-1.md` D4 (lines 44-48), Decision Log
**Issue**: D4's rationale justifies the `reset()` clear by "A reused or re-iterated traversal that switches from the query branch to the by-id branch would otherwise report a **stale** plan." A single `YTDBGraphStep` instance cannot switch branches: the branch in `elements()` is selected on `this.ids` (YTDBGraphStep.java:101), and `ids` is fixed at construction from the original `GraphStep` (constructor lines 40-50) — it is not reassigned across `reset()`. So the same instance always takes the same branch on every re-iteration; the described stale-plan path is not realizable. The `reset()` clear is still worth keeping, but for a *different* staleness scenario: a traversal re-iterated where a downstream short-circuit (e.g. `limit(0)`) never pulls from the source, so `elements()` does not re-run and `getLastExecutionPlan()` would otherwise return the previous iteration's plan for a run whose source did not execute. Clearing in `reset()` returns `null` there, which is correct.
**Proposed fix**: Reword D4's rationale to name the realizable scenario (re-iteration in which the source step is not pulled, leaving a prior-run plan visible) instead of the query→by-id branch switch. The mechanism (clear in `reset()`, leave the by-id path null) is unchanged.

### T2 [suggestion]
**Certificate**: Edge case E1
**Location**: `track-1.md` D5 (line 52), Validation and Acceptance (line 99), Invariants & Constraints (line 136)
**Issue**: The "cache-hit replay of the same query in the same transaction surfaces a **null** plan" behavior is not unconditional. `CachedResultSetView.getExecutionPlan()` returns the `executionPlan` captured at view construction from `entry.getPlan()` (CachedResultSetView.java:581-583, 193). `CachedEntry.getPlan()` returns `null` only after `CachedEntry.close()` runs (CachedEntry.java:483-509), and `close()` fires when the stream drains (`CachedResultSetView.pullOneFromStream` → `entry.setExhausted(true); entry.close()`, lines 520-522). So the replay captures `null` only if the *first* (populating) execution fully exhausted its stream before the replay's view is built. A first query left partially iterated (its stream not drained) leaves `entry.getPlan()` non-null, and the replay would then capture a **non-null** plan — contradicting the stated invariant. Through the Gremlin path this is normally satisfied (the metrics step times full iteration and the source `ResultSet` is closed when the outer traversal completes), but a test that issues the first query without draining it and then repeats it would see the assertion fail.
**Proposed fix**: In the D5 replay test, fully consume the first query's results (e.g. materialize to a list / iterate the traversal to completion) before issuing the identical replay, and note in D5 that the null-on-replay guarantee depends on the populating run having drained. No production change needed.

## Evidence base

#### P1 QueryDetails interface and its current methods
- **Track claim**: `QueryMetricsListener.QueryDetails` today exposes `getQuery()`, `getQuerySummary()`, `getTransactionTrackingId()`; the track adds `@Nullable default ExecutionPlan getExecutionPlan()`.
- **Search performed**: Read of `QueryMetricsListener.java` (fallback tool: Read; mcp-steroid unreachable).
- **Code location**: `core/.../monitoring/QueryMetricsListener.java:22-33`.
- **Actual behavior**: Inner `interface QueryDetails` declares exactly `getQuery()`, `@Nullable getQuerySummary()`, `getTransactionTrackingId()`. Adding a `default` method is source- and binary-compatible.
- **Verdict**: CONFIRMED

#### P2 YTDBQueryMetricsStep builds the anonymous QueryDetails in close()
- **Track claim**: Wire `getExecutionPlan()` into the anonymous `QueryDetails` the step builds; resolve the source step and read its plan.
- **Search performed**: Read of `YTDBQueryMetricsStep.java`.
- **Code location**: `YTDBQueryMetricsStep.java:116-152` (close), `123-145` (anonymous QueryDetails).
- **Actual behavior**: `close()` (guarded by `hasStarted`) constructs an anonymous `QueryDetails` and calls `queryFinished(...)` inside a try/catch that logs callback errors. The `traversal` field (from `AbstractStep`) is the root traversal the strategy appended the step to. An added `getExecutionPlan()` override slots in cleanly; a throw inside it is caught and logged, not propagated.
- **Verdict**: CONFIRMED

#### P3 YTDBQueryMetricsStrategy appends the metrics step to the root traversal
- **Track claim**: The metrics step is appended to the **root** traversal only when metrics are enabled and the graph is embedded; `capturedExecutionPlan()` resolves the source via the metrics step's own `traversal`.
- **Search performed**: Read of `YTDBQueryMetricsStrategy.java`.
- **Code location**: `YTDBQueryMetricsStrategy.java:23-45`.
- **Actual behavior**: `apply()` returns unless `traversal.isRoot()`, the graph is a `YTDBGraph`, and `ytdbTx.isQueryMetricsEnabled()`; then `traversal.addStep(new YTDBQueryMetricsStep<>(traversal, ...))`. The step's `traversal` is therefore the root, so a root-scoped `getFirstStepOfAssignableClass` search is well-defined.
- **Verdict**: CONFIRMED

#### P4 YTDBGraphStep.elements() has the described by-id / query branches
- **Track claim**: `elements()` has a by-id branch (runs no query) and a query branch that builds a `YTDBGraphQuery`, calls `execute`, and streams; capture assigns `lastExecutionPlan` in the query branch before streaming.
- **Search performed**: Read of `YTDBGraphStep.java`.
- **Code location**: `YTDBGraphStep.java:92-160`; branch split at line 101 (`this.ids != null && this.ids.length > 0`), query branch at 131-159 (`builder.build(session)` → `query.execute(session).stream()`).
- **Actual behavior**: Matches the description exactly. The capture insertion point (bind the `ResultSet` from `execute`, read `getExecutionPlan()`, then `.stream()`) is at lines 143-144; the draft patch performs precisely this transform.
- **Verdict**: CONFIRMED

#### P5 YTDBGraphQuery.execute returns a ResultSet via transaction.query
- **Track claim**: The query branch's `ResultSet` carries an execution plan populated at execute time.
- **Search performed**: Read of `YTDBGraphQuery.java`.
- **Code location**: `YTDBGraphQuery.java:22-26`.
- **Actual behavior**: `execute` returns `transaction.query(this.query, this.params)` — a `ResultSet`. The plan reaches the caller through `ResultSet.getExecutionPlan()` (see P7, P11, P12).
- **Verdict**: CONFIRMED

#### P6 ExecutionPlan inspection surface
- **Track claim**: `ExecutionPlan` is `Serializable` with `getSteps()`, `prettyPrint(int,int)` (session-free), and `toResult(DatabaseSessionEmbedded)` (needs a live session, unsafe from the listener).
- **Search performed**: Read of `ExecutionPlan.java`.
- **Code location**: `core/.../query/ExecutionPlan.java:10-20`.
- **Actual behavior**: `extends Serializable`; `@Nonnull List<ExecutionStep> getSteps()`, `@Nonnull String prettyPrint(int,int)`, `@Nonnull BasicResult toResult(@Nullable DatabaseSessionEmbedded)`. Signatures match the track's read-only-contract framing.
- **Verdict**: CONFIRMED

#### P7 ResultSet.getExecutionPlan() is @Nullable
- **Track claim**: `ResultSet.getExecutionPlan()` → `@Nullable ExecutionPlan`.
- **Search performed**: Read of `ResultSet.java`.
- **Code location**: `core/.../query/ResultSet.java:354`.
- **Actual behavior**: `@Nullable ExecutionPlan getExecutionPlan();` declared on the interface.
- **Verdict**: CONFIRMED

#### P8 TraversalHelper.getFirstStepOfAssignableClass scans root direct steps only
- **Track claim**: `getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` iterates the root's direct steps only.
- **Search performed**: `javap` on the pinned `gremlin-core-3.8.1-af9db90-SNAPSHOT.jar` + read of the extracted `TraversalHelper.java` source.
- **Code location**: `TraversalHelper.java:412-418`; `javap` confirms `public static <S> Optional<S> getFirstStepOfAssignableClass(Class<S>, Traversal$Admin)`.
- **Actual behavior**: Body iterates `traversal.getSteps()` (direct steps) and returns the first assignable one, else `Optional.empty()`. Non-recursive — matches D3's locality contract. A raw `YTDBGraphStep.class` arg works with the `isAssignableFrom` test.
- **Verdict**: CONFIRMED

#### P9 GraphStep.reset() is a public overridable method
- **Track claim**: Clear `lastExecutionPlan` in `reset()` (D4); `YTDBGraphStep` overrides `reset()`.
- **Search performed**: `javap` on the pinned gremlin-core jar for `GraphStep` and `AbstractStep`.
- **Code location**: `GraphStep` — `public void reset();` (also on `AbstractStep`).
- **Actual behavior**: `reset()` is public on both classes, so `YTDBGraphStep` can override it, call `super.reset()`, and null the field. `YTDBGraphStep` does not currently override `reset()`, so the override is a clean addition.
- **Verdict**: CONFIRMED

#### P10 SelectExecutionPlan.close() leaves getSteps()/prettyPrint() valid
- **Track claim**: `SelectExecutionPlan.close()` only propagates `close()` through the steps; it does not null the `steps` list, so `getSteps()`/`prettyPrint()` stay valid after the result set closes.
- **Search performed**: Read of `SelectExecutionPlan.java`.
- **Code location**: `SelectExecutionPlan.java:76-78` (close), `54` (`steps` field), `94-104` (prettyPrint), `138-139` (getSteps).
- **Actual behavior**: `close()` is `lastStep.close();` — it does not touch the `steps` list. `getSteps()` returns the live `steps` list; `prettyPrint()` iterates it calling each step's `prettyPrint(depth, indent)` with no session. Both remain callable after close. Confirms the post-close-readable invariant.
- **Verdict**: CONFIRMED

#### P11 Cache path: CachedResultSetView captures entry.getPlan() at construction; CachedEntry.close() nulls the plan
- **Track claim (D2/D5)**: The populating (cache-miss) run yields a populated plan at execute time; a cache-hit replay surfaces `null` because `CachedEntry.close()` sets `plan = null` when the populating stream closes.
- **Search performed**: Read of `DatabaseSessionEmbedded.java` (query/serveThroughCache/buildView), `CachedResultSetView.java`, `CachedEntry.java`.
- **Code location**: `DatabaseSessionEmbedded.java:1387-1408` (buildView passes `entry.getPlan()`), `1039-1056` (miss populate → buildView); `CachedResultSetView.java:193, 581-583` (final `executionPlan` field returned by getExecutionPlan); `CachedEntry.java:326-328` (getPlan), `483-509` (close nulls plan), `520-522`-adjacent drain-close in the view's `pullOneFromStream`.
- **Actual behavior**: On a cache miss the view is built with the live (non-null) plan; `getExecutionPlan()` returns it. `CachedEntry.close()` nulls `plan`, and it runs when the stream drains, so a later identical query's view captures `null` — **provided the first run drained** (see E1). PARTIAL only w.r.t. the unconditional phrasing of the replay-null claim.
- **Verdict**: PARTIAL

#### P12 Uncached path: LocalResultSet.getExecutionPlan() returns the plan
- **Track claim**: For a normal SELECT the concrete result set returns the plan built at execute time.
- **Search performed**: `grep` + Read of `LocalResultSet.java` and `LocalResultSetLifecycleDecorator.java`.
- **Code location**: `LocalResultSet.java:25,36,132-137`; decorator `:64-65` delegates.
- **Actual behavior**: `getExecutionPlan()` returns the `executionPlan` field (non-null; set in constructor). `close()` calls `executionPlan.close()` but does not null the field, so a post-close read still returns the (closed-but-readable, per P10) plan. Whether the graph query routes through the cache view or the uncached `LocalResultSet`, capture-at-execute yields a non-null plan.
- **Verdict**: CONFIRMED

#### P13 QueryDetails has a single implementer; the default method breaks nothing
- **Track claim (D1)**: The new method is a `@Nullable default` returning `null`, so no existing `QueryDetails` implementer breaks.
- **Search performed**: `grep -rln "QueryDetails"` across `core/src`, `server/src`, `tests/src` (mcp-steroid unreachable — grep-based, reference-accuracy caveat applies).
- **Code location**: Matches only in `YTDBTransaction.java` (plumbing), `YTDBQueryMetricsStep.java` (the sole anonymous implementer, updated by this track), the interface itself, and two test files.
- **Actual behavior**: The only implementation is the anonymous one the track edits. Even setting the grep caveat aside, a `default` method is additive and cannot break any implementer. Caveat: grep can miss an implementer named only via generics or an unusual construct; low risk here given the interface is a small internal monitoring type.
- **Verdict**: CONFIRMED (grep-based negative-space claim; default-method safety is independent of the search)

#### E1 Cache-hit replay in the same transaction
- **Trigger**: Same query shape executed twice in one transaction.
- **Code path trace**:
  1. First query: `serveThroughCache` miss → populate entry → `buildView` returns `CachedResultSetView` with `executionPlan = entry.getPlan()` (non-null) @ `DatabaseSessionEmbedded.java:1408`.
  2. YTDBGraphStep captures that non-null plan pre-stream @ query branch.
  3. First result set fully iterated → `pullOneFromStream` drains → `entry.setExhausted(true); entry.close()` → `plan = null` @ `CachedEntry.java:483-509`.
  4. Second identical query: cache **hit** → `buildView(hit, ...)` with `entry.getPlan()` now `null` → view's `executionPlan = null` → capture `null`.
- **Outcome**: `null` on replay **iff** step 3 ran (first run drained). If the first run is left partially iterated, `plan` is still non-null and the replay captures a non-null plan.
- **Track coverage**: partially — D5 states the null-on-replay outcome but not its drain precondition. See T2.

#### E2 By-id lookup (g.V(id))
- **Trigger**: `this.ids` non-empty.
- **Code path trace**: `elements()` takes the by-id branch @ `YTDBGraphStep.java:101-130`; no `YTDBGraphQuery`, no `execute`, `lastExecutionPlan` never assigned → `getLastExecutionPlan()` returns `null` → `capturedExecutionPlan()` returns `null`.
- **Outcome**: `null` plan. Correct — the by-id path runs no query.
- **Track coverage**: yes (D2/D4 risks, acceptance line "by-id lookup surfaces a null plan").

#### E3 Downstream short-circuit that never pulls the source (e.g. limit(0))
- **Trigger**: Traversal iterated but the source step's `elements()` is never invoked.
- **Code path trace**: `YTDBGraphStep.elements()` does not run → `lastExecutionPlan` stays `null` (fresh instance) → metrics step reports `null`.
- **Outcome**: `null` plan — correct (the source query did not execute).
- **Track coverage**: yes (D2 risk note).

#### E4 Traversal re-iteration after reset()
- **Trigger**: A root traversal reset and re-iterated.
- **Code path trace**: A single `YTDBGraphStep` instance's branch is fixed by `this.ids` (set once in the constructor, lines 40-50; read at 101) — it cannot switch from the query branch to the by-id branch across resets. The real staleness path is a re-iteration in which the source step is not pulled (E3-style), leaving a prior-run plan visible; clearing in `reset()` returns `null` there.
- **Outcome**: The `reset()` clear is a valid staleness guard, but D4's stated rationale (query→by-id switch) describes a path the code cannot take.
- **Track coverage**: partially — mechanism correct, rationale inaccurate. See T1.

#### I1 Metrics step → source step plan handoff
- **Plan claim**: `YTDBQueryMetricsStep.capturedExecutionPlan()` resolves the `YTDBGraphStep` on its own root traversal and reads `getLastExecutionPlan()`.
- **Actual entry point**: `YTDBQueryMetricsStep.close()` @ `:116`, calling the new `capturedExecutionPlan()` helper (draft patch) over `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)`.
- **Caller analysis**: The strategy is the sole creator of the metrics step and always passes the root traversal (`:43-44`); the step's `traversal` is that root. `YTDBGraphStep` is the root source step for `g.V()`/`g.E()`. (Grep found no `extends YTDBGraphStep`, so no subclass complicates the assignable-from match — reference-accuracy caveat: grep-based.)
- **Breaking change risk**: none — the helper read is additive and null-safe (`orElse(null)`).
- **Verdict**: MATCHES

#### I2 QueryDetails default method — backward compatibility
- **Plan claim**: No existing `QueryDetails` implementer breaks.
- **Actual entry point**: `QueryDetails` interface @ `QueryMetricsListener.java:22`.
- **Caller analysis**: Sole implementer is the anonymous class in `YTDBQueryMetricsStep` (P13). `NO_OP` is a lambda for the outer `QueryMetricsListener` functional interface, not `QueryDetails`, so it is unaffected.
- **Breaking change risk**: none — `default` return `null` is additive.
- **Verdict**: MATCHES
