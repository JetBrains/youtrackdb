package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests snapshot isolation for the BTreeMultiValueIndexEngine.get() and
 * BTreeSingleValueIndexEngine.get() paths.
 *
 * <p>The get() method (used by Index.getRids()) performs a direct BTree lookup
 * without applying the visibility filter. These tests verify that get() correctly
 * filters out TombstoneRIDs, unwraps SnapshotMarkerRIDs, and respects snapshot
 * boundaries — the same guarantees that iterateEntriesBetween already provides.
 */
public class SnapshotIsolationIndexesGetTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  /**
   * Verifies that getRids() on a NOTUNIQUE index does not return TombstoneRID or
   * SnapshotMarkerRID values — only plain RIDs should be visible to callers.
   *
   * <p>Scenario: insert 3 records with key "Foo", then update one to "Bar" in a
   * separate transaction. A subsequent getRids("Foo") must return 2 plain RIDs (not 3,
   * and none should be Tombstone/Marker wrappers).
   */
  @Test
  public void getRids_returnsPlainRids_afterUpdate_NOTUNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // Insert 3 records with name = "Foo"
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Update one Foo -> Bar
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // Query via getRids() path
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    Index index = session.getIndex("IndexPropertyName");

    List<RID> fooRids;
    try (var stream = index.getRids(session, "Foo")) {
      fooRids = stream.collect(Collectors.toList());
    }

    // Must see exactly 2 results, all plain RIDs
    assertEquals(2, fooRids.size());
    for (RID rid : fooRids) {
      assertFalse("getRids() must not return SnapshotMarkerRID",
          rid instanceof SnapshotMarkerRID);
      assertFalse("getRids() must not return TombstoneRID",
          rid instanceof TombstoneRID);
    }

    List<RID> barRids;
    try (var stream = index.getRids(session, "Bar")) {
      barRids = stream.collect(Collectors.toList());
    }

    assertEquals(1, barRids.size());
    for (RID rid : barRids) {
      assertFalse(rid instanceof SnapshotMarkerRID);
      assertFalse(rid instanceof TombstoneRID);
    }
    session.commit();
    session.close();

    graph.close();
  }

  /**
   * Verifies that a snapshot transaction does not see phantom inserts via getRids().
   *
   * <p>Scenario: insert 3 "Foo" records, start a snapshot TX, then insert a 4th "Foo"
   * in another TX. The snapshot TX must still see only 3 via getRids("Foo").
   */
  @Test
  public void getRids_noPhantoms_NOTUNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    graph.tx().commit();

    // Start snapshot TX
    var snapshotSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    Index snapshotIndex = snapshotSession.getIndex("IndexPropertyName");

    List<RID> beforeInsert;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      beforeInsert = stream.collect(Collectors.toList());
    }
    assertEquals(3, beforeInsert.size());

    // Insert 4th Foo in another TX
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo").next();
    graph.tx().commit();

    // Snapshot must still see 3
    List<RID> afterInsert;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      afterInsert = stream.collect(Collectors.toList());
    }
    assertEquals(3, afterInsert.size());
    snapshotSession.commit();
    snapshotSession.close();

    // Fresh session must see 4
    var freshSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    freshSession.begin();
    Index freshIndex = freshSession.getIndex("IndexPropertyName");
    List<RID> freshRids;
    try (var stream = freshIndex.getRids(freshSession, "Foo")) {
      freshRids = stream.collect(Collectors.toList());
    }
    assertEquals(4, freshRids.size());
    freshSession.commit();
    freshSession.close();

    graph.close();
  }

  /**
   * Verifies that a snapshot transaction does not see concurrent updates via getRids().
   *
   * <p>Scenario: insert 3 "Foo" records, start a snapshot TX, then update one to "Bar".
   * The snapshot TX must still see 3 "Foo" and 0 "Bar" via getRids().
   */
  @Test
  public void getRids_noVisibilityForUpdates_NOTUNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Start snapshot TX
    var snapshotSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    Index snapshotIndex = snapshotSession.getIndex("IndexPropertyName");

    List<RID> beforeUpdate;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      beforeUpdate = stream.collect(Collectors.toList());
    }
    assertEquals(3, beforeUpdate.size());

    // Update one Foo -> Bar in another TX
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // Snapshot must still see 3 Foo, 0 Bar
    List<RID> fooAfter;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      fooAfter = stream.collect(Collectors.toList());
    }
    assertEquals(3, fooAfter.size());

    List<RID> barAfter;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Bar")) {
      barAfter = stream.collect(Collectors.toList());
    }
    assertEquals(0, barAfter.size());

    snapshotSession.commit();
    snapshotSession.close();

    // Fresh session sees updated state
    var freshSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    freshSession.begin();
    Index freshIndex = freshSession.getIndex("IndexPropertyName");

    List<RID> freshFoo;
    try (var stream = freshIndex.getRids(freshSession, "Foo")) {
      freshFoo = stream.collect(Collectors.toList());
    }
    assertEquals(2, freshFoo.size());

    List<RID> freshBar;
    try (var stream = freshIndex.getRids(freshSession, "Bar")) {
      freshBar = stream.collect(Collectors.toList());
    }
    assertEquals(1, freshBar.size());
    freshSession.commit();
    freshSession.close();

    graph.close();
  }

  /**
   * Verifies getRids() with ABA update pattern: Foo -> Bar -> Foo.
   *
   * <p>A snapshot started before the updates must see the original state,
   * while a fresh session sees the final state.
   */
  @Test
  public void getRids_ABAUpdate_NOTUNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Start snapshot TX
    var snapshotSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    Index snapshotIndex = snapshotSession.getIndex("IndexPropertyName");

    List<RID> beforeUpdate;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      beforeUpdate = stream.collect(Collectors.toList());
    }
    assertEquals(3, beforeUpdate.size());

    // Foo -> Bar
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // Bar -> Foo
    graph.tx().begin();
    graph.V(id1).property("name", "Foo").iterate();
    graph.tx().commit();

    // Snapshot must still see original 3 Foo
    List<RID> fooAfter;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      fooAfter = stream.collect(Collectors.toList());
    }
    assertEquals(3, fooAfter.size());

    snapshotSession.commit();
    snapshotSession.close();

    // Fresh session sees final state: all 3 still Foo
    var freshSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    freshSession.begin();
    Index freshIndex = freshSession.getIndex("IndexPropertyName");

    List<RID> freshFoo;
    try (var stream = freshIndex.getRids(freshSession, "Foo")) {
      freshFoo = stream.collect(Collectors.toList());
    }
    assertEquals(3, freshFoo.size());

    List<RID> freshBar;
    try (var stream = freshIndex.getRids(freshSession, "Bar")) {
      freshBar = stream.collect(Collectors.toList());
    }
    assertEquals(0, freshBar.size());
    freshSession.commit();
    freshSession.close();

    graph.close();
  }

  /**
   * Verifies getRids() on a UNIQUE index returns a plain RID after update,
   * not a SnapshotMarkerRID.
   */
  @Test
  public void getRids_returnsPlainRids_afterUpdate_UNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Update Foo -> Bar
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // getRids("Foo") must return empty, getRids("Bar") must return 1 plain RID
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    Index index = session.getIndex("IndexPropertyName");

    List<RID> fooRids;
    try (var stream = index.getRids(session, "Foo")) {
      fooRids = stream.collect(Collectors.toList());
    }
    assertEquals(0, fooRids.size());

    List<RID> barRids;
    try (var stream = index.getRids(session, "Bar")) {
      barRids = stream.collect(Collectors.toList());
    }
    assertEquals(1, barRids.size());
    assertEquals("getRids('Bar') must return the RID of the updated vertex",
        id1, barRids.get(0));
    for (RID rid : barRids) {
      assertFalse(rid instanceof SnapshotMarkerRID);
      assertFalse(rid instanceof TombstoneRID);
    }
    session.commit();
    session.close();

    graph.close();
  }

  /**
   * Verifies snapshot isolation for getRids() on a UNIQUE index: a snapshot TX
   * must not see concurrent updates.
   */
  @Test
  public void getRids_noVisibilityForUpdates_UNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Start snapshot TX
    var snapshotSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    Index snapshotIndex = snapshotSession.getIndex("IndexPropertyName");

    List<RID> beforeUpdate;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      beforeUpdate = stream.collect(Collectors.toList());
    }
    assertEquals(1, beforeUpdate.size());

    // Update Foo -> Bar in another TX
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // Snapshot must still see Foo, not Bar
    List<RID> fooAfter;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      fooAfter = stream.collect(Collectors.toList());
    }
    assertEquals(1, fooAfter.size());
    assertEquals("Snapshot Foo RID must match original vertex", id1, fooAfter.get(0));

    List<RID> barAfter;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Bar")) {
      barAfter = stream.collect(Collectors.toList());
    }
    assertEquals(0, barAfter.size());

    snapshotSession.commit();
    snapshotSession.close();

    // Fresh session sees updated state
    var freshSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    freshSession.begin();
    Index freshIndex = freshSession.getIndex("IndexPropertyName");

    List<RID> freshFoo;
    try (var stream = freshIndex.getRids(freshSession, "Foo")) {
      freshFoo = stream.collect(Collectors.toList());
    }
    assertEquals(0, freshFoo.size());

    List<RID> freshBar;
    try (var stream = freshIndex.getRids(freshSession, "Bar")) {
      freshBar = stream.collect(Collectors.toList());
    }
    assertEquals(1, freshBar.size());
    assertEquals("Fresh Bar RID must match updated vertex", id1, freshBar.get(0));
    freshSession.commit();
    freshSession.close();

    graph.close();
  }

  /**
   * Verifies that a TX started before any data was inserted sees nothing
   * via getRids(), even after inserts and updates happen concurrently.
   */
  @Test
  public void getRids_noVisibilityForTXBeforeInsert_NOTUNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // Start snapshot TX before any data exists
    var snapshotSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    Index snapshotIndex = snapshotSession.getIndex("IndexPropertyName");

    List<RID> beforeInsert;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      beforeInsert = stream.collect(Collectors.toList());
    }
    assertEquals(0, beforeInsert.size());

    // Insert records in another session
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Snapshot must still see nothing
    List<RID> afterInsert;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      afterInsert = stream.collect(Collectors.toList());
    }
    assertEquals(0, afterInsert.size());

    // Update one Foo -> Bar
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // Snapshot must still see nothing for either key
    List<RID> afterUpdate;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      afterUpdate = stream.collect(Collectors.toList());
    }
    assertEquals(0, afterUpdate.size());

    List<RID> barAfterUpdate;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Bar")) {
      barAfterUpdate = stream.collect(Collectors.toList());
    }
    assertEquals(0, barAfterUpdate.size());

    snapshotSession.commit();
    snapshotSession.close();

    // Fresh session sees current state
    var freshSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    freshSession.begin();
    Index freshIndex = freshSession.getIndex("IndexPropertyName");

    List<RID> freshFoo;
    try (var stream = freshIndex.getRids(freshSession, "Foo")) {
      freshFoo = stream.collect(Collectors.toList());
    }
    assertEquals(2, freshFoo.size());

    List<RID> freshBar;
    try (var stream = freshIndex.getRids(freshSession, "Bar")) {
      freshBar = stream.collect(Collectors.toList());
    }
    assertEquals(1, freshBar.size());
    freshSession.commit();
    freshSession.close();

    graph.close();
  }

  /**
   * Verifies that getRids() on an empty UNIQUE index returns an empty stream
   * for a non-existent key. Exercises the getVisible() path when the B-tree
   * has no entries at all.
   */
  @Test
  public void getRids_emptyIndex_returnsEmpty_UNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    Index index = session.getIndex("IndexPropertyName");

    List<RID> rids;
    try (var stream = index.getRids(session, "NonExistent")) {
      rids = stream.collect(Collectors.toList());
    }
    assertEquals(0, rids.size());
    session.commit();
    session.close();
  }

  /**
   * Verifies that getRids() returns empty after deleting a record from a UNIQUE
   * index. The B-tree still contains a TombstoneRID entry; the getVisible() path
   * must suppress it.
   */
  @Test
  public void getRids_afterDelete_returnsEmpty_UNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Delete the record
    graph.tx().begin();
    graph.V(id1).drop().iterate();
    graph.tx().commit();

    // Fresh session: getRids("Foo") must return empty
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    Index index = session.getIndex("IndexPropertyName");

    List<RID> rids;
    try (var stream = index.getRids(session, "Foo")) {
      rids = stream.collect(Collectors.toList());
    }
    assertEquals(0, rids.size());
    session.commit();
    session.close();
    graph.close();
  }

  /**
   * Verifies that a TX started before any data was inserted sees nothing via
   * getRids() on a UNIQUE index, even after inserts happen concurrently.
   * Exercises the getVisible() path for phantom insert isolation.
   */
  @Test
  public void getRids_noVisibilityForTXBeforeInsert_UNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    // Start snapshot TX before any data exists
    var snapshotSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    Index snapshotIndex = snapshotSession.getIndex("IndexPropertyName");

    List<RID> beforeInsert;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      beforeInsert = stream.collect(Collectors.toList());
    }
    assertEquals(0, beforeInsert.size());

    // Insert in another session
    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo").next();
    graph.tx().commit();

    // Snapshot must still see nothing
    List<RID> afterInsert;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      afterInsert = stream.collect(Collectors.toList());
    }
    assertEquals(0, afterInsert.size());

    snapshotSession.commit();
    snapshotSession.close();

    // Fresh session sees the record
    var freshSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    freshSession.begin();
    Index freshIndex = freshSession.getIndex("IndexPropertyName");
    List<RID> freshRids;
    try (var stream = freshIndex.getRids(freshSession, "Foo")) {
      freshRids = stream.collect(Collectors.toList());
    }
    assertEquals(1, freshRids.size());
    freshSession.commit();
    freshSession.close();
    graph.close();
  }

  /**
   * Verifies getRids() with ABA update pattern on a UNIQUE index: Foo -> Bar ->
   * Foo. A snapshot started before the updates must see the original state,
   * while a fresh session sees the final state. Exercises the getVisible() path
   * with multiple version entries for the same key prefix.
   */
  @Test
  public void getRids_ABAUpdate_UNIQUE() {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Start snapshot TX
    var snapshotSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snapshotSession.begin();
    Index snapshotIndex = snapshotSession.getIndex("IndexPropertyName");

    List<RID> beforeUpdate;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      beforeUpdate = stream.collect(Collectors.toList());
    }
    assertEquals(1, beforeUpdate.size());

    // Foo -> Bar
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // Bar -> Foo (ABA)
    graph.tx().begin();
    graph.V(id1).property("name", "Foo").iterate();
    graph.tx().commit();

    // Snapshot must still see 1 Foo (the original version)
    List<RID> fooAfter;
    try (var stream = snapshotIndex.getRids(snapshotSession, "Foo")) {
      fooAfter = stream.collect(Collectors.toList());
    }
    assertEquals(1, fooAfter.size());
    assertEquals("Snapshot Foo RID must match original vertex", id1, fooAfter.get(0));

    snapshotSession.commit();
    snapshotSession.close();

    // Fresh session sees final state: Foo present, Bar absent
    var freshSession = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    freshSession.begin();
    Index freshIndex = freshSession.getIndex("IndexPropertyName");

    List<RID> freshFoo;
    try (var stream = freshIndex.getRids(freshSession, "Foo")) {
      freshFoo = stream.collect(Collectors.toList());
    }
    assertEquals(1, freshFoo.size());

    List<RID> freshBar;
    try (var stream = freshIndex.getRids(freshSession, "Bar")) {
      freshBar = stream.collect(Collectors.toList());
    }
    assertEquals(0, freshBar.size());
    freshSession.commit();
    freshSession.close();

    graph.close();
  }

  private YTDBGraphTraversalSource openGraph() {
    return youTrackDB.openTraversal("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }
}
