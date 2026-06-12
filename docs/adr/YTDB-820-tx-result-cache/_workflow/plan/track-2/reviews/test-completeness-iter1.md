<!-- MANIFEST
dimension: test-completeness
iteration: 1
high_water_mark: 0
findings: 5
evidence_base: 7
cert_index: C1,C2,C3,C4,C5,C6,C7
flags: []
index:
  - id: TC1
    sev: should-fix
    anchor: "#tc1-empty-set-drain-untested-for-min-max-avg-count-count_distinct"
    loc: "AggregateState.java:777-787,903-919; AggregateStateTest.java"
    cert: C1
    basis: "branch-read + production-parity read"
  - id: TC2
    sev: should-fix
    anchor: "#tc2-null-property-value-paths-in-observe-and-applymutation-untested"
    loc: "AggregateState.java:562-568,602-610; AggregateStateTest.java"
    cert: C2
    basis: "branch-read"
  - id: TC3
    sev: should-fix
    anchor: "#tc3-min-max-non-number-comparable-and-cross-subtype-comparison-untested"
    loc: "AggregateState.java:810-838; AggregateStateTest.java"
    cert: C3
    basis: "branch-read + Step-1 episode BC3"
  - id: TC4
    sev: suggestion
    anchor: "#tc4-avg-negative-and-rounding-boundary-values-not-exercised"
    loc: "AggregateState.java:927-940; AggregateStateTest.java"
    cert: C4
    basis: "production-parity read"
  - id: TC5
    sev: suggestion
    anchor: "#tc5-min-distinct-count_distinct-equivalence-and-collapse-coverage-thinner-than-sum-max"
    loc: "AggregateCacheEquivalenceTest.java"
    cert: C5
    basis: "test-matrix audit"
-->

## Findings

### TC1 [should-fix] Empty-set drain untested for MIN/MAX/AVG/COUNT/COUNT_DISTINCT

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateStateTest.java`, `.../AggregateCacheEquivalenceTest.java`
- **Production code**: `AggregateState.java` — `recomputeExtremum` (777-787), `scalar()` (903-919), `computeAverage` (927-940); `SQLFunctionMin/Max/Average.getResult` return `null` over empty input.
- **Missing scenario**: A mutation that drains the *entire* contributing set to empty (the last contributor dropped via DELETE or WHERE-break), then the scalar is read. SUM-empty-after-seed is covered (`sumOverEmptySetIsZeroNotNull`, but that is the *seed* path, not the *drain* path). No test drains MIN/MAX to empty (exercising `recomputeExtremum` setting `currentScalar=null`), drains AVG to empty (`computeAverage(null, 0)` → null, and the would-be `total==0` integer-division-by-zero guard), or drains COUNT/COUNT_DISTINCT to zero through `applyMutation`.
- **Why it matters**: The "0 contributors remaining" state is a distinct boundary from "0 contributors seeded". `recomputeExtremum` over an empty `contributingValues` leaves `currentScalar` null — fresh MIN/MAX over an empty post-mutation set also returns null, so parity should hold, but the transition into that state (holder dropped → rescan finds nothing) is its own code path with no coverage. For AVG, the divide-by-zero is only avoided because `sumAccumulator` is null when `count==0`; if a future refactor ever folds an empty set to a non-null zero accumulator, `computeAverage` divides by `total==0` and throws `ArithmeticException` — there is no regression test pinning that the empty drain stays null-not-zero. `AggregateCacheEquivalenceTest` never empties a tappable shape end-to-end either (every `runWithTarget` scenario keeps ≥2 of 3 records).
- **Evidence**: Input-domain table row "contributing set size after replay = 0" — Currently Tested? NO for MIN/MAX/AVG/COUNT/COUNT_DISTINCT (only SUM seed-empty at `AggregateStateTest.java` `sumOverEmptySetIsZeroNotNull`).
- **Refutation considered**: C1 — confirmed the drain path is reachable (DELETE-all-matching in one tx then re-query is a normal user pattern) and that production returns null/0 over empty, so parity is the assertable contract. Not covered indirectly: the equivalence suite's mutation helpers all leave survivors.
- **Suggested test**:
  ```java
  @Test
  public void minDrainsToNullWhenLastContributorDropped() {
    db.begin();
    var only = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_MIN, FIELD, List.of(only));
    s.applyMutation((RecordAbstract) only, RecordOperation.DELETED, true);
    assertNull("MIN over an emptied set is null, matching SQLFunctionMin", scalarOf(s));
    db.rollback();
  }

  @Test
  public void avgDrainsToNullNotDivideByZero() {
    db.begin();
    var only = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_AVG, FIELD, List.of(only));
    s.applyMutation((RecordAbstract) only, RecordOperation.DELETED, true);
    assertNull("AVG over an emptied set is null, never a divide-by-zero", scalarOf(s));
    db.rollback();
  }
  // plus an AggregateCacheEquivalenceTest case that DELETEs every matching
  // record after populate (min/max/avg/sum/count) and asserts the cached
  // scalar equals the fresh empty-set result.
  ```

### TC2 [should-fix] Null-property-value paths in observe and applyMutation untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateStateTest.java`
- **Production code**: `AggregateState.observe` null-skip (562-568) and `applyMutation` null-new-value → `nowContributing=false` (602-610).
- **Missing scenario**: A contributing record whose *aggregate property* is null. Storage's SUM/AVG/MIN/MAX/DISTINCT skip null values entirely; `AggregateState` mirrors this with two branches — `observe` returns early on a null property, and `applyMutation` flips a WHERE-matching record to non-contributing when its new value is null. Neither branch has a unit test. Every test record sets a non-null `FIELD`/`price`.
- **Why it matters**: This is the difference between "record matches WHERE" and "record contributes to the aggregate". A value aggregate over a class where some matching rows have a null property is a routine production shape (`SUM(price) WHERE active=true` where `price` is sometimes unset). If the null-skip in `observe` regressed, the seeded scalar would mis-fold (NPE in `refoldSum`'s `(Number) v` cast, or a null in a distinct bucket). If the `applyMutation` null branch regressed, a T→T UPDATE that nulls the property would leave a stale contributor instead of dropping it — a silent wrong scalar. The `(Number) v` cast at `refoldSum` (762-763) would throw on a null that slipped through, so the skip is load-bearing.
- **Evidence**: Input-domain table rows "observed property value = null" and "post-mutation property value = null" — Currently Tested? NO.
- **Refutation considered**: C2 — confirmed both branches are reachable on a schema-less or nullable-property class (the equivalence suite even declares `price` as a nullable `INTEGER` property). Not covered by the F→F no-op test (`applyMutationFtoFIsNoOp` uses a non-matching value, not a null property on a matching record).
- **Suggested test**:
  ```java
  @Test
  public void sumSkipsNullPropertyOnObserve() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD,
        List.of(newRec(10), newRec(null), newRec(20)));
    assertEquals("a null-valued matching record does not contribute", 30, scalarOf(s));
    db.rollback();
  }

  @Test
  public void sumDropsContributorWhenUpdateNullsTheProperty() {
    var a = committedRec(10);
    var b = committedRec(20);
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(a, b));
    b.setProperty(FIELD, null);
    s.applyMutation((RecordAbstract) b, RecordOperation.UPDATED, true); // matches WHERE, value null
    assertEquals("a matching record whose value becomes null drops out", 10, scalarOf(s));
    db.rollback();
  }
  ```

### TC3 [should-fix] MIN/MAX non-Number Comparable and cross-subtype comparison untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateStateTest.java`
- **Production code**: `AggregateState.beatsExtremum` / `staysInExtremumDirection` (810-838) — the `castComparableNumber` path runs only when both operands are `Number`; otherwise it falls through to a raw `Comparable.compareTo`.
- **Missing scenario**: MIN/MAX over (a) cross-`Number`-subtype values that force the `castComparableNumber` branch (e.g. an `Integer` extremum challenged by a `Long`, or `Long` vs `Double`), and (b) non-Number `Comparable` values (String dates, `String` ids). Every MIN/MAX test uses homogeneous `Integer` values, so the `castComparableNumber` cast and the non-Number `compareTo` fallback are both unexercised. Step 1's own episode flags this as BC3 ("MIN/MAX over mixed non-Number Comparable values can throw ClassCastException ... untested").
- **Why it matters**: `beatsExtremum` is the comparison primitive behind every MIN/MAX transition. With homogeneous `Integer` input the `castComparableNumber` branch is dead in tests, so an off-by-one in the cast (wrong operand order, wrong sign for MIN vs MAX) would not surface. A `MAX(score)` where `score` is sometimes `Integer` and sometimes `Long` (the `increment`-promotion world these aggregates live in) is exactly the cross-subtype case D19 cares about; the cache must compare numerically (`Integer(5) < Long(10)`), and there is no test that would fail if it compared by `Number.equals`/hashCode or by boxed-reference order instead.
- **Evidence**: Input-domain table rows "MIN/MAX operand types = mixed Integer/Long/Double" and "= non-Number Comparable" — Currently Tested? NO (all MIN/MAX cases use `Integer`). Cross-subtype is tested for COUNT_DISTINCT (`countDistinctTreatsCrossSubtypeValuesAsDistinct`) but never for the MIN/MAX comparison direction.
- **Refutation considered**: C3 — confirmed `SQLFunctionMin/Max` run `castComparableNumber` for the numeric case and a raw `compareTo` otherwise, so the cache must reproduce both. Not covered by `minAndMaxObserveExtremum` (homogeneous Integer) or the equivalence suite (declared INTEGER property coerces everything to one subtype, so even end-to-end the cross-subtype path is unreachable through the equivalence harness).
- **Suggested test**:
  ```java
  @Test
  public void maxComparesAcrossNumberSubtypesNumerically() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_MAX, FIELD,
        List.of(newRec(5), newRec(10L), newRec(7.5d)));
    assertEquals("MAX compares Integer/Long/Double numerically, not by boxed type",
        10L, scalarOf(s));
    db.rollback();
  }

  @Test
  public void minOverStringComparableValues() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_MIN, FIELD,
        List.of(newRec("banana"), newRec("apple"), newRec("cherry")));
    assertEquals("apple", scalarOf(s)); // non-Number Comparable path
    db.rollback();
  }
  ```

### TC4 [suggestion] AVG negative and rounding-boundary values not exercised

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateStateTest.java`
- **Production code**: `AggregateState.computeAverage` (927-940) — integer truncation (toward zero for negatives) and BigDecimal `HALF_UP`.
- **Missing scenario**: AVG over negative integers (where Java integer division truncates toward zero, not floor — `-35/3 == -11`, not `-12`) and a BigDecimal AVG sitting exactly on the `HALF_UP` round-half boundary (e.g. sum `5` over `2` → `2.5` → `3` at scale 0). The existing `avgIntegerInputTruncates` (35/3) and `avgBigDecimalInputRoundsHalfUp` (35/3) use only positive, non-half-boundary inputs.
- **Why it matters**: Truncation direction and the exact half-up rounding rule are the two places AVG most easily diverges from a "plain sum/count" implementation. The current cases pass for both `floor` and `truncate-toward-zero` (both give 11 for 35/3) and for both `HALF_UP` and `HALF_EVEN` (35/3=11.67 rounds to 12 either way). A negative case and an exact-half case are the inputs that actually distinguish the implemented rule. Lower severity because `computeAverage` is copied verbatim from production, so cache-vs-fresh parity holds by construction; the value is guarding against a future independent edit of the cache copy drifting from storage.
- **Evidence**: Input-domain table rows "AVG sign = negative" and "BigDecimal AVG on exact .5 boundary" — Currently Tested? NO.
- **Refutation considered**: C4 — verified production `computeAverage` is reproduced character-for-character, so this is a guard-rail gap, not a current-behavior bug; hence suggestion, not should-fix.
- **Suggested test**:
  ```java
  @Test
  public void avgNegativeIntegerTruncatesTowardZero() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_AVG, FIELD,
        List.of(newRec(-10), newRec(-20), newRec(-5))); // -35 / 3
    assertEquals("integer AVG truncates toward zero, not floor", -11, scalarOf(s));
    db.rollback();
  }
  ```

### TC5 [suggestion] MIN / DISTINCT / COUNT_DISTINCT equivalence and collapse coverage thinner than SUM/MAX

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateCacheEquivalenceTest.java`
- **Production code**: per-kind `applyMutation` paths in `AggregateState.java`.
- **Missing scenario**: The end-to-end equivalence matrix is uneven across kinds. SUM gets CREATE + value-UPDATE + WHERE-break; MAX gets DELETE + value-UPDATE-below-extremum; AVG gets CREATE + value-UPDATE; but **MIN gets only DELETE** (no CREATE-below-extremum, no value-UPDATE, no WHERE-break), **COUNT gets no value-UPDATE** (COUNT ignores value, so this is acceptable), and the **D21 collapse case is exercised only for MAX** end-to-end — SUM's collapse (re-fold-not-double-add) is unit-tested in `AggregateStateTest` but never driven through the live splice path. The MIN F→T-beats-extremum transition (`minApplyMutationNewValueBeatsExtremum`) is unit-tested but never end-to-end.
- **Why it matters**: The unit tests cover each `applyMutation` branch in isolation, but the end-to-end suite is what proves the *splice + eager-drive + buildForAggregate + view* pipeline wires the right metadata (alias, propertyName) and reconciles correctly for each kind. A kind-specific wiring bug (e.g. MIN reading the wrong property, or the collapse membership-dispatch only working when the live tx produces the op type SUM happens to hit) would slip through because MIN/SUM-collapse have no live coverage. Lower severity because the unit layer is thorough and the pipeline is kind-agnostic by construction, so the marginal bug surface is small.
- **Evidence**: Test-matrix audit — per-kind × mutation-pattern grid is fully populated only for SUM and MAX; MIN end-to-end = {DELETE}, SUM-collapse end-to-end = {none}.
- **Refutation considered**: C5 — the pipeline (splice/drive/build/view) is shape-keyed but not value-kind-keyed, so most kind-specific risk is in `AggregateState` which the unit suite covers well; this is defense-in-depth, hence suggestion.
- **Suggested test**: add a `minEquivalence_createBelowExtremumAfterPopulate` and a `sumEquivalence_collapseCreateThenValueChange` mirroring the existing MAX collapse case, so each value kind has at least one live splice-path equivalence assertion per non-trivial transition.

## Evidence base

#### C1 — empty-set drain boundary
CONFIRMED-as-gap. `AggregateState.recomputeExtremum` (777-787) sets `currentScalar=null` when `contributingValues` is empty; `scalar()` (903-919) returns `currentScalar` for MIN/MAX (null), `computeAverage(null,0)` for AVG (null via the final `return null`), `(long) size` for COUNT/COUNT_DISTINCT (0). `SQLFunctionAverage.getResult` (verified at `SQLFunctionAverage.java:91-115`) returns `computeAverage(null, 0)` → null over empty; MIN/MAX (`SQLFunctionMin.java:43-98`) return null over empty. So fresh-execution parity over an emptied set is null/0, which is assertable. The drain *transition* (last contributor removed by replay) is exercised by no test; `sumOverEmptySetIsZeroNotNull` covers only the never-seeded path. Reachable: DELETE-all-matching then re-query in one tx.

#### C2 — null property value
CONFIRMED-as-gap. `observe` (562-568) early-returns on a null property; `applyMutation` (602-610) flips `nowContributing=false` when `readValue` returns null. `refoldSum` casts `(Number) v` (762-763) so a null that bypassed the skip would NPE/CCE. No test sets a null aggregate property. The equivalence-suite `price` is a nullable `INTEGER` property, so the path is reachable through the live API.

#### C3 — MIN/MAX comparison subtypes
CONFIRMED-as-gap. `beatsExtremum`/`staysInExtremumDirection` (810-838) run `PropertyTypeInternal.castComparableNumber` only when both operands are `Number`, else raw `compareTo`. `SQLFunctionMin.execute` (verified `SQLFunctionMin.java:64-72`) runs the same `castComparableNumber` for the numeric case. All MIN/MAX tests use homogeneous `Integer`, so neither the cast branch nor the non-Number fallback executes. Step 1 episode independently recorded this as deferred finding BC3.

#### C4 — AVG sign/rounding boundary
CONFIRMED-as-low-value. `AggregateState.computeAverage` (927-940) is a verbatim copy of `SQLFunctionAverage.computeAverage` (`SQLFunctionAverage.java:101-115`), so cache-vs-fresh parity holds by construction today. The gap is a guard against future drift of the copy, and the existing positive/non-half inputs do not distinguish truncate-toward-zero from floor, nor HALF_UP from HALF_EVEN. Suggestion severity.

#### C5 — per-kind matrix evenness
CONFIRMED-as-low-value. Audited `AggregateCacheEquivalenceTest`: SUM = {create, valueUpdate, whereBreak}; MAX = {delete, valueUpdateBelowExtremum}; AVG = {create, valueUpdate}; MIN = {delete}; COUNT = {create, delete, whereBreak}; collapse end-to-end = {MAX only}. The populate/splice/build/view pipeline is shape-keyed (`isAggregateShape`) not value-kind-keyed, so kind-specific live risk is confined to `AggregateState`, which `AggregateStateTest` covers per-branch. Defense-in-depth, suggestion severity.

#### C6 — tooling basis
mcp-steroid reachable; open project `design.md` rooted at the working tree (`steroid_list_projects`). Production-code reads (`SQLFunctionAverage`, `SQLFunctionMin`, `PropertyTypeInternal.increment` signature) done by direct Read against the working tree, which matches the open project root. No find-usages/override claims underpin any finding, so the grep-vs-PSI reference-accuracy caveat does not bind here — every finding rests on branch reads of code present in the diff plus the three named production methods.

#### C7 — diff completeness
Full cumulative track diff (~3681 lines) paged in four reads (offsets 1/700/1400/2100/2800), covering all five test files end-to-end plus the production context (`AggregateState`, `AggregateCacheTapStep`, `DeltaBuilder.buildForAggregate`, `ShapeClassifier`, `DatabaseSessionEmbedded`, `CachedResultSetView`, `QueryCacheMetrics`, `QueryResultCache`, `CachedEntry`). No finding is formed from a truncated read.
