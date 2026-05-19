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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Multi-thread pins for the per-component-lock invariant on three production hot paths
 * that share a {@code fileId}: the BTree/SLBB/CPMV2/PCV2 allocator audit conclusion
 * (callers enter under {@code executeInsideComponentOperation} /
 * {@code calculateInsideComponentOperation} / {@code acquireExclusiveLockTillOperationComplete}).
 * Each {@code @Test} drives a repeated-rounds shape ({@value #ROUNDS} rounds, fresh
 * component instance per round, {@link CyclicBarrier}(2) start gate) that pins the
 * production-side invariant: passes today, fails loudly if a future change drops the
 * lock and lets two concurrent allocators target the same {@code (fileId, pageIndex)}.
 *
 * <h2>Coverage map</h2>
 *
 * <ul>
 *   <li><b>{@link #cpmv2AllocateUnderSharedClusterToleratesConcurrentAllocators}</b> —
 *       drives two concurrent {@code CollectionPositionMapV2.allocate} call sites on the
 *       same cluster's CPMV2 instance via the public
 *       {@code PaginatedCollectionV2.allocatePosition} surface. The lock contract this
 *       pins is the PCV2-wrapping-CPMV2 lock contract: {@code PCV2.allocatePosition}
 *       enters under PCV2's {@code calculateInsideComponentOperation}, which acquires
 *       the per-component exclusive lock on {@code this} of the {@link
 *       com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2}
 *       instance, and only then calls {@code CollectionPositionMapV2.allocate}.
 *       The test therefore detects a regression that dropped the lock on
 *       {@code PCV2.allocatePosition}; a regression that dropped the lock on
 *       {@code CPMV2.allocate} alone (a path no production caller exercises today —
 *       every production entry into {@code CPMV2.allocate} flows through PCV2's
 *       wrapping lock) would not surface here. Without the PCV2 lock, two allocators
 *       on the same cluster would race the entry-point page write lock and the
 *       cache-layer fast-fail {@code IllegalStateException} in
 *       {@code WOWCache.loadOrAdd} would surface a hard crash on the loser of the
 *       allocator race.
 *   <li><b>{@link #pcv2AllocateNewPageUnderSharedClusterToleratesConcurrentAppends}</b> —
 *       drives two concurrent {@code PaginatedCollectionV2.allocateNewPage} call sites
 *       on the same cluster's PCV2 instance via the public {@code createRecord} surface
 *       with a payload larger than {@code CollectionPage.MAX_RECORD_SIZE} so the FSM
 *       cannot locate an existing page that fits the record and {@code findNewPageToWrite}
 *       fires on every call (the new-page allocator branch is taken deterministically).
 *       Same per-component lock invariant as the CPMV2 case above, but on the data file
 *       ({@code .pcl}) and the state-page write lock.
 *   <li><b>{@link #ihmBuildHistogramUnderSharedIndexToleratesConcurrentBuilds}</b> —
 *       drives two concurrent {@code IndexHistogramManager.buildHistogram} call sites on
 *       the same index's IHM instance. Both calls reach {@code writeSnapshotToPage}
 *       (via the {@code nonNullCount < histogramMinSize} early-exit), which acquires
 *       the per-component exclusive lock directly via
 *       {@code AtomicOperationsManager.acquireExclusiveLockTillOperationComplete}.
 *       This surface is deliberately chosen over {@code flushIfDirty}: {@code
 *       flushIfDirty} gates its body behind a {@code DIRTY_MUTATIONS.compareAndSet}
 *       that admits exactly one CAS-winner per round (so the loser fast-returns
 *       without touching the lock), making it impossible to drive two concurrent
 *       lock-acquiring threads through that path. {@code buildHistogram} has no CAS
 *       gate — both threads enter {@code writeSnapshotToPage} unconditionally, and
 *       the lock is the only structural barrier serialising their page-0 (and
 *       optional page-1) accesses. A regression that dropped the lock at
 *       {@code writeSnapshotToPage} would surface as an {@code IllegalStateException}
 *       from the cache-layer fast-fail in {@code WOWCache.loadOrAdd} on the loser of
 *       the page-0 allocate-or-load race.
 * </ul>
 *
 * <h2>Repeated-rounds + {@link CyclicBarrier}(2) shape</h2>
 *
 * <p>Each {@code @Test} runs {@value #ROUNDS} rounds. Each round provisions a fresh
 * component instance (a new class for CPMV2 / PCV2, a new index for IHM) so the
 * allocator state on round {@code N} is independent of round {@code N-1}. The
 * {@link CyclicBarrier}(2) re-arms each round and gates both threads at the call site,
 * maximising the chance that the two allocator entries land in the same scheduler
 * slice — the contention window the per-component lock is meant to close.
 *
 * <h2>Configuration</h2>
 *
 * <p>The test runs under {@code checksumMode = StoreAndThrow} (the production CI
 * default for disk-mode tests, and the stance under which the original poison cascade
 * surfaced). The magic-check leg of the cascade is silenced when checksums are off, so
 * the regression gate must stay green under the production-default checksum stance.
 *
 * <p>The {@code SequentialTest} category routes the class through the sequential-tests
 * surefire execution: it sets {@code GlobalConfiguration.STORAGE_CHECKSUM_MODE}
 * indirectly via the per-instance builder and opens a disk-mode storage. Parallel
 * forks touching the same build directory would interfere with each other's file
 * allocation accounting.
 */
@Category(SequentialTest.class)
public class ProductionAllocatorConcurrencyMTTest {

  /** Database name used for every YouTrackDB instance opened by this test class. */
  private static final String DB_NAME = "ProductionAllocatorConcurrencyMTTest";

  /**
   * Rounds of two-thread concurrent allocator contention executed per {@code @Test}.
   * {@value} is high enough to surface a regression in the per-component lock contract
   * within seconds (the wrapper's segment lock window is microsecond-scale, so the
   * round count amplifies the probability that the two allocator entries collide in
   * the same scheduler slice) while staying well within the surefire fork's per-test
   * budget.
   */
  private static final int ROUNDS = 100;

  /**
   * Bounded wait on every cross-thread barrier / future. Workers should fly past these
   * within milliseconds; the timeout guards against a hung worker letting the test
   * stall instead of failing cleanly.
   */
  private static final long BARRIER_WAIT_SECONDS = 30L;

  /**
   * Record payload size in the PCV2 {@code allocateNewPage} test. Derived from
   * {@code CollectionPage.MAX_RECORD_SIZE + 1}: the production constant defines
   * the largest record that fits inside a single collection page after the
   * page-header, page-indexes table, and per-entry index slot have been
   * deducted. Any payload above this threshold forces {@code serializeRecord}
   * to split across pages, so {@code findNewPageToWrite} fires on every call
   * (no existing page can fit the record) and both concurrent workers race the
   * new-page allocator branch deterministically. The storage's default page
   * size is 8 KiB ({@code GlobalConfiguration.DISK_CACHE_PAGE_SIZE = 8} KB), so
   * the resulting payload is on the order of a few KiB, not the speculative
   * 65536-byte page the prior draft referenced.
   */
  private static final int PCV2_LARGE_PAYLOAD_BYTES = CollectionPage.MAX_RECORD_SIZE + 1;

  private YouTrackDBImpl youTrackDB;
  private Path directoryPath;

  @Before
  public void setUp() throws Exception {
    // Pre-clean: this test's three methods each create a fresh storage at the same
    // directoryPath, so a previous run's leftover (or a sibling test method's
    // leftover) must be wiped before each invocation.
    directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    if (Files.exists(directoryPath)) {
      FileUtils.deleteDirectory(directoryPath.toFile());
    }
    var config = makeConfigWithChecksumStoreAndThrow();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
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
  // CollectionPositionMapV2.allocate concurrency pin
  // ---------------------------------------------------------------------------

  /**
   * Two concurrent {@code CollectionPositionMapV2.allocate} call sites on the same
   * cluster's CPMV2 instance must both succeed. Driven via the public
   * {@code PaginatedCollectionV2.allocatePosition} surface — each call enters under
   * the per-component exclusive lock and serialises with the other.
   *
   * <p>A regression that dropped the per-component lock would surface as an
   * {@code IllegalStateException} from one of the threads (the cache-layer
   * fast-fail in {@code WOWCache.loadOrAdd} when both allocators target the same
   * entry-point page or the same new bucket page).
   */
  @Test
  public void cpmv2AllocateUnderSharedClusterToleratesConcurrentAllocators() throws Exception {
    final var pool = Executors.newFixedThreadPool(2);
    try (var session = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder()
            .fromApacheConfiguration(makeConfigWithChecksumStoreAndThrow()).build())) {
      final var storage = (AbstractStorage) session.getStorage();
      for (var round = 0; round < ROUNDS; round++) {
        runCpmv2AllocateRound(session, storage, pool, round);
      }
    } finally {
      pool.shutdownNow();
      assertTrue("worker pool must terminate cleanly",
          pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Drives one round of the CPMV2-allocate race. Each round creates a fresh class
   * (and therefore a fresh cluster + CPMV2 instance), spawns two threads gated on a
   * {@link CyclicBarrier}(2), each thread calls
   * {@code PaginatedCollectionV2.allocatePosition} on the same PCV2 instance, then
   * asserts no thread raised. A fresh component per round keeps round {@code N+1}'s
   * race independent of round {@code N}'s leftover state.
   *
   * <p>Positive-evidence: each worker captures its returned
   * {@code PhysicalPosition.collectionPosition} into a shared queue. After both
   * workers complete the round, the test asserts the queue holds exactly two
   * elements with distinct values — proof that two independent allocator calls
   * actually ran to completion and produced different positions, rather than one
   * call silently bypassing the body or both calls returning the same idempotent
   * result. Without this floor, a regression that broke allocation state would
   * still pass the "no exception fired" gate.
   */
  private void runCpmv2AllocateRound(
      final DatabaseSessionEmbedded session,
      final AbstractStorage storage, final ExecutorService pool, final int round)
      throws Exception {
    final var className = "CpmAlloc_" + round;
    final var schemaClass = session.getMetadata().getSchema().createClass(className);
    final var pcv2 = resolveSinglePcv2ForClass(storage, schemaClass);
    assertThat(pcv2)
        .as("round %d: storage must expose a PaginatedCollectionV2 for the fresh "
            + "class's cluster", round)
        .isNotNull();

    final var barrier = new CyclicBarrier(2);
    final var errors = new ConcurrentLinkedQueue<Throwable>();
    final var positions = new ConcurrentLinkedQueue<Long>();
    final List<Callable<Void>> workers = new ArrayList<>(2);
    for (var w = 0; w < 2; w++) {
      // Capture construction-order worker id so diagnostic labels reflect the
      // lambda's identity at the time the worker list was built, not the order
      // in which the executor happened to enter the lambdas.
      final int workerId = w;
      workers.add(() -> {
        try {
          barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
          // Each worker enters the AOM through its own atomic op so the two
          // allocatePosition calls genuinely contend at the per-component lock.
          // A shared op would idempotency-collapse them to one call.
          final var allocated = storage.getAtomicOperationsManager()
              .calculateInsideAtomicOperation(
                  op -> pcv2.allocatePosition(EntityImpl.RECORD_TYPE, op));
          positions.add(allocated.collectionPosition);
        } catch (Throwable t) {
          errors.add(taggedFailure(round, workerId, t));
        }
        return null;
      });
    }
    final var futures = pool.invokeAll(workers);
    for (final var future : futures) {
      future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
    }
    if (!errors.isEmpty()) {
      throw buildAggregatedAssertionError(errors,
          "round " + round + ": concurrent CPMV2.allocate must not raise");
    }
    // Positive-evidence floor: both workers must have completed and produced
    // distinct collection positions. Detects a regression that silently bypassed
    // the allocator body or returned a duplicate position.
    assertThat(positions)
        .as("round %d: each concurrent allocator must produce a distinct "
            + "collectionPosition", round)
        .hasSize(2)
        .doesNotHaveDuplicates();
  }

  // ---------------------------------------------------------------------------
  // PaginatedCollectionV2.allocateNewPage concurrency pin
  // ---------------------------------------------------------------------------

  /**
   * Two concurrent {@code PaginatedCollectionV2.allocateNewPage} call sites on the
   * same cluster's PCV2 instance must both succeed. Driven via the public
   * {@code createRecord} surface with a payload exceeding
   * {@code CollectionPage.MAX_RECORD_SIZE} — large enough that the FSM cannot locate
   * an existing page with enough free space, forcing the {@code allocateNewPage}
   * branch on every call.
   *
   * <p>A regression that dropped the per-component lock would surface as an
   * {@code IllegalStateException} from one of the threads (the cache-layer
   * fast-fail in {@code WOWCache.loadOrAdd} when both allocators target the same
   * new data page).
   */
  @Test
  public void pcv2AllocateNewPageUnderSharedClusterToleratesConcurrentAppends()
      throws Exception {
    final var pool = Executors.newFixedThreadPool(2);
    try (var session = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder()
            .fromApacheConfiguration(makeConfigWithChecksumStoreAndThrow()).build())) {
      final var storage = (AbstractStorage) session.getStorage();
      // A single payload sized just above MAX_RECORD_SIZE reused across rounds —
      // content does not vary the allocator path being tested.
      final var payload = new byte[PCV2_LARGE_PAYLOAD_BYTES];
      for (var round = 0; round < ROUNDS; round++) {
        runPcv2AllocateNewPageRound(session, storage, pool, payload, round);
      }
    } finally {
      pool.shutdownNow();
      assertTrue("worker pool must terminate cleanly",
          pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Drives one round of the PCV2-allocateNewPage race. Each round creates a fresh
   * class (and therefore a fresh data file and PCV2 instance) and spawns two threads
   * that each call {@code createRecord} with a payload sized to defeat the FSM
   * lookup; both then race the {@code allocateNewPage} branch.
   *
   * <p>Positive-evidence: each worker captures its returned
   * {@code PhysicalPosition.collectionPosition} into a shared queue. After both
   * workers complete the round, the test asserts the queue holds exactly two
   * elements with distinct values. Each {@code createRecord} call with a payload
   * larger than {@code CollectionPage.MAX_RECORD_SIZE} forces at least one new
   * page to be allocated, so two distinct positions also imply at least two
   * distinct new-page allocations have actually run — proof that the body did
   * not collapse to a single observed allocation.
   */
  private void runPcv2AllocateNewPageRound(
      final DatabaseSessionEmbedded session,
      final AbstractStorage storage, final ExecutorService pool, final byte[] payload,
      final int round) throws Exception {
    final var className = "PcvAlloc_" + round;
    final var schemaClass = session.getMetadata().getSchema().createClass(className);
    final var pcv2 = resolveSinglePcv2ForClass(storage, schemaClass);
    assertThat(pcv2)
        .as("round %d: storage must expose a PaginatedCollectionV2 for the fresh "
            + "class's cluster", round)
        .isNotNull();

    final var barrier = new CyclicBarrier(2);
    final var errors = new ConcurrentLinkedQueue<Throwable>();
    final var positions = new ConcurrentLinkedQueue<Long>();
    final List<Callable<Void>> workers = new ArrayList<>(2);
    for (var w = 0; w < 2; w++) {
      // Construction-order worker id (see runCpmv2AllocateRound).
      final int workerId = w;
      workers.add(() -> {
        try {
          barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
          // Each worker drives a createRecord in its own atomic op. createRecord
          // calls findNewPageToWrite → allocateNewPage when no FSM page fits;
          // the > MAX_RECORD_SIZE payload ensures no FSM hit on a freshly-extended
          // page and forces a new-page allocation on every call.
          final var allocated = storage.getAtomicOperationsManager()
              .calculateInsideAtomicOperation(
                  op -> pcv2.createRecord(payload, EntityImpl.RECORD_TYPE, null, op));
          positions.add(allocated.collectionPosition);
        } catch (Throwable t) {
          errors.add(taggedFailure(round, workerId, t));
        }
        return null;
      });
    }
    final var futures = pool.invokeAll(workers);
    for (final var future : futures) {
      future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
    }
    if (!errors.isEmpty()) {
      throw buildAggregatedAssertionError(errors,
          "round " + round + ": concurrent PCV2.allocateNewPage must not raise");
    }
    // Positive-evidence floor: both workers must have completed and produced
    // distinct collection positions. With a > MAX_RECORD_SIZE payload, two
    // distinct positions imply at least two new-page allocations actually ran.
    assertThat(positions)
        .as("round %d: each concurrent createRecord must produce a distinct "
            + "collectionPosition", round)
        .hasSize(2)
        .doesNotHaveDuplicates();
  }

  // ---------------------------------------------------------------------------
  // IndexHistogramManager.buildHistogram concurrency pin
  // ---------------------------------------------------------------------------

  /**
   * Two concurrent {@code IndexHistogramManager.buildHistogram} call sites on the
   * same index's IHM instance must both succeed. Both calls reach
   * {@code writeSnapshotToPage} unconditionally (via the
   * {@code nonNullCount < histogramMinSize} early-exit on an empty key stream),
   * which acquires the per-component exclusive lock directly via
   * {@code AtomicOperationsManager.acquireExclusiveLockTillOperationComplete}.
   *
   * <p>This surface is chosen over {@code flushIfDirty} because {@code flushIfDirty}
   * gates its body behind a {@code DIRTY_MUTATIONS.compareAndSet} that admits
   * exactly one CAS-winner per round; the CAS-loser fast-returns without ever
   * touching the per-component lock, so a same-instance two-thread race on
   * {@code flushIfDirty} cannot pin the lock contract (the lock would simply not be
   * contended). {@code buildHistogram} has no CAS gate — both threads enter
   * {@code writeSnapshotToPage} on every call, and the lock is the only structural
   * barrier serialising their page-0 (and optional page-1) accesses.
   *
   * <p>A regression that dropped the lock at {@code writeSnapshotToPage} (the
   * {@code acquireExclusiveLockTillOperationComplete} call inside the method body)
   * would surface as an {@code IllegalStateException} from the cache-layer fast-fail
   * in {@code WOWCache.loadOrAdd} on the loser of the page-0 allocate-or-load race.
   */
  @Test
  public void ihmBuildHistogramUnderSharedIndexToleratesConcurrentBuilds() throws Exception {
    final var pool = Executors.newFixedThreadPool(2);
    try (var session = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder()
            .fromApacheConfiguration(makeConfigWithChecksumStoreAndThrow()).build())) {
      final var storage = (AbstractStorage) session.getStorage();
      for (var round = 0; round < ROUNDS; round++) {
        runIhmBuildHistogramRound(session, storage, pool, round);
      }
    } finally {
      pool.shutdownNow();
      assertTrue("worker pool must terminate cleanly",
          pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Drives one round of the IHM {@code buildHistogram} race. Each round creates a
   * fresh class with a UNIQUE index (a fresh IHM instance per round, so round
   * {@code N}'s allocator state is independent of round {@code N-1}'s), then spawns
   * two threads that each call {@code buildHistogram} on the same IHM with an empty
   * sorted-keys stream. The empty stream + {@code totalCount = 0} input drives the
   * {@code nonNullCount < histogramMinSize} early-exit, which still calls
   * {@code writeSnapshotToPage(op, snapshot)} — the exact production surface the
   * per-component lock protects. Two empty streams suffice because the lock
   * contract is independent of stream content; the goal is to pin the
   * lock-acquisition site, not the histogram-construction logic.
   */
  private void runIhmBuildHistogramRound(
      final DatabaseSessionEmbedded session,
      final AbstractStorage storage, final ExecutorService pool, final int round)
      throws Exception {
    final var className = "IhmBuild_" + round;
    session.getMetadata().getSchema().createClass(className)
        .createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);

    final var ihm = resolveHistogramManagerByIndexName(session, className + ".name");
    assertThat(ihm)
        .as("round %d: index '%s.name' must expose a non-null IndexHistogramManager",
            round, className)
        .isNotNull();
    final var keyFieldCount = ihm.getKeyFieldCount();

    final var barrier = new CyclicBarrier(2);
    final var errors = new ConcurrentLinkedQueue<Throwable>();
    final var completions = new AtomicInteger();
    final List<Callable<Void>> workers = new ArrayList<>(2);
    for (var w = 0; w < 2; w++) {
      // Construction-order worker id (see runCpmv2AllocateRound).
      final int workerId = w;
      workers.add(() -> {
        try {
          barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
          // Each worker enters its own atomic op so the two buildHistogram calls
          // genuinely contend at the per-component lock. The empty key stream +
          // totalCount=0 drives the histogramMinSize early-exit, which still
          // calls writeSnapshotToPage — the lock-acquiring surface this test
          // pins.
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(
              op -> {
                try (var emptyKeys = Stream.<Object>empty()) {
                  ihm.buildHistogram(op, emptyKeys, /* totalCount */ 0L,
                      /* nullCount */ 0L, keyFieldCount);
                }
              });
          completions.incrementAndGet();
        } catch (Throwable t) {
          errors.add(taggedFailure(round, workerId, t));
        }
        return null;
      });
    }
    final var futures = pool.invokeAll(workers);
    for (final var future : futures) {
      future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
    }
    if (!errors.isEmpty()) {
      throw buildAggregatedAssertionError(errors,
          "round " + round + ": concurrent IHM.buildHistogram must not raise");
    }
    // Positive-evidence floor: both workers must have completed their
    // writeSnapshotToPage call (and therefore both acquired and released the
    // per-component lock). A regression that bypassed the body silently would
    // leave completions < 2 and surface here.
    assertThat(completions.get())
        .as("round %d: both buildHistogram workers must run to completion", round)
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Finds the {@link PaginatedCollectionV2} for the first cluster of a class. Schema
   * defaults give a fresh class multiple clusters (the polymorphic-id set); for the
   * MT allocator pin we only need one PCV2 instance that two threads can target
   * concurrently, so the first cluster id is sufficient — the same PCV2 hands both
   * threads through the same per-component lock regardless of how many sibling
   * clusters the class owns.
   *
   * <p>Returns {@code null} if the storage's collection set does not contain a
   * matching PCV2 (e.g., the cluster was not created via PCV2's factory or was
   * dropped concurrently). {@code Storage.getCollectionInstances()} is the supported
   * accessor exposed by {@link AbstractStorage} — the alternative of reaching into
   * the private {@code collections} field via reflection would not survive a future
   * refactor of the collection registry.
   */
  private static PaginatedCollectionV2 resolveSinglePcv2ForClass(
      final AbstractStorage storage, final SchemaClass schemaClass) {
    final var collectionIds = schemaClass.getCollectionIds();
    assertThat(collectionIds)
        .as("freshly created class '%s' must have at least one cluster id",
            schemaClass.getName())
        .isNotEmpty();
    final var collectionId = collectionIds[0];
    for (final StorageCollection c : storage.getCollectionInstances()) {
      if (c instanceof PaginatedCollectionV2 pcv2 && pcv2.getId() == collectionId) {
        return pcv2;
      }
    }
    return null;
  }

  /**
   * Locates the {@link IndexHistogramManager} owned by the BTree index engine backing
   * a named index. The path is: session → index → BTreeIndexEngine → histogramManager.
   * Returns {@code null} only when the index does not exist or is not backed by a BTree
   * engine (a no-histogram-manager BTree returns the {@code null} attached on the engine
   * itself). Any thrown exception from the engine-resolution call propagates as an
   * {@link AssertionError} so a regression in the engine registry surfaces with the root
   * cause chained, rather than collapsing into the generic {@code isNotNull()} failure
   * the original swallowed-exception variant produced.
   */
  private static IndexHistogramManager resolveHistogramManagerByIndexName(
      final DatabaseSessionEmbedded session,
      final String indexName) {
    final var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    if (index == null) {
      return null;
    }
    final var engineId = index.getIndexId();
    final Object engine;
    try {
      engine = ((AbstractStorage) session.getStorage()).getIndexEngine(engineId);
    } catch (final Exception e) {
      throw new AssertionError(
          "failed to resolve index engine for '" + indexName + "' (engineId=" + engineId + ")",
          e);
    }
    if (engine instanceof BTreeIndexEngine btree) {
      return btree.getHistogramManager();
    }
    return null;
  }

  /**
   * Tags a worker failure with its round and worker id so the post-round aggregator
   * can render diagnostic messages that point at the exact thread that raised. The
   * original {@link Throwable} is chained as cause so the stack trace survives.
   */
  private static Throwable taggedFailure(final int round, final int workerId,
      final Throwable cause) {
    return new RuntimeException(
        "round " + round + " worker " + workerId + " raised", cause);
  }

  /**
   * Builds an {@link AssertionError} that chains the first captured failure as cause
   * and adds the remainder as suppressed exceptions. Mirrors the aggregator pattern
   * used by the {@code LoadOrAddPoisonCascadeRegressionTest} smoke test so a future
   * regression that surfaces here produces the same diagnostic shape in CI logs.
   *
   * <p>The error message renders the first failure's root cause inline (e.g.,
   * {@code "...first failure: java.lang.RuntimeException: round 3 worker 1 raised
   * caused by java.lang.IllegalStateException: Page 42:1 was allocated in other thread"})
   * so the root exception class is visible in the surefire summary without walking the
   * cause chain in a debugger. The {@code taggedFailure} wrapper hides the root type
   * behind a {@code RuntimeException} in {@code toString}; rendering the cause restores
   * the diagnostic.
   */
  private static AssertionError buildAggregatedAssertionError(
      final ConcurrentLinkedQueue<Throwable> errors, final String header) {
    final var first = errors.poll();
    final var rootCause = first == null ? null : first.getCause();
    final var causeSuffix = rootCause == null ? "" : " caused by " + rootCause;
    final var error = new AssertionError(
        header + "; first failure: " + first + causeSuffix, first);
    for (final var rest : errors) {
      error.addSuppressed(rest);
    }
    return error;
  }

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
