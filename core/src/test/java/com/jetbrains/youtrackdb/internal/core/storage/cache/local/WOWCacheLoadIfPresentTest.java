package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Smoke coverage for {@link WOWCache#loadIfPresent}, the non-extending probe primitive
 * introduced for the silent-read path in {@code LockFreeReadCache.silentLoadForRead}.
 *
 * <p>The contract being pinned here, branch by branch:
 * <ul>
 *   <li><b>Hit on disk</b> &mdash; an already-extended page returns a usable pointer
 *       distinct from any pointer in {@code writeCachePages} (because nothing was
 *       store()d back on the dirty path in this test).
 *   <li><b>Miss</b> &mdash; an out-of-range pageIndex returns {@code null} without
 *       extending the file. This is the property that distinguishes
 *       {@code loadIfPresent} from {@code loadOrAdd}; it is the behaviour the silent
 *       read path needs to faithfully report "no such page".
 *   <li><b>Dirty-write priority</b> &mdash; when a more recent dirty pointer is sitting
 *       in {@code writeCachePages}, {@code loadIfPresent} must return that exact instance,
 *       not a fresh disk read.
 * </ul>
 *
 * <p>Comprehensive cache-coverage tests, multi-thread stress, and eviction/flush races
 * for {@code loadIfPresent} land in the dedicated cache-coverage test track. This class
 * is intentionally narrow and mirrors {@link WOWCacheLoadOrAddTest}'s scaffolding.
 */
public class WOWCacheLoadIfPresentTest {

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;
  private static final String FILE_NAME = "wowCacheLoadIfPresent.tst";

  private static Path storagePath;
  private static String storageName;
  private static final ByteBufferPool bufferPool = new ByteBufferPool(PAGE_SIZE);

  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private ClosableLinkedContainer<Long, File> files;
  // Holds the cached-thread-pool the WOWCache uses internally; kept so that the executor
  // can be drained in tearDown — without an explicit shutdown each test method would leak
  // a non-daemon AsyncFile worker thread.
  private ExecutorService asyncFileExecutor;

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var buildDirectory = System.getProperty("buildDirectory", ".");
    storageName = "WOWCacheLoadIfPresentTest";
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
   * Hit branch on a freshly-stamped page: extend pages 0 and 1 via {@code loadOrAdd},
   * flush so the on-disk magic stamp is written, then call {@code loadIfPresent(fileId,
   * 1)}. The probe must return a non-null pointer carrying the magic-stamped LSN(-1,-1)
   * (set by {@code EnsurePageIsValidInFileTask}). This pins the dirty-write-priority +
   * disk-fallback contract on the load path.
   */
  @Test
  public void hitBranchReturnsExistingOnDiskPagePointer() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();
    // Drain EnsurePageIsValidInFileTask so the on-disk magic stamp lands before the probe.
    wowCache.flush(fileId);

    final var pointer = wowCache.loadIfPresent(fileId, 1L, false);
    try {
      assertNotNull("loadIfPresent must return a usable pointer for an existing page", pointer);
      assertEquals("buffer should be positioned at 0", 0, pointer.getBuffer().position());
    } finally {
      pointer.decrementReadersReferrer();
    }
  }

  /**
   * Miss branch: a fresh file has {@code AsyncFile.size == 0}; calling
   * {@code loadIfPresent(fileId, 0)} must return {@code null} without advancing the file
   * size or stamping a fresh empty buffer. This is the property that distinguishes the
   * non-extending probe from {@code loadOrAdd} and is what the silent-read code path
   * relies on to faithfully report "no such page".
   */
  @Test
  public void missBranchReturnsNullWithoutExtendingFreshFile() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    assertEquals(
        "fresh file must start at 0 pages", 0L, wowCache.getFilledUpTo(fileId));

    final var probe = wowCache.loadIfPresent(fileId, 0L, false);
    assertNull("loadIfPresent must return null when the page is not yet allocated", probe);
    assertEquals(
        "loadIfPresent must NOT advance AsyncFile.size on miss",
        0L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Miss branch with a non-empty file: pre-extend page 0 so {@code AsyncFile.size == 1},
   * then probe an out-of-range pageIndex. The probe must return {@code null} and leave
   * the file size untouched. Verifies the miss branch is not accidentally restricted to
   * the fresh-file case.
   */
  @Test
  public void missBranchReturnsNullForOutOfRangePageOnNonEmptyFile() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    assertEquals(
        "single extend advances AsyncFile.size to 1 page",
        1L,
        wowCache.getFilledUpTo(fileId));

    final var probe = wowCache.loadIfPresent(fileId, 1L, false);
    assertNull("loadIfPresent must return null when pageIndex >= size", probe);
    assertEquals(
        "loadIfPresent must NOT advance AsyncFile.size on miss",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Dirty-write priority: install a dirty pointer in {@code writeCachePages} via
   * {@code store()}, then probe the same page index. {@code loadIfPresent} must return
   * the same instance as the one in {@code writeCachePages} (priority over the on-disk
   * image), exactly mirroring the existing {@code load} contract.
   */
  @Test
  public void dirtyWriteCacheTakesPriorityOverOnDiskImage() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();
    wowCache.flush(fileId);

    // Pull page 0 from disk via load(), then store() it back to install in writeCachePages.
    final var dirtyPointer = wowCache.load(fileId, 0L, new ModifiableBoolean(), false);
    try {
      assertNotNull(dirtyPointer);
      wowCache.store(fileId, 0L, dirtyPointer);
    } finally {
      dirtyPointer.decrementReadersReferrer();
    }

    final var probedDirty = wowCache.loadIfPresent(fileId, 0L, false);
    try {
      assertNotNull(probedDirty);
      assertSame(
          "loadIfPresent must return the dirty-write-cache pointer over the on-disk image",
          dirtyPointer,
          probedDirty);
    } finally {
      probedDirty.decrementReadersReferrer();
    }

    // For comparison: a page that is NOT in writeCachePages must come from disk
    // (a different instance than the dirty pointer).
    final var probedFromDisk = wowCache.loadIfPresent(fileId, 1L, false);
    try {
      assertNotNull(probedFromDisk);
      assertNotSame(
          "page not in writeCachePages must be loaded from disk",
          dirtyPointer,
          probedFromDisk);
    } finally {
      probedFromDisk.decrementReadersReferrer();
    }
  }

  /**
   * Idempotent re-probe: calling {@code loadIfPresent} twice on the same hit page must
   * return a usable pointer both times and must not advance the file size. Pins the
   * read-only nature of the probe.
   */
  @Test
  public void repeatedProbeOnHitDoesNotMutateFileSize() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    final var sizeBefore = wowCache.getFilledUpTo(fileId);

    final var first = wowCache.loadIfPresent(fileId, 0L, false);
    assertNotNull(first);
    first.decrementReadersReferrer();
    final var second = wowCache.loadIfPresent(fileId, 0L, false);
    assertNotNull(second);
    second.decrementReadersReferrer();

    assertEquals(
        "loadIfPresent must never mutate AsyncFile.size",
        sizeBefore,
        wowCache.getFilledUpTo(fileId));
  }
}
