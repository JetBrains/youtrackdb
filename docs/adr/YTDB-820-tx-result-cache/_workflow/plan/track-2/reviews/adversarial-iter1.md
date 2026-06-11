<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Adversarial review — Track 2 (Aggregate shapes) — iteration 1

- role: reviewer-adversarial
- phase: 3A
- track: "Track 2: Aggregate shapes — side-tap, storage-parity replay, COUNT_DISTINCT"
- verdict: changes-requested
- findings: 6
- blockers: 2

## Manifest

```yaml
index:
  - id: A1
    sev: blocker
    anchor: "#a1"
    loc: "track-2.md §Plan of Work step 3; design D10/D19"
    cert: "Assumption test: every aggregate query exposes an AggregateProjectionCalculationStep to splice"
    basis: "SelectExecutionPlanner.java:259-262,488-509; CountFromClassStep.java:12-18,56-62; CountFromIndexStep.java"
  - id: A2
    sev: blocker
    anchor: "#a2"
    loc: "track-2.md §Plan of Work step 3 (rewires prev)"
    cert: "Violation scenario: splicing prev mutates the shared cached plan"
    basis: "SelectExecutionPlanner.java:235-240; SQLSelectStatement.java:335-341; AbstractExecutionStep.java:66"
  - id: A3
    sev: should-fix
    anchor: "#a3"
    loc: "track-2.md carried-from-Track-1 note; §Plan of Work step 4"
    cert: "Assumption test: recordPulledRow is the right cap point for aggregate material"
    basis: "CachedEntry.java:268-287,155-159"
  - id: A4
    sev: should-fix
    anchor: "#a4"
    loc: "track-2.md §Purpose; design D19"
    cert: "Challenge: D19 — AVG storage parity needs computeAverage, not just increment"
    basis: "SQLFunctionAverage.java:72-115"
  - id: A5
    sev: suggestion
    anchor: "#a5"
    loc: "track-2.md §Validation (COUNT(*) case); design D10"
    cert: "Challenge: D10 — caching bare COUNT(*) duplicates the existing O(1) tx-aware path"
    basis: "CountFromClassStep.java:56-62,74-81"
  - id: A6
    sev: should-fix
    anchor: "#a6"
    loc: "track-2.md §Plan of Work step 1 (membership dispatch); design D21"
    cert: "Assumption test: membership-only dispatch with a boolean matchAfter is sufficient for MIN/MAX/SUM"
    basis: "DeltaBuilder.java:136-179; FrontendTransactionImpl.java:641-668; CachedEntry.java:159"
evidence_base:
  challenges: 3
  violation_scenarios: 1
  assumption_tests: 3
tooling_note: >
  mcp-steroid steroid_execute_code timed out repeatedly this session (trivial
  snippets at 300-400s), so all symbol audits fell back to grep + Read. The
  IDE project ("design.md") is open and matches the working tree, but PSI
  execution was unavailable. Reference-accuracy caveat: the "no other caller
  splices prev on a cached plan" and "no existing incremental-aggregate infra"
  claims rest on grep, not PSI find-usages; a polymorphic or generic call site
  could be missed. The class-existence and method-body facts (CountFromClassStep,
  the hardwired-optimization branch, the no-copy cache return, recordPulledRow's
  body) are direct Reads of the source and are not subject to that caveat.
```

## Findings

### A1 [blocker]
**Certificate**: Assumption test — "every aggregate query exposes an `AggregateProjectionCalculationStep` to splice"
**Target**: Decision D10 (side-tap splice) / Track-2 Plan of Work step 3
**Challenge**: The splice strategy assumes every cacheable aggregate plan contains an `AggregateProjectionCalculationStep` to rewire. For the single most common aggregate shape — bare `SELECT COUNT(*) FROM C` and `SELECT COUNT(*) FROM C WHERE indexed = ?` — the planner short-circuits to a `CountFromClassStep` / `CountFromIndexStep` **before** any aggregation step is built. `SelectExecutionPlanner.createExecutionPlan` calls `handleHardwiredOptimizations` at line 260 and `return result` at 261 when it fires; `handleHardwiredCountOnClass` (488-509) applies to `COUNT(*)` with no GROUP BY/ORDER BY/SKIP/LIMIT/LET. The resulting plan has no scan, no per-record stream, and no `AggregateProjectionCalculationStep`. The track's step 3 ("walk `steps`, find `AggregateProjectionCalculationStep`, rewire its `prev` to the tap; on unexpected shape, fall back to uncached") therefore *always* falls back for `AGGREGATE_COUNT`, silently disabling the cache for the dominant aggregate — yet the track's headline validation case is exactly `SELECT COUNT(*) FROM C` (§Validation line 1, §Purpose). The test would pass against the fallback path and prove nothing about the tap.
**Evidence**: `SelectExecutionPlanner.java:259-262` (short-circuit return), `:488-509` (`handleHardwiredCountOnClass` preconditions), `:470-475` (`handleHardwiredOptimizations` also covers the indexed-count case), `CountFromClassStep.java:12-18` (no scan, metadata-only), `:56-62` (`target.count(session)`). No per-RID material is produced for these plans, so even if a tap point existed there is nothing to seed `contributingRids` with.
**Proposed fix**: Make the plan choose between two delta strategies explicitly. Either (a) route `AGGREGATE_COUNT` (non-DISTINCT) to a count-specific delta that does not depend on the tap — it needs only `contributingRids` membership and `matchAfter`, which can be seeded by a one-time scan or by recognising the hardwired count and re-deriving the contributor set — or (b) declare bare/indexed `COUNT(*)` out of scope for the tap and document that they fall back (see A5, which argues they should not be cached at all). Whichever is chosen, replace the §Validation `COUNT(*)` case with an aggregate kind that actually reaches `AggregateProjectionCalculationStep` (e.g. `SUM`/`MIN`), and add an explicit assertion that the tap was spliced (not the fallback taken) for every kind the track claims to cache.

### A2 [blocker]
**Certificate**: Violation scenario — "splicing `prev` mutates the shared cached plan"
**Target**: Track-2 Plan of Work step 3 (rewires `prev`)
**Challenge**: Step 3 builds the plan via `statement.createExecutionPlan(ctx, false)` and then mutates it in place (rewires `AggregateProjectionCalculationStep.prev` to the tap). `createExecutionPlan(ctx, false)` → `planner.createExecutionPlan(ctx, false, /*useCache=*/true)`, and on a cache hit `SelectExecutionPlanner` returns the cached plan **instance directly with no copy** (`:235-240`, `return (InternalExecutionPlan) plan;`). `AbstractExecutionStep.prev` is a public mutable field (`:66`). So the splice mutates the `SelectExecutionPlan` held in `YqlExecutionPlanCache`, shared by every other caller of the same statement text.
**Violation construction**:
1. Start state: `SELECT SUM(price) FROM Product` executed once uncached; its plan is now in `YqlExecutionPlanCache`.
2. Action: an aggregate cache-miss for the same SQL calls `createExecutionPlan(ctx, false)` → cache hit → the shared plan instance is returned; the track rewires `aggStep.prev = tap`.
3. Intermediate state: the cached plan's step chain now contains `tap` between the fetch step and the aggregation step.
4. Violation point: the *next* execution of `SELECT SUM(price) FROM Product` — cache-disabled, profiling, a different session, or a second aggregate miss — retrieves the same mutated instance (`SelectExecutionPlanner.java:236-238`). It re-runs with a stale `tap` whose `observe` writes into a now-dead `AggregateState`, or with a doubly-spliced chain.
5. Observable consequence: cross-query plan corruption — wrong results or an NPE on a closed tap, intermittent and dependent on cache-eviction timing.
**Feasibility**: CONSTRUCTIBLE. The cache return path is non-copying and the splice target field is mutated in place; nothing in the track isolates the plan.
**Evidence**: `SelectExecutionPlanner.java:235-240`; `SQLSelectStatement.java:335-341` (no copy on the path); `AbstractExecutionStep.java:66`.
**Proposed fix**: Splice into a **non-shared** plan. Use `createExecutionPlanNoCache(ctx, false)` for the aggregate-miss path (it builds a fresh `SelectExecutionPlan` every call, `:344-351`), or deep-`copy()` the plan before mutating. Record the choice in the track's Compatibility note and add a regression test that runs the same aggregate SQL twice (cached then uncached) and asserts the second execution's plan chain is untouched.

### A3 [should-fix]
**Certificate**: Assumption test — "`recordPulledRow` is the right cap-enforcement point for aggregate material"
**Target**: Track-2 carried-from-Track-1 note ("route every per-RID/row append through `CachedEntry.recordPulledRow`") + Plan of Work step 4
**Challenge**: The carried-forward instruction assumes routing aggregate per-RID appends through `recordPulledRow` makes `maxRecordsPerEntry` apply to aggregate material. But `recordPulledRow` appends to `results` (the consumer-visible row list, `:269`) and to `cachedRids` (`:275`), and the aggregate `CachedResultSetView` path returns a single scalar row from `results` (`hasNext` true once, `toResult()`). Pushing every contributing record into `results` would make the aggregate view emit N rows instead of one — a direct I10 transparency violation — and would pollute `cachedRids` with RIDs the RECORD delta semantics do not expect for this shape. The per-contributor growth that the cap should bound lives in `AggregateState` (`contributingRids`, `contributingValues`, `distinctBuckets`), not in `results`.
**Stress scenario**: `SELECT SUM(price) FROM Product` over 50 000 rows with `maxRecordsPerEntry=10000`. Routed through `recordPulledRow`, `results` reaches 50 000 entries and the view emits 50 000 rows; routed correctly, `results` holds one scalar and the cap must instead watch `distinctBuckets`/`contributingValues` size.
**Verdict**: BREAKS as literally specified. The cap is meaningful for aggregates but must be enforced against the `AggregateState` collections, with overflow firing the same `onOverflow` eviction callback `CachedEntry` already exposes (`setOverflowGuard`, `:252`).
**Evidence**: `CachedEntry.java:268-287` (`recordPulledRow` body appends to `results`/`cachedRids` and checks `results.size()`), `:155-159` (`results` is the row list the view reads).
**Proposed fix**: Drop the literal "route per-RID appends through `recordPulledRow`" for aggregates. Add an aggregate-specific cap: count distinct buckets / contributor entries inside `AggregateState.observe`, and on crossing `maxRecordsPerEntry` invoke the existing `onOverflow` to evict and route the key non-cacheable. Keep `results` at exactly one scalar row. Note this in step 4 and the carried-from-Track-1 paragraph.

### A4 [should-fix]
**Certificate**: Challenge — D19 (AVG storage parity)
**Target**: Decision D19 / Track-2 §Purpose ("SUM/AVG fold through the same `PropertyTypeInternal.increment` storage uses")
**Chosen approach**: Replay SUM and AVG through `PropertyTypeInternal.increment` for bit-for-bit storage parity.
**Counterargument trace**:
1. For SUM this is exact: `SQLFunctionSum.sum` is `sum = increment(sum, value)` (`:73`), and `getResult` returns the running sum.
2. For AVG, `increment` only maintains the running **sum**; the returned scalar is `computeAverage(sum, total)` (`SQLFunctionAverage.java:91-115`), which divides by an integer `total` with per-type semantics — `Integer`/`Long` use **integer division** (`iSum.intValue()/iTotal`, truncating), `Float`/`Double` use FP division, `BigDecimal` uses `divide(..., HALF_UP)`.
3. An `AggregateState.toResult()` that returns `increment`-folded sum, or that divides with a different rule, diverges from fresh execution: e.g. AVG over `[3, 4]` of `Long` returns `3` (7/2 truncated), not `3.5`.
**Codebase evidence**: `SQLFunctionAverage.java:72-83` (running sum via `increment` + a separate `total++`), `:91-115` (`computeAverage` type-dispatched divide with integer truncation for Integer/Long).
**Survival test**: WEAK. D19's storage-parity claim is correct for the running accumulator but the design framing ("SUM/AVG fold through increment") omits that AVG parity additionally requires (a) tracking `total` exactly as `SQLFunctionAverage` does — only non-null values increment it — and (b) reproducing `computeAverage`'s per-type division verbatim in `toResult`.
**Proposed fix**: In step 1, specify the AVG state as `(runningSum via increment, total count of non-null values)` and require `toResult` to call the same `computeAverage` logic (extract or reuse it) so the truncation and rounding match. Add an AVG I4 case over `Long` inputs whose true mean is fractional, asserting the cached scalar equals the truncated fresh result.

### A5 [suggestion]
**Certificate**: Challenge — D10 (caching bare COUNT(*))
**Target**: Decision D10 / §Validation `COUNT(*)` case
**Chosen approach**: Cache `AGGREGATE_COUNT` including bare `COUNT(*)`.
**Counterargument trace**:
1. Bare `SELECT COUNT(*) FROM C` is already O(1) and already transaction-correct without any cache: `CountFromClassStep.produce` reads `target.count(session)` inside `computeInTxInternal` (`:56-62`), so the count already reflects in-tx CREATE/DELETE.
2. The cache would replace a single metadata read with: a scan (to seed `contributingRids` — see A1, there is no scan in the hardwired plan to tap), an `AggregateState` copy, and a post-populate delta replay.
3. Outcome: more work, more memory, and a new correctness surface, to re-derive a number the existing step computes correctly in one read.
**Codebase evidence**: `CountFromClassStep.java:56-62` (live tx-aware count), `:74-81` (`canBeCached()` false precisely because the count is cheap-and-live).
**Survival test**: WEAK for bare `COUNT(*)`; the decision likely holds for `COUNT` over a `WHERE` that does not reduce to an indexed count (those do reach the aggregation step and gain from caching). The general D10 decision survives, but the specific inclusion of bare/indexed `COUNT(*)` does not add value.
**Proposed fix**: Either exclude bare and indexed `COUNT(*)` from `AGGREGATE_COUNT` (let the existing O(1) step handle them; classify to K0_NONE or leave uncached) or, if kept for uniformity, state in the track why paying the cache cost over the existing O(1) path is acceptable. This also resolves A1's validation-case problem.

### A6 [should-fix]
**Certificate**: Assumption test — "membership-only dispatch with a precomputed boolean `matchAfter` is sufficient for MIN/MAX/SUM collapse safety"
**Target**: Decision D21 / Track-2 Plan of Work step 1 ("dispatch keys on `(was_contributing → now_contributing)` ... never `op.type`")
**Challenge**: Two sub-claims need tightening.
1. *"Never `op.type`."* The shipped RECORD path does **not** dispatch on membership alone — it switches on `op.type` (`DeltaBuilder.java:145`) and *combines* it with membership (`cachedRids.contains(rid)`, `:139,:150`) to disambiguate the collapsed pre-populate-CREATE-then-UPDATE case. The collapse logic in `addRecordOperation` keeps `type=CREATED` after an update to a created record (`FrontendTransactionImpl.java:654-661`), so `op.type` is still load-bearing alongside membership. The track's absolute "never `op.type`" framing contradicts the foundation it builds on; the real rule is "membership *and* the collapsed `op.type` together," as the RECORD path already does.
2. *Membership set identity.* For aggregates the contributor set is the **post-WHERE survivors** observed by the tap, which is a different set from `cachedRids` (all cached rows). `applyMutation(record, status, matchAfter)` is handed only a boolean `matchAfter`; for SUM and MIN/MAX a pass→pass UPDATE that changes the *value* (not the WHERE outcome) still requires re-extracting the new property value from the post-mutation record. A boolean cannot carry that. The transitions that need the value: was-contributing→still-contributing with a changed value (SUM re-fold; MIN/MAX possible extremum move) and fail→pass (new value enters).
**Code evidence**: `DeltaBuilder.java:136-179` (RECORD combines `op.type` + `cachedRids` membership), `FrontendTransactionImpl.java:641-668` (collapse keeps CREATED, re-stamps version), `CachedEntry.java:159` (`cachedRids` is the full cached-row set, distinct from a post-WHERE contributor set).
**Verdict**: FRAGILE. The membership idea is sound but underspecified: the dispatch must read both the collapsed `op.type` and membership, and `applyMutation` must take (or internally extract) the post-mutation contributing **value**, not just a boolean.
**Proposed fix**: Restate step 1's dispatch rule as "membership (`contributingValues.containsKey(rid)`) combined with the collapsed `op.type` and `matchAfter`," matching the RECORD path's proven shape. Specify that `applyMutation` extracts the aggregate's argument property from the post-mutation record for the value-changing transitions (it already takes `RecordAbstract`), and add an I4 case for a pass→pass UPDATE that changes the summed/extremum property without changing WHERE membership.

## Evidence base

### Challenges

#### Challenge: D19 — AVG storage parity needs computeAverage, not just increment
- **Chosen approach**: replay SUM/AVG through `PropertyTypeInternal.increment` for storage parity.
- **Best rejected alternative**: n/a (parity primitive is correct for SUM); the challenge is to the *completeness* of the AVG parity claim.
- **Counterargument trace**: `increment` maintains the running sum only; AVG's scalar is `computeAverage(sum, total)`, which truncates for Integer/Long and rounds HALF_UP for BigDecimal (`SQLFunctionAverage.java:91-115`). A state that returns the folded sum, or divides differently, diverges (Long mean of [3,4] is 3, not 3.5).
- **Codebase evidence**: `SQLFunctionAverage.java:72-83`, `:91-115`.
- **Survival test**: WEAK — parity for SUM holds; AVG additionally needs exact `total` tracking and a verbatim `computeAverage` in `toResult`.

#### Challenge: D10 — caching bare COUNT(*) duplicates the existing O(1) tx-aware path
- **Chosen approach**: cache `AGGREGATE_COUNT` including bare `COUNT(*)`.
- **Best rejected alternative**: leave bare/indexed `COUNT(*)` to the existing hardwired step (uncached / K0_NONE).
- **Counterargument trace**: `CountFromClassStep` already returns a live, in-tx count in one metadata read (`:56-62`) and declares itself non-cacheable precisely because the value is cheap and live (`:74-81`). The cache adds scan + state-copy + delta replay to re-derive it.
- **Codebase evidence**: `CountFromClassStep.java:56-62,74-81`.
- **Survival test**: WEAK for bare/indexed COUNT(*); the broader D10 decision survives for COUNT over a scanning WHERE.

#### Challenge: D21 — "never op.type" contradicts the RECORD path it builds on
- **Chosen approach**: aggregate dispatch keys on membership, "never `op.type`."
- **Best rejected alternative**: the RECORD path's combined `op.type` + membership dispatch.
- **Counterargument trace**: the collapse keeps `type=CREATED` after CREATE+UPDATE (`FrontendTransactionImpl.java:654-661`); the RECORD `DeltaBuilder` switches on `op.type` and adds `cachedRids` membership to handle that case (`:145,:139,:150`). Membership alone loses the create-vs-update distinction the RECORD path needs `op.type` for.
- **Codebase evidence**: `DeltaBuilder.java:136-179`, `FrontendTransactionImpl.java:641-668`.
- **Survival test**: FRAGILE — the rule must be "membership *and* collapsed `op.type`," and `applyMutation` must carry the post-mutation value.

### Violation scenarios

#### Violation scenario: splicing prev mutates the shared cached plan
- **Invariant claim**: the aggregate-miss splice produces a private, mutable plan it may rewire freely.
- **Violation construction**: see A2 steps 1-5 — `createExecutionPlan(ctx, false)` returns the `YqlExecutionPlanCache` instance without copy (`SelectExecutionPlanner.java:236-238`); rewiring `AbstractExecutionStep.prev` (`:66`) mutates the shared plan; the next caller of the same SQL sees the spliced/corrupted chain.
- **Feasibility**: CONSTRUCTIBLE.

### Assumption tests

#### Assumption test: every aggregate query exposes an AggregateProjectionCalculationStep to splice
- **Claim**: the splice can always find an `AggregateProjectionCalculationStep` to rewire.
- **Stress scenario**: bare `SELECT COUNT(*) FROM C` and indexed `COUNT(*) WHERE k=?`.
- **Code evidence**: `SelectExecutionPlanner.java:259-262,470-475,488-509` (hardwired short-circuit to `CountFromClassStep`/`CountFromIndexStep` before any aggregation step), `CountFromClassStep.java:12-18,56-62`.
- **Verdict**: BREAKS for the dominant COUNT(*) shapes — no tap point, no per-RID material; always falls back.

#### Assumption test: recordPulledRow is the right cap point for aggregate material
- **Claim**: routing aggregate per-RID appends through `recordPulledRow` enforces `maxRecordsPerEntry` on aggregate material.
- **Stress scenario**: `SUM` over 50 000 rows with `maxRecordsPerEntry=10000`.
- **Code evidence**: `CachedEntry.java:268-287` (`recordPulledRow` appends to `results`, checks `results.size()`), `:155-159` (`results` is the single-scalar row list for the aggregate view).
- **Verdict**: BREAKS — would emit N rows from the aggregate view; cap must target `AggregateState` collections instead.

#### Assumption test: membership-only dispatch with a boolean matchAfter is sufficient for MIN/MAX/SUM
- **Claim**: `(was_contributing → now_contributing)` membership plus a boolean `matchAfter` covers all aggregate transitions.
- **Stress scenario**: a pass→pass UPDATE that changes the summed/extremum property without changing WHERE outcome.
- **Code evidence**: `DeltaBuilder.java:136-179`, `FrontendTransactionImpl.java:654-668`, `CachedEntry.java:159`.
- **Verdict**: FRAGILE — value-changing transitions need the post-mutation value re-extracted, and the create-vs-update distinction needs the collapsed `op.type`.
