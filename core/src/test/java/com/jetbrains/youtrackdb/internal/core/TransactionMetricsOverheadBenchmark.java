package com.jetbrains.youtrackdb.internal.core;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.TransactionMetricsListener;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/// Measures the performance overhead of transaction monitoring on a minimal write transaction
/// (`g.addV("V").iterate()`). Compares baseline (no monitoring) against LIGHTWEIGHT and EXACT
/// modes, each with a no-op listener and a listener that reads the tracking ID.
@SuppressWarnings("unused")
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class TransactionMetricsOverheadBenchmark {

  private static final String DB_NAME = "tx-metrics-benchmark";

  private YouTrackDB youTrackDB;
  private YTDBGraphTraversalSource g;

  private static final TransactionMetricsListener NOOP_LISTENER =
      new TransactionMetricsListener() {
      };

  private static final TransactionMetricsListener READING_LISTENER =
      new TransactionMetricsListener() {
        @Override
        public void writeTransactionCommitted(
            TransactionDetails txDetails, long commitAtMillis, long commitTimeNanos) {
          // Force materialization of the tracking ID
          txDetails.getTransactionTrackingId();
        }
      };

  @Setup(Level.Trial)
  public void setup() {
    youTrackDB = YourTracks.instance("./target/databases/TransactionMetricsOverheadBenchmark");
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.MEMORY, "admin", "admin", "admin");
    g = g();
  }

  private @NonNull YTDBGraphTraversalSource g() {
    return youTrackDB.openTraversal(DB_NAME, "admin", "admin");
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    g.close();
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
  }

  @Benchmark
  public void baseline_noMonitoring() {
    g.tx().open();
    g.addV("V").iterate();
    g.tx().commit();
  }

  @Benchmark
  public void lightweight_noopListener() {
    ((YTDBTransaction) g.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
        .withTransactionListener(NOOP_LISTENER);
    g.tx().open();
    g.addV("V").iterate();
    g.tx().commit();
  }

  @Benchmark
  public void lightweight_readingListener() {
    ((YTDBTransaction) g.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
        .withTransactionListener(READING_LISTENER);
    g.tx().open();
    g.addV("V").iterate();
    g.tx().commit();
  }

  @Benchmark
  public void exact_noopListener() {
    ((YTDBTransaction) g.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withTransactionListener(NOOP_LISTENER);
    g.tx().open();
    g.addV("V").iterate();
    g.tx().commit();
  }

  @Benchmark
  public void exact_readingListener() {
    ((YTDBTransaction) g.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withTransactionListener(READING_LISTENER);
    g.tx().open();
    g.addV("V").iterate();
    g.tx().commit();
  }

  public static void main(String[] args) throws RunnerException {
    final var opt = new OptionsBuilder()
        .include("TransactionMetricsOverheadBenchmark.*")
        .build();
    new Runner(opt).run();
  }
}
