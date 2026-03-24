package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import org.junit.Test;

/**
 * Unit tests for {@link ConcurrentLongIntHashMap}.
 *
 * <p>Tests are organized by operation, starting with constructor and get() (Step 1), followed by
 * mutation operations in subsequent steps.
 */
public class ConcurrentLongIntHashMapTest {

  // ---- Constructor tests ----

  @Test
  public void emptyMapHasSizeZero() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
  }

  @Test
  public void constructorWithExpectedItems() {
    var map = new ConcurrentLongIntHashMap<String>(1024);
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
    // 1024 / 16 sections = 64 per section (already power of two), x 16 = 1024
    assertThat(map.capacity()).isEqualTo(1024);
  }

  @Test
  public void constructorWithCustomSectionCount() {
    var map = new ConcurrentLongIntHashMap<String>(256, 4);
    assertThat(map.size()).isEqualTo(0);
    // 256 / 4 = 64 per section (already power of two), x 4 = 256
    assertThat(map.capacity()).isEqualTo(256);
    assertThat(map.capacity() % 4).isEqualTo(0);
    long perSection = map.capacity() / 4;
    assertThat(perSection & (perSection - 1))
        .as("Per-section capacity should be a power of two")
        .isEqualTo(0);
  }

  @Test
  public void constructorWithZeroExpectedItemsCreatesMinimalMap() {
    // expectedItems=0 is valid — creates a map with minimum per-section capacity.
    var map = new ConcurrentLongIntHashMap<String>(0);
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
    // Each section gets Math.max(2, alignToPowerOfTwo(0/16)) = 2, so total = 2 * 16 = 32
    assertThat(map.capacity()).isEqualTo(32);
    // Must be usable — get should not throw
    assertThat(map.get(1L, 1)).isNull();
  }

  @Test
  public void constructorWithOneExpectedItem() {
    // Smallest positive expectedItems — each section gets minimum capacity of 2.
    var map = new ConcurrentLongIntHashMap<String>(1);
    assertThat(map.size()).isEqualTo(0);
    // 1 / 16 = 0, alignToPowerOfTwo(0) = 1, Math.max(2, 1) = 2, x 16 = 32
    assertThat(map.capacity()).isEqualTo(32);
  }

  @Test
  public void constructorRejectsNonPowerOfTwoSectionCount() {
    assertThatThrownBy(() -> new ConcurrentLongIntHashMap<String>(256, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("power of two");
  }

  @Test
  public void constructorRejectsZeroSectionCount() {
    assertThatThrownBy(() -> new ConcurrentLongIntHashMap<String>(256, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("power of two");
  }

  @Test
  public void constructorRejectsNegativeSectionCount() {
    assertThatThrownBy(() -> new ConcurrentLongIntHashMap<String>(256, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("power of two");
    // -16 has the same bit pattern as a power of two in the low bits
    assertThatThrownBy(() -> new ConcurrentLongIntHashMap<String>(256, -16))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("power of two");
  }

  @Test
  public void constructorRejectsNegativeExpectedItems() {
    assertThatThrownBy(() -> new ConcurrentLongIntHashMap<String>(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-negative")
        .hasMessageContaining("-1");
  }

  // ---- get() on empty map ----

  @Test
  public void getOnEmptyMapReturnsNull() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.get(1L, 0)).isNull();
    assertThat(map.get(Long.MAX_VALUE, Integer.MAX_VALUE)).isNull();
  }

  @Test
  public void getWithFileIdZeroOnEmptyMapReturnsNull() {
    // fileId=0 is a valid key, not a sentinel. Must not confuse with empty slot.
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.get(0L, 0)).isNull();
    assertThat(map.get(0L, 42)).isNull();
  }

  @Test
  public void getWithPageIndexZeroOnEmptyMapReturnsNull() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.get(42L, 0)).isNull();
  }

  @Test
  public void getWithNegativeKeyValuesOnEmptyMapReturnsNull() {
    // Negative fileId and pageIndex must not cause ArrayIndexOutOfBoundsException
    // in the section index calculation (uses >>> unsigned shift).
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.get(-1L, -1)).isNull();
    assertThat(map.get(Long.MIN_VALUE, Integer.MIN_VALUE)).isNull();
    assertThat(map.get(-1L, Integer.MIN_VALUE)).isNull();
    assertThat(map.get(Long.MIN_VALUE, -1)).isNull();
  }

  // ---- Hash distribution sanity ----

  @Test
  public void hashFunctionProducesDifferentValuesForDifferentKeys() {
    // Verify that nearby keys produce different hashes (no trivial collisions)
    long h1 = ConcurrentLongIntHashMap.hash(0L, 0);
    long h2 = ConcurrentLongIntHashMap.hash(0L, 1);
    long h3 = ConcurrentLongIntHashMap.hash(1L, 0);
    long h4 = ConcurrentLongIntHashMap.hash(1L, 1);

    assertThat(h1).isNotEqualTo(h2);
    assertThat(h1).isNotEqualTo(h3);
    assertThat(h1).isNotEqualTo(h4);
    assertThat(h2).isNotEqualTo(h3);
    assertThat(h2).isNotEqualTo(h4);
    assertThat(h3).isNotEqualTo(h4);
  }

  @Test
  public void hashFunctionDistributesFileIdsAcrossSections() {
    // With default 16 sections, a range of fileIds should distribute uniformly.
    int[] sectionHits = new int[16];
    for (int fileId = 0; fileId < 1000; fileId++) {
      long h = ConcurrentLongIntHashMap.hash(fileId, 0);
      int section = (int) (h >>> 32) & 15;
      sectionHits[section]++;
    }
    // 1000 keys / 16 sections = 62.5 expected per section.
    // Each section should be within a reasonable range of the mean.
    for (int i = 0; i < sectionHits.length; i++) {
      assertThat(sectionHits[i])
          .as("Section %d hit count should be within reasonable range", i)
          .isBetween(30, 120);
    }
  }

  @Test
  public void hashFunctionDistributesPageIndicesAcrossSections() {
    // Varying only pageIndex while fixing fileId should also distribute well.
    int[] sectionHits = new int[16];
    for (int pageIndex = 0; pageIndex < 1000; pageIndex++) {
      long h = ConcurrentLongIntHashMap.hash(42L, pageIndex);
      int section = (int) (h >>> 32) & 15;
      sectionHits[section]++;
    }
    for (int i = 0; i < sectionHits.length; i++) {
      assertThat(sectionHits[i])
          .as("Section %d hit count should be within reasonable range", i)
          .isBetween(30, 120);
    }
  }

  @Test
  public void hashFunctionHandlesExtremeKeyValues() {
    // All extreme combinations must produce distinct hashes.
    long[] fileIds = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE};
    int[] pageIndices = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};

    var hashes = new HashSet<Long>();
    for (long fid : fileIds) {
      for (int pid : pageIndices) {
        hashes.add(ConcurrentLongIntHashMap.hash(fid, pid));
      }
    }
    // 25 distinct key pairs should yield mostly distinct hashes.
    // A few collisions are acceptable for a 64-bit hash over extreme values.
    assertThat(hashes.size())
        .as("At most 2 collisions among 25 extreme key pairs")
        .isGreaterThanOrEqualTo(23);
  }

  // ---- alignToPowerOfTwo ----

  @Test
  public void alignToPowerOfTwoBasicCases() {
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(-1)).isEqualTo(1);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(-100)).isEqualTo(1);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(Integer.MIN_VALUE)).isEqualTo(1);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(0)).isEqualTo(1);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(1)).isEqualTo(1);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(2)).isEqualTo(2);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(3)).isEqualTo(4);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(4)).isEqualTo(4);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(5)).isEqualTo(8);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(100)).isEqualTo(128);
  }

  @Test
  public void alignToPowerOfTwoMaxValidPowerOfTwo() {
    // 1 << 30 is the largest valid power-of-two for int arrays.
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(1 << 30)).isEqualTo(1 << 30);
  }

  @Test
  public void alignToPowerOfTwoOverflowThrows() {
    // Values above 2^30 cannot be rounded up without overflowing int.
    assertThatThrownBy(() -> ConcurrentLongIntHashMap.alignToPowerOfTwo((1 << 30) + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds maximum");
    assertThatThrownBy(() -> ConcurrentLongIntHashMap.alignToPowerOfTwo(Integer.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds maximum");
  }

  // ---- Capacity ----

  @Test
  public void capacityIsPowerOfTwoPerSection() {
    // Each section's capacity must be power-of-two for the bucket mask to work.
    // 100 / 4 = 25, alignToPowerOfTwo(25) = 32, x 4 = 128
    var map = new ConcurrentLongIntHashMap<String>(100, 4);
    long totalCapacity = map.capacity();
    assertThat(totalCapacity).isEqualTo(128);
    assertThat(totalCapacity % 4).isEqualTo(0);
    long perSection = totalCapacity / 4;
    assertThat(perSection & (perSection - 1))
        .as("Per-section capacity should be a power of two")
        .isEqualTo(0);
  }

  @Test
  public void singleSectionMap() {
    // Edge case: map with a single section
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
    assertThat(map.capacity()).isEqualTo(16);
  }
}
