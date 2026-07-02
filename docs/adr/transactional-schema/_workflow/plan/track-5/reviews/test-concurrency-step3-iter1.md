<!-- MANIFEST
review: test-concurrency
track: 5
step: 3
commit_range: 5e4451d1c3~1..5e4451d1c3
flags: CONTRACT_OK
findings: 1
evidence_base: 5
cert_index: [C1, C2, C3, C4, C5]
index:
  - id: TX1
    sev: should-fix
    anchor: "### TX1 "
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxy.java:100
    cert: C1
    basis: "PSI: SchemaProxy.makeSnapshot tx-branch now builds from txState.getTxLocalSchema().makeUncachedSnapshot (uncommitted classes + provisional ids), not delegate; the only multithreaded isolation test (SchemaDeguardTest.overlayIsInvisibleToAConcurrentReaderOnAnotherThread) checks a tx-created INDEX on a COMMITTED class; concurrentSessionDoesNotSeeInTransactionCreate checks getSchema().existsClass(), not the snapshot; new TxAwareSchemaSnapshotTest is entirely single-threaded"
-->

## Findings

### TX1 [should-fix] No concurrent test proves the tx-aware snapshot keeps a tx-created class and its provisional collection id out of a concurrent session's snapshot

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java` (new, 305 lines, entirely single-threaded) — the missing test belongs here.
- **Production code**: `SchemaProxy.makeSnapshot()` (`SchemaProxy.java:79-101`), which now builds the session-private snapshot from `txState.getTxLocalSchema().makeUncachedSnapshot(session)` (`SchemaShared.java:360`) instead of the committed `delegate`.
- **Issue.** Step 3 changed the tx-aware snapshot source from the committed instance to the session-private tx-local `SchemaShared` copy. That copy carries uncommitted classes, uncommitted property rules, and provisional collection ids (`<= -2`). The load-bearing isolation invariant is that this payload must stay session-scoped: a concurrent session's `makeSnapshot()` must return the committed shared cache and never observe a tx-created class or a provisional collection id. No test exercises that invariant for the class/property/provisional-id payload this step introduces. The three existing cross-session tests all predate this change and cover a narrower payload:
  - `SchemaDeguardTest.overlayIsInvisibleToAConcurrentReaderOnAnotherThread` (`SchemaDeguardTest.java:1261`) is the only genuinely multithreaded isolation test. It holds a mid-tx `createIndex` on a class that was created and **committed** at the top level, and asserts a reader thread's snapshot does not see the tx-created **index**. It never creates a class inside the tx, so it cannot catch a leaked tx-created class or a leaked provisional collection id.
  - `SchemaDeguardTest.overlayDoesNotLeakToConcurrentSessionSnapshot` (`SchemaDeguardTest.java:1119`) is the single-threaded sibling of the same index-on-committed-class check.
  - `SchemaDeguardTest.concurrentSessionDoesNotSeeInTransactionCreate` (`SchemaDeguardTest.java:183`) does create a class inside the tx, but it asserts `other.getMetadata().getSchema().existsClass(...)` — the structural proxy path, not `getImmutableSchemaSnapshot()`. Before this step the snapshot's class view was committed-only, so a snapshot-level assertion was unnecessary; after this step it is exactly the untested surface.
- **Evidence** (see cert C1):
  ```
  CONTRACT: during session A's schema/index tx, SchemaProxy.makeSnapshot() returns a
            session-private ImmutableSchema built from A's tx-local SchemaShared copy
            (uncommitted classes + property rules + provisional collection ids <= -2),
            memoized on A's TxSchemaState.overlaySnapshot and NEVER written to the
            process-shared SchemaShared.snapshot volatile. A concurrent session B's
            makeSnapshot() must resolve the committed shared cache (B.getTxSchemaState()
            is null), never A's tx-local view.
  TEST TRACE:
    - overlayIsInvisibleToAConcurrentReaderOnAnotherThread @ SchemaDeguardTest.java:1261
        threads: 2 | sync: CountDownLatch (sound) | contention: shared SchemaShared.snapshot
        payload exercised: tx-created INDEX on a COMMITTED class
        verification: reader must not see the tx-created index name; reader must not crash
        => EXERCISED for the index payload; DOES NOT cover a tx-created class or a
           provisional collection id (the class is committed; only index names checked)
    - overlayDoesNotLeakToConcurrentSessionSnapshot @ :1119   (single-threaded, index payload)
    - concurrentSessionDoesNotSeeInTransactionCreate @ :183   (single-threaded; getSchema().existsClass(), not the snapshot)
    - TxAwareSchemaSnapshotTest (new)                          (single-threaded; zero cross-session cases)
  VERDICT: NOT TESTED for the Step-3 payload (cross-session snapshot isolation of a
           tx-created class + its provisional collection id).
  INTERLEAVING that hides:
    T1 (session A): begin(); schema.createClass("TxNewClass")   // provisional id <= -2, uncommitted
                    makeSnapshot()                              // builds from tx-local copy, memoizes
    T2 (session B): getImmutableSchemaSnapshot() -> makeSnapshot()  // committed fast path expected
    Critical point: a regression that writes A's tx-local snapshot into the shared
                    SchemaShared.snapshot (or makeUncachedSnapshot starting to cache) makes B
                    observe "TxNewClass" and its provisional id.
    Consequence: isolation break + a provisional id (<= -2) reaching an unrelated session, where
                 getCollectionNameById returns null and doGetAndCheckCollection fails.
  ```
- **Why it matters.** The isolation *mechanism* (session-scoping via `TxSchemaState`, never writing the shared `volatile` cache) is proven for the index payload by the existing thread test, so this is not a blocker. But the *payload* this step newly puts into the session-private snapshot is more dangerous to leak than an index name: a leaked tx-created class or provisional collection id corrupts a concurrent session's view and can drive a collection-resolution failure in a session that never touched the schema. The existing index-only thread test reads a committed class's index set, so a regression in the class/provisional-id dimension slips past it. The review target names this surface explicitly, and it has no multithreaded coverage.
- **Suggested test** (JUnit 4, core module; model on `overlayIsInvisibleToAConcurrentReaderOnAnotherThread`):
  ```java
  @Test
  public void concurrentSessionSnapshotNeverSeesATxCreatedClassOrProvisionalId() throws Exception {
    var snapshotTaken = new CountDownLatch(1);
    var readerDone = new CountDownLatch(1);
    var sawClass = new AtomicBoolean(false);
    var sawProvisionalId = new AtomicBoolean(false);
    var readerError = new AtomicReference<Throwable>();

    var reader = new Thread(() -> {
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS));
        var snap = other.getMetadata().getImmutableSchemaSnapshot();
        if (snap.getClassInternal("TxConcurrentNewClass") != null) {
          sawClass.set(true);
        }
        // No committed class in the concurrent session may resolve to a provisional id.
        for (var cls : snap.getClasses()) {
          for (var id : ((SchemaClassInternal) cls).getCollectionIds()) {
            if (SchemaShared.isProvisionalCollectionId(id)) {
              sawProvisionalId.set(true);
            }
          }
        }
      } catch (Throwable t) {
        readerError.set(t);
      } finally {
        readerDone.countDown();
      }
    }, "tx-created-class-concurrent-reader");
    reader.start();

    session.activateOnCurrentThread();
    session.begin();
    try {
      var cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
          .getClassInternal("TxConcurrentNewClass");
      // Force the tx-aware branch to build A's session-private snapshot before the reader looks.
      session.getMetadata().getSchema().createClass("TxConcurrentNewClass");
      assertNotNull(session.getMetadata().getImmutableSchemaSnapshot()
          .getClassInternal("TxConcurrentNewClass"));
      snapshotTaken.countDown();
      assertTrue(readerDone.await(10, TimeUnit.SECONDS));
    } finally {
      snapshotTaken.countDown();
      session.rollback();
    }
    reader.join(TimeUnit.SECONDS.toMillis(10));

    assertNull("the concurrent reader must not fail", readerError.get());
    assertFalse("a concurrent session must not see the tx-created class", sawClass.get());
    assertFalse("a provisional collection id must never reach a concurrent session's snapshot",
        sawProvisionalId.get());
  }
  ```

## Evidence base

#### C1 — Cross-session snapshot isolation of a tx-created class + provisional id (TX1): CONFIRMED
Step 3's `makeSnapshot` tx-branch builds from the tx-local copy (uncommitted classes + provisional ids), yet the only multithreaded isolation test checks a tx-created index on a committed class and the class-creating cross-session test checks `existsClass()`, not the snapshot, so the class/provisional-id leak dimension has no concurrent coverage.

#### C2 — `version++` on a volatile field in `resolveProvisionalCollectionIds` (SchemaShared.java:573): REFUTED as a concurrency exposure
The `//noinspection NonAtomicOperationOnVolatileField` read-modify-write looked like a possible cross-thread hazard. It is not. PSI find-usages of `SchemaShared.resolveProvisionalCollectionIds` returns exactly one production caller, `AbstractStorage.applyCommitOperations` (`AbstractStorage.java:2544`), plus the in-class assertion helper. The Javadoc and that call site confirm it runs on the **session-private tx-local copy** under that copy's `lock.writeLock()`. A tx-local copy is never published to another thread (it lives on `TxSchemaState`, one per session/tx), and no concurrent reader keys on the tx-local copy's `version` — the version-keyed re-resolution the bump serves is the committing thread's own working-set read against its rebuilt pin. Single writer, unshared object, so the non-atomic increment is safe and needs no concurrent test. Not a finding.

#### C3 — Non-atomic pin fields in `rebuildThreadLocalSchemaSnapshot` (MetadataDefault.java:106-116): NO ISSUE
The new method reads `immutableCount` and writes `immutableSchema` without synchronization. Both are plain per-session instance fields (`MetadataDefault.java:49-50`), and the whole `makeThreadLocalSchemaSnapshot` / `clearThreadLocalSchemaSnapshot` / `forceClearThreadLocalSchemaSnapshot` family is thread-confined: `MetadataDefault` is one instance per `DatabaseSessionEmbedded`, and sessions are thread-bound. The single production caller (`AbstractStorage.applyCommitOperations`, `AbstractStorage.java:2625`, PSI-confirmed) runs on the committing thread while it holds the D19 exclusive write lock. No cross-thread access to the pin fields, so no concurrent test is owed. Not a finding.

#### C4 — Commit-path pin rebuild and the YTDB-1101 committer-side promotion read: NO ISSUE for this step / documented deferral
The pin rebuild at `AbstractStorage.java:2616-2626` runs inside the schema-carrying commit under `stateLock.writeLock()`, which per D19 excludes concurrent data commits for the commit's duration, so the rebuild has no concurrent writer to race. The remaining cross-session hazard — a concurrent reader observing the committer's own schema-promotion read (`fromStream` -> `session.load`) mid-publish — is the documented YTDB-1101 / D19 best-effort boundary. It is an explicit plan Non-Goal ("Closing the residual concurrent-data-commit-vs-new-index window (YTDB-1101)") and already carries Step 2's best-effort concurrent-reader test (`CommitTimeIndexBuildTest.concurrentReaderNeverCrashesAndSeesEventuallyConsistentPublish`). The step's `version++` narrows the committer's own intra-commit read (it operates on the tx-local copy the committing thread consults), not the committed-instance version concurrent readers key on, so it does not narrow the cross-session race and does not owe a new concurrent test for it. The track Surprises entry frames Step 3 as needing to "account for the committer-side promotion read"; this step accounts for it on the intra-commit side only, and cross-session closure stays the deferred YTDB-1101 Non-Goal, so no test gap is chargeable to this step. Not a finding.

#### C5 — Test-race analysis of the new test file (Phase 3): SOUND
`TxAwareSchemaSnapshotTest` is entirely single-threaded: every test drives one `session`, shares no state across threads, runs assertions only after each operation returns, and wraps mutating cases in `begin()` / `finally { rollback() }` (or an explicit `commit()` before the committed-state assertions). There is no result container shared between threads and no assertion that races thread execution. No test-race defect. The finding against this file is a coverage gap (TX1), not a race in the test code itself.
