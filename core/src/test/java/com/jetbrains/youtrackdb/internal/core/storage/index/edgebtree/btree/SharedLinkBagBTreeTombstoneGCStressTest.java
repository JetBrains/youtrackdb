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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Stress test for tombstone GC under concurrent contention in
 * {@link SharedLinkBagBTree}. Multiple threads concurrently perform
 * {@code put()} and {@code remove()} operations, each in separate
 * atomic operations. All threads share the same {@code ridBagId} so
 * entries co-locate in the same B-tree buckets, maximizing the chance
 * that one thread's insert triggers GC on a bucket containing another
 * thread's tombstones.
 *
 * <p>Operations serialize at the B-tree level (component exclusive lock),
 * so this tests contention safety — correctness under rapid lock
 * acquisition/release by multiple threads — not true intra-GC
 * concurrency. The test verifies that after all threads complete:
 * (1) no deadlocks or exceptions occurred, (2) tree invariants hold
 * (all expected entries are findable, no duplicates), (3) live entries
 * are never lost by GC, (4) surviving tombstones retain their state.
 *
 * <p>Uses the same static storage/atomicOperationsManager setup as
 * {@link SharedLinkBagBTreeTombstoneGCTest}.
 */
public class SharedLinkBagBTreeTombstoneGCStressTest {

  private static final String DB_NAME = "tombstoneGCStressTest";
  private static final String DIR_NAME = "/tombstoneGCStressTest";

  // Thread count — enough contention to stress lock handoff and GC
  // interleaving, but not so many that the test becomes slow.
  private static final int THREAD_COUNT = 4;

  // Operations per thread. Each thread inserts this many entries,
  // alternating between live entries and tombstones. With 4 threads
  // × 300 entries = 1200 total entries, enough to trigger many
  // bucket overflows and GC attempts.
  private static final int OPS_PER_THREAD = 300;

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
    bTree = new SharedLinkBagBTree(storage, "tombstoneGCStress", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
    storage.getSharedEdgeSnapshotIndex().clear();
    storage.getEdgeVisibilityIndex().clear();
  }

  // ---- Stress tests ----

  /**
   * Multiple threads concurrently insert live entries and tombstones into
   * the same B-tree. All threads share {@code ridBagId=1} with non-overlapping
   * position ranges, so entries from different threads co-locate in the same
   * B-tree buckets. Bucket overflows trigger GC attempts that filter tombstones
   * from any thread's entries, testing cross-thread GC correctness.
   *
   * <p>Each thread uses positions {@code [base..base+OPS_PER_THREAD*2)}, where
   * even positions are tombstones (ts=1) and odd positions are live (ts=100).
   *
   * <p>After all threads complete, verifies:
   * <ul>
   *   <li>No exceptions or deadlocks during execution</li>
   *   <li>All live entries are findable with correct ts value</li>
   *   <li>Surviving tombstones retain their tombstone state</li>
   *   <li>No live entry was incorrectly removed by GC</li>
   * </ul>
   */
  @Test
  public void testConcurrentPutWithTombstonesAndGC() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();

      for (int t = 0; t < THREAD_COUNT; t++) {
        final int threadId = t;
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }

          // All threads share ridBagId=1, with non-overlapping position ranges.
          // Even positions are tombstones (ts=1), odd positions are live (ts=100).
          // This forces entries from all threads into the same key-space region,
          // maximizing bucket co-location and cross-thread GC filtering.
          int base = threadId * OPS_PER_THREAD * 2;
          for (int i = 0; i < OPS_PER_THREAD; i++) {
            final int pos = base + i;
            final boolean isTombstone = (i % 2 == 0);
            final long ts = isTombstone ? 1L : 100L;
            try {
              atomicOperationsManager.executeInsideAtomicOperation(
                  atomicOperation -> bTree.put(atomicOperation,
                      new EdgeKey(1L, 0, pos, ts),
                      new LinkBagValue(pos, 0, 0, isTombstone)));
            } catch (Exception e) {
              throw new RuntimeException(
                  "Thread " + threadId + " failed at position " + pos, e);
            }
          }
        }));
      }

      // Release all threads simultaneously
      startLatch.countDown();

      // Wait for completion with timeout to detect deadlocks
      executor.shutdown();
      assertThat(executor.awaitTermination(120, TimeUnit.SECONDS))
          .as("Executor should terminate within timeout (deadlock detection)")
          .isTrue();

      // Propagate any thread exceptions
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    // Verify: all live entries (odd positions within each thread's range)
    // must be present with correct ts and non-tombstone state.
    for (int t = 0; t < THREAD_COUNT; t++) {
      int base = t * OPS_PER_THREAD * 2;
      for (int i = 1; i < OPS_PER_THREAD; i += 2) {
        final int pos = base + i;
        final int threadId = t;
        atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
          var entry = bTree.findCurrentEntry(atomicOperation, 1L, 0, pos);
          assertThat(entry)
              .as("Live entry at pos=%d must exist (thread %d)", pos, threadId)
              .isNotNull();
          assertThat(entry.first().ts)
              .as("Live entry at pos=%d must have ts=100 (thread %d)", pos, threadId)
              .isEqualTo(100L);
          assertThat(entry.second().tombstone())
              .as("Entry at pos=%d must be live (not tombstone)", pos)
              .isFalse();
          assertThat(entry.second().counter())
              .as("Live entry at pos=%d must have counter=%d (thread %d)",
                  pos, pos, threadId)
              .isEqualTo(pos);
        });
      }
    }

    // Verify: tombstone entries (even positions), if still present after GC,
    // must still be tombstones with correct value — GC must not flip
    // tombstone to live or corrupt value fields.
    for (int t = 0; t < THREAD_COUNT; t++) {
      int base = t * OPS_PER_THREAD * 2;
      for (int i = 0; i < OPS_PER_THREAD; i += 2) {
        final int pos = base + i;
        final int threadId = t;
        atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
          var entry = bTree.findCurrentEntry(atomicOperation, 1L, 0, pos);
          if (entry != null) {
            assertThat(entry.second().tombstone())
                .as("Surviving entry at pos=%d must still be tombstone (thread %d)",
                    pos, threadId)
                .isTrue();
            assertThat(entry.second().counter())
                .as("Surviving tombstone at pos=%d must have counter=%d (thread %d)",
                    pos, pos, threadId)
                .isEqualTo(pos);
          }
          // entry == null means GC removed it, which is acceptable
        });
      }
    }
  }

  /**
   * Threads perform interleaved put and cross-tx remove operations on a
   * shared {@code ridBagId=1}. All threads operate in an interleaved
   * position space ({@code pos = i * THREAD_COUNT + threadId}) so entries
   * from different threads co-locate in the same B-tree buckets. This
   * ensures that inserter threads' bucket overflows trigger GC on buckets
   * containing remover threads' tombstones, testing cross-thread GC
   * correctness.
   *
   * <p>Phase 1 pre-populates entries at remover-thread positions. Phase 2
   * launches concurrent threads: removers create tombstones + snapshot
   * entries via cross-tx remove, inserters add new live entries that
   * trigger GC which must respect snapshot entries.
   *
   * <p>Verifies that:
   * <ul>
   *   <li>All inserted live entries survive with correct ts and value</li>
   *   <li>All removed entries become tombstones (none remain live)</li>
   *   <li>Snapshot-protected tombstones are not all GC'd (at least some
   *       survive per remover thread)</li>
   * </ul>
   */
  @Test
  public void testConcurrentPutAndRemoveWithGC() throws Exception {
    // Phase 1: Pre-populate entries at positions belonging to remover
    // threads. All threads share ridBagId=1 with interleaved positions:
    // pos = i * THREAD_COUNT + threadId. This forces entries from all
    // threads into the same B-tree buckets.
    for (int t = 0; t < THREAD_COUNT; t++) {
      if (t % 2 != 0) {
        continue; // only pre-populate for remover threads (even threadId)
      }
      for (int i = 0; i < OPS_PER_THREAD; i++) {
        final int pos = i * THREAD_COUNT + t;
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation,
                new EdgeKey(1L, 0, pos, 1L),
                new LinkBagValue(pos, 0, 0, false)));
      }
    }

    // Phase 2: Concurrent threads — removers create tombstones + snapshots
    // at their interleaved positions, inserters add new live entries at
    // their interleaved positions. Bucket overflows from inserters trigger
    // GC that encounters remover tombstones in the same buckets.
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();

      for (int t = 0; t < THREAD_COUNT; t++) {
        final int threadId = t;
        final boolean isRemover = (threadId % 2 == 0);

        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }

          if (isRemover) {
            // Cross-tx remove of pre-populated entries (creates
            // tombstones + snapshot entries). Snapshot entries protect
            // these tombstones from GC.
            for (int i = 0; i < OPS_PER_THREAD; i++) {
              final int pos = i * THREAD_COUNT + threadId;
              try {
                atomicOperationsManager.executeInsideAtomicOperation(
                    atomicOperation -> bTree.remove(atomicOperation,
                        new EdgeKey(1L, 0, pos, 5L)));
              } catch (Exception e) {
                throw new RuntimeException(
                    "Remover thread " + threadId + " failed at pos " + pos, e);
              }
            }
          } else {
            // Insert live entries at this thread's interleaved positions —
            // these share buckets with remover entries and may trigger
            // bucket overflow + GC on tombstones from remover threads.
            for (int i = 0; i < OPS_PER_THREAD; i++) {
              final int pos = i * THREAD_COUNT + threadId;
              try {
                atomicOperationsManager.executeInsideAtomicOperation(
                    atomicOperation -> bTree.put(atomicOperation,
                        new EdgeKey(1L, 0, pos, 100L),
                        new LinkBagValue(pos, 0, 0, false)));
              } catch (Exception e) {
                throw new RuntimeException(
                    "Inserter thread " + threadId + " failed at pos " + pos, e);
              }
            }
          }
        }));
      }

      startLatch.countDown();

      executor.shutdown();
      assertThat(executor.awaitTermination(120, TimeUnit.SECONDS))
          .as("Executor should terminate within timeout")
          .isTrue();

      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    // Verify: for inserter threads (odd threadId), all live entries must
    // be present with correct ts and value fields.
    for (int t = 0; t < THREAD_COUNT; t++) {
      if (t % 2 != 0) { // inserter threads
        for (int i = 0; i < OPS_PER_THREAD; i++) {
          final int pos = i * THREAD_COUNT + t;
          final int threadId = t;
          atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
            var entry =
                bTree.findCurrentEntry(atomicOperation, 1L, 0, pos);
            assertThat(entry)
                .as("Live entry at pos=%d must exist (thread %d)", pos, threadId)
                .isNotNull();
            assertThat(entry.first().ts)
                .as("Entry at pos=%d must have ts=100", pos)
                .isEqualTo(100L);
            assertThat(entry.second().tombstone())
                .as("Entry at pos=%d must be live", pos)
                .isFalse();
            assertThat(entry.second().counter())
                .as("Entry at pos=%d must have counter=%d", pos, pos)
                .isEqualTo(pos);
          });
        }
      }
    }

    // Verify: for remover threads (even threadId), all entries must be
    // tombstones — none should remain live. Snapshot entries protect
    // tombstones from GC, so at least some must survive per thread.
    for (int t = 0; t < THREAD_COUNT; t++) {
      if (t % 2 == 0) { // remover threads
        int tombstoneCount = 0;
        for (int i = 0; i < OPS_PER_THREAD; i++) {
          final int pos = i * THREAD_COUNT + t;
          final int threadId = t;
          final boolean[] isTombstone = {false};
          atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
            var entry =
                bTree.findCurrentEntry(atomicOperation, 1L, 0, pos);
            if (entry != null) {
              // Removed entry must be a tombstone, never still live
              assertThat(entry.second().tombstone())
                  .as("Removed entry at pos=%d must be tombstone, not live (thread %d)",
                      pos, threadId)
                  .isTrue();
              isTombstone[0] = true;
            }
            // entry == null means GC removed it
          });
          if (isTombstone[0]) {
            tombstoneCount++;
          }
        }
        // Snapshot entries protect tombstones from GC — at least some
        // must survive. If all were GC'd, GC is ignoring snapshot protection.
        assertThat(tombstoneCount)
            .as("Remover thread %d: snapshot-protected tombstones must not all be GC'd", t)
            .isGreaterThan(0);
      }
    }
  }
}
