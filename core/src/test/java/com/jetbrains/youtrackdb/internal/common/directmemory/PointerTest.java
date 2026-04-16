package com.jetbrains.youtrackdb.internal.common.directmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link Pointer}. Verifies equals/hashCode contract, getNativeByteBuffer
 * lazy creation with SoftReference caching, clear() zeroing, and accessor methods.
 * Tests are in the same package for access to package-private constructor and getters.
 */
public class PointerTest {

  private DirectMemoryAllocator allocator;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
  }

  @After
  public void tearDown() {
    allocator.checkMemoryLeaks();
  }

  // --- equals() ---

  /**
   * Verifies that two Pointer instances wrapping the same native address and
   * size are considered equal.
   */
  @Test
  public void equals_sameAddressAndSize_areEqual() {
    var ptr = allocator.allocate(128, false, Intention.TEST);
    try {
      // Create a second Pointer with the same address and size
      var ptr2 = new Pointer(ptr.getNativePointer(), ptr.getSize(), Intention.TEST);
      assertThat(ptr).isEqualTo(ptr2);
      assertThat(ptr2).isEqualTo(ptr);
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies that two Pointer instances with the same address but different
   * sizes are NOT equal.
   */
  @Test
  public void equals_sameAddressDifferentSize_notEqual() {
    var ptr = allocator.allocate(256, false, Intention.TEST);
    try {
      var ptr2 = new Pointer(ptr.getNativePointer(), 128, Intention.TEST);
      assertThat(ptr).isNotEqualTo(ptr2);
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies that two Pointer instances with different addresses are NOT equal.
   */
  @Test
  public void equals_differentAddress_notEqual() {
    var ptr1 = allocator.allocate(64, false, Intention.TEST);
    var ptr2 = allocator.allocate(64, false, Intention.TEST);
    try {
      assertThat(ptr1).isNotEqualTo(ptr2);
    } finally {
      allocator.deallocate(ptr1);
      allocator.deallocate(ptr2);
    }
  }

  /**
   * Verifies equals returns false for null.
   */
  @Test
  public void equals_null_returnsFalse() {
    var ptr = allocator.allocate(64, false, Intention.TEST);
    try {
      assertThat(ptr.equals(null)).isFalse();
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies equals returns false for a different class.
   */
  @Test
  public void equals_differentClass_returnsFalse() {
    var ptr = allocator.allocate(64, false, Intention.TEST);
    try {
      assertThat(ptr.equals("not a pointer")).isFalse();
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies equals returns true for the same instance (identity check).
   */
  @Test
  public void equals_sameInstance_returnsTrue() {
    var ptr = allocator.allocate(64, false, Intention.TEST);
    try {
      assertThat(ptr.equals(ptr)).isTrue();
    } finally {
      allocator.deallocate(ptr);
    }
  }

  // --- hashCode() ---

  /**
   * Verifies that hashCode is consistent across multiple calls (including
   * the caching path where hash != 0).
   */
  @Test
  public void hashCode_consistentAcrossCalls() {
    var ptr = allocator.allocate(64, false, Intention.TEST);
    try {
      int hash1 = ptr.hashCode();
      int hash2 = ptr.hashCode(); // should use cached value
      int hash3 = ptr.hashCode();
      assertThat(hash1).isEqualTo(hash2).isEqualTo(hash3);
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies that equal Pointers produce the same hashCode.
   */
  @Test
  public void hashCode_equalPointers_sameHash() {
    var ptr = allocator.allocate(128, false, Intention.TEST);
    try {
      var ptr2 = new Pointer(ptr.getNativePointer(), ptr.getSize(), Intention.TEST);
      assertThat(ptr.hashCode()).isEqualTo(ptr2.hashCode());
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies that different Pointers (different address) usually produce
   * different hashCodes (probabilistic but very likely for non-colliding
   * memory addresses).
   */
  @Test
  public void hashCode_differentPointers_usuallyDiffer() {
    var ptr1 = allocator.allocate(64, false, Intention.TEST);
    var ptr2 = allocator.allocate(64, false, Intention.TEST);
    try {
      // Not guaranteed to differ, but for two different heap addresses
      // it's extremely likely
      assertThat(ptr1.hashCode()).isNotEqualTo(ptr2.hashCode());
    } finally {
      allocator.deallocate(ptr1);
      allocator.deallocate(ptr2);
    }
  }

  // --- getNativeByteBuffer() ---

  /**
   * Verifies getNativeByteBuffer creates a buffer with the correct capacity
   * and native byte order.
   */
  @Test
  public void getNativeByteBuffer_correctCapacityAndOrder() {
    var ptr = allocator.allocate(256, false, Intention.TEST);
    try {
      ByteBuffer buffer = ptr.getNativeByteBuffer();
      assertThat(buffer).isNotNull();
      assertThat(buffer.capacity()).isEqualTo(256);
      assertThat(buffer.order()).isEqualTo(ByteOrder.nativeOrder());
      assertThat(buffer.isDirect()).isTrue();
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies getNativeByteBuffer returns the same cached buffer on subsequent
   * calls (SoftReference reuse path).
   */
  @Test
  public void getNativeByteBuffer_returnsCachedBuffer() {
    var ptr = allocator.allocate(128, false, Intention.TEST);
    try {
      ByteBuffer buffer1 = ptr.getNativeByteBuffer();
      ByteBuffer buffer2 = ptr.getNativeByteBuffer();
      assertThat(buffer1).isSameAs(buffer2);
    } finally {
      allocator.deallocate(ptr);
    }
  }

  // --- clear() ---

  /**
   * Verifies clear() zeros the underlying memory.
   */
  @Test
  public void clear_zerosMemory() {
    var ptr = allocator.allocate(64, false, Intention.TEST);
    try {
      ByteBuffer buffer = ptr.getNativeByteBuffer();
      // Write non-zero data
      for (int i = 0; i < 64; i++) {
        buffer.put(i, (byte) 0xFF);
      }
      // Clear should zero all bytes
      ptr.clear();
      for (int i = 0; i < 64; i++) {
        assertThat(buffer.get(i))
            .as("byte at position %d should be zero after clear()", i)
            .isEqualTo((byte) 0);
      }
    } finally {
      allocator.deallocate(ptr);
    }
  }

  // --- Package-private accessors ---

  /**
   * Verifies getNativePointer returns a valid (positive) address.
   */
  @Test
  public void getNativePointer_returnsPositiveAddress() {
    var ptr = allocator.allocate(64, false, Intention.TEST);
    try {
      assertThat(ptr.getNativePointer()).isGreaterThan(0);
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies getSize returns the allocation size.
   */
  @Test
  public void getSize_returnsAllocationSize() {
    var ptr = allocator.allocate(512, false, Intention.TEST);
    try {
      assertThat(ptr.getSize()).isEqualTo(512);
    } finally {
      allocator.deallocate(ptr);
    }
  }

  /**
   * Verifies getIntention returns the allocation intention.
   */
  @Test
  public void getIntention_returnsAllocationIntention() {
    var ptr = allocator.allocate(64, false, Intention.PAGE_PRE_ALLOCATION);
    try {
      assertThat(ptr.getIntention()).isEqualTo(Intention.PAGE_PRE_ALLOCATION);
    } finally {
      allocator.deallocate(ptr);
    }
  }
}
