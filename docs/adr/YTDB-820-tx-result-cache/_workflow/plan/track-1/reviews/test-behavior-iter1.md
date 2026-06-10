<!--MANIFEST
dimension: test-behavior
step: "track-1 (full track, all 7 steps)"
commit_range: f1cf786fbdc40e99c0a2c4b3f0ad2ab736d56eb7..HEAD
iteration: 1
high_water_mark: 0
findings_total: 5
blocker: 0
should_fix: 2
suggestion: 3
evidence_base: "#### C1..C5 in ## Evidence base"
cert_index: C1..C5
flags: []
index:
  - id: TB1
    sev: should-fix
    anchor: "#tb1-i7-test-does-not-exercise-view-snapshot-isolation-its-claimed-invariant"
    loc: "TxResultCacheInvariantsTest.java i7_viewBeforeMutationIsIsolatedFreshQueryAfterSeesIt; CachedResultSetView.java:1214-1240 (hasNext/next lookahead)"
    cert: C1
    basis: "Source trace: the test fully drains+closes the first view BEFORE the mutation, so no open view spans the mutation; I7 (an in-flight view does not observe a later mutation) is never reached. What is asserted (post-mutation fresh query sees delta) is I10, already covered."
  - id: TB2
    sev: should-fix
    anchor: "#tb2-i8-stability-test-is-near-tautological-no-schema-change-is-attempted"
    loc: "TxResultCacheInvariantsTest.java i8_effectiveFromClassesStableForEntryLifetime; CachedEntry.java:917-927 (computeEffectiveFromClasses)"
    cert: C2
    basis: "Source read: computeEffectiveFromClasses is a pure static function of its SchemaClass arg; the test calls it twice with no intervening schema mutation and asserts the two results equal. A consistently-wrong closure passes; the I8 'stable across the tx' claim is not falsifiable as written."
  - id: TB3
    sev: suggestion
    anchor: "#tb3-wiring-mutation-tests-assert-only-cardinality-not-row-identity-or-order"
    loc: "TxResultCacheWiringTest.java flagOn_mutationBetweenQueriesReflectedInSecondView; TxResultCacheInvariantsTest.java i9_liveViewNotTruncatedUnderLruPressure"
    cert: C3
    basis: "Source read: these assert count()/size() == N only. A delta that injected a wrong row, mis-ordered, or stale-valued at the same cardinality would pass. Mitigated (not eliminated) by the value-and-order equivalence matrix; flagged so the wiring tests are not read as content proofs."
  - id: TB4
    sev: suggestion
    anchor: "#tb4-lookup-level-inflightlookup-guard-has-no-test"
    loc: "QueryResultCache.java:2133-2139 (inFlightLookup); TxResultCacheWiringTest.java flagOn_reentrantQueryBypassesCache"
    cert: C4
    basis: "Source trace: the only re-entrancy test exercises the tx-level cacheCodeDepth guard via enterCacheCode(); the lookup-level inFlightLookup null-return branch is never driven. Step 3 episode acknowledges it 'cannot be unit-tested in isolation' but no integration test reaches it either."
  - id: TB5
    sev: suggestion
    anchor: "#tb5-i1-exception-test-throws-from-the-consumer-not-from-inside-the-view-pull"
    loc: "TxResultCacheInvariantsTest.java i1_iterateExceptionDoesNotClearSynchronouslyButTxEndDoes; CachedResultSetView.java:1345-1371 (pullOneFromStream)"
    cert: C5
    basis: "Source read: the test throws its own IllegalStateException between rs.next() calls; the production exception path (a throw originating inside view.next()/pullOneFromStream, the Step-5 BC3 pin-held/stream-open path) is not exercised. The eventual-vs-synchronous distinction it draws is valid for the consumer-abort case."
-->

# Test Behavior Review — Track 1 (full track, all 7 steps)

## Findings

### TB1 [should-fix] I7 test does not exercise view snapshot-isolation, its claimed invariant

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheInvariantsTest.java`, method `i7_viewBeforeMutationIsIsolatedFreshQueryAfterSeesIt`

**Issue**: The method name, the section header (`I7 — a view started before a mutation does not observe it`), and the Javadoc ("this test pins the snapshot isolation of the earlier view") all claim to verify I7: a `ResultSet` view materialised before an in-tx mutation must not start observing that mutation. The test body does not do this. It calls `drainRows(session.query(sql))`, which fully iterates **and closes** the first view (`drainRows` uses `try (rs)`), and only then creates the new entity. By the time the mutation happens there is no open view to be isolated. The assertions that follow (`second.size() == 3`, `assertNotEquals(first, second)`) verify that a *fresh* post-mutation `query()` observes the in-tx CREATE through the delta merge — which is the I10 delta-merge property, already covered by `flagOn_mutationBetweenQueriesReflectedInSecondView` and the whole equivalence matrix.

**Evidence**: BEHAVIOR TRACE — the consumer calls `query(sql)` → `serveThroughCache` populates an entry and returns a `CachedResultSetView`; `drainRows` iterates it to exhaustion (which calls `releasePin()` and flips the entry exhausted, `CachedResultSetView.java:1222-1228`) and then `close()`s it inside the try-with-resources. The mutation (`session.newEntity`) executes after the view is already closed and exhausted. FALSIFIABILITY CHECK — mutate the production view so that an *open* mid-iteration view re-reads `entry.results` / re-applies a freshly-rebuilt delta on each `next()` (i.e. break snapshot isolation). This test would still pass, because its first view is never held open across the mutation — the `first` list was captured before any mutation existed. The test therefore gives false confidence that I7 is covered.

**Missing behavior**: Hold a view open across the mutation and assert it still emits exactly its pre-mutation rows. Concretely: open view A, pull part of it, mutate, finish draining A, assert A's full output equals the pre-mutation result (cardinality + ordered values), and separately assert a fresh view B opened after the mutation sees the delta. The second half is what the current test does; the first half is the missing I7 proof.

**Suggested fix**:
```java
@Test
public void i7_openViewDoesNotObserveLaterMutation() {
  GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
  seed(2); // values 0, 1
  session.begin();
  var sql = "SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC";

  // Open view A and pull ONE row, leaving it mid-iteration (snapshot taken at construction).
  var a = session.query(sql);
  assertTrue(a.hasNext());
  var firstRowOfA = a.next().<Object>getProperty(FIELD);

  // Mutate inside the same tx, while A is still open.
  var added = session.newEntity(CLASS_NAME);
  added.setProperty(FIELD, 99);

  // A must finish with exactly its pre-mutation remainder — it must NOT pick up value 99.
  var restOfA = new ArrayList<>();
  while (a.hasNext()) {
    restOfA.add(a.next().<Object>getProperty(FIELD));
  }
  a.close();
  assertEquals("an open view must not observe a mutation made after it was built",
      List.of(0, 1), prepend(firstRowOfA, restOfA)); // helper concatenates

  // A fresh view opened after the mutation DOES see it (delta merge).
  var b = drainRows(session.query(sql));
  assertEquals(List.of(0, 1, 99), b);
  session.rollback();
}
```
(If the live view in fact does materialise lazily and would observe a later mutation through `entry.results`, this is then a production-behavior question for the bugs reviewer; either way the test as written does not pin the invariant it names.)

### TB2 [should-fix] I8 stability test is near-tautological; no schema change is attempted

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheInvariantsTest.java`, method `i8_effectiveFromClassesStableForEntryLifetime`

**Issue**: The test asserts `computeEffectiveFromClasses(cls)` called twice returns equal sets, claiming this proves "the entry's `effectiveFromClasses` closure is stable for its lifetime" (I8: schema immutable per transaction). `computeEffectiveFromClasses` is a pure static function of its `SchemaClass` argument (it reads `fromClass.getName()` and `getAllSubclasses()`, `CachedEntry.java:917-927`). Calling a pure function twice on the same input with nothing changing between the calls is guaranteed to return equal results — the assertion is satisfied by referential transparency alone, not by any schema-stability property. The test would pass even if the closure computation were wrong (e.g. omitted subclasses), as long as it were wrong *consistently*.

**Evidence**: ASSERTION PRECISION CHECK — `assertEquals(first, second)` where `first` and `second` are two consecutive pure-function evaluations. PRODUCTION VALUE: deterministic per input. PRECISION: WEAK — passes for any consistent implementation, correct or not, because the I8 hazard (the closure drifting *after a schema change* mid-tx) is never created: no class or subclass is added/removed between the two calls. The only load-bearing assertion in the method is `first.contains(CLASS_NAME)`, which checks the name form, not stability.

**Missing behavior**: To exercise I8 meaningfully, either (a) attempt a schema mutation mid-transaction and assert it is rejected/unreachable (proving schema really is frozen, which is what makes the closure safe to compute once), or (b) drop the misleading double-call and reframe the test as what it actually verifies: the closure contains the declared class and any subclasses under the name form the delta filter probes with (`Entity.getSchemaClassName()`). Note that `DeltaBuilderTest.inClassMutationProducesDeltaWhenEntryBuiltThroughProductionClosure` already pins the name-form equivalence end-to-end, so (b) would make this test redundant — (a) is the higher-value choice if I8 enforcement is to be tested at all.

**Suggested fix**:
```java
@Test
public void i8_subclassMutationsAreCaughtByTheClosureClassFilter() {
  // Build a subclass so the closure is non-trivial, then prove a mutation on the
  // subclass enters the delta of a query over the parent — the property that makes
  // the once-computed closure correct, not just self-consistent.
  session.begin();
  var sub = session.createClass(CLASS_NAME + "Sub", CLASS_NAME);
  var closure = CachedEntry.computeEffectiveFromClasses(session.getClass(CLASS_NAME));
  assertTrue("closure must include the declared class", closure.contains(CLASS_NAME));
  assertTrue("closure must include the subclass", closure.contains(CLASS_NAME + "Sub"));
  session.rollback();
}
// And, if I8 enforcement is in scope, a separate test that a CREATE CLASS / ALTER
// mid-tx throws (or trips the schema-DDL assert canary), proving the schema cannot
// shift under a live entry.
```

### TB3 [suggestion] Wiring mutation tests assert only cardinality, not row identity or order

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheWiringTest.java`, method `flagOn_mutationBetweenQueriesReflectedInSecondView`; `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheInvariantsTest.java`, method `i9_liveViewNotTruncatedUnderLruPressure`

**Issue**: `flagOn_mutationBetweenQueriesReflectedInSecondView` asserts `after == 3` (a row count) after a CREATE between two queries; `i9_liveViewNotTruncatedUnderLruPressure` asserts `remaining.size() == 2`. Both verify cardinality only. A delta that injected the *wrong* row, at the *wrong* sort position, or with a *stale* value — but at the right cardinality — would pass. The I10 floor is cardinality **and order and content**.

**Evidence**: FALSIFIABILITY CHECK — for `flagOn_mutationBetweenQueriesReflectedInSecondView`, mutate the production delta to inject a duplicate of an existing cached row instead of the new entity (cardinality still 3). The `countQuery(...) == 3` assertion still passes. The bug is caught only by `TxResultCacheInvariantsTest.recordEquivalence_createAfterPopulate`, which compares ordered FIELD values against a fresh execution. So the protection exists, but it lives in a different test; these two tests on their own are size-only and should not be read as content proofs.

**Missing behavior**: Assert the ordered value list, not just the count, in the wiring test (it already seeds deterministic values). For `i9`, assert the held view's full ordered output equals the pre-pressure result, which is the actual I9 guarantee (no rows *lost or altered* under LRU pressure), not merely "two rows remained".

**Suggested fix**:
```java
// flagOn_mutationBetweenQueriesReflectedInSecondView
var after = drainRows(session.query(sql)); // drainRows returns ordered FIELD values
assertEquals("second view reflects the in-tx CREATE in sorted position",
    List.of(0, 1, 99),
    after); // requires ORDER BY n in the SQL and a known FIELD=99 for the added row

// i9_liveViewNotTruncatedUnderLruPressure
var remaining = drainRows(held);
assertEquals("a pinned live view keeps its full ordered result under LRU pressure",
    List.of(/* the two remaining values in order */), remaining);
```

### TB4 [suggestion] Lookup-level `inFlightLookup` guard has no test

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/QueryResultCache.java` (lines 2133-2139, the `inFlightLookup` early return); `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheWiringTest.java`, method `flagOn_reentrantQueryBypassesCache`

**Issue**: The cache carries **two** re-entrancy guards per the CR1 resolution: the tx-level `cacheCodeDepth` (on `FrontendTransactionImpl`) and the lookup-level `inFlightLookup` boolean (on `QueryResultCache`). The only re-entrancy test, `flagOn_reentrantQueryBypassesCache`, manually calls `tx().enterCacheCode()` and proves the **tx-level** guard bypasses. No test drives the `inFlightLookup == true → return null` branch of `lookup()`. Step 3's episode states it "cannot be unit-tested in isolation (nothing inside `lookup` re-enters the cache)", but no integration test reaches it either, so this branch is entirely uncovered.

**Evidence**: BEHAVIOR COVERAGE — `QueryResultCache.lookup` first branch (`if (inFlightLookup) return null;`) has no exercising test. FALSIFIABILITY CHECK — delete that branch (or invert the flag) and the whole suite still passes, because the field is only ever observed false at the entry to `lookup` in every test. The guard is dead-tested.

**Missing behavior**: A unit test that re-enters `lookup` while a `lookup` is in flight. Because nothing inside `lookup` itself re-enters, the realistic exercise is a small harness: a `CachedEntry` whose stream's `next()` (or a stubbed component the lookup path touches) issues a nested `cache.lookup(...)` and asserts the nested call returns `null` without recursing. If that cannot be staged, document explicitly in the test class that `inFlightLookup` is belt-and-suspenders behind `cacheCodeDepth` and is not independently reachable, rather than leaving the gap implicit.

**Suggested fix**:
```java
@Test
public void nestedLookupDuringLookupReturnsNullWithoutRecursing() {
  var cache = new QueryResultCache(new QueryCacheMetrics());
  var k = key("select from OUser where name = 'a'");
  // An entry whose access path re-enters lookup() (staged via a probe the lookup touches),
  // proving the inFlightLookup guard short-circuits the nested call to null.
  // ... stage re-entry, then:
  // assertNull(nestedResultObservedByTheProbe);
}
```

### TB5 [suggestion] I1 exception test throws from the consumer, not from inside the view pull

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheInvariantsTest.java`, method `i1_iterateExceptionDoesNotClearSynchronouslyButTxEndDoes`

**Issue**: The test simulates "a consumer that throws partway through iterating the view" by calling `rs.next()` once and then `throw new IllegalStateException(...)` in the consumer's own `try` block. `assertThrows` catches that test-thrown exception. This validly proves the consumer-abort case: a throw *outside* the view does not synchronously clear the cache, and the tx-end path does. But it does not exercise the harder path the Step-5 review flagged (BC3): an exception originating *inside* `CachedResultSetView.next()` / `pullOneFromStream()` — e.g. `stream.next()` throwing — which bypasses the exhausted/close/release-pin tail and leaves the pin held and the stream open until tx-end `clear()`.

**Evidence**: BEHAVIOR TRACE — the test's exception is constructed and thrown by the lambda body after `rs.next()` returns normally; the view's pull machinery never sees a failure. FALSIFIABILITY CHECK — break the in-pull failure handling (e.g. make `pullOneFromStream` leak the pin on a thrown `stream.next()`); this test still passes because no production code path throws during its single `rs.next()`. The eventual-vs-synchronous-clear distinction the test draws is real and correctly asserted (`cache.size() == 1` post-exception, `== 0` post-rollback), so the test is not wrong — it is narrower than its section header ("NOT clear-on-iterate-exception") implies.

**Missing behavior**: A test where the view's underlying stream throws mid-pull, asserting (a) the exception propagates, (b) the cache is not synchronously cleared, and (c) the tx-end path still clears it and closes the stream exactly once (the bounded-leak behavior the design accepts). This pins that an in-view failure does not leave the cache in an inconsistent or unclosed state.

**Suggested fix**:
```java
@Test
public void i1_streamThrowMidPullDoesNotClearSynchronouslyAndTxEndStillCleansUp() {
  // Build (or inject) an entry whose stream.next() throws on the second pull, drive a view
  // until the throw propagates, assert cache.size()==1 immediately after, then rollback and
  // assert cache.size()==0 and the underlying stream was closed exactly once.
}
```
(Whether the in-pull pin-leak itself should be hardened with a try/finally is a production-behavior call for the bugs reviewer — Step 5 BC3 carried it as a suggestion; this finding is only that no test covers the in-view-throw path.)

## Evidence base

#### C1 — TB1: I7 test closes its only view before the mutation

CONFIRMED-as-issue. The `i7` method drains the first view via `drainRows` (which closes it in a try-with-resources) before `session.newEntity`, so no open view spans the mutation; the assertions verify a fresh post-mutation query (I10 delta-merge), not view snapshot isolation (I7). Refutation that another test covers the real I7 contract was checked against every method in the suite via grep of `newEntity`/`addRecordOperation`/`setProperty` relative to held-open `query()` handles: the only view held open across a side effect is in `i9`, and that side effect is a *second query under LRU pressure*, not a mutation — and `i9` asserts size only. No test holds a view open across a mutation. Confirmed.

#### C2 — TB2: computeEffectiveFromClasses is pure; the test mutates nothing between calls

CONFIRMED-as-issue. `CachedEntry.computeEffectiveFromClasses(SchemaClass)` (lines 917-927) reads only `getName()` + `getAllSubclasses()` and returns `Set.copyOf(...)`; it has no transaction or mutable state. The `i8` test invokes it twice on the same `cls` with no schema operation in between, so equality is guaranteed by purity. The I8 "stable for the entry's lifetime under schema immutability" property requires a schema-change attempt to be falsifiable, which the test does not make. Confirmed.

#### C3 — TB3: cardinality-only assertions on the wiring mutation paths

CONFIRMED-as-issue (suggestion). `flagOn_mutationBetweenQueriesReflectedInSecondView` asserts `after == 3` via `countQuery` (`rs.stream().count()`); `i9` asserts `remaining.size() == 2`. Neither checks identity, order, or value. The value-and-order safety net exists in `TxResultCacheInvariantsTest` (`drainRows` + `assertEquals(fresh, cached)` on ordered FIELD lists across CREATED/UPDATED/DELETED × pre/post-populate), which is genuinely precise — so the track as a whole does verify I10 content+order. The finding is scoped to the two size-only tests not being mistaken for content proofs and to cheaply upgrading them. Confirmed as a precision suggestion, not a coverage hole.

#### C4 — TB4: inFlightLookup branch is unreached by any test

CONFIRMED-as-issue (suggestion). `QueryResultCache.lookup` opens with `if (inFlightLookup) return null;` (lines 2137-2139). Searched the test suite: the only re-entrancy exercise is `flagOn_reentrantQueryBypassesCache`, which drives `tx().enterCacheCode()` (the tx-level `cacheCodeDepth` guard) and asserts the gate in `serveThroughCache` bypasses before `lookup` is even called — so `inFlightLookup` is observed `false` on every real entry to `lookup`. The branch is dead-tested. Step 3 episode concedes it is not unit-testable in isolation; no integration test substitutes for it. Confirmed.

#### C5 — TB5: the I1 exception test throws from the consumer body, not the view

CONFIRMED-as-issue (suggestion). The `assertThrows(IllegalStateException.class, ...)` wraps a lambda that calls `rs.next()` once (normal return) then `throw new IllegalStateException("consumer aborts mid-iterate")`. The thrown object is the test's own, not a production exception from `CachedResultSetView.next()`/`pullOneFromStream()`. The eventual-clear assertions (`size()==1` then `size()==0` after rollback) are correct and falsifiable for the consumer-abort case. The narrower in-view-throw path (Step 5 BC3: pin held, stream open until tx-end) is untested. Confirmed as a coverage suggestion; the test it complements is itself sound.
