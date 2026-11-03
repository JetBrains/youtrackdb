package com.jetbrains.youtrackdb.internal.core.storage.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.Test;

/** Mixed masks here describe metadata only. Every physical file remains eagerly created. */
public class OperationFileStateTest {

  /** Own inserts and marks bypass caches, accumulate bits, and survive real attribute rewrites. */
  @Test
  public void mixedMetadataMasksSurviveMarkRenameAttributeAndReopen() throws Exception {
    var base = DbTestBase.getBaseDirectoryPathStr(getClass());
    int id;
    try {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        try (var scope = StorageBirthTestSupport.selectBirthFormat(manager, "mixed",
            StorageFormat.LAYOUT_26)) {
          manager.create("mixed", DatabaseType.DISK, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
        }
        try (var session = (DatabaseSessionEmbedded) manager.open("mixed", "admin",
            DbTestBase.ADMIN_PASSWORD)) {
          var storage = session.getStorage();
          var config = config(storage);
          id = storage.addCollection(session, "maskprobe");
          var metadata = config.getCollections().get(id);
          var lock = stateLock(storage);
          lock.writeLock().lock();
          try {
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
              assertEquals(26, config.getVersion(op));
              config.dropCollection(op, id);
              config.createCollectionForTesting(op, metadata, new CollectionFileState(5));
              assertEquals(5, config.getCollectionFileState(id, op).bits());
              assertThrows(ConfigurationException.class,
                  () -> config.createCollectionForTesting(op, metadata,
                      new CollectionFileState(0)));
              config.markCollectionFileCreated(id, CollectionFileState.Role.POSITION_MAP, op);
              config.markCollectionFileCreated(id, CollectionFileState.Role.POSITION_MAP, op);
              config.updateCollection(op, metadata);
              assertCollection(config, id, 7, op);
              assertThrows(ConfigurationException.class,
                  () -> config.markCollectionFileCreated(id, null, op));
              assertThrows(ConfigurationException.class,
                  () -> config.markCollectionFileCreated(99999, CollectionFileState.Role.DATA, op));
              assertThrows(ConfigurationException.class,
                  () -> config.updateCollection(op, new StoragePaginatedCollectionConfiguration(
                      99999, "missing", null, true, 0, 0, null, null, null, null, 3)));
            });
          } finally {
            lock.writeLock().unlock();
          }
          storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "renamedprobe");
          storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY,
              "version");
          storage.getAtomicOperationsManager()
              .executeInsideAtomicOperation(op -> assertCollection(config, id, 7, op));
          // Rewrite again with a still-absent role, then with a mark committed in an earlier operation.
          lock.writeLock().lock();
          try {
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> config
                .markCollectionFileCreated(id, CollectionFileState.Role.LINK_BAG_TREE, op));
          } finally {
            lock.writeLock().unlock();
          }
          storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.NAME, "finalprobe");
          storage.setCollectionAttribute(id, StorageCollection.ATTRIBUTES.CONFLICTSTRATEGY,
              "version");
        }
      }
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        try (var session = (DatabaseSessionEmbedded) manager.open("mixed", "admin",
            DbTestBase.ADMIN_PASSWORD)) {
          var storage = session.getStorage();
          var config = config(storage);
          assertEquals(StorageFormat.LAYOUT_26, storage.getStorageFormat());
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            assertEquals(26, config.getVersion(op));
            assertCollection(config, id, 23, op);
            assertEquals("finalprobe", config.getCollections().get(id).name());
          });
        }
      }
    } finally {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        if (manager.exists("mixed")) {
          manager.drop("mixed");
        }
      }
    }
  }

  /** Failed schema drops restore exact mixed bytes. Successful drops remove both kinds of state. */
  @Test
  public void mixedMetadataMasksSurviveFailedDropAndRecreationStartsAllCreated() throws Exception {
    try (var manager = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      try (var scope = StorageBirthTestSupport.selectBirthFormat(manager, "drops",
          StorageFormat.LAYOUT_26)) {
        manager.create("drops", DatabaseType.MEMORY, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open("drops", "admin",
          DbTestBase.ADMIN_PASSWORD)) {
        var cls = session.getMetadata().getSchema().createClass("DropProbe");
        cls.createProperty("value", PropertyType.STRING);
        cls.createIndex("one", SchemaClass.INDEX_TYPE.UNIQUE, "value");
        cls.createIndex("many", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
        var id = cls.getCollectionIds()[0];
        var storage = session.getStorage();
        var config = config(storage);
        var lock = stateLock(storage);
        lock.writeLock().lock();
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            var metadata = config.getCollections().get(id);
            config.dropCollection(op, id);
            config.createCollectionForTesting(op, metadata, new CollectionFileState(9));
            for (var name : new String[] {"one", "many"}) {
              var data = config.getIndexEngine(name, -1, op);
              config.deleteIndexEngine(op, name);
              var state = new IndexEngineFileState(data.isMultivalue(), 1);
              config.createIndexEngineForTesting(op, name, data, state);
              assertEquals(state, config.getIndexEngineFileState(name, op));
              assertThrows(ConfigurationException.class,
                  () -> config.createIndexEngineForTesting(op, name, data, state));
              config.markIndexEngineFileCreated(name, IndexEngineFileState.Role.STATISTICS, op);
              config.markIndexEngineFileCreated(name, IndexEngineFileState.Role.STATISTICS, op);
              assertEngine(config, name, 5, op);
            }
            config.markIndexEngineFileCreated("many", IndexEngineFileState.Role.NULL_KEY_TREE, op);
            assertEngine(config, "many", 13, op);
            assertThrows(ConfigurationException.class, () -> config.markIndexEngineFileCreated(
                "one", IndexEngineFileState.Role.NULL_KEY_TREE, op));
            assertThrows(ConfigurationException.class,
                () -> config.markIndexEngineFileCreated("one", null, op));
            assertThrows(ConfigurationException.class, () -> config.markIndexEngineFileCreated(
                "missing", IndexEngineFileState.Role.MAIN_TREE, op));
          });
        } finally {
          lock.writeLock().unlock();
        }
        var before = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            op -> new byte[][] {raw(config, "collection_" + id, op).first,
                raw(config, "engine_one", op).first, raw(config, "engine_many", op).first});
        storage.setPostEngineBuildTestHook(() -> {
          var op = session.getActiveTransaction().getAtomicOperation();
          assertThrows(ConfigurationException.class, () -> config.getCollectionFileState(id, op));
          assertThrows(ConfigurationException.class,
              () -> config.getIndexEngineFileState("one", op));
          assertThrows(ConfigurationException.class,
              () -> config.getIndexEngineFileState("many", op));
          throw new CommandInterruptedException(storage.getName(), "injected drop fault");
        });
        try {
          session.begin();
          session.getMetadata().getSchema().dropClass("DropProbe");
          assertThrows(RuntimeException.class, session::commit);
        } finally {
          storage.setPostEngineBuildTestHook(null);
        }
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          assertCollection(config, id, 9, op);
          assertEngine(config, "one", 5, op);
          assertEngine(config, "many", 13, op);
          assertArrayEquals(before[0], raw(config, "collection_" + id, op).first);
          assertArrayEquals(before[1], raw(config, "engine_one", op).first);
          assertArrayEquals(before[2], raw(config, "engine_many", op).first);
        });
        session.executeInTx(tx -> session.getMetadata().getSchema().dropClass("DropProbe"));
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          assertThrows(ConfigurationException.class, () -> config.getCollectionFileState(id, op));
          assertThrows(ConfigurationException.class,
              () -> config.getIndexEngineFileState("one", op));
        });
        var replacement = session.getMetadata().getSchema().createClass("DropProbe");
        replacement.createProperty("value", PropertyType.STRING);
        replacement.createIndex("one", SchemaClass.INDEX_TYPE.UNIQUE, "value");
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          assertCollection(config, replacement.getCollectionIds()[0], 31, op);
          assertEngine(config, "one", 7, op);
        });
      } finally {
        manager.drop("drops");
      }
    }
  }

  /** Layout-25 marks are byte-for-byte no-ops and rewrites match an independent fixed encoding. */
  @Test
  public void legacyMarkAndRewriteKeepFixedBytesAndRejectMixedFixtures() throws Exception {
    try (var manager = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      manager.create("legacy", DatabaseType.MEMORY, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
      try (var session = (DatabaseSessionEmbedded) manager.open("legacy", "admin",
          DbTestBase.ADMIN_PASSWORD)) {
        var cls = session.getMetadata().getSchema().createClass("LegacyProbe");
        cls.createProperty("value", PropertyType.STRING);
        cls.createIndex("one", SchemaClass.INDEX_TYPE.UNIQUE, "value");
        var storage = session.getStorage();
        var config = config(storage);
        var lock = stateLock(storage);
        lock.writeLock().lock();
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            assertEquals(25, config.getVersion(op));
            var fixture = new StoragePaginatedCollectionConfiguration(
                999, "x", null, true, 0, 0, null, null, null, null, 2);
            var expected =
                HexFormat.of().parseHex(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                    ? "0104000000feff00780102000000000000" : "0100000004feff00780100000002000000");
            config.createCollection(op, fixture);
            config.markCollectionFileCreated(999, CollectionFileState.Role.DATA, op);
            config.updateCollection(op, fixture);
            assertArrayEquals(expected, raw(config, "collection_999", op).first);
            assertEquals(0, raw(config, "collection_999", op).second);
            assertEquals(CollectionFileState.ALL_CREATED, config.getCollectionFileState(999, op));
            var engine = raw(config, "engine_one", op);
            config.markIndexEngineFileCreated("one", IndexEngineFileState.Role.STATISTICS, op);
            assertArrayEquals(engine.first, raw(config, "engine_one", op).first);
            assertEquals(2, raw(config, "engine_one", op).second);
            assertEquals(IndexEngineFileState.allCreated(false),
                config.getIndexEngineFileState("one", op));
            assertThrows(ConfigurationException.class, () -> config.createCollectionForTesting(
                op, fixture, new CollectionFileState(0)));
            assertThrows(ConfigurationException.class, () -> config.createIndexEngineForTesting(
                op, "missing", config.getIndexEngine("one", -1, op),
                new IndexEngineFileState(false, 0)));
            config.dropCollection(op, 999);
          });
        } finally {
          lock.writeLock().unlock();
        }
      } finally {
        manager.drop("legacy");
      }
    }
  }

  /** Marking preserves sentinel ids and every non-state byte, including through a disk reopen. */
  @Test
  public void markingChangesOnlyStateByteAndSentinelEngineOpensWithSubstitutedId()
      throws Exception {
    var base = DbTestBase.getBaseDirectoryPathStr(getClass());
    var expectedId = new int[1];
    try {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        try (var scope = StorageBirthTestSupport.selectBirthFormat(manager, "sentinel",
            StorageFormat.LAYOUT_26)) {
          manager.create("sentinel", DatabaseType.DISK, "admin", DbTestBase.ADMIN_PASSWORD,
              "admin");
        }
        try (var session = (DatabaseSessionEmbedded) manager.open("sentinel", "admin",
            DbTestBase.ADMIN_PASSWORD)) {
          var cls = session.getMetadata().getSchema().createClass("SentinelProbe");
          cls.createProperty("value", PropertyType.STRING);
          cls.createIndex("sentinelIndex", SchemaClass.INDEX_TYPE.UNIQUE, "value");
          var storage = session.getStorage();
          var config = config(storage);
          var id = cls.getCollectionIds()[0];
          var lock = stateLock(storage);
          lock.writeLock().lock();
          try {
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
              var collection = raw(config, "collection_" + id, op).first.clone();
              collection[collection.length - 1] = 0;
              replacePayload(config, "collection_" + id, collection, op);
              config.markCollectionFileCreated(id, CollectionFileState.Role.DATA, op);
              collection[collection.length - 1] = 1;
              assertArrayEquals(collection, raw(config, "collection_" + id, op).first);

              var engine = raw(config, "engine_sentinelIndex", op).first.clone();
              // The stored index id starts after the numeric selectors and key size.
              ByteBuffer.wrap(engine).order(ByteOrder.nativeOrder()).putInt(17, Integer.MIN_VALUE);
              engine[engine.length - 1] = 0;
              replacePayload(config, "engine_sentinelIndex", engine, op);
              config.markIndexEngineFileCreated("sentinelIndex",
                  IndexEngineFileState.Role.STATISTICS, op);
              engine[engine.length - 1] = 4;
              assertArrayEquals(engine, raw(config, "engine_sentinelIndex", op).first);
              for (var name : config.indexEngines(op)) {
                if (!name.equals("sentinelIndex")) {
                  expectedId[0] = Math.max(expectedId[0],
                      config.getIndexEngine(name, -1, op).getIndexId() + 1);
                }
              }
              for (var name : config.indexEngines(op)) {
                if (name.equals("sentinelIndex")) {
                  break;
                }
                expectedId[0]++;
              }
            });
          } finally {
            lock.writeLock().unlock();
          }
        }
      }
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base);
          var session = (DatabaseSessionEmbedded) manager.open("sentinel", "admin",
              DbTestBase.ADMIN_PASSWORD)) {
        var storage = session.getStorage();
        @SuppressWarnings("unchecked")
        var engines = (java.util.Map<String,
            com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine>) field(storage,
                "indexEngineNameMap");
        assertEquals(expectedId[0], engines.get("sentinelIndex").getId());
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          assertEquals(4, config(storage).getIndexEngineFileState("sentinelIndex", op).bits());
          assertEquals(expectedId[0],
              config(storage).getIndexEngine("sentinelIndex", expectedId[0], op).getIndexId());
        });
      }
    } finally {
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        if (manager.exists("sentinel")) {
          manager.drop("sentinel");
        }
      }
    }
  }

  /**
   * Failed layout-26 births leave no entry. Observe all config-record writes after the fault:
   * cleanup may delete records but must never insert or update one. Disk reopen checks durability.
   */
  @Test
  public void failedCreatesUndoWithoutWritingConfigurationEntries() throws Exception {
    var base = DbTestBase.getBaseDirectoryPathStr(getClass());
    for (var type : DatabaseType.values()) {
      for (boolean engine : new boolean[] {false, true}) {
        var name = "failedCreate" + type + engine;
        var captured = new int[] {-1, -1};
        try {
          try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
            try (var scope = StorageBirthTestSupport.selectBirthFormat(manager, name,
                StorageFormat.LAYOUT_26)) {
              manager.create(name, type, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
            }
            try (var session = (DatabaseSessionEmbedded) manager.open(name, "admin",
                DbTestBase.ADMIN_PASSWORD)) {
              if (engine) {
                var cls = session.getMetadata().getSchema().createClass("FailedProbe");
                cls.createProperty("value", PropertyType.STRING);
              }
              var storage = session.getStorage();
              var config = config(storage);
              var existingIds = config.getCollections().stream().filter(c -> c != null)
                  .map(c -> c.id()).collect(java.util.stream.Collectors.toSet());
              var recordsField = config.getClass().getDeclaredField("collection");
              recordsField.setAccessible(true);
              var original = (PaginatedCollection) recordsField.get(config);
              var observed = spy(original);
              recordsField.set(config, observed);
              storage.setEndTxCommitFailureTestHook(() -> {
                var op = session.getActiveTransaction().getAtomicOperation();
                assertEquals(26, config.getVersion(op));
                if (engine) {
                  var data = config.getIndexEngine("failedIndex", -1, op);
                  captured[0] = data.getIndexId();
                  captured[1] = data.getFileBaseId();
                  assertEquals(7, config.getIndexEngineFileState("failedIndex", op).bits());
                } else {
                  captured[0] = config.getCollections().stream()
                      .filter(c -> c != null && !existingIds.contains(c.id()))
                      .findFirst().orElseThrow().id();
                  try {
                    assertCollection(config, captured[0], 31, op);
                  } catch (Exception e) {
                    throw new AssertionError(e);
                  }
                }
                clearInvocations(observed);
                throw new CommandInterruptedException(name, "injected failed create");
              });
              try {
                session.begin();
                if (engine) {
                  session.getMetadata().getSchema().getClass("FailedProbe")
                      .createIndex("failedIndex", SchemaClass.INDEX_TYPE.UNIQUE, "value");
                } else {
                  session.getMetadata().getSchema().createClass("FailedProbe");
                }
                assertThrows(RuntimeException.class, session::commit);
              } finally {
                storage.setEndTxCommitFailureTestHook(null);
                recordsField.set(config, original);
              }
              assertTrue("identifier captured before the fault", captured[0] >= 0);
              if (engine) {
                assertTrue("file base captured before the fault", captured[1] >= 0);
              }
              assertTrue("undo must not write a config record",
                  mockingDetails(observed).getInvocations().stream().noneMatch(
                      invocation -> invocation.getMethod().getName().equals("createRecord")
                          || invocation.getMethod().getName().equals("updateRecord")));
              storage.getAtomicOperationsManager().executeInsideAtomicOperation(
                  op -> assertNull(raw(config, engine ? "engine_failedIndex"
                      : "collection_" + captured[0], op)));
            }
          }
          if (type == DatabaseType.DISK) {
            try (var manager = (YouTrackDBImpl) YourTracks.instance(base);
                var session = (DatabaseSessionEmbedded) manager.open(name, "admin",
                    DbTestBase.ADMIN_PASSWORD)) {
              var storage = session.getStorage();
              storage.getAtomicOperationsManager().executeInsideAtomicOperation(
                  op -> assertNull(raw(config(storage), engine ? "engine_failedIndex"
                      : "collection_" + captured[0], op)));
            }
          }
        } finally {
          try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
            if (manager.exists(name)) {
              manager.drop(name);
            }
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void replacePayload(CollectionBasedStorageConfiguration config, String key,
      byte[] bytes, AtomicOperation op) throws Exception {
    var treeField = config.getClass().getDeclaredField("btree");
    treeField.setAccessible(true);
    var tree = (BTree<String>) treeField.get(config);
    var recordsField = config.getClass().getDeclaredField("collection");
    recordsField.setAccessible(true);
    var records = (PaginatedCollection) recordsField.get(config);
    records.updateRecord(tree.get(key, op).getCollectionPosition(), bytes, (byte) 0, op);
  }

  private static void assertCollection(CollectionBasedStorageConfiguration config, int id,
      int bits, AtomicOperation op) throws Exception {
    assertEquals(bits, config.getCollectionFileState(id, op).bits());
    var raw = raw(config, "collection_" + id, op);
    assertEquals(1, raw.second);
    assertEquals(bits, Byte.toUnsignedInt(raw.first[raw.first.length - 1]));
  }

  private static void assertEngine(CollectionBasedStorageConfiguration config, String name,
      int bits, AtomicOperation op) throws Exception {
    assertEquals(bits, config.getIndexEngineFileState(name, op).bits());
    var raw = raw(config, "engine_" + name, op);
    assertEquals(3, raw.second);
    assertEquals(bits, Byte.toUnsignedInt(raw.first[raw.first.length - 1]));
  }

  private static CollectionBasedStorageConfiguration config(AbstractStorage storage)
      throws Exception {
    return (CollectionBasedStorageConfiguration) field(storage, "configuration");
  }

  private static ScalableRWLock stateLock(AbstractStorage storage) throws Exception {
    return (ScalableRWLock) field(storage, "stateLock");
  }

  private static Object field(AbstractStorage storage, String name) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(storage);
  }

  @SuppressWarnings("unchecked")
  private static RawPairObjectInteger<byte[]> raw(CollectionBasedStorageConfiguration config,
      String key, AtomicOperation op) throws Exception {
    var method =
        config.getClass().getDeclaredMethod("readProperty", String.class, AtomicOperation.class);
    method.setAccessible(true);
    return (RawPairObjectInteger<byte[]>) method.invoke(config, key, op);
  }
}
