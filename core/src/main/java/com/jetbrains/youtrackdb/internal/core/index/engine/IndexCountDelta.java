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
}
