package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot-isolation tests for null keys in NOTUNIQUE indexes (BTreeMultiValueIndexEngine).
 *
 * <p>Null keys are stored in a separate {@code nullTree} inside BTreeMultiValueIndexEngine.
 * These tests verify that the null key path correctly implements snapshot isolation:
 * a concurrent insert/delete of a null-keyed record should be invisible to a snapshot
 * transaction that started before the change.
 *
 * <p>Note: as of this writing, the null key path in BTreeMultiValueIndexEngine does NOT
 * implement snapshot isolation (no versioning, no TombstoneRID/SnapshotMarkerRID, no
 * visibility filter). These tests document the expected behavior and are expected to fail
 * until the null key path is fixed.
 */
public class SnapshotIsolationIndexesNullKeyTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /**
   * A phantom insert of a null-keyed record should be invisible to a snapshot.
   * <pre>
   *   Initial: one record with name=null
   *   Snapshot TX: sees 1 null-keyed record
   *   Concurrent TX: inserts another record with name=null
   *   Snapshot TX: should still see only 1 null-keyed record
   *   Fresh TX: sees 2
   * </pre>
   */
  @Test
  public void noVisibilityForNullKeyPhantomInsert() {
    SchemaClass cls = db.createVertexClass("NullTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("NullTest_name", INDEX_TYPE.NOTUNIQUE, "name");

    // Insert a record with null key
    db.begin();
    var e1 = (EntityImpl) db.newVertex("NullTest");
    // name is not set → null key in the index
    db.commit();

    // Start snapshot TX and read null-keyed entries
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(
        "Snapshot should see 1 null-keyed record",
        1, fetchNullKeyCount(session1, "NullTest_name"));

    // Concurrent TX: insert another null-keyed record
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    var e2 = (EntityImpl) session2.newVertex("NullTest");
    // name not set → null key
    session2.commit();

    // Snapshot should NOT see the phantom insert
    assertEquals(
        "Snapshot must not see phantom null-keyed record",
        1, fetchNullKeyCount(session1, "NullTest_name"));

    session1.commit();

    // Fresh TX should see both
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(
        "Fresh TX should see 2 null-keyed records",
        2, fetchNullKeyCount(session3, "NullTest_name"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  /**
   * A concurrent delete of a null-keyed record should be invisible to a snapshot.
   * <pre>
   *   Initial: two records with name=null
   *   Snapshot TX: sees 2 null-keyed records
   *   Concurrent TX: deletes one record
   *   Snapshot TX: should still see 2
   *   Fresh TX: sees 1
   * </pre>
   */
  @Test
  public void noVisibilityForNullKeyDelete() {
    SchemaClass cls = db.createVertexClass("NullDelTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("NullDelTest_name", INDEX_TYPE.NOTUNIQUE, "name");

    // Insert two records with null key
    db.begin();
    var e1 = (EntityImpl) db.newVertex("NullDelTest");
    var e2 = (EntityImpl) db.newVertex("NullDelTest");
    db.commit();

    // Start snapshot TX
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(
        "Snapshot should see 2 null-keyed records",
        2, fetchNullKeyCount(session1, "NullDelTest_name"));

    // Concurrent TX: delete one record
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    var loaded = (EntityImpl) session2.getActiveTransaction().load(e1);
    loaded.delete();
    session2.commit();

    // Snapshot must still see 2
    assertEquals(
        "Snapshot must not see concurrent delete of null-keyed record",
        2, fetchNullKeyCount(session1, "NullDelTest_name"));

    session1.commit();

    // Fresh TX should see 1
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(
        "Fresh TX should see 1 null-keyed record after delete",
        1, fetchNullKeyCount(session3, "NullDelTest_name"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  /**
   * Update from null key to non-null should be invisible to snapshot.
   * <pre>
   *   Initial: one record with name=null
   *   Snapshot TX: sees 1 null-keyed record
   *   Concurrent TX: updates name from null to "Foo"
   *   Snapshot TX: should still see 1 null-keyed record
   *   Fresh TX: sees 0 null-keyed, 1 "Foo"-keyed
   * </pre>
   */
  @Test
  public void noVisibilityForNullToNonNullUpdate() {
    SchemaClass cls = db.createVertexClass("NullUpdTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("NullUpdTest_name", INDEX_TYPE.NOTUNIQUE, "name");

    // Insert a record with null key
    db.begin();
    var e1 = (EntityImpl) db.newVertex("NullUpdTest");
    db.commit();

    // Start snapshot TX
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(
        "Snapshot should see 1 null-keyed record",
        1, fetchNullKeyCount(session1, "NullUpdTest_name"));

    // Concurrent TX: update null → "Foo"
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    var loaded = (EntityImpl) session2.getActiveTransaction().load(e1);
    loaded.setProperty("name", "Foo");
    session2.commit();

    // Snapshot should still see null-keyed record
    assertEquals(
        "Snapshot must not see concurrent null→Foo update",
        1, fetchNullKeyCount(session1, "NullUpdTest_name"));

    session1.commit();

    // Fresh TX: null-keyed gone, Foo-keyed present
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(
        "Fresh TX should see 0 null-keyed records",
        0, fetchNullKeyCount(session3, "NullUpdTest_name"));
    assertEquals(
        "Fresh TX should see 1 Foo-keyed record",
        1, fetchKeyCount(session3, "NullUpdTest_name", "Foo"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  // ========================================================================
  //  UNIQUE index (BTreeSingleValueIndexEngine) — null key
  // ========================================================================

  /**
   * A concurrent insert of a record with null key into a UNIQUE index should
   * be invisible to a snapshot. Only one null-keyed record can exist in a
   * UNIQUE index at a time; the test verifies snapshot isolation when that
   * single null slot changes ownership.
   *
   * <pre>
   *   Initial: empty
   *   Snapshot TX: sees 0 null-keyed records
   *   Concurrent TX: inserts record with name=null
   *   Snapshot TX: should still see 0
   *   Fresh TX: sees 1
   * </pre>
   */
  @Test
  public void unique_noVisibilityForNullKeyPhantomInsert() {
    SchemaClass cls = db.createVertexClass("UniqueNullIns");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("UniqueNullIns_name", INDEX_TYPE.UNIQUE, "name");

    // Start snapshot TX before any null-keyed record exists
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(
        "Snapshot should see 0 null-keyed records",
        0, fetchNullKeyCount(session1, "UniqueNullIns_name"));

    // Concurrent TX: insert a record with null key
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    session2.newVertex("UniqueNullIns");
    // name not set → null key
    session2.commit();

    // Snapshot should NOT see the phantom insert
    assertEquals(
        "Snapshot must not see phantom null-keyed record in UNIQUE index",
        0, fetchNullKeyCount(session1, "UniqueNullIns_name"));
    session1.commit();

    // Fresh TX should see 1
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(
        "Fresh TX should see 1 null-keyed record",
        1, fetchNullKeyCount(session3, "UniqueNullIns_name"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  /**
   * A concurrent delete of a null-keyed record in a UNIQUE index should be
   * invisible to a snapshot.
   *
   * <pre>
   *   Initial: one record with name=null
   *   Snapshot TX: sees 1 null-keyed record
   *   Concurrent TX: deletes the record
   *   Snapshot TX: should still see 1
   *   Fresh TX: sees 0
   * </pre>
   */
  @Test
  public void unique_noVisibilityForNullKeyDelete() {
    SchemaClass cls = db.createVertexClass("UniqueNullDel");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("UniqueNullDel_name", INDEX_TYPE.UNIQUE, "name");

    // Insert a record with null key
    db.begin();
    var e1 = (EntityImpl) db.newVertex("UniqueNullDel");
    db.commit();

    // Start snapshot TX
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(
        "Snapshot should see 1 null-keyed record",
        1, fetchNullKeyCount(session1, "UniqueNullDel_name"));

    // Concurrent TX: delete the record
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    var loaded = (EntityImpl) session2.getActiveTransaction().load(e1);
    loaded.delete();
    session2.commit();

    // Snapshot must still see 1
    assertEquals(
        "Snapshot must not see concurrent delete of null-keyed record in UNIQUE index",
        1, fetchNullKeyCount(session1, "UniqueNullDel_name"));
    session1.commit();

    // Fresh TX should see 0
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(
        "Fresh TX should see 0 null-keyed records after delete",
        0, fetchNullKeyCount(session3, "UniqueNullDel_name"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  /**
   * Update from null to non-null in a UNIQUE index should be invisible to
   * snapshot.
   *
   * <pre>
   *   Initial: one record with name=null
   *   Snapshot TX: sees 1 null-keyed record
   *   Concurrent TX: updates name from null to "Foo"
   *   Snapshot TX: should still see 1 null-keyed, 0 "Foo"-keyed
   *   Fresh TX: sees 0 null-keyed, 1 "Foo"-keyed
   * </pre>
   */
  @Test
  public void unique_noVisibilityForNullToNonNullUpdate() {
    SchemaClass cls = db.createVertexClass("UniqueNullUpd");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("UniqueNullUpd_name", INDEX_TYPE.UNIQUE, "name");

    // Insert a record with null key
    db.begin();
    var e1 = (EntityImpl) db.newVertex("UniqueNullUpd");
    db.commit();

    // Start snapshot TX
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(
        "Snapshot should see 1 null-keyed record",
        1, fetchNullKeyCount(session1, "UniqueNullUpd_name"));

    // Concurrent TX: update null → "Foo"
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    var loaded = (EntityImpl) session2.getActiveTransaction().load(e1);
    loaded.setProperty("name", "Foo");
    session2.commit();

    // Snapshot should still see null-keyed record
    assertEquals(
        "Snapshot must not see concurrent null→Foo update in UNIQUE index",
        1, fetchNullKeyCount(session1, "UniqueNullUpd_name"));
    session1.commit();

    // Fresh TX: null-keyed gone, Foo-keyed present
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(
        "Fresh TX should see 0 null-keyed records",
        0, fetchNullKeyCount(session3, "UniqueNullUpd_name"));
    assertEquals(
        "Fresh TX should see 1 Foo-keyed record",
        1, fetchKeyCount(session3, "UniqueNullUpd_name", "Foo"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  /**
   * Update from non-null to null in a UNIQUE index should be invisible to
   * snapshot.
   *
   * <pre>
   *   Initial: one record with name="Foo"
   *   Snapshot TX: sees 0 null-keyed, 1 "Foo"-keyed
   *   Concurrent TX: updates name from "Foo" to null
   *   Snapshot TX: should still see 0 null-keyed, 1 "Foo"-keyed
   *   Fresh TX: sees 1 null-keyed, 0 "Foo"-keyed
   * </pre>
   */
  @Test
  public void unique_noVisibilityForNonNullToNullUpdate() {
    SchemaClass cls = db.createVertexClass("UniqueNullUpd2");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("UniqueNullUpd2_name", INDEX_TYPE.UNIQUE, "name");

    // Insert a record with name="Foo"
    db.begin();
    var e1 = (EntityImpl) db.newVertex("UniqueNullUpd2");
    e1.setProperty("name", "Foo");
    db.commit();

    // Start snapshot TX
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(0, fetchNullKeyCount(session1, "UniqueNullUpd2_name"));
    assertEquals(1, fetchKeyCount(session1, "UniqueNullUpd2_name", "Foo"));

    // Concurrent TX: update "Foo" → null
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    var loaded = (EntityImpl) session2.getActiveTransaction().load(e1);
    loaded.removeProperty("name");
    session2.commit();

    // Snapshot should still see old state
    assertEquals(
        "Snapshot must not see concurrent Foo→null update in UNIQUE index",
        0, fetchNullKeyCount(session1, "UniqueNullUpd2_name"));
    assertEquals(
        "Snapshot must still see Foo",
        1, fetchKeyCount(session1, "UniqueNullUpd2_name", "Foo"));
    session1.commit();

    // Fresh TX: null-keyed present, Foo-keyed gone
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(1, fetchNullKeyCount(session3, "UniqueNullUpd2_name"));
    assertEquals(0, fetchKeyCount(session3, "UniqueNullUpd2_name", "Foo"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  /**
   * Delete and re-insert with null key in a UNIQUE index: the snapshot
   * should see the original record, fresh TX should see the new one.
   *
   * <pre>
   *   Initial: one record with name=null
   *   Snapshot TX: sees 1 null-keyed record
   *   Concurrent TX: deletes the record, inserts a new one with name=null
   *   Snapshot TX: should still see 1 (the original)
   *   Fresh TX: sees 1 (the new record)
   * </pre>
   */
  @Test
  public void unique_noVisibilityForNullKeyDeleteAndReinsert() {
    SchemaClass cls = db.createVertexClass("UniqueNullReins");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("UniqueNullReins_name", INDEX_TYPE.UNIQUE, "name");

    // Insert a record with null key
    db.begin();
    var e1 = (EntityImpl) db.newVertex("UniqueNullReins");
    db.commit();

    // Start snapshot TX
    var session1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session1.begin();
    assertEquals(1, fetchNullKeyCount(session1, "UniqueNullReins_name"));

    // Concurrent TX: delete and re-insert
    var session2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session2.begin();
    var loaded = (EntityImpl) session2.getActiveTransaction().load(e1);
    loaded.delete();
    session2.newVertex("UniqueNullReins");
    // name not set → null key
    session2.commit();

    // Snapshot should still see 1
    assertEquals(
        "Snapshot must not see delete+reinsert of null key in UNIQUE index",
        1, fetchNullKeyCount(session1, "UniqueNullReins_name"));
    session1.commit();

    // Fresh TX should see 1 (the new record)
    var session3 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session3.begin();
    assertEquals(1, fetchNullKeyCount(session3, "UniqueNullReins_name"));
    session3.commit();

    session3.close();
    session2.close();
    session1.close();
  }

  // ========================================================================
  //  Null keys must be excluded from stream()/descStream()/keyStream()
  // ========================================================================

  /**
   * For a NOTUNIQUE index containing both null-keyed and non-null-keyed records,
   * stream() must return only non-null keys. Null-keyed entries should only be
   * accessible via getRids(session, null).
   */
  @Test
  public void notUnique_streamExcludesNullKeys() {
    SchemaClass cls = db.createVertexClass("StreamNullNU");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("StreamNullNU_name", INDEX_TYPE.NOTUNIQUE, "name");

    db.begin();
    var e1 = (EntityImpl) db.newVertex("StreamNullNU");
    // name not set → null key
    var e2 = (EntityImpl) db.newVertex("StreamNullNU");
    e2.setProperty("name", "Alice");
    var e3 = (EntityImpl) db.newVertex("StreamNullNU");
    e3.setProperty("name", "Bob");
    db.commit();

    db.begin();
    var index = db.getSharedContext().getIndexManager().getIndex("StreamNullNU_name");

    // stream() should contain only non-null keys
    try (var stream = index.stream(db)) {
      var keys = stream.map(RawPair::first).toList();
      assertFalse("stream() must not contain null keys", keys.contains(null));
      assertEquals("stream() should return 2 non-null entries", 2, keys.size());
    }

    // descStream() should contain only non-null keys
    try (var stream = index.descStream(db)) {
      var keys = stream.map(RawPair::first).toList();
      assertFalse("descStream() must not contain null keys", keys.contains(null));
      assertEquals("descStream() should return 2 non-null entries", 2, keys.size());
    }

    // keyStream() should contain only non-null keys
    var atomicOp = db.getActiveTransaction().getAtomicOperation();
    try (var stream = index.keyStream(atomicOp)) {
      var keys = stream.toList();
      assertFalse("keyStream() must not contain null keys", keys.contains(null));
      assertEquals("keyStream() should return 2 non-null keys", 2, keys.size());
    }

    // getRids(null) should still return the null-keyed entry
    assertEquals(1, fetchNullKeyCount(db, "StreamNullNU_name"));

    db.rollback();
  }

  /**
   * For a UNIQUE index containing both a null-keyed and non-null-keyed records,
   * stream() must return only non-null keys. Null-keyed entries should only be
   * accessible via getRids(session, null).
   */
  @Test
  public void unique_streamExcludesNullKeys() {
    SchemaClass cls = db.createVertexClass("StreamNullU");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("StreamNullU_name", INDEX_TYPE.UNIQUE, "name");

    db.begin();
    var e1 = (EntityImpl) db.newVertex("StreamNullU");
    // name not set → null key
    var e2 = (EntityImpl) db.newVertex("StreamNullU");
    e2.setProperty("name", "Alice");
    var e3 = (EntityImpl) db.newVertex("StreamNullU");
    e3.setProperty("name", "Bob");
    db.commit();

    db.begin();
    var index = db.getSharedContext().getIndexManager().getIndex("StreamNullU_name");

    // stream() should contain only non-null keys
    try (var stream = index.stream(db)) {
      var keys = stream.map(RawPair::first).toList();
      assertFalse("stream() must not contain null keys", keys.contains(null));
      assertEquals("stream() should return 2 non-null entries", 2, keys.size());
    }

    // descStream() should contain only non-null keys
    try (var stream = index.descStream(db)) {
      var keys = stream.map(RawPair::first).toList();
      assertFalse("descStream() must not contain null keys", keys.contains(null));
      assertEquals("descStream() should return 2 non-null entries", 2, keys.size());
    }

    // keyStream() should contain only non-null keys
    var atomicOp = db.getActiveTransaction().getAtomicOperation();
    try (var stream = index.keyStream(atomicOp)) {
      var keys = stream.toList();
      assertFalse("keyStream() must not contain null keys", keys.contains(null));
      assertEquals("keyStream() should return 2 non-null keys", 2, keys.size());
    }

    // getRids(null) should still return the null-keyed entry
    assertEquals(1, fetchNullKeyCount(db, "StreamNullU_name"));

    db.rollback();
  }

  // ========================================================================
  //  Composite keys with null fields must not collide with null key
  // ========================================================================

  /**
   * A composite UNIQUE index on (name, surname): CompositeKey(null, null) and
   * CompositeKey(null, "Smith") are valid composite keys, NOT null keys.
   * They must all live in the main tree (svTree/sbTree) and be returned by
   * stream(). getRids(null) must return 0 — there are no null keys in a
   * composite index.
   *
   * <pre>
   *   Record 1: name=null, surname=null    → key = CompositeKey(null, null)
   *   Record 2: name=null, surname="Smith" → key = CompositeKey(null, "Smith")
   *   Record 3: name="Alice", surname="Jones" → key = CompositeKey("Alice", "Jones")
   *
   *   getRids(null) must return 0 (no null keys in composite indexes)
   *   stream() must return all 3 entries
   *   size() must be 3
   * </pre>
   */
  @Test
  public void unique_compositeIndex_nullFieldsAreNotNullKey() {
    SchemaClass cls = db.createVertexClass("CompositeNullU");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createIndex("CompositeNullU_idx", INDEX_TYPE.UNIQUE, "name", "surname");

    // Record 1: both fields null → CompositeKey(null, null), NOT a null key
    db.begin();
    db.newVertex("CompositeNullU");
    db.commit();

    // Record 2: name=null, surname="Smith" → CompositeKey(null, "Smith")
    db.begin();
    var e2 = (EntityImpl) db.newVertex("CompositeNullU");
    e2.setProperty("surname", "Smith");
    db.commit();

    // Record 3: non-null composite key
    db.begin();
    var e3 = (EntityImpl) db.newVertex("CompositeNullU");
    e3.setProperty("name", "Alice");
    e3.setProperty("surname", "Jones");
    db.commit();

    db.begin();
    var index = db.getSharedContext().getIndexManager().getIndex("CompositeNullU_idx");

    // No null keys in a composite index — all entries are composite keys
    assertEquals(
        "getRids(null) must return 0 for composite index",
        0, fetchNullKeyCount(db, "CompositeNullU_idx"));

    // stream() must return all 3 entries (all are valid composite keys)
    try (var stream = index.stream(db)) {
      assertEquals(
          "stream() should return all 3 composite-keyed entries",
          3, stream.count());
    }

    // size() must be exactly 3
    assertEquals(
        "size() must count all 3 entries exactly once",
        3, index.size(db));

    db.rollback();
  }

  /**
   * Same test for NOTUNIQUE composite index. CompositeKey(null, null) and
   * CompositeKey(null, "Smith") are composite keys stored in svTree, not
   * null keys stored in nullTree.
   */
  @Test
  public void notUnique_compositeIndex_nullFieldsAreNotNullKey() {
    SchemaClass cls = db.createVertexClass("CompositeNullNU");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createIndex("CompositeNullNU_idx", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    // Record 1: both fields null → CompositeKey(null, null), NOT a null key
    db.begin();
    db.newVertex("CompositeNullNU");
    db.commit();

    // Record 2: name=null, surname="Smith" → CompositeKey(null, "Smith")
    db.begin();
    var e2 = (EntityImpl) db.newVertex("CompositeNullNU");
    e2.setProperty("surname", "Smith");
    db.commit();

    // Record 3: non-null composite key
    db.begin();
    var e3 = (EntityImpl) db.newVertex("CompositeNullNU");
    e3.setProperty("name", "Alice");
    e3.setProperty("surname", "Jones");
    db.commit();

    db.begin();
    var index = db.getSharedContext().getIndexManager().getIndex("CompositeNullNU_idx");

    // No null keys in a composite index
    assertEquals(
        "getRids(null) must return 0 for composite index",
        0, fetchNullKeyCount(db, "CompositeNullNU_idx"));

    // stream() must return all 3 entries
    try (var stream = index.stream(db)) {
      assertEquals(
          "stream() should return all 3 composite-keyed entries",
          3, stream.count());
    }

    // size() must be exactly 3
    assertEquals(
        "size() must count all 3 entries exactly once",
        3, index.size(db));

    db.rollback();
  }

  // ========================================================================
  //  Helpers
  // ========================================================================

  private int fetchNullKeyCount(DatabaseSessionEmbedded session, String indexName) {
    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    try (var stream = index.getRids(session, null)) {
      return (int) stream.count();
    }
  }

  private int fetchKeyCount(DatabaseSessionEmbedded session, String indexName, Object key) {
    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    try (var stream = index.getRids(session, key)) {
      return (int) stream.count();
    }
  }
}
