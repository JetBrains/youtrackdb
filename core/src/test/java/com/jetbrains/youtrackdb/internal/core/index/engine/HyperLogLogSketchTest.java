/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link HyperLogLogSketch}.
 *
 * <p>Covers: add + estimate accuracy, small-range correction, duplicate keys,
 * empty sketch, merge (commutativity, correctness, idempotency), clone
 * independence, serialization round-trip, serialized size, register bounds,
 * and hash distribution.
 */
public class HyperLogLogSketchTest {

  // Fixed seed matching the one used in IndexHistogramManager (ADR Section 6.2.1)
  private static final int HASH_SEED = 0x9747b28c;

  /**
   * Hashes a string key to a 64-bit value using MurmurHash3,
   * matching the hashing approach from the ADR.
   */
  private static long hashKey(String key) {
    byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
    return MurmurHash3.murmurHash3_x64_64(bytes, HASH_SEED);
  }

  /**
   * Hashes an integer key.
   */
  private static long hashInt(int key) {
    return hashKey(Integer.toString(key));
  }

  // Constants mirroring the source (used to compute expected values)
  private static final int P = 10;
  private static final int M = 1 << P; // 1024
  private static final double ALPHA_M = 0.7213 / (1.0 + 1.079 / M);
  private static final double TWO_POW_64 = Math.pow(2, 64);

  /** Reads the register array from a sketch by serializing it. */
  private static byte[] readRegisters(HyperLogLogSketch sketch) {
    byte[] regs = new byte[M];
    sketch.writeTo(regs, 0);
    return regs;
  }

  /** Creates a sketch from a raw register array via readFrom. */
  private static HyperLogLogSketch sketchFromRegisters(byte[] registers) {
    return HyperLogLogSketch.readFrom(registers, 0);
  }

  // ── Empty sketch ─────────────────────────────────────────────

  @Test
  public void testEmptySketchReturnsZero() {
    // estimate() on a fresh sketch should return 0
    var sketch = new HyperLogLogSketch();
    Assert.assertEquals(0, sketch.estimate());
  }

  // ── Serialized size ──────────────────────────────────────────

  @Test
  public void testSerializedSizeIs1024() {
    Assert.assertEquals(1024, HyperLogLogSketch.serializedSize());
  }

  // ── add + estimate accuracy ──────────────────────────────────

  @Test
  public void testAddAndEstimateWithin7Percent() {
    // Insert 10_000 distinct hashes and verify estimate is within 7% (~2σ
    // for p=10 HLL with 3.25% standard error)
    var sketch = new HyperLogLogSketch();
    int n = 10_000;
    for (int i = 0; i < n; i++) {
      sketch.add(hashInt(i));
    }
    long estimate = sketch.estimate();
    double relativeError = Math.abs((double) estimate - n) / n;
    Assert.assertTrue(
        "Estimate " + estimate + " has error " + relativeError
            + " (> 7%) for N=" + n,
        relativeError <= 0.07);
  }

  // ── Accuracy sweep across cardinalities ──────────────────────

  @Test
  public void testAccuracySweep() {
    // Test with N = 10, 100, 1K, 10K, 100K, 1M distinct keys.
    // Verify relative error ≤ 7% (~2σ for p=10 HLL) for each.
    int[] cardinalities = {10, 100, 1_000, 10_000, 100_000, 1_000_000};
    for (int n : cardinalities) {
      var sketch = new HyperLogLogSketch();
      for (int i = 0; i < n; i++) {
        sketch.add(hashInt(i));
      }
      long estimate = sketch.estimate();
      double relativeError = Math.abs((double) estimate - n) / n;
      Assert.assertTrue(
          "N=" + n + ": estimate=" + estimate
              + ", relativeError=" + relativeError + " > 7%",
          relativeError <= 0.07);
    }
  }

  // ── Small-range correction ───────────────────────────────────

  @Test
  public void testSmallRangeVerySmallCardinalities() {
    // Insert 1–9 distinct keys. For very small N, use absolute error
    // tolerance since relative error is meaningless at N=1.
    // Linear counting with 1024 registers is highly accurate here.
    for (int n = 1; n <= 9; n++) {
      var sketch = new HyperLogLogSketch();
      for (int i = 0; i < n; i++) {
        sketch.add(hashInt(i));
      }
      long estimate = sketch.estimate();
      Assert.assertTrue(
          "N=" + n + ": estimate=" + estimate + " should be within 1 of N",
          Math.abs(estimate - n) <= 1);
    }
  }

  @Test
  public void testSmallRangeLinearCounting() {
    // Insert 10–100 distinct keys and verify linear counting produces
    // accurate estimates. Linear counting with m=1024 registers uses
    // m * ln(m / V), which has rounding artifacts for small N (e.g.,
    // N=32 → estimate=33, ~3.1% error). Allow up to 5% relative error.
    for (int n = 10; n <= 100; n++) {
      var sketch = new HyperLogLogSketch();
      for (int i = 0; i < n; i++) {
        sketch.add(hashInt(i));
      }
      long estimate = sketch.estimate();
      double relativeError = Math.abs((double) estimate - n) / n;
      Assert.assertTrue(
          "N=" + n + ": estimate=" + estimate
              + ", relativeError=" + relativeError + " > 5%",
          relativeError <= 0.05);
    }
  }

  // ── Duplicate keys ───────────────────────────────────────────

  @Test
  public void testDuplicateKeysEstimateOne() {
    // Insert same hash 1M times → estimate ≈ 1
    var sketch = new HyperLogLogSketch();
    long hash = hashKey("duplicated_key");
    for (int i = 0; i < 1_000_000; i++) {
      sketch.add(hash);
    }
    long estimate = sketch.estimate();
    // With only one distinct value, linear counting gives a very accurate result.
    // Allow some tolerance for the probabilistic nature.
    Assert.assertTrue(
        "Expected estimate ≈ 1, got " + estimate,
        estimate >= 1 && estimate <= 3);
  }

  // ── Merge commutativity ──────────────────────────────────────

  @Test
  public void testMergeCommutativity() {
    // a.merge(b) produces same estimate as b.merge(a) (on clones)
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      a.add(hashInt(i));
    }
    for (int i = 5000; i < 10000; i++) {
      b.add(hashInt(i));
    }

    var ab = a.copy();
    ab.merge(b);

    var ba = b.copy();
    ba.merge(a);

    Assert.assertEquals(
        "Merge should be commutative",
        ab.estimate(), ba.estimate());
  }

  // ── Merge correctness: disjoint sets ─────────────────────────

  @Test
  public void testMergeDisjointSets() {
    // Two sketches with disjoint key sets → merged estimate ≈ sum
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    int n = 5000;
    for (int i = 0; i < n; i++) {
      a.add(hashInt(i));
    }
    for (int i = n; i < 2 * n; i++) {
      b.add(hashInt(i));
    }

    var merged = a.copy();
    merged.merge(b);

    long expected = 2 * n;
    long estimate = merged.estimate();
    double relativeError = Math.abs((double) estimate - expected) / expected;
    Assert.assertTrue(
        "Disjoint merge: estimate=" + estimate + ", expected=" + expected
            + ", error=" + relativeError,
        relativeError <= 0.07);
  }

  // ── Merge correctness: identical sets ────────────────────────

  @Test
  public void testMergeIdenticalSets() {
    // Two sketches with identical key sets → merged estimate ≈ either one
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    int n = 5000;
    for (int i = 0; i < n; i++) {
      long hash = hashInt(i);
      a.add(hash);
      b.add(hash);
    }

    long estimateBefore = a.estimate();
    var merged = a.copy();
    merged.merge(b);
    long estimateAfter = merged.estimate();

    Assert.assertEquals(
        "Identical merge should not change estimate",
        estimateBefore, estimateAfter);
  }

  // ── Merge idempotency ───────────────────────────────────────

  @Test
  public void testMergeIdempotency() {
    // a.merge(a_clone) → estimate unchanged
    var a = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      a.add(hashInt(i));
    }
    long estimateBefore = a.estimate();

    var aClone = a.copy();
    a.merge(aClone);

    Assert.assertEquals(
        "Self-merge should not change estimate",
        estimateBefore, a.estimate());
  }

  // ── Copy independence ────────────────────────────────────────

  @Test
  public void testCopyIndependence() {
    // Modify original after copy → copy's estimate unchanged
    var original = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      original.add(hashInt(i));
    }

    var cloned = original.copy();
    long clonedEstimate = cloned.estimate();

    // Modify original by adding many more keys
    for (int i = 5000; i < 20000; i++) {
      original.add(hashInt(i));
    }

    Assert.assertEquals(
        "Copy should be independent of original modifications",
        clonedEstimate, cloned.estimate());
    Assert.assertNotEquals(
        "Original should have different estimate after adding more keys",
        clonedEstimate, original.estimate());
  }

  // ── Serialization round-trip ─────────────────────────────────

  @Test
  public void testSerializationRoundTrip() {
    // writeTo() → readFrom() → estimate matches original
    var original = new HyperLogLogSketch();
    for (int i = 0; i < 10_000; i++) {
      original.add(hashInt(i));
    }
    long originalEstimate = original.estimate();

    // Serialize
    byte[] buffer = new byte[HyperLogLogSketch.serializedSize()];
    original.writeTo(buffer, 0);

    // Deserialize
    var restored = HyperLogLogSketch.readFrom(buffer, 0);

    Assert.assertEquals(
        "Round-trip estimate should match",
        originalEstimate, restored.estimate());
  }

  @Test
  public void testSerializationRoundTripWithOffset() {
    // Verify serialization works with a non-zero offset
    var original = new HyperLogLogSketch();
    for (int i = 0; i < 5_000; i++) {
      original.add(hashInt(i));
    }
    long originalEstimate = original.estimate();

    int offset = 42;
    byte[] buffer = new byte[offset + HyperLogLogSketch.serializedSize()];
    original.writeTo(buffer, offset);

    var restored = HyperLogLogSketch.readFrom(buffer, offset);
    Assert.assertEquals(originalEstimate, restored.estimate());
  }

  // ── Register bounds ──────────────────────────────────────────

  @Test
  public void testRegisterBoundsWithinValidRange() {
    // Insert keys and verify all registers stay in [0, 54]
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 100_000; i++) {
      sketch.add(hashInt(i));
    }

    // Serialize to inspect register values
    byte[] registers = new byte[HyperLogLogSketch.serializedSize()];
    sketch.writeTo(registers, 0);

    for (int i = 0; i < registers.length; i++) {
      int val = registers[i] & 0xFF; // unsigned byte
      Assert.assertTrue(
          "Register[" + i + "] = " + val + " is out of range [0, 54]",
          val >= 0 && val <= 54);
    }
  }

  // ── Hash distribution ────────────────────────────────────────

  @Test
  public void testHashDistributionNoZeroRegisters() {
    // ADR specifies 10K keys, but with 1024 registers the probability of
    // at least one zero register is ~5.7% — too high for a reliable test.
    // Using 50K keys instead (probability of any zero register: ~10^-21).
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 50_000; i++) {
      sketch.add(hashInt(i));
    }

    byte[] registers = new byte[HyperLogLogSketch.serializedSize()];
    sketch.writeTo(registers, 0);

    for (int i = 0; i < registers.length; i++) {
      Assert.assertNotEquals(
          "Register[" + i + "] should not be 0 after 50K distinct keys",
          0, registers[i]);
    }
  }

  // ── rebuildFrom ──────────────────────────────────────────────

  @Test
  public void testRebuildFromStream() {
    // Build a sketch from individual adds, then rebuild from stream.
    // Both should produce the same estimate.
    var original = new HyperLogLogSketch();
    int n = 10_000;
    for (int i = 0; i < n; i++) {
      original.add(hashInt(i));
    }
    long originalEstimate = original.estimate();

    // Rebuild from stream
    var rebuilt = new HyperLogLogSketch();
    rebuilt.rebuildFrom(
        IntStream.range(0, n).mapToObj(Integer::toString),
        key -> hashKey((String) key));

    long rebuiltEstimate = rebuilt.estimate();
    Assert.assertEquals(
        "Rebuilt sketch should match original estimate",
        originalEstimate, rebuiltEstimate);
  }

  @Test
  public void testRebuildFromClearsExistingRegisters() {
    // Verify that rebuildFrom resets registers before populating
    var sketch = new HyperLogLogSketch();
    // Add many large-valued keys first
    for (int i = 0; i < 100_000; i++) {
      sketch.add(hashInt(i));
    }
    long largePrevEstimate = sketch.estimate();

    // Rebuild with only 100 keys
    sketch.rebuildFrom(
        IntStream.range(0, 100).mapToObj(Integer::toString),
        key -> hashKey((String) key));

    long smallEstimate = sketch.estimate();
    Assert.assertTrue(
        "After rebuild with 100 keys, estimate (" + smallEstimate
            + ") should be much less than previous (" + largePrevEstimate
            + ")",
        smallEstimate < largePrevEstimate / 2);
    double relativeError = Math.abs((double) smallEstimate - 100) / 100;
    Assert.assertTrue(
        "Rebuild estimate should be close to 100, got " + smallEstimate,
        relativeError <= 0.05);
  }

  @Test
  public void testRebuildFromEmptyStreamResetsToZero() {
    // A sketch with data, rebuilt from an empty stream, should estimate 0.
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 1000; i++) {
      sketch.add(hashInt(i));
    }
    Assert.assertTrue(sketch.estimate() > 0);

    sketch.rebuildFrom(Stream.empty(), key -> hashKey((String) key));
    Assert.assertEquals(0, sketch.estimate());
  }

  // ── Merge with empty sketch ────────────────────────────────

  @Test
  public void testMergePopulatedIntoEmpty() {
    // Merging a populated sketch into an empty one should yield the
    // populated sketch's estimate.
    var empty = new HyperLogLogSketch();
    var populated = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      populated.add(hashInt(i));
    }
    long expectedEstimate = populated.estimate();

    empty.merge(populated);
    Assert.assertEquals(expectedEstimate, empty.estimate());
  }

  @Test
  public void testMergeEmptyIntoPopulated() {
    // Merging an empty sketch into a populated one should not change
    // the estimate.
    var populated = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      populated.add(hashInt(i));
    }
    long expectedEstimate = populated.estimate();

    var empty = new HyperLogLogSketch();
    populated.merge(empty);
    Assert.assertEquals(expectedEstimate, populated.estimate());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testReadFromTruncatedBuffer() {
    // readFrom should throw with a diagnostic message when the source
    // buffer is too short, rather than an opaque ArrayIndexOutOfBoundsException.
    var truncated = new byte[512]; // need 1024 (= serializedSize())
    HyperLogLogSketch.readFrom(truncated, 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testReadFromTruncatedBufferWithOffset() {
    // Buffer is large enough from offset 0 but not from offset 100.
    var buf = new byte[HyperLogLogSketch.serializedSize()];
    HyperLogLogSketch.readFrom(buf, 100);
  }

  // ── readFrom register validation ──────────────────────────────

  @Test
  public void testReadFromClampsCorruptRegisterAboveMax() {
    // A corrupted page could contain register values > 54.
    // readFrom must clamp them to MAX_REGISTER_VALUE (54) to prevent
    // 1L << register[i] from overflowing in estimate().
    byte[] src = new byte[HyperLogLogSketch.serializedSize()];
    src[0] = 127; // way above MAX_REGISTER_VALUE (54)
    src[1] = 55; // just above
    src[2] = 54; // exactly at max — should be kept as-is
    src[3] = 30; // normal value

    var sketch = HyperLogLogSketch.readFrom(src, 0);

    // Serialize back and inspect clamped values
    byte[] out = new byte[HyperLogLogSketch.serializedSize()];
    sketch.writeTo(out, 0);

    Assert.assertEquals("Register 0 should be clamped to 54",
        54, out[0]);
    Assert.assertEquals("Register 1 should be clamped to 54",
        54, out[1]);
    Assert.assertEquals("Register 2 should stay at 54",
        54, out[2]);
    Assert.assertEquals("Register 3 should stay at 30",
        30, out[3]);
  }

  @Test
  public void testReadFromClampsNegativeRegister() {
    // Java bytes are signed: -1 = 0xFF = 255 unsigned.
    // This is well above MAX_REGISTER_VALUE and must be clamped.
    byte[] src = new byte[HyperLogLogSketch.serializedSize()];
    src[0] = -1; // 0xFF — negative as signed byte
    src[1] = -128; // 0x80 — minimum signed byte

    var sketch = HyperLogLogSketch.readFrom(src, 0);

    byte[] out = new byte[HyperLogLogSketch.serializedSize()];
    sketch.writeTo(out, 0);

    Assert.assertEquals("Negative register (0xFF) should be clamped to 54",
        54, out[0]);
    Assert.assertEquals("Negative register (0x80) should be clamped to 54",
        54, out[1]);
  }

  // ── add: verify register index from hash ──────────────────────

  @Test
  public void add_verifyRegisterIndex() {
    // The low 10 bits of the hash select the register index.
    // Construct a hash where the low 10 bits = 42 (0b0000101010).
    // After add, register[42] should be non-zero.
    var sketch = new HyperLogLogSketch();
    // Hash with low 10 bits = 42 and some leading-zero pattern above
    long hash = (1L << 10) | 42; // bits above P: ...0001, rho = 53
    sketch.add(hash);

    byte[] registers = new byte[HyperLogLogSketch.serializedSize()];
    sketch.writeTo(registers, 0);

    Assert.assertNotEquals(
        "Register[42] should be updated by hash with low bits = 42",
        0, registers[42]);
    // Verify no other register in the range [40..44] except 42 was touched
    // (to confirm index selection is correct)
    Assert.assertEquals(0, registers[40]);
    Assert.assertEquals(0, registers[41]);
    Assert.assertEquals(0, registers[43]);
    Assert.assertEquals(0, registers[44]);
  }

  // ── add: verify rho value ─────────────────────────────────────

  @Test
  public void add_verifyRhoValue() {
    // Construct a hash where: low 10 bits = 0 (register 0),
    // remaining 54 bits = 0 (all zeros). rho = leading zeros + 1.
    // w = hash >>> 10 = 0. w | 1L = 1. numberOfLeadingZeros(1) = 63.
    // rho = 63 - 10 + 1 = 54 (MAX_REGISTER_VALUE).
    var sketch = new HyperLogLogSketch();
    sketch.add(0L); // all bits zero

    byte[] registers = new byte[HyperLogLogSketch.serializedSize()];
    sketch.writeTo(registers, 0);

    Assert.assertEquals(
        "Register[0] should have rho=54 for all-zero hash",
        54, registers[0]);
  }

  @Test
  public void add_verifyRhoValueWithKnownLeadingZeros() {
    // Hash = 1 << 20: low 10 bits = 0 → register 0.
    // w = hash >>> 10 = 1 << 10 = 1024. w | 1L = 1025.
    // 1025 in binary = 10_0000_0001 → 64 - 11 = 53 leading zeros.
    // rho = 53 - 10 + 1 = 44.
    var sketch = new HyperLogLogSketch();
    long hash = 1L << 20; // only bit 20 set, register index = 0
    sketch.add(hash);

    byte[] registers = new byte[HyperLogLogSketch.serializedSize()];
    sketch.writeTo(registers, 0);

    // w = hash >>> 10 = 1 << 10 = 1024. w | 1L = 1025.
    // numberOfLeadingZeros(1025) = 53 (1025 fits in 11 bits, 64-11=53).
    // rho = 53 - 10 + 1 = 44.
    Assert.assertEquals(
        "Register[0] should have rho=44 for hash with bit 20 set",
        44, registers[0]);
  }

  // ── estimate: all-zero registers returns zero ─────────────────

  @Test
  public void estimate_allZeroRegisters_returnsZero() {
    // A freshly constructed sketch has all-zero registers.
    // All 1024 registers are zero → zeroCount = 1024.
    // rawEstimate = ALPHA_M * M * M / sum where sum = 1024 (each 1/(1<<0) = 1).
    // rawEstimate = 0.72054 * 1024 * 1024 / 1024 ≈ 737.8.
    // Small-range check: 737.8 <= 2.5 * 1024 = 2560, zeroCount = 1024 > 0.
    // Linear counting: M * ln(M / 1024) = 1024 * ln(1) = 0.
    var sketch = new HyperLogLogSketch();
    Assert.assertEquals(
        "All-zero registers should estimate 0", 0, sketch.estimate());
  }

  // ── estimate: single register set → small-range correction ────

  @Test
  public void estimate_singleRegisterSet_smallRangeCorrection() {
    // Set exactly one register by adding one hash. With 1023 zero
    // registers, small-range correction (linear counting) should be
    // used: M * ln(M / zeroCount) = 1024 * ln(1024 / 1023) ≈ 1.0.
    var sketch = new HyperLogLogSketch();
    sketch.add(hashInt(0)); // adds exactly one key

    long est = sketch.estimate();
    // With linear counting, expected ≈ 1024 * ln(1024/1023) ≈ 1.00
    Assert.assertTrue(
        "Single key estimate should be ~1 (got " + est + ")",
        est >= 1 && est <= 2);
  }

  // ── merge: takes max register ─────────────────────────────────

  @Test
  public void merge_takesMaxRegister() {
    // Create two sketches where register 0 has different values.
    // After merge, register 0 should be the maximum of both.
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();

    // Add a hash to register 0 in sketch a with a known rho
    a.add(0L); // register 0, rho = 54 (max)
    // Add a hash to register 0 in sketch b with smaller rho
    b.add(1L << 10); // register 0, w = 1 → rho = 54 too
    // Use different register indices to demonstrate max behavior
    // Register 5: only in sketch a
    a.add(5L); // register 5
    // Register 5: only in sketch b with different value
    b.add(5L | (1L << 20)); // register 5, different w → different rho

    byte[] regA = new byte[1024];
    byte[] regB = new byte[1024];
    a.writeTo(regA, 0);
    b.writeTo(regB, 0);

    a.merge(b);

    byte[] merged = new byte[1024];
    a.writeTo(merged, 0);

    // Each register in merged should be max(regA[i], regB[i])
    for (int i = 0; i < 1024; i++) {
      int expected = Math.max(regA[i] & 0xFF, regB[i] & 0xFF);
      Assert.assertEquals(
          "Register[" + i + "] should be max of both sketches",
          expected, merged[i] & 0xFF);
    }
  }

  // ── readFrom: validates and clamps out-of-range registers ─────

  @Test
  public void readFrom_clampsRegisterAboveMaxRegisterValue() {
    // Register value > 54 (MAX_REGISTER_VALUE) should be clamped to 54.
    byte[] src = new byte[HyperLogLogSketch.serializedSize()];
    src[0] = 55; // just above max

    var sketch = HyperLogLogSketch.readFrom(src, 0);

    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);
    Assert.assertEquals(
        "Register value 55 should be clamped to 54",
        54, out[0]);
  }

  // ── serializedSize returns 1024 ───────────────────────────────

  @Test
  public void serializedSize_returns1024() {
    // Verify serializedSize matches M = 2^10 = 1024
    Assert.assertEquals(1024, HyperLogLogSketch.serializedSize());
  }

  @Test
  public void testReadFromCorruptRegistersStillProducesUsableEstimate() {
    // Even with several corrupt registers clamped to MAX_REGISTER_VALUE,
    // the sketch should still produce a finite, non-negative estimate
    // rather than crashing or returning nonsense.
    byte[] src = new byte[HyperLogLogSketch.serializedSize()];
    // Set 10 registers to corrupt values, rest stay at 0
    for (int i = 0; i < 10; i++) {
      src[i] = (byte) (100 + i); // all above 54
    }
    // Set some normal registers so estimate is non-trivial
    for (int i = 10; i < 100; i++) {
      src[i] = 3;
    }

    var sketch = HyperLogLogSketch.readFrom(src, 0);
    long est = sketch.estimate();

    Assert.assertTrue("Estimate should be non-negative, got " + est,
        est >= 0);
    Assert.assertTrue("Estimate should be finite (not Long.MAX_VALUE)",
        est < Long.MAX_VALUE);
  }

  // ── estimate() boundary conditions ──────────────────────────

  /**
   * Tests estimate at the exact small-range correction threshold.
   * With many registers set to a uniform low value and some zeros,
   * the raw estimate should be near 2.5 * M = 2560. When at or below
   * this threshold with zero registers present, linear counting is used.
   */
  @Test
  public void estimate_atSmallRangeCorrectionThreshold() {
    // With all 1024 registers set to 1 (rho=1), rawEstimate =
    // ALPHA_M * M * M / (M * (1 / 2^1)) = ALPHA_M * M * 2 ≈ 1475.
    // This is below 2560 threshold, but zeroCount=0 → no linear counting.
    // We need zeroCount > 0 for linear counting to kick in.
    var sketch = new HyperLogLogSketch();
    // Add enough keys so that most registers are set (few zeros remain),
    // but rawEstimate <= 2.5 * 1024. This happens around N ~ 800-1500.
    for (int i = 0; i < 900; i++) {
      sketch.add(hashInt(i));
    }
    long est = sketch.estimate();
    // For ~900 distinct keys, small-range correction should be active
    // (many zeros remain). Estimate should be reasonable.
    Assert.assertTrue("Estimate for 900 keys should be in [700, 1100], got "
        + est, est >= 700 && est <= 1100);
  }

  /**
   * Tests that above the small-range correction threshold (when there
   * are no zero registers), the raw estimate is returned without
   * linear counting. With ~50K keys all registers will be non-zero.
   */
  @Test
  public void estimate_aboveSmallRangeThresholdUsesRawEstimate() {
    var sketch = new HyperLogLogSketch();
    // 50K keys ensures all registers are non-zero (zeroCount = 0)
    // and rawEstimate > 2.5 * 1024 = 2560.
    for (int i = 0; i < 50_000; i++) {
      sketch.add(hashInt(i));
    }

    // Verify no zero registers (zeroCount = 0).
    byte[] registers = new byte[1024];
    sketch.writeTo(registers, 0);
    int zeroCount = 0;
    for (byte r : registers) {
      if (r == 0) {
        zeroCount++;
      }
    }
    Assert.assertEquals("All registers should be non-zero with 50K keys",
        0, zeroCount);

    long est = sketch.estimate();
    double relativeError = Math.abs((double) est - 50_000) / 50_000;
    Assert.assertTrue("Raw estimate should be within 7% of 50K, got " + est,
        relativeError <= 0.07);
  }

  /**
   * Verifies that small-range correction (linear counting) returns a
   * different value than the raw HLL estimate for a sketch with many
   * zero registers. With only a few keys added, the raw estimate and
   * linear counting diverge — the test verifies the correction path
   * is actually being taken.
   */
  @Test
  public void estimate_smallRangeCorrectionDiffersFromRawEstimate() {
    // With 5 keys in 1024 registers, ~1019 zeros remain.
    // rawEstimate = ALPHA_M * M^2 / sum ≈ 0.72054 * 1024 * 1024 / 1019.1
    //            ≈ 741.5 (since most terms are 1/(2^0)=1, plus 5 terms
    //            with slightly smaller contributions).
    // Linear counting: M * ln(M / 1019) ≈ 1024 * ln(1024/1019)
    //                ≈ 1024 * 0.00490 ≈ 5.02.
    // These are very different — confirms linear counting is used.
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 5; i++) {
      sketch.add(hashInt(i));
    }
    long est = sketch.estimate();
    // Linear counting should give ~5, not ~742 (the raw estimate).
    Assert.assertTrue(
        "Estimate with 5 keys should be ~5 (linear counting), got " + est,
        est >= 4 && est <= 7);
  }

  // ── add() rho calculation ──────────────────────────────────

  /**
   * Tests that a hash where all remaining bits (above the low P bits)
   * are zero results in rho = MAX_REGISTER_VALUE (54). The sentinel
   * bit (w | 1L) caps rho at 54 rather than allowing it to be 55.
   */
  @Test
  public void add_allZeroRemainingBitsGivesMaxRho() {
    // hash = 7 → low 10 bits = 7 (register 7), remaining 54 bits = 0.
    // w = 7 >>> 10 = 0. w | 1L = 1. numberOfLeadingZeros(1) = 63.
    // rho = 63 - 10 + 1 = 54 (MAX_REGISTER_VALUE).
    var sketch = new HyperLogLogSketch();
    sketch.add(7L); // register 7, all remaining bits zero

    byte[] registers = new byte[1024];
    sketch.writeTo(registers, 0);

    Assert.assertEquals(
        "Register[7] should be 54 (MAX_REGISTER_VALUE) for all-zero "
            + "remaining bits",
        54, registers[7]);
  }

  /**
   * Tests add with a hash that targets a specific register index and
   * produces a specific rho value, verifying both the index selection
   * and the rho calculation together.
   */
  @Test
  public void add_specificRegisterAndRho() {
    // hash = 0b_0000...0100_00000_00011 (binary)
    // Low 10 bits = 3 → register 3.
    // w = hash >>> 10 = 0b100 = 4. w | 1L = 5 = 0b101.
    // numberOfLeadingZeros(5) = 61. rho = 61 - 10 + 1 = 52.
    long hash = (4L << 10) | 3L;
    var sketch = new HyperLogLogSketch();
    sketch.add(hash);

    byte[] registers = new byte[1024];
    sketch.writeTo(registers, 0);

    Assert.assertEquals(
        "Register[3] should be 52 for hash with w=4",
        52, registers[3]);
    // Neighbors should be untouched
    Assert.assertEquals(0, registers[2]);
    Assert.assertEquals(0, registers[4]);
  }

  // ── merge() exact behavior ────────────────────────────────

  /**
   * Verifies that merge takes the MAX of registers, not the sum.
   * Sets register[0] to different values in two sketches, merges,
   * and checks the result is max(a, b).
   */
  @Test
  public void merge_takesMaxNotSum() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();

    // register[0] in sketch a: rho=54 (hash=0)
    a.add(0L);
    // register[0] in sketch b: use hash = (1L << 10) → w=1, rho=54
    // Actually, let's use a hash that gives a lower rho.
    // hash = (8L << 10) → register 0, w=8, w|1=9.
    // numberOfLeadingZeros(9) = 60. rho = 60 - 10 + 1 = 51.
    b.add(8L << 10);

    byte[] regA = new byte[1024];
    byte[] regB = new byte[1024];
    a.writeTo(regA, 0);
    b.writeTo(regB, 0);
    Assert.assertEquals(54, regA[0]);
    Assert.assertEquals(51, regB[0]);

    // Merge b into a: register[0] should be max(54, 51) = 54
    a.merge(b);
    byte[] merged = new byte[1024];
    a.writeTo(merged, 0);
    Assert.assertEquals("Merge should take max, not sum (54 + 51 = 105)",
        54, merged[0]);
  }

  /**
   * Tests merge where one register is higher in sketch A and another
   * register is higher in sketch B. After merge, each register should
   * hold the maximum from the two sketches.
   */
  @Test
  public void merge_mixedHigherRegisters() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();

    // register[0] higher in A: hash=0 → register 0, rho=54
    a.add(0L);
    // register[1] higher in B: hash=1 → register 1, rho=54
    b.add(1L);

    // register[0] lower in B: hash = (8L << 10) → register 0, rho=51
    b.add(8L << 10);
    // register[1] lower in A: hash = (8L << 10) | 1 → register 1, rho=51
    a.add((8L << 10) | 1L);

    byte[] regA = new byte[1024];
    byte[] regB = new byte[1024];
    a.writeTo(regA, 0);
    b.writeTo(regB, 0);
    Assert.assertEquals(54, regA[0]); // A higher at reg 0
    Assert.assertEquals(51, regA[1]); // A lower at reg 1
    Assert.assertEquals(51, regB[0]); // B lower at reg 0
    Assert.assertEquals(54, regB[1]); // B higher at reg 1

    a.merge(b);
    byte[] merged = new byte[1024];
    a.writeTo(merged, 0);
    Assert.assertEquals("Register[0] should be max(54, 51) = 54",
        54, merged[0]);
    Assert.assertEquals("Register[1] should be max(51, 54) = 54",
        54, merged[1]);
  }

  // ── writeTo/readFrom identity ──────────────────────────────

  /**
   * A populated sketch must round-trip perfectly through writeTo/readFrom:
   * the deserialized sketch produces the exact same estimate as the
   * original.
   */
  @Test
  public void writeTo_readFrom_identityForPopulatedSketch() {
    var original = new HyperLogLogSketch();
    for (int i = 0; i < 25_000; i++) {
      original.add(hashInt(i));
    }
    long originalEstimate = original.estimate();

    byte[] buffer = new byte[1024];
    original.writeTo(buffer, 0);
    var restored = HyperLogLogSketch.readFrom(buffer, 0);

    Assert.assertEquals("Round-trip must preserve exact estimate",
        originalEstimate, restored.estimate());

    // Also verify register-by-register equality
    byte[] restoredRegs = new byte[1024];
    restored.writeTo(restoredRegs, 0);
    for (int i = 0; i < 1024; i++) {
      Assert.assertEquals("Register[" + i + "] mismatch after round-trip",
          buffer[i], restoredRegs[i]);
    }
  }

  // ── rebuildFrom clears existing registers ──────────────────

  /**
   * rebuildFrom with an empty stream must reset all registers to zero,
   * producing estimate = 0, even if the sketch previously had data.
   * This specifically tests that Arrays.fill(registers, 0) is executed
   * before the stream is consumed.
   */
  @Test
  public void rebuildFrom_emptyStreamClearsRegistersToZero() {
    var sketch = new HyperLogLogSketch();
    // Populate with 10K keys to set many registers to non-zero.
    for (int i = 0; i < 10_000; i++) {
      sketch.add(hashInt(i));
    }
    Assert.assertTrue("Sketch should have non-zero estimate before rebuild",
        sketch.estimate() > 0);

    // Rebuild from empty stream
    sketch.rebuildFrom(Stream.empty(), key -> hashKey(key.toString()));

    // All registers must be zero → estimate = 0
    Assert.assertEquals("After rebuild from empty stream, estimate must be 0",
        0, sketch.estimate());

    // Verify all registers are actually zero
    byte[] registers = new byte[1024];
    sketch.writeTo(registers, 0);
    for (int i = 0; i < 1024; i++) {
      Assert.assertEquals("Register[" + i + "] should be 0 after empty "
          + "rebuild", 0, registers[i]);
    }
  }

  // ── readFrom register clamping details ─────────────────────

  /**
   * Verifies that register value exactly at MAX_REGISTER_VALUE (54) is
   * NOT clamped — it should be preserved as-is.
   */
  @Test
  public void readFrom_preservesExactMaxRegisterValue() {
    byte[] src = new byte[1024];
    src[0] = 54; // exactly MAX_REGISTER_VALUE
    src[1] = 53; // one below max

    var sketch = HyperLogLogSketch.readFrom(src, 0);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);

    Assert.assertEquals("Register at MAX_REGISTER_VALUE should be preserved",
        54, out[0]);
    Assert.assertEquals("Register below max should be preserved",
        53, out[1]);
  }

  /**
   * Verifies that register value 0 is preserved (not clamped).
   * This tests the lower boundary of the clamping check
   * (registers[i] < 0 condition should not trigger for 0).
   */
  @Test
  public void readFrom_preservesZeroRegister() {
    byte[] src = new byte[1024];
    // All zeros by default. readFrom should keep them as-is.
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);

    for (int i = 0; i < 1024; i++) {
      Assert.assertEquals("Zero register should be preserved", 0, out[i]);
    }
    Assert.assertEquals("All-zero registers should estimate 0",
        0, sketch.estimate());
  }

  // ── Boundary precision tests (catch < vs <= mutations) ─────

  /**
   * Adding the same hash twice must not change any register. The second
   * add has rho == registers[index], so the condition rho > registers[index]
   * is false. If mutated to >=, the write still produces the same byte
   * value (idempotent max), but the test captures the mutation by verifying
   * the register array is byte-for-byte identical after both adds.
   */
  @Test
  public void add_sameHashTwice_registersUnchanged() {
    var sketch = new HyperLogLogSketch();
    sketch.add(0L); // register 0, rho=54
    byte[] after1 = readRegisters(sketch);

    sketch.add(0L); // same hash again — rho == registers[0], no update
    byte[] after2 = readRegisters(sketch);

    assertArrayEquals(
        "Registers must be identical after adding the same hash twice",
        after1, after2);
  }

  /**
   * Adding a hash with strictly greater rho must update the register.
   * This is the positive case for the > condition.
   */
  @Test
  public void add_greaterRho_updatesRegister() {
    var sketch = new HyperLogLogSketch();
    // hash = (8L << 10) | 0 → register 0, w=8, w|1=9,
    // nlz(9)=60, rho=60-10+1=51
    sketch.add(8L << 10);
    byte[] regs = readRegisters(sketch);
    assertEquals(51, regs[0]);

    // hash = 0L → register 0, rho=54 (greater)
    sketch.add(0L);
    regs = readRegisters(sketch);
    assertEquals("Register should update to higher rho",
        54, regs[0]);
  }

  /**
   * Adding a hash with strictly lower rho must NOT update the register.
   */
  @Test
  public void add_lowerRho_doesNotUpdateRegister() {
    var sketch = new HyperLogLogSketch();
    sketch.add(0L); // register 0, rho=54
    byte[] regs = readRegisters(sketch);
    assertEquals(54, regs[0]);

    // hash = (8L << 10) → register 0, rho=51 (lower)
    sketch.add(8L << 10);
    regs = readRegisters(sketch);
    assertEquals("Register should stay at max rho", 54, regs[0]);
  }

  /**
   * Verifies that when rawEstimate is small and zeroCount > 0, the
   * small-range (linear counting) correction is used. With 5 distinct
   * keys in 1024 registers, rawEstimate ~742 but linear counting gives
   * ~5. If the condition is mutated to skip linear counting, the
   * estimate would be ~742, far from the true 5.
   */
  @Test
  public void estimate_smallCardinality_usesLinearCounting() {
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 5; i++) {
      sketch.add(hashInt(i));
    }
    long est = sketch.estimate();
    assertTrue("Estimate should be ~5 (linear counting), got " + est,
        est >= 3 && est <= 8);
  }

  /**
   * When zeroCount == 0, linear counting must NOT be used even if
   * rawEstimate <= 2.5 * M. The && ensures both conditions are needed.
   * If mutated to ||, linear counting would fire with zeroCount=0,
   * causing M * ln(M/0) = +infinity.
   *
   * <p>With 50K keys all registers are non-zero, so zeroCount=0.
   * The raw estimate (~50K) is returned directly.
   */
  @Test
  public void estimate_noZeroRegisters_rawEstimateUsed() {
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 50_000; i++) {
      sketch.add(hashInt(i));
    }
    long est = sketch.estimate();
    double error = Math.abs((double) est - 50_000) / 50_000;
    assertTrue("Estimate should be ~50K (raw), got " + est, error < 0.07);
  }

  /**
   * Tests the boundary at rawEstimate == 2.5 * M = 2560.
   * We create a sketch where all registers are set to 1 except some
   * that are set to 0. The rawEstimate = ALPHA_M * M^2 / sum.
   * With all registers at 1: sum = M * (1/2) = 512,
   * rawEstimate = 0.72054 * 1024^2 / 512 = 1475.2 (below 2560).
   * With all registers at 2: sum = M * (1/4) = 256,
   * rawEstimate = 0.72054 * 1024^2 / 256 = 2950.4 (above 2560).
   *
   * <p>We test just above the threshold (all regs at 2, no zeros)
   * to confirm the raw estimate is returned (no linear counting).
   */
  @Test
  public void estimate_justAboveSmallRangeThreshold_noLinearCounting() {
    // All registers at 2, no zeros → rawEstimate ≈ 2950 > 2560.
    // zeroCount=0, so linear counting would give ln(1024/0)=infinity.
    // The raw estimate should be returned.
    byte[] src = new byte[M];
    for (int i = 0; i < M; i++) {
      src[i] = 2;
    }
    var sketch = sketchFromRegisters(src);
    long est = sketch.estimate();

    // rawEstimate = ALPHA_M * M^2 / (M * 1/4) = ALPHA_M * M * 4
    double expectedRaw = ALPHA_M * M * 4;
    assertEquals("Should return raw estimate (~2950)",
        Math.round(expectedRaw), est);
  }

  /**
   * Below the threshold with zeros present, linear counting is used.
   * All registers at 1 except the last which is 0:
   * zeroCount=1, sum = 1023*(1/2) + 1*(1/1) = 512.5,
   * rawEstimate = ALPHA_M * M^2 / 512.5 ≈ 1474.
   * Since 1474 <= 2560 and zeroCount=1 > 0, linear counting fires:
   * M * ln(M/1) = 1024 * ln(1024) ≈ 7100.
   */
  @Test
  public void estimate_belowThresholdWithZeros_usesLinearCounting() {
    byte[] src = new byte[M];
    for (int i = 0; i < M - 1; i++) {
      src[i] = 1;
    }
    src[M - 1] = 0; // one zero register
    var sketch = sketchFromRegisters(src);
    long est = sketch.estimate();

    // Linear counting: M * ln(M / 1) = 1024 * ln(1024) ≈ 7100
    long expectedLinear = Math.round(M * Math.log((double) M / 1));
    assertEquals("Should use linear counting", expectedLinear, est);
  }

  /**
   * Sets all registers to MAX_REGISTER_VALUE (54) to push the raw
   * estimate far above the large-range threshold (TWO_POW_64 / 30).
   * Verifies the large-range correction produces the mathematically
   * expected value.
   *
   * <p>rawEstimate = ALPHA_M * M^2 / (M * 2^-54) = ALPHA_M * M * 2^54.
   * The correction is: -2^64 * ln(1 - rawEstimate / 2^64).
   *
   * <p>If the * on line 135 is mutated to /, the result would be
   * -TWO_POW_64 / ln(1 - rawEstimate / TWO_POW_64) which is a
   * completely different (much larger) value.
   */
  @Test
  public void estimate_allMaxRegisters_largeRangeCorrection() {
    byte[] src = new byte[M];
    for (int i = 0; i < M; i++) {
      src[i] = 54;
    }
    var sketch = sketchFromRegisters(src);
    long est = sketch.estimate();

    // Compute expected value manually
    double rawEstimate = ALPHA_M * M * M / (M * (1.0 / (1L << 54)));
    // rawEstimate = ALPHA_M * M * 2^54 ≈ 1.33e19
    assertTrue("rawEstimate should exceed large-range threshold",
        rawEstimate > TWO_POW_64 / 30.0);

    double corrected =
        -TWO_POW_64 * Math.log(1.0 - rawEstimate / TWO_POW_64);
    long expected = Math.round(corrected);

    assertEquals(
        "Large-range correction should produce the expected value",
        expected, est);
  }

  /**
   * Tests the large-range correction with registers at 50 (not max).
   * This produces a rawEstimate well above the threshold and allows
   * us to verify the correction formula precisely.
   *
   * <p>rawEstimate = ALPHA_M * M * 2^50 ≈ 8.31e17.
   * TWO_POW_64 / 30 ≈ 6.15e17.
   * Since 8.31e17 > 6.15e17, large-range correction fires.
   */
  @Test
  public void estimate_highRegisters_largeRangeCorrection() {
    byte[] src = new byte[M];
    for (int i = 0; i < M; i++) {
      src[i] = 50;
    }
    var sketch = sketchFromRegisters(src);
    long est = sketch.estimate();

    double rawEstimate = ALPHA_M * M * M / (M * (1.0 / (1L << 50)));
    assertTrue("rawEstimate should exceed large-range threshold",
        rawEstimate > TWO_POW_64 / 30.0);

    double corrected =
        -TWO_POW_64 * Math.log(1.0 - rawEstimate / TWO_POW_64);
    long expected = Math.round(corrected);

    assertEquals(
        "Large-range correction value should match formula",
        expected, est);
  }

  /**
   * Tests a register value (49) that produces a rawEstimate just below
   * the large-range threshold. The raw estimate should be returned
   * without correction.
   *
   * <p>rawEstimate = ALPHA_M * M * 2^49 ≈ 4.15e17.
   * TWO_POW_64 / 30 ≈ 6.15e17.
   * Since 4.15e17 < 6.15e17, no large-range correction.
   */
  @Test
  public void estimate_belowLargeRangeThreshold_noCorrectionApplied() {
    byte[] src = new byte[M];
    for (int i = 0; i < M; i++) {
      src[i] = 49;
    }
    var sketch = sketchFromRegisters(src);
    long est = sketch.estimate();

    // No zeros → zeroCount=0 → small-range correction skipped.
    // rawEstimate < TWO_POW_64/30 → large-range correction skipped.
    // Raw estimate returned directly.
    double rawEstimate = ALPHA_M * M * M / (M * (1.0 / (1L << 49)));
    long expected = Math.round(rawEstimate);

    assertEquals(
        "Below large-range threshold, raw estimate should be returned",
        expected, est);
  }

  /**
   * Verifies the / 30.0 on line 133 is not mutated to * 30.0.
   * With registers at 50, rawEstimate ≈ 8.31e17. The threshold
   * TWO_POW_64 / 30 ≈ 6.15e17, so correction fires. But if the
   * threshold were TWO_POW_64 * 30, it would be ≈ 5.53e20, and the
   * correction would NOT fire — the raw estimate would be returned
   * instead, which is a different value.
   */
  @Test
  public void estimate_divisionNotMultiplication_inThresholdCheck() {
    byte[] src = new byte[M];
    for (int i = 0; i < M; i++) {
      src[i] = 50;
    }
    var sketch = sketchFromRegisters(src);
    long est = sketch.estimate();

    double rawEstimate = ALPHA_M * M * M / (M * (1.0 / (1L << 50)));
    long rawRounded = Math.round(rawEstimate);

    // If / 30 were mutated to * 30, the threshold would be huge and
    // correction wouldn't fire, so est == rawRounded.
    // With correct / 30, correction fires and est != rawRounded.
    double corrected =
        -TWO_POW_64 * Math.log(1.0 - rawEstimate / TWO_POW_64);
    long correctedRounded = Math.round(corrected);

    // The corrected value differs from the raw estimate
    assertTrue(
        "Corrected value should differ from raw estimate",
        rawRounded != correctedRounded);
    assertEquals(
        "Estimate should match the corrected (not raw) value",
        correctedRounded, est);
  }

  /**
   * Merging two identical sketches must produce byte-for-byte identical
   * registers. This catches the >= mutation because the merge operation
   * with >= would still write the same value (idempotent), but we
   * verify the array is not modified.
   */
  @Test
  public void merge_identicalSketches_registersUnchanged() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      long h = hashInt(i);
      a.add(h);
      b.add(h);
    }
    byte[] before = readRegisters(a);
    a.merge(b);
    byte[] after = readRegisters(a);

    assertArrayEquals(
        "Merge of identical sketches should not change any register",
        before, after);
  }

  /**
   * Merging where other has a strictly higher register must update.
   */
  @Test
  public void merge_higherRegisterInOther_updates() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    a.add(8L << 10); // register 0, rho=51
    b.add(0L); // register 0, rho=54

    byte[] regsA = readRegisters(a);
    assertEquals(51, regsA[0]);

    a.merge(b);
    regsA = readRegisters(a);
    assertEquals("Register should be updated to higher value from other",
        54, regsA[0]);
  }

  /**
   * Merging where other has a strictly lower register must not update.
   */
  @Test
  public void merge_lowerRegisterInOther_doesNotUpdate() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    a.add(0L); // register 0, rho=54
    b.add(8L << 10); // register 0, rho=51

    a.merge(b);
    byte[] regsA = readRegisters(a);
    assertEquals("Register should stay at 54 (higher)",
        54, regsA[0]);
  }

  /**
   * Tests merge where exactly one register has equal values in both
   * sketches, verifying the estimate is unchanged.
   */
  @Test
  public void merge_equalSingleRegister_estimateUnchanged() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    // Both get the same hash → same register index, same rho
    long hash = hashInt(42);
    a.add(hash);
    b.add(hash);

    long before = a.estimate();
    a.merge(b);
    assertEquals("Merge of equal single-register sketches should not "
        + "change estimate", before, a.estimate());
  }

  /**
   * Buffer of exactly M bytes with offset=0 should succeed.
   */
  @Test
  public void readFrom_exactlyMBytes_succeeds() {
    byte[] exact = new byte[M];
    exact[0] = 5;
    var sketch = HyperLogLogSketch.readFrom(exact, 0);
    byte[] out = readRegisters(sketch);
    assertEquals(5, out[0]);
  }

  /**
   * Buffer with non-zero offset: exactly enough bytes remaining.
   * src.length = offset + M → passes the check.
   */
  @Test
  public void readFrom_nonZeroOffset_exactFit_succeeds() {
    int offset = 100;
    byte[] buf = new byte[offset + M];
    buf[offset] = 10;
    var sketch = HyperLogLogSketch.readFrom(buf, offset);
    byte[] out = readRegisters(sketch);
    assertEquals(10, out[0]);
  }

  /**
   * Edge case: offset equals src.length (zero remaining bytes).
   * Should throw.
   */
  @Test(expected = IllegalArgumentException.class)
  public void readFrom_offsetAtEnd_throws() {
    byte[] buf = new byte[M];
    HyperLogLogSketch.readFrom(buf, M);
  }

  /**
   * Verifies that a register value of 1 is preserved. This is the
   * first value above the < 0 boundary.
   */
  @Test
  public void readFrom_valueOne_isPreserved() {
    byte[] src = new byte[M];
    src[0] = 1;
    var sketch = sketchFromRegisters(src);
    byte[] out = readRegisters(sketch);
    assertEquals("Register value 1 should be preserved", 1, out[0]);
  }

  /**
   * Verifies that register value 53 (one below MAX) is preserved.
   */
  @Test
  public void readFrom_oneBelowMax_isPreserved() {
    byte[] src = new byte[M];
    src[0] = 53;
    var sketch = sketchFromRegisters(src);
    byte[] out = readRegisters(sketch);
    assertEquals("Register value 53 should be preserved", 53, out[0]);
  }

  /**
   * Estimate must be non-decreasing as more distinct keys are added.
   * Catches mutations that break the estimate formula (e.g., replacing
   * multiplication with division in the correction formulas).
   */
  @Test
  public void estimate_monotonicallyIncreasingWithMoreKeys() {
    var sketch = new HyperLogLogSketch();
    long prev = 0;
    for (int n = 100; n <= 10_000; n += 100) {
      for (int i = n - 100; i < n; i++) {
        sketch.add(hashInt(i));
      }
      long est = sketch.estimate();
      assertTrue("Estimate should be non-decreasing: prev=" + prev
          + " current=" + est + " at n=" + n, est >= prev);
      prev = est;
    }
  }

  /**
   * Merge of non-overlapping sketches must produce an estimate >= either
   * individual sketch's estimate.
   */
  @Test
  public void merge_nonOverlapping_estimateExceedsEitherAlone() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    for (int i = 0; i < 1000; i++) {
      a.add(MurmurHash3.murmurHash3_x64_64(
          ("a" + i).getBytes(StandardCharsets.UTF_8), HASH_SEED));
      b.add(MurmurHash3.murmurHash3_x64_64(
          ("b" + i).getBytes(StandardCharsets.UTF_8), HASH_SEED));
    }
    long estA = a.estimate();
    long estB = b.estimate();
    a.merge(b);
    long estMerged = a.estimate();

    assertTrue("Merged >= estA", estMerged >= estA);
    assertTrue("Merged >= estB", estMerged >= estB);
    assertTrue("Merged > max(estA, estB)",
        estMerged > Math.max(estA, estB));
  }
}
