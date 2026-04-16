package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Unit tests for {@link ReadersWriterSpinLock}. The existing ReadersWriterSpinLockTst is a
 * benchmark (@Ignore'd), not a unit test. These tests cover basic acquire/release, reentrant
 * reads, write-inside-read, tryAcquireReadLock, and multi-threaded reader/writer exclusion.
 */
public class ReadersWriterSpinLockTest {

  // --- Basic acquire/release ---

  /** Basic read lock acquire and release does not throw. */
  @Test
  public void testBasicReadLockAcquireRelease() {
    var lock = new ReadersWriterSpinLock();
    lock.acquireReadLock();
    lock.releaseReadLock();
  }

  /** Basic write lock acquire and release does not throw. */
  @Test
  public void testBasicWriteLockAcquireRelease() {
    var lock = new ReadersWriterSpinLock();
    lock.acquireWriteLock();
    lock.releaseWriteLock();
  }

  // --- Reentrant reads ---

  /**
   * Same thread can acquire read lock twice (reentrant). Requires two releases.
   */
  @Test
  public void testReentrantReadLock() {
    var lock = new ReadersWriterSpinLock();
    lock.acquireReadLock();
    lock.acquireReadLock();
    lock.releaseReadLock();
    lock.releaseReadLock();
  }

  // --- Write inside read ---

  /**
   * When a write lock is held, acquiring a read lock on the same thread is a no-op
   * (holds < 0 path in acquireReadLock). Similarly, releasing the read lock is a
   * no-op (holds < 0 path in releaseReadLock). This tests the write→read nesting.
   * Note: the reverse (read→write) would deadlock because the writer spins on
   * distributedCounter==0 but the reader already incremented it.
   */
  @Test(timeout = 10_000)
  public void testReadInsideWriteIsNoOp() {
    var lock = new ReadersWriterSpinLock();
    lock.acquireWriteLock();
    // Read lock inside write: this is a no-op because lockHolds < 0
    lock.acquireReadLock();
    lock.releaseReadLock();
    lock.releaseWriteLock();
  }

  /** Reentrant write lock: same thread acquires write lock twice. */
  @Test(timeout = 10_000)
  public void testReentrantWriteLock() {
    var lock = new ReadersWriterSpinLock();
    lock.acquireWriteLock();
    lock.acquireWriteLock();
    lock.releaseWriteLock();
    lock.releaseWriteLock();
  }

  // --- tryAcquireReadLock ---

  /** tryAcquireReadLock succeeds when no writer holds the lock. */
  @Test
  public void testTryAcquireReadLockSucceeds() {
    var lock = new ReadersWriterSpinLock();
    assertTrue("tryAcquireReadLock should succeed with no writer",
        lock.tryAcquireReadLock(TimeUnit.SECONDS.toNanos(5)));
    lock.releaseReadLock();
  }

  /** tryAcquireReadLock returns false when writer holds the lock and timeout expires. */
  @Test(timeout = 10_000)
  public void testTryAcquireReadLockTimesOutWhenWriterHolds() throws Exception {
    var lock = new ReadersWriterSpinLock();
    var writeHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var writer = new Thread(() -> {
      lock.acquireWriteLock();
      writeHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      lock.releaseWriteLock();
    });
    writer.start();
    assertTrue(writeHeld.await(5, TimeUnit.SECONDS));

    // tryAcquireReadLock with short timeout should fail
    assertFalse("tryAcquireReadLock should fail when writer holds lock",
        lock.tryAcquireReadLock(TimeUnit.MILLISECONDS.toNanos(200)));

    canRelease.countDown();
    writer.join(5_000);
  }

  /** tryAcquireReadLock inside write lock returns true immediately (holds < 0 path). */
  @Test(timeout = 10_000)
  public void testTryAcquireReadLockInsideWriteIsNoOp() {
    var lock = new ReadersWriterSpinLock();
    lock.acquireWriteLock();
    assertTrue("tryAcquireReadLock inside write lock should return true",
        lock.tryAcquireReadLock(TimeUnit.MILLISECONDS.toNanos(100)));
    lock.releaseReadLock();
    lock.releaseWriteLock();
  }

  /** tryAcquireReadLock with reentrant read returns true immediately. */
  @Test
  public void testTryAcquireReadLockReentrant() {
    var lock = new ReadersWriterSpinLock();
    lock.acquireReadLock();
    assertTrue("Reentrant tryAcquireReadLock should succeed",
        lock.tryAcquireReadLock(TimeUnit.MILLISECONDS.toNanos(100)));
    lock.releaseReadLock();
    lock.releaseReadLock();
  }

  // --- Multi-threaded: multiple concurrent readers ---

  /** Multiple threads can hold read locks simultaneously. */
  @Test(timeout = 10_000)
  public void testMultipleConcurrentReaders() throws Exception {
    var lock = new ReadersWriterSpinLock();
    int readerCount = 4;
    var allAcquired = new CountDownLatch(readerCount);
    var canRelease = new CountDownLatch(1);

    var readers = new Thread[readerCount];
    for (int i = 0; i < readerCount; i++) {
      readers[i] = new Thread(() -> {
        lock.acquireReadLock();
        allAcquired.countDown();
        try {
          canRelease.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        lock.releaseReadLock();
      });
      readers[i].start();
    }

    assertTrue("All readers should acquire locks concurrently",
        allAcquired.await(5, TimeUnit.SECONDS));

    canRelease.countDown();
    for (var reader : readers) {
      reader.join(5_000);
    }
  }

  // --- Multi-threaded: writer blocks pending readers ---

  /**
   * Writer blocks readers: while write lock is held, reader threads block until
   * the write lock is released.
   */
  @Test(timeout = 10_000)
  public void testWriterBlocksReaders() throws Exception {
    var lock = new ReadersWriterSpinLock();
    var writeHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);
    var readerAcquired = new AtomicBoolean(false);

    var writer = new Thread(() -> {
      lock.acquireWriteLock();
      writeHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      lock.releaseWriteLock();
    });
    writer.start();
    assertTrue(writeHeld.await(5, TimeUnit.SECONDS));

    var reader = new Thread(() -> {
      lock.acquireReadLock();
      readerAcquired.set(true);
      lock.releaseReadLock();
    });
    reader.start();

    Thread.sleep(200);
    assertFalse("Reader should be blocked while writer holds lock",
        readerAcquired.get());

    canRelease.countDown();
    reader.join(5_000);
    writer.join(5_000);
    assertTrue("Reader should acquire lock after writer released",
        readerAcquired.get());
  }

  /**
   * Writer waits for readers to drain: a write lock blocks until all active
   * readers have released their locks.
   */
  @Test(timeout = 10_000)
  public void testWriterWaitsForReadersToDrain() throws Exception {
    var lock = new ReadersWriterSpinLock();
    var readHeld = new CountDownLatch(1);
    var canReleaseRead = new CountDownLatch(1);
    var writeAcquired = new AtomicBoolean(false);

    var reader = new Thread(() -> {
      lock.acquireReadLock();
      readHeld.countDown();
      try {
        canReleaseRead.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      lock.releaseReadLock();
    });
    reader.start();
    assertTrue(readHeld.await(5, TimeUnit.SECONDS));

    var writer = new Thread(() -> {
      lock.acquireWriteLock();
      writeAcquired.set(true);
      lock.releaseWriteLock();
    });
    writer.start();

    Thread.sleep(200);
    assertFalse("Writer should be blocked while reader holds lock",
        writeAcquired.get());

    canReleaseRead.countDown();
    writer.join(5_000);
    reader.join(5_000);
    assertTrue("Writer should acquire lock after reader released",
        writeAcquired.get());
  }
}
