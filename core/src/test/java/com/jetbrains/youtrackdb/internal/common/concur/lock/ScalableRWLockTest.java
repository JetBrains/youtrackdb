package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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

  // --- Reentrancy tests ---

  /**
   * Verifies that nested sharedLock()/sharedUnlock() calls track depth correctly: two locks
   * require two unlocks, and isReadLocked() reflects the state at each level.
   */
  @Test(timeout = 10_000)
  public void testNestedSharedLockTracksDepthCorrectly() {
    final var lock = new ScalableRWLock();

    assertFalse("Should not be read-locked initially", lock.isReadLocked());

    lock.sharedLock();
    assertTrue("Should be read-locked after first lock", lock.isReadLocked());

    lock.sharedLock();
    assertTrue("Should be read-locked after second (nested) lock",
        lock.isReadLocked());

    lock.sharedUnlock();
    assertTrue("Should still be read-locked after first unlock (one level remains)",
        lock.isReadLocked());

    lock.sharedUnlock();
    assertFalse("Should not be read-locked after all unlocks", lock.isReadLocked());
  }

  /**
   * Verifies that a writer is blocked while any nesting level of the read lock is held,
   * and succeeds only after all nesting levels are unlocked.
   */
  @Test(timeout = 10_000)
  public void testWriterBlocksUntilAllReentrantReadLocksReleased() throws Exception {
    final var lock = new ScalableRWLock();
    final var writerAcquired = new AtomicBoolean(false);
    final var writerStarted = new CountDownLatch(1);

    // Acquire read lock twice (nested)
    lock.sharedLock();
    lock.sharedLock();

    var writerThread = new Thread(() -> {
      writerStarted.countDown();
      lock.exclusiveLock();
      writerAcquired.set(true);
      lock.exclusiveUnlock();
    });
    writerThread.start();
    assertTrue("Writer thread should start",
        writerStarted.await(5, TimeUnit.SECONDS));

    // Release one level — writer should still be blocked
    lock.sharedUnlock();
    Thread.sleep(200);
    assertFalse("Writer should be blocked while one read-lock level remains",
        writerAcquired.get());

    // Release the last level — writer should proceed
    lock.sharedUnlock();
    writerThread.join(5_000);
    assertTrue("Writer should acquire lock after all read levels released",
        writerAcquired.get());
  }

  /**
   * Verifies that calling sharedUnlock() more times than sharedLock() throws
   * IllegalMonitorStateException (underflow protection).
   */
  @Test(timeout = 10_000)
  public void testUnlockWithoutLockThrowsIllegalMonitorState() {
    final var lock = new ScalableRWLock();

    // Unlock without any lock should throw
    try {
      lock.sharedUnlock();
      fail("Should throw IllegalMonitorStateException for unlock without lock");
    } catch (IllegalMonitorStateException expected) {
      // expected
    }
  }

  /**
   * Verifies that extra sharedUnlock() after balanced lock/unlock throws
   * IllegalMonitorStateException.
   */
  @Test(timeout = 10_000)
  public void testExtraUnlockAfterBalancedPairThrows() {
    final var lock = new ScalableRWLock();

    lock.sharedLock();
    lock.sharedUnlock();

    // Extra unlock should throw — count is now 0
    try {
      lock.sharedUnlock();
      fail("Should throw IllegalMonitorStateException for extra unlock");
    } catch (IllegalMonitorStateException expected) {
      // expected
    }
  }

  /**
   * Verifies that sharedTryLock() supports reentrancy: a nested tryLock succeeds when the
   * read lock is already held, even if a writer is waiting.
   */
  @Test(timeout = 10_000)
  public void testSharedTryLockReentrant() throws Exception {
    final var lock = new ScalableRWLock();

    lock.sharedLock();
    // Nested tryLock should succeed (reentrant fast path)
    assertTrue("Nested sharedTryLock should succeed", lock.sharedTryLock());
    assertTrue("isReadLocked should be true at depth 2", lock.isReadLocked());

    lock.sharedUnlock();
    lock.sharedUnlock();
    assertFalse("Should not be read-locked after all unlocks", lock.isReadLocked());
  }

  /**
   * Verifies that sharedTryLockNanos() supports reentrancy: a nested tryLockNanos succeeds
   * immediately when the read lock is already held.
   */
  @Test(timeout = 10_000)
  public void testSharedTryLockNanosReentrant() throws Exception {
    final var lock = new ScalableRWLock();

    lock.sharedLock();
    // Nested tryLockNanos should succeed immediately (reentrant fast path)
    assertTrue("Nested sharedTryLockNanos should succeed",
        lock.sharedTryLockNanos(TimeUnit.MILLISECONDS.toNanos(1)));

    lock.sharedUnlock();
    lock.sharedUnlock();
    assertFalse("Should not be read-locked after all unlocks", lock.isReadLocked());
  }

  /**
   * Verifies that sharedTryLock() as the outermost acquisition followed by nested sharedLock()
   * works correctly (tryLock sets reentrantCount=1, nested lock increments it).
   */
  @Test(timeout = 10_000)
  public void testTryLockOutermostThenNestedSharedLock() {
    final var lock = new ScalableRWLock();

    assertTrue("Outermost tryLock should succeed", lock.sharedTryLock());
    assertTrue(lock.isReadLocked());

    lock.sharedLock(); // nested
    assertTrue(lock.isReadLocked());

    lock.sharedUnlock(); // inner
    assertTrue("Still locked at depth 1", lock.isReadLocked());

    lock.sharedUnlock(); // outer
    assertFalse("Fully unlocked", lock.isReadLocked());
  }

  /**
   * Verifies that isReadLocked() returns false on a thread that has never acquired the lock.
   */
  @Test(timeout = 10_000)
  public void testIsReadLockedReturnsFalseOnFreshThread() throws Exception {
    final var lock = new ScalableRWLock();
    final var result = new AtomicBoolean(true);
    final var done = new CountDownLatch(1);

    var thread = new Thread(() -> {
      result.set(lock.isReadLocked());
      done.countDown();
    });
    thread.start();
    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertFalse("isReadLocked should be false on a thread that never locked",
        result.get());
  }

  /**
   * Concurrent stress test: multiple reader threads perform nested read locks while one writer
   * thread periodically acquires the write lock. Verifies no deadlocks or data corruption
   * under contention.
   */
  @Test(timeout = 30_000)
  public void testConcurrentNestedReadsWithWriter() throws Exception {
    final var lock = new ScalableRWLock();
    final int readerCount = 4;
    final int iterationsPerReader = 5_000;
    final var sharedCounter = new AtomicInteger(0);
    final var errors = new AtomicReference<Throwable>(null);
    final var barrier = new CyclicBarrier(readerCount + 1);

    var threads = new ArrayList<Thread>();

    // Reader threads: nested read locks, increment a shared counter
    for (int r = 0; r < readerCount; r++) {
      var thread = new Thread(() -> {
        try {
          barrier.await();
          for (int i = 0; i < iterationsPerReader; i++) {
            lock.sharedLock();
            try {
              lock.sharedLock(); // nested
              try {
                sharedCounter.incrementAndGet();
              } finally {
                lock.sharedUnlock();
              }
            } finally {
              lock.sharedUnlock();
            }
          }
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        }
      });
      threads.add(thread);
      thread.start();
    }

    // Writer thread: periodically acquires the write lock
    var writerThread = new Thread(() -> {
      try {
        barrier.await();
        for (int i = 0; i < 100; i++) {
          lock.exclusiveLock();
          lock.exclusiveUnlock();
          Thread.yield();
        }
      } catch (Throwable t) {
        errors.compareAndSet(null, t);
      }
    });
    threads.add(writerThread);
    writerThread.start();

    for (var thread : threads) {
      thread.join(25_000);
      assertFalse("Thread should have terminated: " + thread.getName(),
          thread.isAlive());
    }

    if (errors.get() != null) {
      throw new AssertionError("Thread failed with exception", errors.get());
    }

    assertEquals("All reader increments should be visible",
        readerCount * iterationsPerReader, sharedCounter.get());
  }

  /**
   * Verifies that a thread dying with a reentrant read lock (count > 1) is cleaned up
   * correctly by the Cleaner — the state is reset to NOT_READING, unblocking writers.
   */
  @Test(timeout = 30_000)
  public void testCleanerHandlesReentrantLockOnThreadDeath() throws Exception {
    final var lock = new ScalableRWLock();
    final var lockAcquired = new CountDownLatch(1);

    // Thread acquires nested read locks and dies without unlocking
    var thread = new Thread(() -> {
      lock.sharedLock();
      lock.sharedLock(); // nested — reentrantCount = 2
      lockAcquired.countDown();
      // Thread exits without unlocking
    });
    thread.start();
    assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));
    thread.join(5_000);
    assertFalse("Thread should have terminated", thread.isAlive());

    //noinspection UnusedAssignment
    thread = null;

    // Wait for Cleaner to reset the state
    for (int i = 0; i < 50; i++) {
      System.gc();
      Thread.sleep(100);

      if (lock.exclusiveTryLockNanos(TimeUnit.MILLISECONDS.toNanos(50))) {
        lock.exclusiveUnlock();
        return; // Success
      }
    }

    throw new AssertionError(
        "Writer could not acquire lock after reentrant reader thread death; "
            + "Cleaner did not reset the ghost reader state");
  }
}
