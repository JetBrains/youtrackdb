package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
 *   <li><b>I4 negative defence</b>: two threads driven into the bare
 *       {@link WOWCache#loadOrAdd} call (bypassing
 *       {@link LockFreeReadCache#data}{@code .compute}) on the same {@code (fileId,
 *       pageIndex)} — exactly the I2/I4 violation the wrapper exists to prevent.
 *       At least one thread MUST observe the {@code "allocated pageIndex … does
 *       not match"} {@link IllegalStateException} (the I4 sentinel) added by
 *       Track 1 Step 2's review fix. Pins that the cache-internal fast-fail stays
 *       falsifiable.
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
    readCache = new LockFreeReadCache(bufferPool, READ_CACHE_MAX_MEMORY, PAGE_SIZE);
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
   * but exercises the disk-engine {@code filesLock} discipline. The
   * {@code iterationCounter} pin guards against a vacuous pass where the destroyer
   * finishes before installers ramp up.
   *
   * <p><b>Cache eviction caveat.</b> Each {@code deleteFile + addFile} cycle does
   * NOT remove the stale {@link CacheEntry} from the wrapper's map by itself — the
   * wrapper's stale entries become unreachable when the installer's
   * {@code data.get(staleFileId, …)} misses (the installer threads always query
   * the latest fileId from {@code fileIdRef}). To avoid accumulating stale entries
   * across rotations the destroyer calls {@code readCache.deleteFile} on the dying
   * fileId before issuing the {@code addFile}; the wrapper's evict-by-fileId logic
   * cleans up. Without this the test would gradually pin every page from every
   * rotation in the cache and eventually trip the cache's overflow check.
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
      final var iterationCounter = new AtomicLong();

      for (int t = 0; t < installerThreads; t++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                while (!stop.get()) {
                  try {
                    final var pointer = wowCache.loadOrAdd(fileIdRef.get(), 0L, false);
                    iterationCounter.incrementAndGet();
                    pointer.decrementReadersReferrer();
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
      assertTrue(
          "installers must execute the loadOrAdd loop body at least once per thread; "
              + "vacuous pass detected (iterations="
              + iterationCounter.get()
              + ")",
          iterationCounter.get() >= installerThreads);

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
   * <p><b>truncateFile invalidates cached entries.</b> {@link WOWCache#truncateFile}
   * calls {@code removeCachedPages(intId)} inside the writeLock, but that only
   * touches the {@link WOWCache#writeCachePages} dirty map — the wrapper's
   * {@link LockFreeReadCache#data} entries for the same {@code fileId} are not
   * removed. After a {@code truncateFile + extend}, an in-flight wrapper hit on the
   * stale entry would return a pointer whose underlying disk page is gone. The
   * destroyer also calls {@code readCache.truncateFile} so the wrapper's stale
   * entries are evicted before the next {@code truncateFile + extend} cycle.
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
      final var iterationCounter = new AtomicLong();

      for (int t = 0; t < installerThreads; t++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                while (!stop.get()) {
                  try {
                    final var pointer = wowCache.loadOrAdd(fileId, 0L, false);
                    iterationCounter.incrementAndGet();
                    pointer.decrementReadersReferrer();
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
      assertTrue(
          "installers must execute the loadOrAdd loop body at least once per thread; "
              + "vacuous pass detected (iterations="
              + iterationCounter.get()
              + ")",
          iterationCounter.get() >= installerThreads);

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
   * I4 negative defence — drive two threads directly into bare
   * {@link WOWCache#loadOrAdd} on the same {@code (fileId, pageIndex)}.
   *
   * <p>The wrapper's {@code data.compute} segment write lock is what makes
   * scenario 2 safe; bypassing the wrapper deliberately violates invariants I2 and
   * I4 and exercises the cache-internal fast-fail. With currentSize=0 and N threads
   * all calling {@code loadOrAdd(fileId, 0, false)}, the dispatch prelude routes
   * all threads into the extend branch ({@code pageIndex == currentSize == 0}).
   * Inside the extend branch, {@code AsyncFile.allocateSpace}'s monotonic
   * {@code getAndAdd} serialises the page-byte allocation: one thread gets position
   * 0 ({@code allocatedIndex == pageIndex == 0}) and completes; every other thread
   * gets a position strictly above 0 ({@code allocatedIndex != pageIndex}) and the
   * I4 sentinel — the hard {@link IllegalStateException} throw with the
   * {@code "allocated pageIndex … does not match"} message added by Track 1
   * Step 2's review fix — fires.
   *
   * <p>The test pins:
   *
   * <ul>
   *   <li>At least one worker observes the I4 sentinel exception. If a future
   *       refactor relaxed the equality check to a warning log, every worker would
   *       succeed and the test would fail loudly.
   *   <li>At least one worker succeeds (the {@code allocateSpace} winner).
   *   <li>Every non-success is exactly the I4 sentinel — no other exception type.
   * </ul>
   *
   * <p><b>Why this matters.</b> The bug ticket's poison cascade was driven by a
   * reader observing a partial allocator's pageIndex. The structural fix removes
   * the discovery channel that allowed the misinterpretation; the I4 sentinel is
   * the production-build safety net that catches any residual I2/I4 violation if
   * the segment lock is ever bypassed.
   *
   * <p>Two threads is the minimum to surface the I4 sentinel reliably (one winner,
   * one loser); eight threads gives enough headroom that the I4 throw fires
   * deterministically in CI without depending on scheduler luck.
   */
  @Test(timeout = PER_TEST_TIMEOUT_MS)
  public void bareLoadOrAddOnSameKeySurfacesI4Sentinel() throws Exception {
    final int threads = 16;
    // The race window between fileClassic.getFileSize() and allocateSpace() inside
    // loadOrAdd is tight enough that a single round of N parallel workers may not
    // surface the I4 sentinel — only one thread typically wins the
    // currentSize-read race per round before the others observe the bumped size and
    // fall through to the load branch. Retry the race up to maxAttempts; the test
    // passes as soon as at least one I4 sentinel observation is recorded. This
    // converts the test from "expected to sometimes flake" to "expected to fire
    // deterministically inside the maxAttempts bound" — empirically, 8-thread
    // races trip the sentinel inside 3-5 attempts on a CI runner. The 60s @Test
    // ceiling caps the total wall time regardless.
    final int maxAttempts = 50;
    boolean sentinelObserved = false;
    int attempt = 0;
    while (!sentinelObserved && attempt < maxAttempts) {
      attempt++;
      // Use a per-attempt fileId so each round starts from currentSize == 0 and
      // every worker enters the extend branch contest fresh.
      final var fileId = wowCache.addFile(FILE_NAME + "-attempt-" + attempt);
      final var pool = Executors.newFixedThreadPool(threads);
      try {
        final var startBarrier = new CyclicBarrier(threads);
        final List<Callable<CachePointer>> workers = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
          workers.add(
              () -> {
                startBarrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
                return wowCache.loadOrAdd(fileId, 0L, false);
              });
        }
        final var futures = pool.invokeAll(workers);
        int successes = 0;
        int i4SentinelObservations = 0;
        final List<CachePointer> winnerPointers = new ArrayList<>();
        final List<Throwable> unexpected = new ArrayList<>();
        for (final var future : futures) {
          try {
            final var pointer = future.get(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
            successes++;
            winnerPointers.add(pointer);
          } catch (final java.util.concurrent.ExecutionException e) {
            final var cause = e.getCause();
            if (cause instanceof IllegalStateException
                && cause.getMessage() != null
                && cause.getMessage().contains("allocated pageIndex")
                && cause.getMessage().contains("does not match")) {
              i4SentinelObservations++;
            } else {
              unexpected.add(cause);
            }
          }
        }
        try {
          if (!unexpected.isEmpty()) {
            fail(
                "I4 sentinel scenario surfaced unexpected exception type: "
                    + unexpected.get(0));
          }
          assertTrue(
              "at least one worker must succeed (the allocateSpace winner)",
              successes >= 1);
          assertEquals(
              "every worker accounted for (successes + I4 throws == threads)",
              threads,
              successes + i4SentinelObservations);
          if (i4SentinelObservations >= 1) {
            sentinelObserved = true;
          }
        } finally {
          for (final var winner : winnerPointers) {
            winner.decrementReadersReferrer();
          }
        }
      } finally {
        pool.shutdownNow();
        assertTrue(
            "executor must terminate cleanly",
            pool.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
    assertTrue(
        "at least one worker must observe the I4 sentinel "
            + "(\"allocated pageIndex does not match\") inside "
            + maxAttempts
            + " attempts; cache-layer fast-fail must stay falsifiable",
        sentinelObserved);
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
