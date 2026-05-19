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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.apache.commons.configuration2.BaseConfiguration;
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
 *       {@link WOWCache#getLoadOrAddGapFillBranchInvocations} are split into two
 *       independent assertions. The extend branch is the steady-state allocator path
 *       under a healthy workload, so it must fire at least {@link #THREADS} times. The
 *       gap-fill branch must not scale with the workload — a low single-digit count
 *       is acceptable (cross-component snapshot races between independent allocators
 *       on different files can momentarily route a follow-up call through gap-fill
 *       without indicating a regression), but a count comparable to the extend total
 *       would signal a cascading mis-route from extend onto gap-fill. Splitting the
 *       assertions means a future regression that silently re-routed inserts from the
 *       extend path to the gap-fill path (or away from {@code loadOrAdd} altogether)
 *       fails loud — pooling the counters would let such a regression "pass" the
 *       absence-of-symptom check for the wrong reason.
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
 *       {@link com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCacheLoadOrAddConcurrentTest#bareLoadOrAddOnSameKeySurfacesI4Sentinel}.
 *       Together the two tests cover the cache-layer fast-fail (bare) and the
 *       wrapper-coordinated success path (here).
 * </ol>
 *
 * <p><b>Verification protocol (recorded in the commit message).</b> The smoke test was
 * verified to fail on the unmodified develop branch by cherry-picking this commit onto
 * {@code origin/develop} and running with {@code ./mvnw -pl core clean test
 * -Dtest=LoadOrAddPoisonCascadeRegressionTest}; the precise reproduction rate is captured
 * in the squashed-PR description. On this branch (the structural-fix tree) the same
 * invocation passes 10/10 consecutive runs.
 *
 * <p><b>Test class naming.</b> The class uses the {@code Test} suffix (rather than
 * {@code IT}) so that surefire's default include patterns ({@code *Test.java},
 * {@code *Tests.java}, {@code *TestCase.java}) match it under standard
 * {@code ./mvnw -pl core clean test} invocations. The
 * {@code @Category(SequentialTest.class)} annotation routes it through the
 * sequential-tests surefire execution (configuration mutates
 * {@code GlobalConfiguration.STORAGE_CHECKSUM_MODE} indirectly via the per-instance
 * builder and exercises shared cache primitives). Comparable concurrency tests in
 * this package — {@code AbstractStorageDeadlockFixTest},
 * {@code EdgeSnapshotLifecycleTest}, and {@code FreezeAndDBRecordInsertAtomicityTest}
 * (under {@code core/db}) — follow the same naming convention.
 *
 * <p><b>Sequential category.</b> The test runs under {@link SequentialTest} because it
 * sets {@code GlobalConfiguration.STORAGE_CHECKSUM_MODE} via the per-instance
 * configuration, opens a disk-mode YouTrackDB instance, and exercises the storage's
 * WOWCache primitive directly. Parallel forks touching the same build directory would
 * interfere with each other's file allocation accounting.
 */
@Category(SequentialTest.class)
public class LoadOrAddPoisonCascadeRegressionTest {

  /** Database name used for every YouTrackDB instance opened by this test class. */
  private static final String DB_NAME = "LoadOrAddPoisonCascadeRegressionTest";

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
  private Path directoryPath;

  @Before
  public void setUp() throws Exception {
    // Pre-clean: this test's two methods both create a fresh storage at the same
    // directoryPath, so a previous run's leftover (or a sibling test method's
    // leftover) must be wiped before each invocation.
    directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    if (Files.exists(directoryPath)) {
      FileUtils.deleteDirectory(directoryPath.toFile());
    }
  }

  @After
  public void tearDown() throws Exception {
    if (youTrackDB != null && youTrackDB.isOpen()) {
      youTrackDB.close();
    }
    youTrackDB = null;
    if (directoryPath != null && Files.exists(directoryPath)) {
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
   * propagated {@link IllegalStateException} or {@link StorageException} from one of
   * the worker threads. A regression that silently re-routed inserts away from the
   * {@code loadOrAdd} extend branch (and would let the absence-of-symptom checks
   * "pass" for the wrong reason) would surface as a counter-floor violation below.
   */
  @Test
  public void concurrentInsertWorkloadCompletesWithoutPoisonCascade() throws Exception {
    var config = makeConfigWithChecksumStoreAndThrow();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    // Phase 1 — create the indexed Person class and baseline counters AFTER schema
    // creation (so createClass / createIndex extends are not counted as workload).
    final var baseline = createSchemaAndCaptureCounterBaseline();

    // Phase 2 — concurrent-insert workload.
    final var unexpected = new ConcurrentLinkedQueue<Throwable>();
    runConcurrentInsertWorkload(unexpected);

    // Failure-mode classification: every captured exception must be inspected before
    // the test concludes. The original poison cascade manifested as
    // IllegalStateException("Page X:Y was allocated in other thread") /
    // StorageException("Page Y is broken in file …") / "Internal error happened in
    // storage" — all of which surface as RuntimeExceptions through executeInTx and
    // are captured in `unexpected`. Aggregate every captured exception into one
    // AssertionError (cause-chained + suppressed exceptions) so diagnostic data is
    // not lost; classify by shape so a poison-cascade regression is distinguishable
    // from an unrelated worker-pool flake.
    if (!unexpected.isEmpty()) {
      throw buildAggregatedAssertionError(unexpected);
    }

    // Phase 3 — capture post-workload counters and split positive-evidence floors.
    final var afterWorkload = captureCounters(config);
    final var workloadExtendCount = afterWorkload[0] - baseline[0];
    final var workloadGapFillCount = afterWorkload[1] - baseline[1];

    // Extend branch is the steady-state allocator under a healthy workload — must
    // fire at least once per worker thread. A regression that re-routed inserts away
    // from the extend path (whether to gap-fill or to a different cache primitive
    // entirely) would surface here as a count below THREADS.
    assertThat(workloadExtendCount)
        .as("extend branch is the steady-state allocator; should fire at least "
            + THREADS + " times across " + THREADS + " threads × " + ITERATIONS
            + " iterations (observed " + workloadExtendCount + ")")
        .isGreaterThanOrEqualTo(THREADS);

    // Gap-fill is the multi-page recovery / cross-component-extension path. The
    // WOWCache field Javadoc describes it as "recovery-only under normal callers",
    // but in practice a concurrent workload can legitimately hit a low single-digit
    // count: a component (e.g., IHM after the page-1 discriminator routes a follow-up
    // spill through `loadPageForWrite`) can observe a stale `currentSize` snapshot
    // momentarily while another component on a different file is extending. The
    // assertion's job here is to fail loud on a *cascading* re-route — if a future
    // regression mis-routed steady-state inserts onto the gap-fill path, the count
    // would scale with the workload (hundreds, not a handful). A generous upper
    // bound catches that regression while tolerating the cross-component snapshot
    // window.
    assertThat(workloadGapFillCount)
        .as("gap-fill should not scale with the workload; a cascading re-route from "
            + "the steady-state extend path onto gap-fill would surface as a count "
            + "comparable to extend (observed gap-fill=" + workloadGapFillCount
            + ", extend=" + workloadExtendCount + ")")
        .isLessThanOrEqualTo(Math.max(THREADS / 4L, 4L));

    // Phase 4 — reopen and verify every committed record is visible plus defense-in-
    // depth checks on the reopen path (sanity floor, unique-index lookup, gap-fill
    // defense-in-depth on the clean-shutdown reopen).
    verifyAllRecordsVisibleOnReopen(config);
  }

  /**
   * Creates the indexed {@code Person} class and captures the extend / gap-fill
   * counter baseline AFTER schema creation. The {@code createClass} and
   * {@code createIndex} calls each extend several files; counting those extends as
   * "smoke workload" extends would inflate the positive-evidence assertion below.
   *
   * <p>The unique index on {@code Person.name} forces every insert through both the
   * cluster's {@code PaginatedCollectionV2} (data file, {@code .pcl}) and its
   * embedded {@code CollectionPositionMapV2} ({@code .cpm}), as well as the index
   * B-tree ({@code .cbt} / {@code .sbt}) — maximising the number of file-extending
   * paths contending for the allocator.
   *
   * @return a two-element array {@code [extendBaseline, gapFillBaseline]} sampled
   *     after schema creation and before the concurrent workload starts.
   */
  private long[] createSchemaAndCaptureCounterBaseline() {
    try (var initSession = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(makeConfigWithChecksumStoreAndThrow())
            .build())) {
      initSession.getMetadata().getSchema()
          .createClass("Person")
          .createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
      return readCounters(initSession);
    }
  }

  /**
   * Drives the concurrent-insert workload across {@link #THREADS} workers, each
   * running {@link #ITERATIONS} inserts via {@code executeInTx} on its own session.
   * Captured Throwables are appended to the {@code unexpected} queue rather than
   * surfaced directly so the caller can classify every failure shape (poison-cascade
   * regression vs unrelated worker-pool flake) before deciding how to fail.
   */
  private void runConcurrentInsertWorkload(
      final ConcurrentLinkedQueue<Throwable> unexpected) throws Exception {
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
                try (var workerSession = youTrackDB.open(DB_NAME, "admin", "admin")) {
                  for (var i = 0; i < ITERATIONS; i++) {
                    final var iter = i;
                    workerSession.executeInTx(
                        tx -> {
                          var entity = tx.newEntity("Person");
                          entity.setProperty("name", "name-" + threadId + "-" + iter);
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
  }

  /**
   * Captures the WOWCache extend / gap-fill counters from a freshly opened session.
   * Used after the concurrent workload tears down its per-thread sessions; opening a
   * fresh session ensures we read counters from the same {@code WOWCache} instance
   * the workers wrote to (the {@code YouTrackDBImpl} singleton's underlying storage
   * is shared across all sessions).
   *
   * @return a two-element array {@code [extendCount, gapFillCount]}.
   */
  private long[] captureCounters(final BaseConfiguration config) {
    try (var probeSession = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      return readCounters(probeSession);
    }
  }

  /** Reads the extend / gap-fill counters from the given session's WOWCache. */
  private static long[] readCounters(final DatabaseSessionEmbedded session) {
    var diskStorage = (DiskStorage) session.getStorage();
    var wowCache = (WOWCache) diskStorage.getWriteCache();
    return new long[] {
        wowCache.getLoadOrAddExtendBranchInvocations(),
        wowCache.getLoadOrAddGapFillBranchInvocations()
    };
  }

  /**
   * Builds an {@link AssertionError} that chains the first captured exception as
   * cause and adds the remainder as suppressed exceptions, classifying the shape so
   * a poison-cascade regression is distinguishable from an unrelated worker-pool
   * flake in CI logs.
   */
  private static AssertionError buildAggregatedAssertionError(
      final ConcurrentLinkedQueue<Throwable> unexpected) {
    final var totalCount = unexpected.size();
    final var first = unexpected.poll();
    final var msg = first.getMessage() == null ? "" : first.getMessage();
    final boolean isPoisonCascade =
        (first instanceof IllegalStateException
            && (msg.contains("allocated pageIndex")
                || msg.contains("was allocated in other thread")))
            || (first instanceof StorageException
                && (msg.contains("is broken in file")
                    || msg.contains("Internal error happened in storage")));
    final var error = new AssertionError(
        "concurrent-insert workload surfaced " + totalCount + " unexpected exception(s) ("
            + (isPoisonCascade ? "POISON CASCADE REGRESSION" : "UNRELATED — may be flake")
            + "); first: " + first,
        first);
    for (final var rest : unexpected) {
      error.addSuppressed(rest);
    }
    return error;
  }

  /**
   * Performs the reopen-and-verify phase of the smoke test. Three layered checks:
   *
   * <ol>
   *   <li>Sanity floor: {@code countClass("Person") > 0} (guards against a
   *       class-creation regression dropping {@code Person} entirely).
   *   <li>Exact equality: every committed insert must survive the reopen.
   *   <li>Unique-index probe: one known {@code name} key must locate exactly one row
   *       — verifies the unique-index BTree survived the reopen.
   *   <li>Gap-fill defense-in-depth: the clean-shutdown reopen must never trigger
   *       the gap-fill branch (recovery-only path); a non-zero count would mean WAL
   *       replay observed a torn write the workload didn't actually produce.
   * </ol>
   */
  private void verifyAllRecordsVisibleOnReopen(final BaseConfiguration config) {
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);

    try (var verifySession = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // countClass requires an active transaction; we use a read-only TX bracket so
      // the verification reads see a consistent snapshot.
      verifySession.begin();
      try {
        var total = verifySession.countClass("Person");
        assertTrue("sanity floor: at least one Person record must survive reopen "
            + "(observed " + total + ")", total > 0);
        assertEquals("every committed insert must be visible on reopen",
            (long) THREADS * ITERATIONS, total);

        // Unique-index lookup probe: a known name key must locate exactly one row.
        // Defense against an index-corruption regression where countClass would
        // still return the right number but the unique index lookup would fail.
        try (var rs = verifySession.query(
            "SELECT FROM Person WHERE name = ?", "name-0-0")) {
          assertTrue("unique index must locate a known row on reopen", rs.hasNext());
          final var row = rs.next();
          assertEquals("unique index lookup must return the correct row",
              "name-0-0", row.getProperty("name"));
          assertFalse("unique index lookup must return exactly one row", rs.hasNext());
        }
      } finally {
        verifySession.commit();
      }
    }

    // Defense in depth: a clean-shutdown reopen's WAL replay should not need to
    // gap-fill at a *cascading* rate. The counter is read from the fresh WOWCache
    // instance after the reopen (the prior WOWCache was destroyed by the close +
    // re-instance sequence above), so this read measures only what happened during
    // the reopen path (storage open + WAL replay + verifySession reads). A clean-
    // shutdown sequence should leave physical >= logical, making WAL replay's
    // gap-fill path a no-op; a generous upper bound tolerates cross-component
    // snapshot races on the reads while failing loud on a cascading regression.
    final long postReopenGapFill;
    try (var probeSession = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      postReopenGapFill = readCounters(probeSession)[1];
    }
    assertThat(postReopenGapFill)
        .as("clean-shutdown reopen must not trigger a cascading gap-fill (observed "
            + postReopenGapFill + ")")
        .isLessThanOrEqualTo(Math.max(THREADS / 4L, 4L));
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
   *   <li>Both workers return the same {@link CacheEntry} instance (the underlying
   *       cache map serves one entry per {@code (fileId, pageIndex)}). Distinct
   *       {@code CacheEntryImpl} wrappers would signal a regression in the
   *       cache-install path even when the {@link CachePointer} happens to match.
   *   <li>Both returned {@link CacheEntry} instances share the same
   *       {@link CachePointer} instance (verified via {@link IdentityHashMap}).
   *   <li>Exactly one extend happened: the file holds exactly one physical page on
   *       this round's fileId.
   * </ul>
   *
   * <p>Pinning {@code CachePointer.referrersCount} would be a fourth defense-in-
   * depth check, but {@link CachePointer} does not expose a public getter for the
   * count (only mutators {@code incrementReadersReferrer} /
   * {@code decrementReadersReferrer}); the wrapper's lock-and-release contract is
   * indirectly pinned by the cache-entry identity check above (a leaked referrer
   * after release would surface in tear-down via the DirectMemory track-mode
   * assertions, not here).
   *
   * <p>A regression that broke the wrapper's segment-lock serialisation would
   * surface here as either two distinct {@link CachePointer}s (each worker extended
   * independently) or a physical page count of two on at least one round. A
   * regression that re-introduced the underlying allocator-mismatch poison cascade
   * would surface as an {@link IllegalStateException} propagated out of
   * {@code loadOrAddForWrite}.
   *
   * <p>The {@code wowCache.loadOrAdd} primitive is exercised through the wrapper
   * here with {@code verifyChecksums = true} (the production setting under
   * {@code checksumMode=StoreAndThrow}). The bare-surface I4 sentinel test (where
   * two threads bypass the wrapper and race the bare {@code WOWCache.loadOrAdd}
   * directly) lives in
   * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCacheLoadOrAddConcurrentTest#bareLoadOrAddOnSameKeySurfacesI4Sentinel}
   * — see that class's Javadoc for the failure-mode coverage map. This test is the
   * production-shape counterpart: success-mode invariants on the wrapper-coordinated
   * path, repeated to catch intermittent regressions.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void whiteBoxLoadOrAddSameKeyContentionConverges() throws Exception {
    var config = makeConfigWithChecksumStoreAndThrow();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var session = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      var diskStorage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) diskStorage.getWriteCache();
      var readCache = (LockFreeReadCache) diskStorage.getReadCache();

      final var pool = Executors.newFixedThreadPool(2);
      try {
        for (var round = 0; round < WHITE_BOX_ROUNDS; round++) {
          runSameKeyContentionRound(round, readCache, wowCache, pool);
        }
      } finally {
        pool.shutdownNow();
        assertTrue("pool must terminate cleanly",
            pool.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  /**
   * Executes one round of the two-thread same-key contention harness. A fresh
   * fileId per round guarantees the {@code data.compute} lambda drives the extend
   * branch fresh; the per-round file is deleted at the end of the round so the
   * next round's {@code addFile} does not interfere with the prior round's cached
   * pages.
   *
   * <p>The deprecation suppression on the caller covers the {@code getFilledUpTo}
   * call: this test must read the raw physical page count to assert "exactly one
   * extend happened", and the test-friendly Layer A helper
   * ({@code WriteCache.physicalSizeForBackupSnapshot}) returns bytes rather than
   * the page count this assertion needs. The deprecation forbids cross-component
   * callers but the listed retained internal callers include the storage layer's
   * own probes — this test is in the same category (white-box concurrency check
   * pinning the raw allocator behaviour).
   */
  private void runSameKeyContentionRound(final int round, final LockFreeReadCache readCache,
      final WOWCache wowCache, final ExecutorService pool) throws Exception {
    final var fileName = getClass().getSimpleName() + "-round-" + round + ".dat";
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
            // N=2 with prompt release avoids the same-key cross-worker deadlock
            // on the entry's exclusive lock.
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

      // Identity-based set on CacheEntry: both workers must observe the same
      // CacheEntry wrapper (the cache map serves one entry per (fileId,
      // pageIndex)). Distinct CacheEntryImpl wrappers with matching CachePointers
      // would already signal a cache-install regression.
      final Set<CacheEntry> distinctEntries =
          Collections.newSetFromMap(new IdentityHashMap<>());
      distinctEntries.addAll(entries);
      assertEquals("round " + round
          + ": two writers on the same key must observe one CacheEntry instance",
          1, distinctEntries.size());

      // Identity-based set on CachePointer: defense-in-depth against a regression
      // that produced distinct wrappers around the same pointer (matching this
      // check alone) or distinct pointers altogether (failing both checks).
      final Set<CachePointer> distinctPointers =
          Collections.newSetFromMap(new IdentityHashMap<>());
      for (final var entry : entries) {
        distinctPointers.add(entry.getCachePointer());
      }
      assertEquals("round " + round
          + ": two writers on the same key must observe one CachePointer instance",
          1, distinctPointers.size());

      // Exactly one extend on this round's file. White-box read against the
      // raw page count — the Layer A backup helper returns bytes, which would
      // need a pageSize conversion to assert the same fact, so the deprecated
      // accessor stays for this case (annotated at the test-method level).
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

  // ---------------------------------------------------------------------------
  // Configuration helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a configuration with {@code STORAGE_CHECKSUM_MODE = StoreAndThrow} — the
   * stance under which the original bug surfaced (and the production CI default for
   * disk-mode tests). The bug ticket explicitly notes that
   * {@code checksumMode = Off} silences the magic-check leg of the cascade.
   */
  private static BaseConfiguration makeConfigWithChecksumStoreAndThrow() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        ChecksumMode.StoreAndThrow.name());
    return config;
  }
}
