package com.jetbrains.youtrackdb.internal.core.storage.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Metadata-only fixtures. No assertion here claims physical absence of a file. */
@Category(SequentialTest.class)
@RunWith(Parameterized.class)
public class CommittedFileStatesTest {

  @Parameterized.Parameters(name = "{0}-{1}")
  public static List<Object[]> parameters() {
    return List.of(new Object[] {DatabaseType.DISK, StorageFormat.LAYOUT_25},
        new Object[] {DatabaseType.DISK, StorageFormat.LAYOUT_26},
        new Object[] {DatabaseType.MEMORY, StorageFormat.LAYOUT_25},
        new Object[] {DatabaseType.MEMORY, StorageFormat.LAYOUT_26});
  }

  private final DatabaseType type;
  private final StorageFormat format;

  public CommittedFileStatesTest(DatabaseType type, StorageFormat format) {
    this.type = type;
    this.format = format;
  }

  /** Real commits publish final masks only. Real rollbacks retain both old records and copy. */
  @Test
  public void lifecyclePublishesFinalValuesAndRollbackPublishesNothing() throws Exception {
    try (var manager = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + "-" + type + "-" + format)) {
      try (var birth = StorageBirthTestSupport.selectBirthFormat(manager, "copy", format)) {
        manager.create("copy", type, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open("copy", "admin",
          DbTestBase.ADMIN_PASSWORD)) {
        var storage = session.getStorage();
        var config = (CollectionBasedStorageConfiguration) field(storage, "configuration");
        var stateLock = (ScalableRWLock) field(storage, "stateLock");
        var operations = storage.getAtomicOperationsManager();
        var fixture = new StoragePaginatedCollectionConfiguration(
            999, "copyfixture", null, true, 0, 0, null, null, null, null, 3);
        var initial = format == StorageFormat.LAYOUT_26 ? 0 : 31;

        // A rolled-back create must leave missing, not a live zero-mask target.
        mutate(operations, stateLock, true, op -> {
          create(config, fixture, op);
          assertNull(config.getCommittedCollectionFileState(999));
          assertEquals(initial, config.getCollectionFileState(999, op).bits());
          for (boolean multi : new boolean[] {false, true}) {
            var name = multi ? "manycopy" : "onecopy";
            config.addIndexEngine(op, name, engine(name, multi));
            assertNull(config.getCommittedIndexEngineFileState(name));
          }
        });
        assertNull(config.getCommittedCollectionFileState(999));
        assertNull(config.getCommittedIndexEngineFileState("onecopy"));
        assertNull(config.getCommittedIndexEngineFileState("manycopy"));

        // Fail preparation before registration. A previously committed target stays intact.
        var states = new CommittedFileStates<CollectionFileState>();
        mutate(operations, stateLock, false, op -> states.prepare("probe",
            CollectionFileState.ALL_CREATED, op, storage, "probe.pcl"));
        operations.executeInsideAtomicOperation(
            op -> assertThrows(IllegalStateException.class, () -> states.prepare("probe",
                new CollectionFileState(0), op, storage, "probe.pcl")));
        assertEquals(CollectionFileState.ALL_CREATED, states.get("probe"));

        mutate(operations, stateLock, false, op -> {
          create(config, fixture, op);
          for (boolean multi : new boolean[] {false, true}) {
            var name = multi ? "manycopy" : "onecopy";
            var data = engine(name, multi);
            if (format == StorageFormat.LAYOUT_26) {
              config.createIndexEngineForTesting(op, name, data,
                  new IndexEngineFileState(multi, 0));
            } else {
              config.addIndexEngine(op, name, data);
            }
            assertNull(config.getCommittedIndexEngineFileState(name));
          }
        });
        compareFromSeparateReader(storage, config, initial, initial == 0 ? 0 : 7,
            initial == 0 ? 0 : 31);

        assertThrows(IllegalStateException.class, storage::requireFileStateWriterExclusion);
        // Rollback must discard accumulated marks, not only creates and drops.
        mutate(operations, stateLock, true, op -> {
          config.markCollectionFileCreated(999, CollectionFileState.Role.DATA, op);
          config.markIndexEngineFileCreated("onecopy", IndexEngineFileState.Role.MAIN_TREE, op);
          config.markIndexEngineFileCreated("manycopy", IndexEngineFileState.Role.NULL_KEY_TREE,
              op);
        });
        compareFromSeparateReader(storage, config, initial, initial == 0 ? 0 : 7,
            initial == 0 ? 0 : 31);

        // A real writer fails during staging, after its transactional record write.
        // Force rollback after inspecting the pending record. Neither committed view may change.
        if (format == StorageFormat.LAYOUT_26) {
          var cacheField = CollectionBasedStorageConfiguration.class.getDeclaredField("cache");
          cacheField.setAccessible(true);
          @SuppressWarnings("unchecked")
          var cache = (Map<String, Object>) cacheField.get(config);
          var savedCache = new java.util.HashMap<>(cache);
          mutate(operations, stateLock, true, op -> {
            stateLock.writeLock().unlock();
            try {
              assertThrows(RuntimeException.class, () -> config.markCollectionFileCreated(
                  999, CollectionFileState.Role.DATA, op));
              assertEquals(1, config.getCollectionFileState(999, op).bits());
              assertEquals(0, config.getCommittedCollectionFileState(999).bits());
            } finally {
              stateLock.writeLock().lock();
            }
          });
          // storeProperty invalidates the nontransactional cache on failure. Restore this
          // fixture's snapshot so later lifecycle checks do not depend on cache reload behavior.
          cache.putAll(savedCache);
          compareFromSeparateReader(storage, config, 0, 0, 0);
        }

        // Partial masks must survive repeated marks and a metadata rewrite without filling holes.
        mutate(operations, stateLock, false, op -> {
          config.markCollectionFileCreated(999, CollectionFileState.Role.DATA, op);
          config.markCollectionFileCreated(999, CollectionFileState.Role.DATA, op);
          config.updateCollection(op, fixture);
          for (var name : new String[] {"onecopy", "manycopy"}) {
            config.markIndexEngineFileCreated(name, IndexEngineFileState.Role.MAIN_TREE, op);
            config.markIndexEngineFileCreated(name, IndexEngineFileState.Role.MAIN_TREE, op);
            assertEquals(initial == 0 ? 1 : name.equals("onecopy") ? 7 : 31,
                config.getIndexEngineFileState(name, op).bits());
          }
          assertEquals(initial == 0 ? 1 : 31, config.getCollectionFileState(999, op).bits());
        });
        compareFromSeparateReader(storage, config, initial == 0 ? 1 : 31,
            initial == 0 ? 1 : 7, initial == 0 ? 1 : 31);

        // These are real reader domains, not names supplied by the publication helper.
        var liveId = session.getMetadata().getSchema().getClass("V").getCollectionIds()[0];
        var liveName = session.getCollectionNameById(liveId);
        var domains = List.of("copyfixture.pcl", "global_collection_999.grb",
            liveName + ".pcl", "global_collection_" + liveId + ".grb",
            "ie_999.cbt", "ie_999.ixs", "ie_1000.cbt", "ie_1000$null.cbt", "ie_1000.ixs");
        var attempts = domains.stream().map(name -> {
          var attempt = new OptimisticReadScope(operations.getApplyPhaseEpoch(name));
          attempt.reset();
          return attempt;
        }).toList();

        mutate(operations, stateLock, false, op -> {
          // A deliberately different metadata name pins resolution through the live component.
          config.updateCollection(op, new StoragePaginatedCollectionConfiguration(
              liveId, "not_the_live_domain", null, true, 0, 0, null, null, null, null, 3));
          for (var role : CollectionFileState.Role.values()) {
            config.markCollectionFileCreated(999, role, op);
            config.markCollectionFileCreated(999, role, op);
          }
          config.updateCollection(op, fixture);
          for (var name : new String[] {"onecopy", "manycopy"}) {
            boolean multi = name.equals("manycopy");
            for (var role : IndexEngineFileState.Role.values()) {
              if (multi || role.ordinal() < 3) {
                config.markIndexEngineFileCreated(name, role, op);
                config.markIndexEngineFileCreated(name, role, op);
              }
            }
          }
          assertEquals(initial == 0 ? 1 : 31,
              config.getCommittedCollectionFileState(999).bits());
          assertEquals(31, config.getCollectionFileState(999, op).bits());
        });
        for (int i = 0; i < attempts.size(); i++) {
          assertThrows(domains.get(i), OptimisticReadFailedException.class,
              attempts.get(i)::validateOrThrow);
        }
        compareFromSeparateReader(storage, config, 31, 7, 31);

        // A later unrelated-unit commit must not replace the first unit's target or value.
        mutate(operations, stateLock, false, op -> config.createCollection(op,
            new StoragePaginatedCollectionConfiguration(
                1000, "othercopy", null, true, 0, 0, null, null, null, null, 3)));
        assertEquals(31, config.getCommittedCollectionFileState(1000).bits());
        compareFromSeparateReader(storage, config, 31, 7, 31);

        mutate(operations, stateLock, true, op -> {
          drop(config, op);
          assertEquals(31, config.getCommittedCollectionFileState(999).bits());
          assertEquals(7, config.getCommittedIndexEngineFileState("onecopy").bits());
        });
        compareFromSeparateReader(storage, config, 31, 7, 31);

        mutate(operations, stateLock, false, op -> {
          drop(config, op);
          config.dropCollection(op, 1000);
        });
        assertNull(config.getCommittedCollectionFileState(999));
        assertNull(config.getCommittedIndexEngineFileState("onecopy"));
        assertNull(config.getCommittedIndexEngineFileState("manycopy"));
        operations.executeInsideAtomicOperation(op -> {
          assertThrows(RuntimeException.class, () -> config.getCollectionFileState(999, op));
          assertNull(config.getIndexEngine("onecopy", -1, op));
          assertNull(config.getIndexEngine("manycopy", -1, op));
        });
      } finally {
        manager.drop("copy");
      }
    }
  }

  private void create(CollectionBasedStorageConfiguration config,
      StoragePaginatedCollectionConfiguration fixture, AtomicOperation op) {
    if (format == StorageFormat.LAYOUT_26) {
      config.createCollectionForTesting(op, fixture, new CollectionFileState(0));
    } else {
      config.createCollection(op, fixture);
    }
  }

  private static void drop(CollectionBasedStorageConfiguration config, AtomicOperation op) {
    config.dropCollection(op, 999);
    config.deleteIndexEngine(op, "onecopy");
    config.deleteIndexEngine(op, "manycopy");
  }

  private static IndexEngineData engine(String name, boolean multi) {
    return new IndexEngineData(999, multi ? 1000 : 999, name, "BTREE", null, false, 4, 1,
        multi, (byte) 0, (byte) 0, false, new PropertyTypeInternal[] {PropertyTypeInternal.STRING},
        true, 1, null, null, Map.of());
  }

  private static void compareFromSeparateReader(AbstractStorage storage,
      CollectionBasedStorageConfiguration config, int collection, int one, int many)
      throws Exception {
    try (var reader = Executors.newSingleThreadExecutor()) {
      reader.submit(() -> {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          assertEquals(collection, config.getCommittedCollectionFileState(999).bits());
          assertEquals(config.getCollectionFileState(999, op),
              config.getCommittedCollectionFileState(999));
          for (var name : new String[] {"onecopy", "manycopy"}) {
            assertEquals(name.equals("onecopy") ? one : many,
                config.getCommittedIndexEngineFileState(name).bits());
            assertEquals(config.getIndexEngineFileState(name, op),
                config.getCommittedIndexEngineFileState(name));
          }
        });
        return null;
      }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  private static void mutate(AtomicOperationsManager manager, ScalableRWLock lock,
      boolean rollback, com.jetbrains.youtrackdb.internal.common.function.TxConsumer action)
      throws Exception {
    lock.writeLock().lock();
    try {
      try {
        manager.executeInsideAtomicOperation(op -> {
          action.accept(op);
          if (rollback) {
            throw new com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException(
                "copy", "test rollback");
          }
        });
      } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException error) {
        if (!rollback || !(error
            .getCause() instanceof com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException)) {
          throw error;
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static Object field(AbstractStorage storage, String name) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(storage);
  }
}
