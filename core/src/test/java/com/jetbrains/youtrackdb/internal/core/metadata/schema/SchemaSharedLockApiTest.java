package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

/**
 * Direct pin for the {@link SchemaShared} public lock API: {@code acquireSchemaReadLock},
 * {@code releaseSchemaReadLock}, {@code acquireSchemaWriteLock(session)},
 * {@code releaseSchemaWriteLock(session)}, {@code releaseSchemaWriteLock(session, save)}.
 *
 * <p>The methods are part of the public lock contract — {@link SchemaShared} subclasses and
 * {@link SchemaProxy} both invoke them directly. The underlying {@link
 * java.util.concurrent.locks.ReentrantReadWriteLock} field is private and is NOT probed via
 * reflection.
 *
 * <p>Lock semantics pinned here:
 * <ul>
 *   <li>multiple readers may hold the read lock concurrently;</li>
 *   <li>a writer is exclusive against readers and other writers;</li>
 *   <li>{@code releaseSchemaWriteLock(session, false)} drops the cached snapshot but does NOT
 *       persist (the {@code iSave=false} arm). The next {@code makeSnapshot} call rebuilds a
 *       fresh snapshot.</li>
 *   <li>{@code releaseSchemaWriteLock(session)} (no-arg-save form) persists via the embedded
 *       storage's {@code saveInternal} path AND increments the schema {@code version}. This pins
 *       the version-bump invariant alongside save persistence.</li>
 * </ul>
 *
 * <p>The {@code SchemaShared} reference is fetched fresh from {@code session.getSharedContext()}
 * for each test method (not cached on the test instance). Caching a {@code SchemaShared} reference
 * past an {@code executeInTx} callback flakes under {@code -Dyoutrackdb.test.env=ci} because the
 * disk-mode storage path may rebuild the shared context.
 *
 * <p>Concurrent reader / writer tests use a tracked {@code spawn()} helper with bounded
 * {@code @After} join discipline so a leaked worker cannot hang the surefire JVM if a latch
 * path is misconfigured.
 */
public class SchemaSharedLockApiTest extends DbTestBase {

  private final List<Thread> spawnedWorkers = new CopyOnWriteArrayList<>();

  /**
   * Tracked worker spawn helper. Surefire reuses worker threads across @Test methods, so any
   * thread spawned here is registered for bounded join in the @After hook. Workers are marked
   * daemon so a leaked worker (e.g., a misconfigured latch path that the @After hook cannot
   * unblock) cannot keep the surefire forked JVM alive past the test method.
   */
  private Thread spawn(Runnable body, String name) {
    var t = new Thread(body, name);
    t.setDaemon(true);
    spawnedWorkers.add(t);
    t.start();
    return t;
  }

  @After
  public void joinSpawnedWorkers() throws InterruptedException {
    // Bounded join — a worker still alive after the bound indicates a missing latch.countDown
    // or a stuck lock acquisition. Failing the test loudly here surfaces the misconfiguration
    // instead of letting the leaked worker silently linger as a daemon.
    var leaked = new java.util.ArrayList<String>();
    for (var t : spawnedWorkers) {
      t.join(5_000);
      if (t.isAlive()) {
        leaked.add(t.getName());
        t.interrupt();
      }
    }
    spawnedWorkers.clear();
    if (!leaked.isEmpty()) {
      fail("workers did not join within 5s — likely a missing latch.countDown or stuck "
          + "acquire: " + leaked);
    }
  }

  @Test
  public void readLockIsReentrantOnSameThread() {
    // ReentrantReadWriteLock allows the same thread to acquire the read lock multiple times.
    // Pin that behaviour through the public API: paired acquire/release calls must not deadlock
    // and the release count must match the acquire count.
    var schemaShared = session.getSharedContext().getSchema();

    schemaShared.acquireSchemaReadLock();
    try {
      schemaShared.acquireSchemaReadLock();
      try {
        // Inside two read holds — this is allowed and must not deadlock.
        assertTrue("inside read lock, schema lookup must succeed",
            session.getMetadata().getSchema().existsClass("OUser"));
      } finally {
        schemaShared.releaseSchemaReadLock();
      }
    } finally {
      schemaShared.releaseSchemaReadLock();
    }
  }

  @Test
  public void multipleReadersAreConcurrent() throws InterruptedException {
    // Two reader threads must be able to hold the read lock simultaneously. If the implementation
    // were a mutex (not a RW lock), reader B would be blocked until reader A released. The latch
    // discipline ensures both readers hold the lock at the same instant before either releases.
    var schemaShared = session.getSharedContext().getSchema();
    var bothInside = new CountDownLatch(2);
    var releaseGate = new CountDownLatch(1);
    // Symmetric collector — earlier check-then-set on two slots could clobber concurrent
    // failures because both readers run the SAME Runnable and could observe an empty first slot
    // simultaneously. CopyOnWriteArrayList captures every failure regardless of arrival order.
    var failures = new CopyOnWriteArrayList<Throwable>();

    Runnable reader = () -> {
      try {
        schemaShared.acquireSchemaReadLock();
        try {
          bothInside.countDown();
          // Wait until the orchestrator confirms both readers are inside, then release.
          assertTrue("releaseGate must fire within 5s",
              releaseGate.await(5, TimeUnit.SECONDS));
        } finally {
          schemaShared.releaseSchemaReadLock();
        }
      } catch (Throwable t) {
        failures.add(t);
      }
    };

    spawn(reader, "lock-test-reader-1");
    spawn(reader, "lock-test-reader-2");

    assertTrue("both readers must hold the read lock concurrently within 5s",
        bothInside.await(5, TimeUnit.SECONDS));
    releaseGate.countDown();

    // Drain the workers before assertions — the @After hook also joins, but waiting here lets
    // the test method assert their failures explicitly.
    for (var t : spawnedWorkers) {
      t.join(5_000);
    }

    if (!failures.isEmpty()) {
      fail("reader threads failed: " + failures);
    }
  }

  @Test
  public void writerExcludesReader() throws InterruptedException {
    // A reader started while a writer holds the lock must block until the writer releases.
    // Pin: writer goes first, holds for a controllable interval, reader thread is gated on the
    // writer's release. If exclusion is broken, the reader's "saw post-release state" timestamp
    // would precede the writer's release.
    var schemaShared = session.getSharedContext().getSchema();
    var writerHolding = new CountDownLatch(1);
    var readerEntered = new AtomicBoolean(false);
    // Positive observable that the reader actually reached acquireSchemaReadLock(). Without it,
    // a slow CI scheduler could leave the reader still inside writerHolding.await() when the
    // 200ms sleep elapses, making readerEntered==false an artifact of scheduling rather than
    // proof of exclusion. Spinning until this flag flips guarantees the reader is queued on the
    // RW lock before we observe readerEntered.
    var readerAtLockAttempt = new AtomicBoolean(false);

    Runnable reader = () -> {
      try {
        // Wait until the writer is actually holding the lock — without this we cannot
        // guarantee the reader was contended.
        assertTrue("writerHolding must fire within 5s",
            writerHolding.await(5, TimeUnit.SECONDS));
        readerAtLockAttempt.set(true);
        schemaShared.acquireSchemaReadLock();
        try {
          readerEntered.set(true);
          // If the writer is still holding the write lock when we got here, the RW lock
          // exclusion is broken.
        } finally {
          schemaShared.releaseSchemaReadLock();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    var readerThread = spawn(reader, "lock-test-reader");

    schemaShared.acquireSchemaWriteLock(session);
    try {
      writerHolding.countDown();
      // Bounded spin until the reader has visibly reached acquireSchemaReadLock() — guarantees
      // the "must NOT have entered" assertion below is observed against an actively-blocked
      // reader, not a still-scheduling one.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!readerAtLockAttempt.get() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue("reader must reach acquireSchemaReadLock() before the exclusion check fires",
          readerAtLockAttempt.get());
      // Give the contended acquire 200ms to (incorrectly) succeed; it MUST stay blocked.
      Thread.sleep(200);
      assertFalse("reader must NOT have entered while writer holds the lock",
          readerEntered.get());
    } finally {
      // Use the no-save form to skip persistence for this lock-only test.
      schemaShared.releaseSchemaWriteLock(session, false);
    }

    readerThread.join(5_000);
    assertTrue("reader must enter once writer releases", readerEntered.get());
  }

  @Test
  public void releaseSchemaWriteLockWithoutSaveDropsSnapshotButAdvancesVersion() {
    // releaseSchemaWriteLock(session, false) skips the saveInternal/reload branch and only
    // clears the cached snapshot. Pin: a fresh snapshot is rebuilt on demand AND the version
    // still advances (the advance is unconditional on the modificationCounter==1 branch
    // regardless of iSave). Version values are drawn from a process-wide generator shared by
    // every SchemaShared instance in the JVM, so only the advance is pinned, not an exact +1
    // delta (concurrently running test classes draw from the same generator).
    var schemaShared = session.getSharedContext().getSchema();
    var snapshotBefore = session.getMetadata().getImmutableSchemaSnapshot();
    int versionBefore = schemaShared.getVersion();

    schemaShared.acquireSchemaWriteLock(session);
    try {
      // No mutation needed — just exercise the release-without-save branch.
    } finally {
      schemaShared.releaseSchemaWriteLock(session, false);
    }

    int versionAfter = schemaShared.getVersion();
    assertTrue("version must advance even on the iSave=false branch: before="
        + versionBefore + " after=" + versionAfter, versionAfter > versionBefore);

    var snapshotAfter = session.getMetadata().getImmutableSchemaSnapshot();
    // If the cached snapshot was not invalidated, the snapshot reference would be the same.
    // The contract is that release() with iSave=false sets snapshot = null on the
    // modificationCounter==1 branch.
    assertEquals("snapshot must still report classes after rebuild",
        snapshotBefore.countClasses(), snapshotAfter.countClasses());
  }

  @Test
  public void releaseSchemaWriteLockSavesAndIncrementsVersion() {
    // releaseSchemaWriteLock(session) — the no-arg form delegates to (session, true). When a
    // schema mutation actually occurs (a class was created inside the write-lock window), the
    // save path runs, the snapshot is invalidated, and version increments by 1.
    var schemaShared = session.getSharedContext().getSchema();
    int versionBefore = schemaShared.getVersion();

    // Mutate via the public Schema API — this internally acquires/releases the schema write
    // lock, runs saveInternal under embedded storage, and bumps the version.
    session.getMetadata().getSchema().createClass("LockApiSavingClass");
    assertTrue(session.getMetadata().getSchema().existsClass("LockApiSavingClass"));

    int versionAfter = schemaShared.getVersion();
    assertTrue("createClass under embedded storage must advance the schema version: before="
        + versionBefore + " after=" + versionAfter, versionAfter > versionBefore);
  }

  @Test
  public void writeLockIsReentrantOnSameThread() {
    // A schema mutation under a manually-held write lock exercises the
    // modificationCounter > 1 path of releaseSchemaWriteLock — the inner release decrements
    // but does NOT save (because count != 1); only the outer release saves.
    var schemaShared = session.getSharedContext().getSchema();
    int versionBefore = schemaShared.getVersion();

    schemaShared.acquireSchemaWriteLock(session);
    try {
      // Inside outer write hold, perform an operation that itself acquires/releases the write
      // lock — createClass on a fresh name. Because modificationCounter > 1 on the inner
      // release, no save fires there; the save+version-bump only runs at the OUTER release.
      session.getMetadata().getSchema().createClass("ReentrantWriteClass");
    } finally {
      // Outer release — modificationCounter goes 1→0 here, so save runs and version
      // increments.
      schemaShared.releaseSchemaWriteLock(session);
    }

    int versionAfter = schemaShared.getVersion();
    assertTrue("reentrant write lock must still produce a single version bump, before="
        + versionBefore + " after=" + versionAfter, versionAfter > versionBefore);
    assertTrue(session.getMetadata().getSchema().existsClass("ReentrantWriteClass"));
  }

  @Test
  public void readLockDoesNotBlockSecondReadFromAnotherThread() throws InterruptedException {
    // A second reader from a separate thread acquires the read lock without blocking on the
    // first. This is the symmetric "many-readers" check from the writer-excludes-reader test.
    var schemaShared = session.getSharedContext().getSchema();
    var firstAcquired = new CountDownLatch(1);
    var secondCompleted = new CountDownLatch(1);
    var secondThreadAcquired = new AtomicBoolean(false);

    schemaShared.acquireSchemaReadLock();
    firstAcquired.countDown();
    try {
      // Spawn a second reader; expect it to acquire and release inside the bounded interval
      // even though we still hold the read lock here.
      spawn(() -> {
        try {
          assertTrue("firstAcquired must fire within 5s",
              firstAcquired.await(5, TimeUnit.SECONDS));
          schemaShared.acquireSchemaReadLock();
          try {
            secondThreadAcquired.set(true);
          } finally {
            schemaShared.releaseSchemaReadLock();
          }
          secondCompleted.countDown();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }, "lock-test-second-reader");

      assertTrue("second reader must complete within 5s while first reader holds the lock",
          secondCompleted.await(5, TimeUnit.SECONDS));
    } finally {
      schemaShared.releaseSchemaReadLock();
    }
    assertTrue(secondThreadAcquired.get());
  }

  @Test
  public void writersAreSerializedAcrossThreads() throws InterruptedException {
    // Pin: while writer A holds the schema write lock, writer B's acquireSchemaWriteLock(session)
    // MUST block until A releases. The earlier "both classes exist" assertion was satisfiable
    // even under a hypothetical no-op write lock (because lower-level CHM synchronization could
    // still serialize the createClass map mutations). The serialization invariant is observable
    // only by checking that B's acquire returns AFTER A's release — captured here via an
    // AtomicBoolean flipped in A's finally block before B.acquire returns.
    //
    // One-sided invariant: aReleased.set(true) runs BEFORE the actual ReentrantReadWriteLock
    // unlock (releaseSchemaWriteLock performs version++ then lock.writeLock().unlock() in its
    // own finally — see SchemaShared#releaseSchemaWriteLock). A bEnteredWhileAHeld == true
    // result is therefore conclusive proof of broken exclusion, but a false result is not a
    // proof of correct serialization — only the absence of a detected breakage. The test is
    // a positive smoke gate, not a full serialization invariant pin.
    var schemaShared = session.getSharedContext().getSchema();
    var versionBefore = schemaShared.getVersion();
    var failures = new CopyOnWriteArrayList<Throwable>();
    var aHolding = new CountDownLatch(1);
    var bAttemptingAcquire = new CountDownLatch(1);
    var aReleased = new AtomicBoolean(false);
    var bEnteredWhileAHeld = new AtomicBoolean(false);
    var done = new CountDownLatch(2);

    Runnable writerA = () -> {
      try {
        try (var s = openDatabase()) {
          s.activateOnCurrentThread();
          var sa = s.getSharedContext().getSchema();
          sa.acquireSchemaWriteLock(s);
          try {
            aHolding.countDown();
            // Wait for B to be queued on the write lock — without this, A could release before
            // B even attempts acquire and the test would not exercise contention at all.
            assertTrue("writer B must reach acquireSchemaWriteLock within 5s",
                bAttemptingAcquire.await(5, TimeUnit.SECONDS));
            // Hold for an additional 200ms after B is queued. If exclusion is broken, B's
            // acquire would return during this window.
            Thread.sleep(200);
            // Mutate inside the held window so the version-bump invariant below stays valid.
            s.getMetadata().getSchema().createClass("WriterARace");
          } finally {
            aReleased.set(true);
            sa.releaseSchemaWriteLock(s, true);
          }
        }
      } catch (Throwable t) {
        failures.add(t);
      } finally {
        done.countDown();
      }
    };
    Runnable writerB = () -> {
      try {
        try (var s = openDatabase()) {
          s.activateOnCurrentThread();
          assertTrue("writer A must hold the write lock within 5s",
              aHolding.await(5, TimeUnit.SECONDS));
          var sb = s.getSharedContext().getSchema();
          bAttemptingAcquire.countDown();
          sb.acquireSchemaWriteLock(s); // MUST block until A releases.
          try {
            // If aReleased is false here, B entered while A was still holding the lock — this
            // is the breakage signal. The test asserts the negation outside.
            if (!aReleased.get()) {
              bEnteredWhileAHeld.set(true);
            }
            s.getMetadata().getSchema().createClass("WriterBRace");
          } finally {
            sb.releaseSchemaWriteLock(s, true);
          }
        }
      } catch (Throwable t) {
        failures.add(t);
      } finally {
        done.countDown();
      }
    };

    spawn(writerA, "lock-test-writerA");
    spawn(writerB, "lock-test-writerB");
    assertTrue("both writers must complete within 15s", done.await(15, TimeUnit.SECONDS));

    if (!failures.isEmpty()) {
      fail("writer threads failed: " + failures);
    }
    assertFalse("writer B must NOT enter while writer A holds the schema write lock",
        bEnteredWhileAHeld.get());

    // Reload schema on the main session before asserting — disk-mode CI runs the
    // saveInternal → reload path; memory-mode is in-memory. Reload normalises both modes.
    session.getMetadata().getSchema().reload();
    assertTrue("WriterARace must be present after both writers committed",
        session.getMetadata().getSchema().existsClass("WriterARace"));
    assertTrue("WriterBRace must be present after both writers committed",
        session.getMetadata().getSchema().existsClass("WriterBRace"));
    // Each successful save-form release increments the version at least once. The exact count
    // is not pinned — opening a second session via openDatabase() can itself trigger an
    // internal schema reload that bumps the version, so the "exactly +2" form is too strict
    // for the multi-session pathway.
    assertTrue("schema version must reflect at least two write-lock cycles, before="
        + versionBefore + " after=" + schemaShared.getVersion(),
        schemaShared.getVersion() >= versionBefore + 2);
  }

  @Test
  public void schemaSharedFromSharedContextIsTheSameInstanceAsTheProxyDelegate() {
    // SchemaProxy is a thin wrapper around the SchemaShared returned by
    // SharedContext.getSchema(). Pin: the SchemaShared accessed through SharedContext is the
    // same instance the SchemaProxy delegates to (one canonical SchemaShared per database).
    var fromSharedContext = session.getSharedContext().getSchema();
    var fromMakeSnapshot = ((SchemaInternal) session.getMetadata().getSchema()).makeSnapshot();
    // ImmutableSchema captures version/identity from SchemaShared at snapshot-time. The
    // captured version is the schema-shared version, so they must agree.
    assertEquals("snapshot version must match SchemaShared.getVersion",
        fromSharedContext.getVersion(), fromMakeSnapshot.getVersion());
    assertSame("snapshot identity must equal SchemaShared identity",
        fromSharedContext.getIdentity(), fromMakeSnapshot.getIdentity());
  }

  @Test
  public void createGlobalPropertyAdvancesVersionAndIsObservable() {
    // SchemaShared.createGlobalProperty is wrapped in acquire/releaseSchemaWriteLock. Pin: a
    // global property can be created via the public Schema API, the global-property-by-id
    // lookup returns it, and the schema version advances.
    var schemaShared = session.getSharedContext().getSchema();
    int versionBefore = schemaShared.getVersion();

    var schema = session.getMetadata().getSchema();
    schema.createGlobalProperty("globalLockApi", PropertyType.STRING, 700);
    var prop = schema.getGlobalPropertyById(700);
    assertEquals("globalLockApi", prop.getName());
    assertEquals(PropertyType.STRING, prop.getType());

    int versionAfter = schemaShared.getVersion();
    assertTrue("createGlobalProperty must advance schema version: before="
        + versionBefore + " after=" + versionAfter, versionAfter > versionBefore);
  }
}
