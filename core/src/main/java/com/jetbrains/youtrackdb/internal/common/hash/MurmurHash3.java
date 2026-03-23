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

package com.jetbrains.youtrackdb.internal.common.hash;

/**
 * Implementation of the MurmurHash3 128-bit hash function.
 *
 * @since 13.08.12
 */
public class MurmurHash3 {

  private static class State {

    private long h1;
    private long h2;

    private long k1;
    private long k2;

    private long c1;
    private long c2;
  }

  static long getblock(byte[] key, int i) {
    return ((long) key[i] & 0x00000000000000FFL)
        | (((long) key[i + 1] & 0x00000000000000FFL) << 8)
        | (((long) key[i + 2] & 0x00000000000000FFL) << 16)
        | (((long) key[i + 3] & 0x00000000000000FFL) << 24)
        | (((long) key[i + 4] & 0x00000000000000FFL) << 32)
        | (((long) key[i + 5] & 0x00000000000000FFL) << 40)
        | (((long) key[i + 6] & 0x00000000000000FFL) << 48)
        | (((long) key[i + 7] & 0x00000000000000FFL) << 56);
  }

  static void bmix(State state) {
    state.k1 *= state.c1;
    state.k1 = (state.k1 << 23) | (state.k1 >>> (64 - 23));
    state.k1 *= state.c2;
    state.h1 ^= state.k1;
    state.h1 += state.h2;

    state.h2 = (state.h2 << 41) | (state.h2 >>> (64 - 41));

    state.k2 *= state.c2;
    state.k2 = (state.k2 << 23) | (state.k2 >>> (64 - 23));
    state.k2 *= state.c1;
    state.h2 ^= state.k2;
    state.h2 += state.h1;

    state.h1 = state.h1 * 3 + 0x52dce729;
    state.h2 = state.h2 * 3 + 0x38495ab5;

    state.c1 = state.c1 * 5 + 0x7b7d159c;
    state.c2 = state.c2 * 5 + 0x6bce6396;
  }

  static long fmix(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;

    return k;
  }

  /**
   * Hashes a single long value without allocating a byte array.
   * Equivalent to hashing the 8-byte big-endian representation of {@code value}.
   */
  public static long murmurHash3_x64_64(final long value, final int seed) {
    var state = new State();

    state.h1 = 0x9368e53c2f6af274L ^ seed;
    state.h2 = 0x586dcd208f7cd3fdL ^ seed;

    state.c1 = 0x87c37b91114253d5L;
    state.c2 = 0x4cf5ad432745937fL;

    // 8 bytes: goes into k1, k2 stays 0
    state.k1 = value;
    state.k2 = 0;
    bmix(state);

    state.h2 ^= 8; // length = 8

    state.h1 += state.h2;
    state.h2 += state.h1;

    state.h1 = fmix(state.h1);
    state.h2 = fmix(state.h2);

    state.h1 += state.h2;
    // Note: state.h2 is not computed here because this method only returns
    // the 64-bit h1 half. The full 128-bit MurmurHash3 would also finalize h2.

    return state.h1;
  }

  /**
   * Hashes a CharSequence (typically a String) without allocating a byte array.
   * Reads chars directly via {@code charAt()}, packing 4 chars (8 bytes) into
   * each 64-bit block in little-endian order. This provides full 64-bit entropy
   * unlike the {@code String.hashCode() → long mixer} approach which is limited
   * to 2^32 distinct outputs.
   *
   * <p>The byte-level representation treats each char as a 2-byte little-endian
   * value, so the total logical length is {@code cs.length() * 2} bytes.
   */
  public static long murmurHash3_x64_64(final CharSequence cs, final int seed) {
    var state = new State();

    state.h1 = 0x9368e53c2f6af274L ^ seed;
    state.h2 = 0x586dcd208f7cd3fdL ^ seed;

    state.c1 = 0x87c37b91114253d5L;
    state.c2 = 0x4cf5ad432745937fL;

    int len = cs.length();
    // Process full 8-char (16-byte) blocks
    int fullBlocks = len / 8;
    for (int i = 0; i < fullBlocks; i++) {
      int base = i * 8;
      // Pack 4 chars into k1 (little-endian: char 0 in low bits)
      state.k1 = ((long) cs.charAt(base))
          | ((long) cs.charAt(base + 1) << 16)
          | ((long) cs.charAt(base + 2) << 32)
          | ((long) cs.charAt(base + 3) << 48);
      // Pack 4 chars into k2
      state.k2 = ((long) cs.charAt(base + 4))
          | ((long) cs.charAt(base + 5) << 16)
          | ((long) cs.charAt(base + 6) << 32)
          | ((long) cs.charAt(base + 7) << 48);
      bmix(state);
    }

    // Tail: remaining 0-7 chars
    state.k1 = 0;
    state.k2 = 0;
    int tailStart = fullBlocks * 8;
    int remaining = len - tailStart;

    // Chars 4-7 go into k2 (each char is 2 bytes, so char index 4 = byte index 8)
    // Fall-through is intentional (same pattern as the byte[] variant).
    switch (remaining) {
      case 7 :
        state.k2 ^= (long) cs.charAt(tailStart + 6) << 32;
      case 6 :
        state.k2 ^= (long) cs.charAt(tailStart + 5) << 16;
      case 5 :
        state.k2 ^= (long) cs.charAt(tailStart + 4);
      case 4 :
        state.k1 ^= (long) cs.charAt(tailStart + 3) << 48;
      case 3 :
        state.k1 ^= (long) cs.charAt(tailStart + 2) << 32;
      case 2 :
        state.k1 ^= (long) cs.charAt(tailStart + 1) << 16;
      case 1 :
        state.k1 ^= (long) cs.charAt(tailStart);
        bmix(state);
    }

    state.h2 ^= (long) len * 2; // logical length in bytes

    state.h1 += state.h2;
    state.h2 += state.h1;

    state.h1 = fmix(state.h1);
    state.h2 = fmix(state.h2);

    state.h1 += state.h2;
    // Note: state.h2 is not computed here because this method only returns
    // the 64-bit h1 half. The full 128-bit MurmurHash3 would also finalize h2.

    return state.h1;
  }

  /**
   * MurmurHash3_x86_32: 32-bit hash with seed, operating on a byte array subrange.
   *
   * <p>This is the standard MurmurHash3 32-bit variant (reference: SMHasher
   * MurmurHash3_x86_32). It processes 4-byte blocks, handles 0-3 tail bytes,
   * and applies fmix32 finalization. The seed parameter allows producing different
   * hash values for the same input, which is essential for perfect hash table
   * seed search.
   *
   * @param data   the byte array containing the data to hash
   * @param offset the start offset within the array
   * @param len    the number of bytes to hash
   * @param seed   the hash seed
   * @return a 32-bit hash value
   */
  public static int hash32WithSeed(byte[] data, int offset, int len, int seed) {
    assert data != null : "data must not be null";
    assert offset >= 0 : "offset must be non-negative: " + offset;
    assert len >= 0 : "len must be non-negative: " + len;
    assert offset <= data.length
        : "offset exceeds array length: " + offset + " > " + data.length;
    assert len <= data.length - offset
        : "offset + len exceeds array length: " + offset + " + " + len + " > " + data.length;

    final int c1_32 = 0xcc9e2d51;
    final int c2_32 = 0x1b873593;

    int h1 = seed;

    // Process 4-byte blocks
    int nblocks = len / 4;
    for (int i = 0; i < nblocks; i++) {
      int blockOffset = offset + i * 4;
      int k1 = (data[blockOffset] & 0xff)
          | ((data[blockOffset + 1] & 0xff) << 8)
          | ((data[blockOffset + 2] & 0xff) << 16)
          | ((data[blockOffset + 3] & 0xff) << 24);

      k1 *= c1_32;
      k1 = Integer.rotateLeft(k1, 15);
      k1 *= c2_32;

      h1 ^= k1;
      h1 = Integer.rotateLeft(h1, 13);
      h1 = h1 * 5 + 0xe6546b64;
    }

    // Tail: remaining 0-3 bytes
    int tailOffset = offset + nblocks * 4;
    int k1 = 0;
    switch (len & 3) {
      case 3 :
        k1 ^= (data[tailOffset + 2] & 0xff) << 16;
      case 2 :
        k1 ^= (data[tailOffset + 1] & 0xff) << 8;
      case 1 :
        k1 ^= (data[tailOffset] & 0xff);
        k1 *= c1_32;
        k1 = Integer.rotateLeft(k1, 15);
        k1 *= c2_32;
        h1 ^= k1;
      default :
        break;
    }

    // Finalization: fmix32
    h1 ^= len;
    h1 ^= h1 >>> 16;
    h1 *= 0x85ebca6b;
    h1 ^= h1 >>> 13;
    h1 *= 0xc2b2ae35;
    h1 ^= h1 >>> 16;

    return h1;
  }

  public static long murmurHash3_x64_64(final byte[] key, final int seed) {
    var state = new State();

    state.h1 = 0x9368e53c2f6af274L ^ seed;
    state.h2 = 0x586dcd208f7cd3fdL ^ seed;

    state.c1 = 0x87c37b91114253d5L;
    state.c2 = 0x4cf5ad432745937fL;

    for (var i = 0; i < key.length / 16; i++) {
      state.k1 = getblock(key, i * 2 * 8);
      state.k2 = getblock(key, (i * 2 + 1) * 8);

      bmix(state);
    }

    state.k1 = 0;
    state.k2 = 0;

    var tail = (key.length >>> 4) << 4;

    switch (key.length & 15) {
      case 15 :
        state.k2 ^= (long) key[tail + 14] << 48;
      case 14 :
        state.k2 ^= (long) key[tail + 13] << 40;
      case 13 :
        state.k2 ^= (long) key[tail + 12] << 32;
      case 12 :
        state.k2 ^= (long) key[tail + 11] << 24;
      case 11 :
        state.k2 ^= (long) key[tail + 10] << 16;
      case 10 :
        state.k2 ^= (long) key[tail + 9] << 8;
      case 9 :
        state.k2 ^= key[tail + 8];

      case 8 :
        state.k1 ^= (long) key[tail + 7] << 56;
      case 7 :
        state.k1 ^= (long) key[tail + 6] << 48;
      case 6 :
        state.k1 ^= (long) key[tail + 5] << 40;
      case 5 :
        state.k1 ^= (long) key[tail + 4] << 32;
      case 4 :
        state.k1 ^= (long) key[tail + 3] << 24;
      case 3 :
        state.k1 ^= (long) key[tail + 2] << 16;
      case 2 :
        state.k1 ^= (long) key[tail + 1] << 8;
      case 1 :
        state.k1 ^= key[tail];
        bmix(state);
    }

    state.h2 ^= key.length;

    state.h1 += state.h2;
    state.h2 += state.h1;

    state.h1 = fmix(state.h1);
    state.h2 = fmix(state.h2);

    state.h1 += state.h2;
    state.h2 += state.h1;

    return state.h1;
  }
}
