/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the StorageReadResult sealed interface hierarchy: RawBuffer,
 * RawPageBuffer, and their common contract.
 */
public class StorageReadResultTest {

  private DirectMemoryAllocator allocator;
  private Pointer pointer;
  private PageFrame pageFrame;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    pointer = allocator.allocate(4096, true, Intention.TEST);
    pageFrame = new PageFrame(pointer);
  }

  @After
  public void tearDown() {
    allocator.deallocate(pointer);
  }

  // --- RawBuffer satisfies StorageReadResult contract ---

  @Test
  public void testRawBufferIsStorageReadResult() {
    // Verifies that RawBuffer implements StorageReadResult and that
    // recordVersion() delegates to version(), recordType() matches.
    var rawBuffer = new RawBuffer(new byte[] {1, 2, 3}, 42L, (byte) 'd');

    StorageReadResult result = rawBuffer;
    assertEquals(42L, result.recordVersion());
    assertEquals((byte) 'd', result.recordType());
  }

  @Test
  public void testRawBufferRecordVersionDelegatesToVersion() {
    // Ensures recordVersion() returns the same value as version().
    var rawBuffer = new RawBuffer(new byte[0], 99L, (byte) 'b');
    assertEquals(99L, rawBuffer.recordVersion());
    assertEquals(99L, rawBuffer.version());
  }

  // --- RawPageBuffer satisfies StorageReadResult contract ---

  @Test
  public void testRawPageBufferIsStorageReadResult() {
    // Verifies that RawPageBuffer implements StorageReadResult with
    // correct version and type accessors.
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(pageFrame, stamp, 100, 50, 7L, (byte) 'd');

    StorageReadResult result = pageBuffer;
    assertEquals(7L, result.recordVersion());
    assertEquals((byte) 'd', result.recordType());
  }

  // --- RawPageBuffer.sliceContent() ---

  @Test
  public void testSliceContentReturnsCorrectRange() {
    // Writes known data into the page buffer at a specific offset, then
    // verifies that sliceContent() returns a ByteBuffer covering exactly
    // those bytes.
    ByteBuffer buffer = pageFrame.getBuffer();
    byte[] testData = {10, 20, 30, 40, 50};
    int contentOffset = 200;
    for (int i = 0; i < testData.length; i++) {
      buffer.put(contentOffset + i, testData[i]);
    }

    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer =
        new RawPageBuffer(pageFrame, stamp, contentOffset, testData.length, 1L, (byte) 'd');

    ByteBuffer slice = pageBuffer.sliceContent();
    assertEquals(testData.length, slice.capacity());
    assertEquals(0, slice.position());
    assertEquals(testData.length, slice.limit());

    byte[] actual = new byte[testData.length];
    slice.get(actual);
    assertArrayEquals(testData, actual);
  }

  @Test
  public void testSliceContentWithZeroLength() {
    // Verifies that sliceContent works correctly for an empty record content.
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(pageFrame, stamp, 0, 0, 1L, (byte) 'd');

    ByteBuffer slice = pageBuffer.sliceContent();
    assertEquals(0, slice.capacity());
    assertEquals(0, slice.position());
    assertEquals(0, slice.limit());
  }

  @Test
  public void testSliceContentSharesNativeMemory() {
    // Verifies that the sliced ByteBuffer reflects changes made to the
    // underlying page buffer — proving zero-copy semantics.
    ByteBuffer buffer = pageFrame.getBuffer();
    int contentOffset = 100;
    buffer.put(contentOffset, (byte) 0xAA);

    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(pageFrame, stamp, contentOffset, 1, 1L, (byte) 'd');
    ByteBuffer slice = pageBuffer.sliceContent();
    assertEquals((byte) 0xAA, slice.get(0));

    // Modify the underlying page buffer and verify the slice sees the change.
    buffer.put(contentOffset, (byte) 0xBB);
    assertEquals((byte) 0xBB, slice.get(0));
  }

  @Test
  public void testSliceContentAtExactPageBoundary() {
    // Record content fills from an offset to the very end of the page buffer,
    // verifying no off-by-one in ByteBuffer.slice() at the boundary.
    int capacity = pageFrame.getBuffer().capacity();
    int contentLength = 10;
    int contentOffset = capacity - contentLength;
    ByteBuffer buf = pageFrame.getBuffer();
    for (int i = 0; i < contentLength; i++) {
      buf.put(contentOffset + i, (byte) (i + 1));
    }

    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer =
        new RawPageBuffer(pageFrame, stamp, contentOffset, contentLength, 1L, (byte) 'd');

    ByteBuffer slice = pageBuffer.sliceContent();
    assertEquals(contentLength, slice.capacity());
    assertEquals((byte) 1, slice.get(0));
    assertEquals((byte) 10, slice.get(9));
  }

  // --- Sealed interface exhaustiveness ---

  @Test
  public void testPatternMatchExhaustiveness() {
    // Verifies that pattern matching on StorageReadResult covers both variants,
    // ensuring the sealed interface is exhaustive.
    StorageReadResult rawResult = new RawBuffer(new byte[] {1}, 1L, (byte) 'd');
    long stamp = pageFrame.tryOptimisticRead();
    StorageReadResult pageResult =
        new RawPageBuffer(pageFrame, stamp, 0, 1, 2L, (byte) 'b');

    // Pattern match on RawBuffer
    String rawMatch = switch (rawResult) {
      case RawBuffer rb -> "buffer:" + rb.buffer().length;
      case RawPageBuffer rpb -> "page:" + rpb.contentLength();
    };
    assertEquals("buffer:1", rawMatch);

    // Pattern match on RawPageBuffer
    String pageMatch = switch (pageResult) {
      case RawBuffer rb -> "buffer:" + rb.buffer().length;
      case RawPageBuffer rpb -> "page:" + rpb.contentLength();
    };
    assertEquals("page:1", pageMatch);
  }

  // --- RawPageBuffer field accessors ---

  @Test
  public void testRawPageBufferFieldAccessors() {
    // Verifies all record component accessors return the values passed
    // to the constructor.
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(pageFrame, stamp, 256, 128, 42L, (byte) 'v');

    assertEquals(pageFrame, pageBuffer.pageFrame());
    assertEquals(stamp, pageBuffer.stamp());
    assertEquals(256, pageBuffer.contentOffset());
    assertEquals(128, pageBuffer.contentLength());
    assertEquals(42L, pageBuffer.recordVersion());
    assertEquals((byte) 'v', pageBuffer.recordType());
  }

  // --- Boundary version values ---

  @Test
  public void testRawBufferWithZeroVersion() {
    // Version 0 is a valid boundary value (e.g., initial record version).
    var rawBuffer = new RawBuffer(new byte[] {1}, 0L, (byte) 'd');
    assertEquals(0L, rawBuffer.recordVersion());
  }

  @Test
  public void testRawBufferWithNegativeVersion() {
    // Negative versions may represent special states (-1 = tombstone/unversioned).
    var rawBuffer = new RawBuffer(new byte[] {1}, -1L, (byte) 'd');
    assertEquals(-1L, rawBuffer.recordVersion());
  }

  @Test
  public void testRawBufferWithMaxVersion() {
    // Long.MAX_VALUE is the extreme boundary for version values.
    var rawBuffer = new RawBuffer(new byte[] {1}, Long.MAX_VALUE, (byte) 'd');
    assertEquals(Long.MAX_VALUE, rawBuffer.recordVersion());
  }

  @Test
  public void testRawBufferWithNullBuffer() {
    // RawBuffer allows null buffer (e.g., deleted record or placeholder).
    var rawBuffer = new RawBuffer(null, 1L, (byte) 'd');
    StorageReadResult result = rawBuffer;
    assertEquals(1L, result.recordVersion());
    assertEquals((byte) 'd', result.recordType());
    org.junit.Assert.assertNull(rawBuffer.buffer());
  }

  @Test
  public void testRawPageBufferWithZeroVersion() {
    // Version 0 boundary for PageFrame path.
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(pageFrame, stamp, 0, 10, 0L, (byte) 'd');
    assertEquals(0L, pageBuffer.recordVersion());
  }

  @Test
  public void testRawPageBufferWithMaxVersion() {
    // Long.MAX_VALUE boundary for PageFrame path.
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(pageFrame, stamp, 0, 10, Long.MAX_VALUE, (byte) 'd');
    assertEquals(Long.MAX_VALUE, pageBuffer.recordVersion());
  }

  // --- RawPageBuffer runtime bounds validation ---

  @Test
  public void testRawPageBufferRejectsNullPageFrame() {
    // Validates that the constructor throws IllegalArgumentException
    // (not AssertionError) when pageFrame is null.
    var ex = assertThrows(IllegalArgumentException.class,
        () -> new RawPageBuffer(null, 0L, 0, 0, 1L, (byte) 'd'));
    assertEquals("PageFrame must not be null", ex.getMessage());
  }

  @Test
  public void testRawPageBufferRejectsNegativeContentOffset() {
    // Validates runtime rejection of negative contentOffset.
    long stamp = pageFrame.tryOptimisticRead();
    var ex = assertThrows(IllegalArgumentException.class,
        () -> new RawPageBuffer(pageFrame, stamp, -1, 10, 1L, (byte) 'd'));
    assertEquals("contentOffset must be non-negative: -1", ex.getMessage());
  }

  @Test
  public void testRawPageBufferRejectsNegativeContentLength() {
    // Validates runtime rejection of negative contentLength.
    long stamp = pageFrame.tryOptimisticRead();
    var ex = assertThrows(IllegalArgumentException.class,
        () -> new RawPageBuffer(pageFrame, stamp, 0, -1, 1L, (byte) 'd'));
    assertEquals("contentLength must be non-negative: -1", ex.getMessage());
  }

  @Test
  public void testRawPageBufferRejectsContentRegionExceedingCapacity() {
    // Validates that content region exceeding page buffer capacity is rejected.
    int capacity = pageFrame.getBuffer().capacity();
    long stamp = pageFrame.tryOptimisticRead();
    var ex = assertThrows(IllegalArgumentException.class,
        () -> new RawPageBuffer(pageFrame, stamp, capacity - 5, 10, 1L, (byte) 'd'));
    int expectedEnd = capacity - 5 + 10;
    assertEquals(
        "content region [" + (capacity - 5) + ", " + expectedEnd
            + ") exceeds page buffer capacity " + capacity,
        ex.getMessage());
  }

  @Test
  public void testRawPageBufferRejectsIntegerOverflowInContentRegion() {
    // Validates that Math.addExact catches integer overflow when
    // contentOffset + contentLength would exceed Integer.MAX_VALUE.
    long stamp = pageFrame.tryOptimisticRead();
    var ex = assertThrows(ArithmeticException.class,
        () -> new RawPageBuffer(
            pageFrame, stamp, Integer.MAX_VALUE, 1, 1L, (byte) 'd'));
    assertEquals("integer overflow", ex.getMessage());
  }

  @Test
  public void testRawPageBufferRejectsIntMinValueContentOffset() {
    // Integer.MIN_VALUE is the extreme negative boundary — validates no
    // unsigned interpretation or bit-twiddling issues in the bounds check.
    long stamp = pageFrame.tryOptimisticRead();
    var ex = assertThrows(IllegalArgumentException.class,
        () -> new RawPageBuffer(
            pageFrame, stamp, Integer.MIN_VALUE, 0, 1L, (byte) 'd'));
    assertTrue(ex.getMessage().contains("contentOffset"));
  }

  @Test
  public void testRawPageBufferAcceptsExactBoundary() {
    // Content region [0, capacity) exactly fills the page — should succeed.
    // Verify end-to-end via sliceContent to confirm no off-by-one.
    int capacity = pageFrame.getBuffer().capacity();
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(
        pageFrame, stamp, 0, capacity, 1L, (byte) 'd');
    assertEquals(capacity, pageBuffer.contentLength());
    assertEquals(0, pageBuffer.contentOffset());

    ByteBuffer slice = pageBuffer.sliceContent();
    assertEquals(capacity, slice.capacity());
    assertEquals(0, slice.position());
    assertEquals(capacity, slice.limit());
  }

  @Test
  public void testRawPageBufferAcceptsZeroLengthAtCapacity() {
    // Content region [capacity, capacity) is empty — should succeed.
    // Verifies the > vs >= boundary in the capacity check.
    int capacity = pageFrame.getBuffer().capacity();
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(
        pageFrame, stamp, capacity, 0, 1L, (byte) 'd');
    assertEquals(0, pageBuffer.contentLength());
    assertEquals(capacity, pageBuffer.contentOffset());
  }

  @Test
  public void testRawPageBufferRejectsOffsetBeyondCapacity() {
    // Even with zero length, offset past capacity is invalid.
    int capacity = pageFrame.getBuffer().capacity();
    long stamp = pageFrame.tryOptimisticRead();
    var ex = assertThrows(IllegalArgumentException.class,
        () -> new RawPageBuffer(
            pageFrame, stamp, capacity + 1, 0, 1L, (byte) 'd'));
    assertTrue(ex.getMessage().contains("exceeds page buffer capacity"));
  }
}
