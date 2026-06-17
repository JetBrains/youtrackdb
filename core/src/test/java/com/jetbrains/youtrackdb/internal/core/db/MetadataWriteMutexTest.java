package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
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

    // Give the second writer time to reach (and block on) the mutex. While the first still holds it,
    // the second must not have created its class.
    Thread.sleep(500);
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
    // blocking, not by aborting. Committed-schema visibility of the two classes is the commit-time
    // promotion, which is a later track, so it is intentionally not asserted here.
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

    mutex.engage(session);
    try {
      spawn(() -> {
        var other = openDatabase();
        foreignSession.set(other);
        try {
          other.activateOnCurrentThread();
          // Blocks here until the test thread releases the permit.
          mutex.engage(other);
          foreignEngaged.countDown();
        } catch (Throwable t) {
          foreignError.compareAndSet(null, t);
          foreignEngaged.countDown();
        }
      }, "mutex-foreign-parker");

      // While the test thread holds the permit, the foreign thread must not have engaged.
      assertFalse("a foreign thread must park on the held mutex",
          foreignEngaged.await(500, TimeUnit.MILLISECONDS));
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
   * The engage-order assertion fires when the mutex is engaged while the current thread already
   * holds the schema write lock. Engaging from inside a shared-lock acquisition is the deadlock
   * shape the assert defends against — a second transaction would park on the mutex while holding a
   * shared write lock, freezing lock-based reads and deadlocking against the commit-side lock
   * acquisition — so the assert makes it fail loudly in tests. Driven by holding the schema write
   * lock and then routing a schema write through {@code ensureTxSchemaState}, which is where the
   * engage (and its order assert) lives.
   */
  @Test
  public void engageOrderAssertFiresWhenSchemaLockHeld() {
    var schema = session.getSharedContext().getSchema();
    session.begin();
    schema.acquireSchemaWriteLock(session);
    try {
      session.ensureTxSchemaState();
      fail("engaging the mutex while holding the schema write lock must trip the engage-order"
          + " assertion");
    } catch (AssertionError expected) {
      assertTrue("the assertion message must explain the engage-above-schema-lock requirement",
          expected.getMessage() != null && expected.getMessage().contains("SchemaShared.lock"));
    } finally {
      schema.releaseSchemaWriteLock(session, false);
      session.rollback();
    }
  }

  /**
   * The engage-order assertion fires when the mutex is engaged while the current thread already
   * holds the index-manager write lock. Same engage-from-inside-a-held-lock hazard as the
   * schema-lock case but for the other shared metadata lock the de-guarded index-manager paths take,
   * so the assert must guard against holding either lock at engage time.
   */
  @Test
  public void engageOrderAssertFiresWhenIndexManagerLockHeld() throws Exception {
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
          "engaging the mutex while holding the index-manager write lock must trip the engage-order"
              + " assertion");
    } catch (AssertionError expected) {
      assertTrue(
          "the assertion message must explain the engage-above-index-manager-lock requirement",
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
}
