package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
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
 * Stress test for tombstone GC under concurrent contention in the index
 * {@link BTree}. Multiple threads concurrently perform {@code put()}
 * operations, each in separate atomic operations. All threads share the
 * same key prefix so entries co-locate in the same B-tree buckets,
 * maximizing the chance that one thread's insert triggers GC on a bucket
 * containing another thread's tombstones.
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
 * {@link BTreeTombstoneGCTest}.
 */
public class BTreeTombstoneGCStressTest {

  private static final String DB_NAME = "btreeIndexTombstoneGCStressTest";
  private static final String DIR_NAME = "/btreeIndexTombstoneGCStressTest";

  // Thread count — enough contention to stress lock handoff and GC
  // interleaving, but not so many that the test becomes slow.
  private static final int THREAD_COUNT = 4;

  // Operations per thread. Each thread inserts this many entries,
  // alternating between live entries and tombstones. With 4 threads
  // x 300 entries = 1200 total entries, enough to trigger many
  // bucket overflows and GC attempts.
  private static final int OPS_PER_THREAD = 300;

  private static final int STUB_ENGINE_ID = 99;
  private static final String ENGINE_NAME = "tombstoneGCStressIdx";

  private static YouTrackDBImpl youTrackDB;
  private static AtomicOperationsManager atomicOperationsManager;
  private static AbstractStorage storage;
  private static String buildDirectory;

  private BTree<CompositeKey> bTree;

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
    bTree = new BTree<>(ENGINE_NAME, ".cbt", ".nbt", storage);
    bTree.setEngineId(STUB_ENGINE_ID);
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(
            atomicOperation,
            new IndexMultiValuKeySerializer(),
            new PropertyTypeInternal[] {
                PropertyTypeInternal.STRING,
                PropertyTypeInternal.LONG},
            2));

    BTreeGCTestSupport.registerStubEngine(storage, ENGINE_NAME, STUB_ENGINE_ID);
  }

  @After
  public void afterMethod() throws Exception {
    var snapshot = storage.getIndexSnapshotByEngineName(ENGINE_NAME);
    if (snapshot != null) {
      snapshot.clear();
    }

    BTreeGCTestSupport.unregisterStubEngine(storage, ENGINE_NAME);
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- Stress tests ----

  /**
   * Multiple threads concurrently insert live entries and tombstones into
   * the same B-tree. All threads share a common key prefix with non-overlapping
   * key-position ranges, so entries from different threads co-locate in the same
   * B-tree buckets. Bucket overflows trigger GC attempts that filter tombstones
   * from any thread's entries, testing cross-thread GC correctness.
   *
   * <p>Each thread uses keys {@code "key" + formatted(base+i)} where even
   * indices are tombstones (version=1, below default LWM) and odd indices are
   * live entries (version=100). The base offset ensures non-overlapping key
   * spaces across threads while the shared prefix ensures bucket co-location.
   *
   * <p>After all threads complete, verifies:
   * <ul>
   *   <li>No exceptions or deadlocks during execution</li>
   *   <li>All live entries are findable with correct RID values</li>
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

          // All threads share key prefix "key" with non-overlapping position
          // ranges. Even positions are tombstones (version=1), odd positions
          // are live entries (version=100). This forces entries from all
          // threads into the same key-space region, maximizing bucket
          // co-location and cross-thread GC filtering.
          int base = threadId * OPS_PER_THREAD * 2;
          for (int i = 0; i < OPS_PER_THREAD; i++) {
            final int pos = base + i;
            final boolean isTombstone = (i % 2 == 0);
            final long version = isTombstone ? 1L : 100L;
            final String keyStr = "key" + String.format("%06d", pos);
            try {
              if (isTombstone) {
                final var key = new CompositeKey(keyStr, version);
                final var value = new TombstoneRID(new RecordId(1, pos));
                atomicOperationsManager.executeInsideAtomicOperation(
                    op -> bTree.put(op, key, value));
              } else {
                final var key = new CompositeKey(keyStr, version);
                final var value = new RecordId(2, pos);
                atomicOperationsManager.executeInsideAtomicOperation(
                    op -> bTree.put(op, key, value));
              }
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
    // must be present with correct RID values.
    for (int t = 0; t < THREAD_COUNT; t++) {
      int base = t * OPS_PER_THREAD * 2;
      for (int i = 1; i < OPS_PER_THREAD; i += 2) {
        final int pos = base + i;
        final int threadId = t;
        final String keyStr = "key" + String.format("%06d", pos);
        final var key = new CompositeKey(keyStr, 100L);
        final RID[] result = {null};
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> result[0] = bTree.get(key, op));
        assertThat(result[0])
            .as("Live entry at pos=%d must exist (thread %d)", pos, threadId)
            .isNotNull();
        assertThat(result[0])
            .as("Live entry at pos=%d must be a plain RecordId (thread %d)",
                pos, threadId)
            .isInstanceOf(RecordId.class)
            .isNotInstanceOf(TombstoneRID.class);
        assertThat(result[0].getCollectionId())
            .as("Live entry at pos=%d must have clusterId=2 (thread %d)",
                pos, threadId)
            .isEqualTo(2);
        assertThat(result[0].getCollectionPosition())
            .as("Live entry at pos=%d must have clusterPosition=%d (thread %d)",
                pos, pos, threadId)
            .isEqualTo(pos);
      }
    }

    // Verify: tombstone entries (even positions), if still present after GC,
    // must still be tombstones — GC must not flip tombstone to live or
    // corrupt value fields.
    for (int t = 0; t < THREAD_COUNT; t++) {
      int base = t * OPS_PER_THREAD * 2;
      for (int i = 0; i < OPS_PER_THREAD; i += 2) {
        final int pos = base + i;
        final int threadId = t;
        final String keyStr = "key" + String.format("%06d", pos);
        final var key = new CompositeKey(keyStr, 1L);
        final RID[] result = {null};
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> result[0] = bTree.get(key, op));
        if (result[0] != null) {
          assertThat(result[0])
              .as("Surviving entry at pos=%d must still be tombstone (thread %d)",
                  pos, threadId)
              .isInstanceOf(TombstoneRID.class);
        }
        // result == null means GC removed it, which is acceptable
      }
    }
  }

  /**
   * Threads perform interleaved put and "remove" operations on the index
   * B-tree. Half the threads insert tombstones (simulating cross-tx
   * deletion at the engine level), while the other half insert live entries
   * that trigger bucket overflow and GC. All threads use an interleaved
   * key space ({@code keyIndex = i * THREAD_COUNT + threadId}) so entries
   * from different threads co-locate in the same B-tree buckets.
   *
   * <p>Phase 1 pre-populates entries at "remover" thread positions.
   * Phase 2 launches concurrent threads: removers overwrite existing live
   * entries with TombstoneRID (simulating cross-tx delete at the engine
   * level), inserters add new live entries that trigger GC.
   *
   * <p>Verifies that:
   * <ul>
   *   <li>All inserted live entries survive with correct values</li>
   *   <li>All "removed" entries become tombstones (none remain live)</li>
   *   <li>No exceptions or deadlocks during execution</li>
   * </ul>
   */
  @Test
  public void testConcurrentPutAndRemoveWithGC() throws Exception {
    // Phase 1: Pre-populate entries at positions belonging to remover
    // threads. All threads share an interleaved key space:
    // keyIndex = i * THREAD_COUNT + threadId.
    for (int t = 0; t < THREAD_COUNT; t++) {
      if (t % 2 != 0) {
        continue; // only pre-populate for remover threads (even threadId)
      }
      for (int i = 0; i < OPS_PER_THREAD; i++) {
        final int keyIndex = i * THREAD_COUNT + t;
        final String keyStr = "key" + String.format("%06d", keyIndex);
        final var key = new CompositeKey(keyStr, 1L);
        final var value = new RecordId(1, keyIndex);
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> bTree.put(op, key, value));
      }
    }

    // Phase 2: Concurrent threads — removers overwrite pre-populated entries
    // with TombstoneRID (simulating cross-tx deletion), inserters add new
    // live entries at their interleaved positions. Bucket overflows from
    // inserters trigger GC that encounters remover tombstones.
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
            // Overwrite pre-populated entries with TombstoneRID. In the
            // real system, cross-tx deletion creates a new version entry
            // with TombstoneRID; here we simulate by putting a new key
            // at a higher version with TombstoneRID value.
            for (int i = 0; i < OPS_PER_THREAD; i++) {
              final int keyIndex = i * THREAD_COUNT + threadId;
              final String keyStr =
                  "key" + String.format("%06d", keyIndex);
              try {
                final var key = new CompositeKey(keyStr, 5L);
                final var value =
                    new TombstoneRID(new RecordId(1, keyIndex));
                atomicOperationsManager.executeInsideAtomicOperation(
                    op -> bTree.put(op, key, value));
              } catch (Exception e) {
                throw new RuntimeException(
                    "Remover thread " + threadId
                        + " failed at keyIndex " + keyIndex,
                    e);
              }
            }
          } else {
            // Insert live entries at this thread's interleaved positions —
            // these share buckets with remover entries and may trigger
            // bucket overflow + GC on tombstones from remover threads.
            for (int i = 0; i < OPS_PER_THREAD; i++) {
              final int keyIndex = i * THREAD_COUNT + threadId;
              final String keyStr =
                  "key" + String.format("%06d", keyIndex);
              try {
                final var key = new CompositeKey(keyStr, 100L);
                final var value = new RecordId(2, keyIndex);
                atomicOperationsManager.executeInsideAtomicOperation(
                    op -> bTree.put(op, key, value));
              } catch (Exception e) {
                throw new RuntimeException(
                    "Inserter thread " + threadId
                        + " failed at keyIndex " + keyIndex,
                    e);
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
    // be present with correct values.
    for (int t = 0; t < THREAD_COUNT; t++) {
      if (t % 2 != 0) { // inserter threads
        for (int i = 0; i < OPS_PER_THREAD; i++) {
          final int keyIndex = i * THREAD_COUNT + t;
          final int threadId = t;
          final String keyStr = "key" + String.format("%06d", keyIndex);
          final var key = new CompositeKey(keyStr, 100L);
          final RID[] result = {null};
          atomicOperationsManager.executeInsideAtomicOperation(
              op -> result[0] = bTree.get(key, op));
          assertThat(result[0])
              .as("Live entry at keyIndex=%d must exist (thread %d)",
                  keyIndex, threadId)
              .isNotNull();
          assertThat(result[0])
              .as("Live entry at keyIndex=%d must be RecordId (thread %d)",
                  keyIndex, threadId)
              .isInstanceOf(RecordId.class)
              .isNotInstanceOf(TombstoneRID.class);
          assertThat(result[0].getCollectionId())
              .as("Live entry at keyIndex=%d must have clusterId=2 (thread %d)",
                  keyIndex, threadId)
              .isEqualTo(2);
          assertThat(result[0].getCollectionPosition())
              .as("Live entry at keyIndex=%d must have position=%d (thread %d)",
                  keyIndex, keyIndex, threadId)
              .isEqualTo(keyIndex);
        }
      }
    }

    // Verify: for remover threads (even threadId), the tombstone entries
    // at version=5 must either still exist as TombstoneRID (if not yet
    // GC'd) or be null (if GC removed them). They must never appear as
    // live RecordId — that would mean GC corrupted the tombstone state.
    for (int t = 0; t < THREAD_COUNT; t++) {
      if (t % 2 == 0) { // remover threads
        for (int i = 0; i < OPS_PER_THREAD; i++) {
          final int keyIndex = i * THREAD_COUNT + t;
          final int threadId = t;
          final String keyStr = "key" + String.format("%06d", keyIndex);
          final var key = new CompositeKey(keyStr, 5L);
          final RID[] result = {null};
          atomicOperationsManager.executeInsideAtomicOperation(
              op -> result[0] = bTree.get(key, op));
          if (result[0] != null) {
            assertThat(result[0])
                .as("Removed entry at keyIndex=%d must be TombstoneRID "
                    + "(thread %d)",
                    keyIndex, threadId)
                .isInstanceOf(TombstoneRID.class);
          }
          // result == null means GC removed it, which is acceptable
        }
      }
    }

    // Verify: tree size is consistent — reported size matches actual count
    long[] reportedSize = {0};
    atomicOperationsManager.executeInsideAtomicOperation(
        op -> reportedSize[0] = bTree.size(op));

    long[] actualCount = {0};
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      var firstKey = bTree.firstKey(op);
      if (firstKey == null) {
        return;
      }
      var lastKey = bTree.lastKey(op);
      try (var stream = bTree.iterateEntriesBetween(
          firstKey, true, lastKey, true, true, op)) {
        actualCount[0] = stream.count();
      }
    });

    assertThat(reportedSize[0])
        .as("Reported tree size must match actual entry count after "
            + "concurrent GC")
        .isEqualTo(actualCount[0]);
  }

  /**
   * Reader threads concurrently call {@code bTree.get()} while writer threads
   * trigger bucket overflow and GC. The B-tree's optimistic read path
   * ({@code getOptimistic}) relies on sequence counters to detect concurrent
   * modifications and falls back to pinned reads. GC introduces a new kind
   * of bucket restructuring (rebuild via {@code filterAndRebuildBucket})
   * that must be correctly detected by the optimistic validation.
   *
   * <p>Phase 1 pre-populates the tree with a mix of live entries and
   * tombstones. Phase 2 launches concurrent writers (adding new live entries
   * to trigger GC during overflow) and readers (continuously looking up
   * pre-populated live entries). All live entries must remain readable
   * throughout the concurrent phase.
   */
  @Test
  public void testConcurrentReadDuringGCTriggeredByWrites() throws Exception {
    // Phase 1: Pre-populate with a mix of live entries and tombstones.
    // Every 3rd entry is a tombstone (version=1, below LWM), the rest
    // are live (version=100).
    int prePopCount = 400;
    for (int i = 0; i < prePopCount; i++) {
      final int pos = i;
      final boolean isTombstone = (i % 3 == 0);
      final long version = isTombstone ? 1L : 100L;
      final var key = new CompositeKey(
          "key" + String.format("%06d", pos), version);
      final RID value = isTombstone
          ? new TombstoneRID(new RecordId(1, pos))
          : new RecordId(2, pos);
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, key, value));
    }

    // Phase 2: concurrent writers + readers
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();

      int writerCount = THREAD_COUNT / 2;
      int readerCount = THREAD_COUNT - writerCount;

      // Writer threads: insert new entries to trigger bucket overflow + GC
      for (int t = 0; t < writerCount; t++) {
        final int threadId = t;
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }

          for (int i = 0; i < OPS_PER_THREAD; i++) {
            final int pos = 1000 + threadId * OPS_PER_THREAD + i;
            final var key = new CompositeKey(
                "key" + String.format("%06d", pos), 100L);
            final var value = new RecordId(3, pos);
            try {
              atomicOperationsManager.executeInsideAtomicOperation(
                  op -> bTree.put(op, key, value));
            } catch (Exception e) {
              throw new RuntimeException(
                  "Writer thread " + threadId + " failed at pos " + pos, e);
            }
          }
        }));
      }

      // Reader threads: continuously read pre-populated live entries.
      // Live entries are at non-tombstone positions (i % 3 != 0).
      for (int t = 0; t < readerCount; t++) {
        final int threadId = writerCount + t;
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }

          for (int round = 0; round < OPS_PER_THREAD; round++) {
            // Pick a known live entry (position where i % 3 != 0).
            // 133 * 3 + 1 = 400 = prePopCount, so all computed positions
            // fall within the pre-populated range. The *3 + 1 offset
            // skips tombstone positions (i % 3 == 0).
            int pos = (round % 133) * 3 + 1;
            final var key = new CompositeKey(
                "key" + String.format("%06d", pos), 100L);
            final RID[] result = {null};
            try {
              atomicOperationsManager.executeInsideAtomicOperation(
                  op -> result[0] = bTree.get(key, op));
            } catch (Exception e) {
              throw new RuntimeException(
                  "Reader thread " + threadId + " failed at pos " + pos, e);
            }
            assertThat(result[0])
                .as("Live entry pos=%d must be readable during concurrent "
                    + "GC (reader thread %d, round %d)",
                    pos, threadId, round)
                .isNotNull()
                .isInstanceOf(RecordId.class);
            assertThat(result[0].getCollectionId())
                .as("Live entry pos=%d must have collectionId=2 "
                    + "(reader thread %d, round %d)",
                    pos, threadId, round)
                .isEqualTo(2);
            assertThat(result[0].getCollectionPosition())
                .as("Live entry pos=%d must have collectionPosition=%d "
                    + "(reader thread %d, round %d)",
                    pos, pos, threadId, round)
                .isEqualTo(pos);
          }
        }));
      }

      startLatch.countDown();

      executor.shutdown();
      assertThat(executor.awaitTermination(120, TimeUnit.SECONDS))
          .as("Executor should terminate within timeout (deadlock detection)")
          .isTrue();

      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }
  }

}
