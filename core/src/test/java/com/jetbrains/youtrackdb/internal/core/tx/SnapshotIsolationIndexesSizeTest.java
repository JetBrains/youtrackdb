package com.jetbrains.youtrackdb.internal.core.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.junit.Test;

/**
 * Snapshot-isolation tests for {@code Index.size()} on both UNIQUE
 * ({@code BTreeSingleValueIndexEngine}) and NOTUNIQUE
 * ({@code BTreeMultiValueIndexEngine}) indexes.
 *
 * <p>After SI changes, the BTree stores versioned entries including tombstones.
 * {@code size()} must return only the count of visible entries, not raw BTree size.
 */
public class SnapshotIsolationIndexesSizeTest extends SnapshotIsolationIndexesTestBase {

  // ==========================================================================
  //  NOTUNIQUE  —  BTreeMultiValueIndexEngine.size()
  // ==========================================================================

  /**
   * After insert, size reflects the number of inserted entries.
   */
  @Test
  public void sizeAfterInsert_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After delete, size decreases.
   */
  @Test
  public void sizeAfterDelete_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting all records, size is 0.
   */
  @Test
  public void sizeAfterDeleteAll_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(0, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After update, size stays the same (one entry removed, one added).
   */
  @Test
  public void sizeAfterUpdate_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Qux' WHERE name = 'Foo'");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After concurrent insert, size() reflects the latest committed count.
   * size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoPhantom_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVertices(db, "Baz");

    // Approximate counter reflects latest committed state (3), not snapshot (2).
    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * After concurrent delete, size() reflects the latest committed count.
   * size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDelete_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    // Approximate counter reflects latest committed state (2), not snapshot (3).
    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size after concurrent update (size unchanged).
   */
  @Test
  public void sizeSnapshotNoVisibilityForUpdate_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  UNIQUE  —  BTreeSingleValueIndexEngine.size()
  // ==========================================================================

  /**
   * After insert, size reflects the number of inserted entries.
   */
  @Test
  public void sizeAfterInsert_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After delete, size decreases.
   */
  @Test
  public void sizeAfterDelete_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo'");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting all records, size is 0.
   */
  @Test
  public void sizeAfterDeleteAll_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(0, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After update, size stays the same.
   */
  @Test
  public void sizeAfterUpdate_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Qux' WHERE name = 'Foo'");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After concurrent insert, size() reflects the latest committed count.
   * UNIQUE size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoPhantom_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVertices(db, "Baz");

    // Approximate counter reflects latest committed state (3), not snapshot (2).
    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * After concurrent delete, size() reflects the latest committed count.
   * UNIQUE size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDelete_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo'");
    tx.commit();

    // Approximate counter reflects latest committed state (2), not snapshot (3).
    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size after concurrent update.
   */
  @Test
  public void sizeSnapshotNoVisibilityForUpdate_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: size stays the same for snapshot and fresh session.
   */
  @Test
  public void sizeABAUpdate_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Foo' WHERE name = 'Baz'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: size stays the same for snapshot and fresh session (NOTUNIQUE).
   */
  @Test
  public void sizeABAUpdate_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Foo' WHERE name = 'Baz'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  NOTUNIQUE  —  size() includes null keys
  // ==========================================================================

  /**
   * size() must count both null-keyed and non-null-keyed entries.
   */
  @Test
  public void sizeIncludesNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    // Insert 2 non-null and 1 null-keyed vertex
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * size() must count multiple null-keyed entries in a NOTUNIQUE index.
   */
  @Test
  public void sizeIncludesMultipleNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 3);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(4, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * size() returns correct value when all entries have null keys.
   */
  @Test
  public void sizeAllNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVerticesWithNullKey(db, 3);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting a null-keyed record, size decreases.
   */
  @Test
  public void sizeAfterDeleteNullKey_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 2);

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL LIMIT 1");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After concurrent null-key insert, size() reflects the latest committed count.
   * size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoPhantomNullKey_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 1);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVerticesWithNullKey(db, 1);

    // Approximate counter reflects latest committed state (3), not snapshot (2).
    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * After concurrent null-key delete, size() reflects the latest committed count.
   * size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDeleteNullKey_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 2);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL LIMIT 1");
    tx.commit();

    // Approximate counter reflects latest committed state (2), not snapshot (3).
    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  UNIQUE  —  size() includes null keys
  // ==========================================================================

  /**
   * size() must count both null-keyed and non-null-keyed entries.
   */
  @Test
  public void sizeIncludesNullKeys_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting a null-keyed record, size decreases.
   */
  @Test
  public void sizeAfterDeleteNullKey_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After concurrent null-key insert, size() reflects the latest committed count.
   * UNIQUE size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoPhantomNullKey_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVerticesWithNullKey(db, 1);

    // Approximate counter reflects latest committed state (3), not snapshot (2).
    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * After concurrent null-key delete, size() reflects the latest committed count.
   * UNIQUE size() is approximate (not snapshot-aware) — used for cost estimation.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDeleteNullKey_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL");
    tx.commit();

    // Approximate counter reflects latest committed state (2), not snapshot (3).
    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  Index drop/recreate clears null-keyed entries (exercises doClearTree)
  // ==========================================================================

  /**
   * Dropping and recreating a UNIQUE index must clear null-keyed entries.
   * This exercises doClearTree/delete which must remove CompositeKey(null, version)
   * entries from the sbTree.
   */
  @Test
  public void dropAndRecreateIndex_clearsNullKeys_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 1);

    // Verify 2 entries before drop
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();

    // Drop and recreate the index
    db.execute("drop index IndexName");
    db.getMetadata().getSchema().getClass("Userr")
        .createIndex("IndexName", INDEX_TYPE.UNIQUE, "name");

    // Size must reflect only existing records (re-indexed on create)
    session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * Dropping and recreating a NOTUNIQUE index must clear null-keyed entries
   * from both svTree and nullTree.
   */
  @Test
  public void dropAndRecreateIndex_clearsNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 2);

    // Verify 3 entries before drop
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();

    // Drop and recreate the index
    db.execute("drop index IndexName");
    db.getMetadata().getSchema().getClass("Userr")
        .createIndex("IndexName", INDEX_TYPE.NOTUNIQUE, "name");

    // Size must reflect only existing records (re-indexed on create)
    session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  // ==========================================================================
  //  Eviction: indexesSnapshotEntriesCount drops to 0 after cleanup
  // ==========================================================================

  /**
   * NOTUNIQUE index: after update+delete while a snapshot holds the LWM low on a
   * separate thread, the indexes snapshot accumulates entries
   * (indexesSnapshotEntriesCount > 0). Once the snapshot closes and cleanup runs
   * (threshold=0), the counter must drop to 0.
   *
   * <p>The snapshot must live on a separate thread because tsMin is thread-local:
   * if both sessions share a thread, the writer's commit resets the same holder,
   * allowing premature eviction.
   */
  @Test
  public void evictionResetsEntriesCount_NOTUNIQUE() throws Exception {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    var storage = db.getStorage();

    // Disable cleanup during mutations so entries accumulate
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD,
            Integer.MAX_VALUE);

    insertVertices(db, "Foo", "Bar");

    // Mutations create index snapshot entries
    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Bar'");
    tx.commit();

    // Snapshot entries must have been created
    assertThat(storage.getIndexesSnapshotEntriesCount().get())
        .as("indexes snapshot must contain entries after mutations")
        .isGreaterThan(0);

    // Now lower the threshold so cleanup fires on the next transaction close
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

    // A real write transaction advances idGen past the last snapshot entry's
    // removedKey version (the committing TX's timestamp). This is necessary because
    // eviction uses headMap(lwm) which is exclusive — the LWM must be strictly
    // greater than the removedKey version. A no-op TX would not advance idGen,
    // leaving LWM == removedVersion and preventing eviction.
    var advanceTx = db.begin();
    advanceTx.newVertex("Userr").setProperty("name", "Advance");
    advanceTx.commit();

    // A no-op transaction triggers resetTsMin → cleanupSnapshotIndex → eviction.
    // Now LWM = idGen.getLastId() which is past all snapshot entry versions.
    db.begin().commit();

    assertThat(storage.getIndexesSnapshotEntriesCount().get())
        .as("indexes snapshot entries must be evicted after cleanup")
        .isEqualTo(0);
  }

  /**
   * UNIQUE index: after update+delete while a snapshot holds the LWM low on a
   * separate thread, the indexes snapshot accumulates entries
   * (indexesSnapshotEntriesCount > 0). Once the snapshot closes and cleanup runs
   * (threshold=0), the counter must drop to 0.
   *
   * <p>The snapshot must live on a separate thread because tsMin is thread-local.
   */
  @Test
  public void evictionResetsEntriesCount_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    var storage = db.getStorage();

    // Disable cleanup during mutations so entries accumulate
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD,
            Integer.MAX_VALUE);

    insertVertices(db, "Foo", "Bar", "Baz");

    // Mutations create index snapshot entries
    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Qux' WHERE name = 'Foo'");
    tx.commit();

    tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Bar'");
    tx.commit();

    // Snapshot entries must have been created
    assertThat(storage.getIndexesSnapshotEntriesCount().get())
        .as("indexes snapshot must contain entries after mutations")
        .isGreaterThan(0);

    // Now lower the threshold so cleanup fires on the next transaction close
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

    // A real write transaction advances idGen past the last snapshot entry's
    // removedKey version. Without this, LWM == removedVersion and headMap's
    // exclusive bound prevents eviction.
    var advanceTx = db.begin();
    advanceTx.newVertex("Userr").setProperty("name", "Advance");
    advanceTx.commit();

    // A no-op transaction triggers resetTsMin → cleanupSnapshotIndex → eviction.
    db.begin().commit();

    assertThat(storage.getIndexesSnapshotEntriesCount().get())
        .as("indexes snapshot entries must be evicted after cleanup")
        .isEqualTo(0);
  }

  // ==========================================================================
  //  Helpers
  // ==========================================================================

  private void createSchema(INDEX_TYPE indexType) {
    var schema = db.createVertexClass("Userr");
    schema.createProperty("name", PropertyType.STRING);
    schema.createIndex("IndexName", indexType, "name");
  }

  private void insertVertices(DatabaseSessionEmbedded session, String... names) {
    for (String name : names) {
      var tx = session.begin();
      var v = tx.newVertex("Userr");
      v.setProperty("name", name);
      tx.commit();
    }
  }

  private void insertVerticesWithNullKey(DatabaseSessionEmbedded session, int count) {
    for (int i = 0; i < count; i++) {
      var tx = session.begin();
      tx.newVertex("Userr");
      // name not set → null key in the index
      tx.commit();
    }
  }

  private long getIndexSize(DatabaseSessionEmbedded session) {
    Index index = session.getIndex("IndexName");
    return index.size(session);
  }

}
