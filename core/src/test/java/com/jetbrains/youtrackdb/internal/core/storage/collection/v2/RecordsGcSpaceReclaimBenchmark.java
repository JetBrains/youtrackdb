package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Measures space reclamation behavior at various GC trigger threshold settings. For each
 * invocation, a fixed batch of dead records is created, then {@code periodicRecordsGc()}
 * is run with the parameterized threshold settings. The benchmark reports both the GC
 * pass time and the number of records reclaimed (via return value).
 *
 * <p>This data is used to tune the default values for
 * {@link GlobalConfiguration#STORAGE_COLLECTION_GC_MIN_THRESHOLD} and
 * {@link GlobalConfiguration#STORAGE_COLLECTION_GC_SCALE_FACTOR}: the threshold should
 * be high enough to avoid thrashing on small bursts of updates, but low enough that
 * dead records do not accumulate unboundedly.
 *
 * <p>Run on a CCX33 Hetzner node for reproducible results.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, batchSize = 1)
@Measurement(iterations = 10, batchSize = 1)
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
public class RecordsGcSpaceReclaimBenchmark {

  private static final String DB_NAME = "gcSpaceReclaimBench";
  private static final int RECORD_COUNT = 1000;
  private static final int UPDATE_ROUNDS = 3;

  @Param({"0", "500", "1000"})
  int minThreshold;

  @Param({"0.0", "0.1", "0.2"})
  float scaleFactor;

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private AbstractStorage storage;
  private int versionCounter;

  @Setup(Level.Trial)
  public void setupTrial() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(RecordsGcSpaceReclaimBenchmark.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
            PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) session.getStorage();

    // Eager snapshot eviction so dead records become GC-ready promptly.
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

    session.command("CREATE CLASS SpaceReclaimBench");

    session.begin();
    for (int i = 0; i < RECORD_COUNT; i++) {
      session.command(
          "INSERT INTO SpaceReclaimBench SET idx = " + i + ", ver = 0");
    }
    session.commit();

    versionCounter = 0;
  }

  /**
   * Creates dead record versions by updating all records, evicts their snapshot entries,
   * and then applies the parameterized threshold settings. The benchmark method measures
   * whether GC triggers and how long the reclamation takes.
   *
   * <p>Each invocation produces a fixed batch of {@code RECORD_COUNT * UPDATE_ROUNDS}
   * dead records. The previous invocation's {@link #periodicGcWithThreshold()} reclaims
   * them (if the threshold was met), so no accumulation occurs across invocations.
   *
   * <p>{@code Level.Invocation} is appropriate here because we use
   * {@code SingleShotTime} mode — see {@link RecordsGcOverheadBenchmark} for rationale.
   */
  @Setup(Level.Invocation)
  public void createDeadRecords() {
    // First, ensure GC does not run during setup (high threshold).
    storage.getContextConfiguration()
        .setValue(
            GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD,
            Integer.MAX_VALUE);

    for (int round = 0; round < UPDATE_ROUNDS; round++) {
      versionCounter++;
      session.begin();
      session.command(
          "UPDATE SpaceReclaimBench SET ver = " + versionCounter);
      session.commit();
    }

    // Push LWM past the last update.
    versionCounter++;
    session.begin();
    session.command(
        "UPDATE SpaceReclaimBench SET ver = " + versionCounter + " WHERE idx = 0");
    session.commit();

    // Evict snapshot entries only (GC threshold is MAX_VALUE).
    storage.periodicRecordsGc();

    // Now apply the parameterized threshold settings for the measured GC pass.
    storage.getContextConfiguration()
        .setValue(
            GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, minThreshold);
    storage.getContextConfiguration()
        .setValue(
            GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, scaleFactor);
  }

  /**
   * Runs the full periodic GC task with the parameterized threshold settings. If the
   * dead record count exceeds the threshold, GC triggers and reclaims space. If not,
   * the method returns almost immediately (threshold check only).
   *
   * <p>The method has side effects (modifies storage state), so JMH will not eliminate
   * it via dead code elimination.
   */
  @Benchmark
  public void periodicGcWithThreshold() {
    storage.periodicRecordsGc();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    session.close();
    youTrackDB.close();
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(RecordsGcSpaceReclaimBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
