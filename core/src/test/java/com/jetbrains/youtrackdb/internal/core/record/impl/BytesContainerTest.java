package com.jetbrains.youtrackdb.internal.core.record.impl;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BytesContainer;
import java.util.Arrays;
import org.junit.Test;

public class BytesContainerTest {

  @Test
  public void testSimple() {
    var bytesContainer = new BytesContainer();
    assertNotNull(bytesContainer.bytes);
    assertEquals(bytesContainer.offset, 0);
  }

  @Test
  public void testReallocSimple() {
    var bytesContainer = new BytesContainer();
    bytesContainer.alloc((short) 2050);
    assertTrue(bytesContainer.bytes.length > 2050);
    assertEquals(bytesContainer.offset, 2050);
  }

  @Test
  public void testBorderReallocSimple() {
    var bytesContainer = new BytesContainer();
    bytesContainer.alloc((short) 1024);
    var pos = bytesContainer.alloc((short) 1);
    bytesContainer.bytes[pos] = 0;
    assertTrue(bytesContainer.bytes.length >= 1025);
    assertEquals(bytesContainer.offset, 1025);
  }

  @Test
  public void testReadSimple() {
    var bytesContainer = new BytesContainer();
    bytesContainer.skip((short) 100);
    assertEquals(bytesContainer.offset, 100);
  }

  @Test
  public void testReset_keepsBackingArrayResetsOffsetAndZeros() {
    var bytesContainer = new BytesContainer();
    bytesContainer.alloc(200);
    assertEquals(200, bytesContainer.offset);
    var backingArray = bytesContainer.bytes;
    // Write non-zero data into the allocated region
    Arrays.fill(bytesContainer.bytes, 0, 200, (byte) 0xAB);

    bytesContainer.reset();
    assertEquals(0, bytesContainer.offset);
    // Backing array is reused, not reallocated
    assertSame(backingArray, bytesContainer.bytes);
    // Used region is zeroed so V1 delegate serializers see clean memory
    for (int i = 0; i < 200; i++) {
      assertEquals("byte at index " + i + " should be zero", 0, bytesContainer.bytes[i]);
    }
  }

  @Test
  public void testReset_multipleReuseCycles_noStaleDataLeaks() {
    // Simulates the production pattern: tempBuffer is reset and reused for each property.
    // A short property value after a long one must not see leftover data.
    var bytesContainer = new BytesContainer();

    // Cycle 1: write 500 bytes of 0xAA
    bytesContainer.alloc(500);
    Arrays.fill(bytesContainer.bytes, 0, 500, (byte) 0xAA);

    // Cycle 2: reset, write only 10 bytes of 0xBB
    bytesContainer.reset();
    bytesContainer.alloc(10);
    Arrays.fill(bytesContainer.bytes, 0, 10, (byte) 0xBB);

    // Offset tracks the current allocation
    assertEquals(10, bytesContainer.offset);

    // Active region retains current cycle's data
    for (int i = 0; i < 10; i++) {
      assertEquals("current data at index " + i, (byte) 0xBB, bytesContainer.bytes[i]);
    }

    // Bytes [10, 500) must be zero (not 0xAA from cycle 1)
    for (int i = 10; i < 500; i++) {
      assertEquals("stale data at index " + i, 0, bytesContainer.bytes[i]);
    }
  }

  @Test
  public void testReset_onFreshContainer_isNoOp() {
    // reset() on a newly created container (offset=0) must not throw or corrupt state.
    var bytesContainer = new BytesContainer();
    assertEquals(0, bytesContainer.offset);
    var backingArray = bytesContainer.bytes;

    bytesContainer.reset();

    assertEquals(0, bytesContainer.offset);
    assertSame(backingArray, bytesContainer.bytes);
  }
}
