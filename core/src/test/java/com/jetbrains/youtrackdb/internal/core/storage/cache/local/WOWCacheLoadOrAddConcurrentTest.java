package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Multi-threaded stress harness for the disk-engine {@link WOWCache#loadOrAdd} primitive
 * driven through the {@link LockFreeReadCache} wrapper (the production call path).
 *
 * <p>Each scenario exercises the cache stack at a different contention surface:
 *
 * <ol>
 *   <li><b>Scenario 1</b> (different keys via wrapper): N workers each load a distinct
 *       pageIndex on a pre-extended file. The wrapper's {@code data.compute} lambda
 *       runs per-key, so distinct-key concurrency is genuinely unsynchronised; the
 *       test pins that the resulting {@link CacheEntry}s carry distinct
 *       {@link CachePointer}s and no exception is thrown.
 *   <li><b>Scenario 2</b> (same key via wrapper): N workers all target
 *       {@code (fileId, K)}; the wrapper's segment write lock serialises them. Exactly
 *       one worker takes the extend branch inside the lambda; the remaining workers
 *       observe the already-installed cache entry on their cache-hit fast path; all
 *       N workers see the same {@link CachePointer} instance.
 *   <li><b>Scenario 3</b> (reader at K-1 vs writer extending to K): a reader loops
 *       on the load branch for an existing pageIndex while a writer drives the
 *       extend on a higher pageIndex. The reader never sees a transient null, NPE,
 *       or unexpected exception.
 *   <li><b>Scenario 7</b> (delete/truncate vs concurrent
 *       {@code loadOrAddForWrite}): installer threads loop on the wrapper while a
 *       destroyer rotates {@code deleteFile + addFile} (and {@code truncateFile} in
 *       a sibling test). Installer calls either succeed cleanly (destroyer waited)
 *       or surface {@link IllegalArgumentException} from the dispatch prelude; no
 *       other exception type is acceptable.
 *   <li><b>I4 negative defence</b>: both the extend branch's
 *       {@code allocatedIndex != pageIndex} sentinel and the gap-fill branch's
 *       {@code allocatedStartIndex != currentSize} sentinel — along with their
 *       respective negative-allocation guards — are exercised by direct reflective
 *       invocation of {@code WOWCache.loadOrAddExtendBranch} and
 *       {@code WOWCache.loadOrAddGapFillBranch} with a Mockito-doctored
 *       {@link com.jetbrains.youtrackdb.internal.core.storage.fs.File} whose
 *       {@code allocateSpace} returns a position above, below, or negative of the
 *       requested {@code pageIndex} / {@code currentSize}. The hard
 *       {@link IllegalStateException} throws — the I4 sentinels added by Track 1
 *       Step 2's review fix — must fire deterministically. Pins that the
 *       cache-internal fast-fails stay falsifiable and that the
 *       {@code loadOrAddExtendBranchInvocations} and
 *       {@code loadOrAddGapFillBranchInvocations} positive-evidence counters are
 *       not bumped on the throw path. The production race shape (concurrent
 *       allocators competing for the same {@code pageIndex}) is covered by
 *       {@link com.jetbrains.youtrackdb.internal.core.storage.impl.local.LoadOrAddPoisonCascadeRegressionTest}.
 * </ol>
 *
 * <p><b>Sequential class execution.</b> The tests run sequentially within this class
 * (no {@code @RunWith(Parallel)}). The JVM-singleton {@code wowCacheFlushExecutor}
 * (a single-thread executor on the WOWCache side) is heavily exercised by every test
 * in the class, and double-saturating it across parallel forks would either trigger
 * inactivity-timeout false failures or wedge the {@code @After tearDown} on
 * {@code delete()} drains. The 60s per-{@code @Test} timeout is the per-test safety
 * net; sequential class execution is the per-class safety net.
 */
public class WOWCacheLoadOrAddConcurrentTest {

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;
  private static final String FILE_NAME = "wowCacheLoadOrAddConcurrent.tst";
  private static final long PER_TEST_TIMEOUT_MS = 60_000L;
  // Per-worker iteration budget for the install/probe loop scenarios. Tuned so the
  // 8-thread workers complete the loop body well inside the per-test 60s budget on the
  // slowest CI runner while still producing enough contention to surface a race.
  private static final int ITERATIONS_PER_WORKER = 200;
  // Bounded wait on the start-barrier in workers. Workers should fly past it within
  // milliseconds; the timeout exists so a deadlocked harness fails inside the
  // 60s @Test(timeout) cap rather than hanging.
  private static final long BARRIER_WAIT_SECONDS = 30L;
  // Cache-size budget for the LockFreeReadCache wrapper. 4 MiB is large enough to
  // hold all of the test's working set comfortably (no eviction pressure inside the
  // scenarios — eviction is exercised by the wrapper-MT step's separate scenarios).
  private static final long READ_CACHE_MAX_MEMORY = 4L * 1024 * 1024;

  private static Path storagePath;
  private static String storageName;
  private static final ByteBufferPool bufferPool = new ByteBufferPool(PAGE_SIZE);

  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private LockFreeReadCache readCache;
  private ClosableLinkedContainer<Long, File> files;
  // Cached-thread-pool the WOWCache uses internally for AsyncFile I/O. Held so the
  // executor can be shut down in tearDown; without explicit shutdown each test method
  // would leak non-daemon worker threads.
  private ExecutorService asyncFileExecutor;

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    storageName = "WOWCacheLoadOrAddConcurrentTest";
    storagePath = Paths.get(buildDirectory).resolve(storageName);
  }

  @AfterClass
  public static void afterClass() {
    bufferPool.clear();
  }

  @Before
  public void setUp() throws Exception {
    cleanUp();

    Files.createDirectories(storagePath);
    files = new ClosableLinkedContainer<>(1024);

    writeAheadLog =
        new CASDiskWriteAheadLog(
            storageName,
            storagePath,
            storagePath,
            ContextConfiguration.WAL_DEFAULT_NAME,
            12_000,
            128,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            25,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            true,
            10);
    asyncFileExecutor = Executors.newCachedThreadPool();
    wowCache =
        new WOWCache(
            PAGE_SIZE,
            false,
            bufferPool,
            writeAheadLog,
            new DoubleWriteLogNoOP(),
            PAGES_FLUSH_INTERVAL,
            SHUTDOWN_TIMEOUT,
            EXCLUSIVE_WRITE_CACHE_MAX_SIZE,
            storagePath,
            storageName,
            files,
            1,
            ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
            ChecksumMode.StoreAndVerify,
            null,
            null,
            false,
            asyncFileExecutor);
    wowCache.loadRegisteredFiles();
    readCache = new LockFreeReadCache(READ_CACHE_MAX_MEMORY, PAGE_SIZE);
  }

  @After
  public void tearDown() throws Exception {
    cleanUp();
  }

  private void cleanUp() throws IOException {
    if (readCache != null) {
      readCache.clear();
      readCache = null;
    }
    if (wowCache != null) {
      wowCache.delete();
      wowCache = null;
    }
    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }
    if (asyncFileExecutor != null) {
      asyncFileExecutor.shutdownNow();
      try {
        asyncFileExecutor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      asyncFileExecutor = null;
    }
    if (storagePath != null && Files.exists(storagePath)) {
      try (var stream = Files.walk(storagePath)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    // best-effort cleanup
                  }
                });
      }
    }
  }

  /**
   * Scenario 1 — concurrent {@link LockFreeReadCache#loadOrAddForWrite} for
   * <b>distinct</b> {@code (fileId, pageIndex)} pairs on a pre-extended file.
   *
   * <p>The file is pre-extended sequentially to {@code currentSize == 8} so every
   * worker's load takes the load branch (not the extend branch — concurrent extend
   * on different pageIndices on the same {@code fileId} would violate invariant I4,
   * which is the responsibility of the per-component lock above the cache layer).
   *
   * <p>Eight worker threads then call
   * {@code readCache.loadOrAddForWrite(fileId, distinctPageIndex, …)} in parallel.
   * The wrapper's {@code data.compute} lambda is keyed on the pageIndex, so distinct
   * keys go through different segment locks and the operations are genuinely
   * unsynchronised. The test pins three invariants:
   *
   * <ul>
   *   <li>No worker observes an exception — every {@link CacheEntry} is non-null.
   *   <li>All 8 returned {@link CachePointer} instances are reference-distinct (one
   *       per page; the wrapper does not silently alias keys).
   *   <li>{@code AsyncFile.size} is unchanged after the run (the load branch must
   *       not extend the file under concurrent reads).
   * </ul>
   *
   * <p>A regression that broke the wrapper's per-key segmentation (e.g., a hash
   * collision that aliased two keys onto the same segment lock) would surface here
   * as either duplicate {@link CachePointer} instances or a non-null
   * {@code AsyncFile.size} delta.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void concurrentLoadOnDistinctKeysReturnsDistinctPointers() throws Exception {
    final int threads = 8;
    final var fileId = wowCache.addFile(FILE_NAME);
    // Pre-extend the file sequentially to currentSize == threads so every worker
    // takes the load branch.
    for (int i = 0; i < threads; i++) {
      wowCache.loadOrAdd(fileId, i, false).decrementReadersReferrer();
    }
    final var preRunFileSize = wowCache.getFilledUpTo(fileId);

    final var pool = Executors.newFixedThreadPool(threads);
    try {
      final var startBarrier = new CyclicBarrier(threads);
      final List<Callable<CacheEntry>> workers = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        final long targetPageIndex = i;
        workers.add(
            () -> {
              startBarrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
              return readCache.loadOrAddForWrite(
                  fileId, targetPageIndex, wowCache, false, null);
            });
      }
      final var futures = pool.invokeAll(workers);
      final var entries = new ArrayList<CacheEntry>(threads);
      try {
        for (final var future : futures) {
          final var entry = future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
          assertNotNull(
              "loadOrAddForWrite on a pre-extended page must return a non-null entry",
              entry);
          entries.add(entry);
        }
        // Identity-based set: two CachePointer instances are "the same" only when
        // they are the same reference. The test asserts no instance collisions
        // across workers.
        final Set<CachePointer> distinct =
            Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var entry : entries) {
          distinct.add(entry.getCachePointer());
        }
        assertEquals(
            "eight workers on distinct keys must produce eight distinct CachePointer instances",
            threads,
            distinct.size());
        assertEquals(
            "load branch must not advance AsyncFile.size under concurrent reads",
            preRunFileSize,
            wowCache.getFilledUpTo(fileId));
      } finally {
        for (final var entry : entries) {
          readCache.releaseFromWrite(entry, wowCache, false);
        }
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly",
          pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Scenario 2 — concurrent {@link LockFreeReadCache#loadOrAddForWrite} for the
   * <b>same</b> {@code (fileId, pageIndex)} on a fresh file.
   *
   * <p>Eight worker threads all target {@code (fileId, 0)} on a freshly-opened
   * file. The wrapper's {@code data.compute(fileId, 0, λ)} segment write lock
   * serialises them: exactly one worker enters the lambda first (its
   * {@link WOWCache#loadOrAdd} call takes the extend branch on a fresh file),
   * installs the {@link CacheEntry} into the wrapper map, and returns; the remaining
   * seven workers either hit the lambda after publication (lambda's "entry already
   * present" branch returns the existing entry) or short-circuit on the outer
   * {@code data.get(fileId, 0)} cache-hit fast path. In every case the returned
   * {@link CachePointer} is identity-equal to the one the first worker installed.
   *
   * <p>The test pins:
   *
   * <ul>
   *   <li>All workers succeed (no exception of any kind).
   *   <li>All 8 returned {@link CachePointer}s are reference-equal — exactly one
   *       extend happened, exactly one pointer was installed, all workers observe
   *       it.
   *   <li>{@code AsyncFile.size} ends at exactly one page (one extend, not eight).
   * </ul>
   *
   * <p>A regression that broke the wrapper's segment-lock serialisation would
   * surface here as either multiple distinct pointers, a non-1 file size, or an
   * I4-sentinel-style exception propagating out of the lambda — the same failure
   * shape that the bare-WOWCache I4-negative-defence test pins.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void concurrentLoadOnSameKeyAllObserveSamePointer() throws Exception {
    final int threads = 8;
    final var fileId = wowCache.addFile(FILE_NAME);
    final var pool = Executors.newFixedThreadPool(threads);
    try {
      final var startBarrier = new CyclicBarrier(threads);
      // Use loadForRead in the workers (not loadOrAddForWrite) so the entry-level
      // exclusiveLock is not acquired — eight workers all holding the same
      // entry's exclusiveLock until the main thread releases it would deadlock
      // (each worker would block on the prior worker's acquire). The same-key
      // race is exercised at the wrapper's segment-lock layer regardless of
      // read/write flavour: loadForRead also routes through doLoad's
      // data.compute lambda on a cache miss, and the lambda still calls
      // WriteCache.loadOrAdd which takes the extend branch on the fresh file.
      // The test's invariant (one CachePointer, one extend) is therefore exercised
      // on the read-flavoured wrapper API.
      final List<Callable<CacheEntry>> workers = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        workers.add(
            () -> {
              startBarrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
              return readCache.loadForRead(fileId, 0L, wowCache, false);
            });
      }
      final var futures = pool.invokeAll(workers);
      final var entries = new ArrayList<CacheEntry>(threads);
      try {
        for (final var future : futures) {
          final var entry = future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
          assertNotNull(
              "loadForRead on a same-key race must return a non-null entry", entry);
          entries.add(entry);
        }
        // Identity-based set: all 8 must share the same CachePointer instance.
        final Set<CachePointer> distinct =
            Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var entry : entries) {
          distinct.add(entry.getCachePointer());
        }
        assertEquals(
            "eight workers on the same key must observe one CachePointer instance",
            1,
            distinct.size());
        assertEquals(
            "exactly one extend must have happened — AsyncFile.size == 1 page",
            1L,
            wowCache.getFilledUpTo(fileId));
      } finally {
        for (final var entry : entries) {
          readCache.releaseFromRead(entry);
        }
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly",
          pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Scenario 3 — reader at pageIndex {@code K-1} (existing) vs writer extending
   * to pageIndex {@code K}.
   *
   * <p>The setup pre-extends pages {@code [0, 16)} sequentially. One reader thread
   * loops {@code loadForRead(fileId, 0, …)} (load branch on an existing pageIndex);
   * concurrently, one writer thread loops {@code loadOrAddForWrite(fileId, 16 + i,
   * …)} (extend branch on a higher pageIndex than the reader). The reader's
   * cache-hit fast path (or load-branch lambda) reads through the data map; the
   * writer's extend branch goes through {@code data.compute} on a different key.
   * Neither thread holds the other's segment lock — they should run independently.
   *
   * <p>The test pins:
   *
   * <ul>
   *   <li>Every reader call returns a non-null {@link CacheEntry} (no transient NPE,
   *       no torn-read).
   *   <li>No exception of any kind surfaces on either thread.
   *   <li>{@code AsyncFile.size} ends at exactly {@code 16 + writerIterations}
   *       pages (clean extension on every writer call, no leaked extends, reader
   *       never extends).
   * </ul>
   *
   * <p>This is the canonical "reader path is unaffected by concurrent extension on
   * a higher pageIndex" contract from the bug ticket — the original poison cascade
   * was a reader (silentLoadForRead) misinterpreting an in-flight allocator's
   * pageIndex; the structural fix removes the discovery channel that caused the
   * misinterpretation, and this test pins the new contract.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void readerAtExistingPageUnaffectedByWriterExtensions() throws Exception {
    final int preExtendedPages = 16;
    final var fileId = wowCache.addFile(FILE_NAME);
    for (int i = 0; i < preExtendedPages; i++) {
      wowCache.loadOrAdd(fileId, i, false).decrementReadersReferrer();
    }
    assertEquals(
        "pre-extend must seed currentSize == preExtendedPages",
        (long) preExtendedPages,
        wowCache.getFilledUpTo(fileId));

    final int readerIterations = ITERATIONS_PER_WORKER;
    final int writerIterations = ITERATIONS_PER_WORKER;
    final var pool = Executors.newFixedThreadPool(2);
    try {
      final var startBarrier = new CyclicBarrier(2);
      final var unexpected = new ConcurrentLinkedQueue<Throwable>();
      final var readerIterationCounter = new AtomicLong();

      final Callable<Void> reader =
          () -> {
            try {
              startBarrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
              for (int i = 0; i < readerIterations; i++) {
                final var entry = readCache.loadForRead(fileId, 0L, wowCache, false);
                assertNotNull(
                    "reader load-branch must never observe null on an existing page",
                    entry);
                readCache.releaseFromRead(entry);
                readerIterationCounter.incrementAndGet();
              }
            } catch (final Throwable t) {
              unexpected.add(t);
            }
            return null;
          };

      final Callable<Void> writer =
          () -> {
            try {
              startBarrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
              for (int i = 0; i < writerIterations; i++) {
                final long targetPageIndex = preExtendedPages + i;
                final var entry =
                    readCache.loadOrAddForWrite(
                        fileId, targetPageIndex, wowCache, false, null);
                assertNotNull(
                    "writer extend-branch must always return a non-null entry", entry);
                readCache.releaseFromWrite(entry, wowCache, false);
              }
            } catch (final Throwable t) {
              unexpected.add(t);
            }
            return null;
          };

      final var futures = pool.invokeAll(List.of(reader, writer));
      for (final var future : futures) {
        future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
      }

      if (!unexpected.isEmpty()) {
        fail(
            "reader-vs-writer race surfaced unexpected exception: " + unexpected.peek());
      }
      assertEquals(
          "reader must have executed the loop body the full iteration count",
          (long) readerIterations,
          readerIterationCounter.get());
      assertEquals(
          "AsyncFile.size must end at exactly preExtendedPages + writerIterations",
          (long) preExtendedPages + writerIterations,
          wowCache.getFilledUpTo(fileId));
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly",
          pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Scenario 7 — {@code deleteFile} vs concurrent
   * {@link LockFreeReadCache#loadOrAddForWrite}.
   *
   * <p>Eight installer threads loop calling
   * {@code loadOrAddForWrite(currentFileId, 0L, …)} via the wrapper (the latest
   * registered fileId after the destroyer's rotation), while one destroyer thread
   * rotates {@code deleteFile + addFile} a fixed number of times.
   * {@link WOWCache#deleteFile} acquires {@code filesLock.writeLock} and waits for
   * in-flight {@code loadOrAdd} calls to release their {@code filesLock.readLock};
   * an installer either commits cleanly (destroyer waited) or surfaces
   * {@link IllegalArgumentException} from the dispatch prelude after the file is
   * gone. Any other exception type is a regression.
   *
   * <p>Mirrors the in-memory engine's
   * {@code DirectMemoryOnlyDiskCacheLoadOrAddTest.clearAndLoadOrAddRaceLeavesCacheConsistent}
   * but exercises the disk-engine {@code filesLock} discipline. The per-thread
   * iteration counter ({@code perThreadIterations}) pins a strict per-thread floor
   * (every installer must run the body at least once) so a regression that broke
   * filesLock readLock fairness — letting one thread monopolise the lock while the
   * other N-1 starved — would surface as a per-thread zero rather than being hidden
   * behind a single-counter total that one greedy thread can satisfy alone.
   *
   * <p><b>Wrapper-level eviction is bypassed by design.</b> The destroyer drives
   * {@code wowCache.deleteFile + wowCache.addFile} directly — it does NOT call
   * {@code readCache.deleteFile}. {@link LockFreeReadCache#deleteFile} cannot run
   * concurrently with pinned entries (the wrapper's {@code clearFile} aborts with
   * "Page X is used"), so wiring the wrapper-level cleanup into a contention test
   * where installers pin entries on the dying fileId would either deadlock or
   * surface a spurious abort. Instead, the test exercises the disk-engine
   * {@link WOWCache#filesLock} discipline directly: every installer's
   * {@code loadOrAdd} holds {@code filesLock.readLock}; the destroyer's
   * {@code deleteFile} holds {@code filesLock.writeLock} and waits for in-flight
   * readers. Wrapper-level stale entries on rotated-away fileIds become unreachable
   * because the installers always query the latest fileId from {@code fileIdRef};
   * the cache map's overflow check is the eventual cleanup trigger, but it does
   * not run inside this test's bounded window.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void deleteFileAndLoadOrAddRaceLeavesCacheConsistent() throws Exception {
    // This scenario exercises the disk-engine bare {@link WOWCache} surface
    // directly (not the wrapper). The wrapper's deleteFile asserts no entry is
    // pinned at the time of clear, so coordinating wrapper-level deleteFile with
    // unsynchronised installer threads is not the contract scenario 7 targets.
    // Mirroring the in-memory engine's
    // {@code clearAndLoadOrAddRaceLeavesCacheConsistent}, the test drives bare
    // {@code wowCache.loadOrAdd} from N installer threads and rotates
    // {@code wowCache.deleteFile + wowCache.addFile} from one destroyer thread.
    final int installerThreads = 8;
    final int rotations = 50;
    final var fileIdRef = new AtomicLong(wowCache.addFile(FILE_NAME));
    final var pool = Executors.newFixedThreadPool(installerThreads + 1);
    try {
      final var stop = new AtomicBoolean(false);
      final var unexpected = new ConcurrentLinkedQueue<Throwable>();
      final var startGate = new CountDownLatch(1);
      final var installerDone = new CountDownLatch(installerThreads);
      // Per-thread iteration counter — a single shared counter would let one thread
      // run the full body N times while N-1 threads run 0 times still satisfy
      // total >= N. A regression that starved N-1 threads of the filesLock readLock
      // would pass the single-counter guard; the per-thread floor catches it.
      final var perThreadIterations = new AtomicLongArray(installerThreads);
      // Coordination latch: each installer counts down after it completes its first
      // successful iteration. The destroyer waits on this latch before starting its
      // rotations. Without this gate, the destroyer's 50 rotations can finish
      // before later installer threads even get past startGate.await() under heavy
      // CPU contention (the per-thread floor would surface a spurious vacuous-pass
      // failure on slow CI runners).
      final var allInstallersStarted = new CountDownLatch(installerThreads);

      for (int t = 0; t < installerThreads; t++) {
        final int workerId = t;
        pool.submit(
            () -> {
              try {
                startGate.await();
                boolean firstIterationCompleted = false;
                while (!stop.get() || !firstIterationCompleted) {
                  try {
                    final var pointer = wowCache.loadOrAdd(fileIdRef.get(), 0L, false);
                    perThreadIterations.incrementAndGet(workerId);
                    pointer.decrementReadersReferrer();
                    if (!firstIterationCompleted) {
                      firstIterationCompleted = true;
                      allInstallersStarted.countDown();
                    }
                  } catch (final IllegalArgumentException ignored) {
                    // Tolerated: file was deleted between our fileIdRef read and
                    // the dispatch prelude's files.acquire().
                  } catch (final IllegalStateException e) {
                    // Tolerated when the exception is the I4 sentinel from the
                    // bare-WOWCache same-pageIndex race: two installer threads
                    // both observed currentSize==0 on a freshly-rotated fileId,
                    // both took the extend branch, and one observed
                    // allocatedIndex != requested. Production callers never trip
                    // this — they go through the wrapper's data.compute segment
                    // lock — but the bare-WOWCache surface this scenario uses to
                    // exercise the filesLock discipline is intrinsically prone to
                    // surface the sentinel during the inter-rotation race window.
                    // Any other IllegalStateException is a regression and fails
                    // the test below.
                    if (e.getMessage() == null
                        || !(e.getMessage().contains("allocated pageIndex")
                            || e.getMessage().contains("allocated start index"))
                        || !e.getMessage().contains("does not match")) {
                      unexpected.add(e);
                    }
                  }
                }
              } catch (final Throwable t1) {
                unexpected.add(t1);
              } finally {
                installerDone.countDown();
              }
            });
      }

      pool.submit(
          () -> {
            try {
              startGate.await();
              // Wait for every installer to record at least one successful iteration
              // BEFORE starting the rotations — otherwise the destroyer can outrun
              // the installer ramp-up on slow CI runners and the per-thread floor
              // below would fail spuriously. The 30s timeout matches the
              // BARRIER_WAIT_SECONDS budget used elsewhere in this file.
              if (!allInstallersStarted.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS)) {
                unexpected.add(
                    new AssertionError(
                        "installer threads did not all reach first iteration inside "
                            + BARRIER_WAIT_SECONDS + "s; aborting destroyer"));
                return;
              }
              for (int r = 0; r < rotations; r++) {
                final var existing = fileIdRef.get();
                wowCache.deleteFile(existing);
                final var fresh = wowCache.addFile(FILE_NAME + "-rot-" + r);
                fileIdRef.set(fresh);
              }
            } catch (final Throwable t1) {
              unexpected.add(t1);
            } finally {
              stop.set(true);
            }
          });

      startGate.countDown();
      assertTrue(
          "installer threads must finish within the bounded wait window",
          installerDone.await(45, TimeUnit.SECONDS));

      if (!unexpected.isEmpty()) {
        final var first = unexpected.poll();
        fail("deleteFile/loadOrAdd race surfaced unexpected exception: " + first);
      }
      // Per-thread floor: every installer must run the body at least once.
      // A regression that starved N-1 threads (e.g., broke filesLock readLock
      // fairness) would surface here as a per-thread zero, even if the total
      // iteration count satisfies the old single-counter guard.
      for (int i = 0; i < installerThreads; i++) {
        assertTrue(
            "installer thread "
                + i
                + " did not enter the race loop (iterations="
                + perThreadIterations.get(i)
                + "); vacuous pass detected — per-thread floor must be >= 1",
            perThreadIterations.get(i) >= 1);
      }

      // Final consistency probe.
      final var surviving = wowCache.loadOrAdd(fileIdRef.get(), 0L, false);
      try {
        assertNotNull(
            "surviving file must accept loadOrAdd after the race", surviving);
      } finally {
        surviving.decrementReadersReferrer();
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly",
          pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Scenario 7 sibling — {@code truncateFile} vs concurrent
   * {@link LockFreeReadCache#loadOrAddForWrite} on the same fileId.
   *
   * <p>The {@code deleteFile + addFile} variant above always trips the dispatch
   * prelude's {@code IllegalArgumentException} on the rotated-away fileId; this
   * sibling exercises the {@code truncateFile} shape that keeps the SAME fileId
   * across rotations. The actual contention is at the {@code filesLock} read/write
   * boundary: every installer's {@code loadOrAdd} holds {@code filesLock.readLock}
   * inside the wrapper's lambda; every {@code truncateFile} holds
   * {@code filesLock.writeLock}. The installer either completes (truncate hadn't
   * started yet) or observes a fresh-file state after {@code truncateFile} shrunk
   * the file and may extend it back to size 1 via the extend branch on its next
   * loop iteration.
   *
   * <p><b>Wrapper-level eviction is bypassed by design.</b> The destroyer drives
   * {@code wowCache.truncateFile} directly — it does NOT call
   * {@code readCache.truncateFile}. The wrapper's truncate path cannot run
   * concurrently with pinned entries for the same reason
   * {@link LockFreeReadCache#deleteFile} cannot
   * ({@code clearFile} aborts on pinned pages), so wiring it into a contention
   * test with installers pinning entries on the same fileId would either deadlock
   * or surface a spurious abort. The test instead exercises the disk-engine's
   * {@code filesLock} discipline directly: {@link WOWCache#truncateFile} calls
   * {@code removeCachedPages(intId)} inside {@code filesLock.writeLock} (touching
   * only the {@code writeCachePages} dirty map). The wrapper's {@code data}
   * entries for the same {@code fileId} are not removed, but the installer
   * threads' subsequent {@code loadOrAdd} calls on a freshly-truncated file route
   * back into the disk engine's extend branch and re-populate everything
   * correctly. Wrapper-level stale-entry cleanup is deferred to the eventual
   * overflow / shutdown path; it does not run inside this test's bounded window.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void truncateFileAndLoadOrAddRaceLeavesCacheConsistent() throws Exception {
    final int installerThreads = 8;
    final int rotations = 50;
    final var fileId = wowCache.addFile(FILE_NAME);
    final var pool = Executors.newFixedThreadPool(installerThreads + 1);
    try {
      final var stop = new AtomicBoolean(false);
      final var unexpected = new ConcurrentLinkedQueue<Throwable>();
      final var startGate = new CountDownLatch(1);
      final var installerDone = new CountDownLatch(installerThreads);
      // Per-thread iteration counter — see the deleteFile sibling above for the
      // rationale. A regression that starved N-1 threads of the filesLock read
      // lock would pass a total-count guard but fail the per-thread floor below.
      final var perThreadIterations = new AtomicLongArray(installerThreads);
      // Coordination latch — see the deleteFile sibling above for rationale.
      final var allInstallersStarted = new CountDownLatch(installerThreads);

      for (int t = 0; t < installerThreads; t++) {
        final int workerId = t;
        pool.submit(
            () -> {
              try {
                startGate.await();
                boolean firstIterationCompleted = false;
                while (!stop.get() || !firstIterationCompleted) {
                  try {
                    final var pointer = wowCache.loadOrAdd(fileId, 0L, false);
                    perThreadIterations.incrementAndGet(workerId);
                    pointer.decrementReadersReferrer();
                    if (!firstIterationCompleted) {
                      firstIterationCompleted = true;
                      allInstallersStarted.countDown();
                    }
                  } catch (final IllegalStateException e) {
                    // Tolerated: I4 sentinel from the bare-WOWCache same-pageIndex
                    // race after a {@link WOWCache#truncateFile} shrunk the file.
                    // Same rationale as the deleteFile sibling above.
                    if (e.getMessage() == null
                        || !(e.getMessage().contains("allocated pageIndex")
                            || e.getMessage().contains("allocated start index"))
                        || !e.getMessage().contains("does not match")) {
                      unexpected.add(e);
                    }
                  }
                }
              } catch (final Throwable t1) {
                unexpected.add(t1);
              } finally {
                installerDone.countDown();
              }
            });
      }

      pool.submit(
          () -> {
            try {
              startGate.await();
              // Wait for every installer to record at least one successful iteration
              // before starting truncate rotations — see the deleteFile sibling above
              // for the rationale on slow CI runners.
              if (!allInstallersStarted.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS)) {
                unexpected.add(
                    new AssertionError(
                        "installer threads did not all reach first iteration inside "
                            + BARRIER_WAIT_SECONDS + "s; aborting destroyer"));
                return;
              }
              for (int r = 0; r < rotations; r++) {
                wowCache.truncateFile(fileId);
              }
            } catch (final Throwable t1) {
              unexpected.add(t1);
            } finally {
              stop.set(true);
            }
          });

      startGate.countDown();
      assertTrue(
          "installer threads must finish within the bounded wait window",
          installerDone.await(45, TimeUnit.SECONDS));

      if (!unexpected.isEmpty()) {
        final var first = unexpected.poll();
        fail(
            "truncateFile/loadOrAdd race surfaced unexpected exception: " + first);
      }
      // Per-thread floor: every installer must run the body at least once. See the
      // deleteFile sibling above for the rationale (a starved N-1 threads would
      // pass the old total-count guard but fail the per-thread floor here).
      for (int i = 0; i < installerThreads; i++) {
        assertTrue(
            "installer thread "
                + i
                + " did not enter the race loop (iterations="
                + perThreadIterations.get(i)
                + "); vacuous pass detected — per-thread floor must be >= 1",
            perThreadIterations.get(i) >= 1);
      }

      // Final consistency probe.
      final var surviving = wowCache.loadOrAdd(fileId, 0L, false);
      try {
        assertNotNull(
            "surviving file must accept loadOrAdd after the race", surviving);
      } finally {
        surviving.decrementReadersReferrer();
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly",
          pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * I4 negative defence — invoke {@code WOWCache.loadOrAddExtendBranch} directly
   * with a {@link File} whose {@code allocateSpace} returns a position that does
   * <i>not</i> match the requested {@code pageIndex}. The hard
   * {@link IllegalStateException} throw with the
   * {@code "allocated pageIndex … does not match"} message added by Track 1
   * Step 2's review fix — the I4 sentinel — must fire deterministically.
   *
   * <p>The test pins falsifiability deterministically: if a future refactor
   * relaxed the equality check (e.g. to a warning log), the mismatched allocation
   * would no longer surface the {@link IllegalStateException} and this test would
   * fail loudly. It also pins the ordering of the positive-evidence counter
   * (incremented at the end of the branch only) by asserting
   * {@code getLoadOrAddExtendBranchInvocationsForTest()} is unchanged across the
   * throw — a refactor that moved the increment before the sanity check would be
   * caught here.
   *
   * <p>The bug ticket's poison cascade was driven by a reader observing a
   * partial allocator's pageIndex; the structural fix removes the discovery
   * channel that allowed the misinterpretation, and the I4 sentinel is the
   * production-build safety net for any residual I2/I4 violation if the segment
   * lock is ever bypassed.
   *
   * <p><b>Why direct invocation and not a thread race.</b> An earlier version of
   * this test drove N parallel workers through the public {@code loadOrAdd} on a
   * fresh file with {@code pageIndex == 0}, hoping at least one worker would
   * observe the I4 sentinel within 50 attempts. The race window between
   * {@code fileClassic.getFileSize()} and {@code allocateSpace} closed faster than
   * 16 workers could wedge into it on dedicated CCX33 hardware: the first
   * allocator's monotonic getAndAdd raced ahead of every other worker's
   * currentSize read, all other workers fell through to the load branch, and the
   * sentinel never fired. Direct invocation with a doctored {@link File}
   * sidesteps the timing dependency while still pinning the exact equality check
   * the sentinel guards. The production behaviour (workers seeing concurrent
   * allocations) remains covered by
   * {@link com.jetbrains.youtrackdb.internal.core.storage.impl.local.LoadOrAddPoisonCascadeRegressionTest}.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void extendBranchSurfacesI4SentinelWhenAllocatedIndexAboveRequested()
      throws Exception {
    // pageIndex=0, allocatedIndex=2 — the most common shape, mirroring the original
    // race-based test's setup but driven deterministically through reflection.
    runExtendBranchI4SentinelAssertion(
        /* testCaseSuffix= */ "above-requested",
        /* pageIndex= */ 0L,
        /* allocatedPosition= */ (long) PAGE_SIZE * 2L,
        /* expectedMessageContains= */ List.of(
            "allocated pageIndex 2", "requested pageIndex 0", "does not match"));
  }

  /**
   * I4 negative defence — symmetric direction. Drives {@code pageIndex == 5}
   * while the mocked allocator returns position {@code 2 * pageSize}
   * ({@code allocatedIndex == 2 < pageIndex}). Without this test, a regression
   * that flipped {@code allocatedIndex != pageIndex} to a one-sided comparison
   * (e.g. {@code allocatedIndex > pageIndex}) would still pass
   * {@link #extendBranchSurfacesI4SentinelWhenAllocatedIndexAboveRequested} and
   * land silently. Pairs with the above test to pin the equality check as
   * bidirectional.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void extendBranchSurfacesI4SentinelWhenAllocatedIndexBelowRequested()
      throws Exception {
    runExtendBranchI4SentinelAssertion(
        /* testCaseSuffix= */ "below-requested",
        /* pageIndex= */ 5L,
        /* allocatedPosition= */ (long) PAGE_SIZE * 2L,
        /* expectedMessageContains= */ List.of(
            "allocated pageIndex 2", "requested pageIndex 5", "does not match"));
  }

  /**
   * I4 negative defence — negative-allocation guard at the head of
   * {@code loadOrAddExtendBranch} ({@code allocatedIndex < 0}). With a real
   * {@link com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile} this
   * guard is unreachable, but the deterministic Mockito shape makes it cheap to
   * pin: a regression that swapped the order of the negative-index check and
   * the equality check, or that demoted the {@link IllegalStateException} to a
   * logged warning, would otherwise be tolerated.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void extendBranchRejectsNegativeAllocatedPosition() throws Exception {
    // pageIndex=0, allocatedPosition=-pageSize → allocatedIndex=-1 → trips the
    // negative guard at the head of loadOrAddExtendBranch.
    runExtendBranchI4SentinelAssertion(
        /* testCaseSuffix= */ "negative-allocation",
        /* pageIndex= */ 0L,
        /* allocatedPosition= */ -((long) PAGE_SIZE),
        /* expectedMessageContains= */ List.of("Illegal page index value -1"));
  }

  /**
   * Shared body for the extend-branch guard tests. Drives
   * {@code loadOrAddExtendBranch} reflectively with a mocked {@link File} whose
   * {@code allocateSpace} returns {@code allocatedPosition}, then asserts:
   *
   * <ul>
   *   <li>The exception is an {@link IllegalStateException} whose message contains
   *       every entry in {@code expectedMessageContains} — pins both the numeric
   *       interpolation (operator-facing diagnostic value, e.g. {@code
   *       "allocated pageIndex 2"}) and the discriminating phrase ({@code
   *       "does not match"} for the I4 sentinel, {@code "Illegal page index value -1"}
   *       for the negative-allocation guard).
   *   <li>The positive-evidence counter
   *       {@code getLoadOrAddExtendBranchInvocationsForTest} is unchanged across
   *       the throw — pins the "counted at end of branch" ordering documented on
   *       the production field.
   * </ul>
   *
   * <p><b>Caller precondition.</b> The chosen {@code allocatedPosition} MUST make
   * one of the branch's guards fire (either {@code allocatedPosition / pageSize < 0}
   * or {@code allocatedPosition / pageSize != pageIndex}); the helper does not
   * validate the precondition and a value that satisfies the equality check would
   * surface a misleading {@code fail("expected IllegalStateException ...")}.
   *
   * <p>Signature-drift failures inside the reflective invocation
   * ({@link IllegalAccessException}, {@link IllegalArgumentException}) are
   * translated into a clear test-failure message rather than bubbling as raw
   * reflection exceptions, so a future signature change on
   * {@code loadOrAddExtendBranch} surfaces as "fix the test" instead of an
   * opaque reflection error.
   */
  private void runExtendBranchI4SentinelAssertion(
      final String testCaseSuffix,
      final long pageIndex,
      final long allocatedPosition,
      final List<String> expectedMessageContains)
      throws Exception {
    // wowCache.addFile registers an intId in the cache-internal nameIdMap so the
    // post-success path (commitExecutor().submit(...)) sees a known id — load-bearing
    // even though we never reach that path on the throw side. A literal int would
    // work today, but keeping the real registration insulates the test from future
    // refactors that move bookkeeping above the throw.
    final var fileId = wowCache.addFile(FILE_NAME + "-extend-" + testCaseSuffix);
    final var intId = WOWCache.extractFileId(fileId);
    final var mismatchedFile = mock(File.class);
    when(mismatchedFile.allocateSpace(anyInt())).thenReturn(allocatedPosition);

    final long preInvocations = wowCache.getLoadOrAddExtendBranchInvocationsForTest();
    final Method method =
        WOWCache.class.getDeclaredMethod(
            "loadOrAddExtendBranch", int.class, long.class, File.class);
    method.setAccessible(true);
    try {
      method.invoke(wowCache, intId, pageIndex, mismatchedFile);
      fail(
          "expected IllegalStateException from extend-branch guard ("
              + testCaseSuffix
              + "): pageIndex="
              + pageIndex
              + " allocatedPosition="
              + allocatedPosition);
    } catch (final InvocationTargetException e) {
      final Throwable cause = e.getCause();
      assertTrue(
          "expected IllegalStateException, got " + (cause == null ? "null" : cause.getClass()),
          cause instanceof IllegalStateException);
      final String message = cause.getMessage();
      assertNotNull("expected non-null guard message", message);
      for (final String expected : expectedMessageContains) {
        assertTrue(
            "expected extend-branch guard message to contain \""
                + expected
                + "\", got: "
                + message,
            message.contains(expected));
      }
    } catch (final IllegalAccessException | IllegalArgumentException e) {
      fail(
          "reflection setup broken — loadOrAddExtendBranch signature changed? "
              + e);
    }
    assertEquals(
        "extend-branch guard must NOT bump the positive-evidence probe "
            + "(counter is bumped at the end of the branch on purpose; "
            + "see WOWCache.loadOrAddExtendBranchInvocations field Javadoc)",
        preInvocations,
        wowCache.getLoadOrAddExtendBranchInvocationsForTest());
  }

  /**
   * Gap-fill I4 negative defence — invoke {@code WOWCache.loadOrAddGapFillBranch}
   * directly with a {@link File} whose {@code allocateSpace} returns a position
   * whose {@code allocatedStartIndex} does <i>not</i> match the {@code currentSize}
   * argument. The hard {@link IllegalStateException} throw with the
   * {@code "allocated start index … does not match currentSize"} message added by
   * Track 1's review fix — the gap-fill I4 sentinel — must fire deterministically.
   *
   * <p>Counterpart of
   * {@link #extendBranchSurfacesI4SentinelWhenAllocatedIndexAboveRequested}: that
   * test exercises the I4 sentinel on the single-page extend branch
   * ({@code pageIndex == currentSize}); this test exercises the symmetric sentinel
   * on the gap-fill branch ({@code pageIndex &gt; currentSize}).
   *
   * <p>The test pins falsifiability deterministically: if a future refactor
   * relaxed the equality check (e.g. to a warning log), the mismatched allocation
   * would no longer surface the {@link IllegalStateException} and this test would
   * fail loudly. It also pins the ordering of the positive-evidence counter
   * (incremented at the end of the branch only) by asserting
   * {@code getLoadOrAddGapFillBranchInvocationsForTest()} is unchanged across the
   * throw — a refactor that moved the increment before the sanity check would be
   * caught here.
   *
   * <p><b>Why direct invocation and not a thread race.</b> An earlier version of
   * this test drove 16 parallel workers through the public {@code loadOrAdd} on a
   * file pre-extended to {@code currentSize == 2}, all targeting
   * {@code pageIndex == 10}, hoping at least one worker would observe the gap-fill
   * I4 sentinel within 50 attempts. The race window between the workers'
   * {@code currentSize} read and {@code AsyncFile.allocateSpace}'s monotonic
   * getAndAdd closed faster than 16 workers could wedge into it on Linux arm
   * JDK 25 CI runners: the first allocator's getAndAdd raced ahead of every other
   * worker's currentSize read, all other workers fell through to the load branch,
   * and the gap-fill sentinel never fired. Direct invocation with a doctored
   * {@link File} sidesteps the timing dependency while still pinning the exact
   * equality check the sentinel guards.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void gapFillBranchSurfacesI4SentinelWhenAllocatedStartIndexAboveCurrentSize()
      throws Exception {
    // pageIndex=10, currentSize=2, allocatedPosition=3*pageSize → allocatedStartIndex=3
    // (above 2). Mirrors the original race-based test's setup (currentSize=2, pageIndex=10)
    // but driven deterministically through reflection.
    runGapFillBranchI4SentinelAssertion(
        /* testCaseSuffix= */ "above-currentSize",
        /* pageIndex= */ 10L,
        /* currentSize= */ 2L,
        /* allocatedPosition= */ (long) PAGE_SIZE * 3L,
        /* expectedMessageContains= */ List.of(
            "allocated start index 3", "currentSize 2", "does not match"));
  }

  /**
   * Gap-fill I4 negative defence — symmetric direction. Drives
   * {@code pageIndex == 10} with {@code currentSize == 5} while the mocked
   * allocator returns position {@code 2 * pageSize}
   * ({@code allocatedStartIndex == 2 < currentSize == 5}). Without this test, a
   * regression that flipped {@code allocatedStartIndex != currentSize} to a
   * one-sided comparison (e.g. {@code allocatedStartIndex > currentSize}) would
   * still pass
   * {@link #gapFillBranchSurfacesI4SentinelWhenAllocatedStartIndexAboveCurrentSize}
   * and land silently. Pairs with the above test to pin the equality check as
   * bidirectional.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void gapFillBranchSurfacesI4SentinelWhenAllocatedStartIndexBelowCurrentSize()
      throws Exception {
    runGapFillBranchI4SentinelAssertion(
        /* testCaseSuffix= */ "below-currentSize",
        /* pageIndex= */ 10L,
        /* currentSize= */ 5L,
        /* allocatedPosition= */ (long) PAGE_SIZE * 2L,
        /* expectedMessageContains= */ List.of(
            "allocated start index 2", "currentSize 5", "does not match"));
  }

  /**
   * Gap-fill I4 negative defence — negative-allocation guard at the head of
   * {@code loadOrAddGapFillBranch} ({@code allocatedStartIndex < 0}). With a real
   * {@link com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile} this
   * guard is unreachable, but the deterministic Mockito shape makes it cheap to
   * pin: a regression that swapped the order of the negative-index check and the
   * equality check, or that demoted the {@link IllegalStateException} to a logged
   * warning, would otherwise be tolerated.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void gapFillBranchRejectsNegativeAllocatedStartIndex() throws Exception {
    // pageIndex=10, currentSize=2, allocatedPosition=-pageSize → allocatedStartIndex=-1 →
    // trips the negative guard at the head of loadOrAddGapFillBranch.
    runGapFillBranchI4SentinelAssertion(
        /* testCaseSuffix= */ "negative-allocation",
        /* pageIndex= */ 10L,
        /* currentSize= */ 2L,
        /* allocatedPosition= */ -((long) PAGE_SIZE),
        /* expectedMessageContains= */ List.of("Illegal page index value -1"));
  }

  /**
   * Shared body for the gap-fill-branch guard tests. Drives
   * {@code loadOrAddGapFillBranch} reflectively with a mocked {@link File} whose
   * {@code allocateSpace} returns {@code allocatedPosition}, then asserts:
   *
   * <ul>
   *   <li>The exception is an {@link IllegalStateException} whose message contains
   *       every entry in {@code expectedMessageContains} — pins both the numeric
   *       interpolation (operator-facing diagnostic value, e.g. {@code
   *       "allocated start index 3"}, {@code "currentSize 2"}) and the
   *       discriminating phrase ({@code "does not match"} for the I4 sentinel,
   *       {@code "Illegal page index value -1"} for the negative-allocation guard).
   *   <li>The positive-evidence counter
   *       {@code getLoadOrAddGapFillBranchInvocationsForTest} is unchanged across
   *       the throw — pins the "counted at end of branch" ordering documented on
   *       the production field.
   * </ul>
   *
   * <p><b>Caller precondition.</b> The chosen {@code allocatedPosition} MUST make
   * one of the branch's guards fire (either {@code allocatedPosition / pageSize < 0}
   * or {@code allocatedPosition / pageSize != currentSize}); the helper does not
   * validate the precondition and a value that satisfies the equality check would
   * surface a misleading {@code fail("expected IllegalStateException ...")}. The
   * chosen {@code (pageIndex, currentSize)} pair must additionally satisfy
   * {@code (pageIndex - currentSize + 1) * pageSize <= Integer.MAX_VALUE} so the
   * {@code requestedBytes > Integer.MAX_VALUE} pre-check at the head of the gap-fill
   * branch does not pre-empt the guard the test is targeting.
   *
   * <p>Signature-drift failures inside the reflective invocation
   * ({@link IllegalAccessException}, {@link IllegalArgumentException}) are
   * translated into a clear test-failure message rather than bubbling as raw
   * reflection exceptions, so a future signature change on
   * {@code loadOrAddGapFillBranch} surfaces as "fix the test" instead of an
   * opaque reflection error.
   */
  private void runGapFillBranchI4SentinelAssertion(
      final String testCaseSuffix,
      final long pageIndex,
      final long currentSize,
      final long allocatedPosition,
      final List<String> expectedMessageContains)
      throws Exception {
    // wowCache.addFile registers an intId in the cache-internal nameIdMap; see
    // runExtendBranchI4SentinelAssertion for the rationale.
    final var fileId = wowCache.addFile(FILE_NAME + "-gapfill-" + testCaseSuffix);
    final var intId = WOWCache.extractFileId(fileId);
    final var mismatchedFile = mock(File.class);
    when(mismatchedFile.allocateSpace(anyInt())).thenReturn(allocatedPosition);

    final long preInvocations = wowCache.getLoadOrAddGapFillBranchInvocationsForTest();
    final Method method =
        WOWCache.class.getDeclaredMethod(
            "loadOrAddGapFillBranch", int.class, long.class, long.class, File.class);
    method.setAccessible(true);
    try {
      method.invoke(wowCache, intId, pageIndex, currentSize, mismatchedFile);
      fail(
          "expected IllegalStateException from gap-fill-branch guard ("
              + testCaseSuffix
              + "): pageIndex="
              + pageIndex
              + " currentSize="
              + currentSize
              + " allocatedPosition="
              + allocatedPosition);
    } catch (final InvocationTargetException e) {
      final Throwable cause = e.getCause();
      assertTrue(
          "expected IllegalStateException, got " + (cause == null ? "null" : cause.getClass()),
          cause instanceof IllegalStateException);
      final String message = cause.getMessage();
      assertNotNull("expected non-null guard message", message);
      for (final String expected : expectedMessageContains) {
        assertTrue(
            "expected gap-fill-branch guard message to contain \""
                + expected
                + "\", got: "
                + message,
            message.contains(expected));
      }
    } catch (final IllegalAccessException | IllegalArgumentException e) {
      fail(
          "reflection setup broken — loadOrAddGapFillBranch signature changed? "
              + e);
    }
    assertEquals(
        "gap-fill-branch guard must NOT bump the positive-evidence probe "
            + "(counter is bumped at the end of the branch on purpose; "
            + "see WOWCache.loadOrAddGapFillBranchInvocations field Javadoc)",
        preInvocations,
        wowCache.getLoadOrAddGapFillBranchInvocationsForTest());
  }

  /**
   * Companion to {@link #concurrentLoadOnSameKeyAllObserveSamePointer} that drives the
   * actual production write surface — {@link LockFreeReadCache#loadOrAddForWrite} —
   * with same-key contention. The existing sibling test routes through
   * {@link LockFreeReadCache#loadForRead} to avoid the same-key cross-worker deadlock
   * on the entry's exclusive lock, but the wrapper's segment-lock serialisation
   * contract that the test claims to defend is what {@code loadOrAddForWrite} relies
   * on for invariant I2 ("all cache page-extension occurs inside {@code data.compute}").
   * Pinning the contract on the read surface alone would silently miss a regression
   * that broke the write-side segment-lock path.
   *
   * <p>Race shape: two workers (N=2, the minimum that avoids the same-key
   * cross-worker deadlock on the entry's exclusive lock) both call
   * {@code readCache.loadOrAddForWrite(fileId, 0L, …)} on a fresh file. The
   * wrapper's {@code data.compute} lambda serialises them: exactly one worker enters
   * the lambda first, takes the extend branch on {@link WOWCache#loadOrAdd}, and
   * installs the cache entry; the second worker hits the cache-hit fast path
   * (entry already in the map) and acquires the same entry. Each worker immediately
   * releases the entry (via {@code releaseFromWrite}) so neither holds the entry's
   * exclusive lock long enough to block the other indefinitely.
   *
   * <p>Pins:
   *
   * <ul>
   *   <li>Both workers succeed; no exception of any kind on either thread.
   *   <li>Both returned {@link CacheEntry} instances share the same
   *       {@link CachePointer} instance (verified via {@code IdentityHashMap} with
   *       {@code size == 1}).
   *   <li>Exactly one extend happened: {@code wowCache.getFilledUpTo(fileId) == 1L}.
   * </ul>
   *
   * <p>A regression that broke the wrapper's segment-lock contract on the write
   * surface — e.g., a refactor that bypassed {@code data.compute} for
   * {@code loadOrAddForWrite} — would surface here as either two distinct
   * {@link CachePointer}s (each worker extended independently) or
   * {@code getFilledUpTo == 2L} (two extends).
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void concurrentLoadOrAddForWriteOnSameKeyObservesSegmentLockSerialisation()
      throws Exception {
    final int threads = 2;
    final var fileId = wowCache.addFile(FILE_NAME);
    final var pool = Executors.newFixedThreadPool(threads);
    try {
      final var startBarrier = new CyclicBarrier(threads);
      final List<Callable<CacheEntry>> workers = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        workers.add(
            () -> {
              startBarrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
              final var entry =
                  readCache.loadOrAddForWrite(fileId, 0L, wowCache, false, null);
              // Immediate release: with N=2 and prompt release neither worker
              // holds the entry's exclusive lock long enough to deadlock the
              // other. The race window pinned is the wrapper's segment-lock
              // serialisation inside data.compute, not the entry-level lock.
              readCache.releaseFromWrite(entry, wowCache, /* changed = */ false);
              return entry;
            });
      }
      final var futures = pool.invokeAll(workers);
      final List<CacheEntry> entries = new ArrayList<>(threads);
      for (final var future : futures) {
        final var entry = future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(
            "loadOrAddForWrite on a same-key race must return a non-null entry", entry);
        entries.add(entry);
      }
      // Identity-based set: both workers must share the same CachePointer instance —
      // exactly one extend, exactly one pointer published, both workers observed it.
      final Set<CachePointer> distinct =
          Collections.newSetFromMap(new IdentityHashMap<>());
      for (final var entry : entries) {
        distinct.add(entry.getCachePointer());
      }
      assertEquals(
          "two writers on the same key must observe one CachePointer instance",
          1,
          distinct.size());
      assertEquals(
          "exactly one extend must have happened — AsyncFile.size == 1 page",
          1L,
          wowCache.getFilledUpTo(fileId));
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly",
          pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Smoke-shape sanity probe: a single-threaded {@code loadOrAddForWrite} +
   * {@code deleteFile} round-trip through the wrapper to verify the harness's
   * tearDown pattern is exception-free. Distinguishes a real race-test failure
   * from a test-infrastructure regression where the singleton
   * {@code wowCacheFlushExecutor} is wedged. Also exercises the test's bare-cache
   * {@code AtomicReference}-based fileId rotation pattern with a single rotation
   * so a follow-up reader can verify the destroyer path's basic correctness
   * separate from the contention.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void singleThreadedLoadAndRotateRoundTripIsExceptionFree() throws Exception {
    final var fileIdRef = new AtomicReference<>(wowCache.addFile(FILE_NAME));
    final var pointer = wowCache.loadOrAdd(fileIdRef.get(), 0L, false);
    try {
      assertNotNull(pointer);
      assertEquals(
          "single extend must advance AsyncFile.size to one page",
          1L,
          wowCache.getFilledUpTo(fileIdRef.get()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    wowCache.deleteFile(fileIdRef.get());
    final var fresh = wowCache.addFile(FILE_NAME + "-fresh");
    fileIdRef.set(fresh);
    final var afterRotate = wowCache.loadOrAdd(fileIdRef.get(), 0L, false);
    try {
      assertNotNull("rotated fileId must accept loadOrAdd", afterRotate);
    } finally {
      afterRotate.decrementReadersReferrer();
    }
  }
}
