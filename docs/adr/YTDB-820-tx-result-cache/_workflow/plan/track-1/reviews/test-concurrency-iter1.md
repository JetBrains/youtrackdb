<!--MANIFEST
reviewer: test-concurrency
dimension: test-concurrency
target: track-1 cumulative diff (f1cf786..HEAD)
verdict: findings
finding_count: 3
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: TX1
    sev: should-fix
    anchor: "#tx1-cross-thread-clear-while-view-pinned-is-untested"
    loc: "QueryResultCache.java:235; FrontendTransactionImpl.java:1014; TxResultCacheInvariantsTest.java:470,448"
    cert: C1
    basis: "CONTRACT + TEST TRACE: I6 asserts clear() safe under cross-thread invocation; only same-thread clear is exercised"
  - id: TX2
    sev: should-fix
    anchor: "#tx2-lookup-level-inflightlookup-guard-branch-is-never-executed-by-any-test"
    loc: "QueryResultCache.java:127; TxResultCacheWiringTest.java:181; TransactionMutationVersionTest.java:105"
    cert: C2
    basis: "TEST TRACE: re-entrancy tests trip cacheCodeDepth first; the inFlightLookup==true branch is dead in tests"
  - id: TX3
    sev: suggestion
    anchor: "#tx3-assertonowningthread-violation-is-never-exercised-on-a-cache-path"
    loc: "FrontendTransactionImpl.java:158,1377,1387; TxResultCacheInvariantsTest.java:591"
    cert: C3
    basis: "TEST TRACE: I2 asserted via depth-balance only; no off-thread call proves the assertion trips"
-->

## Findings

### TX1 [should-fix] Cross-thread clear() while a view is pinned is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheInvariantsTest.java`, methods `i3_streamClosedAtTxEnd` (line 470), `i6_secondClearIsNoOp` (line 448); `QueryResultCacheTest.java`, method `clearClosesAllStreamsAndIsIdempotent` (line 223).

**Production code**: `QueryResultCache.clear()` (`QueryResultCache.java:235`), reached from the tx-end sink `FrontendTransactionImpl.clear()` (`FrontendTransactionImpl.java:1014`), which is reachable from `DatabaseSessionEmbeddedPooled.realClose()` (`DatabaseSessionEmbeddedPooled.java:58`) on a pool-shutdown thread distinct from the tx owner.

**Issue**: The design names exactly one genuinely concurrent behavior in this otherwise single-thread feature: invariant I6, "Tx-end `clear()` idempotent and safe under cross-thread invocation (D9 wrapper + null-out)." The class Javadoc states clear() "is safe to reach from the pool-shutdown thread." Every test exercises clear() on the same thread that populated the cache. No test invokes clear() (or the tx-end sink) from a foreign thread while the owning thread holds a live, pinned `CachedResultSetView`.

**Evidence**: CONTRACT/TEST-TRACE C1. The cross-thread contract is asserted in the design (I6) and the production Javadoc (`QueryResultCache.java:36`, `:229-233`), yet no TEST TRACE reaches it. `i3_streamClosedAtTxEnd` populates, half-drains a view, then commits, all on one thread; `i6_secondClearIsNoOp` and `clearClosesAllStreamsAndIsIdempotent` call clear() twice in sequence on the test thread. Thread count in every clear() test is 1.

**Why it matters**: The cross-thread safety claim rests on two unsynchronised mechanisms: `IdempotentExecutionStream.closed` (a non-volatile boolean, `IdempotentExecutionStream.java`) and `CachedResultSetView.pinReleased` / `entry.liveViewCount` (also unsynchronised). If a pool-shutdown thread runs clear() → `entry.close()` while the owning thread is mid-`pullOneFromStream()` (which calls `entry.close()` on drain, `CachedResultSetView.java:291`), the two close paths race on `IdempotentExecutionStream.closed`. The design's position is that this is "best-effort" inherited from the existing tx contract, which may be acceptable. No test demonstrates the claimed safety holds (no exception, stream closed exactly once, no view corruption) when clear() actually arrives from another thread. The one concurrent path the feature has is the one with zero direct coverage.

**Suggested test**:
```java
@Test
public void i6_crossThreadClearWhileViewPinnedClosesStreamOnceAndDoesNotThrow() throws Exception {
  GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
  seed(50);
  session.begin();
  var rs = session.query("SELECT FROM " + CLASS_NAME);
  rs.next(); // entry populated, view pinned, stream un-exhausted
  var cache = tx().getQueryResultCache();
  assertEquals(1, cache.size());

  // The pool-shutdown contract: clear() arrives from a different thread while the
  // owning thread holds a live pinned view. Use a barrier so the foreign clear()
  // and an owning-thread pull race on IdempotentExecutionStream.closed.
  var barrier = new CyclicBarrier(2);
  var foreignError = new AtomicReference<Throwable>();
  var foreign = new Thread(() -> {
    try { barrier.await(); cache.clear(); }
    catch (Throwable t) { foreignError.set(t); }
  });
  foreign.start();
  barrier.await();
  // Owning thread keeps consuming concurrently; must not corrupt or double-close.
  try { while (rs.hasNext()) rs.next(); } catch (NoSuchElementException ignore) {}
  foreign.join();

  assertNull("cross-thread clear must not throw", foreignError.get());
  assertEquals("clear empties the cache", 0, cache.size());
  session.rollback();
}
```
Note: if the design's intent is that clear() is genuinely best-effort and a race is tolerated, the test should still assert the bounded outcome the design promises (no thrown exception escaping clear(), stream `closed` ends true). A test that pins the actual contract is more valuable than the prose claim alone.

### TX2 [should-fix] Lookup-level inFlightLookup guard branch is never executed by any test

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheWiringTest.java`, method `flagOn_reentrantQueryBypassesCache` (line 181); `TransactionMutationVersionTest.java`, method `cacheCodeDepthCountsNestedEntersAndFloorsAtZero` (line 105).

**Production code**: `QueryResultCache.lookup()` re-entrancy short-circuit `if (inFlightLookup) return null;` (`QueryResultCache.java:127`), the lookup-level half of the two-guard CR1 design.

**Issue**: CR1 mandates two re-entrancy guards: the tx-level `cacheCodeDepth` counter and the lookup-level `inFlightLookup` boolean. Every re-entrancy test exercises only `cacheCodeDepth`. The `inFlightLookup == true` branch, the actual recursive-`lookup` short-circuit, is dead code under the test suite.

**Evidence**: TEST-TRACE C2. `flagOn_reentrantQueryBypassesCache` enters the cache scope via `tx().enterCacheCode()` then issues a query; that query bypasses at the `cacheCodeDepth > 0` gate in `serveThroughCache` and never reaches `lookup()`, so `inFlightLookup` is never observed true. `cacheCodeDepthCountsNestedEntersAndFloorsAtZero` drives only the depth counter. The Step 3 episode (track-1.md line ~450) states outright: "The lookup-level `inFlightLookup` guard cannot be unit-tested in isolation (nothing inside `lookup` re-enters the cache); its end-to-end exercise lands with Step 6's session bracketing." But Step 6's bracketing trips the depth guard *first*, so the inFlightLookup branch is reached by neither the unit path nor the end-to-end path. The guard is structurally unreachable through `serveThroughCache` because the session always enters `cacheCodeDepth` before calling `lookup`.

**Why it matters**: An untested, structurally-unreachable guard either (a) protects against a path the current wiring forecloses, in which case it is defensive dead code that should carry a test documenting why it cannot fire, or (b) is the intended backstop if a future shape (Track 2 aggregate splice, Track 3 MATCH) calls `lookup` outside the `cacheCodeDepth` bracket, in which case it must be tested before later tracks rely on it. A guard with no executing test gives false confidence that two-guard CR1 is verified when only one guard is. A unit test can drive it directly.

**Suggested test**:
```java
@Test
public void lookupReentrancyGuardReturnsNullWhileLookupInProgress() {
  // Drive the inFlightLookup branch directly: a CachedEntry whose getShape() is
  // queried via a probe that re-enters lookup() on the same cache instance.
  // Simplest form — assert the documented invariant the wiring relies on:
  var cache = new QueryResultCache(new QueryCacheMetrics());
  var k = key("select from OUser where name = 'a'");
  cache.put(k, recordEntry(new CountingStream()));
  // A second lookup re-entered from inside the first must return null. Use a
  // stream/entry whose accessor re-enters cache.lookup(k, v) to force the branch,
  // or expose a package-private setInFlightForTest() seam if no natural re-entry exists.
  // Then assert: the re-entrant lookup returns null AND does not increment misses.
  assertNull(/* re-entrant lookup */ null);
}
```
If no natural re-entry exists (the episode's own conclusion), add a package-private test seam to flip `inFlightLookup` and assert `lookup` returns null without counting a miss. This is preferable to leaving the second CR1 guard with no executing assertion.

### TX3 [suggestion] assertOnOwningThread violation is never exercised on a cache path

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheInvariantsTest.java`, method `i2_cacheCodeDepthBalancedAfterQuery` (line 591).

**Production code**: `FrontendTransactionImpl.assertOnOwningThread()` (`FrontendTransactionImpl.java:158`), called from the new cache-path methods `enterCacheCode()` (line 1377) and `exitCacheCode()` (line 1387).

**Issue**: Invariant I2 ("Cache mutation paths owner-thread-only") is verified only indirectly, by asserting the `cacheCodeDepth` counter returns to zero after a query. No test calls a cache-path method (`enterCacheCode`/`exitCacheCode`) from a non-owning thread to confirm the assertion actually trips, nor confirms that the documented cross-thread exception (`clear()`) legitimately does NOT trip it.

**Evidence**: TEST-TRACE C3. `i2_cacheCodeDepthBalancedAfterQuery` runs entirely on the owning thread and asserts depth balance — a useful guard-leak check, but it proves nothing about the owner-thread assertion firing. The two new methods that added `assertOnOwningThread()` calls (`enterCacheCode`, `exitCacheCode`) have no off-thread test.

**Why it matters**: This is lower severity because `assertOnOwningThread` is pre-existing infrastructure with the same enforcement caveat across the whole transaction (assert-based, `-ea`-only) and the cache merely reuses it. The design's I2/I6 split (every cache mutation path is owner-thread-only except `clear()`) is a specific contract the new code establishes, and neither half is pinned by a test. A regression that, say, added `assertOnOwningThread()` to `QueryResultCache.clear()` would break the documented cross-thread shutdown path and no test would catch it.

**Suggested test**:
```java
@Test
public void i2_enterCacheCodeFromForeignThreadTripsOwnerAssert() throws Exception {
  // -ea required (asserts disabled otherwise — matches the suite's documented run mode).
  session.begin();
  session.query("SELECT FROM " + CLASS_NAME).close(); // sets storageTxThreadId
  var tx = tx();
  var err = new AtomicReference<Throwable>();
  var t = new Thread(() -> { try { tx.enterCacheCode(); } catch (Throwable e) { err.set(e); } });
  t.start(); t.join();
  assertTrue("owner-thread assert must trip off-thread", err.get() instanceof AssertionError);
  // And the documented exception: clear() from a foreign thread must NOT trip it.
  session.rollback();
}
```

## Evidence base

#### C1 — Cross-thread clear() contract asserted in design (I6) and Javadoc but only same-thread-tested
Phase-3 refutation check: claim survives. I attempted to refute "untested" by finding a cross-thread clear test: searched all changed test files for `Thread`, `Runnable`, `Latch`, `Barrier`, `Executor`, `ConcurrentTestHelper`, with zero matches. The only clear() tests (`i3_streamClosedAtTxEnd:470`, `i6_secondClearIsNoOp:448`, `clearClosesAllStreamsAndIsIdempotent:223`) run clear() on the populating thread. The cross-thread reachability of the sink is real: `FrontendTransactionImpl.clear():1014` ← tx-end sink ← `realClose()`:`DatabaseSessionEmbeddedPooled.java:58` (pool shutdown, documented at `assertOnOwningThread` Javadoc line 155-156 as a cross-thread caller). CONFIRMED-as-issue.

#### C2 — inFlightLookup branch unreachable through serveThroughCache; dead in tests
Phase-3 refutation check: claim survives. Refutation attempt: is the `inFlightLookup == true` branch (`QueryResultCache.java:127`) reached end-to-end? The session's `serveThroughCache` enters `cacheCodeDepth` (via `enterCacheCode`) before calling `lookup` (confirmed by `CachedResultSetView` Javadoc lines 65-73 and `TxResultCacheWiringTest:188`). A re-entrant query therefore short-circuits at the `cacheCodeDepth > 0` session gate before `lookup` is re-entered, so `inFlightLookup` is never observed true through the wiring. The Step 3 episode (track-1.md ~line 450) independently states the guard "cannot be unit-tested in isolation." No test executes the branch. CONFIRMED-as-issue.

#### C3 — I2 verified by depth-balance only; no off-thread assertion-trip test
Phase-3 refutation check: claim survives but down-graded to suggestion. Refutation: `i2_cacheCodeDepthBalancedAfterQuery:591` does exercise an I2-related property (guard balance) on the owning thread, which is a legitimate partial. The assertion-trip and the cross-thread-clear-exception halves are unexercised, but `assertOnOwningThread` is reused pre-existing infrastructure (same `-ea`-only caveat tx-wide), so the residual gap is the new enter/exitCacheCode call sites specifically. Lower impact → suggestion, not should-fix.

#### C4 — Single-thread model legitimately limits concurrency tests; the gap is the asserted-but-untested cross-thread path, not absent thread tests
This is the framing certificate for the whole review, per the dispatch guidance ("a single-thread-model feature may legitimately have FEW concurrency tests"). The cache is correctly designed and documented as owner-thread-only with one cross-thread exception. I did NOT flag the absence of multi-threaded contention tests on `lookup`/`put`/the LRU/the view merge, because those paths are owner-thread-only by contract and threading them would test a scenario the model forbids. The three findings target only behavior the design itself asserts is concurrency-relevant (I6 cross-thread clear, the two-guard CR1, the I2 owner-thread assertion on the new cache methods) yet leaves unexercised. The bulk of the suite (equivalence, LRU pinning, K0 version gate, delta merge) is single-thread by correct design and out of scope for this dimension.
