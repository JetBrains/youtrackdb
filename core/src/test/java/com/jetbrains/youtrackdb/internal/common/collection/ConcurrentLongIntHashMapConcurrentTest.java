package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Concurrent stress tests for {@link ConcurrentLongIntHashMap}.
 *
 * <p>Validates concurrency correctness under high contention: mixed operations with overlapping
 * keys, rehash during concurrent access, and optimistic-read-to-read-lock fallback. Uses raw
 * {@link ExecutorService} + {@link Future#get(long, TimeUnit)} pattern for failure detection.
 */
public class ConcurrentLongIntHashMapConcurrentTest {

  private static final int THREAD_COUNT = 8;
  private static final int OPS_PER_THREAD = 200_000;

  /**
   * Mixed concurrent operations on overlapping keys. N threads perform random
   * get/put/remove/computeIfAbsent/compute on a bounded key space (~10K keys). After all threads
   * complete, verifies that the map is internally consistent: size matches the number of entries
   * visible via forEach, and every entry returned by forEach is retrievable via get().
   *
   * <p>Includes a slow-compute variant where compute lambda sleeps briefly to force readers through
   * the read-lock fallback path (optimistic read fails due to concurrent write lock held).
   */
  @Test
  public void mixedConcurrentOperationsAreConsistent() throws Exception {
    var map = new ConcurrentLongIntHashMap<String>(1024);
    var executor = Executors.newCachedThreadPool();
    var futures = new ArrayList<Future<Void>>();
    var errors = new AtomicBoolean(false);

    // Bounded key space: 100 files x 100 pages = 10K unique keys
    int fileCount = 100;
    int pageCount = 100;

    for (int t = 0; t < THREAD_COUNT; t++) {
      futures.add(
          executor.submit(
              () -> {
                var rng = ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                  long fileId = rng.nextInt(fileCount);
                  int pageIndex = rng.nextInt(pageCount);
                  String value = fileId + ":" + pageIndex;
                  int op = rng.nextInt(10);

                  try {
                    switch (op) {
                      case 0, 1, 2, 3 -> {
                        // 40% reads
                        map.get(fileId, pageIndex);
                      }
                      case 4, 5 -> {
                        // 20% put
                        map.put(fileId, pageIndex, value);
                      }
                      case 6 -> {
                        // 10% remove
                        map.remove(fileId, pageIndex);
                      }
                      case 7 -> {
                        // 10% computeIfAbsent
                        map.computeIfAbsent(
                            fileId, pageIndex, (fId, pIdx) -> fId + ":" + pIdx);
                      }
                      case 8 -> {
                        // 10% compute (fast)
                        map.compute(
                            fileId,
                            pageIndex,
                            (fId, pIdx, cur) -> cur == null ? value : null); // toggle present/absent
                      }
                      case 9 -> {
                        // 10% compute (slow — forces optimistic-read fallback for
                        // concurrent readers)
                        map.compute(
                            fileId,
                            pageIndex,
                            (fId, pIdx, cur) -> {
                              try {
                                Thread.sleep(1);
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                              return value;
                            });
                      }
                      default -> throw new AssertionError("unreachable");
                    }
                  } catch (Exception e) {
                    errors.set(true);
                    throw new RuntimeException(
                        "Operation " + op + " failed at iteration " + i, e);
                  }
                }
                return null;
              }));
    }

    // Wait for all threads — propagates any exception
    for (var future : futures) {
      future.get(60, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertThat(errors.get())
        .as("no unexpected exceptions during concurrent operations")
        .isFalse();

    // Verify map internal consistency: size matches actual entry count,
    // and every entry visible via forEach is retrievable via get()
    var entryCount = new long[] {0};
    map.forEach(
        (fileId, pageIndex, value) -> {
          assertThat(value).as("value at (%d, %d) is non-null", fileId, pageIndex).isNotNull();
          assertThat(map.get(fileId, pageIndex))
              .as("get(%d, %d) matches forEach entry", fileId, pageIndex)
              .isSameAs(value);
          entryCount[0]++;
        });
    assertThat(map.size())
        .as("size() matches forEach entry count")
        .isEqualTo(entryCount[0]);
  }

  /**
   * Rehash under concurrent access. Uses a 1-section map with minimal capacity (4 slots) so that
   * rehash is triggered frequently during inserts. Writer threads insert entries, reader threads
   * continuously get entries, and remover threads remove random entries — all running concurrently.
   *
   * <p>Verifies: no {@link ArrayIndexOutOfBoundsException}, no stale/corrupt values returned, and
   * correct final state (every entry visible via forEach is retrievable via get).
   */
  @Test
  public void rehashUnderConcurrentAccessIsCorrect() throws Exception {
    // Single section, capacity 4 — rehash triggers frequently
    var map = new ConcurrentLongIntHashMap<String>(4, 1);
    var executor = Executors.newCachedThreadPool();
    var futures = new ArrayList<Future<Void>>();

    // Bounded key space to keep memory reasonable (~10K unique keys)
    int keyBound = 10_000;
    int opsPerThread = 100_000;

    // Track all values ever inserted — used for correctness validation of get() results
    var insertedValues = new ConcurrentHashMap<String, String>();

    // 3 writer threads — insert entries, causing frequent rehash
    for (int t = 0; t < 3; t++) {
      futures.add(
          executor.submit(
              () -> {
                var rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                  int key = rng.nextInt(keyBound);
                  long fileId = key / 100;
                  int pageIndex = key % 100;
                  String value = fileId + ":" + pageIndex;
                  insertedValues.put(value, value);
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
    executor.shutdown();

    // Final consistency: size matches forEach count, all entries retrievable
    var entryCount = new long[] {0};
    map.forEach(
        (fileId, pageIndex, value) -> {
          assertThat(value).isNotNull();
          assertThat(map.get(fileId, pageIndex))
              .as("get(%d, %d) after all threads done", fileId, pageIndex)
              .isSameAs(value);
          entryCount[0]++;
        });
    assertThat(map.size())
        .as("size() matches forEach entry count after rehash stress")
        .isEqualTo(entryCount[0]);
  }
}
