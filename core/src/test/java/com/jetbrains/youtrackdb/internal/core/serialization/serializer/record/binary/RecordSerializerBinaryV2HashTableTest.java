package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Tests for the hash table core utilities in RecordSerializerBinaryV2: Fibonacci hashing, capacity
 * computation, perfect hash seed search, hash8 computation, and sentinel constants.
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

  // --- Helper methods ---

  private static byte[] toBytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
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
}
