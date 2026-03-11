package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Measures the throughput of a mixed read/write workload with and without concurrent
 * records GC. Each benchmark thread performs one update followed by one read per
 * invocation. When {@code gcEnabled=true}, a background thread runs
 * {@link AbstractStorage#periodicRecordsGc()} every 100 ms.
 *
 * <p>Comparing the throughput (ops/sec) between {@code gcEnabled=true} and
 * {@code gcEnabled=false} shows the GC overhead under sustained concurrent load.
 * The GC should not degrade write throughput by more than ~1-2%.
 *
 * <p>Run on a CCX33 Hetzner node for reproducible results.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(value = 1, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED"
})
@Threads(4) // Must match THREAD_COUNT.
public class RecordsGcThroughputBenchmark {

  private static final String DB_NAME = "gcThroughputBench";
  private static final int THREAD_COUNT = 4;
  private static final int RECORD_COUNT = 2000;

  @Param({"true", "false"})
  boolean gcEnabled;

  private YouTrackDBImpl youTrackDB;
  private AbstractStorage storage;
  private ScheduledExecutorService gcExecutor;
  private final AtomicInteger versionCounter = new AtomicInteger();
  private final AtomicInteger threadIdCounter = new AtomicInteger();

  /**
   * Per-thread state. Each JMH thread gets its own database session (required because
   * sessions are not thread-safe) and a local counter for cycling through record indices.
   * Counters are offset by thread ID so threads target non-overlapping record ranges,
   * minimizing MVCC conflicts.
   */
  @State(Scope.Thread)
  public static class ThreadState {
    DatabaseSessionEmbedded session;
    int counter;

    @Setup(Level.Trial)
    public void setup(RecordsGcThroughputBenchmark benchState) {
      session = benchState.youTrackDB.open(
          DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD);
      // Offset each thread's starting index so threads cycle through
      // non-overlapping ranges (RECORD_COUNT / THREAD_COUNT per thread).
      counter = benchState.threadIdCounter.getAndIncrement()
          * (RECORD_COUNT / THREAD_COUNT);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      session.close();
    }
  }

  @Setup(Level.Trial)
  public void setup() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(RecordsGcThroughputBenchmark.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
            PredefinedLocalRole.ADMIN));

    try (var session = youTrackDB.open(
        DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD)) {
      storage = (AbstractStorage) session.getStorage();

      // Set aggressive GC thresholds when enabled, high thresholds otherwise.
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
      if (gcEnabled) {
        storage.getContextConfiguration()
            .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
        storage.getContextConfiguration()
            .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
      } else {
        storage.getContextConfiguration()
            .setValue(
                GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD,
                Integer.MAX_VALUE);
      }

      session.command("CREATE CLASS ThroughputBench");

      session.begin();
      for (int i = 0; i < RECORD_COUNT; i++) {
        session.command(
            "INSERT INTO ThroughputBench SET idx = " + i + ", ver = 0");
      }
      session.commit();
    }

    // Start background GC scheduler when enabled.
    if (gcEnabled) {
      gcExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "gc-benchmark-thread");
        t.setDaemon(true);
        return t;
      });
      gcExecutor.scheduleWithFixedDelay(
          () -> storage.periodicRecordsGc(),
          100, 100, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Mixed workload: update one record then read one record. Each invocation exercises
   * both the write path (with dirty bit set) and the read path. Multiple threads run
   * concurrently with offset counters so they target non-overlapping record ranges,
   * minimizing MVCC conflicts. Residual conflicts (e.g., on shared internal metadata)
   * are retried transparently.
   */
  @Benchmark
  public void mixedWorkload(ThreadState state) {
    int idx = state.counter % RECORD_COUNT;
    int ver = versionCounter.incrementAndGet();
    state.counter++;

    // Write: update a single record. Retry on MVCC conflict (rare with offset
    // counters, but possible on shared internal collections like schema metadata).
    for (int retry = 0; retry < 3; retry++) {
      try {
        state.session.begin();
        state.session.command(
            "UPDATE ThroughputBench SET ver = " + ver + " WHERE idx = " + idx);
        state.session.commit();
        break;
      } catch (ConcurrentModificationException e) {
        state.session.rollback();
      }
    }

    // Read: query a single record.
    state.session.begin();
    try {
      state.session.query(
          "SELECT FROM ThroughputBench WHERE idx = " + idx).close();
      state.session.commit();
    } catch (Exception e) {
      state.session.rollback();
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (gcExecutor != null) {
      gcExecutor.shutdownNow();
      try {
        gcExecutor.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    youTrackDB.close();
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(RecordsGcThroughputBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
