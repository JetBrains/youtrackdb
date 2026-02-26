package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    assertTrue("Reader thread should have terminated", !readerThread.isAlive());

    // Null the thread reference so the ThreadLocal (and thus ReadersEntry) become unreachable
    //noinspection UnusedAssignment
    readerThread = null;

    // Trigger GC to make the Cleaner run. Retry a few times since GC is non-deterministic.
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
    final var readerReady = new CountDownLatch(1);
    final var writerStarted = new CountDownLatch(1);

    // Acquire read lock on the main thread
    lock.sharedLock();
    readerReady.countDown();

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
    assertTrue("Writer should be blocked while reader holds the lock",
        !writerAcquired.get());

    // Release the read lock — writer should now proceed
    lock.sharedUnlock();
    writerThread.join(5_000);
    assertTrue("Writer should have acquired the lock after reader released it",
        writerAcquired.get());
  }
}
