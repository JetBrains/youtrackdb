package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.File;
import java.lang.reflect.Method;
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

  // Birth reserves configuration zero and leaves collection allocation unused in both profiles.
  @Test
  public void birthReservesFixedConfigurationIdentityInDiskAndMemory() throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      create(StorageFormat.LAYOUT_26, type);
      try (var session = open()) {
        var storage = session.getStorage();
        assertEquals(26, storage.getStorageFormat().layoutVersion());
        assertEquals(0, counter(storage, PageFileKind.COLLECTION));
        assertEquals(0, counter(storage, PageFileKind.CONFIGURATION));
        assertTrue(counter(storage, PageFileKind.INDEX_ENGINE) > 0);
        assertEquals(0, PageFileIdentity.CONFIGURATION_COMPONENT_ID);
        for (var role : PageFileRole.values()) {
          if (role.kind() == PageFileKind.CONFIGURATION) {
            assertTrue(new PageFileIdentity(role, 0).internalId() > 0);
          }
        }
        assertEquals(1, allocate(storage, PageFileKind.COLLECTION));
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
      storage.stateLock.writeLock().lock();
      try {
        executor.submit(() -> assertThrows(AssertionError.class,
            () -> storage.allocateComponentId(PageFileKind.COLLECTION, null)))
            .get(10, TimeUnit.SECONDS);
      } finally {
        storage.stateLock.writeLock().unlock();
      }
      assertEquals(0, counter(storage, PageFileKind.COLLECTION));
      assertTrue(storage.stateLock.exclusiveLockWithAbort(() -> false, 1000000));
      try {
        assertEquals(1, (int) storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            op -> storage.allocateComponentId(PageFileKind.COLLECTION, op)));
      } finally {
        storage.stateLock.exclusiveUnlock();
      }
      assertEquals(1, counter(storage, PageFileKind.COLLECTION));
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
