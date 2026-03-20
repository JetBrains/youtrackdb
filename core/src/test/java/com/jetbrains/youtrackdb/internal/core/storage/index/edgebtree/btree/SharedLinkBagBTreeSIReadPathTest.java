package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
 * isolation). Uses CountDownLatch for thread coordination with timeouts.
 */
public class SharedLinkBagBTreeSIReadPathTest {

  private static final String DB_NAME = "siReadPathTest";
  private static final String DIR_NAME = "/siReadPathTest";
  private static final int TIMEOUT_SECONDS = 30;

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

  /**
   * Runs a concurrent reader/writer scenario: the reader opens a transaction
   * (capturing a snapshot), then the writer runs and commits, then the reader
   * executes its action and verifies SI behavior.
   */
  private void runConcurrentReaderWriter(
      Consumer<AtomicOperation> readerAction,
      Runnable writerAction) throws Exception {
    var readerReady = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);
    var readerError = new AtomicReference<Throwable>();

    var readerThread = new Thread(() -> {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
          readerReady.countDown();
          assertThat(writerDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
              .as("writer should complete within timeout").isTrue();
          readerAction.accept(atomicOperation);
        });
      } catch (Throwable t) {
        readerError.set(t);
      }
    });

    readerThread.start();
    assertThat(readerReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("reader should acquire snapshot within timeout").isTrue();

    writerAction.run();

    writerDone.countDown();
    readerThread.join(TIMEOUT_SECONDS * 1000L);
    assertThat(readerThread.isAlive())
        .as("reader thread should complete within timeout").isFalse();

    if (readerError.get() != null) {
      throw new AssertionError("Reader thread failed", readerError.get());
    }
  }

  private static EdgeKey allEdgesFrom(long ridBagId) {
    return new EdgeKey(ridBagId, 0, 0L, Long.MIN_VALUE);
  }

  private static EdgeKey allEdgesTo(long ridBagId) {
    return new EdgeKey(ridBagId, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
  }

  // ---- Concurrent get() SI test ----

  @Test
  public void testSnapshotIsolation_getSeesOldValueAfterConcurrentUpdate() throws Exception {
    // 1. Insert entry (counter=10) in tx1 (committed, uses tx1's commitTs)
    // 2. Reader opens tx (snapshot after tx1 committed)
    // 3. Writer updates entry (counter=99)
    // 4. Reader reads via findVisibleEntry — should see counter=10

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(100L, 10, 100L, ts),
          new LinkBagValue(10, 0, 0, false));
    });

    var readerResult = new AtomicReference<LinkBagValue>();

    runConcurrentReaderWriter(
        atomicOperation -> {
          var result = bTree.findVisibleEntry(atomicOperation, 100L, 10, 100L);
          if (result != null) {
            readerResult.set(result.second());
          }
        },
        () -> {
          try {
            atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
              long ts = atomicOperation.getCommitTs();
              bTree.put(atomicOperation, new EdgeKey(100L, 10, 100L, ts),
                  new LinkBagValue(99, 0, 0, false));
            });
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertThat(readerResult.get()).isNotNull();
    // Reader sees the OLD value (counter=10) from before the writer's update
    assertThat(readerResult.get().counter()).isEqualTo(10);

    // Verify fresh transaction sees the updated value
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 100L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.second().counter()).isEqualTo(99);
    });
  }

  // ---- Concurrent iteration SI tests ----

  @Test
  public void testSnapshotIsolation_forwardIterationConsistentDuringConcurrentWrite()
      throws Exception {
    // 1. Insert 3 entries in tx1 (committed)
    // 2. Reader opens tx (snapshot before writer)
    // 3. Writer adds 2 more entries
    // 4. Reader iterates forward — should see only the original 3 entries

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(200L, 10, 100L, ts),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(200L, 20, 200L, ts),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(200L, 30, 300L, ts),
          new LinkBagValue(3, 0, 0, false));
    });

    var readerCount = new AtomicReference<Integer>();

    runConcurrentReaderWriter(
        atomicOperation -> {
          var entries = bTree.streamEntriesBetween(
              allEdgesFrom(200L), true, allEdgesTo(200L), true,
              true, atomicOperation).toList();
          readerCount.set(entries.size());
        },
        () -> {
          try {
            atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
              long ts = atomicOperation.getCommitTs();
              bTree.put(atomicOperation, new EdgeKey(200L, 40, 400L, ts),
                  new LinkBagValue(4, 0, 0, false));
              bTree.put(atomicOperation, new EdgeKey(200L, 50, 500L, ts),
                  new LinkBagValue(5, 0, 0, false));
            });
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    // Reader sees only the original 3 entries, not the 2 added by the writer
    assertThat(readerCount.get()).isEqualTo(3);
  }

  @Test
  public void testSnapshotIsolation_backwardIterationConsistentDuringConcurrentWrite()
      throws Exception {
    // Same as forward iteration test but using descending order.

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(250L, 10, 100L, ts),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(250L, 20, 200L, ts),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(250L, 30, 300L, ts),
          new LinkBagValue(3, 0, 0, false));
    });

    var readerCount = new AtomicReference<Integer>();

    runConcurrentReaderWriter(
        atomicOperation -> {
          var entries = bTree.streamEntriesBetween(
              allEdgesFrom(250L), true, allEdgesTo(250L), true,
              false, atomicOperation).toList();
          readerCount.set(entries.size());
        },
        () -> {
          try {
            atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
              long ts = atomicOperation.getCommitTs();
              bTree.put(atomicOperation, new EdgeKey(250L, 40, 400L, ts),
                  new LinkBagValue(4, 0, 0, false));
              bTree.put(atomicOperation, new EdgeKey(250L, 50, 500L, ts),
                  new LinkBagValue(5, 0, 0, false));
            });
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertThat(readerCount.get()).isEqualTo(3);
  }

  // ---- Tombstone visibility across snapshots ----

  @Test
  public void testSnapshotIsolation_deletedEdgesStillVisibleToOlderSnapshot() throws Exception {
    // 1. Insert 3 entries in tx1 (committed)
    // 2. Reader opens tx (snapshot before writer)
    // 3. Writer removes 2 entries (creates tombstones, committed)
    // 4. Reader reads — should still see all 3 entries via snapshot index

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long ts = atomicOperation.getCommitTs();
      bTree.put(atomicOperation, new EdgeKey(300L, 10, 100L, ts),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(300L, 20, 200L, ts),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(300L, 30, 300L, ts),
          new LinkBagValue(3, 0, 0, false));
    });

    var readerCount = new AtomicReference<Integer>();

    runConcurrentReaderWriter(
        atomicOperation -> {
          var entries = bTree.streamEntriesBetween(
              allEdgesFrom(300L), true, allEdgesTo(300L), true,
              true, atomicOperation).toList();
          readerCount.set(entries.size());
        },
        () -> {
          try {
            atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
              long ts = atomicOperation.getCommitTs();
              bTree.remove(atomicOperation, new EdgeKey(300L, 10, 100L, ts));
              bTree.remove(atomicOperation, new EdgeKey(300L, 20, 200L, ts));
            });
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    // Reader still sees all 3 entries (tombstones not visible to old snapshot)
    assertThat(readerCount.get()).isEqualTo(3);

    // Verify fresh transaction sees the deletions (only 1 entry left)
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = bTree.streamEntriesBetween(
          allEdgesFrom(300L), true, allEdgesTo(300L), true,
          true, atomicOperation).toList();
      assertThat(entries).hasSize(1);
      assertThat(entries.get(0).second().counter()).isEqualTo(3);
    });
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
