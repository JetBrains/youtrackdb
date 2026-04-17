package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    // Third insert triggers resize: 4 → 8
    map.put(3L, 3, "c");
    assertThat(map.capacity()).isEqualTo(8);

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
        .isInstanceOf(RuntimeException.class)
        .hasMessage("computation failed");

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

  // ---- compute() ----

  @Test
  public void computeOnAbsentKeyWithNullReturnIsNoOp() {
    // Absent key + null return = no-op (R1/T2 review decision)
    var map = new ConcurrentLongIntHashMap<String>();
    String result = map.compute(1L, 10, (fid, pid, current) -> null);
    assertThat(result).isNull();
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.get(1L, 10)).isNull();
  }

  @Test
  public void computeOnAbsentKeyWithNonNullReturnInserts() {
    var map = new ConcurrentLongIntHashMap<String>();
    String result = map.compute(1L, 10, (fid, pid, current) -> {
      assertThat(current).as("Current value should be null for absent key").isNull();
      return "new-value";
    });
    assertThat(result).isEqualTo("new-value");
    assertThat(map.get(1L, 10)).isEqualTo("new-value");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void computeOnPresentKeyWithNullReturnRemoves() {
    // Present key + null return = removal (R1/T2 review decision)
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "existing");
    String result = map.compute(1L, 10, (fid, pid, current) -> {
      assertThat(current).isEqualTo("existing");
      return null;
    });
    assertThat(result).isNull();
    assertThat(map.get(1L, 10)).isNull();
    assertThat(map.size()).isEqualTo(0);
  }

  @Test
  public void computeOnPresentKeyWithNonNullReturnReplaces() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "old");
    String result = map.compute(1L, 10, (fid, pid, current) -> "new-" + current);
    assertThat(result).isEqualTo("new-old");
    assertThat(map.get(1L, 10)).isEqualTo("new-old");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void computePassesCallerSuppliedKeysForAbsentKey() {
    // R8 review decision: pass caller's fileId/pageIndex, not array contents
    var map = new ConcurrentLongIntHashMap<String>();
    map.compute(42L, 99, (fid, pid, current) -> {
      assertThat(fid).isEqualTo(42L);
      assertThat(pid).isEqualTo(99);
      return "value";
    });
    assertThat(map.get(42L, 99)).isEqualTo("value");
  }

  // ---- remove() ----

  @Test
  public void removeReturnsRemovedValue() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "hello");
    String removed = map.remove(1L, 10);
    assertThat(removed).isEqualTo("hello");
    assertThat(map.get(1L, 10)).isNull();
    assertThat(map.size()).isEqualTo(0);
  }

  @Test
  public void removeReturnsNullForAbsentKey() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.remove(1L, 10)).isNull();
    assertThat(map.size()).isEqualTo(0);
  }

  @Test
  public void removeDoesNotAffectOtherEntries() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    map.put(1L, 1, "a");
    map.put(2L, 2, "b");
    map.put(3L, 3, "c");

    map.remove(2L, 2);

    assertThat(map.get(1L, 1)).isEqualTo("a");
    assertThat(map.get(2L, 2)).isNull();
    assertThat(map.get(3L, 3)).isEqualTo("c");
    assertThat(map.size()).isEqualTo(2);
  }

  @Test
  public void getFindsEntryAfterRemovalInProbeChain() {
    // Verify backward-sweep cleanup: after removing an entry mid-chain, subsequent
    // entries must still be findable (no tombstone gap breaks the probe).
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    // Insert several entries that may form a probe chain in the single section
    for (int i = 0; i < 8; i++) {
      map.put(1L, i, "val-" + i);
    }

    // Remove entries from the middle of the chain
    map.remove(1L, 2);
    map.remove(1L, 4);

    // All remaining entries must still be findable
    assertThat(map.get(1L, 0)).isEqualTo("val-0");
    assertThat(map.get(1L, 1)).isEqualTo("val-1");
    assertThat(map.get(1L, 2)).isNull();
    assertThat(map.get(1L, 3)).isEqualTo("val-3");
    assertThat(map.get(1L, 4)).isNull();
    assertThat(map.get(1L, 5)).isEqualTo("val-5");
    assertThat(map.get(1L, 6)).isEqualTo("val-6");
    assertThat(map.get(1L, 7)).isEqualTo("val-7");
    assertThat(map.size()).isEqualTo(6);
  }

  // ---- Conditional remove ----

  @Test
  public void conditionalRemoveSucceedsWithSameReference() {
    // T7 review decision: reference equality
    var map = new ConcurrentLongIntHashMap<String>();
    String value = "hello";
    map.put(1L, 10, value);
    boolean removed = map.remove(1L, 10, value);
    assertThat(removed).isTrue();
    assertThat(map.get(1L, 10)).isNull();
    assertThat(map.size()).isEqualTo(0);
  }

  @Test
  public void conditionalRemoveFailsWithDifferentReferenceEvenIfEqual() {
    // T7: uses == not equals()
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "hello");
    // new String creates a different reference that equals() the stored one
    boolean removed = map.remove(1L, 10, new String("hello"));
    assertThat(removed).isFalse();
    assertThat(map.get(1L, 10)).isEqualTo("hello");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void conditionalRemoveReturnsFalseForAbsentKey() {
    var map = new ConcurrentLongIntHashMap<String>();
    assertThat(map.remove(1L, 10, "nothing")).isFalse();
  }

  @Test
  public void removeAndReinsertWorks() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "first");
    map.remove(1L, 10);
    map.put(1L, 10, "second");
    assertThat(map.get(1L, 10)).isEqualTo("second");
    assertThat(map.size()).isEqualTo(1);
  }

  // ---- Backward-sweep edge cases ----

  @Test
  public void removeSingleEntryLeavesMapConsistentForReinsert() {
    var map = new ConcurrentLongIntHashMap<String>(4, 1);
    long initialCapacity = map.capacity();

    map.put(1L, 1, "only");
    assertThat(map.size()).isEqualTo(1);

    map.remove(1L, 1);
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
    assertThat(map.capacity()).isEqualTo(initialCapacity);

    // Proves usedBuckets was correctly decremented to 0
    map.put(2L, 2, "new");
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.capacity()).isEqualTo(initialCapacity);
    assertThat(map.get(2L, 2)).isEqualTo("new");
  }

  @Test
  public void removeAllEntriesOneByOneLeavesEmptyMap() {
    // Non-sequential removal order stresses backward-sweep across the entire section
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    int count = 8;
    for (int i = 0; i < count; i++) {
      map.put(1L, i, "val-" + i);
    }
    assertThat(map.size()).isEqualTo(count);

    int[] removalOrder = {3, 0, 7, 1, 5, 2, 6, 4};
    for (int i = 0; i < removalOrder.length; i++) {
      map.remove(1L, removalOrder[i]);
      assertThat(map.size()).isEqualTo(count - i - 1);
      // All non-removed entries must still be findable
      for (int j = i + 1; j < removalOrder.length; j++) {
        assertThat(map.get(1L, removalOrder[j]))
            .as(
                "Entry (1, %d) must be findable after removing (1, %d)",
                removalOrder[j], removalOrder[i])
            .isEqualTo("val-" + removalOrder[j]);
      }
    }

    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();

    // Map must accept new inserts after full drain
    map.put(99L, 99, "fresh");
    assertThat(map.get(99L, 99)).isEqualTo("fresh");
    assertThat(map.size()).isEqualTo(1);
  }

  // ---- compute() additional edge cases ----

  @Test
  public void computeRemovalMaintainsProbeChainIntegrity() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    for (int i = 0; i < 6; i++) {
      map.put(1L, i, "val-" + i);
    }

    // Remove entry (1, 2) via compute returning null
    String result = map.compute(1L, 2, (fid, pid, current) -> null);
    assertThat(result).isNull();
    assertThat(map.size()).isEqualTo(5);

    for (int i = 0; i < 6; i++) {
      if (i == 2) {
        assertThat(map.get(1L, i)).isNull();
      } else {
        assertThat(map.get(1L, i)).isEqualTo("val-" + i);
      }
    }
  }

  @Test
  public void computeInsertTriggersResizeCorrectly() {
    var map = new ConcurrentLongIntHashMap<String>(8, 4);
    long initialCapacity = map.capacity();

    for (int i = 0; i < 20; i++) {
      final int idx = i;
      map.compute(
          (long) i,
          i,
          (fid, pid, current) -> {
            assertThat(current).isNull();
            return "computed-" + idx;
          });
    }

    assertThat(map.size()).isEqualTo(20);
    assertThat(map.capacity()).isGreaterThan(initialCapacity);
    for (int i = 0; i < 20; i++) {
      assertThat(map.get((long) i, i)).isEqualTo("computed-" + i);
    }
  }

  @Test
  public void computeLeavesMapConsistentWhenFunctionThrowsOnPresentKey() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "existing");

    assertThatThrownBy(
        () -> map.compute(
            1L,
            10,
            (fid, pid, current) -> {
              throw new RuntimeException("remapping failed");
            }))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("remapping failed");
    assertThat(map.get(1L, 10)).isEqualTo("existing");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void computeLeavesMapConsistentWhenFunctionThrowsOnAbsentKey() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "existing");

    assertThatThrownBy(
        () -> map.compute(
            2L,
            20,
            (fid, pid, current) -> {
              throw new RuntimeException("remapping failed");
            }))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("remapping failed");
    assertThat(map.get(2L, 20)).isNull();
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  public void computeWithZeroKeyFieldsWorksCorrectly() {
    var map = new ConcurrentLongIntHashMap<String>();
    // Insert via compute on the (0, 0) key
    map.compute(
        0L,
        0,
        (fid, pid, current) -> {
          assertThat(fid).isEqualTo(0L);
          assertThat(pid).isEqualTo(0);
          assertThat(current).isNull();
          return "zero-zero";
        });
    assertThat(map.get(0L, 0)).isEqualTo("zero-zero");

    // Replace via compute
    map.compute(0L, 0, (fid, pid, current) -> "updated");
    assertThat(map.get(0L, 0)).isEqualTo("updated");

    // Remove via compute returning null
    map.compute(0L, 0, (fid, pid, current) -> null);
    assertThat(map.get(0L, 0)).isNull();
    assertThat(map.size()).isEqualTo(0);
  }

  // ---- removeByFileId() ----

  @Test
  public void removeByFileIdRemovesOnlyTargetFile() {
    var map = new ConcurrentLongIntHashMap<String>();
    // Insert pages from 3 different files
    for (int page = 0; page < 10; page++) {
      map.put(1L, page, "f1-p" + page);
      map.put(2L, page, "f2-p" + page);
      map.put(3L, page, "f3-p" + page);
    }
    assertThat(map.size()).isEqualTo(30);

    // Remove all pages for file 2
    var removed = map.removeByFileId(2L);
    assertThat(removed).hasSize(10);

    // File 2 pages are gone
    for (int page = 0; page < 10; page++) {
      assertThat(map.get(2L, page)).isNull();
    }
    // File 1 and 3 pages intact
    for (int page = 0; page < 10; page++) {
      assertThat(map.get(1L, page)).isEqualTo("f1-p" + page);
      assertThat(map.get(3L, page)).isEqualTo("f3-p" + page);
    }
    assertThat(map.size()).isEqualTo(20);
  }

  @Test
  public void removeByFileIdReturnsCorrectValues() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(42L, 0, "a");
    map.put(42L, 1, "b");
    map.put(42L, 2, "c");
    map.put(99L, 0, "other");

    var removed = map.removeByFileId(42L);
    assertThat(removed).containsExactlyInAnyOrder("a", "b", "c");
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.get(99L, 0)).isEqualTo("other");
  }

  @Test
  public void removeByFileIdOnEmptyMap() {
    var map = new ConcurrentLongIntHashMap<String>();
    var removed = map.removeByFileId(1L);
    assertThat(removed).isEmpty();
    assertThat(map.size()).isEqualTo(0);
  }

  @Test
  public void removeByFileIdForNonExistentFileId() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 0, "exists");
    var removed = map.removeByFileId(999L);
    assertThat(removed).isEmpty();
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.get(1L, 0)).isEqualTo("exists");
  }

  @Test
  public void removeByFileIdCompactsSection() {
    // After removeByFileId, the section should be compacted so that
    // remaining entries are still findable (no broken probe chains).
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    // Interleave entries from two files
    for (int i = 0; i < 8; i++) {
      map.put(1L, i, "f1-" + i);
      map.put(2L, i, "f2-" + i);
    }
    assertThat(map.size()).isEqualTo(16);

    map.removeByFileId(1L);
    assertThat(map.size()).isEqualTo(8);

    // All file-2 entries must be findable after compaction
    for (int i = 0; i < 8; i++) {
      assertThat(map.get(2L, i))
          .as("Entry (2, %d) must be findable after removeByFileId(1)", i)
          .isEqualTo("f2-" + i);
    }
  }

  @Test
  public void removeByFileIdAllowsReinsertionAfterRemoval() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    for (int i = 0; i < 5; i++) {
      map.put(1L, i, "old-" + i);
    }
    map.removeByFileId(1L);
    assertThat(map.size()).isEqualTo(0);

    // Re-insert with same fileId — must work correctly
    for (int i = 0; i < 5; i++) {
      map.put(1L, i, "new-" + i);
    }
    for (int i = 0; i < 5; i++) {
      assertThat(map.get(1L, i)).isEqualTo("new-" + i);
    }
    assertThat(map.size()).isEqualTo(5);
  }

  @Test
  public void removeByFileIdWithManyEntriesAcrossMultipleSections() {
    // Entries from many files spread across all 16 sections
    var map = new ConcurrentLongIntHashMap<String>();
    int filesCount = 10;
    int pagesPerFile = 50;
    for (long fid = 0; fid < filesCount; fid++) {
      for (int page = 0; page < pagesPerFile; page++) {
        map.put(fid, page, "f" + fid + "-p" + page);
      }
    }
    assertThat(map.size()).isEqualTo(filesCount * pagesPerFile);

    // Remove file 5
    var removed = map.removeByFileId(5L);
    assertThat(removed).hasSize(pagesPerFile);
    assertThat(map.size()).isEqualTo((filesCount - 1) * pagesPerFile);

    // Verify all other files intact
    for (long fid = 0; fid < filesCount; fid++) {
      for (int page = 0; page < pagesPerFile; page++) {
        if (fid == 5) {
          assertThat(map.get(fid, page)).isNull();
        } else {
          assertThat(map.get(fid, page)).isEqualTo("f" + fid + "-p" + page);
        }
      }
    }
  }

  @Test
  public void removeByFileIdWithFileIdZero() {
    // fileId=0 must not confuse empty slots (where fileIds[i] defaults to 0)
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    map.put(0L, 0, "f0-p0");
    map.put(0L, 1, "f0-p1");
    map.put(1L, 0, "f1-p0");

    var removed = map.removeByFileId(0L);
    assertThat(removed).containsExactlyInAnyOrder("f0-p0", "f0-p1");
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.get(0L, 0)).isNull();
    assertThat(map.get(0L, 1)).isNull();
    assertThat(map.get(1L, 0)).isEqualTo("f1-p0");
  }

  @Test
  public void removeByFileIdRemoveAllThenFillToResizeThreshold() {
    // Single section, capacity=16, resizeThreshold = (int)(16 * 0.66) = 10
    var map = new ConcurrentLongIntHashMap<String>(16, 1);

    for (int i = 0; i < 10; i++) {
      map.put(1L, i, "v" + i);
    }
    assertThat(map.size()).isEqualTo(10);

    var removed = map.removeByFileId(1L);
    assertThat(removed).hasSize(10);
    assertThat(map.size()).isEqualTo(0);

    // Re-fill to the same level — proves usedBuckets was correctly reset
    for (int i = 0; i < 10; i++) {
      map.put(2L, i, "new-" + i);
    }
    assertThat(map.size()).isEqualTo(10);
    assertThat(map.capacity()).isEqualTo(16);
    for (int i = 0; i < 10; i++) {
      assertThat(map.get(2L, i)).isEqualTo("new-" + i);
    }
  }

  @Test
  public void removeByFileIdConsecutiveCallsOnDifferentFiles() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    for (int i = 0; i < 5; i++) {
      map.put(1L, i, "f1-" + i);
      map.put(2L, i, "f2-" + i);
      map.put(3L, i, "f3-" + i);
    }
    assertThat(map.size()).isEqualTo(15);

    map.removeByFileId(1L);
    assertThat(map.size()).isEqualTo(10);

    map.removeByFileId(3L);
    assertThat(map.size()).isEqualTo(5);

    for (int i = 0; i < 5; i++) {
      assertThat(map.get(2L, i)).isEqualTo("f2-" + i);
      assertThat(map.get(1L, i)).isNull();
      assertThat(map.get(3L, i)).isNull();
    }
  }

  @Test
  public void removeByFileIdRepeatedPutRemoveCycles() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);

    for (int cycle = 0; cycle < 10; cycle++) {
      long fid = cycle;
      for (int p = 0; p < 8; p++) {
        map.put(fid, p, "c" + cycle + "-p" + p);
      }
      assertThat(map.size()).isEqualTo(8);

      var removed = map.removeByFileId(fid);
      assertThat(removed).hasSize(8);
      assertThat(map.size()).isEqualTo(0);
    }

    // Capacity should not have grown (8 entries in cap-16 section)
    assertThat(map.capacity()).isEqualTo(16);
    map.put(100L, 0, "final");
    assertThat(map.get(100L, 0)).isEqualTo("final");
  }

  @Test
  public void removeByFileIdCalledTwiceForSameFile() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(5L, 0, "a");
    map.put(5L, 1, "b");
    map.put(9L, 0, "other");

    var first = map.removeByFileId(5L);
    assertThat(first).hasSize(2);

    var second = map.removeByFileId(5L);
    assertThat(second).isEmpty();
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.get(9L, 0)).isEqualTo("other");
  }

  // ---- clear() ----

  @Test
  public void clearRemovesAllEntries() {
    var map = new ConcurrentLongIntHashMap<String>();
    for (int i = 0; i < 50; i++) {
      map.put((long) i, i, "val-" + i);
    }
    assertThat(map.size()).isEqualTo(50);

    map.clear();
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
    for (int i = 0; i < 50; i++) {
      assertThat(map.get((long) i, i)).isNull();
    }
  }

  @Test
  public void clearOnEmptyMapIsNoOp() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.clear();
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
  }

  @Test
  public void clearAllowsReinsertion() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    long initialCapacity = map.capacity();

    map.put(1L, 1, "a");
    map.put(2L, 2, "b");
    map.clear();

    map.put(3L, 3, "c");
    assertThat(map.get(3L, 3)).isEqualTo("c");
    assertThat(map.size()).isEqualTo(1);
    // Capacity should not change from clear
    assertThat(map.capacity()).isEqualTo(initialCapacity);
  }

  // ---- forEach() ----

  @Test
  public void forEachVisitsAllEntries() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "a");
    map.put(2L, 20, "b");
    map.put(3L, 30, "c");

    var visited = new java.util.HashMap<String, String>();
    map.forEach((fid, pid, val) -> visited.put(fid + ":" + pid, val));

    assertThat(visited)
        .containsEntry("1:10", "a")
        .containsEntry("2:20", "b")
        .containsEntry("3:30", "c")
        .hasSize(3);
  }

  @Test
  public void forEachOnEmptyMapVisitsNothing() {
    var map = new ConcurrentLongIntHashMap<String>();
    int[] count = {0};
    map.forEach((fid, pid, val) -> count[0]++);
    assertThat(count[0]).isEqualTo(0);
  }

  // ---- forEachValue() ----

  @Test
  public void forEachValueVisitsAllValues() {
    var map = new ConcurrentLongIntHashMap<String>();
    map.put(1L, 10, "a");
    map.put(2L, 20, "b");
    map.put(3L, 30, "c");

    var values = new java.util.ArrayList<String>();
    map.forEachValue(values::add);

    assertThat(values).containsExactlyInAnyOrder("a", "b", "c");
  }

  // ---- shrink() ----

  @Test
  public void shrinkReducesCapacity() {
    var map = new ConcurrentLongIntHashMap<String>(1024, 1);
    assertThat(map.capacity()).isEqualTo(1024);

    // Insert only a few entries
    map.put(1L, 1, "a");
    map.put(2L, 2, "b");
    map.put(3L, 3, "c");

    map.shrink();
    // ceil(3 / 0.66) = 5, alignToPowerOfTwo(5) = 8
    assertThat(map.capacity()).isEqualTo(8);

    // All entries must survive shrink
    assertThat(map.get(1L, 1)).isEqualTo("a");
    assertThat(map.get(2L, 2)).isEqualTo("b");
    assertThat(map.get(3L, 3)).isEqualTo("c");
    assertThat(map.size()).isEqualTo(3);
  }

  @Test
  public void shrinkOnEmptyMapKeepsMinimumCapacity() {
    var map = new ConcurrentLongIntHashMap<String>(1024, 1);
    map.shrink();
    // Minimum capacity is 2 per section
    assertThat(map.capacity()).isEqualTo(2);
    // Map must still be usable
    map.put(1L, 1, "a");
    assertThat(map.get(1L, 1)).isEqualTo("a");
  }

  @Test
  public void shrinkIsNoOpWhenCapacityAlreadyOptimal() {
    // With capacity=2 (minimum) and 1 entry, ceil(1/0.66)=2 → no shrink possible
    var map = new ConcurrentLongIntHashMap<String>(2, 1);
    map.put(1L, 1, "a");
    long capBefore = map.capacity();

    map.shrink();
    assertThat(map.capacity()).isEqualTo(capBefore);
    assertThat(map.get(1L, 1)).isEqualTo("a");
  }

  // ---- Resize with many entries (entries survive) ----

  @Test
  public void resizeWithManyEntriesPreservesAll() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);

    // Insert enough to trigger multiple resizes
    for (int i = 0; i < 1000; i++) {
      map.put((long) i, i, "val-" + i);
    }
    assertThat(map.size()).isEqualTo(1000);

    // Verify all entries survived all resizes
    for (int i = 0; i < 1000; i++) {
      assertThat(map.get((long) i, i))
          .as("Entry (%d, %d) must survive multiple resizes", (long) i, i)
          .isEqualTo("val-" + i);
    }

    // Capacity should be power of two
    long cap = map.capacity();
    assertThat(cap & (cap - 1))
        .as("Capacity must be power of two")
        .isEqualTo(0);
  }

  // ---- Large map with many sections ----

  @Test
  public void largeMapWithManySections() {
    var map = new ConcurrentLongIntHashMap<String>(1024, 16);
    for (int i = 0; i < 1000; i++) {
      map.put((long) i, i, "val-" + i);
    }
    assertThat(map.size()).isEqualTo(1000);

    for (int i = 0; i < 1000; i++) {
      assertThat(map.get((long) i, i)).isEqualTo("val-" + i);
    }

    // Remove half
    for (int i = 0; i < 500; i++) {
      map.remove((long) i, i);
    }
    assertThat(map.size()).isEqualTo(500);

    // Remaining entries intact
    for (int i = 500; i < 1000; i++) {
      assertThat(map.get((long) i, i)).isEqualTo("val-" + i);
    }
  }

  // ---- Additional edge cases from Step 5 review ----

  @Test
  public void shrinkAfterManyInsertsAndRemovesPreservesRemainingEntries() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);

    for (int i = 0; i < 500; i++) {
      map.put((long) i, i, "val-" + i);
    }
    long capacityBeforeRemoval = map.capacity();

    // Remove most entries, leaving only 10
    for (int i = 10; i < 500; i++) {
      map.remove((long) i, i);
    }
    assertThat(map.size()).isEqualTo(10);
    assertThat(map.capacity()).isEqualTo(capacityBeforeRemoval);

    map.shrink();
    // ceil(10 / 0.66) = 16, alignToPowerOfTwo(16) = 16
    assertThat(map.capacity()).isEqualTo(16);

    for (int i = 0; i < 10; i++) {
      assertThat(map.get((long) i, i))
          .as("Entry (%d, %d) must survive shrink", (long) i, i)
          .isEqualTo("val-" + i);
    }
    assertThat(map.size()).isEqualTo(10);

    // Map still functional after shrink
    map.put(999L, 999, "new");
    assertThat(map.get(999L, 999)).isEqualTo("new");
  }

  @Test
  public void clearFollowedByShrinkReducesToMinimumCapacity() {
    var map = new ConcurrentLongIntHashMap<String>(1024, 1);
    for (int i = 0; i < 100; i++) {
      map.put((long) i, i, "val-" + i);
    }

    map.clear();
    map.shrink();
    assertThat(map.capacity()).isEqualTo(2);

    map.put(1L, 1, "a");
    assertThat(map.get(1L, 1)).isEqualTo("a");
  }

  @Test
  public void forEachVisitsCorrectEntriesAfterRemovals() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    for (int i = 0; i < 20; i++) {
      map.put((long) i, i, "val-" + i);
    }
    for (int i = 0; i < 20; i += 2) {
      map.remove((long) i, i);
    }

    var visited = new java.util.HashMap<String, String>();
    map.forEach((fid, pid, val) -> visited.put(fid + ":" + pid, val));

    assertThat(visited).hasSize(10);
    for (int i = 1; i < 20; i += 2) {
      assertThat(visited).containsEntry(i + ":" + i, "val-" + i);
    }
  }

  @Test
  public void shrinkThenInsertManyTriggersCorrectResize() {
    var map = new ConcurrentLongIntHashMap<String>(1024, 1);
    map.put(1L, 1, "a");
    map.shrink();
    long shrunkCapacity = map.capacity();

    for (int i = 0; i < 200; i++) {
      map.put((long) i, i, "val-" + i);
    }
    assertThat(map.size()).isEqualTo(200);
    assertThat(map.capacity()).isGreaterThan(shrunkCapacity);

    for (int i = 0; i < 200; i++) {
      assertThat(map.get((long) i, i)).isEqualTo("val-" + i);
    }
  }

  // ---- Backward-sweep compaction wraparound tests (TC1) ----

  @Test
  public void removeAtWraparoundBoundaryPreservesProbeChain() {
    // Single section, capacity=8 so entries can wrap around the array boundary.
    // Insert 5 entries — with linear probing some will probe-chain across the 7→0 boundary.
    var map = new ConcurrentLongIntHashMap<String>(8, 1);
    assertThat(map.capacity()).isEqualTo(8);

    for (int i = 0; i < 5; i++) {
      map.put(1L, i, "val-" + i);
    }

    // Remove entries one by one in forward order, verifying all remaining entries
    // are still findable after each removal. This exercises backward-sweep compaction
    // including the wraparound case when entries span the array boundary.
    for (int removed = 0; removed < 5; removed++) {
      map.remove(1L, removed);
      for (int remaining = removed + 1; remaining < 5; remaining++) {
        assertThat(map.get(1L, remaining))
            .as("Entry (1, %d) must be findable after removing (1, %d)", remaining, removed)
            .isEqualTo("val-" + remaining);
      }
    }
    assertThat(map.size()).isEqualTo(0);
  }

  @Test
  public void removeAtWraparoundWithReverseRemovalOrder() {
    // Same setup but remove in reverse order — stresses different compaction patterns
    var map = new ConcurrentLongIntHashMap<String>(8, 1);
    for (int i = 0; i < 5; i++) {
      map.put(1L, i, "val-" + i);
    }

    for (int removed = 4; removed >= 0; removed--) {
      map.remove(1L, removed);
      for (int remaining = 0; remaining < removed; remaining++) {
        assertThat(map.get(1L, remaining))
            .as("Entry (1, %d) must be findable after removing (1, %d)", remaining, removed)
            .isEqualTo("val-" + remaining);
      }
    }
    assertThat(map.size()).isEqualTo(0);
  }

  @Test
  public void removeAtWraparoundWithInterleavedFiles() {
    // Two files interleaved in a small single-section map — removal of one file's entries
    // must not break probe chains for the other file's entries that may span the boundary.
    var map = new ConcurrentLongIntHashMap<String>(8, 1);

    for (int i = 0; i < 5; i++) {
      map.put(1L, i, "f1-" + i);
      if (i < 3) {
        map.put(2L, i, "f2-" + i);
      }
    }
    // 5 + 3 = 8 entries would trigger resize, but 5 entries leaves room

    // Remove all file-1 entries one by one
    for (int i = 0; i < 5; i++) {
      map.remove(1L, i);
    }

    // All file-2 entries must survive
    for (int i = 0; i < 3; i++) {
      assertThat(map.get(2L, i))
          .as("File-2 entry (2, %d) must survive file-1 removals", i)
          .isEqualTo("f2-" + i);
    }
    assertThat(map.size()).isEqualTo(3);
  }

  // ---- removeByFileId boundary tests (TC2) ----

  @Test
  public void removeByFileIdWithHighLoadFactorPreservesAllSurvivors() {
    // Single section, fill close to the resize threshold to maximize probe chain collisions.
    // capacity=32, threshold=(int)(32*0.66)=21
    var map = new ConcurrentLongIntHashMap<String>(32, 1);
    assertThat(map.capacity()).isEqualTo(32);

    // Insert 10 entries for fileId=1 and 10 for fileId=2 = 20 entries (just under threshold)
    for (int p = 0; p < 10; p++) {
      map.put(1L, p, "f1-" + p);
      map.put(2L, p, "f2-" + p);
    }
    assertThat(map.size()).isEqualTo(20);
    assertThat(map.capacity()).isEqualTo(32);

    var removed = map.removeByFileId(1L);
    assertThat(removed).hasSize(10);
    assertThat(map.size()).isEqualTo(10);

    // Every file-2 entry must survive the same-capacity rehash
    for (int p = 0; p < 10; p++) {
      assertThat(map.get(2L, p))
          .as("Entry (2, %d) must survive removeByFileId rehash", p)
          .isEqualTo("f2-" + p);
    }
  }

  // ---- putIfAbsent resize test (TC3) ----

  @Test
  public void putIfAbsentTriggersResizeCorrectly() {
    var map = new ConcurrentLongIntHashMap<String>(8, 4);
    long initialCapacity = map.capacity();

    for (int i = 0; i < 20; i++) {
      map.putIfAbsent((long) i, i, "val-" + i);
    }

    assertThat(map.size()).isEqualTo(20);
    assertThat(map.capacity()).isGreaterThan(initialCapacity);
    for (int i = 0; i < 20; i++) {
      assertThat(map.get((long) i, i)).isEqualTo("val-" + i);
    }
  }

  // ---- compute remove then reinsert (TC4) ----

  @Test
  public void computeRemoveThenReinsertSameKey() {
    // Verifies that backward-sweep compaction after compute-removal leaves the table
    // in a state where the same key can be correctly re-inserted via compute.
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    for (int i = 0; i < 6; i++) {
      map.put(1L, i, "val-" + i);
    }

    // Remove via compute returning null
    map.compute(1L, 3, (fid, pid, cur) -> null);
    assertThat(map.get(1L, 3)).isNull();
    assertThat(map.size()).isEqualTo(5);

    // Re-insert same key via compute
    map.compute(
        1L,
        3,
        (fid, pid, cur) -> {
          assertThat(cur).as("Key was removed, current should be null").isNull();
          return "reinserted";
        });
    assertThat(map.get(1L, 3)).isEqualTo("reinserted");
    assertThat(map.size()).isEqualTo(6);

    // All other entries must be intact
    for (int i = 0; i < 6; i++) {
      if (i == 3) {
        assertThat(map.get(1L, i)).isEqualTo("reinserted");
      } else {
        assertThat(map.get(1L, i)).isEqualTo("val-" + i);
      }
    }
  }

  // ---- Extreme pageIndex values with same fileId (TC5) ----

  @Test
  public void putDistinguishesExtremePageIndexValues() {
    // Verifies that the hash function correctly separates extreme pageIndex values
    // when the fileId is identical.
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    map.put(1L, Integer.MAX_VALUE, "max");
    map.put(1L, Integer.MIN_VALUE, "min");
    map.put(1L, 0, "zero");
    map.put(1L, -1, "neg-one");
    map.put(1L, 1, "one");

    assertThat(map.get(1L, Integer.MAX_VALUE)).isEqualTo("max");
    assertThat(map.get(1L, Integer.MIN_VALUE)).isEqualTo("min");
    assertThat(map.get(1L, 0)).isEqualTo("zero");
    assertThat(map.get(1L, -1)).isEqualTo("neg-one");
    assertThat(map.get(1L, 1)).isEqualTo("one");
    assertThat(map.size()).isEqualTo(5);
  }

  // ---- removeByFileId with fileId=0 at high occupancy (TC6) ----

  @Test
  public void removeByFileIdZeroWithHighOccupancyOnlyRemovesActualEntries() {
    // At high occupancy, many empty slots have fileIds[i] == 0L (Java default).
    // The val != null guard in removeByFileId is essential — verify it works at scale.
    var map = new ConcurrentLongIntHashMap<String>(64, 1);
    for (int p = 0; p < 20; p++) {
      map.put(0L, p, "f0-" + p);
      map.put(1L, p, "f1-" + p);
    }
    assertThat(map.size()).isEqualTo(40);

    var removed = map.removeByFileId(0L);
    assertThat(removed).hasSize(20);
    assertThat(map.size()).isEqualTo(20);

    // All fileId=1 entries must survive
    for (int p = 0; p < 20; p++) {
      assertThat(map.get(1L, p)).isEqualTo("f1-" + p);
    }
    // All fileId=0 entries must be gone
    for (int p = 0; p < 20; p++) {
      assertThat(map.get(0L, p)).isNull();
    }
  }

  // ---- forEachValue on empty map (TC7) ----

  @Test
  public void forEachValueOnEmptyMapVisitsNothing() {
    var map = new ConcurrentLongIntHashMap<String>();
    int[] count = {0};
    map.forEachValue(val -> count[0]++);
    assertThat(count[0]).isEqualTo(0);
  }

  // ---- hashForFrequencySketch ----

  /** Verify hashForFrequencySketch is consistent (same inputs → same output). */
  @Test
  public void hashForFrequencySketchIsConsistent() {
    int h1 = ConcurrentLongIntHashMap.hashForFrequencySketch(42L, 7);
    int h2 = ConcurrentLongIntHashMap.hashForFrequencySketch(42L, 7);
    assertThat(h1).isEqualTo(h2);
  }

  /** Verify hashForFrequencySketch differentiates keys that differ only in pageIndex. */
  @Test
  public void hashForFrequencySketchDiffersForDifferentPageIndex() {
    int h1 = ConcurrentLongIntHashMap.hashForFrequencySketch(1L, 0);
    int h2 = ConcurrentLongIntHashMap.hashForFrequencySketch(1L, 1);
    assertThat(h1).isNotEqualTo(h2);
  }

  /** Verify hashForFrequencySketch differentiates keys that differ only in fileId. */
  @Test
  public void hashForFrequencySketchDiffersForDifferentFileId() {
    int h1 = ConcurrentLongIntHashMap.hashForFrequencySketch(0L, 5);
    int h2 = ConcurrentLongIntHashMap.hashForFrequencySketch(1L, 5);
    assertThat(h1).isNotEqualTo(h2);
  }

  /** Verify hashForFrequencySketch computes a known value for zero keys. */
  @Test
  public void hashForFrequencySketchWithZeroKeys() {
    // Long.hashCode(0) == 0, so result is 0 * 31 + 0 == 0
    int h = ConcurrentLongIntHashMap.hashForFrequencySketch(0L, 0);
    assertThat(h).isEqualTo(0);
  }

  /**
   * Verify hashForFrequencySketch returns pre-computed known values — guards against
   * accidental formula changes (a tautological re-derivation would not catch that).
   */
  @Test
  public void hashForFrequencySketchMatchesPrecomputedValues() {
    // Long.hashCode(1L) = 1, so 1 * 31 + 0 = 31
    assertThat(ConcurrentLongIntHashMap.hashForFrequencySketch(1L, 0)).isEqualTo(31);
    // Long.hashCode(1L) = 1, so 1 * 31 + 5 = 36
    assertThat(ConcurrentLongIntHashMap.hashForFrequencySketch(1L, 5)).isEqualTo(36);
    // Long.hashCode(123456789L) = 123456789, so 123456789 * 31 + 42 = -467806795
    assertThat(ConcurrentLongIntHashMap.hashForFrequencySketch(123456789L, 42))
        .isEqualTo(-467806795);
    // Long.hashCode(Long.MAX_VALUE) = (int)(MAX ^ (MAX >>> 32)) = -2147483648 (Integer.MIN_VALUE)
    // Integer.MIN_VALUE * 31 overflows and wraps to Integer.MIN_VALUE in int arithmetic
    assertThat(ConcurrentLongIntHashMap.hashForFrequencySketch(Long.MAX_VALUE, 0))
        .isEqualTo(-2147483648);
  }

  // ---- drainAll ----

  /** drainAll on an empty map must not invoke the consumer and must leave size at zero. */
  @Test
  public void drainAllOnEmptyMapInvokesConsumerZeroTimes() {
    var map = new ConcurrentLongIntHashMap<String>();
    var collected = new ArrayList<String>();
    map.drainAll(collected::add);
    assertThat(collected).isEmpty();
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
  }

  /** drainAll on a single-section map delivers every value exactly once, then empties the map. */
  @Test
  public void drainAllOnSingleSectionDeliversEveryValueExactlyOnce() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    for (int i = 0; i < 10; i++) {
      map.put(1L, i, "v" + i);
    }
    assertThat(map.size()).isEqualTo(10);

    var collected = new ArrayList<String>();
    map.drainAll(collected::add);

    assertThat(collected).hasSize(10);
    var expected = new ArrayList<String>();
    for (int i = 0; i < 10; i++) {
      expected.add("v" + i);
    }
    assertThat(collected).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.isEmpty()).isTrue();
  }

  /**
   * drainAll across many sections collects entries from every section — verifies the iteration
   * covers all sections, not just the first.
   */
  @Test
  public void drainAllAcrossAllSectionsCollectsEveryEntry() {
    var map = new ConcurrentLongIntHashMap<String>();
    int filesCount = 20;
    int pagesPerFile = 30;
    var expected = new ArrayList<String>();
    for (long fid = 0; fid < filesCount; fid++) {
      for (int page = 0; page < pagesPerFile; page++) {
        var v = "f" + fid + "-p" + page;
        map.put(fid, page, v);
        expected.add(v);
      }
    }
    assertThat(map.size()).isEqualTo(filesCount * pagesPerFile);

    var collected = new ArrayList<String>();
    map.drainAll(collected::add);

    assertThat(collected).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(map.size()).isEqualTo(0);
  }

  /**
   * drainAll must return the map to a state where get returns null for every previously present
   * key. Guards against partial drains that leave stale entries.
   */
  @Test
  public void drainAllClearsEveryEntryFromGet() {
    var map = new ConcurrentLongIntHashMap<String>();
    for (long fid = 0; fid < 5; fid++) {
      for (int page = 0; page < 10; page++) {
        map.put(fid, page, "v-" + fid + "-" + page);
      }
    }

    map.drainAll(v -> {
    });

    for (long fid = 0; fid < 5; fid++) {
      for (int page = 0; page < 10; page++) {
        assertThat(map.get(fid, page))
            .as("Entry (%d, %d) must be null after drainAll", fid, page)
            .isNull();
      }
    }
  }

  /**
   * drainAll shrinks each section back to the capacity it was constructed with — releases the
   * large Entry[] accumulated during growth while keeping enough headroom that a refill does
   * not pay a rehash chain from the 2-slot absolute minimum.
   *
   * <p>Default constructor: expectedItems=256, sectionCount=16 → 16 slots per section × 16
   * sections = 256 total capacity at construction time. After growth and drainAll, the map
   * should shrink back to exactly that.
   */
  @Test
  public void drainAllShrinksCapacityToInitialCapacity() {
    var map = new ConcurrentLongIntHashMap<String>();
    long initialCapacity = map.capacity();
    assertThat(initialCapacity)
        .as("constructor default: 256 expectedItems / 16 sections = 16 per section * 16 = 256")
        .isEqualTo(256L);

    // Grow the map far past its initial capacity.
    for (long fid = 0; fid < 100; fid++) {
      for (int page = 0; page < 100; page++) {
        map.put(fid, page, "v");
      }
    }
    assertThat(map.capacity()).isGreaterThan(initialCapacity);

    map.drainAll(v -> {
    });

    assertThat(map.capacity())
        .as("drainAll must reset capacity back to the constructor-time value")
        .isEqualTo(initialCapacity);
    assertThat(map.size()).isEqualTo(0);
  }

  /** After drainAll the map must be usable — puts and gets work as expected on a fresh table. */
  @Test
  public void drainAllLeavesMapUsableForSubsequentPutAndGet() {
    var map = new ConcurrentLongIntHashMap<String>();
    for (int i = 0; i < 50; i++) {
      map.put(1L, i, "old-" + i);
    }
    map.drainAll(v -> {
    });

    for (int i = 0; i < 50; i++) {
      map.put(1L, i, "new-" + i);
    }
    for (int i = 0; i < 50; i++) {
      assertThat(map.get(1L, i)).isEqualTo("new-" + i);
    }
    assertThat(map.size()).isEqualTo(50);
  }

  /**
   * An exception thrown by the consumer propagates out of drainAll. Because the consumer runs
   * after each section is drained (and the lock released), the map is already fully drained by
   * the time the exception surfaces — matching the "best effort, fail fast" semantics of the
   * close path.
   */
  @Test
  public void drainAllPropagatesConsumerException() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    map.put(1L, 0, "first");
    map.put(1L, 1, "second");

    assertThatThrownBy(
        () -> map.drainAll(
            v -> {
              throw new IllegalStateException("boom on " + v);
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom on");

    // The map has been fully drained even though the consumer threw — entries were removed
    // under the write lock before the consumer ran.
    assertThat(map.size()).isEqualTo(0);
  }

  /**
   * When the consumer throws mid-iteration, later sections must NOT have been drained. The
   * throw aborts the outer section loop, so sections with an index greater than the failing
   * section still hold their entries.
   *
   * <p>Uses two sections and picks one key for each by consulting the package-private
   * {@link ConcurrentLongIntHashMap#hash} helper, so section assignment is deterministic.
   */
  @Test
  public void drainAllAbortsOnConsumerExceptionLeavingLaterSectionsPopulated() {
    var map = new ConcurrentLongIntHashMap<String>(4, 2);

    // Find two fileIds that fall into different sections — sectionMask = 1, so bit 32 of the
    // mixed hash picks the section.
    long keyInSection0 = -1L;
    long keyInSection1 = -1L;
    for (long fid = 1L; keyInSection0 == -1L || keyInSection1 == -1L; fid++) {
      int sectionIdx = (int) (ConcurrentLongIntHashMap.hash(fid, 0) >>> 32) & 1;
      if (sectionIdx == 0 && keyInSection0 == -1L) {
        keyInSection0 = fid;
      } else if (sectionIdx == 1 && keyInSection1 == -1L) {
        keyInSection1 = fid;
      }
    }
    map.put(keyInSection0, 0, "A");
    map.put(keyInSection1, 0, "B");
    assertThat(map.size()).isEqualTo(2);

    assertThatThrownBy(
        () -> map.drainAll(
            v -> {
              throw new IllegalStateException("boom on " + v);
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom on A");

    // drainAll iterates sections in index order. The first consumer call (for the entry in
    // section 0) threw, so section 1 was never drained — its entry survives.
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.get(keyInSection1, 0)).isEqualTo("B");
    assertThat(map.get(keyInSection0, 0)).isNull();
  }

  /**
   * drainAll invokes the consumer outside the section write lock, so a consumer that re-enters
   * the map (e.g., to insert a new entry) must succeed without deadlock. This mirrors the close
   * path where {@code freeze()} / policy callbacks may touch other maps.
   */
  @Test
  public void drainAllConsumerCanReenterMap() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    map.put(1L, 0, "original");
    map.put(1L, 1, "original");

    var collected = new ArrayList<String>();
    map.drainAll(
        v -> {
          collected.add(v);
          // Re-entering the map inside the consumer: must not deadlock on the section lock.
          if (v.equals("original") && map.get(2L, 0) == null) {
            map.put(2L, 0, "reentrant");
          }
        });

    assertThat(collected).hasSize(2);
    // The reentrant put landed after drainAll returned from that section's write lock.
    assertThat(map.get(2L, 0)).isEqualTo("reentrant");
  }

  /**
   * drainAll resets both size and usedBuckets to zero — a subsequent fill to the original
   * resize threshold triggers a normal rehash without corruption.
   */
  @Test
  public void drainAllResetsInternalCountersSoRefillWorks() {
    var map = new ConcurrentLongIntHashMap<String>(16, 1);
    // Fill single section to its resize threshold (capacity 16, threshold = 10)
    for (int i = 0; i < 10; i++) {
      map.put(1L, i, "a-" + i);
    }
    map.drainAll(v -> {
    });

    // Fill again past the (shrunken) capacity — this exercises rehashes on a fresh table.
    for (int i = 0; i < 100; i++) {
      map.put(1L, i, "b-" + i);
    }
    assertThat(map.size()).isEqualTo(100);
    for (int i = 0; i < 100; i++) {
      assertThat(map.get(1L, i)).isEqualTo("b-" + i);
    }
  }

  /**
   * drainAll on a map whose sections have mixed populations (some empty, some nearly full) must
   * visit only live entries — never an empty slot.
   */
  @Test
  public void drainAllSkipsEmptySlots() {
    var map = new ConcurrentLongIntHashMap<String>(64, 1);
    // Insert only 3 entries into a 64-slot section so most slots remain empty.
    map.put(1L, 0, "a");
    map.put(1L, 5, "b");
    map.put(1L, 10, "c");

    List<String> collected = new ArrayList<>();
    map.drainAll(collected::add);

    assertThat(collected).containsExactlyInAnyOrder("a", "b", "c");
    assertThat(map.size()).isEqualTo(0);
  }
}
