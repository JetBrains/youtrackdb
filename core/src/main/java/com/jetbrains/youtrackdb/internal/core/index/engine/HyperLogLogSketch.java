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

import java.util.Arrays;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * HyperLogLog sketch for approximate distinct value counting.
 *
 * <p>Compact probabilistic data structure with O(1) insert, O(m) merge and
 * estimate, and fixed 1 KB memory footprint. Used exclusively for multi-value
 * index NDV tracking. Single-value indexes do not use this class.
 *
 * <p>Implements the standard HyperLogLog algorithm from Flajolet et al. with
 * small-range (linear counting) and large-range corrections.
 *
 * <p>Not thread-safe. External synchronization is provided by the delta
 * accumulation model: each transaction has its own delta sketch, and the
 * snapshot sketch is updated atomically via {@code cache.compute()}.
 */
public final class HyperLogLogSketch {

  // Precision parameter: number of index bits
  private static final int P = 10;

  // Number of registers (2^P)
  private static final int M = 1 << P; // 1024

  // Bias correction constant for m = 1024
  // ALPHA_M ≈ 0.7213 / (1 + 1.079 / 1024) ≈ 0.72054
  private static final double ALPHA_M = 0.7213 / (1.0 + 1.079 / M);

  // Maximum valid rho value: 64 - P = 54 (all remaining bits are zero)
  static final int MAX_REGISTER_VALUE = 64 - P;

  // Precomputed 2^64 for large-range correction
  private static final double TWO_POW_64 = Math.pow(2, 64);

  private final byte[] registers;

  public HyperLogLogSketch() {
    this.registers = new byte[M];
  }

  // Private constructor for clone
  private HyperLogLogSketch(byte[] registers) {
    this.registers = registers;
  }

  /**
   * Adds a hashed value to the sketch. O(1).
   *
   * <p>The 64-bit hash is split into two parts:
   * <ul>
   *   <li>Low P bits select the register index (0..1023)</li>
   *   <li>Remaining 54 bits: leading zeros are counted (the "rho" function)</li>
   * </ul>
   *
   * @param hash 64-bit hash of the key (from MurmurHash3)
   */
  public void add(long hash) {
    // Low P bits select the register index (0..1023)
    int index = (int) (hash & (M - 1));

    // Remaining bits: count leading zeros + 1 (the "rho" function).
    // Shift right by P to isolate the remaining 54 bits. OR with 1L sets
    // a sentinel bit to cap rho at 54 when all remaining bits are zero.
    long w = hash >>> P;
    // rho range: [1, 64 - P] = [1, 54]. The sentinel (| 1L) caps rho at 54
    // when all 54 remaining bits are zero (probability 2^-54).
    int rho = Long.numberOfLeadingZeros(w | 1L) - P + 1;
    assert rho >= 1 && rho <= (64 - P) : "rho out of range: " + rho;

    // Update register to max(current, rho)
    if (rho > registers[index]) {
      registers[index] = (byte) rho;
    }
  }

  /**
   * Returns the estimated number of distinct values added to this sketch.
   *
   * <p>Uses the standard HyperLogLog algorithm with small-range and
   * large-range corrections from Flajolet et al. O(m) — iterates all
   * 1024 registers.
   *
   * @return the estimated distinct value count
   */
  public long estimate() {
    // 1. Compute the raw HyperLogLog estimate (harmonic mean of 2^(-register[i]))
    double sum = 0.0;
    int zeroCount = 0;
    for (int i = 0; i < M; i++) {
      sum += 1.0 / (1L << registers[i]);
      if (registers[i] == 0) {
        zeroCount++;
      }
    }
    double rawEstimate = ALPHA_M * M * M / sum;

    // 2. Small-range correction (linear counting).
    // When many registers are zero, the raw estimate is biased high.
    // Use linear counting: m * ln(m / zeroCount), which is more accurate
    // for small cardinalities.
    if (rawEstimate <= 2.5 * M && zeroCount > 0) { // threshold: 2560
      return Math.round(M * Math.log((double) M / zeroCount));
    }

    // 3. Large-range correction.
    // For very large cardinalities approaching 2^64, hash collisions cause
    // underestimation. In practice this threshold (~2^63) is never reached
    // for database indexes, but included for algorithmic completeness.
    if (rawEstimate > TWO_POW_64 / 30.0) {
      return Math.round(
          -TWO_POW_64 * Math.log(1.0 - rawEstimate / TWO_POW_64));
    }

    // 4. Normal range — raw estimate is unbiased
    return Math.round(rawEstimate);
  }

  /**
   * Merges another sketch into this one. O(m).
   *
   * <p>After merge, this sketch's estimate reflects the union of both input
   * sets. Merge is commutative, associative, and idempotent.
   *
   * @param other the sketch to merge into this one
   */
  public void merge(HyperLogLogSketch other) {
    for (int i = 0; i < M; i++) {
      if (other.registers[i] > registers[i]) {
        registers[i] = other.registers[i];
      }
    }
  }

  /**
   * Creates a deep copy of this sketch. O(m).
   *
   * @return a new independent sketch with the same register values
   */
  public HyperLogLogSketch copy() {
    byte[] copied = new byte[M];
    System.arraycopy(this.registers, 0, copied, 0, M);
    return new HyperLogLogSketch(copied);
  }

  /**
   * Resets all registers to zero and re-populates from the given key stream.
   * Called during histogram rebalance.
   *
   * @param sortedKeys   non-null keys from the index (may contain duplicates)
   * @param hashFunction the same hash function used by onPut()
   */
  public void rebuildFrom(Stream<Object> sortedKeys,
      ToLongFunction<Object> hashFunction) {
    Arrays.fill(registers, (byte) 0);
    sortedKeys.forEach(key -> add(hashFunction.applyAsLong(key)));
  }

  /**
   * Writes the register array to a destination byte array at the given offset.
   * Called during batched persistence flush.
   *
   * @param dest   the destination byte array (must have at least
   *               {@code offset + serializedSize()} bytes)
   * @param offset the starting byte offset in the destination
   */
  public void writeTo(byte[] dest, int offset) {
    System.arraycopy(registers, 0, dest, offset, M);
  }

  /**
   * Reads the register array from a source byte array at the given offset.
   * Called during engine load (openStatsFile).
   *
   * @param src    the source byte array
   * @param offset the starting byte offset in the source
   * @return a new sketch populated from the source data
   */
  public static HyperLogLogSketch readFrom(byte[] src, int offset) {
    if (src.length < offset + M) {
      throw new IllegalArgumentException(
          "HLL source buffer too short: need " + (offset + M)
              + " bytes, got " + src.length);
    }
    var sketch = new HyperLogLogSketch();
    System.arraycopy(src, offset, sketch.registers, 0, M);
    // Validate register values: each must be in [0, MAX_REGISTER_VALUE].
    // A corrupted page could contain values > 54, causing 1L << register[i]
    // to overflow in estimate() and silently bias the result toward zero.
    // Clamp to MAX_REGISTER_VALUE rather than rejecting the entire sketch —
    // a partially-corrupt HLL still provides a useful (if noisy) estimate,
    // and the next rebalance will rebuild it from scratch.
    for (int i = 0; i < M; i++) {
      if (sketch.registers[i] < 0 || sketch.registers[i] > MAX_REGISTER_VALUE) {
        sketch.registers[i] = (byte) MAX_REGISTER_VALUE;
      }
    }
    return sketch;
  }

  /**
   * Returns the serialized size in bytes. Always 1024 (= M) for p=10.
   *
   * @return the number of bytes needed to store the register array
   */
  public static int serializedSize() {
    return M;
  }
}
