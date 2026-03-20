package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Multi-threaded SI read-path integration tests for {@link SharedLinkBagBTree}.
 * Verifies that concurrent readers and writers see consistent snapshots.
 *
 * <p>All EdgeKey entries use {@code atomicOperation.getCommitTs()} as the ts,
 * matching production semantics where IsolatedLinkBagBTreeImpl passes the real
 * commitTs. This ensures the visibility checks use actual transaction timestamps
 * rather than hardcoded values that might fall below the snapshot boundary.
 *
 * <p>Pattern: Thread A holds a transaction open while the main thread writes and
 * commits. Thread A then reads and verifies it sees the pre-write state (snapshot
 * isolation). Uses CountDownLatch for thread coordination.
 */
public class SharedLinkBagBTreeSIReadPathTest {

  private static final String DB_NAME = "siReadPathTest";
  private static final String DIR_NAME = "/siReadPathTest";

  private static YouTrackDBImpl youTrackDB;
  private static AtomicOperationsManager atomicOperationsManager;
  private static AbstractStorage storage;
  private static String buildDirectory;

  private SharedLinkBagBTree bTree;

  @BeforeClass
  public static void beforeClass() {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null) {
      buildDirectory = "./target" + DIR_NAME;
    } else {
      buildDirectory += DIR_NAME;
    }

    FileUtils.deleteRecursively(new File(buildDirectory));

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");

    var databaseSession = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = databaseSession.getStorage();
    atomicOperationsManager = storage.getAtomicOperationsManager();
    databaseSession.close();
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
    FileUtils.deleteRecursively(new File(buildDirectory));
  }

  @Before
  public void beforeMethod() throws Exception {
    bTree = new SharedLinkBagBTree(storage, "siRead", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- Concurrent get() SI test ----

  @Test
  public void testSnapshotIsolation_getSeesOldValueAfterConcurrentUpdate() throws Exception {
    // 1. Insert entry (counter=10) in tx1 (committed, uses tx1's commitTs)
    // 2. Thread A opens tx (snapshot after tx1 committed)
    // 3. Main thread updates entry (counter=99) in tx2 (uses tx2's commitTs)
    // 4. Thread A reads via findVisibleEntry — should see counter=10

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(100L, 10, 100L, ts),
          new LinkBagValue(10, 0, 0, false));
    });

    var readerReady = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);
    var readerResult = new AtomicReference<LinkBagValue>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
          // Signal: reader has snapshot
          readerReady.countDown();
          // Wait for writer to commit
          writerDone.await();

          var result = bTree.findVisibleEntry(atomicOperation, 100L, 10, 100L);
          if (result != null) {
            readerResult.set(result.second());
          }
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });

    readerThread.start();
    readerReady.await();

    // Writer: update entry with new value (uses its own commitTs)
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(100L, 10, 100L, ts),
          new LinkBagValue(99, 0, 0, false));
    });

    writerDone.countDown();
    readerThread.join();

    assertThat(readerError.get()).isNull();
    assertThat(readerResult.get()).isNotNull();
    // Reader sees the OLD value (counter=10) from before the writer's update
    assertThat(readerResult.get().counter()).isEqualTo(10);
  }

  // ---- Concurrent iteration SI test ----

  @Test
  public void testSnapshotIsolation_forwardIterationConsistentDuringConcurrentWrite()
      throws Exception {
    // 1. Insert 3 entries in tx1 (committed)
    // 2. Thread A opens a transaction (snapshot before tx2)
    // 3. Main thread adds 2 more entries in tx2 (committed)
    // 4. Thread A iterates — should see only the original 3 entries

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(200L, 10, 100L, ts),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(200L, 20, 200L, ts),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(200L, 30, 300L, ts),
          new LinkBagValue(3, 0, 0, false));
    });

    var readerReady = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);
    var readerCount = new AtomicReference<Integer>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
          readerReady.countDown();
          writerDone.await();

          var fromKey = new EdgeKey(200L, 0, 0L, Long.MIN_VALUE);
          var toKey = new EdgeKey(200L, Integer.MAX_VALUE, Long.MAX_VALUE,
              Long.MAX_VALUE);
          var entries = bTree.streamEntriesBetween(
              fromKey, true, toKey, true, true, atomicOperation).toList();
          readerCount.set(entries.size());
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });

    readerThread.start();
    readerReady.await();

    // Writer: add 2 more entries using its own commitTs
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(200L, 40, 400L, ts),
          new LinkBagValue(4, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(200L, 50, 500L, ts),
          new LinkBagValue(5, 0, 0, false));
    });

    writerDone.countDown();
    readerThread.join();

    assertThat(readerError.get()).isNull();
    // Reader sees only the original 3 entries, not the 2 added by the writer
    assertThat(readerCount.get()).isEqualTo(3);
  }

  // ---- Tombstone visibility across snapshots ----

  @Test
  public void testSnapshotIsolation_deletedEdgesStillVisibleToOlderSnapshot() throws Exception {
    // 1. Insert 3 entries in tx1 (committed)
    // 2. Thread A opens a transaction (snapshot before tx2)
    // 3. Main thread removes 2 entries in tx2 (creates tombstones, committed)
    // 4. Thread A reads — should still see all 3 entries via snapshot index

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(300L, 10, 100L, ts),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(300L, 20, 200L, ts),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(300L, 30, 300L, ts),
          new LinkBagValue(3, 0, 0, false));
    });

    var readerReady = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);
    var readerCount = new AtomicReference<Integer>();
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
          readerReady.countDown();
          writerDone.await();

          var fromKey = new EdgeKey(300L, 0, 0L, Long.MIN_VALUE);
          var toKey = new EdgeKey(300L, Integer.MAX_VALUE, Long.MAX_VALUE,
              Long.MAX_VALUE);
          var entries = bTree.streamEntriesBetween(
              fromKey, true, toKey, true, true, atomicOperation).toList();
          readerCount.set(entries.size());
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });

    readerThread.start();
    readerReady.await();

    // Writer: delete 2 entries using its own commitTs (creates tombstones)
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.remove(atomicOperation, new EdgeKey(300L, 10, 100L, ts));
      bTree.remove(atomicOperation, new EdgeKey(300L, 20, 200L, ts));
    });

    writerDone.countDown();
    readerThread.join();

    assertThat(readerError.get()).isNull();
    // Reader still sees all 3 entries (tombstones not visible to old snapshot,
    // old values available via snapshot index)
    assertThat(readerCount.get()).isEqualTo(3);
  }

  // ---- Self-read visibility ----

  @Test
  public void testSelfReadVisibility_writeFollowedByReadInSameTransaction() throws Exception {
    // Within a single transaction, a write followed by a read returns the
    // written value (self-read shortcut, not a stale snapshot version).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long commitTs = atomicOperation.getCommitTs();
      bTree.put(atomicOperation,
          new EdgeKey(400L, 10, 100L, commitTs),
          new LinkBagValue(77, 0, 0, false));

      var result = bTree.findVisibleEntry(atomicOperation, 400L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.second().counter()).isEqualTo(77);
    });
  }

  // ---- Fresh transaction sees committed state ----

  @Test
  public void testFreshTransactionSeesAllCommittedState() throws Exception {
    // After writer commits, a new transaction sees all the committed changes.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(500L, 10, 100L, ts),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(500L, 20, 200L, ts),
          new LinkBagValue(2, 0, 0, false));
    });

    // Update one entry
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(500L, 10, 100L, ts),
          new LinkBagValue(99, 0, 0, false));
    });

    // Fresh transaction: sees the updated value
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 500L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.second().counter()).isEqualTo(99);

      var result2 = bTree.findVisibleEntry(atomicOperation, 500L, 20, 200L);
      assertThat(result2).isNotNull();
      assertThat(result2.second().counter()).isEqualTo(2);
    });
  }
}
