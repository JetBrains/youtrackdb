package com.jetbrains.youtrackdb.internal.common.hash;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Known-value tests for the MurmurHash3 hash function. Each test asserts the exact hash output
 * for a specific input, ensuring that any mutation to the hash computation (arithmetic, bitwise,
 * shift, XOR, constant) will be detected.
 */
public class MurmurHash3Test {

  // --- Long overload: murmurHash3_x64_64(long, int) ---

  @Test
  public void longHash_zeroWithDefaultSeed() {
    assertThat(MurmurHash3.murmurHash3_x64_64(0L, 0)).isEqualTo(-6180381343859527073L);
  }

  @Test
  public void longHash_oneWithDefaultSeed() {
    assertThat(MurmurHash3.murmurHash3_x64_64(1L, 0)).isEqualTo(6333283390351972457L);
  }

  @Test
  public void longHash_minusOneWithDefaultSeed() {
    assertThat(MurmurHash3.murmurHash3_x64_64(-1L, 0)).isEqualTo(-2430564858822907883L);
  }

  @Test
  public void longHash_maxLongWithDefaultSeed() {
    assertThat(MurmurHash3.murmurHash3_x64_64(Long.MAX_VALUE, 0)).isEqualTo(-999350745582466048L);
  }

  @Test
  public void longHash_minLongWithDefaultSeed() {
    assertThat(MurmurHash3.murmurHash3_x64_64(Long.MIN_VALUE, 0)).isEqualTo(2948338765831686026L);
  }

  @Test
  public void longHash_42WithDefaultSeed() {
    assertThat(MurmurHash3.murmurHash3_x64_64(42L, 0)).isEqualTo(6680639807124125198L);
  }

  @Test
  public void longHash_deadbeefWithDefaultSeed() {
    assertThat(MurmurHash3.murmurHash3_x64_64(0xDEADBEEFL, 0)).isEqualTo(-2250810076645059223L);
  }

  @Test
  public void longHash_differentSeedProducesDifferentResult() {
    // Same value, different seed must produce a different hash
    assertThat(MurmurHash3.murmurHash3_x64_64(42L, 12345)).isEqualTo(3924573562633331253L);
    assertThat(MurmurHash3.murmurHash3_x64_64(42L, 12345))
        .isNotEqualTo(MurmurHash3.murmurHash3_x64_64(42L, 0));
  }

  // --- CharSequence overload: murmurHash3_x64_64(CharSequence, int) ---
  // Tests cover all tail lengths 0-7 plus full blocks (len=8,9,16,17).

  @Test
  public void charSequenceHash_emptyString() {
    // Tail length 0: no chars processed
    assertThat(MurmurHash3.murmurHash3_x64_64("", 0)).isEqualTo(-7781342737886326986L);
  }

  @Test
  public void charSequenceHash_length1() {
    // Tail length 1: exercises case 1 only
    assertThat(MurmurHash3.murmurHash3_x64_64("a", 0)).isEqualTo(-4060589748530793459L);
  }

  @Test
  public void charSequenceHash_length2() {
    // Tail length 2: exercises case 2 → 1
    assertThat(MurmurHash3.murmurHash3_x64_64("ab", 0)).isEqualTo(3371390696271435024L);
  }

  @Test
  public void charSequenceHash_length3() {
    // Tail length 3: exercises case 3 → 2 → 1
    assertThat(MurmurHash3.murmurHash3_x64_64("abc", 0)).isEqualTo(7099591825648212176L);
  }

  @Test
  public void charSequenceHash_length4() {
    // Tail length 4: exercises case 4 → 3 → 2 → 1
    assertThat(MurmurHash3.murmurHash3_x64_64("abcd", 0)).isEqualTo(6592752022319426910L);
  }

  @Test
  public void charSequenceHash_length5() {
    // Tail length 5: exercises case 5 → 4 → 3 → 2 → 1
    assertThat(MurmurHash3.murmurHash3_x64_64("abcde", 0)).isEqualTo(-4884312323288591925L);
  }

  @Test
  public void charSequenceHash_length6() {
    // Tail length 6: exercises case 6 → 5 → 4 → 3 → 2 → 1
    assertThat(MurmurHash3.murmurHash3_x64_64("abcdef", 0)).isEqualTo(-979904102157717082L);
  }

  @Test
  public void charSequenceHash_length7() {
    // Tail length 7: exercises all tail cases (7 → 6 → ... → 1)
    assertThat(MurmurHash3.murmurHash3_x64_64("abcdefg", 0)).isEqualTo(3389670505183857921L);
  }

  @Test
  public void charSequenceHash_length8_exactlyOneBlock() {
    // Exactly 1 full 8-char block, no tail
    assertThat(MurmurHash3.murmurHash3_x64_64("abcdefgh", 0)).isEqualTo(-3364713676898773966L);
  }

  @Test
  public void charSequenceHash_length9_oneBlockPlusTail1() {
    // 1 full block + tail length 1
    assertThat(MurmurHash3.murmurHash3_x64_64("abcdefghi", 0)).isEqualTo(-3006228879249915946L);
  }

  @Test
  public void charSequenceHash_length16_twoFullBlocks() {
    // 2 full blocks, no tail
    assertThat(MurmurHash3.murmurHash3_x64_64("abcdefghijklmnop", 0))
        .isEqualTo(1705432138945715914L);
  }

  @Test
  public void charSequenceHash_length17_twoBlocksPlusTail1() {
    // 2 full blocks + tail length 1
    assertThat(MurmurHash3.murmurHash3_x64_64("abcdefghijklmnopq", 0))
        .isEqualTo(4485702361801488403L);
  }

  @Test
  public void charSequenceHash_differentSeedProducesDifferentResult() {
    assertThat(MurmurHash3.murmurHash3_x64_64("hello", 99)).isEqualTo(-1567717746062969060L);
    assertThat(MurmurHash3.murmurHash3_x64_64("hello", 99))
        .isNotEqualTo(MurmurHash3.murmurHash3_x64_64("hello", 0));
  }

  // --- byte[] overload: murmurHash3_x64_64(byte[], int) ---
  // Tests exercise every tail length from 0 to 15, plus 16 (full block) and 17 (block + tail 1).

  @Test
  public void byteHash_emptyArray() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {}, 0))
        .isEqualTo(-7781342737886326986L);
  }

  @Test
  public void byteHash_tailLength1() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1}, 0))
        .isEqualTo(-2251886599231904971L);
  }

  @Test
  public void byteHash_tailLength2() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2}, 0))
        .isEqualTo(1739395130355374806L);
  }

  @Test
  public void byteHash_tailLength3() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3}, 0))
        .isEqualTo(492029490741731226L);
  }

  @Test
  public void byteHash_tailLength4() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3, 4}, 0))
        .isEqualTo(-170868427806902235L);
  }

  @Test
  public void byteHash_tailLength5() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3, 4, 5}, 0))
        .isEqualTo(-3902809372243876965L);
  }

  @Test
  public void byteHash_tailLength6() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3, 4, 5, 6}, 0))
        .isEqualTo(7615920780962878600L);
  }

  @Test
  public void byteHash_tailLength7() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3, 4, 5, 6, 7}, 0))
        .isEqualTo(-217301646648926418L);
  }

  @Test
  public void byteHash_tailLength8() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0))
        .isEqualTo(-3032185381894660834L);
  }

  @Test
  public void byteHash_tailLength9() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, 0))
        .isEqualTo(1474419114225903436L);
  }

  @Test
  public void byteHash_tailLength10() {
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 0))
        .isEqualTo(5745530786812235977L);
  }

  @Test
  public void byteHash_tailLength11() {
    assertThat(MurmurHash3.murmurHash3_x64_64(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, 0))
        .isEqualTo(-2075471372228467093L);
  }

  @Test
  public void byteHash_tailLength12() {
    assertThat(MurmurHash3.murmurHash3_x64_64(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, 0))
        .isEqualTo(-560599178314965018L);
  }

  @Test
  public void byteHash_tailLength13() {
    assertThat(MurmurHash3.murmurHash3_x64_64(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, 0))
        .isEqualTo(5778938241322871061L);
  }

  @Test
  public void byteHash_tailLength14() {
    assertThat(MurmurHash3.murmurHash3_x64_64(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}, 0))
        .isEqualTo(-5058703038363834716L);
  }

  @Test
  public void byteHash_tailLength15() {
    assertThat(MurmurHash3.murmurHash3_x64_64(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, 0))
        .isEqualTo(7441962444598815095L);
  }

  @Test
  public void byteHash_exactlyOneBlock() {
    // 16 bytes = 1 full block, no tail
    assertThat(MurmurHash3.murmurHash3_x64_64(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, 0))
        .isEqualTo(3665714945722039059L);
  }

  @Test
  public void byteHash_oneBlockPlusTail1() {
    // 17 bytes = 1 full block + tail length 1
    assertThat(MurmurHash3.murmurHash3_x64_64(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17}, 0))
        .isEqualTo(-1592450216787179230L);
  }

  // --- Cross-overload consistency ---

  @Test
  public void emptyInputsProduceSameHash() {
    // Empty byte[] and empty string should produce the same hash (both have length 0)
    assertThat(MurmurHash3.murmurHash3_x64_64(new byte[] {}, 0))
        .isEqualTo(MurmurHash3.murmurHash3_x64_64("", 0));
  }

  @Test
  public void differentInputsProduceDifferentHashes() {
    // Sanity check: distinct inputs produce distinct hashes
    long h1 = MurmurHash3.murmurHash3_x64_64(1L, 0);
    long h2 = MurmurHash3.murmurHash3_x64_64(2L, 0);
    assertThat(h1).isNotEqualTo(h2);
  }

  @Test
  public void deterministicResults() {
    // Calling the hash with the same input twice must return the same result
    long first = MurmurHash3.murmurHash3_x64_64(12345L, 42);
    long second = MurmurHash3.murmurHash3_x64_64(12345L, 42);
    assertThat(first).isEqualTo(second);
  }

  // --- 32-bit seeded variant: hash32WithSeed(byte[], int, int, int) ---
  // Reference test vectors verified against canonical MurmurHash3_x86_32.

  @Test
  public void hash32_emptyInputSeedZero_returnsZero() {
    // Canonical reference: MurmurHash3_x86_32("", 0) = 0
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {}, 0, 0, 0)).isEqualTo(0);
  }

  @Test
  public void hash32_fourZeroBytesSeedZero_returnsCanonicalValue() {
    // Canonical reference: MurmurHash3_x86_32({0,0,0,0}, 0) = 0x2362f9de
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {0, 0, 0, 0}, 0, 4, 0))
        .isEqualTo(0x2362f9de);
  }

  @Test
  public void hash32_tailLength1() {
    // 1 byte: exercises tail case 1 only
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1}, 0, 1, 0)).isEqualTo(0xe45ad1ab);
  }

  @Test
  public void hash32_tailLength2() {
    // 2 bytes: exercises tail cases 2 → 1
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1, 2}, 0, 2, 0)).isEqualTo(0x64c7667e);
  }

  @Test
  public void hash32_tailLength3() {
    // 3 bytes: exercises tail cases 3 → 2 → 1
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3}, 0, 3, 0))
        .isEqualTo(0x80d1d204);
  }

  @Test
  public void hash32_exactlyOneBlock() {
    // 4 bytes: exactly one 4-byte block, no tail
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3, 4}, 0, 4, 0))
        .isEqualTo(0x3e349da5);
  }

  @Test
  public void hash32_oneBlockPlusTail() {
    // 5 bytes: 1 block + 1 tail byte
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3, 4, 5}, 0, 5, 0))
        .isEqualTo(0xa291b9c8);
  }

  @Test
  public void hash32_twoFullBlocks() {
    // 8 bytes: 2 full blocks, no tail
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, 8, 0))
        .isEqualTo(0x0d4b1fbb);
  }

  @Test
  public void hash32_offsetHashesSubarray() {
    // Hashing a subarray via offset must equal hashing the extracted subarray
    byte[] padded = {99, 99, 1, 2, 3, 99, 99};
    int hashWithOffset = MurmurHash3.hash32WithSeed(padded, 2, 3, 0);
    int hashDirect = MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3}, 0, 3, 0);
    assertThat(hashWithOffset).isEqualTo(hashDirect);
  }

  @Test
  public void hash32_differentSeedProducesDifferentResult() {
    byte[] data = "Hello".getBytes();
    int hash0 = MurmurHash3.hash32WithSeed(data, 0, data.length, 0);
    int hash42 = MurmurHash3.hash32WithSeed(data, 0, data.length, 42);
    assertThat(hash0).isEqualTo(316307400);
    assertThat(hash42).isEqualTo(1466740371);
    assertThat(hash0).isNotEqualTo(hash42);
  }

  @Test
  public void hash32_deterministicResults() {
    byte[] data = {1, 2, 3, 4, 5};
    int first = MurmurHash3.hash32WithSeed(data, 0, data.length, 77);
    int second = MurmurHash3.hash32WithSeed(data, 0, data.length, 77);
    assertThat(first).isEqualTo(second);
  }

  // --- Comprehensive known-value tests for hash32WithSeed ---
  // These form the regression safety net: any mutation to the algorithm
  // (constants, rotations, shifts, XOR, finalization) will flip at least one.

  @Test
  public void hash32_emptyInputNonZeroSeed() {
    // Empty input with seed=1 produces a non-zero hash (seed affects result)
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {}, 0, 0, 1)).isEqualTo(1364076727);
  }

  @Test
  public void hash32_emptyInputNegativeSeed() {
    // Negative seed (high bit set): algorithm must handle sign extension correctly
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {}, 0, 0, -1)).isEqualTo(-2114883783);
  }

  @Test
  public void hash32_emptyInputMaxIntSeed() {
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {}, 0, 0, Integer.MAX_VALUE))
        .isEqualTo(-104067416);
  }

  @Test
  public void hash32_emptyInputMinIntSeed() {
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {}, 0, 0, Integer.MIN_VALUE))
        .isEqualTo(1832674720);
  }

  @Test
  public void hash32_tailLength1_nonZeroSeed() {
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1}, 0, 1, 42)).isEqualTo(1522706598);
  }

  @Test
  public void hash32_tailLength2_nonZeroSeed() {
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1, 2}, 0, 2, 42)).isEqualTo(1662749572);
  }

  @Test
  public void hash32_tailLength3_nonZeroSeed() {
    assertThat(MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3}, 0, 3, 42)).isEqualTo(736634079);
  }

  @Test
  public void hash32_threeBlocks() {
    // 12 bytes = 3 full 4-byte blocks, exercises multi-iteration loop
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, 0, 12, 0))
        .isEqualTo(-1887373509);
  }

  @Test
  public void hash32_fourBlocks() {
    // 16 bytes = 4 full blocks, larger multi-block input
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, 0, 16, 0))
        .isEqualTo(-629264281);
  }

  @Test
  public void hash32_threeBlocksPlusTail1() {
    // 13 bytes = 3 blocks + 1 tail byte, exercises both loop and tail
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, 0, 13, 0))
        .isEqualTo(-920072715);
  }

  @Test
  public void hash32_negativeSeedWithData() {
    // 0xdeadbeef is negative as a signed int: tests seed sign handling in block mixing
    byte[] hello = "Hello, World!".getBytes();
    assertThat(MurmurHash3.hash32WithSeed(hello, 0, hello.length, 0xdeadbeef))
        .isEqualTo(-575203513);
  }

  @Test
  public void hash32_offsetMultiBlock() {
    // Offset into a padded array, hashing a multi-block subrange
    byte[] padded = {0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0};
    assertThat(MurmurHash3.hash32WithSeed(padded, 4, 8, 0))
        .isEqualTo(MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, 8, 0));
  }

  @Test
  public void hash32_offsetWithNonZeroSeed() {
    // Offset + non-zero seed combination
    byte[] padded = {0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0};
    assertThat(MurmurHash3.hash32WithSeed(padded, 4, 8, 99))
        .isEqualTo(MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, 8, 99));
  }

  @Test
  public void hash32_zeroLengthAtNonZeroOffset() {
    // Zero-length hash at a non-zero offset must equal empty hash
    byte[] data = {1, 2, 3, 4, 5};
    assertThat(MurmurHash3.hash32WithSeed(data, 4, 0, 0))
        .isEqualTo(MurmurHash3.hash32WithSeed(new byte[] {}, 0, 0, 0));
  }

  @Test
  public void hash32_typicalPropertyNames() {
    // Property name strings as they would appear in serialized records — locks in
    // the exact hash values that the V2 serializer's perfect hash table will use
    assertThat(MurmurHash3.hash32WithSeed("propertyName".getBytes(), 0, 12, 0))
        .isEqualTo(-944075627);
    assertThat(MurmurHash3.hash32WithSeed("age".getBytes(), 0, 3, 0))
        .isEqualTo(717653329);
    assertThat(MurmurHash3.hash32WithSeed("createdAt".getBytes(), 0, 9, 0))
        .isEqualTo(515696882);
  }

  @Test
  public void hash32_blocksPlusTail2() {
    // 14 bytes = 3 blocks + tail length 2: exercises tail case 2 after block loop
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}, 0, 14, 42))
        .isEqualTo(1236488183);
  }

  @Test
  public void hash32_blocksPlusTail3() {
    // 15 bytes = 3 blocks + tail length 3: exercises tail case 3 after block loop
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, 0, 15, 42))
        .isEqualTo(-580403171);
  }

  @Test
  public void hash32_highByteValues_block() {
    // Bytes with high bit set exercise the & 0xff sign-extension masking in block read
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {(byte) 0xff, (byte) 0x80, (byte) 0xfe, (byte) 0x7f}, 0, 4, 0))
        .isEqualTo(0x14c023d3);
  }

  @Test
  public void hash32_highByteValues_tail() {
    // High-bit bytes in tail path exercise & 0xff masking in tail handling
    assertThat(MurmurHash3.hash32WithSeed(
        new byte[] {(byte) 0xff, (byte) 0x80, (byte) 0xfe}, 0, 3, 0))
        .isEqualTo(0x2db15157);
  }

  @Test
  public void hash32_offsetWithTailBytes() {
    // Offset + block + tail: exercises offset correctness across both code paths
    byte[] padded = {0, 0, 1, 2, 3, 4, 5, 0, 0};
    assertThat(MurmurHash3.hash32WithSeed(padded, 2, 5, 0))
        .isEqualTo(MurmurHash3.hash32WithSeed(new byte[] {1, 2, 3, 4, 5}, 0, 5, 0));
  }
}
