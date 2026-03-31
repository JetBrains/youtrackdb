package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
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
    final var ndPointer = new CachePointer((PageFrame) null, null, ndFileId, 0);
    final var durablePointer = new CachePointer((PageFrame) null, null, durableFileId, 0);

    final var startLsn = new LogSequenceNumber(0, 0);

    // Call updateDirtyPagesTable for both
    wowCache.updateDirtyPagesTable(ndPointer, startLsn);
    wowCache.updateDirtyPagesTable(durablePointer, startLsn);

    // Access the private dirtyPages field via reflection
    final Field dirtyPagesField = WOWCache.class.getDeclaredField("dirtyPages");
    dirtyPagesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var dirtyPages =
        (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyPagesField.get(wowCache);

    // Build the expected page keys using the internal file IDs
    final var ndInternalId = wowCache.internalFileId(ndFileId);
    final var durableInternalId = wowCache.internalFileId(durableFileId);
    final var ndPageKey = new PageKey(ndInternalId, 0);
    final var durablePageKey = new PageKey(durableInternalId, 0);

    assertFalse(
        "Non-durable page must NOT appear in dirtyPages table",
        dirtyPages.containsKey(ndPageKey));
    assertTrue(
        "Durable page must appear in dirtyPages table",
        dirtyPages.containsKey(durablePageKey));

    // Falsification: temporarily clear the nonDurableFileIds set so all files appear durable,
    // re-call updateDirtyPagesTable for the non-durable file, and verify the page IS now added.
    // This proves the early-return guard (not some other reason) prevented the insertion above.
    final Field ndSetField = WOWCache.class.getDeclaredField("nonDurableFileIds");
    ndSetField.setAccessible(true);
    final var originalSet = ndSetField.get(wowCache);
    ndSetField.set(wowCache, new IntOpenHashSet());

    wowCache.updateDirtyPagesTable(ndPointer, startLsn);

    assertTrue(
        "Falsification: without the non-durable guard, the page must appear in dirtyPages",
        dirtyPages.containsKey(ndPageKey));

    // Restore original set to avoid side effects on tearDown
    ndSetField.set(wowCache, originalSet);
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
}
