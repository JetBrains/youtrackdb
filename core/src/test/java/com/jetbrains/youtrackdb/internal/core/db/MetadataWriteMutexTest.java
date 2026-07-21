package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * Concurrency coverage for the metadata-write mutex that serializes schema- and index-changing
 * transactions. The mutex is engaged on a transaction's first schema/index write strictly above the
 * shared metadata locks and released once the transaction's outermost frame closes. These tests pin
 * the behaviour this track ships: a second schema transaction blocks rather than aborting; a held
 * mutex does not block data commits or snapshot-based schema reads; a same-thread embedded session
 * fails loudly instead of self-deadlocking; a foreign thread parks until release; and a mis-ordered
 * engage (from inside a held shared metadata lock) trips the engage-order assertion.
 *
 * <p>The abnormal-termination permit handshake and the freezer gate are a later track and are not
 * exercised here.
 */
public class MetadataWriteMutexTest extends DbTestBase {

  private final List<Thread> spawnedWorkers = new CopyOnWriteArrayList<>();

  /**
   * Tracked worker spawn helper. Surefire reuses worker threads across {@code @Test} methods, so any
   * thread spawned here is registered for a bounded join in the {@code @After} hook. Workers are
   * daemon so a leaked worker (a stuck mutex acquire that the test cannot unblock) cannot keep the
   * surefire forked JVM alive.
   */
  private Thread spawn(Runnable body, String name) {
    var t = new Thread(body, name);
    t.setDaemon(true);
    spawnedWorkers.add(t);
    t.start();
    return t;
  }

  /**
   * Spin until {@code worker} settles into a parked state (WAITING/TIMED_WAITING — the states a
   * thread blocked on {@code Semaphore.acquireUninterruptibly} reports) or the timeout elapses,
   * returning the last observed state. Used to prove a worker is parked on the mutex permit by
   * observing its thread state rather than inferring blocking from absence of progress after a sleep,
   * so the blocking proof fails closed if a regression lets the worker through instead of parking it.
   */
  private static Thread.State awaitThreadParked(Thread worker, long timeoutMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    Thread.State state = worker.getState();
    while (System.nanoTime() < deadline) {
      state = worker.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        return state;
      }
      Thread.onSpinWait();
    }
    return state;
  }

  @After
  public void joinSpawnedWorkers() throws InterruptedException {
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
      fail("workers did not join within 5s — likely a stuck mutex acquire: " + leaked);
    }
  }

  /**
   * Two concurrent schema transactions serialize on the mutex without either aborting. The first
   * session opens a schema transaction (engaging the mutex on its first schema write) and parks
   * holding it. A second session on another thread starts its own schema transaction; its first
   * schema write must block on the mutex — proven by the second thread not having created its class
   * while the first still holds the permit. Once the first commits and releases, the second proceeds
   * and commits its own class. Neither transaction is rolled back by contention: blocking, not
   * aborting, is the single-writer mechanism.
   */
  @Test
  public void twoConcurrentSchemaTransactionsSerializeWithoutAbort() throws InterruptedException {
    var firstHoldsMutex = new CountDownLatch(1);
    var firstMayCommit = new CountDownLatch(1);
    var secondCreatedClass = new AtomicBoolean(false);
    var secondAborted = new AtomicBoolean(false);
    var secondError = new AtomicReference<Throwable>();
    var secondAboutToEngage = new CountDownLatch(1);
    var secondThreadRef = new AtomicReference<Thread>();

    // First session: open a schema tx, engage the mutex by creating a class, then park holding it
    // until the test releases it. Runs on its own thread so its session activation does not collide
    // with the test thread's default session.
    spawn(() -> {
      try (var first = openDatabase()) {
        first.activateOnCurrentThread();
        first.begin();
        first.getMetadata().getSchema().createClass("FirstSchemaTx");
        firstHoldsMutex.countDown();
        firstMayCommit.await();
        first.commit();
      } catch (Throwable t) {
        secondError.compareAndSet(null, t);
      }
    }, "mutex-first-writer");

    assertTrue("first session must engage the mutex", firstHoldsMutex.await(5, TimeUnit.SECONDS));

    // Second session: try to start a schema tx while the first holds the mutex. Its createClass must
    // block on the mutex, so secondCreatedClass stays false until the first releases.
    var secondDone = new CountDownLatch(1);
    spawn(() -> {
      try (var second = openDatabase()) {
        second.activateOnCurrentThread();
        second.begin();
        try {
          // Publish this worker's thread and signal that the very next call is the blocking schema
          // write, so the test can observe this thread park on the permit deterministically.
          secondThreadRef.set(Thread.currentThread());
          secondAboutToEngage.countDown();
          second.getMetadata().getSchema().createClass("SecondSchemaTx");
          secondCreatedClass.set(true);
          second.commit();
        } catch (Throwable txError) {
          secondAborted.set(true);
          throw txError;
        }
      } catch (Throwable t) {
        secondError.compareAndSet(null, t);
      } finally {
        secondDone.countDown();
      }
    }, "mutex-second-writer");

    // Wait until the second worker is about to make its blocking schema write, then observe it
    // actually park on the permit (WAITING/TIMED_WAITING) rather than inferring blocking from a
    // sleep. The full-path latch only proves the worker reached the call; the thread-state poll
    // proves the call is parked on the semaphore, so this fails closed if a regression let the
    // second writer through instead of blocking it.
    assertTrue("the second worker must reach its blocking schema write",
        secondAboutToEngage.await(5, TimeUnit.SECONDS));
    var secondThread = secondThreadRef.get();
    assertNotNull("the second worker thread must have been published", secondThread);
    var parkedState = awaitThreadParked(secondThread, 5_000);
    assertTrue("the second schema tx must park on the mutex while the first holds it, observed in"
        + " thread state " + parkedState,
        parkedState == Thread.State.WAITING || parkedState == Thread.State.TIMED_WAITING);
    assertFalse("the second schema tx must block on the mutex while the first holds it",
        secondCreatedClass.get());
    assertFalse("the second schema tx must not be aborted by contention — it blocks instead",
        secondAborted.get());

    // Release the first; the second now acquires the mutex and finishes.
    firstMayCommit.countDown();
    assertTrue("the second schema tx must finish after the first releases the mutex",
        secondDone.await(5, TimeUnit.SECONDS));

    if (secondError.get() != null) {
      throw new AssertionError("no schema tx should error on contention", secondError.get());
    }
    assertTrue("the second schema tx must have created its class once unblocked",
        secondCreatedClass.get());
    // Both transactions committed without a contention abort: the first held the mutex to its
    // commit, the second blocked and then ran, and neither threw. Single-writer is enforced by
    // blocking, not by aborting. Both classes are visible in the committed schema afterwards: the
    // second transaction seeded its tx-local schema from a fresh committed read after unparking,
    // so it built on the first transaction's just-committed class instead of re-parsing its own
    // stale begin-time snapshot — whose set-diff would have phantom-dropped the first class's
    // collection and made the second commit fail (the stale-seed regression this pins).
    var committedSchema = session.getMetadata().getSchema();
    assertTrue("the first tx's class must survive the second tx's commit",
        committedSchema.existsClass("FirstSchemaTx"));
    assertTrue("the second tx's class must be committed",
        committedSchema.existsClass("SecondSchemaTx"));
  }

  /**
   * A committed-schema reload never engages the metadata-write mutex and never trips the
   * engage-order guard, even though its {@code fromStream} inheritance rebuild ripples a subclass's
   * collection into an indexed superclass's membership inside the reload's own transaction — the
   * shape that, unguarded, made the index-manager seam treat the ripple as the transaction's first
   * schema write and engage the mutex under the schema write lock (the
   * {@code IllegalStateException} "must engage above SchemaShared.lock" red). The test pins both
   * halves: the reload runs while ANOTHER session is parked holding the mutex mid-schema-tx, so a
   * spurious engage attempt could not return (it would park on the held permit and wedge the reload
   * under the schema write lock) — completing promptly proves no engage happened — and afterwards
   * the committed view is intact, the reloading session holds no permit, and a follow-up schema
   * transaction on the same session works (no leaked guard state).
   */
  @Test
  public void schemaReloadWithIndexedSuperclassDoesNotEngageMutex() throws InterruptedException {
    // Committed class graph whose reload ripples: an indexed superclass and a subclass. Built on
    // the legacy top-level DDL path like the sibling tests' setup classes.
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ReloadRippleParent");
    parent.createProperty("name", PropertyType.STRING);
    parent.createIndex("ReloadRippleParent.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    schema.createClass("ReloadRippleChild", parent);

    // Another session engages the mutex via a schema tx and parks holding it, so an engage attempt
    // from the reload below cannot succeed silently — it would park and wedge the reload.
    var mutexHeld = new CountDownLatch(1);
    var holderMayFinish = new CountDownLatch(1);
    var holderError = new AtomicReference<Throwable>();
    spawn(() -> {
      try (var holder = openDatabase()) {
        holder.activateOnCurrentThread();
        holder.begin();
        holder.getMetadata().getSchema().createClass("ReloadRippleMutexHolder");
        mutexHeld.countDown();
        holderMayFinish.await();
        holder.rollback();
      } catch (Throwable t) {
        holderError.compareAndSet(null, t);
        mutexHeld.countDown();
      }
    }, "reload-ripple-mutex-holder");
    assertTrue("the holder session must engage the mutex", mutexHeld.await(5, TimeUnit.SECONDS));

    try {
      // The reload must complete while the mutex is held: the committed-view rebuild is not a
      // schema write, so its inheritance-rebuild ripples are suppressed by the reload guard
      // instead of seeding a tx-local schema state (which would either throw the engage-order
      // IllegalStateException or park on the held permit).
      session.getMetadata().reload();
    } finally {
      holderMayFinish.countDown();
    }

    assertFalse("the reloading session must not hold the mutex after the reload",
        session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session));
    if (holderError.get() != null) {
      throw new AssertionError("the mutex-holder session must not error", holderError.get());
    }
    // The reload rebuilt the committed view intact.
    assertTrue(session.getMetadata().getSchema().existsClass("ReloadRippleParent"));
    assertTrue(session.getMetadata().getSchema().existsClass("ReloadRippleChild"));
    // A follow-up schema transaction on the reloading session works: the reload guard cleared and
    // the mutex is free again once the holder rolled back.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("ReloadRippleAfter"));
    assertTrue(session.getMetadata().getSchema().existsClass("ReloadRippleAfter"));
  }

  /**
   * A held mutex does not block data commits or snapshot-based schema reads. While one session holds
   * the mutex (an open schema tx with a tx-local write), a second session commits a pure-data
   * transaction and performs a snapshot-based schema read; both proceed without waiting on the
   * mutex. This is the mutex-orthogonality property that keeps the low-rate-low-contention premise
   * holding: the mutex only serializes schema writers, never readers or data writers.
   */
  @Test
  public void heldMutexDoesNotBlockDataCommitOrSnapshotRead() throws InterruptedException {
    // A data class for the concurrent data commit to write into.
    session.getMetadata().getSchema().createClass("DataClass");

    var mutexHeld = new CountDownLatch(1);
    var schemaTxMayCommit = new CountDownLatch(1);
    var holderError = new AtomicReference<Throwable>();

    spawn(() -> {
      try (var holder = openDatabase()) {
        holder.activateOnCurrentThread();
        holder.begin();
        // Engage the mutex via a schema write, then park holding it.
        holder.getMetadata().getSchema().createClass("MutexHolderClass");
        assertTrue("the schema writer must hold the mutex",
            holder.getSharedContext().getMetadataWriteMutex().isEngagedBy(holder));
        mutexHeld.countDown();
        schemaTxMayCommit.await();
        holder.rollback();
      } catch (Throwable t) {
        holderError.compareAndSet(null, t);
      }
    }, "mutex-holder");

    assertTrue("the schema writer must engage the mutex", mutexHeld.await(5, TimeUnit.SECONDS));

    // On a separate thread (so it does not touch the test session), run a pure-data commit and a
    // snapshot-based schema read while the mutex is held. Both must complete promptly.
    var dataDone = new CountDownLatch(1);
    var dataError = new AtomicReference<Throwable>();
    var snapshotReadSawDataClass = new AtomicBoolean(false);
    spawn(() -> {
      try (var dataSession = openDatabase()) {
        dataSession.activateOnCurrentThread();
        // Pure-data commit: create an entity in an existing class. Touches no schema, so it never
        // engages the mutex and must not block behind the held permit.
        dataSession.executeInTx(tx -> {
          var entity = dataSession.newEntity("DataClass");
          entity.setProperty("v", 1);
        });
        // Snapshot-based schema read: a plain existsClass goes through the immutable snapshot, which
        // does not take the mutex.
        snapshotReadSawDataClass.set(
            dataSession.getMetadata().getSchema().existsClass("DataClass"));
      } catch (Throwable t) {
        dataError.compareAndSet(null, t);
      } finally {
        dataDone.countDown();
      }
    }, "data-writer-and-reader");

    assertTrue("a data commit and a snapshot read must complete while the mutex is held — they do"
        + " not wait on the schema mutex",
        dataDone.await(5, TimeUnit.SECONDS));
    if (dataError.get() != null) {
      throw new AssertionError("the data path must not be blocked by the mutex", dataError.get());
    }
    assertTrue("the snapshot-based schema read must have run unblocked",
        snapshotReadSawDataClass.get());

    schemaTxMayCommit.countDown();
    if (holderError.get() != null) {
      throw new AssertionError("the mutex holder must not error", holderError.get());
    }
  }

  /**
   * The same thread cannot open a second session's schema transaction while it still holds the mutex
   * through the first session. This is the legal embedded-session case: rather than parking forever
   * on a permit its own thread holds (a self-deadlock), the inner engage throws loudly. Driven
   * directly against the mutex with two real session objects on one thread, which is exactly the
   * holder/engage relationship a same-thread embedded session produces.
   */
  @Test
  public void sameThreadSecondSessionEngageThrows() {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var outer = session;
    var inner = openDatabase();
    try {
      mutex.engage(outer);
      assertTrue("the outer session must hold the mutex", mutex.isEngagedBy(outer));
      try {
        mutex.engage(inner);
        fail("engaging on a thread that already holds the mutex through a different session must"
            + " throw rather than self-deadlock");
      } catch (IllegalStateException expected) {
        assertTrue("the reject must name the same-thread different-session cause",
            expected.getMessage().contains("different session"));
      }
      // The outer session still holds the permit; the failed inner engage did not release it.
      assertTrue("a rejected same-thread engage must not disturb the outer session's hold",
          mutex.isEngagedBy(outer));
    } finally {
      mutex.releaseFor(outer);
      inner.activateOnCurrentThread();
      inner.close();
      outer.activateOnCurrentThread();
    }
  }

  /**
   * A foreign thread parks on a held mutex and proceeds only once the permit is released. The test
   * thread engages the mutex through its session; a second thread tries to engage through another
   * session and must block until the test thread releases. Driven directly against the mutex so the
   * park/unpark timing is observable without a full schema-tx teardown.
   */
  @Test
  public void differentThreadParksUntilRelease() throws InterruptedException {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var foreignEngaged = new CountDownLatch(1);
    var foreignError = new AtomicReference<Throwable>();
    var foreignSession = new AtomicReference<DatabaseSessionEmbedded>();
    var foreignThreadRef = new AtomicReference<Thread>();
    var foreignAboutToEngage = new CountDownLatch(1);

    mutex.engage(session);
    try {
      spawn(() -> {
        var other = openDatabase();
        foreignSession.set(other);
        try {
          other.activateOnCurrentThread();
          // Publish this worker's thread and signal that the next call blocks, so the test can
          // observe this thread park on the permit deterministically.
          foreignThreadRef.set(Thread.currentThread());
          foreignAboutToEngage.countDown();
          // Blocks here until the test thread releases the permit.
          mutex.engage(other);
          foreignEngaged.countDown();
        } catch (Throwable t) {
          foreignError.compareAndSet(null, t);
          foreignEngaged.countDown();
        }
      }, "mutex-foreign-parker");

      // While the test thread holds the permit, the foreign thread must park on it. Observe the
      // parked thread state deterministically rather than inferring parking from a negative await.
      assertTrue("the foreign worker must reach its blocking engage",
          foreignAboutToEngage.await(5, TimeUnit.SECONDS));
      var foreignThread = foreignThreadRef.get();
      assertNotNull("the foreign worker thread must have been published", foreignThread);
      var parkedState = awaitThreadParked(foreignThread, 5_000);
      assertTrue("a foreign thread must park on the held mutex, observed in thread state "
          + parkedState,
          parkedState == Thread.State.WAITING || parkedState == Thread.State.TIMED_WAITING);
      assertFalse("the foreign engage must not have completed while the permit is held",
          foreignEngaged.getCount() == 0);
    } finally {
      mutex.releaseFor(session);
    }

    assertTrue("the foreign thread must engage once the permit is released",
        foreignEngaged.await(5, TimeUnit.SECONDS));
    if (foreignError.get() != null) {
      throw new AssertionError("the foreign engage must succeed after release", foreignError.get());
    }
    // Release on the foreign thread's behalf and close its session so the @After join is clean.
    var other = foreignSession.get();
    assertNotNull("the foreign session must have been opened", other);
    mutex.releaseFor(other);
    other.activateOnCurrentThread();
    other.close();
    session.activateOnCurrentThread();
  }

  /**
   * The engage-order guard rejects an engage attempted while the current thread already holds the
   * schema write lock. Engaging from inside a shared-lock acquisition is the deadlock
   * shape the guard defends against: a second transaction would park on the mutex while holding a
       * shared write lock, freezing lock-based reads and deadlocking against the commit-side lock
       * acquisition. The guard is an always-on runtime throw (an {@link IllegalStateException}), not an
       * assert, so it survives the production default of disabled assertions. Driven by holding the schema write lock and then routing a schema write
   * through {@code ensureTxSchemaState}, which is where the engage and its order guard live.
   */
  @Test
  public void engageOrderGuardRejectsWhenSchemaLockHeld() {
    var schema = session.getSharedContext().getSchema();
    session.begin();
    schema.acquireSchemaWriteLock(session);
    try {
      session.ensureTxSchemaState();
      fail("engaging the mutex while holding the schema write lock must be rejected by the"
          + " engage-order guard");
    } catch (IllegalStateException expected) {
      assertTrue("the rejection message must explain the engage-above-schema-lock requirement",
          expected.getMessage() != null && expected.getMessage().contains("SchemaShared.lock"));
    } finally {
      schema.releaseSchemaWriteLock(session, false);
      session.rollback();
    }
  }

  /**
   * The engage-order guard rejects an engage attempted while the current thread already holds the
   * index-manager write lock. Same engage-from-inside-a-held-lock hazard as the schema-lock case but
   * for the other shared metadata lock the de-guarded index-manager paths take, so the guard must
   * reject holding either lock at engage time, and it must do so at runtime under disabled assertions.
   */
  @Test
  public void engageOrderGuardRejectsWhenIndexManagerLockHeld() throws Exception {
    var indexManager = session.getSharedContext().getIndexManager();
    // The index-manager write lock is a private field guarded by protected acquire/release methods
    // that take a transaction. Acquire it directly via reflection so the engage runs with only that
    // lock held — the faithful "engaged from inside the index-manager lock" hazard — without adding
    // a test-only acquire seam to production code.
    var lockField = indexManager.getClass().getDeclaredField("lock");
    lockField.setAccessible(true);
    var rwLock = (java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(indexManager);

    session.begin();
    rwLock.writeLock().lock();
    try {
      assertTrue("the index-manager write lock must be held for the test",
          indexManager.isWriteLockHeldByCurrentThread());
      session.ensureTxSchemaState();
      fail(
          "engaging the mutex while holding the index-manager write lock must be rejected by the"
              + " engage-order guard");
    } catch (IllegalStateException expected) {
      assertTrue(
          "the rejection message must explain the engage-above-index-manager-lock requirement",
          expected.getMessage() != null && expected.getMessage().contains("index-manager"));
    } finally {
      rwLock.writeLock().unlock();
      session.rollback();
    }

    // Sanity: with no lock held, the engage-order assert passes and the seed engages the mutex.
    session.begin();
    try {
      assertNotNull("a well-ordered engage must seed the tx-local state",
          session.ensureTxSchemaState());
      assertTrue("a well-ordered first schema write must engage the mutex",
          session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session));
    } finally {
      session.rollback();
    }
    assertFalse("the mutex must be released once the outermost frame closes",
        session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session));
  }

  /**
   * The same-thread second-session reject fires through the real production seam — a schema write
   * routed via {@code ensureTxSchemaState} — not just against bare {@code mutex.engage} calls. The
   * test thread opens a schema transaction on the outer session (engaging the mutex by creating a
   * class), then opens a second session on the same thread and attempts a schema write through it;
   * that write must throw loudly rather than self-deadlock on a permit its own thread already holds.
   * Driving the reject through {@code createClass} (not a direct {@code engage}) makes the wiring
   * load-bearing: if {@code ensureTxSchemaState} ever stopped engaging the mutex, this test fails
   * where the primitive-only reject test would not. After the outer transaction's outermost frame
   * closes, the permit must be released through the real {@code close()} teardown.
   */
  @Test
  public void sameThreadSecondSessionSchemaWriteThrowsThroughProductionPath() {
    var outer = session;
    outer.begin();
    // First schema write engages the mutex through the production seam (ensureTxSchemaState).
    outer.getMetadata().getSchema().createClass("OuterTxClass");
    assertTrue("the outer schema write must engage the mutex through the production seam",
        outer.getSharedContext().getMetadataWriteMutex().isEngagedBy(outer));

    var inner = openDatabase();
    try {
      inner.activateOnCurrentThread();
      inner.begin();
      // The inner session's first schema write reaches engage on a thread that already holds the
      // permit through the outer session — it must throw the same-thread different-session reject
      // rather than park forever on the single permit.
      var ex =
          assertThrows(
              "a same-thread second session's schema write must throw rather than self-deadlock",
              IllegalStateException.class,
              () -> inner.getMetadata().getSchema().createClass("InnerTxClass"));
      assertTrue("the reject must name the same-thread different-session cause: " + ex.getMessage(),
          ex.getMessage() != null && ex.getMessage().contains("different session"));
      inner.rollback();
    } finally {
      inner.activateOnCurrentThread();
      inner.close();
      outer.activateOnCurrentThread();
      // The outer rollback's close() must release the permit through the real teardown path.
      outer.rollback();
    }
    assertFalse("the outer rollback's close() must release the permit through the real teardown",
        outer.getSharedContext().getMetadataWriteMutex().isEngagedBy(outer));
  }

  /**
   * A failed seed releases the permit so the next schema writer is not stranded. When the first
   * schema write of a transaction engages the mutex and the subsequent tx-local copy seed throws,
   * {@code ensureTxSchemaState} releases the permit in its catch arm before rethrowing. Without that
   * release the single permit would be held forever (the custom-data marker that records "the seed
   * exists" was never written, so a same-tx retry would re-engage on the holding thread and a
   * foreign thread would park forever). This test forces the seed to throw by stubbing the committed
   * schema's {@code copyForTx} to fail, asserts the throw surfaces, then proves the concurrency
   * consequence the release prevents: a second schema transaction on another thread engages promptly
   * rather than parking on a leaked permit.
   */
  @Test
  public void seedFailureReleasesPermitSoTheNextWriterIsNotStranded() throws Exception {
    var sharedContext = session.getSharedContext();
    var mutex = sharedContext.getMetadataWriteMutex();
    var realSchema = sharedContext.getSchema();

    // Stub copyForTx to throw so the seed fails after the mutex is engaged but before the marker is
    // written — exactly the engage-then-failed-seed window the catch-arm release covers. A spy keeps
    // every other schema read delegating to the real instance, so only the seed path fails.
    var failingSchema = org.mockito.Mockito.spy(realSchema);
    org.mockito.Mockito.doThrow(new RuntimeException("forced seed failure"))
        .when(failingSchema)
        .copyForTx(org.mockito.ArgumentMatchers.any());

    var schemaField =
        com.jetbrains.youtrackdb.internal.core.db.SharedContext.class.getDeclaredField("schema");
    schemaField.setAccessible(true);
    schemaField.set(sharedContext, failingSchema);
    try {
      session.begin();
      try {
        var thrown =
            assertThrows(
                "a seed whose copyForTx fails must rethrow",
                RuntimeException.class,
                session::ensureTxSchemaState);
        assertNotNull("the rethrown seed failure must carry its cause", thrown);
        // The catch arm must have released the permit before rethrowing.
        assertFalse("a failed seed must not strand the permit on the failing session",
            mutex.isEngagedBy(session));
      } finally {
        session.rollback();
      }
    } finally {
      // Restore the real schema before exercising the next writer, so its seed succeeds.
      schemaField.set(sharedContext, realSchema);
    }

    assertFalse("after the failed seed and rollback the permit must be free",
        mutex.isEngagedBy(session));

    // The concurrency consequence: a fresh schema transaction on another thread must engage and
    // commit promptly. If the failed seed had stranded the permit, this worker would park forever on
    // engage and the bounded join below would leave it alive, failing the test.
    var workerError = new AtomicReference<Throwable>();
    var worker =
        spawn(
            () -> {
              try (var next = openDatabase()) {
                next.activateOnCurrentThread();
                next.begin();
                next.getMetadata().getSchema().createClass("AfterSeedFailure");
                next.commit();
              } catch (Throwable t) {
                workerError.compareAndSet(null, t);
              }
            },
            "post-seed-failure-writer");
    worker.join(5_000);
    assertFalse("the next schema writer must not be stranded behind a leaked permit",
        worker.isAlive());
    if (workerError.get() != null) {
      throw new AssertionError("the next schema writer must engage and commit after a failed seed",
          workerError.get());
    }
  }
}
