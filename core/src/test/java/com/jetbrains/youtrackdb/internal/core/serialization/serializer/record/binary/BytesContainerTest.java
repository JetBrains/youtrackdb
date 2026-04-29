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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Direct unit coverage for {@link BytesContainer}. The class is the write-side
 * scratchpad used by every record serializer in the binary stack — its public
 * fields ({@code bytes} / {@code offset}) are read directly by callers, so
 * behavioral invariants like "alloc returns the pre-write offset" and "resize
 * doubles capacity" are part of the contract.
 *
 * <p>The existing {@code ReadBytesContainerTest} covers the read-side container.
 * This test focuses on the write-side: alloc growth strategy, {@code allocExact}
 * exact-size growth, {@code skip} offset advancement, {@code copy} share-vs-copy
 * semantics, and {@code fitBytes} trim-vs-passthrough.
 */
public class BytesContainerTest {

  @Test
  public void defaultConstructorAllocates64BytesAtZeroOffset() {
    var c = new BytesContainer();
    assertEquals("default capacity is 64", 64, c.bytes.length);
    assertEquals("default offset is 0", 0, c.offset);
  }

  @Test
  public void byteArrayConstructorWrapsArrayInPlaceAtOffsetZero() {
    var src = new byte[] {1, 2, 3};
    var c = new BytesContainer(src);
    assertSame("ctor must NOT defensively copy", src, c.bytes);
    assertEquals("offset starts at 0", 0, c.offset);
  }

  @Test
  public void byteArrayWithOffsetConstructorRetainsBothFields() {
    var src = new byte[] {10, 20, 30, 40};
    var c = new BytesContainer(src, 2);
    assertSame(src, c.bytes);
    assertEquals(2, c.offset);
  }

  // --- alloc semantics ---

  @Test
  public void allocReturnsPreAllocOffsetAndAdvances() {
    var c = new BytesContainer();
    var pos = c.alloc(5);
    assertEquals("alloc returns offset BEFORE the bump", 0, pos);
    assertEquals("offset advanced by 5", 5, c.offset);

    pos = c.alloc(7);
    assertEquals(5, pos);
    assertEquals(12, c.offset);
  }

  @Test
  public void allocZeroAdvancesByZeroAndReturnsCurrentOffset() {
    var c = new BytesContainer();
    c.alloc(3); // offset = 3
    var pos = c.alloc(0);
    assertEquals(3, pos);
    assertEquals(3, c.offset);
    assertEquals(64, c.bytes.length);
  }

  @Test
  public void allocGrowsArrayByDoublingWhenCapacityExceeded() {
    var c = new BytesContainer();
    // Fill exactly to capacity (64) — no resize yet.
    c.alloc(64);
    assertEquals(64, c.bytes.length);
    assertEquals(64, c.offset);

    // One more byte triggers doubling.
    c.alloc(1);
    assertEquals("first growth doubles 64 -> 128", 128, c.bytes.length);
    assertEquals(65, c.offset);
  }

  @Test
  public void allocGrowsByMultipleDoublingsWhenSingleAllocExceedsDouble() {
    var c = new BytesContainer(); // capacity 64
    // Single alloc requests 200 bytes, far exceeding doubled capacity (128). The
    // resize loop must keep doubling until newLength >= offset; here that's 256.
    c.alloc(200);
    assertEquals("64 -> 128 -> 256 (smallest power of 2 >= 200)", 256, c.bytes.length);
    assertEquals(200, c.offset);
  }

  @Test
  public void allocPreservesExistingBytesAcrossResize() {
    var c = new BytesContainer();
    var pos = c.alloc(5);
    c.bytes[pos] = 1;
    c.bytes[pos + 1] = 2;
    c.bytes[pos + 2] = 3;
    c.bytes[pos + 3] = 4;
    c.bytes[pos + 4] = 5;

    // Force a resize.
    c.alloc(100);
    assertEquals("first 5 bytes preserved across the resize", 1, c.bytes[0]);
    assertEquals(2, c.bytes[1]);
    assertEquals(5, c.bytes[4]);
  }

  // --- allocExact semantics ---

  @Test
  public void allocExactGrowsToExactSize() {
    var c = new BytesContainer();
    // Capacity 64; request offset to land exactly at 100.
    c.allocExact(100);
    assertEquals("allocExact does NOT double; sizes to exact requested offset",
        100, c.bytes.length);
    assertEquals(100, c.offset);
  }

  @Test
  public void allocExactDoesNothingWhenWithinExistingCapacity() {
    var c = new BytesContainer();
    // Within initial 64-byte capacity.
    c.allocExact(10);
    assertEquals("no resize when fits within existing capacity",
        64, c.bytes.length);
    assertEquals(10, c.offset);
  }

  @Test
  public void allocExactPreservesExistingBytesOnResize() {
    var c = new BytesContainer();
    var pos = c.alloc(3); // offset = 3
    c.bytes[pos] = 7;
    c.bytes[pos + 1] = 8;
    c.bytes[pos + 2] = 9;

    // allocExact(80) advances offset by 80, so the new offset is 83 — and bytes.length
    // is sized to that exact offset (no doubling). Pin the exact-size growth contract:
    // bytes.length == new offset, NOT == toAlloc.
    c.allocExact(80);
    assertEquals(83, c.bytes.length);
    assertEquals(83, c.offset);
    assertEquals(7, c.bytes[0]);
    assertEquals(8, c.bytes[1]);
    assertEquals(9, c.bytes[2]);
  }

  // --- skip semantics ---

  @Test
  public void skipAdvancesOffsetAndReturnsSelf() {
    var c = new BytesContainer();
    var returned = c.skip(7);
    assertSame("skip returns this for chaining", c, returned);
    assertEquals(7, c.offset);
  }

  @Test
  public void skipDoesNotResizeArrayEvenWhenAdvancingPastCapacity() {
    // Pin: skip is dumb arithmetic, no growth. A subsequent alloc lands the resize.
    // This is part of the contract because callers use skip to "reserve" a slot they
    // intend to backfill once they've measured the body — the array does not need to
    // be physically large at the time skip() is called.
    var c = new BytesContainer(new byte[8]);
    c.skip(100);
    assertEquals("skip does not grow the underlying array", 8, c.bytes.length);
    assertEquals(100, c.offset);
  }

  // --- copy semantics ---

  @Test
  public void copyShareesBytesArrayButHasIndependentOffset() {
    var c = new BytesContainer();
    c.alloc(10);
    c.bytes[0] = 1;
    c.bytes[5] = 5;

    var copy = c.copy();
    assertSame("copy shares the underlying byte[]", c.bytes, copy.bytes);
    assertEquals("copy starts at the same offset", 10, copy.offset);

    // Independent advance.
    copy.skip(3);
    assertEquals(13, copy.offset);
    assertEquals("original offset is unaffected by copy.skip", 10, c.offset);
  }

  @Test
  public void copyReflectsLaterMutationsToSharedArray() {
    // Pin: copy() does NOT clone the array — a mutation through one container is
    // visible through the other. This is intentional: the read-side path
    // (BinaryField.copy → BytesContainer.copy) explicitly relies on byte sharing.
    var c = new BytesContainer(new byte[] {0, 0, 0, 0});
    var copy = c.copy();
    c.bytes[0] = 42;
    assertEquals("mutation through original visible via copy", 42, copy.bytes[0]);
  }

  // --- fitBytes semantics ---

  @Test
  public void fitBytesReturnsSameArrayWhenFullyFilled() {
    // When offset == bytes.length, no trim is needed; pin the optimization that
    // returns the existing array reference rather than allocating a fresh copy.
    var src = new byte[8];
    var c = new BytesContainer(src);
    c.skip(8); // offset = 8 = src.length
    var fitted = c.fitBytes();
    assertSame("fully-filled fitBytes returns the same array reference", src, fitted);
  }

  @Test
  public void fitBytesReturnsTrimmedCopyWhenNotFullyFilled() {
    var c = new BytesContainer();
    c.alloc(5);
    c.bytes[0] = 1;
    c.bytes[4] = 5;

    var fitted = c.fitBytes();
    assertNotSame("partial fit returns a fresh array", c.bytes, fitted);
    assertEquals("fitted length matches offset", 5, fitted.length);
    assertEquals(1, fitted[0]);
    assertEquals(5, fitted[4]);
    // Source array still has size 64 (initial default).
    assertEquals(64, c.bytes.length);
  }

  @Test
  public void fitBytesReturnsZeroLengthArrayWhenNothingWritten() {
    var c = new BytesContainer();
    var fitted = c.fitBytes();
    assertEquals(0, fitted.length);
    assertNotSame("zero-length fit still allocates a fresh array (offset 0 != 64)",
        c.bytes, fitted);
  }

  @Test
  public void fitBytesFromExternallyConstructedExactSizeArray() {
    var src = new byte[3];
    src[0] = 1;
    src[1] = 2;
    src[2] = 3;
    var c = new BytesContainer(src, 3);
    // offset == bytes.length, so fitBytes returns src.
    assertSame(src, c.fitBytes());
    assertArrayEquals(new byte[] {1, 2, 3}, c.fitBytes());
  }

  // --- combined behaviors ---

  @Test
  public void allocSkipAllocSequenceMaintainsConsistentOffsets() {
    var c = new BytesContainer();
    var p1 = c.alloc(3);
    c.bytes[p1] = 1;
    c.skip(2);
    var p2 = c.alloc(4);
    c.bytes[p2] = 9;

    assertEquals(0, p1);
    assertEquals("skip must move offset between alloc calls", 5, p2);
    assertEquals(9, c.offset);
    assertEquals(1, c.bytes[0]);
    assertEquals(9, c.bytes[5]);
  }

  @Test
  public void resizeNeverShrinks() {
    // Pin: there is no mechanism to shrink the underlying array — callers cannot
    // accidentally truncate a buffer they were relying on to hold previously-written
    // bytes.
    var c = new BytesContainer(new byte[200]);
    c.alloc(50);
    var len = c.bytes.length;
    c.alloc(10);
    assertEquals("array length non-decreasing", len, c.bytes.length);
    assertFalse(c.bytes.length < len);
  }
}
