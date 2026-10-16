package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.MockedStatic;

/**
 * Tests the in-memory non-durable file tracking in WOWCache: addFile with nonDurable flag,
 * isNonDurable queries, deleteFile cleanup, and close() clearing.
 *
 * <p>Tagged {@link SequentialTest} because {@link #beforeClass()} mutates two
 * {@link GlobalConfiguration} flags ({@code STORAGE_EXCLUSIVE_FILE_ACCESS}, {@code FILE_LOCK})
 * without per-method save/restore. {@link #afterClass()} captures the pre-test values in
 * {@link #beforeClass()} and restores them so a parallel surefire fork running afterwards is
 * not affected by the leak.
 */
@Category(SequentialTest.class)
public class WOWCacheNonDurableFileTrackingTest {

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;

  private static Path storagePath;
  private static String storageName;
  private static final ByteBufferPool bufferPool = new ByteBufferPool(PAGE_SIZE);

  // Pre-test snapshot of the two GlobalConfiguration flags this class mutates. Captured in
  // beforeClass() and restored in afterClass() so the leak does not affect later tests.
  private static Object savedExclusiveFileAccess;
  private static Object savedFileLock;

  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private ClosableLinkedContainer<Long, File> files;

  @BeforeClass
  public static void beforeClass() {
    // Snapshot before mutating so afterClass() can restore the exact pre-test values
    savedExclusiveFileAccess = GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.getValue();
    savedFileLock = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var buildDirectory = System.getProperty("buildDirectory", ".");
    storageName = "WOWCacheNonDurableTest";
    storagePath = Paths.get(buildDirectory).resolve(storageName);
  }

  @AfterClass
  public static void afterClass() {
    bufferPool.clear();
    // Restore the pre-test GlobalConfiguration values to avoid leaking the mutation into
    // any sequential test that runs afterwards in the same JVM
    if (savedExclusiveFileAccess != null) {
      GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(savedExclusiveFileAccess);
    }
    if (savedFileLock != null) {
      GlobalConfiguration.FILE_LOCK.setValue(savedFileLock);
    }
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

  /**
   * Registry mutations skip every channel force when the cache disables fsync.
   */
  @Test
  public void testRegistryMutationsDoNotForceWhenFsyncIsDisabled() throws Exception {
    assertRegistryMutationForces(false);
  }

  /**
   * Registry mutations retain their channel force when the cache enables fsync.
   */
  @Test
  public void testRegistryMutationsForceWhenFsyncIsEnabled() throws Exception {
    assertRegistryMutationForces(true);
  }

  private void assertRegistryMutationForces(boolean callFsync) throws Exception {
    wowCache.delete();
    createNewCache(callFsync);

    final Field registryPathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
    registryPathField.setAccessible(true);
    final var registryPath = (Path) registryPathField.get(wowCache);

    final var channels = new ArrayList<FileChannel>();
    for (var i = 0; i < 4; i++) {
      channels.add(
          spy(
              FileChannel.open(
                  registryPath, StandardOpenOption.READ, StandardOpenOption.WRITE)));
    }

    final var nextChannel = new java.util.concurrent.atomic.AtomicInteger();
    final var stubbingChannel = new FileChannel[1];
    try (MockedStatic<FileChannel> mockedChannels =
        mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      try {
        mockedChannels
            .when(
                () -> stubbingChannel[0] = FileChannel.open(
                    registryPath, StandardOpenOption.READ, StandardOpenOption.WRITE))
            .thenAnswer(invocation -> channels.get(nextChannel.getAndIncrement()));
      } finally {
        // CALLS_REAL_METHODS opens a separate channel while recording the stub.
        if (stubbingChannel[0] != null) {
          stubbingChannel[0].close();
        }
      }
      assertNotNull("stub setup must open the registry channel", stubbingChannel[0]);

      final var fileId = wowCache.addFile("forceRegistry.tst");
      wowCache.renameFile(fileId, "forceRegistryRenamed.tst");
      wowCache.deleteFile(fileId);
    }

    assertEquals("add, rename twice, and delete must open four registry channels", 4,
        nextChannel.get());
    if (callFsync) {
      final var forceCount =
          channels.stream()
              .flatMap(channel -> mockingDetails(channel).getInvocations().stream())
              .filter(invocation -> invocation.getMethod().getName().equals("force"))
              .count();
      assertEquals("add, rename, and delete must each force their durable registry entry", 3,
          forceCount);
    } else {
      for (final var channel : channels) {
        assertEquals(
            "disabled fsync must suppress every registry force invocation",
            0,
            countInvocations(channel, "force"));
      }
    }
  }

  /** Clean close suppresses the rewritten registry force when fsync is disabled. */
  @Test
  public void testCleanCloseRegistryRewriteSkipsForceWhenFsyncIsDisabled() throws Exception {
    assertCleanCloseRegistryForces(false, 0);
  }

  /** Clean close retains the rewritten registry force when fsync is enabled. */
  @Test
  public void testCleanCloseRegistryRewriteForcesWhenFsyncIsEnabled() throws Exception {
    assertCleanCloseRegistryForces(true, 1);
  }

  private void assertCleanCloseRegistryForces(boolean callFsync, long expectedForces)
      throws Exception {
    wowCache.delete();
    createNewCache(callFsync);
    wowCache.addFile("closeForceProbe.tst");

    final var backupPath = storagePath.resolve("name_id_map_v2_backup.cm");
    final var channel =
        spy(
            FileChannel.open(
                backupPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE));
    final var stubbingChannel = new FileChannel[1];
    try (MockedStatic<FileChannel> mockedChannels =
        mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      try {
        mockedChannels
            .when(
                () -> stubbingChannel[0] = FileChannel.open(
                    backupPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE))
            .thenReturn(channel);
      } finally {
        // Close the real channel opened by CALLS_REAL_METHODS during stub setup.
        if (stubbingChannel[0] != null) {
          stubbingChannel[0].close();
        }
      }
      assertNotNull("stub setup must open the backup channel", stubbingChannel[0]);
      wowCache.close();
      wowCache = null;
    }

    assertEquals(
        "clean-close registry force count must follow callFsync",
        expectedForces,
        countInvocations(channel, "force"));
  }

  /** Initial V3 map creation suppresses its registry force when fsync is disabled. */
  @Test
  public void testV3CreationSkipsForceWhenFsyncIsDisabled() throws Exception {
    assertV3CreationForces(false, 0);
  }

  /** Initial V3 map creation retains its registry force when fsync is enabled. */
  @Test
  public void testV3CreationForcesWhenFsyncIsEnabled() throws Exception {
    assertV3CreationForces(true, 1);
  }

  private void assertV3CreationForces(boolean callFsync, long expectedForces) throws Exception {
    wowCache.delete();
    createNewCache(callFsync);

    Files.deleteIfExists(storagePath.resolve("name_id_map_v3.cm"));
    final var temporaryPath = storagePath.resolve("name_id_map_v3_t.cm");
    final var channel = new java.util.concurrent.atomic.AtomicReference<FileChannel>();
    final var stubbingChannel = new FileChannel[1];
    try (MockedStatic<FileChannel> mockedChannels =
        mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      try {
        mockedChannels
            .when(
                () -> stubbingChannel[0] = FileChannel.open(
                    temporaryPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ))
            .thenAnswer(
                invocation -> {
                  final var realChannel =
                      temporaryPath
                          .getFileSystem()
                          .provider()
                          .newFileChannel(
                              temporaryPath,
                              java.util.Set.of(
                                  StandardOpenOption.CREATE,
                                  StandardOpenOption.WRITE,
                                  StandardOpenOption.READ));
                  final var channelSpy = spy(realChannel);
                  channel.set(channelSpy);
                  return channelSpy;
                });
      } finally {
        // Close the real channel opened by CALLS_REAL_METHODS during stub setup.
        if (stubbingChannel[0] != null) {
          stubbingChannel[0].close();
        }
      }
      assertNotNull("stub setup must open the temporary channel", stubbingChannel[0]);
      invokeFsyncFilesOrMigration(wowCache, "createNameIdMapV3");
    }

    assertNotNull("creation must open its temporary registry channel", channel.get());
    assertEquals(
        "V3 creation force count must follow callFsync",
        expectedForces,
        countInvocations(channel.get(), "force"));
  }

  /** fsyncFiles skips per-file synchronization but still truncates the DWL when disabled. */
  @Test
  public void testFsyncFilesSkipsFilesButTruncatesDwlWhenFsyncIsDisabled() throws Exception {
    assertFsyncFilesBehavior(false);
  }

  /** fsyncFiles synchronizes files and truncates the DWL when fsync is enabled. */
  @Test
  public void testFsyncFilesSynchronizesFilesAndTruncatesDwlWhenFsyncIsEnabled()
      throws Exception {
    assertFsyncFilesBehavior(true);
  }

  private void assertFsyncFilesBehavior(boolean callFsync) throws Exception {
    wowCache.delete();
    final var doubleWriteLog = mock(DoubleWriteLog.class);
    createNewCache(callFsync, doubleWriteLog);

    final var fileId = wowCache.addFile("fsyncFilesProbe.tst");
    final var realFile = files.remove(fileId);
    realFile.close();
    final var file = mock(File.class);
    when(file.isOpen()).thenReturn(true);
    files.add(fileId, file);

    invokeFsyncFilesOrMigration(wowCache, "fsyncFiles");

    if (callFsync) {
      verify(file).synch();
    } else {
      verify(file, never()).synch();
    }
    verify(doubleWriteLog).truncate();
  }

  /** A partial two-file synchronization failure prevents WAL and recovery-copy cleanup. */
  @Test
  public void testSyncDataFilesStopsCleanupAfterPartialForceFailure() throws Exception {
    wowCache.delete();
    final var doubleWriteLog = mock(DoubleWriteLog.class);
    createNewCache(true, doubleWriteLog);

    final var firstId = wowCache.addFile("syncFirst.tst");
    final var secondId = wowCache.addFile("syncSecond.tst");
    final var firstReal = files.remove(firstId);
    final var secondReal = files.remove(secondId);
    firstReal.close();
    secondReal.close();
    final var attempts = new AtomicInteger();
    final var first = mock(File.class);
    final var second = mock(File.class);
    when(first.isOpen()).thenReturn(true);
    when(second.isOpen()).thenReturn(true);
    doAnswer(invocation -> {
      if (attempts.incrementAndGet() == 2) {
        throw new StorageException(storageName, "injected second force failure");
      }
      return null;
    }).when(first).synch();
    doAnswer(invocation -> {
      if (attempts.incrementAndGet() == 2) {
        throw new StorageException(storageName, "injected second force failure");
      }
      return null;
    }).when(second).synch();
    files.add(firstId, first);
    files.add(secondId, second);
    final var originalBegin = writeAheadLog.begin();

    assertThrows(StorageException.class, () -> wowCache.syncDataFiles(Long.MAX_VALUE));
    assertEquals("one successful file must precede the failure", 2, attempts.get());
    assertEquals("failed attempt must retain WAL", originalBegin, writeAheadLog.begin());
    verify(doubleWriteLog, never()).truncate();
    verify(doubleWriteLog).endCheckpoint();

    wowCache.syncDataFiles(writeAheadLog.begin().getSegment());
    assertEquals("the next attempt retries both files", 4, attempts.get());
  }

  /** Full checkpoint and fuzzy checkpoint both stop on a real AsyncFile force failure. */
  @Test
  public void testRealAsyncFileForceFailureBlocksFlushAndCheckpointCleanup() throws Exception {
    wowCache.delete();
    final var doubleWriteLog = mock(DoubleWriteLog.class);
    createNewCache(true, doubleWriteLog);
    final var fileId = wowCache.addFile("realForceFailure.tst");
    final var file =
        (com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile) files.get(fileId);
    final var channelField = file.getClass().getDeclaredField("fileChannel");
    channelField.setAccessible(true);
    final var original = (java.nio.channels.AsynchronousFileChannel) channelField.get(file);
    final var channel = mock(java.nio.channels.AsynchronousFileChannel.class,
        org.mockito.AdditionalAnswers.delegatesTo(original));
    channelField.set(file, channel);
    file.allocateSpace(PAGE_SIZE);
    file.write(0, ByteBuffer.allocate(PAGE_SIZE));
    final var attempts = new AtomicInteger();
    org.mockito.Mockito.doAnswer(invocation -> {
      if (attempts.incrementAndGet() <= 2) {
        throw new IOException("injected data-file force failure");
      }
      return null;
    }).when(channel).force(true);
    final var begin = writeAheadLog.begin();
    final var storage = mock(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage.class,
        CALLS_REAL_METHODS);
    final var storageType =
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage.class;
    final var walField = storageType.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(storage, writeAheadLog);
    final var cacheField = storageType.getDeclaredField("writeCache");
    cacheField.setAccessible(true);
    cacheField.set(storage, wowCache);
    final var checkpoint = storageType.getDeclaredMethod("flushAllData");
    checkpoint.setAccessible(true);

    final var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> checkpoint.invoke(storage));
    assertTrue("real full checkpoint reports the file-force failure",
        failure.getCause() instanceof RuntimeException);
    assertThrows(StorageException.class,
        () -> wowCache.syncDataFiles(Long.MAX_VALUE));
    assertEquals("neither failure can delete WAL", begin, writeAheadLog.begin());
    verify(doubleWriteLog, never()).truncate();
    wowCache.flush();
    wowCache.syncDataFiles(writeAheadLog.begin().getSegment());
    assertEquals("both paths invoked file force again", 3, attempts.get());
  }

  /** A failed per-batch force cannot discard the double-write copies. */
  @Test
  public void testFsyncFilesFailureRetainsDwlCopies() throws Exception {
    wowCache.delete();
    final var doubleWriteLog = mock(DoubleWriteLog.class);
    createNewCache(true, doubleWriteLog);
    final var fileId = wowCache.addFile("failedDwlSync.tst");
    final var realFile = files.remove(fileId);
    realFile.close();
    final var file = mock(File.class);
    when(file.isOpen()).thenReturn(true);
    doThrow(new StorageException(storageName, "injected force failure")).when(file).synch();
    files.add(fileId, file);

    final var invocation = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> invokeFsyncFilesOrMigration(wowCache, "fsyncFiles"));
    assertTrue(invocation.getCause() instanceof StorageException);
    verify(doubleWriteLog, never()).truncate();
  }

  /** A creation-time force failure closes the file before it can be registered. */
  @Test
  public void testCreateFileForceFailureDoesNotLeaveUnownedOpenFile() throws Exception {
    final var file = mock(File.class);
    final var open = new AtomicBoolean(true);
    when(file.exists()).thenReturn(true);
    when(file.isOpen()).thenAnswer(invocation -> open.get());
    doAnswer(invocation -> {
      open.set(false);
      return null;
    }).when(file).close();
    doThrow(new StorageException(storageName, "injected initial force failure"))
        .when(file).synch();
    final var method = WOWCache.class.getDeclaredMethod("createFile", File.class, boolean.class);
    method.setAccessible(true);

    final var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> method.invoke(null, file, true));
    assertTrue(failure.getCause() instanceof StorageException);
    assertFalse("failed creation must close its unregistered handle", open.get());
    verify(file).close();
  }

  /** A failed initial force through addFile closes the real unregistered AsyncFile channel. */
  @Test
  public void failedCreationForceClosesUnregisteredAsyncFileHandle() throws Exception {
    wowCache.delete();
    createNewCache(true);
    final var name = "failedCreationForce.tst";
    final var bookedId = wowCache.bookFileId(name);
    final var path = storagePath.resolve("failedCreationForce_"
        + WOWCache.extractFileId(bookedId) + ".tst");
    final var realChannel = new AtomicReference<AsynchronousFileChannel>();
    final var channel = new AtomicReference<AsynchronousFileChannel>();

    try (MockedStatic<AsynchronousFileChannel> opens =
        mockStatic(AsynchronousFileChannel.class, CALLS_REAL_METHODS)) {
      opens.when(() -> AsynchronousFileChannel.open(eq(path), any(), any(ExecutorService.class)))
          .thenAnswer(invocation -> {
            final var real = (AsynchronousFileChannel) invocation.callRealMethod();
            realChannel.set(real);
            final var tracked = mock(AsynchronousFileChannel.class, delegatesTo(real));
            doThrow(new IOException("injected initial force failure"))
                .when(tracked).force(true);
            channel.set(tracked);
            return tracked;
          });

      final var failure = assertThrows(StorageException.class,
          () -> wowCache.addFile(name));
      assertTrue(failure.getMessage().contains("synchronizing"));
      assertNotNull("the data file must open before its initial force", channel.get());
      verify(channel.get()).close();
      assertFalse("failed creation must close the unregistered channel", channel.get().isOpen());
      assertEquals("failed creation cannot register the file", null, files.get(bookedId));
    } finally {
      if (realChannel.get() != null) {
        realChannel.get().close();
      }
    }
  }

  /** A file in use cannot be silently discarded by whole-cache close. */
  @Test
  public void testCloseKeepsAcquiredFileRegisteredUntilReleased() throws Exception {
    final var fileId = wowCache.addFile("inUseAtClose.tst");
    final var acquired = files.acquire(fileId);
    assertNotNull(acquired);
    try {
      final var failure = assertThrows(StorageException.class, wowCache::close);
      assertTrue(failure.getMessage().contains("still in use"));
      assertNotNull("failed close retains the file owner", files.get(fileId));
      assertTrue(files.get(fileId).isOpen());
    } finally {
      files.release(acquired);
    }
    wowCache.close();
    assertEquals(null, files.get(fileId));
    wowCache = null;
  }

  /** A failed cleanup retains the original creation error and reports the close error. */
  @Test
  public void testCreateFileFailedOpenSuppressesCleanupFailure() throws Exception {
    final var file = mock(File.class);
    final var failure = new StorageException(storageName, "injected open failure");
    final var closeFailure = new StorageException(storageName, "injected close failure");
    when(file.exists()).thenReturn(true);
    when(file.isOpen()).thenReturn(false, true);
    doThrow(failure).when(file).open();
    doThrow(closeFailure).when(file).close();
    final var method = WOWCache.class.getDeclaredMethod("createFile", File.class, boolean.class);
    method.setAccessible(true);

    final var invocation = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> method.invoke(null, file, true));
    assertEquals(failure, invocation.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(closeFailure, failure.getSuppressed()[0]);
    verify(file).close();
    verify(file, never()).synch();
  }

  /** Failed cache close leaves the file registered for a later owner-controlled attempt. */
  @Test
  public void testCloseForceFailureKeepsFileRegistered() throws Exception {
    final var fileId = wowCache.addFile("closeFailure.tst");
    final var realFile = files.remove(fileId);
    realFile.close();
    final var file = mock(File.class);
    when(file.isOpen()).thenReturn(true);
    when(file.getName()).thenReturn("closeFailure.tst");
    doThrow(new StorageException(storageName, "injected close force failure"))
        .when(file).close();
    files.add(fileId, file);

    assertThrows(StorageException.class, wowCache::close);
    assertEquals("failed close cannot orphan its handle", file, files.get(fileId));
  }

  /** A registry-force failure leaves closed entries and their names owned for a retry. */
  @Test
  public void testCloseRegistryForceFailureCanRetryWithoutLosingFileNames() throws Exception {
    wowCache.delete();
    createNewCache(true);
    final var fileId = wowCache.addFile("retryRegistryForce.tst");
    final var backupPath = storagePath.resolve("name_id_map_v2_backup.cm");
    final var forceCalls = new AtomicInteger();
    final var stubbingChannel = new FileChannel[1];
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      try {
        channels.when(() -> stubbingChannel[0] = FileChannel.open(backupPath,
            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE))
            .thenAnswer(invocation -> {
              final var real = backupPath.getFileSystem().provider().newFileChannel(backupPath,
                  java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ,
                      StandardOpenOption.WRITE));
              final var channel = spy(real);
              org.mockito.Mockito.doAnswer(force -> {
                if (forceCalls.incrementAndGet() == 1) {
                  throw new IOException("injected registry force failure");
                }
                force.callRealMethod();
                return null;
              }).when(channel).force(true);
              return channel;
            });
      } finally {
        // Close the real channel opened by CALLS_REAL_METHODS during stub setup.
        if (stubbingChannel[0] != null) {
          stubbingChannel[0].close();
        }
      }
      assertNotNull("stub setup must open the backup channel", stubbingChannel[0]);
      assertThrows(IOException.class, wowCache::close);
      assertEquals("failed registry write must retain the file owner", false,
          files.get(fileId) == null);
      assertEquals("physical file closed but still owned", false, files.get(fileId).isOpen());
      wowCache.close();
    }
    assertEquals(2, forceCalls.get());
    assertEquals("successful close releases the file registration", null, files.get(fileId));
    createNewCache(true);
    assertTrue("a retried close must persist the original name",
        wowCache.fileIdByName("retryRegistryForce.tst") >= 0);
  }

  /** A partial close transitions successful files to CLOSED without orphaning the failed one. */
  @Test
  public void testPartialCloseReopensEarlierFileAndRetriesFailedFile() throws Exception {
    final var firstId = wowCache.addFile("partialFirst.tst");
    final var secondId = wowCache.addFile("partialSecond.tst");
    final var closes = new AtomicInteger();
    for (final var id : new long[] {firstId, secondId}) {
      final var real = files.remove(id);
      final var name = real.getName();
      real.close();
      final var open = new AtomicBoolean(true);
      final var file = mock(File.class);
      when(file.getName()).thenReturn(name);
      when(file.isOpen()).thenAnswer(invocation -> open.get());
      org.mockito.Mockito.doAnswer(invocation -> {
        open.set(true);
        return null;
      }).when(file).open();
      org.mockito.Mockito.doAnswer(invocation -> {
        if (closes.incrementAndGet() == 2) {
          throw new StorageException(storageName, "injected second close failure");
        }
        open.set(false);
        return null;
      }).when(file).close();
      files.add(id, file);
    }
    assertThrows(StorageException.class, wowCache::close);
    final var firstClosedId = files.get(firstId).isOpen() ? secondId : firstId;
    final var failedId = firstClosedId == firstId ? secondId : firstId;
    assertFalse("completed file channel is physically closed", files.get(firstClosedId).isOpen());
    assertTrue("failed file channel stays owned", files.get(failedId).isOpen());
    final var acquired = files.acquire(firstClosedId);
    assertTrue("container must reopen a completed file after partial failure",
        acquired.get().isOpen());
    files.release(acquired);
    wowCache.close();
    assertTrue("failed file must be retried", closes.get() >= 3);
    wowCache = null;
  }

  private static void invokeFsyncFilesOrMigration(WOWCache cache, String methodName)
      throws Exception {
    final Method method = WOWCache.class.getDeclaredMethod(methodName);
    method.setAccessible(true);
    method.invoke(cache);
  }

  private static long countInvocations(Object mock, String methodName) {
    return mockingDetails(mock).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals(methodName))
        .count();
  }

  /** Deletion appends the logical name first and allows recreating that same logical name. */
  @Test
  public void deletedRecordKeepsLogicalAndDiskNamesInTheirFields() throws Exception {
    final var id = wowCache.addFile("deletedFields.tst");
    final var diskName = files.get(id).getName();
    wowCache.deleteFile(id);
    final var entries = readMapEntries();
    final var deleted = entries.get(entries.size() - 1);
    assertEquals("deletedFields.tst", deleted.getName());
    assertEquals(-wowCache.internalFileId(id), deleted.getFileId());
    assertEquals(diskName, deleted.getFileSystemName());
    assertEquals(id, wowCache.addFile("deletedFields.tst"));
    assertEquals(-1, wowCache.fileIdByName(diskName));
  }

  /** Failed creation restores booked maps and flags, closes its handle and stops disk writers. */
  @Test
  public void failedAppendUndoesBookedCreateAndStopsWriters() throws Exception {
    final var survivor = wowCache.addFile("surviving.tst");
    final var booked = wowCache.bookFileId("failedBooked.tst");
    final var names = mapSnapshot("nameIdMap");
    final var ids = mapSnapshot("idNameMap");
    final var path = storagePath.resolve(files.get(survivor).getName());
    final var bytes = Files.readAllBytes(path);
    final var created = new AtomicReference<File>();
    failAppend(1, () -> created.set(files.get(booked)));
    assertThrows(IOException.class, () -> wowCache.addFile("failedBooked.tst", booked, true));
    assertEquals(names, mapSnapshot("nameIdMap"));
    assertEquals(ids, mapSnapshot("idNameMap"));
    assertFalse(wowCache.isNonDurable(booked));
    assertEquals(null, files.get(booked));
    assertNotNull(created.get());
    assertFalse("undo closes the unowned handle", created.get().isOpen());
    assertEquals(booked, wowCache.bookFileId("failedBooked.tst"));
    assertThrows(IOException.class, () -> wowCache.addFile("blockedCreate.tst"));
    assertThrows(IOException.class,
        () -> wowCache.addFile("blockedExplicit.tst", 999_999L, true));
    assertThrows(IOException.class, () -> wowCache.deleteFile(survivor));
    assertThrows(IOException.class, () -> wowCache.renameFile(survivor, "blockedRename.tst"));
    assertThrows(IOException.class, () -> wowCache.loadFile("blockedLoad.tst"));
    assertThrows(IOException.class, () -> wowCache.restoreFileById(booked));
    assertEquals(names, mapSnapshot("nameIdMap"));
    assertEquals(ids, mapSnapshot("idNameMap"));
    org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(path));
    assertEquals(survivor, wowCache.loadFile("surviving.tst"));
    try (var paths = Files.list(storagePath)) {
      assertFalse("stopped writers must not create or rename disk files",
          paths.anyMatch(p -> p.getFileName().toString().startsWith("blocked")));
    }
    reopenCache();
    assertEquals(survivor, wowCache.fileIdByName("surviving.tst"));
    assertEquals(-1, wowCache.fileIdByName("failedBooked.tst"));
    assertEquals(booked, wowCache.bookFileId("failedBooked.tst"));
    assertEquals(booked, wowCache.addFile("failedBooked.tst", booked, true));
    assertTrue(wowCache.isNonDurable(booked));
  }

  /** Unbooked creation removes its new map keys and closes its handle on append failure. */
  @Test
  public void failedUnbookedCreateRestoresAbsentMapKeys() throws Exception {
    final var created = new AtomicReference<File>();
    wowCache.pauseBackgroundFlush();
    wowCache = spy(wowCache);
    doAnswer(invocation -> {
      final var entry = (NameFileIdEntry) invocation.getArgument(0);
      created.set(files.get(wowCache.externalFileId(entry.getFileId())));
      throw new IOException("injected unbooked append failure");
    }).when(wowCache).appendNameIdEntry(any(), org.mockito.ArgumentMatchers.anyBoolean());
    assertThrows(IOException.class, () -> wowCache.addFile("unbookedFailure.tst"));
    assertTrue(mapSnapshot("nameIdMap").isEmpty());
    assertTrue(mapSnapshot("idNameMap").isEmpty());
    assertFalse(created.get().isOpen());
  }

  /** Shrink-branch undo retains the earlier owner, both maps and the non-durable flag. */
  @Test
  public void failedShrinkRegistrationRetainsContainerAndEarlierDurability() throws Exception {
    wowCache.delete();
    createNewCache(true);
    final var id = wowCache.bookFileId("shrinkFailure.tst");
    wowCache.addFile("shrinkFailure.tst", id, true);
    final var file = files.get(id);
    file.allocateSpace(PAGE_SIZE);
    makeRegistrationBooked("shrinkFailure.tst", id);
    final var names = mapSnapshot("nameIdMap");
    final var ids = mapSnapshot("idNameMap");
    failAppend(1, () -> {
    });
    assertThrows(IOException.class, () -> wowCache.addFile("shrinkFailure.tst", id, true));
    assertEquals(names, mapSnapshot("nameIdMap"));
    assertEquals(ids, mapSnapshot("idNameMap"));
    assertEquals(file, files.get(id));
    assertTrue("undo cannot close an earlier owner", file.isOpen());
    assertEquals("the completed physical shrink remains visible", 0, file.getFileSize());
    assertTrue(wowCache.isNonDurable(id));
    // The fixture owns this pre-existing entry, which is intentionally absent from live maps.
    files.remove(id).close();
  }

  /** Side-file failure is undone before a live append, including disk state and earlier flags. */
  @Test
  public void failedNonDurableRegistryWriteUndoesRegistration() throws Exception {
    final var earlier = wowCache.bookFileId("earlierNonDurable.tst");
    wowCache.addFile("earlierNonDurable.tst", earlier, true);
    final var id = wowCache.bookFileId("failedSideFile.tst");
    final var names = mapSnapshot("nameIdMap");
    final var ids = mapSnapshot("idNameMap");
    final var bytes = Files.readAllBytes(storagePath.resolve("name_id_map_v3.cm"));
    final var attempts = new AtomicInteger();
    final var created = new AtomicReference<File>();
    wowCache.pauseBackgroundFlush();
    wowCache = spy(wowCache);
    doAnswer(invocation -> {
      invocation.callRealMethod();
      if (attempts.incrementAndGet() == 1) {
        created.set(files.get(id));
        throw new IOException("injected side-file failure after publication");
      }
      return null;
    }).when(wowCache).writeNonDurableRegistry();
    assertThrows(IOException.class, () -> wowCache.addFile("failedSideFile.tst", id, true));
    assertEquals(names, mapSnapshot("nameIdMap"));
    assertEquals(ids, mapSnapshot("idNameMap"));
    assertEquals(null, files.get(id));
    assertFalse(created.get().isOpen());
    assertTrue(wowCache.isNonDurable(earlier));
    assertFalse(wowCache.isNonDurable(id));
    assertEquals(IntOpenHashSet.of(wowCache.internalFileId(earlier)),
        readNonDurableIdsFromSideFile());
    org.junit.Assert.assertArrayEquals(bytes,
        Files.readAllBytes(storagePath.resolve("name_id_map_v3.cm")));
    assertThrows(IOException.class, () -> wowCache.addFile("blockedSideFile.tst"));
    reopenCache();
    assertTrue(wowCache.isNonDurable(earlier));
    assertEquals(-1, wowCache.fileIdByName("failedSideFile.tst"));
  }

  /**
   * A delete-side registry failure keeps the completed deletion in memory and stops every
   * later map writer before disk work. Clean reopen preserves the survivor and permits reuse
   * of the deleted ID as a durable file.
   */
  @Test
  public void failedDeleteRegistryWriteStopsWritersBeforeDiskSteps() throws Exception {
    final var survivor = wowCache.addFile("registrySurvivor.tst");
    final var deleted = wowCache.bookFileId("registryDelete.tst");
    wowCache.addFile("registryDelete.tst", deleted, true);
    final var survivorPath = storagePath.resolve(files.get(survivor).getName());
    final var deletedPath = storagePath.resolve(files.get(deleted).getName());
    final var mapBytes = Files.readAllBytes(storagePath.resolve("name_id_map_v3.cm"));
    wowCache.pauseBackgroundFlush();
    wowCache = spy(wowCache);
    doThrow(new IOException("injected delete registry failure"))
        .when(wowCache).writeNonDurableRegistry();

    assertThrows(IOException.class, () -> wowCache.deleteFile(deleted));
    assertFalse(Files.exists(deletedPath));
    assertFalse(wowCache.isNonDurable(deleted));
    assertEquals(null, files.get(deleted));
    final var names = mapSnapshot("nameIdMap");
    final var ids = mapSnapshot("idNameMap");
    assertThrows(IOException.class, () -> wowCache.addFile("blockedDeleteRegistry.tst"));
    assertThrows(IOException.class,
        () -> wowCache.addFile("registryDelete.tst", deleted, true));
    assertThrows(IOException.class, () -> wowCache.deleteFile(survivor));
    assertThrows(IOException.class,
        () -> wowCache.renameFile(survivor, "blockedRegistryRename.tst"));
    assertThrows(IOException.class, () -> wowCache.loadFile("registryDelete.tst"));
    assertThrows(IOException.class, () -> wowCache.restoreFileById(deleted));
    assertEquals(names, mapSnapshot("nameIdMap"));
    assertEquals(ids, mapSnapshot("idNameMap"));
    assertTrue(Files.exists(survivorPath));
    org.junit.Assert.assertArrayEquals(mapBytes,
        Files.readAllBytes(storagePath.resolve("name_id_map_v3.cm")));
    try (var paths = Files.list(storagePath)) {
      assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith("blocked")));
    }
    reopenCache();
    assertEquals(survivor, wowCache.fileIdByName("registrySurvivor.tst"));
    assertEquals(deleted, wowCache.addFile("registryDelete.tst"));
    assertFalse(wowCache.isNonDurable(deleted));
  }

  /** Failed delete append preserves the completed disk deletion through clean close and open. */
  @Test
  public void closeAfterDeleteAppendFailurePersistsDeletedState() throws Exception {
    final var id = wowCache.bookFileId("failedDelete.tst");
    wowCache.addFile("failedDelete.tst", id, true);
    final var path = storagePath.resolve(files.get(id).getName());
    failAppend(1, () -> {
    });
    assertThrows(IOException.class, () -> wowCache.deleteFile(id));
    assertFalse(Files.exists(path));
    assertEquals(null, files.get(id));
    assertEquals(-1, wowCache.fileIdByName("failedDelete.tst"));
    assertFalse(wowCache.isNonDurable(id));
    reopenCache();
    assertEquals(-1, wowCache.fileIdByName("failedDelete.tst"));
    assertEquals(id, wowCache.addFile("failedDelete.tst"));
  }

  /** Either rename-record failure leaves memory and clean close describing the OS rename. */
  @Test
  public void renameAppendFailuresKeepPhysicalRenameInMemoryAndAtClose() throws Exception {
    for (var append = 1; append <= 2; append++) {
      final var oldName = "renameFailure" + append + ".tst";
      final var newName = "renamedFailure" + append + ".tst";
      final var id = wowCache.addFile(oldName);
      final var path = storagePath.resolve(files.get(id).getName());
      failAppend(append, () -> {
      });
      assertThrows(IOException.class, () -> wowCache.renameFile(id, newName));
      assertFalse(Files.exists(path));
      assertTrue(files.get(id).exists());
      assertEquals(-1, wowCache.fileIdByName(oldName));
      assertEquals(id, wowCache.fileIdByName(newName));
      reopenCache();
      assertEquals(-1, wowCache.fileIdByName(oldName));
      assertEquals(id, wowCache.fileIdByName(newName));
    }
  }

  /** Clean close writes an inconsistent positive entry with no container owner as deleted. */
  @Test
  public void closeTreatsOnlyAbsentContainerEntriesAsDeleted() throws Exception {
    final var id = wowCache.addFile("missingOwner.tst");
    files.remove(id).close();
    wowCache.close();
    assertEquals(-wowCache.internalFileId(id), readMapEntries().get(0).getFileId());
    createNewCache();
    assertEquals(-1, wowCache.fileIdByName("missingOwner.tst"));
  }

  /** Low-limit eviction closes channels but preserves live records in the close rewrite. */
  @Test
  public void closeKeepsEvictedContainerEntriesLive() throws Exception {
    wowCache.delete();
    createNewCache(false, new DoubleWriteLogNoOP(), 1);
    final var ids = new java.util.HashMap<String, Long>();
    for (var i = 0; i < 5; i++) {
      final var name = "evicted" + i + ".tst";
      ids.put(name, wowCache.addFile(name));
    }
    assertTrue("the low limit must actually close a registered channel",
        ids.values().stream().anyMatch(id -> !files.get(id).isOpen()));
    reopenCache();
    for (final var entry : ids.entrySet()) {
      assertEquals(entry.getValue().longValue(), wowCache.fileIdByName(entry.getKey()));
      assertNotNull(files.get(entry.getValue()));
    }
  }

  /** Successful registration reopens and reuses a closed shrink-branch entry. */
  @Test
  public void successfulShrinkRegistrationReopensEarlierContainerEntry() throws Exception {
    final var id = wowCache.addFile("shrinkSuccess.tst");
    final var file = files.get(id);
    wowCache.close(id, false);
    makeRegistrationBooked("shrinkSuccess.tst", id);
    assertEquals(id, wowCache.addFile("shrinkSuccess.tst", id));
    assertEquals(file, files.get(id));
    assertTrue(file.isOpen());
  }

  /** Load append failure retains its completed live registration for the close rewrite. */
  @Test
  public void failedLoadAppendKeepsCompletedRegistration() throws Exception {
    final var id = wowCache.addFile("loadFailure.tst");
    files.remove(id).close();
    makeRegistrationBooked("loadFailure.tst", id);
    failAppend(1, () -> {
    });
    assertThrows(IOException.class, () -> wowCache.loadFile("loadFailure.tst"));
    assertEquals(id, wowCache.fileIdByName("loadFailure.tst"));
    assertNotNull(files.get(id));
    reopenCache();
    assertEquals(id, wowCache.fileIdByName("loadFailure.tst"));
  }

  private void failAppend(int failedCall, Runnable beforeFailure) throws Exception {
    // Periodic tasks retain their original cache object. Quiesce it before making a spy.
    wowCache.pauseBackgroundFlush();
    wowCache = spy(wowCache);
    final var calls = new AtomicInteger();
    doAnswer(invocation -> {
      if (calls.incrementAndGet() == failedCall) {
        beforeFailure.run();
        // Model a torn append. Clean close must replace it instead of appending after it.
        Files.write(storagePath.resolve("name_id_map_v3.cm"), new byte[] {1, 2, 3},
            StandardOpenOption.APPEND);
        throw new IOException("injected map append failure");
      }
      return invocation.callRealMethod();
    }).when(wowCache).appendNameIdEntry(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  private java.util.Map<?, ?> mapSnapshot(String fieldName) throws Exception {
    final var field = WOWCache.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return new java.util.HashMap<>((java.util.Map<?, ?>) field.get(wowCache));
  }

  @SuppressWarnings("unchecked")
  private void makeRegistrationBooked(String name, long id) throws Exception {
    final var intId = wowCache.internalFileId(id);
    final var nameField = WOWCache.class.getDeclaredField("nameIdMap");
    final var idField = WOWCache.class.getDeclaredField("idNameMap");
    nameField.setAccessible(true);
    idField.setAccessible(true);
    ((java.util.Map<String, Integer>) nameField.get(wowCache)).put(name, -intId);
    final var idMap = (java.util.Map<Integer, String>) idField.get(wowCache);
    idMap.remove(intId);
    idMap.put(-intId, name);
  }

  private java.util.List<NameFileIdEntry> readMapEntries() throws Exception {
    final var reader = WOWCache.class.getDeclaredMethod("readNextNameIdEntryV3", FileChannel.class);
    reader.setAccessible(true);
    final var entries = new ArrayList<NameFileIdEntry>();
    try (var channel = FileChannel.open(storagePath.resolve("name_id_map_v3.cm"))) {
      NameFileIdEntry entry;
      while ((entry = (NameFileIdEntry) reader.invoke(wowCache, channel)) != null) {
        entries.add(entry);
      }
    }
    return entries;
  }

  /** V1 is rejected without changing either the legacy map or an existing V3 map. */
  @Test
  public void testV1MapIsRejectedWithMigrationRemedy() throws Exception {
    assertLegacyMapRejected("name_id_map.cm");
  }

  /** V2 is rejected without changing either the legacy map or an existing V3 map. */
  @Test
  public void testV2MapIsRejectedWithMigrationRemedy() throws Exception {
    assertLegacyMapRejected("name_id_map_v2.cm");
  }

  private void assertLegacyMapRejected(String fileName) throws Exception {
    wowCache.addFile("retained.tst");
    wowCache.close();
    final var legacy = storagePath.resolve(fileName);
    final var legacyBytes = new byte[] {1, 2, 3};
    Files.write(legacy, legacyBytes);
    final var v3 = storagePath.resolve("name_id_map_v3.cm");
    final var v3Bytes = Files.readAllBytes(v3);
    final var failure = assertThrows(IOException.class, () -> createNewCache());
    // loadRegisteredFiles releases and closes the failed cache before propagating the error.
    wowCache = null;
    assertTrue(failure.getMessage().contains(fileName));
    assertTrue(failure.getMessage().contains("no longer supported"));
    assertTrue(failure.getMessage().contains("Export the database with the older release"));
    assertTrue(failure.getMessage().contains("import it into a fresh database"));
    org.junit.Assert.assertArrayEquals(legacyBytes, Files.readAllBytes(legacy));
    org.junit.Assert.assertArrayEquals(v3Bytes, Files.readAllBytes(v3));
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
    createNewCache(false);
  }

  private void createNewCache(boolean callFsync) throws Exception {
    createNewCache(callFsync, new DoubleWriteLogNoOP());
  }

  private void createNewCache(boolean callFsync, DoubleWriteLog doubleWriteLog) throws Exception {
    createNewCache(callFsync, doubleWriteLog, 1024);
  }

  private void createNewCache(boolean callFsync, DoubleWriteLog doubleWriteLog, int openLimit)
      throws Exception {
    createNewCache(callFsync, doubleWriteLog, new ClosableLinkedContainer<>(openLimit));
  }

  private void createNewCache(boolean callFsync, DoubleWriteLog doubleWriteLog,
      ClosableLinkedContainer<Long, File> newFiles) throws Exception {
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

    files = newFiles;
    wowCache =
        new WOWCache(
            PAGE_SIZE,
            false,
            bufferPool,
            writeAheadLog,
            doubleWriteLog,
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
            callFsync,
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
   * Deleting the last non-durable file retains two valid empty registries for later writes.
   */
  @Test
  public void testDeleteLastNonDurableFileRetainsEmptySideFiles() throws Exception {
    final var bookedId = wowCache.bookFileId("ndLast.tst");
    final var fileId = wowCache.addFile("ndLast.tst", bookedId, true);
    assertTrue(Files.exists(storagePath.resolve("non_durable_files.cm")));
    assertTrue(Files.exists(storagePath.resolve("non_durable_files_shadow.cm")));

    wowCache.deleteFile(fileId);

    assertEmptyRegistryCopies();
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
   * A durable-only storage retains two valid empty registries after clean close.
   */
  @Test
  public void testNoNonDurableFilesRetainsEmptySideFiles() throws Exception {
    // Add only durable files
    wowCache.addFile("durable1.tst");
    wowCache.addFile("durable2.tst");

    wowCache.close();

    assertEmptyRegistryCopies();

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
   * (stale entries from a crash or manual edit) are dropped and persisted during reload.
   * Valid non-durable entries remain in both registry copies.
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
    for (final var path : new Path[] {primaryPath, shadowPath}) {
      final var persisted = ByteBuffer.wrap(Files.readAllBytes(path));
      assertEquals("open must persist only the surviving ID", 1, persisted.getInt(12));
      assertEquals(wowCache.internalFileId(nd2), persisted.getInt(16));
    }
  }

  /**
   * An append-log reopen must match the session's deleted-id maps for both durable and
   * non-durable files. A stale side-file flag is filtered out, and reusing the deleted ID
   * creates a durable file that recovery leaves intact after a second crash.
   */
  @Test
  public void crashReopenOfDeletedRecordRemovesPositiveIdAndAllowsDurableReuse() throws Exception {
    for (final var nonDurable : new boolean[] {false, true}) {
      final var name = "crashDeleted" + nonDurable + ".tst";
      final var id = wowCache.bookFileId(name);
      wowCache.addFile(name, id, nonDurable);
      final var primary = storagePath.resolve("non_durable_files.cm");
      final var shadow = storagePath.resolve("non_durable_files_shadow.cm");
      final var primaryBytes = nonDurable ? Files.readAllBytes(primary) : null;
      final var shadowBytes = nonDurable ? Files.readAllBytes(shadow) : null;
      wowCache.deleteFile(id);
      if (nonDurable) {
        // Model a crash after the deleted map record but before the recovery batch registry write.
        Files.write(primary, primaryBytes);
        Files.write(shadow, shadowBytes);
      }
      final var names = mapSnapshot("nameIdMap");
      final var ids = mapSnapshot("idNameMap");
      crashReopenCache();
      assertEquals(names, mapSnapshot("nameIdMap"));
      assertEquals(ids, mapSnapshot("idNameMap"));
      assertFalse(mapSnapshot("idNameMap").containsKey(wowCache.internalFileId(id)));
      assertFalse("the loaded registry must discard the deleted ID", wowCache.isNonDurable(id));
      assertEquals(id, wowCache.bookFileId(name));
      assertEquals(id, wowCache.addFile(name, id, false));
      assertFalse("reused ID must be durable", wowCache.isNonDurable(id));
      assertEmptyRegistryCopies();
      crashReopenCache();
      assertFalse("a second crash must not restore the stale flag", wowCache.isNonDurable(id));
      final var readCache = createReadCache();
      assertTrue(wowCache.deleteNonDurableFilesOnRecovery(readCache).isEmpty());
      assertTrue("recovery must keep the reused durable file", wowCache.exists(id));
      wowCache.deleteFile(id);
    }
  }

  /**
   * A shadow fallback that filters a deleted ID rewrites both copies. A second shadow fallback
   * must keep the surviving non-durable file and must not flag the reused durable ID.
   */
  @Test
  public void filteredShadowRegistrySurvivesSecondCrashAndFallback() throws Exception {
    final var deleted = wowCache.bookFileId("shadowDeleted.tst");
    wowCache.addFile("shadowDeleted.tst", deleted, true);
    final var survivor = wowCache.bookFileId("shadowSurvivor.tst");
    wowCache.addFile("shadowSurvivor.tst", survivor, true);
    final var primary = storagePath.resolve("non_durable_files.cm");
    final var shadow = storagePath.resolve("non_durable_files_shadow.cm");
    final var staleShadow = Files.readAllBytes(shadow);
    wowCache.deleteFile(deleted);
    Files.write(shadow, staleShadow);
    Files.write(primary, new byte[2]);
    crashReopenCache();
    assertTrue(wowCache.isNonDurable(survivor));
    assertFalse(wowCache.isNonDurable(deleted));
    org.junit.Assert.assertArrayEquals(Files.readAllBytes(primary), Files.readAllBytes(shadow));
    assertEquals("the rewritten registry must contain only the survivor", 1,
        ByteBuffer.wrap(Files.readAllBytes(shadow)).getInt(12));
    wowCache.addFile("shadowDeleted.tst", deleted, false);
    Files.write(primary, new byte[2]);
    crashReopenCache();
    assertTrue("fallback must retain the live non-durable flag", wowCache.isNonDurable(survivor));
    assertFalse("fallback must not restore the deleted flag", wowCache.isNonDurable(deleted));
    final var removed = wowCache.deleteNonDurableFilesOnRecovery(createReadCache());
    assertTrue(removed.contains(wowCache.internalFileId(survivor)));
    assertFalse(removed.contains(wowCache.internalFileId(deleted)));
    assertTrue("recovery must preserve the reused durable file", wowCache.exists(deleted));
  }

  /** Filtering from any valid source must survive either torn in-place copy and retry open. */
  @Test
  public void filteredRegistryRewritePreservesEitherSourceAfterPartialWrite() throws Exception {
    for (final var source : new int[] {0, 1, 2}) {
      for (final var failedCopy : new int[] {1, 2}) {
        wowCache.delete();
        createNewCache(true);
        final var deleted = wowCache.bookFileId("filterDeleted.tst");
        wowCache.addFile("filterDeleted.tst", deleted, true);
        final var survivor = wowCache.bookFileId("filterSurvivor.tst");
        wowCache.addFile("filterSurvivor.tst", survivor, true);
        final var primary = storagePath.resolve("non_durable_files.cm");
        final var shadow = storagePath.resolve("non_durable_files_shadow.cm");
        final var stale = Files.readAllBytes(primary);
        wowCache.deleteFile(deleted);
        wowCache.close();
        Files.write(primary, source == 1 ? new byte[2] : stale);
        Files.write(shadow, source == 0 ? new byte[2] : stale);
        final var first = source == 0 ? shadow : primary;
        final var second = source == 0 ? primary : shadow;
        final var injected = new IOException("partial filtered registry write");
        try (var failure = failInPlaceRegistryWrite(failedCopy == 1 ? first : second, injected)) {
          assertEquals(injected, assertThrows(IOException.class, () -> createNewCache(true)));
        }
        assertRegistryContents(failedCopy == 1 ? second : first,
            failedCopy == 1 ? new IntOpenHashSet(new int[] {wowCache.internalFileId(deleted),
                wowCache.internalFileId(survivor)})
                : new IntOpenHashSet(new int[] {wowCache.internalFileId(survivor)}));
        createNewCache(true);
        assertTrue("retry must retain the live non-durable flag", wowCache.isNonDurable(survivor));
        assertFalse("retry must filter the deleted flag", wowCache.isNonDurable(deleted));
        assertRegistryContents(shadow, new IntOpenHashSet(new int[] {
            wowCache.internalFileId(survivor)}));
        // Both repaired copies must retain the live flag through another crash and fallback.
        crashReopenCache();
        Files.write(primary, new byte[2]);
        crashReopenCache();
        assertTrue(wowCache.isNonDurable(survivor));
        assertFalse(wowCache.isNonDurable(deleted));
      }
    }
  }

  /** A newer main copy cannot leave a stale backup flag across durable ID reuse and two crashes. */
  @Test
  public void newerMainCopyIsReconciledBeforeDurableReuseAndLaterTornWrite() throws Exception {
    assertDivergentCopiesCannotResurrectDeletedFlag(true);
  }

  /** A newer backup copy still yields to the main copy before filtering and durable ID reuse. */
  @Test
  public void newerBackupCopyIsReconciledBeforeFilteringAndLaterTornWrite() throws Exception {
    assertDivergentCopiesCannotResurrectDeletedFlag(false);
  }

  private void assertDivergentCopiesCannotResurrectDeletedFlag(boolean mainNewer) throws Exception {
    wowCache.delete();
    createNewCache(true);
    final var name = "divergentDeleted.tst";
    final var deleted = wowCache.bookFileId(name);
    wowCache.addFile(name, deleted, true);
    final var main = storagePath.resolve("non_durable_files.cm");
    final var backup = storagePath.resolve("non_durable_files_shadow.cm");
    final var stale = Files.readAllBytes(main);
    wowCache.deleteFile(deleted);
    wowCache.close();
    // The deleted map record survived, but neither registry copy dropped its flag yet.
    Files.write(main, stale);
    Files.write(backup, stale);
    if (!mainNewer) {
      // A damaged backup is rewritten first, allowing it to hold the newer empty snapshot.
      Files.write(backup, new byte[2]);
    }
    final var interruptedCopy = mainNewer ? backup : main;
    final var firstCrash = new IOException("crash before the second copy rewrite");
    try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      channels.when(() -> FileChannel.open(interruptedCopy, StandardOpenOption.WRITE))
          .thenThrow(firstCrash);
      assertEquals(firstCrash, assertThrows(IOException.class, () -> createNewCache(true)));
    }
    final var staleIds = new IntOpenHashSet(new int[] {wowCache.internalFileId(deleted)});
    assertRegistryContents(main, mainNewer ? new IntOpenHashSet() : staleIds);
    assertRegistryContents(backup, mainNewer ? staleIds : new IntOpenHashSet());

    final var selectedMain = Files.readAllBytes(main);
    final var backupForces = new AtomicInteger();
    final var mainWrites = new AtomicInteger();
    try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      channels.when(() -> FileChannel.open(backup, StandardOpenOption.WRITE))
          .thenAnswer(invocation -> {
            final var real = (FileChannel) invocation.callRealMethod();
            final var tracked = mock(FileChannel.class, delegatesTo(real));
            doAnswer(force -> {
              real.force(true);
              if (backupForces.incrementAndGet() == 1) {
                org.junit.Assert.assertArrayEquals(
                    "reconciliation must force the selected main copy before filtering",
                    selectedMain, Files.readAllBytes(backup));
              }
              return null;
            }).when(tracked).force(true);
            return tracked;
          });
      channels.when(() -> FileChannel.open(main, StandardOpenOption.WRITE))
          .thenAnswer(invocation -> {
            assertEquals("reconciliation must precede every other registry write", 1,
                backupForces.get());
            mainWrites.incrementAndGet();
            return invocation.callRealMethod();
          });
      createNewCache(true);
    }
    assertEquals(mainNewer ? 1 : 2, backupForces.get());
    assertEquals(mainNewer ? 0 : 1, mainWrites.get());
    assertEmptyRegistryCopies();
    assertFalse(wowCache.isNonDurable(deleted));
    assertEquals(deleted, wowCache.addFile(name, deleted, false));
    final var durablePath = storagePath.resolve(wowCache.nativeFileNameById(deleted));
    final var durableBytes = Files.readAllBytes(durablePath);
    final var nextId = wowCache.bookFileId("divergentNext.tst");
    final var secondCrash = new IOException("crash during a later main-copy rewrite");
    final var failedWrites = new AtomicInteger();
    // Tear both the attempted snapshot and its rollback, with a distinct error for each I/O call.
    try (var channels = failInPlaceRegistryWrite(main, () -> failedWrites.getAndIncrement() == 0
        ? secondCrash : new IOException("rollback main-copy rewrite also failed"))) {
      assertEquals(secondCrash, assertThrows(IOException.class,
          () -> wowCache.addFile("divergentNext.tst", nextId, true)));
    }
    assertEquals("both writes must tear before falling back to the backup copy", 2,
        failedWrites.get());
    assertRegistryContents(backup, new IntOpenHashSet());
    crashReopenCache();
    assertFalse("fallback must not restore the deleted flag", wowCache.isNonDurable(deleted));
    assertFalse(wowCache.isNonDurable(nextId));
    assertTrue(wowCache.deleteNonDurableFilesOnRecovery(createReadCache()).isEmpty());
    assertTrue("recovery must retain the reused durable file", wowCache.exists(deleted));
    org.junit.Assert.assertArrayEquals(durableBytes, Files.readAllBytes(durablePath));
    assertEmptyRegistryCopies();
  }

  /**
   * A 16-byte header and 4,092 IDs fill the 16 KiB limit exactly in both copies. The reader
   * accepts each copy, and the writer rejects one more ID without opening either copy for writing.
   */
  @Test
  public void registrySizeBoundaryPreservesMainAndBackupCopies() throws Exception {
    wowCache.pauseBackgroundFlush();
    final var registry = WOWCache.class.getDeclaredField("nonDurableFileIds");
    registry.setAccessible(true);
    final var boundaryIds = new IntOpenHashSet();
    for (var id = 1; id <= 4_092; id++) {
      boundaryIds.add(id);
    }
    registry.set(wowCache, boundaryIds);
    wowCache.writeNonDurableRegistry();
    final var copies = new java.util.HashMap<Path, byte[]>();
    for (final var name : new String[] {"non_durable_files.cm", "non_durable_files_shadow.cm"}) {
      final var path = storagePath.resolve(name);
      assertEquals("16 header bytes plus 4 bytes per ID must reach exactly 16 KiB",
          16 + 4L * boundaryIds.size(), Files.size(path));
      assertEquals(16_384, Files.size(path));
      assertRegistryContents(path, boundaryIds);
      copies.put(path, Files.readAllBytes(path));
    }

    final var oversizedIds = new IntOpenHashSet(boundaryIds);
    oversizedIds.add(4_093);
    registry.set(wowCache, oversizedIds);
    try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      final var failure = assertThrows(IOException.class, () -> wowCache.writeNonDurableRegistry());
      assertTrue("the error must name the unchanged byte limit",
          failure.getMessage().contains("limit of 16384 bytes"));
      for (final var path : copies.keySet()) {
        channels.verify(() -> FileChannel.open(path, StandardOpenOption.WRITE), never());
      }
    } finally {
      registry.set(wowCache, boundaryIds);
    }
    for (final var copy : copies.entrySet()) {
      org.junit.Assert.assertArrayEquals(copy.getValue(), Files.readAllBytes(copy.getKey()));
      assertRegistryContents(copy.getKey(), boundaryIds);
    }
  }

  /**
   * Creating the 4,093rd non-durable file must preserve the exact booked maps, registry object,
   * file owners and disk bytes without writing either copy. Crash reopen must retain all 4,092 IDs.
   */
  @Test
  public void oversizedNonDurableCreateKeepsEarlierStateAndBothCopiesAfterReopen()
      throws Exception {
    wowCache.pauseBackgroundFlush();
    final var expectedIds = new IntOpenHashSet();
    final var owners = new java.util.HashMap<Long, File>();
    for (var id = 1; id <= 4_092; id++) {
      final var fileId = wowCache.externalFileId(id);
      wowCache.addFile("registryBoundary" + id + ".tst", fileId, true);
      expectedIds.add(id);
      owners.put(fileId, files.get(fileId));
    }
    final var booked = wowCache.bookFileId("registryOverLimit.tst");
    final var names = mapSnapshot("nameIdMap");
    final var ids = mapSnapshot("idNameMap");
    final var registry = WOWCache.class.getDeclaredField("nonDurableFileIds");
    registry.setAccessible(true);
    final var earlierRegistry = registry.get(wowCache);
    final var diskBytes = new java.util.HashMap<Path, byte[]>();
    try (var paths = Files.list(storagePath)) {
      for (final var path : paths.filter(p -> p.toString().endsWith(".tst")
          || p.toString().endsWith(".cm")).toList()) {
        diskBytes.put(path, Files.readAllBytes(path));
      }
    }
    final var main = storagePath.resolve("non_durable_files.cm");
    final var backup = storagePath.resolve("non_durable_files_shadow.cm");
    try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      final var failure = assertThrows(IOException.class,
          () -> wowCache.addFile("registryOverLimit.tst", booked, true));
      assertTrue("the capacity error must identify the registry and its byte limit",
          failure.getMessage().contains("Non-durable registry size 16388 bytes")
              && failure.getMessage().contains("limit of 16384 bytes"));
      channels.verify(() -> FileChannel.open(main, StandardOpenOption.WRITE), never());
      channels.verify(() -> FileChannel.open(backup, StandardOpenOption.WRITE), never());
    }
    assertEquals(names, mapSnapshot("nameIdMap"));
    assertEquals(ids, mapSnapshot("idNameMap"));
    org.junit.Assert.assertSame(earlierRegistry, registry.get(wowCache));
    assertEquals(expectedIds, registry.get(wowCache));
    assertFalse(wowCache.isNonDurable(booked));
    assertEquals(null, files.get(booked));
    for (final var owner : owners.entrySet()) {
      org.junit.Assert.assertSame(owner.getValue(), files.get(owner.getKey()));
    }
    try (var paths = Files.list(storagePath)) {
      assertEquals("rejected creation must not leave a new data or registry file",
          diskBytes.keySet(),
          new java.util.HashSet<>(paths.filter(p -> p.toString().endsWith(".tst")
              || p.toString().endsWith(".cm")).toList()));
    }
    for (final var entry : diskBytes.entrySet()) {
      org.junit.Assert.assertArrayEquals("rejected creation must preserve " + entry.getKey(),
          entry.getValue(), Files.readAllBytes(entry.getKey()));
    }
    assertRegistryContents(main, expectedIds);
    assertRegistryContents(backup, expectedIds);
    crashReopenCache();
    assertEquals(expectedIds, registry.get(wowCache));
    assertEquals(-1, wowCache.fileIdByName("registryOverLimit.tst"));
    for (var id = 1; id <= 4_092; id++) {
      assertEquals(wowCache.externalFileId(id),
          wowCache.fileIdByName("registryBoundary" + id + ".tst"));
      assertTrue(wowCache.isNonDurable(wowCache.externalFileId(id)));
    }
    assertRegistryContents(main, expectedIds);
    assertRegistryContents(backup, expectedIds);
  }

  /** Equal valid copies, empty or non-empty, must allow open without any registry write. */
  @Test
  public void equalValidRegistryCopiesAreNotRewrittenAtOpen() throws Exception {
    for (final var nonEmpty : new boolean[] {false, true}) {
      if (nonEmpty) {
        final var id = wowCache.bookFileId("equalCopies.tst");
        wowCache.addFile("equalCopies.tst", id, true);
      }
      wowCache.close();
      final var writes = new AtomicInteger();
      try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
        for (final var name : new String[] {"non_durable_files.cm",
            "non_durable_files_shadow.cm"}) {
          final var path = storagePath.resolve(name);
          channels.when(() -> FileChannel.open(path, StandardOpenOption.WRITE))
              .thenAnswer(invocation -> {
                writes.incrementAndGet();
                throw new AssertionError("equal valid registry copies must not be rewritten");
              });
        }
        createNewCache(true);
      }
      assertEquals(0, writes.get());
      assertEquals(nonEmpty, !readNonDurableIdsFromSideFile().isEmpty());
    }
  }

  /** In-session torn writes from primary, shadow or both valid copies keep one exact set. */
  @Test
  public void sessionRegistryRewritePreservesEitherSourceAfterPartialWrite() throws Exception {
    assertInPlaceRewriteSurvivesPartialWrite(false);
  }

  /** Empty-set publication retains a checksummed old or empty copy at either write boundary. */
  @Test
  public void emptyRegistryRewritePreservesEitherSourceAfterPartialWrite() throws Exception {
    assertInPlaceRewriteSurvivesPartialWrite(true);
  }

  private void assertInPlaceRewriteSurvivesPartialWrite(boolean empty) throws Exception {
    for (final var source : new int[] {0, 1, 2}) {
      for (final var failedCopy : new int[] {1, 2}) {
        wowCache.delete();
        createNewCache(true);
        final var oldId = wowCache.bookFileId("sessionOld.tst");
        wowCache.addFile("sessionOld.tst", oldId, true);
        final var newId = wowCache.addFile("sessionNew.tst");
        wowCache.pauseBackgroundFlush();
        final var primary = storagePath.resolve("non_durable_files.cm");
        final var shadow = storagePath.resolve("non_durable_files_shadow.cm");
        if (source != 2) {
          Files.write(source == 1 ? primary : shadow, new byte[2]);
        }
        final var oldSet = new IntOpenHashSet(new int[] {wowCache.internalFileId(oldId)});
        final var updated = empty ? new IntOpenHashSet()
            : new IntOpenHashSet(new int[] {wowCache.internalFileId(newId)});
        final var registry = WOWCache.class.getDeclaredField("nonDurableFileIds");
        registry.setAccessible(true);
        registry.set(wowCache, updated);
        final var first = source == 0 ? shadow : primary;
        final var second = source == 0 ? primary : shadow;
        final var injected = new IOException("partial in-place registry write");
        try (var failure = failInPlaceRegistryWrite(failedCopy == 1 ? first : second, injected)) {
          assertEquals(injected,
              assertThrows(IOException.class, () -> wowCache.writeNonDurableRegistry()));
        }
        assertRegistryContents(failedCopy == 1 ? second : first,
            failedCopy == 1 ? oldSet : updated);
        crashReopenCache();
        assertEquals("reopen must read the complete old or new set", failedCopy == 1,
            wowCache.isNonDurable(oldId));
        assertEquals("reopen must not combine sets or lose both non-empty sets",
            !empty && failedCopy == 2, wowCache.isNonDurable(newId));
        assertRegistryContents(primary, failedCopy == 1 ? oldSet : updated);
        assertRegistryContents(shadow, failedCopy == 1 ? oldSet : updated);
      }
    }
  }

  /** Empty copies remain valid and exactly header-sized through repeated writes and reopen. */
  @Test
  public void emptyRegistryCopiesRemainAfterRewriteAndReopen() throws Exception {
    final var id = wowCache.bookFileId("emptyRetained.tst");
    wowCache.addFile("emptyRetained.tst", id, true);
    wowCache.deleteFile(id);
    wowCache.writeNonDurableRegistry();
    crashReopenCache();
    assertEmptyRegistryCopies();
  }

  /** A retry repairs the damaged second copy before touching the only valid first copy. */
  @Test
  public void registryRetryProtectsFirstPublishedCopy() throws Exception {
    final var id = wowCache.bookFileId("registryRetry.tst");
    wowCache.addFile("registryRetry.tst", id, true);
    wowCache.pauseBackgroundFlush();
    final var primary = storagePath.resolve("non_durable_files.cm");
    final var shadow = storagePath.resolve("non_durable_files_shadow.cm");
    final var injected = new IOException("second copy failed");
    for (var attempt = 0; attempt < 2; attempt++) {
      try (var failure = failInPlaceRegistryWrite(shadow, injected)) {
        assertEquals(injected,
            assertThrows(IOException.class, () -> wowCache.writeNonDurableRegistry()));
      }
      assertRegistryContents(primary,
          new IntOpenHashSet(new int[] {wowCache.internalFileId(id)}));
    }
    wowCache.writeNonDurableRegistry();
    crashReopenCache();
    assertTrue(wowCache.isNonDurable(id));
  }

  /** Open creates both missing copies, and recreates either missing copy without losing flags. */
  @Test
  public void missingRegistryCopiesAreCreatedBeforeWritersEnter() throws Exception {
    assertEmptyRegistryCopies();
    wowCache.close();
    Files.delete(storagePath.resolve("non_durable_files.cm"));
    Files.delete(storagePath.resolve("non_durable_files_shadow.cm"));
    createNewCache(true);
    assertEmptyRegistryCopies();
    final var id = wowCache.bookFileId("missingCopy.tst");
    wowCache.addFile("missingCopy.tst", id, true);
    for (final var name : new String[] {"non_durable_files.cm", "non_durable_files_shadow.cm"}) {
      wowCache.close();
      Files.delete(storagePath.resolve(name));
      createNewCache(true);
      assertTrue("recreation must retain the selected snapshot", wowCache.isNonDurable(id));
      for (final var copy : new String[] {"non_durable_files.cm", "non_durable_files_shadow.cm"}) {
        assertRegistryContents(storagePath.resolve(copy),
            new IntOpenHashSet(new int[] {wowCache.internalFileId(id)}));
      }
    }
  }

  /** Each existing copy is written, shortened and metadata-forced in that order without moving. */
  @Test
  public void inPlaceRegistryWritesTruncateBeforeForceAndKeepDirectoryEntries() throws Exception {
    for (final var fsync : new boolean[] {false, true}) {
      wowCache.delete();
      createNewCache(fsync);
      final var id = wowCache.bookFileId("forceOrder.tst");
      wowCache.addFile("forceOrder.tst", id, true);
      wowCache.pauseBackgroundFlush();
      final var registry = WOWCache.class.getDeclaredField("nonDurableFileIds");
      registry.setAccessible(true);
      registry.set(wowCache, new IntOpenHashSet());
      final var events = new ArrayList<String>();
      try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
        for (final var name : new String[] {"non_durable_files.cm",
            "non_durable_files_shadow.cm"}) {
          final var path = storagePath.resolve(name);
          channels.when(() -> FileChannel.open(path, StandardOpenOption.WRITE))
              .thenAnswer(invocation -> {
                final var real = (FileChannel) invocation.callRealMethod();
                final var delegate = mock(FileChannel.class, delegatesTo(real));
                doAnswer(write -> {
                  events.add(name + ":write");
                  return real.write(write.getArgument(0), (long) write.getArgument(1));
                }).when(delegate).write(any(ByteBuffer.class),
                    org.mockito.ArgumentMatchers.anyLong());
                doAnswer(truncate -> {
                  events.add(name + ":truncate");
                  assertEquals(16L, (long) truncate.getArgument(0));
                  return real.truncate(truncate.getArgument(0));
                }).when(delegate).truncate(org.mockito.ArgumentMatchers.anyLong());
                doAnswer(force -> {
                  events.add(name + ":force");
                  assertEquals("force must follow the exact-length truncation", 16, real.size());
                  assertEquals(Boolean.TRUE, force.getArgument(0));
                  real.force(true);
                  return null;
                }).when(delegate).force(org.mockito.ArgumentMatchers.anyBoolean());
                return delegate;
              });
        }
        // An in-session rewrite must not use a directory-entry operation on any platform.
        try (var moves = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
          wowCache.writeNonDurableRegistry();
          moves.verifyNoInteractions();
        }
      }
      final var expected = new ArrayList<String>();
      for (final var name : new String[] {"non_durable_files.cm", "non_durable_files_shadow.cm"}) {
        expected.add(name + ":write");
        expected.add(name + ":truncate");
        if (fsync) {
          expected.add(name + ":force");
        }
      }
      assertEquals("each complete copy must precede the next copy", expected, events);
      assertEmptyRegistryCopies();
    }
  }

  /** A failed missing-primary publication cannot expose an empty primary over a valid shadow. */
  @Test
  public void failedMissingCopyCreationPreservesShadowAndPermitsRetry() throws Exception {
    final var id = wowCache.bookFileId("creationRetry.tst");
    wowCache.addFile("creationRetry.tst", id, true);
    wowCache.close();
    final var primary = storagePath.resolve("non_durable_files.cm");
    final var shadow = storagePath.resolve("non_durable_files_shadow.cm");
    Files.delete(primary);
    final var injected = new IOException("creation publication failed");
    try (var moves = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
      moves.when(() -> FileUtils.durableAtomicMove(any(Path.class), eq(primary), any()))
          .thenThrow(injected);
      assertEquals(injected, assertThrows(IOException.class, () -> createNewCache(true)));
    }
    assertFalse("an incomplete initialization must not hide the shadow", Files.exists(primary));
    assertRegistryContents(shadow, new IntOpenHashSet(new int[] {wowCache.internalFileId(id)}));
    try (var paths = Files.list(storagePath)) {
      assertFalse("failed creation must remove its staging file",
          paths.anyMatch(path -> path.getFileName().toString().startsWith("registry_")));
    }
    createNewCache(true);
    assertTrue(wowCache.isNonDurable(id));
  }

  private void assertEmptyRegistryCopies() throws Exception {
    for (final var name : new String[] {"non_durable_files.cm", "non_durable_files_shadow.cm"}) {
      final var path = storagePath.resolve(name);
      assertRegistryContents(path, new IntOpenHashSet());
      assertEquals("an empty copy has no trailing IDs", 16, Files.size(path));
    }
  }

  private MockedStatic<FileChannel> failInPlaceRegistryWrite(Path target, IOException injected) {
    return failInPlaceRegistryWrite(target, () -> injected);
  }

  private MockedStatic<FileChannel> failInPlaceRegistryWrite(Path target,
      java.util.function.Supplier<IOException> failure) {
    final var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS);
    channels.when(() -> FileChannel.open(target, StandardOpenOption.WRITE))
        .thenAnswer(invocation -> {
          try (var channel = (FileChannel) invocation.callRealMethod()) {
            assertTrue("injection must overwrite an existing copy", channel.size() >= 2);
            channel.write(ByteBuffer.wrap(new byte[] {1, 2}), 0);
            channel.force(true);
          }
          throw failure.get();
        });
    return channels;
  }

  private void assertRegistryContents(Path path, IntOpenHashSet expected) throws Exception {
    final var reader = WOWCache.class.getDeclaredMethod("readNonDurableRegistryFile", Path.class);
    reader.setAccessible(true);
    assertEquals("registry must have a valid checksum and the exact expected IDs: " + path,
        expected, reader.invoke(wowCache, path));
  }

  /** A real eviction-latch interrupt closes an opened unregistered file and permits retry. */
  @Test
  public void interruptedLoadAdmissionClosesHandleAndPermitsRetry() throws Exception {
    final var id = wowCache.addFile("admissionLoad.tst");
    wowCache.close();
    final var opened = new AtomicReference<File>();
    final var admission = spy(new ClosableLinkedContainer<Long, File>(1024));
    doAnswer(invocation -> {
      final File file = invocation.getArgument(1);
      assertTrue("admission must see an already open file", file.isOpen());
      opened.set(file);
      final var latch = ClosableLinkedContainer.class.getDeclaredField("openLatch");
      latch.setAccessible(true);
      @SuppressWarnings("unchecked")
      final var reference = (AtomicReference<CountDownLatch>) latch.get(admission);
      reference.set(new CountDownLatch(1));
      Thread.currentThread().interrupt();
      return invocation.callRealMethod();
    }).when(admission).add(any(), any());
    try {
      assertThrows(java.lang.InterruptedException.class,
          () -> createNewCache(false, new DoubleWriteLogNoOP(), admission));
      assertNotNull(opened.get());
      assertFalse("failed admission must close the handle", opened.get().isOpen());
      assertEquals(null, files.get(id));
      assertTrue("failed open must preserve interruption", Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    createNewCache();
    assertTrue("retry must open the registered data file", files.get(id).isOpen());
  }

  /** An unchecked append fails as IO, preserves its cause and blocks every subsequent append. */
  @Test
  public void runtimeAppendFailureStopsWritersAndPreservesCause() throws Exception {
    wowCache = spy(wowCache);
    final var injected = new IllegalStateException("append failed");
    doThrow(injected).when(wowCache).appendNameIdEntry(any(),
        org.mockito.ArgumentMatchers.anyBoolean());
    final var failure = assertThrows(IOException.class, () -> wowCache.addFile("runtimeFail.tst"));
    assertEquals(injected, failure.getCause());
    assertThrows(IOException.class, () -> wowCache.addFile("blockedRuntime.tst"));
    assertTrue(mapSnapshot("nameIdMap").isEmpty());
    verify(wowCache).appendNameIdEntry(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  /** An Error from an append remains an Error rather than becoming a checked exception. */
  @Test
  public void appendErrorIsNotConvertedToIOException() throws Exception {
    wowCache = spy(wowCache);
    final var injected = new AssertionError("append error");
    doThrow(injected).when(wowCache).appendNameIdEntry(any(),
        org.mockito.ArgumentMatchers.anyBoolean());
    assertEquals(injected,
        assertThrows(AssertionError.class, () -> wowCache.addFile("errorFail.tst")));
    assertThrows(IOException.class, () -> wowCache.addFile("blockedError.tst"));
  }

  /** A dirty real channel closes on rejected admission even when synchronization fails. */
  @Test
  public void failedAdmissionClosesDirtyChannelWithoutSynchronizing() throws Exception {
    final var id = wowCache.addFile("dirtyAdmission.tst");
    final var path = storagePath.resolve(wowCache.nativeFileNameById(id));
    wowCache.close();
    Files.write(path, new byte[0]);
    final var opened = new AtomicReference<AsynchronousFileChannel>();
    final var admission = spy(new ClosableLinkedContainer<Long, File>(1024));
    final var original = new java.lang.InterruptedException("admission rejected");
    doAnswer(invocation -> {
      final File file = invocation.getArgument(1);
      final var channelField = file.getClass().getDeclaredField("fileChannel");
      channelField.setAccessible(true);
      final var real = (AsynchronousFileChannel) channelField.get(file);
      opened.set(real);
      final var failing = mock(AsynchronousFileChannel.class, delegatesTo(real));
      doThrow(new IOException("dirty header force rejected")).when(failing).force(true);
      channelField.set(file, failing);
      assertTrue("short-file initialization must dirty the header",
          file.needsSynchronizationOnClose());
      assertThrows(StorageException.class, file::synch);
      throw original;
    }).when(admission).add(any(), any());
    try {
      assertEquals(original, assertThrows(java.lang.InterruptedException.class,
          () -> createNewCache(true, new DoubleWriteLogNoOP(), admission)));
      assertNotNull(opened.get());
      assertFalse("the real channel must close despite its failing sync", opened.get().isOpen());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
      if (opened.get() != null && opened.get().isOpen()) {
        opened.get().close();
      }
    }
    createNewCache();
    assertTrue(files.get(id).isOpen());
  }

  /** A reconciliation force failure closes an admitted dirty channel without retrying its sync. */
  @Test
  public void failedOpenClosesAdmittedDirtyChannelWithoutSynchronizing() throws Exception {
    assertFailedOpenClosesAdmittedDirtyChannel(false);
  }

  /** Failed-open cleanup retains the open error, suppresses close errors and restores interruption. */
  @Test
  public void failedOpenSuppressesAdmittedChannelCloseFailureAndKeepsInterrupt() throws Exception {
    assertFailedOpenClosesAdmittedDirtyChannel(true);
  }

  private void assertFailedOpenClosesAdmittedDirtyChannel(boolean failClose) throws Exception {
    final var main = storagePath.resolve("non_durable_files.cm");
    final var backup = storagePath.resolve("non_durable_files_shadow.cm");
    final var empty = Files.readAllBytes(main);
    final var id = wowCache.bookFileId("dirtyFailedOpen.tst");
    wowCache.addFile("dirtyFailedOpen.tst", id, true);
    final var path = storagePath.resolve(wowCache.nativeFileNameById(id));
    wowCache.close();
    Files.write(main, empty);
    Files.write(path, new byte[0]);
    final var realChannel = new AtomicReference<AsynchronousFileChannel>();
    final var trackedChannel = new AtomicReference<AsynchronousFileChannel>();
    final var admittedFile = new AtomicReference<File>();
    final var admission = spy(new ClosableLinkedContainer<Long, File>(1024));
    final var closeFailure = new IOException("admitted channel close failed");
    final var closeCalls = new AtomicInteger();
    doAnswer(invocation -> {
      invocation.callRealMethod();
      final File file = invocation.getArgument(1);
      admittedFile.set(file);
      assertEquals("the file must be admitted before open fails", file, admission.get(id));
      final var channelField = file.getClass().getDeclaredField("fileChannel");
      channelField.setAccessible(true);
      final var real = (AsynchronousFileChannel) channelField.get(file);
      realChannel.set(real);
      final var tracked = mock(AsynchronousFileChannel.class, delegatesTo(real));
      doThrow(new IOException("dirty header force failed")).when(tracked).force(true);
      if (failClose) {
        doAnswer(close -> {
          if (closeCalls.incrementAndGet() == 1) {
            Thread.interrupted();
            throw closeFailure;
          }
          real.close();
          return null;
        }).when(tracked).close();
      }
      trackedChannel.set(tracked);
      channelField.set(file, tracked);
      assertTrue("short-file initialization must dirty the admitted header",
          file.needsSynchronizationOnClose());
      assertThrows("the fixture must reject synchronization", StorageException.class, file::synch);
      return null;
    }).when(admission).add(any(), any());
    final var original = new IOException("reconciliation force failed");
    try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      channels.when(() -> FileChannel.open(backup, StandardOpenOption.WRITE))
          .thenAnswer(invocation -> {
            final var real = (FileChannel) invocation.callRealMethod();
            final var tracked = mock(FileChannel.class, delegatesTo(real));
            doAnswer(force -> {
              assertEquals(admittedFile.get(), admission.get(id));
              if (failClose) {
                Thread.currentThread().interrupt();
              }
              throw original;
            }).when(tracked).force(true);
            return tracked;
          });
      assertEquals(original, assertThrows(IOException.class,
          () -> createNewCache(true, new DoubleWriteLogNoOP(), admission)));
      assertNotNull(realChannel.get());
      assertFalse("failed open must close the admitted real channel", realChannel.get().isOpen());
      assertFalse(admittedFile.get().isOpen());
      assertEquals(null, admission.get(id));
      assertEquals("cleanup must not retry the failing data-file force", 1,
          countInvocations(trackedChannel.get(), "force"));
      if (failClose) {
        org.junit.Assert.assertArrayEquals(new Throwable[] {closeFailure},
            original.getSuppressed());
        assertEquals("no-sync cleanup retries a failed close", 2, closeCalls.get());
        assertTrue("cleanup must restore the original interrupt status",
            Thread.currentThread().isInterrupted());
      } else {
        assertEquals(0, original.getSuppressed().length);
      }
    } finally {
      Thread.interrupted();
      if (realChannel.get() != null && realChannel.get().isOpen()) {
        realChannel.get().close();
      }
    }
    createNewCache(true);
    assertTrue("a retry must reopen the registered data file", files.get(id).isOpen());
    assertFalse(wowCache.isNonDurable(id));
    assertEmptyRegistryCopies();
  }

  /** A rejected load keeps the admission error and suppresses any local handle-close error. */
  @Test
  public void failedAdmissionSuppressesCloseErrorWithoutReplacingOriginalFailure()
      throws Exception {
    final var original = new IllegalStateException("admission rejected");
    final var closeFailure = new IllegalStateException("handle close failed");
    final var file = mock(File.class);
    when(file.isOpen()).thenReturn(true);
    doThrow(closeFailure).when(file).close();
    final var admission = spy(files);
    doThrow(original).when(admission).add(any(), eq(file));
    final var field = WOWCache.class.getDeclaredField("files");
    field.setAccessible(true);
    field.set(wowCache, admission);
    final var register =
        WOWCache.class.getDeclaredMethod("registerOpenFile", long.class, File.class);
    register.setAccessible(true);
    final var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> register.invoke(wowCache, wowCache.externalFileId(123), file));
    assertEquals(original, failure.getCause());
    org.junit.Assert.assertArrayEquals(new Throwable[] {closeFailure}, original.getSuppressed());
    verify(file).close();
  }

  /** A triggered-flush latch interruption keeps the caller interrupted while failing shutdown. */
  @Test
  public void stopFlushLatchWaitPreservesInterrupt() throws Exception {
    wowCache.pauseBackgroundFlush();
    final var field = WOWCache.class.getDeclaredField("triggeredTasks");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var tasks = (java.util.Map<Object, CountDownLatch>) field.get(wowCache);
    tasks.put(new Object(), new CountDownLatch(1));
    final var stop = WOWCache.class.getDeclaredMethod("stopFlush");
    stop.setAccessible(true);
    try {
      Thread.currentThread().interrupt();
      final var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> stop.invoke(wowCache));
      assertTrue(failure.getCause().getCause() instanceof java.lang.InterruptedException);
      assertTrue("latch wait must restore interrupt status",
          Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
      tasks.clear();
    }
  }

  /** Rename's -1 tombstone must not remove a different file's positive ID or durability flag. */
  @Test
  public void crashReopenOfRenameTombstoneKeepsUnrelatedPositiveId() throws Exception {
    final var first = wowCache.externalFileId(1);
    wowCache.addFile("unrelatedOne.tst", first, true);
    final var renamed = wowCache.externalFileId(2);
    wowCache.addFile("renameTwo.tst", renamed);
    wowCache.renameFile(renamed, "renamedTwo.tst");
    crashReopenCache();
    assertEquals("unrelatedOne.tst", wowCache.fileNameById(first));
    assertTrue(wowCache.isNonDurable(first));
    assertEquals(renamed, wowCache.fileIdByName("renamedTwo.tst"));
  }

  /** Release channels without whole-cache close, so reopening reads the original append log. */
  private void crashReopenCache() throws Exception {
    wowCache.pauseBackgroundFlush();
    for (final var fileId : wowCache.files().values()) {
      files.remove(fileId).close();
    }
    createNewCache();
  }

  // --- Crash recovery deletion tests ---

  /** A map append failure during recovery must fail the call before it can return a skip set. */
  @Test
  public void recoveryMapAppendFailurePropagatesAndStopsFurtherDeletion() throws Exception {
    final var first = wowCache.bookFileId("recoveryAppendOne.tst");
    wowCache.addFile("recoveryAppendOne.tst", first, true);
    final var second = wowCache.bookFileId("recoveryAppendTwo.tst");
    wowCache.addFile("recoveryAppendTwo.tst", second, true);
    failAppend(1, () -> {
    });
    final var readCache = spy(createReadCache());
    final var failure = assertThrows(IOException.class,
        () -> wowCache.deleteNonDurableFilesOnRecovery(readCache));
    assertTrue(failure.getCause() instanceof IOException);
    assertTrue(failure.getCause().getMessage().contains("injected map append failure"));
    assertEquals("recovery must stop at the failed append", 1,
        countInvocations(readCache, "deleteFile"));
    assertTrue("the next non-durable file must not be deleted",
        wowCache.exists(first) || wowCache.exists(second));
    assertThrows(IOException.class, () -> wowCache.addFile("blockedRecovery.tst"));
    final var field = WOWCache.class.getDeclaredField("suppressNonDurableRegistryPersist");
    field.setAccessible(true);
    assertFalse("failure must clear the recovery batching flag", field.getBoolean(wowCache));
  }

  /** A failed final recovery registry write must fail recovery and stop later disk writers. */
  @Test
  public void recoveryBatchRegistryFailurePropagatesAndStopsWriters() throws Exception {
    final var id = wowCache.bookFileId("recoveryRegistry.tst");
    wowCache.addFile("recoveryRegistry.tst", id, true);
    wowCache.pauseBackgroundFlush();
    wowCache = spy(wowCache);
    final var failure = new IOException("injected recovery registry failure");
    doThrow(failure).when(wowCache).writeNonDurableRegistry();
    final var readCache = createReadCache();
    assertEquals(failure, assertThrows(IOException.class,
        () -> wowCache.deleteNonDurableFilesOnRecovery(readCache)));
    assertFalse(wowCache.exists(id));
    assertThrows(IOException.class, () -> wowCache.addFile("recoveryRegistry.tst"));
  }

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

      // Recovery retains valid empty copies before a later file ID can be reused.
      assertEmptyRegistryCopies();
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

      assertEmptyRegistryCopies();
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

      assertEmptyRegistryCopies();
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
    // Extend the file to the target pageIndex via the total loadOrAdd primitive;
    // release the temporary referrer immediately since the subsequent load() bumps its own.
    wowCache.loadOrAdd(fileId, pageIndex, false).decrementReadersReferrer();
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
