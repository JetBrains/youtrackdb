package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class IndexHistogramIdentityTest {
  private String directory;
  private YouTrackDBImpl ytdb;

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

  private void create(DatabaseType type, StorageFormat format) throws Exception {
    try (var ignored = StorageBirthTestSupport.selectBirthFormat(ytdb, "hist", format)) {
      ytdb.create("hist", type, "admin", "adminpwd", "admin");
    }
  }

  private DatabaseSessionEmbedded open() {
    return (DatabaseSessionEmbedded) ytdb.open("hist", "admin", "adminpwd");
  }

  private static IndexEngineData data(AbstractStorage storage) throws Exception {
    return storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
        op -> ((CollectionBasedStorageConfiguration) storage.configuration)
            .getIndexEngine("Hist.value", -1, op));
  }

  private static BTreeIndexEngine engine(AbstractStorage storage) throws Exception {
    return (BTreeIndexEngine) storage.getIndexEngine(storage.loadIndexEngine("Hist.value"));
  }

  private static void createIndex(DatabaseSessionEmbedded session) {
    createIndex(session, true);
  }

  private static void createIndex(DatabaseSessionEmbedded session, boolean populate) {
    var cls = session.getMetadata().getSchema().createClass("Hist");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("Hist.value", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
    if (populate) {
      session.executeInTx(tx -> tx.newEntity("Hist").setProperty("value", "retained"));
    }
  }

  private static void assertHistogram(AbstractStorage storage, int id) throws Exception {
    var name = "ytdb-p28-index-" + id + "-histogram.ixs";
    var registered = storage.getWriteCache().files().get(name);
    assertNotNull(registered);
    assertEquals((2 << 28) | (4 << 25) | id,
        AbstractWriteCache.extractFileId(registered));
    var manager = engine(storage).getHistogramManager();
    assertNotNull(manager);
    assertEquals(name, manager.getLockName());
    for (var role : new PageFileRole[] {PageFileRole.INDEX_DATA, PageFileRole.NULL_KEY_DATA}) {
      var treeLock = PageFileNames.layout28(new PageFileIdentity(role, id));
      assertNotEquals(treeLock, manager.getLockName());
      assertNotSame(storage.getAtomicOperationsManager().getApplyPhaseEpoch(treeLock),
          manager.getApplyPhaseEpoch());
    }
    storage.getAtomicOperationsManager().executeInsideAtomicOperation(
        op -> assertTrue(manager.statsFileExists(op)));
  }

  // A real missing disk file becomes a deletion entry on cold open. Reconstruction must
  // preserve the encoded name/id, restore counters from the tree, and remain droppable.
  @Test
  public void diskColdOpenReconstructsMissingHistogramUnderTheSameIdentity() throws Exception {
    create(DatabaseType.DISK, StorageFormat.LAYOUT_28);
    final int id;
    final java.nio.file.Path diskFile;
    try (var session = open()) {
      createIndex(session);
      var storage = session.getStorage();
      id = data(storage).getFileBaseId();
      assertHistogram(storage, id);
      diskFile = ((WOWCache) storage.getWriteCache()).getRootDirectory().resolve(
          PageFileNames.layout28DiskName(new PageFileIdentity(PageFileRole.HISTOGRAM, id)));
    }
    ytdb.close();
    Files.delete(diskFile);
    ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    try (var session = open()) {
      var storage = session.getStorage();
      assertHistogram(storage, id);
      assertTrue(Files.exists(diskFile));
      assertEquals(1, engine(storage).getHistogramManager().getStatistics().totalCount());
      session.execute("drop index Hist.value").close();
      assertFalse(storage.engineFilesPresent(id));
      assertFalse(Files.exists(diskFile));
    }
  }

  // Memory deletion removes the registration. The real load-time wiring must reconstruct it
  // with the same supplied id while holding the storage write lock.
  @Test
  public void memoryLoadReconstructsDeletedHistogram() throws Exception {
    create(DatabaseType.MEMORY, StorageFormat.LAYOUT_28);
    try (var session = open()) {
      createIndex(session);
      var storage = session.getStorage();
      var entry = data(storage);
      var tree = engine(storage);
      var identity = new PageFileIdentity(PageFileRole.HISTOGRAM, entry.getFileBaseId());
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          tree.getHistogramManager().deleteStatsFile(op);
        });
        assertNull(storage.getWriteCache().registeredFileId(PageFileNames.layout28(identity)));
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> wire(storage, tree, entry, op));
      } finally {
        storage.stateLock.writeLock().unlock();
      }
      assertHistogram(storage, entry.getFileBaseId());
      assertEquals(1, tree.getHistogramManager().getStatistics().totalCount());
      session.execute("drop index Hist.value").close();
      assertFalse(storage.engineFilesPresent(entry.getFileBaseId()));
    }
  }

  private static void wire(AbstractStorage storage, BTreeIndexEngine tree,
      IndexEngineData entry, AtomicOperation op) throws java.io.IOException {
    try {
      var method = AbstractStorage.class.getDeclaredMethod("wireHistogramManagerOnLoad",
          BTreeIndexEngine.class, IndexEngineData.class, AtomicOperation.class);
      method.setAccessible(true);
      method.invoke(storage, tree, entry, op);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new AssertionError(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  // Every single survivor, especially the histogram alone, activates family cleanup.
  // Legacy stems, other identities, and non-index roles must not activate layout 28 cleanup.
  @Test
  public void everyRoleAloneIsFamilyEvidenceInDiskAndMemory() throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      create(type, StorageFormat.LAYOUT_28);
      try (var session = open()) {
        var storage = session.getStorage();
        var cache = storage.getWriteCache();
        assertFalse(storage.engineFilesPresent(9000));
        cache.addFile("ie_9000.ixs");
        var unrelated = new PageFileIdentity(PageFileRole.FREE_SPACE_MAP, 9000);
        cache.addFile(PageFileNames.layout28(unrelated), unrelated.internalId());
        var other = new PageFileIdentity(PageFileRole.HISTOGRAM, 9001);
        cache.addFile(PageFileNames.layout28(other), other.internalId());
        assertFalse(storage.engineFilesPresent(9000));
        for (var role : PageFileRole.values()) {
          if (role.kind() != PageFileKind.INDEX_ENGINE) {
            continue;
          }
          var identity = new PageFileIdentity(role, 9000);
          var name = PageFileNames.layout28(identity);
          long fileId = cache.addFile(name, identity.internalId());
          assertTrue("Single survivor: " + role, storage.engineFilesPresent(9000));
          storage.getReadCache().deleteFile(fileId, cache);
          assertFalse(storage.engineFilesPresent(9000));
        }
      }
      ytdb.drop("hist");
    }
  }

  // A wrong registration must escape the load-time IOException warning handler as a storage
  // error. The manager must not silently disappear and family detection must also reject it.
  @Test
  public void identityConflictIsNotConvertedToMissingHistogramWarning() throws Exception {
    create(DatabaseType.MEMORY, StorageFormat.LAYOUT_28);
    try (var session = open()) {
      createIndex(session);
      var storage = session.getStorage();
      var entry = data(storage);
      var tree = engine(storage);
      var identity = new PageFileIdentity(PageFileRole.HISTOGRAM, entry.getFileBaseId());
      var name = PageFileNames.layout28(identity);
      storage.stateLock.writeLock().lock();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> tree.getHistogramManager().deleteStatsFile(op));
        long wrongId = storage.getWriteCache().addFile(name);
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          assertThrows(StorageException.class, () -> wire(storage, tree, entry, op));
        });
        assertNotNull(tree.getHistogramManager());
        assertThrows(StorageException.class,
            () -> storage.engineFilesPresent(entry.getFileBaseId()));
        storage.getReadCache().deleteFile(wrongId, storage.getWriteCache());
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> wire(storage, tree, entry, op));
      } finally {
        storage.stateLock.writeLock().unlock();
      }
      assertHistogram(storage, entry.getFileBaseId());
    }
  }

  // A failed drop/recreate must clean only the failed identity, preserve the old config owner
  // and registry, burn the failed id, and let a later retry allocate beyond that id.
  @Test
  public void failedCommitCleansIdentityFamilyAndPreservesPreviousOwner() throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      create(type, StorageFormat.LAYOUT_28);
      try (var session = open()) {
        // In-commit builds require an empty source collection.
        createIndex(session, false);
        var storage = session.getStorage();
        int oldId = data(storage).getFileBaseId();
        int[] failedId = {-1};
        storage.setPostEngineBuildTestHook(() -> {
          failedId[0] = (int) storage.indexEngineFileBaseIdHwmForTesting();
          throw new CommandInterruptedException("hist", "injected after family creation");
        });
        try {
          session.begin();
          session.getSharedContext().getIndexManager().dropIndex(session, "Hist.value");
          session.getMetadata().getSchema().getClass("Hist")
              .createIndex("Hist.value", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
          var failure = assertThrows(RuntimeException.class, session::commit);
          assertTrue("Post-build hook did not run: " + failure, failedId[0] > oldId);
        } finally {
          storage.setPostEngineBuildTestHook(null);
        }
        assertTrue(failedId[0] > oldId);
        assertEquals(oldId, data(storage).getFileBaseId());
        assertHistogram(storage, oldId);
        assertFalse(storage.engineFilesPresent(failedId[0]));
        for (var role : PageFileRole.values()) {
          if (role.kind() == PageFileKind.INDEX_ENGINE) {
            var identity = new PageFileIdentity(role, failedId[0]);
            assertFalse(
                storage.getWriteCache().files().containsKey(PageFileNames.layout28(identity)));
            if (storage.getWriteCache() instanceof WOWCache disk) {
              assertFalse(Files.exists(disk.getRootDirectory().resolve(
                  PageFileNames.layout28DiskName(identity))));
            }
          }
        }
        session.execute("drop index Hist.value").close();
        session.getMetadata().getSchema().getClass("Hist")
            .createIndex("Hist.value", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
        assertTrue(data(storage).getFileBaseId() > failedId[0]);
      }
      ytdb.drop("hist");
    }
  }
}
