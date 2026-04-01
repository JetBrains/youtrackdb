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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Unit tests for {@link ReadBytesContainer} covering all API methods with both heap and direct
 * ByteBuffer backing.
 */
public class ReadBytesContainerTest {

  // --- Constructor tests ---

  @Test
  public void testByteArrayConstructorWrapsEntireArray() {
    var data = new byte[] {1, 2, 3, 4, 5};
    var container = new ReadBytesContainer(data);

    assertEquals(0, container.offset());
    assertEquals(5, container.remaining());
  }

  @Test
  public void testByteArrayWithOffsetConstructor() {
    var data = new byte[] {1, 2, 3, 4, 5};
    var container = new ReadBytesContainer(data, 2);

    assertEquals(2, container.offset());
    assertEquals(3, container.remaining());
    assertEquals(3, container.getByte());
  }

  @Test
  public void testByteBufferConstructorPreservesPosition() {
    var buf = ByteBuffer.allocate(10);
    buf.position(3);
    buf.limit(8);
    var container = new ReadBytesContainer(buf);

    assertEquals(3, container.offset());
    assertEquals(5, container.remaining());
  }

  @Test
  public void testDirectByteBufferConstructor() {
    var buf = ByteBuffer.allocateDirect(5);
    buf.put(new byte[] {10, 20, 30, 40, 50});
    buf.flip();
    var container = new ReadBytesContainer(buf);

    assertEquals(0, container.offset());
    assertEquals(5, container.remaining());
    assertEquals(10, container.getByte());
  }

  @Test
  public void testEmptyBuffer() {
    var container = new ReadBytesContainer(new byte[0]);

    assertEquals(0, container.offset());
    assertEquals(0, container.remaining());
  }

  // --- getByte tests ---

  @Test
  public void testGetByteAdvancesPosition() {
    var container = new ReadBytesContainer(new byte[] {0x41, 0x42, 0x43});

    assertEquals(0x41, container.getByte());
    assertEquals(1, container.offset());
    assertEquals(0x42, container.getByte());
    assertEquals(2, container.offset());
    assertEquals(1, container.remaining());
  }

  @Test
  public void testGetByteFromDirectBuffer() {
    var buf = ByteBuffer.allocateDirect(3);
    buf.put(new byte[] {(byte) 0xFF, 0x00, 0x7F});
    buf.flip();
    var container = new ReadBytesContainer(buf);

    assertEquals((byte) 0xFF, container.getByte());
    assertEquals(0x00, container.getByte());
    assertEquals(0x7F, container.getByte());
  }

  @Test
  public void testGetByteThrowsOnExhaustedBuffer() {
    var container = new ReadBytesContainer(new byte[] {1});
    container.getByte();

    assertThrows(BufferUnderflowException.class, container::getByte);
  }

  // --- peekByte tests ---

  @Test
  public void testPeekByteDoesNotAdvancePosition() {
    var container = new ReadBytesContainer(new byte[] {10, 20, 30, 40});

    assertEquals(20, container.peekByte(1));
    assertEquals(30, container.peekByte(2));
    assertEquals(0, container.offset()); // position unchanged
  }

  @Test
  public void testPeekByteAtZeroReadsCurrent() {
    var container = new ReadBytesContainer(new byte[] {42, 99});

    assertEquals(42, container.peekByte(0));
    assertEquals(0, container.offset());
  }

  @Test
  public void testPeekByteFromDirectBuffer() {
    var buf = ByteBuffer.allocateDirect(3);
    buf.put(new byte[] {5, 10, 15});
    buf.flip();
    var container = new ReadBytesContainer(buf);

    assertEquals(10, container.peekByte(1));
    assertEquals(0, container.offset());
  }

  // --- getBytes tests ---

  @Test
  public void testGetBytesBulkRead() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3, 4, 5});
    var dst = new byte[3];

    container.getBytes(dst, 0, 3);

    assertArrayEquals(new byte[] {1, 2, 3}, dst);
    assertEquals(3, container.offset());
    assertEquals(2, container.remaining());
  }

  @Test
  public void testGetBytesWithDestinationOffset() {
    var container = new ReadBytesContainer(new byte[] {10, 20});
    var dst = new byte[5];

    container.getBytes(dst, 2, 2);

    assertEquals(0, dst[0]);
    assertEquals(0, dst[1]);
    assertEquals(10, dst[2]);
    assertEquals(20, dst[3]);
    assertEquals(0, dst[4]);
  }

  @Test
  public void testGetBytesFromDirectBuffer() {
    var buf = ByteBuffer.allocateDirect(4);
    buf.put(new byte[] {0x0A, 0x0B, 0x0C, 0x0D});
    buf.flip();
    var container = new ReadBytesContainer(buf);
    var dst = new byte[4];

    container.getBytes(dst, 0, 4);

    assertArrayEquals(new byte[] {0x0A, 0x0B, 0x0C, 0x0D}, dst);
    assertEquals(0, container.remaining());
  }

  @Test
  public void testGetBytesZeroLength() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3});
    var dst = new byte[0];

    container.getBytes(dst, 0, 0);

    assertEquals(0, container.offset());
  }

  // --- getStringBytes tests ---

  @Test
  public void testGetStringBytesAscii() {
    var hello = "Hello".getBytes(StandardCharsets.UTF_8);
    var container = new ReadBytesContainer(hello);

    assertEquals("Hello", container.getStringBytes(5));
    assertEquals(5, container.offset());
  }

  @Test
  public void testGetStringBytesUtf8() {
    var utf8 = "Привет".getBytes(StandardCharsets.UTF_8);
    var container = new ReadBytesContainer(utf8);

    assertEquals("Привет", container.getStringBytes(utf8.length));
    assertEquals(0, container.remaining());
  }

  @Test
  public void testGetStringBytesFromDirectBuffer() {
    var hello = "World".getBytes(StandardCharsets.UTF_8);
    var buf = ByteBuffer.allocateDirect(hello.length);
    buf.put(hello);
    buf.flip();
    var container = new ReadBytesContainer(buf);

    assertEquals("World", container.getStringBytes(5));
  }

  @Test
  public void testGetStringBytesEmpty() {
    var container = new ReadBytesContainer(new byte[] {1, 2});

    // Zero-length string read should return empty string
    assertEquals("", container.getStringBytes(0));
    assertEquals(0, container.offset());
  }

  // --- remaining tests ---

  @Test
  public void testRemainingDecreasesAsRead() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3, 4, 5});

    assertEquals(5, container.remaining());
    container.getByte();
    assertEquals(4, container.remaining());
    container.skip(2);
    assertEquals(2, container.remaining());
    container.getByte();
    container.getByte();
    assertEquals(0, container.remaining());
  }

  // --- offset tests ---

  @Test
  public void testOffsetTracksPosition() {
    var container = new ReadBytesContainer(new byte[] {0, 0, 0, 0, 0});

    assertEquals(0, container.offset());
    container.getByte();
    assertEquals(1, container.offset());
    container.skip(3);
    assertEquals(4, container.offset());
  }

  // --- skip tests ---

  @Test
  public void testSkipAdvancesPosition() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3, 4, 5});

    container.skip(3);

    assertEquals(3, container.offset());
    assertEquals(4, container.getByte());
  }

  @Test
  public void testSkipZero() {
    var container = new ReadBytesContainer(new byte[] {1, 2});

    container.skip(0);

    assertEquals(0, container.offset());
  }

  // --- setOffset tests ---

  @Test
  public void testSetOffsetJumpsToAbsolutePosition() {
    var container = new ReadBytesContainer(new byte[] {10, 20, 30, 40, 50});

    container.setOffset(3);

    assertEquals(3, container.offset());
    assertEquals(40, container.getByte());
    assertEquals(1, container.remaining()); // after reading 1 byte at position 3 -> position 4
  }

  @Test
  public void testSetOffsetCanGoBackward() {
    var container = new ReadBytesContainer(new byte[] {10, 20, 30});

    container.skip(2);
    assertEquals(2, container.offset());

    container.setOffset(0);
    assertEquals(0, container.offset());
    assertEquals(10, container.getByte());
  }

  // --- slice tests ---

  @Test
  public void testSliceCreatesIndependentSubContainer() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3, 4, 5, 6});

    container.skip(1); // position at 1
    var sliced = container.slice(3); // slice bytes [2, 3, 4]

    // Parent position advanced past the sliced region
    assertEquals(4, container.offset());
    assertEquals(2, container.remaining());

    // Sliced container starts at 0 and has 3 bytes
    assertEquals(0, sliced.offset());
    assertEquals(3, sliced.remaining());
    assertEquals(2, sliced.getByte());
    assertEquals(3, sliced.getByte());
    assertEquals(4, sliced.getByte());
  }

  @Test
  public void testSliceFromDirectBuffer() {
    var buf = ByteBuffer.allocateDirect(5);
    buf.put(new byte[] {10, 20, 30, 40, 50});
    buf.flip();
    var container = new ReadBytesContainer(buf);

    container.skip(2);
    var sliced = container.slice(2);

    assertEquals(4, container.offset());
    assertEquals(0, sliced.offset());
    assertEquals(2, sliced.remaining());
    assertEquals(30, sliced.getByte());
    assertEquals(40, sliced.getByte());
  }

  // --- Mixed operations sequence ---

  @Test
  public void testMixedOperationsSequence() {
    // Simulate a realistic deserialization sequence:
    // [varint_byte, varint_byte, string_len(3), 'a', 'b', 'c', skip_2, data_byte]
    var data = new byte[] {0x05, 0x0A, 3, 'a', 'b', 'c', 99, 98, 42};
    var container = new ReadBytesContainer(data);

    // Read two "varint" bytes
    assertEquals(0x05, container.getByte());
    assertEquals(0x0A, container.getByte());
    assertEquals(2, container.offset());

    // Read string length and string
    int len = container.getByte() & 0xFF;
    assertEquals(3, len);
    assertEquals("abc", container.getStringBytes(len));
    assertEquals(6, container.offset());

    // Skip 2 bytes
    container.skip(2);
    assertEquals(8, container.offset());

    // Read final byte
    assertEquals(42, container.getByte());
    assertEquals(0, container.remaining());
  }

  @Test
  public void testMixedOperationsWithPeekAndSetOffset() {
    var data = new byte[] {10, 20, 30, 40, 50};
    var container = new ReadBytesContainer(data);

    // Peek ahead without advancing
    assertEquals(30, container.peekByte(2));
    assertEquals(0, container.offset());

    // Read one byte
    assertEquals(10, container.getByte());

    // Jump to position 3
    container.setOffset(3);
    assertEquals(40, container.getByte());

    // Jump back to position 1
    container.setOffset(1);
    assertEquals(20, container.getByte());
  }

  // --- Single byte buffer ---

  @Test
  public void testSingleByteBuffer() {
    var container = new ReadBytesContainer(new byte[] {0x7F});

    assertEquals(1, container.remaining());
    assertEquals(0x7F, container.getByte());
    assertEquals(0, container.remaining());
  }

  // --- Exact size read ---

  @Test
  public void testExactSizeGetBytes() {
    var data = new byte[] {1, 2, 3};
    var container = new ReadBytesContainer(data);
    var dst = new byte[3];

    container.getBytes(dst, 0, 3);

    assertArrayEquals(data, dst);
    assertEquals(0, container.remaining());
  }

  // --- Error / boundary tests ---

  @Test
  public void testPeekByteThrowsOnOutOfBounds() {
    // peekByte at an offset beyond the buffer limit should throw
    var container = new ReadBytesContainer(new byte[] {1, 2});
    assertThrows(IndexOutOfBoundsException.class, () -> container.peekByte(2));
  }

  @Test
  public void testGetBytesThrowsOnUnderflow() {
    // Requesting more bytes than available should throw BufferUnderflowException
    var container = new ReadBytesContainer(new byte[] {1, 2});
    var dst = new byte[5];
    assertThrows(BufferUnderflowException.class, () -> container.getBytes(dst, 0, 5));
  }

  @Test
  public void testGetStringBytesThrowsOnUnderflow() {
    // getStringBytes with length > remaining should throw before allocating
    var container = new ReadBytesContainer(new byte[] {1, 2});
    assertThrows(BufferUnderflowException.class, () -> container.getStringBytes(10));
  }

  @Test
  public void testGetStringBytesThrowsOnUnderflowDirectBuffer() {
    // Same test with direct ByteBuffer — ensures guard fires before allocation
    var buf = ByteBuffer.allocateDirect(2);
    buf.put(new byte[] {1, 2});
    buf.flip();
    var container = new ReadBytesContainer(buf);
    assertThrows(BufferUnderflowException.class, () -> container.getStringBytes(10));
  }

  @Test
  public void testSkipPastEndThrows() {
    // Skipping past the buffer limit should throw
    var container = new ReadBytesContainer(new byte[] {1, 2});
    assertThrows(IllegalArgumentException.class, () -> container.skip(5));
  }

  @Test
  public void testSetOffsetPastLimitThrows() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3});
    assertThrows(IllegalArgumentException.class, () -> container.setOffset(10));
  }

  @Test
  public void testSetOffsetNegativeThrows() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3});
    assertThrows(IllegalArgumentException.class, () -> container.setOffset(-1));
  }

  @Test
  public void testSlicePastRemainingThrows() {
    var container = new ReadBytesContainer(new byte[] {1, 2, 3});
    assertThrows(IndexOutOfBoundsException.class, () -> container.slice(5));
  }

  @Test
  public void testSliceIsIndependentOfParentPositionChanges() {
    // Verify slice and parent have truly independent positions
    var container = new ReadBytesContainer(new byte[] {1, 2, 3, 4, 5, 6});
    container.skip(1);
    var sliced = container.slice(3); // slices bytes [2, 3, 4]

    // Mutate parent position
    container.getByte(); // reads byte at position 4 (value 5)

    // Slice should still read from its own position 0, unaffected by parent
    assertEquals(2, sliced.getByte());
    assertEquals(3, sliced.getByte());
  }

  @Test
  public void testGetStringBytesWithHeapBufferOffset() {
    // Verify getStringBytes works correctly when the heap buffer has a non-zero arrayOffset
    var data = new byte[] {0, 0, 'H', 'i', '!'};
    var container = new ReadBytesContainer(data, 2);

    assertEquals("Hi!", container.getStringBytes(3));
    assertEquals(0, container.remaining());
  }

  @Test
  public void testSliceZeroLength() {
    // Zero-length slice should produce an empty sub-container
    var container = new ReadBytesContainer(new byte[] {1, 2, 3});
    container.skip(1);
    var sliced = container.slice(0);

    assertEquals(0, sliced.remaining());
    assertEquals(1, container.offset());
  }

  @Test
  public void testGetInternedStringWithNullCache() {
    // When cache is null, getInternedString should return a plain String
    var data = "hello".getBytes(StandardCharsets.UTF_8);
    var container = new ReadBytesContainer(data);

    var result = container.getInternedString(null, 5);

    assertEquals("hello", result);
    assertEquals(0, container.remaining());
  }

  @Test
  public void testGetInternedStringThrowsOnUnderflow() {
    // getInternedString with length > remaining should throw before allocating
    var container = new ReadBytesContainer(new byte[] {1, 2});
    assertThrows(BufferUnderflowException.class,
        () -> container.getInternedString(null, 10));
  }
}
