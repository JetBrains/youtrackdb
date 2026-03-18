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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Measures the write-path overhead introduced by the dirty page bit set. Compares the
 * per-operation cost of <em>updating</em> an existing record (which triggers
 * {@code keepPreviousRecordVersion()} and sets the dirty bit) against <em>inserting</em>
 * a new record (no dirty bit set involvement).
 *
 * <p>Both benchmarks execute a single SQL command inside a transaction per invocation.
 * The difference in average time approximates the overhead of the dirty page bit set on
 * the write path. A negligible delta validates the design goal.
 *
 * <p>Run on a CCX33 Hetzner node for reproducible results.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
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
@Threads(1)
public class RecordsGcWritePathBenchmark {

  private static final String DB_NAME = "gcWritePathBench";
  private static final int RECORD_COUNT = 1000;

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private int updateCounter;
  private int insertCounter;

  @Setup(Level.Trial)
  public void setup() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(RecordsGcWritePathBenchmark.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
            PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD);

    // Disable automatic GC so it does not interfere with measurements.
    var storage = (AbstractStorage) session.getStorage();
    storage.getContextConfiguration()
        .setValue(
            GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD,
            Integer.MAX_VALUE);

    session.command("CREATE CLASS WriteBench");
    session.command("CREATE PROPERTY WriteBench.idx INTEGER");
    session.command("CREATE INDEX WriteBench.idx ON WriteBench (idx) UNIQUE");

    // Pre-populate records for the update benchmark.
    session.begin();
    for (int i = 0; i < RECORD_COUNT; i++) {
      session.command("INSERT INTO WriteBench SET idx = " + i + ", ver = 0");
    }
    session.commit();

    updateCounter = 0;
    insertCounter = RECORD_COUNT;
  }

  /**
   * Updates a single existing record. This triggers {@code keepPreviousRecordVersion()}
   * which stores the old version in the snapshot index and sets the dirty page bit.
   */
  @Benchmark
  public void updateRecord() {
    int idx = updateCounter % RECORD_COUNT;
    updateCounter++;
    session.begin();
    session.command(
        "UPDATE WriteBench SET ver = " + updateCounter + " WHERE idx = " + idx);
    session.commit();
  }

  /**
   * Inserts a new record. No snapshot index entry or dirty page bit is involved — this
   * serves as the baseline to isolate the overhead of the dirty bit set on the update path.
   *
   * <p>Note: the collection grows over the measurement window. With ~50s of measurement
   * time and typical insert rates, the collection may reach tens of thousands of records.
   * This drift is acceptable because the insert cost is dominated by the transaction and
   * WAL overhead, not the collection size.
   */
  @Benchmark
  public void insertRecord() {
    insertCounter++;
    session.begin();
    session.command(
        "INSERT INTO WriteBench SET idx = " + insertCounter + ", ver = 0");
    session.commit();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    session.close();
    youTrackDB.close();
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(RecordsGcWritePathBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
