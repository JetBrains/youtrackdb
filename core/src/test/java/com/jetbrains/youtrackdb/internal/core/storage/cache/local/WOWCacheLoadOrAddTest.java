package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.Executors;
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
            Executors.newCachedThreadPool());
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
   * {@code AsyncFile.size} to one page, and return a non-null {@link com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer}
   * with a clean buffer at position 0.
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
   * Load branch: after extending pages 0 and 1 and flushing them to disk, calling
   * {@code loadOrAdd(fileId, 0, false)} must take the load path (pageIndex less than
   * currentSize) and return a non-null {@link com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer}
   * for an existing on-disk page. The returned pointer must be the dirty-write-cache
   * pointer (priority over the on-disk image) when the page is still in
   * {@code writeCachePages}.
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
    // (different-instance) CachePointer reading from disk.
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
    } finally {
      pointer.decrementReadersReferrer();
    }
    // After gap-fill, AsyncFile.size advanced from 0 to 6 pages
    // ([0..5] inclusive = 6 pages).
    assertTrue(
        "gap-fill must advance AsyncFile.size to at least pageIndex + 1 pages",
        wowCache.getFilledUpTo(fileId) >= 6L);
  }
}
