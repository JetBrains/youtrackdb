package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * Concurrent stress tests for {@link ConcurrentLongIntHashMap}.
 *
 * <p>Validates concurrency correctness under high contention: mixed operations with overlapping
 * keys, rehash during concurrent access, and optimistic-read-to-read-lock fallback. Uses raw {@link
 * java.util.concurrent.ExecutorService} + {@link Future#get(long, TimeUnit)} pattern for failure
 * detection.
 */
public class ConcurrentLongIntHashMapConcurrentTest {

  private static final int THREAD_COUNT = 8;
  private static final int OPS_PER_THREAD = 200_000;

  /**
   * Mixed concurrent operations on overlapping keys. N threads perform random
   * get/put/putIfAbsent/remove/computeIfAbsent/compute on a bounded key space (~10K keys). After
   * all threads complete, verifies that the map is internally consistent: size matches the number of
   * entries visible via forEach, every entry's value matches its key's canonical form, and every
   * entry returned by forEach is retrievable via get().
   *
   * <p>Includes a slow-compute variant where the compute lambda parks briefly to hold the write
   * lock, forcing concurrent readers through the read-lock fallback path (optimistic read fails due
   * to concurrent write lock held).
   */
  @Test
  public void mixedConcurrentOperationsAreConsistent() throws Exception {
    var map = new ConcurrentLongIntHashMap<String>(1024);
    var executor = Executors.newFixedThreadPool(THREAD_COUNT);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(THREAD_COUNT);

    // Bounded key space: 100 files x 100 pages = 10K unique keys
    int fileCount = 100;
    int pageCount = 100;

    try {
      for (int t = 0; t < THREAD_COUNT; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < OPS_PER_THREAD; i++) {
                    long fileId = rng.nextInt(fileCount);
                    int pageIndex = rng.nextInt(pageCount);
                    String value = fileId + ":" + pageIndex;
                    int op = rng.nextInt(12);

                    switch (op) {
                      case 0, 1, 2, 3 -> {
                        // ~33% reads — verify value correctness when present
                        String result = map.get(fileId, pageIndex);
                        if (result != null) {
                          assertThat(result)
                              .as("get(%d, %d) returned correct value", fileId, pageIndex)
                              .isEqualTo(value);
                        }
                      }
                      case 4, 5 -> {
                        // ~17% put
                        map.put(fileId, pageIndex, value);
                      }
                      case 6 -> {
                        // ~8% putIfAbsent
                        String result = map.putIfAbsent(fileId, pageIndex, value);
                        if (result != null) {
                          assertThat(result)
                              .as(
                                  "putIfAbsent(%d, %d) returned correct existing value",
                                  fileId, pageIndex)
                              .isEqualTo(value);
                        }
                      }
                      case 7 -> {
                        // ~8% remove
                        map.remove(fileId, pageIndex);
                      }
                      case 8 -> {
                        // ~8% conditional remove (three-arg, reference equality)
                        String current = map.get(fileId, pageIndex);
                        if (current != null) {
                          map.remove(fileId, pageIndex, current);
                        }
                      }
                      case 9 -> {
                        // ~8% computeIfAbsent
                        String result =
                            map.computeIfAbsent(
                                fileId, pageIndex, (fId, pIdx) -> fId + ":" + pIdx);
                        assertThat(result)
                            .as(
                                "computeIfAbsent(%d, %d) returned correct value",
                                fileId, pageIndex)
                            .isEqualTo(value);
                      }
                      case 10 -> {
                        // ~8% compute (fast) — toggle present/absent
                        map.compute(
                            fileId,
                            pageIndex,
                            (fId, pIdx, cur) -> cur == null ? value : null);
                      }
                      case 11 -> {
                        // ~8% compute (slow — holds write lock to force optimistic-read
                        // fallback for concurrent readers).
                        // Uses busy-wait instead of LockSupport.parkNanos because Windows
                        // timer granularity rounds parkNanos(100µs) up to ~15ms, causing
                        // the test to exceed its 60s timeout on Windows CI runners.
                        map.compute(
                            fileId,
                            pageIndex,
                            (fId, pIdx, cur) -> {
                              long deadline = System.nanoTime() + 100_000; // 100µs
                              while (System.nanoTime() < deadline) {
                                Thread.onSpinWait();
                              }
                              return value;
                            });
                      }
                      default -> throw new AssertionError("unreachable");
                    }
                  }
                  return null;
                }));
      }

      // Wait for all threads — propagates any exception
      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // Verify map internal consistency: size matches actual entry count,
    // every value matches its key's canonical form, and every entry
    // visible via forEach is retrievable via get()
    var entryCount = new AtomicLong();
    map.forEach(
        (fileId, pageIndex, value) -> {
          assertThat(value).as("value at (%d, %d) is non-null", fileId, pageIndex).isNotNull();
          assertThat(value)
              .as("value at (%d, %d) matches canonical form", fileId, pageIndex)
              .isEqualTo(fileId + ":" + pageIndex);
          assertThat(map.get(fileId, pageIndex))
              .as("get(%d, %d) matches forEach entry", fileId, pageIndex)
              .isSameAs(value);
          entryCount.incrementAndGet();
        });
    assertThat(map.size())
        .as("size() matches forEach entry count")
        .isEqualTo(entryCount.get());
  }

  /**
   * Rehash under concurrent access. Uses a 1-section map with minimal capacity (4 slots) so that
   * rehash is triggered frequently during inserts. Writer threads insert entries, reader threads
   * continuously get entries, and remover threads remove random entries — all running concurrently.
   * A startup barrier ensures all threads overlap during rehash events.
   *
   * <p>Verifies: no {@link ArrayIndexOutOfBoundsException}, no stale/corrupt values returned,
   * correct final state (every entry visible via forEach is retrievable via get and matches its
   * key's canonical form), and that rehash actually occurred (capacity grew beyond initial 4).
   */
  @Test
  public void rehashUnderConcurrentAccessIsCorrect() throws Exception {
    // Single section, capacity 4 — rehash triggers frequently
    var map = new ConcurrentLongIntHashMap<String>(4, 1);
    int totalThreads = 8; // 3 writers + 3 readers + 2 removers
    var executor = Executors.newFixedThreadPool(totalThreads);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(totalThreads);

    // Bounded key space to keep memory reasonable (~10K unique keys)
    int keyBound = 10_000;
    int opsPerThread = 100_000;

    try {
      // 3 writer threads — insert entries, causing frequent rehash
      for (int t = 0; t < 3; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int key = rng.nextInt(keyBound);
                    long fileId = key / 100;
                    int pageIndex = key % 100;
                    String value = fileId + ":" + pageIndex;
                    map.put(fileId, pageIndex, value);
                  }
                  return null;
                }));
      }

      // 3 reader threads — continuous reads; verify non-null results are valid
      for (int t = 0; t < 3; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int key = rng.nextInt(keyBound);
                    long fileId = key / 100;
                    int pageIndex = key % 100;
                    String result = map.get(fileId, pageIndex);
                    if (result != null) {
                      // Value must match the canonical form for this key
                      assertThat(result)
                          .as("get(%d, %d) returned a valid value", fileId, pageIndex)
                          .isEqualTo(fileId + ":" + pageIndex);
                    }
                  }
                  return null;
                }));
      }

      // 2 remover threads — remove random entries to keep occupancy oscillating
      for (int t = 0; t < 2; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int key = rng.nextInt(keyBound);
                    long fileId = key / 100;
                    int pageIndex = key % 100;
                    map.remove(fileId, pageIndex);
                  }
                  return null;
                }));
      }

      // Wait for all threads
      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // Verify rehash actually occurred — capacity must have grown from initial 4
    assertThat(map.capacity())
        .as("capacity should have grown via rehash from initial 4")
        .isGreaterThan(4);

    // Final consistency: size matches forEach count, all entries retrievable
    // and values match their keys' canonical form
    var entryCount = new AtomicLong();
    map.forEach(
        (fileId, pageIndex, value) -> {
          assertThat(value).isNotNull();
          assertThat(value)
              .as("value at (%d, %d) matches canonical form", fileId, pageIndex)
              .isEqualTo(fileId + ":" + pageIndex);
          assertThat(map.get(fileId, pageIndex))
              .as("get(%d, %d) after all threads done", fileId, pageIndex)
              .isSameAs(value);
          entryCount.incrementAndGet();
        });
    assertThat(map.size())
        .as("size() matches forEach entry count after rehash stress")
        .isEqualTo(entryCount.get());
  }

  /**
   * Rehash from minimum capacity (2) under concurrent access. The capacity-2 section has resize
   * threshold {@code (int)(2 * 0.66) = 1}, so the second insert triggers a rehash. The mask
   * change from 1 (0b01) to 3 (0b11) is the most extreme relative shift, maximizing the window
   * for the ARM store-reorder race where a reader could observe the new (larger) capacity before
   * the new (larger) array.
   *
   * <p>Verifies: no {@link ArrayIndexOutOfBoundsException}, correct values returned by concurrent
   * readers, and that the map grew well beyond the initial capacity.
   */
  @Test
  public void rehashFromMinimumCapacityUnderConcurrentAccessIsCorrect() throws Exception {
    // Single section, capacity 2 — rehash triggers on the second insert
    var map = new ConcurrentLongIntHashMap<String>(2, 1);
    assertThat(map.capacity()).as("initial capacity should be minimum 2").isEqualTo(2);

    int totalThreads = 6; // 3 writers + 3 readers
    var executor = Executors.newFixedThreadPool(totalThreads);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(totalThreads);
    int keyBound = 5_000;
    int opsPerThread = 50_000;

    try {
      // 3 writer threads — insert entries, causing frequent rehash from minimum capacity
      for (int t = 0; t < 3; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int key = rng.nextInt(keyBound);
                    long fileId = key / 100;
                    int pageIndex = key % 100;
                    map.put(fileId, pageIndex, fileId + ":" + pageIndex);
                  }
                  return null;
                }));
      }

      // 3 reader threads — continuous reads; verify non-null results are valid
      for (int t = 0; t < 3; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int key = rng.nextInt(keyBound);
                    long fileId = key / 100;
                    int pageIndex = key % 100;
                    String result = map.get(fileId, pageIndex);
                    if (result != null) {
                      assertThat(result)
                          .as("get(%d, %d) returned a valid value", fileId, pageIndex)
                          .isEqualTo(fileId + ":" + pageIndex);
                    }
                  }
                  return null;
                }));
      }

      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // Verify consistency and that capacity grew well beyond initial 2
    var entryCount = new AtomicLong();
    map.forEach(
        (fileId, pageIndex, value) -> {
          assertThat(value).isNotNull();
          assertThat(value)
              .as("value at (%d, %d) matches canonical form", fileId, pageIndex)
              .isEqualTo(fileId + ":" + pageIndex);
          assertThat(map.get(fileId, pageIndex))
              .as("get(%d, %d) matches forEach entry", fileId, pageIndex)
              .isSameAs(value);
          entryCount.incrementAndGet();
        });
    assertThat(map.size())
        .as("size() matches forEach entry count")
        .isEqualTo(entryCount.get());
    assertThat(entryCount.get())
        .as("map should contain entries after 150K puts with no removers into 5K key space")
        .isGreaterThan(0);
    assertThat(map.capacity())
        .as("capacity should have grown well beyond initial 2")
        .isGreaterThan(2);
  }

  /**
   * Shrink under concurrent access. Uses a single-section map that is initially grown to large
   * capacity, then one thread repeatedly calls {@code shrink()} while reader threads continuously
   * perform {@code get()}. Exercises the same {@code rehashTo()} code path as grow-rehash but with
   * {@code newCapacity < capacity}, which creates the symmetric ARM store-reorder risk (reader sees
   * new smaller array but old larger capacity). A grower thread re-inserts entries to oscillate
   * capacity up and down, maximizing rehash frequency.
   *
   * <p>Verifies: no {@link ArrayIndexOutOfBoundsException}, correct values returned by concurrent
   * readers, and final map consistency.
   */
  @Test
  public void shrinkUnderConcurrentAccessIsCorrect() throws Exception {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    // 500 total keys: entries 0-99 are always kept (readers have stable data),
    // entries 100-499 are removed/re-inserted each round to oscillate capacity.
    int keyCount = 500;
    int totalThreads = 6; // 1 shrinker + 1 grower + 4 readers
    var executor = Executors.newFixedThreadPool(totalThreads);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(totalThreads);
    int rounds = 200;

    // Pre-populate so there are entries to read during shrink cycles
    for (int i = 0; i < keyCount; i++) {
      map.put((long) i, i, i + ":" + i);
    }

    try {
      // 1 thread repeatedly removes entries then calls shrink()
      futures.add(
          executor.submit(
              () -> {
                startBarrier.await();
                for (int r = 0; r < rounds; r++) {
                  // Remove most entries to make shrink meaningful
                  for (int i = 100; i < keyCount; i++) {
                    map.remove((long) i, i);
                  }
                  map.shrink();
                  // Re-insert to grow capacity back up for next shrink
                  for (int i = 100; i < keyCount; i++) {
                    map.put((long) i, i, i + ":" + i);
                  }
                }
                return null;
              }));

      // 1 thread that triggers grow-rehash by inserting beyond current capacity
      futures.add(
          executor.submit(
              () -> {
                startBarrier.await();
                var rng = ThreadLocalRandom.current();
                // 100x more iterations than shrinker to ensure grow-rehash overlaps
                // with shrink-rehash windows
                for (int r = 0; r < rounds * 100; r++) {
                  int key = rng.nextInt(keyCount);
                  map.put((long) key, key, key + ":" + key);
                }
                return null;
              }));

      // 4 reader threads — continuous reads during shrink/grow cycles
      for (int t = 0; t < 4; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  // 500x more iterations than shrinker to maximize read coverage
                  // during both shrink-rehash and grow-rehash
                  for (int r = 0; r < rounds * 500; r++) {
                    int key = rng.nextInt(keyCount);
                    String result = map.get((long) key, key);
                    if (result != null) {
                      assertThat(result)
                          .as("get(%d, %d) returned a valid value", (long) key, key)
                          .isEqualTo(key + ":" + key);
                    }
                  }
                  return null;
                }));
      }

      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // Final consistency check
    var entryCount = new AtomicLong();
    map.forEach(
        (fileId, pageIndex, value) -> {
          assertThat(value).isNotNull();
          assertThat(value)
              .as("value at (%d, %d) matches canonical form", fileId, pageIndex)
              .isEqualTo(fileId + ":" + pageIndex);
          assertThat(map.get(fileId, pageIndex))
              .as("get(%d, %d) matches forEach entry", fileId, pageIndex)
              .isSameAs(value);
          entryCount.incrementAndGet();
        });
    assertThat(map.size())
        .as("size() matches forEach entry count after shrink stress")
        .isEqualTo(entryCount.get());
    assertThat(entryCount.get())
        .as("map should contain entries after shrink/grow oscillation")
        .isGreaterThan(0);
  }

  // ---- removeByFileId concurrent tests ----

  /**
   * removeByFileId for a target file while other threads concurrently read/write entries for
   * different file IDs. Verifies that all target-file entries are removed and entries for other
   * files are unaffected.
   *
   * <p>Setup: pre-populate the map with entries for files 0-9. One thread repeatedly calls
   * removeByFileId(targetFileId) while other threads perform get/put on files != targetFileId. After
   * completion, verify target-file entries are gone and other-file entries are intact.
   */
  @Test
  public void removeByFileIdDoesNotAffectOtherFiles() throws Exception {
    var map = new ConcurrentLongIntHashMap<String>(1024);
    long targetFileId = 5;
    int pagesPerFile = 100;
    int otherFileCount = 9; // files 0-4 and 6-9
    int rounds = 50;

    // Pre-populate all files
    for (long fId = 0; fId < 10; fId++) {
      for (int pIdx = 0; pIdx < pagesPerFile; pIdx++) {
        map.put(fId, pIdx, fId + ":" + pIdx);
      }
    }

    int totalThreads = 5; // 1 remover + 4 read/write workers on other files
    var executor = Executors.newFixedThreadPool(totalThreads);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(totalThreads);

    try {
      // Remover thread — repeatedly removes and re-populates the target file
      futures.add(
          executor.submit(
              () -> {
                startBarrier.await();
                for (int r = 0; r < rounds; r++) {
                  var removed = map.removeByFileId(targetFileId);
                  // Every returned value must belong to the target file
                  // and match the canonical fileId:pageIndex form
                  for (var val : removed) {
                    assertThat(val)
                        .as("removed value belongs to target file %d", targetFileId)
                        .matches(targetFileId + ":\\d+");
                  }
                  // Re-populate so next round has entries to remove
                  for (int pIdx = 0; pIdx < pagesPerFile; pIdx++) {
                    map.put(targetFileId, pIdx, targetFileId + ":" + pIdx);
                  }
                }
                return null;
              }));

      // 4 worker threads on other files — get/put to verify no interference
      for (int t = 0; t < 4; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < rounds * pagesPerFile; i++) {
                    // Pick a file != targetFileId
                    long fileId = rng.nextInt(otherFileCount);
                    if (fileId >= targetFileId) {
                      fileId++; // skip targetFileId
                    }
                    int pageIndex = rng.nextInt(pagesPerFile);
                    String value = fileId + ":" + pageIndex;

                    if (rng.nextBoolean()) {
                      map.put(fileId, pageIndex, value);
                    } else {
                      String result = map.get(fileId, pageIndex);
                      if (result != null) {
                        assertThat(result)
                            .as("get(%d, %d) returned correct value", fileId, pageIndex)
                            .isEqualTo(value);
                      }
                    }
                  }
                  return null;
                }));
      }

      for (var future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // Final removeByFileId to ensure target is gone
    var finalRemoved = map.removeByFileId(targetFileId);
    for (var val : finalRemoved) {
      assertThat(val)
          .as("final removal returned value belonging to target file")
          .matches(targetFileId + ":\\d+");
    }

    // Verify: no entries for the target file remain, and count other-file entries
    var otherEntryCount = new AtomicLong();
    map.forEach(
        (fileId, pageIndex, value) -> {
          assertThat(fileId)
              .as("no entries for target file %d should remain", targetFileId)
              .isNotEqualTo(targetFileId);
          assertThat(value)
              .as("value at (%d, %d) matches canonical form", fileId, pageIndex)
              .isEqualTo(fileId + ":" + pageIndex);
          otherEntryCount.incrementAndGet();
        });
    // Workers only write canonical values for other files — all 9 files x 100 pages must survive
    assertThat(otherEntryCount.get())
        .as("all other-file entries must be present")
        .isEqualTo((long) otherFileCount * pagesPerFile);
    assertThat(map.size()).isEqualTo(otherEntryCount.get());
  }

  /**
   * removeByFileId + concurrent put and reads on the same file. Removers call removeByFileId while
   * inserters add entries and readers verify value correctness — all for the same file. Uses a
   * single section to maximize intra-section contention on the write lock. After all threads
   * complete, a final removeByFileId verifies the map is empty for that file.
   *
   * <p>Readers exercise the optimistic-read-to-read-lock fallback during removeByFileId's
   * same-capacity rehash, which replaces the section's arrays under the write lock.
   */
  @Test
  public void removeByFileIdWithConcurrentPutOnSameFile() throws Exception {
    // Single section — all operations contend on one StampedLock
    var map = new ConcurrentLongIntHashMap<String>(256, 1);
    long fileId = 42;
    int pagesPerFile = 200;
    int rounds = 100;

    int totalThreads = 6; // 2 removers + 2 inserters + 2 readers
    var executor = Executors.newFixedThreadPool(totalThreads);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(totalThreads);

    try {
      // 2 remover threads — validate returned values belong to correct file
      for (int t = 0; t < 2; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  for (int r = 0; r < rounds; r++) {
                    var removed = map.removeByFileId(fileId);
                    for (var val : removed) {
                      assertThat(val)
                          .as("removed value must belong to file %d", fileId)
                          .matches(fileId + ":\\d+");
                    }
                  }
                  return null;
                }));
      }

      // 2 inserter threads
      for (int t = 0; t < 2; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  for (int r = 0; r < rounds; r++) {
                    for (int pIdx = 0; pIdx < pagesPerFile; pIdx++) {
                      map.put(fileId, pIdx, fileId + ":" + pIdx);
                    }
                  }
                  return null;
                }));
      }

      // 2 reader threads — verify get() returns correct or null during removal
      for (int t = 0; t < 2; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int r = 0; r < rounds * pagesPerFile; r++) {
                    int pIdx = rng.nextInt(pagesPerFile);
                    String result = map.get(fileId, pIdx);
                    if (result != null) {
                      assertThat(result)
                          .as("get(%d, %d) must return canonical value or null", fileId, pIdx)
                          .isEqualTo(fileId + ":" + pIdx);
                    }
                  }
                  return null;
                }));
      }

      for (var future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // After all threads stop, do a final removal and verify empty for this file.
    // Some entries may remain from the last inserter round — that's expected
    // (inserters can re-insert after the last removeByFileId call).
    map.removeByFileId(fileId);

    map.forEach(
        (fId, pageIndex, value) -> assertThat(fId)
            .as("no entries for file %d should remain after final removeByFileId", fileId)
            .isNotEqualTo(fileId));
  }

  /**
   * Multiple concurrent removeByFileId for different files. Several threads each call
   * removeByFileId for their own file ID simultaneously. Verifies each file's entries are fully
   * removed and no cross-file interference occurs.
   */
  @Test
  public void multipleConcurrentRemoveByFileIdForDifferentFiles() throws Exception {
    var map = new ConcurrentLongIntHashMap<String>(4096);
    int fileCount = 8;
    int pagesPerFile = 200;

    // Pre-populate
    for (long fId = 0; fId < fileCount; fId++) {
      for (int pIdx = 0; pIdx < pagesPerFile; pIdx++) {
        map.put(fId, pIdx, fId + ":" + pIdx);
      }
    }

    assertThat(map.size())
        .as("initial size")
        .isEqualTo((long) fileCount * pagesPerFile);

    var executor = Executors.newFixedThreadPool(fileCount);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(fileCount);

    try {
      // Each thread removes one file
      for (int t = 0; t < fileCount; t++) {
        long targetFile = t;
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var removed = map.removeByFileId(targetFile);
                  // Must return exactly pagesPerFile entries with exact content —
                  // no concurrent writes, so the set is fully deterministic
                  var expected = new ArrayList<String>(pagesPerFile);
                  for (int pIdx = 0; pIdx < pagesPerFile; pIdx++) {
                    expected.add(targetFile + ":" + pIdx);
                  }
                  assertThat(removed)
                      .as("removeByFileId(%d) should return all page values", targetFile)
                      .containsExactlyInAnyOrderElementsOf(expected);
                  return null;
                }));
      }

      for (var future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // All files removed concurrently — map should be empty
    assertThat(map.size()).as("map should be empty after all files removed").isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
  }
}
