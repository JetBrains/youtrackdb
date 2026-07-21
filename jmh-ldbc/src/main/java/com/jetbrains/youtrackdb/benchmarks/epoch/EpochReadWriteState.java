package com.jetbrains.youtrackdb.benchmarks.epoch;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadStats;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared JMH state for the YTDB-1203 apply-phase-epoch ceiling benchmark
 * ({@link EpochReadWriteBenchmark}).
 *
 * <p>Creates a fresh single-class embedded DISK database per trial (fork):
 *
 * <ul>
 *   <li>class {@code EpochDoc} with properties {@code key} (LONG, UNIQUE index),
 *       {@code stamp} (LONG, NOTUNIQUE index) and {@code payload} (STRING);
 *   <li>{@code recordCount} records with {@code key} = 0..N-1, loaded in batches;
 *   <li>final (post-commit) RIDs of all records, collected by a full scan so readers can
 *       do direct point loads without any index involvement.
 * </ul>
 *
 * <p>Also owns the anomaly bookkeeping for deliberately UNSOUND epoch-off measurement
 * runs: read-path exceptions swallowed by the benchmark methods are counted (total plus
 * per-exception-class) and printed per iteration together with the optimistic-read abort
 * counters from {@link OptimisticReadStats}, so a run produces fallback/abort evidence
 * alongside the throughput numbers.
 *
 * <p>Configure via system properties:
 * <ul>
 *   <li>{@code -Depoch.db.path=./target/epoch-bench-db} — directory for the database
 *       (wiped and re-created every trial).</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class EpochReadWriteState {

  private static final Logger log = LoggerFactory.getLogger(EpochReadWriteState.class);

  private static final String DB_NAME = "epoch_bench";
  private static final int LOAD_BATCH_SIZE = 1_000;

  /** Number of EpochDoc records preloaded before the measurement starts. */
  @Param({"100000"})
  public int recordCount;

  YouTrackDB db;
  YTDBGraphTraversalSource traversal;

  // Final (post-commit) RIDs of the preloaded records; rids[i] is the record whose
  // key property equals i. Written once in setup, read-only afterwards.
  Object[] rids;

  // ---- Anomaly bookkeeping (epoch-off evidence) ----

  // Total read-path exceptions swallowed by benchmark methods. Zero on sound (baseline)
  // runs; non-zero values on epoch-off runs are the expected unsoundness evidence.
  final LongAdder swallowedReadAnomalies = new LongAdder();

  // Per-exception-class breakdown of swallowed read anomalies, reported at trial end.
  final ConcurrentHashMap<String, LongAdder> anomaliesByType = new ConcurrentHashMap<>();

  // Writer commit conflicts (e.g., MVCC version conflicts when several writers pick the
  // same record). Swallowed and counted so writer threads never crash the run.
  final LongAdder writeConflicts = new LongAdder();

  // Snapshots taken at iteration start; deltas are printed at iteration end.
  private long fallbacksAtIterStart;
  private long stampAbortsAtIterStart;
  private long epochAbortsAtIterStart;
  private long anomaliesAtIterStart;
  private long conflictsAtIterStart;

  @Setup(Level.Trial)
  public void setup() {
    String dbPath = System.getProperty("epoch.db.path", "./target/epoch-bench-db");

    db = YourTracks.instance(dbPath);
    // Always start from a fresh dataset so every fork measures the same B-tree shape.
    if (db.exists(DB_NAME)) {
      db.drop(DB_NAME);
    }
    db.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");
    traversal = db.openTraversal(DB_NAME, "admin", "admin");

    createSchema();
    loadRecords();
    collectRids();

    log.info("Epoch benchmark state ready: {} records at {}", recordCount, dbPath);
  }

  private void createSchema() {
    traversal.executeInTx(g -> {
      g.yql("CREATE CLASS EpochDoc EXTENDS V").iterate();
      g.yql("CREATE PROPERTY EpochDoc.key LONG").iterate();
      g.yql("CREATE PROPERTY EpochDoc.stamp LONG").iterate();
      g.yql("CREATE PROPERTY EpochDoc.payload STRING").iterate();
      // UNIQUE index on key: the reader-side point lookup path (B-tree get).
      g.yql("CREATE INDEX EpochDoc.key ON EpochDoc(key) UNIQUE").iterate();
      // NOTUNIQUE index on stamp: mutated by every writer commit, so each commit is a
      // multi-page apply (record page + index remove + index insert) and bumps the
      // storage-wide ApplyPhaseEpoch.
      g.yql("CREATE INDEX EpochDoc.stamp ON EpochDoc(stamp) NOTUNIQUE").iterate();
    });
  }

  private void loadRecords() {
    long start = System.currentTimeMillis();
    for (int from = 0; from < recordCount; from += LOAD_BATCH_SIZE) {
      final int batchStart = from;
      final int batchEnd = Math.min(from + LOAD_BATCH_SIZE, recordCount);
      traversal.executeInTx(g -> {
        for (int k = batchStart; k < batchEnd; k++) {
          g.addV("EpochDoc")
              .property("key", (long) k)
              .property("stamp", (long) k)
              .property("payload", payloadFor(k))
              .iterate();
        }
      });
    }
    log.info("Loaded {} EpochDoc records in {}ms",
        recordCount, System.currentTimeMillis() - start);
  }

  /**
   * Collects the final RIDs of all records after commit. New records get temporary RIDs
   * inside the loading transactions, so the ids must be re-read once loading is done.
   */
  private void collectRids() {
    rids = new Object[recordCount];
    traversal.executeInTx(g -> g.V().hasLabel("EpochDoc").forEachRemaining(v -> {
      int key = ((Number) v.value("key")).intValue();
      rids[key] = v.id();
    }));
    for (int i = 0; i < recordCount; i++) {
      if (rids[i] == null) {
        throw new IllegalStateException("Missing RID for key " + i);
      }
    }
  }

  /** Deterministic ~64-byte payload so record size (and page layout) is stable. */
  private static String payloadFor(int key) {
    var sb = new StringBuilder(64);
    sb.append("payload-").append(key).append('-');
    while (sb.length() < 64) {
      sb.append('x');
    }
    return sb.toString();
  }

  /** Returns the RID of a uniformly random preloaded record. */
  Object randomRid() {
    return rids[ThreadLocalRandom.current().nextInt(recordCount)];
  }

  /** Returns a uniformly random key of a preloaded record. */
  long randomKey() {
    return ThreadLocalRandom.current().nextInt(recordCount);
  }

  /**
   * Records a swallowed read-path exception. Benchmark methods call this instead of
   * letting the exception crash the run — required for the deliberately UNSOUND
   * epoch-off measurement, where readers may observe inconsistent multi-page state and
   * fail in arbitrary ways (false misses, LinksConsistencyException, validation errors).
   */
  void recordReadAnomaly(RuntimeException e) {
    swallowedReadAnomalies.increment();
    anomaliesByType.computeIfAbsent(e.getClass().getName(), k -> new LongAdder())
        .increment();
  }

  /** Records a swallowed writer commit conflict. */
  void recordWriteConflict(@SuppressWarnings("unused") RuntimeException e) {
    writeConflicts.increment();
  }

  @Setup(Level.Iteration)
  public void snapshotCounters() {
    fallbacksAtIterStart = OptimisticReadStats.fallbacks();
    stampAbortsAtIterStart = OptimisticReadStats.stampAborts();
    epochAbortsAtIterStart = OptimisticReadStats.epochAborts();
    anomaliesAtIterStart = swallowedReadAnomalies.sum();
    conflictsAtIterStart = writeConflicts.sum();
  }

  @TearDown(Level.Iteration)
  public void printCounterDeltas() {
    // Printed to stdout so the numbers land next to JMH's own iteration output.
    System.out.printf(
        "  [epoch-stats] fallbacks=%d stampAborts=%d epochAborts=%d"
            + " swallowedReadAnomalies=%d writeConflicts=%d%n",
        OptimisticReadStats.fallbacks() - fallbacksAtIterStart,
        OptimisticReadStats.stampAborts() - stampAbortsAtIterStart,
        OptimisticReadStats.epochAborts() - epochAbortsAtIterStart,
        swallowedReadAnomalies.sum() - anomaliesAtIterStart,
        writeConflicts.sum() - conflictsAtIterStart);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (!anomaliesByType.isEmpty()) {
      log.warn("Swallowed read anomalies by type (UNSOUND run evidence):");
      anomaliesByType.forEach((type, count) -> log.warn("  {} = {}", type, count.sum()));
    }
    try {
      if (traversal != null) {
        traversal.close();
      }
    } catch (Exception e) {
      log.warn("Error closing traversal", e);
    }
    try {
      if (db != null) {
        db.close();
      }
    } catch (Exception e) {
      log.warn("Error closing database", e);
    }
  }
}
