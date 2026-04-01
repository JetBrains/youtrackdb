package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.AfterClass;
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
   * non-durable registry if it was non-durable. The surviving original ID retains its
   * (durable) status.
   */
  @Test
  public void testReplaceFileIdRemovesReplacedNonDurableEntry() throws IOException {
    // Create original durable file
    final var originalId = wowCache.addFile("original.tst");

    // Create a non-durable replacement file
    final var replacementBookedId = wowCache.bookFileId("replacement.tst");
    final var replacementId =
        wowCache.addFile("replacement.tst", replacementBookedId, true);
    assertTrue(
        "Replacement file should be non-durable before replaceFileId",
        wowCache.isNonDurable(replacementId));

    // Replace original with replacement — newFileId's internal ID should be removed
    wowCache.replaceFileId(originalId, replacementId);

    // The replacement's internal ID was removed from the non-durable registry
    assertFalse(
        "After replaceFileId, the consumed replacement file's ID should no longer be non-durable",
        wowCache.isNonDurable(replacementId));

    // The original file ID retains its durable status (was never in the non-durable set)
    assertFalse(
        "After replaceFileId, the original file ID should remain durable",
        wowCache.isNonDurable(originalId));
  }

  /**
   * Verifies that when the original (surviving) file ID was non-durable, it retains its
   * non-durable status after replaceFileId. Durability status is tied to the ID slot, not
   * the file content.
   */
  @Test
  public void testReplaceFileIdPreservesOriginalNonDurableStatus() throws IOException {
    // Create original non-durable file
    final var bookedOriginalId = wowCache.bookFileId("originalNd.tst");
    final var originalId =
        wowCache.addFile("originalNd.tst", bookedOriginalId, true);
    assertTrue(wowCache.isNonDurable(originalId));

    // Create a durable replacement file
    final var replacementId = wowCache.addFile("replacementDurable.tst");
    assertFalse(wowCache.isNonDurable(replacementId));

    // Replace: originalId's internal ID is kept; it should remain non-durable
    wowCache.replaceFileId(originalId, replacementId);

    assertTrue(
        "Original non-durable file's ID should remain non-durable after replaceFileId",
        wowCache.isNonDurable(originalId));
  }

  /**
   * Verifies that when both original and replacement are non-durable, replaceFileId removes
   * only the replacement's internal ID from the registry, leaving the original's.
   */
  @Test
  public void testReplaceFileIdBothNonDurableRemovesOnlyReplacement() throws IOException {
    final var bookedOrigId = wowCache.bookFileId("ndOrig2.tst");
    final var ndOrigId = wowCache.addFile("ndOrig2.tst", bookedOrigId, true);

    final var bookedReplId = wowCache.bookFileId("ndRepl2.tst");
    final var ndReplId = wowCache.addFile("ndRepl2.tst", bookedReplId, true);

    assertTrue(wowCache.isNonDurable(ndOrigId));
    assertTrue(wowCache.isNonDurable(ndReplId));

    wowCache.replaceFileId(ndOrigId, ndReplId);

    assertTrue(
        "Original non-durable ID must remain in registry after replace",
        wowCache.isNonDurable(ndOrigId));
    assertFalse(
        "Replacement's ID must be removed from registry after replace",
        wowCache.isNonDurable(ndReplId));
  }

  /**
   * Verifies that close() clears the non-durable file IDs set entirely, so no stale non-durable
   * entries remain after cache shutdown. Uses multiple non-durable files to verify the set is
   * fully cleared (not just one specific entry).
   */
  @Test
  public void testCloseResetsNonDurableRegistry() throws IOException {
    final var bookedId1 = wowCache.bookFileId("ndClose1.tst");
    final var fileId1 = wowCache.addFile("ndClose1.tst", bookedId1, true);

    final var bookedId2 = wowCache.bookFileId("ndClose2.tst");
    final var fileId2 = wowCache.addFile("ndClose2.tst", bookedId2, true);

    // Confirm both are tracked before close
    assertTrue(wowCache.isNonDurable(fileId1));
    assertTrue(wowCache.isNonDurable(fileId2));

    wowCache.close();

    // After close, both entries must be gone — verifies the set was fully cleared
    assertFalse(
        "First non-durable file should be absent from registry after close()",
        wowCache.isNonDurable(fileId1));
    assertFalse(
        "Second non-durable file should be absent from registry after close()",
        wowCache.isNonDurable(fileId2));

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
   * Verifies that renaming a non-durable file does not change its non-durable status, since
   * the internal file ID (used as the registry key) is unchanged by renaming.
   */
  @Test
  public void testRenameFilePreservesNonDurableStatus() throws IOException {
    final var bookedId = wowCache.bookFileId("ndRename.tst");
    final var fileId = wowCache.addFile("ndRename.tst", bookedId, true);
    assertTrue(wowCache.isNonDurable(fileId));

    wowCache.renameFile(fileId, "ndRenamed.tst");

    assertTrue(
        "Non-durable status must survive a rename (internal ID is unchanged)",
        wowCache.isNonDurable(fileId));
  }

  /**
   * Verifies that if addFile(name, id, nonDurable=true) throws because the file name is
   * already registered, the non-durable registry remains unmodified.
   */
  @Test
  public void testAddFileFailureDoesNotPoisonNonDurableRegistry() throws IOException {
    // Register a file name so the second addFile call will fail
    final var existingId = wowCache.addFile("conflict.tst");
    assertFalse(wowCache.isNonDurable(existingId));

    // Attempt to add the same name as non-durable with a different booked ID
    final var bookedId = wowCache.bookFileId("another.tst");
    assertThrows(
        "Expected StorageException for duplicate file name",
        StorageException.class,
        () -> wowCache.addFile("conflict.tst", bookedId, true));

    // Registry must not contain the booked ID's internal ID
    assertFalse(
        "Failed addFile must not leave a non-durable entry behind",
        wowCache.isNonDurable(bookedId));
    // The existing durable file must still be durable
    assertFalse(wowCache.isNonDurable(existingId));
  }

  // --- Side file persistence tests ---

  /**
   * Reopens the WOWCache by closing the current instance and creating a new one against the
   * same storage path. The WAL is also closed and reopened.
   */
  private void reopenCache() throws Exception {
    wowCache.close();
    createNewCache();
  }

  /**
   * Creates a new WAL + WOWCache against the same storage path. Callers that already closed
   * the old cache (e.g., to corrupt side files before reopening) call this directly.
   */
  private void createNewCache() throws Exception {
    writeAheadLog.close();
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

    files = new ClosableLinkedContainer<>(1024);
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

  /**
   * Verifies that non-durable file IDs are persisted to side files and correctly restored
   * after a close/reopen cycle. The non-durable file should still be reported as non-durable
   * after reopening.
   */
  @Test
  public void testNonDurableRegistryPersistsAcrossCloseReopen() throws Exception {
    final var bookedId = wowCache.bookFileId("ndPersist.tst");
    final var fileId = wowCache.addFile("ndPersist.tst", bookedId, true);
    assertTrue(wowCache.isNonDurable(fileId));

    // Also add a durable file to verify it remains durable
    final var durableId = wowCache.addFile("durablePersist.tst");
    assertFalse(wowCache.isNonDurable(durableId));

    reopenCache();

    // After reopen, non-durable file should still be non-durable
    final var restoredNdId = wowCache.fileIdByName("ndPersist.tst");
    assertTrue("Non-durable file must be findable by name after reopen", restoredNdId >= 0);
    assertTrue(
        "Non-durable status must survive close/reopen via side file persistence",
        wowCache.isNonDurable(restoredNdId));

    // Durable file should still be durable
    final var restoredDurableId = wowCache.fileIdByName("durablePersist.tst");
    assertTrue("Durable file must be findable by name after reopen", restoredDurableId >= 0);
    assertFalse(
        "Durable file should remain durable after close/reopen",
        wowCache.isNonDurable(restoredDurableId));
  }

  /**
   * Verifies that when the primary side file is corrupted (by truncating it), the shadow
   * copy is used as fallback and non-durable status is preserved.
   */
  @Test
  public void testCorruptPrimaryFallsBackToShadow() throws Exception {
    final var bookedId = wowCache.bookFileId("ndCorrupt.tst");
    final var fileId = wowCache.addFile("ndCorrupt.tst", bookedId, true);
    assertTrue(wowCache.isNonDurable(fileId));

    wowCache.close();

    // Corrupt the primary side file by truncating it
    final var primaryPath = storagePath.resolve("non_durable_files.cm");
    assertTrue("Primary side file should exist", Files.exists(primaryPath));
    try (var ch = java.nio.channels.FileChannel.open(primaryPath,
        java.nio.file.StandardOpenOption.WRITE)) {
      ch.truncate(2); // Truncate to an invalid size
    }

    // Reopen — should fall back to shadow
    createNewCache();

    final var restoredId = wowCache.fileIdByName("ndCorrupt.tst");
    assertTrue(restoredId >= 0);
    assertTrue(
        "Non-durable status must be recovered from shadow when primary is corrupt",
        wowCache.isNonDurable(restoredId));
  }

  /**
   * Verifies that when both the primary and shadow side files are missing or corrupt,
   * all files are treated as durable (safe fallback).
   */
  @Test
  public void testBothSideFilesCorruptTreatsAllAsDurable() throws Exception {
    final var bookedId = wowCache.bookFileId("ndBothCorrupt.tst");
    final var fileId = wowCache.addFile("ndBothCorrupt.tst", bookedId, true);
    assertTrue(wowCache.isNonDurable(fileId));

    wowCache.close();

    // Delete both side files
    Files.deleteIfExists(storagePath.resolve("non_durable_files.cm"));
    Files.deleteIfExists(storagePath.resolve("non_durable_files_shadow.cm"));

    // Reopen — should treat all files as durable
    createNewCache();

    final var restoredId = wowCache.fileIdByName("ndBothCorrupt.tst");
    assertTrue(restoredId >= 0);
    assertFalse(
        "With both side files missing, all files should be treated as durable",
        wowCache.isNonDurable(restoredId));
  }

  /**
   * Verifies that when the primary side file has a valid size but corrupted content (invalid
   * xxHash checksum), the shadow copy is used as fallback.
   */
  @Test
  public void testCorruptChecksumFallsBackToShadow() throws Exception {
    final var bookedId = wowCache.bookFileId("ndChecksum.tst");
    wowCache.addFile("ndChecksum.tst", bookedId, true);

    wowCache.close();

    // Corrupt the primary side file by overwriting content bytes (after version+hash header)
    // while keeping the file large enough to pass the size check
    final var primaryPath = storagePath.resolve("non_durable_files.cm");
    try (var ch = java.nio.channels.FileChannel.open(primaryPath,
        java.nio.file.StandardOpenOption.WRITE)) {
      // Overwrite the xxHash field with zeros to make the checksum invalid
      ch.position(4); // skip version
      ch.write(java.nio.ByteBuffer.allocate(8)); // zero out the hash
    }

    // Reopen — should fall back to shadow
    createNewCache();

    final var restoredId = wowCache.fileIdByName("ndChecksum.tst");
    assertTrue(restoredId >= 0);
    assertTrue(
        "Non-durable status must be recovered from shadow when primary has bad checksum",
        wowCache.isNonDurable(restoredId));
  }

  /**
   * Verifies that when both primary and shadow have corrupted checksums, the safe fallback
   * treats all files as durable.
   */
  @Test
  public void testBothChecksumCorruptTreatsAllAsDurable() throws Exception {
    final var bookedId = wowCache.bookFileId("ndBothBad.tst");
    wowCache.addFile("ndBothBad.tst", bookedId, true);

    wowCache.close();

    // Corrupt both files by zeroing the hash
    for (var fileName : new String[] {"non_durable_files.cm", "non_durable_files_shadow.cm"}) {
      final var path = storagePath.resolve(fileName);
      try (var ch = java.nio.channels.FileChannel.open(path,
          java.nio.file.StandardOpenOption.WRITE)) {
        ch.position(4);
        ch.write(java.nio.ByteBuffer.allocate(8));
      }
    }

    createNewCache();

    final var restoredId = wowCache.fileIdByName("ndBothBad.tst");
    assertTrue(restoredId >= 0);
    assertFalse(
        "With both checksums corrupt, all files should be treated as durable",
        wowCache.isNonDurable(restoredId));
  }

  /**
   * Verifies that a side file with an unsupported version is ignored (treated as corrupt).
   */
  @Test
  public void testUnsupportedVersionTreatsAsDurable() throws Exception {
    final var bookedId = wowCache.bookFileId("ndVersion.tst");
    wowCache.addFile("ndVersion.tst", bookedId, true);

    wowCache.close();

    // Overwrite the version field in both files to an unsupported value
    for (var fileName : new String[] {"non_durable_files.cm", "non_durable_files_shadow.cm"}) {
      final var path = storagePath.resolve(fileName);
      try (var ch = java.nio.channels.FileChannel.open(path,
          java.nio.file.StandardOpenOption.WRITE)) {
        ch.position(0);
        final var buf = java.nio.ByteBuffer.allocate(4);
        buf.putInt(999); // unsupported version
        buf.flip();
        ch.write(buf);
      }
    }

    createNewCache();

    final var restoredId = wowCache.fileIdByName("ndVersion.tst");
    assertTrue(restoredId >= 0);
    assertFalse(
        "With unsupported version, all files should be treated as durable",
        wowCache.isNonDurable(restoredId));
  }

  /**
   * Verifies that deleting the last non-durable file removes both side files, exercising
   * the empty-set deletion branch of writeNonDurableRegistry.
   */
  @Test
  public void testDeleteLastNonDurableFileRemovesSideFiles() throws Exception {
    final var bookedId = wowCache.bookFileId("ndLast.tst");
    final var fileId = wowCache.addFile("ndLast.tst", bookedId, true);
    assertTrue(Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertTrue(Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

    wowCache.deleteFile(fileId);

    assertFalse(
        "Primary side file must be deleted when last non-durable file is removed",
        Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertFalse(
        "Shadow side file must be deleted when last non-durable file is removed",
        Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));
  }

  /**
   * Simulates a crash where the primary side file was never created but the shadow is valid.
   * Recovery must read the shadow and restore non-durable status.
   */
  @Test
  public void testPrimaryAbsentShadowPresentRestoresFromShadow() throws Exception {
    final var bookedId = wowCache.bookFileId("ndShadowOnly.tst");
    wowCache.addFile("ndShadowOnly.tst", bookedId, true);

    wowCache.close();

    // Remove only the primary; leave the shadow intact
    Files.delete(storagePath.resolve("non_durable_files.cm"));
    assertTrue(Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

    createNewCache();

    final var restoredId = wowCache.fileIdByName("ndShadowOnly.tst");
    assertTrue(restoredId >= 0);
    assertTrue(
        "Non-durable status must be recovered from shadow when primary is absent",
        wowCache.isNonDurable(restoredId));
  }

  /**
   * Verifies that delete() removes both non-durable side files alongside the name-id map.
   */
  @Test
  public void testDeleteRemovesSideFiles() throws Exception {
    final var bookedId = wowCache.bookFileId("ndDelete.tst");
    wowCache.addFile("ndDelete.tst", bookedId, true);

    // Verify side files exist
    assertTrue(Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertTrue(Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

    wowCache.delete();
    wowCache = null;

    assertFalse(
        "Primary side file should be deleted after delete()",
        Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertFalse(
        "Shadow side file should be deleted after delete()",
        Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));
  }

  /**
   * Verifies that when no non-durable files exist, the side files are not created (or are
   * deleted if they existed from a previous state).
   */
  @Test
  public void testNoNonDurableFilesProducesNoSideFiles() throws Exception {
    // Add only durable files
    wowCache.addFile("durable1.tst");
    wowCache.addFile("durable2.tst");

    wowCache.close();

    assertFalse(
        "Primary side file should not exist when there are no non-durable files",
        Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertFalse(
        "Shadow side file should not exist when there are no non-durable files",
        Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

    wowCache = null;
  }

  /**
   * Verifies that multiple non-durable file IDs are correctly serialized and deserialized
   * across a close/reopen cycle. Uses 3 non-durable files to exercise the multi-entry
   * iteration in both write and read paths of the side file.
   */
  @Test
  public void testMultipleNonDurableFilesPersistAcrossReopen() throws Exception {
    final var booked1 = wowCache.bookFileId("ndMulti1.tst");
    final var id1 = wowCache.addFile("ndMulti1.tst", booked1, true);
    final var booked2 = wowCache.bookFileId("ndMulti2.tst");
    final var id2 = wowCache.addFile("ndMulti2.tst", booked2, true);
    final var booked3 = wowCache.bookFileId("ndMulti3.tst");
    final var id3 = wowCache.addFile("ndMulti3.tst", booked3, true);
    final var durableId = wowCache.addFile("durableMulti.tst");

    assertTrue(wowCache.isNonDurable(id1));
    assertTrue(wowCache.isNonDurable(id2));
    assertTrue(wowCache.isNonDurable(id3));
    assertFalse(wowCache.isNonDurable(durableId));

    reopenCache();

    assertTrue(
        "First non-durable file must survive reopen",
        wowCache.isNonDurable(wowCache.fileIdByName("ndMulti1.tst")));
    assertTrue(
        "Second non-durable file must survive reopen",
        wowCache.isNonDurable(wowCache.fileIdByName("ndMulti2.tst")));
    assertTrue(
        "Third non-durable file must survive reopen",
        wowCache.isNonDurable(wowCache.fileIdByName("ndMulti3.tst")));
    assertFalse(
        "Durable file must remain durable after reopen",
        wowCache.isNonDurable(wowCache.fileIdByName("durableMulti.tst")));
  }

  // --- Dirty pages table tests ---

  /**
   * Verifies that calling updateDirtyPagesTable for a non-durable file's page does NOT add
   * the page to the dirtyPages map. This is the critical invariant: non-durable pages must
   * never enter the dirty pages table, because they have no WAL records and would block WAL
   * segment truncation.
   *
   * <p>Also verifies that a durable file's page IS added to the dirty pages table under the
   * same conditions, confirming the skip is targeted at non-durable files only.
   */
  @Test
  public void testUpdateDirtyPagesTableSkipsNonDurableFile() throws Exception {
    // Add a non-durable file
    final var bookedNdId = wowCache.bookFileId("ndDirty.tst");
    final var ndFileId = wowCache.addFile("ndDirty.tst", bookedNdId, true);
    assertTrue(wowCache.isNonDurable(ndFileId));

    // Add a durable file for comparison
    final var durableFileId = wowCache.addFile("durableDirty.tst");
    assertFalse(wowCache.isNonDurable(durableFileId));

    // Create sentinel CachePointers (null pointer/frame is fine — updateDirtyPagesTable
    // only reads fileId and pageIndex from the pointer)
    final var ndPointer0 = new CachePointer((PageFrame) null, null, ndFileId, 0);
    final var ndPointer42 = new CachePointer((PageFrame) null, null, ndFileId, 42);
    final var durablePointer = new CachePointer((PageFrame) null, null, durableFileId, 0);

    final var startLsn = new LogSequenceNumber(0, 0);

    // Call updateDirtyPagesTable for both files at multiple page indexes
    wowCache.updateDirtyPagesTable(ndPointer0, startLsn);
    wowCache.updateDirtyPagesTable(ndPointer42, startLsn);
    wowCache.updateDirtyPagesTable(durablePointer, startLsn);

    // Also test the null-LSN path (falls back to writeAheadLog.end()) to confirm the
    // early return fires before any LSN resolution logic
    final var ndPointerNullLsn = new CachePointer((PageFrame) null, null, ndFileId, 99);
    wowCache.updateDirtyPagesTable(ndPointerNullLsn, null);

    // Access the private dirtyPages field via reflection
    final Field dirtyPagesField = WOWCache.class.getDeclaredField("dirtyPages");
    dirtyPagesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var dirtyPages =
        (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyPagesField.get(wowCache);

    // Build the expected page keys using the internal file IDs
    final var ndInternalId = wowCache.internalFileId(ndFileId);
    final var durableInternalId = wowCache.internalFileId(durableFileId);
    final var ndPageKey0 = new PageKey(ndInternalId, 0);
    final var ndPageKey42 = new PageKey(ndInternalId, 42);
    final var ndPageKey99 = new PageKey(ndInternalId, 99);
    final var durablePageKey = new PageKey(durableInternalId, 0);

    assertFalse(
        "Non-durable page 0 must NOT appear in dirtyPages table",
        dirtyPages.containsKey(ndPageKey0));
    assertFalse(
        "Non-durable page 42 must NOT appear in dirtyPages table",
        dirtyPages.containsKey(ndPageKey42));
    assertFalse(
        "Non-durable page 99 (null LSN path) must NOT appear in dirtyPages table",
        dirtyPages.containsKey(ndPageKey99));
    assertTrue(
        "Durable page must appear in dirtyPages table",
        dirtyPages.containsKey(durablePageKey));
    assertEquals(
        "Durable page's dirty LSN must match the provided startLSN",
        startLsn,
        dirtyPages.get(durablePageKey));

    // Falsification: temporarily clear the nonDurableFileIds set so all files appear durable,
    // re-call updateDirtyPagesTable for the non-durable file, and verify the page IS now added.
    // This proves the early-return guard (not some other reason) prevented the insertion above.
    final Field ndSetField = WOWCache.class.getDeclaredField("nonDurableFileIds");
    ndSetField.setAccessible(true);
    final var originalSet = ndSetField.get(wowCache);
    try {
      ndSetField.set(wowCache, new IntOpenHashSet());

      wowCache.updateDirtyPagesTable(ndPointer0, startLsn);

      assertTrue(
          "Falsification: without the non-durable guard, the page must appear in dirtyPages",
          dirtyPages.containsKey(ndPageKey0));
    } finally {
      // Restore original set to avoid side effects on tearDown
      ndSetField.set(wowCache, originalSet);
    }
  }

  /**
   * Verifies that syncDataFiles skips non-durable files during the fsync loop. Uses a
   * WOWCache instance with callFsync=true and deletes the non-durable file's underlying
   * data file from disk before calling syncDataFiles. If the skip works correctly, no
   * IOException is thrown (the deleted file is never opened for fsync). If the skip were
   * absent, the method would fail trying to synch a file whose handle was invalidated.
   */
  @Test
  public void testSyncDataFilesSkipsNonDurableFiles() throws Exception {
    // Close the default cache (callFsync=false) and create one with callFsync=true
    wowCache.delete();
    writeAheadLog.close();

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

    files = new ClosableLinkedContainer<>(1024);
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
            true, // callFsync=true
            Executors.newCachedThreadPool());
    wowCache.loadRegisteredFiles();

    // Create a durable file
    final var durableFileId = wowCache.addFile("durableSync.tst");
    assertFalse(wowCache.isNonDurable(durableFileId));

    // Create a non-durable file
    final var bookedNdId = wowCache.bookFileId("ndSync.tst");
    final var ndFileId = wowCache.addFile("ndSync.tst", bookedNdId, true);
    assertTrue(wowCache.isNonDurable(ndFileId));

    // Verify callFsync is true via reflection — ensures we are testing the fsync path,
    // not the trivial no-fsync path. Without this, the test could pass even if the
    // WOWCache was accidentally constructed with callFsync=false.
    final Field callFsyncField = WOWCache.class.getDeclaredField("callFsync");
    callFsyncField.setAccessible(true);
    assertTrue("WOWCache must have callFsync=true for this test",
        callFsyncField.getBoolean(wowCache));

    // Close the non-durable file's channel via the ClosableLinkedContainer. When
    // syncDataFiles attempts to fsync a closed channel, it throws. If the non-durable
    // skip works correctly, the closed channel is never reached.
    final var ndExternalId = wowCache.externalFileId(
        wowCache.internalFileId(ndFileId));
    final var ndEntry = files.acquire(ndExternalId);
    assertFalse("Non-durable file entry must exist", ndEntry == null);
    ndEntry.get().close();
    files.release(ndEntry);

    // syncDataFiles should succeed — the non-durable file is skipped before fsync.
    // The durable file's channel is still open, so its fsync proceeds normally.
    final var walSegment = writeAheadLog.begin().getSegment();
    wowCache.syncDataFiles(walSegment);
  }

  /**
   * Verifies that file IDs present in the side file but absent from the name-id map
   * (stale entries from a crash or manual edit) are silently dropped during reload.
   * Exercises the idNameMap.containsKey filter in readNonDurableRegistryFile.
   */
  @Test
  public void testStaleFileIdInSideFileIsFilteredOnReload() throws Exception {
    // Register two non-durable files
    final var booked1 = wowCache.bookFileId("ndStale1.tst");
    final var nd1 = wowCache.addFile("ndStale1.tst", booked1, true);
    final var booked2 = wowCache.bookFileId("ndStale2.tst");
    final var nd2 = wowCache.addFile("ndStale2.tst", booked2, true);

    // Close to persist both IDs in the side file
    wowCache.close();

    // Save the side files that contain both nd1 and nd2
    final var primaryPath = storagePath.resolve("non_durable_files.cm");
    final var shadowPath = storagePath.resolve("non_durable_files_shadow.cm");
    final var savedPrimary = Files.readAllBytes(primaryPath);
    final var savedShadow = Files.readAllBytes(shadowPath);

    // Reopen and delete nd1 (this rewrites side files without nd1)
    createNewCache();
    wowCache.deleteFile(nd1);
    wowCache.close();

    // Restore the old side files that still reference both nd1 and nd2
    Files.write(primaryPath, savedPrimary);
    Files.write(shadowPath, savedShadow);

    createNewCache();

    // nd1 was deleted from the name-id map, so its stale entry should be filtered out
    assertFalse(
        "Stale file ID should be filtered out on reload",
        wowCache.isNonDurable(nd1));
    // nd2 still exists, so it should be preserved
    final var restoredNd2 = wowCache.fileIdByName("ndStale2.tst");
    assertTrue(restoredNd2 >= 0);
    assertTrue(
        "Valid non-durable file should survive reload with stale entries",
        wowCache.isNonDurable(restoredNd2));
  }

  // --- Crash recovery deletion tests ---

  /**
   * Creates a LockFreeReadCache instance for use by crash recovery tests.
   */
  private LockFreeReadCache createReadCache() {
    final long maxMemory = 64L * 1024 * 1024; // 64 MB
    return new LockFreeReadCache(bufferPool, maxMemory, PAGE_SIZE);
  }

  /**
   * Verifies that deleteNonDurableFilesOnRecovery removes non-durable files from both caches,
   * cleans up name-id maps, and returns the correct set of deleted internal file IDs.
   * Durable files must remain intact.
   */
  @Test
  public void testDeleteNonDurableFilesOnRecovery() throws Exception {
    // Register a durable and a non-durable file
    final var durableId = wowCache.addFile("recoverDurable.tst");
    final var bookedNdId = wowCache.bookFileId("recoverNd.tst");
    final var ndFileId = wowCache.addFile("recoverNd.tst", bookedNdId, true);

    assertTrue(wowCache.isNonDurable(ndFileId));
    assertFalse(wowCache.isNonDurable(durableId));
    assertTrue(wowCache.exists(ndFileId));
    assertTrue(wowCache.exists(durableId));

    final var readCache = createReadCache();
    try {
      final var deletedIds = wowCache.deleteNonDurableFilesOnRecovery(readCache);

      // Verify the returned set contains the non-durable file's internal ID
      assertFalse("Deleted set should not be empty", deletedIds.isEmpty());
      final var ndIntId = wowCache.internalFileId(ndFileId);
      assertTrue(
          "Deleted set must contain the non-durable file's internal ID",
          deletedIds.contains(ndIntId));

      // Non-durable file should be gone from write cache
      assertFalse(
          "Non-durable file must not exist in write cache after recovery deletion",
          wowCache.exists(ndFileId));

      // Non-durable file should no longer be in the non-durable registry
      assertFalse(
          "Non-durable file must be removed from registry after recovery deletion",
          wowCache.isNonDurable(ndFileId));

      // Durable file must remain
      assertTrue(
          "Durable file must still exist after recovery deletion",
          wowCache.exists(durableId));
      assertFalse(wowCache.isNonDurable(durableId));

      // Side files should be deleted
      assertFalse(
          "Primary side file should be deleted after recovery",
          Files.exists(storagePath.resolve("non_durable_files.cm")));
      assertFalse(
          "Shadow side file should be deleted after recovery",
          Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));
    } finally {
      readCache.deleteStorage(wowCache);
      wowCache = null;
    }
  }

  /**
   * Verifies that deleteNonDurableFilesOnRecovery correctly handles multiple non-durable files,
   * deleting all of them while preserving durable files.
   */
  @Test
  public void testDeleteMultipleNonDurableFilesOnRecovery() throws Exception {
    final var durableId = wowCache.addFile("multiRecoverDurable.tst");

    final var booked1 = wowCache.bookFileId("multiRecoverNd1.tst");
    final var nd1 = wowCache.addFile("multiRecoverNd1.tst", booked1, true);
    final var booked2 = wowCache.bookFileId("multiRecoverNd2.tst");
    final var nd2 = wowCache.addFile("multiRecoverNd2.tst", booked2, true);

    final var readCache = createReadCache();
    try {
      final var deletedIds = wowCache.deleteNonDurableFilesOnRecovery(readCache);

      assertEquals("Should have deleted 2 non-durable files", 2, deletedIds.size());

      // Both non-durable files should be gone
      assertFalse(wowCache.exists(nd1));
      assertFalse(wowCache.exists(nd2));

      // Durable file must remain
      assertTrue(wowCache.exists(durableId));
    } finally {
      readCache.deleteStorage(wowCache);
      wowCache = null;
    }
  }

  /**
   * Verifies that deleteNonDurableFilesOnRecovery is a no-op when no non-durable files exist,
   * returning an empty set without affecting durable files.
   */
  @Test
  public void testDeleteNonDurableFilesOnRecoveryWithEmptyRegistry() throws Exception {
    // Only durable files
    final var durableId = wowCache.addFile("emptyRecoverDurable.tst");

    final var readCache = createReadCache();
    try {
      final var deletedIds = wowCache.deleteNonDurableFilesOnRecovery(readCache);

      assertTrue("Deleted set should be empty when no non-durable files exist",
          deletedIds.isEmpty());
      assertTrue("Durable file must still exist", wowCache.exists(durableId));
    } finally {
      readCache.deleteStorage(wowCache);
      wowCache = null;
    }
  }

  /**
   * Verifies that after close/reopen (simulating a crash where the side file persisted the
   * non-durable IDs), deleteNonDurableFilesOnRecovery correctly deletes the non-durable files
   * that were restored from the side file.
   */
  @Test
  public void testDeleteNonDurableFilesOnRecoveryAfterReopen() throws Exception {
    // Register a non-durable file and a durable file
    final var bookedNdId = wowCache.bookFileId("reopenRecoverNd.tst");
    final var ndFileId = wowCache.addFile("reopenRecoverNd.tst", bookedNdId, true);
    final var durableId = wowCache.addFile("reopenRecoverDurable.tst");

    // Simulate restart by closing and reopening
    reopenCache();

    // Verify non-durable status was restored from side file
    final var restoredNdId = wowCache.fileIdByName("reopenRecoverNd.tst");
    assertTrue(restoredNdId >= 0);
    assertTrue(wowCache.isNonDurable(restoredNdId));

    // Now perform crash recovery deletion
    final var readCache = createReadCache();
    try {
      final var deletedIds = wowCache.deleteNonDurableFilesOnRecovery(readCache);

      assertFalse("Should have deleted the non-durable file", deletedIds.isEmpty());
      assertFalse(
          "Non-durable file should be gone after recovery",
          wowCache.exists(restoredNdId));

      // Durable file must remain
      final var restoredDurableId = wowCache.fileIdByName("reopenRecoverDurable.tst");
      assertTrue(restoredDurableId >= 0);
      assertTrue(wowCache.exists(restoredDurableId));
    } finally {
      readCache.deleteStorage(wowCache);
      wowCache = null;
    }
  }

  /**
   * Verifies that deleteNonDurableFilesOnRecovery succeeds when the non-durable file's
   * on-disk data file is already missing (e.g., never flushed to disk or already deleted by
   * a prior recovery). The underlying deleteFile handles missing OS files gracefully without
   * throwing — this tests the success path, not the exception-handling path.
   */
  @Test
  public void testDeleteNonDurableFilesOnRecoverySucceedsWhenDiskFileMissing() throws Exception {
    final var bookedNdId = wowCache.bookFileId("missingOnDisk.tst");
    final var ndFileId = wowCache.addFile("missingOnDisk.tst", bookedNdId, true);
    assertTrue(wowCache.isNonDurable(ndFileId));

    // Manually delete the on-disk data file to simulate a crash where data was never flushed
    final var intId = wowCache.internalFileId(ndFileId);
    final var nativeFileName = wowCache.nativeFileNameById(ndFileId);
    assertNotNull("nativeFileName should not be null", nativeFileName);
    final var dataFilePath = storagePath.resolve(nativeFileName);
    assertTrue("Data file should exist before manual deletion", Files.exists(dataFilePath));
    Files.delete(dataFilePath);
    assertFalse("Data file should be gone after manual deletion", Files.exists(dataFilePath));

    final var readCache = createReadCache();
    try {
      // This should not throw — the missing file should be handled gracefully
      final var deletedIds = wowCache.deleteNonDurableFilesOnRecovery(readCache);

      // The returned set should still contain the file ID (it was in the registry)
      assertTrue(
          "Deleted set must contain the non-durable file's internal ID even if disk file missing",
          deletedIds.contains(intId));

      // Side files should be cleaned up
      assertFalse(
          "Primary side file should be deleted after recovery",
          Files.exists(storagePath.resolve("non_durable_files.cm")));
      assertFalse(
          "Shadow side file should be deleted after recovery",
          Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));
    } finally {
      readCache.deleteStorage(wowCache);
      wowCache = null;
    }
  }

  /**
   * Verifies that calling deleteNonDurableFilesOnRecovery twice is idempotent: the second call
   * returns an empty set and does not throw, since the first call already deleted everything.
   */
  @Test
  public void testDeleteNonDurableFilesOnRecoveryIsIdempotent() throws Exception {
    final var bookedNdId = wowCache.bookFileId("idempotent.tst");
    wowCache.addFile("idempotent.tst", bookedNdId, true);

    final var readCache = createReadCache();
    try {
      // First call — should delete the file
      final var firstResult = wowCache.deleteNonDurableFilesOnRecovery(readCache);
      assertFalse("First call should return non-empty set", firstResult.isEmpty());

      // Second call — should be a no-op
      final var secondResult = wowCache.deleteNonDurableFilesOnRecovery(readCache);
      assertTrue(
          "Second call should return empty set (everything already deleted)",
          secondResult.isEmpty());
    } finally {
      readCache.deleteStorage(wowCache);
      wowCache = null;
    }
  }

  /**
   * Verifies that clean shutdown preserves both non-durable side files on disk, confirming
   * they are available for crash recovery identification on the next startup. Also verifies
   * that after a clean reopen, non-durable files remain intact (not deleted) and recovery
   * deletion correctly removes them.
   */
  @Test
  public void testCleanShutdownPreservesSideFilesForCrashRecovery() throws Exception {
    final var bookedNdId = wowCache.bookFileId("ndShutdown.tst");
    wowCache.addFile("ndShutdown.tst", bookedNdId, true);
    final var durableId = wowCache.addFile("durableShutdown.tst");

    // Side files should exist while cache is open
    assertTrue(
        "Primary side file should exist before close",
        Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertTrue(
        "Shadow side file should exist before close",
        Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

    // Close cleanly
    wowCache.close();

    // Side files must survive clean shutdown
    assertTrue(
        "Primary side file must survive clean shutdown",
        Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertTrue(
        "Shadow side file must survive clean shutdown",
        Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

    // Reopen and verify non-durable file is still present (not deleted on clean open)
    createNewCache();
    final var restoredNdId = wowCache.fileIdByName("ndShutdown.tst");
    assertTrue(restoredNdId >= 0);
    assertTrue(
        "Non-durable file should be present after clean reopen (not crash recovery)",
        wowCache.isNonDurable(restoredNdId));
    assertTrue(
        "Non-durable file should exist in write cache after clean reopen",
        wowCache.exists(restoredNdId));

    // Now simulate crash recovery by calling deleteNonDurableFilesOnRecovery
    final var readCache = createReadCache();
    try {
      final var deletedIds = wowCache.deleteNonDurableFilesOnRecovery(readCache);

      assertFalse("Recovery should have deleted the non-durable file", deletedIds.isEmpty());
      assertFalse(
          "Non-durable file should be gone after crash recovery",
          wowCache.exists(restoredNdId));

      // Durable file must still exist
      final var restoredDurableId = wowCache.fileIdByName("durableShutdown.tst");
      assertTrue(restoredDurableId >= 0);
      assertTrue(wowCache.exists(restoredDurableId));

      // Side files should be cleaned up after recovery
      assertFalse(
          "Primary side file should be deleted after recovery",
          Files.exists(storagePath.resolve("non_durable_files.cm")));
    } finally {
      readCache.deleteStorage(wowCache);
      wowCache = null;
    }
  }

  /**
   * TC1/TY1 — Verifies partial failure behavior of deleteNonDurableFilesOnRecovery: when
   * readCache.deleteFile() throws for one non-durable file while succeeding for another, the
   * method must:
   * (1) return deletedIds containing BOTH IDs (snapshot taken before any deletion),
   * (2) keep the failed file in the live registry,
   * (3) remove the successful file from the live registry,
   * (4) persist remaining (failed) IDs to the side file for next recovery retry.
   */
  @Test
  public void testDeleteNonDurableFilesOnRecoveryPartialFailure() throws Exception {
    // Register two non-durable files and one durable file
    final var durableId = wowCache.addFile("partialRecoverDurable.tst");

    final var booked1 = wowCache.bookFileId("partialRecoverNd1.tst");
    final var nd1 = wowCache.addFile("partialRecoverNd1.tst", booked1, true);
    final var booked2 = wowCache.bookFileId("partialRecoverNd2.tst");
    final var nd2 = wowCache.addFile("partialRecoverNd2.tst", booked2, true);

    final var nd1IntId = wowCache.internalFileId(nd1);
    final var nd2IntId = wowCache.internalFileId(nd2);

    assertTrue(wowCache.isNonDurable(nd1));
    assertTrue(wowCache.isNonDurable(nd2));

    // Create a spy ReadCache that throws for nd2 but delegates normally for nd1
    final var readCache = spy(createReadCache());
    doThrow(new IOException("Simulated disk failure for nd2"))
        .when(readCache).deleteFile(eq(nd2), any());

    try {
      final var deletedIds = wowCache.deleteNonDurableFilesOnRecovery(readCache);

      // (1) deletedIds is a snapshot taken before deletion — must contain exactly both IDs
      assertEquals(
          "Snapshot should contain exactly 2 non-durable IDs",
          2, deletedIds.size());
      assertTrue(
          "Snapshot should contain nd1 internal ID",
          deletedIds.contains(nd1IntId));
      assertTrue(
          "Snapshot should contain nd2 internal ID",
          deletedIds.contains(nd2IntId));

      // (2) Failed file (nd2) must remain in the live non-durable registry
      assertTrue(
          "Failed file should still be in non-durable registry",
          wowCache.isNonDurable(nd2));

      // (3) Successful file (nd1) must be removed from the live registry
      assertFalse(
          "Successfully deleted file should be removed from registry",
          wowCache.isNonDurable(nd1));
      assertFalse(
          "Successfully deleted file should no longer exist in cache",
          wowCache.exists(nd1));

      // (4) Side files should still exist — writeNonDurableRegistry() persisted
      //     the remaining failed ID
      assertTrue(
          "Primary side file should exist after partial failure",
          Files.exists(storagePath.resolve("non_durable_files.cm")));
      assertTrue(
          "Shadow side file should exist after partial failure",
          Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

      // (5) Re-read side files to verify only the failed ID is persisted
      final var restoredIds = readNonDurableIdsFromSideFile();
      assertEquals(
          "Side file should contain exactly one ID (the failed file)",
          1, restoredIds.size());
      assertTrue(
          "Side file should contain the failed file's internal ID",
          restoredIds.contains(nd2IntId));

      // (6) Verify readCache.deleteFile was actually called for the successful file
      verify(readCache).deleteFile(eq(nd1), any());

      // Durable file must remain intact
      assertTrue(wowCache.exists(durableId));
      assertFalse(wowCache.isNonDurable(durableId));
    } finally {
      // nd2 still exists — delete via wowCache directly (not readCache spy)
      if (wowCache != null) {
        try {
          // Use a fresh readCache for cleanup to avoid the spy's exception
          var cleanupReadCache = createReadCache();
          cleanupReadCache.deleteStorage(wowCache);
        } catch (Exception ignored) {
          // best-effort cleanup
        }
        wowCache = null;
      }
    }
  }

  // --- Flush path tests ---

  /**
   * Writes data to a page and stores it in the write cache.
   */
  private void writeAndStorePage(long fileId, int pageIndex, byte[] data) throws Exception {
    wowCache.allocateNewPage(fileId);
    var ptr = wowCache.load(fileId, pageIndex, new ModifiableBoolean(), false);
    long stamp = ptr.acquireExclusiveLock();
    var buf = ptr.getBuffer();
    assertNotNull(buf);
    buf.put(DurablePage.NEXT_FREE_POSITION, data);
    DurablePage.setLogSequenceNumberForPage(buf, new LogSequenceNumber(0, 0));
    ptr.releaseExclusiveLock(stamp);
    wowCache.store(fileId, pageIndex, ptr);
    ptr.decrementReadersReferrer();
  }

  /**
   * Verifies that the partitioned flush path works end-to-end for both durable and non-durable
   * files. When dirty pages exist for both file types, {@code partitionAndFlushChunks} splits
   * them into durable chunks (flushed via {@code flushPages} with DWL + fsync) and non-durable
   * chunks (flushed via {@code flushNonDurablePages} without DWL/fsync).
   *
   * <p>The test writes pages, flushes via {@code close(fileId, true)} (per-file flush),
   * then reads data back from disk. The per-file flush goes through executeFileFlush →
   * partitionAndFlushChunks → flushPages / flushNonDurablePages.
   *
   * <p>This test covers:
   * <ul>
   *   <li>{@code partitionAndFlushChunks} — partition logic and dual-path dispatch</li>
   *   <li>{@code flushNonDurablePages} — entire method (write without DWL/fsync)</li>
   *   <li>{@code flushPages} — durable flush path (with DWL)</li>
   *   <li>{@code executeFileFlush} — page copy logic for both file types</li>
   * </ul>
   */
  @Test
  public void testFlushMixedDurableAndNonDurablePages() throws Exception {
    // Create one durable and one non-durable file
    final var durableFileId = wowCache.addFile("flushDurable.tst");
    assertFalse(wowCache.isNonDurable(durableFileId));

    final var bookedNdId = wowCache.bookFileId("flushNonDurable.tst");
    final var ndFileId = wowCache.addFile("flushNonDurable.tst", bookedNdId, true);
    assertTrue(wowCache.isNonDurable(ndFileId));

    // Write pages to both files
    final var durableData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    final var ndData = new byte[] {10, 20, 30, 40, 50, 60, 70, 80};

    writeAndStorePage(durableFileId, 0, durableData);
    writeAndStorePage(ndFileId, 0, ndData);

    // Per-file flush goes through executeFileFlush → partitionAndFlushChunks,
    // which routes durable chunks to flushPages and non-durable to flushNonDurablePages.
    wowCache.close(durableFileId, true);
    wowCache.close(ndFileId, true);

    // Re-load from disk to verify data was written correctly.
    // After close(fileId, true), the pages are flushed and removed from write cache,
    // so load() reads from the on-disk file.
    var durableCheck = wowCache.load(durableFileId, 0, new ModifiableBoolean(), false);
    var checkBuf = durableCheck.getBuffer();
    assertNotNull(checkBuf);
    var readData = new byte[8];
    checkBuf.get(DurablePage.NEXT_FREE_POSITION, readData);
    durableCheck.decrementReadersReferrer();
    for (int i = 0; i < 8; i++) {
      assertEquals("Durable page data mismatch at offset " + i, durableData[i], readData[i]);
    }

    var ndCheck = wowCache.load(ndFileId, 0, new ModifiableBoolean(), false);
    checkBuf = ndCheck.getBuffer();
    assertNotNull(checkBuf);
    checkBuf.get(DurablePage.NEXT_FREE_POSITION, readData);
    ndCheck.decrementReadersReferrer();
    for (int i = 0; i < 8; i++) {
      assertEquals("Non-durable page data mismatch at offset " + i, ndData[i], readData[i]);
    }
  }

  /**
   * Verifies that flushing only non-durable files (no durable files in the flush set) goes
   * through the non-durable flush path without triggering WAL flush or DWL writes. The fast
   * path in {@code executeFileFlush} detects an all-non-durable file set and skips WAL flush.
   */
  @Test
  public void testFlushOnlyNonDurablePages() throws Exception {
    final var bookedNdId = wowCache.bookFileId("flushNdOnly.tst");
    final var ndFileId = wowCache.addFile("flushNdOnly.tst", bookedNdId, true);
    assertTrue(wowCache.isNonDurable(ndFileId));

    final var data = new byte[] {11, 22, 33, 44, 55, 66, 77, 88};

    writeAndStorePage(ndFileId, 0, data);

    // Per-file flush for non-durable-only: executeFileFlush → partitionAndFlushChunks
    // → flushNonDurablePages (all chunks are non-durable, WAL flush is skipped)
    wowCache.close(ndFileId, true);

    // Re-load from disk (file was closed, but WOWCache still manages the name-id map)
    var check = wowCache.load(ndFileId, 0, new ModifiableBoolean(), false);
    var checkBuf = check.getBuffer();
    assertNotNull(checkBuf);
    var readData = new byte[8];
    checkBuf.get(DurablePage.NEXT_FREE_POSITION, readData);
    check.decrementReadersReferrer();
    for (int i = 0; i < 8; i++) {
      assertEquals("Non-durable page data mismatch at offset " + i, data[i], readData[i]);
    }
  }

  /**
   * Verifies that flushing multiple pages across both durable and non-durable files exercises
   * the chunk-splitting logic in the flush path. Multiple pages ensure that chunk boundaries
   * are hit and that each chunk is correctly classified as durable or non-durable.
   */
  @Test
  public void testFlushMultiplePagesPartitioned() throws Exception {
    final var durableFileId = wowCache.addFile("flushMultiDurable.tst");
    final var bookedNdId = wowCache.bookFileId("flushMultiNd.tst");
    final var ndFileId = wowCache.addFile("flushMultiNd.tst", bookedNdId, true);

    // Write 5 pages to each file
    for (int p = 0; p < 5; p++) {
      writeAndStorePage(durableFileId, p, new byte[] {(byte) (p + 1), 0, 0, 0, 0, 0, 0, 0});
      writeAndStorePage(ndFileId, p, new byte[] {(byte) (p + 100), 0, 0, 0, 0, 0, 0, 0});
    }

    // Per-file flush goes through partitionAndFlushChunks for each file
    wowCache.close(durableFileId, true);
    wowCache.close(ndFileId, true);

    // Verify all durable pages
    for (int p = 0; p < 5; p++) {
      var ptr = wowCache.load(durableFileId, p, new ModifiableBoolean(), false);
      var buf = ptr.getBuffer();
      assertNotNull(buf);
      assertEquals(
          "Durable page " + p + " data mismatch",
          (byte) (p + 1),
          buf.get(DurablePage.NEXT_FREE_POSITION));
      ptr.decrementReadersReferrer();
    }

    // Verify all non-durable pages
    for (int p = 0; p < 5; p++) {
      var ptr = wowCache.load(ndFileId, p, new ModifiableBoolean(), false);
      var buf = ptr.getBuffer();
      assertNotNull(buf);
      assertEquals(
          "Non-durable page " + p + " data mismatch",
          (byte) (p + 100),
          buf.get(DurablePage.NEXT_FREE_POSITION));
      ptr.decrementReadersReferrer();
    }
  }

  /**
   * Reads the non-durable IDs from the primary side file (non_durable_files.cm).
   * Format: [4 bytes version] [8 bytes xxHash64] [4 bytes count] [count * 4 bytes IDs]
   * (big-endian, matching writeNonDurableRegistry)
   */
  private IntOpenHashSet readNonDurableIdsFromSideFile() throws IOException {
    final var path = storagePath.resolve("non_durable_files.cm");
    final var bytes = Files.readAllBytes(path);
    final var buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    // Version field — detect format drift if writeNonDurableRegistry changes
    final var version = buf.getInt();
    assertEquals("Unexpected side file version", 1, version);
    // Skip xxHash (8 bytes)
    buf.position(buf.position() + 8);
    final var count = buf.getInt();
    final var ids = new IntOpenHashSet(count);
    for (int i = 0; i < count; i++) {
      ids.add(buf.getInt());
    }
    return ids;
  }
}
