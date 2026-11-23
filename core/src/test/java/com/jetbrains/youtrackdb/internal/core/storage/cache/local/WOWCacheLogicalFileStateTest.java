package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
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
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
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
        cache.addFile(createdName);
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
      assertLive(cache, identity, false);
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
        assertLive(cache, identity, false);

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
        assertLive(cache, identity, false);
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
      cache.addFile(name, id);
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
          long id = cache.addFile(PageFileNames.layout28(identity));
          long kept = cache.addFile(PageFileNames.layout28(survivor));
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
          var force = WOWCache.class.getDeclaredMethod("fsyncFiles");
          force.setAccessible(true);
          force.invoke(cache);
          verify(handle, times(callFsync ? 1 : 0)).synch();
          assertFalse(Files.exists(map));
          cache.deleteFile(id);
          assertDeleted(cache, identity);
          assertFalse(Files.exists(disk));
          cache.close();
          assertFalse(Files.exists(map));
          // Closed-cache force must not fall back to either legacy map.
          force.invoke(cache);
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
      long id = cache.addFile(PageFileNames.layout28(identity));
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
      long id = cache.addFile(PageFileNames.layout28(identity));
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
      long id = cache.addFile(name);
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
        cache.addFile(PageFileNames.layout28(identity), id);
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
        var force = WOWCache.class.getDeclaredMethod("fsyncFiles");
        force.setAccessible(true);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
          try {
            force.invoke(cache);
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        }).get();
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
      cache.addFile(PageFileNames.layout28(identity), id);
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

  // A handle cannot override an explicit disk-absent logical state during force or verification.
  @Test
  public void diskAbsentLogicalStateWithHandleIsNotForcedOrVerified() throws Exception {
    withStandaloneCache(cache -> {
      var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9062);
      var name = PageFileNames.layout28(identity);
      long id = cache.externalFileId(identity.internalId());
      cache.addFile(name, id);
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
      cache.addFile(name, id);
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
      var force = WOWCache.class.getDeclaredMethod("fsyncFiles");
      force.setAccessible(true);
      force.invoke(cache);
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
        assertLive(cache, identity, true);
        assertFalse(cache.logicalFiles().reserved(identity.internalId()));
        cache.deleteFile(id);
        cache.addFile(name, id, true);
        assertTrue(cache.isNonDurable(id));
        assertLive(cache, identity, true);
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
      cache.addFile(name, cache.externalFileId(identity.internalId()));
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

  private void withCache(CacheAction action) throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-registration";
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory)) {
      try (var selection = StorageBirthTestSupport.selectBirthFormat(
          manager, "logical", StorageFormat.LAYOUT_28)) {
        manager.create("logical", DatabaseType.DISK, "admin", "adminpwd", "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        action.run((WOWCache) session.getStorage().getWriteCache());
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

  private static void assertLive(WOWCache cache, PageFileIdentity identity, boolean nonDurable) {
    assertOracle(cache);
    assertFalse(cache.logicalFiles().reserved(identity.internalId()));
    assertFalse(cache.logicalFiles().deleted(identity.internalId()));
    var state = cache.logicalFiles().live(PageFileNames.layout28(identity), identity.internalId());
    assertNotNull(state);
    assertEquals(identity, state.identity());
    assertTrue(state.diskPresent());
    assertEquals(nonDurable, state.nonDurable());
    assertEquals(0, state.logicalExtent());
    assertEquals(0, state.diskExtent());
    assertEquals(Integer.valueOf(identity.internalId()),
        cache.registeredFileId(PageFileNames.layout28(identity)));
  }
}
