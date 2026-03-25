package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
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
                        // fallback for concurrent readers)
                        map.compute(
                            fileId,
                            pageIndex,
                            (fId, pIdx, cur) -> {
                              LockSupport.parkNanos(100_000); // 100µs
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
}
