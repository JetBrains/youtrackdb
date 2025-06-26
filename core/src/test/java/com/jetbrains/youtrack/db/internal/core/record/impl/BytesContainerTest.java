package com.jetbrains.youtrack.db.internal.core.record.impl;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BytesContainer;
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
}
