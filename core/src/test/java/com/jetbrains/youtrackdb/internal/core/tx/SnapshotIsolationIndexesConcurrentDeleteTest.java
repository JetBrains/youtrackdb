package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * Reproduces the assert failure in index engine {@code remove()} methods:
 * {@code assert !(pair.second() instanceof TombstoneRID);}
 *
 * <p>Two threads both delete the same record. The DELETE is buffered in the
 * TX; the actual index modification happens during {@code commit()} →
 * {@code commitIndexes()}. The first commit creates a tombstone; the second
 * commit's index {@code remove()} encounters it.
 *
 * <p>This happens because {@code doDeleteRecord()} silently returns when
 * the record is already deleted ({@code ppos == null}), so no
 * ConcurrentModificationException is thrown. The index operations then
 * proceed and find the tombstone from the first commit.
 */
public class SnapshotIsolationIndexesConcurrentDeleteTest
    extends SnapshotIsolationIndexesTestBase {

  @Override
  @After
  public void tearDown() {
    db.activateOnCurrentThread();
    super.tearDown();
  }

  /**
   * Targets {@code BTreeMultiValueIndexEngine.remove()} (NOTUNIQUE index).
   * Two threads both delete the same record; the second commit's index
   * remove finds the tombstone from the first commit.
   */
  @Test(timeout = 60_000)
  public void notUnique_concurrentDeleteSameRecord_encountersTombstone()
      throws Throwable {
    db.activateOnCurrentThread();
    var clazz = db.createClass("RecNU");
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createIndex("RecNUIdx", INDEX_TYPE.NOTUNIQUE, "val");

    db.begin();
    db.newEntity("RecNU").setProperty("val", 1);
    db.commit();

    runConcurrentDelete("RecNU");

    // Post-condition: the record must be deleted after concurrent deletes
    db.begin();
    try (var rs = db.query("SELECT FROM RecNU WHERE val = 1")) {
      assertFalse("Record should be deleted after concurrent delete", rs.hasNext());
    }
    db.rollback();
  }

  /**
   * Targets {@code BTreeSingleValueIndexEngine.remove()} (UNIQUE index).
   * Same scenario: two threads both delete the same record; the second
   * commit's index remove finds the tombstone from the first commit.
   */
  @Test(timeout = 60_000)
  public void unique_concurrentDeleteSameRecord_encountersTombstone()
      throws Throwable {
    db.activateOnCurrentThread();
    var clazz = db.createClass("RecU");
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createIndex("RecUIdx", INDEX_TYPE.UNIQUE, "val");

    db.begin();
    db.newEntity("RecU").setProperty("val", 1);
    db.commit();

    runConcurrentDelete("RecU");

    // Post-condition: the record must be deleted after concurrent deletes
    db.begin();
    try (var rs = db.query("SELECT FROM RecU WHERE val = 1")) {
      assertFalse("Record should be deleted after concurrent delete", rs.hasNext());
    }
    db.rollback();
  }

  /**
   * Two threads buffer a DELETE of the same record (by class name),
   * synchronize via a barrier, then both commit. The second commit's
   * index {@code remove()} encounters the tombstone from the first.
   */
  private void runConcurrentDelete(String className) throws Throwable {
    var barrier = new CyclicBarrier(2);
    var done = new CountDownLatch(2);
    var failure = new AtomicReference<Throwable>();

    for (int i = 0; i < 2; i++) {
      new Thread(() -> {
        DatabaseSessionEmbedded s = null;
        try {
          s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          s.begin();
          s.command("DELETE FROM " + className + " WHERE val = 1 LIMIT 1");
          // Wait until both threads have buffered the delete
          barrier.await();
          // Both commit — second one's index remove() finds the tombstone
          s.commit();
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
          if (s != null) {
            try {
              s.rollback();
            } catch (Exception ignored) {
            }
          }
        } finally {
          if (s != null && !s.isClosed()) {
            s.close();
          }
          done.countDown();
        }
      }).start();
    }

    assertTrue("Threads should finish within 10s",
        done.await(10, TimeUnit.SECONDS));

    if (failure.get() != null) {
      throw failure.get();
    }
  }

  /**
   * Without the TombstoneRID guard in remove(), the second commit's
   * remove() re-tombstones an already-deleted entry and adds a corrupt
   * snapshot pair to the IndexesSnapshot. This causes a snapshot TX
   * started between the two commits to see a phantom resurrected record.
   *
   * <p>Timeline (UNIQUE index, no TombstoneRID guard):
   * <ol>
   *   <li>Insert record with val=1 (committed)</li>
   *   <li>Thread A and B both buffer DELETE WHERE val=1</li>
   *   <li>Thread A commits → tombstone in B-tree, snapshot pair added</li>
   *   <li>Snapshot TX opens → sees val=1 deleted (0 results)</li>
   *   <li>Thread B commits → re-tombstones, adds CORRUPT snapshot pair
   *       that maps (oldTombstoneKey → TombstoneRID, newTombstoneKey → rid).
   *       The visibility filter interprets newTombstoneKey → rid as a
   *       live entry, resurrecting the deleted record.</li>
   *   <li>Snapshot TX queries val=1 → sees 1 phantom record (WRONG)</li>
   * </ol>
   */
  @Test(timeout = 60_000)
  public void unique_doubleDelete_corruptsSnapshotVisibility() throws Exception {
    db.activateOnCurrentThread();
    var clazz = db.createClass("RecVis");
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createIndex("RecVisIdx", INDEX_TYPE.UNIQUE, "val");

    db.begin();
    db.newEntity("RecVis").setProperty("val", 1);
    db.commit();

    var threadACommitted = new CountDownLatch(1);
    var snapshotOpened = new CountDownLatch(1);
    var bothReady = new CyclicBarrier(2);
    var done = new CountDownLatch(2);
    var error = new AtomicReference<Throwable>();

    // Thread A: delete and commit first
    new Thread(() -> {
      DatabaseSessionEmbedded s = null;
      try {
        s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        s.begin();
        s.command("DELETE FROM RecVis WHERE val = 1 LIMIT 1");
        bothReady.await();
        s.commit();
        threadACommitted.countDown();

        // Wait for snapshot TX to open before B commits, so the
        // snapshot version sits between A's and B's commits
        snapshotOpened.await();
      } catch (Exception e) {
        error.compareAndSet(null, e);
        threadACommitted.countDown();
      } finally {
        if (s != null && !s.isClosed()) {
          s.close();
        }
        done.countDown();
      }
    }).start();

    // Thread B: buffer delete, wait for snapshot to open, then commit
    new Thread(() -> {
      DatabaseSessionEmbedded s = null;
      try {
        s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        s.begin();
        s.command("DELETE FROM RecVis WHERE val = 1 LIMIT 1");
        bothReady.await();
        threadACommitted.await();
        // Wait for snapshot TX to open between A and B commits
        snapshotOpened.await();
        s.commit();
      } catch (Throwable t) {
        error.compareAndSet(null, t);
        if (s != null) {
          try {
            s.rollback();
          } catch (Exception ignored) {
          }
        }
      } finally {
        if (s != null && !s.isClosed()) {
          s.close();
        }
        done.countDown();
      }
    }).start();

    // Main thread: wait for A to commit, open snapshot session between
    // A's and B's commits so its snapshot version sits in between
    threadACommitted.await();
    var snapshotSession = youTrackDB.open(
        "test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    snapshotOpened.countDown();

    assertTrue("Threads should finish within 10s",
        done.await(10, TimeUnit.SECONDS));

    if (error.get() instanceof AssertionError ae) {
      throw ae;
    }

    // After B commits with the corrupt double-delete: the snapshot TX
    // queries via the index. Without the TombstoneRID guard, the corrupt
    // snapshot pair causes the visibility filter to return a phantom RID
    // pointing to a physically deleted record.
    // This manifests as RecordNotFoundException or a non-zero count.
    boolean corrupted = false;
    try {
      var rs = snapshotSession.query(
          "SELECT FROM RecVis WHERE val = 1");
      int count = 0;
      while (rs.hasNext()) {
        rs.next();
        count++;
      }
      rs.close();
      corrupted = (count != 0);
    } catch (Throwable e) {
      // RecordNotFoundException (or any other error): the visibility
      // filter returned a phantom RID pointing to a physically deleted
      // record
      corrupted = true;
    } finally {
      try {
        snapshotSession.rollback();
      } catch (Throwable ignored) {
      }
      try {
        snapshotSession.close();
      } catch (Throwable ignored) {
      }
    }

    if (corrupted) {
      fail("Double-delete corrupted the index: snapshot TX sees a "
          + "phantom record that was already deleted");
    }
  }

}
