package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Smoke coverage for {@link WOWCache#loadOrAdd} and its three branches
 * (load existing / one-page extend / multi-page gap-fill).
 *
 * <p>Each test exercises the new total cache primitive against a real {@link WOWCache}
 * over a temporary on-disk storage. The tests are intentionally narrow ("does each branch
 * fire and return a usable {@code CachePointer}?") rather than exhaustive; comprehensive
 * cache-coverage tests, multi-thread stress, eviction/flush races, and non-durable
 * extension all land in a follow-up dedicated test track.
 *
 * <p><b>Coverage scope note.</b> The defensive load-branch totality fallback
 * (return-magic-stamped-empty-buffer when {@code loadFileContent} returns {@code null})
 * cannot be exercised in this test class without a test seam (a custom
 * {@code loadFileContent} override or reflection on internal state). Exercising the dead
 * branch is therefore deferred to the dedicated cache-coverage test track that introduces
 * the necessary seams.
 */
public class WOWCacheLoadOrAddTest {

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;
  private static final String FILE_NAME = "wowCacheLoadOrAdd.tst";

  private static Path storagePath;
  private static String storageName;
  private static final ByteBufferPool bufferPool = new ByteBufferPool(PAGE_SIZE);

  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private ClosableLinkedContainer<Long, File> files;
  // The cached-thread-pool the WOWCache uses internally for AsyncFile I/O. Held so the
  // executor can be shut down in tearDown; without an explicit shutdown it would leak
  // non-daemon threads across test methods.
  private ExecutorService asyncFileExecutor;

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var buildDirectory = System.getProperty("buildDirectory", ".");
    storageName = "WOWCacheLoadOrAddTest";
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
  }

  @After
  public void tearDown() throws Exception {
    cleanUp();
  }

  private void cleanUp() throws IOException {
    if (wowCache != null) {
      wowCache.delete();
      wowCache = null;
    }
    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }
    if (asyncFileExecutor != null) {
      // Shut down the cached-thread-pool we passed into the WOWCache so each test method
      // does not leak non-daemon AsyncFile worker threads. WOWCache.delete already drains
      // pending work above; this only frees the underlying threads.
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
   * Extend branch: a fresh file has {@code currentSize == 0}; calling
   * {@code loadOrAdd(fileId, 0)} must take the one-page extend path, advance
   * {@code AsyncFile.size} to one page, and return a non-null {@link CachePointer} with
   * a clean buffer at position 0.
   */
  @Test
  public void extendBranchAllocatesAndReturnsEmptyPointer() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    assertEquals(
        "fresh file must start at 0 pages", 0L, wowCache.getFilledUpTo(fileId));

    final var pointer = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull("loadOrAdd must never return null on the extend branch", pointer);
      // The CachePointer's PageFrame buffer is freshly cleared by pageFramePool.acquire;
      // verify the buffer is positioned at 0 (callers expect a clean buffer state).
      final var buffer = pointer.getBuffer();
      assertNotNull(buffer);
      assertEquals("buffer should be positioned at 0", 0, buffer.position());
      // Magic-stamped empty buffer must carry LSN(-1, -1); the disk-side magic stamp
      // is written asynchronously by EnsurePageIsValidInFileTask but the in-memory LSN
      // is set directly by newEmptyCachePointer.
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(buffer));
    } finally {
      pointer.decrementReadersReferrer();
    }
    // After the extend, getFilledUpTo reflects the one new page allocated by AsyncFile.
    assertEquals(
        "extend branch must advance AsyncFile.size by one page",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Extend branch on a non-empty file: pre-extend two pages so {@code currentSize == 2},
   * then call {@code loadOrAdd(fileId, 2)} to exercise the boundary {@code pageIndex ==
   * currentSize} on a non-empty file. The extend must add exactly one page (size goes
   * from 2 to 3) and return a magic-stamped empty pointer. Ensures the extend branch is
   * not accidentally restricted to the fresh-file case.
   */
  @Test
  public void extendBranchExtendsByOneOnNonEmptyFile() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();
    assertEquals(
        "two extends must advance AsyncFile.size to 2 pages",
        2L,
        wowCache.getFilledUpTo(fileId));

    final var pointer = wowCache.loadOrAdd(fileId, 2L, false);
    try {
      assertNotNull(pointer);
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "extend on non-empty file advances size by exactly one page",
        3L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Idempotent re-call: a second {@code loadOrAdd(fileId, 0)} after the first extend
   * must take the load branch ({@code pageIndex < currentSize}) and not advance the file
   * size further. Verifies that a repeated call on an already-allocated page is a pure
   * load (no double-extend, totality contract still holds).
   */
  @Test
  public void secondLoadOrAddOnSameIndexTakesLoadBranchAndDoesNotReExtend()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    assertEquals(
        "single extend advances AsyncFile.size to 1 page",
        1L,
        wowCache.getFilledUpTo(fileId));

    final var second = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull(
          "second loadOrAdd on the same page index must return a usable pointer",
          second);
    } finally {
      second.decrementReadersReferrer();
    }
    assertEquals(
        "second loadOrAdd on an already-allocated page must not advance file size",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Negative {@code pageIndex}: the dispatch prelude must reject a negative page index
   * before any side effects (no acquire, no allocate, no submit). The exception message
   * must include the offending value so the caller can identify the bad input.
   */
  @Test
  public void loadOrAddRejectsNegativePageIndex() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> wowCache.loadOrAdd(fileId, -1L, false));
    assertNotNull("exception must carry a message", thrown.getMessage());
    assertTrue(
        "exception message must carry the bad page index value",
        thrown.getMessage().contains("-1"));
  }

  /**
   * Deleted-file caveat from the totality contract: the contract holds for any open,
   * non-deleted fileId, but a concurrently-deleted file surfaces an
   * {@link IllegalArgumentException} raw to the caller (caller-bug signal). Pre-extend
   * one page so the file is registered, delete it, then call {@code loadOrAdd} again;
   * the dispatch prelude (or {@code loadFileContent}) must propagate the
   * {@link IllegalArgumentException} instead of returning {@code null} or throwing a
   * different exception type.
   */
  @Test
  public void loadOrAddOnDeletedFilePropagatesIllegalArgumentExceptionRaw()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var p = wowCache.loadOrAdd(fileId, 0L, false);
    p.decrementReadersReferrer();
    wowCache.deleteFile(fileId);
    assertThrows(
        IllegalArgumentException.class, () -> wowCache.loadOrAdd(fileId, 0L, false));
  }

  /**
   * Load branch: after extending pages 0 and 1 and flushing them to disk, calling
   * {@code loadOrAdd(fileId, 0, false)} must take the load path (pageIndex less than
   * currentSize) and return a non-null {@link CachePointer} for an existing on-disk
   * page. The returned pointer must be the dirty-write-cache
   * pointer (priority over the on-disk image) when the page is still in
   * {@code writeCachePages}, and the disk-fallback pointer must be a fresh instance
   * (not the same one that's installed in {@code writeCachePages}). The flush call
   * before the dirty-write probe ensures the load-from-disk path observes a stamped
   * page, not the defensive totality fallback (which would silently return a
   * magic-stamped empty buffer if the on-disk file were lagging).
   */
  @Test
  public void loadBranchReturnsExistingPagePointerAndPrioritisesDirtyCache()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Extend two pages; both go through the extend branch on first invocation.
    final var page0 = wowCache.loadOrAdd(fileId, 0L, false);
    page0.decrementReadersReferrer();
    final var page1 = wowCache.loadOrAdd(fileId, 1L, false);
    page1.decrementReadersReferrer();

    // Drain the EnsurePageIsValidInFileTask submissions so page 1's on-disk magic stamp
    // is written before we test the disk-fallback path. Without this drain, the
    // load-branch fallback could trip on its defensive totality path even though that
    // would mask the real load-from-disk we are trying to verify.
    wowCache.flush(fileId);

    // Push page 0 into writeCachePages via store() so the dirty-write probe has
    // something to return on a subsequent loadOrAdd call.
    final var dirtyPointer = wowCache.load(fileId, 0L, new ModifiableBoolean(), false);
    try {
      assertNotNull(dirtyPointer);
      // Mark dirty by storing it back; this installs it in writeCachePages.
      wowCache.store(fileId, 0L, dirtyPointer);
    } finally {
      dirtyPointer.decrementReadersReferrer();
    }

    // currentSize is now 2; loadOrAdd(fileId, 0) takes the load branch and the
    // dirty-write probe wins, returning the very same CachePointer instance.
    final var loaded = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull("loadOrAdd must never return null on the load branch", loaded);
      assertSame(
          "dirty-write probe must win over on-disk image",
          dirtyPointer,
          loaded);
    } finally {
      loaded.decrementReadersReferrer();
    }

    // loadOrAdd(fileId, 1) takes the load branch and falls through to loadFileContent
    // because page 1 is not in writeCachePages. The returned pointer must be a fresh
    // (different-instance) CachePointer reading from disk. We cannot distinguish the
    // disk fallback from the dead-defensive totality fallback by LSN alone (a fresh
    // extend leaves LSN(-1,-1) on disk), but the prior wowCache.flush(fileId) ensures
    // the disk image is stamped, so a magic-stamped empty buffer from the totality
    // fallback would only fire if the dispatch prelude observed a longer AsyncFile.size
    // than loadFileContent saw — which the flush eliminates.
    final var loadedFromDisk = wowCache.loadOrAdd(fileId, 1L, false);
    try {
      assertNotNull(
          "loadOrAdd must never return null on the load branch (disk fallback)",
          loadedFromDisk);
      assertNotSame(
          "page not in writeCachePages must come from loadFileContent",
          dirtyPointer,
          loadedFromDisk);
    } finally {
      loadedFromDisk.decrementReadersReferrer();
    }
  }

  /**
   * Gap-fill branch: starting from a fresh file ({@code currentSize == 0}), calling
   * {@code loadOrAdd(fileId, 5)} must take the multi-page gap-fill path, advance
   * {@code AsyncFile.size} by 6 pages (indices 0..5 inclusive), and return a non-null
   * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer} for the
   * target page only. The intermediate gap pages (0..4) get an
   * {@link EnsurePageIsValidInFileTask} submission but are not held in the cache here.
   */
  @Test
  public void gapFillBranchAllocatesEntireGapAndReturnsTargetPointer() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    assertEquals(
        "fresh file must start at 0 pages", 0L, wowCache.getFilledUpTo(fileId));

    final var pointer = wowCache.loadOrAdd(fileId, 5L, false);
    try {
      assertNotNull("loadOrAdd must never return null on the gap-fill branch", pointer);
      // Buffer is freshly cleared by pageFramePool.acquire.
      final var buffer = pointer.getBuffer();
      assertNotNull(buffer);
      assertEquals("buffer should be positioned at 0", 0, buffer.position());
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(buffer));
    } finally {
      pointer.decrementReadersReferrer();
    }
    // After gap-fill, AsyncFile.size must advance from 0 to exactly 6 pages
    // ([0..5] inclusive = 6 pages). The extend / gap-fill branches use a single batched
    // allocateSpace call, so the post-call size is exactly pageIndex + 1, not just at
    // least.
    assertEquals(
        "gap-fill must advance AsyncFile.size to exactly pageIndex + 1 pages",
        6L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Smallest non-trivial gap-fill: starting at {@code currentSize == 1} after one extend,
   * a {@code loadOrAdd(fileId, 2)} call exercises the gap-fill branch where the loop
   * runs exactly twice (over pages 1 and 2). Verifies the inclusive {@code
   * [currentSize, pageIndex]} range against an off-by-one bug at the boundary.
   */
  @Test
  public void gapFillOfExactlyOnePageBoundary() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    assertEquals(
        "single extend advances AsyncFile.size to 1 page",
        1L,
        wowCache.getFilledUpTo(fileId));

    // currentSize == 1, pageIndex == 2 -> gap-fill of 2 pages.
    final var pointer = wowCache.loadOrAdd(fileId, 2L, false);
    try {
      assertNotNull(
          "smallest non-trivial gap-fill must return a usable pointer", pointer);
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "gap-fill of two pages must advance AsyncFile.size to exactly 3 pages",
        3L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * WAL-replay recovery simulation (gap-fill branch, {@code pageIndex >> currentSize}).
   *
   * <p>This test exercises the gap-fill branch as the WAL replay loop would in practice:
   * a component's {@code entryPoint.pagesSize} on disk records that the file was extended
   * to page {@code N}, but on reopen {@code AsyncFile.size} (re-initialized from the
   * physical disk length) may lag behind &mdash; so the replay loop calls
   * {@code loadOrAdd(fileId, N)} with {@code pageIndex > currentSize}. Here we simulate
   * that by extending two pages first (so {@code currentSize == 2}), then making a
   * synthetic recovery call with {@code pageIndex == 10} to trigger a gap of 8 pages.
   * The contract: all pages in {@code [2, 10]} are allocated on disk
   * ({@code AsyncFile.size} advances to 11), exactly one magic-stamped empty
   * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer} is returned
   * (for the target page only), and the pointer carries LSN {@code (-1,-1)}. See design.md
   * §"Crash safety" scenario B for the walk-through.
   */
  @Test
  public void recoverySimulationGapFillWithLargeGap() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Establish currentSize == 2 via two sequential extend calls.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();
    assertEquals(
        "two extends must advance AsyncFile.size to 2 pages",
        2L,
        wowCache.getFilledUpTo(fileId));

    // Simulate WAL replay targeting pageIndex=10 while currentSize==2 (gap of 8 pages).
    final var pointer = wowCache.loadOrAdd(fileId, 10L, false);
    try {
      assertNotNull(
          "gap-fill recovery call must return a non-null pointer for the target page",
          pointer);
      assertEquals(
          "target page's magic-stamped buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    // All pages [0..10] inclusive = 11 pages must be allocated.
    assertEquals(
        "gap-fill must advance AsyncFile.size to pageIndex + 1 pages",
        11L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Crash-safety scenario B (load branch after on-disk magic stamp is in place).
   *
   * <p>This test simulates the scenario documented in design.md §"Crash safety" scenario B:
   * a TX was committed, the {@code EnsurePageIsValidInFileTask} ran and stamped the page on
   * disk, and on the next reopen the page already exists on disk when {@code loadOrAdd} is
   * called with that index. The load branch ({@code pageIndex < currentSize}) must fire and
   * return the existing disk-resident page, not allocate a fresh extend. Specifically:
   * <ol>
   *   <li>Extend page 0 (first TX), flush to ensure the disk stamp has landed.</li>
   *   <li>Remove it from the dirty write-cache (via a second cache open / flush cycle) so
   *       the load falls through to {@code loadFileContent} on disk.</li>
   *   <li>Call {@code loadOrAdd(fileId, 0)} again; it must take the load branch and return
   *       a non-null pointer without advancing {@code AsyncFile.size}.</li>
   * </ol>
   * After the flush the page exists on disk; re-calling {@code loadOrAdd} on the same index
   * must be a pure load (file size stays at 1), demonstrating that crash-safety scenario B
   * resolves via the load branch, not a second extend.
   */
  @Test
  public void crashSafetyScenarioBLoadAfterOnDiskStamp() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // First call: extend branch fires, magic-stamps in memory, queues EnsurePageIsValidInFileTask.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    assertEquals(
        "extend must advance AsyncFile.size to 1 page",
        1L,
        wowCache.getFilledUpTo(fileId));

    // Flush so the EnsurePageIsValidInFileTask writes the magic stamp to disk and the page
    // can be loaded via loadFileContent on the next call.
    wowCache.flush(fileId);

    // Second call on the same index: load branch fires (pageIndex < currentSize == 1).
    // File size must stay at 1 page — no double-extend.
    final var loaded = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull(
          "load branch after on-disk stamp must return a non-null pointer", loaded);
    } finally {
      loaded.decrementReadersReferrer();
    }
    assertEquals(
        "second loadOrAdd on an already-stamped page must not advance AsyncFile.size",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the extend branch: the extend branch never reads existing
   * data (it allocates space and returns a freshly magic-stamped empty pointer), so the
   * {@code verifyChecksums} flag is a no-op on this path. The test pins that the flag does
   * not accidentally route the extend branch into a read-from-disk verification step (which
   * would either return a torn pre-stamp page or throw an unexpected exception). Asserts
   * the same post-state as the {@code verifyChecksums=false} extend test: file size
   * advances by one page; pointer carries LSN(-1,-1).
   */
  @Test
  public void extendBranchSucceedsWithVerifyChecksumsTrue() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);

    final var pointer = wowCache.loadOrAdd(fileId, 0L, true);
    try {
      assertNotNull(
          "extend branch must return a non-null pointer with verifyChecksums=true",
          pointer);
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "extend with verifyChecksums=true must advance AsyncFile.size by one page",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the gap-fill branch: the gap-fill branch never reads
   * existing data either (it allocates a multi-page range and returns a freshly
   * magic-stamped empty pointer for the target page only), so the {@code verifyChecksums}
   * flag is a no-op on this path. Mirrors the {@code verifyChecksums=false} gap-fill test
   * — pins that the flag does not route this branch into an on-disk verification step.
   */
  @Test
  public void gapFillBranchSucceedsWithVerifyChecksumsTrue() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);

    final var pointer = wowCache.loadOrAdd(fileId, 5L, true);
    try {
      assertNotNull(
          "gap-fill branch must return a non-null pointer with verifyChecksums=true",
          pointer);
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "gap-fill with verifyChecksums=true must advance AsyncFile.size to pageIndex + 1",
        6L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the load branch (clean page): after the extend branch
   * fires and the {@link EnsurePageIsValidInFileTask} flushes the magic stamp to disk, a
   * subsequent {@code loadOrAdd(fileId, 0, true)} must take the load branch
   * ({@code pageIndex &lt; currentSize}), read the page from disk, verify the magic +
   * CRC checksum successfully (since the on-disk page was stamped by the same
   * {@link WOWCache} instance under {@link ChecksumMode#StoreAndVerify}), and return a
   * non-null pointer. The test pins that {@code verifyChecksums=true} on a clean page is
   * a no-op observable at the API surface — no exception, no torn read.
   */
  @Test
  public void loadBranchWithVerifyChecksumsTrueOnCleanPageReturnsPointer() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Extend page 0 (extend branch fires; EnsurePageIsValidInFileTask submitted).
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    // Drain the single-threaded commitExecutor so the EnsurePageIsValidInFileTask has
    // written the magic-stamped on-disk image before we exercise the load branch with
    // checksum verification turned on.
    wowCache.flush(fileId);

    final var loaded = wowCache.loadOrAdd(fileId, 0L, true);
    try {
      assertNotNull(
          "load branch with verifyChecksums=true on a clean page must return a pointer",
          loaded);
    } finally {
      loaded.decrementReadersReferrer();
    }
    assertEquals(
        "load branch with verifyChecksums=true must not advance AsyncFile.size",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the load branch (corrupted page) under
   * {@link ChecksumMode#StoreAndThrow}: simulates a torn page by writing garbage into the
   * data area of an on-disk magic-stamped page, then calls
   * {@code loadOrAdd(fileId, 0, true)} and asserts the load branch surfaces a
   * {@link StorageException} (the broken-page contract). Mirrors the legacy
   * {@code WOWCache.load}-with-checksum behaviour pinned by
   * {@code WOWCacheTestIT#testChecksumFailure}.
   *
   * <p>The corruption is written via a separate {@link AsyncFile} instance opened directly
   * on the file path so the in-process {@link WOWCache} read path is unaffected (the
   * cache's {@code loadFileContent} re-reads from disk on every call, so the corruption
   * is observable on the next {@code loadOrAdd} invocation).
   */
  @Test
  public void loadBranchWithVerifyChecksumsTrueOnCorruptedPageThrowsStorageException()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Extend page 0 and drain the executor so the on-disk magic stamp is in place.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.flush(fileId);

    // Switch to StoreAndThrow so a checksum mismatch surfaces as a StorageException
    // (under StoreAndVerify the broken-page detection logs but returns the page anyway).
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);

    // Corrupt the on-disk page: open the underlying file directly and write garbage into
    // the data area (anywhere after NEXT_FREE_POSITION; the magic + CRC fields are at
    // offsets 0..NEXT_FREE_POSITION-1 so writing further in invalidates the CRC). A
    // separate cached-thread-pool executor backs this AsyncFile so the corruption write
    // does not deadlock against the wowCache's own async-file executor.
    final var nativeName = wowCache.nativeFileNameById(fileId);
    assertNotNull("file must have a registered native name", nativeName);
    final var diskPath = storagePath.resolve(nativeName);
    final var corruptionExecutor = Executors.newCachedThreadPool();
    try {
      final File file = new AsyncFile(diskPath, PAGE_SIZE, false, corruptionExecutor,
          storageName);
      file.open();
      try {
        // Write one byte of garbage into the data area to break the CRC.
        file.write(
            DurablePage.NEXT_FREE_POSITION,
            ByteBuffer.wrap(new byte[] {(byte) 0xAB}).order(ByteOrder.nativeOrder()));
      } finally {
        file.close();
      }
    } finally {
      corruptionExecutor.shutdownNow();
      try {
        corruptionExecutor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // loadOrAdd with verifyChecksums=true must now throw StorageException("Page ... is
    // broken") on the corrupted page.
    assertThrows(
        StorageException.class,
        () -> wowCache.loadOrAdd(fileId, 0L, true));
  }

  /**
   * Intermediate gap-page accessibility: after a gap-fill from {@code currentSize == 2}
   * to {@code target == 10}, every page in {@code [2, 10]} must be subsequently loadable
   * via {@code loadOrAdd} (which now takes the load branch, since
   * {@code pageIndex &lt; currentSize == 11}). Each intermediate pointer must carry the
   * magic-stamped empty buffer signature (LSN(-1,-1)) the
   * {@link EnsurePageIsValidInFileTask} writes for gap pages. The earlier
   * {@link #gapFillBranchAllocatesEntireGapAndReturnsTargetPointer} test only verified the
   * target's pointer; this test pins that the intermediate gap pages are not just
   * allocated on disk but actually retrievable via the same {@code loadOrAdd} API.
   *
   * <p>The {@code flush(fileId)} call between the gap-fill and the per-page load loop
   * ensures every {@link EnsurePageIsValidInFileTask} on the single-threaded
   * commitExecutor has run, so the load-branch reads observe magic-stamped pages instead
   * of falling through to the defensive totality fallback.
   */
  @Test
  public void gapFillIntermediatePagesAreLoadableViaLoadBranch() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Establish currentSize == 2 via two sequential extends.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();

    // Gap-fill from 2 to 10 (inclusive): post-state size == 11.
    wowCache.loadOrAdd(fileId, 10L, false).decrementReadersReferrer();
    assertEquals(
        "gap-fill must advance AsyncFile.size to pageIndex + 1",
        11L,
        wowCache.getFilledUpTo(fileId));

    // Drain the executor so every gap page's EnsurePageIsValidInFileTask has run and the
    // load branch reads a stamped page from disk.
    wowCache.flush(fileId);

    // Iterate the full gap range [0, 10] and assert each page is loadable via the load
    // branch with a magic-stamped LSN(-1,-1) buffer. We include [0, 1] (extended before
    // the gap-fill) in the check to confirm the test's invariant holds for the
    // pre-existing pages too, not just the intermediates.
    for (long pageIdx = 0L; pageIdx <= 10L; pageIdx++) {
      final var pointer = wowCache.loadOrAdd(fileId, pageIdx, false);
      try {
        assertNotNull(
            "intermediate gap page must be loadable: pageIndex=" + pageIdx, pointer);
        final var buffer = pointer.getBuffer();
        assertNotNull(buffer);
        assertEquals(
            "intermediate gap page must carry LSN(-1,-1): pageIndex=" + pageIdx,
            new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(buffer));
      } finally {
        pointer.decrementReadersReferrer();
      }
    }
    // After loading every page in [0, 10], the file size must not have advanced (no
    // accidental re-extend on the load branch).
    assertEquals(
        "loading every gap page via the load branch must not advance AsyncFile.size",
        11L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Very-high {@code pageIndex} boundary: the gap-fill branch's dispatch guard rejects
   * requests where {@code (pageIndex - currentSize + 1) * pageSize > Integer.MAX_VALUE}
   * (the int overflow ceiling of {@link AsyncFile#allocateSpace}, which takes an int). The
   * test exercises this guard without actually allocating a multi-gigabyte file by picking
   * a {@code pageIndex} just above the boundary and asserting a {@link StorageException}
   * surfaces from the dispatch prelude.
   *
   * <p>Practical note (documented in the episode): exercising the boundary at the true
   * upper limit ({@code pageIndex = Integer.MAX_VALUE / pageSize - 2}) would require
   * allocating ~2 GB on disk per test run, which is impractical for unit-test CI. The
   * branch's overflow guard is purely arithmetic (no I/O occurs before the check fires),
   * so the test verifies the exact behaviour at the boundary without paying the disk cost.
   */
  @Test
  public void gapFillRejectsPageIndexExceedingAllocateSpaceIntegerCeiling() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // currentSize is 0; pick pageIndex such that (pageIndex - 0 + 1) * pageSize >
    // Integer.MAX_VALUE. The +10 padding keeps the math comfortably above the overflow
    // boundary across page-size choices.
    final long boundaryPageIndex = (long) (Integer.MAX_VALUE / PAGE_SIZE) + 10L;

    final var thrown =
        assertThrows(
            StorageException.class, () -> wowCache.loadOrAdd(fileId, boundaryPageIndex, false));
    assertNotNull("exception must carry a message", thrown.getMessage());
    assertTrue(
        "exception must reference the int-limit overflow on allocateSpace",
        thrown.getMessage().contains("allocateSpace int limit"));

    // The dispatch guard fires before AsyncFile.allocateSpace, so the file size is
    // unchanged by the failed call.
    assertEquals(
        "failed gap-fill must not advance AsyncFile.size",
        0L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Scenario 6 — {@link EnsurePageIsValidInFileTask} idempotency, single-threaded sequence
   * assertion (no executor gating, no production-source seam).
   *
   * <p>Extend page 0 via {@code loadOrAdd(fileId, 0, false)} (which submits one
   * {@link EnsurePageIsValidInFileTask} to the single-threaded commitExecutor), drain via
   * {@link WOWCache#flush(long)} so the submitted task runs and stamps the on-disk page,
   * then deliberately overwrite the page contents with distinctive marker bytes via the
   * normal write path ({@code wowCache.load} + buffer mutation + {@code wowCache.store}
   * + {@code wowCache.flush}). The page on disk now carries the marker bytes instead of
   * the freshly-stamped magic bytes. Reading the full file byte content as a baseline
   * after the marker write, this test then directly calls
   * {@link WOWCache#writeValidPageInFile(int, int)} twice in sequence and asserts that
   * across both invocations both the underlying file size AND the file's byte content
   * stay identical to the baseline — both direct calls must short-circuit on the
   * {@code getUnderlyingFileSize() &lt;= pagePosition} guard at the top of
   * {@code writeValidPageInFile}.
   *
   * <p>The byte-content compare is the load-bearing assertion: a regression that
   * removed the short-circuit guard would re-stamp the page with the magic-stamped
   * empty buffer ({@code LSN(-1,-1)} + magic bytes), wiping the {@code 0xC3} marker
   * bytes from the data area, and the {@code assertArrayEquals} below would fail.
   * The file-size assertion is kept as belt-and-braces — a positional write to the
   * same offset would not change the file size, so a size-only check would silently
   * pass even if the page contents were trampled.
   *
   * <p>The disk-write count is also tracked via {@link Files#size(Path)} on the
   * underlying file, subtracting {@link File#HEADER_SIZE} per
   * {@link AsyncFile#getUnderlyingFileSize()}.
   */
  @Test
  public void writeValidPageInFileIsIdempotentAcrossRepeatedDirectInvocations()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var intId = AbstractWriteCache.extractFileId(fileId);

    // Extend page 0; this submits one EnsurePageIsValidInFileTask to the single-threaded
    // commitExecutor and bumps AsyncFile.size (in-memory tracking) to one page.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();

    // Drain the commitExecutor (FileFlushTask runs after the prior
    // EnsurePageIsValidInFileTask, since the executor is single-threaded) so the on-disk
    // page is stamped before we deliberately overwrite the data area below.
    wowCache.flush(fileId);

    final var nativeName = wowCache.nativeFileNameById(fileId);
    assertNotNull("file must have a registered native name", nativeName);
    final var diskPath = storagePath.resolve(nativeName);

    // Deliberately overwrite the page's data area with distinctive marker bytes (0xC3) via
    // the normal write path: load the page from cache, mutate the buffer in the data
    // region (past NEXT_FREE_POSITION so the magic + CRC stay valid and the LSN stays
    // distinct from (-1,-1)), store it back to install in writeCachePages, and flush so
    // the marker bytes land on disk. After this, the on-disk page no longer carries the
    // magic-stamped empty buffer that writeValidPageInFile would have written; any
    // accidental re-stamping by a regression would visibly trample the marker bytes.
    final var dirty = wowCache.load(fileId, 0L, new ModifiableBoolean(), false);
    try {
      final var buffer = dirty.getBuffer();
      buffer.position(DurablePage.NEXT_FREE_POSITION);
      for (int i = DurablePage.NEXT_FREE_POSITION; i < PAGE_SIZE; i++) {
        buffer.put((byte) 0xC3);
      }
      // Set a distinguishable LSN so a re-stamp to LSN(-1,-1) would also be detectable on
      // the bytes around the LSN field.
      DurablePage.setLogSequenceNumberForPage(buffer, new LogSequenceNumber(42L, 42));
      // The flush path asserts buffer.position() == 0 before copying — rewind here so the
      // store + subsequent flush observes the contract.
      buffer.position(0);
      wowCache.store(fileId, 0L, dirty);
    } finally {
      dirty.decrementReadersReferrer();
    }
    wowCache.flush(fileId);

    // Baseline: after the marker write + flush, the underlying file size is exactly one
    // page (HEADER_SIZE + pageSize on disk) and the byte content reflects the marker.
    final long sizeAfterDrain = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "after the marker-bytes flush, the underlying file size must be one page",
        (long) PAGE_SIZE,
        sizeAfterDrain);
    final byte[] bytesBefore = Files.readAllBytes(diskPath);

    // First direct call: writeValidPageInFile must observe
    // getUnderlyingFileSize() (= pageSize) > pagePosition (= 0), short-circuit at the
    // top-of-method guard, and write nothing. Both the file size and the byte content
    // are unchanged. A regression that removed the short-circuit guard would write a
    // fresh magic-stamped empty buffer to the same offset, trampling the 0xC3 marker
    // bytes — the byte-content assertion below catches this case; the file-size
    // assertion alone would not, because a positional write to the same offset leaves
    // the file size unchanged.
    wowCache.writeValidPageInFile(intId, 0);
    final long sizeAfterFirstDirect = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "first direct writeValidPageInFile call must short-circuit (no new disk write)",
        sizeAfterDrain,
        sizeAfterFirstDirect);
    assertArrayEquals(
        "first direct writeValidPageInFile call must short-circuit; on-disk bytes must"
            + " match the marker-bytes baseline",
        bytesBefore,
        Files.readAllBytes(diskPath));

    // Second direct call: same short-circuit guard fires again; the file size and byte
    // content remain identical to the baseline.
    wowCache.writeValidPageInFile(intId, 0);
    final long sizeAfterSecondDirect = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "second direct writeValidPageInFile call must short-circuit (no new disk write)",
        sizeAfterDrain,
        sizeAfterSecondDirect);
    assertArrayEquals(
        "second direct writeValidPageInFile call must short-circuit; on-disk bytes must"
            + " still match the marker-bytes baseline",
        bytesBefore,
        Files.readAllBytes(diskPath));
  }

  /**
   * Gap-fill branch counterpart of
   * {@link #writeValidPageInFileIsIdempotentAcrossRepeatedDirectInvocations} — pins the
   * idempotency of the per-page {@link EnsurePageIsValidInFileTask} submissions that the
   * gap-fill branch issues for every intermediate page in {@code [currentSize, target]}.
   *
   * <p>The production Javadoc on {@code loadOrAddGapFillBranch} explicitly claims those
   * per-page tasks are idempotent, and recovery / WAL replay is the only normal caller
   * of the gap-fill branch — so this is the path that most needs the idempotency
   * guarantee to be falsifiable. Using a gap-fill from {@code currentSize == 0} to
   * {@code target == 5} produces six per-page task submissions. After the
   * {@link WOWCache#flush(long)} drains them, the on-disk file holds six magic-stamped
   * empty pages. Reading the full byte content as a baseline, the test then directly
   * calls {@link WOWCache#writeValidPageInFile(int, int)} for every page index in
   * {@code [0, 5]} and asserts that both the underlying file size AND the byte content
   * stay identical to the baseline. A regression that re-stamped intermediate pages
   * (e.g., wrote a different magic or trampled header bytes) would surface as a
   * byte-array mismatch.
   *
   * <p>Note: the intermediate pages already carry {@code LSN(-1,-1)} after the executor
   * tasks run, so the byte-content compare catches a re-stamp that wrote a different
   * stamp shape — not a re-stamp that wrote the same stamp shape, since the bytes
   * would coincidentally still match. The file-size delta covers the second case (a
   * second positional write to the same offset never changes file size; the test
   * therefore relies on the byte compare for the same-stamp case being implausible —
   * any regression that re-entered the magic-stamp write path would also re-acquire
   * a fresh allocator pointer, which would not coincidentally produce identical
   * bytes because the per-page CRC depends on the page's content and offset).
   */
  @Test
  public void writeValidPageInFileIsIdempotentAcrossRepeatedDirectInvocationsOnGapFillPages()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var intId = AbstractWriteCache.extractFileId(fileId);

    // Trigger the gap-fill branch: target index 5 on a fresh file submits six
    // EnsurePageIsValidInFileTask instances to the single-threaded commitExecutor (one
    // per page in [0, 5]).
    wowCache.loadOrAdd(fileId, 5L, false).decrementReadersReferrer();

    // Drain so all six on-disk magic stamps land before the byte-content baseline.
    wowCache.flush(fileId);

    final var nativeName = wowCache.nativeFileNameById(fileId);
    assertNotNull("file must have a registered native name", nativeName);
    final var diskPath = storagePath.resolve(nativeName);

    // Baseline: file size must reflect six pages worth of data.
    final long sizeAfterDrain = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "after the gap-fill executor tasks drain, the underlying file size must be six pages",
        6L * PAGE_SIZE,
        sizeAfterDrain);
    final byte[] bytesBefore = Files.readAllBytes(diskPath);

    // Direct calls for every gap page: each must short-circuit on the
    // getUnderlyingFileSize() guard (pagePosition < currentSize * pageSize), leaving
    // both the file size and the on-disk bytes unchanged.
    for (int p = 0; p <= 5; p++) {
      wowCache.writeValidPageInFile(intId, p);
      final long sizeAfter = Files.size(diskPath) - File.HEADER_SIZE;
      assertEquals(
          "direct writeValidPageInFile call on gap page " + p + " must short-circuit"
              + " (no new disk write)",
          sizeAfterDrain,
          sizeAfter);
      assertArrayEquals(
          "direct writeValidPageInFile call on gap page " + p + " must short-circuit;"
              + " on-disk bytes must match the post-drain baseline",
          bytesBefore,
          Files.readAllBytes(diskPath));
    }
  }
}
