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
 * Measures the per-pass cost of the records GC algorithm: dirty page scanning, stale
 * record identification, chunk chain deletion, and page defragmentation. Parameterized
 * by record count and number of update rounds (stale versions per record).
 *
 * <p>Each measurement is one full {@link PaginatedCollectionV2#collectDeadRecords} call.
 * The per-invocation setup creates dead records by updating all records and evicting their
 * snapshot entries (without triggering GC), so the benchmark isolates just the GC pass cost.
 *
 * <p>Run on a CCX33 Hetzner node for reproducible results.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, batchSize = 1)
@Measurement(iterations = 20, batchSize = 1)
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
public class RecordsGcOverheadBenchmark {

  private static final String DB_NAME = "gcOverheadBench";

  @Param({"500", "2000", "5000"})
  int recordCount;

  @Param({"1", "3"})
  int updateRounds;

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private AbstractStorage storage;
  private PaginatedCollectionV2 collection;
  private int versionCounter;

  @Setup(Level.Trial)
  public void setupTrial() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(RecordsGcOverheadBenchmark.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
            PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) session.getStorage();

    // Eager snapshot eviction, but disable GC trigger so periodicRecordsGc()
    // only evicts entries without running collectDeadRecords().
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(
            GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD,
            Integer.MAX_VALUE);

    session.command("CREATE CLASS GcOverheadBench");

    // Batch-insert initial records.
    session.begin();
    for (int i = 0; i < recordCount; i++) {
      session.command("INSERT INTO GcOverheadBench SET idx = " + i + ", ver = 0");
    }
    session.commit();

    collection = findCollection(session, storage, "GcOverheadBench");
    versionCounter = 0;
  }

  /**
   * Creates dead record versions by updating all records, then evicts their snapshot
   * entries so the dead records are ready for GC. The GC itself is NOT run here — that
   * is the measured operation in {@link #gcPass()}.
   *
   * <p>Each invocation produces exactly {@code recordCount * updateRounds} dead records
   * (plus ~1 from the LWM bump). The previous invocation's {@link #gcPass()} reclaims
   * all dead records, so no accumulation across invocations occurs — every GC pass
   * starts from a clean state.
   *
   * <p>{@code Level.Invocation} is appropriate here because we use
   * {@code SingleShotTime} mode: each "invocation" is measured individually as a single
   * shot, and the setup runs before the timer starts. The GC pass time (milliseconds)
   * is well above the nanosecond-level JMH infrastructure overhead.
   */
  @Setup(Level.Invocation)
  public void createDeadRecords() {
    for (int round = 0; round < updateRounds; round++) {
      versionCounter++;
      session.begin();
      session.command("UPDATE GcOverheadBench SET ver = " + versionCounter);
      session.commit();
    }

    // One more write to push the LWM past the last update's commit timestamp.
    // Eviction uses strict less-than (recordTs < lwm), so without this bump the
    // last round's snapshot entries would not be evictable.
    versionCounter++;
    session.begin();
    session.command(
        "UPDATE GcOverheadBench SET ver = " + versionCounter + " WHERE idx = 0");
    session.commit();

    // Evict snapshot entries (increments deadRecordCount) without running GC.
    storage.periodicRecordsGc();
  }

  /**
   * Runs one full GC pass over all dirty pages in the collection, deleting stale
   * record chunks and defragmenting pages. Returns the number of records reclaimed.
   */
  @Benchmark
  public long gcPass() {
    return collection.collectDeadRecords(storage.getSharedSnapshotIndex());
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    session.close();
    youTrackDB.close();
  }

  private static PaginatedCollectionV2 findCollection(
      DatabaseSessionEmbedded session, AbstractStorage storage, String className) {
    for (int cid : session.getClass(className).getCollectionIds()) {
      for (var coll : storage.getCollectionInstances()) {
        if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
          return pc;
        }
      }
    }
    throw new IllegalStateException("Collection not found: " + className);
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(RecordsGcOverheadBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
