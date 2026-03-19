package com.jetbrains.youtrackdb.internal.common.hash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Mutation-killing tests for {@link MurmurHash3} targeting pitest mutations
 * in the final mixing steps of the long and CharSequence overloads.
 *
 * <p>Pins exact hash output values for seed {@code 0x9747b28c} (the seed used
 * by the HyperLogLog hash function). Any mutation to the final mixing steps
 * will produce different output and fail these tests.
 *
 * <p>Complements {@link MurmurHash3Test} which pins values for seed 0.
 */
public class MurmurHash3MutationTest {

  // The HLL hash seed used throughout the histogram subsystem.
  private static final int SEED = 0x9747b28c;

  // ═══════════════════════════════════════════════════════════════
  // Long overload: final mixing (h1 += h2)
  // If + is mutated to -, the output changes for all inputs.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void longHash_zero_pinnedValue() {
    assertEquals(-3215302150364801703L,
        MurmurHash3.murmurHash3_x64_64(0L, SEED));
  }

  @Test
  public void longHash_one_pinnedValue() {
    assertEquals(-517320654685323774L,
        MurmurHash3.murmurHash3_x64_64(1L, SEED));
  }

  // ═══════════════════════════════════════════════════════════════
  // CharSequence overload: final mixing (h1 += h2)
  // If + is mutated to -, the output changes for all inputs.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void charSequenceHash_empty_pinnedValue() {
    assertEquals(-4582578146627449948L,
        MurmurHash3.murmurHash3_x64_64("", SEED));
  }

  @Test
  public void charSequenceHash_singleChar_pinnedValue() {
    assertEquals(-8789867018859915846L,
        MurmurHash3.murmurHash3_x64_64("a", SEED));
  }

  @Test
  public void charSequenceHash_multiChar_pinnedValue() {
    assertEquals(3499968064982600993L,
        MurmurHash3.murmurHash3_x64_64("hello", SEED));
  }

  @Test
  public void longHash_finalizationStepProducesDistinctOutputs() {
    // Kills MathMutator: h1 += h2 → h1 -= h2 in long overload finalization
    long h42 = MurmurHash3.murmurHash3_x64_64(42L, 0);
    long h43 = MurmurHash3.murmurHash3_x64_64(43L, 0);
    long h0 = MurmurHash3.murmurHash3_x64_64(0L, 0);
    long hMax = MurmurHash3.murmurHash3_x64_64(Long.MAX_VALUE, 0);

    assertNotEquals("42 and 43 should hash differently", h42, h43);
    assertNotEquals("0 and 42 should hash differently", h0, h42);
    assertNotEquals("MAX and 42 should hash differently", hMax, h42);
    assertNotEquals("0 and MAX should hash differently", h0, hMax);
    assertEquals(h42, MurmurHash3.murmurHash3_x64_64(42L, 0));
  }

  @Test
  public void charSequenceHash_finalizationStepProducesDistinctOutputs() {
    // Kills MathMutator: h1 += h2 → h1 -= h2 in CharSequence overload finalization
    long h1 = MurmurHash3.murmurHash3_x64_64("hello", 0);
    long h2 = MurmurHash3.murmurHash3_x64_64("hellp", 0);
    long hShort = MurmurHash3.murmurHash3_x64_64("ab", 0);
    long hLong = MurmurHash3.murmurHash3_x64_64("abcdefghijklmnop", 0);

    assertEquals("Deterministic", h1,
        MurmurHash3.murmurHash3_x64_64("hello", 0));
    assertNotEquals("hello vs hellp", h1, h2);
    assertNotEquals("short vs long", hShort, hLong);
    assertNotEquals("Hash of 'ab' should not be 0", 0L, hShort);
    assertNotEquals("Hash of long string should not be 0", 0L, hLong);
  }
}
