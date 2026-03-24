package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Tests for the hash table core utilities in RecordSerializerBinaryV2: hash8 computation, sentinel
 * constants, linear probing hash table construction, and Fibonacci slot indexing.
 */
public class RecordSerializerBinaryV2HashTableTest {

  // --- Sentinel constants ---

  @Test
  public void emptySlotSentinelValues() {
    // Empty slot sentinel must be 0xFF / 0xFFFF per review decision T3/R2/A2
    assertThat(RecordSerializerBinaryV2.EMPTY_HASH8).isEqualTo((byte) 0xFF);
    assertThat(RecordSerializerBinaryV2.EMPTY_OFFSET).isEqualTo((short) 0xFFFF);
  }

  @Test
  public void slotSizeIsThreeBytes() {
    assertThat(RecordSerializerBinaryV2.SLOT_SIZE).isEqualTo(3);
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

  // ========================================================================================
  // Linear probing hash table tests
  // ========================================================================================

  // --- computeLog2Capacity ---

  @Test
  public void computeLog2Capacity_thirteenProperties() {
    // 13 * 8 / 5 = 20.8 → ceil = 21 → nextPow2 = 32 → log2 = 5
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(13)).isEqualTo(5);
  }

  @Test
  public void computeLog2Capacity_twentyProperties() {
    // 20 * 8 / 5 = 32.0 → ceil = 32 → nextPow2 = 32 → log2 = 5
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(20)).isEqualTo(5);
  }

  @Test
  public void computeLog2Capacity_thirtyProperties() {
    // 30 * 8 / 5 = 48.0 → ceil = 48 → nextPow2 = 64 → log2 = 6
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(30)).isEqualTo(6);
  }

  @Test
  public void computeLog2Capacity_fiftyProperties() {
    // 50 * 8 / 5 = 80.0 → ceil = 80 → nextPow2 = 128 → log2 = 7
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(50)).isEqualTo(7);
  }

  @Test
  public void computeLog2Capacity_hundredProperties() {
    // 100 * 8 / 5 = 160.0 → ceil = 160 → nextPow2 = 256 → log2 = 8
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(100)).isEqualTo(8);
  }

  @Test
  public void computeLog2Capacity_singleProperty() {
    // 1 * 8 / 5 = 1.6 → ceil = 2 → log2 = 1 (minimum)
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(1)).isEqualTo(1);
  }

  @Test
  public void computeLog2Capacity_twoProperties() {
    // 2 * 8 / 5 = 3.2 → ceil = 4 → log2 = 2
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(2)).isEqualTo(2);
  }

  @Test
  public void computeLog2Capacity_threeProperties() {
    // 3 * 8 / 5 = 4.8 → ceil = 5 → nextPow2 = 8 → log2 = 3
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(3)).isEqualTo(3);
  }

  @Test
  public void computeLog2Capacity_exactPowerOfTwoMinSlots() {
    // N=5: minSlots = (5*8+4)/5 = 8 (exact power of two)
    // 32 - nlz(7) = 32 - 29 = 3 → capacity = 8 (correct, not 4)
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(5)).isEqualTo(3);
    // N=10: minSlots = (10*8+4)/5 = 16 (exact power of two)
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(10)).isEqualTo(4);
  }

  @Test
  public void computeLog2Capacity_clampedToMax() {
    // Very large property count should be clamped to MAX_LOG2_CAPACITY
    assertThat(RecordSerializerBinaryV2.computeLog2Capacity(5000))
        .isEqualTo(RecordSerializerBinaryV2.MAX_LOG2_CAPACITY);
  }

  // --- fibonacciSlotIndex ---

  @Test
  public void fibonacciSlotIndex_producesValidIndices() {
    // For each valid log2Capacity, slot index must be in [0, capacity)
    for (int log2 = 1; log2 <= RecordSerializerBinaryV2.MAX_LOG2_CAPACITY; log2++) {
      int capacity = 1 << log2;
      for (int hash = -1000; hash <= 1000; hash++) {
        int slot = RecordSerializerBinaryV2.fibonacciSlotIndex(hash, log2);
        assertThat(slot)
            .as("log2=%d, hash=%d", log2, hash)
            .isGreaterThanOrEqualTo(0)
            .isLessThan(capacity);
      }
    }
  }

  @Test
  public void fibonacciSlotIndex_extremeHashValues() {
    // MurmurHash3 can return any int value including Integer.MIN_VALUE.
    // Verify Fibonacci hashing produces valid slot indices at extremes.
    int[] extremeHashes = {Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1};
    for (int log2 = 1; log2 <= RecordSerializerBinaryV2.MAX_LOG2_CAPACITY; log2++) {
      int capacity = 1 << log2;
      for (int hash : extremeHashes) {
        int slot = RecordSerializerBinaryV2.fibonacciSlotIndex(hash, log2);
        assertThat(slot)
            .as("log2=%d, hash=%d", log2, hash)
            .isGreaterThanOrEqualTo(0)
            .isLessThan(capacity);
      }
    }
  }

  // --- buildHashTable ---

  @Test
  public void buildHashTable_thirteenProperties() {
    // Minimum hash table property count: 13. Verify all entries locatable via linear probe.
    byte[][] names = generateNames(13);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(13);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_twentyProperties() {
    byte[][] names = generateNames(20);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(20);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_fiftyProperties() {
    byte[][] names = generateNames(50);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(50);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_hundredProperties() {
    byte[][] names = generateNames(100);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(100);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_similarPrefixNames() {
    // Adversarial: names that differ only in suffix — tests hash independence
    byte[][] names = new byte[20][];
    for (int i = 0; i < 20; i++) {
      names[i] = toBytes("commonPrefix_" + i);
    }
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(20);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_identicalLengthNames() {
    // Adversarial: all names have the same length
    byte[][] names = new byte[20][];
    for (int i = 0; i < 20; i++) {
      names[i] = toBytes(String.format("name%04d", i));
    }
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(20);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_allCountsFrom13To60() {
    // Comprehensive sweep: verify linear probing construction succeeds for all counts in range
    for (int n = 13; n <= 60; n++) {
      byte[][] names = generateNames(n);
      int log2 = RecordSerializerBinaryV2.computeLog2Capacity(n);
      RecordSerializerBinaryV2.HashTableResult result =
          RecordSerializerBinaryV2.buildHashTable(names, log2);
      assertAllEntriesLocatable(names, result);
    }
  }

  @Test
  public void buildHashTable_deterministic() {
    // Same input produces same output: seed and slot assignments are deterministic
    byte[][] names = generateNames(30);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(30);

    RecordSerializerBinaryV2.HashTableResult result1 =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    RecordSerializerBinaryV2.HashTableResult result2 =
        RecordSerializerBinaryV2.buildHashTable(names, log2);

    assertThat(result1.seed).isEqualTo(0);
    assertThat(result2.seed).isEqualTo(0);
    assertThat(result1.log2Capacity).isEqualTo(result2.log2Capacity);
    assertThat(result1.slotArray).isEqualTo(result2.slotArray);
    assertThat(result1.slotPropertyIndex).isEqualTo(result2.slotPropertyIndex);
    assertAllEntriesLocatable(names, result1);
  }

  @Test
  public void buildHashTable_slotArraySize() {
    // Slot array size must be capacity * SLOT_SIZE
    byte[][] names = generateNames(25);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(25);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);

    int capacity = 1 << result.log2Capacity;
    int expectedSize = capacity * RecordSerializerBinaryV2.SLOT_SIZE;
    assertThat(result.slotArray).hasSize(expectedSize);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_noSlotOccupiedTwice() {
    // Verify no two properties occupy the same slot and exactly N slots are occupied
    byte[][] names = generateNames(40);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(40);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);

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
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_capacityBoundary() {
    // Smallest hash table case (13 properties): capacity must be exactly 32 (= 2^5)
    byte[][] names = generateNames(13);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(13);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
    assertThat(1 << result.log2Capacity).isEqualTo(32);
  }

  @Test
  public void buildHashTable_wrapAroundProbe() {
    // Verify that linear probing actually wraps around the end of the slot array.
    // Sweep property counts until we find one where at least one property's final
    // slot index is less than its Fibonacci start slot (proving wrap-around occurred).
    boolean wrapFound = false;
    for (int n = 13; n <= 200 && !wrapFound; n++) {
      byte[][] names = generateNames(n);
      int log2 = RecordSerializerBinaryV2.computeLog2Capacity(n);
      RecordSerializerBinaryV2.HashTableResult result =
          RecordSerializerBinaryV2.buildHashTable(names, log2);
      int capacity = 1 << result.log2Capacity;
      assertAllEntriesLocatable(names, result);
      for (int i = 0; i < n; i++) {
        int hash = MurmurHash3.hash32WithSeed(names[i], 0, names[i].length, result.seed);
        int startSlot = RecordSerializerBinaryV2.fibonacciSlotIndex(hash, result.log2Capacity);
        for (int s = 0; s < capacity; s++) {
          if (result.slotPropertyIndex[s] == i) {
            if (s < startSlot) {
              wrapFound = true;
            }
            break;
          }
        }
      }
    }
    assertThat(wrapFound)
        .as("Expected at least one property to wrap around the slot array boundary")
        .isTrue();
  }

  @Test
  public void buildHashTable_unicodePropertyNames() {
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
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(names.length);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
  }

  @Test
  public void buildHashTable_emptySlotsSentinelBytes() {
    // Verify unoccupied slots contain the empty sentinel pattern: 0xFF, 0xFF, 0xFF
    byte[][] names = generateNames(13);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(13);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);

    for (int s = 0; s < result.slotPropertyIndex.length; s++) {
      if (result.slotPropertyIndex[s] == -1) {
        int offset = s * RecordSerializerBinaryV2.SLOT_SIZE;
        assertThat(result.slotArray[offset])
            .as("Empty slot %d hash8 byte", s)
            .isEqualTo(RecordSerializerBinaryV2.EMPTY_HASH8);
        assertThat(result.slotArray[offset + 1])
            .as("Empty slot %d offset low byte", s)
            .isEqualTo((byte) 0xFF);
        assertThat(result.slotArray[offset + 2])
            .as("Empty slot %d offset high byte", s)
            .isEqualTo((byte) 0xFF);
      }
    }
  }

  @Test
  public void buildHashTable_maxProbeLengthBounded() {
    // R4: At 0.625 load factor, expected max probe length is O(log n) ~7 for 100 entries.
    // Bound of 15 provides generous headroom for hash clustering variance.
    for (int n : new int[] {50, 75, 100}) {
      byte[][] names = generateNames(n);
      int log2 = RecordSerializerBinaryV2.computeLog2Capacity(n);
      RecordSerializerBinaryV2.HashTableResult result =
          RecordSerializerBinaryV2.buildHashTable(names, log2);
      int capacity = 1 << result.log2Capacity;
      int seed = result.seed;

      int maxProbes = 0;
      for (int i = 0; i < n; i++) {
        byte[] nameBytes = names[i];
        int hash = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
        int startSlot = RecordSerializerBinaryV2.fibonacciSlotIndex(hash, result.log2Capacity);

        int probes = 0;
        for (int p = 0; p < capacity; p++) {
          int slot = (startSlot + p) & (capacity - 1);
          probes++;
          if (result.slotPropertyIndex[slot] == i) {
            break;
          }
        }
        maxProbes = Math.max(maxProbes, probes);
      }
      assertThat(maxProbes)
          .as("Max probe length for %d properties", n)
          .isLessThan(15);
    }
  }

  @Test
  public void buildHashTable_failsWhenCapacityInsufficient() {
    // 10 properties in a table with only 2 slots (log2Capacity=1) cannot fit.
    byte[][] names = generateNames(10);
    assertThatThrownBy(() -> RecordSerializerBinaryV2.buildHashTable(names, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be < capacity");
  }

  @Test
  public void buildHashTable_atMaxCapacityClamp_1280Properties() {
    // 1280 properties at MAX_LOG2_CAPACITY=11 (2048 slots) = exact 0.625 load factor.
    // This is the tightest packing the capacity formula is designed for.
    byte[][] names = generateNames(1280);
    int log2 = RecordSerializerBinaryV2.computeLog2Capacity(1280);
    assertThat(log2).isEqualTo(RecordSerializerBinaryV2.MAX_LOG2_CAPACITY);
    RecordSerializerBinaryV2.HashTableResult result =
        RecordSerializerBinaryV2.buildHashTable(names, log2);
    assertAllEntriesLocatable(names, result);
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
   * Asserts that every property in the hash table is locatable by starting at the Fibonacci slot
   * index and probing forward. Each property must be found with the correct hash8 prefix.
   */
  private static void assertAllEntriesLocatable(byte[][] names,
      RecordSerializerBinaryV2.HashTableResult result) {
    int seed = result.seed;
    int log2 = result.log2Capacity;
    int capacity = 1 << log2;
    int[] slotPropIdx = result.slotPropertyIndex;

    for (int i = 0; i < names.length; i++) {
      byte[] nameBytes = names[i];
      int hash = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
      byte expectedHash8 = RecordSerializerBinaryV2.computeHash8(hash);
      int startSlot = RecordSerializerBinaryV2.fibonacciSlotIndex(hash, log2);

      boolean found = false;
      // Linear probe forward from startSlot
      for (int probe = 0; probe < capacity; probe++) {
        int slot = (startSlot + probe) & (capacity - 1);

        // Empty slot means property not found (should not happen)
        if (slotPropIdx[slot] == -1) {
          break;
        }

        if (slotPropIdx[slot] == i) {
          // Verify hash8 is correct in the slot array
          assertThat(result.slotArray[slot * RecordSerializerBinaryV2.SLOT_SIZE])
              .as("hash8 mismatch for property '%s' at slot %d",
                  new String(nameBytes, StandardCharsets.UTF_8), slot)
              .isEqualTo(expectedHash8);
          found = true;
          break;
        }
      }
      assertThat(found)
          .as("Property '%s' (index %d) not found via linear probe from slot %d",
              new String(nameBytes, StandardCharsets.UTF_8), i, startSlot)
          .isTrue();
    }

    // Verify empty slots in slotArray contain correct sentinel bytes
    for (int s = 0; s < slotPropIdx.length; s++) {
      int byteOffset = s * RecordSerializerBinaryV2.SLOT_SIZE;
      if (slotPropIdx[s] == -1) {
        assertThat(result.slotArray[byteOffset])
            .as("empty slot %d hash8 must be EMPTY_HASH8", s)
            .isEqualTo(RecordSerializerBinaryV2.EMPTY_HASH8);
        int storedOffset = (result.slotArray[byteOffset + 1] & 0xFF)
            | ((result.slotArray[byteOffset + 2] & 0xFF) << 8);
        assertThat(storedOffset)
            .as("empty slot %d offset must be EMPTY_OFFSET", s)
            .isEqualTo(RecordSerializerBinaryV2.EMPTY_OFFSET & 0xFFFF);
      }
    }
  }
}
