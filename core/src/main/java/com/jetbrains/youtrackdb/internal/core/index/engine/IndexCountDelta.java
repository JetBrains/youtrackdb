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

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;

/**
 * Transaction-local accumulator for index entry count changes. Carried by the
 * AtomicOperation and applied to the engine's {@code AtomicLong} counters only
 * on successful commit. On rollback, simply discarded (write-nothing-on-error).
 *
 * <p>One {@code IndexCountDelta} is created per (engine, transaction) pair.
 * All fields are modified only by the owning transaction thread, so no
 * synchronization is needed.
 */
public final class IndexCountDelta {

  /** Net change to the total entry count (put = +1, remove = -1). */
  long totalDelta;

  /** Net change to the null-key entry count. */
  long nullDelta;

  public long getTotalDelta() {
    return totalDelta;
  }

  public long getNullDelta() {
    return nullDelta;
  }

  /**
   * Accumulates a count delta for the given engine on the transaction's delta
   * holder. Called from engine put/remove methods instead of mutating counters
   * directly.
   *
   * @param atomicOperation current transaction
   * @param engineId stable engine identifier (key in the delta map)
   * @param sign +1 for insert, -1 for remove
   * @param isNullKey true if the affected key is null
   */
  public static void accumulate(
      AtomicOperation atomicOperation, int engineId, int sign, boolean isNullKey) {
    assert sign == 1 || sign == -1 : "sign must be +1 or -1, got " + sign;
    var delta = atomicOperation.getOrCreateIndexCountDeltas().getOrCreate(engineId);
    delta.totalDelta += sign;
    if (isNullKey) {
      delta.nullDelta += sign;
    }
  }

  /**
   * Accumulates an arbitrary-magnitude signed delta on the transaction's delta
   * holder. Semantics are <strong>additive</strong>: both fields advance by
   * {@code += totalDelta} and {@code += nullDelta}. The shape mirrors the
   * {@code += sign} accumulation in {@link #accumulate(AtomicOperation, int,
   * int, boolean)}, so a per-put accumulation and a clear/recalibrate
   * accumulation issued in the same atomic operation compose algebraically.
   *
   * <p>The four production callers, all operating at engine scope rather than
   * per-key, are:
   *
   * <ul>
   *   <li>{@code BTreeMultiValueIndexEngine.clear}: passes
   *       {@code (-currentTotal, -currentNull)} to collapse both counters.
   *   <li>{@code BTreeSingleValueIndexEngine.clear}: same shape for the
   *       single-tree case.
   *   <li>{@code BTreeMultiValueIndexEngine.buildInitialHistogram}: passes
   *       {@code (target - current)} to recalibrate against an observed
   *       absolute target.
   *   <li>{@code BTreeSingleValueIndexEngine.buildInitialHistogram}: same
   *       shape for the single-tree case.
   * </ul>
   *
   * <p><strong>Do not call from per-put or per-remove paths.</strong> Those
   * sites use {@link #accumulate(AtomicOperation, int, int, boolean)} with
   * the {@code sign + isNullKey} form. The long-form overload here carries
   * no per-key null-fraction arithmetic and would mis-encode the null count
   * on misuse.
   *
   * <p>Precondition (runtime assert): {@code |nullDelta| <= |totalDelta|} and
   * the two deltas are sign-aligned (either is zero, or both share the same
   * sign). The four callers above all satisfy this: {@code currentNull <=
   * currentTotal} holds structurally, and recalibration targets advance both
   * counters in the same direction.
   *
   * @param atomicOperation current transaction
   * @param engineId stable engine identifier (key in the delta map)
   * @param totalDelta signed change to total-entry count; arbitrary magnitude
   * @param nullDelta signed change to null-entry count; arbitrary magnitude,
   *     with magnitude no greater than {@code totalDelta} and same sign
   */
  public static void accumulateClearOrRecalibrate(
      AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta) {
    assert Math.abs(nullDelta) <= Math.abs(totalDelta)
        && (totalDelta == 0
            || nullDelta == 0
            || Long.signum(totalDelta) == Long.signum(nullDelta))
        : "accumulateClearOrRecalibrate requires sign-aligned deltas with"
            + " |nullDelta| <= |totalDelta|; got totalDelta="
            + totalDelta
            + " nullDelta="
            + nullDelta;
    var delta = atomicOperation.getOrCreateIndexCountDeltas().getOrCreate(engineId);
    delta.totalDelta += totalDelta;
    delta.nullDelta += nullDelta;
  }
}
