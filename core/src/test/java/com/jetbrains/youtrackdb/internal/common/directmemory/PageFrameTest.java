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
}
