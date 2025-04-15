package com.jetbrains.youtrack.db.internal.common.directmemory;

import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import java.nio.ByteBuffer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class DirectMemoryAllocatorTest {

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testAllocateDeallocate() {
    final var directMemoryAllocator = new DirectMemoryAllocator();
    final var pointer = directMemoryAllocator.allocate(42, false, Intention.TEST);
    Assert.assertNotNull(pointer);

    Assert.assertEquals(42, directMemoryAllocator.getMemoryConsumption());

    final var buffer = pointer.getNativeByteBuffer();
    Assert.assertEquals(42, buffer.capacity());
    directMemoryAllocator.deallocate(pointer);

    Assert.assertEquals(0, directMemoryAllocator.getMemoryConsumption());
  }

  @Test
  public void testNegativeOrZeroIsPassedToAllocate() {
    final var directMemoryAllocator = new DirectMemoryAllocator();
    try {
      directMemoryAllocator.allocate(0, false, Intention.TEST);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(true);
    }

    try {
      directMemoryAllocator.allocate(-1, false, Intention.TEST);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(true);
    }
  }

  @Test
  public void testNullValueIsPassedToDeallocate() {
    final var directMemoryAllocator = new DirectMemoryAllocator();
    try {
      directMemoryAllocator.deallocate(null);
      Assert.fail();
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(true);
    }
  }
}
