package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link BTree#getVisible} — the direct leaf-page lookup with inline
 * visibility checking for snapshot isolation.
 *
 * <p>Uses DISK storage so both optimistic and pinned read paths can be exercised.
 * Entries are versioned CompositeKeys (userKey, version) — the same format used
 * by {@code BTreeSingleValueIndexEngine} under SI.
 *
 * <p>The key types are [STRING, LONG] where STRING is the user key and LONG is
 * the version, matching the production setup for a single-field string index.
 */
public class BTreeGetVisibleTest {

  private static final long INDEX_ID = 1L;
  private static final RID RID_1 = new RecordId(10, 1);
  private static final RID RID_2 = new RecordId(10, 2);
  private static final TombstoneRID TOMBSTONE_1 = new TombstoneRID(RID_1);

  // Key types: [STRING, LONG] — user key + version, matching
  // BTreeSingleValueIndexEngine.calculateTypes() output for a STRING property.
  private static final PropertyTypeInternal[] KEY_TYPES = {
      PropertyTypeInternal.STRING, PropertyTypeInternal.LONG
  };

  private AtomicOperationsManager atomicOperationsManager;
  private BTree<CompositeKey> tree;
  private YouTrackDBImpl youTrackDB;
  private String dbName;
  private String buildDirectory;

  @Before
  public void before() throws Exception {
    buildDirectory =
        System.getProperty("buildDirectory", ".")
            + File.separator
            + BTreeGetVisibleTest.class.getSimpleName();

    dbName = "btreeGetVisibleTest";
    var dbDirectory = new File(buildDirectory, dbName);
    FileUtils.deleteRecursively(dbDirectory);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

    AbstractStorage storage;
    try (var session = youTrackDB.open(dbName, "admin", "admin")) {
      storage = session.getStorage();
    }
    tree = new BTree<>("getVisibleTree", ".sbt", ".nbt", storage);
    atomicOperationsManager = storage.getAtomicOperationsManager();
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.create(
            atomicOperation, new IndexMultiValuKeySerializer(), KEY_TYPES, 2));
  }

  @After
  public void after() {
    youTrackDB.drop(dbName);
    youTrackDB.close();
    FileUtils.deleteRecursively(new File(buildDirectory));
  }

  private static IndexesSnapshot newSnapshot() {
    return new IndexesSnapshot(
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR),
        new AtomicLong(), INDEX_ID);
  }

  /**
   * Warms the read cache by reading the entry through the pinned path,
   * so the next read can exercise the optimistic path.
   */
  private void warmCache(CompositeKey key) throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.get(key, atomicOperation));
  }

  // ---- Committed entry visible → returns RID ----

  @Test
  public void getVisible_committedEntry_returnsRid() throws Exception {
    // Insert a committed entry: CompositeKey("Foo", 0L) → RID_1
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Foo", 0L), RID_1);
    });

    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey("Foo");

    // Read in a new atomic operation — visibleVersion will be > 0
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      assertThat(result)
          .as("Committed entry should be visible")
          .isEqualTo(RID_1);
    });
  }

  // ---- Tombstone entry → returns null ----

  @Test
  public void getVisible_tombstoneEntry_returnsNull() throws Exception {
    // Insert a tombstone entry: the key was deleted
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Foo", 0L), TOMBSTONE_1);
    });

    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey("Foo");

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      assertThat(result)
          .as("Tombstone entry should not be visible")
          .isNull();
    });
  }

  // ---- Key not found → returns null ----

  @Test
  public void getVisible_keyNotFound_returnsNull() throws Exception {
    // Insert an entry with a different key
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Bar", 0L), RID_1);
    });

    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey("Foo");

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      assertThat(result)
          .as("Non-existent key should return null")
          .isNull();
    });
  }

  // ---- Multiple versions → returns first visible in scan order ----

  @Test
  public void getVisible_multipleVersions_returnsFirstVisibleInScanOrder() throws Exception {
    // Insert two versions of the same key: v0 with RID_1, v1 with RID_2.
    // Both are committed before the read, so the scan finds v0 first
    // (lower version sorts first) and returns RID_1 since it's visible.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Foo", 0L), RID_1);
      tree.put(atomicOperation, new CompositeKey("Foo", 1L), RID_2);
    });

    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey("Foo");

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      // Both versions are visible; the scan finds v0 first (v0 < v1).
      assertThat(result)
          .as("Should return v0 (first visible in scan order), not v1")
          .isEqualTo(RID_1);
    });
  }

  // ---- Null key lookup ----

  @Test
  public void getVisible_nullKey_returnsVisibleEntry() throws Exception {
    // Null keys are stored as CompositeKey(null, version) in the main B-tree.
    // getVisible() handles them uniformly via the same prefix-matching code path.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey((Object) null, 0L), RID_1);
    });

    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey((Object) null);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      assertThat(result)
          .as("Null key entry should be visible via getVisible()")
          .isEqualTo(RID_1);
    });
  }

  // ---- Empty tree ----

  @Test
  public void getVisible_emptyTree_returnsNull() throws Exception {
    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey("Foo");

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      assertThat(result)
          .as("Empty tree should return null")
          .isNull();
    });
  }

  // ---- Optimistic path (after cache warm) ----

  @Test
  public void getVisible_afterCacheWarm_returnsCorrectResult() throws Exception {
    // After warming the read cache, getVisible() should still return the correct
    // result. This exercises the code path after cache pages are loaded (likely the
    // optimistic path, though we cannot directly observe which path was taken).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Foo", 0L), RID_1);
    });

    warmCache(new CompositeKey("Foo", 0L));

    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey("Foo");

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      assertThat(result)
          .as("Result after cache warm should match committed entry")
          .isEqualTo(RID_1);
    });
  }

  // ---- Tombstone followed by live entry for same key ----

  @Test
  public void getVisible_tombstoneThenLiveEntry_returnsLiveRid() throws Exception {
    // Version 0 is a tombstone, version 1 is a live entry. checkVisibility()
    // returns null for the tombstone (committed TombstoneRID), so the forward
    // scan continues to v1 and returns RID_2.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Foo", 0L), TOMBSTONE_1);
      tree.put(atomicOperation, new CompositeKey("Foo", 1L), RID_2);
    });

    var snapshot = newSnapshot();
    var prefixKey = new CompositeKey("Foo");

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(prefixKey, snapshot, atomicOperation);
      assertThat(result)
          .as("Should skip tombstone and return live entry")
          .isEqualTo(RID_2);
    });
  }

  // ---- Adjacent key prefix — scan must not bleed into next key ----

  @Test
  public void getVisible_adjacentKeyPrefix_doesNotBleedIntoNextKey() throws Exception {
    // "Foo" has only a tombstone; "Fop" (sort-adjacent) has a live entry.
    // getVisible("Foo") must NOT return Fop's RID — the prefix scan must stop
    // at the user-key boundary.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Foo", 0L), TOMBSTONE_1);
      tree.put(atomicOperation, new CompositeKey("Fop", 0L), RID_2);
    });

    var snapshot = newSnapshot();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(new CompositeKey("Foo"), snapshot, atomicOperation);
      assertThat(result)
          .as("Must not return adjacent key's RID")
          .isNull();
    });
  }

  // ---- Empty string key ----

  @Test
  public void getVisible_emptyStringKey_returnsVisibleEntry() throws Exception {
    // Empty string is a valid user key. Serialization and prefix matching must
    // handle it correctly.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("", 0L), RID_1);
    });

    var snapshot = newSnapshot();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(new CompositeKey(""), snapshot, atomicOperation);
      assertThat(result)
          .as("Empty string key should be found")
          .isEqualTo(RID_1);
    });
  }

  // ---- Multiple distinct keys — correct isolation ----

  @Test
  public void getVisible_multipleDistinctKeys_returnsCorrectEntry() throws Exception {
    // Tree contains entries for several distinct user keys. Looking up "Beta" must
    // return only Beta's entry, exercising B-tree traversal finding the correct
    // starting position among many entries.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, new CompositeKey("Alpha", 0L), RID_1);
      tree.put(atomicOperation, new CompositeKey("Beta", 0L), RID_2);
      tree.put(atomicOperation, new CompositeKey("Gamma", 0L),
          new RecordId(10, 3));
    });

    var snapshot = newSnapshot();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.getVisible(new CompositeKey("Beta"), snapshot, atomicOperation);
      assertThat(result)
          .as("Should return exactly Beta's entry")
          .isEqualTo(RID_2);
    });
  }
}
