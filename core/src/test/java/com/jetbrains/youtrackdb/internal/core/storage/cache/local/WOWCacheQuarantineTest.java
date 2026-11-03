package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Exercises the inactive quarantine protocol with real files and injected persistence failures. */
@Category(SequentialTest.class)
public class WOWCacheQuarantineTest {
  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;

  private static Object savedExclusive;
  private static Object savedLock;
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
    savedExclusive = GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.getValue();
    savedLock = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var buildDirectory = System.getProperty("buildDirectory", ".");
    storageName = "WOWCacheQuarantineTest";
    storagePath = Paths.get(buildDirectory).resolve(storageName);
  }

  @AfterClass
  public static void afterClass() {
    bufferPool.clear();
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(savedExclusive);
    GlobalConfiguration.FILE_LOCK.setValue(savedLock);
  }

  @Before
  public void setUp() throws Exception {
    cleanUp();

    Files.createDirectories(storagePath);
    files = spy(new ClosableLinkedContainer<>(1024));

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
            true,
            asyncFileExecutor);
    wowCache.loadRegisteredFiles();
    wowCache.pauseBackgroundFlush();
    wowCache = spy(wowCache);
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

  private final ReadCache readCache = mock(ReadCache.class);

  @Test
  public void readSweepAndWritePurgeFinishBeforeAnyClose() throws Exception {
    final var realReadCache =
        new com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache(
            bufferPool, PAGE_SIZE * 100L, PAGE_SIZE);
    final long first = wowCache.addFile("purge1.tst");
    final long second = wowCache.addFile("purge2.tst");
    final long retained = wowCache.addFile("retained.tst");
    for (long fileId : Set.of(first, second, retained)) {
      final var entry = realReadCache.loadOrAddForWrite(fileId, 0, wowCache, false,
          new com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber(
              0, 0));
      realReadCache.releaseFromWrite(entry, wowCache, true);
    }
    doAnswer(call -> {
      final var field = WOWCache.class.getDeclaredField("writeCachePages");
      field.setAccessible(true);
      final var pages = (java.util.Map<?, ?>) field.get(wowCache);
      for (final var key : pages.keySet()) {
        final int internalId = ((PageKey) key).fileId;
        assertFalse(internalId == (int) first || internalId == (int) second);
      }
      return call.callRealMethod();
    }).when(wowCache).moveQuarantineFile(anyInt(), any());
    try {
      wowCache.quarantineFiles(Set.of(first, second), realReadCache);
      assertNotNull(files.get(retained));
    } finally {
      realReadCache.clearFiles(Set.of(retained), wowCache);
    }
  }

  @Test
  public void interruptDuringEveryExecutorWaitCompletesAndRestoresFlag() throws Exception {
    final Thread caller = Thread.currentThread();
    final long first = wowCache.addFile("wait1.tst");
    final long second = wowCache.addFile("wait2.tst");
    doAnswer(call -> {
      interruptWaitingCaller(caller);
      return call.callRealMethod();
    }).when(wowCache).purgeQuarantinePages(any());
    doAnswer(call -> {
      interruptWaitingCaller(caller);
      return call.callRealMethod();
    }).when(wowCache).moveQuarantineFile(anyInt(), any());
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      return call.callRealMethod();
    }).when(wowCache).syncQuarantineDirectory(any());
    try {
      wowCache.quarantineFiles(Set.of(first, second), readCache);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(wowCache.files().isEmpty());
    } finally {
      Thread.interrupted();
    }
  }

  /** A completed move future must drain a pending interrupt before the next task is awaited. */
  @Test
  public void completedMoveWaitDrainsInterruptAndOperationRestoresIt() throws Exception {
    final long fileId = wowCache.addFile("completed-wait.tst");
    final var waits = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> {
      if (waits.incrementAndGet() == 2) {
        // Complete the move first, then enter the real wait with the caller interrupted.
        final java.util.concurrent.Future<?> future = call.getArgument(0);
        future.get(10, TimeUnit.SECONDS);
        Thread.currentThread().interrupt();
        final Object result = call.callRealMethod();
        assertFalse(Thread.currentThread().isInterrupted());
        final boolean[] recorded = call.getArgument(1);
        assertTrue(recorded[0]);
        return result;
      }
      return call.callRealMethod();
    }).when(wowCache).awaitQuarantineTask(any(), any());
    try {
      wowCache.quarantineFiles(Set.of(fileId), readCache);
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(3, waits.get());
      assertTrue(wowCache.files().isEmpty());
    } finally {
      Thread.interrupted();
    }
  }

  /** Interrupting the caller during executor persistence cannot abort any channel write or force. */
  @Test
  public void callerInterruptDuringPersistenceCompletesAndRestoresFlag() throws Exception {
    final Thread caller = Thread.currentThread();
    final long fileId = wowCache.addFile("persistence-interrupt.tst",
        wowCache.bookFileId("persistence-interrupt.tst"), true);
    final var syncs = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> {
      assertFalse(caller == Thread.currentThread());
      assertFalse(Thread.currentThread().isInterrupted());
      if (syncs.incrementAndGet() == 1) {
        // Gate persistence until the caller is waiting for this task, then interrupt that wait.
        interruptWaitingCaller(caller);
      }
      return call.callRealMethod();
    }).when(wowCache).syncQuarantineDirectory(any());
    doAnswer(call -> {
      assertFalse(caller == Thread.currentThread());
      assertFalse(Thread.currentThread().isInterrupted());
      return call.callRealMethod();
    }).when(wowCache).writeNonDurableRegistry();
    doAnswer(call -> {
      assertFalse(caller == Thread.currentThread());
      assertFalse(Thread.currentThread().isInterrupted());
      return call.callRealMethod();
    }).when(wowCache).appendDeletedEntries(anyList());
    try {
      wowCache.quarantineFiles(Set.of(fileId), readCache);
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(3, syncs.get());
      assertTrue(wowCache.files().isEmpty());
      assertFalse(wowCache.isNonDurable(fileId));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void flushAndFsyncBetweenCandidatesSeeCoherentContainerAndMap() throws Exception {
    final long first = wowCache.addFile("between1.tst");
    final long second = wowCache.addFile("between2.tst");
    final var executorMethod = WOWCache.class.getDeclaredMethod("commitExecutor");
    executorMethod.setAccessible(true);
    final var executor = (ExecutorService) executorMethod.invoke(null);
    final var fsyncMethod = WOWCache.class.getDeclaredMethod("fsyncFiles");
    fsyncMethod.setAccessible(true);
    final var checks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
    doAnswer(call -> {
      final Object result = call.callRealMethod();
      checks.add(executor.submit(() -> {
        try {
          final var live = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
          // Executor code must not call files(), which would acquire filesLock.
          for (long fileId : Set.of(first, second)) {
            if (files.get(fileId) != null) {
              live.add((int) fileId);
            }
          }
          wowCache.executeFileFlush(live);
          fsyncMethod.invoke(wowCache);
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      }));
      return result;
    }).when(wowCache).moveQuarantineFile(anyInt(), any());
    wowCache.quarantineFiles(Set.of(first, second), readCache);
    for (final var check : checks) {
      check.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void uncleanSnapshotsAtPersistenceBoundariesReopenMissingFilesAsDeleted()
      throws Exception {
    // Copy live registry bytes without closing the source cache. This models process loss, not
    // power loss, at each boundary. The copied directory has the post-rename missing source file.
    for (int boundary = 0; boundary < 6; boundary++) {
      reset(wowCache);
      final long fileId = wowCache.addFile("crash" + boundary + ".tst");
      final Path snapshot = Files.createDirectory(storagePath.resolve("snapshot" + boundary));
      final Runnable copy = () -> {
        try (var paths = Files.list(storagePath)) {
          for (final var path : paths.filter(Files::isRegularFile).toList()) {
            Files.copy(path, snapshot.resolve(path.getFileName()));
          }
        } catch (IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
      };
      if (boundary < 3) {
        final int selected = boundary + 1;
        final var count = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
          if (count.incrementAndGet() == selected) {
            copy.run();
            throw new IOException("crash snapshot");
          }
          return call.callRealMethod();
        }).when(wowCache).syncQuarantineDirectory(any());
      } else {
        final int selected = boundary;
        doAnswer(call -> {
          final boolean force = call.getArgument(2);
          if ((selected == 3 && force) || (selected == 5 && !force)) {
            copy.run();
            throw new IOException("crash snapshot");
          }
          final Object result = call.callRealMethod();
          if (selected == 4 && force) {
            copy.run();
            throw new IOException("crash snapshot after main force");
          }
          return result;
        }).when(wowCache).appendMapBatch(any(), anyList(), anyBoolean());
      }
      assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
      final var reopened = new WOWCache(PAGE_SIZE, false, bufferPool, writeAheadLog,
          new DoubleWriteLogNoOP(), PAGES_FLUSH_INTERVAL, SHUTDOWN_TIMEOUT,
          EXCLUSIVE_WRITE_CACHE_MAX_SIZE, snapshot, storageName,
          new ClosableLinkedContainer<Long, File>(1024), 2,
          ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, ChecksumMode.StoreAndVerify,
          null, null, false, asyncFileExecutor);
      try {
        reopened.loadRegisteredFiles();
        assertFalse(reopened.files().containsKey("crash" + boundary + ".tst"));
      } finally {
        reopened.delete();
      }
      // Use a new instance after a latched registry failure.
      cleanUp();
      setUp();
    }
  }

  private static void interruptWaitingCaller(final Thread caller) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (caller.getState() != Thread.State.WAITING) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Caller did not enter the executor wait");
      }
      Thread.yield();
    }
    caller.interrupt();
  }

  @Test
  public void restoreFailureAfterRenameFailureFailsOperation() throws Exception {
    final long fileId = wowCache.addFile("restore-failure.tst");
    doThrow(new IOException("rename")).when(wowCache).renameQuarantineFile(any(), any());
    doThrow(new IllegalStateException("restore")).when(files).acquire(fileId);
    assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
  }

  @Test
  public void backupAppendFailureAlsoLatchesRegistryWrites() throws Exception {
    final long fileId = wowCache.addFile("backup-failure.tst");
    doThrow(new IOException("backup append")).when(wowCache)
        .appendMapBatch(any(), anyList(), eq(false));
    assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
    assertThrows(IOException.class, () -> wowCache.addFile("blocked.tst"));
  }

  @Test
  public void mainMapForceFailureLatchesRegistryWrites() throws Exception {
    final long fileId = wowCache.addFile("force-failure.tst");
    doAnswer(call -> {
      final Path path = call.getArgument(0);
      final var realChannel = java.nio.channels.FileChannel.open(
          path, java.nio.file.StandardOpenOption.WRITE);
      final var channel = mock(java.nio.channels.FileChannel.class,
          org.mockito.AdditionalAnswers.delegatesTo(realChannel));
      doThrow(new IOException("injected force")).when(channel).force(true);
      try (var channels = org.mockito.Mockito.mockStatic(java.nio.channels.FileChannel.class)) {
        channels.when(() -> java.nio.channels.FileChannel.open(
            path, java.nio.file.StandardOpenOption.WRITE)).thenReturn(channel);
        return call.callRealMethod();
      } finally {
        realChannel.close();
      }
    }).when(wowCache).appendMapBatch(any(), anyList(), eq(true));
    assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
    assertThrows(IOException.class, () -> wowCache.addFile("blocked.tst"));
  }

  @Test
  public void interruptFlagIsRestoredWhenPersistenceFails() throws Exception {
    final long fileId = wowCache.addFile("interrupted-failure.tst");
    doThrow(new IOException("sync")).when(wowCache).syncQuarantineDirectory(any());
    Thread.currentThread().interrupt();
    try {
      assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void uncleanSnapshotBeforeNonDurableListWriteReopensAsDeleted() throws Exception {
    final long fileId = wowCache.addFile("list-crash.tst",
        wowCache.bookFileId("list-crash.tst"), true);
    final Path snapshot = Files.createDirectory(storagePath.resolve("list-snapshot"));
    doAnswer(call -> {
      copyLiveFiles(snapshot);
      throw new IOException("crash before list write");
    }).when(wowCache).writeNonDurableRegistry();
    assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
    assertSnapshotDeleted(snapshot, "list-crash.tst");
  }

  @Test
  public void uncleanSnapshotImmediatelyAfterRenameReopensAsDeleted() throws Exception {
    final long fileId = wowCache.addFile("rename-crash.tst");
    final Path snapshot = Files.createDirectory(storagePath.resolve("rename-snapshot"));
    doAnswer(call -> {
      call.callRealMethod();
      copyLiveFiles(snapshot);
      throw new AssertionError("process loss after rename");
    }).when(wowCache).renameQuarantineFile(any(), any());
    assertThrows(AssertionError.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
    assertSnapshotDeleted(snapshot, "rename-crash.tst");
  }

  private void copyLiveFiles(final Path snapshot) throws IOException {
    try (var paths = Files.list(storagePath)) {
      for (final var path : paths.filter(Files::isRegularFile).toList()) {
        Files.copy(path, snapshot.resolve(path.getFileName()));
      }
    }
  }

  private void assertSnapshotDeleted(final Path snapshot, final String logicalName)
      throws Exception {
    final var reopened = new WOWCache(PAGE_SIZE, false, bufferPool, writeAheadLog,
        new DoubleWriteLogNoOP(), PAGES_FLUSH_INTERVAL, SHUTDOWN_TIMEOUT,
        EXCLUSIVE_WRITE_CACHE_MAX_SIZE, snapshot, storageName,
        new ClosableLinkedContainer<Long, File>(1024), 2,
        ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, ChecksumMode.StoreAndVerify,
        null, null, false, asyncFileExecutor);
    try {
      reopened.loadRegisteredFiles();
      assertFalse(reopened.files().containsKey(logicalName));
    } finally {
      reopened.delete();
    }
  }

  @Test
  public void encodeFailureLatchesRegistryWrites() throws Exception {
    assertThrows(IOException.class, () -> wowCache.appendDeletedEntries(
        java.util.Collections.singletonList(null)));
    assertThrows(IOException.class, () -> wowCache.addFile("blocked.tst"));
  }

  @Test
  public void emptySetDoesNotCreateDirectory() throws Exception {
    assertNull(wowCache.quarantineFiles(Set.of(), readCache));
    assertFalse(Files.exists(storagePath.resolve("orphans")));
    verifyNoInteractions(readCache);
  }

  @Test
  public void movesBytesAndClearsNonDurableFlagInBothRegistries() throws Exception {
    final long first = wowCache.addFile("first.tst");
    final long second = wowCache.addFile("second.tst", wowCache.bookFileId("second.tst"), true);
    wowCache.loadOrAdd(first, 0, false).decrementReadersReferrer();
    wowCache.flush(first);
    final String diskName = wowCache.nativeFileNameById(first);
    final byte[] bytes = Files.readAllBytes(storagePath.resolve(diskName));
    clearInvocations(wowCache);
    final Path directory = wowCache.quarantineFiles(Set.of(first, second), readCache);
    assertTrue(directory.getFileName().toString()
        .matches("[0-9]{8}T[0-9]{9}Z-[0-9a-f]{32}"));
    assertArrayEquals(bytes, Files.readAllBytes(directory.resolve(diskName)));
    assertFalse(wowCache.files().containsValue(first));
    assertFalse(wowCache.isNonDurable(second));
    assertNull(files.get(first));
    verify(readCache).clearFiles(Set.of(first, second), wowCache);
    verify(wowCache, times(1)).writeNonDurableRegistry();
    verify(wowCache, times(1)).appendDeletedEntries(anyList());
  }

  @Test
  public void collisionGeneratesAnotherSuffixWithoutReusingDirectory() throws Exception {
    final Path parent = Files.createDirectories(storagePath.resolve("orphans"));
    Files.createDirectory(parent.resolve("collision"));
    doReturn("collision", "new-suffix").when(wowCache).quarantineFolderName();
    final long fileId = wowCache.addFile("collision.tst");
    assertEquals(parent.resolve("new-suffix"),
        wowCache.quarantineFiles(Set.of(fileId), readCache));
    verify(wowCache, times(2)).quarantineFolderName();
  }

  @Test
  public void existingTargetIsNotReplacedAndFailedRenameRestoresReadableHandle() throws Exception {
    final long fileId = wowCache.addFile("restore.tst");
    wowCache.loadOrAdd(fileId, 0, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    doAnswer(call -> {
      final Path target = call.getArgument(1);
      Files.write(target, new byte[] {9, 8, 7});
      return call.callRealMethod();
    }).when(wowCache).renameQuarantineFile(any(), any());
    final Path directory = wowCache.quarantineFiles(Set.of(fileId), readCache);
    assertArrayEquals(new byte[] {9, 8, 7},
        Files.readAllBytes(directory.resolve(wowCache.nativeFileNameById(fileId))));
    assertTrue(wowCache.files().containsValue(fileId));
    final var pointer = wowCache.loadIfPresent(fileId, 0, false);
    assertNotNull(pointer);
    pointer.decrementReadersReferrer();
  }

  @Test
  public void everyDirectorySyncFailurePropagatesAfterRename() throws Exception {
    for (int failAt = 1; failAt <= 3; failAt++) {
      reset(wowCache);
      final int selected = failAt;
      final var count = new java.util.concurrent.atomic.AtomicInteger();
      doAnswer(call -> {
        if (count.incrementAndGet() == selected) {
          throw new IOException("injected directory force");
        }
        return call.callRealMethod();
      }).when(wowCache).syncQuarantineDirectory(any());
      final long fileId = wowCache.addFile("sync" + failAt + ".tst");
      final String disk = wowCache.nativeFileNameById(fileId);
      assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
      assertFalse(Files.exists(storagePath.resolve(disk)));
      assertFalse(wowCache.files().containsValue(fileId));
      verify(wowCache, never()).appendDeletedEntries(anyList());
    }
  }

  @Test
  public void nonDurableWriteFailureLatchesRegistryWrites() throws Exception {
    final long fileId = wowCache.addFile("nondurable.tst",
        wowCache.bookFileId("nondurable.tst"), true);
    doThrow(new IOException("injected list write")).when(wowCache).writeNonDurableRegistry();
    assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
    assertFalse(wowCache.isNonDurable(fileId));
    assertThrows(IOException.class, () -> wowCache.addFile("blocked.tst"));
  }

  @Test
  public void batchAppendFailureLatchesRegistryWrites() throws Exception {
    final long fileId = wowCache.addFile("append.tst");
    doThrow(new IOException("injected append")).when(wowCache)
        .appendMapBatch(any(), anyList(), eq(true));
    assertThrows(IOException.class, () -> wowCache.quarantineFiles(Set.of(fileId), readCache));
    assertThrows(IOException.class, () -> wowCache.addFile("blocked.tst"));
  }

  @Test
  public void preexistingInterruptIsMaskedThroughPersistenceAndRestored() throws Exception {
    final long fileId = wowCache.addFile("interrupt.tst");
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      return call.callRealMethod();
    }).when(wowCache).syncQuarantineDirectory(any());
    Thread.currentThread().interrupt();
    try {
      wowCache.quarantineFiles(Set.of(fileId), readCache);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
