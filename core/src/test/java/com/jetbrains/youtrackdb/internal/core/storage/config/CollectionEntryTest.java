package com.jetbrains.youtrackdb.internal.core.storage.config;

import static com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration.decodeCollectionEntry;
import static com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration.encodeCollectionEntry;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrackdb.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
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
import java.util.HexFormat;
import java.util.List;
import org.junit.Test;

public class CollectionEntryTest {

  // Hand-derived: name "x" uses UTF-16 BOM and x, then useWal, binary version 2,
  // and three absent strings. Integer fields use the platform's native order.
  private static byte[] legacyBytes() {
    return HexFormat.of().parseHex(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        ? "0104000000feff00780102000000000000"
        : "0100000004feff00780100000002000000");
  }

  private static StoragePaginatedCollectionConfiguration sample() {
    return sample(3);
  }

  private static StoragePaginatedCollectionConfiguration sample(int binaryVersion) {
    return new StoragePaginatedCollectionConfiguration(
        7, "x", null, true, 0, 0, null, null, null, null, binaryVersion);
  }

  // The supported binary selector is 3 and the final byte carries the role flags.
  private static byte[] layout26Bytes() {
    return HexFormat.of().parseHex(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        ? "0104000000feff007801030000000000001f"
        : "0100000004feff007801000000030000001f");
  }

  /** Layout 25 keeps independently specified bytes and its implicit all-created state. */
  @Test
  public void legacyBytesStayExactAndLegacyDecodingStaysLax() {
    assertArrayEquals(legacyBytes(),
        encodeCollectionEntry(sample(2), CollectionFileState.ALL_CREATED, StorageFormat.LAYOUT_25));
    var bytes = Arrays.copyOf(legacyBytes(), legacyBytes().length + 1);
    bytes[9] = 2;
    var entry = decodeCollectionEntry(7, bytes, 0, StorageFormat.LAYOUT_25, null);
    assertFalse(((StoragePaginatedCollectionConfiguration) entry.configuration()).useWal);
    assertEquals(CollectionFileState.ALL_CREATED, entry.state());
    assertEquals(2, entry.configuration().getBinaryVersion());
    assertEquals(99, decodeCollectionEntry(7,
        encodeCollectionEntry(sample(99), CollectionFileState.ALL_CREATED, StorageFormat.LAYOUT_25),
        0, StorageFormat.LAYOUT_25, null).configuration().getBinaryVersion());
  }

  /** Fixed role fixtures pin both the declared order and each persisted role bit. */
  @Test
  public void namedRolesHaveFixedBytes() {
    assertEquals(0x1F, CollectionFileState.ALL_ROLES_MASK);
    assertEquals(0x1F, CollectionFileState.ALL_CREATED.bits());
    var roles = new CollectionFileState.Role[] {
        CollectionFileState.Role.DATA, CollectionFileState.Role.POSITION_MAP,
        CollectionFileState.Role.FREE_SPACE_MAP, CollectionFileState.Role.DIRTY_PAGE_BITSET,
        CollectionFileState.Role.LINK_BAG_TREE};
    assertArrayEquals(roles, CollectionFileState.Role.values());
    int[] flags = {0x01, 0x02, 0x04, 0x08, 0x10};
    for (int i = 0; i < roles.length; i++) {
      var fixture = layout26Bytes();
      fixture[fixture.length - 1] = (byte) flags[i];
      var entry = decodeCollectionEntry(7, fixture, 1, StorageFormat.LAYOUT_26, null);
      for (var role : roles) {
        assertEquals(role == roles[i], entry.state().isCreated(role));
      }
      assertArrayEquals(fixture,
          encodeCollectionEntry(sample(), entry.state(), StorageFormat.LAYOUT_26));
    }
    var mixed = layout26Bytes();
    mixed[mixed.length - 1] = 0x05;
    var state = decodeCollectionEntry(7, mixed, 1, StorageFormat.LAYOUT_26, null).state();
    assertTrue(state.isCreated(CollectionFileState.Role.DATA));
    assertTrue(state.isCreated(CollectionFileState.Role.FREE_SPACE_MAP));
    assertFalse(state.isCreated(CollectionFileState.Role.POSITION_MAP));
    assertFalse(state.isCreated(CollectionFileState.Role.DIRTY_PAGE_BITSET));
    assertFalse(state.isCreated(CollectionFileState.Role.LINK_BAG_TREE));
    assertArrayEquals(mixed, encodeCollectionEntry(sample(), state, StorageFormat.LAYOUT_26));
  }

  /** Every subset has its assigned packed byte, including independent empty and mixed fixtures. */
  @Test
  public void all32StatesRoundTripWithFixedRoleAssignments() {
    var roles = CollectionFileState.Role.values();
    for (int bits = 0; bits < 32; bits++) {
      var state = new CollectionFileState(bits);
      var bytes = encodeCollectionEntry(sample(), state, StorageFormat.LAYOUT_26);
      var expected = layout26Bytes();
      expected[expected.length - 1] = (byte) bits;
      assertArrayEquals(expected, bytes);
      var decoded = decodeCollectionEntry(7, expected, 1, StorageFormat.LAYOUT_26, "key");
      assertEquals(state, decoded.state());
      assertEquals("x", decoded.configuration().name());
      assertEquals(3, decoded.configuration().getBinaryVersion());
      for (int bit = 0; bit < roles.length; bit++) {
        assertEquals((bits & (1 << bit)) != 0, state.isCreated(roles[bit]));
      }
    }
    assertThrows(ConfigurationException.class, () -> new CollectionFileState(-1));
    assertThrows(ConfigurationException.class, () -> new CollectionFileState(32));
  }

  /** Wrong tags fail in both layouts before payload parsing. Unknown tags fail too. */
  @Test
  public void layoutsAcceptOnlyTheirOwnTag() {
    for (var format : StorageFormat.values()) {
      var bytes = format == StorageFormat.LAYOUT_25 ? legacyBytes() : layout26Bytes();
      for (int tag : new int[] {-1, 0, 1, 2}) {
        if (tag != (format == StorageFormat.LAYOUT_25 ? 0 : 1)) {
          var failure = assertThrows(ConfigurationException.class,
              () -> decodeCollectionEntry(7, bytes, tag, format, null));
          assertTrue(failure.getMessage().contains("collection entry 7"));
        }
      }
    }
  }

  /** Every truncation, surplus byte, invalid boolean and out-of-range role bit fails explicitly. */
  @Test
  public void strictPayloadRejectsDamage() {
    for (var bytes : damagedPayloads()) {
      var failure = assertThrows(ConfigurationException.class,
          () -> decodeCollectionEntry(7, bytes, 1, StorageFormat.LAYOUT_26, null));
      assertTrue(failure.getMessage().contains("collection entry 7"));
    }
  }

  private static List<byte[]> damagedPayloads() {
    var valid = layout26Bytes();
    var damaged = new ArrayList<byte[]>();
    for (int length = 0; length < valid.length; length++) {
      damaged.add(Arrays.copyOf(valid, length));
    }
    damaged.add(Arrays.copyOf(valid, valid.length + 1));
    for (int offset : new int[] {0, 9, 14, 15, 16}) {
      var bytes = valid.clone();
      bytes[offset] = 2;
      damaged.add(bytes);
    }
    for (int flag : new int[] {32, 64, 128, 255}) {
      var bytes = valid.clone();
      bytes[bytes.length - 1] = (byte) flag;
      damaged.add(bytes);
    }
    for (int version : new int[] {-1, 0, 1, 2, 4, 99, Integer.MAX_VALUE}) {
      var bytes = valid.clone();
      ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).putInt(10, version);
      damaged.add(bytes);
    }
    damaged.add(oddStringLengthPayload());
    var negativeLength = valid.clone();
    Arrays.fill(negativeLength, 1, 5, (byte) 255);
    damaged.add(negativeLength);
    var hugeLength = valid.clone();
    Arrays.fill(hugeLength, 1, 5, (byte) 127);
    damaged.add(hugeLength);
    return damaged;
  }

  private static byte[] oddStringLengthPayload() {
    var valid = layout26Bytes();
    // Remove one UTF-16 byte and shorten the name so all subsequent fields remain aligned.
    var odd = new byte[valid.length - 1];
    System.arraycopy(valid, 0, odd, 0, 8);
    System.arraycopy(valid, 9, odd, 8, valid.length - 9);
    ByteBuffer.wrap(odd).order(ByteOrder.nativeOrder()).putInt(1, 3);
    return odd;
  }

  /** Persisted malformed entries fail preload and retain their original bytes after each check. */
  @Test
  public void preloadRejectsEveryDamagedPayload() throws Exception {
    try (var manager = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      var name = "damagedentries";
      try (var scope = StorageBirthTestSupport.selectBirthFormat(
          manager, name, StorageFormat.LAYOUT_26)) {
        manager.create(name, DatabaseType.MEMORY, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
      }
      try (var session = (DatabaseSessionEmbedded) manager.open(
          name, "admin", DbTestBase.ADMIN_PASSWORD)) {
        var storage = session.getStorage();
        var config = (CollectionBasedStorageConfiguration) field(
            storage, "configuration", AbstractStorage.class);
        var first =
            config.getCollections().stream().filter(c -> c != null).findFirst().orElseThrow();
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          for (var bytes : damagedPayloads()) {
            rejectPersisted(config, storage, first.id(), bytes, 1, op);
          }
        });
      } finally {
        manager.drop(name);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void rejectPersisted(CollectionBasedStorageConfiguration config,
      AbstractStorage storage, int id, byte[] bytes, int tag, AtomicOperation op) throws Exception {
    var tree = (BTree<String>) field(config, "btree", config.getClass());
    var records = (PaginatedCollection) field(config, "collection", config.getClass());
    var key = "collection_" + id;
    var original = tree.get(key, op);
    var originalBytes = raw(config, id, op).first;
    try {
      records.updateRecord(original.getCollectionPosition(), bytes, (byte) 0, op);
      tree.put(op, key, new RecordId(tag, original.getCollectionPosition()));
      var direct = assertThrows(ConfigurationException.class,
          () -> config.getCollectionFileState(id, op));
      var preload =
          config.getClass().getDeclaredMethod("preloadCollections", AtomicOperation.class);
      preload.setAccessible(true);
      var failure = assertThrows(InvocationTargetException.class, () -> preload.invoke(config, op));
      assertTrue(failure.getCause() instanceof ConfigurationException);
      assertTrue(failure.getCause().getMessage().contains("collection entry " + id));
      assertTrue(failure.getCause().getMessage().contains(storage.getName()));
      if (Arrays.equals(bytes, oddStringLengthPayload())) {
        assertTrue(direct.getMessage().contains("Invalid UTF-16 string length"));
        assertTrue(failure.getCause().getMessage().contains("Invalid UTF-16 string length"));
        assertThrows(ConfigurationException.class,
            () -> config.markCollectionFileCreated(id, CollectionFileState.Role.DATA, op));
        assertArrayEquals(bytes, raw(config, id, op).first);
      }
    } finally {
      records.updateRecord(original.getCollectionPosition(), originalBytes, (byte) 0, op);
      tree.put(op, key, original);
    }
    assertArrayEquals(originalBytes, raw(config, id, op).first);
    assertEquals(original.getCollectionId(), raw(config, id, op).second);
  }

  /** Nullable and present strings and both useWal values keep their legacy payload meanings. */
  @Test
  public void strictPayloadSupportsPresentStringsAndFalseBoolean() {
    var config = sample();
    config.useWal = false;
    config.encryption = "aes";
    config.conflictStrategy = "version";
    config.compression = "none";
    var entry = decodeCollectionEntry(7,
        encodeCollectionEntry(config, new CollectionFileState(0), StorageFormat.LAYOUT_26),
        1, StorageFormat.LAYOUT_26, "secret");
    var decoded = (StoragePaginatedCollectionConfiguration) entry.configuration();
    assertFalse(decoded.useWal);
    assertEquals("aes", decoded.encryption);
    assertEquals("version", decoded.conflictStrategy);
    assertEquals("none", decoded.compression);
    assertEquals("secret", decoded.encryptionKey);
  }

  /** Birth entries and later rewrites persist the selected tag and all five role flags. */
  @Test
  public void birthsAndRewritesPersistLayoutAndCollectionEntries() throws Exception {
    for (var format : StorageFormat.values()) {
      for (var type : DatabaseType.values()) {
        try (var manager = (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()))) {
          var name = "entries" + format.layoutVersion() + type;
          try (var scope = StorageBirthTestSupport.selectBirthFormat(manager, name, format)) {
            manager.create(name, type, "admin", DbTestBase.ADMIN_PASSWORD, "admin");
          }
          try (var session = (DatabaseSessionEmbedded) manager.open(
              name, "admin", DbTestBase.ADMIN_PASSWORD)) {
            var storage = session.getStorage();
            var config = (CollectionBasedStorageConfiguration) field(storage, "configuration",
                AbstractStorage.class);
            var ops = storage.getAtomicOperationsManager();
            assertEquals(format, storage.getStorageFormat());
            if (storage instanceof DiskStorage disk) {
              assertEquals(format, new StorageBootstrapMetadata(disk.getStoragePath(),
                  new FeatureFormatIdentity(1)).readActiveRequired().storageFormat());
            }
            assertEquals(format.layoutVersion(), ops.calculateInsideAtomicOperation(
                config::getVersion).intValue());
            assertTrue(config.getCollections().stream().filter(c -> c != null).count() > 0);
            var stateLock =
                (com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock) field(storage,
                    "stateLock", AbstractStorage.class);
            stateLock.writeLock().lock();
            try {
              for (var collection : config.getCollections()) {
                if (collection == null) {
                  continue;
                }
                ops.executeInsideAtomicOperation(op -> {
                  var raw = raw(config, collection.id(), op);
                  assertEquals(format == StorageFormat.LAYOUT_25 ? 0 : 1, raw.second);
                  var entry =
                      decodeCollectionEntry(collection.id(), raw.first, raw.second, format, null);
                  assertEquals(CollectionFileState.ALL_CREATED, entry.state());
                  if (format == StorageFormat.LAYOUT_26) {
                    assertEquals(31, raw.first[raw.first.length - 1]);
                  }
                  config.updateCollection(op, collection);
                  var rewritten = raw(config, collection.id(), op);
                  assertArrayEquals(raw.first, rewritten.first);
                  assertEquals(raw.second, rewritten.second);
                });
              }
            } finally {
              stateLock.writeLock().unlock();
            }
            // Name enumeration does not accept or decode the stored entry.
            var first =
                config.getCollections().stream().filter(c -> c != null).findFirst().orElseThrow();
            ops.executeInsideAtomicOperation(op -> {
              assertTrue(storage.getCollectionNames().contains(first.name()));
              var raw = raw(config, first.id(), op);
              for (int tag : new int[] {-1, 2, format == StorageFormat.LAYOUT_25 ? 1 : 0}) {
                assertThrows(ConfigurationException.class,
                    () -> decodeCollectionEntry(first.id(), raw.first, tag, format, null));
                rejectPersisted(config, storage, first.id(), raw.first, tag, op);
              }
            });
          } finally {
            manager.drop(name);
          }
        }
      }
    }
  }

  private static Object field(Object target, String name, Class<?> owner) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  @SuppressWarnings("unchecked")
  private static RawPairObjectInteger<byte[]> raw(CollectionBasedStorageConfiguration config,
      int id, AtomicOperation op) throws Exception {
    var method = config.getClass().getDeclaredMethod("readProperty", String.class,
        AtomicOperation.class);
    method.setAccessible(true);
    return (RawPairObjectInteger<byte[]>) method.invoke(config, "collection_" + id, op);
  }
}
