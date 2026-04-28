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
package com.jetbrains.youtrackdb.internal.core.serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the raw read/write/grow/move/copyFrom/peek primitives of the {@code @Deprecated}
 * {@link MemoryStream}. Higher-level uses such as {@code RecordId.toStream/fromStream} and
 * {@code RecordBytes} round-trips are deliberately out of scope here — they are picked up where
 * the live callers are tested (in the DB record serialization tracks).
 */
public class MemoryStreamTest {

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  /** Default constructor allocates a {@link MemoryStream#DEF_SIZE}-byte internal buffer. */
  @Test
  public void defaultConstructorAllocatesDefaultCapacity() {
    var ms = new MemoryStream();
    Assert.assertEquals(MemoryStream.DEF_SIZE, ms.getInternalBuffer().length);
    Assert.assertEquals(MemoryStream.DEF_SIZE, ms.getSize());
    Assert.assertEquals(0, ms.getPosition());
    Assert.assertEquals(0, ms.size());
  }

  /** The capacity-arg constructor honours the requested initial size. */
  @Test
  public void capacityConstructorAllocatesRequestedCapacity() {
    var ms = new MemoryStream(16);
    Assert.assertEquals(16, ms.getInternalBuffer().length);
    Assert.assertEquals(0, ms.getPosition());
  }

  /**
   * The wrap-existing-buffer constructor stores the buffer reference itself (not a copy); writes
   * to the stream observe in the original array.
   */
  @Test
  public void wrapBufferConstructorStoresBufferReference() {
    var backing = new byte[] {1, 2, 3, 4};
    var ms = new MemoryStream(backing);
    Assert.assertSame(backing, ms.getInternalBuffer());
    Assert.assertEquals(0, ms.getPosition());
  }

  // ---------------------------------------------------------------------------
  // write — single byte and array
  // ---------------------------------------------------------------------------

  /** {@code write(int)} appends one byte and advances position by one. */
  @Test
  public void writeSingleByteAdvancesPosition() {
    var ms = new MemoryStream(8);
    ms.write(0xAB);
    ms.write(0xCD);
    Assert.assertEquals(2, ms.getPosition());
    var copy = ms.copy();
    Assert.assertEquals((byte) 0xAB, copy[0]);
    Assert.assertEquals((byte) 0xCD, copy[1]);
  }

  /** {@code write(byte[], offset, length)} bulk-copies the slice, advances position by length. */
  @Test
  public void writeArraySliceCopiesBytesAndAdvancesPosition() {
    var ms = new MemoryStream(16);
    var source = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    // Write a slice [2,7) — i.e. {3, 4, 5, 6, 7}.
    ms.write(source, 2, 5);
    Assert.assertEquals(5, ms.getPosition());
    Assert.assertArrayEquals(new byte[] {3, 4, 5, 6, 7}, ms.copy());
  }

  /** Writing an array slice longer than {@code NATIVE_COPY_THRESHOLD} (=9) goes through the native-arraycopy branch. */
  @Test
  public void writeArrayLargerThanCopyThresholdGoesThroughNativeArrayCopy() {
    var ms = new MemoryStream(64);
    var source = new byte[16];
    for (var i = 0; i < source.length; i++) {
      source[i] = (byte) i;
    }
    ms.write(source, 0, source.length);
    Assert.assertEquals(16, ms.getPosition());
    Assert.assertArrayEquals(source, ms.copy());
  }

  // ---------------------------------------------------------------------------
  // grow / assureSpaceFor
  // ---------------------------------------------------------------------------

  /** Writing more bytes than the initial capacity grows the buffer. */
  @Test
  public void writeBeyondInitialCapacityGrowsBuffer() {
    var ms = new MemoryStream(4);
    ms.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, 8);
    Assert.assertEquals(8, ms.getPosition());
    Assert.assertTrue(
        "buffer should have grown beyond the 4-byte initial capacity",
        ms.getInternalBuffer().length >= 8);
    Assert.assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ms.copy());
  }

  /** A single very-large write makes the buffer at least the required size. */
  @Test
  public void writeOfHugeSliceGrowsBufferToFit() {
    var ms = new MemoryStream(4);
    var huge = new byte[100];
    for (var i = 0; i < huge.length; i++) {
      huge[i] = (byte) (i & 0x7F);
    }
    ms.write(huge, 0, huge.length);
    Assert.assertEquals(100, ms.getPosition());
    Assert.assertTrue(ms.getInternalBuffer().length >= 100);
    Assert.assertArrayEquals(huge, ms.copy());
  }

  // ---------------------------------------------------------------------------
  // read
  // ---------------------------------------------------------------------------

  /**
   * {@code read()} returns the byte at the current position (sign-extended into an int) and
   * advances. Two consecutive reads consume two bytes in order.
   */
  @Test
  public void readSingleByteAdvancesPosition() {
    var ms = new MemoryStream(new byte[] {0x12, (byte) 0xAB});
    Assert.assertEquals(0x12, ms.read());
    Assert.assertEquals((byte) 0xAB, (byte) ms.read());
    Assert.assertEquals(2, ms.getPosition());
  }

  /** {@code read(byte[])} copies up to {@code b.length} bytes from the current position. */
  @Test
  public void readIntoBufferCopiesBytesAndAdvances() {
    var ms = new MemoryStream(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    ms.setPosition(2);
    var dst = new byte[3];
    var read = ms.read(dst);
    Assert.assertEquals(3, read);
    Assert.assertArrayEquals(new byte[] {3, 4, 5}, dst);
    Assert.assertEquals(5, ms.getPosition());
  }

  /** {@code read(byte[], off, len)} copies into a sub-range of the destination. */
  @Test
  public void readIntoBufferWithOffsetCopiesIntoTargetRange() {
    var ms = new MemoryStream(new byte[] {10, 20, 30, 40, 50});
    ms.setPosition(1);
    var dst = new byte[] {-1, -1, -1, -1};
    var read = ms.read(dst, 1, 2);
    Assert.assertEquals(2, read);
    Assert.assertArrayEquals(new byte[] {-1, 20, 30, -1}, dst);
    Assert.assertEquals(3, ms.getPosition());
  }

  /** When position is at end, {@code read(byte[])} returns 0 and does not advance. */
  @Test
  public void readWhenPositionAtEndReturnsZero() {
    var ms = new MemoryStream(new byte[] {1, 2, 3});
    ms.setPosition(3);
    var dst = new byte[2];
    Assert.assertEquals(0, ms.read(dst));
    Assert.assertEquals(3, ms.getPosition());
  }

  // ---------------------------------------------------------------------------
  // move
  // ---------------------------------------------------------------------------

  /** {@code move(from, 0)} is a no-op: the buffer is unchanged. */
  @Test
  public void moveZeroIsNoOp() {
    var ms = new MemoryStream(new byte[] {1, 2, 3, 4, 5});
    ms.move(2, 0);
    Assert.assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, ms.getInternalBuffer());
  }

  /** {@code move(from, +n)} shifts bytes right; the source range is overwritten by the shift. */
  @Test
  public void moveRightShiftsBytesAndOverwritesSourceRange() {
    var ms = new MemoryStream(new byte[] {1, 2, 3, 4, 5, 0, 0, 0});
    // Shift from index 0 right by 3 positions: indices 0..4 become indices 3..7.
    ms.move(0, 3);
    Assert.assertArrayEquals(new byte[] {1, 2, 3, 1, 2, 3, 4, 5}, ms.getInternalBuffer());
  }

  /** {@code move(from, -n)} shifts bytes left toward the start of the buffer. */
  @Test
  public void moveLeftShiftsBytesTowardBufferStart() {
    var ms = new MemoryStream(new byte[] {0, 0, 1, 2, 3, 4, 5});
    // Shift from index 2 left by 2 positions: indices 2..6 become indices 0..4.
    ms.move(2, -2);
    Assert.assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 4, 5}, ms.getInternalBuffer());
  }

  // ---------------------------------------------------------------------------
  // copyFrom
  // ---------------------------------------------------------------------------

  /** copyFrom with a negative size is a no-op. */
  @Test
  public void copyFromNegativeSizeIsNoOp() {
    var dst = new MemoryStream(new byte[] {1, 2, 3});
    dst.setPosition(1);
    var src = new MemoryStream(new byte[] {9, 9, 9});
    dst.copyFrom(src, -1);
    Assert.assertArrayEquals(new byte[] {1, 2, 3}, dst.getInternalBuffer());
    Assert.assertEquals(1, dst.getPosition());
  }

  /** copyFrom copies {@code size} bytes from the source stream's current position. */
  @Test
  public void copyFromAppendsBytesAtCurrentPosition() {
    var dst = new MemoryStream(8);
    dst.setPosition(2);
    var src = new MemoryStream(new byte[] {10, 20, 30, 40, 50});
    src.setPosition(1);

    dst.copyFrom(src, 3);

    // dst positions 2..4 now hold 20, 30, 40 from src; surrounding bytes stay zero.
    Assert.assertEquals(0, dst.getInternalBuffer()[0]);
    Assert.assertEquals(0, dst.getInternalBuffer()[1]);
    Assert.assertEquals((byte) 20, dst.getInternalBuffer()[2]);
    Assert.assertEquals((byte) 30, dst.getInternalBuffer()[3]);
    Assert.assertEquals((byte) 40, dst.getInternalBuffer()[4]);
    // copyFrom does not advance dst.position; only the buffer was filled.
    Assert.assertEquals(2, dst.getPosition());
  }

  /** copyFrom triggers a buffer growth when the destination cannot accommodate the size. */
  @Test
  public void copyFromGrowsDestinationBufferWhenNeeded() {
    var dst = new MemoryStream(2);
    var src = new MemoryStream(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    dst.copyFrom(src, 8);
    Assert.assertTrue(dst.getInternalBuffer().length >= 8);
    Assert.assertEquals((byte) 1, dst.getInternalBuffer()[0]);
    Assert.assertEquals((byte) 8, dst.getInternalBuffer()[7]);
  }

  // ---------------------------------------------------------------------------
  // peek
  // ---------------------------------------------------------------------------

  /** {@code peek()} returns the byte at the current position without advancing. */
  @Test
  public void peekReturnsByteWithoutAdvancing() {
    var ms = new MemoryStream(new byte[] {0x55, 0x66});
    Assert.assertEquals(0x55, ms.peek());
    Assert.assertEquals(0, ms.getPosition());
    ms.setPosition(1);
    Assert.assertEquals(0x66, ms.peek());
    Assert.assertEquals(1, ms.getPosition());
  }

  // ---------------------------------------------------------------------------
  // Position management — reset, jump, fill, setPosition, close
  // ---------------------------------------------------------------------------

  /** {@code reset()} returns position to zero without shrinking the underlying buffer. */
  @Test
  public void resetReturnsPositionToZeroAndKeepsCapacity() {
    var ms = new MemoryStream(8);
    ms.write(1);
    ms.write(2);
    ms.write(3);
    Assert.assertEquals(3, ms.getPosition());

    ms.reset();
    Assert.assertEquals(0, ms.getPosition());
    Assert.assertEquals(8, ms.getInternalBuffer().length);
  }

  /** {@code close()} is documented as equivalent to reset(); position returns to zero. */
  @Test
  public void closeIsEquivalentToReset() {
    var ms = new MemoryStream(8);
    ms.write(1);
    ms.write(2);

    ms.close();
    Assert.assertEquals(0, ms.getPosition());
  }

  /** {@code setPosition(int)} rewinds (or fast-forwards) without checking against capacity. */
  @Test
  public void setPositionMovesPositionToTarget() {
    var ms = new MemoryStream(16);
    ms.fill(5);
    Assert.assertEquals(5, ms.getPosition());
    ms.setPosition(2);
    Assert.assertEquals(2, ms.getPosition());
    Assert.assertSame(ms, ms.setPosition(3));
  }

  /** {@code jump(int)} moves to a position within capacity. */
  @Test
  public void jumpMovesPositionWithinCapacity() {
    var ms = new MemoryStream(16);
    Assert.assertSame(ms, ms.jump(7));
    Assert.assertEquals(7, ms.getPosition());
  }

  /** {@code jump(int)} past the buffer end throws IndexOutOfBoundsException. */
  @Test(expected = IndexOutOfBoundsException.class)
  public void jumpBeyondCapacityThrows() {
    var ms = new MemoryStream(8);
    ms.jump(9);
  }

  /** {@code fill(int)} advances position without writing — leaves bytes in their previous state. */
  @Test
  public void fillAdvancesPositionWithoutWriting() {
    var ms = new MemoryStream(16);
    Assert.assertEquals(0, ms.getPosition());
    ms.fill(5);
    Assert.assertEquals(5, ms.getPosition());
    // First five bytes remain zero because the constructor allocates them as zero.
    var buffer = ms.getInternalBuffer();
    for (var i = 0; i < 5; i++) {
      Assert.assertEquals("byte at index " + i, 0, buffer[i]);
    }
  }

  /** {@code fill(int, byte)} writes {@code length} copies of the filler. */
  @Test
  public void fillWithFillerWritesFillerBytes() {
    var ms = new MemoryStream(16);
    ms.fill(4, (byte) 0x55);
    Assert.assertEquals(4, ms.getPosition());
    Assert.assertArrayEquals(new byte[] {0x55, 0x55, 0x55, 0x55}, ms.copy());
  }

  /** {@code fill(int)} grows the buffer when the requested length exceeds capacity. */
  @Test
  public void fillGrowsBufferWhenLengthExceedsCapacity() {
    var ms = new MemoryStream(4);
    ms.fill(10);
    Assert.assertEquals(10, ms.getPosition());
    Assert.assertTrue(ms.getInternalBuffer().length >= 10);
  }

  // ---------------------------------------------------------------------------
  // Snapshot / writeTo / available
  // ---------------------------------------------------------------------------

  /** {@code writeTo(OutputStream)} writes everything up to the current position only. */
  @Test
  public void writeToCopiesUpToCurrentPosition() throws IOException {
    var ms = new MemoryStream(16);
    ms.write(1);
    ms.write(2);
    ms.write(3);

    var sink = new ByteArrayOutputStream();
    ms.writeTo(sink);
    Assert.assertArrayEquals(new byte[] {1, 2, 3}, sink.toByteArray());
  }

  /**
   * {@code copy()} returns a fresh array with content up to the current position; mutating it
   * does not affect the stream.
   */
  @Test
  public void copyReturnsSnapshotIndependentOfFurtherWrites() {
    var ms = new MemoryStream(16);
    ms.write(0x11);
    ms.write(0x22);
    var snapshot = ms.copy();
    Assert.assertArrayEquals(new byte[] {0x11, 0x22}, snapshot);

    snapshot[0] = 0;
    ms.write(0x33);
    var second = ms.copy();
    Assert.assertArrayEquals(new byte[] {0x11, 0x22, 0x33}, second);
  }

  /** {@code available()} reports the number of bytes between the current position and capacity. */
  @Test
  public void availableReportsRemainingCapacity() {
    var ms = new MemoryStream(16);
    Assert.assertEquals(16, ms.available());
    ms.fill(5);
    Assert.assertEquals(11, ms.available());
  }

  // ---------------------------------------------------------------------------
  // setSource — buffer rebind resets position
  // ---------------------------------------------------------------------------

  /** {@code setSource(byte[])} replaces the buffer reference and rewinds position to zero. */
  @Test
  public void setSourceReplacesBufferAndResetsPosition() {
    var ms = new MemoryStream(8);
    ms.fill(5);
    Assert.assertEquals(5, ms.getPosition());

    var newBacking = new byte[] {9, 9, 9};
    ms.setSource(newBacking);
    Assert.assertEquals(0, ms.getPosition());
    Assert.assertSame(newBacking, ms.getInternalBuffer());
  }
}
