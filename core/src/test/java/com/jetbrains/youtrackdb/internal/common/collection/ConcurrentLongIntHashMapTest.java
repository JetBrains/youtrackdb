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

  // ---- put() ----

  @Test
  public void putAndGetRoundtrip() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.put(1L, 10, "hello")).isNull();
    assertThat(map.get(1L, 10)).isEqualTo("hello");
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.isEmpty()).isFalse();
  }

  @Test
  public void putReplacesExistingValue() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "first");
    String prev = map.put(1L, 10, "second");
    assertThat(prev).isEqualTo("first");
    assertThat(map.get(1L, 10)).isEqualTo("second");
    // Size should not change on replace
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void putWithFileIdZeroAndPageIndexZero() {
    // fileId=0 and pageIndex=0 are valid keys, not sentinels.
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(0L, 0, "zero-zero");
    assertThat(map.get(0L, 0)).isEqualTo("zero-zero");
    assertThat(map.size()).isEqualTo(1);

    // Also put with fileId=0, different pageIndex
    map.put(0L, 1, "zero-one");
    assertThat(map.get(0L, 1)).isEqualTo("zero-one");
    assertThat(map.get(0L, 0)).isEqualTo("zero-zero");
    assertThat(map.size()).isEqualTo(2);
  }

  @Test
  public void putRejectsNullValue() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThatThrownBy(() -> map.put(1L, 1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Null");
    // Map must remain consistent after the error
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.get(1L, 1)).isNull();
  }

  @Test
  public void putMultipleDistinctKeys() {
    var map = new ConcurrentLongIntHashMap<String>();
    for (int i = 0; i < 100; i++) {
      map.put((long) i, i, "val-" + i);
    }
    assertThat(map.size()).isEqualTo(100);
    for (int i = 0; i < 100; i++) {
      assertThat(map.get((long) i, i)).isEqualTo("val-" + i);
    }
  }

  @Test
  public void putTriggersResizeWhenCapacityExceeded() {
    // Create a small map to easily trigger resize.
    // 4 sections, 8 items expected => 2 per section capacity, but min 2, so each section cap=2.
    // Fill factor 0.66, threshold = 2*0.66 = 1. So inserting 2 items in one section triggers resize.
    var map = new ConcurrentLongIntHashMap<String>(8, 4);
    long initialCapacity = map.capacity();

    // Insert enough entries to force at least one section to resize
    for (int i = 0; i < 20; i++) {
      map.put((long) i, i, "val-" + i);
    }

    // All entries should survive resize
    for (int i = 0; i < 20; i++) {
      assertThat(map.get((long) i, i))
          .as("Entry (%d, %d) should survive resize", (long) i, i)
          .isEqualTo("val-" + i);
    }
    assertThat(map.size()).isEqualTo(20);
    // Capacity should have grown
    assertThat(map.capacity()).isGreaterThan(initialCapacity);
  }

  // ---- putIfAbsent() ----

  @Test
  public void putIfAbsentInsertsWhenAbsent() {
    var map = new ConcurrentLongIntHashMap<String>();
    String result = map.putIfAbsent(1L, 10, "hello");
    assertThat(result).isNull();
    assertThat(map.get(1L, 10)).isEqualTo("hello");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void putIfAbsentReturnsExistingWhenPresent() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "first");
    String result = map.putIfAbsent(1L, 10, "second");
    assertThat(result).isEqualTo("first");
    // Value should not have changed
    assertThat(map.get(1L, 10)).isEqualTo("first");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void putIfAbsentRejectsNullValue() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThatThrownBy(() -> map.putIfAbsent(1L, 1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Null");
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.get(1L, 1)).isNull();
  }

  // ---- computeIfAbsent() ----

  @Test
  public void computeIfAbsentComputesWhenAbsent() {
    var map = new ConcurrentLongIntHashMap<String>();
    String result = map.computeIfAbsent(1L, 10, (fid, pid) -> "computed-" + fid + "-" + pid);
    assertThat(result).isEqualTo("computed-1-10");
    assertThat(map.get(1L, 10)).isEqualTo("computed-1-10");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void computeIfAbsentReturnsExistingWhenPresent() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "existing");
    int[] callCount = {0};
    String result =
        map.computeIfAbsent(
            1L,
            10,
            (fid, pid) -> {
              callCount[0]++;
              return "should-not-compute";
            });
    assertThat(result).isEqualTo("existing");
    assertThat(map.get(1L, 10)).isEqualTo("existing");
    assertThat(map.size()).isEqualTo(1);
    assertThat(callCount[0]).as("Mapping function must not be called when key is present").isZero();
  }

  @Test
  public void computeIfAbsentRejectsNullReturnFromFunction() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThatThrownBy(() -> map.computeIfAbsent(1L, 10, (fid, pid) -> null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not return null");
    // Map must remain consistent after the error
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.get(1L, 10)).isNull();
  }

  @Test
  public void computeIfAbsentWithFileIdZero() {
    // fileId=0 passed to the mapping function must be correct, not confused with a sentinel
    var map = new ConcurrentLongIntHashMap<String>();
    String result = map.computeIfAbsent(0L, 0, (fid, pid) -> "fid=" + fid + ",pid=" + pid);
    assertThat(result).isEqualTo("fid=0,pid=0");
    assertThat(map.get(0L, 0)).isEqualTo("fid=0,pid=0");
    assertThat(map.size()).isEqualTo(1);
  }

  // ---- Probe chain: same fileId/different pageIndex and vice versa ----

  @Test
  public void putDistinguishesSameFileIdDifferentPageIndex() {
    // Single section forces all keys into the same probe chain
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    map.put(42L, 0, "page-0");
    map.put(42L, 1, "page-1");
    map.put(42L, 2, "page-2");

    assertThat(map.get(42L, 0)).isEqualTo("page-0");
    assertThat(map.get(42L, 1)).isEqualTo("page-1");
    assertThat(map.get(42L, 2)).isEqualTo("page-2");
    assertThat(map.size()).isEqualTo(3);
  }

  @Test
  public void putDistinguishesSamePageIndexDifferentFileId() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    map.put(1L, 99, "file-1");
    map.put(2L, 99, "file-2");
    map.put(3L, 99, "file-3");

    assertThat(map.get(1L, 99)).isEqualTo("file-1");
    assertThat(map.get(2L, 99)).isEqualTo("file-2");
    assertThat(map.get(3L, 99)).isEqualTo("file-3");
    assertThat(map.size()).isEqualTo(3);
  }

  @Test
  public void putAndGetWithNegativeKeys() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(-1L, -1, "neg-neg");
    map.put(Long.MIN_VALUE, Integer.MIN_VALUE, "min-min");
    map.put(Long.MAX_VALUE, Integer.MAX_VALUE, "max-max");

    assertThat(map.get(-1L, -1)).isEqualTo("neg-neg");
    assertThat(map.get(Long.MIN_VALUE, Integer.MIN_VALUE)).isEqualTo("min-min");
    assertThat(map.get(Long.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo("max-max");
    assertThat(map.size()).isEqualTo(3);
  }

  @Test
  public void putReplaceDoesNotAffectSizeOrTriggerSpuriousResize() {
    // Single section, capacity 4, threshold = floor(4 * 0.66) = 2
    var map = new ConcurrentLongIntHashMap<String>(4, 1);
    long initialCapacity = map.capacity();

    map.put(1L, 1, "v1");
    assertThat(map.size()).isEqualTo(1);

    // Replace the same entry many times — size must stay 1, no resize
    for (int i = 0; i < 50; i++) {
      map.put(1L, 1, "v1-replaced-" + i);
    }
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.capacity()).isEqualTo(initialCapacity);
    assertThat(map.get(1L, 1)).isEqualTo("v1-replaced-49");
  }

  @Test
  public void resizeTriggersAtExactThreshold() {
    // Single section with capacity=4, threshold = (int)(4 * 0.66) = 2
    // Inserts: usedBuckets 0→1→2, third insert sees usedBuckets=2 >= 2, triggers resize
    var map = new ConcurrentLongIntHashMap<String>(4, 1);
    assertThat(map.capacity()).isEqualTo(4);

    map.put(1L, 1, "a");
    map.put(2L, 2, "b");
    assertThat(map.capacity()).isEqualTo(4);

    // Third insert triggers resize
    map.put(3L, 3, "c");
    assertThat(map.capacity()).isGreaterThan(4);

    assertThat(map.get(1L, 1)).isEqualTo("a");
    assertThat(map.get(2L, 2)).isEqualTo("b");
    assertThat(map.get(3L, 3)).isEqualTo("c");
    assertThat(map.size()).isEqualTo(3);
  }

  @Test
  public void computeIfAbsentTriggersResizeCorrectly() {
    var map = new ConcurrentLongIntHashMap<String>(8, 4);
    long initialCapacity = map.capacity();

    for (int i = 0; i < 20; i++) {
      final int idx = i;
      map.computeIfAbsent((long) i, i, (fid, pid) -> "computed-" + idx);
    }

    for (int i = 0; i < 20; i++) {
      assertThat(map.get((long) i, i))
          .as("Entry (%d, %d) should survive resize via computeIfAbsent", (long) i, i)
          .isEqualTo("computed-" + i);
    }
    assertThat(map.size()).isEqualTo(20);
    assertThat(map.capacity()).isGreaterThan(initialCapacity);
  }

  @Test
  public void putManyPagesForSameFile() {
    // Realistic access pattern: one file with many pages
    var map = new ConcurrentLongIntHashMap<String>();
    long fileId = 42L;
    for (int page = 0; page < 500; page++) {
      map.put(fileId, page, "page-" + page);
    }
    assertThat(map.size()).isEqualTo(500);
    for (int page = 0; page < 500; page++) {
      assertThat(map.get(fileId, page)).isEqualTo("page-" + page);
    }
    assertThat(map.get(43L, 0)).isNull();
  }

  @Test
  public void computeIfAbsentLeavesMapConsistentWhenFunctionThrows() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 1, "existing");

    assertThatThrownBy(
        () -> map.computeIfAbsent(
            2L,
            2,
            (fid, pid) -> {
              throw new RuntimeException("computation failed");
            }))
        .isInstanceOf(RuntimeException.class);

    // Map should still be usable and the failed key should not be present
    assertThat(map.get(1L, 1)).isEqualTo("existing");
    assertThat(map.get(2L, 2)).isNull();
    assertThat(map.size()).isEqualTo(1);
  }

  // ---- get() with populated map (deferred from Step 1) ----

  @Test
  public void getReturnsNullForAbsentKeyInPopulatedMap() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "hello");
    assertThat(map.get(1L, 11)).isNull();
    assertThat(map.get(2L, 10)).isNull();
  }

  @Test
  public void getWithFileIdZeroRetrievesCorrectly() {
    // Now that put exists, verify fileId=0 is not confused with empty sentinel
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(0L, 0, "zero-zero");
    map.put(0L, 1, "zero-one");
    map.put(1L, 0, "one-zero");

    assertThat(map.get(0L, 0)).isEqualTo("zero-zero");
    assertThat(map.get(0L, 1)).isEqualTo("zero-one");
    assertThat(map.get(1L, 0)).isEqualTo("one-zero");
    assertThat(map.get(0L, 2)).isNull();
    assertThat(map.size()).isEqualTo(3);
  }

}
