package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Verifies that the placeholder {@link WOWCache#loadOrAdd} stub throws
 * {@link UnsupportedOperationException} until the disk-engine three-branch implementation
 * (load existing / one-page extend / multi-page gap-fill) lands in the next step of the
 * read-cache concurrency fix.
 *
 * <p>This guard is intentional: the new {@code WriteCache.loadOrAdd} method is declared
 * abstract on the interface so any divergent mock or implementation is a compile-time
 * error. The real behaviour is implemented in a follow-up step; until then production
 * code keeps routing through the legacy {@code load} / {@code allocateNewPage} paths and
 * the stub must never be reached at runtime.
 */
public class WOWCacheLoadOrAddStubTest {

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;

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
    storageName = "WOWCacheLoadOrAddStubTest";
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
   * Calling {@code loadOrAdd} on the disk engine must throw
   * {@link UnsupportedOperationException} with the documented "not yet wired" message,
   * confirming the stub is in place and unreachable from production paths in this commit.
   */
  @Test
  public void loadOrAddStubThrowsUnsupportedOperationException() {
    var thrown =
        assertThrows(
            UnsupportedOperationException.class,
            () -> wowCache.loadOrAdd(/* fileId= */ 0L, /* pageIndex= */ 0L,
                /* verifyChecksums= */ false));

    assertTrue(
        "stub message must signal the placeholder is intentional",
        thrown.getMessage() != null && thrown.getMessage().contains("loadOrAdd"));
  }
}
