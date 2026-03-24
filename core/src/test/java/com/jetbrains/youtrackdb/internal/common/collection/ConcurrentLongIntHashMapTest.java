package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    // Capacity should be at least 1024 / 16 sections, power-of-two aligned, times 16
    assertThat(map.capacity()).isGreaterThanOrEqualTo(1024);
  }

  @Test
  public void constructorWithCustomSectionCount() {
    var map = new ConcurrentLongIntHashMap<String>(256, 4);
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.capacity()).isGreaterThan(0);
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
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void constructorRejectsNegativeExpectedItems() {
    assertThatThrownBy(() -> new ConcurrentLongIntHashMap<String>(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-negative");
  }

  // ---- get() on empty map ----

  @Test
  public void getOnEmptyMapReturnsNull() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.get(1L, 0)).isNull();
    assertThat(map.get(0L, 0)).isNull();
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
  public void hashFunctionDistributesAcrossSections() {
    // With default 16 sections, a range of fileIds should hit multiple sections.
    int[] sectionHits = new int[16];
    for (int fileId = 0; fileId < 1000; fileId++) {
      long h = ConcurrentLongIntHashMap.hash(fileId, 0);
      int section = (int) (h >>> 32) & 15;
      sectionHits[section]++;
    }
    // Each section should get at least some hits — no section should be starved.
    for (int hits : sectionHits) {
      assertThat(hits)
          .as("Each section should receive some entries for a range of fileIds")
          .isGreaterThan(0);
    }
  }

  // ---- alignToPowerOfTwo ----

  @Test
  public void alignToPowerOfTwoBasicCases() {
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(0)).isEqualTo(1);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(1)).isEqualTo(1);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(2)).isEqualTo(2);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(3)).isEqualTo(4);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(4)).isEqualTo(4);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(5)).isEqualTo(8);
    assertThat(ConcurrentLongIntHashMap.alignToPowerOfTwo(100)).isEqualTo(128);
  }

  // ---- Capacity ----

  @Test
  public void capacityIsPowerOfTwoPerSection() {
    // Each section's capacity must be power-of-two for the bucket mask to work.
    var map = new ConcurrentLongIntHashMap<String>(100, 4);
    long totalCapacity = map.capacity();
    // Total capacity = 4 sections × per-section capacity (power of two)
    assertThat(totalCapacity).isGreaterThanOrEqualTo(100);
    // Per-section capacity is power of two, so total is divisible by 4
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
    assertThat(map.capacity()).isGreaterThanOrEqualTo(16);
  }
}
