package com.jetbrains.youtrackdb.internal.common.hash;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Mutation-killing tests for {@link MurmurHash3} targeting pitest mutations
 * at lines 112 and 189 (addition replaced with subtraction in final mixing).
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
  // Line 112: state.h2 += state.h1 (long overload, final mixing)
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
  // Line 189: state.h2 += state.h1 (CharSequence overload, final mixing)
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
}
