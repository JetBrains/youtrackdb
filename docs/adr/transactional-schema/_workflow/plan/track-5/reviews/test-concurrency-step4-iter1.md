<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: TX1, sev: should-fix, loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java:269", anchor: "### TX1 ", cert: C1, basis: "polymorphic tx-shaped plan leak into the cross-session shared plan cache is untested; the polymorphic test passes even if both anti-leak guards regress"}
  - {id: TX2, sev: suggestion, loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/FetchFromClassExecutionStepTest.java:486", anchor: "### TX2 ", cert: C2, basis: "canBeCached()==false provisional-id branch has no direct test; stepIsAlwaysCacheable name/comment now overstate the contract"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### TX1 [should-fix] Polymorphic cross-session plan-cache leak is untested; the polymorphic test passes even if both anti-leak guards regress

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java`, method `polymorphicQueryCachedBeforeTheTransactionSeesATxCreatedSubclassRows` (line 269).
- **Production code**: `FetchFromClassExecutionStep.canBeCached()` (~line 255) returns false when the scan set carries a provisional id; `YqlExecutionPlanCache.getInternal` / `putInternal` (lines 140, 99) bypass the shared cache while `getTxSchemaState() != null`. The plan cache is genuine cross-session state: `SharedContext.getYqlExecutionPlanCache()` hands every session of the database the same `Cache<String, InternalExecutionPlan>`.
- **Issue**: The central cross-session guarantee this step introduces has no test. A tx-shaped polymorphic plan (the `select from TxPolyParent` scan whose polymorphic collection set now includes the tx-created child's provisional id `<= -2`) must never enter the shared cache, because to any other session that plan scans a collection that does not exist and silently misses rows. The polymorphic test verifies only that the schema-tx session itself re-plans through the get-bypass; it rolls back without ever reading `select from TxPolyParent` again from a clean (`getTxSchemaState()==null`) context, and it never involves a second session.
- **Evidence**: INTERLEAVING + TEST TRACE (see C1, and the refuted alternatives C3/C4/C5 for why no other test closes the gap). During the schema tx the parent query re-plans (get returned null via the bypass). At `SelectExecutionPlanner:284-289` the put is gated on `result.canBeCached()`, and `putInternal` also bypasses on `getTxSchemaState()!=null`; both prevent the leak today. Rollback does not invalidate the plan cache (only a schema commit fires `onSchemaUpdate → invalidateAll`), so a leaked entry would overwrite the primed committed plan under the same statement key and persist. A concurrent pure-data session (or the same session post-rollback) running `select from TxPolyParent` would then execute a plan scanning a provisional collection id and either throw or silently return the wrong rows. The test asserts nothing after rollback, so it stays green even if both guards regress — false confidence, exactly the failure mode this dimension exists to catch.
- **Why it matters**: cross-session data-correctness corruption. An unrelated session executes a poisoned plan and crashes or silently drops the tx-created subclass rows once the class commits, the precise scenario the `canBeCached()` javadoc names ("to every other session ... a plan carrying one scans a collection that does not exist and silently misses the real rows").
- **Suggested test** (ideal: a genuine concurrent reader; the same file already uses this pattern in `concurrentSessionSnapshotNeverSeesATxCreatedClassOrProvisionalId`, so `openDatabase()` and the latch idiom are in place). A cheaper single-session proxy is shown commented at the end, since the shared-cache read path branches only on `getTxSchemaState()`, so a post-rollback read from a clean context is a faithful stand-in for a foreign session:

```java
@Test
public void polymorphicPlanFromASchemaTxNeverPoisonsTheSharedCache() throws Exception {
  var schema = session.getMetadata().getSchema();
  schema.createClass("TxPolyLeakParent");
  session.begin();
  ((EntityImpl) session.newEntity("TxPolyLeakParent")).setProperty("name", "committed");
  session.commit();

  // Prime the shared cache with the committed-state plan (pure-data tx keeps tx state clean).
  session.begin();
  try (var rs = session.query("select from TxPolyLeakParent")) {
    rs.stream().count();
  }
  session.commit();

  var schemaTxQueried = new CountDownLatch(1);
  var readerDone = new CountDownLatch(1);
  var readerError = new AtomicReference<Throwable>();
  var readerRows = new AtomicReference<List<String>>();

  // A concurrent pure-data session must keep getting exactly the committed row, never a plan
  // that leaked from the schema tx and scans a provisional collection id.
  var reader = new Thread(() -> {
    try (var other = openDatabase()) {
      other.activateOnCurrentThread();
      if (!schemaTxQueried.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("the schema tx never signalled");
      }
      try (var rs = other.query("select from TxPolyLeakParent")) {
        readerRows.set(rs.stream().map(r -> (String) r.getProperty("name")).sorted().toList());
      }
    } catch (Throwable t) {
      readerError.set(t);
    } finally {
      readerDone.countDown();
    }
  }, "poly-plan-cache-clean-reader");
  reader.start();

  session.activateOnCurrentThread();
  session.begin();
  try {
    schema.createClass("TxPolyLeakChild", schema.getClass("TxPolyLeakParent"));
    ((EntityImpl) session.newEntity("TxPolyLeakChild")).setProperty("name", "tx-child");
    // Re-plan under the tx; this is where a regressed guard would publish the tx-shaped plan.
    try (var rs = session.query("select from TxPolyLeakParent")) {
      rs.stream().count();
    }
    schemaTxQueried.countDown();
    assertTrue("the concurrent reader must finish", readerDone.await(10, TimeUnit.SECONDS));
  } finally {
    schemaTxQueried.countDown();
    session.rollback();
  }
  reader.join(TimeUnit.SECONDS.toMillis(10));

  assertNull("a concurrent pure-data reader must not crash on a leaked tx-shaped plan",
      readerError.get());
  assertEquals("the concurrent session must see only the committed row, never the tx child",
      List.of("committed"), readerRows.get());

  // Cheaper single-session proxy (drop the thread above and assert after rollback instead):
  // session.activateOnCurrentThread();
  // try (var rs = session.query("select from TxPolyLeakParent")) {
  //   assertEquals(List.of("committed"),
  //       rs.stream().map(r -> (String) r.getProperty("name")).sorted().toList());
  // }
}
```

### TX2 [suggestion] The canBeCached()==false provisional-id branch has no direct test, and stepIsAlwaysCacheable now overstates the contract

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/FetchFromClassExecutionStepTest.java`, method `stepIsAlwaysCacheable` (line 486). Not touched by this diff.
- **Production code**: `FetchFromClassExecutionStep.canBeCached()` (~line 255) — new branch returns false when `collectionIds` carries a provisional id.
- **Issue**: `canBeCached()==false` for a provisional scan set is one of the two barriers that keep a tx-shaped plan out of the shared cross-session cache (`SelectExecutionPlan.canBeCached()` propagates any false step to the whole plan at the `SelectExecutionPlanner:287` put gate). The new branch has no direct unit test. The existing `stepIsAlwaysCacheable` asserts `canBeCached()==true`, and its name plus javadoc ("The step is always plan-cache-safe", "always cacheable") now overstate the contract; a regression that makes provisional-scan-set steps cacheable again would not be caught by it. This is defense-in-depth for the TX1 cross-session leak, hence flagged here rather than left purely to test-completeness.
- **Evidence**: PSI find-usages (see C2). `SelectExecutionPlan.canBeCached()` (line 282) consults each step, and `SelectExecutionPlanner:287` gates the cache put on it. No test constructs a step whose scan set carries a provisional id. `collectionIds` is private and populated only from the class's polymorphic ids, so the test must run inside a schema tx against a tx-created class rather than injecting an `int[]`.
- **Why it matters**: with TX1 open, the cross-session leak rests on two untested guards. A silent regression of `canBeCached()` removes one barrier without any red test.
- **Suggested test**:

```java
@Test
public void aStepScanningATxCreatedClassIsNotCacheable() {
  session.begin();
  try {
    session.getMetadata().getSchema().createClass("TxUncacheableStep");
    var ctx = newContext();
    // The tx-aware snapshot exposes the tx-created class; its polymorphic collection ids
    // carry the provisional id (<= -2), so the scan set is not plan-cache-safe.
    var step = new FetchFromClassExecutionStep("TxUncacheableStep", null, ctx, null, false);
    assertThat(step.canBeCached())
        .as("a scan set carrying a provisional collection id must not be cacheable")
        .isFalse();
  } finally {
    session.rollback();
  }
}
```

Rename `stepIsAlwaysCacheable` to `stepScanningCommittedCollectionsIsCacheable` (or similar) and update its javadoc, so the "always" claim no longer contradicts the new branch.

## Evidence base

Phase-3 test-race analysis. Two claims survived the refutation check and are the findings above (one line each). Three candidate findings were refuted during analysis and appear in full, because the refutations are what narrow TX1 to the polymorphic case and confirm no other test covers it.

#### C1 CONFIRMED — polymorphic tx-shaped plan leak into the shared cache is executable cross-session and untested; the polymorphic test passes regardless of the guards (grounds TX1).

#### C2 CONFIRMED — the `canBeCached()==false` provisional branch and the `getTxSchemaState()` put-bypass are the two leak barriers; only the get-bypass is exercised, `canBeCached()==false` has no direct test, and `stepIsAlwaysCacheable` asserts the opposite for the committed case only (grounds TX2).

#### C3 REFUTED — candidate: "`queryPlanBuiltInsideTheCreatingTransactionIsNotReusedAfterCommit` covers the leak."

Refuted. A schema commit fires `YqlExecutionPlanCache.onSchemaUpdate → invalidate() → cache.invalidateAll()`, so the whole plan cache is wiped at commit. Any plan cached during the schema tx is gone by the time the post-commit query runs, so that test's post-commit re-plan is guaranteed by the invalidation alone and cannot distinguish "the put-bypass / canBeCached guard worked" from "the commit wiped the cache." The test would stay green even if both anti-leak guards regressed. It therefore gives no leak coverage for the commit case; it only pins that the committed row is visible afterward. This is why the leak-absence coverage rests on the rollback path, not the commit path.

#### C4 REFUTED — candidate: "the tx-created (non-polymorphic) leak is also untested."

Refuted for the tx-created case. `rolledBackTxCreatedClassLeavesNoReusablePlanBehind` is a genuine leak test: rollback does not fire `onSchemaUpdate`, so the cache is not invalidated, and if the tx-shaped plan for `select from TxRolledBackQueried` had leaked in, the post-rollback re-query (clean context, `getTxSchemaState()==null`) would execute it. The test instead asserts the statement fails with class-not-found, which holds only if nothing leaked. This closes the tx-created scan-set case and narrows TX1 to the polymorphic case, where the statement (`select from TxPolyParent`) stays valid for a clean/foreign session and a leaked plan would be executed rather than failing to resolve.

#### C5 REFUTED — candidate: "the new tests carry latch/timing or implicit-read-only-tx hazards (focal questions 3 and 4)."

Refuted. This diff adds no new multithreaded tests; the only concurrent test in the class (`concurrentSessionSnapshotNeverSeesATxCreatedClassOrProvisionalId`) predates this step (Step 3) and is unchanged, so there are no new latch/timing hazards to flag. On the implicit-read-only-tx nesting subtlety: the only new test with a bare-query-then-`begin()` sequence is `polymorphicQueryCachedBeforeTheTransactionSeesATxCreatedSubclassRows`, and it correctly wraps the priming query in an explicit `begin()`/`commit()` with a comment naming the exact hazard (an implicit read-only tx that a later `begin()` would nest into). The other new tests (`queryPlanBuiltInsideTheCreatingTransactionIsNotReusedAfterCommit`, `sameTransactionQueryAndCommitReflectUpdatesAndDeletesOfTxCreatedClassRows`) end on a bare terminal query with no following `begin()`, so no nesting occurs. The step's other production changes (the `checkCollectionLimits` relaxation, the `RecordIteratorCollection` provisional-scan skip, and the commit-time `rewriteProvisionalRecordCollectionIds`) are either session-local or run under the D19 whole-commit exclusive write lock, so single-threaded coverage is adequate for them; the shared plan cache is the only genuine cross-session surface, which is where TX1 lands.
