package com.jetbrains.youtrackdb.internal.core.storage.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.FeatureFormatIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageBootstrapMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import org.junit.Test;

/**
 * Disk lifecycle checks for stored file state. The test-only creation seams change metadata,
 * not physical files. All real files remain present, so these tests prove persistence of the
 * stored state, not file absence. Memory storage has neither durable reopen nor backup.
 */
public class FileStateLifecycleTest {

  /** Mixed masks and previously committed marks survive cold reopen and both restore paths. */
  @Test
  public void mixedStatesSurviveReopenFullAndIncrementalRestore() throws Exception {
    lifecycle(StorageFormat.LAYOUT_26);
  }

  /** Independent version-3 collection and both engine fixtures keep exact layout-25 bytes. */
  @Test
  public void legacyBytesSurviveReopenFullAndIncrementalRestore() throws Exception {
    lifecycle(StorageFormat.LAYOUT_25);
  }

  private void lifecycle(StorageFormat format) throws Exception {
    // One cleanup scope includes source birth, both backups, both restores and cold reopens.
    var root = Files.createTempDirectory("file-state-lifecycle-");
    var base = root.resolve("databases").toString();
    try {
      var backup = Files.createDirectory(root.resolve("backup"));
      Snapshot before;
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        try (var scope = StorageBirthTestSupport.selectBirthFormat(manager, "source", format)) {
          manager.create("source", DatabaseType.DISK, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
        }
        try (var session = open(manager, "source")) {
          var cls = session.getMetadata().getSchema().createClass("X");
          cls.createProperty("value", PropertyType.STRING);
          cls.createIndex("one", SchemaClass.INDEX_TYPE.UNIQUE, "value");
          cls.createIndex("many", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
          int id = session.addCollection("x");
          install(session, id, format);
          before = snapshot(session, id, format, false);
          assertSnapshot(session, before, format, false);
        }
      }
      Snapshot after;
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        try (var session = open(manager, "source")) {
          assertSnapshot(session, before, format, false);
          session.fullBackup(backup);
        }
        // Restore the full unit before adding the increment to the same directory.
        manager.restore("full", backup.toString());
        try (var restored = open(manager, "full")) {
          assertSnapshot(restored, before, format, false);
        }
        try (var session = open(manager, "source")) {
          change(session, before.id(), format);
          after = snapshot(session, before.id(), format, true);
          for (int i = 0; i < 3; i++) {
            assertFalse("increment must change each tested entry",
                Arrays.equals(before.bytes()[i], after.bytes()[i]));
          }
          assertSnapshot(session, after, format, true);
          // change() has committed after fullBackup() returned. Both units stay in backup.
          session.backup(backup);
        }
        try (var units = Files.list(backup)) {
          assertEquals(2, units.filter(p -> p.toString().endsWith(".ibu")).count());
        }
        manager.restore("incremental", backup.toString());
        try (var restored = open(manager, "incremental")) {
          assertSnapshot(restored, after, format, true);
          for (int i = 0; i < 3; i++) {
            assertFalse("restore must not return the full-backup state",
                Arrays.equals(before.bytes()[i], read(restored, before.id())[i]));
          }
        }
      }
      try (var manager = (YouTrackDBImpl) YourTracks.instance(base)) {
        try (var session = open(manager, "source")) {
          assertSnapshot(session, after, format, true);
        }
        try (var session = open(manager, "full")) {
          assertSnapshot(session, before, format, false);
        }
        try (var session = open(manager, "incremental")) {
          assertSnapshot(session, after, format, true);
        }
      }
    } finally {
      // Closing every manager first releases database files even on assertion or restore failure.
      try (var paths = Files.walk(root)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static DatabaseSessionEmbedded open(YouTrackDBImpl manager, String name) {
    return (DatabaseSessionEmbedded) manager.open(name, "admin", DbTestBase.ADMIN_PASSWORD);
  }

  private record Snapshot(int id, byte[][] bytes) {
  }

  private static void install(DatabaseSessionEmbedded session, int id, StorageFormat format)
      throws Exception {
    var storage = session.getStorage();
    var config = config(storage);
    var lock = (ScalableRWLock) field(storage, AbstractStorage.class, "stateLock");
    lock.writeLock().lock();
    try {
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        if (format == StorageFormat.LAYOUT_26) {
          var collection = config.getCollections().get(id);
          config.dropCollection(op, id);
          config.createCollectionForTesting(op, collection, new CollectionFileState(9));
          for (var name : new String[] {"one", "many"}) {
            var data = config.getIndexEngine(name, -1, op);
            config.deleteIndexEngine(op, name);
            config.createIndexEngineForTesting(op, name, data,
                new IndexEngineFileState(data.isMultivalue(), name.equals("one") ? 1 : 18));
          }
        } else {
          replace(config, "collection_" + id, legacyCollection(false), op);
          for (var name : new String[] {"one", "many"}) {
            replace(config, "engine_" + name,
                legacyEngine(config.getIndexEngine(name, -1, op), false), op);
          }
        }
      });
      // A separate committed operation marks roles before any lifecycle step.
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        config.markCollectionFileCreated(id, CollectionFileState.Role.FREE_SPACE_MAP, op);
        config.markIndexEngineFileCreated("one", IndexEngineFileState.Role.STATISTICS, op);
        config.markIndexEngineFileCreated("many", IndexEngineFileState.Role.MAIN_TREE, op);
      });
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static void change(DatabaseSessionEmbedded session, int id, StorageFormat format)
      throws Exception {
    var storage = session.getStorage();
    var config = config(storage);
    var lock = (ScalableRWLock) field(storage, AbstractStorage.class, "stateLock");
    lock.writeLock().lock();
    try {
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        if (format == StorageFormat.LAYOUT_26) {
          config.markCollectionFileCreated(id, CollectionFileState.Role.LINK_BAG_TREE, op);
          config.markIndexEngineFileCreated("one",
              IndexEngineFileState.Role.MAIN_TREE_NULL_BUCKET, op);
          config.markIndexEngineFileCreated("many", IndexEngineFileState.Role.NULL_KEY_TREE, op);
        } else {
          replace(config, "collection_" + id, legacyCollection(true), op);
          for (var name : new String[] {"one", "many"}) {
            replace(config, "engine_" + name,
                legacyEngine(config.getIndexEngine(name, -1, op), true), op);
          }
        }
      });
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static Snapshot snapshot(DatabaseSessionEmbedded session, int id, StorageFormat format,
      boolean changed) throws Exception {
    if (format == StorageFormat.LAYOUT_25) {
      var config = config(session.getStorage());
      return session.getStorage().getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> new Snapshot(id, new byte[][] {legacyCollection(changed),
              legacyEngine(config.getIndexEngine("one", -1, op), changed),
              legacyEngine(config.getIndexEngine("many", -1, op), changed)}));
    }
    return new Snapshot(id, read(session, id));
  }

  private static void assertSnapshot(DatabaseSessionEmbedded session, Snapshot expected,
      StorageFormat format, boolean changed) throws Exception {
    var storage = (DiskStorage) session.getStorage();
    assertEquals(format, storage.getStorageFormat());
    assertEquals(format, new StorageBootstrapMetadata(storage.getStoragePath(),
        new FeatureFormatIdentity(1)).readActiveRequired().storageFormat());
    var config = config(storage);
    storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
      assertEquals(format.layoutVersion(), config.getVersion(op));
      var keys = new String[] {"collection_" + expected.id(), "engine_one", "engine_many"};
      int[] masks = format == StorageFormat.LAYOUT_25 ? new int[] {31, 7, 31}
          : changed ? new int[] {29, 7, 27} : new int[] {13, 5, 19};
      for (int i = 0; i < keys.length; i++) {
        var entry = raw(config, keys[i], op);
        assertEquals(keys[i], format == StorageFormat.LAYOUT_25 ? (i == 0 ? 0 : 2)
            : (i == 0 ? 1 : 3), entry.second);
        assertArrayEquals(keys[i], expected.bytes()[i], entry.first);
        if (format == StorageFormat.LAYOUT_26) {
          assertEquals(keys[i], masks[i], Byte.toUnsignedInt(entry.first[entry.first.length - 1]));
        }
      }
      var collection = config.getCollectionFileState(expected.id(), op);
      assertEquals(masks[0], collection.bits());
      int flag = 1;
      for (var role : CollectionFileState.Role.values()) {
        assertEquals(role.name(), (masks[0] & flag) != 0, collection.isCreated(role));
        flag <<= 1;
      }
      for (int i = 1; i < 3; i++) {
        var state = config.getIndexEngineFileState(i == 1 ? "one" : "many", op);
        assertEquals(i == 2, state.multivalue());
        assertEquals(masks[i], state.bits());
        flag = 1;
        for (var role : IndexEngineFileState.Role.values()) {
          assertEquals(role.name(), (masks[i] & flag) != 0, state.isCreated(role));
          flag <<= 1;
        }
      }
    });
  }

  private static byte[][] read(DatabaseSessionEmbedded session, int id) throws Exception {
    var config = config(session.getStorage());
    return session.getStorage().getAtomicOperationsManager().calculateInsideAtomicOperation(
        op -> new byte[][] {raw(config, "collection_" + id, op).first,
            raw(config, "engine_one", op).first, raw(config, "engine_many", op).first});
  }

  // Independent version-3 collection fixture: name x, WAL enabled, no encryption or conflict
  // strategy. The increment selects the no-op compression implementation "nothing".
  private static byte[] legacyCollection(boolean changed) {
    return hex((little() ? "0104000000" : "0100000004") + "feff007801"
        + (little() ? "03000000" : "00000003") + "0000"
        + (changed ? (little() ? "0110000000" : "0100000010")
            + "feff006e006f007400680069006e0067" : "00"));
  }

  // Fixed legacy header and UTF-16 strings. Only the two allocated identity integers vary.
  // No encoder or serializer from production builds the expected bytes.
  private static byte[] legacyEngine(IndexEngineData data, boolean changed) {
    boolean multi = data.isMultivalue();
    var bytes = hex((little() ? "ffffffff01000000" : "ffffffff00000001")
        + "10190101" + (multi ? "01" : "00")
        + (little() ? "010000000000000000000000010c000000"
            : "000000010000000000000000010000000c")
        + "feff00420054005200450045"
        + (multi ? (little() ? "0114000000" : "0100000014")
            + "feff004e004f00540055004e0049005100550045"
            : (little() ? "010e000000" : "010000000e") + "feff0055004e0049005100550045")
        + "00" + (little() ? "01000000010e000000" : "00000001010000000e")
        + "feff0053005400520049004e0047"
        + (changed ? (little() ? "010000000104000000feff006b0104000000feff0076"
            : "000000010100000004feff006b0100000004feff0076") : "00000000"));
    ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        .putInt(17, data.getIndexId()).putInt(21, data.getFileBaseId());
    return bytes;
  }

  private static boolean little() {
    return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
  }

  private static byte[] hex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static CollectionBasedStorageConfiguration config(AbstractStorage storage)
      throws Exception {
    return (CollectionBasedStorageConfiguration) field(storage, AbstractStorage.class,
        "configuration");
  }

  private static Object field(Object target, Class<?> owner, String name) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  @SuppressWarnings("unchecked")
  private static void replace(CollectionBasedStorageConfiguration config, String key,
      byte[] bytes, AtomicOperation op) throws Exception {
    var tree = (BTree<String>) field(config, config.getClass(), "btree");
    var records = (PaginatedCollection) field(config, config.getClass(), "collection");
    records.updateRecord(tree.get(key, op).getCollectionPosition(), bytes, (byte) 0, op);
  }

  @SuppressWarnings("unchecked")
  private static RawPairObjectInteger<byte[]> raw(CollectionBasedStorageConfiguration config,
      String key, AtomicOperation op) throws Exception {
    var method = config.getClass().getDeclaredMethod("readProperty", String.class,
        AtomicOperation.class);
    method.setAccessible(true);
    return (RawPairObjectInteger<byte[]>) method.invoke(config, key, op);
  }
}
