<!-- MANIFEST
dimension: bugs-concurrency
iteration: 1
high_water_mark: 0
findings: 2
index:
  - id: BC1
    sev: suggestion
    anchor: "#bc1-null-rid-corruption-is-assert-only-no-production-fallback"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java:551-556"
    cert: "#c1-null-rid-guard-is-assert-only"
    basis: "PSI+source: SelectExecutionPlanner chain order, ResultInternal.getIdentity null path"
  - id: BC2
    sev: suggestion
    anchor: "#bc2-eager-drive-does-not-handle-an-aggregation-step-with-non-immediate-projection"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:203-223"
    cert: "#c2-splice-only-walks-immediate-prev"
    basis: "source: SelectExecutionPlanner.handleProjections chain order"
evidence_base: "Planner chain order (SelectExecutionPlanner.handleProjections L755-799), SelectExecutionPlan.start/close prev-chain semantics, ResultInternal.getIdentity null path, QueryResultCache guard/overflow flow, AggregateState fold/dispatch/copy logic, full equivalence + state test suites."
cert_index:
  - "#c1-null-rid-guard-is-assert-only"
  - "#c2-splice-only-walks-immediate-prev"
flags: []
-->

## Findings

### BC1 [suggestion] Null-RID corruption is assert-only, no production fallback

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java` (line 551-556)

**Issue**: `observe(Result)` reads `result.getIdentity()` and guards a non-null RID only with a Java `assert` (disabled in production). `ResultInternal.getIdentity()` returns `null` when its `identifiable` is null — exactly the post-projection row a `ProjectionCalculationStep` produces. If the tap ever observes such a row in a `-ea`-off build, `contributingRids.add(null)` / `contributingValues.put(null, value)` silently store under a null key, producing a wrong scalar (e.g. two distinct identity-stripped rows collapse to one contributor) rather than failing loudly or falling back uncached. This violates I10 transparency silently.

**Evidence**: The splice deliberately lands the tap above the pre-aggregate `ProjectionCalculationStep` (DatabaseSessionEmbedded.spliceTap, L210-214) precisely because that projection strips identity. The current planner chain (`SelectExecutionPlanner.handleProjections`, L762-798) puts the pre-aggregate projection as the immediate `prev` of the aggregation step, so today the tap sees identity-carrying records and the assert never trips. The risk is forward-looking: a future planner change that inserts a second identity-affecting step between the splice point and the source, or a plan shape where the immediate `prev` is an identity-stripping step that is not a plain `ProjectionCalculationStep`, would route null-identity rows into `observe` with no production-visible signal.

**Refutation considered**: Confirmed the current chain order in `SelectExecutionPlanner` (Phase 1 pre-aggregate projection chained directly before Phase 2 aggregation, nothing between). So this is not a live bug. Recorded as defense-in-depth: a cheap production-safe path (treat a null RID as a splice failure → uncached fallback, mirroring the existing `incrementSpliceFailures` contract) would convert a silent correctness violation into a benign cache bypass.

**Suggestion**: In `observe`, when `rid == null`, do not mutate the contributor collections; signal the populate as untappable so `populateAndBuildAggregateView` falls back to uncached execution (or at minimum keep the assert and add a comment that a null RID in production is unreconcilable). Optional — the planner-chain analysis shows the path is currently unreachable.

### BC2 [suggestion] Eager-drive does not handle an aggregation step with a non-immediate projection

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 203-223)

**Issue**: `spliceTap` inspects only `aggregateStep.prev` to decide whether to splice above a pre-aggregate projection. It assumes the identity-stripping projection, when present, is the aggregation step's *immediate* predecessor. If a future plan shape places any step (another projection, a filter, an unwind artifact) between the pre-aggregate projection and the aggregation step, the tap would splice between that intervening step and the aggregation — potentially still below an identity-stripping projection — and feed null-identity rows into `observe` (the BC1 corruption), or splice above a step that does not strip identity and observe rows twice/incorrectly.

**Evidence**: `spliceTap` reads `var prev = aggregateStep.prev;` and only descends one level (L210-214). The correctness of the splice rests entirely on the planner emitting the pre-aggregate projection as the direct `prev`. The current planner (`handleProjections`, L762-785) does exactly that with nothing chained between Phase 1 and Phase 2, so the single-level inspection is sufficient today.

**Refutation considered**: Verified the planner chains Phase 1 (`ProjectionCalculationStep(preAggregateProjection)`) immediately followed by Phase 2 (`AggregateProjectionCalculationStep`) with no intervening `chain(...)` call. The `GuaranteeEmptyCountStep` (L790-794) is chained *after* the aggregation (downstream/next), not between, so it does not affect the upstream splice. No current bug. The single-level assumption is correct against the present planner but is a coupling that would break silently (no test, no assert) if the planner adds an upstream step.

**Suggestion**: Add a brief comment in `spliceTap` documenting the load-bearing assumption ("the pre-aggregate projection, when present, is always the aggregation step's immediate prev per `SelectExecutionPlanner.handleProjections`"), or harden by walking up the prev-chain while each step is identity-stripping. Optional.

## Evidence base

#### C1 null-RID guard is assert-only
CONFIRMED-as-issue (suggestion): `AggregateState.observe` guards null RID with a Java `assert` only; `ResultInternal.getIdentity()` returns null for an identity-less projection row (verified at ResultInternal.java:708-715); planner chain analysis (SelectExecutionPlanner.handleProjections L762-798) shows the current splice point sees identity-carrying rows, so the assert never trips in practice — the finding is forward-looking hardening, not a live defect.

#### C2 splice only walks immediate prev
CONFIRMED-as-issue (suggestion): `spliceTap` inspects only `aggregateStep.prev`; the planner (SelectExecutionPlanner.handleProjections L762-785) chains the pre-aggregate projection as the direct predecessor of the aggregation step with nothing between, so single-level inspection is correct against the present planner. Coupling, not a current bug.

#### Refuted / examined-and-cleared claims (full)

- **Cache-code guard leak on eager-drive exception** — REFUTED. If `plan.start()` or the drive loop throws, the assignment `viewOwnsGuard = aggregateResult instanceof CachedResultSetView` (DatabaseSessionEmbedded L809) is never reached, so `viewOwnsGuard` stays false and `serveThroughCache`'s finally (L832-834) calls `tx.exitCacheCode()`. The inner try closes the stream, the outer try closes the plan. No guard leak, no plan leak. Matches the Track-1 "release the guard on every exit" contract the track file requires.

- **`inFlightLookup` reachable outside the `cacheCodeDepth` bracket** (carried Track-1 caution) — REFUTED. The aggregate path calls `cache.lookup` exactly once, inside the same `tx.enterCacheCode()` bracket as the RECORD/K0 path (`serveThroughCache` L782-785). `populateAndBuildAggregateView` does not call `lookup`. The guard stays defense-in-depth as Track 1 left it.

- **SUM/AVG `count` divisor staleness after lazy fold + copy** — REFUTED. Every contributor-changing path (`addContributor`, `removeContributor`, `updateContributor`) sets `sumDirty = true`; `ensureSumFolded` recomputes both `sumAccumulator` and `count` together in `refoldSum`. `copy()` carries `sumDirty` so a copied-then-replayed state folds lazily on first read. `count` can only be read stale if contributingValues changed without setting `sumDirty`, which no path does.

- **AVG divide-by-zero** — REFUTED. `computeAverage` divides only when `sum != null`; `refoldSum` leaves `sumAccumulator == null` exactly when `contributingValues` is empty, in which case `count == 0` too and the null-sum branch returns null before any division. Non-null sum implies count >= 1.

- **`copy()` shares mutable bucket sets with the original** — REFUTED. `copy()` rebuilds each `distinctBuckets` value as `new HashSet<>(e.getValue())` (AggregateState L882-884); `contributingValues`/`contributingRids` are copied via `putAll`/`addAll` into fresh containers. Test `copyDeepCopiesDistinctBuckets` pins this. A replay on the copy cannot disturb the entry's seeded state.

- **D21 collapse double-apply / stale contributor** — REFUTED. `applyMutation` derives `wasContributing` from cache membership and `nowContributing` from `(status != DELETED) && matchAfter`, never from `op.type`. A collapsed pre-populate CREATE already in `contributingValues` is read as a drop/update, not a new add. Tests `collapseCase_prePopulateCreateThenWhereBreak`, `collapseCreatedOpAlreadyContributingDropsOnWhereFail`, and `collapseCreatedOpAlreadyContributingRefoldsOnValueChange` cover it. The populate-version filter in `buildForAggregate` (op.version <= populateMutationVersion skipped) prevents replaying ops already baked into the seed.

- **Tap unreachable by `plan.start()`/`close()` because it is absent from `getSteps()`** — REFUTED. `SelectExecutionPlan.start()` calls `lastStep.start(ctx)` which recurses up the `prev` chain (verified SelectExecutionPlan.java:85-86); `close()` calls `lastStep.close()` which propagates backward via prev with a double-close guard (L76-77, AbstractExecutionStep.close L115-116). The spliced tap is in the prev chain, so both reach it. This matches the BC1 finding the Step-3 episode already deferred.

- **Double-close of the spliced tap (stream.close then plan.close)** — REFUTED. The eager-drive does `stream.close(ctx)` (propagates back through the prev chain including the tap) then `plan.close()` (calls `lastStep.close()` again). `AbstractExecutionStep` has a double-close guard flag (close propagates backward and could cycle, AbstractExecutionStep L101). No double-side-effect.

- **Plan/ctx leak via the entry** — REFUTED. The aggregate entry is built with null stream/plan/ctx (DatabaseSessionEmbedded L161-169); the plan is closed in `populateAndBuildAggregateView`'s own finally. The entry retains only the `AggregateState` (RIDs + detached Number/Object values), not the closed plan or its context.

- **Overflow guard installed but never reset after exception** — REFUTED. `installAggregateOverflowGuard` mutates only the soon-discarded `state`; on any exception path the state is not stored (put is reached only on the success path, and is a no-op once the key is in `nonCacheableKeys`). The overflow latch (`overflowed`) is one-shot per state and irrelevant after the state is discarded. Test `contributorCapOverflowRoutesKeyNonCacheable` confirms the entry is not retained and a repeat stays uncached.

- **`count(*) + 1` mis-routed to RECORD instead of K0_NONE** — REFUTED. `rootAggregateCall` returns null for an aggregate under arithmetic (`rootCallWithoutArithmetic` rejects any math node with a non-empty operator list, ShapeClassifier L1586-1589), so `singleAggregateShape` returns null and `classifySelect` falls to `projectionContainsAggregate` → K0_NONE (ShapeClassifier L166-176). Test `aggregateUnderArithmeticServedByK0None` pins the served value matches fresh.

- **Metric bridge null-registry NPE** — REFUTED. `globalRate` returns `TimeRate.NOOP` when the registry is null (QueryCacheMetrics L1287-1290), and every `increment*` records into a non-null sink. The no-arg constructor resolves the registry from `YouTrackDBEnginesManager`; the package-visible constructors inject sinks directly for deterministic tests.
