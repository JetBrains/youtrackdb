<!-- MANIFEST
dimension: test-behavior
prefix: TB
findings: 1
high_water_mark: 1
evidence_base: true
cert_index: true
index:
  - id: TB1
    sev: suggestion
    anchor: "#tb1-no-leak-floor-test-asserts-only-non-null-not-the-scalar"
    loc: "AggregateCacheEquivalenceTest.java:499 allKindsRunWithoutExceptionLeak"
    cert: C1
    basis: "FALSIFIABILITY CHECK — a wrong-but-non-null scalar passes; mitigated by per-kind value-pinned siblings"
flags: []
-->

# Track 2 test-behavior review (iter 1)

## Findings

### TB1 [suggestion] No-leak floor test asserts only non-null, not the scalar

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateCacheEquivalenceTest.java`, method `allKindsRunWithoutExceptionLeak` (line 499)

**Issue**: The only per-kind assertion in this case is `assertTrue("...returns a non-null scalar...", s != null)`. This is an existence-only assertion: a regression that made the SUM fold, the AVG finalisation, or a MIN/MAX recompute return a wrong-but-non-null scalar would not fail this test.

**Evidence (FALSIFIABILITY CHECK)**: If `AggregateState.refoldSum` were mutated to double-add a contributor (return `60` instead of `30` for `sum(price)` over `{10,20}`), `allKindsRunWithoutExceptionLeak` would still pass — `60 != null`. The mutation is caught only by the sibling cases (`aggregateHitOnPureReadRepeat` pins `30`; `sumEquivalence_valueUpdateAfterPopulate` pins fresh-vs-cached over distinct values), not by this case.

**Why this is a suggestion, not a defect**: the case's stated scope (Javadoc lines 492-497) is the no-exception-leak floor — every aggregate kind also has a dedicated value-pinning equivalence test in the same class, so the shallow assertion is a deliberate narrowing, not a coverage gap. The behavior it claims to verify (no exception leaks across all five kinds in one tx) is genuinely verified: a thrown exception fails the test.

**Suggested fix** (optional — folds a free value check onto the existing loop):
```java
for (var agg : new String[] {"count(*)", "sum(" + VALUE + ")", "avg(" + VALUE + ")",
    "min(" + VALUE + ")", "max(" + VALUE + ")"}) {
  var cached = scalar(session.query(aggSql(agg)));
  GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
  var fresh = scalar(session.query(aggSql(agg)));
  GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
  assertEquals("every kind's cached scalar matches fresh, no leak: " + agg, fresh, cached);
}
```

## Evidence base

#### C1 — TB1 confirmed-as-issue (suggestion)

`allKindsRunWithoutExceptionLeak` asserts `s != null` only; a wrong-but-non-null scalar survives the mutation. Confirmed by FALSIFIABILITY CHECK against a hypothetical double-add in `refoldSum`. Held at suggestion because every kind is value-pinned in a dedicated sibling case and the Javadoc scopes this case to the no-leak floor.

---

The following candidate concerns were examined and **refuted** — recorded here so a re-review does not re-raise them.

**REFUTED — create-after-populate cases are vacuous (the F→T add path is never exercised).** Hypothesis: `createMatching` (line 160) creates a record via `session.newEntity` but never calls `addRecordOperation` or `save()` (unlike `breakWhere` / `changeValue` / `deleteRecord`, which stage the op explicitly), so the new contributor would be invisible to both the delta build (`DeltaBuilder.buildForAggregate` iterates `tx.getRecordOperationsInternal()`, line 243) and the fresh scan — making `assertEquivalent` pass with both sides returning the stale seed count and never testing the F→T transition. **Refutation**: ran an instrumented probe of the exact `count(*) FROM AggRec WHERE active = true` create-after-populate scenario. Result: `first=3 second(cached)=4`. The unsaved `newEntity` IS picked up by the populating scan and the cached second query's replay correctly reflects the new contributor, so the F→T add delta path is genuinely exercised. The `addRecordOperation` calls live inside the `save`/`newInstance` registration path (`DatabaseSessionEmbedded` lines 1481/1501) reached by the query/commit machinery. Concern dropped.

**REFUTED — differential-only equivalence cases give false confidence.** Hypothesis: cases using `assertEquivalent` / `runWithTarget` (SUM/AVG value-update, MIN/MAX delete, where-break) compare only fresh-vs-cached with no absolute-value anchor, so a bug shared by both paths passes. **Refutation**: the flag-off run is a genuine uncached `query()` (`runScenario` line 128 forces `QUERY_TX_RESULT_CACHE_ENABLED=false` before the second query) — the source of truth is real execution, not a literal the test author could mis-transcribe. A cached-replay bug that diverges from uncached execution is caught. The only residual blind spot (a bug in the seed setup shared by both halves) is closed by the seed values being distinct enough that a fold error changes the number: `{10,20,30}` with the 30-holder updated to 999 gives fresh `1029` vs a double-add `1059`. Differential testing against an independent oracle is the correct design here, and the Javadoc (lines 28-29) justifies it explicitly. Concern dropped.

**REFUTED — metrics-bridge routing test is vacuous.** Hypothesis: `QueryCacheMetricsTest.eachIncrementRecordsOnlyItsOwnGlobalRate` injects a `RecordingRate` that overrides only `record(long value)`, while production `QueryCacheMetrics.incrementHits` calls the no-arg `hitRate.record()`; if `record()` did not delegate, `recordCount` would stay 0 and the routing asserts would be checking 0-against-0 — vacuously green. **Refutation**: `TimeRate.record()` is a default method delegating to `record(1)` (`TimeRate.java:16-17`), which dispatches to the overridden `record(long)`. The case asserts `1L` for the incremented sink (line 115) — that assertion fails if delegation breaks — and `0L` for the other four, so each increment's routing is genuinely falsifiable. Concern dropped.

**REFUTED — AggregateStateTest scan-order tests only pin the value (a type-regression to unordered map slips through on Double inputs).** Hypothesis: the IEEE-754 non-associativity tests could pass on type alone. **Refutation**: `sumMixedIntOverflowFoldsInObserveOrderMatchingStorageScanOrder` (line 382) asserts BOTH `expected` value AND `expected.getClass()` against the scalar (lines 395-397), and `sumDoubleFoldsInObserveOrderMatchingStorageScanOrder` (line 325) constructs inputs (`1e16` + 64 ones) where a permuted fold rounds to a measurably different double — so a revert of `contributingValues` from `LinkedHashMap` to `HashMap` fails the value assertion, not merely the type. These are among the strongest tests in the track. Concern dropped.

## Coverage

Examined all five changed test files against their production counterparts:

- `AggregateStateTest.java` (34 cases) vs `AggregateState.java` — per-kind observe/applyMutation, SUM/AVG storage-parity fold (value + type pinned against `PropertyTypeInternal.increment`), AVG integer-truncation and BigDecimal HALF_UP, MIN/MAX O(n) recompute on both extremum-leaving transitions, COUNT_DISTINCT bucket lifecycle, the D21 collapse case (CREATED-typed op on an existing contributor dispatched by membership), `copy` deep-isolation, contributor-cap one-shot overflow. Strong, precise, falsifiable.
- `AggregateCacheEquivalenceTest.java` (19 cases) vs `DatabaseSessionEmbedded` splice + `DeltaBuilder.buildForAggregate` + `CachedResultSetView.forAggregate` — per-kind I4 differential against uncached execution, D21 collapse, hardwired/indexed COUNT(*) fallback with `spliceFailures` assertion, `count(*)+1` and `count(distinct)` K0_NONE routing, contributor-cap overflow with `overflows`/`size()` assertions, hit-on-repeat with `hits`/`misses` assertions. One suggestion (TB1).
- `ShapeClassifierTest.java` vs `ShapeClassifier` — both Track 2 tightenings (arithmetic→K0_NONE for COUNT and SUM, bare COUNT(*)→K0_NONE), `aggregateMetadata` per-shape facts including null for K0_NONE shapes. Precise enum and field assertions.
- `FunctionDeterminismEnumerationTest.java` (3 cases) vs `NonDeterministicQueryDetector` denylist — four-factory enumeration completeness, factory-count pin, bidirectional denylist-drift guard. Genuinely build-gating.
- `QueryCacheMetricsTest.java` (5 cases) vs `QueryCacheMetrics` — per-counter independence, accumulation, and the global-rate bridge routing (one-per-increment, own-sink-only). Falsifiable via the injected recording sinks.
