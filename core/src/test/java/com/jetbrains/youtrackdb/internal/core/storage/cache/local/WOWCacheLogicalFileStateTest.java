package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.RecoveryFilePermission;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionStateV2;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.function.ThrowingRunnable;

public class WOWCacheLogicalFileStateTest {

  // Growth and metadata refresh after token capture must survive completion of a shorter prefix.
  @Test
  public void completionMergesGrowthAndCurrentMetadataWithinOneLifetime() throws Exception {
    var table = new LogicalPageFileTable();
    var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9080);
    var name = PageFileNames.layout28(identity);
    int id = identity.internalId();
    var dependency =
        new com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber(
            7, 42);
    table.register(name, id, false, 5, 0, false, dependency);
    var captured = table.live(id);
    var capturedLatch = new java.util.concurrent.CountDownLatch(1);
    var refreshed = new java.util.concurrent.CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var completion = executor.submit(() -> {
        capturedLatch.countDown();
        assertTrue(refreshed.await(10, TimeUnit.SECONDS));
        assertTrue(table.isCurrent(id, captured.extents()));
        return table.publishCompleted(id, captured.extents(), 3);
      });
      assertTrue(capturedLatch.await(10, TimeUnit.SECONDS));
      captured.extents().allocateThrough(11);
      table.register(name, id, true);
      refreshed.countDown();
      assertTrue(completion.get(10, TimeUnit.SECONDS));
      var current = table.live(id);
      assertEquals(11, current.logicalExtent());
      assertEquals(3, current.diskExtent());
      assertTrue(current.diskPresent());
      assertTrue(current.nonDurable());
      org.junit.Assert.assertSame(dependency, current.creationLsn());
      org.junit.Assert.assertSame(captured.extents(), current.extents());
      assertTrue(table.publishCompleted(id, captured.extents(), 2));
      assertEquals(3, table.live(id).diskExtent());
      table.register(name, id, false);
      assertTrue(table.live(id).diskPresent());
      assertFalse(table.live(id).nonDurable());
      org.junit.Assert.assertSame(dependency, table.live(id).creationLsn());
    } finally {
      refreshed.countDown();
      executor.shutdownNow();
    }
  }

  // Refresh attempts the same entry after publication reads it. It must either wait for the
  // atomic update or finish before a broken get-then-put publisher overwrites its metadata.
  @Test
  public void completionSerializesRefreshInsideItsReadWriteWindow() throws Exception {
    var table = new LogicalPageFileTable();
    var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9082);
    var name = PageFileNames.layout28(identity);
    int id = identity.internalId();
    table.register(name, id, false, 5, 0, false);
    var token = table.live(id).extents();
    var startRefresh = new java.util.concurrent.CountDownLatch(1);
    var finished = new java.util.concurrent.CountDownLatch(1);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var refresher = new Thread(() -> {
      try {
        assertTrue(startRefresh.await(10, TimeUnit.SECONDS));
        token.allocateThrough(11);
        table.register(name, id, true);
      } catch (Throwable thrown) {
        failure.set(thrown);
      } finally {
        finished.countDown();
      }
    }, "logical-file-refresh");
    refresher.start();
    try {
      assertTrue(table.publishCompleted(id, token, 3, () -> {
        startRefresh.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (finished.getCount() != 0 && refresher.getState() != Thread.State.BLOCKED) {
          assertTrue("refresh must reach the update window", System.nanoTime() < deadline);
          Thread.onSpinWait();
        }
      }));
      assertTrue(finished.await(10, TimeUnit.SECONDS));
      assertNull(failure.get());
      assertEquals(11, table.live(id).logicalExtent());
      assertTrue(table.live(id).nonDurable());
      assertTrue(table.live(id).diskPresent());
      assertEquals(3, table.live(id).diskExtent());
    } finally {
      startRefresh.countDown();
      refresher.join(TimeUnit.SECONDS.toMillis(10));
      assertFalse(refresher.isAlive());
    }
  }

  // Explicit extents refresh keeps published presence and the same lifetime, even at prefix zero.
  @Test
  public void explicitRefreshCannotUndoPublishedDiskPresence() {
    var table = new LogicalPageFileTable();
    var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9083);
    var name = PageFileNames.layout28(identity);
    int id = identity.internalId();
    table.register(name, id, false, 5, 0, false);
    var token = table.live(id).extents();
    assertTrue(table.publishCompleted(id, token, 0));
    table.register(name, id, true, 0, 0, false);
    assertTrue(table.live(id).diskPresent());
    org.junit.Assert.assertSame(token, table.live(id).extents());
  }

  // Every terminal transition invalidates queued work before its simulated creation action.
  @Test
  public void staleLifetimeCannotCreateOrPublishIntoReplacement() {
    for (int transition = 0; transition < 3; transition++) {
      var table = new LogicalPageFileTable();
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9081);
      var name = PageFileNames.layout28(identity);
      int id = identity.internalId();
      table.register(name, id, false, 8, 0, false);
      var token = table.live(id).extents();
      switch (transition) {
        case 0 -> table.delete(name, id);
        case 1 -> table.forget(id);
        default -> table.clear();
      }
      assertFalse(table.isCurrent(id, token));
      assertFalse(table.publishCompleted(id, token, 8));
      table.register(name, id, true, 2, 0, false);
      var replacement = table.live(id);
      var creates = new java.util.concurrent.atomic.AtomicInteger();
      if (table.isCurrent(id, token)) {
        creates.incrementAndGet();
      }
      assertEquals(0, creates.get());
      assertFalse(table.publishCompleted(id, token, 8));
      org.junit.Assert.assertSame(replacement, table.live(id));
      assertFalse(replacement.diskPresent());
      assertEquals(0, replacement.diskExtent());
    }
  }

  // Actual eager, replay and restore creation notify once, before the header exists.
  // Booking, misses, existing-file admission, repeated resolution and reopen never notify.
  @Test
  public void creationRosterNotifiesOnlyForNewDirectoryEntries() throws Exception {
    withStandaloneCache(cache -> {
      var notifications = new java.util.ArrayList<Integer>();
      try (var maps = StorageBirthTestSupport.poisonSessionMaps(cache);
          var observer = cache.observePhysicalCreation(id -> {
            notifications.add(id);
            var identity = PageFileIdentity.decode(id);
            try {
              assertEquals(0, Files.size(cache.getRootDirectory()
                  .resolve(PageFileNames.layout28DiskName(identity))));
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          })) {
        for (int route = 0; route < 3; route++) {
          var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9082 + route);
          var name = PageFileNames.layout28(identity);
          long id = cache.externalFileId(identity.internalId());
          cache.bookFileId(name, id, false);
          assertEquals(route, notifications.size());
          assertFalse(cache.admitExpectedFile(identity));
          assertFalse(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.OPEN_ONLY));
          if (route == 0) {
            cache.addFile(name, id);
            assertEquals(route, notifications.size());
            assertPending(cache, identity, false);
            storeAndFlushPage(cache, id, 123);
          } else {
            cache.resolveRecoveryFile(name, id, route == 1
                ? RecoveryFilePermission.REPLAY_CREATE : RecoveryFilePermission.RESTORE);
          }
          assertEquals(route + 1, notifications.size());
          assertEquals(Integer.valueOf(identity.internalId()), notifications.get(route));
          assertTrue(cache.admitExpectedFile(identity));
          cache.resolveRecoveryFile(name, id, RecoveryFilePermission.REPLAY_CREATE);
          assertThrows(StorageException.class, () -> cache.addFile(name, id));
          assertEquals(route + 1, notifications.size());
        }
        cache.close();
        cache.loadRegisteredFiles();
        assertTrue(cache.admitExpectedFile(
            new PageFileIdentity(PageFileRole.RECORD_DATA, 9082)));
        assertEquals(3, notifications.size());
      }
    });
  }

  // Every map variant can be unreadable without affecting layout 28 open or close.
  // No mutation runs between the two lifecycle operations.
  @Test
  public void openAndCloseNeverAccessAnyNameIdMapVariant() throws Exception {
    withStandaloneCache(cache -> {
      var field = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
      field.setAccessible(true);
      var directory = ((Path) field.get(cache)).getParent();
      cache.close();
      var maps = List.of("name_id_map.cm", "name_id_map_v2.cm", "name_id_map_v3.cm",
          "name_id_map_v2_backup.cm", "name_id_map_v3_t.cm");
      for (var map : maps) {
        Files.createDirectory(directory.resolve(map));
      }
      cache.loadRegisteredFiles();
      assertTrue(cache.files().isEmpty());
      for (var map : maps) {
        assertTrue(Files.isDirectory(directory.resolve(map)));
      }
      cache.close();
      for (var map : maps) {
        assertTrue(Files.isDirectory(directory.resolve(map)));
        Files.delete(directory.resolve(map));
      }
      cache.loadRegisteredFiles();
    });
  }

  // Explicit stale map evidence survives cold open without installing an unknown file.
  @Test
  public void staleCanonicalMapIsIgnoredAndCreationLeavesMapAbsent() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9070);
      var name = PageFileNames.layout28(identity);
      long id = cache.addFile(name);
      assertPending(cache, identity, false);
      assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.RESTORE));
      var map = cache.getRootDirectory().resolve("name_id_map_v3.cm");
      assertFalse(Files.exists(map));
      StorageBirthTestSupport.seedNameIdMap(cache, name, identity.internalId(),
          PageFileNames.layout28DiskName(identity));
      var bytes = Files.readAllBytes(map);
      cache.close();
      cache.loadRegisteredFiles();
      assertAbsent(cache, identity);
      var handlesField = WOWCache.class.getDeclaredField("files");
      handlesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var handles = (ClosableLinkedContainer<Long, ?>) handlesField.get(cache);
      assertNull(handles.get(id));
      org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(map));
      Files.delete(map);
      Files.createDirectory(map);
      try (var ignored = StorageBirthTestSupport.poisonSessionMaps(cache)) {
        assertTrue(cache.admitExpectedFile(identity));
        var created = new PageFileIdentity(PageFileRole.RECORD_DATA, 9072);
        var createdName = PageFileNames.layout28(created);
        assertEquals(cache.externalFileId(created.internalId()), cache.bookFileId(createdName));
        long createdId = cache.addFile(createdName);
        assertPending(cache, created, false);
        assertTrue(
            cache.resolveRecoveryFile(createdName, createdId, RecoveryFilePermission.RESTORE));
        assertLive(cache, created, false);
        assertTrue(Files.exists(cache.getRootDirectory()
            .resolve(PageFileNames.layout28DiskName(created))));
      }
      assertTrue(Files.isDirectory(map));
      Files.delete(map);
    });
  }

  // Both entry kinds reject unadmitted leftovers and paths that appear after booking.
  @Test
  public void freshCreationRefusesExistingPathsWithoutChangingBytes() throws Exception {
    withStandaloneCache(cache -> {
      var map = cache.getRootDirectory().resolve("name_id_map_v3.cm");
      Files.createDirectory(map);
      try (var ignored = StorageBirthTestSupport.poisonSessionMaps(cache)) {
        for (boolean booked : new boolean[] {false, true}) {
          var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, booked ? 9074 : 9073);
          var name = PageFileNames.layout28(identity);
          long id = cache.externalFileId(identity.internalId());
          if (booked) {
            assertEquals(id, cache.bookFileId(name, id, false));
          }
          var disk = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
          byte[] bytes = {1, 4, 9};
          Files.write(disk, bytes);
          assertThrows(StorageException.class, () -> cache.addFile(name, id));
          assertThrows(StorageException.class, () -> cache.addFile(name));
          assertThrows(StorageException.class, () -> cache.bookFileId(name));
          org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(disk));
          assertFalse(cache.exists(id));
          assertEquals(booked, cache.logicalFiles().reserved(identity.internalId()));
        }
      }
      Files.delete(map);
    });
  }

  // Neither booking form may treat a dangling link as an absent layout 28 path.
  @Test
  public void layout28BookingRejectsDanglingLinkWithoutChangingIt() throws Exception {
    withStandaloneCache(cache -> {
      final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9075);
      final var name = PageFileNames.layout28(identity);
      final var id = cache.externalFileId(identity.internalId());
      final var path = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      final var target = Path.of("missing");
      Files.createSymbolicLink(path, target);
      try {
        assertThrows(StorageException.class, () -> cache.bookFileId(name));
        assertThrows(StorageException.class, () -> cache.bookFileId(name, id, false));
        assertNull(cache.registeredFileId(name));
        assertEquals(target, Files.readSymbolicLink(path));
      } finally {
        Files.delete(path);
      }
    });
  }

  // A link that appears after booking must be refused before constructing a file handle.
  @Test
  public void layout28RegistrationRejectsDanglingLinkBeforeConstructingHandle() throws Exception {
    withStandaloneCache(cache -> {
      final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9076);
      final var name = PageFileNames.layout28(identity);
      final var path = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      final var target = Path.of("missing");
      final var registeringCache = spy(cache);
      final var id = registeringCache.bookFileId(name);
      Files.createSymbolicLink(path, target);
      try {
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> registeringCache.addFile(name, id));
        verify(registeringCache, never()).createFileHandle(name, identity.internalId());
        assertEquals(target, Files.readSymbolicLink(path));
        assertEquals(Integer.valueOf(-identity.internalId()), cache.registeredFileId(name));
      } finally {
        Files.delete(path);
      }
    });
  }

  @Test(timeout = 60_000)
  public void layout28RegistrationPreservesAccessDeniedFromPathLookup() throws Exception {
    withStandaloneCache(cache -> {
      final var root = cache.getRootDirectory();
      assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
      final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9077);
      final var name = PageFileNames.layout28(identity);
      final var id = cache.bookFileId(name);
      final var path = root.resolve(PageFileNames.layout28DiskName(identity));
      final var original = Files.getPosixFilePermissions(root);
      final var withoutSearch = new HashSet<>(original);
      withoutSearch.removeAll(List.of(PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
      Files.write(path, new byte[] {42});
      try {
        Files.setPosixFilePermissions(root, withoutSearch);
        boolean denied = false;
        try {
          Files.readAttributes(path, BasicFileAttributes.class);
        } catch (AccessDeniedException expected) {
          denied = true;
        }
        assumeTrue("The test process must not bypass directory search permissions", denied);
        assertThrows(AccessDeniedException.class, () -> cache.addFile(name, id));
        assertNull(cache.logicalFiles().live(identity.internalId()));
      } finally {
        Files.setPosixFilePermissions(root, original);
        Files.delete(path);
      }
    });
  }

  // Exclusive creation refuses a path that wins the race, without opening or shrinking it.
  @Test
  public void exclusiveCreateNeverFallsBackToOpeningAnExistingFile() throws Exception {
    var file = mock(com.jetbrains.youtrackdb.internal.core.storage.fs.File.class);
    var failure = new java.nio.file.FileAlreadyExistsException("raced");
    org.mockito.Mockito.doThrow(failure).when(file).create();
    var create = WOWCache.class.getDeclaredMethod("createFile",
        com.jetbrains.youtrackdb.internal.core.storage.fs.File.class,
        boolean.class, boolean.class);
    create.setAccessible(true);
    var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> create.invoke(null, file, true, true));
    org.junit.Assert.assertSame(failure, thrown.getCause());
    verify(file, never()).exists();
    verify(file, never()).open();
    verify(file, never()).shrink(0);
  }

  // A handle-free tombstone rejects strict booking. Reconstruction owns only its new reservation.
  @Test
  public void releasedReconstructionDoesNotRestoreDeletedStateOrRemoveLiveState() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.FREE_SPACE_MAP, 9075);
      var name = PageFileNames.layout28(identity);
      long id = cache.externalFileId(identity.internalId());
      cache.deleteFile(id);
      assertDeleted(cache, identity);
      assertThrows(StorageException.class, () -> cache.bookFileId(name, id, false));
      var owner = cache.bookFileIdWithOwnership(name, id, true);
      var borrower = cache.bookFileIdWithOwnership(name, id, true);
      borrower.release().run();
      assertReserved(cache, identity);
      owner.release().run();
      assertAbsent(cache, identity);
      var next = cache.bookFileIdWithOwnership(name, null, false);
      owner.release().run();
      assertReserved(cache, identity);
      cache.addFile(name);
      next.release().run();
      assertPending(cache, identity, false);
    });
  }

  // Expected-file admission opens only the computed path and never appends map evidence.
  // A second pass preserves allocation state. Missing paths neither create nor publish files.
  @Test
  public void expectedAdmissionIsMapFreePresentOnlyAndIdempotent() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9040);
      var absent = new PageFileIdentity(PageFileRole.RECORD_DATA, 9041);
      var name = PageFileNames.layout28(identity);
      var id = cache.externalFileId(identity.internalId());
      var pathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
      pathField.setAccessible(true);
      var mapPath = (Path) pathField.get(cache);
      assertFalse(Files.exists(mapPath));
      var disk = mapPath.getParent().resolve(PageFileNames.layout28DiskName(identity));
      var bytes = new byte[1024 + cache.pageSize()];
      java.util.Arrays.fill(bytes, (byte) 17);
      Files.write(disk, bytes);
      assertTrue(cache.admitExpectedFile(identity));
      assertEquals(id, cache.loadFile(name));
      var state = cache.logicalFiles().live(identity.internalId());
      assertEquals(1, state.logicalExtent());
      assertEquals(1, state.diskExtent());
      assertTrue(state.diskPresent());
      state.extents().allocateThrough(3);
      assertTrue(cache.admitExpectedFile(identity));
      assertEquals(3, cache.logicalFiles().live(identity.internalId()).logicalExtent());
      assertFalse(cache.admitExpectedFile(absent));
      assertAbsent(cache, absent);
      assertFalse(Files.exists(mapPath.getParent()
          .resolve(PageFileNames.layout28DiskName(absent))));
      org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(disk));
      assertFalse(Files.exists(mapPath));
      assertOracle(cache);
      cache.close();
      assertFalse(Files.exists(mapPath));
      cache.loadRegisteredFiles();
      // Only the expected identity can supply this file on the second admission.
      assertAbsent(cache, identity);
      assertTrue(cache.admitExpectedFile(identity));
      assertEquals(1, cache.logicalFiles().live(identity.internalId()).logicalExtent());
    });
  }

  // After a cold reopen, expected admission leaves maps empty. Adding the same live identity
  // must fail before truncating its present bytes or publishing map evidence.
  @Test
  public void addFileRejectsAdmittedLiveIdentityWithoutTruncatingBytes() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9043);
      var name = PageFileNames.layout28(identity);
      var id = cache.externalFileId(identity.internalId());
      cache.close();
      cache.loadRegisteredFiles();
      var pathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
      pathField.setAccessible(true);
      var map = (Path) pathField.get(cache);
      var disk = map.getParent().resolve(PageFileNames.layout28DiskName(identity));
      var bytes = new byte[1024 + cache.pageSize()];
      java.util.Arrays.fill(bytes, (byte) 43);
      Files.write(disk, bytes);
      assertTrue(cache.admitExpectedFile(identity));
      assertTrue(snapshot(cache, "nameIdMap").isEmpty());
      var failure = assertThrows(StorageException.class, () -> cache.addFile(name, id));
      assertTrue(failure.getMessage().contains("already exists"));
      org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(disk));
      assertEquals(1, cache.logicalFiles().live(identity.internalId()).logicalExtent());
      assertFalse(Files.exists(map));
    });
  }

  // Session maps cannot redirect admission. Every route preserves present bytes on retry.
  @Test
  public void admissionIgnoresSessionMapsAndSupportsPartialRetry() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9042);
      var pathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
      pathField.setAccessible(true);
      var map = (Path) pathField.get(cache);
      var disk = map.getParent().resolve(PageFileNames.layout28DiskName(identity));
      var bytes = new byte[1024 + cache.pageSize()];
      java.util.Arrays.fill(bytes, (byte) 42);
      Files.write(disk, bytes);
      Files.createDirectory(map);
      for (int pass = 0; pass < 2; pass++) {
        assertAbsent(cache, identity);
        try (var ignored = StorageBirthTestSupport.poisonSessionMaps(cache)) {
          assertTrue(cache.admitExpectedFile(identity));
          for (var permission : RecoveryFilePermission.values()) {
            assertTrue(cache.resolveRecoveryFile(PageFileNames.layout28(identity),
                cache.externalFileId(identity.internalId()), permission));
          }
          assertTrue(cache.admitExpectedFile(identity));
          var state = cache.logicalFiles().live(identity.internalId());
          assertEquals(identity, state.identity());
          assertTrue(state.diskPresent());
          assertFalse(state.nonDurable());
          assertEquals(1, state.logicalExtent());
          assertEquals(1, state.diskExtent());
          assertOracle(cache);
        }
        org.junit.Assert.assertArrayEquals(bytes, Files.readAllBytes(disk));
        cache.close();
        cache.loadRegisteredFiles();
      }
      Files.delete(map);
    });
  }

  // Missing-file recovery needs explicit authority. Creation and repeat admission use no maps.
  @Test
  public void recoveryCreationRequiresPermissionWithoutMapAccess() throws Exception {
    withStandaloneCache(cache -> {
      var pathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
      pathField.setAccessible(true);
      var map = (Path) pathField.get(cache);
      Files.createDirectory(map);
      for (var permission : RecoveryFilePermission.values()) {
        var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9050 + permission.ordinal());
        var id = cache.externalFileId(identity.internalId());
        var allowed = permission != RecoveryFilePermission.OPEN_ONLY;
        try (var ignored = StorageBirthTestSupport.poisonSessionMaps(cache)) {
          assertEquals(allowed, cache.resolveRecoveryFile(null, id, permission));
          assertEquals(allowed, cache.resolveRecoveryFile(null, id, permission));
          if (allowed) {
            assertLive(cache, identity, false);
          } else {
            assertAbsent(cache, identity);
          }
        }
        assertEquals(allowed,
            Files.exists(map.getParent().resolve(PageFileNames.layout28DiskName(identity))));
      }
      cache.close();
      cache.loadRegisteredFiles();
      var restored = new PageFileIdentity(PageFileRole.RECORD_DATA, 9052);
      assertTrue(cache.admitExpectedFile(restored));
      assertLive(cache, restored, false);
      Files.delete(map);
    });
  }

  // Strict parsing rejects aliases, disk names and mismatched ids without inventing membership.
  @Test
  public void resolverChecksCanonicalNamesAndIdsWithoutDiskAccess() {
    var table = new LogicalPageFileTable();
    var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9000);
    var name = PageFileNames.layout28(identity);
    for (var role : PageFileRole.values()) {
      var candidate = new PageFileIdentity(role, 9000);
      assertEquals(candidate, LogicalPageFileTable.resolve(
          PageFileNames.layout28(candidate), candidate.internalId()));
    }
    assertThrows(IllegalArgumentException.class,
        () -> LogicalPageFileTable.resolve(name, identity.internalId() + 1));
    assertThrows(IllegalArgumentException.class,
        () -> LogicalPageFileTable.resolve(PageFileNames.layout28DiskName(identity),
            identity.internalId()));
    assertThrows(IllegalArgumentException.class,
        () -> LogicalPageFileTable.resolve(name.replace("9000", "09000"), identity.internalId()));
    assertNull(table.live(name, identity.internalId()));
    table.reserve(name, identity.internalId());
    assertTrue(table.reserved(identity.internalId()));
    assertNull(table.live(name, identity.internalId()));
    table.register(name, identity.internalId(), true);
    assertFalse(table.reserved(identity.internalId()));
    assertTrue(table.live(name, identity.internalId()).nonDurable());
    table.delete(name, identity.internalId());
    assertNull(table.live(name, identity.internalId()));
    assertTrue(table.deleted(identity.internalId()));
    assertFalse(table.reserved(identity.internalId()));
    table.reserve(name, identity.internalId());
    assertNull(table.live(name, identity.internalId()));
    assertTrue(table.reserved(identity.internalId()));
    assertFalse(table.deleted(identity.internalId()));
    table.clear();
    assertFalse(table.reserved(identity.internalId()));
  }

  // Explicit birth selection exercises shadow registration, recovery deletion and cold reopen.
  // Legacy fixtures remain on their default layout.
  @Test
  public void shadowStateAgreesWithRegistrationThroughLifecycle() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass());
    var manager = (YouTrackDBImpl) YourTracks.instance(directory);
    var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9000);
    var name = PageFileNames.layout28(identity);
    var loaded = new PageFileIdentity(PageFileRole.RECORD_DATA, 9001);
    WOWCache cache;
    try {
      try (var selection = StorageBirthTestSupport.selectBirthFormat(
          manager, "logical", StorageFormat.LAYOUT_28)) {
        manager.create("logical", DatabaseType.DISK, "admin", "adminpwd", "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        cache = (WOWCache) session.getStorage().getWriteCache();
        var id = cache.externalFileId(identity.internalId());
        cache.bookFileId(name, id, false);
        assertReserved(cache, identity);
        assertEquals(Integer.valueOf(-identity.internalId()), cache.registeredFileId(name));
        cache.addFile(name, id, true);
        assertPending(cache, identity, true);
        assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.RESTORE));
        assertLive(cache, identity, true);
        assertEquals(id, cache.loadFile(name));
        assertLive(cache, identity, true);

        var readCache = mock(ReadCache.class);
        var deletionCache = cache;
        doAnswer(invocation -> {
          deletionCache.deleteFile(invocation.getArgument(0));
          return null;
        }).when(readCache).deleteFile(id, cache);
        assertTrue(cache.deleteNonDurableFilesOnRecovery(readCache)
            .contains(identity.internalId()));
        assertDeleted(cache, identity);
        assertEquals(Integer.valueOf(-identity.internalId()), cache.registeredFileId(name));
        assertEquals(id, cache.bookFileId(name));
        assertReserved(cache, identity);
        cache.addFile(name);
        assertPending(cache, identity, false);

        // A reservation does not authorize ordinary lookup to adopt an unknown disk file.
        var loadedName = PageFileNames.layout28(loaded);
        var loadedId = cache.externalFileId(loaded.internalId());
        cache.bookFileId(loadedName, loadedId, false);
        assertReserved(cache, loaded);
        Files.createFile(((DiskStorage) session.getStorage()).getStoragePath()
            .resolve(PageFileNames.layout28DiskName(loaded)));
        var lookupCache = cache;
        assertThrows(StorageException.class, () -> lookupCache.loadFile(loadedName));
        assertTrue(cache.resolveRecoveryFile(loadedName, loadedId,
            com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.RecoveryFilePermission.OPEN_ONLY));
        assertLive(cache, loaded, false);
        // Rejected aliases cannot displace canonical membership.
        assertThrows(StorageException.class,
            () -> lookupCache.renameFile(loadedId, "renamed.pcl"));
        assertLive(cache, loaded, false);
        cache.deleteFile(loadedId);
        assertDeleted(cache, loaded);
        assertEquals(loadedId, cache.bookFileId(loadedName));
        assertReserved(cache, loaded);
        cache.deleteFile(id);
        assertDeleted(cache, identity);
        assertNull("identity recovery must not resurrect a map tombstone",
            cache.restoreFileById(id));
        assertDeleted(cache, identity);
        assertFalse(Files.exists(((DiskStorage) session.getStorage()).getStoragePath()
            .resolve(PageFileNames.layout28DiskName(identity))));

        var reserved = new PageFileIdentity(PageFileRole.RECORD_DATA, 9002);
        cache.bookFileId(PageFileNames.layout28(reserved),
            cache.externalFileId(reserved.internalId()), false);
        assertReserved(cache, reserved);
      }
      manager.close();
      assertTrue(cache.logicalFiles().inventory().isEmpty());
      manager = (YouTrackDBImpl) YourTracks.instance(directory);
      try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        cache = (WOWCache) session.getStorage().getWriteCache();
        assertAbsent(cache, identity);
        assertAbsent(cache, loaded);
        cache.addFile(name, cache.externalFileId(identity.internalId()));
        assertPending(cache, identity, false);
        var reserved = new PageFileIdentity(PageFileRole.RECORD_DATA, 9002);
        assertAbsent(cache, reserved);
        cache.deleteFile(cache.externalFileId(identity.internalId()));
        assertDeleted(cache, identity);
      }
    } finally {
      manager.close();
      FileUtils.deleteDirectory(Path.of(directory).toFile());
    }
  }

  // Map corruption cannot change visible inventory, lookup, verification or force sets.
  @Test
  public void logicalReadersIgnoreMapsAndInventoryIsImmutable() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9040);
      var name = PageFileNames.layout28(identity);
      var id = cache.externalFileId(identity.internalId());
      assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.RESTORE));
      var reserved = new PageFileIdentity(PageFileRole.RECORD_DATA, 9041);
      cache.bookFileId(PageFileNames.layout28(reserved),
          cache.externalFileId(reserved.internalId()), false);
      var pending = new PageFileIdentity(PageFileRole.RECORD_DATA, 9042);
      cache.logicalFiles().register(PageFileNames.layout28(pending), pending.internalId(),
          false, 0, 0, false);
      var privateIdentity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9043);
      assertThrows(StorageException.class,
          () -> cache.addFile("private.pcl", cache.externalFileId(privateIdentity.internalId())));
      var inventory = cache.files();
      assertFalse(inventory.containsKey("private.pcl"));
      assertFalse(cache.exists("private.pcl"));
      assertFalse(cache.exists(cache.externalFileId(privateIdentity.internalId())));
      assertEquals(2, inventory.size());
      assertThrows(UnsupportedOperationException.class, inventory::clear);
      assertFalse(cache.exists(PageFileNames.layout28(reserved)));
      assertFalse(cache.exists(cache.externalFileId(reserved.internalId())));
      assertEquals(-1, cache.fileIdByName(PageFileNames.layout28(reserved)));
      var namesField = WOWCache.class.getDeclaredField("nameIdMap");
      var idsField = WOWCache.class.getDeclaredField("idNameMap");
      namesField.setAccessible(true);
      idsField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var names = (Map<String, Integer>) namesField.get(cache);
      @SuppressWarnings("unchecked")
      var ids = (Map<Integer, String>) idsField.get(cache);
      var savedNames = new HashMap<>(names);
      var savedIds = new HashMap<>(ids);
      try {
        names.clear();
        ids.clear();
        names.put("map-only.pcl", 123456);
        names.put("map-only-deletion.pcl", -123457);
        assertEquals(inventory.keySet(),
            new HashSet<>(cache.registrationEvidence().positiveNames()));
        assertEquals(java.util.Set.of(PageFileNames.layout28(reserved)),
            new HashSet<>(cache.registrationEvidence().negativeNames()));
        assertEquals(inventory, cache.files());
        assertEquals(id, cache.fileIdByName(name));
        assertEquals(Integer.valueOf(identity.internalId()), cache.registeredFileId(name));
        assertTrue(cache.exists(name));
        assertTrue(cache.exists(id));
        assertEquals(id, cache.loadFile(name));
        assertEquals(name, cache.fileNameById(id));
        assertEquals(PageFileNames.layout28DiskName(identity), cache.nativeFileNameById(id));
        assertEquals(cache.externalFileId(pending.internalId()),
            cache.loadFile(PageFileNames.layout28(pending)));
        assertTrue(cache.exists(PageFileNames.layout28(pending)));
        assertTrue(cache.exists(cache.externalFileId(pending.internalId())));
        var verification = new StringBuilder();
        assertEquals(0, cache.checkStoredPages(verification::append).length);
        assertTrue(verification.toString().contains(name));
        cache.flush();
        cache.syncDataFiles();
        cache.shrinkFile(id, 0);
        cache.deleteFile(id);
        names.clear();
        ids.clear();
        assertEquals(java.util.Set.of(name, PageFileNames.layout28(reserved)),
            new HashSet<>(cache.registrationEvidence().negativeNames()));
        assertEquals(2, inventory.size());
        assertFalse(cache.exists(name));
        assertFalse(cache.exists(id));
        assertEquals(-1, cache.fileIdByName(name));
        assertNull(cache.fileNameById(id));
        assertNull(cache.nativeFileNameById(id));
        assertEquals(java.util.Set.of(name, PageFileNames.layout28(reserved)),
            new HashSet<>(cache.deletedFiles()));
        assertFalse(cache.files().containsKey(name));
        assertThrows(StorageException.class, () -> cache.loadFile(name));
      } finally {
        names.putAll(savedNames);
        ids.putAll(savedIds);
        // Restore fixture evidence and discard the synthetic pending identity.
        cache.logicalFiles().forget(pending.internalId());
        names.remove(name);
        ids.remove(identity.internalId());
      }
    });
  }

  // A cache born at layout 28 stays map-free across mutation, force, deletion and cold reopen.
  // Poison maps through the first registered-file load, close and reopen.
  @Test
  public void zeroMapLifecycleWithEitherFsyncSetting() throws Exception {
    for (boolean callFsync : new boolean[] {false, true}) {
      withStandaloneCache(cache -> {
        var fsync = WOWCache.class.getDeclaredField("callFsync");
        fsync.setAccessible(true);
        fsync.set(cache, callFsync);
        var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9080);
        var survivor = new PageFileIdentity(PageFileRole.RECORD_DATA, 9081);
        var disk = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
        var map = cache.getRootDirectory().resolve("name_id_map_v3.cm");
        assertFalse(Files.exists(map));
        try (var ignored = StorageBirthTestSupport.poisonSessionMaps(cache)) {
          long id = cache.externalFileId(identity.internalId());
          long kept = cache.externalFileId(survivor.internalId());
          assertTrue(cache.resolveRecoveryFile(PageFileNames.layout28(identity), id,
              RecoveryFilePermission.RESTORE));
          assertTrue(cache.resolveRecoveryFile(PageFileNames.layout28(survivor), kept,
              RecoveryFilePermission.RESTORE));
          assertTrue(Files.exists(disk));
          var handlesField = WOWCache.class.getDeclaredField("files");
          handlesField.setAccessible(true);
          @SuppressWarnings("unchecked")
          var handles = (ClosableLinkedContainer<Long,
              com.jetbrains.youtrackdb.internal.core.storage.fs.File>) handlesField.get(cache);
          var handle = spy(handles.remove(kept));
          handles.add(kept, handle);
          var page = cache.loadOrAdd(kept, 0, false);
          long stamp = page.acquireExclusiveLock();
          try {
            page.getBuffer().putLong(64, 123456L);
            cache.store(kept, 0, page);
          } finally {
            page.releaseExclusiveLock(stamp);
            page.decrementReadersReferrer();
          }
          cache.flush();
          verify(handle, times(callFsync ? 1 : 0)).synch();
          org.mockito.Mockito.clearInvocations(handle);
          cache.syncDataFiles();
          verify(handle, times(callFsync ? 1 : 0)).synch();
          org.mockito.Mockito.clearInvocations(handle);
          forceOnFlushExecutor(cache);
          verify(handle, times(callFsync ? 1 : 0)).synch();
          assertFalse(Files.exists(map));
          cache.deleteFile(id);
          assertDeleted(cache, identity);
          assertFalse(Files.exists(disk));
          cache.close();
          assertFalse(Files.exists(map));
          // Closed-cache force must not fall back to either legacy map.
          forceOnFlushExecutor(cache);
          cache.loadRegisteredFiles();
          assertFalse(cache.admitExpectedFile(identity));
          assertTrue(cache.admitExpectedFile(survivor));
          var loaded = cache.loadOrAdd(kept, 0, false);
          try {
            assertEquals(123456L, loaded.getBuffer().getLong(64));
          } finally {
            loaded.decrementReadersReferrer();
          }
          assertFalse(Files.exists(map));
          cache.deleteFile(kept);
          cache.close();
          assertFalse(Files.exists(map));
        }
      });
    }
  }

  // An indeterminate exists() result must not hide an actual physical deletion failure.
  @Test
  public void physicalDeletionFailurePropagatesWithoutLogicalDeletion() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9083);
      long id = cache.externalFileId(identity.internalId());
      assertTrue(cache.resolveRecoveryFile(PageFileNames.layout28(identity), id,
          RecoveryFilePermission.RESTORE));
      var disk = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      var field = WOWCache.class.getDeclaredField("files");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var handles = (ClosableLinkedContainer<Long,
          com.jetbrains.youtrackdb.internal.core.storage.fs.File>) field.get(cache);
      var handle = spy(handles.remove(id));
      handles.add(id, handle);
      var failure = new AccessDeniedException(disk.toString());
      doReturn(false).when(handle).exists();
      doThrow(failure).when(handle).delete();
      try {
        var thrown = assertThrows(WriteCacheException.class, () -> cache.deleteFile(id));
        assertEquals(failure, thrown.getCause().getCause());
        verify(handle).delete();
        assertTrue(Files.exists(disk));
        assertLive(cache, identity, false);
      } finally {
        handle.close();
      }
      // A retry without a registered handle must still remove the physical file.
      cache.deleteFile(id);
      assertDeleted(cache, identity);
      assertFalse(Files.exists(disk));
    });
  }

  // The handle-free path must also propagate non-missing failures before publishing DELETED.
  @Test
  public void handleFreeDeletionFailureDoesNotPublishDeleted() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9084);
      var disk = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      Files.createDirectory(disk);
      var child = Files.createFile(disk.resolve("blocks-deletion"));
      long id = cache.externalFileId(identity.internalId());
      assertThrows(WriteCacheException.class, () -> cache.deleteFile(id));
      assertAbsent(cache, identity);
      assertTrue(Files.exists(child));
      Files.delete(child);
      Files.delete(disk);
      cache.deleteFile(id);
      assertDeleted(cache, identity);
    });
  }

  // A registered handle whose physical file is missing still transitions to logical DELETED.
  @Test
  public void missingPhysicalFileIsDeletedLogically() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9085);
      long id = cache.externalFileId(identity.internalId());
      assertTrue(cache.resolveRecoveryFile(PageFileNames.layout28(identity), id,
          RecoveryFilePermission.RESTORE));
      var disk = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      Files.delete(disk);
      cache.deleteFile(id);
      assertDeleted(cache, identity);
      cache.deleteFile(id);
      assertDeleted(cache, identity);
      assertFalse(Files.exists(disk));
    });
  }

  // Deleting a handle-backed layout 28 file leaves pre-existing legacy map bytes untouched.
  @Test
  public void handleBackedDeletionLeavesExistingMapUnchanged() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9086);
      var name = PageFileNames.layout28(identity);
      long id = cache.externalFileId(identity.internalId());
      assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.RESTORE));
      StorageBirthTestSupport.seedNameIdMap(cache, name, identity.internalId(),
          PageFileNames.layout28DiskName(identity));
      var map = cache.getRootDirectory().resolve("name_id_map_v3.cm");
      var bytes = Files.readAllBytes(map);
      assertTrue(bytes.length > 0);
      try (var ignored = StorageBirthTestSupport.poisonSessionMaps(cache)) {
        cache.deleteFile(id);
        assertDeleted(cache, identity);
      }
      assertFalse(Files.exists(
          cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity))));
      assertArrayEquals(bytes, Files.readAllBytes(map));
    });
  }

  // Replay deletion followed by recreation must not leave a negative session-map tombstone.
  @Test
  public void replayDeleteThenRecreateLeavesNoSessionTombstone() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9082);
      var name = PageFileNames.layout28(identity);
      long id = cache.addFile(name);
      try (var ignored = StorageBirthTestSupport.poisonSessionMaps(cache)) {
        cache.deleteFile(id);
        assertDeleted(cache, identity);
        assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.REPLAY_CREATE));
        assertLive(cache, identity, false);
      }
      assertTrue(snapshot(cache, "nameIdMap").isEmpty());
      assertTrue(snapshot(cache, "idNameMap").isEmpty());
    });
  }

  // Every force path includes all materialized live files even without map mirrors.
  @Test
  public void forceSetsUseLogicalDiskPresenceWithoutMaps() throws Exception {
    withStandaloneCache(cache -> {
      var field = WOWCache.class.getDeclaredField("files");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var handles = (ClosableLinkedContainer<Long,
          com.jetbrains.youtrackdb.internal.core.storage.fs.File>) field.get(cache);
      var observed =
          new java.util.ArrayList<com.jetbrains.youtrackdb.internal.core.storage.fs.File>();
      for (int component = 9050; component < 9052; component++) {
        var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, component);
        long id = cache.externalFileId(identity.internalId());
        assertTrue(cache.resolveRecoveryFile(PageFileNames.layout28(identity), id,
            RecoveryFilePermission.RESTORE));
        var handle = org.mockito.Mockito.spy(handles.remove(id));
        handles.add(id, handle);
        observed.add(handle);
      }
      assertEquals(2, cache.files().size());
      var fsync = WOWCache.class.getDeclaredField("callFsync");
      fsync.setAccessible(true);
      fsync.set(cache, true);
      var namesField = WOWCache.class.getDeclaredField("nameIdMap");
      var idsField = WOWCache.class.getDeclaredField("idNameMap");
      namesField.setAccessible(true);
      idsField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var names = (Map<String, Integer>) namesField.get(cache);
      @SuppressWarnings("unchecked")
      var ids = (Map<Integer, String>) idsField.get(cache);
      var savedNames = new HashMap<>(names);
      var savedIds = new HashMap<>(ids);
      try {
        names.clear();
        ids.clear();
        cache.flush();
        for (var handle : observed) {
          org.mockito.Mockito.verify(handle).synch();
          org.mockito.Mockito.clearInvocations(handle);
        }
        cache.syncDataFiles();
        for (var handle : observed) {
          org.mockito.Mockito.verify(handle).synch();
          org.mockito.Mockito.clearInvocations(handle);
        }
        forceOnFlushExecutor(cache);
        for (var handle : observed) {
          org.mockito.Mockito.verify(handle).synch();
        }
        cache.close();
        for (var handle : observed) {
          assertFalse(handle.isOpen());
        }
        savedNames.clear();
        savedIds.clear();
      } finally {
        names.putAll(savedNames);
        ids.putAll(savedIds);
      }
    });
  }

  // Database deletion covers logical files whose legacy registration entries are absent.
  @Test
  public void databaseDeletionUsesLogicalInventoryWithoutMaps() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9053);
      long id = cache.externalFileId(identity.internalId());
      assertTrue(cache.resolveRecoveryFile(PageFileNames.layout28(identity), id,
          RecoveryFilePermission.RESTORE));
      for (var fieldName : List.of("nameIdMap", "idNameMap")) {
        var field = WOWCache.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<?, ?>) field.get(cache)).clear();
      }
      org.junit.Assert.assertArrayEquals(new long[] {id}, cache.delete());
      assertFalse(cache.exists(id));
      assertNull(cache.logicalFiles().live(identity.internalId()));
      // Reinitialize the deleted registry so the shared fixture can close normally.
      cache.loadRegisteredFiles();
    });
  }

  // The committed cache cannot expose an atomic operation's private file before commit.
  @Test
  public void operationPrivateFileBecomesVisibleOnlyAfterCommit() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-private";
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory)) {
      try (var selection = StorageBirthTestSupport.selectBirthFormat(
          manager, "logical", StorageFormat.LAYOUT_28)) {
        manager.create("logical", DatabaseType.DISK, "admin", "adminpwd", "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        var storage = session.getStorage();
        var cache = (WOWCache) storage.getWriteCache();
        var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9058);
        var name = PageFileNames.layout28(identity);
        long id = cache.externalFileId(identity.internalId());
        var before = cache.files();
        var lockField =
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage.class
                .getDeclaredField("stateLock");
        lockField.setAccessible(true);
        var lock = (com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock) lockField
            .get(storage);
        lock.writeLock().lock();
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            op.addFile(name, id, false, false);
            initStatePage(op, id, 42);
            assertTrue(op.isFileExists(name, id));
            assertEquals(before, cache.files());
            assertFalse(cache.exists(name));
            assertFalse(cache.exists(id));
          });
        } finally {
          lock.writeLock().unlock();
        }
        var after = new HashMap<>(before);
        after.put(name, id);
        assertEquals(after, cache.files());
        assertTrue(cache.exists(name));
        assertTrue(cache.exists(id));
        assertEquals(id, cache.fileIdByName(name));
      }
    } finally {
      FileUtils.deleteDirectory(Path.of(directory).toFile());
    }
  }

  // The commit returns with both lifetimes pending while the commitExecutor() thread is held.
  @Category(SequentialTest.class)
  @Test(timeout = 90_000)
  public void layout28RealCommitDoesNotWaitAndFirstWriteMaterializesEachDurability()
      throws Exception {
    withStorage(DatabaseType.DISK, StorageFormat.LAYOUT_28, storage -> {
      final var cache = (WOWCache) storage.getWriteCache();
      final var durable = new PageFileIdentity(PageFileRole.RECORD_DATA, 9060);
      final var nonDurable = new PageFileIdentity(PageFileRole.RECORD_DATA, 9061);
      final var identities = List.of(durable, nonDurable);
      final var count = new java.util.concurrent.atomic.AtomicInteger();
      final var executor = (ScheduledThreadPoolExecutor) YouTrackDBEnginesManager.instance()
          .getWowCacheFlushExecutor();
      final var entered = new CountDownLatch(1);
      final var release = new CountDownLatch(1);
      final var worker = Executors.newSingleThreadExecutor();
      try (var observation = cache.observePhysicalCreation(internalId -> {
        if (internalId == durable.internalId() || internalId == nonDurable.internalId()) {
          count.incrementAndGet();
        }
      })) {
        worker.submit(cache::pauseBackgroundFlush).get(10, TimeUnit.SECONDS);
        executor.submit(() -> {
        }).get(10, TimeUnit.SECONDS);
        final var trackerField = WOWCache.class.getDeclaredField("changedPageTracker");
        trackerField.setAccessible(true);
        final var tracker = (ChangedPageTracker) trackerField.get(cache);
        tracker.mark(durable.internalId(), 7);
        assertTrue(trackedPages(tracker, durable.internalId()).contains(7L));
        final var wal = storage.getWALInstance();
        final var beforeCommit = wal.end();
        final var gate = executor.submit(() -> {
          entered.countDown();
          assertTrue(release.await(20, TimeUnit.SECONDS));
          return null;
        });
        final LogSequenceNumber afterCommit;
        try {
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          final long submissionsBefore = executor.getTaskCount();
          afterCommit = worker.submit(() -> {
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
              for (var identity : identities) {
                long id = op.addFile(PageFileNames.layout28(identity), identity.equals(nonDurable));
                initStatePage(op, id, identity.componentId());
              }
            });
            return wal.end();
          }).get(10, TimeUnit.SECONDS);
          assertEquals("Commit must not submit work to the commitExecutor() thread",
              submissionsBefore, executor.getTaskCount());
          assertFalse("Registration must reset the prior lifetime's tracker marks",
              trackedPages(tracker, durable.internalId()).contains(7L));
          tracker.mark(durable.internalId(), 8);
          assertNotNull(afterCommit);
          assertEquals("Registration must retain the committing unit's end LSN", afterCommit,
              cache.logicalFiles().live(durable.internalId()).creationLsn());
          for (var identity : identities) {
            var name = PageFileNames.layout28(identity);
            long id = cache.externalFileId(identity.internalId());
            assertEquals(id, cache.loadFile(name));
            assertEquals(name, cache.fileNameById(id));
            var state = cache.logicalFiles().live(identity.internalId());
            assertEquals(identity.equals(nonDurable), state.nonDurable());
            assertEquals(1, state.logicalExtent());
            assertEquals(0, state.diskExtent());
            assertFalse(state.diskPresent());
            assertFalse(Files.exists(cache.getRootDirectory().resolve(
                PageFileNames.layout28DiskName(identity))));
          }
          assertEquals(0, count.get());
        } finally {
          release.countDown();
          gate.get(10, TimeUnit.SECONDS);
        }

        // Match the end record to the unit that logged this file's creation.
        final var endLsn = worker.submit(() -> {
          wal.flush();
          final var records = wal.next(beforeCommit, 32);
          final var creation = records.stream().filter(FileCreatedWALRecord.class::isInstance)
              .map(FileCreatedWALRecord.class::cast)
              .filter(record -> record.getFileName().equals(PageFileNames.layout28(durable)))
              .findFirst().orElseThrow();
          return records.stream().filter(AtomicUnitEndRecord.class::isInstance)
              .map(AtomicUnitEndRecord.class::cast)
              .filter(record -> record.getOperationUnitId() == creation.getOperationUnitId())
              .findFirst().orElseThrow().getLsn();
        }).get(10, TimeUnit.SECONDS);
        assertEquals(endLsn, afterCommit);

        for (var identity : identities) {
          long id = cache.externalFileId(identity.internalId());
          worker.submit(() -> cache.flush(id)).get(10, TimeUnit.SECONDS);
          var state = cache.logicalFiles().live(identity.internalId());
          assertTrue(state.diskPresent());
          assertEquals(1, state.diskExtent());
          var image = Files.readAllBytes(cache.getRootDirectory().resolve(
              PageFileNames.layout28DiskName(identity)));
          assertEquals(File.HEADER_SIZE + cache.pageSize(), image.length);
          assertArrayEquals(new byte[File.HEADER_SIZE], java.util.Arrays.copyOf(image,
              File.HEADER_SIZE));
          // The state page stores its file size after two reserved integers.
          final int payloadOffset =
              com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage.NEXT_FREE_POSITION
                  + 2 * Integer.BYTES;
          assertEquals(identity.componentId(), java.nio.ByteBuffer.wrap(image)
              .order(java.nio.ByteOrder.nativeOrder()).getInt(File.HEADER_SIZE + payloadOffset));
        }
        assertEquals(2, count.get());
        assertTrue("First write must preserve tracker marks from the current lifetime",
            trackedPages(tracker, durable.internalId()).contains(8L));
      } finally {
        try {
          worker.shutdownNow();
          assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
          executor.submit(() -> {
          }).get(10, TimeUnit.SECONDS);
        } finally {
          cache.resumeBackgroundFlush();
        }
      }
    });
  }

  private static List<Long> trackedPages(ChangedPageTracker tracker, int id) throws IOException {
    final var seal = tracker.beginBackup();
    assertNotNull(seal);
    try {
      final var result = new ArrayList<Long>();
      final var bitmap = tracker.candidateBitmap(seal, id);
      if (bitmap != null) {
        bitmap.forEachCandidate(9, result::add);
      }
      return result;
    } finally {
      tracker.mergeBack(seal);
    }
  }

  @Test
  public void layout28RollbackKeepsOnlyTheBookingItDidNotOwn() throws Exception {
    withStorage(DatabaseType.DISK, StorageFormat.LAYOUT_28, storage -> {
      var cache = (WOWCache) storage.getWriteCache();
      var owner = new PageFileIdentity(PageFileRole.RECORD_DATA, 9065);
      var foreign = new PageFileIdentity(PageFileRole.FREE_SPACE_MAP, 9066);
      var ownedName = PageFileNames.layout28(owner);
      var borrowedName = PageFileNames.layout28(foreign);
      long borrowedId = cache.externalFileId(foreign.internalId());
      var registry = cache.getRootDirectory().resolve("non_durable_files.cm");
      var lockField = AbstractStorage.class.getDeclaredField("stateLock");
      lockField.setAccessible(true);
      var lock = (com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock) lockField
          .get(storage);
      cache.pauseBackgroundFlush();
      lock.writeLock().lock();
      try {
        var foreignBooking = cache.bookFileIdWithOwnership(borrowedName, borrowedId, false);
        byte[] before = Files.exists(registry) ? Files.readAllBytes(registry) : null;
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            initStatePage(op, op.addFile(ownedName, true), 1);
            assertEquals(borrowedId, op.addFile(borrowedName, borrowedId, true, true));
            initStatePage(op, borrowedId, 2);
            op.rollbackInProgress();
          });
          assertAbsent(cache, owner);
          assertReserved(cache, foreign);
          for (var identity : List.of(owner, foreign)) {
            assertFalse(cache.isNonDurable(cache.externalFileId(identity.internalId())));
            assertFalse(Files.exists(cache.getRootDirectory().resolve(
                PageFileNames.layout28DiskName(identity))));
          }
          assertArrayEquals(before, Files.exists(registry) ? Files.readAllBytes(registry) : null);
        } finally {
          foreignBooking.release().run();
        }
      } finally {
        lock.writeLock().unlock();
        cache.resumeBackgroundFlush();
      }
    });
  }

  @Test(timeout = 60_000)
  public void layout28LateDanglingLinkRefusalReachesRealChangedPageCommit() throws Exception {
    withStandaloneAtomicOperations((cache, manager) -> {
      final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9069);
      final var name = PageFileNames.layout28(identity);
      final var path = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      final var target = Path.of("missing");
      try {
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> manager.executeInsideAtomicOperation(op -> {
              initStatePage(op, op.addFile(name, false), 42);
              Files.createSymbolicLink(path, target);
            }));
        assertEquals(target, Files.readSymbolicLink(path));
        assertNull(cache.logicalFiles().live(identity.internalId()));
      } finally {
        Files.deleteIfExists(path);
      }
    });
  }

  @Test
  public void layout28RegistryFailureReachesRealCommitWithoutPublishingFile() throws Exception {
    withStandaloneAtomicOperations((cache, manager) -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9068);
      var name = PageFileNames.layout28(identity);
      long id = cache.externalFileId(identity.internalId());
      var registry = cache.getRootDirectory().resolve("non_durable_files.cm");
      var path = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      var saved = WOWCache.class.getDeclaredField("savedNonDurableFileIds");
      saved.setAccessible(true);
      assertFalse(((IntSet) saved.get(cache)).contains(identity.internalId()));
      Files.createDirectory(registry);
      try {
        var failure = assertThrows(IOException.class, () -> manager
            .executeInsideAtomicOperation(op -> initStatePage(op, op.addFile(name, true), 42)));
        assertTrue(failure.getMessage(), failure.getMessage().contains(registry.toString()));
        assertNull(cache.logicalFiles().live(identity.internalId()));
        assertFalse(cache.isNonDurable(id));
        assertFalse(((IntSet) saved.get(cache)).contains(identity.internalId()));
        assertFalse(Files.exists(path));
      } finally {
        Files.delete(registry);
      }
    });
  }

  @FunctionalInterface
  private interface AtomicCacheAction {
    void run(WOWCache cache, AtomicOperationsManager manager) throws Exception;
  }

  private void withStandaloneAtomicOperations(AtomicCacheAction action) throws Exception {
    YouTrackDBEnginesManager.startUp(false);
    var root = Path.of(DbTestBase.getBaseDirectoryPathStr(getClass()) + "-failed-commit");
    Files.createDirectories(root);
    var pool = new ByteBufferPool(4096);
    var io = Executors.newCachedThreadPool();
    var wal = new MemoryWriteAheadLog();
    try {
      var cache = new WOWCache(4096, false, pool, wal, new DoubleWriteLogNoOP(),
          0, 10000, 128L * 4096, root, "logical", new ClosableLinkedContainer<>(128),
          1, ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
          ChecksumMode.StoreAndVerify, null, null, false, io);
      var readCache = new LockFreeReadCache(pool, 64L * 1024 * 1024, 4096);
      // Delete the caches directly. A storage close would hit the checkpoint failure in YTDB-1389.
      try (AutoCloseable ignored = () -> readCache.deleteStorage(cache)) {
        cache.setStorageFormat(StorageFormat.LAYOUT_28);
        cache.loadRegisteredFiles();
        cache.pauseBackgroundFlush();
        var storage = mock(AbstractStorage.class);
        when(storage.getName()).thenReturn("logical");
        when(storage.getId()).thenReturn(1);
        when(storage.getIdGen()).thenReturn(new AtomicOperationIdGen());
        when(storage.getWALInstance()).thenReturn(wal);
        when(storage.getReadCache()).thenReturn(readCache);
        when(storage.getWriteCache()).thenReturn(cache);
        when(storage.getSharedSnapshotIndex()).thenReturn(new ConcurrentSkipListMap<>());
        when(storage.getVisibilityIndex()).thenReturn(new ConcurrentSkipListMap<>());
        when(storage.getSnapshotIndexSize()).thenReturn(new AtomicLong());
        when(storage.getSharedEdgeSnapshotIndex()).thenReturn(new ConcurrentSkipListMap<>());
        when(storage.getEdgeVisibilityIndex()).thenReturn(new ConcurrentSkipListMap<>());
        when(storage.getEdgeSnapshotIndexSize()).thenReturn(new AtomicLong());
        var manager = new AtomicOperationsManager(storage, new AtomicOperationsTable(100, 0));
        action.run(cache, manager);
      }
    } finally {
      io.shutdownNow();
      assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
      pool.clear();
      FileUtils.deleteDirectory(root.toFile());
    }
  }

  @Test(timeout = 60_000)
  public void layout28IntegrityCheckMaterializesAndVerifiesPendingRealCommit() throws Exception {
    withStorage(DatabaseType.DISK, StorageFormat.LAYOUT_28, storage -> {
      final var cache = (WOWCache) storage.getWriteCache();
      final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9070);
      final var name = PageFileNames.layout28(identity);
      final var path = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      final var worker = Executors.newSingleThreadExecutor();
      try {
        worker.submit(cache::pauseBackgroundFlush).get(10, TimeUnit.SECONDS);
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> initStatePage(op, op.addFile(name, false), 42));
        assertFalse(Files.exists(path));
        final var output = new StringBuilder();
        final var errors = worker.submit(() -> cache.checkStoredPages(output::append))
            .get(10, TimeUnit.SECONDS);
        assertEquals(0, errors.length);
        assertTrue(output.toString(), output.toString().contains(
            "Verification of file '" + name + "' is successfully finished."));
        assertTrue(Files.exists(path));
        final int payloadOffset =
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage.NEXT_FREE_POSITION
                + 2 * Integer.BYTES;
        assertEquals(42, java.nio.ByteBuffer.wrap(Files.readAllBytes(path))
            .order(java.nio.ByteOrder.nativeOrder()).getInt(File.HEADER_SIZE + payloadOffset));
      } finally {
        try {
          worker.shutdownNow();
          assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
          cache.resumeBackgroundFlush();
        }
      }
    });
  }

  @Test
  public void layout28PendingPageReadWriteShrinkAndCloseWithFlush() throws Exception {
    withStorage(DatabaseType.DISK, StorageFormat.LAYOUT_28, storage -> {
      var cache = (WOWCache) storage.getWriteCache();
      var operations = storage.getAtomicOperationsManager();
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9067);
      var name = PageFileNames.layout28(identity);
      var path = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(identity));
      cache.pauseBackgroundFlush();
      try {
        long id = operations.calculateInsideAtomicOperation(op -> {
          long added = op.addFile(name, false);
          initStatePage(op, added, 42);
          var tail = op.allocatePageForWrite(added, 1);
          try {
            new PaginatedCollectionStateV2(tail).setFileSize(99);
          } finally {
            op.releasePageFromWrite(tail);
          }
          return added;
        });
        assertEquals(2, cache.getFilledUpTo(id));
        assertEquals(0, cache.logicalFiles().live(identity.internalId()).diskExtent());
        assertFalse(Files.exists(path));
        operations.executeInsideAtomicOperation(op -> {
          var page = op.loadPageForWrite(id, 0, 1, true);
          try {
            var data = new PaginatedCollectionStateV2(page);
            assertEquals(42, data.getFileSize());
            data.setFileSize(43);
          } finally {
            op.releasePageFromWrite(page);
          }
        });
        storage.getReadCache().shrinkFile(id, cache.pageSize(), cache);
        assertEquals(1, cache.getFilledUpTo(id));
        assertFalse(Files.exists(path));
        storage.getReadCache().closeFile(id, true, cache);
        assertTrue(cache.exists(id));
        assertEquals(File.HEADER_SIZE + cache.pageSize(), Files.size(path));
        assertEquals(0, cache.checkStoredPages(null).length);
        var marker = operations.calculateInsideAtomicOperation(op -> {
          var page = op.loadPageForRead(id, 0);
          try {
            return new PaginatedCollectionStateV2(page).getFileSize();
          } finally {
            op.releasePageFromRead(page);
          }
        });
        assertEquals(43, marker.intValue());
      } finally {
        cache.resumeBackgroundFlush();
      }
    });
  }

  @Test
  public void layout28BirthWritesItsFilesAndClosePersistsPendingCollectionContent()
      throws Exception {
    var root = Path.of(DbTestBase.getBaseDirectoryPathStr(getClass()) + "-deferred-close");
    var birthFiles = new HashSet<Path>();
    WOWCache closeCache = null;
    WOWCache dropCache = null;
    try {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString())) {
        var birthCache = new java.util.concurrent.atomic.AtomicReference<WOWCache>();
        try (var selection = StorageBirthTestSupport.selectBirthFormat(
            manager, "logical", StorageFormat.LAYOUT_28);
            var observation = StorageBirthTestSupport.observeBirthCompletion(storage -> {
              var cache = (WOWCache) storage.getWriteCache();
              birthCache.set(cache);
              cache.pauseBackgroundFlush();
              for (var id : cache.files().values()) {
                birthFiles.add(cache.getRootDirectory().resolve(cache.nativeFileNameById(id)));
              }
            }, storage -> {
            })) {
          manager.create("logical", DatabaseType.DISK, "admin", "adminpwd", "admin");
          assertFalse(birthFiles.isEmpty());
          for (var birthFile : birthFiles) {
            assertTrue(birthFile.toString(), Files.isRegularFile(birthFile));
          }
        } finally {
          if (birthCache.get() != null) {
            birthCache.get().resumeBackgroundFlush();
          }
        }
        try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
          var cache = (WOWCache) session.getStorage().getWriteCache();
          closeCache = cache;
          cache.pauseBackgroundFlush();
          int dropped = session.addCollection("pendingdrop");
          var pending = collectionPageFile(session, dropped);
          var path = cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(pending));
          assertTrue(cache.exists(PageFileNames.layout28(pending)));
          assertFalse(Files.exists(path));
          assertTrue(session.dropCollection(dropped));
          assertFalse(cache.exists(PageFileNames.layout28(pending)));
          assertFalse(Files.exists(path));

          session.execute("create class DeferredMarker").close();
          session.begin();
          session.execute("insert into DeferredMarker set marker = 'written on clean close'")
              .close();
          session.commit();
          int id = session.getMetadata().getSchema().getClass("DeferredMarker")
              .getCollectionIds()[0];
          var retained = collectionPageFile(session, id);
          assertTrue(cache.exists(PageFileNames.layout28(retained)));
          assertFalse(Files.exists(cache.getRootDirectory().resolve(
              PageFileNames.layout28DiskName(retained))));
        }
      }
      try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString())) {
        PageFileIdentity dropFile;
        try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
          try (var rows = session.query("select from DeferredMarker")) {
            assertTrue(rows.hasNext());
            assertEquals("written on clean close", rows.next().getProperty("marker"));
            assertFalse(rows.hasNext());
          }
          dropCache = (WOWCache) session.getStorage().getWriteCache();
          dropCache.pauseBackgroundFlush();
          dropFile = collectionPageFile(session, session.addCollection("pendingdatabasedrop"));
        }
        assertTrue(dropCache.exists(PageFileNames.layout28(dropFile)));
        assertFalse(Files.exists(dropCache.getRootDirectory().resolve(
            PageFileNames.layout28DiskName(dropFile))));
        manager.drop("logical");
        assertFalse(Files.exists(root.resolve("logical")));
      }
    } finally {
      if (closeCache != null) {
        closeCache.resumeBackgroundFlush();
      }
      if (dropCache != null) {
        dropCache.resumeBackgroundFlush();
      }
      FileUtils.deleteDirectory(root.toFile());
    }
  }

  private static PageFileIdentity collectionPageFile(DatabaseSessionEmbedded session, int id) {
    var collection =
        (com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2) session
            .getStorage().getCollectionInstances().stream()
            .filter(candidate -> candidate.getId() == id).findFirst().orElseThrow();
    return new PageFileIdentity(PageFileRole.RECORD_DATA,
        collection.generateCollectionConfig().getComponentId());
  }

  @Test
  public void layout28CommitRejectsAllocationWithoutChangesAndAllowsCheckpoint() throws Exception {
    assertLayout28EmptyFileRollsBack(false);
  }

  @Test
  public void layout28CommitRejectsEmptyFileEvenWhenSiblingChangesAndAllowsCheckpoint()
      throws Exception {
    assertLayout28EmptyFileRollsBack(true);
  }

  private void assertLayout28EmptyFileRollsBack(boolean changedSibling) throws Exception {
    withStorage(DatabaseType.DISK, StorageFormat.LAYOUT_28, storage -> {
      final var cache = (WOWCache) storage.getWriteCache();
      cache.pauseBackgroundFlush();
      final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9081);
      final var sibling = new PageFileIdentity(PageFileRole.RECORD_DATA, 9082);
      final var name = PageFileNames.layout28(identity);
      final var siblingName = PageFileNames.layout28(sibling);
      final var failure = assertThrows(StorageException.class,
          () -> storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            final var id = op.addFile(name, false);
            op.releasePageFromWrite(op.allocatePageForWrite(id, 0));
            if (changedSibling) {
              initStatePage(op, op.addFile(siblingName, false), 0);
            }
          }));
      assertTrue(failure.getMessage(), failure.getMessage().contains(name));
      assertNull(cache.registeredFileId(name));
      assertNull(cache.registeredFileId(siblingName));
      assertTrue(Files.notExists(cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(
          identity)), java.nio.file.LinkOption.NOFOLLOW_LINKS));
      assertTrue(Files.notExists(cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(
          sibling)), java.nio.file.LinkOption.NOFOLLOW_LINKS));
      assertNoInProgressOperationAndCheckpoint(storage);
    });
  }

  @Test
  public void layout28CommitAllowsCancelledEmptyAddAndThenCheckpoints() throws Exception {
    withStorage(DatabaseType.DISK, StorageFormat.LAYOUT_28, storage -> {
      final var cache = (WOWCache) storage.getWriteCache();
      cache.pauseBackgroundFlush();
      final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9081);
      final var name = PageFileNames.layout28(identity);
      storage.getAtomicOperationsManager()
          .executeInsideAtomicOperation(op -> op.deleteFile(op.addFile(name, false)));
      assertNull(cache.registeredFileId(name));
      assertTrue(Files.notExists(cache.getRootDirectory().resolve(PageFileNames.layout28DiskName(
          identity)), java.nio.file.LinkOption.NOFOLLOW_LINKS));
      assertNoInProgressOperationAndCheckpoint(storage);
    });
  }

  @Test
  public void layout27AndMemoryStillCommitEmptyFiles() throws Exception {
    assertEmptyFileCommits(DatabaseType.DISK, StorageFormat.LAYOUT_27);
    assertEmptyFileCommits(DatabaseType.MEMORY, StorageFormat.LAYOUT_28);
  }

  private void assertEmptyFileCommits(DatabaseType engine, StorageFormat format)
      throws Exception {
    withStorage(engine, format, storage -> {
      final var cache = storage.getWriteCache();
      final var name = "empty.pcl";
      final var id = storage.getAtomicOperationsManager()
          .calculateInsideAtomicOperation(op -> op.addFile(name, false));
      final var registered = cache.fileIdByName(name);
      assertTrue(cache.exists(name));
      assertEquals(cache.internalFileId(id), cache.internalFileId(registered));
      if (cache instanceof WOWCache disk) {
        assertTrue(Files.exists(disk.getRootDirectory().resolve(disk.nativeFileNameById(id))));
      }
      assertNoInProgressOperationAndCheckpoint(storage);
    });
  }

  private static void storeAndFlushPage(WOWCache cache, long id, long marker) throws IOException {
    var page = cache.loadOrAdd(id, 0, false);
    var stamp = page.acquireExclusiveLock();
    try {
      page.getBuffer().putLong(64, marker);
      cache.store(id, 0, page);
    } finally {
      page.releaseExclusiveLock(stamp);
      page.decrementReadersReferrer();
    }
    cache.flush(id);
  }

  private static void initStatePage(AtomicOperation op, long id, int size) throws IOException {
    final var page = op.allocatePageForWrite(id, 0);
    try {
      new PaginatedCollectionStateV2(page).setFileSize(size);
    } finally {
      op.releasePageFromWrite(page);
    }
  }

  private static void assertNoInProgressOperationAndCheckpoint(AbstractStorage storage)
      throws ReflectiveOperationException {
    final var field = AbstractStorage.class.getDeclaredField("atomicOperationsTable");
    field.setAccessible(true);
    final var table = (AtomicOperationsTable) field.get(storage);
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    storage.synch();
  }

  // A handle cannot override an explicit disk-absent logical state during force or verification.
  @Test
  public void diskAbsentLogicalStateWithHandleIsNotForcedOrVerified() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9062);
      var name = PageFileNames.layout28(identity);
      long id = cache.externalFileId(identity.internalId());
      assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.RESTORE));
      var field = WOWCache.class.getDeclaredField("files");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var handles = (ClosableLinkedContainer<Long,
          com.jetbrains.youtrackdb.internal.core.storage.fs.File>) field.get(cache);
      var handle = org.mockito.Mockito.spy(handles.remove(id));
      handles.add(id, handle);
      // Model a new disk-absent lifetime while retaining a handle to expose bad routing.
      cache.logicalFiles().forget(identity.internalId());
      cache.logicalFiles().register(name, identity.internalId(), false, 0, 0, false);
      var fsync = WOWCache.class.getDeclaredField("callFsync");
      fsync.setAccessible(true);
      fsync.set(cache, true);
      cache.syncDataFiles();
      org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).synch();
      var verification = new StringBuilder();
      assertEquals(0, cache.checkStoredPages(verification::append).length);
      assertFalse(verification.toString().contains(name));
      cache.logicalFiles().register(name, identity.internalId(), false, 0, 0, true);
    });
  }

  // A stale disk-present state with no owned handle must not crash terminal close.
  @Test
  public void closeToleratesDiskPresentLogicalStateWithoutHandle() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9059);
      cache.logicalFiles().register(PageFileNames.layout28(identity), identity.internalId(), false);
      cache.close();
      assertTrue(cache.logicalFiles().inventory().isEmpty());
    });
  }

  // Membership updates invalidate the snapshot without rebuilding it, even over many mutations.
  @Test
  public void individualMutationsDeferInventoryCopyUntilRead() throws Exception {
    var table = spy(new LogicalPageFileTable());
    for (int component = 9100; component < 10100; component++) {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, component);
      table.register(PageFileNames.layout28(identity), identity.internalId(), false);
      verify(table, never()).copyLiveNames();
    }
    var before = table.inventory();
    assertEquals(1000, before.size());
    for (int component = 9100; component < 10100; component++) {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, component);
      table.forget(identity.internalId());
      verify(table, times(1)).copyLiveNames();
    }
    assertTrue(table.inventory().isEmpty());
    assertEquals(1000, before.size());
  }

  // An executor deletion can invalidate an uncached inventory after a reader copies live names.
  // The in-flight reader may return its old copy, but later readers must not reuse that copy.
  @Test
  public void deletionBetweenInventoryCopyAndPublicationCannotCacheStaleNames() {
    var table = spy(new LogicalPageFileTable());
    var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9060);
    var name = PageFileNames.layout28(identity);
    table.register(name, identity.internalId(), false);
    doAnswer(invocation -> {
      var copy = invocation.callRealMethod();
      table.delete(name, identity.internalId());
      return copy;
    }).doCallRealMethod().when(table).copyLiveNames();

    var inFlight = table.inventory();
    assertEquals(Map.of(name, identity.internalId()), inFlight);
    assertTrue(table.deleted(identity.internalId()));
    assertNull(table.fileId(name));
    var fresh = table.inventory();
    assertTrue(fresh.isEmpty());
    org.junit.Assert.assertSame(fresh, table.inventory());
    assertEquals(Map.of(name, identity.internalId()), inFlight);
  }

  // Lazy snapshots reflect each mutation, reuse unchanged results and preserve older snapshots.
  @Test
  public void lazyInventoryRetainsReservationsAndStableSnapshots() {
    var table = new LogicalPageFileTable();
    var first = new PageFileIdentity(PageFileRole.RECORD_DATA, 9060);
    var second = new PageFileIdentity(PageFileRole.RECORD_DATA, 9061);
    var firstName = PageFileNames.layout28(first);
    var secondName = PageFileNames.layout28(second);
    table.register(firstName, first.internalId(), false);
    var before = table.inventory();
    org.junit.Assert.assertSame(before, table.inventory());
    table.delete(firstName, first.internalId());
    table.reserve(secondName, second.internalId());
    assertTrue(table.inventory().isEmpty());
    table.register(secondName, second.internalId(), false);
    assertEquals(Map.of(secondName, second.internalId()), table.inventory());
    assertEquals(Integer.valueOf(second.internalId()), table.fileId(secondName));
    table.delete(secondName, second.internalId());
    assertEquals(Map.of(firstName, first.internalId()), before);
    assertEquals(java.util.Set.of(firstName, secondName), new HashSet<>(table.deletedFiles()));
    assertThrows(UnsupportedOperationException.class, () -> before.clear());
    table.clear();
    assertTrue(table.deletedFiles().isEmpty());
  }

  // All registration overloads reject aliases and mismatched identities without mutation.
  @Test
  public void nonCanonicalRegistrationsFailBeforeMutation() throws Exception {
    withCache(cache -> {
      var a = new PageFileIdentity(PageFileRole.RECORD_DATA, 9010);
      var b = new PageFileIdentity(PageFileRole.RECORD_DATA, 9011);
      var name = PageFileNames.layout28(a);
      var id = cache.externalFileId(a.internalId());
      var wrongId = cache.externalFileId(b.internalId());
      cache.bookFileId(name, id, false);
      for (ThrowingRunnable registration : List.<ThrowingRunnable>of(
          () -> cache.addFile(name, wrongId),
          () -> cache.addFile(name, wrongId, true),
          () -> cache.bookFileId(name, wrongId, false),
          () -> cache.bookFileId(name, wrongId, true),
          () -> cache.addFile("alias.pcl"),
          () -> cache.addFile("alias.pcl", wrongId),
          () -> cache.addFile("alias.pcl", wrongId, true),
          () -> cache.bookFileId("alias.pcl"),
          () -> cache.bookFileId("alias.pcl", wrongId, false),
          () -> cache.bookFileId("alias.pcl", wrongId, true))) {
        assertRejectedUnchanged(cache, registration);
        assertReserved(cache, a);
      }
      assertRejectedUnchanged(cache,
          () -> org.junit.Assert.assertNull(cache.restoreFileById(wrongId)), false);
    });
  }

  // A rejected rename preserves canonical membership and the background force set.
  @Test
  public void rejectedRenamePreservesLogicalStateAndFsyncSet() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9063);
      var name = PageFileNames.layout28(identity);
      long id = cache.externalFileId(identity.internalId());
      assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.RESTORE));
      var field = WOWCache.class.getDeclaredField("files");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var handles = (ClosableLinkedContainer<Long,
          com.jetbrains.youtrackdb.internal.core.storage.fs.File>) field.get(cache);
      var handle = org.mockito.Mockito.spy(handles.remove(id));
      handles.add(id, handle);
      assertRejectedUnchanged(cache, () -> cache.renameFile(id, "alias.pcl"));
      assertRejectedUnchanged(cache, () -> cache.renameFile(id, name));
      assertLive(cache, identity, false);
      var fsync = WOWCache.class.getDeclaredField("callFsync");
      fsync.setAccessible(true);
      fsync.set(cache, true);
      forceOnFlushExecutor(cache);
      org.mockito.Mockito.verify(handle).synch();
    });
  }

  // Layout 28 ignores positive and deleted aliases. Layout 27 retains map-backed handles.
  @Test
  public void openIgnoresPositiveAliasesAndNegativeEvidence() throws Exception {
    withStandaloneCache(cache -> {
      cache.close();
      cache.setStorageFormat(StorageFormat.LAYOUT_27);
      cache.loadRegisteredFiles();
      long aliasId = cache.addFile("alias.pcl");
      assertTrue(cache.physicalFileExists(aliasId));
      cache.close();
      cache.setStorageFormat(StorageFormat.LAYOUT_28);
      cache.loadRegisteredFiles();
      assertTrue(cache.files().isEmpty());
      var handles = snapshot(cache, "idNameMap");
      assertTrue(handles.isEmpty());
      assertFalse(cache.exists(aliasId));
      assertFalse(cache.exists("alias.pcl"));
      cache.close();
      cache.setStorageFormat(StorageFormat.LAYOUT_27);
      cache.loadRegisteredFiles();
      cache.deleteFile(aliasId);
      assertFalse(cache.physicalFileExists(aliasId));
      cache.close();
      cache.setStorageFormat(StorageFormat.LAYOUT_28);
      cache.loadRegisteredFiles();
      assertFalse(cache.registrationEvidence().negativeNames().contains("alias.pcl"));
      assertTrue(cache.files().isEmpty());
    });
  }

  // A blocked map path cannot prevent eager creation or change durability classification.
  @Test
  public void blockedNameIdPathDoesNotAffectDurabilityRegistry() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-failure";
    var manager = (YouTrackDBImpl) YourTracks.instance(directory);
    try {
      try (var selection = StorageBirthTestSupport.selectBirthFormat(
          manager, "logical", StorageFormat.LAYOUT_28)) {
        manager.create("logical", DatabaseType.DISK, "admin", "adminpwd", "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        var cache = (WOWCache) session.getStorage().getWriteCache();
        var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9003);
        var name = PageFileNames.layout28(identity);
        var id = cache.externalFileId(identity.internalId());
        cache.bookFileId(name, id, false);
        var pathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
        pathField.setAccessible(true);
        var originalPath = pathField.get(cache);
        try {
          // A directory cannot be opened as the writable name-id registry file.
          pathField.set(cache, ((DiskStorage) session.getStorage()).getStoragePath());
          cache.addFile(name, id, true);
        } finally {
          pathField.set(cache, originalPath);
        }
        assertTrue(cache.isNonDurable(id));
        assertPending(cache, identity, true);
        assertFalse(cache.logicalFiles().reserved(identity.internalId()));
        cache.deleteFile(id);
        cache.addFile(name, id, true);
        assertTrue(cache.isNonDurable(id));
        assertPending(cache, identity, true);
      }
    } finally {
      manager.close();
      FileUtils.deleteDirectory(Path.of(directory).toFile());
    }
  }

  // A cold reopen loads durability independently of map records. Explicit admission restores
  // unconfigured files with their persisted durability classification.
  @Test
  public void reopenPreservesLiveCanonicalDurability() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-durability-open";
    var nonDurable = new PageFileIdentity(PageFileRole.RECORD_DATA, 9020);
    var durable = new PageFileIdentity(PageFileRole.RECORD_DATA, 9021);
    try {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(directory)) {
        try (var selection = StorageBirthTestSupport.selectBirthFormat(
            manager, "logical", StorageFormat.LAYOUT_28)) {
          manager.create("logical", DatabaseType.DISK, "admin", "adminpwd", "admin");
        }
        try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
          var cache = (WOWCache) session.getStorage().getWriteCache();
          for (var identity : List.of(nonDurable, durable)) {
            var name = PageFileNames.layout28(identity);
            var id = cache.externalFileId(identity.internalId());
            cache.addFile(name, id, identity.equals(nonDurable));
            assertPending(cache, identity, identity.equals(nonDurable));
            assertTrue(cache.resolveRecoveryFile(name, id, RecoveryFilePermission.RESTORE));
            assertLive(cache, identity, identity.equals(nonDurable));
          }
        }
      }
      try (var manager = (YouTrackDBImpl) YourTracks.instance(directory);
          var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        var cache = (WOWCache) session.getStorage().getWriteCache();
        assertTrue(cache.isNonDurable(cache.externalFileId(nonDurable.internalId())));
        assertFalse(cache.isNonDurable(cache.externalFileId(durable.internalId())));
        assertAbsent(cache, nonDurable);
        assertAbsent(cache, durable);
        assertTrue(cache.admitExpectedFile(nonDurable));
        assertTrue(cache.admitExpectedFile(durable));
        assertLive(cache, nonDurable, true);
        assertLive(cache, durable, false);
      }
    } finally {
      FileUtils.deleteDirectory(Path.of(directory).toFile());
    }
  }

  // A missing alias backing file admits neither a handle nor logical seeding evidence.
  @Test
  public void missingAliasBackingFileAdmitsNoLogicalEvidence() throws Exception {
    withStandaloneCache(cache -> {
      cache.close();
      cache.setStorageFormat(StorageFormat.LAYOUT_27);
      cache.loadRegisteredFiles();
      long aliasId = cache.addFile("missing-alias.pcl");
      var field = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
      field.setAccessible(true);
      var directory = ((Path) field.get(cache)).getParent();
      var backingFile = directory.resolve(cache.nativeFileNameById(aliasId));
      cache.close();
      Files.delete(backingFile);
      cache.setStorageFormat(StorageFormat.LAYOUT_28);
      cache.loadRegisteredFiles();
      assertFalse(cache.registrationEvidence().negativeNames().contains("missing-alias.pcl"));
      assertTrue(cache.registrationEvidence().positiveNames().isEmpty());
      assertTrue(cache.files().isEmpty());
      assertFalse(cache.exists("missing-alias.pcl"));
      assertFalse(cache.exists(aliasId));
      assertEquals(-1, cache.fileIdByName("missing-alias.pcl"));
      assertTrue(snapshot(cache.logicalFiles(), "states").isEmpty());
      var handlesField = WOWCache.class.getDeclaredField("files");
      handlesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var handles = (ClosableLinkedContainer<Long,
          com.jetbrains.youtrackdb.internal.core.storage.fs.File>) handlesField.get(cache);
      assertNull(handles.get(aliasId));
    });
  }

  // Log-open failure before file admission leaves no logical state. Cleanup is idempotent,
  // and a retry can explicitly admit the preserved file. Closing that inventory releases its handle.
  @Test
  public void failedRegistryLoadCleanupClearsLogicalState() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9022);
      var name = PageFileNames.layout28(identity);
      assertTrue(cache.resolveRecoveryFile(name, cache.externalFileId(identity.internalId()),
          RecoveryFilePermission.RESTORE));
      assertLive(cache, identity, false);
      cache.close();
      assertTrue(snapshot(cache.logicalFiles(), "states").isEmpty());
      var logField = WOWCache.class.getDeclaredField("doubleWriteLog");
      logField.setAccessible(true);
      var originalLog = logField.get(cache);
      var failingLog = mock(DoubleWriteLog.class);
      var failure = new IOException("Injected log open failure before file admission");
      doAnswer(invocation -> {
        assertNull(cache.logicalFiles().live(identity.internalId()));
        throw failure;
      }).when(failingLog).open(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.anyInt());
      try {
        logField.set(cache, failingLog);
        assertEquals(failure, assertThrows(IOException.class, cache::loadRegisteredFiles));
        assertNull(cache.logicalFiles().live(name, identity.internalId()));
        var handlesField = WOWCache.class.getDeclaredField("files");
        handlesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var handles = (ClosableLinkedContainer<Long, ?>) handlesField.get(cache);
        cache.close();
        assertTrue(snapshot(cache.logicalFiles(), "states").isEmpty());
        assertNull(handles.get(cache.externalFileId(identity.internalId())));
        cache.close();
        assertTrue(snapshot(cache.logicalFiles(), "states").isEmpty());
      } finally {
        logField.set(cache, originalLog);
      }
      cache.loadRegisteredFiles();
      assertAbsent(cache, identity);
      assertTrue(cache.admitExpectedFile(identity));
      assertLive(cache, identity, false);
      var handlesField = WOWCache.class.getDeclaredField("files");
      handlesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var handles = (ClosableLinkedContainer<Long,
          com.jetbrains.youtrackdb.internal.core.storage.fs.File>) handlesField.get(cache);
      var handle = handles.get(cache.externalFileId(identity.internalId()));
      cache.close();
      assertFalse(handle.isOpen());
      assertNull(handles.get(cache.externalFileId(identity.internalId())));
      cache.loadRegisteredFiles();
      assertTrue(cache.admitExpectedFile(identity));
      assertLive(cache, identity, false);
    });
  }

  private static void forceOnFlushExecutor(WOWCache cache) throws Exception {
    var force = WOWCache.class.getDeclaredMethod("fsyncFiles", long.class);
    force.setAccessible(true);
    YouTrackDBEnginesManager.instance().getWowCacheFlushExecutor().submit(() -> {
      force.invoke(cache, 1L);
      return null;
    }).get(10, TimeUnit.SECONDS);
  }

  // Isolate cache lifecycle failures from storage-owned read pages and background checkpointing.
  private void withStandaloneCache(CacheAction action) throws Exception {
    YouTrackDBEnginesManager.startUp(false);
    var path = Path.of(DbTestBase.getBaseDirectoryPathStr(getClass()) + "-failed-open");
    Files.createDirectories(path);
    var pool = new ByteBufferPool(4096);
    var io = Executors.newCachedThreadPool();
    try {
      var wal = new CASDiskWriteAheadLog("logical", path, path,
          ContextConfiguration.WAL_DEFAULT_NAME, 12000, 128, null, null,
          Integer.MAX_VALUE, Integer.MAX_VALUE, 25, true, Locale.US,
          -1, 1000, false, false, true, 10);
      try {
        var cache = new WOWCache(4096, false, pool, wal, new DoubleWriteLogNoOP(),
            0, 10000, 128L * 4096, path, "logical", new ClosableLinkedContainer<>(128),
            1, ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
            ChecksumMode.StoreAndVerify, null, null, false, io);
        // Preserve the assertion failure if terminal cleanup also fails.
        try (AutoCloseable ignored = cache::close) {
          cache.setStorageFormat(StorageFormat.LAYOUT_28);
          try (var maps = StorageBirthTestSupport.poisonSessionMaps(cache)) {
            cache.loadRegisteredFiles();
          }
          action.run(cache);
        }
      } finally {
        wal.delete();
      }
    } finally {
      io.shutdownNow();
      assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
      pool.clear();
      FileUtils.deleteDirectory(path.toFile());
    }
  }

  @FunctionalInterface
  private interface CacheAction {
    void run(WOWCache cache) throws Exception;
  }

  @FunctionalInterface
  private interface StorageAction {
    void run(AbstractStorage storage) throws Exception;
  }

  private void withCache(CacheAction action) throws Exception {
    withStorage(DatabaseType.DISK, StorageFormat.LAYOUT_28,
        storage -> action.run((WOWCache) storage.getWriteCache()));
  }

  private void withStorage(DatabaseType engine, StorageFormat format, StorageAction action)
      throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-registration";
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory)) {
      try (var selection = StorageBirthTestSupport.selectBirthFormat(manager, "logical", format)) {
        manager.create("logical", engine, "admin", "adminpwd", "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        action.run(session.getStorage());
      }
    } finally {
      FileUtils.deleteDirectory(Path.of(directory).toFile());
    }
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Map<K, V> snapshot(Object owner, String fieldName) throws Exception {
    var field = owner.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return new HashMap<>((Map<K, V>) field.get(owner));
  }

  private static void assertRejectedUnchanged(WOWCache cache, ThrowingRunnable registration)
      throws Exception {
    assertRejectedUnchanged(cache, registration, true);
  }

  private static void assertRejectedUnchanged(WOWCache cache, ThrowingRunnable registration,
      boolean throwsException) throws Exception {
    var names = snapshot(cache, "nameIdMap");
    var ids = snapshot(cache, "idNameMap");
    var states = snapshot(cache.logicalFiles(), "states");
    var pathField = WOWCache.class.getDeclaredField("nameIdMapHolderPath");
    pathField.setAccessible(true);
    var path = (Path) pathField.get(cache);
    var registry = Files.exists(path) ? Files.readAllBytes(path) : null;
    java.util.Set<Path> files;
    try (var entries = Files.list(path.getParent())) {
      files = entries.collect(java.util.stream.Collectors.toSet());
    }
    if (throwsException) {
      assertThrows(StorageException.class, registration);
    } else {
      try {
        registration.run();
      } catch (Throwable failure) {
        throw new AssertionError(failure);
      }
    }
    assertEquals(names, snapshot(cache, "nameIdMap"));
    assertEquals(ids, snapshot(cache, "idNameMap"));
    assertEquals(states, snapshot(cache.logicalFiles(), "states"));
    org.junit.Assert.assertArrayEquals(registry,
        Files.exists(path) ? Files.readAllBytes(path) : null);
    try (var entries = Files.list(path.getParent())) {
      assertEquals(files, entries.collect(java.util.stream.Collectors.toSet()));
    }
    assertOracle(cache);
  }

  // The oracle compares complete live sets, not just the identity used by the current step.
  private static void assertOracle(WOWCache cache) {
    try {
      Map<Integer, LogicalPageFileTable.State> states = snapshot(cache.logicalFiles(), "states");
      var visible = new HashMap<String, Long>();
      states.forEach((id, state) -> {
        if (state.kind() == LogicalPageFileTable.Kind.LIVE) {
          visible.put(PageFileNames.layout28(state.identity()), cache.externalFileId(id));
        }
      });
      assertEquals(visible, cache.files());
      for (var state : states.values()) {
        var name = PageFileNames.layout28(state.identity());
        var live = state.kind() == LogicalPageFileTable.Kind.LIVE;
        assertEquals(live, cache.exists(name));
        assertEquals(visible.getOrDefault(name, -1L).longValue(), cache.fileIdByName(name));
        assertEquals(live, cache.exists(cache.externalFileId(state.identity().internalId())));
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void assertAbsent(WOWCache cache, PageFileIdentity identity) {
    var name = PageFileNames.layout28(identity);
    assertFalse(cache.files().containsKey(name));
    assertFalse(cache.exists(name));
    assertFalse(cache.exists(cache.externalFileId(identity.internalId())));
    assertEquals(-1, cache.fileIdByName(name));
    assertNull(cache.logicalFiles().live(PageFileNames.layout28(identity), identity.internalId()));
    assertFalse(cache.logicalFiles().reserved(identity.internalId()));
    assertFalse(cache.logicalFiles().deleted(identity.internalId()));
    assertOracle(cache);
  }

  private static void assertDeleted(WOWCache cache, PageFileIdentity identity) {
    assertNull(cache.logicalFiles().live(PageFileNames.layout28(identity), identity.internalId()));
    assertFalse(cache.logicalFiles().reserved(identity.internalId()));
    assertTrue(cache.logicalFiles().deleted(identity.internalId()));
    assertOracle(cache);
  }

  private static void assertReserved(WOWCache cache, PageFileIdentity identity) {
    assertOracle(cache);
    assertFalse(cache.logicalFiles().deleted(identity.internalId()));
    assertNull(cache.logicalFiles().live(PageFileNames.layout28(identity), identity.internalId()));
    assertTrue(cache.logicalFiles().reserved(identity.internalId()));
  }

  private static void assertPending(WOWCache cache, PageFileIdentity identity,
      boolean nonDurable) {
    assertLive(cache, identity, nonDurable, false);
  }

  private static void assertLive(WOWCache cache, PageFileIdentity identity, boolean nonDurable) {
    assertLive(cache, identity, nonDurable, true);
  }

  private static void assertLive(WOWCache cache, PageFileIdentity identity, boolean nonDurable,
      boolean diskPresent) {
    assertOracle(cache);
    assertFalse(cache.logicalFiles().reserved(identity.internalId()));
    assertFalse(cache.logicalFiles().deleted(identity.internalId()));
    var state = cache.logicalFiles().live(PageFileNames.layout28(identity), identity.internalId());
    assertNotNull(state);
    assertEquals(identity, state.identity());
    assertEquals(diskPresent, state.diskPresent());
    assertEquals(nonDurable, state.nonDurable());
    assertEquals(0, state.logicalExtent());
    assertEquals(0, state.diskExtent());
    assertEquals(Integer.valueOf(identity.internalId()),
        cache.registeredFileId(PageFileNames.layout28(identity)));
  }
}
