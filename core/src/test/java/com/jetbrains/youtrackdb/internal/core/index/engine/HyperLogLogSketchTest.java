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

    var ab = a.clone();
    ab.merge(b);

    var ba = b.clone();
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

    var merged = a.clone();
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
    var merged = a.clone();
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

    var aClone = a.clone();
    a.merge(aClone);

    Assert.assertEquals(
        "Self-merge should not change estimate",
        estimateBefore, a.estimate());
  }

  // ── Clone independence ───────────────────────────────────────

  @Test
  public void testCloneIndependence() {
    // Modify original after clone → clone's estimate unchanged
    var original = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      original.add(hashInt(i));
    }

    var cloned = original.clone();
    long clonedEstimate = cloned.estimate();

    // Modify original by adding many more keys
    for (int i = 5000; i < 20000; i++) {
      original.add(hashInt(i));
    }

    Assert.assertEquals(
        "Clone should be independent of original modifications",
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
}
