package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.StorageCollectionFactory;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
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
      assertEquals(format == StorageFormat.LAYOUT_28 ? 28 : 27,
          session.getStorage().getStorageFormat().layoutVersion());
    }
  }

  private static String treeName(int componentId) {
    return PageFileNames.layout28(new PageFileIdentity(PageFileRole.LINK_BAG_TREE, componentId));
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
      create(StorageFormat.LAYOUT_28, type);
      try (var session = open()) {
        var storage = session.getStorage();
        assertEquals(28, storage.getStorageFormat().layoutVersion());
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

  // Real engine creation must advance the file base id and durable floor together in layout 28.
  @Test
  public void layout28IndexCreationPersistsLiteralFileBaseIdsAndFloor() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(28, storage.getStorageFormat().layoutVersion());
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
      assertIndexTreeRegistrations(storage, first.getFileBaseId(), false);
      assertEquals(101, (int) storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> config(storage).getIndexEngineFileBaseIdFloor(op)));
      assertEquals(101, counter(storage, PageFileKind.INDEX_ENGINE));

      cls.createIndex("CounterIndex.second", SchemaClass.INDEX_TYPE.UNIQUE, "second");
      var second = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> config(storage).getIndexEngine("CounterIndex.second", -1, op));
      assertEquals(102, second.getFileBaseId());
      assertIndexTreeRegistrations(storage, second.getFileBaseId(), false);
      assertEquals(102, (int) storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> config(storage).getIndexEngineFileBaseIdFloor(op)));
      assertEquals(102, counter(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // Both engine types must register canonical roles and computed ids on disk and survive cold load.
  @Test
  public void layout28IndexTreesSurviveDiskReopenAndDrop() throws Exception {
    indexTreeLifecycle(DatabaseType.DISK);
  }

  // Sequential memory ids must coexist with supplied ids for both engines and all four roles.
  @Test
  public void layout28IndexTreesRegisterAndDropInMemory() throws Exception {
    indexTreeLifecycle(DatabaseType.MEMORY);
  }

  private void indexTreeLifecycle(DatabaseType type) throws Exception {
    create(StorageFormat.LAYOUT_28, type);
    int singleId;
    int multiId;
    try (var session = open()) {
      var cls = session.getMetadata().getSchema().createClass("RoleIndex");
      cls.createProperty("single", PropertyType.STRING);
      cls.createProperty("multi", PropertyType.STRING);
      cls.createIndex("RoleIndex.single", SchemaClass.INDEX_TYPE.UNIQUE, "single");
      cls.createIndex("RoleIndex.multi", SchemaClass.INDEX_TYPE.NOTUNIQUE, "multi");
      var storage = session.getStorage();
      singleId = indexFileBaseId(storage, "RoleIndex.single");
      multiId = indexFileBaseId(storage, "RoleIndex.multi");
      assertTrue(multiId > singleId);
      assertIndexTreeRegistrations(storage, singleId, false);
      assertIndexTreeRegistrations(storage, multiId, true);
      session.executeInTx(tx -> {
        tx.newEntity("RoleIndex").setProperty("single", "unique");
        var entity = tx.newEntity("RoleIndex");
        entity.setProperty("single", "other");
        entity.setProperty("multi", "shared");
        var duplicate = tx.newEntity("RoleIndex");
        duplicate.setProperty("single", "third");
        duplicate.setProperty("multi", "shared");
      });
    }
    if (type == DatabaseType.DISK) {
      ytdb.close();
      ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    }
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(singleId, indexFileBaseId(storage, "RoleIndex.single"));
      assertEquals(multiId, indexFileBaseId(storage, "RoleIndex.multi"));
      assertIndexTreeRegistrations(storage, singleId, false);
      assertIndexTreeRegistrations(storage, multiId, true);
      try (var rows = session.query("select from RoleIndex where single = 'unique'")) {
        assertEquals(1, rows.stream().count());
      }
      try (var rows = session.query("select from RoleIndex where multi = 'shared'")) {
        assertEquals(2, rows.stream().count());
      }
      try (var rows = session.query("select from RoleIndex where multi is null")) {
        assertEquals(1, rows.stream().count());
      }
      // Query opens a read-only transaction. End it before the top-level schema changes.
      session.commit();
      session.execute("drop index RoleIndex.single").close();
      session.execute("drop index RoleIndex.multi").close();
      for (int id : new int[] {singleId, multiId}) {
        for (var role : indexTreeRoles(id == multiId)) {
          var name = PageFileNames.layout28(new PageFileIdentity(role, id));
          assertFalse("Dropped tree remains registered: " + name,
              storage.getWriteCache().files().containsKey(name));
          assertFalse(storage.getWriteCache().exists(name));
        }
      }
    }
  }

  private static int indexFileBaseId(AbstractStorage storage, String name) throws Exception {
    return storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
        op -> config(storage).getIndexEngine(name, -1, op)).getFileBaseId();
  }

  private static PageFileRole[] indexTreeRoles(boolean multi) {
    return multi
        ? new PageFileRole[] {PageFileRole.INDEX_DATA, PageFileRole.INDEX_NULL_BUCKET,
            PageFileRole.NULL_KEY_DATA, PageFileRole.NULL_KEY_NULL_BUCKET, PageFileRole.HISTOGRAM}
        : new PageFileRole[] {PageFileRole.INDEX_DATA, PageFileRole.INDEX_NULL_BUCKET,
            PageFileRole.HISTOGRAM};
  }

  private static void assertIndexTreeRegistrations(AbstractStorage storage, int id,
      boolean multi) throws Exception {
    var cache = storage.getWriteCache();
    var names = new HashSet<String>();
    for (var role : indexTreeRoles(multi)) {
      var identity = new PageFileIdentity(role, id);
      // Literal names and bit fields keep this check independent of the production name codec.
      var suffix = switch (role) {
        case INDEX_DATA -> "tree-data.cbt";
        case INDEX_NULL_BUCKET -> "tree-null-bucket.nbt";
        case NULL_KEY_DATA -> "null-key-data.cbt";
        case NULL_KEY_NULL_BUCKET -> "null-key-null-bucket.nbt";
        case HISTOGRAM -> "histogram.ixs";
        default -> throw new AssertionError(role);
      };
      int expectedId = (2 << 28) | (role.code() << 25) | id;
      var name = "ytdb-p28-index-" + id + "-" + suffix;
      assertEquals(name, PageFileNames.layout28(identity));
      assertTrue(cache.files().containsKey(name));
      long registered = cache.files().get(name);
      assertEquals(expectedId, cache.internalFileId(registered));
      assertEquals(expectedId, AbstractWriteCache.extractFileId(registered));
      assertEquals(Integer.valueOf(expectedId), cache.registeredFileId(name));
      assertTrue(cache.exists(name));
      if (cache instanceof WOWCache diskCache) {
        assertTrue(Files.exists(diskCache.getRootDirectory().resolve(
            PageFileNames.layout28DiskName(identity))));
      }
      names.add(name);
    }
    var prefix = "ytdb-p28-index-" + id + "-";
    assertEquals(names, cache.files().keySet().stream()
        .filter(name -> name.startsWith(prefix)).collect(java.util.stream.Collectors.toSet()));
    var legacyStem = AbstractStorage.indexEngineFileStem(id);
    assertFalse(cache.files().containsKey(legacyStem + ".cbt"));
    assertFalse(cache.files().containsKey(legacyStem + ".nbt"));
    assertFalse(cache.files().containsKey(legacyStem + AbstractStorage.NULL_TREE_SUFFIX + ".cbt"));
    assertFalse(cache.files().containsKey(legacyStem + AbstractStorage.NULL_TREE_SUFFIX + ".nbt"));
  }

  // Another thread's write lock must not admit allocation. Abortable acquisition must admit its owner.
  @Test
  public void allocationRequiresCurrentWriteOwnerIncludingAbortableAcquisition() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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

  // Raw property reads distinguish an absent layout 27 counter from a default or cache value.
  @Test
  public void layout27HasNoNewPropertiesAndKeepsIntegerIndexCeiling() throws Exception {
    create(StorageFormat.LAYOUT_27, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(27, storage.getStorageFormat().layoutVersion());
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
      assertEquals(28, storage.getStorageFormat().layoutVersion());
      for (var kind : PageFileKind.values()) {
        assertEquals(issued[kind.ordinal()], counter(storage, kind));
        assertEquals(issued[kind.ordinal()] + 1, allocate(storage, kind));
      }
    }
  }

  // A real atomic rollback restores each durable counter but burns the issued live value.
  @Test
  public void rollbackBurnsIdsForEveryKindInTheSameStorageObject() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
  public void reopenRejectsMissingLayout28Counter() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(28, storage.getStorageFormat().layoutVersion());
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    final int[] counters;
    try (var session = open()) {
      registerEvidence(session.getStorage(), 9000);
      counters = counters(session.getStorage());
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(28, storage.getStorageFormat().layoutVersion());
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
      create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
        appendRegistry(cache, PageFileNames.layout28(identity), -identity.internalId(), "");
      }
      ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
      try (var session = open()) {
        var storage = session.getStorage();
        assertCounterBytes(storage, bytes);
        for (var role : roles) {
          var identity = new PageFileIdentity(role, 9000);
          var name = PageFileNames.layout28(identity);
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    final WOWCache cache;
    final int[] counters;
    final byte[][] bytes;
    final String logical = "ytdb-p28-collection-8000-record-data.pcl";
    final String alias = "ytdb-p28-collection-8000-record-data_123457.pcl";
    final String nonComputed = "ytdb-p28-index-9000-tree-data.cbt";
    final String[] ignored = {
        "ytdb-p28-configuration-30000-data_123458.cd",
        "ytdb-p28-configuration-33554432-data.cd",
        "ytdb-p28-configuration-09000-data.cd",
        "ytdb-p28-index-30000-unknown.cbt", "user-file.dat", "ie_30000.cbt"};
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
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      var storage = session.getStorage();
      final var counters = counters(storage);
      assertTrue(counter(storage, PageFileKind.INDEX_ENGINE) < 9000);
      storage.getWriteCache().addFile("ytdb-p28-index-9000-tree-data.cbt",
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    Path backup = Files.createDirectory(Path.of(directory, "seed-backup"));
    try (var session = open()) {
      var storage = session.getStorage();
      registerEvidence(storage, 9000);
      final var fullCounters = counters(storage);
      storage.backup(backup);
      storage.restoreFromBackup(backup, null);
      assertEquals(28, storage.getStorageFormat().layoutVersion());
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

  // Layout 27 must ignore prefix-shaped collection names and still accept legacy ids above 25 bits.
  @Test
  public void layout27PrefixCollectionDoesNotSeedAndLegacyNameKeepsIntegerBound() throws Exception {
    create(StorageFormat.LAYOUT_27, DatabaseType.DISK);
    long before;
    try (var session = open()) {
      var storage = session.getStorage();
      before = storage.indexEngineFileBaseIdHwmForTesting();
      session.addCollection("ytdb-p28-collection-9000-record-data");
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(27, storage.getStorageFormat().layoutVersion());
      assertEquals(before, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(0, hwm(storage, PageFileKind.COLLECTION));
      assertEquals(0, hwm(storage, PageFileKind.CONFIGURATION));
      storage.getWriteCache().addFile("ie_33554432$null.ixs");
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertEquals(27, storage.getStorageFormat().layoutVersion());
      assertEquals(33554432, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(33554433, allocate(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // All reserved-prefix parse failures are visible storage errors, including every kind over bound.
  @Test
  public void layout28ColdOpenRejectsInvalidRegisteredEvidenceWithFileName() throws Exception {
    for (String file : new String[] {
        "ie_33554432.cbt", "ie_033554432$null.ixs",
        "ie_2147483648.cbt", "ie_9223372036854775808$null.ixs",
        "ytdb-p28-collection-33554432-record-data.pcl",
        "ytdb-p28-index-33554432-tree-data.cbt",
        "ytdb-p28-configuration-33554432-data.cd",
        "ytdb-p28-index-9000-unknown.cbt"}) {
      create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
  public void layout28ColdOpenRejectsOversizedPersistedEngineEntry() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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

  // Overflow-shaped names remain nonmatches in layout 27, even after a real cold reopen.
  @Test
  public void layout27ColdOpenIgnoresLegacyNumericOverflow() throws Exception {
    create(StorageFormat.LAYOUT_27, DatabaseType.DISK);
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
      assertEquals(27, storage.getStorageFormat().layoutVersion());
      assertEquals(before, hwm(storage, PageFileKind.INDEX_ENGINE));
      assertEquals(before + 1, allocate(storage, PageFileKind.INDEX_ENGINE));
    }
  }

  // A negative persisted file base id is corruption, not an absent-id sentinel.
  @Test
  public void layout28ColdOpenRejectsNegativePersistedEngineEntry() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
  public void layout28MaximumRegisteredEvidenceLoadsAndRefusesNextAllocation() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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

  // Both construction routes enforce ownership before init can change ids, metrics or strategy.
  @Test
  public void layout28ConfigureRejectsOwnerReplacementBeforeAnyMutation() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      var storage = session.getStorage();
      for (boolean explicit : new boolean[] {true, false}) {
        var collection =
            explicit ? StorageCollectionFactory.createCollection("prepared", storage, 0)
                : new PaginatedCollectionV2("prepared", storage);
        assertEquals(explicit ? Integer.valueOf(0) : null,
            collection.generateCollectionConfig().getComponentId());
        collection.configure(120, "prepared", 0);
        var config = collection.generateCollectionConfig();
        collection.configure(storage, config);
        collection.configure(120, "prepared", 0);
        for (Integer invalid : new Integer[] {1, null}) {
          var bad = new StoragePaginatedCollectionConfiguration(999, "retargeted", null, true,
              1, 1, null, null, null, "version", 3, invalid);
          var meters = collectionField(collection, "meters");
          var strategy = collectionField(collection, "recordConflictStrategy");
          assertThrows(StorageException.class,
              () -> collection.configure(999, "retargeted", invalid));
          assertThrows(StorageException.class, () -> collection.configure(storage, bad));
          assertEquals(120, collection.getId());
          assertEquals("prepared", collection.getName());
          assertEquals(Integer.valueOf(0), collection.generateCollectionConfig().getComponentId());
          assertSame(meters, collectionField(collection, "meters"));
          assertSame(strategy, collectionField(collection, "recordConflictStrategy"));
        }
        assertThrows(StorageException.class, () -> collection.configure(999, "retargeted"));
        assertEquals(120, collection.getId());
      }
      var configuration = new PaginatedCollectionV2("configuration", ".cd", ".ccm", ".fcm",
          ".dcm", storage, new PageFileIdentity(PageFileRole.CONFIG_DATA, 0));
      configuration.configure(0, "configuration");
      configuration.configure(storage, configuration.generateCollectionConfig());
      assertNull(configuration.generateCollectionConfig().getComponentId());
    }
  }

  // Layout 27 retains reconfiguration, including clearing a previously supplied owner.
  @Test
  public void layout27ConfigureKeepsLegacyReassignment() throws Exception {
    create(StorageFormat.LAYOUT_27, DatabaseType.MEMORY);
    try (var session = open()) {
      var storage = session.getStorage();
      var collection = new PaginatedCollectionV2("legacy", storage);
      collection.configure(120, "legacy", 0);
      collection.configure(121, "legacy", 1);
      assertEquals(Integer.valueOf(1), collection.generateCollectionConfig().getComponentId());
      collection.configure(122, "legacy");
      collection.configure(storage, collection.generateCollectionConfig());
      assertEquals(122, collection.getId());
      assertNull(collection.generateCollectionConfig().getComponentId());
    }
  }

  // The actual reuse caller accepts the stored layout 28 owner and rejects retargeting.
  @Test
  public void createCollectionFromConfigReuseKeepsThePublishedOwner() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      var storage = session.getStorage();
      int id = session.addCollection("reusedconfiguration");
      var collection = (PaginatedCollectionV2) storage.getCollectionInstances().stream()
          .filter(c -> c.getId() == id).findFirst().orElseThrow();
      var config = collection.generateCollectionConfig();
      var method = AbstractStorage.class.getDeclaredMethod("createCollectionFromConfig",
          com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration.class,
          AtomicOperation.class);
      method.setAccessible(true);
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          assertEquals(-1, invoke(method, storage, config, op));
          var meters = collectionField(collection, "meters");
          var bad = new StoragePaginatedCollectionConfiguration(id, config.name(), null, true,
              1, 1, null, null, null, "version", 3, config.getComponentId() + 1);
          var failure = assertThrows(InvocationTargetException.class,
              () -> method.invoke(storage, bad, op));
          assertTrue(failure.getCause() instanceof StorageException);
          assertSame(meters, collectionField(collection, "meters"));
          assertEquals(config.getComponentId(),
              collection.generateCollectionConfig().getComponentId());
        });
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
  }

  // Creation touches all four real roles under the collection's operation-lifetime lock.
  // Refresh, rename and a happens-before handoff must retain the shared epoch and owner.
  @Test
  public void preparedFamilyKeepsOneAcquiredLockAndEpochAndSeparateTrees() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      var storage = session.getStorage();
      int owner = allocate(storage, PageFileKind.COLLECTION);
      var collection = StorageCollectionFactory.createCollection("prepared", storage, owner);
      collection.configure(120, "prepared", owner);
      var manager = storage.getAtomicOperationsManager();
      var lockName = collection.getLockName();
      var epoch = collection.getApplyPhaseEpoch();
      assertNotNull(epoch);
      assertSame(manager.getApplyPhaseEpoch(lockName), epoch);
      for (String field : new String[] {"collectionPositionMap", "freeSpaceMap",
          "dirtyPageBitSet"}) {
        var auxiliary = (StorageComponent) collectionField(collection, field);
        assertEquals(lockName, auxiliary.getLockName());
        assertSame(epoch, auxiliary.getApplyPhaseEpoch());
      }
      storage.stateLock.writeLock().lock();
      try {
        manager.executeInsideAtomicOperation(op -> {
          collection.create(op);
          var locked = new java.util.ArrayList<StorageComponent>();
          op.lockedComponents().forEach(locked::add);
          assertEquals(java.util.List.of(collection), locked);
          assertTrue(collection.isExclusiveOwner());
          assertEquals(AtomicOperation.ComponentLockMode.EXCLUSIVE, op.lockedObjectMode(lockName));
        });
        var tree = new SharedLinkBagBTree(storage, "prepared", ".grb",
            new PageFileIdentity(PageFileRole.LINK_BAG_TREE, owner));
        var other = new SharedLinkBagBTree(storage, "other", ".grb",
            new PageFileIdentity(PageFileRole.LINK_BAG_TREE, owner + 1));
        assertFalse(lockName.equals(tree.getLockName()));
        assertFalse(tree.getLockName().equals(other.getLockName()));
        assertNotNull(tree.getApplyPhaseEpoch());
        assertNotNull(other.getApplyPhaseEpoch());
        assertNotSame(epoch, tree.getApplyPhaseEpoch());
        assertNotSame(tree.getApplyPhaseEpoch(), other.getApplyPhaseEpoch());
        manager.executeInsideAtomicOperation(tree::create);
      } finally {
        storage.stateLock.writeLock().unlock();
      }
      var loaded = new SharedLinkBagBTree(storage, "differentdisplay", ".grb",
          new PageFileIdentity(PageFileRole.LINK_BAG_TREE, owner));
      var tree = new SharedLinkBagBTree(storage, "prepared", ".grb",
          new PageFileIdentity(PageFileRole.LINK_BAG_TREE, owner));
      manager.executeInsideAtomicOperation(tree::load);
      manager.executeInsideAtomicOperation(loaded::load);
      assertEquals(tree.getFileId(), loaded.getFileId());
      manager.executeInsideAtomicOperation(loaded::delete);
      assertFalse(storage.getWriteCache().files().containsKey(tree.getLockName()));
      collection.setCollectionName("renamedprepared");
      collection.configure(storage, collection.generateCollectionConfig());
      assertEquals(lockName, collection.getLockName());
      assertSame(epoch, collection.getApplyPhaseEpoch());
      for (String field : new String[] {"collectionPositionMap", "freeSpaceMap",
          "dirtyPageBitSet"}) {
        var auxiliary = (StorageComponent) collectionField(collection, field);
        assertEquals(lockName, auxiliary.getLockName());
        assertSame(epoch, auxiliary.getApplyPhaseEpoch());
      }
      var executor = Executors.newSingleThreadExecutor();
      try {
        executor.submit(() -> {
          assertEquals(Integer.valueOf(owner),
              collection.generateCollectionConfig().getComponentId());
          assertEquals(lockName, collection.getLockName());
          assertSame(epoch, collection.getApplyPhaseEpoch());
        }).get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
      collection.close();
    }
  }

  private static Object collectionField(PaginatedCollectionV2 collection, String name)
      throws Exception {
    var field = PaginatedCollectionV2.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(collection);
  }

  // Layout 28 keeps display-named ordinary files and an identity-owned tree on cold open.
  @Test
  public void productionCollectionNamesRemainUnchangedAcrossColdReopen() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    final int id;
    final java.util.Map<String, Long> registered = new HashMap<>();
    try (var session = open()) {
      var storage = session.getStorage();
      id = session.addCollection("displayfamily");
      for (String ext : new String[] {".pcl", ".cpm", ".fsm", ".dpb"}) {
        String name = "displayfamily" + ext;
        assertTrue(storage.getWriteCache().files().containsKey(name));
        registered.put(name, storage.getWriteCache().files().get(name));
      }
      String tree = treeName(operationEntry(storage, id).getComponentId());
      assertTrue(storage.getWriteCache().files().containsKey(tree));
      registered.put(tree, storage.getWriteCache().files().get(tree));
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      registered.forEach((name, fileId) -> {
        assertTrue(storage.getWriteCache().files().containsKey(name));
        // Cache ids change on cold open. The internal file id remains stable.
        assertEquals(AbstractWriteCache.extractFileId(fileId),
            AbstractWriteCache.extractFileId(storage.getWriteCache().files().get(name)));
      });
      assertEquals(id, session.getCollectionIdByName("displayfamily"));
    }
  }

  // Explicit add, metadata updates, slot reuse and cold load must preserve independent identities.
  @Test
  public void collectionIdentitySurvivesUpdatesSlotReuseAndReopen() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
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
  public void layout27CollectionCreationAndUpdatesNeverAllocateComponentIds() throws Exception {
    create(StorageFormat.LAYOUT_27, DatabaseType.DISK);
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

  // A layout 28 writer must reject an absent id before changing the stored entry or counter.
  @Test
  public void layout28CollectionWriteRejectsMissingIdentity() throws Exception {
    assertMismatchedCollectionWriteRejected(StorageFormat.LAYOUT_28, null);
  }

  // A layout 27 writer must reject even a present zero id without changing legacy metadata.
  @Test
  public void layout27CollectionWriteRejectsPresentIdentity() throws Exception {
    assertMismatchedCollectionWriteRejected(StorageFormat.LAYOUT_27, 0);
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
      int before = format == StorageFormat.LAYOUT_28
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
          if (format == StorageFormat.LAYOUT_28) {
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
      if (format == StorageFormat.LAYOUT_28) {
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
    for (var format : new StorageFormat[] {StorageFormat.LAYOUT_27, StorageFormat.LAYOUT_28}) {
      create(format, DatabaseType.DISK);
      try (var session = open()) {
        var storage = session.getStorage();
        int id = session.addCollection("mismatchedidentity");
        replaceCollectionIdentity(storage, id, format == StorageFormat.LAYOUT_28 ? null : 0);
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
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    final int id;
    try (var session = open()) {
      id = session.addCollection("zeroidentity");
      var storage = session.getStorage();
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          manager.deleteComponent(op, storage.captureCollectionOwner(id, op));
          new SharedLinkBagBTree(storage, "zero", ".grb",
              new PageFileIdentity(PageFileRole.LINK_BAG_TREE, 0)).create(op);
        });
      } finally {
        storage.stateLock.writeLock().unlock();
      }
      replaceCollectionIdentity(storage, id, 0);
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

  // A failed public drop leaves an empty runtime slot but restores the stored owner. Both
  // allocation routes must respect it, including the display name, before any creation effects.
  @Test
  public void failedPublicDropProtectsSlotNameAndRecordsOnDiskAndMemory() throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      create(StorageFormat.LAYOUT_28, type);
      int id;
      Integer owner;
      com.jetbrains.youtrackdb.internal.core.db.record.record.RID rid;
      try (var session = open()) {
        var cls = session.getMetadata().getSchema().createClass("KeptOwner", 1);
        id = cls.getCollectionIds()[0];
        rid = session.computeInTx(tx -> {
          var row = tx.newEntity("KeptOwner");
          row.setProperty("value", "survives");
          return row.getIdentity();
        });
        var storage = session.getStorage();
        var capturedCollection = storage.getCollectionInstances().stream()
            .filter(c -> c.getId() == id).findFirst().orElseThrow();
        owner = operationEntry(storage, id).getComponentId();
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        config(storage).setConfigurationUpdateListener(update -> {
          if (once.compareAndSet(false, true)) {
            throw new ConfigurationException("injected public drop failure");
          }
        });
        try {
          assertThrows(ConfigurationException.class, () -> storage.dropCollection(session, id));
        } finally {
          config(storage).setConfigurationUpdateListener(null);
        }
        assertEquals(owner, operationEntry(storage, id).getComponentId());
        var files = new HashMap<>(storage.getWriteCache().files());
        int before = counter(storage, PageFileKind.COLLECTION);
        long liveBefore = hwm(storage, PageFileKind.COLLECTION);
        assertThrows(ConfigurationException.class,
            () -> session
                .addCollection(capturedCollection.getName().toUpperCase(java.util.Locale.ROOT)));
        assertEquals(liveBefore, hwm(storage, PageFileKind.COLLECTION));
        assertThrows(ConfigurationException.class,
            () -> storage.addCollection(session, "requestedreplacement", id));
        assertEquals(liveBefore, hwm(storage, PageFileKind.COLLECTION));
        assertEquals(before, counter(storage, PageFileKind.COLLECTION));
        assertEquals(files, storage.getWriteCache().files());
        assertTrue(storage.getCollectionInstances().stream().noneMatch(c -> c.getId() == id));
        assertTrue(session.addCollection("automaticreplacement") != id);
        session.executeInTx(tx -> session.getMetadata().getSchema().createClass("SchemaSkip", 1));
        assertTrue(session.getMetadata().getSchema().getClass("SchemaSkip")
            .getCollectionIds()[0] != id);
        // Runtime rollback of public drop is outside this track. Read the captured collection
        // directly in memory. A cold disk reopen below also proves normal record access.
        var op = storage.getAtomicOperationsManager().startAtomicOperation();
        try {
          assertTrue(capturedCollection.readRecord(rid.getCollectionPosition(), op)
              .toRawBuffer().buffer().length > 0);
        } finally {
          op.deactivate();
        }
      }
      if (type == DatabaseType.DISK) {
        ytdb.close();
        ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
        try (var session = open()) {
          assertEquals(owner, operationEntry(session.getStorage(), id).getComponentId());
          session.executeInTx(tx -> {
            var row =
                (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session.load(rid);
            assertEquals("survives", row.getProperty("value"));
          });
        }
      }
      ytdb.drop("counters");
    }
  }

  // Both production drops must retain the loaded tree before removing the slot and config.
  // Reopen first so the capture cannot rely on the manager's newly-created-owner cache.
  @Test
  public void publicAndSchemaDropsDeleteCapturedTreesAfterRegistryRemoval() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    int publicId;
    int schemaId;
    try (var session = open()) {
      publicId = session.addCollection("publiccapture");
      schemaId = session.getMetadata().getSchema().createClass("SchemaCapture", 1)
          .getCollectionIds()[0];
    }
    ytdb.close();
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      var manager =
          (com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared) storage
              .getLinkCollectionsBtreeCollectionManager();
      var owners = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> java.util.List.of(storage.captureCollectionOwner(publicId, op),
              storage.captureCollectionOwner(schemaId, op)));
      assertTrue(storage.dropCollection(session, publicId));
      session.executeInTx(tx -> session.getMetadata().getSchema().dropClass("SchemaCapture"));
      var field = manager.getClass().getDeclaredField("fileIdBTreeMap");
      field.setAccessible(true);
      var loaded = (java.util.Map<?, ?>) field.get(manager);
      for (var owner : owners) {
        assertTrue(owner.tree() != null);
        assertNull(operationEntry(storage, owner.collectionId()));
        assertFalse(loaded.containsKey(AbstractWriteCache.extractFileId(owner.tree().getFileId())));
        assertFalse(storage.getWriteCache().files()
            .containsKey(treeName(owner.componentId())));
      }
    }
  }

  // Cleanup of a non-published failed collection must not use a surviving tree as authority.
  // Cover a disappeared file with stale loaded state, a missing loaded tree, and another owner
  // restored into the configuration slot. Ordinary files always belong to the failed capture.
  @Test
  public void failedCreationCleanupSeparatesFilesLoadedStateAndConfiguredOwner() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      var manager =
          (com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared) storage
              .getLinkCollectionsBtreeCollectionManager();
      var create = AbstractStorage.class.getDeclaredMethod("doCreateCollection",
          AtomicOperation.class, String.class, int.class);
      create.setAccessible(true);
      var cleanup = AbstractStorage.class.getDeclaredMethod("revertCreatedCollectionStructure",
          int.class, StorageCollection.class, boolean.class,
          LinkCollectionsBTreeManagerShared.CollectionOwner.class);
      cleanup.setAccessible(true);
      var loadedField = manager.getClass().getDeclaredField("fileIdBTreeMap");
      loadedField.setAccessible(true);
      for (int scenario = 0; scenario < 3; scenario++) {
        int id = storage.getCollections();
        var variant = scenario;
        storage.stateLock.writeLock().lock();
        try {
          var collection = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> (StorageCollection) invoke(create, storage, op, "cleanup" + variant, id));
          var captured = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> manager.captureOwner(collection, op));
          var loaded = (java.util.Map<?, ?>) loadedField.get(manager);
          var key = AbstractWriteCache.extractFileId(captured.tree().getFileId());
          if (scenario != 1) {
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(
                op -> op.deleteFile(captured.tree().getFileId()));
            assertTrue(loaded.containsKey(key));
          } else {
            // Evict the loaded handle but keep the file and configuration entry.
            manager.removeLoadedComponent(captured);
          }
          Integer keptOwner = null;
          if (scenario == 2) {
            keptOwner = captured.componentId() + 100;
            replaceCollectionIdentity(storage, id, keptOwner);
          }
          invoke(cleanup, storage, id, collection, false, captured);
          assertFalse(loaded.containsKey(key));
          assertFalse(storage.getWriteCache().files()
              .containsKey(treeName(captured.componentId())));
          var read = storage.getAtomicOperationsManager().startAtomicOperation();
          try {
            assertFalse(collection.exists(read));
            var entry = config(storage).readCollection(id, read);
            if (keptOwner == null) {
              assertNull(entry);
            } else {
              assertEquals(keptOwner, entry.getComponentId());
            }
          } finally {
            read.deactivate();
          }
          if (keptOwner != null) {
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(
                op -> config(storage).dropCollection(op, id));
          }
        } finally {
          storage.stateLock.writeLock().unlock();
        }
      }
    }
  }

  // Operation-visible readers must see deletion before commit and restoration after rollback,
  // while the cache still reports the failed drop. A same-operation replacement is legal.
  @Test
  public void operationVisibleOwnershipAllowsSameOperationReuse() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      int id = session.addCollection("sameoperation");
      var previous = operationEntry(storage, id).getComponentId();
      var create = AbstractStorage.class.getDeclaredMethod("doCreateCollection",
          AtomicOperation.class, String.class, int.class);
      create.setAccessible(true);
      var drop = AbstractStorage.class.getDeclaredMethod("dropCollectionInternal",
          AtomicOperation.class, int.class);
      drop.setAccessible(true);
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          var captured = storage.captureCollectionOwner(id, op);
          assertEquals(previous, captured.componentId());
          invoke(drop, storage, op, id);
          config(storage).dropCollection(op, id);
          ((com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared) storage
              .getLinkCollectionsBtreeCollectionManager()).deleteComponent(op, captured);
          assertNull(config(storage).readCollection(id, op));
          assertNull(config(storage).readCollectionByName("sameoperation", op));
          var replacement = (StorageCollection) invoke(create, storage, op, "sameoperation", id);
          assertEquals(id, replacement.getId());
          assertTrue(!previous.equals(runtimeConfig(replacement).getComponentId()));
        });
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
  }

  // Production reconciliation must reuse the dropped slot and publish a new component owner.
  // Check disk durability after a cold reopen and memory usability in the same instance.
  @Test
  public void schemaDropAndCreatePublishesReusedSlotOnDiskAndMemory() throws Exception {
    schemaDropAndCreateReusesSlot(false);
  }

  // Failed reconciliation must preserve the restored owner's tree, configuration and records.
  // The commit hook proves that cleanup receives a replacement in the dropped slot.
  @Test
  public void failedSchemaSlotReusePreservesRestoredOwnerOnDiskAndMemory() throws Exception {
    schemaDropAndCreateReusesSlot(true);
  }

  private void schemaDropAndCreateReusesSlot(boolean failCommit) throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      create(StorageFormat.LAYOUT_28, type);
      int id;
      Integer expectedOwner;
      String expectedClass = failCommit ? "OriginalOwner" : "ReplacementOwner";
      com.jetbrains.youtrackdb.internal.core.db.record.record.RID expectedRid;
      try (var session = open()) {
        session
            .executeInTx(tx -> session.getMetadata().getSchema().createClass("OriginalOwner", 1));
        id = session.getMetadata().getSchema().getClass("OriginalOwner").getCollectionIds()[0];
        var originalRid = session.computeInTx(tx -> {
          var row = tx.newEntity("OriginalOwner");
          row.setProperty("value", "survives");
          return row.getIdentity();
        });
        var storage = session.getStorage();
        var originalOwner = operationEntry(storage, id).getComponentId();
        var field = AbstractStorage.class.getDeclaredField("collections");
        field.setAccessible(true);
        var collections = (java.util.List<?>) field.get(storage);
        var replacementOwner = new java.util.concurrent.atomic.AtomicReference<Integer>();
        storage.setCommitWindowTestHook(() -> {
          // The hook holds the non-reentrant state write lock. Inspect the captured list directly.
          var replacement = (StorageCollection) collections.get(id);
          assertEquals(id, replacement.getId());
          var componentId = runtimeConfig(replacement).getComponentId();
          assertFalse(originalOwner.equals(componentId));
          replacementOwner.set(componentId);
          if (failCommit) {
            throw new CommandInterruptedException(storage.getName(), "injected slot reuse fault");
          }
        });
        try {
          session.begin();
          var schema = session.getMetadata().getSchema();
          schema.dropClass("OriginalOwner");
          schema.createClass("ReplacementOwner", 1);
          if (failCommit) {
            assertThrows(CommandInterruptedException.class, session::commit);
          } else {
            session.commit();
          }
        } finally {
          storage.setCommitWindowTestHook(null);
        }
        assertTrue("the hook must observe a replacement owner", replacementOwner.get() != null);
        expectedOwner = failCommit ? originalOwner : replacementOwner.get();
        assertEquals(id,
            session.getMetadata().getSchema().getClass(expectedClass).getCollectionIds()[0]);
        assertFalse(session.getMetadata().getSchema()
            .existsClass(failCommit ? "ReplacementOwner" : "OriginalOwner"));
        assertReconciledOwner(session, id, expectedOwner);
        if (failCommit) {
          expectedRid = originalRid;
        } else {
          expectedRid = session.computeInTx(tx -> {
            var row = tx.newEntity(expectedClass);
            row.setProperty("value", "survives");
            return row.getIdentity();
          });
        }
        assertSurvivingRecord(session, expectedRid);
        session.executeInTx(tx -> tx.newEntity(expectedClass).setProperty("value", "writable"));
      }
      if (type == DatabaseType.DISK) {
        ytdb.close();
        ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
        try (var session = open()) {
          assertReconciledOwner(session, id, expectedOwner);
          assertSurvivingRecord(session, expectedRid);
          session.executeInTx(tx -> tx.newEntity(expectedClass).setProperty("value", "reopened"));
        }
      }
      ytdb.drop("counters");
    }
  }

  private static void assertReconciledOwner(DatabaseSessionEmbedded session, int id,
      Integer expectedOwner) throws Exception {
    var storage = session.getStorage();
    assertEquals(expectedOwner, operationEntry(storage, id).getComponentId());
    var collection = storage.getCollectionInstances().stream()
        .filter(c -> c.getId() == id).findFirst().orElseThrow();
    assertEquals(expectedOwner, runtimeConfig(collection).getComponentId());
    var manager = storage.getLinkCollectionsBtreeCollectionManager();
    assertTrue(storage.getWriteCache().files().containsKey(
        treeName(expectedOwner)));
    storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
      var owner = storage.captureCollectionOwner(id, op);
      assertEquals(expectedOwner, owner.componentId());
      assertTrue(owner.tree() != null);
      // Allocate through the restored/published handle, not merely through a cached registry name.
      assertTrue(manager.createBTree(id, op, session).linkBagId() < 0);
    });
  }

  private static void assertSurvivingRecord(DatabaseSessionEmbedded session,
      com.jetbrains.youtrackdb.internal.core.db.record.record.RID rid) {
    session.executeInTx(tx -> {
      var row = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session.load(rid);
      assertEquals("survives", row.getProperty("value"));
    });
  }

  // Both failure points are inside reconciliation, not the post-reconciliation commit hook.
  // The second waits until B is published in A's slot before failing a later create.
  @Test
  public void identityReplacementCreationFailureRestoresPopulatedBag() throws Exception {
    reconcileFailureRestoresOwner(StorageFormat.LAYOUT_28, false);
  }

  @Test
  public void identityReplacementLaterCreateFailureRestoresPopulatedBag() throws Exception {
    reconcileFailureRestoresOwner(StorageFormat.LAYOUT_28, true);
  }

  // Legacy restoration uses only shared drop retention, including the memory-storage path.
  @Test
  public void legacyCreationFailureRestoresPopulatedBag() throws Exception {
    reconcileFailureRestoresOwner(StorageFormat.LAYOUT_27, false);
  }

  @Test
  public void legacyLaterCreateFailureRestoresPopulatedBag() throws Exception {
    reconcileFailureRestoresOwner(StorageFormat.LAYOUT_27, true);
  }

  // Ordinary listener exceptions poison storage before undo. Loaded cleanup and read-only A
  // restoration must still complete even though fresh component writes are rejected.
  @Test
  public void identityReplacementErrorStateStillRestoresOwner() throws Exception {
    reconcileFailureRestoresOwner(StorageFormat.LAYOUT_28, true, true);
  }

  private void reconcileFailureRestoresOwner(StorageFormat format, boolean later) throws Exception {
    reconcileFailureRestoresOwner(format, later, false);
  }

  // Fail after B has a real tree but before its early undo capture receives the handle.
  @Test
  public void identityReplacementTreeCreationFailureRestoresPopulatedBag() throws Exception {
    reconcileFailureRestoresOwner(StorageFormat.LAYOUT_28, false, false, true);
  }

  private void reconcileFailureRestoresOwner(StorageFormat format, boolean later, boolean poison)
      throws Exception {
    reconcileFailureRestoresOwner(format, later, poison, false);
  }

  private void reconcileFailureRestoresOwner(StorageFormat format, boolean later, boolean poison,
      boolean afterTree) throws Exception {
    var types = poison ? new DatabaseType[] {DatabaseType.MEMORY}
        : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY};
    for (var type : types) {
      create(format, type);
      int id;
      Integer originalId;
      String originalName;
      com.jetbrains.youtrackdb.internal.core.db.record.record.RID rid;
      com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer pointer;
      var failedNames = new HashSet<String>();
      try (var session = open()) {
        session.executeInTx(tx -> session.getMetadata().getSchema().createClass("Kept", 1));
        id = session.getMetadata().getSchema().getClass("Kept").getCollectionIds()[0];
        rid = session.computeInTx(tx -> {
          var row = tx.newEntity("Kept");
          row.setProperty("value", "survives");
          return row.getIdentity();
        });
        var storage = session.getStorage();
        var original = storage.getCollectionInstances().stream()
            .filter(c -> c.getId() == id).findFirst().orElseThrow();
        originalName = original.getName();
        originalId = operationEntry(storage, id).getComponentId();
        var manager = storage.getLinkCollectionsBtreeCollectionManager();
        pointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(op -> {
          var p = manager.createBTree(id, op, session);
          manager.loadIsolatedBTree(p).put(op, rid,
              new com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue(
                  1, rid.getCollectionId(), rid.getCollectionPosition(), false));
          return p;
        });
        assertRestoredContents(session, id, originalId, originalName, rid, pointer, failedNames);
        var field = AbstractStorage.class.getDeclaredField("collections");
        field.setAccessible(true);
        var slots = (java.util.List<?>) field.get(storage);
        var injected = new java.util.concurrent.atomic.AtomicBoolean();
        var replacementId = new java.util.concurrent.atomic.AtomicReference<Integer>();
        // Capture the list view before the listener holds the non-reentrant configuration lock.
        var notifications = config(storage).getCollections();
        if (afterTree) {
          storage.setLinkBagCreationTestHook(() -> {
            var op = session.getActiveTransaction().getAtomicOperation();
            var entry = config(storage).readCollection(id, op);
            assertNotNull(entry);
            assertFalse(originalId.equals(entry.getComponentId()));
            replacementId.set(entry.getComponentId());
            failedNames.add(entry.name);
            assertNull(slots.get(id));
            var owner = storage.captureCollectionOwner(id, op);
            assertNotNull(owner.tree());
            assertTrue(op.isFileExists(owner.tree().getFullName()));
            injected.set(true);
            throw new AssertionError("injected after tree creation before full capture");
          });
        }
        config(storage).setConfigurationUpdateListener(update -> {
          if (afterTree || injected.get()) {
            return;
          }
          // Observe the notification payload only to choose the injection point. All restoration
          // ownership assertions below read operation-visible entries, not this eager cache.
          var entry = (StoragePaginatedCollectionConfiguration) notifications.get(id);
          if (entry == null || entry.name.equals(originalName)) {
            return;
          }
          var published = (StorageCollection) slots.get(id);
          if (later && published == null) {
            return;
          }
          assertEquals(id, entry.id);
          if (format == StorageFormat.LAYOUT_28) {
            assertFalse(originalId.equals(entry.getComponentId()));
            replacementId.set(entry.getComponentId());
          }
          failedNames.add(entry.name);
          if (later) {
            assertNotNull(published);
            assertEquals(entry.name, published.getName());
          } else {
            assertNull(published);
          }
          injected.set(true);
          // AssertionError bypasses the configuration listener's Exception wrapper and cache clear.
          // It also avoids poisoning storage so same-instance memory reads remain observable.
          if (poison) {
            throw new IllegalStateException("injected ordinary listener failure");
          }
          throw new AssertionError("injected in-reconcile creation failure");
        });
        try {
          session.begin();
          var schema = session.getMetadata().getSchema();
          schema.dropClass("Kept");
          schema.createClass("Replacement", 1);
          if (later) {
            schema.createClass("Later", 1);
          }
          assertThrows(StorageException.class, session::commit);
        } finally {
          config(storage).setConfigurationUpdateListener(null);
          storage.setLinkBagCreationTestHook(null);
        }
        assertTrue("failure must occur inside reconciliation", injected.get());
        if (poison) {
          assertTrue(storage.isInError());
        }
        if (format == StorageFormat.LAYOUT_28) {
          assertNotNull(replacementId.get());
          var ownersField = manager.getClass().getDeclaredField("createdOwners");
          ownersField.setAccessible(true);
          assertFalse(((java.util.Map<?, ?>) ownersField.get(manager))
              .containsKey(replacementId.get()));
          var failedIdentity =
              new PageFileIdentity(PageFileRole.LINK_BAG_TREE, replacementId.get());
          if (!poison) {
            assertFalse(storage.getWriteCache().files()
                .containsKey(PageFileNames.layout28(failedIdentity)));
          }
          var loadedField = manager.getClass().getDeclaredField("fileIdBTreeMap");
          loadedField.setAccessible(true);
          assertFalse(((java.util.Map<?, ?>) loadedField.get(manager))
              .containsKey(failedIdentity.internalId()));
        }
        assertRestoredContents(session, id, originalId, originalName, rid, pointer, failedNames);
      }
      // An ordinary listener failure clears configuration caches. This fixture verifies the
      // same-instance error-state undo only, not configuration-cache recovery on reopen.
      if (type == DatabaseType.DISK && !poison) {
        ytdb.close();
        ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
        try (var session = open()) {
          assertRestoredContents(session, id, originalId, originalName, rid, pointer, failedNames);
        }
      }
      if (!poison) {
        ytdb.drop("counters");
      }
    }
  }

  private static void assertRestoredContents(DatabaseSessionEmbedded session, int id,
      Integer owner, String originalName,
      com.jetbrains.youtrackdb.internal.core.db.record.record.RID rid,
      com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer pointer,
      HashSet<String> failedNames) throws Exception {
    var storage = session.getStorage();
    assertEquals(id, storage.getCollectionIdByName(originalName));
    assertEquals(owner, operationEntry(storage, id).getComponentId());
    var collection = storage.getCollectionInstances().stream()
        .filter(c -> c.getId() == id).findFirst().orElseThrow();
    assertEquals(originalName, collection.getName());
    assertEquals(owner, runtimeConfig(collection).getComponentId());
    for (var failed : failedNames) {
      assertEquals(-1, storage.getCollectionIdByName(failed));
    }
    var op = storage.getAtomicOperationsManager().startAtomicOperation();
    try {
      assertTrue(collection.readRecord(rid.getCollectionPosition(), op)
          .toRawBuffer().buffer().length > 0);
      var bag = storage.getLinkCollectionsBtreeCollectionManager().loadIsolatedBTree(pointer);
      assertEquals(1, bag.get(rid, op).counter());
      for (var failed : failedNames) {
        assertNull(config(storage).readCollectionByName(failed, op));
      }
    } finally {
      op.deactivate();
    }
    assertSurvivingRecord(session, rid);
  }

  // A published replacement really reuses A's slot. Run only cleanup, with no restore that could
  // hide a missing loaded-state removal. Error state also prevents the cleanup write from starting.
  @Test
  public void reusedSlotCleanupRemovesPublishedOwnerWithoutRestoration() throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      for (boolean poison : new boolean[] {false, true}) {
        create(StorageFormat.LAYOUT_28, type);
        try (var session = open()) {
          var storage = session.getStorage();
          int id = session.addCollection("cleanuporiginal");
          session.dropCollection("cleanuporiginal");
          assertEquals(id, session.addCollection("cleanuppublished"));
          var collection = storage.getCollectionInstances().stream()
              .filter(c -> c.getId() == id).findFirst().orElseThrow();
          var manager = (LinkCollectionsBTreeManagerShared) storage
              .getLinkCollectionsBtreeCollectionManager();
          var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> manager.captureOwner(collection, op));
          var owners = manager.getClass().getDeclaredField("createdOwners");
          owners.setAccessible(true);
          var loaded = manager.getClass().getDeclaredField("fileIdBTreeMap");
          loaded.setAccessible(true);
          int key = AbstractWriteCache.extractFileId(owner.tree().getFileId());
          assertTrue(((java.util.Map<?, ?>) owners.get(manager)).containsKey(owner.componentId()));
          assertSame(owner.tree(), ((java.util.Map<?, ?>) loaded.get(manager)).get(key));
          var legacyCleanup = AbstractStorage.class.getDeclaredMethod(
              "revertCreatedCollectionStructure", int.class, StorageCollection.class,
              boolean.class);
          legacyCleanup.setAccessible(true);
          var rejected = assertThrows(InvocationTargetException.class,
              () -> legacyCleanup.invoke(storage, id, collection, true));
          assertTrue(rejected.getCause() instanceof IllegalStateException);
          var cleanup = AbstractStorage.class.getDeclaredMethod("revertCreatedCollectionStructure",
              int.class, StorageCollection.class, boolean.class, owner.getClass());
          cleanup.setAccessible(true);
          storage.stateLock.writeLock().lock();
          try {
            if (poison) {
              storage.moveToErrorStateIfNeeded(
                  new StorageException(storage.getName(), "injected cleanup error state"));
            }
            invoke(cleanup, storage, id, collection, true, owner);
            assertFalse(((java.util.Map<?, ?>) owners.get(manager))
                .containsKey(owner.componentId()));
            assertFalse(((java.util.Map<?, ?>) loaded.get(manager)).containsKey(key));
            assertEquals(poison,
                storage.getWriteCache().files().containsKey(treeName(owner.componentId())));
            if (poison) {
              assertTrue(storage.isInError());
              var read = storage.getAtomicOperationsManager().startAtomicOperation();
              try {
                assertTrue("error state blocks file cleanup", collection.exists(read));
              } finally {
                read.deactivate();
              }
            }
          } finally {
            storage.stateLock.writeLock().unlock();
          }
        }
        ytdb.drop("counters");
      }
    }
  }

  // The existing post-reconciliation hook removes A's required file outside the rolled-back
  // operation. Failed-commit undo must not publish A, and must preserve the primary failure.
  @Test
  public void failedCommitMissingRequiredTreeDoesNotPublishOriginal() throws Exception {
    assertMissingRequiredTreeDoesNotPublishOriginal(false);
  }

  // An ordinary drop-only commit failure sets error state before undo. Missing-tree restoration
  // must preserve that stored failure and leave the original unregistered. No replacement cleanup
  // runs, so a blocked cleanup write cannot change error state before the restoration catch.
  @Test
  public void failedCommitMissingRequiredTreePreservesStoredCommitFailure() throws Exception {
    assertMissingRequiredTreeDoesNotPublishOriginal(true);
  }

  private void assertMissingRequiredTreeDoesNotPublishOriginal(boolean ordinaryFailure)
      throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      session
          .executeInTx(tx -> session.getMetadata().getSchema().createClass("MissingOriginal", 1));
      var storage = session.getStorage();
      int id = session.getMetadata().getSchema().getClass("MissingOriginal").getCollectionIds()[0];
      var original = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> storage.captureCollectionOwner(id, op));
      var injected = ordinaryFailure
          ? new StorageException(storage.getName(), "primary missing-tree commit failure")
          : new AssertionError("primary missing-tree commit failure");
      storage.setCommitWindowTestHook(() -> {
        try {
          storage.getReadCache().deleteFile(original.tree().getFileId(), storage.getWriteCache());
        } catch (java.io.IOException e) {
          throw new AssertionError(e);
        }
        if (injected instanceof StorageException storageFailure) {
          throw storageFailure;
        }
        throw (AssertionError) injected;
      });
      try {
        session.begin();
        var schema = session.getMetadata().getSchema();
        schema.dropClass("MissingOriginal");
        if (!ordinaryFailure) {
          schema.createClass("MissingReplacement", 1);
        }
        var failure = assertThrows(StorageException.class, session::commit);
        if (ordinaryFailure) {
          assertSame(injected, failure);
        } else {
          assertSame(injected, failure.getCause());
        }
      } finally {
        storage.setCommitWindowTestHook(null);
      }
      assertTrue(storage.isInError());
      assertEquals(-1, storage.getCollectionIdByName("missingoriginal"));
      assertTrue(storage.getCollectionInstances().stream().noneMatch(c -> c.getId() == id));
      var storedFailure = assertThrows(StorageException.class, storage::checkErrorState);
      if (ordinaryFailure) {
        assertSame(injected, storedFailure.getCause());
      }
    }
  }

  // Required identity-layout restoration rejects absence rather than silently accepting it.
  @Test
  public void identityRestoreRejectsMissingTreeButLegacyRestoreToleratesIt() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      var storage = session.getStorage();
      int id = session.addCollection("missingtree");
      var manager = (LinkCollectionsBTreeManagerShared) storage
          .getLinkCollectionsBtreeCollectionManager();
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          var owner = storage.captureCollectionOwner(id, op);
          manager.deleteComponent(op, owner);
          assertThrows(StorageException.class, () -> manager.restoreComponent(op, owner));
          manager.restoreComponentByCollectionId(op, id);
        });
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
  }

  // Restoration must invalidate the creation-owner cache so captures and writers share one tree.
  @Test
  public void restoredTreeRetainsCapturedLockAndInvalidatesCachedCreationOwner() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      int id = session.addCollection("restoredtree");
      var storage = session.getStorage();
      var collection = storage.getCollectionInstances().stream()
          .filter(c -> c.getId() == id).findFirst().orElseThrow();
      var manager =
          (com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared) storage
              .getLinkCollectionsBtreeCollectionManager();
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          var before = manager.captureOwner(collection, op);
          manager.restoreComponent(op, before);
          var after = manager.captureOwner(collection, op);
          var mapped = storage.captureCollectionOwner(id, op);
          assertSame(before.tree(), mapped.tree());
          assertSame(mapped.tree(), after.tree());
          manager.deleteComponent(op, after);
          assertNull(manager.captureOwner(id, after.componentId(), op).tree());
        });
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
  }

  // Owner-aware allocation must complete while the caller owns the state write lock.
  // A state read-lock re-entry cannot succeed, so the timeout makes that regression fail.
  @Test(timeout = 30000)
  public void ownerAwareBagAllocationDoesNotReenterStateLock() throws Exception {
    create(StorageFormat.LAYOUT_28, DatabaseType.MEMORY);
    try (var session = open()) {
      int id = session.addCollection("lockedallocation");
      var storage = session.getStorage();
      var manager =
          (com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared) storage
              .getLinkCollectionsBtreeCollectionManager();
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          var pointer = manager.createBTree(id, op, session);
          assertTrue(pointer.linkBagId() < 0);
          assertTrue(storage.captureCollectionOwner(id, op).tree() != null);
          manager.deleteComponent(op, storage.captureCollectionOwner(id, op));
          assertThrows(StorageException.class, () -> manager.createBTree(id, op, session));
        });
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    }
  }

  // Layout 27 retains its first-null allocator even when a stored configuration owns that slot.
  @Test
  public void layout27StillSelectsConfigurationOwnedNullSlot() throws Exception {
    create(StorageFormat.LAYOUT_27, DatabaseType.DISK);
    try (var session = open()) {
      var storage = session.getStorage();
      int id = session.addCollection("legacyslot");
      var field = AbstractStorage.class.getDeclaredField("collections");
      field.setAccessible(true);
      ((java.util.List<?>) field.get(storage)).set(id, null);
      var next = AbstractStorage.class.getDeclaredMethod("nextFreeCollectionId",
          AtomicOperation.class);
      next.setAccessible(true);
      storage.getAtomicOperationsManager()
          .executeInsideAtomicOperation(op -> assertEquals(id, invoke(next, storage, op)));
    }
  }

  private static StoragePaginatedCollectionConfiguration operationEntry(AbstractStorage storage,
      int id) throws Exception {
    return storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
        op -> config(storage).readCollection(id, op));
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
    assertEquals(28, storage.getStorageFormat().layoutVersion());
    for (var kind : PageFileKind.values()) {
      assertTrue(counter(storage, kind) < id);
    }
    for (var role : new PageFileRole[] {PageFileRole.RECORD_DATA, PageFileRole.INDEX_DATA,
        PageFileRole.CONFIG_DATA}) {
      var identity = new PageFileIdentity(role, id);
      storage.getWriteCache().addFile(PageFileNames.layout28(identity), identity.internalId());
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
