package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
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
 * Contract pin for {@link WOWCache#physicalSizeForBackupSnapshot(long)} on the disk
 * engine. The helper is a named alias for {@link WOWCache#getFilledUpTo(long)} that
 * external (cross-component) callers must funnel through; its body is a thin delegator,
 * so the contract under test is "returns the same value as {@code getFilledUpTo} for the
 * same {@code fileId} in every observable state". A regression that diverged the two
 * (e.g. forgot to drop the {@code filesLock} acquisition, or accidentally returned a
 * cached value) would surface here as an assertion failure.
 *
 * <p>The test scaffolding mirrors {@link WOWCacheLoadOrAddTest} so that the cache fixture
 * matches the rest of the suite (same {@code CASDiskWriteAheadLog} config, same page
 * size, same checksum mode). The helper itself does not depend on checksum mode, but
 * keeping the scaffolding aligned avoids accidental divergence in cleanup ordering.
 */
public class WOWCachePhysicalSizeForBackupSnapshotTest {

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;
  private static final String FILE_NAME = "wowCachePhysicalSizeForBackupSnapshot.tst";

  private static Path storagePath;
  private static String storageName;
  private static final ByteBufferPool bufferPool = new ByteBufferPool(PAGE_SIZE);

  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private ClosableLinkedContainer<Long, File> files;
  // Held so the AsyncFile worker thread can be shut down in tearDown — leaking it would
  // pin non-daemon threads across the suite.
  private ExecutorService asyncFileExecutor;

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var buildDirectory = System.getProperty("buildDirectory", ".");
    storageName = "WOWCachePhysicalSizeForBackupSnapshotTest";
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
   * Fresh, never-extended file: both {@code getFilledUpTo} and
   * {@code physicalSizeForBackupSnapshot} must report zero pages. A divergence here would
   * indicate the helper short-circuited around the wrapped delegator (for example,
   * returned a cached pre-open value).
   */
  @Test
  public void freshFileBothSurfacesReportZero() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);

    final var viaLegacy = wowCache.getFilledUpTo(fileId);
    final var viaHelper = wowCache.physicalSizeForBackupSnapshot(fileId);

    assertEquals("fresh file must report 0 pages via getFilledUpTo", 0L, viaLegacy);
    assertEquals(
        "physicalSizeForBackupSnapshot must agree with getFilledUpTo on a fresh file",
        viaLegacy,
        viaHelper);
  }

  /**
   * After one extend: both surfaces must report exactly one page. Extending the file via
   * {@code loadOrAdd(fileId, 0)} is the same path a normal allocator uses; the helper
   * must observe the new size without needing an additional refresh / barrier.
   */
  @Test
  public void afterOneExtendBothSurfacesReportOnePage() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();

    final var viaLegacy = wowCache.getFilledUpTo(fileId);
    final var viaHelper = wowCache.physicalSizeForBackupSnapshot(fileId);

    assertEquals("single extend must advance getFilledUpTo to 1", 1L, viaLegacy);
    assertEquals(
        "physicalSizeForBackupSnapshot must observe the same single-page extend",
        viaLegacy,
        viaHelper);
  }

  /**
   * After multiple extends: pinning the wider-range case so a regression that silently
   * truncated the helper's return at some boundary (e.g. an accidental {@code int} cast
   * around the {@code file.getFileSize() / pageSize} math) would surface here rather
   * than only at small sizes. Five extends is small enough to stay fast yet outside the
   * {@code 0} / {@code 1} fixed-shape corners.
   */
  @Test
  public void afterMultipleExtendsBothSurfacesAgree() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    for (int i = 0; i < 5; i++) {
      wowCache.loadOrAdd(fileId, i, false).decrementReadersReferrer();
    }

    final var viaLegacy = wowCache.getFilledUpTo(fileId);
    final var viaHelper = wowCache.physicalSizeForBackupSnapshot(fileId);

    assertEquals("five extends must advance getFilledUpTo to 5 pages", 5L, viaLegacy);
    assertEquals(
        "physicalSizeForBackupSnapshot must agree across multiple extends",
        viaLegacy,
        viaHelper);
  }

  /**
   * Deleted-file safety: after deleting the file, both surfaces must return {@code 0}
   * (the documented null-file behaviour of {@code WOWCache.getFilledUpTo}, which the
   * helper inherits because its body re-enters the wrapped call under {@code filesLock}).
   * Pins that the helper does not throw an NPE or surface a stale size on the
   * concurrent-delete code path that the wrapped method explicitly guards against.
   */
  @Test
  public void deletedFileBothSurfacesReportZero() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.deleteFile(fileId);

    final var viaLegacy = wowCache.getFilledUpTo(fileId);
    final var viaHelper = wowCache.physicalSizeForBackupSnapshot(fileId);

    assertEquals(
        "deleted file must report 0 pages via getFilledUpTo (null-file safety)",
        0L,
        viaLegacy);
    assertEquals(
        "physicalSizeForBackupSnapshot must inherit the deleted-file safety: returns 0,"
            + " not an NPE or stale size",
        viaLegacy,
        viaHelper);
  }
}
