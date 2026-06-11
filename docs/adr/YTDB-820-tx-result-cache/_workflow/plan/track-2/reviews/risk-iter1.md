<!-- role: reviewer-risk | phase: 3A | track: Track 2 — Aggregate shapes | iteration: 1 -->
# Track 2 Risk Review — iteration 1

## Manifest

```yaml
review_type: risk
track: "Track 2: Aggregate shapes — side-tap, storage-parity replay, COUNT_DISTINCT"
phase: 3A
iteration: 1
reviewer: reviewer-risk
verdict: changes-requested
findings: 6
counts:
  blocker: 0
  should-fix: 5
  suggestion: 1
evidence_base:
  exposures: 3
  assumptions: 5
  testability: 2
tooling_note: >
  mcp-steroid PSI was unreachable this session (steroid_execute_code timed out on
  every query, including minimal ones, despite the project being open and matching
  the working tree). Symbol audits fell back to grep + Read. Reference-accuracy
  caveats are attached to the findings whose verdict depends on a symbol search
  (R5, R6).
index:
  - id: R1
    sev: should-fix
    anchor: "R1"
    loc: "track-2.md Validation lines 130-133; SelectExecutionPlanner.handleHardwiredCountOnClass:488"
    cert: "Exposure: splice point / aggregate-step presence"
    basis: "SELECT COUNT(*) FROM C is hardwired to CountFromClassStep — no aggregate step to splice"
  - id: R2
    sev: should-fix
    anchor: "R2"
    loc: "track-2.md Key signatures line 184; SQLFunctionAverage.computeAverage:101"
    cert: "Assumption: SUM/AVG storage parity via PropertyTypeInternal.increment"
    basis: "AVG parity needs computeAverage replication (type-dispatched division), not just increment"
  - id: R3
    sev: should-fix
    anchor: "R3"
    loc: "track-2.md Interfaces line 163; CachedEntry.java (final, 8-arg ctor, recordPulledRow)"
    cert: "Exposure: CachedEntry aggregate-state field + cap routing"
    basis: "CachedEntry has no aggregate field; cap-routing obligation maps to AggregateState, not results"
  - id: R4
    sev: should-fix
    anchor: "R4"
    loc: "track-2.md Plan of Work step 3-4; DatabaseSessionEmbedded.populateAndBuildView:842"
    cert: "Exposure: aggregate populate path divergence from RECORD"
    basis: "Eager-drive splice is a parallel populate path; must re-mirror guard/stream/close contract"
  - id: R5
    sev: should-fix
    anchor: "R5"
    loc: "track-2.md Context line 56-58, Plan of Work step 6; META-INF/services SQLFunctionFactory"
    cert: "Assumption: three SQLFunctionFactory implementations are the I5 surface"
    basis: "Four factories are registered; DynamicSQLElementFactory omitted without stated rationale"
  - id: R6
    sev: suggestion
    anchor: "R6"
    loc: "track-2.md Plan of Work step 5; SQLProjection / YouTrackDBSql.jj:3835"
    cert: "Assumption: COUNT(DISTINCT prop) classify is a flat function check"
    basis: "count(distinct(prop)) is a nested-function AST; classify must walk the nesting"
```

## Evidence base

### CRITICAL PATH EXPOSURE

#### Exposure: aggregate splice point — is an `AggregateProjectionCalculationStep` present to splice into?
- **Track claim**: the miss path builds the plan, walks `steps`, finds `AggregateProjectionCalculationStep`, and rewires its `prev` to the tap (Plan of Work step 3). The canonical validation query is `SELECT COUNT(*) FROM C` (Validation lines 130-133).
- **Critical path trace**:
  1. `SQLSelectStatement.createExecutionPlan(ctx, false)` @ `SQLSelectStatement.java:335` → `planner.createExecutionPlan(ctx, false, true)`.
  2. `SelectExecutionPlanner.createExecutionPlan` @ `:223` runs `handleHardwiredOptimizations(result, ctx, enableProfiling)` @ `:260` *before* the projection/aggregate chain is built.
  3. `handleHardwiredCountOnClass` @ `:488` fires for `COUNT(*)` on a class with no WHERE / no security policy / minimal query, chaining a single `CountFromClassStep` @ `:514` and returning early @ `:261`.
  4. `handleHardwiredCountOnClassUsingIndex` @ `:553` fires for `COUNT(*) FROM C WHERE field = ?` when a single-field index covers the equality.
- **Blast radius**: not a crash. The most-cited example (`COUNT(*) FROM C`) and the indexed-`COUNT(*)-WHERE` form produce plans with **no** `AggregateProjectionCalculationStep`, so the splice walk finds nothing and the track's own fallback (close plan, increment `spliceFailures`, uncached `LocalResultSet`) fires. Effect: the headline aggregate never caches, and the per-kind I4 test for `COUNT(*)` would exercise the fallback path, not the side-tap — a silently vacuous test if written against the example as stated.
- **Existing safeguards**: the fallback path is designed and correct (mirrors `populateAndBuildView`'s `!(original instanceof LocalResultSet)` early-return contract at `DatabaseSessionEmbedded.java:860`). `spliceFailures` maps to the registered `QUERY_CACHE_SPLICE_FAILURE_RATE` metric (`CoreMetrics.java:64`).
- **Residual risk**: MEDIUM — correctness is safe (fallback), but the track's scope/test framing overstates what caches. SUM/AVG/MIN/MAX/COUNT_DISTINCT and `COUNT(*)` over a non-indexed WHERE do build a real aggregate step; plain `COUNT(*)` and indexed-`COUNT(*)-WHERE` do not.

#### Exposure: `CachedEntry` aggregate-state field and the carried `recordPulledRow` cap obligation
- **Track claim**: "`CachedEntry` (aggregateState field, if not already added in Track 1)" (Interfaces line 163); carried obligation — "route every per-RID/row append through `CachedEntry.recordPulledRow` so the `maxRecordsPerEntry` cap applies to aggregate material" (plan Track-2 entry).
- **Critical path trace**:
  1. `CachedEntry` is `public final` with a single 8-arg constructor (`CachedEntry.java:53,113`) and no aggregate field; every Track-1 call site (`DatabaseSessionEmbedded.populateAndBuildView:873`) passes exactly those 8 args.
  2. The cap machinery (`recordPulledRow:268`, `setOverflowGuard:252`, `maxRecordsPerEntry:100`) governs `results`/`cachedRids` — record-oriented storage. The aggregate path holds **one** scalar `Result` plus the per-contributor material inside `AggregateState` (`contributingValues`/`contributingRids`/`distinctBuckets`).
- **Blast radius**: if the aggregate path appends its single scalar through `recordPulledRow`, the `maxRecordsPerEntry` cap (default 10000) never fires for an aggregate, because `results.size()` is 1 regardless of how many contributors the tap observed — the per-contributor growth that actually consumes memory lives in `AggregateState`, uncapped. The carried cap obligation, taken literally, protects the wrong collection.
- **Existing safeguards**: the cap is the only per-entry memory bound (D8); LRU `maxEntries` bounds entry count but not per-entry aggregate-state size.
- **Residual risk**: MEDIUM — an unbounded `distinctBuckets`/`contributingRids` on a high-cardinality `COUNT(DISTINCT)` or wide MIN/MAX recompute set is the realistic OOM vector this track introduces, and the Track-1 cap does not reach it without an explicit aggregate-material cap check.

#### Exposure: aggregate populate path diverges from the RECORD populate path
- **Track claim**: Plan of Work steps 3-4 — build the plan via `createExecutionPlan`, splice the tap, then **eager-drive** `plan.start(ctx).next(ctx)`, holding the scalar + completed `AggregateState` on the entry, "wrap in try / put-on-success-only".
- **Critical path trace**:
  1. The Track-1 RECORD path reuses `executeUncached` (`DatabaseSessionEmbedded.java:818`), which calls `statement.execute(...)` → `LocalResultSet` whose constructor calls `plan.start()` (per the comment at `populateAndBuildView:849-850`), then lifts the stream/plan lazily — never drives to completion.
  2. The aggregate path cannot reuse that: it must construct the plan itself (`createExecutionPlan`), mutate the step chain (splice), and drive to completion at populate. This is a second, parallel populate path that does not flow through `executeUncached`/`populateAndBuildView`.
  3. The guard contract (`serveThroughCache:774-814`): `enterCacheCode()` brackets the lookup; `viewOwnsGuard = result instanceof CachedResultSetView` transfers the guard to the view, which releases it once on close. The aggregate view eagerly drained at populate, so it never lazily pulls — yet it must still release the guard exactly once on close.
- **Blast radius**: a populate path that does not faithfully re-mirror Track 1's contract risks (a) a leaked `cacheCodeDepth` bump that bypasses the cache for the rest of the transaction (the exact bug Track-1 Phase C fixed for the fallback branch), (b) a double-closed or never-closed plan/stream, or (c) a populate-version stamp captured after the eager drive instead of before, which would let the delta builder mis-filter post-populate ops.
- **Existing safeguards**: `IdempotentExecutionStream` (D9) makes a second close a no-op; `CachedEntry.close()` is null-out idempotent (`CachedEntry.java:303`); the `viewOwnsGuard`/finally structure is in place to copy.
- **Residual risk**: MEDIUM — the contract is documented and copyable, but it is the highest-complexity integration point in the track and the failure modes are silent (leaked depth disables caching invisibly; mis-ordered stamp produces wrong scalars only under specific mutation patterns).

### UNKNOWNS & ASSUMPTIONS

#### Assumption: SUM/AVG replay matches storage bit-for-bit via `PropertyTypeInternal.increment`
- **Track claim**: "SUM/AVG fold through the same `PropertyTypeInternal.increment` storage uses (D19)" (Purpose; Key signatures line 186 lists only `increment`).
- **Evidence search**: grep + Read of `SQLFunctionSum.java`, `SQLFunctionAverage.java`, `PropertyTypeInternal.java`.
- **Code evidence**: `SQLFunctionSum.sum` @ `:73` and `SQLFunctionAverage.sum` @ `:80` both call `PropertyTypeInternal.increment(sum, value)` — SUM half VALIDATED. But `SQLFunctionAverage.getResult` @ `:91` returns `computeAverage(sum, total)` @ `:101`, which is a **separate** primitive doing type-dispatched division: `Integer → iSum.intValue() / iTotal` (integer-truncating), `Long → longValue()/iTotal`, `Float`/`Double` floating division, `BigDecimal → bd.divide(new BigDecimal(iTotal), RoundingMode.HALF_UP)`. AVG also requires tracking `total` (the contributor count), which `increment` does not touch. Separately, `SQLFunctionSum.getResult` @ `:89` returns `0` (Integer) for an empty set, not null.
- **Verdict**: PARTIALLY VALIDATED — SUM-accumulation parity holds; AVG-finalization parity needs `computeAverage` + `total` replicated, which the track's signature list omits.
- **Detail**: an `AggregateState.toResult` for AVG that divides without reproducing `computeAverage`'s exact type dispatch (especially Integer truncation and BigDecimal HALF_UP rounding) will diverge from fresh execution on the I4 parity check.

#### Assumption: `COUNT(DISTINCT prop)` is a single function the classifier matches by name
- **Track claim**: "`COUNT(DISTINCT prop)` → AGGREGATE_COUNT_DISTINCT, expression-DISTINCT → K0_NONE" (Plan of Work step 5); D20 models it on `SQLFunctionDistinct`'s `LinkedHashSet` raw equals.
- **Evidence search**: grep of `YouTrackDBSql.jj`, `SQLFunctionCount.java`, `SQLFunctionDistinct.java`.
- **Code evidence**: `SQLFunctionCount` @ `SQLFunctionCount.java:34` is a plain counter with no DISTINCT support. The grammar @ `YouTrackDBSql.jj:3835` parses `DISTINCT` inside a function-arg as `new SQLIdentifier("distinct")` — i.e. `COUNT(DISTINCT prop)` becomes the **nested** call `count(distinct(prop))`. `SQLFunctionDistinct.context` @ `SQLFunctionDistinct.java:37` is a `LinkedHashSet<Object>` with raw `contains` @ `:53` — so the D20 raw-equals model is grounded.
- **Verdict**: VALIDATED (D20 model) but the classify shape is UNDERSTATED.
- **Detail**: the classifier must recognise the nested `count(distinct(<plain prop>))` AST shape and distinguish it from `count(distinct(<expression>))` (→ K0_NONE). A flat top-level function-name check is insufficient; the branch walks one level into the count's argument.

#### Assumption: there are three `SQLFunctionFactory` implementations to enumerate for I5
- **Track claim**: "Three `SQLFunctionFactory` implementations (`DefaultSQLFunctionFactory`, `CustomSQLFunctionFactory` reflective `math_*`, `DatabaseFunctionFactory` stored) are the enumeration surface for the I5 completeness test" (Context lines 56-58); Plan of Work step 6 — "walks all three factories".
- **Evidence search**: grep of `implements SQLFunctionFactory` across `core/src/main`; Read of `META-INF/services/...SQLFunctionFactory`. (PSI `ClassInheritorsSearch` was attempted but the IDE timed out — see tooling note.)
- **Code evidence**: the ServiceLoader file registers **four** implementations — `DefaultSQLFunctionFactory`, `DynamicSQLElementFactory`, `DatabaseFunctionFactory`, `CustomSQLFunctionFactory`. `DynamicSQLElementFactory` @ `DynamicSQLElementFactory.java:37` implements `SQLFunctionFactory`; its `getFunctionNames` @ `:51` returns a runtime-populated static `FUNCTIONS` map (`:41`), not a build-time set.
- **Verdict**: PARTIALLY CONTRADICTED — three named, four registered.
- **Detail**: `DynamicSQLElementFactory`'s set is runtime-registered, so omitting it from a build-time enumeration test is defensible, but the track states "three" as if complete. The omission is currently silent and reads as an oversight; the D6 fail-open safety net rests on the I5 test being the completeness gate, so the scope of that gate must be explicit. Reference-accuracy caveat: factory enumeration was confirmed via grep + the ServiceLoader manifest, not PSI inheritor search.

#### Assumption: splicing into the per-execution plan does not corrupt the shared plan cache
- **Track claim**: implicit in Plan of Work step 3 — the miss path mutates `plan.steps` / `step.prev`.
- **Evidence search**: Read of `SelectExecutionPlanner.createExecutionPlan` and `YqlExecutionPlanCache`.
- **Code evidence**: the plan-cache hit returns `result.copy(ctx)` (`YqlExecutionPlanCache.java:138`) and `put` stores `internal.copy(ctx)` (`:104`); `copyOn` (`SelectExecutionPlan.java:260`) builds an independent step chain. So `createExecutionPlan(ctx, false)` always hands the caller a fresh per-execution copy.
- **Verdict**: VALIDATED.
- **Detail**: the splice mutates a private copy; no shared-cache corruption risk. This is an existing safeguard that lowers what could otherwise be a blocker to a non-issue — recorded so the track does not over-engineer a defensive clone.

#### Assumption: eager-drive at populate matches uncached aggregate latency
- **Track claim**: "Eager drive matches the uncached aggregate latency profile (every aggregate query blocks to produce its row anyway)" (Compatibility line 172).
- **Evidence search**: Read of `AggregateProjectionCalculationStep`.
- **Code evidence**: `executeAggregation` @ `:121-153` is a blocking full drain — `while lastRs.hasNext(ctx) { aggregate(...) }` then finalize — before emitting any row. `internalStart` @ `:104` returns the already-materialized iterator.
- **Verdict**: VALIDATED — the uncached aggregate already blocks to full drain, so eager-driving at populate adds no new latency class.

### TESTABILITY & COVERAGE

#### Testability: per-kind I4 equivalence + D21 collapse case
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the I4 matrix (six kinds × four mutation patterns + collapse case) is achievable with the cache-on/cache-off parity harness Track 1 established (`TxResultCacheInvariantsTest`, `DeltaBuilderTest`). The hidden gap is R1: a `COUNT(*) FROM C` I4 test written against the stated example exercises the fallback, not the side-tap, so it would pass while testing nothing of this track's new code. The side-tap path is reached only by SUM/AVG/MIN/MAX/COUNT_DISTINCT and `COUNT(*)` over a non-indexed WHERE.
- **Existing test infrastructure**: `core/src/test/.../cache/` carries the Track-1 invariant + delta-builder + classifier suites; the parity-harness pattern is reusable.
- **Feasibility**: ACHIEVABLE, conditioned on the test queries actually routing through the splice (R1) and on AVG/COUNT_DISTINCT finalization parity (R2, R6).

#### Testability: splice-fallback branch and `spliceFailures` increment
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the fallback branch (no `AggregateProjectionCalculationStep` in the plan) is directly reachable by a plain `COUNT(*) FROM C` (R1 makes this the default, not an edge case), so coverage of the fallback + `spliceFailures`/`QUERY_CACHE_SPLICE_FAILURE_RATE` increment is straightforward. Driving the *unexpected-shape* branch deliberately (a plan that has projections but the aggregate step in an unexpected position) is harder to construct and may need a synthetic plan.
- **Existing test infrastructure**: `QueryCacheMetricsTest` covers metric increments; the fallback assertion (`no exception leaks`, increments `spliceFailures`) is in the Validation list.
- **Feasibility**: ACHIEVABLE.

## Findings

### R1 [should-fix]
**Certificate**: Exposure — aggregate splice point / aggregate-step presence
**Location**: `track-2.md` Validation lines 130-133 and Purpose lines 5-8; `SelectExecutionPlanner.handleHardwiredCountOnClass` @ `:488`, `handleHardwiredCountOnClassUsingIndex` @ `:553`.
**Issue**: The track's headline example, `SELECT COUNT(*) FROM C`, never reaches the side-tap. The planner hardwires bare `COUNT(*)` (and indexed `COUNT(*) WHERE field = ?`) to a single `CountFromClassStep` before the aggregate chain is built, so the splice walk finds no `AggregateProjectionCalculationStep` and the fallback (uncached, `spliceFailures++`) fires. Likelihood: certain for these query forms; impact: the most common aggregate silently never caches, and a per-kind I4 test written against the stated `COUNT(*)` example would assert against the fallback path while believing it tests the side-tap (vacuous coverage).
**Proposed fix**: Update the track's Purpose/Validation to state which aggregate forms reach the side-tap (SUM/AVG/MIN/MAX/COUNT_DISTINCT, and `COUNT(*)` over a *non-indexed* WHERE) versus which hardwire to `CountFromClassStep` and route to the fallback. Either (a) accept that bare/indexed `COUNT(*)` stays uncached and write the I4 `COUNT(*)` test against a non-indexed-WHERE form so it actually exercises the tap, or (b) add an explicit decision to extend caching to the `CountFromClassStep` shape (separate, simpler delta — a metadata count + post-populate created/deleted count adjustment). Add a fallback-path test using bare `COUNT(*)` to cover `spliceFailures`.

### R2 [should-fix]
**Certificate**: Assumption — SUM/AVG storage parity via `PropertyTypeInternal.increment`
**Location**: `track-2.md` Purpose lines 15-17, Key signatures line 186 (lists only `increment`); `SQLFunctionAverage.computeAverage` @ `:101`, `getResult` @ `:91`; `SQLFunctionSum.getResult` @ `:89`.
**Issue**: D19 parity is presented as "fold through `increment`," but AVG finalization is a distinct primitive: `computeAverage(sum, total)` does type-dispatched division (Integer truncation, BigDecimal HALF_UP), and AVG needs the contributor `total` tracked alongside `sum`. SUM's empty-set result is `0` (Integer), not null. Likelihood: high that an `AggregateState.toResult` for AVG re-implements division generically; impact: I4 parity divergence on Integer AVG (truncation), BigDecimal AVG (rounding), and empty-set SUM.
**Proposed fix**: Add `total` to the AVG state and have `toResult` reproduce `computeAverage`'s exact type dispatch (ideally by calling the same code path or a shared helper), and reproduce SUM's `sum == null ? 0` empty-set result. Note both explicitly in the step that builds `AggregateState` and add I4 cases for Integer-AVG truncation, BigDecimal-AVG rounding, and SUM-of-empty.

### R3 [should-fix]
**Certificate**: Exposure — `CachedEntry` aggregate-state field + carried cap obligation
**Location**: `track-2.md` Interfaces line 163, plan Track-2 entry (carried `recordPulledRow` obligation); `CachedEntry.java` (`final`, 8-arg ctor @ `:113`, `recordPulledRow` @ `:268`, `maxRecordsPerEntry` @ `:100`).
**Issue**: `CachedEntry` is `final` with no aggregate field and a single 8-arg constructor used by Track 1's only call site, so adding `AggregateState` needs a new constructor or a settable field plus a populate-path change. More importantly, the carried "route appends through `recordPulledRow` so `maxRecordsPerEntry` applies" obligation does not bound aggregate memory: an aggregate entry's `results` holds one scalar row, while the per-contributor growth (`contributingRids`, `distinctBuckets`) lives in `AggregateState` and is uncapped. Likelihood: certain the obligation as written misses the aggregate memory vector; impact: unbounded `AggregateState` growth on high-cardinality `COUNT(DISTINCT)` / large MIN/MAX recompute sets — the realistic OOM this track introduces.
**Proposed fix**: Decide and document how the aggregate path satisfies the memory bound — either apply `maxRecordsPerEntry` to the count of observed contributors inside the tap/`AggregateState` (overflow → evict entry + `nonCacheableKeys`, same one-shot semantics as `recordPulledRow`), or add a separate aggregate-material cap. Specify the `CachedEntry` extension (new constructor vs setter) so the Track-1 call site and the new aggregate populate path stay consistent.

### R4 [should-fix]
**Certificate**: Exposure — aggregate populate path divergence from RECORD
**Location**: `track-2.md` Plan of Work steps 3-4; `DatabaseSessionEmbedded.populateAndBuildView` @ `:842`, `serveThroughCache` guard structure @ `:774-814`.
**Issue**: The aggregate miss path cannot reuse `populateAndBuildView` (which lifts a lazily-driven stream off a `LocalResultSet` built by `executeUncached`). It must build the plan itself, splice, and eager-drive — a parallel populate path that must independently re-mirror three Track-1 contracts: (1) capture `populateMutationVersion` *before* the eager drive; (2) `enterCacheCode`/`viewOwnsGuard`/finally so the guard is released exactly once and `cacheCodeDepth` is never leaked on the fallback branch (the exact class of bug Track-1 Phase C fixed); (3) idempotent stream/plan close via the entry. Likelihood: medium that one of the three is missed; impact: silent failures — a leaked depth disables caching for the rest of the transaction invisibly, a mis-ordered stamp produces wrong scalars only under specific mutation timing.
**Proposed fix**: In the step that adds the splice + eager drive, call out all three contracts explicitly and structure the aggregate populate path to mirror `populateAndBuildView` / `serveThroughCache` (same stamp-before-drive ordering, same `viewOwnsGuard` transfer + finally release, same `IdempotentExecutionStream`/entry-owns-close wiring). Add a test that asserts `cacheCodeDepth` returns to 0 after both the splice-success and splice-fallback aggregate paths.

### R5 [should-fix]
**Certificate**: Assumption — three `SQLFunctionFactory` implementations are the I5 surface
**Location**: `track-2.md` Context lines 56-58, Plan of Work step 6; `META-INF/services/...SQLFunctionFactory`; `DynamicSQLElementFactory` @ `:37,51`.
**Issue**: The track names three factories as "the enumeration surface" but four are registered (`DefaultSQLFunctionFactory`, `DynamicSQLElementFactory`, `DatabaseFunctionFactory`, `CustomSQLFunctionFactory`). The D6 fail-open denylist relies on the I5 test as its completeness gate, so an unstated omission undercuts that guarantee on its face. `DynamicSQLElementFactory` exposes a runtime-populated `FUNCTIONS` map (not build-time enumerable), which is a legitimate reason to exclude it — but the exclusion must be stated, not silent. Likelihood: medium that the I5 test ships enumerating three and a reviewer reads "three" as complete; impact: a future non-deterministic builtin routed through the omitted factory slips both the denylist (fail-open) and the completeness gate. Reference-accuracy caveat: factory set confirmed via grep + ServiceLoader manifest (PSI inheritor search timed out), so a polymorphic/indirect `SQLFunctionFactory` implementor outside the manifest is not ruled out by this audit.
**Proposed fix**: In Context/step 6, state the four registered factories and why `DynamicSQLElementFactory` is out of the build-time I5 surface (runtime-registered functions). If any statically-known function reaches the engine through it, fold that factory into the test; otherwise document the runtime-registration boundary as the explicit limit of the I5 gate.

### R6 [suggestion]
**Certificate**: Assumption — `COUNT(DISTINCT prop)` classify shape
**Location**: `track-2.md` Plan of Work step 5; `YouTrackDBSql.jj` @ `:3835`; `SQLFunctionCount` @ `:34`, `SQLFunctionDistinct` @ `:37`.
**Issue**: `COUNT(DISTINCT prop)` parses as the nested call `count(distinct(prop))` (the grammar turns the `DISTINCT` keyword into an inner `distinct(...)` function), not a flat `count` with a modifier. The classify branch that routes `COUNT(DISTINCT prop)` → AGGREGATE_COUNT_DISTINCT and `COUNT(DISTINCT a+b)` → K0_NONE must walk one level into the count's argument and check the inner `distinct`'s argument is a plain property. Likelihood: low that this blocks the track (it is testable), but a flat name check would misclassify. Impact: a missed nesting either fails to cache a cacheable `COUNT(DISTINCT prop)` or wrongly caches an expression form.
**Proposed fix**: Specify in step 5 that the COUNT_DISTINCT classifier inspects the nested `count(distinct(<arg>))` shape and gates on `<arg>` being a plain property reference; add classifier tests for `count(distinct(prop))` (cacheable) and `count(distinct(a+b))` (K0_NONE).
