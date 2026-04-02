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
import static org.junit.Assert.assertNotNull;
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
    // recordVersion() delegates to version().
    var rawBuffer = new RawBuffer(new byte[] {1, 2, 3}, 42L, (byte) 'd');

    assertTrue(rawBuffer instanceof StorageReadResult);

    StorageReadResult result = rawBuffer;
    assertEquals(42L, result.recordVersion());
    assertEquals((byte) 'd', result.recordType());
  }

  @Test
  public void testRawBufferRecordVersionDelegatesToVersion() {
    // Ensures recordVersion() and version() return the same value.
    var rawBuffer = new RawBuffer(new byte[0], 99L, (byte) 'b');
    assertEquals(rawBuffer.version(), rawBuffer.recordVersion());
  }

  // --- RawPageBuffer satisfies StorageReadResult contract ---

  @Test
  public void testRawPageBufferIsStorageReadResult() {
    // Verifies that RawPageBuffer implements StorageReadResult with
    // correct version and type accessors.
    long stamp = pageFrame.tryOptimisticRead();
    var pageBuffer = new RawPageBuffer(pageFrame, stamp, 100, 50, 7L, (byte) 'd');

    assertTrue(pageBuffer instanceof StorageReadResult);

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
    assertNotNull(slice);
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
}
