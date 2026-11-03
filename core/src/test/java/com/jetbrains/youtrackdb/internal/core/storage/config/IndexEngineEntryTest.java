package com.jetbrains.youtrackdb.internal.core.storage.config;

import static com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration.decodeIndexEngineEntry;
import static com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration.encodeIndexEngineEntry;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.Indexes;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
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
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public class IndexEngineEntryTest {

  private static IndexEngineData sample(boolean multi, String algorithm, int version) {
    return new IndexEngineData(7, 9, "probe", algorithm, null, true, version, 1, multi,
        (byte) 2, (byte) 3, true, new PropertyTypeInternal[] {PropertyTypeInternal.STRING},
        false, 1, null, null, Map.of());
  }

  // Hand-derived numeric header: implementation 4, API 1, serializers 2/3,
  // automatic=true, null support=false, multi flag, key size 1, index 7, file base 9.
  // Strings use UTF-16 BOMs. Index type is empty, encryption absent, one STRING key, no properties.
  private static byte[] fixture(boolean multi, boolean layout26) {
    var little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    var hex = (little ? "040000000100000002030100" : "000000040000000102030100")
        + (multi ? "01" : "00")
        + (little ? "010000000700000009000000010c000000"
            : "000000010000000700000009010000000c")
        + "feff00420054005200450045"
        + (little ? "01000000000001000000010e000000" : "01000000000000000001010000000e")
        + "feff0053005400520049004e004700000000";
    var bytes = HexFormat.of().parseHex(hex);
    if (layout26) {
      bytes = Arrays.copyOf(bytes, bytes.length + 1);
      bytes[bytes.length - 1] = (byte) (multi ? 31 : 7);
    }
    return bytes;
  }

  /** Independent bytes pin both legacy kinds and every subset of each new role set. */
  @Test
  public void fixedBytesAndEveryRoleSubset() {
    var roles = new IndexEngineFileState.Role[] {
        IndexEngineFileState.Role.MAIN_TREE, IndexEngineFileState.Role.MAIN_TREE_NULL_BUCKET,
        IndexEngineFileState.Role.STATISTICS, IndexEngineFileState.Role.NULL_KEY_TREE,
        IndexEngineFileState.Role.NULL_KEY_TREE_NULL_BUCKET};
    assertArrayEquals(roles, IndexEngineFileState.Role.values());
    int[] flags = {1, 2, 4, 8, 16};
    for (boolean multi : new boolean[] {false, true}) {
      var data = sample(multi, "BTREE", 4);
      assertArrayEquals(fixture(multi, false), encodeIndexEngineEntry(data,
          IndexEngineFileState.allCreated(multi), StorageFormat.LAYOUT_25, null));
      assertEquals(IndexEngineFileState.allCreated(multi),
          decodeIndexEngineEntry("probe", fixture(multi, false), -1, 2,
              StorageFormat.LAYOUT_25, null, null).state());
      for (int bits = 0; bits <= (multi ? 31 : 7); bits++) {
        var expected = fixture(multi, true);
        expected[expected.length - 1] = (byte) bits;
        var state = new IndexEngineFileState(multi, bits);
        assertArrayEquals(expected,
            encodeIndexEngineEntry(data, state, StorageFormat.LAYOUT_26, null));
        var entry = decodeIndexEngineEntry("probe", expected, -1, 3,
            StorageFormat.LAYOUT_26, null, null);
        assertEquals(state, entry.state());
        for (int i = 0; i < roles.length; i++) {
          assertEquals((bits & flags[i]) != 0, entry.state().isCreated(roles[i]));
        }
      }
      for (int i = 0; i < (multi ? 5 : 3); i++) {
        var bytes = fixture(multi, true);
        bytes[bytes.length - 1] = (byte) flags[i];
        var state = decodeIndexEngineEntry("probe", bytes, -1, 3,
            StorageFormat.LAYOUT_26, null, null).state();
        assertTrue(state.isCreated(roles[i]));
        assertEquals(flags[i], state.bits());
      }
    }
    assertThrows(ConfigurationException.class, () -> new IndexEngineFileState(false, 8));
    assertThrows(ConfigurationException.class, () -> new IndexEngineFileState(true, 32));
  }

  /** Explicit single-value version 3 and optional fields retain their payload meanings. */
  @Test
  public void supportedSelectorsOptionalFieldsAndLegacyLaxness() {
    var data = new IndexEngineData(Integer.MIN_VALUE, 9, "probe", "BTREE", "UNIQUE", true,
        3, 1, false, (byte) 2, (byte) 3, false,
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING}, true, 1, "aes", null,
        Map.of("key", "value"));
    var bytes = encodeIndexEngineEntry(data, new IndexEngineFileState(false, 0),
        StorageFormat.LAYOUT_26, null);
    var entry = decodeIndexEngineEntry("probe", bytes, 17, 3,
        StorageFormat.LAYOUT_26, null, "secret");
    assertEquals(3, entry.data().getVersion());
    assertEquals(17, entry.data().getIndexId());
    assertEquals(Map.of("key", "value"), entry.data().getEngineProperties());
    assertEquals("aes", entry.data().getEncryption());
    assertFalse(entry.data().isAutomatic());
    assertTrue(entry.data().isNullValuesSupport());
    assertThrows(ConfigurationException.class, () -> encodeIndexEngineEntry(data,
        IndexEngineFileState.allCreated(true), StorageFormat.LAYOUT_26, null));
    var legacy = Arrays.copyOf(fixture(false, false), 80);
    legacy[10] = 2;
    var lax = decodeIndexEngineEntry("probe", legacy, -1, 2,
        StorageFormat.LAYOUT_25, null, null);
    assertFalse(lax.data().isAutomatic());
    for (int tag : new int[] {-1, 2, 4, Integer.MAX_VALUE}) {
      assertThrows(ConfigurationException.class, () -> decodeIndexEngineEntry(
          "probe", bytes, -1, tag, StorageFormat.LAYOUT_26, null, null));
    }
  }

  private record DamagedCase(String label, byte[] bytes) {
  }

  private static List<DamagedCase> damaged(boolean multi) {
    var valid = fixture(multi, true);
    var result = new ArrayList<DamagedCase>();
    for (int i = 0; i < valid.length; i++) {
      result.add(new DamagedCase("truncated at byte " + i, Arrays.copyOf(valid, i)));
    }
    result.add(new DamagedCase("trailing byte", Arrays.copyOf(valid, valid.length + 1)));
    for (int offset : new int[] {10, 11, 12, 25, 42, 47, 52}) {
      var bytes = valid.clone();
      bytes[offset] = 2;
      result.add(new DamagedCase("invalid boolean or string marker at " + offset, bytes));
    }
    for (int flag : new int[] {8, 16, 32, 64, 128, 255}) {
      if (multi && flag < 32) {
        continue;
      }
      var bytes = valid.clone();
      bytes[bytes.length - 1] = (byte) flag;
      result.add(new DamagedCase("unknown or foreign role bits " + flag, bytes));
    }
    for (int version : new int[] {-1, 0, 1, 2, 3, 5, Integer.MAX_VALUE}) {
      if (!multi && version == 3) {
        continue;
      }
      var bytes = encodeIndexEngineEntry(sample(multi, "BTREE", version),
          IndexEngineFileState.allCreated(multi), StorageFormat.LAYOUT_25, null);
      result.add(new DamagedCase("unsupported implementation version " + version,
          Arrays.copyOf(bytes, valid.length)));
    }
    // Null key type names must also become contextual configuration errors.
    var nullType = new byte[valid.length - 18];
    System.arraycopy(valid, 0, nullType, 0, 52);
    System.arraycopy(valid, 71, nullType, 53, valid.length - 71);
    result.add(new DamagedCase("null key type name", nullType));
    var unknownAlgorithm = valid.clone();
    unknownAlgorithm[32] = 'X';
    result.add(new DamagedCase("unknown algorithm", unknownAlgorithm));
    var unknownType = valid.clone();
    unknownType[59] = 'X';
    result.add(new DamagedCase("unknown key type", unknownType));
    // String length and count fields include negative, huge and odd string lengths.
    for (int offset : new int[] {26, 43, 48, 53, 71}) {
      for (int value : new int[] {-1, Integer.MAX_VALUE, 1}) {
        if (offset == 48 && value == 1) {
          continue;
        }
        var bytes = valid.clone();
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).putInt(offset, value);
        result.add(new DamagedCase("length or count at " + offset + " = " + value, bytes));
      }
    }
    return result;
  }

  /**
   * Public and commit-window creation keep live and reloaded metadata equal to layout 25.
   * Layout 26 passes version 4 to the factory and stores tag 3 with every role created.
   * Persisted births also pin role-to-file agreement, both readers and every rejection.
   */
  @Test
  public void birthsAndPersistedDamage() throws Exception {
    var legacyVersions = new HashMap<String, Integer>();
    for (var format : StorageFormat.values()) {
      for (var type : DatabaseType.values()) {
        try (var manager = (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()))) {
          var name = "engines" + format.layoutVersion() + type;
          try (var scope = StorageBirthTestSupport.selectBirthFormat(manager, name, format)) {
            manager.create(name, type, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
          }
          try (var session = (DatabaseSessionEmbedded) manager.open(
              name, "admin", DbTestBase.ADMIN_PASSWORD)) {
            var cls = session.getMetadata().getSchema().createClass("EngineProbe");
            cls.createProperty("value", PropertyType.STRING);
            var factoryInputs = new HashMap<String, IndexEngineData>();
            // Observe the actual factory boundary. A version-4 engine alone would not prove
            // this, because DefaultIndexFactory also resolves negative input versions.
            try (var factories = mockStatic(Indexes.class, invocation -> {
              var result = invocation.callRealMethod();
              if (invocation.getMethod().getName().equals("createIndexEngine")) {
                IndexEngineData data = invocation.getArgument(1);
                factoryInputs.put(data.getName(), data);
                assertTrue(data.isMultivalue()
                    ? result instanceof BTreeMultiValueIndexEngine
                    : result instanceof BTreeSingleValueIndexEngine);
              }
              return result;
            })) {
              cls.createIndex("one", SchemaClass.INDEX_TYPE.UNIQUE, "value");
              cls.createIndex("many", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
              session.begin();
              var transactionalClass = session.getMetadata().getSchema().getClass("EngineProbe");
              transactionalClass.createIndex("txOne", SchemaClass.INDEX_TYPE.UNIQUE, "value");
              transactionalClass.createIndex("txMany", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");
              session.commit();
            }
            assertEquals(Set.of("one", "many", "txOne", "txMany"), factoryInputs.keySet());
            var indexes = session.getSharedContext().getIndexManager();
            for (var target : factoryInputs.keySet()) {
              assertEquals(format == StorageFormat.LAYOUT_26 ? 4 : -1,
                  factoryInputs.get(target).getVersion());
              var liveVersion = indexes.getIndex(session, target).getVersion();
              var key = type + "/" + target;
              if (format == StorageFormat.LAYOUT_25) {
                legacyVersions.put(key, liveVersion);
              }
              assertEquals(key + " live metadata in " + format,
                  (int) legacyVersions.get(key), liveVersion);
            }
            indexes.reload(session);
            for (var target : factoryInputs.keySet()) {
              var key = type + "/" + target;
              assertEquals(key + " reloaded metadata in " + format,
                  (int) legacyVersions.get(key), indexes.getIndex(session, target).getVersion());
            }
            var storage = session.getStorage();
            var config = (CollectionBasedStorageConfiguration) field(
                storage, "configuration", AbstractStorage.class);
            assertEquals(format, storage.getStorageFormat());
            if (storage instanceof DiskStorage disk) {
              assertEquals(format, new StorageBootstrapMetadata(disk.getStoragePath(),
                  new FeatureFormatIdentity(1)).readActiveRequired().storageFormat());
            }
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
              assertEquals(format.layoutVersion(), config.getVersion(op));
              for (var engineName : config.indexEngines(op)) {
                var raw = raw(config, engineName, op);
                assertEquals(format == StorageFormat.LAYOUT_25 ? 2 : 3, raw.second);
                var entry = decodeIndexEngineEntry(engineName, raw.first, -1, raw.second,
                    format, storage.getName(), null);
                assertEquals(IndexEngineFileState.allCreated(entry.data().isMultivalue()),
                    entry.state());
                var stem = AbstractStorage.indexEngineFileStem(entry.data().getFileBaseId());
                var expected = new TreeSet<>(Set.of(stem + ".cbt", stem + ".nbt", stem + ".ixs"));
                if (entry.data().isMultivalue()) {
                  expected.add(stem + "$null.cbt");
                  expected.add(stem + "$null.nbt");
                }
                var actual = new TreeSet<String>();
                for (var file : storage.getWriteCache().files().keySet()) {
                  if (file.startsWith(stem + ".") || file.startsWith(stem + "$")) {
                    actual.add(file);
                  }
                }
                assertEquals(expected, actual);
                assertEquals(expected.size(), Integer.bitCount(entry.state().bits()));
                assertEquals(format == StorageFormat.LAYOUT_26 ? 4 : -1,
                    entry.data().getVersion());
              }
              for (boolean multi : new boolean[] {false, true}) {
                var target = multi ? "many" : "one";
                var valid = fixture(multi, format == StorageFormat.LAYOUT_26);
                for (int tag : new int[] {-1, 0, 1, 2, 3, 4, 32767}) {
                  if (tag != (format == StorageFormat.LAYOUT_25 ? 2 : 3)) {
                    rejectPersisted(config, storage, target, valid, tag, op,
                        "foreign entry tag " + tag, format, type);
                  }
                }
                if (format == StorageFormat.LAYOUT_26) {
                  for (var damage : damaged(multi)) {
                    rejectPersisted(config, storage, target, damage.bytes(), 3, op,
                        damage.label(), format, type);
                  }
                }
              }
              // Criterion 8: the writer rejects unsupported kinds before storing any property.
              for (var bad : List.of(sample(false, "HASH", 4), sample(true, "BTREE", 3))) {
                if (format == StorageFormat.LAYOUT_26) {
                  assertThrows(ConfigurationException.class,
                      () -> config.addIndexEngine(op, "probe", bad));
                  assertFalse(config.indexEngines(op).contains("probe"));
                  assertNull(raw(config, "probe", op));
                }
              }
            });
          } finally {
            manager.drop(name);
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void rejectPersisted(CollectionBasedStorageConfiguration config,
      AbstractStorage storage, String name, byte[] bytes, int tag, AtomicOperation op,
      String label, StorageFormat format, DatabaseType type) throws Exception {
    var context = label + ", index=" + name + ", layout=" + format + ", database=" + type;
    var tree = (BTree<String>) field(config, "btree", config.getClass());
    var records = (PaginatedCollection) field(config, "collection", config.getClass());
    var key = "engine_" + name;
    var original = tree.get(key, op);
    var originalBytes = raw(config, name, op).first;
    try {
      records.updateRecord(original.getCollectionPosition(), bytes, (byte) 0, op);
      tree.put(op, key, new RecordId(tag, original.getCollectionPosition()));
      assertTrue(context + ", enumeration", config.indexEngines(op).contains(name));
      var directContext = context + ", reader=getIndexEngine";
      var direct = assertThrows(directContext, ConfigurationException.class,
          () -> config.getIndexEngine(name, -1, op));
      assertTrue(directContext, direct.getMessage().contains(name));
      assertTrue(directContext, direct.getMessage().contains(storage.getName()));
      var load = config.getClass().getDeclaredMethod("loadIndexEngines", AtomicOperation.class);
      load.setAccessible(true);
      var bulkContext = context + ", reader=loadIndexEngines";
      var bulk = assertThrows(bulkContext, InvocationTargetException.class,
          () -> load.invoke(config, op));
      assertTrue(bulkContext, bulk.getCause() instanceof ConfigurationException);
      assertTrue(bulkContext, bulk.getCause().getMessage().contains(name));
      assertTrue(bulkContext, bulk.getCause().getMessage().contains(storage.getName()));
    } finally {
      records.updateRecord(original.getCollectionPosition(), originalBytes, (byte) 0, op);
      tree.put(op, key, original);
    }
    assertArrayEquals(context + ", restored payload", originalBytes, raw(config, name, op).first);
    assertEquals(context + ", restored tag", original.getCollectionId(),
        raw(config, name, op).second);
  }

  private static Object field(Object target, String name, Class<?> owner) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  @SuppressWarnings("unchecked")
  private static RawPairObjectInteger<byte[]> raw(CollectionBasedStorageConfiguration config,
      String name, AtomicOperation op) throws Exception {
    var method = config.getClass().getDeclaredMethod("readProperty", String.class,
        AtomicOperation.class);
    method.setAccessible(true);
    return (RawPairObjectInteger<byte[]>) method.invoke(config, "engine_" + name, op);
  }
}
