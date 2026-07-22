package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests for {@link ScalableRWLock}, specifically verifying that ghost reader entries left by
 * terminated threads are cleaned up by the {@link java.lang.ref.Cleaner} so that writers do not
 * spin forever.
 */
public class ScalableRWLockTest {

  /**
   * Verifies that a reader thread dying without calling sharedUnlock() does not permanently block
   * writers. After the thread terminates and its ReadersEntry becomes phantom-reachable, the
   * Cleaner should reset the reader state, allowing exclusiveLock() to proceed.
   */
  @Test(timeout = 30_000)
  public void testGhostReaderCleanupAfterThreadDeath() throws Exception {
    final var lock = new ScalableRWLock();
    final var readLockAcquired = new CountDownLatch(1);

    // Spawn a thread that acquires the read lock and then dies without unlocking
    var readerThread = new Thread(() -> {
      lock.sharedLock();
      readLockAcquired.countDown();
      // Thread exits without calling sharedUnlock()
    });
    readerThread.start();

    // Wait for the reader thread to acquire the lock and terminate
    assertTrue("Reader thread should acquire the lock",
        readLockAcquired.await(5, TimeUnit.SECONDS));
    readerThread.join(5_000);
    assertFalse("Reader thread should have terminated", readerThread.isAlive());

    // Null the thread reference so the ThreadLocal (and thus ReadersEntry) become unreachable
    //noinspection UnusedAssignment
    readerThread = null;

    // Trigger GC to make the Cleaner run. Retry a few times since GC is non-deterministic.
    // Note: this may be flaky on unusual GC configurations (e.g. Epsilon GC or very large heaps)
    // where System.gc() is a no-op. With the default G1 collector on JDK 21+, it is reliable.
    for (int i = 0; i < 50; i++) {
      System.gc();
      Thread.sleep(100);

      // Try to acquire the write lock with a short timeout — if cleanup happened, this succeeds
      if (lock.exclusiveTryLockNanos(TimeUnit.MILLISECONDS.toNanos(50))) {
        lock.exclusiveUnlock();
        return; // Success: ghost reader was cleaned up
      }
    }

    // If we get here, the Cleaner never ran — fail the test
    throw new AssertionError(
        "exclusiveLock() could not be acquired after thread death; "
            + "ghost reader state was not cleaned up by Cleaner");
  }

  /**
   * Verifies that a ScalableRWLock instance is collectible even when threads that previously used
   * it are still alive. This is the regression test for the memory leak in CleanupAction.
   *
   * <p>The leak scenario: if CleanupAction captures a reference to the ScalableRWLock instance,
   * two GC roots hold opposite ends of a dependency chain, creating a deadlock that prevents
   * collection of both the lock and its ReadersEntry objects:
   *
   * <p><b>GC root 1 — Cleaner daemon thread:</b><br>
   * Cleaner daemon → CleanupAction → ScalableRWLock → ThreadLocal field {@code entry}
   *
   * <p><b>GC root 2 — pool thread T (still alive):</b><br>
   * Thread T → ThreadLocalMap → Entry(WeakReference key: ThreadLocal, strong value: ReadersEntry)
   *
   * <p>The Cleaner daemon won't release CleanupAction until ReadersEntry becomes
   * phantom-reachable. But ReadersEntry is strongly reachable from Thread T's ThreadLocalMap
   * because the lock (kept alive by CleanupAction) keeps the ThreadLocal key strongly reachable,
   * so the WeakReference key is never cleared, so the map entry is never stale. Neither GC root
   * lets go, and the primary leak manifests as growing {@code readersStateList}: every pool
   * thread that ever touches the lock leaves behind a ReadersEntry that can never be cleaned up.
   *
   * <p>With the fix (CleanupAction captures only the needed fields, not the lock), dropping
   * all external references to the lock breaks the deadlock: the lock becomes weakly reachable
   * and is collected, the ThreadLocal key goes weak and is cleared, the ReadersEntry eventually
   * becomes phantom-reachable, and the Cleaner fires to remove it from {@code readersStateList}.
   *
   * <p>Note: this test relies on {@code System.gc()} actually triggering collection. With the
   * default G1 collector on JDK 21+ this is reliable, but it may be flaky under unusual GC
   * configurations (e.g. Epsilon GC or very large heaps) where {@code System.gc()} is a no-op.
   */
  @Test(timeout = 30_000)
  public void testLockIsCollectibleWhileReaderThreadIsAlive() throws Exception {
    var readLockUsed = new CountDownLatch(1);
    var threadCanExit = new CountDownLatch(1);

    // Pass the lock through an AtomicReference so the thread can clear its reference after use
    var lockHolder = new AtomicReference<>(new ScalableRWLock());
    var weakRef = new WeakReference<>(lockHolder.get());

    // Simulate a long-lived pool thread that uses the lock and then drops its reference.
    // The thread stays alive (like a pool thread would), but no longer holds a strong
    // reference to the lock.
    var poolThread = new Thread(() -> {
      var localLock = lockHolder.getAndSet(null);
      localLock.sharedLock();
      localLock.sharedUnlock();
      //noinspection UnusedAssignment
      localLock = null;
      readLockUsed.countDown();
      try {
        threadCanExit.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    poolThread.start();

    assertTrue("Pool thread should use the lock",
        readLockUsed.await(5, TimeUnit.SECONDS));

    // At this point, no strong references to the lock exist:
    // - lockHolder was cleared by the thread via getAndSet(null)
    // - the thread's local variable was nulled after unlock
    // - the thread's ThreadLocalMap holds a WeakReference to the ThreadLocal key
    // - only our WeakReference and the Cleaner's internal PhantomReference remain
    //
    // If CleanupAction does NOT capture the lock instance, the lock is weakly reachable
    // and should be collected. If it does capture the lock, the chain described in the
    // Javadoc above keeps it strongly reachable — a memory leak.
    for (int i = 0; i < 50; i++) {
      System.gc();
      Thread.sleep(100);
      if (weakRef.get() == null) {
        threadCanExit.countDown();
        poolThread.join(5_000);
        return; // Success: lock was garbage-collected
      }
    }

    threadCanExit.countDown();
    poolThread.join(5_000);
    throw new AssertionError(
        "ScalableRWLock was not collected while a reader thread was still alive; "
            + "CleanupAction likely retains a reference to the lock instance, "
            + "preventing collection of both the lock and its ReadersEntry objects "
            + "in long-lived thread pools");
  }

  /**
   * Verifies that sharedTryLock() returns false when a writer holds the lock, exercising the
   * lazySet back-off path.
   */
  @Test(timeout = 10_000)
  public void testSharedTryLockFailsWhenWriterHoldsLock() throws Exception {
    final var lock = new ScalableRWLock();

    lock.exclusiveLock();
    try {
      // sharedTryLock should fail immediately because writer holds the lock
      assertFalse("sharedTryLock should return false when writer holds the lock",
          lock.sharedTryLock());
    } finally {
      lock.exclusiveUnlock();
    }

    // After releasing, sharedTryLock should succeed
    assertTrue("sharedTryLock should succeed after writer releases",
        lock.sharedTryLock());
    lock.sharedUnlock();
  }

  /**
   * Verifies that sharedTryLockNanos() returns false when a writer holds the lock and the timeout
   * expires, exercising the lazySet back-off path in the timed variant.
   */
  @Test(timeout = 10_000)
  public void testSharedTryLockNanosFailsWhenWriterHoldsLock() throws Exception {
    final var lock = new ScalableRWLock();

    lock.exclusiveLock();
    try {
      // sharedTryLockNanos with a short timeout should fail
      assertFalse("sharedTryLockNanos should return false when writer holds the lock",
          lock.sharedTryLockNanos(TimeUnit.MILLISECONDS.toNanos(50)));
    } finally {
      lock.exclusiveUnlock();
    }

    // After releasing, sharedTryLockNanos should succeed
    assertTrue("sharedTryLockNanos should succeed after writer releases",
        lock.sharedTryLockNanos(TimeUnit.SECONDS.toNanos(5)));
    lock.sharedUnlock();
  }

  /**
   * Verifies that normal read-lock / write-lock acquisition still works correctly after replacing
   * finalize() with Cleaner.
   */
  @Test(timeout = 10_000)
  public void testBasicReadWriteLockBehavior() throws Exception {
    final var lock = new ScalableRWLock();

    // Acquire and release read lock
    lock.sharedLock();
    lock.sharedUnlock();

    // Acquire and release write lock
    lock.exclusiveLock();
    lock.exclusiveUnlock();

    // Read lock should not block when no writer holds the lock
    assertTrue("sharedTryLock should succeed when no writer holds the lock",
        lock.sharedTryLock());
    lock.sharedUnlock();

    // Write lock should succeed when no reader holds the lock
    assertTrue("exclusiveTryLock should succeed when no reader holds the lock",
        lock.exclusiveTryLock());
    lock.exclusiveUnlock();
  }

  /**
   * Verifies that a writer blocks while a reader holds the lock, and proceeds once the reader
   * releases it.
   */
  @Test(timeout = 10_000)
  public void testWriterBlocksWhileReaderHoldsLock() throws Exception {
    final var lock = new ScalableRWLock();
    final var writerAcquired = new AtomicBoolean(false);
    final var writerStarted = new CountDownLatch(1);

    // Acquire read lock on the main thread
    lock.sharedLock();

    // Spawn a writer thread that tries to acquire the write lock
    var writerThread = new Thread(() -> {
      writerStarted.countDown();
      lock.exclusiveLock();
      writerAcquired.set(true);
      lock.exclusiveUnlock();
    });
    writerThread.start();
    assertTrue("Writer thread should start",
        writerStarted.await(5, TimeUnit.SECONDS));

    // Give the writer a moment to attempt acquisition — it should be blocked
    Thread.sleep(200);
    assertFalse("Writer should be blocked while reader holds the lock",
        writerAcquired.get());

    // Release the read lock — writer should now proceed
    lock.sharedUnlock();
    writerThread.join(5_000);
    assertTrue("Writer should have acquired the lock after reader released it",
        writerAcquired.get());
  }

  // ---------------------------------------------------------------------------------------------
  // exclusiveLockWithAbort — the abort-predicate single-acquisition primitive
  // ---------------------------------------------------------------------------------------------

  /** Bounded spin until {@code thread} reports a parked state or the timeout elapses. */
  private static Thread.State awaitParked(final Thread thread, final long timeoutMillis) {
    final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    var state = thread.getState();
    while (System.nanoTime() < deadline) {
      state = thread.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        return state;
      }
      Thread.onSpinWait();
    }
    return state;
  }

  /** Bounded spin until {@code condition} holds or the timeout elapses; returns the last value. */
  private static boolean awaitCondition(
      final java.util.function.BooleanSupplier condition, final long timeoutMillis) {
    final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.onSpinWait();
    }
    return condition.getAsBoolean();
  }

  /**
   * The no-abort path acquires the write bit once and returns true; release restores the lock for
   * both readers and later writers, and the primitive is reusable on the same instance. This is
   * the plain round-trip contract every other scenario builds on.
   */
  @Test(timeout = 30_000)
  public void abortLockPlainRoundTripWithoutAbort() {
    final var lock = new ScalableRWLock();
    assertTrue("the no-abort acquisition must succeed",
        lock.exclusiveLockWithAbort(() -> false, TimeUnit.MILLISECONDS.toNanos(10)));
    assertTrue("the write bit must be held after a successful acquisition", lock.isWriteLocked());
    lock.exclusiveUnlock();
    assertFalse("the write bit must be free after release", lock.isWriteLocked());

    // The lock stays fully usable through the ordinary API and the primitive is reusable.
    lock.sharedLock();
    lock.sharedUnlock();
    assertTrue("the primitive must be reusable after a full round trip",
        lock.exclusiveLockWithAbort(() -> false, TimeUnit.MILLISECONDS.toNanos(10)));
    lock.exclusiveUnlock();
  }

  /**
   * Abort while parked in phase 1 (queued behind another writer holding the bit): the waiter
   * returns false promptly after the predicate flips, holds no state (the bit still belongs to
   * the first writer), and the primitive is immediately reusable once the holder releases.
   */
  @Test(timeout = 30_000)
  public void abortWhileQueuedBehindWriterReturnsFalsePromptly() throws Exception {
    final var lock = new ScalableRWLock();
    lock.exclusiveLock(); // the competing writer holds the bit for the whole phase-1 park
    final var abort = new AtomicBoolean(false);
    final var result = new AtomicReference<Boolean>();
    final var waiter = new Thread(
        () -> result.set(
            lock.exclusiveLockWithAbort(abort::get, TimeUnit.MILLISECONDS.toNanos(5))));
    waiter.start();
    final var state = awaitParked(waiter, 5_000);
    assertTrue("the waiter must park in the phase-1 timed acquire, observed state " + state,
        state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);

    abort.set(true);
    waiter.join(5_000);
    assertFalse("the aborted waiter must exit promptly (one poll granularity)", waiter.isAlive());
    assertEquals("the aborted acquisition must return false", Boolean.FALSE, result.get());
    assertTrue("the competing writer's bit must be untouched by the abort", lock.isWriteLocked());

    lock.exclusiveUnlock();
    // No residual writer-intent state: the primitive succeeds immediately afterwards.
    assertTrue("the primitive must be reusable after a phase-1 abort",
        lock.exclusiveLockWithAbort(() -> false, TimeUnit.MILLISECONDS.toNanos(10)));
    lock.exclusiveUnlock();
  }

  /**
   * Abort during the phase-2 reader drain: the acquisition holds the write bit while a residual
   * reader is still inside, the predicate flips, and the primitive must release the bit fully and
   * return false — backed-off readers proceed (a fresh shared acquire succeeds) and no state is
   * left behind.
   */
  @Test(timeout = 30_000)
  public void abortDuringReaderDrainReleasesBitAndReadersProceed() throws Exception {
    final var lock = new ScalableRWLock();
    final var readerIn = new CountDownLatch(1);
    final var readerMayLeave = new CountDownLatch(1);
    final var reader = new Thread(() -> {
      lock.sharedLock();
      readerIn.countDown();
      try {
        readerMayLeave.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.sharedUnlock();
      }
    });
    reader.start();
    assertTrue("the residual reader must be inside", readerIn.await(5, TimeUnit.SECONDS));

    final var abort = new AtomicBoolean(false);
    final var result = new AtomicReference<Boolean>();
    final var writer = new Thread(
        () -> result.set(
            lock.exclusiveLockWithAbort(abort::get, TimeUnit.MILLISECONDS.toNanos(5))));
    writer.start();
    // The writer acquires the bit once and enters the drain spin against the parked reader.
    assertTrue("the writer must hold the bit while draining the residual reader",
        awaitCondition(lock::isWriteLocked, 5_000));

    abort.set(true);
    writer.join(5_000);
    assertFalse("the aborted drain must exit promptly", writer.isAlive());
    assertEquals("the aborted acquisition must return false", Boolean.FALSE, result.get());
    assertFalse("the abort must release the write bit fully", lock.isWriteLocked());
    // Readers proceed after the abort: a fresh shared acquire succeeds while the residual reader
    // is still inside (no reader stranded, no lost wakeup — readers poll the released bit).
    assertTrue("a fresh reader must acquire after the abort released the bit",
        lock.sharedTryLock());
    lock.sharedUnlock();

    readerMayLeave.countDown();
    reader.join(5_000);
    assertFalse("the residual reader must finish", reader.isAlive());
    // The lock is fully usable afterwards.
    lock.exclusiveLock();
    lock.exclusiveUnlock();
  }

  /**
   * The acquisition-success-edge re-check: a predicate that turns true exactly at the success
   * edge — after the bit is acquired and the (empty) drain completes, before the method returns —
   * must abort, not report a spurious success. Deterministic: on a fresh lock with no registered
   * readers the predicate is polled exactly twice (once in phase 1 before the acquire, once at
   * the success-edge re-check), so a count-based predicate that turns true on the second call
   * lands precisely on the edge.
   */
  @Test(timeout = 30_000)
  public void abortAtAcquisitionSuccessEdgeReturnsFalse() {
    final var lock = new ScalableRWLock();
    final var calls = new AtomicInteger();
    final var result = lock.exclusiveLockWithAbort(
        () -> calls.incrementAndGet() >= 2, TimeUnit.MILLISECONDS.toNanos(10));
    assertFalse("a predicate turning true at the success edge must abort the acquisition",
        result);
    assertEquals("the edge shape must poll exactly twice (phase-1 entry + success-edge re-check);"
        + " a different count means the scenario drifted off the edge",
        2, calls.get());
    assertFalse("the success-edge abort must release the write bit", lock.isWriteLocked());
    // Reusable immediately.
    assertTrue(lock.exclusiveLockWithAbort(() -> false, TimeUnit.MILLISECONDS.toNanos(10)));
    lock.exclusiveUnlock();
  }

  /**
   * Writer preference while the bit is held: from the moment the primitive acquires the write bit
   * (even while still draining a residual reader), new readers are refused exactly as against
   * {@code exclusiveLock()}, and they stay refused through the completed acquisition until the
   * release. This is the single-acquisition admission-control half of the primitive's contract
   * (no inter-attempt release window for readers to slip through).
   */
  @Test(timeout = 30_000)
  public void writerPreferenceRefusesNewReadersWhileBitHeld() throws Exception {
    final var lock = new ScalableRWLock();
    final var readerIn = new CountDownLatch(1);
    final var readerMayLeave = new CountDownLatch(1);
    final var reader = new Thread(() -> {
      lock.sharedLock();
      readerIn.countDown();
      try {
        readerMayLeave.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.sharedUnlock();
      }
    });
    reader.start();
    assertTrue(readerIn.await(5, TimeUnit.SECONDS));

    final var acquired = new CountDownLatch(1);
    final var mayRelease = new CountDownLatch(1);
    final var writer = new Thread(() -> {
      if (lock.exclusiveLockWithAbort(() -> false, TimeUnit.MILLISECONDS.toNanos(5))) {
        acquired.countDown();
        try {
          mayRelease.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          lock.exclusiveUnlock();
        }
      }
    });
    writer.start();
    // The bit is held while the drain waits on the residual reader: new readers already refused.
    assertTrue("the writer must hold the bit during the drain",
        awaitCondition(lock::isWriteLocked, 5_000));
    assertFalse("a new reader must be refused while the bit is held (drain in progress)",
        lock.sharedTryLock());

    // Let the residual reader leave: the acquisition completes; new readers still refused.
    readerMayLeave.countDown();
    assertTrue("the acquisition must complete once the residual reader leaves",
        acquired.await(5, TimeUnit.SECONDS));
    assertFalse("a new reader must be refused while the write lock is held",
        lock.sharedTryLock());

    mayRelease.countDown();
    writer.join(5_000);
    reader.join(5_000);
    assertFalse(writer.isAlive());
    assertFalse(reader.isAlive());
    assertTrue("readers must be admitted again after the release", lock.sharedTryLock());
    lock.sharedUnlock();
  }

  /**
   * Interrupt while parked in the phase-1 timed acquire: the primitive restores the interrupt
   * flag and throws {@code DatabaseException} naming the wait — the waiter is killable, and the
   * lock (still owned by the competing writer) is undisturbed and usable afterwards.
   */
  @Test(timeout = 30_000)
  public void interruptDuringPhaseOneRestoresFlagAndThrows() throws Exception {
    final var lock = new ScalableRWLock();
    lock.exclusiveLock(); // competing writer holds the bit so the waiter parks in phase 1
    final var thrown = new AtomicReference<Throwable>();
    final var flagRestored = new AtomicBoolean(false);
    final var waiter = new Thread(() -> {
      try {
        lock.exclusiveLockWithAbort(() -> false, TimeUnit.MILLISECONDS.toNanos(100));
      } catch (Throwable t) {
        thrown.set(t);
        flagRestored.set(Thread.currentThread().isInterrupted());
      }
    });
    waiter.start();
    final var state = awaitParked(waiter, 5_000);
    assertTrue("the waiter must park in the timed acquire, observed state " + state,
        state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);

    waiter.interrupt();
    waiter.join(5_000);
    assertFalse("the interrupted waiter must exit", waiter.isAlive());
    assertNotNull("the interrupted waiter must have thrown", thrown.get());
    assertTrue("the throw must be a DatabaseException naming the interrupted wait: " + thrown.get(),
        thrown.get() instanceof DatabaseException
            && thrown.get().getMessage().contains("interrupted while acquiring"));
    assertTrue("the interrupt flag must be restored before the throw", flagRestored.get());

    assertTrue("the holder's bit must be undisturbed", lock.isWriteLocked());
    lock.exclusiveUnlock();
    assertTrue("the lock must be usable after the interrupted waiter exited",
        lock.exclusiveLockWithAbort(() -> false, TimeUnit.MILLISECONDS.toNanos(10)));
    lock.exclusiveUnlock();
  }

  /**
   * Bounded stress sanity: several readers loop shared acquire/release while one writer loops the
   * abort primitive with a predicate that flips true on every other attempt (so both the success
   * and the abort paths run repeatedly under contention). No reader may strand, no wakeup may be
   * lost, every thread must finish its bounded iterations, and the lock must be fully usable
   * afterwards. All waits are bounded joins — no unbounded sleeps.
   */
  @Test(timeout = 60_000)
  public void stressReadersAgainstAbortingWriterLeavesLockUsable() throws Exception {
    final var lock = new ScalableRWLock();
    final var readerThreads = 4;
    final var readerIterations = 2_000;
    final var writerIterations = 200;
    final var failures = new AtomicReference<Throwable>();
    final var threads = new ArrayList<Thread>();

    for (var i = 0; i < readerThreads; i++) {
      final var t = new Thread(() -> {
        try {
          for (var k = 0; k < readerIterations; k++) {
            lock.sharedLock();
            try {
              Thread.onSpinWait();
            } finally {
              lock.sharedUnlock();
            }
          }
        } catch (Throwable t2) {
          failures.compareAndSet(null, t2);
        }
      }, "stress-reader-" + i);
      threads.add(t);
    }

    final var successes = new AtomicInteger();
    final var aborts = new AtomicInteger();
    final var writer = new Thread(() -> {
      try {
        final var attempt = new AtomicInteger();
        for (var k = 0; k < writerIterations; k++) {
          // Every other attempt aborts mid-flight: the predicate turns true after a few polls,
          // exercising phase-1 aborts, drain aborts, and the success-edge re-check by timing
          // noise; even attempts never abort, exercising the full acquisition under contention.
          final var abortingAttempt = (k % 2) == 1;
          final var polls = new AtomicInteger();
          final var acquired = lock.exclusiveLockWithAbort(
              () -> abortingAttempt && polls.incrementAndGet() > 3,
              TimeUnit.MILLISECONDS.toNanos(1));
          if (acquired) {
            successes.incrementAndGet();
            lock.exclusiveUnlock();
          } else {
            aborts.incrementAndGet();
          }
          attempt.incrementAndGet();
        }
      } catch (Throwable t2) {
        failures.compareAndSet(null, t2);
      }
    }, "stress-abort-writer");
    threads.add(writer);

    for (final var t : threads) {
      t.start();
    }
    for (final var t : threads) {
      t.join(50_000);
      assertFalse("thread " + t.getName() + " must finish its bounded iterations (no stranded"
          + " reader, no lost wakeup)", t.isAlive());
    }
    if (failures.get() != null) {
      throw new AssertionError("no thread may fail under stress", failures.get());
    }
    // Every attempt terminates in exactly one of the two outcomes. The non-aborting (even)
    // attempts must ALL have acquired — the bounded-acquisition guarantee under sustained
    // readers; an "aborting" (odd) attempt may legitimately land on either side, because a
    // lightly-contended acquisition can complete before its predicate reaches the flip
    // threshold — the deterministic abort shapes are pinned by the dedicated tests above.
    assertEquals("every attempt must terminate in exactly one outcome", writerIterations,
        successes.get() + aborts.get());
    assertTrue("every non-aborting attempt must acquire (bounded acquisition under sustained"
        + " readers), successes=" + successes.get(),
        successes.get() >= writerIterations / 2);
    // The lock is fully usable afterwards in both modes.
    lock.exclusiveLock();
    lock.exclusiveUnlock();
    lock.sharedLock();
    lock.sharedUnlock();
  }
}
