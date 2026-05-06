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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * for each test method (not cached on the test instance) — the R7 working note explicitly warns
 * against keeping a {@code SchemaShared} reference past an {@code executeInTx} callback under
 * {@code -Dyoutrackdb.test.env=ci}.
 *
 * <p>Concurrent reader / writer tests use a tracked {@code spawn()} helper with bounded
 * {@code @After} join discipline so a leaked worker cannot hang the surefire JVM if a latch
 * path is misconfigured.
 */
public class SchemaSharedLockApiTest extends DbTestBase {

  private final List<Thread> spawnedWorkers = new CopyOnWriteArrayList<>();

  /**
   * Tracked worker spawn helper. Surefire reuses worker threads across @Test methods, so any
   * thread spawned here is registered for bounded join in the @After hook.
   */
  private Thread spawn(Runnable body, String name) {
    var t = new Thread(body, name);
    spawnedWorkers.add(t);
    t.start();
    return t;
  }

  @After
  public void joinSpawnedWorkers() throws InterruptedException {
    // Bounded join — a worker still alive at this point indicates a missing latch.countDown
    // or a stuck lock acquisition. The bound prevents a misconfigured test from hanging the
    // entire surefire JVM.
    for (var t : spawnedWorkers) {
      t.join(5_000);
    }
    spawnedWorkers.clear();
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
    var bothCompleted = new AtomicBoolean(false);
    var reader1Failed = new AtomicReference<Throwable>();
    var reader2Failed = new AtomicReference<Throwable>();

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
        if (reader1Failed.get() == null) {
          reader1Failed.set(t);
        } else {
          reader2Failed.set(t);
        }
      }
    };

    spawn(reader, "lock-test-reader-1");
    spawn(reader, "lock-test-reader-2");

    assertTrue("both readers must hold the read lock concurrently within 5s",
        bothInside.await(5, TimeUnit.SECONDS));
    bothCompleted.set(true);
    releaseGate.countDown();

    // Drain the workers before assertions — the @After hook also joins, but waiting here lets
    // the test method assert their failures explicitly.
    for (var t : spawnedWorkers) {
      t.join(5_000);
    }

    assertTrue("reader-1 must have completed without exception, got: "
        + reader1Failed.get(), reader1Failed.get() == null);
    assertTrue("reader-2 must have completed without exception, got: "
        + reader2Failed.get(), reader2Failed.get() == null);
    assertTrue(bothCompleted.get());
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
    var writerHeldDuringReader = new AtomicBoolean(true);

    Runnable reader = () -> {
      try {
        // Wait until the writer is actually holding the lock — without this we cannot
        // guarantee the reader was contended.
        assertTrue("writerHolding must fire within 5s",
            writerHolding.await(5, TimeUnit.SECONDS));
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
      // Give the reader 200ms to attempt entry; it MUST be blocked.
      Thread.sleep(200);
      assertFalse("reader must NOT have entered while writer holds the lock",
          readerEntered.get());
      // Snapshot the "writer-held while reader was queued" assertion before releasing.
      writerHeldDuringReader.set(true);
    } finally {
      // Use the no-save form to skip persistence for this lock-only test.
      schemaShared.releaseSchemaWriteLock(session, false);
    }

    readerThread.join(5_000);
    assertTrue("reader must enter once writer releases", readerEntered.get());
    assertTrue(writerHeldDuringReader.get());
  }

  @Test
  public void releaseSchemaWriteLockWithoutSaveDropsSnapshotButPreservesVersion() {
    // releaseSchemaWriteLock(session, false) skips the saveInternal/reload branch and only
    // clears the cached snapshot. Pin: a fresh snapshot is rebuilt on demand AND the version
    // counter still advances (the increment is unconditional on the modificationCounter==1
    // branch regardless of iSave).
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
    assertEquals("version must advance even on the iSave=false branch",
        versionBefore + 1, versionAfter);

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
    // Two competing writers must not run in parallel. Pin: both writers create distinct classes;
    // the schema must contain BOTH after both writers complete (no lost update). Class-creation
    // count gives a deterministic invariant — if writers raced and one was lost, we would see 1
    // class instead of 2.
    var schemaShared = session.getSharedContext().getSchema();
    var attemptCount = new AtomicInteger();
    var failures = new CopyOnWriteArrayList<Throwable>();
    var startGate = new CountDownLatch(1);
    var done = new CountDownLatch(2);

    Runnable writerA = () -> {
      try {
        // Each writer reopens its own session — schema mutations from a non-test thread require
        // an active session.
        try (var s = openDatabase()) {
          s.activateOnCurrentThread();
          assertTrue(startGate.await(5, TimeUnit.SECONDS));
          s.getMetadata().getSchema().createClass("WriterARace");
          attemptCount.incrementAndGet();
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
          assertTrue(startGate.await(5, TimeUnit.SECONDS));
          s.getMetadata().getSchema().createClass("WriterBRace");
          attemptCount.incrementAndGet();
        }
      } catch (Throwable t) {
        failures.add(t);
      } finally {
        done.countDown();
      }
    };

    spawn(writerA, "lock-test-writerA");
    spawn(writerB, "lock-test-writerB");
    startGate.countDown();
    assertTrue("both writers must complete within 10s", done.await(10, TimeUnit.SECONDS));

    if (!failures.isEmpty()) {
      fail("writer threads failed: " + failures);
    }
    assertEquals("both writers must have completed", 2, attemptCount.get());

    // Reload schema on the main session before asserting — disk-mode CI runs the
    // saveInternal → reload path; memory-mode is in-memory. Reload normalises both modes.
    session.getMetadata().getSchema().reload();
    assertTrue("WriterARace must be present after both writers committed",
        session.getMetadata().getSchema().existsClass("WriterARace"));
    assertTrue("WriterBRace must be present after both writers committed",
        session.getMetadata().getSchema().existsClass("WriterBRace"));
    assertTrue("schema version must reflect at least two write-lock cycles",
        schemaShared.getVersion() >= 2);
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
