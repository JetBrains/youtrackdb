package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CoreMetrics {
  // ===================== GLOBAL ===================== //

  public static final MetricDefinition<MetricScope.Global, TimeRate> FILE_EVICTION_RATE =
      new MetricDefinition<>(
          "FileEvictionRate",
          "File Eviction Rate",
          "The rate of file evictions (files per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global, Ratio> CACHE_HIT_RATIO =
      new MetricDefinition<>(
          "DiskCacheHitRatio",
          "Disk Cache Hit Ratio",
          "The ratio of disk cache hits (in percents) for the last 60 seconds",
          MetricType.ratio(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              100.0));

  // ---- Pre-filter index scan metrics ----

  public static final MetricDefinition<MetricScope.Global, TimeRate> PREFILTER_SCAN_NANOS =
      new MetricDefinition<>(
          "PrefilterScanNanos",
          "Pre-filter Scan Nanos Rate",
          "Cumulative nanoseconds spent in index scans per second for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global, TimeRate> PREFILTER_SCAN_ENTRIES =
      new MetricDefinition<>(
          "PrefilterScanEntries",
          "Pre-filter Scan Entries Rate",
          "Cumulative index entries scanned per second for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global, Ratio> PREFILTER_EFFECTIVENESS =
      new MetricDefinition<>(
          "PrefilterEffectiveness",
          "Pre-filter Effectiveness",
          "The ratio of filtered entries to probed entries (in percents) for the last 60 seconds",
          MetricType.ratio(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              100.0));

  public static final Set<MetricDefinition<MetricScope.Global, ?>> GLOBAL_METRICS = Set.of(
      FILE_EVICTION_RATE,
      CACHE_HIT_RATIO,
      PREFILTER_SCAN_NANOS,
      PREFILTER_SCAN_ENTRIES,
      PREFILTER_EFFECTIVENESS);

  // ===================== DATABASE ===================== //

  public static final MetricDefinition<MetricScope.Database, TimeRate> DISK_READ_RATE =
      new MetricDefinition<>(
          "DiskReadRate",
          "Disk Read Rate",
          "The rate of disk reads (bytes per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Database, TimeRate> DISK_WRITE_RATE =
      new MetricDefinition<>(
          "DiskWriteRate",
          "Disk Write Rate",
          "The rate of disk writes (bytes per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_FREEZE_DURATION =
      new MetricDefinition<>(
          "DatabaseFreezeDuration",
          "Database Freeze Duration",
          "The duration of the last database freeze (in nanoseconds)",
          MetricType.stopwatch());

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_RELEASE_DURATION =
      new MetricDefinition<>(
          "DatabaseReleaseDuration",
          "Database Release Duration",
          "The duration of the last database release (in nanoseconds)",
          MetricType.stopwatch());

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_DROP_DURATION =
      new MetricDefinition<>(
          "DatabaseDropDuration",
          "Database Drop Duration",
          "The duration of the last database drop (in nanoseconds)",
          MetricType.stopwatch());

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_SHUTDOWN_DURATION =
      new MetricDefinition<>(
          "DatabaseShutdownDuration",
          "Database Shutdown Duration",
          "The duration of the last database shutdown (in nanoseconds)",
          MetricType.stopwatch());

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_SYNCH_DURATION =
      new MetricDefinition<>(
          "DatabaseSynchDuration",
          "Database Synch Duration",
          "The duration of the last database synch (in nanoseconds)",
          MetricType.stopwatch());

  public static final MetricDefinition<MetricScope.Database, TimeRate> TRANSACTION_RATE =
      new MetricDefinition<>(
          "TransactionRate",
          "Transaction Rate",
          "The rate of transactions (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Database, TimeRate> TRANSACTION_WRITE_RATE =
      new MetricDefinition<>(
          "TransactionWriteRate",
          "Transaction Write Rate",
          "The rate of write transactions (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Database,
      TimeRate> TRANSACTION_WRITE_ROLLBACK_RATE =
          new MetricDefinition<>(
              "TransactionWriteRollbackRate",
              "Transaction Write Rollback Rate",
              "The rate of write transaction rollbacks (per second) for the last 60 seconds",
              MetricType.rate(
                  TimeInterval.of(60, TimeUnit.SECONDS),
                  TimeInterval.of(1, TimeUnit.SECONDS),
                  TimeUnit.SECONDS));

  // --- Stale transaction monitor metrics (YTDB-550) ---

  public static final MetricDefinition<MetricScope.Database, Gauge<Long>> OLDEST_TX_AGE =
      new MetricDefinition<>(
          "OldestTxAge",
          "Oldest Transaction Age",
          "Age of the oldest active transaction in seconds",
          MetricType.gauge(Long.class));

  public static final MetricDefinition<MetricScope.Database, Gauge<Long>> SNAPSHOT_INDEX_SIZE =
      new MetricDefinition<>(
          "SnapshotIndexSize",
          "Snapshot Index Size",
          "Approximate number of entries in the shared snapshot index",
          MetricType.gauge(Long.class));

  public static final MetricDefinition<MetricScope.Database, Gauge<Long>> LWM_LAG =
      new MetricDefinition<>(
          "LwmLag",
          "Low-Water-Mark Lag",
          "Difference between the current commit timestamp and the global low-water-mark",
          MetricType.gauge(Long.class));

  public static final MetricDefinition<MetricScope.Database, Gauge<Integer>> STALE_TX_COUNT =
      new MetricDefinition<>(
          "StaleTxCount",
          "Stale Transaction Count",
          "Number of active transactions exceeding the warn timeout threshold",
          MetricType.gauge(Integer.class));

  public static final MetricDefinition<MetricScope.Database, Gauge<Integer>> ACTIVE_TX_COUNT =
      new MetricDefinition<>(
          "ActiveTxCount",
          "Active Transaction Count",
          "Number of currently active transactions across all threads",
          MetricType.gauge(Integer.class));

  public static final Set<MetricDefinition<MetricScope.Database, ?>> DATABASE_METRICS = Set.of(
      DISK_READ_RATE,
      DISK_WRITE_RATE,
      DATABASE_FREEZE_DURATION,
      DATABASE_RELEASE_DURATION,
      DATABASE_DROP_DURATION,
      DATABASE_SHUTDOWN_DURATION,
      DATABASE_SYNCH_DURATION,
      TRANSACTION_RATE,
      TRANSACTION_WRITE_RATE,
      TRANSACTION_WRITE_ROLLBACK_RATE,
      OLDEST_TX_AGE,
      SNAPSHOT_INDEX_SIZE,
      LWM_LAG,
      STALE_TX_COUNT,
      ACTIVE_TX_COUNT);

  // ===================== CLASS (disabled for now) ===================== //

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_CREATE_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordCreateRate",
          "Record Create Rate",
          "The rate of record creations (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS))
          .disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_UPDATE_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordUpdateRate",
          "Record Update Rate",
          "The rate of record updates (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS))
          .disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_CONFLICT_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordUpdateConflictRate",
          "Record Update Conflict Rate",
          "The rate of record update conflicts (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS),
          false).disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_DELETE_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordDeleteRate",
          "Record Delete Rate",
          "The rate of record deletions (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS))
          .disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_READ_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordReadRate",
          "Record Read Rate",
          "The rate of record reads (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS))
          .disable();

  public static final Set<MetricDefinition<MetricScope.Class, ?>> CLASS_METRICS = Set.of(
      RECORD_CREATE_RATE,
      RECORD_UPDATE_RATE,
      RECORD_CONFLICT_RATE,
      RECORD_DELETE_RATE,
      RECORD_READ_RATE);
}
