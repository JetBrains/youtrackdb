<!--MANIFEST
dimension: test-concurrency
track: 3
iteration: 1
range: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
verdict: PASS-WITH-FINDINGS
finding_count: 4
high_water_mark: TX4
evidence_base: 5
cert_index: C1,C2,C3,C4,C5
flags: none
index:
  - id: TX1
    sev: should-fix
    anchor: "#tx1-the-two-highest-value-blocking-tests-bypass-the-production-engagerelease-wiring"
    loc: "MetadataWriteMutexTest.java:263,296"
    cert: C1
    basis: "PSI find-usages of engage/releaseFor/ensureTxSchemaState/close; CONTRACT + TEST TRACE"
  - id: TX2
    sev: should-fix
    anchor: "#tx2-the-seed-failure-release-path-strands-the-permit-and-is-untested-for-its-concurrency-consequence"
    loc: "DatabaseSessionEmbedded.java:2459-2470"
    cert: C2
    basis: "PSI find-usages of releaseMetadataWriteMutexForTx; grep for any seed-failure test; INTERLEAVING"
  - id: TX3
    sev: suggestion
    anchor: "#tx3-engage-above-the-shared-locks-ordering-is-proven-only-by-the-assert-never-by-a-real-contended-interleaving"
    loc: "MetadataWriteMutexTest.java:364,388"
    cert: C3
    basis: "Read of engageMetadataWriteMutex asserts; TEST TRACE + INTERLEAVING"
  - id: TX4
    sev: suggestion
    anchor: "#tx4-same-thread-reject-and-foreign-park-are-not-exercised-through-a-real-schema-transaction"
    loc: "MetadataWriteMutexTest.java:263,296"
    cert: C4
    basis: "PSI find-usages of engage; TEST TRACE"
-->

## Findings

### TX1 [should-fix] The two highest-value blocking tests bypass the production engage/release wiring

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, methods `sameThreadSecondSessionEngageThrows` (line 263) and `differentThreadParksUntilRelease` (line 296)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lines 2438-2514) — `ensureTxSchemaState` → `engageMetadataWriteMutex` → `MetadataWriteMutex.engage`; release via `FrontendTransactionImpl.close()` (line 976) → `releaseMetadataWriteMutexForTx` (line 2508) → `MetadataWriteMutex.releaseFor`
- **Issue**: Both tests drive the mutex object **directly** — `mutex.engage(outer)` / `mutex.engage(inner)` / `mutex.releaseFor(...)` — rather than through the production seam that engages and releases the permit during a real schema transaction. They prove the `MetadataWriteMutex` primitive in isolation, but they cannot fail on a regression in the **wiring** between the primitive and the transaction lifecycle. If `ensureTxSchemaState` stopped calling `engageMetadataWriteMutex`, or `FrontendTransactionImpl.close()` stopped calling `releaseMetadataWriteMutexForTx`, both tests stay green because neither touches those call sites.
- **Evidence**: CONTRACT + TEST TRACE in C1. PSI find-usages confirms the only production caller of `engage` is `DatabaseSessionEmbedded.engageMetadataWriteMutex:2493` and the only production caller of `releaseFor` is `releaseMetadataWriteMutexForTx:2513`; every other `engage`/`releaseFor` caller is inside these two test methods (`MetadataWriteMutexTest.java:268,271,282,304,316,337,348`). Only `twoConcurrentSchemaTransactionsSerializeWithoutAbort` (line 94) reaches `engage` through the real seam, and it covers only the two-distinct-threads serialization case.
- **Why it matters**: The same-thread loud-reject (I-C4) and the foreign-thread-parks-until-release behaviour are the two concurrency contracts most likely to break silently if the engage seam moves (a future refactor of `resolveForWrite`/`ensureTxSchemaState`, or the Track 7 holder widening). A primitive-only test gives false confidence that the *transaction* serializes correctly. The same-thread case in particular is the legal embedded-session alternation the design calls out (D7), and it is exercised only against two bare `engage()` calls, never against two real sessions running a schema write on one thread.
- **Suggested test**: add one end-to-end same-thread case that runs the reject through the production path, so the wiring is load-bearing:
  ```java
  @Test
  public void sameThreadSecondSessionSchemaWriteThrowsThroughProductionPath() {
    var outer = session;
    outer.begin();
    outer.getMetadata().getSchema().createClass("OuterTx"); // engages via ensureTxSchemaState
    var inner = openDatabase();
    try {
      inner.activateOnCurrentThread();
      inner.begin();
      var ex = assertThrows(IllegalStateException.class,
          () -> inner.getMetadata().getSchema().createClass("InnerTx"));
      assertTrue(ex.getMessage().contains("different session"));
      inner.rollback();
    } finally {
      inner.activateOnCurrentThread();
      inner.close();
      outer.activateOnCurrentThread();
      outer.rollback(); // close() releases the permit through the real teardown
    }
    assertFalse("the outer rollback's close() must release the permit",
        outer.getSharedContext().getMetadataWriteMutex().isEngagedBy(outer));
  }
  ```
  An analogous foreign-thread variant where the holder releases by reaching `commit()`/`rollback()` (not `mutex.releaseFor`) would make `differentThreadParksUntilRelease` exercise the `close()` release too.

### TX2 [should-fix] The seed-failure release path strands the permit and is untested for its concurrency consequence

- **File**: no test exists (gap)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lines 2459-2470) — the `catch (RuntimeException | Error e)` arm in `ensureTxSchemaState` that calls `releaseMetadataWriteMutexForTx()` before rethrowing when `copyForTx` fails after the permit was engaged
- **Issue**: The engage→seed window is engaged-then-seeded: `engageMetadataWriteMutex()` acquires the permit (line 2453), then `copyForTx` seeds (line 2456). If the seed throws (the code comment enumerates record-not-found, I/O, malformed per-class record, non-persistent linked id), the catch arm releases the permit and rethrows. This is a concurrency-correctness path — its failure mode is a permanently stranded permit that parks **every future schema writer in the storage forever** — yet no test exercises it. The track ships exactly this defensive release; its absence regressing (e.g., a future edit dropping the `try`/`catch`, or the marker-ordering inverting) would not be caught.
- **Evidence**: INTERLEAVING + C2. PSI confirms `releaseMetadataWriteMutexForTx` has exactly two callers — the seed-failure catch (`DatabaseSessionEmbedded.java:2468`) and `FrontendTransactionImpl.close():976`. Grep across all four track test files (`MetadataWriteMutexTest`, `SchemaDeguardTest`, `SchemaProxyRoutingTest`, `CopyForTxTest`) returns zero matches for a seed-failure or stranded-permit scenario.
- **Why it matters**: INTERLEAVING — T1 begins a schema tx; `engage()` takes the single permit; `copyForTx` throws (corrupt committed root record); without the catch-arm release the permit is never returned because `metadataMutexEngaged` was set true at line 2494 and the custom-data marker was never written, so on the next write `ensureTxSchemaState` sees no `existing` state and re-enters `engage()` on the same thread that already holds the permit (comment at line 2463-2465). T2 (any other session) then parks forever on the next schema write. This is the worst-case liveness failure the mutex can produce, and it rests entirely on the untested catch arm.
- **Suggested test**:
  ```java
  @Test
  public void seedFailureReleasesPermitSoTheNextWriterIsNotStranded() throws Exception {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    session.begin();
    // Force copyForTx to throw after engage: e.g. stub/spy the SharedContext schema so
    // copyForTx raises, or corrupt the committed root record id for this session.
    var thrown = assertThrows(RuntimeException.class, session::ensureTxSchemaState);
    assertNotNull(thrown);
    session.rollback();
    // The permit must be free: a fresh schema writer on another thread must engage promptly,
    // not park forever on a stranded permit.
    assertFalse("a failed seed must not strand the permit", mutex.isEngagedBy(session));
    var t = new Thread(() -> {
      try (var s2 = openDatabase()) {
        s2.activateOnCurrentThread();
        s2.begin();
        s2.getMetadata().getSchema().createClass("AfterSeedFailure");
        s2.commit();
      }
    });
    t.setDaemon(true);
    t.start();
    t.join(5_000);
    assertFalse("the next schema writer must not be stranded behind a leaked permit", t.isAlive());
  }
  ```

### TX3 [suggestion] "Engage above the shared locks" ordering is proven only by the assert, never by a real contended interleaving

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, methods `engageOrderAssertFiresWhenSchemaLockHeld` (line 364) and `engageOrderAssertFiresWhenIndexManagerLockHeld` (line 388)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lines 2485-2495) — `engageMetadataWriteMutex` with the two engage-order asserts
- **Issue**: Both tests verify the **assert trips** when a shared lock is pre-held by the current thread. That is the right defense and it is well exercised for both locks. But the property the assert protects — that a mis-ordered engage would deadlock two real concurrent schema transactions (I-C2) — is never demonstrated as an actual concurrent failure. There is no test where a second schema tx parks on the permit while a first holds a shared write lock; the deadlock shape is asserted-against, not reproduced-and-prevented.
- **Evidence**: TEST TRACE + INTERLEAVING in C3. The two assert tests are single-threaded (`session.begin()` on the test thread, pre-acquire a lock, call `ensureTxSchemaState`, expect `AssertionError`). The interleaving the assert defends against (C3) involves two threads and a shared write lock held across a mutex park, which no test constructs.
- **Why it matters**: The assert is a developer-time guard only (Java asserts are off in production by default per the project's JaCoCo note). If the engage placement ever regresses *below* a shared lock in a way the assert misses (e.g., a new write path that takes a lock before reaching `ensureTxSchemaState`), no concurrent test would catch the resulting deadlock; the suite would still pass because the assert tests only check the one placement they pre-hold a lock for. This is a residual, not a blocker — the assert plus TX1's end-to-end serialization test cover the common paths — but a two-thread "engage-first keeps the second writer off the shared lock" liveness test would convert the deadlock defense from assert-only to behaviourally verified.
- **Suggested test**: a two-thread test where T1 holds the permit (via a real first schema write) and T2's first schema write is observed to park on the **permit** (not on a shared lock), proving the engage-first ordering keeps the shared locks contention-free for the parked writer. The existing `awaitThreadParked` helper already gives the deterministic park observation; assert additionally that T2 holds neither `SchemaShared.lock` nor the index-manager lock while parked (the orthogonality the engage-first order buys).

### TX4 [suggestion] Same-thread reject and foreign-park are not exercised through a real schema transaction

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutexTest.java`, methods `sameThreadSecondSessionEngageThrows` (line 263) and `differentThreadParksUntilRelease` (line 296)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutex.java` (lines 81-115) — `engage` / `releaseFor`
- **Issue**: Subsumed scope note relative to TX1, kept separate because it is a coverage-breadth observation rather than a wiring-regression observation. The foreign-park test releases via `mutex.releaseFor(session)` directly (line 337), so the release-on-`close()` path is exercised by exactly one test (`twoConcurrentSchemaTransactionsSerializeWithoutAbort`, where the first writer's `commit()` releases). A single end-to-end path is thin coverage for a release that has to fire correctly from both `doCommit` and `rollbackInternal` (both reach `close()` at the outermost frame — PSI-confirmed).
- **Evidence**: TEST TRACE + C4. PSI find-usages of `engage`: the only release reached through `close()` in any test is the implicit one inside `twoConcurrentSchemaTransactionsSerializeWithoutAbort`'s `first.commit()`. No test drives the **rollback** teardown's release while a foreign thread is parked.
- **Why it matters**: `releaseMetadataWriteMutexForTx` is gated on the `volatile metadataMutexEngaged` marker that "survives the tx custom-data wipe" (the Step 4 discovery). A regression in the marker's lifetime — e.g., it being cleared too early by a future `clear()` change — would strand the permit on the rollback path specifically, and the current suite only proves the commit path releases. A foreign-thread-parked-then-first-writer-**rolls-back** variant would close this.
- **Suggested test**: clone `twoConcurrentSchemaTransactionsSerializeWithoutAbort` but have the first writer `rollback()` instead of `commit()` after the second is observed parked, then assert the second proceeds — proving the release fires on the rollback teardown path while a writer is genuinely parked.

## Evidence base

#### C1 — engage/release wiring callers (PSI find-usages, transactional-schema project, IDE-aligned)
CONFIRMED-as-issue (TX1). PSI `ReferencesSearch` over `MetadataWriteMutex#engage` returns one production caller — `DatabaseSessionEmbedded#engageMetadataWriteMutex` at `DatabaseSessionEmbedded.java:2493` — and four test callers, all inside `sameThreadSecondSessionEngageThrows` (`:268,:271`) and `differentThreadParksUntilRelease` (`:304,:316`). `MetadataWriteMutex#releaseFor` returns one production caller (`DatabaseSessionEmbedded#releaseMetadataWriteMutexForTx:2513`) plus test callers at `:282,:337,:348`. `DatabaseSessionEmbedded#ensureTxSchemaState` is reached from `SchemaProxedResource#resolveForWrite:89,:98`, `IndexManagerEmbedded#recordMembershipChangeIntoTxLocalView:225`, `IndexManagerEmbedded#createIndex:398`, `IndexManagerEmbedded#dropIndex:557` — so a real `createClass`/`createIndex` inside a tx flows through the engage seam, but only `twoConcurrentSchemaTransactionsSerializeWithoutAbort` (the sole test reaching engage through that seam) exercises it. The two direct-drive tests therefore cannot fail on a wiring regression. Verdict survived the refutation check: there is no indirect path by which moving the engage seam would still fail these two tests, because they never reference `ensureTxSchemaState`/`close()`.

#### C2 — seed-failure release path reachability (PSI find-usages + grep)
CONFIRMED-as-issue (TX2). PSI confirms `DatabaseSessionEmbedded#releaseMetadataWriteMutexForTx` has exactly two callers: the seed-failure catch arm (`ensureTxSchemaState:2468`) and `FrontendTransactionImpl#close:976`. Read of `ensureTxSchemaState` (lines 2448-2470) confirms the engage-then-seed order: `engageMetadataWriteMutex()` acquires the permit and sets `metadataMutexEngaged=true` at line 2494 before `copyForTx` runs at line 2456, so a seed throw with no catch-arm release would strand the permit (the custom-data marker is not yet written, so a same-tx retry re-enters `engage()` on the same thread per the comment at lines 2463-2465). `grep` across all four track test files for `seedFail|copyForTx.*throw|releaseMetadataWriteMutexForTx|sleep` returned zero matches (exit 1). The concurrency consequence (next writer parks forever) is unexercised.

#### C3 — engage-order interleaving (read of engageMetadataWriteMutex)
Refuted as a blocker, recorded as a suggestion (TX3). The engage-order asserts at `DatabaseSessionEmbedded.java:2487` (schema lock) and `:2490` (index-manager lock) are exercised by `engageOrderAssertFiresWhenSchemaLockHeld` and `engageOrderAssertFiresWhenIndexManagerLockHeld`, both single-threaded, both expecting the `AssertionError`. The I-C2 deadlock interleaving the assert defends against — T1 parks on the permit while holding a shared write lock, deadlocking against the commit-side lock acquisition — is not reproduced as a concurrent test. Refutation: the assert plus TX1's end-to-end two-thread serialization test do cover the live engage placement, and Java asserts run in tests (the project enables `-ea` in surefire), so the deadlock cannot land in the current code without tripping the assert. That demotes it below should-fix: it is a residual coverage gap (no behavioural proof of the liveness property), not an active hole. Hence suggestion.

#### C4 — release-path breadth (PSI find-usages of engage; read of FrontendTransactionImpl close/rollbackInternal/doCommit)
Refuted as a distinct should-fix, recorded as a suggestion (TX4) and scoped against TX1. PSI confirms `FrontendTransactionImpl#close` is reached from both `rollbackInternal:400` and `doCommit:698`, each only at `txStartCounter == 0` (the outermost frame — read of `rollbackInternal` lines 395-400 confirms the frame guard). The mutex release at `close():976` therefore fires once per transaction on both teardown paths. Only the commit path is behaviourally exercised (inside `twoConcurrentSchemaTransactionsSerializeWithoutAbort`); the rollback teardown's release is exercised by `engageOrderAssertFiresWhenIndexManagerLockHeld`'s sanity tail (`:417-427`) but with no foreign thread parked, so the "release-on-rollback unblocks a parked writer" property is unproven. Refutation against TX1: this overlaps TX1's wiring observation, so it is held at suggestion to avoid double-counting the same root cause; it is recorded because the rollback-specific release breadth is a separable test gap.

#### C5 — Thread.sleep and coordination-primitive audit (read + grep)
CONFIRMED-as-non-issue (no finding). The test file uses no `Thread.sleep` for coordination anywhere (grep across all four track test files returned zero `sleep` matches, exit 1). Blocking is proven deterministically via `awaitThreadParked` (lines 55-66) observing `Thread.State.WAITING`/`TIMED_WAITING` — the states a thread blocked on `Semaphore.acquireUninterruptibly` reports — with a 5s deadline and `Thread.onSpinWait()`, so the blocking proofs fail closed if a regression lets a worker through instead of parking it. Coordination uses `CountDownLatch` handshakes (`firstHoldsMutex`/`firstMayCommit`/`secondAboutToEngage`/`foreignAboutToEngage`) that establish a real, observed race window (the second writer is provably parked while the first holds the permit). Worker exceptions are captured into `AtomicReference<Throwable>` and re-thrown on the test thread as `AssertionError` (lines 170-172, 243-245, 250-252, 342-343) — not swallowed. Workers are joined with a bounded 5s join in `@After` (lines 69-82) that **fails the test** on a leak, and are daemon so a stuck acquire cannot hang the surefire JVM. Result containers (`AtomicBoolean`, `AtomicReference`, `CopyOnWriteArrayList`) are thread-safe. No test uses a `synchronized` block in test code to mask a production race. This is materially above the usual bar; the file's harness is sound and the findings above are coverage-breadth gaps, not test-race defects.
