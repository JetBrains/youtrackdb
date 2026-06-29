/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Commit-time reconciliation coverage: a schema-changing transaction now becomes real storage
 * structure at commit. A class created inside a transaction carries a provisional collection id
 * during the transaction; at commit the storage creates the real collection inside the commit's own
 * atomic operation, resolves the provisional id to its real id before any record serializes, and
 * promotes the tx-local schema into the committed shared instances. A rolled-back schema transaction
 * leaves no real collection. These tests assert the create/resolve/promote round trip, the rollback
 * leaves-clean property, and the positive drop.
 *
 * <p>A class created inside a transaction is not yet resolvable through the immutable schema
 * snapshot {@code session.newEntity} consults, so the record-insert tests create-and-commit the
 * class in one transaction and insert records in a later one, matching how production code reaches a
 * newly created class.
 */
public class SchemaCommitReconciliationTest extends DbTestBase {

  private SchemaShared schemaShared() {
    return session.getSharedContext().getSchema();
  }

  /**
   * Reads the RID set held by the root record's {@code "classes"} link set, the persisted membership
   * the per-class-record format relies on. Loaded inside a transaction as in production.
   */
  private Set<RID> rootClassLinks() {
    return session.computeInTx(tx -> {
      var root = session.<EntityImpl>load(schemaShared().getIdentity());
      var links = root.getLinkSet("classes");
      var rids = new HashSet<RID>();
      if (links != null) {
        for (var link : links) {
          rids.add(link.getIdentity());
        }
      }
      return rids;
    });
  }

  /**
   * A class created inside a transaction is promoted to the committed schema at commit, carrying a
   * real (non-negative) collection id: the provisional id it held during the transaction is resolved
   * to a real collection created inside the commit, and that collection exists on disk. This is the
   * create half of the metadata-first inversion.
   */
  @Test
  public void inTransactionCreateResolvesToRealCollectionAtCommit() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("CommitCreated"));

    var cls = schemaShared().getClass("CommitCreated");
    assertNotNull("the created class must be promoted to the committed schema after commit", cls);
    var collectionIds = cls.getCollectionIds();
    assertTrue("a committed class must own at least one collection", collectionIds.length > 0);
    for (var collectionId : collectionIds) {
      assertTrue(
          "no provisional id may survive commit; every collection id must be a real (>= 0) id, was "
              + collectionId,
          collectionId >= 0);
      var collectionName = session.getCollectionNameById(collectionId);
      assertNotNull(
          "the resolved real collection must exist in storage, id " + collectionId, collectionName);
    }
    // The default collection id is the class's primary real collection and must also be resolved.
    assertTrue("the default collection id must be a resolved real id",
        cls.getCollectionIds()[0] >= 0);
  }

  /**
   * The committed class and its real collection survive a durable round trip: after a reload re-reads
   * the on-disk per-class records, the class resolves to the same real collection ids, and no
   * provisional id reached durable bytes. The reload-then-reopen forces a fromStream re-parse
   * on every storage profile.
   */
  @Test
  public void committedClassAndCollectionSurviveReload() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("DurableCreated"));

    var idsBefore = schemaShared().getClass("DurableCreated").getCollectionIds();
    assertTrue("the class must own a collection before the round trip", idsBefore.length > 0);
    assertTrue("the bound link set must contain the created class record before reload",
        !rootClassLinks().isEmpty());

    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    var clsAfter = schemaShared().getClass("DurableCreated");
    assertNotNull("the created class must survive a reload", clsAfter);
    assertEquals("the class's real collection ids must survive the round trip unchanged",
        java.util.Arrays.toString(idsBefore),
        java.util.Arrays.toString(clsAfter.getCollectionIds()));
    for (var collectionId : clsAfter.getCollectionIds()) {
      assertTrue("no provisional id may survive a reload, was " + collectionId, collectionId >= 0);
      assertNotNull("the real collection must still exist after the reload",
          session.getCollectionNameById(collectionId));
    }
  }

  /**
   * Records inserted into a committed-then-reconciled class resolve to the class's real collection:
   * the class is created and committed in one transaction, then a record is inserted in a later one,
   * lands in a persistent RID inside one of the class's resolved real collections, and reads back
   * with its value. This proves the promoted class is usable for record writes and the snapshot was
   * invalidated so the new class is visible.
   */
  @Test
  public void recordInsertedIntoReconciledClassResolvesToRealCollection() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("InsertTarget"));

    var classCollectionIds = schemaShared().getClass("InsertTarget").getCollectionIds();
    var validCollectionIds = new HashSet<Integer>();
    for (var id : classCollectionIds) {
      validCollectionIds.add(id);
    }

    session.begin();
    var entity = (EntityImpl) session.newEntity("InsertTarget");
    entity.setProperty("value", 7);
    session.commit();

    var recordId = entity.getIdentity();
    assertTrue("the inserted record must hold a persistent RID after commit",
        recordId.isPersistent());
    assertTrue(
        "the inserted record must land in one of the class's resolved real collections, was "
            + recordId.getCollectionId(),
        validCollectionIds.contains(recordId.getCollectionId()));

    // Read the property inside the transaction: a record loaded through the active transaction is
    // bound only for that transaction's lifetime, so the value must be read before the tx closes.
    var reloadedValue =
        session.computeInTx(
            tx -> {
              var reloaded = session.getActiveTransaction().<EntityImpl>load(recordId);
              assertNotNull("the inserted record must be readable from its real collection",
                  reloaded);
              return reloaded.<Integer>getProperty("value");
            });
    assertEquals("the inserted record's value must round-trip", Integer.valueOf(7), reloadedValue);
  }

  /**
   * A class created inside a transaction that rolls back leaves no real collection: the metadata
   * change is discarded and storage is byte-for-byte unchanged. The provisional id never resolves to
   * a real collection, so no collection file or registry entry is created. The committed
   * schema also stays without the class.
   */
  @Test
  public void rolledBackInTransactionCreateLeavesNoCollection() {
    var collectionsBefore = new HashSet<>(session.getCollectionNames());

    session.begin();
    session.getMetadata().getSchema().createClass("RolledBackCommit");
    session.rollback();

    assertFalse("the rolled-back class must be absent from the committed schema",
        schemaShared().existsClass("RolledBackCommit"));
    assertEquals(
        "a rolled-back schema transaction must create no real collection (storage unchanged)",
        collectionsBefore, new HashSet<>(session.getCollectionNames()));
  }

  /**
   * The positive drop across a commit: a class created and committed in one transaction, then
   * dropped and committed in a second, has its real collection removed from storage and its class
   * record unlinked from the root, confirmed after a durable reload. A drop must be detected from the
   * collection-id set difference, not the changed-record set (a dropped class's record is deleted,
   * so it carries no per-property change signal).
   */
  @Test
  public void droppedClassRemovesItsCollectionAcrossCommit() {
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("ToReconcileDrop"));

    var droppedClass = schemaShared().getClass("ToReconcileDrop");
    var droppedRid = droppedClass.getRecordId();
    var droppedCollectionIds = droppedClass.getCollectionIds();
    assertTrue("the class must own a real collection before the drop",
        droppedCollectionIds.length > 0 && droppedCollectionIds[0] >= 0);
    var droppedName = session.getCollectionNameById(droppedCollectionIds[0]);
    assertNotNull("the class's real collection must exist before the drop", droppedName);
    assertTrue("the class record must be linked from the root before the drop",
        rootClassLinks().contains(droppedRid));

    session.executeInTx(tx -> session.getMetadata().getSchema().dropClass("ToReconcileDrop"));

    assertFalse("the dropped class must be gone from the committed schema after commit",
        schemaShared().existsClass("ToReconcileDrop"));
    assertFalse("the dropped class record must be unlinked from the root after commit",
        rootClassLinks().contains(droppedRid));

    schemaShared().reload(session);
    reOpen("admin", ADMIN_PASSWORD);

    assertFalse("the dropped class must not reappear after a reload",
        schemaShared().existsClass("ToReconcileDrop"));
    assertFalse("the dropped class's collection must not exist after a reload",
        session.getCollectionNames().contains(droppedName));
  }

  /**
   * A schema-carrying commit that fails after it has published the new collection into the in-memory
   * registries (but before the record apply makes the commit durable) leaves no phantom registration:
   * the failure path rolls back the atomic operation and undoes the synchronous registry publication.
   * After the failure the collection name is absent from the registry, the class is absent from the
   * committed schema, and the next successful create reuses the freed id (the allocator finds the
   * slot free again). This exercises the net-new failure-path recovery — the published-but-undone
   * branch — which no other test reaches, because the rollback test rolls back before reconciliation
   * publishes anything.
   *
   * <p>The fault is injected through the storage's in-window test hook, which fires after structure
   * is published and before the record apply (i.e. on the failure-path side, not the success-path
   * side): a fault there routes through {@code rollback} and the registry undo, which is precisely
   * the recovery code under test.
   */
  @Test
  public void failedSchemaCommitLeavesNoPhantomRegistration() {
    var storage = (AbstractStorage) session.getStorage();
    var namesBefore = new HashSet<>(session.getCollectionNames());

    // Inject a fault inside the commit window, after reconcileCollections published the new
    // collection into collections/collectionMap. The throw routes to the failure finally, which runs
    // undoReconciledCollections under the held write lock. A NeedRetryException-family fault
    // (CommandInterruptedException) is used deliberately: moveToErrorStateIfNeeded skips the
    // retry family, so the storage stays OPEN after the failure. That is the reachable divergence the
    // review describes — the storage keeps serving transactions against the in-memory registry, so a
    // phantom registration would actually be observable. A plain RuntimeException would instead poison
    // the storage (forcing a reopen that re-syncs from config) and mask the in-memory cleanliness we
    // are verifying.
    storage.setCommitWindowTestHook(
        () -> {
          throw new CommandInterruptedException(
              session.getDatabaseName(), "injected in-window commit fault");
        });
    try {
      session.begin();
      session.getMetadata().getSchema().createClass("FailAtApply");
      try {
        session.commit();
        fail("the schema commit must fail when the in-window fault hook throws");
      } catch (final RuntimeException expected) {
        // Routed through rollback + undoReconciledCollections, as intended.
      }
    } finally {
      storage.setCommitWindowTestHook(null);
    }

    // No phantom in-memory registration survives: the collection registry is unchanged and the class
    // never reached the committed schema.
    assertEquals(
        "a failed schema commit must leave the collection registry unchanged (no phantom)",
        namesBefore, new HashSet<>(session.getCollectionNames()));
    assertFalse("the failed class must not be in the committed schema",
        schemaShared().existsClass("FailAtApply"));

    // The freed slot is reusable and nothing leaked: a subsequent successful create takes real
    // collections, and the registry afterwards is exactly the baseline plus that new class's
    // collections — no phantom collection from the failed commit lingers. (A create adds more than
    // one collection, so the assertion is on the name set, not a fixed count.)
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("ReuseAfterFail"));
    var reused = schemaShared().getClass("ReuseAfterFail");
    assertNotNull("the post-failure create must succeed and be promoted", reused);
    var reusedCollectionNames = new HashSet<String>();
    for (var id : reused.getCollectionIds()) {
      assertTrue("the reused collection id must be a real (>= 0) id, was " + id, id >= 0);
      var collectionName = session.getCollectionNameById(id);
      assertNotNull("the reused real collection must exist in storage, id " + id, collectionName);
      reusedCollectionNames.add(collectionName);
    }
    var expectedAfter = new HashSet<>(namesBefore);
    expectedAfter.addAll(reusedCollectionNames);
    assertEquals(
        "after a failed commit, the registry must be exactly baseline plus the new class's "
            + "collections (no leaked phantom slot from the failed commit)",
        expectedAfter, new HashSet<>(session.getCollectionNames()));
  }

  /**
   * The drop mirror of the failed-commit registry-cleanliness guarantee: a schema-carrying commit
   * that drops a class and then fails after reconciliation must restore the dropped collection's
   * in-memory registration (the rolled-back atomic operation reverts the files, so the registry must
   * not report a collection absent that is fully present on disk and in the storage configuration).
   * The class is created and committed first, then dropped in a second commit that faults inside the
   * window after the drop has already removed the collection from the registries. After the failure
   * the dropped collection's name is back in the registry and the class is still in the committed
   * schema. This directly exercises the drop-restore arm of the failure-path undo, which the
   * create-only failed-commit test above does not reach.
   */
  @Test
  public void failedSchemaCommitWithDropRestoresDroppedRegistration() {
    var storage = (AbstractStorage) session.getStorage();
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("DropThenFail"));

    var dropped = schemaShared().getClass("DropThenFail");
    var droppedCollectionId = dropped.getCollectionIds()[0];
    var droppedCollectionName = session.getCollectionNameById(droppedCollectionId);
    assertNotNull("the class's real collection must exist before the failed drop",
        droppedCollectionName);
    var namesBeforeDrop = new HashSet<>(session.getCollectionNames());

    // Fault after reconciliation removed the collection from the registries (a retry-family fault so
    // the storage stays OPEN — see the create-case test for the rationale).
    storage.setCommitWindowTestHook(
        () -> {
          throw new CommandInterruptedException(
              session.getDatabaseName(), "injected in-window drop-commit fault");
        });
    try {
      session.begin();
      session.getMetadata().getSchema().dropClass("DropThenFail");
      try {
        session.commit();
        fail("the drop commit must fail when the in-window fault hook throws");
      } catch (final RuntimeException expected) {
        // Routed through rollback + undoReconciledCollections (drop-restore arm).
      }
    } finally {
      storage.setCommitWindowTestHook(null);
    }

    // The dropped collection's in-memory registration is restored: the registry is back to its
    // pre-drop state, and the class is still present in the committed schema (the drop never
    // committed).
    assertEquals(
        "a failed drop commit must restore the dropped collection's in-memory registration",
        namesBeforeDrop, new HashSet<>(session.getCollectionNames()));
    assertTrue("the dropped collection name must be back in the registry after the failed drop",
        session.getCollectionNames().contains(droppedCollectionName));
    assertNotNull("the dropped collection must resolve by id again after the failed drop",
        session.getCollectionNameById(droppedCollectionId));
    assertTrue("the class must still be in the committed schema after the failed drop",
        schemaShared().existsClass("DropThenFail"));
  }

  /**
   * Breadcrumb for the crash-before-commit recovery of an in-transaction schema create. The rollback
   * half (a programmatic {@code session.rollback()} leaves no collection) is covered by
   * {@link #rolledBackInTransactionCreateLeavesNoCollection}. The crash half — stop the storage hard
   * before commit becomes durable, restore, and assert the created collection and its files are
   * absent — leans on the already-verified {@code ensureFileForReplay} prerequisite and the
   * {@code LocalPaginatedStorageRestoreFromWALIT} close-copy-restore harness, which is heavier
   * integration-test machinery deferred to the integration-test layer. Kept as a self-documenting
   * placeholder so the gap is visible at the test surface.
   */
  @Test
  @Ignore("crash-before-commit recovery of a schema create: needs the "
      + "LocalPaginatedStorageRestoreFromWALIT close-copy-restore harness; deferred to the "
      + "integration-test layer")
  public void crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore() {
    // Intentionally empty: see the Javadoc breadcrumb above. The rollback half is covered by
    // rolledBackInTransactionCreateLeavesNoCollection.
  }

  /**
   * Schema-commit lock contract: a schema-carrying commit holds {@code stateLock.writeLock()} for its
   * whole duration, so a concurrent pure-data commit on a second session (which takes the read lock)
   * is serialized behind it rather than racing it. The schema commit is pinned inside its window by a
   * latch wired through the in-window test hook; while it is pinned, a second thread starts a
   * pure-data commit and is observed to block (it cannot complete because the write lock excludes its
   * read lock). Releasing the schema commit lets the data commit complete. Both commits succeed with
   * no deadlock. The {@code @Test(timeout)} is the safety net: a regression that let the schema
   * branch fall back to the read lock (losing the exclusion) or that deadlocked would trip it.
   *
   * <p>Note on shape: the data commit cannot complete <em>while</em> the write lock is held — a real
   * read/write lock excludes a reader behind a writer — so the test pins the schema commit, confirms
   * the data commit is still blocked, then releases and confirms it completes. That serialization is
   * exactly the guarantee under test; an "await the data commit while still holding the write lock"
   * shape would deadlock by construction.
   */
  @Test(timeout = 60_000)
  public void dataCommitSerializesBehindHeldSchemaWriteLock() throws Exception {
    // A @Test(timeout) body runs on a JUnit watchdog thread, not the @Before thread, so the bound
    // session must be re-activated here before any use (the session is ThreadLocal-bound).
    session.activateOnCurrentThread();
    // A pre-existing class for the pure-data commit to insert into (a create would itself be a
    // schema-carry commit; we need the read-lock branch).
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("DataTarget"));

    var storage = (AbstractStorage) session.getStorage();
    var schemaInWindow = new CountDownLatch(1);
    var releaseSchema = new CountDownLatch(1);
    var dataCommitStarted = new CountDownLatch(1);
    var dataCommitted = new CountDownLatch(1);
    var errors = new AtomicReference<Throwable>();

    // The schema transaction runs entirely on its own thread with its own session (the session is
    // ThreadLocal-bound, so the main thread must never share it). The in-window hook latches it inside
    // the held write lock until releaseSchema fires.
    var schemaSession = openDatabase();
    var schemaThread =
        new Thread(
            () -> {
              try {
                schemaSession.activateOnCurrentThread();
                schemaSession.begin();
                schemaSession.getMetadata().getSchema().createClass("SchemaWhileData");
                schemaSession.commit();
              } catch (final Throwable t) {
                errors.compareAndSet(null, t);
              } finally {
                schemaSession.activateOnCurrentThread();
                schemaSession.close();
              }
            },
            "schema-commit-thread");

    // The data thread: a pure-data commit on its own session, started once the schema commit is
    // pinned inside its window.
    var dataSession = openDatabase();
    var dataThread =
        new Thread(
            () -> {
              try {
                dataSession.activateOnCurrentThread();
                schemaInWindow.await();
                dataCommitStarted.countDown();
                dataSession.begin();
                var e = (EntityImpl) dataSession.newEntity("DataTarget");
                e.setProperty("v", 1);
                dataSession.commit();
                dataCommitted.countDown();
              } catch (final Throwable t) {
                errors.compareAndSet(null, t);
              } finally {
                dataSession.activateOnCurrentThread();
                dataSession.close();
              }
            },
            "data-commit-thread");

    // Pin the schema commit inside its window so the data commit demonstrably contends with the held
    // write lock.
    storage.setCommitWindowTestHook(
        () -> {
          schemaInWindow.countDown();
          try {
            releaseSchema.await();
          } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      schemaThread.start();
      dataThread.start();

      // Wait until the schema commit is inside its window and the data thread has started its commit.
      assertTrue("the schema commit must enter its window",
          schemaInWindow.await(30, TimeUnit.SECONDS));
      assertTrue("the data thread must start its commit",
          dataCommitStarted.await(30, TimeUnit.SECONDS));

      // The data commit must NOT have completed yet: it is blocked on the read lock behind the held
      // write lock. A short bounded wait that times out is the positive signal of exclusion.
      assertFalse("the data commit must be blocked while the schema write lock is held",
          dataCommitted.await(1, TimeUnit.SECONDS));

      // Release the schema commit; both commits must now complete with no deadlock.
      releaseSchema.countDown();
      schemaThread.join(30_000);
      assertFalse("the schema commit must finish after release", schemaThread.isAlive());
      assertTrue("the data commit must complete once the write lock is released",
          dataCommitted.await(30, TimeUnit.SECONDS));
    } finally {
      releaseSchema.countDown();
      storage.setCommitWindowTestHook(null);
      schemaThread.join(30_000);
      dataThread.join(30_000);
    }

    if (errors.get() != null) {
      throw new AssertionError("a concurrent commit failed", errors.get());
    }
    session.activateOnCurrentThread();
    assertNotNull("the schema commit must have promoted its class",
        schemaShared().getClass("SchemaWhileData"));
  }

  /**
   * Lock-ordering deadlock-freedom: a schema-carrying commit (which takes the four locks in the fixed
   * acyclic order: schema write lock, index-manager exclusive lock, storage write lock), a data-path
   * schema reload (takes the schema write lock), and an index-manager load (takes the index-manager
   * exclusive lock) run concurrently across many rounds without an interleaving deadlock. These are
   * the exact overlapping lock subsets that could deadlock if the acquisition order were not fixed.
   * The four-lock order keeps acquisition acyclic; the lock order is not runtime-assertable
   * ({@code ScalableRWLock} exposes no owner-thread query), so a timeout-bounded concurrent test is
   * the only way to verify deadlock-freedom. The {@code @Test(timeout)} is the deadlock detector: a
   * lock-order regression hangs and trips the timeout instead of hanging the whole suite.
   */
  @Test(timeout = 60_000)
  public void schemaCommitReloadAndIndexLoadRaceWithoutDeadlock() throws Exception {
    // A @Test(timeout) body runs on a JUnit watchdog thread, not the @Before thread, so the bound
    // session must be re-activated here before its racer thread re-binds it.
    session.activateOnCurrentThread();
    final var rounds = 30;
    var barrier = new CyclicBarrier(3);
    var error = new AtomicReference<Throwable>();

    var reloadSession = openDatabase();
    var indexSession = openDatabase();

    // Thread A: schema-carrying commits (create then drop a uniquely named class each round) on the
    // base session — the four-lock commit path.
    var commitThread =
        new Thread(
            () -> {
              try {
                session.activateOnCurrentThread();
                for (var i = 0; i < rounds; i++) {
                  barrier.await();
                  var cls = "Racer" + i;
                  session.executeInTx(tx -> session.getMetadata().getSchema().createClass(cls));
                  session.executeInTx(tx -> session.getMetadata().getSchema().dropClass(cls));
                }
              } catch (final Throwable t) {
                error.compareAndSet(null, t);
              }
            },
            "schema-commit-racer");

    // Thread B: schema reloads — takes the schema write lock.
    var reloadThread =
        new Thread(
            () -> {
              try {
                reloadSession.activateOnCurrentThread();
                var schema = reloadSession.getSharedContext().getSchema();
                for (var i = 0; i < rounds; i++) {
                  barrier.await();
                  schema.reload(reloadSession);
                }
              } catch (final Throwable t) {
                error.compareAndSet(null, t);
              } finally {
                reloadSession.activateOnCurrentThread();
                reloadSession.close();
              }
            },
            "schema-reload-racer");

    // Thread C: index-manager loads — takes the index-manager exclusive lock.
    var indexThread =
        new Thread(
            () -> {
              try {
                indexSession.activateOnCurrentThread();
                var indexManager = indexSession.getSharedContext().getIndexManager();
                for (var i = 0; i < rounds; i++) {
                  barrier.await();
                  indexManager.load(indexSession);
                }
              } catch (final Throwable t) {
                error.compareAndSet(null, t);
              } finally {
                indexSession.activateOnCurrentThread();
                indexSession.close();
              }
            },
            "index-load-racer");

    commitThread.start();
    reloadThread.start();
    indexThread.start();

    commitThread.join(55_000);
    reloadThread.join(55_000);
    indexThread.join(55_000);

    assertFalse("the schema-commit racer must finish (no deadlock)", commitThread.isAlive());
    assertFalse("the schema-reload racer must finish (no deadlock)", reloadThread.isAlive());
    assertFalse("the index-load racer must finish (no deadlock)", indexThread.isAlive());
    if (error.get() != null) {
      throw new AssertionError("a concurrent racer failed", error.get());
    }
  }
}
