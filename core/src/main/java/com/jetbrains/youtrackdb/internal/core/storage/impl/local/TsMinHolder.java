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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import javax.annotation.Nullable;

/**
 * Holds the minimum active operation timestamp for a single thread. Shared between the owning
 * thread (via {@code ThreadLocal}) and the cleanup thread (via the {@code tsMins} set in
 * {@link AbstractStorage}).
 *
 * <p>{@code tsMin} is volatile because the cleanup thread must see the current value for
 * threads with active read sessions. A stale {@code MAX_VALUE} would let cleanup evict
 * entries the read session is actively using.
 *
 * <p>The reset at end-of-transaction uses {@link VarHandle#setOpaque} instead of a volatile
 * write: the full {@code StoreLoad} barrier of a volatile write is unnecessary there because
 * no subsequent loads on the owning thread depend on the reset being globally visible
 * immediately. Opaque guarantees atomicity and eventual cross-thread visibility, which is
 * sufficient for the cleanup thread to observe the reset in due course.
 *
 * <p>Multiple sessions on the same thread may have overlapping transactions (e.g., session
 * initialization starts a metadata-loading tx while another session's tx is active). The
 * {@code activeTxCount} field tracks this: {@code tsMin} is only reset to {@code MAX_VALUE}
 * when all transactions on the thread have ended.
 */
// Identity-based equals/hashCode (inherited from Object) is required — instances are used
// as weak keys in AbstractStorage.tsMins (Guava MapMaker.weakKeys()).
final class TsMinHolder {

  private static final VarHandle TS_MIN =
      lookupVarHandle(TsMinHolder.class, "tsMin", long.class);
  private static final VarHandle TX_START_TIME_NANOS =
      lookupVarHandle(TsMinHolder.class, "txStartTimeNanos", long.class);
  private static final VarHandle OWNER_THREAD_NAME =
      lookupVarHandle(TsMinHolder.class, "ownerThreadName", String.class);
  private static final VarHandle OWNER_THREAD_ID =
      lookupVarHandle(TsMinHolder.class, "ownerThreadId", long.class);
  private static final VarHandle TX_START_STACK_TRACE =
      lookupVarHandle(TsMinHolder.class, "txStartStackTrace", StackTraceElement[].class);

  /**
   * Looks up a {@link VarHandle} for the given field. Package-private so the error path can be
   * tested with an invalid field name.
   */
  static VarHandle lookupVarHandle(Class<?> clazz, String fieldName, Class<?> fieldType) {
    try {
      return MethodHandles.lookup().findVarHandle(clazz, fieldName, fieldType);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

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

  // --- Diagnostic fields for stale transaction detection (YTDB-550) ---
  //
  // These fields use opaque access (VarHandle.setOpaque / getOpaque) instead of volatile
  // to avoid generating StoreLoad write memory barriers on the owning thread's hot path.
  // Opaque provides atomicity and eventual cross-thread visibility, which is sufficient
  // for the stale transaction monitor that tolerates stale reads.

  // Approximate nano time when the first transaction on this thread started (from Ticker).
  // Set on first tx begin (activeTxCount transitions 0→1), reset on last tx end (1→0).
  // Read by the stale transaction monitor to compute transaction age.
  @SuppressWarnings("unused") // accessed via VarHandle
  private long txStartTimeNanos;

  // Name of the owning thread, captured at registration time. Used by the monitor to
  // identify which thread holds a stale transaction in log messages.
  @SuppressWarnings("unused") // accessed via VarHandle
  private String ownerThreadName;

  // ID of the owning thread, captured at registration time.
  @SuppressWarnings("unused") // accessed via VarHandle
  private long ownerThreadId;

  // Stack trace captured at the point where the transaction was started. Only populated
  // when STORAGE_TX_CAPTURE_STACK_TRACE is enabled. Helps diagnose leaked transactions.
  @SuppressWarnings("unused") // accessed via VarHandle
  @Nullable private StackTraceElement[] txStartStackTrace;

  /**
   * Resets {@code tsMin} to the given value using an opaque write (no {@code StoreLoad} barrier).
   * Used at end-of-transaction where the full memory fence of a volatile write is not needed.
   */
  void setTsMinOpaque(long value) {
    TS_MIN.setOpaque(this, value);
  }

  // --- Opaque accessors for diagnostic fields ---

  void setTxStartTimeNanosOpaque(long value) {
    TX_START_TIME_NANOS.setOpaque(this, value);
  }

  long getTxStartTimeNanosOpaque() {
    return (long) TX_START_TIME_NANOS.getOpaque(this);
  }

  void setOwnerThreadNameOpaque(String value) {
    OWNER_THREAD_NAME.setOpaque(this, value);
  }

  String getOwnerThreadNameOpaque() {
    return (String) OWNER_THREAD_NAME.getOpaque(this);
  }

  void setOwnerThreadIdOpaque(long value) {
    OWNER_THREAD_ID.setOpaque(this, value);
  }

  long getOwnerThreadIdOpaque() {
    return (long) OWNER_THREAD_ID.getOpaque(this);
  }

  void setTxStartStackTraceOpaque(@Nullable StackTraceElement[] value) {
    TX_START_STACK_TRACE.setOpaque(this, value);
  }

  @Nullable StackTraceElement[] getTxStartStackTraceOpaque() {
    return (StackTraceElement[]) TX_START_STACK_TRACE.getOpaque(this);
  }

  /**
   * Captures diagnostic metadata for the stale transaction monitor. Called at the first tx
   * begin on this thread (activeTxCount transitions 0→1). Must be called <b>before</b> the
   * volatile tsMin write to ensure the monitor never sees an active tsMin without valid
   * diagnostic fields.
   *
   * @param startTimeNanos approximate nano time when the transaction started (from Ticker)
   * @param thread the owning thread (for name and id)
   * @param captureStackTrace whether to capture the stack trace at tx begin
   */
  void captureDiagnostics(long startTimeNanos, Thread thread, boolean captureStackTrace) {
    setTxStartTimeNanosOpaque(startTimeNanos);
    setOwnerThreadNameOpaque(thread.getName());
    setOwnerThreadIdOpaque(thread.threadId());
    if (captureStackTrace) {
      setTxStartStackTraceOpaque(thread.getStackTrace());
    }
  }

  /**
   * Clears diagnostic metadata when the last transaction on this thread ends (activeTxCount
   * transitions 1→0). Must be called <b>before</b> the opaque tsMin reset to ensure the
   * monitor never sees an active tsMin with cleared diagnostic fields.
   */
  void clearDiagnostics() {
    setTxStartTimeNanosOpaque(0);
    setTxStartStackTraceOpaque(null);
    setOwnerThreadNameOpaque(null);
    setOwnerThreadIdOpaque(0);
  }
}
