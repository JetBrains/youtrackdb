package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Regression for the mixed-mode {@code buildInitialHistogram} on both the
 * single-value and multi-value B-tree index engines. The contract under test
 * is the structural-impossibility claim scoped to count counters: a
 * rolled-back {@code buildInitialHistogram} call must leave the in-memory
 * {@code AtomicLong} counters at their pre-recalibration value AND must let
 * the WAL revert the persisted entry-point page writes. A separate success
 * path lands both sides on the post-recalibration target. The persisted-side
 * verification routes through a close-and-reopen cycle: the reopen's
 * {@code load()} recalibrates the in-memory counters from the persisted
 * entry-point pages, so equality between the post-restart reads and the
 * expected values proves the persisted side matches.
 *
 * <p>Scope: the count counters {@code approximateIndexEntriesCount} and
 * {@code approximateNullCount} on each engine. The {@link
 * com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager}
 * CHM cache snapshots installed by the histogram-build call are NOT reverted
 * on rollback (a heap-only cache rebuilt on next storage open), and that
 * divergence is named as out-of-scope by the {@code buildInitialHistogram}
 * mixed-mode design; this test does not pin the CHM-cache shape.
 *
 * <p>Test-side wrapper of {@code executeInsideAtomicOperation}: each test
 * invokes {@code engine.buildInitialHistogram(op)} inside the lambda body
 * and then re-throws {@code IOException} to drive rollback through the
 * AOM's wrapper catch. The catch rewraps the {@code IOException} as a
 * {@code StorageException} (a {@code RuntimeException} via
 * {@code BaseException}), so the test catches {@code StorageException} on
 * the rollback path, not {@code IOException}. This shape bypasses the
 * production-side {@code IndexAbstract.buildHistogramAfterFill} catch (which
 * swallows {@code IOException} and logs a warning); the test exercises the
 * structural rollback contract on the engine, not the swallow.
 *
 * <p>Histogram-build threshold pinning. {@link
 * com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager#buildHistogram}
 * takes an early-exit path when {@code totalCount - nullCount <
 * histogramMinSize}: it returns the {@code nonNullCount} derived from the
 * in-memory total without scanning the key stream. With the default
 * threshold of 1000, our small fixture indexes (a few entries) would not
 * trigger the real scan and {@code scannedNonNull} would echo the diverged
 * in-memory total, collapsing the recalibration delta to zero. The tests
 * pin {@code QUERY_STATS_HISTOGRAM_MIN_SIZE} to 1 so the manager always
 * scans the actual B-tree leaves and the recalibration delta is observable.
 * Because {@code GlobalConfiguration} is JVM-wide singleton state, the
 * class is marked {@link SequentialTest} so surefire does not run it in
 * parallel with another class that mutates the same key.
 */
@Category(SequentialTest.class)
public class IndexAbstractBuildHistogramRollbackTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  // GlobalConfiguration overrides applied in @Before and restored in @After.
  // Linked map preserves insertion order so unrelated overrides land last
  // (none currently, but the helper composes cleanly with future additions).
  private final Map<GlobalConfiguration, Object> configOverrides =
      new LinkedHashMap<>();

  @Before
  public void before() {
    // Pin the histogram-build minimum size to 1 so the manager always
    // performs the actual key-stream scan and returns the real
    // {@code scannedNonNull}; otherwise the diverged in-memory total feeds
    // through the early-exit path and the recalibration delta degenerates
    // to zero (see class Javadoc).
    pinConfig(GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE, 1);

    // DISK type lets the post-rollback persisted-side assertion read the
    // entry-point pages through a close-and-reopen cycle. MEMORY would zero
    // the B-tree on restart, and the persisted check would degenerate.
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.DISK, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    if (db != null && !db.isClosed()) {
      db.close();
    }
    if (youTrackDB != null) {
      youTrackDB.close();
    }
    // Restore the pinned config values regardless of test outcome.
    configOverrides.forEach(GlobalConfiguration::setValue);
    configOverrides.clear();
  }

  private void pinConfig(GlobalConfiguration key, Object value) {
    configOverrides.putIfAbsent(key, key.getValue());
    key.setValue(value);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine: rollback contract
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Single-value rollback: the lambda runs {@code buildInitialHistogram}
   * then throws {@code IOException}. The in-memory {@code AtomicLong}
   * counters must retain their pre-recalibration values (the apply hook is
   * gated on {@code currentError == null}, so it skips on rollback), the
   * holder's {@code isApplied()} latch must be {@code false}, and the
   * persisted entry-point page must revert via WAL — verified through a
   * close-and-reopen cycle whose {@code load()} reseeds the in-mem counters
   * from the persisted EP page.
   */
  @Test
  public void singleValue_rollback_inMemRetained_persistedReverted() throws Exception {
    db.createClassIfNotExist("SvBhRb");
    var cls = db.getClass("SvBhRb");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("SvBhRb.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");
    var indexName = "SvBhRb.name";

    // Preparatory commit: 4 non-null entries. Counters land at (4, 0) on
    // both sides after the implicit buildHistogramAfterFill on first
    // creation. Subsequent buildInitialHistogram calls become a no-op when
    // counts match the scan; we drive divergence by direct in-mem mutation
    // below.
    db.begin();
    for (int i = 0; i < 4; i++) {
      db.newEntity("SvBhRb").setProperty("name", "entry_" + i);
    }
    db.commit();

    var engine = getBTreeIndexEngine(db, indexName);
    long[] preRb = readCountsViaEngine(engine);
    assertEquals("Preparatory total count", 4L, preRb[0]);
    assertEquals("Preparatory null count", 0L, preRb[1]);

    // Drive divergence: bump the in-mem counters to (10, 2) so the
    // recalibration delta is non-zero and the rollback contract is
    // observable. The persisted EP page still reads 4 / 0 — the in-mem
    // skew is the kind that real production exposes (e.g., underflow
    // clamp events on the engine's reportAndClampUnderflow path).
    engine.addToApproximateEntriesCount(6);
    engine.addToApproximateNullCount(2);

    // Capture the holder reference inside the lambda before it is dropped
    // along with the rolled-back AtomicOperation. The holder's isApplied()
    // latch is read after the rollback completes.
    var holderRef = new IndexCountDeltaHolder[1];

    var thrown = new IOException("simulated buildInitialHistogram failure");
    Throwable caught = null;
    try {
      db.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            engine.buildInitialHistogram(atomicOp);
            holderRef[0] = atomicOp.getIndexCountDeltas();
            throw thrown;
          });
    } catch (Throwable t) {
      caught = t;
    }
    // AOM wraps IOException as StorageException (a RuntimeException via
    // BaseException). The test catches the wrap, not the original.
    assertNotNull("rollback path must surface the wrapped failure", caught);
    assertThat(caught)
        .as("Caught throwable must be the AOM's StorageException wrap")
        .isInstanceOf(StorageException.class);

    // The holder existed inside the lambda — the recalibration recorded an
    // in-mem-only adjustment on the holder before the throw. After the
    // rollback the holder must NOT have isApplied() == true; Hook B is
    // gated on currentError == null and skipped its apply call.
    assertNotNull(
        "holder must have been created inside the lambda by accumulateInMemRecalibration",
        holderRef[0]);
    assertFalse(
        "Hook B's setApplied() latch must be false on the rollback path",
        holderRef[0].isApplied());

    // In-memory check via the engine accessors. Hook B is the sole writer
    // to the in-memory AtomicLongs under the consolidated lifecycle, so the
    // rollback path bypassing the apply gate leaves these untouched at the
    // diverged pre-recalibration values (10, 2).
    long[] postRb = readCountsViaEngine(engine);
    assertEquals(
        "In-memory total count must retain the pre-recalibration value",
        10L, postRb[0]);
    assertEquals(
        "In-memory null count must retain the pre-recalibration value",
        2L, postRb[1]);

    // Persisted check via forceDatabaseClose+reopen. A plain db.close() only
    // decrements the storage's session count; the engine and its in-memory
    // AtomicLong counters stay alive in the YouTrackDB manager. forceDatabase-
    // Close drops the storage entirely so the subsequent open() runs the
    // engine's load(), which reseeds approximateIndexEntriesCount from the
    // persisted EP page (sbTree.getApproximateEntriesCount). Equality with
    // the preparatory values proves the rolled-back setApproximateEntries-
    // Count write inside the atomic op did not leak into the durable page.
    db.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(dbName);
    db = (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    var enginePost = getBTreeIndexEngine(db, indexName);
    long[] postRestart = readCountsViaEngine(enginePost);
    assertEquals(
        "Persisted total count (sbTree EP) must revert to preparatory value",
        4L, postRestart[0]);
    assertEquals(
        "Persisted null count (sbTree EP) must revert to preparatory value",
        0L, postRestart[1]);
  }

  /**
   * Single-value success path: the lambda runs {@code buildInitialHistogram}
   * and returns normally. The in-memory counters must advance to the
   * post-recalibration target via Hook B, the holder's {@code isApplied()}
   * latch must be {@code true}, and the persisted entry-point page (read
   * after a close-and-reopen cycle) must match the post-recalibration
   * target.
   */
  @Test
  public void singleValue_success_inMemAndPersistedLandAtTarget() throws Exception {
    db.createClassIfNotExist("SvBhOk");
    var cls = db.getClass("SvBhOk");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("SvBhOk.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");
    var indexName = "SvBhOk.name";

    db.begin();
    for (int i = 0; i < 4; i++) {
      db.newEntity("SvBhOk").setProperty("name", "entry_" + i);
    }
    db.commit();

    var engine = getBTreeIndexEngine(db, indexName);
    // Drive divergence: bump in-mem to (10, 2) and rebuild. Target after
    // recalibration is (4, 0).
    engine.addToApproximateEntriesCount(6);
    engine.addToApproximateNullCount(2);

    var holderRef = new IndexCountDeltaHolder[1];
    db.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          engine.buildInitialHistogram(atomicOp);
          holderRef[0] = atomicOp.getIndexCountDeltas();
        });

    // The success path runs Hook B; the holder's apply latch is set.
    assertNotNull(
        "holder must have been created inside the lambda",
        holderRef[0]);
    assertTrue(
        "Hook B's setApplied() latch must be true on the success path",
        holderRef[0].isApplied());

    // In-memory advance: Hook B sums totalDelta + inMemAdjustTotal and
    // nullDelta + inMemAdjustNull. The recalibration delta on this path is
    // (4-10, 0-2) = (-6, -2). Post-apply: (10-6, 2-2) = (4, 0).
    long[] postSucceed = readCountsViaEngine(engine);
    assertEquals("In-memory total must land at target", 4L, postSucceed[0]);
    assertEquals("In-memory null must land at target", 0L, postSucceed[1]);

    // Persisted check via forceDatabaseClose+reopen. The inline
    // setApproximateEntriesCount write inside buildInitialHistogram lands
    // the absolute target (4) on the sbTree EP page; the reopen's load()
    // reseeds the in-mem AtomicLong from that page (a plain db.close()
    // would only decrement session count and reuse the live engine, so the
    // read would echo Hook B's in-memory advance rather than verify
    // persistence).
    db.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(dbName);
    db = (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    var enginePost = getBTreeIndexEngine(db, indexName);
    long[] postRestart = readCountsViaEngine(enginePost);
    assertEquals(
        "Persisted total count (sbTree EP) must equal the recalibrated target",
        4L, postRestart[0]);
    assertEquals(
        "Persisted null count (sbTree EP) must equal the recalibrated target",
        0L, postRestart[1]);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine: rollback contract
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Multi-value rollback: same shape as the single-value rollback test, but
   * the engine has two trees (svTree + nullTree) and the persisted-side
   * absolute writes are issued separately on each. A WAL revert that
   * dropped one tree's write while replaying the other would surface as a
   * drifted post-restart counter pair.
   */
  @Test
  public void multiValue_rollback_inMemRetained_persistedReverted() throws Exception {
    db.createClassIfNotExist("MvBhRb");
    var cls = db.getClass("MvBhRb");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("MvBhRb.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");
    var indexName = "MvBhRb.tag";

    // Preparatory commit: 3 non-null + 1 null entry. Counters land at
    // (total = 4, null = 1) on both sides.
    db.begin();
    for (int i = 0; i < 3; i++) {
      db.newEntity("MvBhRb").setProperty("tag", "tag_" + i);
    }
    db.newEntity("MvBhRb");
    db.commit();

    var engine = getBTreeIndexEngine(db, indexName);
    long[] preRb = readCountsViaEngine(engine);
    assertEquals("Preparatory total count", 4L, preRb[0]);
    assertEquals("Preparatory null count", 1L, preRb[1]);

    // Drive divergence: bump in-mem to (20, 5) so the recalibration delta
    // is large and the rollback contract is observable.
    engine.addToApproximateEntriesCount(16);
    engine.addToApproximateNullCount(4);

    var holderRef = new IndexCountDeltaHolder[1];
    var thrown = new IOException("simulated buildInitialHistogram failure");
    Throwable caught = null;
    try {
      db.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            engine.buildInitialHistogram(atomicOp);
            holderRef[0] = atomicOp.getIndexCountDeltas();
            throw thrown;
          });
    } catch (Throwable t) {
      caught = t;
    }
    assertNotNull("rollback path must surface the wrapped failure", caught);
    assertThat(caught)
        .as("Caught throwable must be the AOM's StorageException wrap")
        .isInstanceOf(StorageException.class);

    assertNotNull(
        "holder must have been created inside the lambda",
        holderRef[0]);
    assertFalse(
        "Hook B's setApplied() latch must be false on the rollback path",
        holderRef[0].isApplied());

    long[] postRb = readCountsViaEngine(engine);
    assertEquals(
        "In-memory total count must retain the pre-recalibration value",
        20L, postRb[0]);
    assertEquals(
        "In-memory null count must retain the pre-recalibration value",
        5L, postRb[1]);

    // The MV engine writes both svTree and nullTree EP pages inside
    // buildInitialHistogram. WAL must revert both. forceDatabaseClose drops
    // the live storage so the subsequent open() actually reseeds the in-mem
    // AtomicLongs from the persisted EP pages via the engine's load() — a
    // plain db.close() only decrements the session count.
    db.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(dbName);
    db = (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    var enginePost = getBTreeIndexEngine(db, indexName);
    long[] postRestart = readCountsViaEngine(enginePost);
    assertEquals(
        "Persisted total count (svTree EP) must revert to preparatory value",
        4L, postRestart[0]);
    assertEquals(
        "Persisted null count (nullTree EP) must revert to preparatory value",
        1L, postRestart[1]);
  }

  /**
   * Multi-value success path: same shape as the single-value success test.
   * Both persisted absolute writes land at their respective targets, the
   * in-memory counters advance via Hook B, and the apply latch is set.
   */
  @Test
  public void multiValue_success_inMemAndPersistedLandAtTarget() throws Exception {
    db.createClassIfNotExist("MvBhOk");
    var cls = db.getClass("MvBhOk");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("MvBhOk.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");
    var indexName = "MvBhOk.tag";

    db.begin();
    for (int i = 0; i < 3; i++) {
      db.newEntity("MvBhOk").setProperty("tag", "tag_" + i);
    }
    db.newEntity("MvBhOk");
    db.commit();

    var engine = getBTreeIndexEngine(db, indexName);
    engine.addToApproximateEntriesCount(16);
    engine.addToApproximateNullCount(4);

    var holderRef = new IndexCountDeltaHolder[1];
    db.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          engine.buildInitialHistogram(atomicOp);
          holderRef[0] = atomicOp.getIndexCountDeltas();
        });

    assertNotNull(
        "holder must have been created inside the lambda",
        holderRef[0]);
    assertTrue(
        "Hook B's setApplied() latch must be true on the success path",
        holderRef[0].isApplied());

    long[] postSucceed = readCountsViaEngine(engine);
    assertEquals("In-memory total must land at target", 4L, postSucceed[0]);
    assertEquals("In-memory null must land at target", 1L, postSucceed[1]);

    // forceDatabaseClose drops the live storage so the subsequent open()
    // runs the engine's load() and reseeds the in-mem AtomicLongs from the
    // persisted EP pages (svTree and nullTree). A plain db.close() would
    // only decrement the session count and reuse Hook B's already-applied
    // in-memory advance.
    db.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(dbName);
    db = (DatabaseSessionEmbedded) youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    var enginePost = getBTreeIndexEngine(db, indexName);
    long[] postRestart = readCountsViaEngine(enginePost);
    assertEquals(
        "Persisted total count (svTree EP) must equal the recalibrated target",
        4L, postRestart[0]);
    assertEquals(
        "Persisted null count (nullTree EP) must equal the recalibrated target",
        1L, postRestart[1]);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Crash recovery: WAL replay re-applies the persisted-side absolute writes
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Crash-recovery WAL-replay: drive a successful {@code
   * buildInitialHistogram} commit, force the database closed before any
   * checkpoint can flush the dirty pages, then reopen. WAL replay must
   * re-apply the two persisted-side absolute writes (svTree EP and nullTree
   * EP) so the post-replay load() reads the recalibrated target.
   *
   * <p>The MV engine is chosen because it exercises the two-tree write
   * pattern; a regression where WAL replay reordered or dropped one of the
   * writes would surface as a drifted post-restart counter pair.
   */
  @Test
  public void crashRecovery_walReplayLandsRecalibratedTarget() throws Exception {
    var crashDbName = "crash_buildhist_" + UUID.randomUUID();
    youTrackDB.create(crashDbName, DatabaseType.DISK,
        "admin", DbTestBase.ADMIN_PASSWORD, "admin");
    var crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(
            crashDbName, "admin", DbTestBase.ADMIN_PASSWORD);

    try {
      crashSession.createClassIfNotExist("CrashBh");
      var cls = crashSession.getClass("CrashBh");
      cls.createProperty("tag", PropertyType.STRING);
      cls.createIndex("CrashBh.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");
      var indexName = "CrashBh.tag";

      crashSession.begin();
      for (int i = 0; i < 5; i++) {
        crashSession.newEntity("CrashBh").setProperty("tag", "tag_" + i);
      }
      crashSession.newEntity("CrashBh");
      crashSession.commit();

      var engine = getBTreeIndexEngine(crashSession, indexName);
      // Drive divergence and run a successful buildInitialHistogram. Target
      // after recalibration is (6, 1).
      engine.addToApproximateEntriesCount(25);
      engine.addToApproximateNullCount(5);

      crashSession.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(engine::buildInitialHistogram);

      // Simulate non-graceful shutdown: no flush, no checkpoint. The WAL
      // record for the two setApproximateEntriesCount calls survives.
      crashSession.activateOnCurrentThread();
      youTrackDB.internal.forceDatabaseClose(crashDbName);

      // Reopen — WAL replay re-applies the persisted writes.
      crashSession =
          (DatabaseSessionEmbedded) youTrackDB.open(
              crashDbName, "admin", DbTestBase.ADMIN_PASSWORD);

      var enginePost = getBTreeIndexEngine(crashSession, indexName);
      long[] postReplay = readCountsViaEngine(crashSession, enginePost);
      assertEquals(
          "Persisted total count must equal the recalibrated target after WAL replay",
          6L, postReplay[0]);
      assertEquals(
          "Persisted null count must equal the recalibrated target after WAL replay",
          1L, postReplay[1]);
    } finally {
      if (!crashSession.isClosed()) {
        crashSession.close();
      }
      youTrackDB.drop(crashDbName);
    }
  }

  /**
   * Reload-via-{@code load()} convergence: simulates a crash after a
   * successful {@code buildInitialHistogram} commit. After the crash and
   * reopen, the in-memory {@code AtomicLong} counters are reseeded by
   * {@code load()} from the persisted entry-point pages. The persisted side
   * already landed at the recalibrated target inside the committed
   * transaction, so the post-restart in-mem read must equal that target
   * regardless of whether the original Hook B's in-mem apply ever ran on
   * the pre-crash JVM.
   *
   * <p>The crash here is approximated by {@code forceDatabaseClose}: the JVM
   * state — including the in-mem {@code AtomicLong} values that Hook B
   * mutated — is discarded on the close, and the reopen rebuilds the in-mem
   * counters from the persisted EP page. A regression where the in-mem
   * apply was the sole source of truth (and the persisted side did not
   * land the absolute target) would surface here as a stale post-restart
   * counter pair.
   */
  @Test
  public void reloadViaLoad_inMemReseededFromPersistedTarget() throws Exception {
    var crashDbName = "reload_buildhist_" + UUID.randomUUID();
    youTrackDB.create(crashDbName, DatabaseType.DISK,
        "admin", DbTestBase.ADMIN_PASSWORD, "admin");
    var crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(
            crashDbName, "admin", DbTestBase.ADMIN_PASSWORD);

    try {
      crashSession.createClassIfNotExist("ReloadBh");
      var cls = crashSession.getClass("ReloadBh");
      cls.createProperty("name", PropertyType.STRING);
      cls.createIndex("ReloadBh.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");
      var indexName = "ReloadBh.name";

      crashSession.begin();
      for (int i = 0; i < 7; i++) {
        crashSession.newEntity("ReloadBh").setProperty("name", "entry_" + i);
      }
      crashSession.commit();

      var engine = getBTreeIndexEngine(crashSession, indexName);
      engine.addToApproximateEntriesCount(12);
      engine.addToApproximateNullCount(3);

      crashSession.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(engine::buildInitialHistogram);

      // The in-mem counters on the live engine reflect the post-Hook-B
      // apply (7, 0). Force-close discards the JVM state including these
      // AtomicLongs.
      long[] preCrash = readCountsViaEngine(crashSession, engine);
      assertEquals("In-mem total after apply", 7L, preCrash[0]);
      assertEquals("In-mem null after apply", 0L, preCrash[1]);

      crashSession.activateOnCurrentThread();
      youTrackDB.internal.forceDatabaseClose(crashDbName);

      crashSession =
          (DatabaseSessionEmbedded) youTrackDB.open(
              crashDbName, "admin", DbTestBase.ADMIN_PASSWORD);

      // load() seeds the in-mem AtomicLongs from the persisted EP page.
      // The persisted side landed at the recalibrated target inside the
      // committed transaction, so the reseed must produce (7, 0).
      var enginePost = getBTreeIndexEngine(crashSession, indexName);
      long[] postReload = readCountsViaEngine(crashSession, enginePost);
      assertEquals(
          "In-mem total reseeded by load() must equal the recalibrated target",
          7L, postReload[0]);
      assertEquals(
          "In-mem null reseeded by load() must equal the recalibrated target",
          0L, postReload[1]);
    } finally {
      if (!crashSession.isClosed()) {
        crashSession.close();
      }
      youTrackDB.drop(crashDbName);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Pre-existing test failure path: the test wrapper's IOException survives
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Sanity test that the AOM's IOException-to-StorageException wrap is
   * actually exercised by the test scaffold: a lambda that throws an
   * IOException without first calling buildInitialHistogram must produce
   * the same StorageException wrap as the buildInitialHistogram rollback
   * tests above. Guards against a regression where a future AOM rewrite
   * routes IOException differently and the rollback tests above silently
   * stop catching the throw.
   */
  @Test
  public void rollbackPath_aomWrapsIOExceptionAsStorageException() throws Exception {
    db.createClassIfNotExist("WrapCheck");
    var thrown = new IOException("scaffold wrap check");
    try {
      db.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            throw thrown;
          });
      fail("expected the AOM wrap to surface the IOException");
    } catch (StorageException expected) {
      assertNotNull("wrap must carry the original IOException as cause",
          expected.getCause());
      assertEquals(
          "wrapped cause must be the test scaffold IOException",
          thrown, expected.getCause());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Reads {@code getTotalCount} and {@code getNullCount} from the engine via
   * an atomic-operation envelope. The accessors are O(1) reads of the
   * in-memory AtomicLongs.
   */
  private long[] readCountsViaEngine(BTreeIndexEngine engine) throws Exception {
    return readCountsViaEngine(db, engine);
  }

  /**
   * Variant of {@link #readCountsViaEngine(BTreeIndexEngine)} that targets a
   * specific session (used by the crash-recovery tests which open their own
   * sessions on per-test database names rather than the {@link #db} session).
   */
  private static long[] readCountsViaEngine(
      DatabaseSessionEmbedded session, BTreeIndexEngine engine) throws Exception {
    long[] out = new long[2];
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          out[0] = engine.getTotalCount(atomicOp);
          out[1] = engine.getNullCount(atomicOp);
        });
    return out;
  }

  /**
   * Resolves the named index to its {@link BTreeIndexEngine} via reflection
   * on the {@code indexId} field. Same shape as the helper in {@code
   * BTreeMultiValueIndexEngineClearRollbackTest} and {@code
   * BTreeEnginePersistedCountIT}.
   */
  private static BTreeIndexEngine getBTreeIndexEngine(
      DatabaseSessionEmbedded session, String indexName) {
    try {
      var idx = session.getSharedContext().getIndexManager().getIndex(indexName);
      var indexIdField = IndexAbstract.class.getDeclaredField("indexId");
      indexIdField.setAccessible(true);
      int indexId = indexIdField.getInt(idx);
      var storage = (AbstractStorage) session.getStorage();
      var getEngineMethod = AbstractStorage.class.getMethod("getIndexEngine", int.class);
      return (BTreeIndexEngine) getEngineMethod.invoke(storage, indexId);
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to resolve BTreeIndexEngine for " + indexName, e);
    }
  }
}
