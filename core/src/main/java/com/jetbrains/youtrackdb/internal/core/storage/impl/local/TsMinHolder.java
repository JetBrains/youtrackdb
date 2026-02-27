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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

/**
 * Holds the minimum active operation timestamp for a single thread. Shared between the owning
 * thread (via {@code ThreadLocal}) and the cleanup thread (via the {@code tsMins} set in
 * {@link AbstractStorage}).
 *
 * <p>{@code tsMin} is volatile because the cleanup thread must see the current value for
 * threads with active read sessions. A stale {@code MAX_VALUE} would let cleanup evict
 * entries the read session is actively using.
 *
 * <p>Multiple sessions on the same thread may have overlapping transactions (e.g., session
 * initialization starts a metadata-loading tx while another session's tx is active). The
 * {@code activeTxCount} field tracks this: {@code tsMin} is only reset to {@code MAX_VALUE}
 * when all transactions on the thread have ended.
 */
// Identity-based equals/hashCode (inherited from Object) is required — instances are used
// as weak keys in AbstractStorage.tsMins (Guava MapMaker.weakKeys()).
final class TsMinHolder {

  // The minimum {@code minActiveOperationTs} across all currently active transactions on this
  // thread. Set to {@code Math.min(current, snapshot.minActiveOperationTs())} on each tx begin;
  // reset to {@code Long.MAX_VALUE} when {@code activeTxCount} drops to zero. The cleanup thread
  // reads this value to compute the global low-water-mark. Must be volatile: the cleanup thread
  // must see the current tsMin of threads with active read sessions to avoid evicting snapshot
  // entries those sessions need. The table-based bound in computeGlobalLowWaterMark() handles
  // the TOCTOU for idle threads (tsMin=MAX_VALUE), but active readers must be visible.
  volatile long tsMin = Long.MAX_VALUE;

  // Number of active transactions on the owning thread. Only accessed by the owning thread.
  int activeTxCount;

  // Used by lazy registration in AbstractStorage (Leaf 3, YTDB-510): the owning thread checks
  // this flag before calling tsMins.add(this) so registration happens at most once per thread.
  boolean registeredInTsMins;
}
