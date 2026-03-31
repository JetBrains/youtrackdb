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
  public long totalDelta;

  /** Net change to the null-key entry count. */
  public long nullDelta;
}
