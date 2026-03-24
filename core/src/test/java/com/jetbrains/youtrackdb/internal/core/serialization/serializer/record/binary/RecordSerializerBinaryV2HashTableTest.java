package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Tests for the hash table core utilities in RecordSerializerBinaryV2: Fibonacci hashing, capacity
 * computation, perfect hash seed search, hash8 computation, sentinel constants, and bucketized
 * cuckoo hash table construction.
 */
public class RecordSerializerBinaryV2HashTableTest {

  // --- Sentinel constants ---

  @Test
  public void emptySlotSentinelValues() {
    // Empty slot sentinel must be 0xFF / 0xFFFF (not 0x00 / 0x0000) per review decision T3/R2/A2
    assertThat(RecordSerializerBinaryV2.EMPTY_HASH8).isEqualTo((byte) 0xFF);
    assertThat(RecordSerializerBinaryV2.EMPTY_OFFSET).isEqualTo((short) 0xFFFF);
  }

  @Test
  public void slotSizeIsThreeBytes() {
    assertThat(RecordSerializerBinaryV2.SLOT_SIZE).isEqualTo(3);
  }

  // --- fibonacciIndex ---

  @Test
  public void fibonacciIndex_producesValidIndicesForAllCapacities() {
    // For each valid log2Capacity, all indices must be in [0, capacity)
    for (int log2 = RecordSerializerBinaryV2.MIN_LOG2_CAPACITY;
        log2 <= RecordSerializerBinaryV2.MAX_LOG2_CAPACITY; log2++) {
      int capacity = 1 << log2;
      for (int hash = -1000; hash <= 1000; hash++) {
        int index = RecordSerializerBinaryV2.fibonacciIndex(hash, log2);
        assertThat(index)
            .as("log2=%d, hash=%d", log2, hash)
            .isGreaterThanOrEqualTo(0)
            .isLessThan(capacity);
      }
    }
  }

  @Test
  public void fibonacciIndex_differentHashesProduceDifferentIndices() {
    // With enough capacity, distinct hashes should generally map to different slots
    int log2 = 6; // 64 slots
    Set<Integer> indices = new HashSet<>();
    // Use 10 diverse hash values
    int[] hashes = {0, 1, -1, 42, 1000, -1000, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x12345678,
        0xDEADBEEF};
    for (int hash : hashes) {
      indices.add(RecordSerializerBinaryV2.fibonacciIndex(hash, log2));
    }
    // At least 8 out of 10 should be distinct in a 64-slot table
    assertThat(indices.size()).isGreaterThanOrEqualTo(8);
  }

  @Test
  public void fibonacciIndex_zeroHashMapsToZero() {
    // Fibonacci(0) should always be 0 regardless of capacity
    for (int log2 = RecordSerializerBinaryV2.MIN_LOG2_CAPACITY;
        log2 <= RecordSerializerBinaryV2.MAX_LOG2_CAPACITY; log2++) {
      assertThat(RecordSerializerBinaryV2.fibonacciIndex(0, log2)).isEqualTo(0);
    }
  }

  @Test
  public void fibonacciIndex_resultIsPowerOfTwoBounded() {
    // Result must always be < capacity (a power of two)
    int hash = 0x7FFFFFFF;
    for (int log2 = 2; log2 <= 10; log2++) {
      int result = RecordSerializerBinaryV2.fibonacciIndex(hash, log2);
      assertThat(result).isLessThan(1 << log2);
    }
  }

  // --- computeLog2Capacity ---

  @Test
  public void computeLog2Capacity_singleProperty() {
    // 1 property → 2*1=2, next power of two=2, but min is 4 (log2=2)
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(1))
        .isEqualTo(RecordSerializerBinaryV2.MIN_LOG2_CAPACITY);
  }

  @Test
  public void computeLog2Capacity_twoProperties() {
    // 2 properties → 2*2=4, log2(4)=2
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(2))
        .isEqualTo(2);
  }

  @Test
  public void computeLog2Capacity_threeProperties() {
    // 3 properties → 2*3=6, next power of two=8, log2(8)=3
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(3))
        .isEqualTo(3);
  }

  @Test
  public void computeLog2Capacity_fiveProperties() {
    // 5 properties → 2*5=10, next power of two=16, log2=4
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(5))
        .isEqualTo(4);
  }

  @Test
  public void computeLog2Capacity_tenProperties() {
    // 10 properties → 2*10=20, next power of two=32, log2=5
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(10))
        .isEqualTo(5);
  }

  @Test
  public void computeLog2Capacity_twentyProperties() {
    // 20 properties → 2*20=40, next power of two=64, log2=6
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(20))
        .isEqualTo(6);
  }

  @Test
  public void computeLog2Capacity_fortyProperties() {
    // 40 properties → 2*40=80, next power of two=128, log2=7
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(40))
        .isEqualTo(7);
  }

  @Test
  public void computeLog2Capacity_fortyOneProperties_switchesToFourX() {
    // 41 properties → 4*41=164, next power of two=256, log2=8
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(41))
        .isEqualTo(8);
  }

  @Test
  public void computeLog2Capacity_fiftyProperties() {
    // 50 properties → 4*50=200, next power of two=256, log2=8
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(50))
        .isEqualTo(8);
  }

  @Test
  public void computeLog2Capacity_clampedToMax() {
    // Very large count should be clamped to MAX_LOG2_CAPACITY=10
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(500))
        .isEqualTo(RecordSerializerBinaryV2.MAX_LOG2_CAPACITY);
  }

  @Test
  public void computeLog2Capacity_resultIsAlwaysAtLeastMinimum() {
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(1))
        .isGreaterThanOrEqualTo(RecordSerializerBinaryV2.MIN_LOG2_CAPACITY);
  }

  // --- findPerfectHashSeed ---

  @Test
  public void findPerfectHashSeed_singleProperty() {
    byte[][] names = {toBytes("name")};
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(1);
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    assertThat(result).hasSize(2);
    assertThat(result[1]).isGreaterThanOrEqualTo(log2);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_fiveProperties() {
    byte[][] names = {toBytes("firstName"), toBytes("lastName"), toBytes("email"),
        toBytes("age"), toBytes("active")};
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(5);
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_twentyProperties() {
    byte[][] names = new byte[20][];
    for (int i = 0; i < 20; i++) {
      names[i] = toBytes("property_" + i);
    }
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(20);
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_fiftyProperties() {
    byte[][] names = new byte[50][];
    for (int i = 0; i < 50; i++) {
      names[i] = toBytes("prop" + i);
    }
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(50);
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_hundredProperties() {
    byte[][] names = new byte[100][];
    for (int i = 0; i < 100; i++) {
      names[i] = toBytes("field" + i);
    }
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(100);
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_typicalEntityProperties() {
    // Realistic entity with mixed property names
    byte[][] names = {
        toBytes("@class"), toBytes("name"), toBytes("age"), toBytes("email"),
        toBytes("address"), toBytes("phone"), toBytes("createdAt"), toBytes("updatedAt"),
        toBytes("isActive"), toBytes("role")
    };
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(10);
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_similarPrefixNames() {
    // Names that differ only in suffix — stress test for hash quality
    byte[][] names = new byte[10][];
    for (int i = 0; i < 10; i++) {
      names[i] = toBytes("field" + (char) ('a' + i));
    }
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(10);
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_mayIncreaseCapacity() {
    // Use an artificially small capacity to force capacity increase
    byte[][] names = new byte[10][];
    for (int i = 0; i < 10; i++) {
      names[i] = toBytes("p" + i);
    }
    // Start with min capacity (4 slots for 10 properties = guaranteed need to grow)
    int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names,
        RecordSerializerBinaryV2.MIN_LOG2_CAPACITY);
    // Capacity must have grown to fit 10 properties
    assertThat(result[1]).isGreaterThan(RecordSerializerBinaryV2.MIN_LOG2_CAPACITY);
    assertNoPerfectHashCollision(names, result[0], result[1]);
  }

  @Test
  public void findPerfectHashSeed_seedIsConsistentAcrossCalls() {
    byte[][] names = {toBytes("a"), toBytes("b"), toBytes("c")};
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(3);
    int[] result1 = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    int[] result2 = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
    // Deterministic: same input → same seed
    assertThat(result1).isEqualTo(result2);
  }

  // --- computeHash8 ---

  @Test
  public void computeHash8_extractsHighByte() {
    // 0xAB______ → hash8 = 0xAB
    assertThat(RecordSerializerBinaryV2.computeHash8(0xAB000000)).isEqualTo((byte) 0xAB);
  }

  @Test
  public void computeHash8_lowBitsIgnored() {
    assertThat(RecordSerializerBinaryV2.computeHash8(0xAB123456)).isEqualTo((byte) 0xAB);
  }

  @Test
  public void computeHash8_ffHashIsNotAdjusted() {
    // hash8 = 0xFF is safe because empty sentinel uses offset 0xFFFF (reserved, never assigned)
    assertThat(RecordSerializerBinaryV2.computeHash8(0xFF000000)).isEqualTo((byte) 0xFF);
  }

  @Test
  public void computeHash8_zeroHash() {
    assertThat(RecordSerializerBinaryV2.computeHash8(0x00000000)).isEqualTo((byte) 0x00);
  }

  // --- Stress: seed search for various property counts ---

  @Test
  public void findPerfectHashSeed_allCountsFromOneToFifty() {
    // Verify seed search succeeds for all realistic property counts
    for (int n = 1; n <= 50; n++) {
      byte[][] names = new byte[n][];
      for (int i = 0; i < n; i++) {
        names[i] = toBytes("prop_" + i);
      }
      int log2 = RecordSerializerBinaryV2.computeLog2Capacity(n);
      int[] result = RecordSerializerBinaryV2.findPerfectHashSeed(names, log2);
      assertNoPerfectHashCollision(names, result[0], result[1]);
    }
  }

  // ========================================================================================
  // Bucketized cuckoo hashing tests (Track 7)
  // ========================================================================================

  // --- computeH2Seed ---

  @Test
  public void computeH2Seed_xorsWithCuckooConstant() {
    // h2 seed is derived by XOR with the MurmurHash3 finalization constant
    assertThat(RecordSerializerBinaryV2.computeH2Seed(0))
        .isEqualTo(RecordSerializerBinaryV2.CUCKOO_XOR_CONSTANT);
    assertThat(RecordSerializerBinaryV2.computeH2Seed(42))
        .isEqualTo(42 ^ RecordSerializerBinaryV2.CUCKOO_XOR_CONSTANT);
  }

  @Test
  public void computeH2Seed_neverEqualToH1Seed() {
    // XOR with non-zero constant ensures h1 seed != h2 seed
    for (int seed = 0; seed < 1000; seed++) {
      assertThat(RecordSerializerBinaryV2.computeH2Seed(seed))
          .as("seed=%d", seed)
          .isNotEqualTo(seed);
    }
  }

  // --- computeLog2NumBuckets ---

  @Test
  public void computeLog2NumBuckets_thirteenProperties() {
    // 13 / 3.4 = 3.82 → ceil = 4 → nextPow2 = 4 → log2 = 2
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(13)).isEqualTo(2);
  }

  @Test
  public void computeLog2NumBuckets_twentyProperties() {
    // 20 / 3.4 = 5.88 → ceil = 6 → nextPow2 = 8 → log2 = 3
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(20)).isEqualTo(3);
  }

  @Test
  public void computeLog2NumBuckets_thirtyProperties() {
    // 30 / 3.4 = 8.82 → ceil = 9 → nextPow2 = 16 → log2 = 4
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(30)).isEqualTo(4);
  }

  @Test
  public void computeLog2NumBuckets_fiftyProperties() {
    // 50 / 3.4 = 14.71 → ceil = 15 → nextPow2 = 16 → log2 = 4
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(50)).isEqualTo(4);
  }

  @Test
  public void computeLog2NumBuckets_hundredProperties() {
    // 100 / 3.4 = 29.41 → ceil = 30 → nextPow2 = 32 → log2 = 5
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(100)).isEqualTo(5);
  }

  @Test
  public void computeLog2NumBuckets_singleProperty() {
    // 1 / 3.4 = 0.29 → ceil = 1 → log2 = 0 (single bucket)
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(1)).isEqualTo(0);
  }

  @Test
  public void computeLog2NumBuckets_threeProperties() {
    // 3 / 3.4 = 0.88 → ceil = 1 → log2 = 0 (single bucket with 4 slots)
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(3)).isEqualTo(0);
  }

  @Test
  public void computeLog2NumBuckets_twoProperties() {
    // 2 / 3.4 = 0.59 → ceil = 1 → log2 = 0 (still single bucket)
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(2)).isEqualTo(0);
  }

  @Test
  public void computeLog2NumBuckets_fourProperties_transitionToTwoBuckets() {
    // 4 / 3.4 = 1.18 → ceil = 2 → log2 = 1 (first count requiring 2 buckets)
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(4)).isEqualTo(1);
  }

  @Test
  public void computeLog2NumBuckets_sevenProperties_transitionToFourBuckets() {
    // 7 / 3.4 = 2.06 → ceil = 3 → nextPow2 = 4 → log2 = 2
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(7)).isEqualTo(2);
  }

  @Test
  public void computeLog2NumBuckets_fourteenProperties_transitionToEightBuckets() {
    // 14 / 3.4 = 4.12 → ceil = 5 → nextPow2 = 8 → log2 = 3
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(14)).isEqualTo(3);
  }

  @Test
  public void computeLog2NumBuckets_clampedToMax() {
    // Very large property count should be clamped to MAX_LOG2_CAPACITY
    assertThat(RecordSerializerBinaryV2.computeLog2NumBuckets(5000))
        .isEqualTo(RecordSerializerBinaryV2.MAX_LOG2_CAPACITY);
  }

  // --- fibonacciBucketIndex ---

  @Test
  public void fibonacciBucketIndex_singleBucketAlwaysReturnsZero() {
    // log2NumBuckets = 0 means 1 bucket — all hashes map to bucket 0
    for (int hash = -1000; hash <= 1000; hash++) {
      assertThat(RecordSerializerBinaryV2.fibonacciBucketIndex(hash, 0))
          .as("hash=%d", hash)
          .isEqualTo(0);
    }
  }

  @Test
  public void fibonacciBucketIndex_producesValidIndices() {
    // For each valid log2NumBuckets, bucket index must be in [0, numBuckets)
    for (int log2 = 0; log2 <= RecordSerializerBinaryV2.MAX_LOG2_CAPACITY; log2++) {
      int numBuckets = 1 << log2;
      for (int hash = -1000; hash <= 1000; hash++) {
        int bucket = RecordSerializerBinaryV2.fibonacciBucketIndex(hash, log2);
        assertThat(bucket)
            .as("log2=%d, hash=%d", log2, hash)
            .isGreaterThanOrEqualTo(0)
            .isLessThan(numBuckets);
      }
    }
  }

  // --- buildCuckooTable ---

  @Test
  public void buildCuckooTable_thirteenProperties() {
    // Minimum cuckoo property count: 13. Verify all entries locatable via 2-bucket scan.
    byte[][] names = generateNames(13);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(13);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_twentyProperties() {
    byte[][] names = generateNames(20);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(20);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_fiftyProperties() {
    byte[][] names = generateNames(50);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(50);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_hundredProperties() {
    byte[][] names = generateNames(100);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(100);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_similarPrefixNames() {
    // Adversarial: names that differ only in suffix — tests hash independence
    byte[][] names = new byte[20][];
    for (int i = 0; i < 20; i++) {
      names[i] = toBytes("commonPrefix_" + i);
    }
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(20);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_identicalLengthNames() {
    // Adversarial: all names have the same length
    byte[][] names = new byte[20][];
    for (int i = 0; i < 20; i++) {
      names[i] = toBytes(String.format("name%04d", i));
    }
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(20);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_allCountsFrom13To60() {
    // Comprehensive sweep: verify cuckoo construction succeeds for all counts in range
    for (int n = 13; n <= 60; n++) {
      byte[][] names = generateNames(n);
      int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(n);
      RecordSerializerBinaryV2.CuckooTableResult result =
          RecordSerializerBinaryV2.buildCuckooTable(names, log2);
      assertAllCuckooEntriesLocatable(names, result);
    }
  }

  @Test
  public void buildCuckooTable_deterministic() {
    // Same input produces same output: seed and slot assignments are deterministic
    byte[][] names = generateNames(30);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(30);

    RecordSerializerBinaryV2.CuckooTableResult result1 =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    RecordSerializerBinaryV2.CuckooTableResult result2 =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);

    assertThat(result1.seed).isEqualTo(result2.seed);
    assertThat(result1.log2NumBuckets).isEqualTo(result2.log2NumBuckets);
    assertThat(result1.bucketArray).isEqualTo(result2.bucketArray);
    assertThat(result1.slotPropertyIndex).isEqualTo(result2.slotPropertyIndex);
    assertAllCuckooEntriesLocatable(names, result1);
  }

  @Test
  public void buildCuckooTable_bucketArraySize() {
    // Bucket array size must be numBuckets * BUCKET_SIZE * SLOT_SIZE
    byte[][] names = generateNames(25);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(25);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);

    int numBuckets = 1 << result.log2NumBuckets;
    int expectedSize =
        numBuckets * RecordSerializerBinaryV2.BUCKET_SIZE * RecordSerializerBinaryV2.SLOT_SIZE;
    assertThat(result.bucketArray).hasSize(expectedSize);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_noSlotOccupiedTwice() {
    // Verify no two properties occupy the same slot and exactly N slots are occupied
    byte[][] names = generateNames(40);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(40);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);

    int placedCount = 0;
    boolean[] seenPropertyIndex = new boolean[names.length];
    for (int s = 0; s < result.slotPropertyIndex.length; s++) {
      int propIdx = result.slotPropertyIndex[s];
      if (propIdx != -1) {
        assertThat(propIdx).as("slot %d propIdx", s).isBetween(0, names.length - 1);
        assertThat(seenPropertyIndex[propIdx])
            .as("Property %d placed in multiple slots", propIdx)
            .isFalse();
        seenPropertyIndex[propIdx] = true;
        placedCount++;
      }
    }
    assertThat(placedCount).isEqualTo(names.length);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_singleBucket_threeProperties() {
    // 3 properties with log2NumBuckets=0: single bucket (4 slots), both bucket1 and
    // bucket2 resolve to bucket 0, testing the degenerate case
    byte[][] names = generateNames(3);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(3);
    assertThat(log2).isEqualTo(0);

    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_allCountsFrom1To12() {
    // Sweep small property counts covering single-bucket and two-bucket degenerate cases
    for (int n = 1; n <= 12; n++) {
      byte[][] names = generateNames(n);
      int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(n);
      RecordSerializerBinaryV2.CuckooTableResult result =
          RecordSerializerBinaryV2.buildCuckooTable(names, log2);
      assertAllCuckooEntriesLocatable(names, result);
    }
  }

  @Test
  public void buildCuckooTable_forcesCapacityDoubling() {
    // 20 properties with log2NumBuckets=0 (4 slots) forces capacity doubling
    byte[][] names = generateNames(20);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, 0);
    assertThat(result.log2NumBuckets).isGreaterThan(0);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_throwsWhenPropertyCountExceedsMaxCapacity() {
    // 4096 slots at MAX_LOG2_CAPACITY cannot hold 5000 properties
    byte[][] names = generateNames(5000);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(5000);
    assertThatThrownBy(() -> RecordSerializerBinaryV2.buildCuckooTable(names, log2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to build cuckoo table");
  }

  @Test
  public void buildCuckooTable_unicodePropertyNames() {
    // Multi-byte UTF-8 property names: CJK, accented, mixed-script
    byte[][] names = {
        toBytes("\u540d\u524d"),
        toBytes("\u30e1\u30fc\u30eb"),
        toBytes("r\u00f4le"),
        toBytes("\u00fcber"),
        toBytes("stra\u00dfe"),
        toBytes("\u0438\u043c\u044f"),
        toBytes("\u4e3b\u952e"),
        toBytes("caf\u00e9"),
        toBytes("na\u00efve"),
        toBytes("\u03b1\u03b2\u03b3"),
        toBytes("normal_ascii"),
        toBytes("\u2603_snowman"),
        toBytes("prop_extra"),
    };
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(names.length);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);
    assertAllCuckooEntriesLocatable(names, result);
  }

  @Test
  public void buildCuckooTable_emptySlotsSentinelBytes() {
    // Verify unoccupied slots contain the empty sentinel pattern: 0xFF, 0xFF, 0xFF
    byte[][] names = generateNames(13);
    int log2 = RecordSerializerBinaryV2.computeLog2NumBuckets(13);
    RecordSerializerBinaryV2.CuckooTableResult result =
        RecordSerializerBinaryV2.buildCuckooTable(names, log2);

    for (int s = 0; s < result.slotPropertyIndex.length; s++) {
      if (result.slotPropertyIndex[s] == -1) {
        int offset = s * RecordSerializerBinaryV2.SLOT_SIZE;
        assertThat(result.bucketArray[offset])
            .as("Empty slot %d hash8 byte", s)
            .isEqualTo(RecordSerializerBinaryV2.EMPTY_HASH8);
        assertThat(result.bucketArray[offset + 1])
            .as("Empty slot %d offset low byte", s)
            .isEqualTo((byte) 0xFF);
        assertThat(result.bucketArray[offset + 2])
            .as("Empty slot %d offset high byte", s)
            .isEqualTo((byte) 0xFF);
      }
    }
  }

  // --- Helper methods ---

  private static byte[] toBytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[][] generateNames(int n) {
    byte[][] names = new byte[n][];
    for (int i = 0; i < n; i++) {
      names[i] = toBytes("prop_" + i);
    }
    return names;
  }

  /**
   * Asserts that the given seed produces no collisions: all property names map to distinct slots.
   */
  private static void assertNoPerfectHashCollision(byte[][] names, int seed, int log2Capacity) {
    int capacity = 1 << log2Capacity;
    boolean[] occupied = new boolean[capacity];
    for (byte[] name : names) {
      int hash = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3.hash32WithSeed(
          name, 0, name.length, seed);
      int slot = RecordSerializerBinaryV2.fibonacciIndex(hash, log2Capacity);
      assertThat(occupied[slot])
          .as("Collision at slot %d for property '%s' with seed %d and log2Capacity %d",
              slot, new String(name, StandardCharsets.UTF_8), seed, log2Capacity)
          .isFalse();
      occupied[slot] = true;
    }
  }

  /**
   * Asserts that every property in the cuckoo table is locatable by scanning at most 2 buckets
   * (bucket1 from h1, bucket2 from h2). Each property must be found in exactly one slot with the
   * correct hash8 prefix.
   */
  private static void assertAllCuckooEntriesLocatable(byte[][] names,
      RecordSerializerBinaryV2.CuckooTableResult result) {
    int seed = result.seed;
    int h2Seed = RecordSerializerBinaryV2.computeH2Seed(seed);
    int log2 = result.log2NumBuckets;
    int[] slotPropIdx = result.slotPropertyIndex;

    for (int i = 0; i < names.length; i++) {
      byte[] nameBytes = names[i];
      int h1 = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3.hash32WithSeed(
          nameBytes, 0, nameBytes.length, seed);
      int h2 = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3.hash32WithSeed(
          nameBytes, 0, nameBytes.length, h2Seed);
      byte expectedHash8 = RecordSerializerBinaryV2.computeHash8(h1);

      int bucket1 = RecordSerializerBinaryV2.fibonacciBucketIndex(h1, log2);
      int bucket2 = RecordSerializerBinaryV2.fibonacciBucketIndex(h2, log2);

      boolean found = false;
      // Scan bucket1
      int b1Start = bucket1 * RecordSerializerBinaryV2.BUCKET_SIZE;
      for (int s = 0; s < RecordSerializerBinaryV2.BUCKET_SIZE; s++) {
        if (slotPropIdx[b1Start + s] == i) {
          assertThat(result.bucketArray[(b1Start + s) * RecordSerializerBinaryV2.SLOT_SIZE])
              .as("hash8 mismatch for property '%s' at bucket1 slot %d",
                  new String(nameBytes, StandardCharsets.UTF_8), s)
              .isEqualTo(expectedHash8);
          found = true;
          break;
        }
      }
      // Scan bucket2 if not found
      if (!found) {
        int b2Start = bucket2 * RecordSerializerBinaryV2.BUCKET_SIZE;
        for (int s = 0; s < RecordSerializerBinaryV2.BUCKET_SIZE; s++) {
          if (slotPropIdx[b2Start + s] == i) {
            assertThat(result.bucketArray[(b2Start + s) * RecordSerializerBinaryV2.SLOT_SIZE])
                .as("hash8 mismatch for property '%s' at bucket2 slot %d",
                    new String(nameBytes, StandardCharsets.UTF_8), s)
                .isEqualTo(expectedHash8);
            found = true;
            break;
          }
        }
      }
      assertThat(found)
          .as("Property '%s' (index %d) not found in bucket1=%d or bucket2=%d",
              new String(nameBytes, StandardCharsets.UTF_8), i, bucket1, bucket2)
          .isTrue();
    }

    // Verify empty slots in bucketArray contain correct sentinel bytes
    for (int s = 0; s < slotPropIdx.length; s++) {
      int byteOffset = s * RecordSerializerBinaryV2.SLOT_SIZE;
      if (slotPropIdx[s] == -1) {
        assertThat(result.bucketArray[byteOffset])
            .as("empty slot %d hash8 must be EMPTY_HASH8", s)
            .isEqualTo(RecordSerializerBinaryV2.EMPTY_HASH8);
        int storedOffset = (result.bucketArray[byteOffset + 1] & 0xFF)
            | ((result.bucketArray[byteOffset + 2] & 0xFF) << 8);
        assertThat(storedOffset)
            .as("empty slot %d offset must be EMPTY_OFFSET", s)
            .isEqualTo(RecordSerializerBinaryV2.EMPTY_OFFSET & 0xFFFF);
      }
    }
  }
}
