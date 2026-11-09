package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ComponentIdAllocationTest {

  private YouTrackDBImpl ytdb;
  private String directory;

  @Before
  public void setUp() {
    directory = DbTestBase.getBaseDirectoryPathStr(getClass());
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
  }

  @After
  public void tearDown() throws Exception {
    ytdb.close();
    FileUtils.deleteDirectory(new File(directory));
  }

  private void create(StorageFormat format, DatabaseType type) throws Exception {
    try (var ignored = StorageBirthTestSupport.selectBirthFormat(ytdb, "counters", format)) {
      ytdb.create("counters", type, "admin", "adminpwd", "admin");
    }
    try (var session = open()) {
      assertEquals(format == StorageFormat.LAYOUT_26 ? 26 : 25,
          session.getStorage().getStorageFormat().layoutVersion());
    }
  }

  private DatabaseSessionEmbedded open() {
    return (DatabaseSessionEmbedded) ytdb.open("counters", "admin", "adminpwd");
  }

  private static CollectionBasedStorageConfiguration config(AbstractStorage storage) {
    return (CollectionBasedStorageConfiguration) storage.configuration;
  }

  private static int counter(AbstractStorage storage, PageFileKind kind) throws Exception {
    // Catch expected corruption inside the operation so the test can repair its fixture.
    StorageException[] failure = new StorageException[1];
    int value = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(op -> {
      try {
        return config(storage).getComponentIdCounter(kind, op);
      } catch (StorageException e) {
        failure[0] = e;
        return -1;
      }
    });
    if (failure[0] != null) {
      throw failure[0];
    }
    return value;
  }

  private static int allocate(AbstractStorage storage, PageFileKind kind) throws Exception {
    storage.stateLock.writeLock().lock();
    try {
      // Expected refusal must not poison the storage before the next boundary assertion.
      StorageException[] failure = new StorageException[1];
      int value = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(op -> {
        try {
          return kind == PageFileKind.INDEX_ENGINE
              ? storage.allocateIndexEngineFileBaseId(op) : storage.allocateComponentId(kind, op);
        } catch (StorageException e) {
          failure[0] = e;
          return -1;
        }
      });
      if (failure[0] != null) {
        throw failure[0];
      }
      return value;
    } finally {
      storage.stateLock.writeLock().unlock();
    }
  }

  // Birth allocates distinct collection identities and reserves configuration zero in both profiles.
  @Test
  public void birthReservesFixedConfigurationIdentityInDiskAndMemory() throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      create(StorageFormat.LAYOUT_26, type);
      try (var session = open()) {
        var storage = session.getStorage();
        assertEquals(26, storage.getStorageFormat().layoutVersion());
        var ids = new HashSet<Integer>();
        for (var collection : storage.getCollectionInstances()) {
          var entry = storedCollection(storage, collection.getId());
          assertEquals(1, entryVersion(storage, collection.getId()));
          assertTrue(entry.getComponentId() > 0);
          assertTrue(ids.add(entry.getComponentId()));
          assertEquals(entry.getComponentId(), runtimeConfig(collection).getComponentId());
        }
        int birthCount = ids.size();
        assertTrue("Birth must create collections", birthCount > 0);
        assertEquals(birthCount, counter(storage, PageFileKind.COLLECTION));
        assertEquals(0, counter(storage, PageFileKind.CONFIGURATION));
        assertTrue(counter(storage, PageFileKind.INDEX_ENGINE) > 0);
        assertEquals(0, PageFileIdentity.CONFIGURATION_COMPONENT_ID);
        for (var role : PageFileRole.values()) {
          if (role.kind() == PageFileKind.CONFIGURATION) {
            assertTrue(new PageFileIdentity(role, 0).internalId() > 0);
          }
        }
        assertEquals(birthCount + 1, allocate(storage, PageFileKind.COLLECTION));
        assertEquals(1, allocate(storage, PageFileKind.CONFIGURATION));
      }
      ytdb.drop("counters");
    }
  }

  // Real engine creation must advance the file base id and durable floor together in layout 26.
  @Test
  public void layout26IndexCreationPersistsLiteralFileBaseIdsAndFloor() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(26, storage.getStorageFormat().layoutVersion());
      var cls = session.getMetadata().getSchema().createClass("CounterIndex");
      cls.createProperty("first", PropertyType.STRING);
      cls.createProperty("second", PropertyType.STRING);
      // A known floor makes the expectations independent of security bootstrap engine counts.
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> config(storage).setComponentIdCounter(PageFileKind.INDEX_ENGINE, op, 100));
      cls.createIndex("CounterIndex.first", SchemaClass.INDEX_TYPE.UNIQUE, "first");
      var first = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> config(storage).getIndexEngine("CounterIndex.first", -1, op));
      assertEquals(101, first.getFileBaseId());
      assertEquals(101, (int) storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> config(storage).getIndexEngineFileBaseIdFloor(op)));
      assertEquals(101, counter(storage, PageFileKind.INDEX_ENGINE));

      cls.createIndex("CounterIndex.second", SchemaClass.INDEX_TYPE.UNIQUE, "second");
      var second = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> config(storage).getIndexEngine("CounterIndex.second", -1, op));
      assertEquals(102, second.getFileBaseId());
      assertEquals(102, (int) storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> config(storage).getIndexEngineFileBaseIdFloor(op)));
      assertEquals(102, counter(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // Another thread's write lock must not admit allocation. Abortable acquisition must admit its owner.
  @Test
  public void allocationRequiresCurrentWriteOwnerIncludingAbortableAcquisition() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open(); var executor = Executors.newSingleThreadExecutor()) {
      var storage = session.getStorage();
      int before = counter(storage, PageFileKind.COLLECTION);
      storage.stateLock.writeLock().lock();
      try {
        executor.submit(() -> assertThrows(AssertionError.class,
            () -> storage.allocateComponentId(PageFileKind.COLLECTION, null)))
            .get(10, TimeUnit.SECONDS);
      } finally {
        storage.stateLock.writeLock().unlock();
      }
      assertEquals(before, counter(storage, PageFileKind.COLLECTION));
      assertTrue(storage.stateLock.exclusiveLockWithAbort(() -> false, 1000000));
      try {
        assertEquals(before + 1,
            (int) storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
                op -> storage.allocateComponentId(PageFileKind.COLLECTION, op)));
      } finally {
        storage.stateLock.exclusiveUnlock();
      }
      assertEquals(before + 1, counter(storage, PageFileKind.COLLECTION));
    }
  }

  // Raw property reads distinguish an absent layout 25 counter from a default or cache value.
  @Test
  public void layout25HasNoNewPropertiesAndKeepsIntegerIndexCeiling() throws Exception {
    create(StorageFormat.LAYOUT_25, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(25, storage.getStorageFormat().layoutVersion());
      var read = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
          "readProperty", String.class, AtomicOperation.class);
      read.setAccessible(true);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        assertNull(invoke(read, config(storage), "collectionComponentIdCounter", op));
        assertNull(invoke(read, config(storage), "configurationComponentIdCounter", op));
      });
      assertThrows(StorageException.class, () -> counter(storage, PageFileKind.COLLECTION));
      setHwm(storage, "indexEngineFileBaseIdHwm", 2147483646L);
      assertEquals(2147483647, allocate(storage, PageFileKind.INDEX_ENGINE));
      assertThrows(StorageException.class, () -> allocate(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // Every kind persists the highest issued value and a real new storage object resumes above it.
  @Test
  public void everyKindPersistsAcrossRealCloseAndReopen() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    int[] issued = new int[3];
    try (var session = open()) {
      var storage = session.getStorage();
      for (var kind : PageFileKind.values()) {
        issued[kind.ordinal()] = allocate(storage, kind);
        assertEquals(issued[kind.ordinal()], counter(storage, kind));
      }
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(26, storage.getStorageFormat().layoutVersion());
      for (var kind : PageFileKind.values()) {
        assertEquals(issued[kind.ordinal()], counter(storage, kind));
        assertEquals(issued[kind.ordinal()] + 1, allocate(storage, kind));
      }
    }
  }

  // A real atomic rollback restores each durable counter but burns the issued live value.
  @Test
  public void rollbackBurnsIdsForEveryKindInTheSameStorageObject() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      for (var kind : PageFileKind.values()) {
        int before = counter(storage, kind);
        storage.stateLock.writeLock().lock();
        try {
          assertThrows(ConfigurationException.class,
              () -> storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
                assertEquals(before + 1, storage.allocateComponentId(kind, op));
                throw new ConfigurationException("forced rollback");
              }));
        } finally {
          storage.stateLock.writeLock().unlock();
        }
        assertEquals(before, counter(storage, kind));
        assertEquals(before + 2, allocate(storage, kind));
        assertEquals(before + 2, counter(storage, kind));
      }
    }
  }

  // The final 25-bit id succeeds, then repeated exhaustion neither wraps nor changes the counter.
  @Test
  public void everyKindStopsAtThe25BitCeiling() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      for (var kind : PageFileKind.values()) {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> config(storage).setComponentIdCounter(kind, op, 33554430));
        assertEquals(33554431, allocate(storage, kind));
        for (int attempt = 0; attempt < 2; attempt++) {
          assertThrows(StorageException.class, () -> allocate(storage, kind));
          assertEquals(33554431, counter(storage, kind));
        }
      }
    }
  }

  // Missing, negative and oversized values are errors for every kind, not allocation defaults.
  @Test
  public void everyKindRejectsCorruptAndMissingCounterBytes() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      var drop = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
          "dropProperty", AtomicOperation.class, String.class);
      drop.setAccessible(true);
      for (var kind : PageFileKind.values()) {
        int original = counter(storage, kind);
        for (int corrupt : new int[] {-1, 33554432}) {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(
              op -> config(storage).setComponentIdCounter(kind, op, corrupt));
          assertThrows(StorageException.class, () -> counter(storage, kind));
          assertThrows(StorageException.class, () -> allocate(storage, kind));
        }
        String key = switch (kind) {
          case COLLECTION -> "collectionComponentIdCounter";
          case CONFIGURATION -> "configurationComponentIdCounter";
          case INDEX_ENGINE -> "indexEngineFileBaseIdFloor";
        };
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> invoke(drop, config(storage), op, key));
        assertThrows(StorageException.class, () -> counter(storage, kind));
        assertThrows(StorageException.class, () -> allocate(storage, kind));
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> config(storage).setComponentIdCounter(kind, op, original));
      }
    }
  }

  // Configuration load rejects a missing counter before it can admit component allocation.
  @Test
  public void reopenRejectsMissingLayout26Counter() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(26, storage.getStorageFormat().layoutVersion());
      var drop = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
          "dropProperty", AtomicOperation.class, String.class);
      drop.setAccessible(true);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> invoke(drop, config(storage), op, "collectionComponentIdCounter"));
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    var failure = assertThrows(DatabaseException.class, this::open);
    var messages = new StringBuilder();
    boolean storageError = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage());
      storageError |= cause instanceof StorageException;
    }
    assertTrue(storageError);
    assertTrue(messages.toString().contains("collectionComponentIdCounter"));
  }

  // Registered crash leftovers exceed every durable counter and seed all three kinds on cold open.
  @Test
  public void coldOpenSeedsEveryKindFromRegisteredNamesWithoutWritingCounters() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    final int[] counters;
    try (var session = open()) {
      registerEvidence(session.getStorage(), 9000);
      counters = counters(session.getStorage());
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(26, storage.getStorageFormat().layoutVersion());
      assertEvidenceAndAllocate(storage, 9000, counters);
    }
  }

  // Append durable deletion records after closing the baseline database. No counter update or
  // registry compaction follows these writes, as when a crash preserves only deletion evidence.
  // Both primary-only and auxiliary-only families must protect every kind on the next cold open.
  @Test
  public void coldOpenSeedsDeletionOnlyFamiliesWithoutChangingLowerCounterBytes() throws Exception {
    for (var roles : new PageFileRole[][] {
        {PageFileRole.RECORD_DATA, PageFileRole.INDEX_DATA, PageFileRole.CONFIG_DATA},
        {PageFileRole.FREE_SPACE_MAP, PageFileRole.HISTOGRAM,
            PageFileRole.CONFIG_FREE_SPACE_MAP}}) {
      create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
      final WOWCache cache;
      final int[] counters;
      final byte[][] bytes;
      try (var session = open()) {
        var storage = session.getStorage();
        cache = (WOWCache) storage.getWriteCache();
        counters = counters(storage);
        bytes = counterBytes(storage);
      }
      ytdb.close();
      for (var role : roles) {
        var identity = new PageFileIdentity(role, 9000);
        appendRegistry(cache, PageFileNames.layout26(identity), -identity.internalId(), "");
      }
      ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
      try (var session = open()) {
        var storage = session.getStorage();
        assertCounterBytes(storage, bytes);
        for (var role : roles) {
          var identity = new PageFileIdentity(role, 9000);
          var name = PageFileNames.layout26(identity);
          assertEquals(Integer.valueOf(-identity.internalId()),
              storage.getWriteCache().registeredFileId(name));
          assertTrue(storage.getWriteCache().deletedFiles().contains(name));
          assertTrue(!storage.getWriteCache().files().containsKey(name));
          assertTrue(counters[role.kind().ordinal()] < 9000);
        }
        assertEvidenceAndAllocate(storage, 9000, counters);
      }
      ytdb.drop("counters");
    }
  }

  // A non-computed tombstone id still reserves its logical identity. Old swapped records leave
  // a logical positive registration with a missing backing file plus a negative disk-name alias.
  // Replay converts the former to deletion evidence, while all non-strict names stay irrelevant.
  @Test
  public void coldOpenCountsLogicalTombstonesButIgnoresAliasesAndMalformedNames() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    final WOWCache cache;
    final int[] counters;
    final byte[][] bytes;
    final String logical = "ytdb-p26-collection-8000-record-data.pcl";
    final String alias = "ytdb-p26-collection-8000-record-data_123457.pcl";
    final String nonComputed = "ytdb-p26-index-9000-tree-data.cbt";
    final String[] ignored = {
        "ytdb-p26-configuration-30000-data_123458.cd",
        "ytdb-p26-configuration-33554432-data.cd",
        "ytdb-p26-configuration-09000-data.cd",
        "ytdb-p26-index-30000-unknown.cbt", "user-file.dat", "ie_30000.cbt"};
    try (var session = open()) {
      var storage = session.getStorage();
      cache = (WOWCache) storage.getWriteCache();
      counters = counters(storage);
      bytes = counterBytes(storage);
    }
    ytdb.close();
    appendRegistry(cache, nonComputed, -123456, "");
    appendRegistry(cache, logical, 123457, alias);
    appendRegistry(cache, alias, -123457, logical);
    for (int i = 0; i < ignored.length; i++) {
      appendRegistry(cache, ignored[i], -(123458 + i), "");
    }
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      var wc = storage.getWriteCache();
      assertEquals(Integer.valueOf(-123456), wc.registeredFileId(nonComputed));
      assertEquals(Integer.valueOf(-123457), wc.registeredFileId(logical));
      assertEquals(Integer.valueOf(-123457), wc.registeredFileId(alias));
      for (var name : ignored) {
        assertTrue(wc.deletedFiles().contains(name));
        assertTrue(!wc.files().containsKey(name));
      }
      assertCounterBytes(storage, bytes);
      assertCounters(storage, counters);
      assertEquals(8000, hwm(storage, PageFileKind.COLLECTION));
      assertEquals(9000, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(counters[PageFileKind.CONFIGURATION.ordinal()],
          hwm(storage, PageFileKind.CONFIGURATION));
      // Ignored evidence must not relax creation conflicts, even for an old disk-name alias.
      assertThrows(StorageException.class, () -> wc.bookFileId(alias, 123457, false));
      assertThrows(StorageException.class, () -> wc.bookFileId(nonComputed, 123459, false));
      assertEquals(8001, allocate(storage, PageFileKind.COLLECTION));
      assertEquals(9001, allocate(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(counters[PageFileKind.CONFIGURATION.ordinal()] + 1,
          allocate(storage, PageFileKind.CONFIGURATION));
    }
  }

  private void appendRegistry(WOWCache cache, String name, int id, String physical)
      throws Exception {
    var entryClass = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.cache.local.NameFileIdEntry");
    var constructor = entryClass.getDeclaredConstructor(String.class, int.class, String.class);
    constructor.setAccessible(true);
    var write = WOWCache.class.getDeclaredMethod("writeNameIdEntry", FileChannel.class,
        entryClass, boolean.class);
    write.setAccessible(true);
    try (var channel = FileChannel.open(Path.of(directory, "counters", "name_id_map_v3.cm"),
        StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      invoke(write, cache, channel, constructor.newInstance(name, id, physical), true);
    }
  }

  private static byte[][] counterBytes(AbstractStorage storage) throws Exception {
    var read = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
        "readProperty", String.class, AtomicOperation.class);
    read.setAccessible(true);
    return storage.getAtomicOperationsManager().calculateInsideAtomicOperation(op -> {
      var result = new byte[PageFileKind.values().length][];
      for (var kind : PageFileKind.values()) {
        String key = switch (kind) {
          case COLLECTION -> "collectionComponentIdCounter";
          case INDEX_ENGINE -> "indexEngineFileBaseIdFloor";
          case CONFIGURATION -> "configurationComponentIdCounter";
        };
        result[kind.ordinal()] = ((RawPairObjectInteger<byte[]>) invoke(
            read, config(storage), key, op)).first.clone();
      }
      return result;
    });
  }

  private static void assertCounterBytes(AbstractStorage storage, byte[][] expected)
      throws Exception {
    var actual = counterBytes(storage);
    for (var kind : PageFileKind.values()) {
      assertArrayEquals(expected[kind.ordinal()], actual[kind.ordinal()]);
    }
  }

  // The hook also works in memory and an index name cannot raise collection or configuration ids.
  @Test
  public void memorySeedIsKindIsolatedAndNeverLowersLiveMarks() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.MEMORY);
    try (var session = open()) {
      var storage = session.getStorage();
      final var counters = counters(storage);
      assertTrue(counter(storage, PageFileKind.INDEX_ENGINE) < 9000);
      storage.getWriteCache().addFile("ytdb-p26-index-9000-tree-data.cbt",
          new PageFileIdentity(PageFileRole.INDEX_DATA, 9000).internalId());
      seed(storage);
      assertCounters(storage, counters);
      assertEquals(9000, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(counters[PageFileKind.COLLECTION.ordinal()],
          hwm(storage, PageFileKind.COLLECTION));
      assertEquals(0, hwm(storage, PageFileKind.CONFIGURATION));
      assertEquals(9001, allocate(storage, PageFileKind.INDEX_ENGINE));
      seed(storage);
      assertEquals(9001, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(9002, allocate(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // A full backup and then an incremental chain both reseed the same live target before loading.
  // Restored evidence must win over the counters, while an even higher live mark must survive.
  @Test
  public void sameObjectFullAndIncrementalRestoreSeedEveryKindAndPreserveHigherMarks()
      throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    Path backup = Files.createDirectory(Path.of(directory, "seed-backup"));
    try (var session = open()) {
      var storage = session.getStorage();
      registerEvidence(storage, 9000);
      final var fullCounters = counters(storage);
      storage.backup(backup);
      storage.restoreFromBackup(backup, null);
      assertEquals(26, storage.getStorageFormat().layoutVersion());
      assertEvidenceAndAllocate(storage, 9000, fullCounters);
      for (var kind : PageFileKind.values()) {
        setHwm(storage, hwmField(kind), 20000);
      }
      storage.restoreFromBackup(backup, null);
      assertCounters(storage, fullCounters);
      for (var kind : PageFileKind.values()) {
        assertEquals(20000, hwm(storage, kind));
        assertEquals(20001, allocate(storage, kind));
      }

      registerEvidence(storage, 30000);
      final var incrementalCounters = counters(storage);
      storage.backup(backup);
      storage.restoreFromBackup(backup, null);
      assertEvidenceAndAllocate(storage, 30000, incrementalCounters);
      for (var kind : PageFileKind.values()) {
        setHwm(storage, hwmField(kind), 40000);
      }
      storage.restoreFromBackup(backup, null);
      assertCounters(storage, incrementalCounters);
      for (var kind : PageFileKind.values()) {
        assertEquals(40000, hwm(storage, kind));
        assertEquals(40001, allocate(storage, kind));
      }
    }
  }

  // Layout 25 must ignore prefix-shaped collection names and still accept legacy ids above 25 bits.
  @Test
  public void layout25PrefixCollectionDoesNotSeedAndLegacyNameKeepsIntegerBound() throws Exception {
    create(StorageFormat.LAYOUT_25, DatabaseType.DISK);
    long before;
    try (var session = open()) {
      var storage = session.getStorage();
      before = storage.indexEngineFileBaseIdHwmForTesting();
      session.addCollection("ytdb-p26-collection-9000-record-data");
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(25, storage.getStorageFormat().layoutVersion());
      assertEquals(before, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(0, hwm(storage, PageFileKind.COLLECTION));
      assertEquals(0, hwm(storage, PageFileKind.CONFIGURATION));
      storage.getWriteCache().addFile("ie_33554432$null.ixs");
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(25, storage.getStorageFormat().layoutVersion());
      assertEquals(33554432, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(33554433, allocate(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // All reserved-prefix parse failures are visible storage errors, including every kind over bound.
  @Test
  public void layout26ColdOpenRejectsInvalidRegisteredEvidenceWithFileName() throws Exception {
    for (String file : new String[] {
        "ie_33554432.cbt", "ie_033554432$null.ixs",
        "ie_2147483648.cbt", "ie_9223372036854775808$null.ixs",
        "ytdb-p26-collection-33554432-record-data.pcl",
        "ytdb-p26-index-33554432-tree-data.cbt",
        "ytdb-p26-configuration-33554432-data.cd",
        "ytdb-p26-index-9000-unknown.cbt"}) {
      create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
      try (var session = open()) {
        session.getStorage().getWriteCache().addFile(file);
      }
      ytdb.close();
      ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
      assertStorageFailureNames(assertThrows(DatabaseException.class, this::open), file);
      // Drop opens the poisoned storage, so dispose only this test's closed fixture instead.
      ytdb.close();
      FileUtils.deleteDirectory(new File(directory));
      ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    }
  }

  // Persisted raw engine ids above 25 bits must fail before an engine attempts to open its files.
  @Test
  public void layout26ColdOpenRejectsOversizedPersistedEngineEntry() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      var entry = new IndexEngineData(
          7, 33554432, "oversized-entry", "CELL_BTREE", "UNIQUE", true, 4, 1, false,
          (byte) 0, (byte) 0, false, new PropertyTypeInternal[] {PropertyTypeInternal.STRING},
          true, 1, null, null, null);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> config(storage).storeIndexEngineEntryForTesting(op, "oversized-entry", entry, 2));
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    assertStorageFailureNames(assertThrows(DatabaseException.class, this::open), "oversized-entry");
  }

  // Overflow-shaped names remain nonmatches in layout 25, even after a real cold reopen.
  @Test
  public void layout25ColdOpenIgnoresLegacyNumericOverflow() throws Exception {
    create(StorageFormat.LAYOUT_25, DatabaseType.DISK);
    final long before;
    try (var session = open()) {
      var storage = session.getStorage();
      before = storage.indexEngineFileBaseIdHwmForTesting();
      storage.getWriteCache().addFile("ie_2147483648.cbt");
      storage.getWriteCache().addFile("ie_9223372036854775808$null.ixs");
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(25, storage.getStorageFormat().layoutVersion());
      assertEquals(before, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(before + 1, allocate(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // A negative persisted file base id is corruption, not an absent-id sentinel.
  @Test
  public void layout26ColdOpenRejectsNegativePersistedEngineEntry() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      var entry = new IndexEngineData(
          7, -1, "negative-entry", "CELL_BTREE", "UNIQUE", true, 4, 1, false,
          (byte) 0, (byte) 0, false, new PropertyTypeInternal[] {PropertyTypeInternal.STRING},
          true, 1, null, null, null);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> config(storage).storeIndexEngineEntryForTesting(op, "negative-entry", entry, 2));
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    assertStorageFailureNames(assertThrows(DatabaseException.class, this::open), "negative-entry");
  }

  // The maximum legal evidence loads without counter writes and exhausts all three allocators.
  @Test
  public void layout26MaximumRegisteredEvidenceLoadsAndRefusesNextAllocation() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    final int[] counters;
    try (var session = open()) {
      var storage = session.getStorage();
      registerEvidence(storage, 33554431);
      counters = counters(storage);
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertCounters(storage, counters);
      for (var kind : PageFileKind.values()) {
        assertEquals(33554431, hwm(storage, kind));
        assertTrue(assertThrows(StorageException.class, () -> allocate(storage, kind))
            .getMessage().startsWith("The " + kind + " component-id space is exhausted"));
        assertEquals(33554431, hwm(storage, kind));
      }
      assertCounters(storage, counters);
    }
  }

  // Explicit add, metadata updates, slot reuse and cold load must preserve independent identities.
  @Test
  public void collectionIdentitySurvivesUpdatesSlotReuseAndReopen() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    var expected = new HashMap<Integer, Integer>();
    try (var session = open()) {
      var storage = session.getStorage();
      int before = counter(storage, PageFileKind.COLLECTION);
      int id = session.addCollection("identity");
      assertEquals(before + 1, counter(storage, PageFileKind.COLLECTION));
      assertCollectionIdentity(storage, id, before + 1, 1);
      storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "renamedidentity");
      storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY, "version");
      assertCollectionIdentity(storage, id, before + 1, 1);
      assertTrue(storage.dropCollection(session, id));
      assertEquals(id, session.addCollection("replacementidentity"));
      assertCollectionIdentity(storage, id, before + 2, 1);
      for (var collection : storage.getCollectionInstances()) {
        expected.put(collection.getId(),
            storedCollection(storage, collection.getId()).getComponentId());
      }
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(expected.size(), storage.getCollectionInstances().size());
      for (var entry : expected.entrySet()) {
        assertCollectionIdentity(storage, entry.getKey(), entry.getValue(), 1);
      }
    }
  }

  // Reconciliation allocates during commit, not provisional class creation. Failed apply burns ids.
  @Test
  public void schemaCommitAllocatesAndFailedCommitBurnsCollectionIdentities() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      int before = counter(storage, PageFileKind.COLLECTION);
      session.begin();
      session.getMetadata().getSchema().createClass("CommittedIdentity", 1);
      assertEquals(before, counter(storage, PageFileKind.COLLECTION));
      session.commit();
      var cls = session.getMetadata().getSchema().getClass("CommittedIdentity");
      assertEquals(1, cls.getCollectionIds().length);
      int id = cls.getCollectionIds()[0];
      assertCollectionIdentity(storage, id, before + 1, 1);
      assertEquals(before + 1, counter(storage, PageFileKind.COLLECTION));
      var names = new HashSet<>(session.getCollectionNames());
      var burned = new HashSet<Integer>();
      storage.setCommitWindowTestHook(() -> {
        // The hook owns the non-reentrant state write lock. Read the stored configurations
        // directly rather than calling a runtime accessor that acquires the state read lock.
        for (var collection : config(storage).getCollections()) {
          if (collection != null && !names.contains(collection.name())) {
            burned.add(((StoragePaginatedCollectionConfiguration) collection).getComponentId());
          }
        }
        throw new CommandInterruptedException("counters", "forced post-reconcile rollback");
      });
      try {
        session.begin();
        session.getMetadata().getSchema().createClass("FailedIdentity", 1);
        assertThrows(CommandInterruptedException.class, session::commit);
      } finally {
        storage.setCommitWindowTestHook(null);
      }
      assertEquals(names, new HashSet<>(session.getCollectionNames()));
      assertEquals(java.util.Set.of(before + 2), burned);
      assertEquals(before + 1, counter(storage, PageFileKind.COLLECTION));
      int next = session.addCollection("afterfailure");
      assertCollectionIdentity(storage, next, before + 3, 1);
    }
  }

  // A rollback after physical creation restores the persistent counter, but not the live watermark.
  @Test
  public void rolledBackCollectionCreationBurnsItsComponentId() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      int before = counter(storage, PageFileKind.COLLECTION);
      var create = AbstractStorage.class.getDeclaredMethod(
          "doCreateCollection", AtomicOperation.class, String.class, int.class);
      create.setAccessible(true);
      int slot = storage.getCollections();
      storage.stateLock.writeLock().lock();
      try {
        assertThrows(ConfigurationException.class,
            () -> storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
              var collection = (StorageCollection) invoke(create, storage, op, "rolledback", slot);
              assertEquals(Integer.valueOf(before + 1), runtimeConfig(collection).getComponentId());
              throw new ConfigurationException("forced collection rollback");
            }));
      } finally {
        storage.stateLock.writeLock().unlock();
      }
      assertEquals(before, counter(storage, PageFileKind.COLLECTION));
      assertCollectionIdentity(storage, session.addCollection("afterrollback"), before + 2, 1);
    }
  }

  // Production layout creation and metadata updates keep version zero and never create a counter.
  @Test
  public void layout25CollectionCreationAndUpdatesNeverAllocateComponentIds() throws Exception {
    create(StorageFormat.LAYOUT_25, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      int id = session.addCollection("legacyidentity");
      assertCollectionIdentity(storage, id, null, 0);
      storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "renamedlegacy");
      storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY, "version");
      assertCollectionIdentity(storage, id, null, 0);
      session.begin();
      session.getMetadata().getSchema().createClass("LegacySchemaIdentity", 1);
      session.commit();
      for (var collection : storage.getCollectionInstances()) {
        assertCollectionIdentity(storage, collection.getId(), null, 0);
      }
      assertEquals(0, hwm(storage, PageFileKind.COLLECTION));
      var read = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
          "readProperty", String.class, AtomicOperation.class);
      read.setAccessible(true);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> assertNull(invoke(read, config(storage), "collectionComponentIdCounter", op)));
    }
  }

  // A layout 26 writer must reject an absent id before changing the stored entry or counter.
  @Test
  public void layout26CollectionWriteRejectsMissingIdentity() throws Exception {
    assertMismatchedCollectionWriteRejected(StorageFormat.LAYOUT_26, null);
  }

  // A layout 25 writer must reject even a present zero id without changing legacy metadata.
  @Test
  public void layout25CollectionWriteRejectsPresentIdentity() throws Exception {
    assertMismatchedCollectionWriteRejected(StorageFormat.LAYOUT_25, 0);
  }

  private void assertMismatchedCollectionWriteRejected(StorageFormat format, Integer mismatchedId)
      throws Exception {
    create(format, DatabaseType.DISK);
    final int id;
    final Integer originalId;
    try (var session = open()) {
      var storage = session.getStorage();
      id = session.addCollection("mismatchedwrite");
      var original = storedCollection(storage, id);
      originalId = original.getComponentId();
      int before = format == StorageFormat.LAYOUT_26
          ? counter(storage, PageFileKind.COLLECTION) : 0;
      var collection = storage.getCollectionInstances().stream()
          .filter(c -> c.getId() == id).findFirst().orElseThrow();
      var identity = collection.getClass().getDeclaredField("componentId");
      identity.setAccessible(true);
      var update = AbstractStorage.class.getDeclaredMethod("doSetCollectionAttributed",
          AtomicOperation.class, StorageCollection.ATTRIBUTES.class, Object.class,
          StorageCollection.class);
      update.setAccessible(true);
      var read = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
          "readProperty", String.class, AtomicOperation.class);
      read.setAccessible(true);
      storage.stateLock.writeLock().lock();
      try {
        // Inject a writer defect without altering the durable configuration. Catch the expected
        // refusal inside the operation so rollback cannot hide an accidental entry write.
        identity.set(collection, mismatchedId);
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          var entryBefore = (RawPairObjectInteger<?>) invoke(read, config(storage),
              "collection_" + id, op);
          var failure = assertThrows(InvocationTargetException.class,
              () -> update.invoke(storage, op, StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY,
                  "version", collection));
          assertTrue(failure.getCause() instanceof StorageException);
          assertEquals(new StorageException("counters",
              "Collection 'mismatchedwrite' has an identity incompatible with storage layout "
                  + format.layoutVersion())
              .getMessage(), failure.getCause().getMessage());
          assertSame(original, storedCollection(storage, id));
          var entryAfter = (RawPairObjectInteger<?>) invoke(read, config(storage),
              "collection_" + id, op);
          assertEquals(entryBefore.second, entryAfter.second);
          assertArrayEquals((byte[]) entryBefore.first, (byte[]) entryAfter.first);
          if (format == StorageFormat.LAYOUT_26) {
            assertEquals(before,
                config(storage).getComponentIdCounter(PageFileKind.COLLECTION, op));
          } else {
            assertNull(invoke(read, config(storage), "collectionComponentIdCounter", op));
          }
        });
      } finally {
        identity.set(collection, originalId);
        storage.stateLock.writeLock().unlock();
      }
      assertCollectionIdentity(storage, id, originalId, originalId == null ? 0 : 1);
      if (format == StorageFormat.LAYOUT_26) {
        assertEquals(before, counter(storage, PageFileKind.COLLECTION));
      } else {
        assertEquals(0, hwm(storage, PageFileKind.COLLECTION));
      }
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      assertCollectionIdentity(session.getStorage(), id, originalId, originalId == null ? 0 : 1);
    }
  }

  // Cold load rejects both directions of layout mismatch with the offending collection name.
  @Test
  public void coldLoadRejectsCollectionIdentityLayoutMismatch() throws Exception {
    for (var format : new StorageFormat[] {StorageFormat.LAYOUT_25, StorageFormat.LAYOUT_26}) {
      create(format, DatabaseType.DISK);
      try (var session = open()) {
        var storage = session.getStorage();
        int id = session.addCollection("mismatchedidentity");
        replaceCollectionIdentity(storage, id, format == StorageFormat.LAYOUT_26 ? null : 0);
      }
      ytdb.close();
      ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
      assertStorageFailureNames(assertThrows(DatabaseException.class, this::open),
          "mismatchedidentity");
      ytdb.close();
      FileUtils.deleteDirectory(new File(directory));
      ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    }
  }

  // Zero is a present identity, not a missing identity. Load and update must retain it.
  @Test
  public void coldLoadAcceptsPresentZeroComponentIdentity() throws Exception {
    create(StorageFormat.LAYOUT_26, DatabaseType.DISK);
    final int id;
    try (var session = open()) {
      id = session.addCollection("zeroidentity");
      replaceCollectionIdentity(session.getStorage(), id, 0);
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      assertCollectionIdentity(session.getStorage(), id, 0, 1);
      session.getStorage().setCollectionAttribute(id,
          StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY, "version");
      assertCollectionIdentity(session.getStorage(), id, 0, 1);
    }
  }

  private static void replaceCollectionIdentity(AbstractStorage storage, int id,
      Integer componentId)
      throws Exception {
    var old = storedCollection(storage, id);
    var replacement = new StoragePaginatedCollectionConfiguration(id, old.name, old.location,
        old.useWal, old.recordOverflowGrowFactor, old.recordGrowFactor, old.compression,
        old.encryption, old.encryptionKey, old.conflictStrategy, old.getBinaryVersion(),
        componentId);
    storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
      config(storage).dropCollection(op, id);
      config(storage).updateCollection(op, replacement);
    });
  }

  private static StoragePaginatedCollectionConfiguration
      runtimeConfig(StorageCollection collection) {
    return ((PaginatedCollection) collection).generateCollectionConfig();
  }

  private static StoragePaginatedCollectionConfiguration storedCollection(AbstractStorage storage,
      int id) {
    return (StoragePaginatedCollectionConfiguration) config(storage).getCollections().get(id);
  }

  private static int entryVersion(AbstractStorage storage, int id) throws Exception {
    var read = CollectionBasedStorageConfiguration.class.getDeclaredMethod(
        "readProperty", String.class, AtomicOperation.class);
    read.setAccessible(true);
    return storage.getAtomicOperationsManager()
        .calculateInsideAtomicOperation(op -> ((RawPairObjectInteger<?>) invoke(read,
            config(storage), "collection_" + id, op)).second);
  }

  private static void assertCollectionIdentity(AbstractStorage storage, int id, Integer expected,
      int version) throws Exception {
    assertEquals(expected, storedCollection(storage, id).getComponentId());
    assertEquals(version, entryVersion(storage, id));
    var collection = storage.getCollectionInstances().stream()
        .filter(c -> c.getId() == id).findFirst().orElseThrow();
    assertEquals(expected, runtimeConfig(collection).getComponentId());
  }

  private static void registerEvidence(AbstractStorage storage, int id) throws Exception {
    assertEquals(26, storage.getStorageFormat().layoutVersion());
    for (var kind : PageFileKind.values()) {
      assertTrue(counter(storage, kind) < id);
    }
    for (var role : new PageFileRole[] {PageFileRole.RECORD_DATA, PageFileRole.INDEX_DATA,
        PageFileRole.CONFIG_DATA}) {
      var identity = new PageFileIdentity(role, id);
      storage.getWriteCache().addFile(PageFileNames.layout26(identity), identity.internalId());
    }
  }

  private static int[] counters(AbstractStorage storage) throws Exception {
    var counters = new int[PageFileKind.values().length];
    for (var kind : PageFileKind.values()) {
      counters[kind.ordinal()] = counter(storage, kind);
    }
    return counters;
  }

  private static void assertCounters(AbstractStorage storage, int[] expected) throws Exception {
    for (var kind : PageFileKind.values()) {
      assertEquals(expected[kind.ordinal()], counter(storage, kind));
    }
  }

  private static void assertEvidenceAndAllocate(AbstractStorage storage, int id, int[] counters)
      throws Exception {
    assertCounters(storage, counters);
    for (var kind : PageFileKind.values()) {
      assertEquals(id, hwm(storage, kind));
      assertEquals(id + 1, allocate(storage, kind));
    }
  }

  private static void seed(AbstractStorage storage) throws Exception {
    storage.stateLock.writeLock().lock();
    try {
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(storage::seedComponentIds);
    } finally {
      storage.stateLock.writeLock().unlock();
    }
  }

  private static String hwmField(PageFileKind kind) {
    return switch (kind) {
      case COLLECTION -> "collectionComponentIdHwm";
      case INDEX_ENGINE -> "indexEngineFileBaseIdHwm";
      case CONFIGURATION -> "configurationComponentIdHwm";
    };
  }

  private static long hwm(AbstractStorage storage, PageFileKind kind) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(hwmField(kind));
    field.setAccessible(true);
    return ((AtomicLong) field.get(storage)).get();
  }

  private static void assertStorageFailureNames(Throwable failure, String entry) {
    boolean found = false;
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof StorageException && cause.getMessage().contains(entry)) {
        found = true;
      }
    }
    assertTrue("Expected a StorageException naming " + entry + " in " + failure, found);
  }

  private static Object invoke(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void setHwm(AbstractStorage storage, String name, long value) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    ((AtomicLong) field.get(storage)).set(value);
  }
}
