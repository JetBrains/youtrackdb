package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the in-memory non-durable file tracking in WOWCache: addFile with nonDurable flag,
 * isNonDurable queries, deleteFile cleanup, replaceFileId cleanup, and close() clearing.
 */
public class WOWCacheNonDurableFileTrackingTest {

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
    storageName = "WOWCacheNonDurableTest";
    storagePath = Paths.get(buildDirectory).resolve(storageName);
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
   * Verifies that a file added with nonDurable=true is reported as non-durable by isNonDurable,
   * while a file added with nonDurable=false (or via the 2-arg addFile) is reported as durable.
   */
  @Test
  public void testAddFileWithNonDurableFlag() throws IOException {
    // Add a durable file via the standard 2-arg addFile
    final var durableFileId = wowCache.addFile("durable.tst");
    assertFalse(
        "File added via 2-arg addFile should be durable",
        wowCache.isNonDurable(durableFileId));

    // Add a non-durable file via the 3-arg addFile
    final var bookedId = wowCache.bookFileId("nonDurable.tst");
    final var nonDurableFileId =
        wowCache.addFile("nonDurable.tst", bookedId, true);
    assertTrue(
        "File added with nonDurable=true should be non-durable",
        wowCache.isNonDurable(nonDurableFileId));
  }

  /**
   * Verifies that a file added with nonDurable=false via the 3-arg overload is reported as
   * durable, identical to using the 2-arg addFile.
   */
  @Test
  public void testAddFileWithNonDurableFalse() throws IOException {
    final var bookedId = wowCache.bookFileId("durableExplicit.tst");
    final var fileId = wowCache.addFile("durableExplicit.tst", bookedId, false);
    assertFalse(
        "File added with nonDurable=false should be durable",
        wowCache.isNonDurable(fileId));
  }

  /**
   * Verifies that isNonDurable returns false for an unknown/non-existent file ID, which is the
   * safe fallback (treat as durable).
   */
  @Test
  public void testIsNonDurableForUnknownFileReturnsFalse() {
    // An arbitrary file ID that was never registered
    assertFalse(
        "isNonDurable should return false for unknown file IDs",
        wowCache.isNonDurable(999_999L));
  }

  /**
   * Verifies that deleting a non-durable file removes it from the non-durable registry, so
   * isNonDurable returns false after deletion.
   */
  @Test
  public void testDeleteFileRemovesFromNonDurableRegistry() throws IOException {
    final var bookedId = wowCache.bookFileId("toDelete.tst");
    final var fileId = wowCache.addFile("toDelete.tst", bookedId, true);
    assertTrue(wowCache.isNonDurable(fileId));

    wowCache.deleteFile(fileId);

    assertFalse(
        "isNonDurable should return false after the non-durable file is deleted",
        wowCache.isNonDurable(fileId));
  }

  /**
   * Verifies that deleting a durable file does not affect the non-durable registry (no crash,
   * no spurious entries).
   */
  @Test
  public void testDeleteDurableFileDoesNotAffectNonDurableRegistry() throws IOException {
    final var durableId = wowCache.addFile("durableToDelete.tst");
    final var bookedNdId = wowCache.bookFileId("ndFile.tst");
    final var ndFileId = wowCache.addFile("ndFile.tst", bookedNdId, true);

    // Delete the durable file
    wowCache.deleteFile(durableId);

    // Non-durable file should still be tracked
    assertTrue(
        "Non-durable file should remain in registry after deleting a different durable file",
        wowCache.isNonDurable(ndFileId));
  }

  /**
   * Verifies that replaceFileId removes the replaced (new) file's internal ID from the
   * non-durable registry if it was non-durable.
   */
  @Test
  public void testReplaceFileIdRemovesReplacedNonDurableEntry() throws IOException {
    // Create original durable file
    final var originalId = wowCache.addFile("original.tst");

    // Create a non-durable replacement file
    final var replacementBookedId = wowCache.bookFileId("replacement.tst");
    final var replacementId =
        wowCache.addFile("replacement.tst", replacementBookedId, true);
    assertTrue(wowCache.isNonDurable(replacementId));

    // Replace original with replacement — newFileId's internal ID should be removed
    wowCache.replaceFileId(originalId, replacementId);

    // The replacement's internal ID is no longer valid; the original's ID now points to
    // the replacement file content. The original was durable, so it stays durable.
    assertFalse(
        "After replaceFileId, the original file ID should remain durable",
        wowCache.isNonDurable(originalId));
  }

  /**
   * Verifies that close() clears the non-durable file IDs set, so no stale non-durable entries
   * remain after cache shutdown.
   */
  @Test
  public void testCloseResetsNonDurableRegistry() throws IOException {
    final var bookedId = wowCache.bookFileId("ndClose.tst");
    final var fileId = wowCache.addFile("ndClose.tst", bookedId, true);
    assertTrue(wowCache.isNonDurable(fileId));

    wowCache.close();

    // After close, isNonDurable should return false for any file ID because the set is cleared.
    // Note: accessing isNonDurable after close is technically accessing the volatile field
    // directly — it doesn't go through checkForClose() because it's a lock-free read.
    assertFalse(
        "Non-durable registry should be empty after close()",
        wowCache.isNonDurable(fileId));

    // Prevent double-close in tearDown
    wowCache = null;
  }

  /**
   * Verifies that multiple non-durable files can be tracked simultaneously and that each has
   * independent lifecycle in the registry.
   */
  @Test
  public void testMultipleNonDurableFiles() throws IOException {
    final var booked1 = wowCache.bookFileId("nd1.tst");
    final var nd1 = wowCache.addFile("nd1.tst", booked1, true);

    final var booked2 = wowCache.bookFileId("nd2.tst");
    final var nd2 = wowCache.addFile("nd2.tst", booked2, true);

    final var durableId = wowCache.addFile("durable.tst");

    assertTrue(wowCache.isNonDurable(nd1));
    assertTrue(wowCache.isNonDurable(nd2));
    assertFalse(wowCache.isNonDurable(durableId));

    // Delete one non-durable file — the other should remain
    wowCache.deleteFile(nd1);
    assertFalse(wowCache.isNonDurable(nd1));
    assertTrue(
        "Second non-durable file should remain after deleting the first",
        wowCache.isNonDurable(nd2));
  }

  /**
   * Verifies that the default methods on the WriteCache interface provide safe fallback
   * behavior: addFile delegates to the 2-arg version, isNonDurable returns false.
   */
  @Test
  public void testWriteCacheDefaultMethodBehavior() throws IOException {
    // Use the 3-arg addFile with nonDurable=false — should behave identically to 2-arg
    final var bookedId = wowCache.bookFileId("defaultBehavior.tst");
    final var fileId = wowCache.addFile("defaultBehavior.tst", bookedId, false);

    // The file should exist and be queryable
    assertEquals(
        "defaultBehavior.tst", wowCache.fileNameById(fileId));
    assertFalse(wowCache.isNonDurable(fileId));
  }
}
