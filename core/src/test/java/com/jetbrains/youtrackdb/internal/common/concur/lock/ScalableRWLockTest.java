package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
