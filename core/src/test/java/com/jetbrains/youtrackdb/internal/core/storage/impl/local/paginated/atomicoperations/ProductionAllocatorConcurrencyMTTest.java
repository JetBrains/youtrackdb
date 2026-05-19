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
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
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
 *       {@code PaginatedCollectionV2.allocatePosition} surface. Each call enters under
 *       the per-component exclusive lock (acquired inside CPMV2's
 *       {@code calculateInsideComponentOperation}); without the lock, two allocators
 *       would race the entry-point page write lock and the cache-layer fast-fail
 *       {@code IllegalStateException} in {@code WOWCache.loadOrAdd} would surface a
 *       hard crash on the loser of the allocator race.
 *   <li><b>{@link #pcv2AllocateNewPageUnderSharedClusterToleratesConcurrentAppends}</b> —
 *       drives two concurrent {@code PaginatedCollectionV2.allocateNewPage} call sites
 *       on the same cluster's PCV2 instance via the public {@code createRecord} surface
 *       with a payload large enough to force an {@code allocateNewPage} on every call
 *       (the FSM cannot find a fitting existing page, so the new-page allocator path is
 *       taken deterministically). Same per-component lock invariant as the CPMV2 case
 *       above, but on the data file ({@code .pcl}) and the state-page write lock.
 *   <li><b>{@link #ihmFlushUnderSharedIndexToleratesConcurrentDirtyFlush}</b> — drives
 *       two concurrent {@code IndexHistogramManager} dirty-flush call sites on the same
 *       index's IHM instance: one via the explicit {@code flushIfDirty(AtomicOperation)}
 *       overload (which calls {@code writeSnapshotToPage}) and one via the no-arg
 *       {@code flushIfDirty()} (which calls {@code flushSnapshotToPage} through a
 *       dedicated atomic operation). Both methods acquire the same per-component
 *       exclusive lock via {@code acquireExclusiveLockTillOperationComplete}; without
 *       the lock the two page-0 / page-1 allocators (the page-1 path under HLL spill,
 *       the page-0 path on every flush) could race for the same {@code (fileId,
 *       pageIndex)} and trip the cache-layer fast-fail in {@code WOWCache.loadOrAdd}.
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
   * Record payload size in the PCV2 {@code allocateNewPage} test. Sized so the FSM
   * cannot locate an existing page with enough free space on every call, forcing the
   * {@code allocateNewPage} branch deterministically. The collection page's usable
   * payload area after the page-header is well below the storage's default 65536-byte
   * page; a 16 KiB record fits at most three records per page, so two concurrent
   * inserts on a freshly-extended page race the new-page allocator on the very next
   * call instead of fitting next to existing records.
   */
  private static final int PCV2_LARGE_PAYLOAD_BYTES = 16 * 1024;

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
    final List<Callable<Void>> workers = new ArrayList<>(2);
    final var workerIdGen = new AtomicInteger();
    for (var w = 0; w < 2; w++) {
      workers.add(() -> {
        final var workerId = workerIdGen.getAndIncrement();
        try {
          barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
          // Each worker enters the AOM through its own atomic op so the two
          // allocatePosition calls genuinely contend at the per-component lock.
          // A shared op would idempotency-collapse them to one call.
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(
              op -> pcv2.allocatePosition(/* recordType = */ (byte) 'd', op));
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
  }

  // ---------------------------------------------------------------------------
  // PaginatedCollectionV2.allocateNewPage concurrency pin
  // ---------------------------------------------------------------------------

  /**
   * Two concurrent {@code PaginatedCollectionV2.allocateNewPage} call sites on the
   * same cluster's PCV2 instance must both succeed. Driven via the public
   * {@code createRecord} surface with a 16 KiB payload — large enough that the FSM
   * cannot locate an existing page with enough free space, forcing the
   * {@code allocateNewPage} branch on every call.
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
      // A single 16 KiB payload reused across rounds — content does not vary the
      // allocator path being tested.
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
    final List<Callable<Void>> workers = new ArrayList<>(2);
    final var workerIdGen = new AtomicInteger();
    for (var w = 0; w < 2; w++) {
      workers.add(() -> {
        final var workerId = workerIdGen.getAndIncrement();
        try {
          barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
          // Each worker drives a createRecord in its own atomic op. createRecord
          // calls findNewPageToWrite → allocateNewPage when no FSM page fits;
          // the 16 KiB payload ensures no FSM hit on a freshly-extended page.
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(
              op -> pcv2.createRecord(payload, (byte) 'd', null, op));
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
  }

  // ---------------------------------------------------------------------------
  // IndexHistogramManager flushSnapshotToPage vs writeSnapshotToPage concurrency pin
  // ---------------------------------------------------------------------------

  /**
   * Two concurrent {@code IndexHistogramManager} dirty-flush call sites on the same
   * index's IHM instance must both succeed. The {@code flushIfDirty(AtomicOperation)}
   * overload calls {@code writeSnapshotToPage}; the no-arg {@code flushIfDirty()}
   * calls {@code flushSnapshotToPage} through a dedicated atomic operation. Both
   * methods acquire the same per-component exclusive lock via
   * {@code acquireExclusiveLockTillOperationComplete}.
   *
   * <p>The lock matters when an HLL spill has put state on page 1: the page-1
   * allocator (called from one method) and the page-1 loader (called from the other)
   * would race for the same {@code (fileId, pageIndex)} without the lock. Driving the
   * HLL-spill threshold deterministically from a session-level workload is fragile
   * (the boundary is set at IHM construction from
   * {@code QUERY_STATS_MAX_BOUNDARY_BYTES}); the page-1 discriminator branch itself
   * is pinned at the unit level by the IHM spill-discriminator test sibling. The
   * value of this MT test is the lock-acquisition contract on the
   * {@code DIRTY_MUTATIONS}-driven flush race — a regression that dropped the lock
   * would surface as concurrent invocations of {@code writeSnapshotToPage} and
   * {@code flushSnapshotToPage} on the same IHM instance, both of which write page 0
   * and would race the page-0 loader.
   */
  @Test
  public void ihmFlushUnderSharedIndexToleratesConcurrentDirtyFlush() throws Exception {
    final var pool = Executors.newFixedThreadPool(2);
    try (var session = youTrackDB.open(DB_NAME, "admin", "admin",
        YouTrackDBConfig.builder()
            .fromApacheConfiguration(makeConfigWithChecksumStoreAndThrow()).build())) {
      final var storage = (AbstractStorage) session.getStorage();
      for (var round = 0; round < ROUNDS; round++) {
        runIhmFlushRound(session, storage, pool, round);
      }
    } finally {
      pool.shutdownNow();
      assertTrue("worker pool must terminate cleanly",
          pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Drives one round of the IHM flush race. Each round creates a fresh class with a
   * UNIQUE index, inserts one row to put DIRTY_MUTATIONS &gt; 0 (so both flush
   * branches actually enter the {@code writeSnapshotToPage} body rather than the CAS
   * early-return), then spawns two threads — one calls {@code flushIfDirty(op)}, the
   * other calls {@code flushIfDirty()} — racing for the same IHM instance under the
   * per-component exclusive lock.
   *
   * <p>Note that {@code DIRTY_MUTATIONS} is a single counter and only one of the two
   * concurrent threads will win the CAS to take the flush path; the other will fast-
   * return. Both paths exercise the lock-acquisition decision (the no-arg path
   * acquires through {@code executeInsideComponentOperation}, the with-op path
   * acquires through the direct {@code acquireExclusiveLockTillOperationComplete}
   * call inside {@code writeSnapshotToPage}). The lock pin is therefore valid even
   * when the CAS picks a winner deterministically: a regression that dropped the
   * lock on either path would still surface here as an {@code IllegalStateException}
   * on the next round, when the winner thread on round {@code N} releases (or fails
   * to release) the lock the round {@code N+1} CAS-winner needs.
   */
  private void runIhmFlushRound(
      final DatabaseSessionEmbedded session,
      final AbstractStorage storage, final ExecutorService pool, final int round)
      throws Exception {
    final var className = "IhmFlush_" + round;
    session.getMetadata().getSchema().createClass(className)
        .createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
    // One insert dirties DIRTY_MUTATIONS via onPut so the flush methods actually
    // enter writeSnapshotToPage instead of taking the CAS early-return.
    session.executeInTx(tx -> {
      var entity = tx.newEntity(className);
      entity.setProperty("name", "name-round-" + round);
    });

    final var ihm = resolveHistogramManagerByIndexName(session, className + ".name");
    assertThat(ihm)
        .as("round %d: index '%s.name' must expose a non-null IndexHistogramManager",
            round, className)
        .isNotNull();

    final var barrier = new CyclicBarrier(2);
    final var errors = new ConcurrentLinkedQueue<Throwable>();
    final List<Callable<Void>> workers = new ArrayList<>(2);
    final var workerIdGen = new AtomicInteger();
    // Worker 0: explicit-op overload → writeSnapshotToPage path.
    workers.add(() -> {
      final var workerId = workerIdGen.getAndIncrement();
      try {
        barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> ihm.flushIfDirty(op));
      } catch (Throwable t) {
        errors.add(taggedFailure(round, workerId, t));
      }
      return null;
    });
    // Worker 1: no-arg overload → flushSnapshotToPage path (creates own op).
    workers.add(() -> {
      final var workerId = workerIdGen.getAndIncrement();
      try {
        barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
        ihm.flushIfDirty();
      } catch (Throwable t) {
        errors.add(taggedFailure(round, workerId, t));
      }
      return null;
    });
    final var futures = pool.invokeAll(workers);
    for (final var future : futures) {
      future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
    }
    if (!errors.isEmpty()) {
      throw buildAggregatedAssertionError(errors,
          "round " + round + ": concurrent IHM flushIfDirty must not raise");
    }
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
   * Returns {@code null} if the index does not exist, is not backed by a BTree engine,
   * or has no histogram manager attached (the latter happens for index types that
   * skip histogram setup at wire-up).
   */
  private static IndexHistogramManager resolveHistogramManagerByIndexName(
      final DatabaseSessionEmbedded session,
      final String indexName) {
    final var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    if (index == null) {
      return null;
    }
    final var engineId = index.getIndexId();
    try {
      final var engine = ((AbstractStorage) session.getStorage()).getIndexEngine(engineId);
      if (engine instanceof BTreeIndexEngine btree) {
        return btree.getHistogramManager();
      }
    } catch (final Exception e) {
      return null;
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
   */
  private static AssertionError buildAggregatedAssertionError(
      final ConcurrentLinkedQueue<Throwable> errors, final String header) {
    final var first = errors.poll();
    final var error = new AssertionError(
        header + "; first failure: " + first, first);
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
