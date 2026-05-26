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

  /**
   * In-mem-only adjustment to the total entry count, accumulated by
   * {@link #accumulateInMemRecalibration(AtomicOperation, int, long, long)}.
   * Read by {@code AbstractStorage.applyIndexCountDeltas} (Hook B) and
   * summed with {@link #totalDelta} when calling the engine's
   * {@code addToApproximateEntriesCount} mutator. NOT read by Hook A's
   * {@code persistCountDelta}: the persisted EP-page side is fed by the
   * inline {@code setApproximateEntriesCount(op, target)} writes in
   * {@code buildInitialHistogram}, which are WAL-tracked and revert on
   * rollback.
   */
  long inMemAdjustTotal;

  /**
   * In-mem-only adjustment to the null-key entry count. See
   * {@link #inMemAdjustTotal} for the contract; this field carries the
   * recalibration delta for the null counter.
   */
  long inMemAdjustNull;

  public long getTotalDelta() {
    return totalDelta;
  }

  public long getNullDelta() {
    return nullDelta;
  }

  public long getInMemAdjustTotal() {
    return inMemAdjustTotal;
  }

  public long getInMemAdjustNull() {
    return inMemAdjustNull;
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
   * <p><strong>Awaiting cleanup.</strong> The production caller count is
   * zero after the {@code clear()} mixed-mode retrofit: both
   * {@code BTreeMultiValueIndexEngine.clear} and
   * {@code BTreeSingleValueIndexEngine.clear} now route through
   * {@link #accumulateInMemRecalibration(AtomicOperation, int, long, long)}
   * instead. Method body, sign-alignment precondition, and the
   * {@code IndexCountDeltaHolderTest} fixtures that exercise this method's
   * contract are kept in place pending a follow-up cleanup tracked under a
   * separate YouTrack issue in the {@code YTDB} project with the
   * {@code dev-workflow} tag.
   *
   * <p>Recalibration callers ({@code buildInitialHistogram} on both engines)
   * route through {@link #accumulateInMemRecalibration(AtomicOperation, int,
   * long, long)} instead, because recalibration deltas are arbitrarily signed
   * by drift nature and would trip this method's sign-alignment precondition.
   *
   * <p><strong>Do not call from per-put or per-remove paths.</strong> Those
   * sites use {@link #accumulate(AtomicOperation, int, int, boolean)} with
   * the {@code sign + isNullKey} form. The long-form overload here carries
   * no per-key null-fraction arithmetic and would mis-encode the null count
   * on misuse.
   *
   * <p>Precondition (runtime assert): {@code |nullDelta| <= |totalDelta|} and
   * the two deltas are sign-aligned (either is zero, or both share the same
   * sign). Both {@code clear()} callers satisfy this: they pass
   * {@code (-currentTotal, -currentNull)} where {@code currentNull <=
   * currentTotal} holds structurally on a well-formed engine, and both deltas
   * share the same (negative) sign.
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

  /**
   * Accumulates an in-mem-only recalibration delta on the transaction's delta
   * holder. The delta advances {@link #inMemAdjustTotal} and
   * {@link #inMemAdjustNull}, which Hook B's
   * {@code AbstractStorage.applyIndexCountDeltas} sums with
   * {@link #totalDelta} / {@link #nullDelta} when calling the engine's
   * {@code addToApproximate*Count} mutators. Hook A's
   * {@code persistCountDelta} does NOT read these fields: the persisted
   * EP-page side is fed by the inline {@code setApproximateEntriesCount(op,
   * target)} writes in {@code buildInitialHistogram}, which are WAL-tracked
   * and revert on rollback.
   *
   * <p>The production callers are {@code
   * BTreeMultiValueIndexEngine.buildInitialHistogram} and {@code
   * BTreeSingleValueIndexEngine.buildInitialHistogram} (both pass
   * {@code (targetTotal - currentInMemTotal, exactNullCount -
   * currentInMemNull)}), plus {@code BTreeMultiValueIndexEngine.clear} and
   * {@code BTreeSingleValueIndexEngine.clear} under the mixed-mode
   * encoding (both pass {@code (-currentTotal, -currentNull)} to collapse
   * both counters).
   *
   * <p><strong>No precondition.</strong> Unlike
   * {@link #accumulateClearOrRecalibrate(AtomicOperation, int, long, long)},
   * this method accepts arbitrarily-signed and arbitrarily-skewed deltas
   * because organic in-mem drift between {@code approximateIndexEntriesCount}
   * and {@code approximateNullCount} (from underflow-clamp events, or any
   * pre-existing in-mem skew) can produce sign-opposed deltas where the
   * null-magnitude exceeds the total-magnitude. A magnitude or
   * sign-alignment assert here would spuriously trip on legitimate
   * recalibration arithmetic.
   *
   * @param atomicOperation current transaction
   * @param engineId stable engine identifier (key in the delta map)
   * @param totalDelta signed in-mem-only change to total-entry count;
   *     arbitrary magnitude and sign
   * @param nullDelta signed in-mem-only change to null-entry count;
   *     arbitrary magnitude and sign
   */
  public static void accumulateInMemRecalibration(
      AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta) {
    var delta = atomicOperation.getOrCreateIndexCountDeltas().getOrCreate(engineId);
    delta.inMemAdjustTotal += totalDelta;
    delta.inMemAdjustNull += nullDelta;
  }
}
