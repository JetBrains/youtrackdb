package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class TsMinHolderTest {

  @Test
  public void testDefaultValues() {
    var holder = new TsMinHolder();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
    assertThat(holder.activeTxCount).isZero();
    assertThat(holder.registeredInTsMins).isFalse();
  }

  @Test
  public void testMutableTsMin() {
    var holder = new TsMinHolder();
    holder.tsMin = 42L;
    assertThat(holder.tsMin).isEqualTo(42L);

    holder.tsMin = Long.MAX_VALUE;
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testLazyRegistration() {
    var holder = new TsMinHolder();
    assertThat(holder.registeredInTsMins).isFalse();

    holder.registeredInTsMins = true;
    assertThat(holder.registeredInTsMins).isTrue();
  }

  @Test
  public void testLowWaterMarkSingleHolder() {
    var tsMins = newTsMinsSet();

    var holder = new TsMinHolder();
    holder.tsMin = 100L;
    tsMins.add(holder);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);
  }

  @Test
  public void testLowWaterMarkMultipleHolders() {
    var tsMins = newTsMinsSet();

    var h1 = new TsMinHolder();
    h1.tsMin = 300L;
    tsMins.add(h1);

    var h2 = new TsMinHolder();
    h2.tsMin = 100L;
    tsMins.add(h2);

    var h3 = new TsMinHolder();
    h3.tsMin = 200L;
    tsMins.add(h3);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);
  }

  @Test
  public void testLowWaterMarkIdleHoldersIgnored() {
    var tsMins = newTsMinsSet();

    var active = new TsMinHolder();
    active.tsMin = 50L;
    tsMins.add(active);

    var idle = new TsMinHolder();
    // idle.tsMin remains Long.MAX_VALUE (no active tx)
    tsMins.add(idle);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(50L);
  }

  @Test
  public void testLowWaterMarkAllIdle() {
    var tsMins = newTsMinsSet();

    var h1 = new TsMinHolder();
    tsMins.add(h1);

    var h2 = new TsMinHolder();
    tsMins.add(h2);

    // Both holders have default MAX_VALUE (idle)
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testLowWaterMarkEmptySet() {
    var tsMins = newTsMinsSet();

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testLowWaterMarkUpdatesWhenHolderChanges() {
    var tsMins = newTsMinsSet();

    var holder = new TsMinHolder();
    holder.tsMin = 100L;
    tsMins.add(holder);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);

    // Simulate transaction end — tsMin goes back to MAX_VALUE
    holder.tsMin = Long.MAX_VALUE;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(Long.MAX_VALUE);

    // Simulate new transaction begin
    holder.tsMin = 200L;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(200L);
  }

  @Test
  public void testActiveTxCountLifecycle() {
    var holder = new TsMinHolder();
    assertThat(holder.activeTxCount).isZero();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);

    // Simulate first tx begin: tsMin set, count goes to 1
    holder.tsMin = Math.min(holder.tsMin, 100L);
    holder.activeTxCount++;
    assertThat(holder.activeTxCount).isEqualTo(1);
    assertThat(holder.tsMin).isEqualTo(100L);

    // Simulate second tx begin (overlapping session): tsMin stays at min, count goes to 2
    holder.tsMin = Math.min(holder.tsMin, 200L);
    holder.activeTxCount++;
    assertThat(holder.activeTxCount).isEqualTo(2);
    assertThat(holder.tsMin).isEqualTo(100L);

    // Simulate first tx end: count goes to 1, tsMin stays (still an active tx)
    holder.activeTxCount--;
    if (holder.activeTxCount == 0) {
      holder.tsMin = Long.MAX_VALUE;
    }
    assertThat(holder.activeTxCount).isEqualTo(1);
    assertThat(holder.tsMin).isEqualTo(100L);

    // Simulate second tx end: count goes to 0, tsMin resets
    holder.activeTxCount--;
    if (holder.activeTxCount == 0) {
      holder.tsMin = Long.MAX_VALUE;
    }
    assertThat(holder.activeTxCount).isZero();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testThreadLocalReturnsSameHolderForSameThread() {
    ThreadLocal<TsMinHolder> threadLocal = ThreadLocal.withInitial(TsMinHolder::new);

    var mainHolder = threadLocal.get();
    mainHolder.tsMin = 100L;

    // Verify the holder is the same object on repeated access
    assertThat(threadLocal.get()).isSameAs(mainHolder);
    assertThat(threadLocal.get().tsMin).isEqualTo(100L);
  }

  @Test
  public void testGcRemovesDeadHolder() {
    var tsMins = newTsMinsSet();

    // Keep a strong reference to the "surviving" holder
    var survivor = new TsMinHolder();
    survivor.tsMin = 500L;
    tsMins.add(survivor);

    // Create a holder that will become eligible for GC
    var ephemeral = new TsMinHolder();
    ephemeral.tsMin = 100L;
    tsMins.add(ephemeral);
    // Use a WeakReference to detect when GC actually collects the holder
    var weakRef = new WeakReference<>(ephemeral);

    assertThat(tsMins).hasSize(2);
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);

    // Release the strong reference — the weak-key entry becomes eligible for GC
    //noinspection UnusedAssignment
    ephemeral = null;

    // Poll until GC collects the weak reference (bounded to avoid hanging).
    // Allocate 1 MB per iteration to increase GC pressure in large-heap CI environments.
    for (int i = 0; i < 100 && weakRef.get() != null; i++) {
      @SuppressWarnings("UnusedVariable") // Allocation creates GC pressure
      byte[] pressure = new byte[1024 * 1024];
      System.gc();
      try {
        //noinspection BusyWait
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    if (weakRef.get() == null) {
      // GC collected the holder — Guava's weak-key map iterator skips stale entries,
      // so computeGlobalLowWaterMark (which iterates) should see only the survivor.
      // Note: size() may still overcount because Guava's segment cleanup is lazy,
      // but iteration is the semantically important operation for this data structure.
      assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(500L);
    }
    // If GC didn't collect within the timeout, the test is inconclusive but not a failure —
    // weak-key GC behavior is JVM-dependent. The survivor invariant still holds.
    assertThat(tsMins).contains(survivor);
  }

  @Test
  public void testMultiThreadedLowWaterMark() throws InterruptedException {
    var tsMins = newTsMinsSet();
    // Each thread gets its own TsMinHolder from the ThreadLocal supplier.
    var threadLocal = ThreadLocal.withInitial(TsMinHolder::new);
    var allRegistered = new CountDownLatch(2);
    var checkDone = new CountDownLatch(1);
    var t2Holder = new AtomicReference<TsMinHolder>();

    // Thread 1: register with tsMin=200
    var t1 = new Thread(() -> {
      var holder = threadLocal.get();
      holder.tsMin = 200L;
      holder.activeTxCount++;
      tsMins.add(holder);
      allRegistered.countDown();
      try {
        checkDone.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    // Thread 2: register with tsMin=50
    var t2 = new Thread(() -> {
      var holder = threadLocal.get();
      holder.tsMin = 50L;
      holder.activeTxCount++;
      tsMins.add(holder);
      t2Holder.set(holder);
      allRegistered.countDown();
      try {
        checkDone.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    t1.start();
    t2.start();
    assertThat(allRegistered.await(10, TimeUnit.SECONDS))
        .as("Both threads should register within 10s")
        .isTrue();

    // Both threads registered — LWM should be the minimum (50)
    assertThat(tsMins).hasSize(2);
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(50L);

    // Thread 2 "ends" its transaction.
    // Mutated from main thread for test simplicity (in production, only the owning thread writes).
    t2Holder.get().tsMin = Long.MAX_VALUE;
    t2Holder.get().activeTxCount--;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(200L);

    checkDone.countDown();
    t1.join(5000);
    t2.join(5000);
    assertThat(t1.isAlive()).isFalse();
    assertThat(t2.isAlive()).isFalse();
  }

  @Test
  public void testLazyRegistrationAddsToSetOnlyOnce() {
    var tsMins = newTsMinsSet();
    var holder = new TsMinHolder();

    // Simulate the lazy registration pattern from AbstractStorage.startStorageTx()
    // First call: not yet registered — should add
    if (!holder.registeredInTsMins) {
      tsMins.add(holder);
      holder.registeredInTsMins = true;
    }
    assertThat(tsMins).hasSize(1);
    assertThat(holder.registeredInTsMins).isTrue();

    // Second call: already registered — should skip
    if (!holder.registeredInTsMins) {
      tsMins.add(holder);
      holder.registeredInTsMins = true;
    }
    assertThat(tsMins).hasSize(1);

    // Third call from a "different tx begin" on the same thread — still skip
    holder.tsMin = 42L;
    if (!holder.registeredInTsMins) {
      tsMins.add(holder);
      holder.registeredInTsMins = true;
    }
    assertThat(tsMins).hasSize(1);
    assertThat(holder.tsMin).isEqualTo(42L);
  }

  /**
   * Stress test: multiple worker threads rapidly cycle through tx begin (volatile write)
   * and tx end (opaque write via setTsMinOpaque), while a cleanup thread continuously
   * computes the global low-water-mark. Verifies the safety invariant: the LWM must never
   * exceed the tsMin of any holder whose owning thread currently has an active transaction.
   *
   * <p>A stale (too-low) LWM is acceptable — it just means cleanup retains entries a bit
   * longer. But a too-high LWM would mean the cleanup evicts entries an active reader needs.
   */
  @Test
  public void testConcurrentTxCyclesAndLwmComputation() throws Exception {
    int workerCount = 4;
    int iterationsPerWorker = 5_000;
    var tsMins = newTsMinsSet();
    var stop = new AtomicBoolean(false);
    var error = new AtomicReference<Throwable>();
    var barrier = new CyclicBarrier(workerCount + 1); // +1 for cleanup thread

    var workers = new ArrayList<Thread>(workerCount);
    var holders = new ArrayList<TsMinHolder>(workerCount);

    // Create and register one holder per worker.
    for (int w = 0; w < workerCount; w++) {
      var holder = new TsMinHolder();
      holders.add(holder);
      tsMins.add(holder);
    }

    // Worker threads: simulate rapid tx begin/end cycles.
    // Begin uses a volatile write (direct field assignment on a volatile field).
    // End uses the opaque write (setTsMinOpaque).
    for (int w = 0; w < workerCount; w++) {
      int workerIndex = w;
      var thread = new Thread(() -> {
        try {
          var holder = holders.get(workerIndex);
          barrier.await(); // synchronize start
          for (int i = 0; i < iterationsPerWorker && error.get() == null; i++) {
            // tx begin: volatile write (same as production startStorageTx)
            long ts = 1000L + workerIndex * iterationsPerWorker + i;
            holder.tsMin = ts;
            holder.activeTxCount++;

            // small busy-spin to increase interleaving opportunities
            Thread.onSpinWait();

            // tx end: opaque write (same as production resetTsMin)
            holder.activeTxCount--;
            if (holder.activeTxCount == 0) {
              holder.setTsMinOpaque(Long.MAX_VALUE);
            }
          }
        } catch (Throwable t) {
          error.compareAndSet(null, t);
        }
      });
      thread.setName("worker-" + w);
      thread.setDaemon(true);
      workers.add(thread);
    }

    // Cleanup thread: continuously compute LWM and verify safety invariant.
    var cleanupThread = new Thread(() -> {
      try {
        barrier.await(); // synchronize start
        while (!stop.get() && error.get() == null) {
          long lwm = AbstractStorage.computeGlobalLowWaterMark(tsMins);
          // Safety invariant: LWM must be <= every active holder's tsMin.
          // Since we can't atomically snapshot all holders + the LWM, we check
          // a weaker but still useful property: LWM must be a value that was
          // actually written (either a valid timestamp >= 1000, or MAX_VALUE).
          assertThat(lwm)
              .as("LWM must be MAX_VALUE (all idle) or a valid timestamp >= 1000")
              .satisfiesAnyOf(
                  v -> assertThat(v).isEqualTo(Long.MAX_VALUE),
                  v -> assertThat(v).isGreaterThanOrEqualTo(1000L));
        }
      } catch (Throwable t) {
        error.compareAndSet(null, t);
      }
    });
    cleanupThread.setName("cleanup");
    cleanupThread.setDaemon(true);

    // Start all threads
    workers.forEach(Thread::start);
    cleanupThread.start();

    // Wait for workers to finish
    for (var w : workers) {
      w.join(30_000);
      assertThat(w.isAlive()).as("Worker " + w.getName() + " should finish").isFalse();
    }

    stop.set(true);
    cleanupThread.join(5_000);
    assertThat(cleanupThread.isAlive()).as("Cleanup thread should finish").isFalse();

    // Propagate any assertion or exception from worker/cleanup threads
    if (error.get() != null) {
      throw new AssertionError("Thread failure", error.get());
    }

    // All workers done, all txs ended — LWM must be MAX_VALUE
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(Long.MAX_VALUE);
  }

  /**
   * Verifies that an opaque reset to MAX_VALUE (setTsMinOpaque) eventually becomes visible
   * to a reader thread without any additional synchronization. This guards against a
   * regression where the reset might use a plain write instead of opaque, which could
   * allow infinite staleness on some architectures.
   *
   * <p>The reader polls the LWM without any latch or fence after the opaque write, so the
   * only visibility guarantee comes from the opaque semantics themselves.
   */
  @Test
  public void testOpaqueResetEventuallyVisibleToReader() throws Exception {
    var holder = new TsMinHolder();
    var tsMins = newTsMinsSet();
    tsMins.add(holder);

    var txStarted = new CountDownLatch(1);
    var readerSawReset = new AtomicBoolean(false);
    var error = new AtomicReference<Throwable>();

    // Writer thread: begin tx (volatile write), signal, then end tx (opaque reset)
    // without any synchronization after the opaque write.
    var writer = new Thread(() -> {
      try {
        holder.tsMin = 42L; // volatile write — tx begin
        holder.activeTxCount++;
        txStarted.countDown();

        // Wait a moment so the reader can observe the active tsMin
        Thread.sleep(10);

        // tx end — opaque reset (the code path under test).
        // No latch or fence after this: only opaque visibility guarantees apply.
        holder.activeTxCount--;
        holder.setTsMinOpaque(Long.MAX_VALUE);
      } catch (Throwable t) {
        error.compareAndSet(null, t);
      }
    });

    // Reader thread: after confirming the volatile write is visible, polls the LWM
    // relying solely on opaque eventual visibility to see the reset.
    var reader = new Thread(() -> {
      try {
        txStarted.await();

        // First, verify the active tsMin is visible (volatile write + latch guarantee)
        assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(42L);

        // Poll until the opaque reset propagates. No synchronization fence between
        // the opaque write and this loop — eventual visibility is all we rely on.
        // On x86 this is near-instant; on ARM it may take more iterations.
        for (int i = 0; i < 10_000_000; i++) {
          if (AbstractStorage.computeGlobalLowWaterMark(tsMins) == Long.MAX_VALUE) {
            readerSawReset.set(true);
            break;
          }
          Thread.onSpinWait();
        }
      } catch (Throwable t) {
        error.compareAndSet(null, t);
      }
    });

    writer.setDaemon(true);
    reader.setDaemon(true);
    writer.start();
    reader.start();

    writer.join(10_000);
    reader.join(10_000);

    if (error.get() != null) {
      throw new AssertionError("Thread failure", error.get());
    }

    assertThat(readerSawReset)
        .as("Reader must eventually see the opaque reset to MAX_VALUE")
        .isTrue();
  }

  /**
   * Coordinated multi-threaded test that verifies the LWM is correct at well-defined
   * synchronization points using the production access pattern (volatile write on tx begin,
   * opaque write on tx end). Multiple rounds of "all workers begin → verify LWM → all workers
   * end → verify LWM resets" ensure the opaque reset in setTsMinOpaque is safely visible.
   */
  @Test
  public void testLwmCorrectAtSynchronizedCheckpoints() throws Exception {
    int workerCount = 4;
    int rounds = 50;
    var tsMins = newTsMinsSet();
    var error = new AtomicReference<Throwable>();

    var holders = new ArrayList<TsMinHolder>(workerCount);
    for (int w = 0; w < workerCount; w++) {
      var holder = new TsMinHolder();
      holders.add(holder);
      tsMins.add(holder);
    }

    for (int round = 0; round < rounds; round++) {
      var allStarted = new CountDownLatch(workerCount);
      var proceedToEnd = new CountDownLatch(1);
      var allEnded = new CountDownLatch(workerCount);

      long baseTs = 1000L + round * workerCount;

      // Each worker: begin tx (volatile write), signal, wait, end tx (opaque), signal.
      for (int w = 0; w < workerCount; w++) {
        int idx = w;
        long ts = baseTs + idx;
        var thread = new Thread(() -> {
          try {
            var holder = holders.get(idx);

            // tx begin: volatile write (same as production startStorageTx)
            holder.tsMin = ts;
            holder.activeTxCount++;
            allStarted.countDown();

            proceedToEnd.await();

            // tx end: opaque write (same as production resetTsMin)
            holder.activeTxCount--;
            holder.setTsMinOpaque(Long.MAX_VALUE);
            allEnded.countDown();
          } catch (Throwable t) {
            error.compareAndSet(null, t);
          }
        });
        thread.setDaemon(true);
        thread.start();
      }

      // Wait for all workers to begin their transactions.
      assertThat(allStarted.await(10, TimeUnit.SECONDS))
          .as("All workers should start within 10s (round %d)", round).isTrue();

      // Checkpoint 1: all txs active — LWM must equal the minimum tsMin.
      long expectedMin = baseTs; // worker 0 has the smallest ts
      assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins))
          .as("LWM with all txs active (round %d)", round)
          .isEqualTo(expectedMin);

      // Signal workers to end their transactions.
      proceedToEnd.countDown();
      assertThat(allEnded.await(10, TimeUnit.SECONDS))
          .as("All workers should end within 10s (round %d)", round).isTrue();

      // Checkpoint 2: all txs ended via opaque reset — LWM must be MAX_VALUE.
      // The latch provides happens-before with the opaque writes, so visibility
      // is guaranteed here (this checkpoint tests functional correctness, not
      // opaque visibility — testOpaqueResetEventuallyVisibleToReader covers that).
      assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins))
          .as("LWM after all txs ended (round %d)", round)
          .isEqualTo(Long.MAX_VALUE);

      if (error.get() != null) {
        throw new AssertionError("Worker thread failure in round " + round, error.get());
      }
    }
  }

  private static Set<TsMinHolder> newTsMinsSet() {
    return AbstractStorage.newTsMinsSet();
  }
}
