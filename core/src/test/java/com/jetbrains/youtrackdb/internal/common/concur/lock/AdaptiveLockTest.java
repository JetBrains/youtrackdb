package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Test;

/**
 * Tests for {@link AdaptiveLock} covering all constructor modes, lock/unlock behavior,
 * timeout handling, thread interruption, tryAcquireLock, callInLock (inherited from
 * {@link AbstractLock}), and close().
 */
public class AdaptiveLockTest {

  /**
   * Starts a background thread that acquires the given lock and holds it until
   * {@code canRelease} is counted down. The returned thread must be joined by the caller.
   */
  private Thread holdLockInBackground(
      AdaptiveLock lock, CountDownLatch lockHeld, CountDownLatch canRelease) {
    var holder = new Thread(() -> {
      lock.lock();
      lockHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.unlock();
      }
    });
    holder.start();
    return holder;
  }

  // --- Constructor modes ---

  /**
   * Default constructor creates a concurrent lock with no timeout and
   * ignoreThreadInterruption=false.
   */
  @Test
  public void testDefaultConstructorIsConcurrent() {
    var lock = new AdaptiveLock();
    assertTrue("Default constructor should be concurrent", lock.isConcurrent());
    assertNotNull("getUnderlying should return a ReentrantLock", lock.getUnderlying());
  }

  /**
   * Constructor with timeout creates a concurrent lock with the specified timeout.
   */
  @Test
  public void testTimeoutConstructorIsConcurrent() {
    var lock = new AdaptiveLock(500);
    assertTrue("Timeout constructor should be concurrent", lock.isConcurrent());
  }

  /**
   * Constructor with concurrent=false creates a non-concurrent lock where lock/unlock
   * are no-ops.
   */
  @Test
  public void testNonConcurrentConstructor() {
    var lock = new AdaptiveLock(false);
    assertFalse("Non-concurrent constructor should not be concurrent",
        lock.isConcurrent());
  }

  /**
   * Full constructor with concurrent=true, timeout, and ignoreThreadInterruption.
   */
  @Test
  public void testFullConstructor() {
    var lock = new AdaptiveLock(true, 1000, true);
    assertTrue("Full constructor with concurrent=true should be concurrent",
        lock.isConcurrent());
  }

  // --- Non-concurrent mode ---

  /**
   * In non-concurrent mode, lock() and unlock() are no-ops — calling them does not
   * actually acquire the underlying ReentrantLock.
   */
  @Test
  public void testNonConcurrentLockUnlockAreNoOps() {
    var lock = new AdaptiveLock(false);
    lock.lock();
    // The underlying lock should NOT be held because non-concurrent mode skips it
    assertFalse("Non-concurrent lock should not hold underlying lock",
        lock.isHeldByCurrentThread());
    lock.unlock(); // Should not throw
  }

  // --- Concurrent mode: basic lock/unlock ---

  /**
   * In concurrent mode with no timeout, lock() acquires the underlying ReentrantLock
   * and unlock() releases it.
   */
  @Test
  public void testConcurrentLockAndUnlock() {
    var lock = new AdaptiveLock();
    lock.lock();
    assertTrue("Lock should be held after lock()",
        lock.isHeldByCurrentThread());
    lock.unlock();
    assertFalse("Lock should not be held after unlock()",
        lock.isHeldByCurrentThread());
  }

  /**
   * Reentrant locking: lock() can be called multiple times by the same thread and
   * requires the same number of unlock() calls.
   */
  @Test
  public void testReentrantLocking() {
    var lock = new AdaptiveLock();
    lock.lock();
    lock.lock();
    assertTrue("Lock should be held after double lock",
        lock.isHeldByCurrentThread());
    lock.unlock();
    assertTrue("Lock should still be held after first unlock",
        lock.isHeldByCurrentThread());
    lock.unlock();
    assertFalse("Lock should be released after second unlock",
        lock.isHeldByCurrentThread());
  }

  /**
   * getUnderlying() returns the internal ReentrantLock instance.
   */
  @Test
  public void testGetUnderlying() {
    var lock = new AdaptiveLock();
    ReentrantLock underlying = lock.getUnderlying();
    assertNotNull("getUnderlying() should not return null", underlying);
    lock.lock();
    assertTrue("Underlying lock should be locked when AdaptiveLock is locked",
        underlying.isLocked());
    lock.unlock();
    assertFalse("Underlying lock should be unlocked after AdaptiveLock unlock",
        underlying.isLocked());
  }

  // --- Timeout mode ---

  /**
   * With a timeout, lock() succeeds when the lock is available and completes within
   * the timeout period.
   */
  @Test
  public void testTimeoutLockSucceedsWhenAvailable() {
    var lock = new AdaptiveLock(5000);
    lock.lock();
    assertTrue("Lock should be held after timeout lock() succeeds",
        lock.isHeldByCurrentThread());
    lock.unlock();
  }

  /**
   * AdaptiveLock(0) treats timeout=0 as "no timeout" — lock() uses unconditional
   * blocking (same as default constructor), because the condition is timeout > 0.
   */
  @Test
  public void testZeroTimeoutUsesBlockingLock() {
    var lock = new AdaptiveLock(0);
    lock.lock();
    assertTrue("Lock should be held", lock.isHeldByCurrentThread());
    lock.unlock();
  }

  /**
   * With a timeout, lock() throws TimeoutException when the lock cannot be acquired
   * within the timeout period. The exception message contains the timeout value and
   * resource class name.
   */
  @Test(timeout = 10_000)
  public void testTimeoutLockThrowsOnTimeout() throws Exception {
    var lock = new AdaptiveLock(100);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    try {
      lock.lock();
      fail("Should have thrown TimeoutException");
    } catch (TimeoutException e) {
      assertTrue("Message should contain timeout value",
          e.getMessage().contains("timeout=100"));
      assertTrue("Message should mention resource class",
          e.getMessage().contains("AdaptiveLock"));
    } finally {
      canRelease.countDown();
      holder.join(5_000);
    }
  }

  // --- Thread interruption ---

  /**
   * When a thread is interrupted during a timed lock attempt and
   * ignoreThreadInterruption=false, lock() throws LockException wrapping the
   * InterruptedException as its cause.
   */
  @Test(timeout = 10_000)
  public void testInterruptionDuringTimedLockThrowsLockException() throws Exception {
    var lock = new AdaptiveLock(true, 5000, false);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    var caughtException = new AtomicReference<LockException>();
    var acquiringThread = new Thread(() -> {
      try {
        lock.lock();
      } catch (LockException e) {
        caughtException.set(e);
      }
    });
    acquiringThread.start();

    // Wait for thread to start, then interrupt it
    Thread.sleep(100);
    acquiringThread.interrupt();
    acquiringThread.join(5_000);

    canRelease.countDown();
    holder.join(5_000);

    assertNotNull("LockException should be thrown when thread is interrupted",
        caughtException.get());
    assertNotNull("LockException should have a cause",
        caughtException.get().getCause());
    assertTrue("Cause should be InterruptedException",
        caughtException.get().getCause() instanceof InterruptedException);
  }

  /**
   * When ignoreThreadInterruption=true, the first interruption is retried. If the
   * retry succeeds (lock available), the thread's interrupt flag is re-set and the
   * lock is acquired.
   */
  @Test(timeout = 10_000)
  public void testIgnoreThreadInterruptionRetries() throws Exception {
    // Use a short timeout; lock is not contended so retry will succeed
    var lock = new AdaptiveLock(true, 5000, true);

    var lockAcquired = new AtomicBoolean(false);
    var interruptedAfter = new AtomicBoolean(false);

    var thread = new Thread(() -> {
      // Interrupt the thread before calling lock — tryLock will throw
      // InterruptedException on the first attempt, then retry succeeds
      // because the lock is not contended
      Thread.currentThread().interrupt();
      try {
        lock.lock();
        lockAcquired.set(true);
        interruptedAfter.set(Thread.currentThread().isInterrupted());
      } finally {
        if (lockAcquired.get()) {
          lock.unlock();
        }
      }
    });
    thread.start();
    thread.join(5_000);

    assertTrue("Lock should be acquired after retry", lockAcquired.get());
    assertTrue("Interrupt flag should be re-set after retry",
        interruptedAfter.get());
  }

  /**
   * When ignoreThreadInterruption=true: the first tryLock throws InterruptedException
   * (clearing the interrupt flag). The retry tryLock then waits up to the timeout but
   * cannot acquire the lock (still held), so it returns false. Execution falls through
   * to throw LockException (not TimeoutException) with the original InterruptedException
   * as cause.
   */
  @Test(timeout = 10_000)
  public void testInterruptRetryTimesOutThrowsLockException() throws Exception {
    var lock = new AdaptiveLock(true, 200, true);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    var caughtException = new AtomicReference<LockException>();
    var interruptPreserved = new AtomicBoolean(false);
    var acquiringThread = new Thread(() -> {
      // Pre-interrupt: first tryLock throws InterruptedException (clears flag),
      // retry tryLock times out because the lock is still held by holder
      Thread.currentThread().interrupt();
      try {
        lock.lock();
      } catch (LockException e) {
        caughtException.set(e);
        interruptPreserved.set(Thread.currentThread().isInterrupted());
      }
    });
    acquiringThread.start();
    acquiringThread.join(5_000);

    canRelease.countDown();
    holder.join(5_000);

    assertNotNull("LockException should be thrown on interrupt + retry timeout",
        caughtException.get());
    assertNotNull("LockException should have a cause",
        caughtException.get().getCause());
    assertTrue("Cause should be InterruptedException",
        caughtException.get().getCause() instanceof InterruptedException);
  }

  // --- tryAcquireLock ---

  /**
   * tryAcquireLock() without timeout succeeds when lock is available.
   */
  @Test
  public void testTryAcquireLockNoTimeoutSucceeds() {
    var lock = new AdaptiveLock();
    assertTrue("tryAcquireLock should succeed on uncontended lock",
        lock.tryAcquireLock());
    lock.unlock();
  }

  /**
   * tryAcquireLock() without timeout fails when lock is held by another thread.
   */
  @Test(timeout = 10_000)
  public void testTryAcquireLockNoTimeoutFails() throws Exception {
    var lock = new AdaptiveLock();
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    assertFalse("tryAcquireLock should fail when lock is held",
        lock.tryAcquireLock());

    canRelease.countDown();
    holder.join(5_000);
  }

  /**
   * tryAcquireLock() no-arg on a lock with timeout > 0 uses the timed tryLock path
   * (delegates with instance timeout). Succeeds when lock is available.
   */
  @Test
  public void testTryAcquireLockNoArgOnTimedLockSucceeds() {
    var lock = new AdaptiveLock(5000);
    assertTrue("tryAcquireLock() on timed lock should succeed when uncontended",
        lock.tryAcquireLock());
    lock.unlock();
  }

  /**
   * tryAcquireLock() no-arg on a lock with timeout > 0 returns false when the lock
   * cannot be acquired within the instance timeout.
   */
  @Test(timeout = 10_000)
  public void testTryAcquireLockNoArgOnTimedLockFails() throws Exception {
    var lock = new AdaptiveLock(100);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    assertFalse("tryAcquireLock() should return false when timed lock is held",
        lock.tryAcquireLock());

    canRelease.countDown();
    holder.join(5_000);
  }

  /**
   * tryAcquireLock(timeout, unit) succeeds when lock becomes available within timeout.
   */
  @Test
  public void testTryAcquireLockWithTimeoutSucceeds() {
    var lock = new AdaptiveLock(5000);
    assertTrue("tryAcquireLock with timeout should succeed on uncontended lock",
        lock.tryAcquireLock(1000, TimeUnit.MILLISECONDS));
    lock.unlock();
  }

  /**
   * tryAcquireLock(timeout, unit) returns false when timeout expires while lock is held.
   */
  @Test(timeout = 10_000)
  public void testTryAcquireLockWithTimeoutExpires() throws Exception {
    var lock = new AdaptiveLock(5000);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    assertFalse("tryAcquireLock should return false when timeout expires",
        lock.tryAcquireLock(100, TimeUnit.MILLISECONDS));

    canRelease.countDown();
    holder.join(5_000);
  }

  /**
   * In non-concurrent mode, tryAcquireLock always returns true without acquiring
   * the underlying lock.
   */
  @Test
  public void testTryAcquireLockNonConcurrentAlwaysTrue() {
    var lock = new AdaptiveLock(false);
    assertTrue("Non-concurrent tryAcquireLock should always return true",
        lock.tryAcquireLock());
    assertFalse("Underlying lock should not be held in non-concurrent mode",
        lock.isHeldByCurrentThread());
  }

  /**
   * tryAcquireLock with timeout in non-concurrent mode always returns true.
   */
  @Test
  public void testTryAcquireLockWithTimeoutNonConcurrentAlwaysTrue() {
    var lock = new AdaptiveLock(false, 0, false);
    assertTrue("Non-concurrent tryAcquireLock(timeout) should always return true",
        lock.tryAcquireLock(100, TimeUnit.MILLISECONDS));
  }

  /**
   * tryAcquireLock throws LockException with InterruptedException cause when thread
   * is interrupted during timed wait.
   */
  @Test(timeout = 10_000)
  public void testTryAcquireLockInterruptionThrowsLockException() throws Exception {
    var lock = new AdaptiveLock(5000);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    var caughtException = new AtomicReference<LockException>();
    var thread = new Thread(() -> {
      try {
        lock.tryAcquireLock(5000, TimeUnit.MILLISECONDS);
      } catch (LockException e) {
        caughtException.set(e);
      }
    });
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join(5_000);

    canRelease.countDown();
    holder.join(5_000);

    assertNotNull(
        "LockException should be thrown when interrupted during tryAcquireLock",
        caughtException.get());
    assertNotNull("LockException should have a cause",
        caughtException.get().getCause());
    assertTrue("Cause should be InterruptedException",
        caughtException.get().getCause() instanceof InterruptedException);
  }

  // --- callInLock (inherited from AbstractLock) ---

  /**
   * callInLock executes the callable while holding the lock and returns its result.
   */
  @Test
  public void testCallInLockExecutesCallable() throws Exception {
    var lock = new AdaptiveLock();
    String result = lock.callInLock(() -> {
      assertTrue("Lock should be held during callable execution",
          lock.isHeldByCurrentThread());
      return "hello";
    });
    assertEquals("callInLock should return the callable's result", "hello", result);
    assertFalse("Lock should be released after callInLock",
        lock.isHeldByCurrentThread());
  }

  /**
   * callInLock propagates the callable's exception and releases the lock.
   */
  @Test
  public void testCallInLockReleasesOnException() {
    var lock = new AdaptiveLock();
    try {
      lock.callInLock((Callable<Void>) () -> {
        throw new RuntimeException("test");
      });
      fail("callInLock should propagate the callable's exception");
    } catch (RuntimeException e) {
      assertEquals("test", e.getMessage());
    } catch (Exception e) {
      fail("Unexpected exception type: " + e.getClass().getName());
    }
    assertFalse("Lock should be released even after callable throws",
        lock.isHeldByCurrentThread());
  }

  /**
   * callInLock works in non-concurrent mode — lock/unlock are no-ops but the
   * callable is still executed and returns its result.
   */
  @Test
  public void testCallInLockNonConcurrentExecutesCallable() throws Exception {
    var lock = new AdaptiveLock(false);
    String result = lock.callInLock(() -> "non-concurrent result");
    assertEquals("callInLock should return result in non-concurrent mode",
        "non-concurrent result", result);
  }

  // --- close() ---

  /**
   * close() unlocks the lock when it is held.
   */
  @Test
  public void testCloseUnlocksWhenHeld() {
    var lock = new AdaptiveLock();
    lock.lock();
    assertTrue("Lock should be held before close()",
        lock.isHeldByCurrentThread());
    lock.close();
    assertFalse("Lock should be unlocked after close()",
        lock.isHeldByCurrentThread());
  }

  /**
   * close() does not throw when the lock is not held.
   */
  @Test
  public void testCloseWhenNotHeldDoesNotThrow() {
    var lock = new AdaptiveLock();
    // Should not throw — close() catches the exception internally
    lock.close();
  }

  /**
   * close() does not throw when the lock is held by a different thread.
   * The underlying lock.isLocked() returns true, but unlock() throws
   * IllegalMonitorStateException — close() catches it.
   */
  @Test(timeout = 10_000)
  public void testCloseWhenHeldByAnotherThreadDoesNotThrow() throws Exception {
    var lock = new AdaptiveLock();
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = holdLockInBackground(lock, lockHeld, canRelease);
    assertTrue("Holder should acquire lock", lockHeld.await(5, TimeUnit.SECONDS));

    // close() on current thread — lock.isLocked() is true but not by us
    lock.close(); // Should not throw

    canRelease.countDown();
    holder.join(5_000);
  }
}
