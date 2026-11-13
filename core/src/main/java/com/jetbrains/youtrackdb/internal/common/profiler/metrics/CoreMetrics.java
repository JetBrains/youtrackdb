package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CoreMetrics {
  // ===================== GLOBAL ===================== //

  public static final MetricDefinition<MetricScope.Global, Counter> DATA_FILE_FSYNC_COUNT =
      new MetricDefinition<>(
          "DataFileFsyncCount", "Data File Fsync Count",
          "Cumulative count of file synchronization (fsync) calls issued on asynchronous page files"
              + " in this Java virtual machine (JVM) since engine start. Unit: calls."
              + " Failed calls count. Disabled calls do not count. Ordinary-channel files do not count."
              + "\n\nThe value never decreases until engine shutdown."
              + " It returns to zero when the engine restarts. Read differences between samples."
              + " Concurrent reads can lag active updates.",
          MetricType.counter());

  public static final MetricDefinition<MetricScope.Global, Gauge<Long>> DIRECT_MEMORY_BYTES =
      new MetricDefinition<>(
          "DirectMemoryBytes", "Direct Memory Bytes",
          "Current bytes of direct memory held by the engine in this Java virtual machine (JVM)."
              + " Includes retained pool buffers, disk cache and write-ahead log (WAL) buffers."
              + " Concurrent reads can lag active allocations and releases.",
          MetricType.gauge(Long.class));

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

  public static final MetricDefinition<MetricScope.Global, Ratio> PREFILTER_EFFECTIVENESS =
      new MetricDefinition<>(
          "PrefilterEffectiveness",
          "Pre-filter Effectiveness",
          "The ratio of filtered entries to probed entries (in percents) for the last 60 seconds",
          MetricType.ratio(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              100.0));

  // --- Query result cache rate metrics (global scope, fed by per-transaction counters) ---

  public static final MetricDefinition<MetricScope.Global, TimeRate> QUERY_CACHE_HIT_RATE =
      new MetricDefinition<>(
          "QueryCacheHitRate",
          "Query Result Cache Hit Rate",
          "The rate of tx-result-cache hits (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global, TimeRate> QUERY_CACHE_MISS_RATE =
      new MetricDefinition<>(
          "QueryCacheMissRate",
          "Query Result Cache Miss Rate",
          "The rate of tx-result-cache misses (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global,
      TimeRate> QUERY_CACHE_SPLICE_FAILURE_RATE =
          new MetricDefinition<>(
              "QueryCacheSpliceFailureRate",
              "Query Result Cache Splice Failure Rate",
              "The rate of tx-result-cache aggregate splice failures (per second) for the last 60"
                  + " seconds",
              MetricType.rate(
                  TimeInterval.of(60, TimeUnit.SECONDS),
                  TimeInterval.of(1, TimeUnit.SECONDS),
                  TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global,
      TimeRate> QUERY_CACHE_K0_INVALIDATION_RATE =
          new MetricDefinition<>(
              "QueryCacheK0InvalidationRate",
              "Query Result Cache K0 Invalidation Rate",
              "The rate of tx-result-cache K0_NONE invalidations (per second) for the last 60 seconds",
              MetricType.rate(
                  TimeInterval.of(60, TimeUnit.SECONDS),
                  TimeInterval.of(1, TimeUnit.SECONDS),
                  TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global,
      TimeRate> QUERY_CACHE_MULTI_INVALIDATION_RATE =
          new MetricDefinition<>(
              "QueryCacheMultiInvalidationRate",
              "Query Result Cache Multi Invalidation Rate",
              "The rate of tx-result-cache multi alias match invalidations (per second) for the last 60 seconds",
              MetricType.rate(
                  TimeInterval.of(60, TimeUnit.SECONDS),
                  TimeInterval.of(1, TimeUnit.SECONDS),
                  TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global, TimeRate> QUERY_CACHE_OVERFLOW_RATE =
      new MetricDefinition<>(
          "QueryCacheOverflowRate",
          "Query Result Cache Overflow Rate",
          "The rate of tx-result-cache entry overflows (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  // --- Gremlin-to-MATCH plan cache (same Global hit+miss TimeRate pair as QUERY_CACHE_*) ---

  public static final MetricDefinition<MetricScope.Global, TimeRate> GREMLIN_PLAN_CACHE_HIT_RATE =
      new MetricDefinition<>(
          "GremlinPlanCacheHitRate",
          "Gremlin Plan Cache Hit Rate",
          "The rate of Gremlin-to-MATCH plan-cache hits (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  public static final MetricDefinition<MetricScope.Global, TimeRate> GREMLIN_PLAN_CACHE_MISS_RATE =
      new MetricDefinition<>(
          "GremlinPlanCacheMissRate",
          "Gremlin Plan Cache Miss Rate",
          "The rate of Gremlin-to-MATCH plan-cache misses (per second) for the last 60 seconds",
          MetricType.rate(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              TimeUnit.SECONDS));

  // --- Gremlin-to-MATCH translation outcomes (fed by GremlinTranslationMetrics) ---

  /**
   * Share of translation attempts that succeed. Numerator = successes; denominator = successes +
   * declines + errors. Kill-switch / missing session and idempotent re-applies are not attempts;
   * edge starts and repeat vetoes are declines (and therefore attempts).
   */
  public static final MetricDefinition<MetricScope.Global,
      Ratio> GREMLIN_TRANSLATION_SUCCESS_RATIO =
          new MetricDefinition<>(
              "GremlinTranslationSuccessRatio",
              "Gremlin Translation Success Ratio",
              "The ratio of successful Gremlin-to-MATCH translations to translation attempts (in"
                  + " percents) for the last 60 seconds",
              MetricType.ratio(
                  TimeInterval.of(60, TimeUnit.SECONDS),
                  TimeInterval.of(1, TimeUnit.SECONDS),
                  100.0));

  /**
   * Share of translation attempts that decline (unsupported shape, edge / non-vertex start, repeat
   * veto, or cached decline). Same attempt denominator as {@link #GREMLIN_TRANSLATION_SUCCESS_RATIO}.
   */
  public static final MetricDefinition<MetricScope.Global,
      Ratio> GREMLIN_TRANSLATION_DECLINE_RATIO =
          new MetricDefinition<>(
              "GremlinTranslationDeclineRatio",
              "Gremlin Translation Decline Ratio",
              "The ratio of declined Gremlin-to-MATCH translations to translation attempts (in"
                  + " percents) for the last 60 seconds",
              MetricType.ratio(
                  TimeInterval.of(60, TimeUnit.SECONDS),
                  TimeInterval.of(1, TimeUnit.SECONDS),
                  100.0));

  /**
   * Share of translation attempts that hit the throw-safety net. Same attempt denominator as
   * {@link #GREMLIN_TRANSLATION_SUCCESS_RATIO}.
   */
  public static final MetricDefinition<MetricScope.Global, Ratio> GREMLIN_TRANSLATION_ERROR_RATIO =
      new MetricDefinition<>(
          "GremlinTranslationErrorRatio",
          "Gremlin Translation Error Ratio",
          "The ratio of Gremlin-to-MATCH translation failures (throw-safety net) to translation"
              + " attempts (in percents) for the last 60 seconds",
          MetricType.ratio(
              TimeInterval.of(60, TimeUnit.SECONDS),
              TimeInterval.of(1, TimeUnit.SECONDS),
              100.0));

  public static final Set<MetricDefinition<MetricScope.Global, ?>> GLOBAL_METRICS = Set.of(
      DATA_FILE_FSYNC_COUNT,
      DIRECT_MEMORY_BYTES,
      FILE_EVICTION_RATE,
      CACHE_HIT_RATIO,
      PREFILTER_EFFECTIVENESS,
      QUERY_CACHE_HIT_RATE,
      QUERY_CACHE_MISS_RATE,
      QUERY_CACHE_SPLICE_FAILURE_RATE,
      QUERY_CACHE_K0_INVALIDATION_RATE,
      QUERY_CACHE_MULTI_INVALIDATION_RATE,
      QUERY_CACHE_OVERFLOW_RATE,
      GREMLIN_PLAN_CACHE_HIT_RATE,
      GREMLIN_PLAN_CACHE_MISS_RATE,
      GREMLIN_TRANSLATION_SUCCESS_RATIO,
      GREMLIN_TRANSLATION_DECLINE_RATIO,
      GREMLIN_TRANSLATION_ERROR_RATIO);

  // ===================== DATABASE ===================== //

  public static final MetricDefinition<MetricScope.Database, Counter> WAL_QUEUE_WAIT_COUNT =
      new MetricDefinition<>(
          "WalQueueWaitCount", "WAL Queue Wait Count",
          "Cumulative count of callers that enter the write-ahead log (WAL) queue-space wait path."
              + " Callers enter when the queue reaches its limit. Unit: wait-path entries."
              + " Counts on entry, including immediate completion and interruption."
              + " Elapsed time updates on exit."
              + "\n\nDatabases with the same name in one engine share this value."
              + " The value never decreases until engine shutdown, including across database close, drop and reopen."
              + " It covers time since engine start and returns to zero when the engine restarts."
              + " Read differences between samples. Concurrent reads can lag active updates.",
          MetricType.counter());

  public static final MetricDefinition<MetricScope.Database, Counter> WAL_QUEUE_WAIT_TIME_NANOS =
      new MetricDefinition<>(
          "WalQueueWaitTimeNanos", "WAL Queue Wait Time",
          "Cumulative nanoseconds in the write-ahead log (WAL) queue-space wait path."
              + " Callers enter when the queue reaches its limit. Unit: nanoseconds."
              + " Includes immediate completion, interruption and caller-assisted flushing."
              + " The count updates on entry. Time updates on exit."
              + "\n\nDatabases with the same name in one engine share this value."
              + " The value never decreases until engine shutdown, including across database close, drop and reopen."
              + " It covers time since engine start and returns to zero when the engine restarts."
              + " Read differences between samples. Concurrent reads can lag active updates.",
          MetricType.counter());

  public static final MetricDefinition<MetricScope.Database, Counter> WAL_VACUUM_RUN_COUNT =
      new MetricDefinition<>(
          "WalVacuumRunCount", "WAL Vacuum Run Count",
          "Cumulative count of write-ahead log (WAL) vacuum runs. Unit: runs."
              + " Each run executes the background task that flushes data so old WAL segments can be removed."
              + " Counts every execution for every storage type, whatever its result."
              + "\n\nDatabases with the same name in one engine share this value."
              + " The value never decreases until engine shutdown, including across database close, drop and reopen."
              + " It covers time since engine start and returns to zero when the engine restarts."
              + " Read differences between samples. Concurrent reads can lag active updates.",
          MetricType.counter());

  public static final MetricDefinition<MetricScope.Database, Counter> WAL_VACUUM_TIME_NANOS =
      new MetricDefinition<>(
          "WalVacuumTimeNanos", "WAL Vacuum Time",
          "Cumulative nanoseconds in write-ahead log (WAL) vacuum runs. Unit: nanoseconds."
              + " Each run executes the background task that flushes data so old WAL segments can be removed."
              + " Includes every execution for every storage type, whatever its result. Time updates on exit."
              + "\n\nDatabases with the same name in one engine share this value."
              + " The value never decreases until engine shutdown, including across database close, drop and reopen."
              + " It covers time since engine start and returns to zero when the engine restarts."
              + " Read differences between samples. Concurrent reads can lag active updates.",
          MetricType.counter());

  public static final MetricDefinition<MetricScope.Database, Counter> WAL_VACUUM_REMOVAL_RUN_COUNT =
      new MetricDefinition<>(
          "WalVacuumRemovalRunCount", "WAL Vacuum Removal Run Count",
          "Cumulative count of write-ahead log (WAL) vacuum runs that deleted at least one segment."
              + " Includes runs that later fail. Counts runs, not segments. Unit: runs."
              + " Each run executes the background task that flushes data so old WAL segments can be removed."
              + " Covers every storage type, but memory storage removes no segments."
              + "\n\nDatabases with the same name in one engine share this value."
              + " The value never decreases until engine shutdown, including across database close, drop and reopen."
              + " It covers time since engine start and returns to zero when the engine restarts."
              + " Read differences between samples. Concurrent reads can lag active updates.",
          MetricType.counter());

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
      WAL_QUEUE_WAIT_COUNT,
      WAL_QUEUE_WAIT_TIME_NANOS,
      WAL_VACUUM_RUN_COUNT,
      WAL_VACUUM_TIME_NANOS,
      WAL_VACUUM_REMOVAL_RUN_COUNT,
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
