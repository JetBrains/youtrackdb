package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.File;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Verifies link-bag counter recovery through write-ahead log replay. */
@Category(SequentialTest.class)
public class LinkCollectionsBTreeManagerSharedAllocatorCrashRecoveryIT {

  private String directory;
  private YouTrackDBImpl youTrackDB;

  @Before
  public void setUp() {
    directory = DbTestBase.getBaseDirectoryPathStr(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directory);
  }

  @After
  public void tearDown() throws Exception {
    if (youTrackDB != null) {
      youTrackDB.close();
    }
    FileUtils.deleteDirectory(new File(directory));
  }

  // Dirty copies omit committed data pages, so both counts and component counters need WAL redo.
  // This extends the existing crash harness rather than introducing a second recovery fixture.
  @Test(timeout = 180000)
  public void bothLayoutsReplayHistogramAndLayout28ReplaysEveryComponentKind() throws Exception {
    for (var format : new StorageFormat[] {StorageFormat.LAYOUT_27, StorageFormat.LAYOUT_28}) {
      var sourceName = "identitySource" + format;
      var recoveredName = "identityRecovered" + format;
      try (var ignored =
          StorageBirthTestSupport.selectBirthFormat(youTrackDB, sourceName, format)) {
        youTrackDB.create(sourceName, DatabaseType.DISK, "admin", "admin", "admin");
      }
      try (var session = (DatabaseSessionEmbedded) youTrackDB.open(sourceName, "admin", "admin")) {
        var cls = session.getMetadata().getSchema().createClass("CrashHistogram", 1);
        cls.createProperty("value", PropertyType.STRING);
        cls.createIndex("CrashHistogram.value", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
      }
      youTrackDB.close();
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(directory);
      int[] issued = new int[PageFileKind.values().length];
      var expectedFiles = new java.util.HashMap<String, Integer>();
      try (var session = (DatabaseSessionEmbedded) youTrackDB.open(sourceName, "admin", "admin")) {
        var storage = (DiskStorage) session.getStorage();
        WalTestUtils.withWalProtection(session, () -> {
          var created = session.getMetadata().getSchema().createClass("WalCreated", 1);
          created.createProperty("value", PropertyType.STRING);
          created.createIndex("WalCreated.value", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
          session.executeInTx(tx -> {
            for (int i = 0; i < 17; i++) {
              tx.newEntity("CrashHistogram").setProperty("value", "value-" + i);
            }
          });
          histogram(storage).flushIfDirtyOrFail();
          if (format == StorageFormat.LAYOUT_28) {
            for (var kind : PageFileKind.values()) {
              issued[kind.ordinal()] = allocateForRecovery(storage, kind);
            }
          }
          storage.getWriteCache().files().forEach((name, fileId) -> {
            if (PageFileNames.parseLayout28(name).isPresent() || name.endsWith(".ixs")) {
              expectedFiles.put(name,
                  AbstractWriteCache.extractFileId(fileId));
            }
          });
          storage.getWALInstance().flush();
          WalTestUtils.copyDirtyStorage(java.nio.file.Path.of(session.getURL().substring(5)),
              Path.of(directory, recoveredName));
        });
      }
      try (var session =
          (DatabaseSessionEmbedded) youTrackDB.open(recoveredName, "admin", "admin")) {
        var storage = session.getStorage();
        assertTrue("Dirty image must actually replay WAL", storage.wereDataRestoredAfterOpen());
        assertEquals(17, histogram(storage).getStatistics().totalCount());
        session.executeInTx(tx -> assertEquals(17, session.countClass("CrashHistogram")));
        assertTrue(session.getMetadata().getSchema().existsClass("WalCreated"));
        for (var entry : expectedFiles.entrySet()) {
          assertEquals((int) entry.getValue(),
              AbstractWriteCache
                  .extractFileId(storage.getWriteCache().files().get(entry.getKey())));
          var identity = PageFileNames.parseLayout28(entry.getKey());
          if (identity.isPresent()) {
            assertEquals(identity.get().internalId(), (int) entry.getValue());
            assertTrue(java.nio.file.Files.exists(Path.of(directory, recoveredName,
                PageFileNames.layout28DiskName(identity.get()))));
          }
        }
        if (format == StorageFormat.LAYOUT_28) {
          for (var kind : PageFileKind.values()) {
            var configuration = configuration(storage);
            assertEquals(issued[kind.ordinal()],
                (int) storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
                    op -> configuration.getComponentIdCounter(kind, op)));
            assertEquals(issued[kind.ordinal()] + 1, allocateForRecovery(storage, kind));
          }
        }
      }
    }
  }

  private static IndexHistogramManager histogram(AbstractStorage storage) throws Exception {
    var engine =
        (BTreeIndexEngine) storage.getIndexEngine(storage.loadIndexEngine("CrashHistogram.value"));
    return engine.getHistogramManager();
  }

  private static int allocateForRecovery(AbstractStorage storage, PageFileKind kind)
      throws Exception {
    var lockField = AbstractStorage.class.getDeclaredField("stateLock");
    lockField.setAccessible(true);
    var lock = (ScalableRWLock) lockField.get(storage);
    var allocate = AbstractStorage.class.getDeclaredMethod("allocateComponentId",
        PageFileKind.class, AtomicOperation.class);
    allocate.setAccessible(true);
    lock.writeLock().lock();
    try {
      return storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> (int) allocate.invoke(storage, kind, op));
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static CollectionBasedStorageConfiguration configuration(AbstractStorage storage)
      throws Exception {
    var field = AbstractStorage.class.getDeclaredField("configuration");
    field.setAccessible(true);
    return (CollectionBasedStorageConfiguration) field.get(storage);
  }

  @Test
  public void committedEmptyBagAllocationIsReplayedFromWal() throws Exception {
    var sourceName = "linkBagCounterSource";
    var recoveredName = "linkBagCounterRecovered";
    youTrackDB.create(
        sourceName,
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    int collectionId;
    try (var session = (DatabaseSessionEmbedded) youTrackDB.open(sourceName, "admin", "admin")) {
      collectionId = session.getMetadata().getSchema().createClass("CrashOwner")
          .getCollectionIds()[0];
    }

    // Persist the entry-point page with counter zero before the allocation under test.
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directory);

    try (var session = (DatabaseSessionEmbedded) youTrackDB.open(sourceName, "admin", "admin")) {
      var sourcePath = Path.of(session.getURL().substring("disk:".length()));
      var recoveredPath = Path.of(directory).resolve(recoveredName);
      var storage = (DiskStorage) session.getStorage();

      WalTestUtils.withWalProtection(session, () -> {
        // The paused page flusher keeps the copied entry point at counter zero.
        var pointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            operation -> session.getBTreeCollectionManager()
                .createBTree(collectionId, operation, session));
        assertEquals(-1L, pointer.linkBagId());
        storage.getWALInstance().flush();
        WalTestUtils.copyDirtyStorage(sourcePath, recoveredPath);
      });
    }

    try (var session =
        (DatabaseSessionEmbedded) youTrackDB.open(recoveredName, "admin", "admin")) {
      var storage = (AbstractStorage) session.getStorage();
      assertTrue("The copied dirty storage must replay its write-ahead log",
          storage.wereDataRestoredAfterOpen());

      var pointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          operation -> session.getBTreeCollectionManager()
              .createBTree(collectionId, operation, session));
      assertEquals(-2L, pointer.linkBagId());
    }
  }
}
