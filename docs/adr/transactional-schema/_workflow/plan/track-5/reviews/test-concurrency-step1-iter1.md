<!-- MANIFEST
dimension: test-concurrency
step: track-5-step-1
commit_range: 608493b718~1..608493b718
flags: CONTRACT_OK
findings: 2
evidence_base: 4
cert_index: 2
index:
  - id: TX1
    sev: should-fix
    anchor: "#tx1-cross-session-leak-claim-verified-only-by-a-single-thread-sequential-two-session-test"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java:1182
    cert: C1
    basis: "PSI caller-set of makeUncachedSnapshot/forceClearThreadLocalSchemaSnapshot + ThreadLocal activeSession thread-binding; no concurrency primitive in either overlay test"
  - id: TX2
    sev: suggestion
    anchor: "#tx2-pin-count-zero-invariant-on-forceclearthreadlocalschemasnapshot-is-never-exercised"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/MetadataDefault.java:95
    cert: C2
    basis: "PSI find-usages: forceRebuildSchemaSnapshotForIndexOverlay has production callers only; no test drives index DDL under a held snapshot pin"
-->

## Findings

### TX1 [should-fix] Cross-session-leak claim verified only by a single-thread sequential two-session test

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`, method `overlayDoesNotLeakToConcurrentSessionSnapshot` (line 1182)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxy.java` (line 78-85), `SchemaShared.java` (line 134 `volatile snapshot`, 324-346 cached `makeSnapshot`, 359-366 `makeUncachedSnapshot`, 368-379 `forceSnapshot`)
- **Issue**: The step's central correctness claim is that an overlay-resolved snapshot is session-private and does not leak into a *concurrent* session. The only test for that claim, `overlayDoesNotLeakToConcurrentSessionSnapshot`, runs both sessions on the single JUnit thread. YouTrackDB sessions are thread-bound: `DatabaseSessionEmbedded` gates every operation on a `ThreadLocal<Boolean> activeSession` (`DatabaseSessionEmbedded.java:283`) and `openDatabase()` → `youTrackDB.open(...)` calls `activateOnCurrentThread()`, which deactivates the previously-active `session` on that thread. So the test never has two live sessions on two threads; it is a sequential A-then-B stand-in for a claim explicitly about concurrent sessions.
- **Evidence**: CONTRACT (P1/P2/P3) + TEST TRACE + INTERLEAVING in `## Evidence base` → C1. The shared state under contract is the process-shared `volatile SchemaShared.snapshot`, mutated under `snapshotLock` and nulled by `forceSnapshot()`. The test exercises the leak-through-shared-cache regression (a revert to the cached `makeSnapshot(session)` path would taint the shared field and B would see the index — so it is not a false-confidence stand-in for that specific regression), but it does not exercise any genuine interleaving: no second thread reads `SchemaShared.snapshot` while the overlay session is mid-`makeUncachedSnapshot`, nor while an in-flight index DDL calls `forceRebuildSchemaSnapshotForIndexOverlay` (session-local, no shared-cache null) versus a commit-path `forceSnapshot()` (shared-cache null under `snapshotLock`). A concurrent reader racing the `volatile`-field publication and the lock window is the scenario the "concurrent session" wording promises and the test does not deliver.
- **Why it matters**: A future change that (re)introduces a shared-cache write on the overlay path, or a visibility/ordering bug in the `volatile snapshot` publish versus a concurrent reader, would pass this sequential test because thread-binding serializes the two sessions. The isolation property is provable from the sequential test for the *shared-cache-content* leak, but the memory-visibility and interleaving guarantees that "concurrent session" implies are unverified. This is a WEAK concurrency test, not a race that would catch a regression in the cross-thread ordering.
- **Suggested test** (JUnit 4, core module; a genuine second-thread reader racing the overlay session):
  ```java
  @Test
  public void overlayIsInvisibleToAConcurrentReaderOnAnotherThread() throws Exception {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("OverlayRaceClass");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "OverlayRaceClass.name";

    var overlayBuilt = new CountDownLatch(1);
    var readerDone = new CountDownLatch(1);
    var leaked = new AtomicBoolean(false);
    var readerError = new AtomicReference<Throwable>();

    // Reader thread: its own session, reads the shared snapshot while thread 1 holds the overlay.
    var reader = new Thread(() -> {
      try (var other = openDatabase()) {
        other.activateOnCurrentThread();
        overlayBuilt.await(5, TimeUnit.SECONDS);
        var otherClass = (SchemaClassInternal) other.getMetadata()
            .getImmutableSchemaSnapshot().getClassInternal("OverlayRaceClass");
        for (var idx : otherClass.getIndexesInternal()) {
          if (indexName.equals(idx.getName())) {
            leaked.set(true);
          }
        }
      } catch (Throwable t) {
        readerError.set(t);
      } finally {
        readerDone.countDown();
      }
    }, "overlay-concurrent-reader");
    reader.start();

    session.activateOnCurrentThread();
    session.begin();
    try {
      cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
      // Signal only after the overlay build so the reader observes the shared snapshot
      // during the overlay window, not before or after it.
      overlayBuilt.countDown();
      assertTrue(readerDone.await(10, TimeUnit.SECONDS));
    } finally {
      session.rollback();
    }
    assertNull("the concurrent reader must not fail", readerError.get());
    assertFalse("the overlay must not leak into a concurrent reader's snapshot", leaked.get());
  }
  ```

### TX2 [suggestion] Pin-count-zero invariant on `forceClearThreadLocalSchemaSnapshot` is never exercised

- **File**: (no test) — production only
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/MetadataDefault.java` (line 95-103); called by `DatabaseSessionEmbedded.forceRebuildSchemaSnapshotForIndexOverlay` (`DatabaseSessionEmbedded.java:2550`), driven from `IndexManagerEmbedded` create (line 522) and drop (line 703)
- **Issue**: The step's design leans on a stated invariant — an index DDL change is never issued from inside a pinned read-record operation, so `forceClearThreadLocalSchemaSnapshot()` always sees `immutableCount == 0` and its lazy clear is safe. When the count is non-zero the method throws `IllegalStateException` "to surface a misplaced call" (Javadoc on `forceRebuildSchemaSnapshotForIndexOverlay`). Neither the throw path nor the safe zero-count path is asserted by any test.
- **Evidence**: PSI find-usages (C2) shows `forceRebuildSchemaSnapshotForIndexOverlay` has only the two `IndexManagerEmbedded` production callers and no test caller; no test in the diff drives an index create/drop while a snapshot pin is held (`makeThreadLocalSchemaSnapshot` outstanding). The existing overlay tests read through the un-pinned `getImmutableSchemaSnapshot()` path (`immutableSchema == null`), so `immutableCount` is 0 throughout and the assert branch is inert.
- **Why it matters**: This is a defensive invariant that guards a lazy shared-state clear. If a later step or refactor issues an index DDL under a held pin (the very "misplaced call" the throw is meant to surface), no regression test would confirm the throw fires; and a positive test that the common path clears at count 0 documents the contract. Both are single-threaded and cheap.
- **Suggested test** (JUnit 4; pin the snapshot, then assert the force-clear throws):
  ```java
  @Test
  public void forceRebuildUnderAHeldSnapshotPinThrowsLoudly() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("PinnedRebuildClass");
    cls.createProperty("name", PropertyType.STRING);
    session.begin();
    var meta = session.getMetadata();
    meta.makeThreadLocalSchemaSnapshot(); // immutableCount == 1
    try {
      // A mid-tx index change force-rebuilds; under a held pin the invariant must throw.
      assertThrows(IllegalStateException.class,
          () -> cls.createIndex("PinnedRebuildClass.name",
              SchemaClass.INDEX_TYPE.NOTUNIQUE, "name"));
    } finally {
      meta.clearThreadLocalSchemaSnapshot(); // immutableCount == 0
      session.rollback();
    }
  }
  ```
  (If the intended contract is that this scenario is unreachable, prefer a positive test asserting `forceRebuildSchemaSnapshotForIndexOverlay` succeeds at count 0 plus a code comment; the point is that the invariant is documented by a test either way.)

## Evidence base

#### C1: Cross-session-leak test is single-thread sequential; genuine concurrent interleaving untested

PREMISE P1: `SchemaShared.snapshot` (`SchemaShared.java:134`, `protected volatile ImmutableSchema`) is process-shared mutable state. Reads/writes flow through the cached `makeSnapshot(session)` (`SchemaShared.java:324-346`, populate under `snapshotLock`) and are invalidated by `forceSnapshot()` (`SchemaShared.java:368-379`, null under `snapshotLock`). One `SchemaShared` instance is shared by all sessions of a database via `SharedContext`.

PREMISE P2: `SchemaProxy.makeSnapshot()` (`SchemaProxy.java:78-85`) claims session-privacy: when `session.hasActiveIndexOverlay()` it returns `delegate.makeUncachedSnapshot(session)` (`SchemaShared.java:359-366`), which builds a fresh `ImmutableSchema` and never writes `this.snapshot`; otherwise it takes the cached path. So an overlay session leaves the shared `volatile` field untouched.

PREMISE P3: Expected concurrent access — an overlay-holding session on thread T1, plus readers on other threads reading the shared snapshot; the overlay must be invisible to them and not survive rollback.

CONTRACT: overlay-resolved snapshot is session-private and does not leak into a concurrent session.

TEST TRACE (`overlayDoesNotLeakToConcurrentSessionSnapshot`, `SchemaDeguardTest.java:1182-1214`):
  - Thread count: 1. Sessions are thread-bound — `DatabaseSessionEmbedded.activeSession` is a `ThreadLocal<Boolean>` (`DatabaseSessionEmbedded.java:283`) and `openDatabase()` → `youTrackDB.open` activates `other` on the test thread, deactivating `session`. There is never a second live session on a second thread.
  - Synchronization used: none (sequential); no `new Thread`, `CountDownLatch`, `CyclicBarrier`, or `ConcurrentTestHelper` anywhere in `SchemaDeguardTest` or `IndexOverlayTest` (grep-confirmed).
  - Contention point: the process-shared `volatile SchemaShared.snapshot`.
  - Interleaving exercised: none — session A builds an uncached snapshot, then, only after that, session B reads the shared cache.
  - Verification: `other`'s snapshot class does not list the uncommitted index.
VERDICT: EXERCISED for the shared-cache-content regression (A's uncached build leaves the shared field clean; a revert to the cached path would taint it and B would see the index — so this is NOT a false-confidence stand-in for that regression), but WEAK for the concurrency the "concurrent session" wording promises.

INTERLEAVING left untested:
  Thread T1: `session.begin()`; `cls.createIndex(...)` → `IndexManagerEmbedded.createIndex` records the overlay and calls `session.forceRebuildSchemaSnapshotForIndexOverlay()` (session-local clear, no shared-cache write); then `SchemaProxy.makeSnapshot()` → `makeUncachedSnapshot`.
  Thread T2: `other.getImmutableSchemaSnapshot()` → cached `makeSnapshot(session)` reading/publishing the `volatile SchemaShared.snapshot` under `snapshotLock`.
  Critical point: T2 reads the shared `volatile` field while T1 is mid-overlay and, at commit boundaries, `forceSnapshot()` nulls that field under `snapshotLock`.
  Consequence a race would surface: a stale or overlay-tainted `ImmutableSchema` observed by T2, or a visibility gap on the `volatile` publish — none reachable on one thread because thread-binding serializes A and B.
  Test needed: the second-thread reader in TX1's suggested test.

TEST RACE CHECK (`overlayDoesNotLeakToConcurrentSessionSnapshot`): coordination none; shared test state none across threads (single thread); assertions run after each op; result container none. SOUND — but its soundness derives from being single-threaded, which is the gap itself.

#### C2: Pin-count-zero invariant assert path has no test caller

PREMISE P4: `MetadataDefault.forceClearThreadLocalSchemaSnapshot()` (`MetadataDefault.java:95-103`) clears the pinned `immutableSchema` only when `immutableCount == 0`, else throws `IllegalStateException`. The step's Javadoc treats "index DDL is never issued from inside a pinned read" as a load-bearing invariant.

CONTRACT: the force-rebuild is only ever called at pin count 0 (safe clear), and a misplaced call under a held pin throws.

TEST TRACE: PSI find-usages — `forceRebuildSchemaSnapshotForIndexOverlay` is called only from `IndexManagerEmbedded.java:522` (create) and `:703` (drop); `forceClearThreadLocalSchemaSnapshot` from `DatabaseSessionEmbedded.java:2550` and the pre-existing `IndexManagerEmbedded.java:387` (release-exclusive-lock). No test caller for either. The overlay tests read through the un-pinned `getImmutableSchemaSnapshot()` path, so `immutableCount` stays 0 and the assert branch is never taken (neither the safe nor the throwing arm asserted).
VERDICT: NOT TESTED.
