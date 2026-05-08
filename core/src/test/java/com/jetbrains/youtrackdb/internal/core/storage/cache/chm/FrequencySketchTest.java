package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link FrequencySketch} — a 4-bit CountMinSketch with periodic aging.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code ensureCapacity()} — lazy initialisation and no-op when already large enough.</li>
 *   <li>{@code frequency()} — returns 0 before any {@code increment}, and increases after.</li>
 *   <li>{@code increment()} — raises frequency for the incremented key and saturates at 15.</li>
 *   <li>{@code reset()} (via {@code increment()} driving {@code size == sampleSize}) — halves
 *       all counters after the sample window is exhausted (aging).</li>
 * </ul>
 *
 * <p>{@link FrequencySketch} is package-private; this test must live in
 * {@code com.jetbrains.youtrackdb.internal.core.storage.cache.chm}.
 */
public class FrequencySketchTest {

  // ---- ensureCapacity ----

  /**
   * Calling {@code ensureCapacity(0)} must initialise the table to length 1 (minimum) and must
   * not throw. The table mask is {@code Math.max(0, 1-1) = 0}, so any subsequent
   * {@code frequency(hash)} returns 0 without index-out-of-bounds.
   */
  @Test
  public void testEnsureCapacityZeroIsNoOp() {
    final var sketch = new FrequencySketch();
    // ensureCapacity(0) sets table to length 1 and sampleSize to 10. Must not throw.
    sketch.ensureCapacity(0);
    // frequency of any key on a size-1 table must be 0.
    Assert.assertEquals("frequency must be 0 before any increment (zero capacity)",
        0, sketch.frequency(42));
  }

  /**
   * {@code ensureCapacity} with a positive capacity must allocate a table large enough to hold
   * frequency counts. The capacity is rounded up to the next power of two; {@code frequency}
   * must return 0 for any key that has not been incremented.
   */
  @Test
  public void testEnsureCapacityAllocatesTable() {
    final var sketch = new FrequencySketch();
    sketch.ensureCapacity(100);
    // Key 0 was never incremented; frequency must be 0.
    Assert.assertEquals("frequency must be 0 for any un-incremented key", 0, sketch.frequency(0));
    Assert.assertEquals("frequency must be 0 for any un-incremented key (different hash)",
        0, sketch.frequency(12345));
  }

  /**
   * A second {@code ensureCapacity(n)} call with the same (or smaller) n must be a no-op —
   * the sketch must still function correctly and return the same frequencies as before.
   */
  @Test
  public void testEnsureCapacityIsNoOpWhenAlreadyLargeEnough() {
    final var sketch = new FrequencySketch();
    sketch.ensureCapacity(64);
    sketch.increment(7);
    int freqBefore = sketch.frequency(7);
    Assert.assertTrue("frequency must be positive after one increment", freqBefore > 0);

    // Re-calling with the same size must not reset counts.
    sketch.ensureCapacity(64);
    int freqAfter = sketch.frequency(7);
    Assert.assertEquals(
        "ensureCapacity with same size must be a no-op (frequency unchanged)",
        freqBefore, freqAfter);
  }

  // ---- frequency before any increment ----

  /**
   * Before any {@code increment} call, {@code frequency(hash)} must return 0 for any hash,
   * regardless of the capacity.
   */
  @Test
  public void testFrequencyZeroBeforeAnyIncrement() {
    final var sketch = new FrequencySketch();
    sketch.ensureCapacity(256);
    for (int hash : new int[] {0, 1, -1, 99, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
      Assert.assertEquals("frequency must be 0 before increment for hash=" + hash,
          0, sketch.frequency(hash));
    }
  }

  // ---- increment and frequency ----

  /**
   * After incrementing a key once, its frequency must be at least 1. The CountMinSketch
   * overestimates (never underestimates), so frequency is guaranteed to be ≥ 1 and ≤ 15.
   */
  @Test
  public void testFrequencyPositiveAfterOneIncrement() {
    final var sketch = new FrequencySketch();
    sketch.ensureCapacity(256);
    final int hash = 55;
    sketch.increment(hash);
    final int freq = sketch.frequency(hash);
    Assert.assertTrue(
        "frequency must be ≥ 1 after one increment, was " + freq, freq >= 1);
    Assert.assertTrue(
        "frequency must be ≤ 15 (4-bit counter max), was " + freq, freq <= 15);
  }

  /**
   * Incrementing the same key repeatedly must increase its estimated frequency up to the maximum
   * (15). After 15 increments the frequency must reach 15 and stay there on further increments
   * (saturation / cap).
   */
  @Test
  public void testFrequencyIncreasesWithRepeatedIncrements() {
    final var sketch = new FrequencySketch();
    // Large capacity so sampleSize >> 16 and we don't trigger reset() prematurely.
    sketch.ensureCapacity(1_000_000);
    final int hash = 123;

    // Increment 15 times — minimum frequency should reach 15 (all 4 counters saturated).
    for (int i = 0; i < 15; i++) {
      sketch.increment(hash);
    }
    final int freqAt15 = sketch.frequency(hash);
    Assert.assertEquals("Frequency must saturate at 15 after 15 increments", 15, freqAt15);

    // One more increment — must not go above 15 (saturation).
    sketch.increment(hash);
    Assert.assertEquals(
        "Frequency must stay at 15 after increment at saturation", 15, sketch.frequency(hash));
  }

  /**
   * Two different keys have independent frequency counters. Incrementing key A must not raise
   * the frequency of key B (unless a hash collision occurs — acceptable, since CountMinSketch
   * only overestimates; we use hashes far apart to minimise collision probability).
   */
  @Test
  public void testFrequencyIsKeyIndependent() {
    final var sketch = new FrequencySketch();
    sketch.ensureCapacity(1024);
    final int hashA = 1000003;
    final int hashB = 999983; // distinct prime → low collision probability

    sketch.increment(hashA);
    final int freqA = sketch.frequency(hashA);
    final int freqB = sketch.frequency(hashB);

    Assert.assertTrue("frequency(A) must be ≥ 1 after increment(A)", freqA >= 1);
    // freqB might be 0 or >0 depending on hash collision; we just assert it doesn't exceed freqA.
    Assert.assertTrue(
        "frequency(B) must not exceed frequency(A) due to overcount from A alone",
        freqB <= freqA);
  }

  // ---- reset (aging) triggered by sampleSize ----

  /**
   * After exactly {@code sampleSize = 10 * capacity} distinct increments the sketch halves all
   * counters (the {@code reset()} aging step). We verify that a key incremented to frequency 15
   * drops to approximately 7–8 after a reset. The exact post-reset value is
   * {@code (15 >> 1) = 7} for a fully-saturated counter. This exercises the {@code reset()}
   * private method that {@code increment()} calls when {@code ++size == sampleSize}.
   */
  @Test
  public void testResetHalvesCountersAfterSampleWindow() {
    // Use a small capacity so sampleSize = 10 * maximumSize is reachable in a tight loop.
    final int maxSize = 4;
    final var sketch = new FrequencySketch();
    sketch.ensureCapacity(maxSize);
    // sampleSize = 10 * 4 = 40 for maximumSize=4.

    final int targetHash = 17;

    // First saturate targetHash to frequency 15 (needs at most 15 increments of the same key;
    // but incrementing updates 'size' and could trigger reset before saturation if maxSize is
    // too small. With maxSize=4, sampleSize=40, and 15 increments of ONE key, size only reaches
    // 15 < 40 — no premature reset). Verify frequency == 15 before triggering reset.
    for (int i = 0; i < 15; i++) {
      sketch.increment(targetHash);
    }
    Assert.assertEquals("frequency must be 15 before reset", 15, sketch.frequency(targetHash));

    // Now drive 'size' up to sampleSize (40) by incrementing a high-entropy set of distinct
    // keys. We need size to reach 40 from 15, so 25 more increments of sufficiently-distinct
    // keys (each adds 1 to size if at least one counter was not already at 15).
    for (int i = 0; i < 30; i++) {
      // Use large spread keys unlikely to hash to the same slot as targetHash.
      sketch.increment(i * 100_003 + 37);
    }

    // After reset(), the targetHash counter was halved: floor(15/2) = 7.
    final int freqAfterReset = sketch.frequency(targetHash);
    Assert.assertTrue(
        "After reset(), frequency of the saturated key must be ≤ 8 (halved from 15), was "
            + freqAfterReset,
        freqAfterReset <= 8);
    Assert.assertTrue(
        "After reset(), frequency of the saturated key must be ≥ 1 (not zeroed), was "
            + freqAfterReset,
        freqAfterReset >= 1);
  }
}
