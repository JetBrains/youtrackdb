package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
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

        // A booked canonical id also survives the current load-from-disk registration path.
        var loadedName = PageFileNames.layout28(loaded);
        var loadedId = cache.externalFileId(loaded.internalId());
        cache.bookFileId(loadedName, loadedId, false);
        assertReserved(cache, loaded);
        Files.createFile(((DiskStorage) session.getStorage()).getStoragePath()
            .resolve(PageFileNames.layout28DiskName(loaded)));
        assertEquals(loadedId, cache.loadFile(loadedName));
        assertLive(cache, loaded, false);
        // A real rename to an alias keeps the physical file live but removes canonical membership.
        cache.renameFile(loadedId, "renamed.pcl");
        assertEquals(loadedId, cache.fileIdByName("renamed.pcl"));
        assertTrue(cache.exists(loadedId));
        assertNull(cache.registeredFileId(loadedName));
        assertNull(cache.logicalFiles().live(loadedName, loaded.internalId()));
        assertAbsent(cache, loaded);
        cache.renameFile(loadedId, loadedName);
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
      assertAbsent(cache, identity);
      assertAbsent(cache, loaded);
      assertAbsent(cache, new PageFileIdentity(PageFileRole.RECORD_DATA, 9002));
      manager = (YouTrackDBImpl) YourTracks.instance(directory);
      try (var session = (DatabaseSessionEmbedded) manager.open("logical", "admin", "adminpwd")) {
        cache = (WOWCache) session.getStorage().getWriteCache();
        assertAbsent(cache, identity);
        assertAbsent(cache, loaded);
        cache.addFile(name);
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

  // RG2: alias displacement must not erase the canonical session reservation for the same id.
  @Test
  public void renameOntoAliasPreservesCanonicalReservation() throws Exception {
    withCache(cache -> {
      var a = new PageFileIdentity(PageFileRole.RECORD_DATA, 9004);
      var b = new PageFileIdentity(PageFileRole.RECORD_DATA, 9005);
      var name = PageFileNames.layout28(a);
      var id = cache.externalFileId(a.internalId());
      var otherId = cache.externalFileId(b.internalId());
      cache.addFile("alias.pcl", id);
      assertAbsent(cache, a);
      cache.deleteFile(id);
      assertAbsent(cache, a);
      cache.addFile(name, id);
      assertLive(cache, a, false);
      cache.deleteFile(id);
      assertDeleted(cache, a);
      cache.bookFileId(name);
      assertReserved(cache, a);
      cache.addFile(PageFileNames.layout28(b), otherId);
      assertLive(cache, b, false);
      cache.renameFile(otherId, "alias.pcl");
      assertReserved(cache, a);
      assertAbsent(cache, b);
      cache.deleteFile(otherId);
      assertReserved(cache, a);
      assertAbsent(cache, b);
    });
  }

  // RG3 and option A reject each canonical mismatched registration without touching inventory.
  // The same overloads keep accepting aliases. Rename supplies deliberately malformed evidence.
  @Test
  public void mismatchedRegistrationsFailBeforeMutationButAliasesRemainSupported()
      throws Exception {
    withCache(cache -> {
      var a = new PageFileIdentity(PageFileRole.RECORD_DATA, 9010);
      var b = new PageFileIdentity(PageFileRole.RECORD_DATA, 9011);
      var name = PageFileNames.layout28(a);
      var id = cache.externalFileId(a.internalId());
      var wrongId = cache.externalFileId(b.internalId());
      cache.bookFileId(name, id, false);
      assertReserved(cache, a);
      for (ThrowingRunnable registration : List.<ThrowingRunnable>of(
          () -> cache.addFile(name, wrongId),
          () -> cache.addFile(name, wrongId, true),
          () -> cache.bookFileId(name, wrongId, false),
          () -> cache.bookFileId(name, wrongId, true))) {
        assertRejectedUnchanged(cache, registration);
        assertReserved(cache, a);
      }
      var unbookedName = PageFileNames.layout28(b);
      assertRejectedUnchanged(cache, () -> cache.addFile(unbookedName));
      assertRejectedUnchanged(cache, () -> cache.bookFileId(unbookedName));

      cache.addFile("wrong.pcl", wrongId);
      assertAbsent(cache, b);
      cache.renameFile(wrongId, unbookedName);
      assertLive(cache, b, false);
      cache.renameFile(wrongId, name);
      assertAbsent(cache, b);
      // A wrong-id canonical rename is an alias event, so the existing reservation survives.
      assertTrue(cache.logicalFiles().reserved(a.internalId()));
      cache.deleteFile(wrongId);
      assertAbsent(cache, b);
      for (ThrowingRunnable registration : List.<ThrowingRunnable>of(
          () -> cache.addFile(name),
          () -> cache.bookFileId(name))) {
        assertRejectedUnchanged(cache, registration);
      }

      // Identity recovery refuses tombstones with null, without mutating any staged state.
      assertRejectedUnchanged(cache,
          () -> org.junit.Assert.assertNull(cache.restoreFileById(wrongId)), false);

      var aliasId = cache.bookFileId("booked.pcl");
      assertOracle(cache);
      cache.addFile("booked.pcl");
      assertOracle(cache);
      cache.deleteFile(aliasId);
      assertOracle(cache);
      var suppliedId = cache.externalFileId(
          new PageFileIdentity(PageFileRole.RECORD_DATA, 9012).internalId());
      cache.bookFileId("supplied.pcl", suppliedId, false);
      assertOracle(cache);
      cache.addFile("supplied.pcl", suppliedId);
      assertOracle(cache);
      cache.deleteFile(suppliedId);
      assertOracle(cache);
      cache.addFile("durable.pcl", suppliedId, true);
      assertOracle(cache);
    });
  }

  // A failed name-id write leaves a live map entry but must not invent non-durable membership.
  @Test
  public void failedNameIdWriteKeepsShadowDurabilityAlignedWithRegistry() throws Exception {
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
          assertThrows(IOException.class, () -> cache.addFile(name, id, true));
        } finally {
          pathField.set(cache, originalPath);
        }
        assertFalse(cache.isNonDurable(id));
        assertLive(cache, identity, false);
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

  // A clean cold reopen rebuilds live canonical entries with both persisted durability values.
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
        assertLive(cache, nonDurable, true);
        assertLive(cache, durable, false);
      }
    } finally {
      FileUtils.deleteDirectory(Path.of(directory).toFile());
    }
  }

  // Registry loading rebuilds live state before the log opens. Failed-open close must clear it.
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
      var failure = new IOException("Injected log open failure after logical state rebuild");
      doAnswer(invocation -> {
        assertNotNull(cache.logicalFiles().live(name, identity.internalId()));
        throw failure;
      }).when(failingLog).open(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.anyInt());
      try {
        logField.set(cache, failingLog);
        assertEquals(failure, assertThrows(IOException.class, cache::loadRegisteredFiles));
        assertNotNull(cache.logicalFiles().live(name, identity.internalId()));
        cache.close();
        assertTrue(snapshot(cache.logicalFiles(), "states").isEmpty());
        cache.close();
        assertTrue(snapshot(cache.logicalFiles(), "states").isEmpty());
      } finally {
        logField.set(cache, originalLog);
      }
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
        try {
          cache.setStorageFormat(StorageFormat.LAYOUT_28);
          cache.loadRegisteredFiles();
          action.run(cache);
        } finally {
          cache.close();
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
    var registry = Files.readAllBytes(path);
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
    org.junit.Assert.assertArrayEquals(registry, Files.readAllBytes(path));
    try (var entries = Files.list(path.getParent())) {
      assertEquals(files, entries.collect(java.util.stream.Collectors.toSet()));
    }
    assertOracle(cache);
  }

  // The oracle compares complete live sets, not just the identity used by the current step.
  private static void assertOracle(WOWCache cache) {
    try {
      Map<String, Integer> names = snapshot(cache, "nameIdMap");
      Map<Integer, LogicalPageFileTable.State> states = snapshot(cache.logicalFiles(), "states");
      var expected = new HashSet<Integer>();
      names.forEach((name, id) -> PageFileNames.parseLayout28(name)
          .filter(identity -> identity.internalId() == id)
          .ifPresent(identity -> expected.add(id)));
      var actual = new HashSet<Integer>();
      states.forEach((id, state) -> {
        if (state.kind() == LogicalPageFileTable.Kind.LIVE) {
          actual.add(id);
        } else {
          var registered = names.get(PageFileNames.layout28(state.identity()));
          // Wrong-id entries are aliases and are outside the map-agreement contract.
          if (registered != null && Math.abs(registered) == id) {
            assertEquals(Integer.valueOf(-id), registered);
          }
        }
      });
      assertEquals(expected, actual);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void assertAbsent(WOWCache cache, PageFileIdentity identity) {
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
