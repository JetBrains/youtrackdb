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
              TimeUnit.SECONDS
          )
      );

  public static final MetricDefinition<MetricScope.Global, Ratio> CACHE_HIT_RATIO =
      new MetricDefinition<>(
          "DiskCacheHitRatio",
          "Disk Cache Hit Ratio",
          "The ratio of disk cache hits (in percents) for the last 60 seconds",
          MetricType.ratio(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              100.0
          )
      );

  public static final Set<MetricDefinition<MetricScope.Global, ?>> GLOBAL_METRICS = Set.of(
      FILE_EVICTION_RATE,
      CACHE_HIT_RATIO
  );

  // ===================== DATABASE ===================== //

  public static final MetricDefinition<MetricScope.Database, TimeRate> DISK_READ_RATE =
      new MetricDefinition<>(
          "DiskReadRate",
          "Disk Read Rate",
          "The rate of disk reads (bytes per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      );

  public static final MetricDefinition<MetricScope.Database, TimeRate> DISK_WRITE_RATE =
      new MetricDefinition<>(
          "DiskWriteRate",
          "Disk Write Rate",
          "The rate of disk writes (bytes per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      );

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_FREEZE_DURATION =
      new MetricDefinition<>(
          "DatabaseFreezeDuration",
          "Database Freeze Duration",
          "The duration of the last database freeze (in nanoseconds)",
          MetricType.stopwatch()
      );

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_RELEASE_DURATION =
      new MetricDefinition<>(
          "DatabaseReleaseDuration",
          "Database Release Duration",
          "The duration of the last database release (in nanoseconds)",
          MetricType.stopwatch()
      );

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_DROP_DURATION =
      new MetricDefinition<>(
          "DatabaseDropDuration",
          "Database Drop Duration",
          "The duration of the last database drop (in nanoseconds)",
          MetricType.stopwatch()
      );

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_SHUTDOWN_DURATION =
      new MetricDefinition<>(
          "DatabaseShutdownDuration",
          "Database Shutdown Duration",
          "The duration of the last database shutdown (in nanoseconds)",
          MetricType.stopwatch()
      );

  public static final MetricDefinition<MetricScope.Database, Stopwatch> DATABASE_SYNCH_DURATION =
      new MetricDefinition<>(
          "DatabaseSynchDuration",
          "Database Synch Duration",
          "The duration of the last database synch (in nanoseconds)",
          MetricType.stopwatch()
      );

  public static final MetricDefinition<MetricScope.Database, TimeRate> TRANSACTION_RATE =
      new MetricDefinition<>(
          "TransactionRate",
          "Transaction Rate",
          "The rate of transactions (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      );

  public static final MetricDefinition<MetricScope.Database, TimeRate> TRANSACTION_WRITE_RATE =
      new MetricDefinition<>(
          "TransactionWriteRate",
          "Transaction Write Rate",
          "The rate of write transactions (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      );

  public static final MetricDefinition<MetricScope.Database, TimeRate> TRANSACTION_WRITE_ROLLBACK_RATE =
      new MetricDefinition<>(
          "TransactionWriteRollbackRate",
          "Transaction Write Rollback Rate",
          "The rate of write transaction rollbacks (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      );

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
      TRANSACTION_WRITE_ROLLBACK_RATE
  );

  // ===================== CLASS (disabled for now) ===================== //

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_CREATE_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordCreateRate",
          "Record Create Rate",
          "The rate of record creations (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      ).disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_UPDATE_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordUpdateRate",
          "Record Update Rate",
          "The rate of record updates (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      ).disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_CONFLICT_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordUpdateConflictRate",
          "Record Update Conflict Rate",
          "The rate of record update conflicts (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          ),
          false
      ).disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_DELETE_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordDeleteRate",
          "Record Delete Rate",
          "The rate of record deletions (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      ).disable();

  public static final MetricDefinition<MetricScope.Class, TimeRate> RECORD_READ_RATE =
      new MetricDefinition<MetricScope.Class, TimeRate>(
          "RecordReadRate",
          "Record Read Rate",
          "The rate of record reads (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS
          )
      ).disable();

  public static final Set<MetricDefinition<MetricScope.Class, ?>> CLASS_METRICS = Set.of(
      RECORD_CREATE_RATE,
      RECORD_UPDATE_RATE,
      RECORD_CONFLICT_RATE,
      RECORD_DELETE_RATE,
      RECORD_READ_RATE
  );
}
