package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Direct unit tests for {@link DirtyPageBitSetPage}, verifying the low-level bit
 * manipulation operations: setBit, clearBit, isBitSet, and nextSetBit.
 *
 * <p>These tests target PIT surviving mutants at DirtyPageBitSetPage lines 80-96,
 * which involve byte offset calculation, bit mask creation, bitwise operations,
 * and nextSetBit boundary conditions.
 */
public class DirtyPageBitSetPageTest {

  private ByteBufferPool bufferPool;
  private CachePointer cachePointer;
  private DirtyPageBitSetPage page;

  @Before
  public void setUp() {
    bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    cachePointer = new CachePointer(pointer, bufferPool, 1L, 0);
    cachePointer.incrementReferrer();

    var cacheEntry = new CacheEntryImpl(1L, 0, cachePointer, false, mock(ReadCache.class));
    page = new DirtyPageBitSetPage(cacheEntry);
    page.init();
  }

  @After
  public void tearDown() {
    cachePointer.decrementReferrer();
  }

  // --- isBitSet: correct byte offset calculation (line 80) ---

  // Verifies that isBitSet correctly computes the byte offset for bits in different bytes.
  // Targets mutant: "Replaced integer addition with subtraction / Replaced integer division
  // with multiplication" at line 80.
  @Test
  public void isBitSetComputesCorrectByteOffset() {
    // Bit 0 is in byte 0, bit 8 is in byte 1, bit 16 is in byte 2
    page.setBit(0);
    page.setBit(8);
    page.setBit(16);

    assertThat(page.isBitSet(0)).isTrue();
    assertThat(page.isBitSet(8)).isTrue();
    assertThat(page.isBitSet(16)).isTrue();

    // Adjacent bits should NOT be set
    assertThat(page.isBitSet(1)).isFalse();
    assertThat(page.isBitSet(7)).isFalse();
    assertThat(page.isBitSet(9)).isFalse();
    assertThat(page.isBitSet(15)).isFalse();
    assertThat(page.isBitSet(17)).isFalse();
  }

  // --- isBitSet: correct bit mask (line 82) ---

  // Verifies that isBitSet uses the correct bit mask (1 << (bitIndex % 8)).
  // Targets mutant: "Replaced Shift Left with Shift Right / Replaced integer modulus
  // with multiplication" at line 82.
  @Test
  public void isBitSetUsesCorrectBitMask() {
    // Set each bit in the first byte individually and verify
    for (int bit = 0; bit < 8; bit++) {
      page.init(); // Clear all bits
      page.setBit(bit);

      // Only the set bit should be true
      for (int check = 0; check < 8; check++) {
        if (check == bit) {
          assertThat(page.isBitSet(check))
              .as("bit %d should be set", check)
              .isTrue();
        } else {
          assertThat(page.isBitSet(check))
              .as("bit %d should NOT be set when only bit %d is set", check, bit)
              .isFalse();
        }
      }
    }
  }

  // --- isBitSet: bitwise AND comparison (line 83) ---

  // Verifies that isBitSet correctly distinguishes set and unset bits.
  // Targets mutant: "replaced boolean return with true / removed conditional /
  // Replaced bitwise AND with OR" at line 83.
  @Test
  public void isBitSetReturnsFalseForUnsetBits() {
    // No bits are set after init()
    for (int i = 0; i < 64; i++) {
      assertThat(page.isBitSet(i))
          .as("bit %d should be false after init", i)
          .isFalse();
    }

    // Set specific bits and verify others remain false
    page.setBit(5);
    page.setBit(13);
    assertThat(page.isBitSet(5)).isTrue();
    assertThat(page.isBitSet(13)).isTrue();
    assertThat(page.isBitSet(4)).isFalse();
    assertThat(page.isBitSet(6)).isFalse();
    assertThat(page.isBitSet(12)).isFalse();
    assertThat(page.isBitSet(14)).isFalse();
  }

  // --- nextSetBit: boundary condition at BITS_PER_PAGE (line 95) ---

  // Verifies that nextSetBit returns -1 when called with fromBitIndex >= BITS_PER_PAGE.
  // Targets mutant: "changed conditional boundary" at line 95.
  @Test
  public void nextSetBitReturnsMinusOneAtBitsPerPage() {
    page.setBit(0); // Set a bit so the page isn't empty

    // Calling with fromBitIndex == BITS_PER_PAGE should return -1
    assertThat(page.nextSetBit(DirtyPageBitSetPage.BITS_PER_PAGE))
        .as("nextSetBit at BITS_PER_PAGE should return -1")
        .isEqualTo(-1);

    // Calling with fromBitIndex == BITS_PER_PAGE - 1 should work
    page.setBit(DirtyPageBitSetPage.BITS_PER_PAGE - 1);
    assertThat(page.nextSetBit(DirtyPageBitSetPage.BITS_PER_PAGE - 1))
        .as("nextSetBit at last valid bit should find it")
        .isEqualTo(DirtyPageBitSetPage.BITS_PER_PAGE - 1);
  }

  // --- nextSetBit: return value at boundary (line 96) ---

  // Verifies that nextSetBit returns the correct bit index, not zero.
  // Targets mutant: "replaced int return with 0" at line 96.
  @Test
  public void nextSetBitReturnsCorrectIndex() {
    // Set a bit far from index 0 to detect "return 0" mutation
    page.setBit(100);
    assertThat(page.nextSetBit(0))
        .as("nextSetBit should return 100, not 0")
        .isEqualTo(100);

    page.setBit(42);
    assertThat(page.nextSetBit(0))
        .as("nextSetBit should return 42 (first set bit)")
        .isEqualTo(42);

    assertThat(page.nextSetBit(43))
        .as("nextSetBit from 43 should return 100")
        .isEqualTo(100);
  }

  // --- Additional: setBit and clearBit round-trip ---

  @Test
  public void setBitAndClearBitRoundTrip() {
    // Test across multiple bytes
    int[] positions = {0, 1, 7, 8, 15, 16, 31, 32, 63, 64, 100, 255, 1000};
    for (int pos : positions) {
      page.setBit(pos);
      assertThat(page.isBitSet(pos))
          .as("bit %d should be set", pos)
          .isTrue();

      page.clearBit(pos);
      assertThat(page.isBitSet(pos))
          .as("bit %d should be cleared", pos)
          .isFalse();
    }
  }

  // --- nextSetBit: scanning across byte boundaries ---

  @Test
  public void nextSetBitScansAcrossByteBoundaries() {
    page.setBit(7); // Last bit of byte 0
    page.setBit(8); // First bit of byte 1
    page.setBit(15); // Last bit of byte 1
    page.setBit(16); // First bit of byte 2

    assertThat(page.nextSetBit(0)).isEqualTo(7);
    assertThat(page.nextSetBit(8)).isEqualTo(8);
    assertThat(page.nextSetBit(9)).isEqualTo(15);
    assertThat(page.nextSetBit(16)).isEqualTo(16);
    assertThat(page.nextSetBit(17)).isEqualTo(-1);
  }

  // --- nextSetBit: empty page ---

  @Test
  public void nextSetBitOnEmptyPageReturnsMinusOne() {
    assertThat(page.nextSetBit(0)).isEqualTo(-1);
  }

  // --- nextSetBit: bit at position 0 ---

  @Test
  public void nextSetBitFindsZerothBit() {
    page.setBit(0);
    assertThat(page.nextSetBit(0)).isEqualTo(0);
  }

  // --- Multiple bits in same byte ---

  @Test
  public void multipleBitsInSameByte() {
    page.setBit(2);
    page.setBit(5);
    page.setBit(7);

    assertThat(page.isBitSet(2)).isTrue();
    assertThat(page.isBitSet(5)).isTrue();
    assertThat(page.isBitSet(7)).isTrue();
    assertThat(page.isBitSet(0)).isFalse();
    assertThat(page.isBitSet(1)).isFalse();
    assertThat(page.isBitSet(3)).isFalse();
    assertThat(page.isBitSet(4)).isFalse();
    assertThat(page.isBitSet(6)).isFalse();

    assertThat(page.nextSetBit(0)).isEqualTo(2);
    assertThat(page.nextSetBit(3)).isEqualTo(5);
    assertThat(page.nextSetBit(6)).isEqualTo(7);
    assertThat(page.nextSetBit(8)).isEqualTo(-1);
  }
}
