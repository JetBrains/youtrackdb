package com.jetbrains.youtrackdb.benchmarks.epoch;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * YTDB-1203 apply-phase-epoch ceiling benchmark: measures the cost the storage-wide
 * {@code ApplyPhaseEpoch} imposes on optimistic point readers under concurrent writer
 * commits, and the baseline writer commit throughput (the denominator for the &lt;1%
 * sort-overhead budget of the future page-level implementation).
 *
 * <p>Workload: one embedded DISK database with {@code recordCount} small records
 * (see {@link EpochReadWriteState}). Readers do point lookups — either a direct record
 * load by RID ({@code g.V(rid)}) or an index get ({@code SELECT ... WHERE key = :k},
 * UNIQUE B-tree) — both of which run on the optimistic read path. Writers update the
 * indexed {@code stamp} property of a random record in a small transaction; every commit
 * is a multi-page apply (record page + index remove/insert) and bumps the epoch.
 *
 * <p>Benchmark shapes:
 *
 * <ul>
 *   <li>{@code readOnlyByRid} / {@code readOnlyByIndex} — plain benchmarks for the
 *       writers=0 axis; reader thread count via {@code -t N};
 *   <li>{@code writeOnlyCommit} — plain benchmark, baseline writer commit throughput;
 *       writer thread count via {@code -t N};
 *   <li>{@code mixedRid_*} / {@code mixedIndex_*} — asymmetric groups (readers +
 *       writers); the default split is 4 readers / 1 writer, overridable with
 *       {@code -tg R,W} (methods are ordered read-then-write alphabetically, which is
 *       the order {@code -tg} follows).
 * </ul>
 *
 * <p>Recommended parameter axes for the Hetzner measurement runs: readers {1,4,16} ×
 * writers {0,1,4}:
 *
 * <pre>
 *   # writers = 0 axis
 *   java -jar ... "Epoch.*readOnly.*" -t 1   # 4, 16
 *   # writer baseline (budget denominator)
 *   java -jar ... "Epoch.*writeOnlyCommit" -t 1  # and -t 4
 *   # mixed axes
 *   java -jar ... "Epoch.*mixed.*" -tg 1,1   # 4,1  16,1  1,4  4,4  16,4
 *   # reader latency percentiles
 *   java -jar ... "Epoch.*mixed.*" -tg 4,1 -bm sample
 * </pre>
 *
 * <p>Evidence: reader methods report swallowed read anomalies via {@link ReaderCounters}
 * (JMH secondary metrics; {@code AuxCounters.Type.EVENTS} counters are emitted in all
 * benchmark modes, including sample mode), writers report commit conflicts via
 * {@link WriterCounters}, and {@link EpochReadWriteState} prints per-iteration deltas of
 * the optimistic-read abort counters ({@code fallbacks} / {@code stampAborts} /
 * {@code epochAborts}) from {@code OptimisticReadStats}.
 *
 * <p>Anomaly handling: on deliberately UNSOUND epoch-off runs (reader-side epoch check
 * disabled via the local-only {@code /tmp/ytdb-1203-epoch-off.patch}) readers can observe
 * inconsistent multi-page state and fail arbitrarily. All read-path
 * {@link RuntimeException}s are therefore counted and swallowed instead of crashing the
 * run; on sound baseline runs these counters must be zero.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(value = 2, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
})
public class EpochReadWriteBenchmark {

  /**
   * Per-reader-thread event counters, reported as JMH secondary metrics.
   * {@code readAnomalies} counts swallowed read-path exceptions — the unsoundness
   * evidence for epoch-off runs; must be zero on baseline runs.
   */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  public static class ReaderCounters {

    public long readAnomalies;
  }

  /**
   * Per-writer-thread event counters. {@code writeConflicts} counts swallowed commit
   * conflicts (e.g., two writers picking the same record).
   */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  public static class WriterCounters {

    public long writeConflicts;
  }

  // ==================== Operation bodies ====================

  /**
   * Point read of a random record by RID — exercises the record-load optimistic path
   * (PaginatedCollectionV2). Swallows and counts read-path exceptions (see class doc).
   */
  private static Object readByRid(EpochReadWriteState state, ReaderCounters counters) {
    final Object rid = state.randomRid();
    try {
      return state.traversal.computeInTx(g -> g.V(rid).values("payload").next());
    } catch (RuntimeException e) {
      counters.readAnomalies++;
      state.recordReadAnomaly(e);
      return null;
    }
  }

  /**
   * Point read of a random record via the UNIQUE key index — exercises the B-tree get
   * optimistic path. Swallows and counts read-path exceptions (see class doc).
   */
  private static List<?> readByIndex(EpochReadWriteState state, ReaderCounters counters) {
    final long key = state.randomKey();
    try {
      return state.traversal.computeInTx(
          g -> g.yql("SELECT FROM EpochDoc WHERE key = :k", "k", key).toList());
    } catch (RuntimeException e) {
      counters.readAnomalies++;
      state.recordReadAnomaly(e);
      return null;
    }
  }

  /**
   * Small transactional commit: updates the indexed {@code stamp} property of a random
   * record. The commit applies multiple pages (record page + index B-tree pages) and
   * therefore bumps the ApplyPhaseEpoch. Commit conflicts are swallowed and counted so
   * concurrent writers never crash the run.
   */
  private static void writeCommit(EpochReadWriteState state, WriterCounters counters) {
    final Object rid = state.randomRid();
    final long newStamp = ThreadLocalRandom.current().nextLong();
    try {
      state.traversal.executeInTx(g -> g.V(rid).property("stamp", newStamp).iterate());
    } catch (RuntimeException e) {
      counters.writeConflicts++;
      state.recordWriteConflict(e);
    }
  }

  // ==================== writers = 0 axis (plain benchmarks, -t N) ====================

  /** Reader-only baseline, record load by RID. Thread count = readers axis via -t. */
  @Benchmark
  public Object readOnlyByRid(EpochReadWriteState state, ReaderCounters counters) {
    return readByRid(state, counters);
  }

  /** Reader-only baseline, index point get. Thread count = readers axis via -t. */
  @Benchmark
  public Object readOnlyByIndex(EpochReadWriteState state, ReaderCounters counters) {
    return readByIndex(state, counters);
  }

  // ==================== writer baseline (budget denominator) ====================

  /**
   * Writer-only commit throughput — the denominator for the &lt;1% commit-time
   * sort-overhead budget of the future page-level implementation.
   */
  @Benchmark
  public void writeOnlyCommit(EpochReadWriteState state, WriterCounters counters) {
    writeCommit(state, counters);
  }

  // ==================== mixed groups (readers + writers, -tg R,W) ====================

  /** Mixed workload reader (by RID); default 4 reader threads, override with -tg. */
  @Benchmark
  @Group("mixedRid")
  @GroupThreads(4)
  public Object mixedRid_read(EpochReadWriteState state, ReaderCounters counters) {
    return readByRid(state, counters);
  }

  /** Mixed workload writer; default 1 writer thread, override with -tg. */
  @Benchmark
  @Group("mixedRid")
  @GroupThreads(1)
  public void mixedRid_write(EpochReadWriteState state, WriterCounters counters) {
    writeCommit(state, counters);
  }

  /** Mixed workload reader (by index); default 4 reader threads, override with -tg. */
  @Benchmark
  @Group("mixedIndex")
  @GroupThreads(4)
  public Object mixedIndex_read(EpochReadWriteState state, ReaderCounters counters) {
    return readByIndex(state, counters);
  }

  /** Mixed workload writer; default 1 writer thread, override with -tg. */
  @Benchmark
  @Group("mixedIndex")
  @GroupThreads(1)
  public void mixedIndex_write(EpochReadWriteState state, WriterCounters counters) {
    writeCommit(state, counters);
  }
}
