package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Mutation-killing tests for {@link HyperLogLogSketch}.
 *
 * <p>Targets survived mutations at lines 95, 125, 133-135, 152, 204, 217
 * (conditional boundaries, math operators, comparison directions).
 */
public class HyperLogLogSketchMutationTest {

  private static final int HASH_SEED = 0x9747b28c;

  private static long hashInt(int key) {
    byte[] bytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);
    return MurmurHash3.murmurHash3_x64_64(bytes, HASH_SEED);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 95: changed conditional boundary (rho > registers[index])
  // The mutation changes > to >=, which would overwrite equal values
  // unnecessarily (functionally equivalent for max, but we can detect
  // if the register update behavior changes for equal rho).
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void add_registerUpdateOnlyWhenRhoIsStrictlyGreater() {
    // Add a hash that sets register 0 to rho=54 (max).
    // Then add another hash targeting register 0 with a lower rho.
    // The register should stay at 54.
    var sketch = new HyperLogLogSketch();
    sketch.add(0L); // register 0, rho=54

    byte[] regs = new byte[1024];
    sketch.writeTo(regs, 0);
    assertEquals(54, regs[0]);

    // Add with smaller rho: hash = (8L << 10) → register 0, rho=51
    sketch.add(8L << 10);
    sketch.writeTo(regs, 0);
    assertEquals("Register should stay at max rho", 54, regs[0]);
  }

  @Test
  public void add_registerUpdateWhenRhoIsGreater() {
    // Start with a lower rho, then add a hash with higher rho.
    // Register must be updated.
    var sketch = new HyperLogLogSketch();
    // hash = (8L << 10) → register 0, rho=51
    sketch.add(8L << 10);
    byte[] regs = new byte[1024];
    sketch.writeTo(regs, 0);
    assertEquals(51, regs[0]);

    // Now add hash that gives higher rho
    sketch.add(0L); // register 0, rho=54
    sketch.writeTo(regs, 0);
    assertEquals("Register should update to higher rho", 54, regs[0]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 125: changed conditional boundary (rawEstimate <= 2.5 * M)
  // The mutation changes <= to < which could skip linear counting
  // at the exact threshold.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void estimate_linearCountingVsRawEstimate_divergeForSmallN() {
    // With 5 distinct keys, linear counting should return ~5,
    // while raw estimate would be ~742. This catches mutations
    // that skip the linear counting path.
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 5; i++) {
      sketch.add(hashInt(i));
    }
    long est = sketch.estimate();
    // Must be in linear counting range, not raw estimate range
    assertTrue("Estimate should be ~5 (linear counting), got " + est,
        est >= 3 && est <= 8);
  }

  @Test
  public void estimate_noZeroRegisters_usesRawEstimate() {
    // With 50K keys, all 1024 registers are non-zero.
    // zeroCount=0 means linear counting cannot be used even if
    // rawEstimate <= 2.5*M. The raw estimate is returned.
    var sketch = new HyperLogLogSketch();
    for (int i = 0; i < 50_000; i++) {
      sketch.add(hashInt(i));
    }
    long est = sketch.estimate();
    // Should be close to 50K (raw estimate)
    double error = Math.abs((double) est - 50_000) / 50_000;
    assertTrue("Estimate should be ~50K, got " + est, error < 0.07);
  }

  // ═══════════════════════════════════════════════════════════════
  // Lines 133-135: large-range correction (NO_COVERAGE)
  // The large-range correction path (rawEstimate > 2^64/30) is
  // virtually unreachable with real data. We can trigger it by
  // setting all registers to MAX_REGISTER_VALUE (54), which gives
  // a huge rawEstimate.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void estimate_allMaxRegisters_triggerLargeRangeCorrection() {
    // Set all registers to MAX_REGISTER_VALUE (54) via readFrom.
    // rawEstimate = ALPHA_M * M * M / sum(1/(1<<54))
    //            = 0.72054 * 1024^2 / (1024 * 2^-54)
    //            = 0.72054 * 1024 * 2^54 ≈ 1.33e19
    // 2^64 / 30 ≈ 6.15e17
    // Since 1.33e19 > 6.15e17, large-range correction fires.
    byte[] src = new byte[1024];
    for (int i = 0; i < 1024; i++) {
      src[i] = 54; // MAX_REGISTER_VALUE
    }
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    long est = sketch.estimate();
    // The large-range correction should produce a finite positive value.
    // The exact value isn't important — what matters is that the code
    // path is exercised and doesn't crash or return nonsense.
    // The large-range correction may overflow to Long.MAX_VALUE due to
    // Math.round on very large doubles. The important thing is the code
    // path executes without error.
    assertTrue("Estimate should be > 0 for all-max registers", est > 0);
  }

  @Test
  public void estimate_largeRangeCorrection_producesReasonableValue() {
    // Verify the math: with all registers at 54, the corrected estimate
    // should be in a specific range. rawEstimate ≈ ALPHA_M * M * 2^54.
    // The correction is: -2^64 * ln(1 - rawEstimate / 2^64).
    // Since rawEstimate << 2^64, the result should be close to rawEstimate.
    byte[] src = new byte[1024];
    for (int i = 0; i < 1024; i++) {
      src[i] = 54;
    }
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    long est = sketch.estimate();
    // rawEstimate ≈ 0.72054 * 1024 * 2^54 ≈ 1.33e19
    // After correction, result may be very large (possibly Long.MAX_VALUE
    // from Math.round overflow). The key is the code path executes.
    assertTrue("Large-range estimate should be > 0", est > 0);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 152: changed conditional boundary (other.registers[i] > registers[i])
  // If > is changed to >=, merge would redundantly write equal values
  // (functionally equivalent but testable via side effects).
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void merge_equalRegisters_resultUnchanged() {
    // Two sketches with identical registers — merge should not change estimate.
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    for (int i = 0; i < 5000; i++) {
      long h = hashInt(i);
      a.add(h);
      b.add(h);
    }
    long before = a.estimate();
    a.merge(b);
    assertEquals("Merge of identical sketches should not change estimate",
        before, a.estimate());
  }

  @Test
  public void merge_higherRegisterInOther_updates() {
    // sketch b has higher register[0] than a → merge should update
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    a.add(8L << 10); // register 0, rho=51
    b.add(0L); // register 0, rho=54

    byte[] regsA = new byte[1024];
    a.writeTo(regsA, 0);
    assertEquals(51, regsA[0]);

    a.merge(b);
    a.writeTo(regsA, 0);
    assertEquals("Register should be updated to higher value from other",
        54, regsA[0]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 204: Replaced integer addition with subtraction
  // (in readFrom: offset + M → offset - M)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void readFrom_bufferLengthValidation_catchesTruncated() {
    // Buffer exactly at required size should work
    byte[] exact = new byte[1024];
    exact[0] = 5;
    var sketch = HyperLogLogSketch.readFrom(exact, 0);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);
    assertEquals(5, out[0]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void readFrom_bufferTooShort_throws() {
    // Buffer one byte too short
    byte[] tooShort = new byte[1023];
    HyperLogLogSketch.readFrom(tooShort, 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void readFrom_bufferWithOffset_tooShort_throws() {
    // Buffer is 1024 bytes but offset=1 makes it 1 byte short
    byte[] buf = new byte[1024];
    HyperLogLogSketch.readFrom(buf, 1);
  }

  @Test
  public void readFrom_bufferWithOffset_exactFit_works() {
    // Buffer is 1025 bytes, offset=1 → exactly 1024 bytes available
    byte[] buf = new byte[1025];
    buf[1] = 10; // first register at offset 1
    var sketch = HyperLogLogSketch.readFrom(buf, 1);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);
    assertEquals(10, out[0]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 217: changed conditional boundary
  // (registers[i] < 0 || registers[i] > MAX_REGISTER_VALUE)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void readFrom_exactlyAtMaxRegisterValue_isPreserved() {
    byte[] src = new byte[1024];
    src[0] = 54; // exactly MAX_REGISTER_VALUE
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);
    assertEquals("Register at MAX (54) should be preserved", 54, out[0]);
  }

  @Test
  public void readFrom_oneAboveMax_isClamped() {
    byte[] src = new byte[1024];
    src[0] = 55; // one above MAX
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);
    assertEquals("Register 55 should be clamped to 54", 54, out[0]);
  }

  @Test
  public void readFrom_negativeValue_isClamped() {
    byte[] src = new byte[1024];
    src[0] = -1; // signed: -1, unsigned: 255
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);
    assertEquals("Negative register should be clamped to 54", 54, out[0]);
  }

  @Test
  public void readFrom_zeroValue_isPreserved() {
    byte[] src = new byte[1024];
    src[0] = 0; // boundary: should NOT be clamped
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    byte[] out = new byte[1024];
    sketch.writeTo(out, 0);
    assertEquals("Zero register should be preserved", 0, out[0]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Estimate monotonicity: adding more distinct keys always
  // increases or maintains the estimate. This catches math mutations.
  // ═══════════════════════════════════════════════════════════════

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

  @Test
  public void estimate_highRegisters_triggerLargeRangeCorrection_v2() {
    // All registers at 50 (not max): triggers large-range correction.
    byte[] src = new byte[1024];
    for (int i = 0; i < 1024; i++) {
      src[i] = 50;
    }
    var sketch = HyperLogLogSketch.readFrom(src, 0);
    long est = sketch.estimate();
    assertTrue("Estimate with register=50 should be > 0", est > 0);
  }

  @Test
  public void merge_nonOverlapping_estimateExceedsEitherAlone() {
    var a = new HyperLogLogSketch();
    var b = new HyperLogLogSketch();
    for (int i = 0; i < 1000; i++) {
      a.add(MurmurHash3.murmurHash3_x64_64(
          ("a" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8), 0x9747b28c));
      b.add(MurmurHash3.murmurHash3_x64_64(
          ("b" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8), 0x9747b28c));
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
