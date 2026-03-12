/*
 * Copyright 2010-2025 OrientDB LTD (http://orientdb.com)
 * Copyright 2010-2025 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.storage.cache.ebr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Stress tests for {@link EpochTable} — Group A stress tests (A23–A27).
 *
 * <p>These tests exercise concurrent enter/exit, growth, slot reclamation,
 * and virtual thread scenarios under load. They are designed to run on a
 * dedicated Hetzner CCX33 node (8 vCPU, 32 GB RAM, AMD EPYC) for
 * reproducible results free from noisy-neighbor interference.
 *
 * <p>Named with {@code IT} suffix so Maven failsafe picks them up during
 * {@code verify -P ci-integration-tests} and surefire skips them during
 * {@code test}.
 */
public class EpochTableStressIT {

  private static final long TEST_TIMEOUT_MS = 30_000L;

  /**
   * A23. Concurrent enter/exit — epoch safety invariant.
   *
   * <p>16 threads each perform 100K iterations of: enter → read
   * currentEpoch → verify slot epoch <= currentEpoch → exit. One
   * dedicated thread advances the epoch every 100μs. No assertion
   * failures allowed. Validates that computeSafeEpoch never returns a
   * value higher than the minimum active slot.
   */
  @Test
  public void testConcurrentEnterExitEpochSafetyInvariant() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int numThreads = 16;
    int iterationsPerThread = 100_000;
    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicReference<Throwable> error = new AtomicReference<>();
    CyclicBarrier startBarrier = new CyclicBarrier(numThreads + 1);

    // Worker threads: enter, verify, exit loop.
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      Thread worker = new Thread(() -> {
        try {
          startBarrier.await();
          for (int i = 0; i < iterationsPerThread; i++) {
            table.enter();
            try {
              long currentEpoch = table.currentEpoch();
              int slotIndex = table.getSlotIndex();
              long slotValue = table.getSlotValue(slotIndex);
              // The slot must hold an epoch <= currentEpoch.
              // It could be less if the epoch advanced after enter().
              if (slotValue > currentEpoch) {
                throw new AssertionError(
                    "Slot " + slotIndex + " holds epoch " + slotValue
                        + " > currentEpoch " + currentEpoch);
              }
            } finally {
              table.exit();
            }
          }
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      workers.add(worker);
      worker.start();
    }

    // Epoch advancer thread: advances every 100μs until workers finish.
    Thread advancer = new Thread(() -> {
      try {
        startBarrier.await();
        while (!stop.get()) {
          table.advanceEpoch();
          // Sleep ~100μs — parkNanos is close enough for a stress test.
          //noinspection BusyWait
          Thread.sleep(0, 100_000);
        }
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    advancer.start();

    // Wait for all workers to complete.
    for (Thread w : workers) {
      w.join(60_000);
      assertThat(w.isAlive())
          .as("Worker thread should finish within timeout")
          .isFalse();
    }
    stop.set(true);
    advancer.join(5_000);
    assertThat(advancer.isAlive()).isFalse();
    assertThat(error.get()).isNull();

    // After all workers exit, computeSafeEpoch should equal currentEpoch - 1.
    assertThat(table.computeSafeEpoch())
        .isEqualTo(table.currentEpoch() - 1);
  }

  /**
   * A24. Concurrent enter/exit with growth.
   *
   * <p>Start with initial capacity 4. Spawn 64 threads, each doing
   * enter/exit in a tight loop (10K iterations). Verify: no
   * ArrayIndexOutOfBoundsException, computeSafeEpoch always returns a
   * valid value, and all threads complete without error.
   */
  @Test
  public void testConcurrentEnterExitWithGrowth() throws Exception {
    // Small initial capacity to force many grow() calls.
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS, 4);
    int numThreads = 64;
    int iterationsPerThread = 10_000;
    AtomicReference<Throwable> error = new AtomicReference<>();
    CyclicBarrier startBarrier = new CyclicBarrier(numThreads);

    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < numThreads; t++) {
      Thread worker = new Thread(() -> {
        try {
          startBarrier.await();
          for (int i = 0; i < iterationsPerThread; i++) {
            table.enter();
            // Minimal work inside critical section.
            Thread.onSpinWait();
            table.exit();
          }
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      threads.add(worker);
      worker.start();
    }

    for (Thread thread : threads) {
      thread.join(60_000);
      assertThat(thread.isAlive())
          .as("Worker thread should finish within timeout")
          .isFalse();
    }
    assertThat(error.get()).isNull();

    // computeSafeEpoch must return a valid value.
    long safeEpoch = table.computeSafeEpoch();
    assertThat(safeEpoch).isEqualTo(table.currentEpoch() - 1);

    // The array must have grown well beyond the initial capacity of 4.
    assertThat(table.getSlotCount()).isGreaterThanOrEqualTo(numThreads);
  }

  /**
   * A25. Virtual thread slot exhaustion stress.
   *
   * <p>Spawn 10K virtual threads in waves of 200, each doing: enter →
   * short sleep (1ms) → exit. Between waves, GC is requested and the
   * reclamation thread scans for dead-thread slots, recycling them back
   * to the free list. After all virtual threads complete, verify:
   * (a) the slot array did NOT grow to 10K entries (reclamation kept
   * it bounded), (b) the final slot count is bounded by the peak
   * concurrency (not total thread count), (c) no double-free (each
   * slot index appears at most once in the free list).
   */
  @Test
  public void testVirtualThreadSlotExhaustionStress() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int totalVirtualThreads = 10_000;
    int waveSize = 200;
    AtomicBoolean allDone = new AtomicBoolean(false);
    AtomicReference<Throwable> error = new AtomicReference<>();

    // Reclamation thread: calls computeSafeEpoch() in a tight loop to
    // trigger scan-based slot reclamation for dead virtual threads.
    Thread reclaimer = new Thread(() -> {
      try {
        while (!allDone.get()) {
          table.computeSafeEpoch();
          // Yield to avoid starving virtual threads on limited cores.
          Thread.yield();
        }
        // Final scan after all virtual threads complete.
        table.computeSafeEpoch();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    reclaimer.start();

    // Track the peak slot count observed across all waves.
    int peakSlotCount = 0;

    // Submit virtual threads in waves. Each wave creates waveSize
    // threads, waits for them to complete, then requests GC so
    // WeakReferences to dead threads are cleared before the next wave.
    // This simulates realistic virtual thread churn where threads come
    // and go over time, giving reclamation a chance to recycle slots.
    for (int wave = 0; wave < totalVirtualThreads / waveSize; wave++) {
      try (ExecutorService executor =
          Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < waveSize; i++) {
          futures.add(executor.submit(() -> {
            try {
              table.enter();
              Thread.sleep(1);
              table.exit();
            } catch (Throwable ex) {
              error.compareAndSet(null, ex);
            }
          }));
        }

        for (Future<?> f : futures) {
          f.get(60, TimeUnit.SECONDS);
        }
      }

      peakSlotCount = Math.max(peakSlotCount, table.getSlotCount());

      // Request GC to clear WeakReferences to dead virtual threads.
      // The reclamation thread will detect cleared refs on its next
      // computeSafeEpoch() scan and return slots to the free list.
      System.gc();
      // Short pause to give GC and the reclaimer time to process.
      Thread.sleep(50);
    }

    allDone.set(true);
    reclaimer.join(5_000);
    assertThat(reclaimer.isAlive()).isFalse();
    assertThat(error.get()).isNull();

    // (a) The slot array did NOT grow to 10K entries. With waves of 200
    // and active reclamation between waves, slot count should be a
    // small multiple of waveSize, well below totalVirtualThreads.
    int finalSlotCount = table.getSlotCount();
    assertThat(finalSlotCount)
        .as("Slot count should be much less than total virtual threads " +
            "(reclamation should keep it bounded)")
        .isLessThan(totalVirtualThreads);

    // (b) Peak slot count should be bounded by peak concurrency (waveSize),
    // not total thread count. Allow generous margin for timing variability
    // and GC lag in clearing WeakReferences.
    assertThat(peakSlotCount)
        .as("Peak slot count should be bounded by wave size, " +
            "not total thread count")
        .isLessThan(waveSize * 10);

    // Run a few more reclamation scans to clean up any stragglers.
    System.gc();
    Thread.sleep(200);
    for (int i = 0; i < 10; i++) {
      table.computeSafeEpoch();
    }

    // (c) No double-free: each slot index in the free list is unique.
    List<Integer> freeListSnapshot = table.getFreeListSnapshot();
    Set<Integer> unique = new HashSet<>(freeListSnapshot);
    assertThat(unique.size())
        .as("Free list should contain no duplicate slot indices")
        .isEqualTo(freeListSnapshot.size());
  }

  /**
   * A26. Concurrent grow + enter + computeSafeEpoch.
   *
   * <p>Three thread groups running concurrently for 5 seconds:
   * <ul>
   *   <li>Group 1 (8 threads): enter → short work → exit loop</li>
   *   <li>Group 2 (1 thread): forces grow repeatedly by allocating
   *       new slot indices beyond current capacity</li>
   *   <li>Group 3 (2 threads): calls computeSafeEpoch in a loop</li>
   * </ul>
   * Verify: no exceptions, computeSafeEpoch never returns a value that
   * would allow reclaiming an entry visible to an active thread.
   * Checked via AtomicLong array tracking each thread's entered epoch.
   */
  @Test
  public void testConcurrentGrowEnterComputeSafeEpoch() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS, 4);
    long durationMs = 5_000;
    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicReference<Throwable> error = new AtomicReference<>();

    int numGroup1 = 8;
    // Each Group 1 thread publishes its entered epoch (or MAX_VALUE when
    // outside the critical section) so the verifier can check the invariant.
    AtomicLong[] threadEpochs = new AtomicLong[numGroup1];
    for (int i = 0; i < numGroup1; i++) {
      threadEpochs[i] = new AtomicLong(Long.MAX_VALUE);
    }

    CyclicBarrier startBarrier = new CyclicBarrier(numGroup1 + 3);
    List<Thread> allThreads = new ArrayList<>();

    // Group 1: enter/exit loop with epoch tracking.
    for (int t = 0; t < numGroup1; t++) {
      final int threadId = t;
      Thread worker = new Thread(() -> {
        try {
          startBarrier.await();
          while (!stop.get()) {
            table.enter();
            try {
              long epoch = table.currentEpoch();
              threadEpochs[threadId].set(epoch);
              // Short work inside critical section.
              Thread.onSpinWait();
            } finally {
              threadEpochs[threadId].set(Long.MAX_VALUE);
              table.exit();
            }
          }
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      allThreads.add(worker);
      worker.start();
    }

    // Group 2: force grow() by spawning short-lived threads that allocate
    // new slots, forcing the array to grow beyond initial capacity.
    Thread grower = new Thread(() -> {
      try {
        startBarrier.await();
        while (!stop.get()) {
          // Spawn a batch of threads to force slot allocation and growth.
          List<Thread> batch = new ArrayList<>();
          for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
              table.enter();
              Thread.onSpinWait();
              table.exit();
            });
            batch.add(t);
            t.start();
          }
          for (Thread t : batch) {
            t.join(5_000);
          }
          // Release their slots to allow re-growth pressure.
          Thread.sleep(10);
        }
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    allThreads.add(grower);
    grower.start();

    // Group 3: computeSafeEpoch verifiers.
    for (int v = 0; v < 2; v++) {
      Thread verifier = new Thread(() -> {
        try {
          startBarrier.await();
          while (!stop.get()) {
            long safeEpoch = table.computeSafeEpoch();
            long currentEpoch = table.currentEpoch();

            // safeEpoch must be <= currentEpoch - 1 (upper bound).
            if (safeEpoch > currentEpoch - 1) {
              throw new AssertionError(
                  "safeEpoch " + safeEpoch + " > currentEpoch - 1 ("
                      + (currentEpoch - 1) + ")");
            }
            // safeEpoch must be >= -1 (lower bound at epoch 0).
            if (safeEpoch < -1) {
              throw new AssertionError(
                  "safeEpoch " + safeEpoch + " < -1");
            }

            // Snapshot the min entered epoch across Group 1 threads.
            // This is a best-effort check: threads may enter/exit between
            // the safeEpoch computation and this snapshot, but the check
            // can only produce false negatives (not false positives) when
            // the read order is: computeSafeEpoch first, then read mins.
            // If a thread was active during the scan, safeEpoch < its epoch.
            // If it exited after, the snapshot sees MAX_VALUE, which is fine.
            long minEntered = Long.MAX_VALUE;
            for (AtomicLong ae : threadEpochs) {
              minEntered = Math.min(minEntered, ae.get());
            }
            if (minEntered != Long.MAX_VALUE && safeEpoch >= minEntered) {
              // This could be a false positive due to the TOCTOU race:
              // the thread may have exited between computeSafeEpoch and
              // our read of threadEpochs. Only fail if safeEpoch is
              // strictly greater, indicating a real violation.
              // Re-read to confirm — if the thread exited, its entry will
              // be MAX_VALUE now, making this a benign race.
              long minEnteredRecheck = Long.MAX_VALUE;
              for (AtomicLong ae : threadEpochs) {
                minEnteredRecheck = Math.min(minEnteredRecheck, ae.get());
              }
              if (minEnteredRecheck != Long.MAX_VALUE
                  && safeEpoch >= minEnteredRecheck) {
                throw new AssertionError(
                    "safeEpoch " + safeEpoch + " >= minEnteredEpoch "
                        + minEnteredRecheck
                        + " — would allow reclaiming visible entry");
              }
            }

            Thread.onSpinWait();
          }
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      allThreads.add(verifier);
      verifier.start();
    }

    // Let the test run for the specified duration.
    Thread.sleep(durationMs);
    stop.set(true);

    for (Thread t : allThreads) {
      t.join(10_000);
      assertThat(t.isAlive())
          .as("Thread should finish within timeout")
          .isFalse();
    }
    assertThat(error.get()).isNull();

    // After all threads exit, computeSafeEpoch = currentEpoch - 1.
    assertThat(table.computeSafeEpoch())
        .isEqualTo(table.currentEpoch() - 1);
  }

  /**
   * A27. Concurrent releaseSlot + reclaimSlot + slot reuse.
   *
   * <p>Spawn 100 threads that each: allocate a slot, enter, exit, then
   * die. Concurrently, a reclamation thread calls computeSafeEpoch
   * repeatedly (triggering scan-based reclamation). Additionally, 50
   * new threads start and reuse slots from the free list. After all
   * threads complete, verify: no duplicate slot indices in the free
   * list, no IllegalStateException from slot operations, total
   * allocated slots <= peak concurrency + free list size.
   */
  @Test
  public void testConcurrentReleaseSlotReclaimSlotReuse() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int phase1Threads = 100;
    int phase2Threads = 50;
    AtomicBoolean phase1Done = new AtomicBoolean(false);
    AtomicBoolean allDone = new AtomicBoolean(false);
    AtomicReference<Throwable> error = new AtomicReference<>();

    // Reclamation thread: runs computeSafeEpoch() continuously to trigger
    // scan-based slot reclamation for dead threads.
    Thread reclaimer = new Thread(() -> {
      try {
        while (!allDone.get()) {
          table.computeSafeEpoch();
          Thread.yield();
        }
        // Final reclamation passes.
        for (int i = 0; i < 10; i++) {
          table.computeSafeEpoch();
        }
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    reclaimer.start();

    // Phase 1: 100 threads allocate a slot, enter, exit, then die.
    // Each thread runs on its own Thread object so it can be GC'd.
    List<Thread> phase1Workers = new ArrayList<>();
    for (int i = 0; i < phase1Threads; i++) {
      Thread t = new Thread(() -> {
        try {
          table.enter();
          Thread.onSpinWait();
          table.exit();
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      phase1Workers.add(t);
      t.start();
    }

    // Wait for all phase 1 threads to complete.
    for (Thread t : phase1Workers) {
      t.join(10_000);
      assertThat(t.isAlive()).isFalse();
    }
    phase1Done.set(true);
    assertThat(error.get()).isNull();

    // Help GC collect dead threads so WeakReferences clear.
    // Clear local references to phase 1 threads.
    phase1Workers.clear();
    System.gc();
    Thread.sleep(200);

    // Run a few reclamation scans to return dead-thread slots to the free list.
    for (int i = 0; i < 20; i++) {
      table.computeSafeEpoch();
    }

    int slotCountBeforePhase2 = table.getSlotCount();
    int nextSlotIndexBeforePhase2 = table.getNextSlotIndex();

    // Phase 2: 50 new threads reuse slots from the free list.
    List<Thread> phase2Workers = new ArrayList<>();
    for (int i = 0; i < phase2Threads; i++) {
      Thread t = new Thread(() -> {
        try {
          table.enter();
          Thread.onSpinWait();
          table.exit();
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      phase2Workers.add(t);
      t.start();
    }

    for (Thread t : phase2Workers) {
      t.join(10_000);
      assertThat(t.isAlive()).isFalse();
    }
    assertThat(error.get()).isNull();

    allDone.set(true);
    reclaimer.join(5_000);
    assertThat(reclaimer.isAlive()).isFalse();
    assertThat(error.get()).isNull();

    // Help GC and run final reclamation.
    phase2Workers.clear();
    System.gc();
    Thread.sleep(200);
    for (int i = 0; i < 20; i++) {
      table.computeSafeEpoch();
    }

    // Verify: no duplicate slot indices in the free list.
    List<Integer> freeListSnapshot = table.getFreeListSnapshot();
    Set<Integer> unique = new HashSet<>(freeListSnapshot);
    assertThat(unique.size())
        .as("Free list should contain no duplicate slot indices")
        .isEqualTo(freeListSnapshot.size());

    // Verify: phase 2 threads predominantly reused free-list slots rather
    // than allocating fresh ones. newAllocations < phase2Threads means at
    // least some reuse occurred. This is a meaningful check: if reclamation
    // were broken, all phase 2 threads would allocate fresh indices.
    int newAllocations = table.getNextSlotIndex() - nextSlotIndexBeforePhase2;
    assertThat(newAllocations)
        .as("Phase 2 should reuse free-list slots, not allocate all fresh "
            + "(newAllocations=%d, phase2Threads=%d)",
            newAllocations, phase2Threads)
        .isLessThan(phase2Threads);

    // Verify: slot count did not grow during phase 2 (reused existing slots).
    // Allow some growth due to concurrent slot allocation before reclamation
    // returns slots to the free list.
    assertThat(table.getSlotCount())
        .as("Phase 2 should mostly reuse slots, not cause unbounded growth")
        .isLessThanOrEqualTo(slotCountBeforePhase2 * 2);

    // Verify: no IllegalStateException (covered by error.get() == null above).
    // After all threads exit, safeEpoch = currentEpoch - 1.
    assertThat(table.computeSafeEpoch())
        .isEqualTo(table.currentEpoch() - 1);
  }
}
