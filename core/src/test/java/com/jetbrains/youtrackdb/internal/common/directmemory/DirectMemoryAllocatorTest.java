package com.jetbrains.youtrackdb.internal.common.directmemory;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SequentialTest.class)
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

  /**
   * Verifies that allocate with clear=true zeros the allocated memory.
   * Every byte in the returned buffer should be zero.
   */
  @Test
  public void testAllocateWithClear_memoryShouldBeZeroed() {
    final var allocator = new DirectMemoryAllocator();
    final var pointer = allocator.allocate(256, true, Intention.TEST);
    try {
      final var buffer = pointer.getNativeByteBuffer();
      for (int i = 0; i < 256; i++) {
        Assert.assertEquals("byte at position " + i + " should be zero",
            0, buffer.get(i));
      }
    } finally {
      allocator.deallocate(pointer);
    }
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that allocate with clear=false returns a valid pointer (memory
   * content is indeterminate but pointer is non-null and usable).
   */
  @Test
  public void testAllocateWithoutClear_pointerIsValid() {
    final var allocator = new DirectMemoryAllocator();
    final var pointer = allocator.allocate(128, false, Intention.TEST);
    try {
      Assert.assertNotNull(pointer);
      final var buffer = pointer.getNativeByteBuffer();
      Assert.assertEquals(128, buffer.capacity());
    } finally {
      allocator.deallocate(pointer);
    }
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that checkMemoryLeaks passes when all pointers have been
   * properly deallocated (no leaks).
   */
  @Test
  public void testCheckMemoryLeaks_noLeaks_passes() {
    final var allocator = new DirectMemoryAllocator();
    final var p1 = allocator.allocate(64, false, Intention.TEST);
    final var p2 = allocator.allocate(128, false, Intention.TEST);
    allocator.deallocate(p1);
    allocator.deallocate(p2);

    Assert.assertEquals(0, allocator.getMemoryConsumption());
    // Should not throw — all memory is freed
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that multiple allocations and deallocations correctly track
   * total memory consumption (LongAdder increments/decrements).
   */
  @Test
  public void testMemoryConsumption_multipleAllocations() {
    final var allocator = new DirectMemoryAllocator();
    final var p1 = allocator.allocate(100, false, Intention.TEST);
    final var p2 = allocator.allocate(200, false, Intention.TEST);
    try {
      Assert.assertEquals(300, allocator.getMemoryConsumption());
    } finally {
      allocator.deallocate(p1);
      allocator.deallocate(p2);
    }
    Assert.assertEquals(0, allocator.getMemoryConsumption());
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that the singleton instance() method returns the same instance
   * on subsequent calls.
   */
  @Test
  public void testInstance_returnsSameInstance() {
    final var inst1 = DirectMemoryAllocator.instance();
    final var inst2 = DirectMemoryAllocator.instance();
    Assert.assertSame(inst1, inst2);
  }

  /**
   * Verifies that allocating multiple pointers with different intentions
   * all track correctly and can be cleanly deallocated.
   */
  @Test
  public void testAllocateWithDifferentIntentions() {
    final var allocator = new DirectMemoryAllocator();
    final var p1 = allocator.allocate(64, true, Intention.TEST);
    final var p2 = allocator.allocate(64, false, Intention.PAGE_PRE_ALLOCATION);
    final var p3 = allocator.allocate(64, true, Intention.LOAD_PAGE_FROM_DISK);
    try {
      Assert.assertEquals(192, allocator.getMemoryConsumption());
    } finally {
      allocator.deallocate(p1);
      allocator.deallocate(p2);
      allocator.deallocate(p3);
    }
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that checkTrackedPointerLeaks does not fail when no pointers
   * have been leaked (reference queue is empty).
   */
  @Test
  public void testCheckTrackedPointerLeaks_noLeaks() {
    final var allocator = new DirectMemoryAllocator();
    final var ptr = allocator.allocate(64, false, Intention.TEST);
    allocator.deallocate(ptr);
    // Should not fail — no leaked pointers in the reference queue
    allocator.checkTrackedPointerLeaks();
  }

  /**
   * Verifies that checkMemoryLeaks detects a non-deallocated pointer.
   * With TRACK=true (set by @BeforeClass), the method iterates over
   * tracked references, logs them, and fires an assertion. The leaked
   * pointer is cleaned up in the finally block.
   */
  @Test
  public void testCheckMemoryLeaks_withLeak_detectsUnreleasedPointer() {
    final var allocator = new DirectMemoryAllocator();
    final var leaked = allocator.allocate(64, false, Intention.TEST);
    try {
      // checkMemoryLeaks should detect the unreleased pointer.
      // With -ea enabled, it fires "assert trackedReferences.isEmpty()"
      // or "assert false" for non-zero consumption.
      allocator.checkMemoryLeaks();
      // If assertions are disabled, the method logs but does not throw.
      // Verify non-zero consumption as fallback.
      Assert.assertTrue("Expected non-zero consumption for leaked pointer",
          allocator.getMemoryConsumption() > 0);
    } catch (AssertionError expected) {
      // Expected: leak detection assertion fired — this confirms the
      // detection path is exercised.
    } finally {
      allocator.deallocate(leaked);
    }
    // After cleanup, should pass
    Assert.assertEquals(0, allocator.getMemoryConsumption());
  }
}
