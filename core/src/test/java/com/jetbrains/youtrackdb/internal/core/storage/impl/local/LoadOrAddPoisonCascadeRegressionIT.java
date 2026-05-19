/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration regression test for the original "poison cascade" bug
 * (https://youtrack.jetbrains.com/issues/YTDB-823 et al.): a concurrent-insert workload on
 * a freshly-built indexed class would trip an
 * {@code IllegalStateException("Page X:Y was allocated in other thread")} that the storage
 * layer then translated into {@code StorageException("Page Y is broken in file …")}, after
 * which subsequent operations cascaded into "Internal error happened in storage" failures.
 * The structural fix (read-cache concurrency contract on {@code WOWCache.loadOrAdd}, the
 * I4 sentinel, and the per-component allocator-only AOBT contract) closes the discovery
 * channel that allowed the race; this test pins the closure end-to-end and end-to-cache.
 *
 * <p>Two scenarios are exercised here:
 *
 * <ol>
 *   <li><b>High-level concurrent-insert smoke test
 *       ({@link #concurrentInsertWorkloadCompletesWithoutPoisonCascade}).</b> Opens a
 *       fresh disk-mode {@code YouTrackDB} instance with {@code checksumMode=StoreAndThrow}
 *       (the magic-check leg of the bug is silenced when checksums are off — this leg
 *       must stay green under the production-default checksum stance). Creates a class
 *       with an indexed string property — its backing cluster's
 *       {@link com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2}
 *       and {@code CollectionPositionMapV2} are the canonical contention surfaces from the
 *       bug ticket. Spawns {@link #THREADS} concurrent transactional inserts (capped at
 *       16 per the Phase A risk-review consensus so dev workstations and CI runners
 *       produce comparable timing profiles), each thread runs {@link #ITERATIONS}
 *       inserts via {@code executeInTx}. Asserts none of the documented failure shapes
 *       fire (allocator-mismatch, magic-stamp, "Internal error happened in storage").
 *       After the workload finishes, reopens the storage and verifies every committed
 *       record is readable.
 *
 *       <p>Positive-evidence assertion: instrumented {@link LongAdder} counters on
 *       {@link WOWCache#getLoadOrAddExtendBranchInvocations} +
 *       {@link WOWCache#getLoadOrAddGapFillBranchInvocations} must show that the
 *       allocator path actually ran (combined count is at least {@link #THREADS} — a
 *       conservative floor that survives in-flight optimisations like the same-key
 *       wrapper fast-path). Without this, a future refactor that re-routed inserts away
 *       from the {@code loadOrAdd} extend / gap-fill branches would silently make the
 *       absence-of-symptom check "pass" for the wrong reason.
 *
 *   <li><b>Deterministic white-box reproducer
 *       ({@link #whiteBoxLoadOrAddSameKeyContentionConverges}).</b> A repeated-rounds
 *       harness driving two threads against the production read-cache wrapper's
 *       {@code loadOrAddForWrite} → {@code WOWCache.loadOrAdd} path under a
 *       {@link CyclicBarrier} start-gate, fresh fileId per round (so each round
 *       starts from {@code currentSize == 0} and the extend branch is the only legal
 *       allocator path), {@value #WHITE_BOX_ROUNDS} rounds. Each round runs both
 *       threads through the read cache with {@code verifyChecksums = true} (the
 *       production setting under {@code checksumMode=StoreAndThrow}). The wrapper's
 *       {@code data.compute} segment write lock serialises the two threads at the
 *       {@code AsyncFile.allocateSpace} / cache-pointer install boundary; both
 *       threads must observe the same {@link CachePointer} instance and the cache
 *       must end the round in a consistent state.
 *
 *       <p>Going through the wrapper rather than the bare {@link WOWCache#loadOrAdd}
 *       surface is intentional: the wrapper-coordinated path is what production
 *       inserts traverse, and the test pins the success-mode contract on that path
 *       (one extend, one published pointer, both readers observe it). The bare-surface
 *       I4-sentinel failure-mode test (where two threads bypass the wrapper and
 *       observe the {@code "allocated pageIndex … does not match"}
 *       {@link IllegalStateException}) lives in
 *       {@code WOWCacheLoadOrAddConcurrentTest.bareLoadOrAddOnSameKeySurfacesI4Sentinel}.
 *       Together the two tests cover the cache-layer fast-fail (bare) and the
 *       wrapper-coordinated success path (here).
 * </ol>
 *
 * <p><b>Verification protocol (recorded in the commit message).</b> The smoke test was
 * verified to fail on the unmodified develop branch by cherry-picking this commit onto
 * {@code origin/develop} and running with {@code ./mvnw -pl core clean test
 * -Dtest=LoadOrAddPoisonCascadeRegressionIT}; the precise reproduction rate is captured
 * in the squashed-PR description. On this branch (the structural-fix tree) the same
 * invocation passes 10/10 consecutive runs.
 *
 * <p><b>Sequential category.</b> The test runs under {@link SequentialTest} because it
 * sets {@code GlobalConfiguration.STORAGE_CHECKSUM_MODE} via the per-instance
 * configuration, opens a disk-mode YouTrackDB instance, and exercises the storage's
 * WOWCache primitive directly. Parallel forks touching the same build directory would
 * interfere with each other's file allocation accounting.
 */
@Category(SequentialTest.class)
public class LoadOrAddPoisonCascadeRegressionIT {

  /**
   * Concurrent-insert workload thread count. Capped per the Phase A risk-review
   * consensus at {@code min(availableProcessors() * 2, 16)} so dev workstations (24+
   * logical cores) and CI runners (typically 4-8 cores) produce comparable timing
   * profiles. The cap also keeps the per-thread allocator pressure within the
   * {@code wowCacheFlushExecutor}'s single-threaded drain capacity — a higher cap on
   * a beefy host could queue up flush tasks long enough to mask a regression in the
   * tear-down phase.
   */
  private static final int THREADS =
      Math.min(Runtime.getRuntime().availableProcessors() * 2, 16);

  /**
   * Per-thread insert count in the smoke test. {@value} iterations across
   * {@link #THREADS} threads gives total inserts ≥ 1600 — empirically enough to
   * surface the original race on the develop baseline with ≥ 90% reproduction rate
   * inside one minute on a CI runner, per the Phase A calibration evidence captured
   * in this branch's commit message.
   */
  private static final int ITERATIONS = 100;

  /**
   * Rounds of two-thread same-key contention in the deterministic white-box
   * reproducer. {@value} is high enough to hit the {@code allocateSpace} /
   * {@code putIfAbsent} race window many times per run while staying well within the
   * smoke-test class's overall time budget.
   */
  private static final int WHITE_BOX_ROUNDS = 100;

  /**
   * Bounded wait on every cross-thread barrier / latch. Workers should fly past these
   * within milliseconds; the timeout guards against a hung worker letting the test
   * stall instead of failing cleanly.
   */
  private static final long BARRIER_WAIT_SECONDS = 30L;

  private YouTrackDBImpl youTrackDB;
  private java.nio.file.Path directoryPath;

  @Before
  public void setUp() throws Exception {
    // Pre-clean: this test's two methods both create a fresh storage at the same
    // directoryPath, so a previous run's leftover (or a sibling test method's
    // leftover) must be wiped before each invocation.
    directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    if (java.nio.file.Files.exists(directoryPath)) {
      FileUtils.deleteDirectory(directoryPath.toFile());
    }
  }

  @After
  public void tearDown() throws Exception {
    if (youTrackDB != null && youTrackDB.isOpen()) {
      youTrackDB.close();
    }
    youTrackDB = null;
    if (directoryPath != null && java.nio.file.Files.exists(directoryPath)) {
      FileUtils.deleteDirectory(directoryPath.toFile());
    }
  }

  // ---------------------------------------------------------------------------
  // High-level concurrent-insert smoke test
  // ---------------------------------------------------------------------------

  /**
   * Drives a concurrent-insert workload against a disk-mode storage with
   * {@code checksumMode=StoreAndThrow}. Asserts no allocator-mismatch /
   * magic-stamp / cascade exception fires during the workload, all committed records
   * are visible after a clean reopen, and the {@code loadOrAdd} extend / gap-fill
   * counters confirm the allocator path actually ran.
   *
   * <p>A regression that re-introduced the original poison cascade (the bug ticket's
   * "Page X:Y was allocated in other thread" symptom) would surface here as a
   * propagated {@link IllegalStateException} or
   * {@link com.jetbrains.youtrackdb.internal.core.exception.StorageException} from
   * one of the worker threads. A regression that silently re-routed inserts away
   * from the {@code loadOrAdd} allocator path (and would let the absence-of-symptom
   * checks "pass" for the wrong reason) would surface as a counter-floor violation
   * below.
   */
  @Test
  public void concurrentInsertWorkloadCompletesWithoutPoisonCascade() throws Exception {
    var config = makeConfigWithChecksumStoreAndThrow();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(LoadOrAddPoisonCascadeRegressionIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    final long extendBranchBaseline;
    final long gapFillBranchBaseline;
    final long extendBranchAfterWorkload;
    final long gapFillBranchAfterWorkload;

    try (var initSession = youTrackDB.open(
        LoadOrAddPoisonCascadeRegressionIT.class.getSimpleName(),
        "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Indexed string property on PCV2-backed cluster — the canonical trigger surface
      // from the original bug report. The unique index forces every insert through both
      // the cluster's PaginatedCollectionV2 (data file, .pcl) and its embedded
      // CollectionPositionMapV2 (.cpm), as well as the index B-tree (.cbt / .sbt) —
      // maximising the number of file-extending paths contending for the
      // allocator.
      initSession.getMetadata().getSchema()
          .createClass("Person")
          .createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);

      // Baseline the counters AFTER schema creation: the createClass + createIndex
      // calls each extend several files, and counting those extends as "smoke
      // workload" extends would inflate the positive-evidence assertion below.
      var diskStorage = (DiskStorage) initSession.getStorage();
      var wowCache = (WOWCache) diskStorage.getWriteCache();
      extendBranchBaseline = wowCache.getLoadOrAddExtendBranchInvocations();
      gapFillBranchBaseline = wowCache.getLoadOrAddGapFillBranchInvocations();
    }

    // Concurrent-insert workload. Each worker opens its own session from the same
    // database; the per-session storage instance is shared via the YouTrackDBImpl
    // singleton, so all sessions contend on the same WOWCache.
    final var unexpected = new ConcurrentLinkedQueue<Throwable>();
    final var pool = Executors.newFixedThreadPool(THREADS);
    final var startBarrier = new CyclicBarrier(THREADS);
    final var threadIdGen = new AtomicInteger();
    try {
      final List<Callable<Void>> workers = new ArrayList<>(THREADS);
      for (var t = 0; t < THREADS; t++) {
        workers.add(
            () -> {
              final var threadId = threadIdGen.getAndIncrement();
              try {
                startBarrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
                try (var workerSession = youTrackDB.open(
                    LoadOrAddPoisonCascadeRegressionIT.class.getSimpleName(),
                    "admin", "admin")) {
                  for (var i = 0; i < ITERATIONS; i++) {
                    final var val = i;
                    workerSession.executeInTx(
                        tx -> {
                          var entity = tx.newEntity("Person");
                          entity.setProperty("name", "name-" + threadId + "-" + val);
                        });
                  }
                }
              } catch (final Throwable th) {
                unexpected.add(th);
              }
              return null;
            });
      }
      final var futures = pool.invokeAll(workers);
      for (final var future : futures) {
        // Per-future get() inside the bounded wait: a hung worker surfaces here
        // rather than letting the test pass quietly. The unexpected queue holds
        // the actual exception bodies for the assertion below; this get() only
        // surfaces propagated executor errors.
        future.get(BARRIER_WAIT_SECONDS * THREADS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      assertTrue("worker pool must terminate cleanly",
          pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    // Failure-mode classification: every captured exception must be inspected
    // before the test concludes. The original poison cascade manifested as
    // IllegalStateException("Page X:Y was allocated in other thread") /
    // StorageException("Page Y is broken in file …") / "Internal error happened in
    // storage" — all of which surface as RuntimeExceptions through executeInTx and
    // are captured in `unexpected`.
    if (!unexpected.isEmpty()) {
      final var first = unexpected.peek();
      fail("concurrent-insert workload surfaced unexpected exception (" + unexpected.size()
          + " total): " + first);
    }

    // Capture the post-workload counters BEFORE close so the test can compare against
    // the baseline. We use a fresh open here rather than caching the prior session's
    // WOWCache reference because the worker pool's per-thread sessions are torn down
    // by their try-with-resources.
    try (var probeSession = youTrackDB.open(
        LoadOrAddPoisonCascadeRegressionIT.class.getSimpleName(),
        "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      var diskStorage = (DiskStorage) probeSession.getStorage();
      var wowCache = (WOWCache) diskStorage.getWriteCache();
      extendBranchAfterWorkload = wowCache.getLoadOrAddExtendBranchInvocations();
      gapFillBranchAfterWorkload = wowCache.getLoadOrAddGapFillBranchInvocations();
    }

    // Positive-evidence assertion: the workload must have driven the allocator path
    // through `loadOrAdd` extend / gap-fill branches at least THREADS times. The
    // exact count is unstable across runs (depends on cache reuse, fast-path hits,
    // and the wrapper's segment-lock serialisation) — the floor is conservative,
    // chosen so a regression that silently re-routed inserts elsewhere fails loud
    // while a benign optimisation that reduced allocator pressure (e.g., a batched
    // allocate) still passes.
    final var workloadExtendCount =
        extendBranchAfterWorkload - extendBranchBaseline;
    final var workloadGapFillCount =
        gapFillBranchAfterWorkload - gapFillBranchBaseline;
    final var workloadAllocatorPathInvocations =
        workloadExtendCount + workloadGapFillCount;
    assertThat(workloadAllocatorPathInvocations)
        .as("positive-evidence floor: the concurrent-insert workload must have driven "
            + "the loadOrAdd extend / gap-fill branches at least " + THREADS + " times "
            + "across " + THREADS + " threads × " + ITERATIONS + " iterations "
            + "(observed extend=" + workloadExtendCount
            + ", gap-fill=" + workloadGapFillCount + ")")
        .isGreaterThanOrEqualTo(THREADS);

    // Reopen-and-read verification: every committed record must be visible on a
    // clean reopen with the same configuration. A regression that left the storage
    // in a corrupted state would surface here as a missing record or a load
    // exception. We close the running instance first to make the reopen genuine.
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);

    try (var verifySession = youTrackDB.open(
        LoadOrAddPoisonCascadeRegressionIT.class.getSimpleName(),
        "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // countClass requires an active transaction; we use a read-only TX bracket so the
      // verification reads see a consistent snapshot.
      verifySession.begin();
      try {
        var total = verifySession.countClass("Person");
        assertEquals("every committed insert must be visible on reopen",
            (long) THREADS * ITERATIONS, total);
      } finally {
        verifySession.commit();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Deterministic white-box reproducer
  // ---------------------------------------------------------------------------

  /**
   * Two-thread repeated-rounds harness driving the production read-cache wrapper's
   * {@code loadOrAddForWrite} surface at the same {@code (fileId, pageIndex == 0)}.
   * {@value #WHITE_BOX_ROUNDS} rounds, each with a fresh fileId so each round drives
   * the extend branch fresh. The {@link CyclicBarrier} start gate maximises
   * contention at the wrapper's {@code data.compute} → {@code WOWCache.loadOrAdd}
   * → {@code AsyncFile.allocateSpace} install boundary.
   *
   * <p>The wrapper's {@code data.compute} segment write lock serialises the two
   * threads: exactly one enters the lambda first, takes the extend branch on
   * {@code WOWCache.loadOrAdd}, installs the {@link CachePointer} into the
   * {@code data} map, and returns; the second worker either hits the cache-hit fast
   * path (entry already in the map) or enters the lambda after publication and
   * observes the existing entry. In both cases the returned {@link CachePointer} is
   * identity-equal to the one the first worker installed.
   *
   * <p>Invariants pinned per round:
   *
   * <ul>
   *   <li>Both threads succeed. The wrapper's segment lock and the in-cache
   *       publish-before-release ordering guarantee no thread observes a
   *       partially-stamped page.
   *   <li>Both returned {@link CacheEntry} instances share the same
   *       {@link CachePointer} instance (verified via {@link IdentityHashMap}).
   *   <li>Exactly one extend happened: {@code wowCache.getFilledUpTo(fileId) == 1L}.
   * </ul>
   *
   * <p>A regression that broke the wrapper's segment-lock serialisation would surface
   * here as either two distinct {@link CachePointer}s (each worker extended
   * independently) or {@code getFilledUpTo == 2L} on at least one round. A regression
   * that re-introduced the underlying allocator-mismatch poison cascade would surface
   * as an {@link IllegalStateException} propagated out of {@code loadOrAddForWrite}.
   *
   * <p>The {@code wowCache.loadOrAdd} primitive is exercised through the wrapper here
   * with {@code verifyChecksums = true} (the production setting under
   * {@code checksumMode=StoreAndThrow}). The bare-surface I4 sentinel test (where two
   * threads bypass the wrapper and race the bare {@code WOWCache.loadOrAdd} directly)
   * lives in {@code WOWCacheLoadOrAddConcurrentTest.bareLoadOrAddOnSameKeySurfacesI4Sentinel}
   * — see that class's Javadoc for the failure-mode coverage map. This test is the
   * production-shape counterpart: success-mode invariants on the wrapper-coordinated
   * path, repeated to catch intermittent regressions.
   */
  @Test
  public void whiteBoxLoadOrAddSameKeyContentionConverges() throws Exception {
    var config = makeConfigWithChecksumStoreAndThrow();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(LoadOrAddPoisonCascadeRegressionIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var session = youTrackDB.open(
        LoadOrAddPoisonCascadeRegressionIT.class.getSimpleName(),
        "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      var diskStorage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) diskStorage.getWriteCache();
      var readCache = diskStorage.getReadCache();

      final var pool = Executors.newFixedThreadPool(2);
      try {
        for (var round = 0; round < WHITE_BOX_ROUNDS; round++) {
          // Fresh fileId per round so each round starts from currentSize == 0 and
          // the wrapper's data.compute lambda drives the extend branch fresh.
          final var fileName =
              getClass().getSimpleName() + "-round-" + round + ".dat";
          final var fileId = readCache.addFile(fileName, wowCache);

          final var barrier = new CyclicBarrier(2);
          final List<Callable<CacheEntry>> workers = new ArrayList<>(2);
          for (var w = 0; w < 2; w++) {
            workers.add(
                () -> {
                  barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
                  // verifyChecksums = true exercises the production setting under
                  // checksumMode=StoreAndThrow; the load-branch path on a previously
                  // extended page must read the magic stamp and pass the CRC check.
                  // N=2 with prompt release avoids the same-key cross-worker
                  // deadlock on the entry's exclusive lock.
                  final var entry = readCache.loadOrAddForWrite(
                      fileId, 0L, wowCache, /* verifyChecksums = */ true, null);
                  readCache.releaseFromWrite(entry, wowCache, /* changed = */ false);
                  return entry;
                });
          }
          final var futures = pool.invokeAll(workers);
          final List<CacheEntry> entries = new ArrayList<>(2);
          try {
            for (final var future : futures) {
              final var entry = future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
              assertNotNull("round " + round
                  + ": loadOrAddForWrite on a same-key race must return a non-null entry",
                  entry);
              entries.add(entry);
            }

            // Identity-based set: both workers must share the same CachePointer
            // instance — exactly one extend, exactly one pointer published, both
            // workers observed it.
            final Set<CachePointer> distinct =
                Collections.newSetFromMap(new IdentityHashMap<>());
            for (final var entry : entries) {
              distinct.add(entry.getCachePointer());
            }
            assertEquals("round " + round
                + ": two writers on the same key must observe one CachePointer instance",
                1, distinct.size());
            assertEquals("round " + round
                + ": exactly one extend must have happened on this round's file",
                1L, wowCache.getFilledUpTo(fileId));
          } finally {
            // The two future-returned entries already had their write lock released
            // inside the worker callable; no explicit per-entry release needed here.
            // Per-round file cleanup so the next round's addFile does not interfere
            // with the prior round's cached pages.
            readCache.deleteFile(fileId, wowCache);
          }
        }
      } finally {
        pool.shutdownNow();
        assertTrue("pool must terminate cleanly",
            pool.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Configuration helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a configuration with {@code STORAGE_CHECKSUM_MODE = StoreAndThrow} — the
   * stance under which the original bug surfaced (and the production CI default for
   * disk-mode tests). The bug ticket explicitly notes that
   * {@code checksumMode = Off} silences the magic-check leg of the cascade.
   */
  private static org.apache.commons.configuration2.BaseConfiguration
      makeConfigWithChecksumStoreAndThrow() {
    var config = new org.apache.commons.configuration2.BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndThrow.name());
    return config;
  }
}
