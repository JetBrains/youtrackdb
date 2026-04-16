package com.jetbrains.youtrackdb.internal.common.directmemory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link PageFrame} — validates lock API correctness, page coordinate lifecycle,
 * and buffer access.
 */
public class PageFrameTest {

  private DirectMemoryAllocator allocator;
  private Pointer pointer;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    pointer = allocator.allocate(4096, true, Intention.TEST);
  }

  @After
  public void tearDown() {
    allocator.deallocate(pointer);
  }

  // --- Optimistic Read API ---

  @Test
  public void testOptimisticReadReturnsNonZeroStamp() {
    // Verifies that tryOptimisticRead returns a non-zero stamp when no writer is active,
    // indicating the stamp is valid for subsequent validation.
    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    assertNotEquals(0, stamp);
  }

  @Test
  public void testValidateSucceedsWhenNoWriterIntervenes() {
    // Verifies that validate returns true when the stamp has not been invalidated
    // by an exclusive lock acquisition between tryOptimisticRead and validate.
    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    assertTrue(frame.validate(stamp));
  }

  @Test
  public void testValidateFailsAfterExclusiveLock() {
    // Verifies that acquiring and releasing an exclusive lock invalidates all
    // outstanding optimistic stamps. This is the mechanism that makes eviction
    // visible to optimistic readers.
    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    long writeStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(writeStamp);

    assertFalse(frame.validate(stamp));
  }

  @Test
  public void testOptimisticReadReturnsZeroDuringExclusiveLock() {
    // Verifies that tryOptimisticRead returns 0 when the exclusive lock is held,
    // signaling callers that optimistic reading is not currently possible.
    var frame = new PageFrame(pointer);
    long writeStamp = frame.acquireExclusiveLock();
    try {
      long stamp = frame.tryOptimisticRead();
      assertEquals(0, stamp);
    } finally {
      frame.releaseExclusiveLock(writeStamp);
    }
  }

  // --- Exclusive Lock API ---

  @Test
  public void testExclusiveLockAcquireRelease() {
    // Verifies basic exclusive lock acquire/release cycle. The returned stamp
    // must be non-zero and accepted by releaseExclusiveLock.
    var frame = new PageFrame(pointer);
    long stamp = frame.acquireExclusiveLock();
    assertNotEquals(0, stamp);
    frame.releaseExclusiveLock(stamp);
  }

  // --- Shared Lock API ---

  @Test
  public void testSharedLockAcquireRelease() {
    // Verifies basic shared lock acquire/release cycle. Shared locks allow
    // concurrent readers but block exclusive lock acquisition.
    var frame = new PageFrame(pointer);
    long stamp = frame.acquireSharedLock();
    assertNotEquals(0, stamp);
    frame.releaseSharedLock(stamp);
  }

  @Test
  public void testMultipleSharedLocksAllowed() {
    // Verifies that multiple shared locks can be held simultaneously.
    var frame = new PageFrame(pointer);
    long stamp1 = frame.acquireSharedLock();
    long stamp2 = frame.acquireSharedLock();
    assertNotEquals(0, stamp1);
    assertNotEquals(0, stamp2);
    frame.releaseSharedLock(stamp1);
    frame.releaseSharedLock(stamp2);
  }

  @Test
  public void testOptimisticValidateSucceedsWithSharedLockHeld() {
    // Shared locks should not invalidate optimistic stamps — only exclusive
    // locks invalidate them. This is important because the fallback read path
    // uses shared locks and should not interfere with concurrent optimistic readers.
    var frame = new PageFrame(pointer);
    long optimisticStamp = frame.tryOptimisticRead();
    long sharedStamp = frame.acquireSharedLock();
    assertTrue(frame.validate(optimisticStamp));
    frame.releaseSharedLock(sharedStamp);
  }

  // --- Try Acquire Shared Lock API ---

  @Test
  public void testTryAcquireSharedLockSucceedsWhenUnlocked() {
    // Verifies that tryAcquireSharedLock returns a non-zero stamp when no exclusive
    // lock is held, indicating successful non-blocking acquisition.
    var frame = new PageFrame(pointer);
    long stamp = frame.tryAcquireSharedLock();
    assertNotEquals(0, stamp);
    frame.releaseSharedLock(stamp);
  }

  @Test
  public void testTryAcquireSharedLockFailsWhenExclusiveLockHeld() {
    // Verifies that tryAcquireSharedLock returns 0 when the exclusive lock is held,
    // indicating the non-blocking attempt failed.
    var frame = new PageFrame(pointer);
    long exclusiveStamp = frame.acquireExclusiveLock();
    try {
      long stamp = frame.tryAcquireSharedLock();
      assertEquals(0, stamp);
    } finally {
      frame.releaseExclusiveLock(exclusiveStamp);
    }
  }

  @Test
  public void testTryAcquireSharedLockCoexistsWithOtherSharedLocks() {
    // Verifies that tryAcquireSharedLock succeeds even when other shared locks
    // are already held — shared locks are non-exclusive by design.
    var frame = new PageFrame(pointer);
    long sharedStamp1 = frame.acquireSharedLock();
    long sharedStamp2 = frame.tryAcquireSharedLock();
    assertNotEquals(0, sharedStamp2);
    frame.releaseSharedLock(sharedStamp1);
    frame.releaseSharedLock(sharedStamp2);
  }

  @Test
  public void testTryAcquireSharedLockStampValidForRelease() {
    // Verifies that a stamp from tryAcquireSharedLock can be used to release
    // the shared lock, and that after release an exclusive lock can be acquired.
    var frame = new PageFrame(pointer);
    long stamp = frame.tryAcquireSharedLock();
    assertNotEquals(0, stamp);
    frame.releaseSharedLock(stamp);

    // After release, exclusive lock acquisition should succeed immediately
    long exclusiveStamp = frame.acquireExclusiveLock();
    assertNotEquals(0, exclusiveStamp);
    frame.releaseExclusiveLock(exclusiveStamp);
  }

  @Test
  public void testTryAcquireSharedLockDoesNotInvalidateOptimisticStamps() {
    // Shared locks (including try-acquired) must not invalidate optimistic stamps —
    // only exclusive locks invalidate them. This is the symmetric test to
    // testOptimisticValidateSucceedsWithSharedLockHeld for the try-variant.
    var frame = new PageFrame(pointer);
    long optimisticStamp = frame.tryOptimisticRead();
    assertNotEquals(0, optimisticStamp);

    long sharedStamp = frame.tryAcquireSharedLock();
    assertNotEquals(0, sharedStamp);
    assertTrue(frame.validate(optimisticStamp));
    frame.releaseSharedLock(sharedStamp);
  }

  // --- Try Acquire Exclusive Lock API ---

  @Test
  public void testTryAcquireExclusiveLockSucceedsWhenUnlocked() {
    // Verifies that tryAcquireExclusiveLock returns a non-zero stamp when the lock
    // is completely free.
    var frame = new PageFrame(pointer);
    long stamp = frame.tryAcquireExclusiveLock();
    assertNotEquals(0, stamp);
    frame.releaseExclusiveLock(stamp);
  }

  @Test
  public void testTryAcquireExclusiveLockFailsWhenSharedLockHeld() {
    // Verifies that tryAcquireExclusiveLock returns 0 when a shared lock is held,
    // since exclusive and shared locks are mutually exclusive.
    var frame = new PageFrame(pointer);
    long sharedStamp = frame.acquireSharedLock();
    try {
      long stamp = frame.tryAcquireExclusiveLock();
      assertEquals(0, stamp);
    } finally {
      frame.releaseSharedLock(sharedStamp);
    }
  }

  @Test
  public void testTryAcquireExclusiveLockFailsWhenExclusiveLockHeld() {
    // Verifies that tryAcquireExclusiveLock returns 0 when an exclusive lock
    // is already held (non-reentrant).
    var frame = new PageFrame(pointer);
    long exclusiveStamp = frame.acquireExclusiveLock();
    try {
      long stamp = frame.tryAcquireExclusiveLock();
      assertEquals(0, stamp);
    } finally {
      frame.releaseExclusiveLock(exclusiveStamp);
    }
  }

  @Test
  public void testTryAcquireExclusiveLockInvalidatesOptimisticStamps() {
    // Verifies that successfully acquiring an exclusive lock via tryAcquireExclusiveLock
    // invalidates outstanding optimistic stamps — same behavior as acquireExclusiveLock.
    var frame = new PageFrame(pointer);
    long optimisticStamp = frame.tryOptimisticRead();
    assertNotEquals(0, optimisticStamp);

    long exclusiveStamp = frame.tryAcquireExclusiveLock();
    assertNotEquals(0, exclusiveStamp);
    frame.releaseExclusiveLock(exclusiveStamp);

    assertFalse(frame.validate(optimisticStamp));
  }

  @Test
  public void testTryAcquireExclusiveLockStampValidForRelease() {
    // Verifies that a stamp from tryAcquireExclusiveLock can be used to release
    // the exclusive lock, and that after release shared locks can be acquired.
    var frame = new PageFrame(pointer);
    long stamp = frame.tryAcquireExclusiveLock();
    assertNotEquals(0, stamp);
    frame.releaseExclusiveLock(stamp);

    // After release, shared lock acquisition should succeed
    long sharedStamp = frame.acquireSharedLock();
    assertNotEquals(0, sharedStamp);
    frame.releaseSharedLock(sharedStamp);
  }

  // --- Eviction Stamp Invalidation ---

  @Test
  public void testEvictionCycleInvalidatesOptimisticStampAndReassignsFrame() {
    // Simulates the eviction path: an optimistic reader takes a stamp on a frame assigned
    // to page (42, 7). Eviction acquires the exclusive lock, reassigns the frame to a
    // different page (simulating pool reuse), and releases. The reader's validate() must
    // return false, and the frame now serves a different page.
    var frame = new PageFrame(pointer);
    long setupStamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(42, 7);
    frame.releaseExclusiveLock(setupStamp);

    long optimisticStamp = frame.tryOptimisticRead();
    assertNotEquals(0, optimisticStamp);

    // Eviction: acquire exclusive lock, reassign frame to different page
    long exclusiveStamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(99, 13);
    frame.releaseExclusiveLock(exclusiveStamp);

    // Reader detects the invalidation
    assertFalse(frame.validate(optimisticStamp));
    // Frame now serves a different page
    assertEquals(99, frame.getFileId());
    assertEquals(13, frame.getPageIndex());
  }

  @Test
  public void testSharedLockStampInvalidatedByExclusiveLock() {
    // Verifies that a stamp obtained from acquireSharedLock() becomes invalid (via validate)
    // after an exclusive lock is acquired and released. This is the mechanism used by
    // WOWCache's copy-then-verify: the shared lock stamp from the copy phase is validated
    // in removeWrittenPagesFromCache after the page may have been modified.
    var frame = new PageFrame(pointer);

    // Copy phase: shared lock stamp
    long sharedStamp = frame.acquireSharedLock();
    frame.releaseSharedLock(sharedStamp);

    // No modification: stamp should still validate
    assertTrue(frame.validate(sharedStamp));

    // Writer modifies the page
    long exclusiveStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(exclusiveStamp);

    // Stamp from before the modification should be invalid
    assertFalse(frame.validate(sharedStamp));
  }

  // --- Page Coordinates ---

  @Test
  public void testInitialCoordinatesAreMinusOne() {
    // Newly created frames have (-1, -1) coordinates, indicating they are
    // not assigned to any page.
    var frame = new PageFrame(pointer);
    assertEquals(-1, frame.getFileId());
    assertEquals(-1, frame.getPageIndex());
  }

  @Test
  public void testSetPageCoordinates() {
    // Verifies that page coordinates can be set and read back correctly.
    var frame = new PageFrame(pointer);
    long stamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(42, 7);
    frame.releaseExclusiveLock(stamp);

    assertEquals(42, frame.getFileId());
    assertEquals(7, frame.getPageIndex());
  }

  // --- Buffer Access ---

  @Test
  public void testGetBufferReturnsValidBuffer() {
    // Verifies that getBuffer returns a non-null ByteBuffer with the correct capacity.
    var frame = new PageFrame(pointer);
    ByteBuffer buffer = frame.getBuffer();
    assertNotNull(buffer);
    assertEquals(4096, buffer.capacity());
  }

  @Test
  public void testGetPointerReturnsSamePointer() {
    // Verifies that getPointer returns the same Pointer that was passed to the constructor.
    var frame = new PageFrame(pointer);
    assertEquals(pointer, frame.getPointer());
  }

  @Test
  public void testClearZerosMemory() {
    // Verifies that clear() fills the frame's memory with zeros.
    var frame = new PageFrame(pointer);
    ByteBuffer buffer = frame.getBuffer();

    // Write non-zero data
    buffer.putInt(0, 0xDEADBEEF);
    assertNotEquals(0, buffer.getInt(0));

    // Clear and verify
    frame.clear();
    assertEquals(0, buffer.getInt(0));
  }

  @Test
  public void testBufferWriteAndRead() {
    // Verifies that data written to the buffer can be read back correctly.
    var frame = new PageFrame(pointer);
    ByteBuffer buffer = frame.getBuffer();
    buffer.putLong(0, 123456789L);
    assertEquals(123456789L, buffer.getLong(0));
  }

  /**
   * Verifies that initPageCoordinates sets file ID and page index without
   * requiring an exclusive lock (single-threaded initialization path).
   */
  @Test
  public void testInitPageCoordinates_setsCoordinatesWithoutLock() {
    var frame = new PageFrame(pointer);
    // initPageCoordinates is used during initial frame assignment before
    // the frame is visible to other threads.
    frame.initPageCoordinates(42L, 99);

    // Read under optimistic read to verify values were set.
    long stamp = frame.tryOptimisticRead();
    long fileId = frame.getFileId();
    int pageIndex = frame.getPageIndex();
    assertTrue("stamp should still be valid", stamp != 0 && frame.validate(stamp));
    assertEquals(42L, fileId);
    assertEquals(99, pageIndex);
  }

  /**
   * Verifies that initPageCoordinates can overwrite previously set coordinates
   * (used when re-assigning a pooled frame to a new page).
   */
  @Test
  public void testInitPageCoordinates_overwritesPreviousCoordinates() {
    var frame = new PageFrame(pointer);
    frame.initPageCoordinates(10L, 20);

    // Overwrite with new coordinates
    frame.initPageCoordinates(30L, 40);

    long stamp = frame.tryOptimisticRead();
    assertEquals(30L, frame.getFileId());
    assertEquals(40, frame.getPageIndex());
    assertTrue(frame.validate(stamp));
  }
}
