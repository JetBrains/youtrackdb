package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  public void testLookupVarHandleThrowsOnInvalidField() {
    // Exercises the error path (catch + throw) in lookupVarHandle, which is unreachable
    // during normal class initialization because the "tsMin" field always exists.
    assertThatThrownBy(
        () -> TsMinHolder.lookupVarHandle(TsMinHolder.class, "nonExistent", long.class))
        .isInstanceOf(ExceptionInInitializerError.class)
        .hasCauseInstanceOf(NoSuchFieldException.class);
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

    // Active holder's tsMin (100) is less than the fallback (500), so LWM = 100.
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 500L))
        .isEqualTo(100L);
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

    // Minimum across active holders is 100, regardless of fallback.
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 500L))
        .isEqualTo(100L);
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

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 500L))
        .isEqualTo(50L);
  }

  @Test
  public void testLowWaterMarkActiveTsMinWinsOverLowerFallback() {
    // In production, currentId >= tsMin always holds (idGen is monotonic and tsMin
    // is derived from a snapshot taken after getLastId()). If currentId were somehow
    // lower, the active tsMin still wins because min(tsMin) < Long.MAX_VALUE causes
    // the fallback branch to be skipped.
    var tsMins = newTsMinsSet();

    var holder = new TsMinHolder();
    holder.tsMin = 100L;
    tsMins.add(holder);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 50L))
        .isEqualTo(100L);
  }

  @Test
  public void testLowWaterMarkAllIdleFallsBackToCurrentId() {
    var tsMins = newTsMinsSet();

    var h1 = new TsMinHolder();
    tsMins.add(h1);

    var h2 = new TsMinHolder();
    tsMins.add(h2);

    // Both holders are idle (tsMin == MAX_VALUE). The LWM falls back to
    // the currentId parameter instead of returning Long.MAX_VALUE.
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 42L))
        .isEqualTo(42L);
  }

  @Test
  public void testLowWaterMarkEmptySetFallsBackToCurrentId() {
    var tsMins = newTsMinsSet();

    // No holders registered. The LWM falls back to currentId.
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 99L))
        .isEqualTo(99L);
  }

  @Test
  public void testLowWaterMarkUpdatesWhenHolderChanges() {
    var tsMins = newTsMinsSet();

    var holder = new TsMinHolder();
    holder.tsMin = 100L;
    tsMins.add(holder);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 500L))
        .isEqualTo(100L);

    // Simulate transaction end — tsMin goes back to MAX_VALUE.
    // With no active transactions, LWM falls back to currentId (500).
    holder.tsMin = Long.MAX_VALUE;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 500L))
        .isEqualTo(500L);

    // Simulate new transaction begin — active tsMin (200) wins over fallback.
    holder.tsMin = 200L;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 500L))
        .isEqualTo(200L);
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
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 1000L))
        .isEqualTo(100L);

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
      assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 1000L))
          .isEqualTo(500L);
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
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 1000L))
        .isEqualTo(50L);

    // Thread 2 "ends" its transaction.
    // Mutated from main thread for test simplicity (in production, only the owning thread writes).
    t2Holder.get().tsMin = Long.MAX_VALUE;
    t2Holder.get().activeTxCount--;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 1000L))
        .isEqualTo(200L);

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
          // Use a fallback of 999 (below any worker's tsMin range of 1000+)
          // so we can distinguish "fallback was used" from "active tsMin found".
          long lwm = AbstractStorage.computeGlobalLowWaterMark(tsMins, 999L);
          // Safety invariant: LWM must be <= every active holder's tsMin.
          // Since we can't atomically snapshot all holders + the LWM, we check
          // a weaker but still useful property: LWM must be either the fallback
          // value (999, when all idle) or a valid timestamp >= 1000 (active tx).
          assertThat(lwm)
              .as("LWM must be fallback (999, all idle) or a valid timestamp >= 1000")
              .satisfiesAnyOf(
                  v -> assertThat(v).isEqualTo(999L),
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

    // All workers done, all txs ended — LWM falls back to currentId (999)
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 999L))
        .isEqualTo(999L);
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
    var readerObservedActive = new CountDownLatch(1);
    var readerSawReset = new AtomicBoolean(false);
    var error = new AtomicReference<Throwable>();

    // Writer thread: begin tx (volatile write), signal, wait for the reader to confirm
    // it observed the active tsMin, then end tx (opaque reset) without any further
    // synchronization — only opaque visibility guarantees apply after this point.
    var writer = new Thread(() -> {
      try {
        holder.tsMin = 42L; // volatile write — tx begin
        holder.activeTxCount++;
        txStarted.countDown();

        // Wait until the reader has verified the active tsMin before resetting.
        // This replaces the previous Thread.sleep(10) which was racy on ARM.
        readerObservedActive.await();

        // tx end — opaque reset (the code path under test).
        // No latch or fence after this: only opaque visibility guarantees apply.
        holder.activeTxCount--;
        holder.setTsMinOpaque(Long.MAX_VALUE);
      } catch (Throwable t) {
        error.compareAndSet(null, t);
      }
    });

    // Reader thread: after confirming the volatile write is visible, signals the writer
    // to proceed with the opaque reset, then polls the LWM relying solely on opaque
    // eventual visibility to see the reset.
    var reader = new Thread(() -> {
      try {
        txStarted.await();

        // Use a fallback of 999 so we can detect when the opaque reset propagates
        // (LWM goes from 42 to 999).
        // First, verify the active tsMin is visible (volatile write + latch guarantee)
        assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, 999L))
            .isEqualTo(42L);

        // Let the writer proceed with the opaque reset
        readerObservedActive.countDown();

        // Poll until the opaque reset propagates. No synchronization fence between
        // the opaque write and this loop — eventual visibility is all we rely on.
        // On x86 this is near-instant; on ARM it may take more iterations.
        for (int i = 0; i < 10_000_000; i++) {
          if (AbstractStorage.computeGlobalLowWaterMark(tsMins, 999L) == 999L) {
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
        .as("Reader must eventually see the opaque reset (LWM falls to fallback)")
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

      // Use a fallback value below any worker's tsMin range so that when all
      // txs end, the LWM becomes the fallback (not Long.MAX_VALUE).
      long fallback = baseTs - 1;

      // Checkpoint 1: all txs active — LWM must equal the minimum tsMin.
      long expectedMin = baseTs; // worker 0 has the smallest ts
      assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, fallback))
          .as("LWM with all txs active (round %d)", round)
          .isEqualTo(expectedMin);

      // Signal workers to end their transactions.
      proceedToEnd.countDown();
      assertThat(allEnded.await(10, TimeUnit.SECONDS))
          .as("All workers should end within 10s (round %d)", round).isTrue();

      // Checkpoint 2: all txs ended via opaque reset — LWM falls back to currentId.
      // The latch provides happens-before with the opaque writes, so visibility
      // is guaranteed here (this checkpoint tests functional correctness, not
      // opaque visibility — testOpaqueResetEventuallyVisibleToReader covers that).
      assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins, fallback))
          .as("LWM after all txs ended (round %d)", round)
          .isEqualTo(fallback);

      if (error.get() != null) {
        throw new AssertionError("Worker thread failure in round " + round, error.get());
      }
    }
  }

  // --- Diagnostic field capture/clear lifecycle ---

  /**
   * captureDiagnostics sets all diagnostic fields: txStartTimeNanos, ownerThreadName,
   * ownerThreadId. When captureStackTrace is false, txStartStackTrace remains null.
   */
  @Test
  public void testCaptureDiagnosticsWithoutStackTrace() {
    var holder = new TsMinHolder();
    var thread = Thread.currentThread();

    holder.captureDiagnostics(12345L, thread, false);

    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(12345L);
    assertThat(holder.getOwnerThreadNameOpaque()).isEqualTo(thread.getName());
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(thread.threadId());
    assertThat(holder.getTxStartStackTraceOpaque()).isNull();
  }

  /**
   * captureDiagnostics with captureStackTrace=true also captures the stack trace.
   */
  @Test
  public void testCaptureDiagnosticsWithStackTrace() {
    var holder = new TsMinHolder();
    var thread = Thread.currentThread();

    holder.captureDiagnostics(99999L, thread, true);

    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(99999L);
    assertThat(holder.getOwnerThreadNameOpaque()).isEqualTo(thread.getName());
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(thread.threadId());
    assertThat(holder.getTxStartStackTraceOpaque()).isNotNull();
    assertThat(holder.getTxStartStackTraceOpaque().length).isGreaterThan(0);
  }

  /**
   * clearDiagnostics resets all diagnostic fields to their default values:
   * txStartTimeNanos=0, ownerThreadName=null, ownerThreadId=0, txStartStackTrace=null.
   */
  @Test
  public void testClearDiagnosticsResetsAllFields() {
    var holder = new TsMinHolder();
    var thread = Thread.currentThread();

    // First populate all fields
    holder.captureDiagnostics(12345L, thread, true);
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(12345L);
    assertThat(holder.getOwnerThreadNameOpaque()).isNotNull();
    assertThat(holder.getOwnerThreadIdOpaque()).isNotEqualTo(0);
    assertThat(holder.getTxStartStackTraceOpaque()).isNotNull();

    // Clear and verify all are reset
    holder.clearDiagnostics();

    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
    assertThat(holder.getOwnerThreadNameOpaque()).isNull();
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(0);
    assertThat(holder.getTxStartStackTraceOpaque()).isNull();
  }

  /**
   * Calling clearDiagnostics on a fresh holder (no diagnostics captured) should be a no-op.
   */
  @Test
  public void testClearDiagnosticsOnFreshHolder() {
    var holder = new TsMinHolder();
    holder.clearDiagnostics();

    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
    assertThat(holder.getOwnerThreadNameOpaque()).isNull();
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(0);
    assertThat(holder.getTxStartStackTraceOpaque()).isNull();
  }

  /**
   * Full lifecycle: capture → verify → clear → verify. Simulates the tx begin/end pattern.
   */
  @Test
  public void testDiagnosticsCaptureAndClearLifecycle() {
    var holder = new TsMinHolder();
    var thread = Thread.currentThread();

    // Simulate tx begin
    holder.captureDiagnostics(50000L, thread, false);
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(50000L);
    assertThat(holder.getOwnerThreadNameOpaque()).isEqualTo(thread.getName());
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(thread.threadId());

    // Simulate tx end
    holder.clearDiagnostics();
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
    assertThat(holder.getOwnerThreadNameOpaque()).isNull();
    assertThat(holder.getOwnerThreadIdOpaque()).isEqualTo(0);

    // Simulate second tx begin with different start time
    holder.captureDiagnostics(70000L, thread, false);
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(70000L);

    // Simulate second tx end
    holder.clearDiagnostics();
    assertThat(holder.getTxStartTimeNanosOpaque()).isEqualTo(0);
  }

  /**
   * setTsMinOpaque writes the value and it becomes visible to a volatile read.
   */
  @Test
  public void testSetTsMinOpaque() {
    var holder = new TsMinHolder();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);

    holder.setTsMinOpaque(42L);
    assertThat(holder.tsMin).isEqualTo(42L);

    holder.setTsMinOpaque(Long.MAX_VALUE);
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
  }

  private static Set<TsMinHolder> newTsMinsSet() {
    return AbstractStorage.newTsMinsSet();
  }
}
