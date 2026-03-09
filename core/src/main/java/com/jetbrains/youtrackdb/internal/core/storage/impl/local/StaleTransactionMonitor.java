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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Gauge;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Periodically scans active transactions to detect long-running ones that prevent snapshot
 * index garbage collection. Logs warnings at configurable thresholds and updates database-scoped
 * metrics exposed via the {@link com.jetbrains.youtrackdb.internal.common.profiler.Profiler}.
 *
 * <p>The monitor tracks two complementary signals:
 * <ul>
 *   <li><b>Transaction age</b>: how long individual transactions have been open</li>
 *   <li><b>Snapshot index growth trend</b>: whether the snapshot index is growing monotonically
 *       while the low-water-mark is stuck (indicates GC is blocked)</li>
 * </ul>
 *
 * <p>Warning rate-limiting: each stale transaction is warned about once at the WARN threshold,
 * once at the CRITICAL threshold, then every 5 minutes thereafter to avoid log flooding.
 */
final class StaleTransactionMonitor implements Runnable {

  // Repeat interval for warnings after the CRITICAL threshold (in nanoseconds).
  private static final long REPEAT_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

  // Number of consecutive cycles the snapshot index must grow with a stuck LWM before
  // a growth trend warning is emitted.
  private static final int GROWTH_TREND_CYCLES = 3;

  private final String storageName;
  private final Set<TsMinHolder> tsMins;
  private final AtomicLong snapshotIndexSize;
  private final Ticker ticker;
  private final ContextConfiguration config;
  private final AtomicOperationIdGen idGen;

  // Metrics (database-scoped)
  private final Gauge<Long> oldestTxAgeMetric;
  private final Gauge<Long> snapshotIndexSizeMetric;
  private final Gauge<Long> lwmLagMetric;
  private final Gauge<Integer> staleTxCountMetric;
  private final Gauge<Integer> activeTxCountMetric;

  // Per-holder tracking for rate-limited warnings. Keyed by identity (TsMinHolder uses
  // identity equals/hashCode). Value is the nano time of the last warning emitted.
  private final ConcurrentHashMap<TsMinHolder, WarnState> warnStates =
      new ConcurrentHashMap<>();

  // Snapshot index growth trend tracking
  private long previousSnapshotIndexSize;
  private long previousLwm = Long.MAX_VALUE;
  private int consecutiveGrowthCycles;

  @Nullable private volatile ScheduledFuture<?> scheduledFuture;

  StaleTransactionMonitor(
      String storageName,
      Set<TsMinHolder> tsMins,
      AtomicLong snapshotIndexSize,
      AtomicOperationIdGen idGen,
      Ticker ticker,
      ContextConfiguration config,
      MetricsRegistry metricsRegistry) {
    this.storageName = storageName;
    this.tsMins = tsMins;
    this.snapshotIndexSize = snapshotIndexSize;
    this.idGen = idGen;
    this.ticker = ticker;
    this.config = config;

    this.oldestTxAgeMetric =
        metricsRegistry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, storageName);
    this.snapshotIndexSizeMetric =
        metricsRegistry.databaseMetric(CoreMetrics.SNAPSHOT_INDEX_SIZE, storageName);
    this.lwmLagMetric =
        metricsRegistry.databaseMetric(CoreMetrics.LWM_LAG, storageName);
    this.staleTxCountMetric =
        metricsRegistry.databaseMetric(CoreMetrics.STALE_TX_COUNT, storageName);
    this.activeTxCountMetric =
        metricsRegistry.databaseMetric(CoreMetrics.ACTIVE_TX_COUNT, storageName);
  }

  /**
   * Starts the monitor on the given executor. Safe to call multiple times — subsequent calls
   * are no-ops if already running. Must be called under {@code AbstractStorage.stateLock}
   * write lock to prevent concurrent scheduling.
   */
  void start(ScheduledExecutorService executor) {
    if (scheduledFuture != null) {
      return;
    }
    int intervalSecs = config.getValueAsInteger(
        GlobalConfiguration.STORAGE_TX_MONITOR_INTERVAL_SECS);
    scheduledFuture = executor.scheduleWithFixedDelay(
        this, intervalSecs, intervalSecs, TimeUnit.SECONDS);
  }

  /**
   * Stops the monitor. Safe to call if not running.
   */
  void stop() {
    var future = scheduledFuture;
    if (future != null) {
      future.cancel(false);
      scheduledFuture = null;
    }
  }

  @Override
  public void run() {
    try {
      doCheck();
    } catch (Exception e) {
      LogManager.instance().warn(this,
          "Stale transaction monitor encountered an error in storage '%s'",
          e, storageName);
    }
  }

  // Package-private for testability (StaleTransactionMonitorIT).
  void doCheck() {
    long now = ticker.approximateNanoTime();
    long warnThresholdNanos = TimeUnit.SECONDS.toNanos(
        config.getValueAsInteger(GlobalConfiguration.STORAGE_TX_WARN_TIMEOUT_SECS));
    long criticalThresholdNanos = TimeUnit.SECONDS.toNanos(
        config.getValueAsInteger(GlobalConfiguration.STORAGE_TX_CRITICAL_TIMEOUT_SECS));

    long oldestAge = 0;
    int activeTxCount = 0;
    int staleTxCount = 0;

    long currentSnapshotSize = snapshotIndexSize.get();
    long lwm = AbstractStorage.computeGlobalLowWaterMark(tsMins);
    long currentTs = idGen.getLastId();
    long lwmLag = (lwm == Long.MAX_VALUE) ? 0 : currentTs - lwm;

    // Benign race: tsMin and txStartTimeNanos are read non-atomically. Between the two
    // reads, the owning thread could end and start a new transaction, causing the computed
    // age to be too small (underestimate). This is acceptable for a diagnostic monitor —
    // the next cycle will see the correct age.
    for (TsMinHolder holder : tsMins) {
      long tsMin = holder.tsMin;
      if (tsMin == Long.MAX_VALUE) {
        // No active transaction on this thread
        warnStates.remove(holder);
        continue;
      }

      activeTxCount++;

      long startNanos = holder.getTxStartTimeNanosOpaque();
      if (startNanos == 0) {
        // Owning thread is between setting tsMin and txStartTimeNanos, or has just
        // cleared diagnostic fields before the opaque tsMin reset. Skip this cycle.
        continue;
      }

      long ageNanos = now - startNanos;
      if (ageNanos > oldestAge) {
        oldestAge = ageNanos;
      }

      if (ageNanos >= warnThresholdNanos) {
        staleTxCount++;
        emitWarning(holder, ageNanos, criticalThresholdNanos,
            now, currentSnapshotSize, lwmLag);
      }
    }

    // Clean up warn states for holders that are no longer in tsMins (thread died)
    warnStates.keySet().retainAll(tsMins);

    // Update metrics
    oldestTxAgeMetric.setValue(TimeUnit.NANOSECONDS.toSeconds(oldestAge));
    snapshotIndexSizeMetric.setValue(currentSnapshotSize);
    lwmLagMetric.setValue(lwmLag);
    staleTxCountMetric.setValue(staleTxCount);
    activeTxCountMetric.setValue(activeTxCount);

    // Track snapshot index growth trend: if the LWM is stuck and the snapshot index is
    // growing monotonically, it means GC is blocked by one or more long-running transactions.
    checkGrowthTrend(currentSnapshotSize, lwm);
  }

  private void emitWarning(TsMinHolder holder, long ageNanos,
      long criticalThresholdNanos,
      long now, long snapshotSize, long lwmLag) {
    boolean isCritical = ageNanos >= criticalThresholdNanos;

    var state = warnStates.computeIfAbsent(holder, k -> new WarnState());

    // Rate-limiting logic:
    // - First warning at WARN threshold: emit once
    // - First warning at CRITICAL threshold: emit once
    // - After CRITICAL: repeat every REPEAT_WARN_INTERVAL_NANOS
    if (!state.warnEmitted) {
      state.warnEmitted = true;
      state.lastWarnTimeNanos = now;
      logWarn(holder, ageNanos, snapshotSize, lwmLag);
    } else if (isCritical && !state.criticalEmitted) {
      state.criticalEmitted = true;
      state.lastWarnTimeNanos = now;
      logCritical(holder, ageNanos, snapshotSize, lwmLag);
    } else if (isCritical && (now - state.lastWarnTimeNanos) >= REPEAT_WARN_INTERVAL_NANOS) {
      state.lastWarnTimeNanos = now;
      logCritical(holder, ageNanos, snapshotSize, lwmLag);
    }
  }

  private void logWarn(TsMinHolder holder, long ageNanos,
      long snapshotSize, long lwmLag) {
    var stackTrace = holder.getTxStartStackTraceOpaque();
    var threadName = holder.getOwnerThreadNameOpaque();
    long threadId = holder.getOwnerThreadIdOpaque();
    if (stackTrace != null) {
      LogManager.instance().warn(this,
          "Long-running transaction detected in storage '%s': thread='%s' (id=%d), "
              + "age=%ds, snapshotIndexSize=%d, lwmLag=%d, startedAt:%n%s",
          (Throwable) null, storageName, threadName, threadId,
          TimeUnit.NANOSECONDS.toSeconds(ageNanos), snapshotSize, lwmLag,
          formatStackTrace(stackTrace));
    } else {
      LogManager.instance().warn(this,
          "Long-running transaction detected in storage '%s': thread='%s' (id=%d), "
              + "age=%ds, snapshotIndexSize=%d, lwmLag=%d",
          (Throwable) null, storageName, threadName, threadId,
          TimeUnit.NANOSECONDS.toSeconds(ageNanos), snapshotSize, lwmLag);
    }
  }

  private void logCritical(TsMinHolder holder, long ageNanos,
      long snapshotSize, long lwmLag) {
    var stackTrace = holder.getTxStartStackTraceOpaque();
    var threadName = holder.getOwnerThreadNameOpaque();
    long threadId = holder.getOwnerThreadIdOpaque();
    if (stackTrace != null) {
      LogManager.instance().error(this,
          "CRITICAL: Transaction in storage '%s' has been open for %ds and is preventing "
              + "garbage collection of %d snapshot entries. thread='%s' (id=%d), lwmLag=%d, "
              + "startedAt:%n%s",
          (Throwable) null, storageName, TimeUnit.NANOSECONDS.toSeconds(ageNanos),
          snapshotSize, threadName, threadId, lwmLag,
          formatStackTrace(stackTrace));
    } else {
      LogManager.instance().error(this,
          "CRITICAL: Transaction in storage '%s' has been open for %ds and is preventing "
              + "garbage collection of %d snapshot entries. thread='%s' (id=%d), lwmLag=%d",
          (Throwable) null, storageName, TimeUnit.NANOSECONDS.toSeconds(ageNanos),
          snapshotSize, threadName, threadId, lwmLag);
    }
  }

  private void checkGrowthTrend(long currentSize, long currentLwm) {
    if (currentLwm == Long.MAX_VALUE) {
      // No active transactions — reset trend tracking
      consecutiveGrowthCycles = 0;
      previousSnapshotIndexSize = currentSize;
      previousLwm = currentLwm;
      return;
    }

    if (currentLwm == previousLwm && currentSize > previousSnapshotIndexSize) {
      consecutiveGrowthCycles++;
      if (consecutiveGrowthCycles >= GROWTH_TREND_CYCLES) {
        LogManager.instance().warn(this,
            "Snapshot index in storage '%s' has been growing for %d consecutive cycles "
                + "while the low-water-mark is stuck at %d. Current size: %d. "
                + "This indicates one or more transactions are preventing GC.",
            (Throwable) null, storageName, consecutiveGrowthCycles, currentLwm, currentSize);
        // Reset to avoid flooding — will warn again after another GROWTH_TREND_CYCLES
        consecutiveGrowthCycles = 0;
      }
    } else {
      consecutiveGrowthCycles = 0;
    }

    previousSnapshotIndexSize = currentSize;
    previousLwm = currentLwm;
  }

  private static String formatStackTrace(StackTraceElement[] stackTrace) {
    var sb = new StringBuilder();
    for (var element : stackTrace) {
      sb.append("    at ").append(element).append('\n');
    }
    return sb.toString();
  }

  /**
   * Per-holder mutable state for rate-limiting warnings.
   */
  private static final class WarnState {
    boolean warnEmitted;
    boolean criticalEmitted;
    long lastWarnTimeNanos;
  }
}
