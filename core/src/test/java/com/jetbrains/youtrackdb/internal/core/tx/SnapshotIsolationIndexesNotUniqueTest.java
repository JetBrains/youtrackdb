package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot-isolation tests for NOTUNIQUE indexes. Exercises BTreeMultiValueIndexEngine via
 * Gremlin traversals.
 */
public class SnapshotIsolationIndexesNotUniqueTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @Test
  public void noVisibilityForPhantoms() throws Exception {

    String fooValue = "Foo";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();

      snapshotGraph.tx().begin();
      var beforeInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", "Foo")
          .toList();

      assertEquals(3, beforeInsert.size());

      // insert NEW Foo (phantom candidate)
      graph.tx().begin();
      graph.addV("Userr").property("name", "Foo").next();
      graph.tx().commit();

      var afterInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", "Foo")
          .toList();

      assertEquals(3, afterInsert.size());
      snapshotGraph.tx().commit();

      // New graph must see final reality
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", "Foo")
          .toList();

      assertEquals(4, foos.size()); // 3 old untouched + 1 newly inserted
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForUpdates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();

      snapshotGraph.tx().begin();
      var beforeUpdate = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, beforeUpdate.size());

      // update Foo -> Bar
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      var afterUpdate = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, afterUpdate.size());
      snapshotGraph.tx().commit();

      // New graph must see final values
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(2, foos.size()); // 2 old untouched
      assertEquals(1, bars.size()); // 1 updated
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForUpdates_MultipleIndexes() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createProperty("surname", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name", "surname");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).property("surname", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();

      snapshotGraph.tx().begin();
      var beforeUpdate = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .has("surname", fooValue)
          .toList();

      assertEquals(1, beforeUpdate.size());

      // update Foo -> Bar
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", barValue)
          .property("surname", barValue)
          .iterate();
      graph.tx().commit();

      var afterUpdate = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .has("surname", fooValue)
          .toList();

      assertEquals(1, afterUpdate.size());
      snapshotGraph.tx().commit();

      // New graph must see final values
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .has("surname", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .has("surname", barValue)
          .toList();

      assertEquals(0, foos.size()); // 2 old untouched
      assertEquals(1, bars.size()); // 1 updated
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForTXBeforeInsertAndAfterUpdate() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    var graphBeforeInsert = openGraph();
    YTDBGraphTraversalSource graph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graphBeforeInsert.tx().begin();
      var beforeInsertFoo = graphBeforeInsert
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(0, beforeInsertFoo.size());

      // create MULTIPLE records with the same property value
      graph = openGraph();
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // No values after insert with TX started before
      beforeInsertFoo = graphBeforeInsert
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(0, beforeInsertFoo.size());

      // Session 1: concurrent modifications
      graph.tx().begin();
      // modify one existing Foo → Bar to populate the index snapshot
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      // Session 3: fresh graph must see changes
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(2, foos.size()); // 2 inserted
      assertEquals(1, bars.size()); // 1 updated
      newGraph.tx().commit();

      // No values after update with TX started before
      beforeInsertFoo = graphBeforeInsert
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var beforeInsertBar = graphBeforeInsert
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(0, beforeInsertFoo.size());
      assertEquals(0, beforeInsertBar.size());
      graphBeforeInsert.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (graph != null)
        graph.close();
      graphBeforeInsert.close();
    }
  }

  @Test
  public void visibleMultipleUpdates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();

      snapshotGraph.tx().begin();
      var beforeInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, beforeInsert.size());

      // update for all record Foo -> Bar
      graph.tx().begin();
      graph
          .V()
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      var afterInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, afterInsert.size());
      snapshotGraph.tx().commit();

      // New graph must see final reality
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(0, foos.size()); // 0 old untouched
      assertEquals(3, bars.size()); // 3 updated
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void visibleMultipleVersionsUpdates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();
      graph.tx().begin();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();
      graph.tx().begin();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();

      snapshotGraph.tx().begin();
      var beforeInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, beforeInsert.size());

      // update Foo -> Bar
      graph.tx().begin();
      graph
          .V()
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      var afterInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, afterInsert.size());
      snapshotGraph.tx().commit();

      // New graph must see final reality
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(0, foos.size()); // 0 old untouched
      assertEquals(3, bars.size()); // 3 updated
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void visibleABAUpdates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource barGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();

      snapshotGraph.tx().begin();
      var beforeInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, beforeInsert.size());

      // update Foo -> Bar
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      // start repeatable-read snapshot TX for Bar
      barGraph = openGraph();
      barGraph.tx().begin();
      var barInsert = barGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();
      assertEquals(1, barInsert.size());

      // update Bar -> Foo
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", fooValue)
          .iterate();
      graph.tx().commit();

      // update Foo -> Bar
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      // update Bar -> Foo
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", fooValue)
          .iterate();
      graph.tx().commit();

      var afterInsert = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, afterInsert.size());
      snapshotGraph.tx().commit();

      // New graph must see final reality
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(3, foos.size()); // 0 old untouched
      assertEquals(0, bars.size()); // 3 updated
      newGraph.tx().commit();

      var barRepeatableRead = barGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();
      assertEquals(1, barRepeatableRead.size());
      barGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (barGraph != null)
        barGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void snapshotIsolationABA() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // ==========================================================
    // Schema with NOTUNIQUE index
    // ==========================================================
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // ==========================================================
    // Session 1: create MULTIPLE records
    // ==========================================================
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // Session 2: start repeatable-read snapshot
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var fooBeforeUpdate = snapshotGraph
          .V()
          //.hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      // ---- snapshot must see all 3 ----
      assertEquals(3, fooBeforeUpdate.size());

      // Session 1: concurrent modifications
      graph.tx().begin();
      // 1) modify one existing Foo → Bar
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();

      // 2) insert NEW Foo (phantom candidate)
      graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();

      // Session 2: snapshot must NOT see changes
      var fooAfterUpdate = snapshotGraph
          .V()
          //.hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var barAfterUpdate = snapshotGraph
          .V()
          //.hasLabel("Userr")
          .has("name", barValue)
          .toList();

      // ---- still must be original 3 ----
      assertEquals(3, fooAfterUpdate.size());
      assertEquals(0, barAfterUpdate.size());

      snapshotGraph.tx().commit();

      // ==========================================================
      // Session 3: fresh graph must see final reality
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();

      var foos = newGraph
          .V()
          //.hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      // ---- final state expectations ----
      assertEquals(3, foos.size()); // 2 old untouched + 1 newly inserted
      assertEquals(1, bars.size()); // the updated one
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void removedSnapshotMarkerRID() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // ==========================================================
    // Schema with NOTUNIQUE index
    // ==========================================================
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // ==========================================================
    // Session 1: create MULTIPLE records
    // ==========================================================
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // Session 2: start repeatable-read snapshot
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var fooBeforeUpdate = snapshotGraph
          .V()
          //.hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      // ---- snapshot must see all 3 ----
      assertEquals(3, fooBeforeUpdate.size());

      // Session 1: concurrent modifications
      graph.tx().begin();
      // modify one existing Foo → Bar
      // TombstoneRID for Foo should create
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      graph.tx().begin();
      // modify one existing Bar → Foo
      // SnapshotMarkerRID for Foo should create
      graph
          .V(id1)
          .property("name", fooValue)
          .iterate();
      graph.tx().commit();

      graph.tx().begin();
      // modify one existing Foo → Bar
      // SnapshotMarkerRID for Foo should replaced on TombstoneRID
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      // Session 2: snapshot must NOT see changes
      var fooAfterUpdate = snapshotGraph
          .V()
          //.hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var barAfterUpdate = snapshotGraph
          .V()
          //.hasLabel("Userr")
          .has("name", barValue)
          .toList();

      // ---- still must be original 3 ----
      assertEquals(3, fooAfterUpdate.size());
      assertEquals(0, barAfterUpdate.size());

      snapshotGraph.tx().commit();

      // ==========================================================
      // Session 3: fresh graph must see final reality
      // ==========================================================
      newGraph = openGraph();

      newGraph.tx().begin();

      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      // ---- final state expectations ----
      assertEquals(2, foos.size()); // 2 old untouched
      assertEquals(1, bars.size()); // the updated one

      graph.tx().begin();
      // modify one existing Foo → Bar
      // TombstoneRID for Foo should replaced on SnapshotMarkerRID
      graph
          .V(id1)
          .property("name", fooValue)
          .iterate();
      graph.tx().commit();

      var foosRep = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      var barsRep = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      // ---- final state expectations ----
      assertEquals(2, foosRep.size()); // 2 old untouched
      assertEquals(1, barsRep.size()); // the updated one

      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForDeletes() throws Exception {

    String fooValue = "Foo";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var beforeDelete = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, beforeDelete.size());

      // delete one record in a concurrent TX
      graph.tx().begin();
      graph
          .V(id1)
          .drop()
          .iterate();
      graph.tx().commit();

      // snapshot must NOT see the delete
      var afterDelete = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, afterDelete.size());
      snapshotGraph.tx().commit();

      // New graph must see final reality (only 2 remain)
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(2, foos.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForMultipleDeletes() throws Exception {

    String fooValue = "Foo";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create MULTIPLE records
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      graph.addV("Userr").property("name", fooValue).next();
      graph.addV("Userr").property("name", fooValue).next();
      graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var beforeDelete = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, beforeDelete.size());

      // delete ALL records in a concurrent TX
      graph.tx().begin();
      graph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .drop()
          .iterate();
      graph.tx().commit();

      // snapshot must still see all 3
      var afterDelete = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, afterDelete.size());
      snapshotGraph.tx().commit();

      // Fresh graph must see 0
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(0, foos.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForDeleteAndInsert() throws Exception {

    String fooValue = "Foo";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create 2 records
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var beforeChange = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(2, beforeChange.size());

      // In a concurrent TX: delete one record AND insert two new ones (net +1)
      graph.tx().begin();
      graph
          .V(id1)
          .drop()
          .iterate();
      graph.addV("Userr").property("name", fooValue).next();
      graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();

      // snapshot must still see original 2
      var afterChange = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(2, afterChange.size());
      snapshotGraph.tx().commit();

      // Fresh graph must see final reality: 1 old + 2 new = 3
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(3, foos.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForUpdateAndDelete() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create 3 records
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var u3 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      var id2 = u2.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var fooBefore = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var barBefore = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(3, fooBefore.size());
      assertEquals(0, barBefore.size());

      // Concurrent TX: update one Foo→Bar, delete another Foo
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph
          .V(id2)
          .drop()
          .iterate();
      graph.tx().commit();

      // snapshot must still see original state
      var fooAfter = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var barAfter = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(3, fooAfter.size());
      assertEquals(0, barAfter.size());
      snapshotGraph.tx().commit();

      // Fresh graph: 1 Foo remaining, 1 Bar
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(1, foos.size());
      assertEquals(1, bars.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForSequentialConcurrentTXs() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";
    String bazValue = "Baz";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create initial records
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      var id2 = u2.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var fooBefore = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(2, fooBefore.size());

      // Concurrent TX1: update first Foo→Bar
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      // Concurrent TX2: update second Foo→Baz and insert new Foo
      graph.tx().begin();
      graph
          .V(id2)
          .property("name", bazValue)
          .iterate();
      graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();

      // snapshot must still see original 2 Foos, no Bars, no Bazs
      var fooAfter = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var barAfter = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();
      var bazAfter = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", bazValue)
          .toList();

      assertEquals(2, fooAfter.size());
      assertEquals(0, barAfter.size());
      assertEquals(0, bazAfter.size());
      snapshotGraph.tx().commit();

      // Fresh graph must see final reality: 1 Foo (new), 1 Bar, 1 Baz
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();
      var bazs = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", bazValue)
          .toList();

      assertEquals(1, foos.size());
      assertEquals(1, bars.size());
      assertEquals(1, bazs.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void noVisibilityForDeleteAndReinsertSameValue() throws Exception {

    String fooValue = "Foo";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create single record
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // start repeatable-read snapshot TX
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var before = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(1, before.size());

      // Concurrent TX: delete the record and insert a new one with the same value
      graph.tx().begin();
      graph
          .V(id1)
          .drop()
          .iterate();
      graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();

      // snapshot must still see the original 1 record
      var after = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(1, after.size());
      snapshotGraph.tx().commit();

      // Fresh graph must also see exactly 1 (the newly inserted one)
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(1, foos.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void snapshotSeesOwnIndexedKey() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create initial records
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      graph.tx().commit();

      // Snapshot TX: read, then do own insert inside the snapshot TX, verify it sees its own writes
      // but not concurrent external writes
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();

      var fooBefore = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(2, fooBefore.size());

      // snapshot TX inserts its own Bar
      snapshotGraph.addV("Userr").property("name", barValue).next();

      // concurrent TX inserts another Bar externally
      graph.tx().begin();
      graph.addV("Userr").property("name", barValue).next();
      graph.addV("Userr").property("name", barValue).next();
      graph.tx().commit();

      // snapshot sees its own Bar (1), not the external ones (2)
      var barsInSnapshot = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(1, barsInSnapshot.size());

      // snapshot still sees original 2 Foos
      var foosInSnapshot = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();

      assertEquals(2, foosInSnapshot.size());
      snapshotGraph.tx().commit();

      // Fresh graph: 2 Foos, 3 Bars (1 from snapshot + 2 from concurrent)
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var bars = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();

      assertEquals(2, foos.size());
      assertEquals(3, bars.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  @Test
  public void twoSnapshotsSeeDifferentStates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // create 2 Foo records
    var graph = openGraph();
    YTDBGraphTraversalSource snapshot1 = null;
    YTDBGraphTraversalSource snapshot2 = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var u2 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // Snapshot TX1: sees 2 Foos
      snapshot1 = openGraph();
      snapshot1.tx().begin();
      var foos1 = snapshot1
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      assertEquals(2, foos1.size());

      // Concurrent TX: update one Foo→Bar
      graph.tx().begin();
      graph
          .V(id1)
          .property("name", barValue)
          .iterate();
      graph.tx().commit();

      // Snapshot TX2: started after the update, sees 1 Foo + 1 Bar
      snapshot2 = openGraph();
      snapshot2.tx().begin();
      var foos2 = snapshot2
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var bars2 = snapshot2
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();
      assertEquals(1, foos2.size());
      assertEquals(1, bars2.size());

      // Snapshot TX1 still sees 2 Foos, 0 Bars
      var foos1After = snapshot1
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      var bars1After = snapshot1
          .V()
          .hasLabel("Userr")
          .has("name", barValue)
          .toList();
      assertEquals(2, foos1After.size());
      assertEquals(0, bars1After.size());

      snapshot1.tx().commit();
      snapshot2.tx().commit();
    } finally {
      if (snapshot2 != null)
        snapshot2.close();
      if (snapshot1 != null)
        snapshot1.close();
      graph.close();
    }
  }

  /**
   * Exercises SnapshotMarkerRID lifecycle through repeated ABA updates on a NOTUNIQUE index.
   *
   * <p>Sequence: create 3 Foo records → snapshot TX sees 3 → concurrent TX does Foo→Bar (creates
   * TombstoneRID), Bar→Foo (creates SnapshotMarkerRID), Foo→Bar (replaces SnapshotMarkerRID with
   * TombstoneRID) → snapshot TX still sees original 3 Foo / 0 Bar → fresh TX sees 2 Foo / 1 Bar
   * → another concurrent TX does Bar→Foo (replaces TombstoneRID with SnapshotMarkerRID) → the
   * fresh TX still sees its snapshot (2 Foo / 1 Bar).
   */
  @Test
  public void snapshotMarkerRIDLifecycle() throws Exception {
    String fooValue = "Foo";
    String barValue = "Bar";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // Session 1: create 3 records with name=Foo
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      graph.addV("Userr").property("name", fooValue).next();
      graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // Session 2: start repeatable-read snapshot — sees 3 Foo
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      assertEquals(3, snapshotGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());

      // Session 1: Foo→Bar (TombstoneRID for Foo created)
      graph.tx().begin();
      graph.V(id1).property("name", barValue).iterate();
      graph.tx().commit();

      // Session 1: Bar→Foo (SnapshotMarkerRID for Foo created)
      graph.tx().begin();
      graph.V(id1).property("name", fooValue).iterate();
      graph.tx().commit();

      // Session 1: Foo→Bar (SnapshotMarkerRID replaced with TombstoneRID)
      graph.tx().begin();
      graph.V(id1).property("name", barValue).iterate();
      graph.tx().commit();

      // Session 2: snapshot must NOT see any changes — still 3 Foo, 0 Bar
      assertEquals(3, snapshotGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());
      assertEquals(0, snapshotGraph.V().hasLabel("Userr").has("name", barValue).toList().size());
      snapshotGraph.tx().commit();

      // Session 3: fresh TX sees current reality — 2 Foo, 1 Bar
      newGraph = openGraph();
      newGraph.tx().begin();
      assertEquals(2, newGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());
      assertEquals(1, newGraph.V().hasLabel("Userr").has("name", barValue).toList().size());

      // Session 1: Bar→Foo (TombstoneRID replaced with SnapshotMarkerRID)
      graph.tx().begin();
      graph.V(id1).property("name", fooValue).iterate();
      graph.tx().commit();

      // Session 3: repeatable-read — still sees 2 Foo, 1 Bar
      assertEquals(2, newGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());
      assertEquals(1, newGraph.V().hasLabel("Userr").has("name", barValue).toList().size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  private YTDBGraphTraversalSource openGraph() {
    return youTrackDB.openTraversal("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  @Test
  public void deleteInsertDeleteSameKey() throws Exception {
    // Exercises the tombstone → SnapshotMarkerRID → tombstone lifecycle.
    // TX1 creates a record, TX2 deletes it (tombstone), TX3 inserts a new
    // record with the same key (SnapshotMarkerRID over the new RID),
    // TX4 deletes that new record (tombstone again).
    // A fresh graph must see 0 records after all operations.

    String fooValue = "Foo";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // TX1: create a record
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var u1 = graph.addV("Userr").property("name", fooValue).next();
      var id1 = u1.id();
      graph.tx().commit();

      // Snapshot before any modifications
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      var before = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      assertEquals(1, before.size());

      // TX2: delete the record (creates tombstone in index)
      graph.tx().begin();
      graph.V(id1).drop().iterate();
      graph.V(id1).drop().iterate();

      graph.tx().commit();

      //   // TX3: insert a new record with the same key value
      //   graph.tx().begin();
      //   var u2 = graph.addV("Userr").property("name", fooValue).next();
      //   var id2 = u2.id();
      //   graph.tx().commit();

      // TX4: delete the new record
      graph.tx().begin();
      graph.V(id1).drop().iterate();
      graph.tx().commit();

      // Snapshot must still see the original 1 record
      var afterAll = snapshotGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      assertEquals(1, afterAll.size());
      snapshotGraph.tx().commit();

      // Fresh graph must see 0 records — everything was deleted
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph
          .V()
          .hasLabel("Userr")
          .has("name", fooValue)
          .toList();
      assertEquals(0, foos.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  /**
   * Regression test: put() replacing a live RecordId entry from a prior TX.
   *
   * Scenario: Three vertices with distinct names. A concurrent TX sets all names
   * to null, then reassigns them in a different order, then reassigns again.
   * interpretAsNonUnique collapses these changes so that some keys get a
   * standalone PUT for an RID that already has a committed live entry — the
   * engine's put() finds the prior TX's live RecordId and must preserve it in
   * the snapshot for concurrent readers.
   *
   * Pattern from DuplicateNonUniqueIndexChangesTxTest.testDuplicateNullsOnUpdate.
   */
  @Test
  public void liveRecordUpdates() throws Exception {

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // TX0: insert three vertices with distinct names
    var graph = openGraph();
    YTDBGraphTraversalSource snapshotGraph = null;
    try {
      graph.tx().begin();
      var v1 = graph.addV("Userr").property("name", "Name1").next();
      var id1 = v1.id();
      var v2 = graph.addV("Userr").property("name", "Name2").next();
      var id2 = v2.id();
      var v3 = graph.addV("Userr").property("name", "Name3").next();
      var id3 = v3.id();
      graph.tx().commit();

      // Open snapshot TX — must see 1 of each
      snapshotGraph = openGraph();
      snapshotGraph.tx().begin();
      assertEquals(1,
          snapshotGraph.V().hasLabel("Userr").has("name", "Name1").toList().size());
      assertEquals(1,
          snapshotGraph.V().hasLabel("Userr").has("name", "Name2").toList().size());
      assertEquals(1,
          snapshotGraph.V().hasLabel("Userr").has("name", "Name3").toList().size());

      // TX1: set all to null, then reassign, then reassign again.
      // This triggers interpretAsNonUnique to produce standalone PUTs for
      // keys that already have committed live entries.
      graph.tx().begin();
      graph.V(id1).property("name", null).iterate();
      graph.V(id2).property("name", null).iterate();
      graph.V(id3).property("name", null).iterate();

      graph.V(id1).property("name", "Name2").iterate();
      graph.V(id2).property("name", "Name1").iterate();
      graph.V(id3).property("name", "Name2").iterate();

      graph.V(id1).property("name", "Name1").iterate();
      graph.V(id2).property("name", "Name2").iterate();
      graph.tx().commit();

      // Snapshot must still see original state
      assertEquals(1,
          snapshotGraph.V().hasLabel("Userr").has("name", "Name1").toList().size());
      assertEquals(1,
          snapshotGraph.V().hasLabel("Userr").has("name", "Name2").toList().size());
      assertEquals(1,
          snapshotGraph.V().hasLabel("Userr").has("name", "Name3").toList().size());
      snapshotGraph.tx().commit();
    } finally {
      if (snapshotGraph != null)
        snapshotGraph.close();
      graph.close();
    }
  }

  /**
   * Regression: put() replacing a SnapshotMarkerRID must call addSnapshotPair.
   *
   * <p>Uses the storage API to call putRidIndexEntry directly (bypassing the
   * ClassIndexManager remove+put pairing) to create the scenario where put()
   * encounters a SnapshotMarkerRID from a prior TX's put(). Two consecutive
   * standalone PUTs for the same (key, rid): the first creates SnapshotMarkerRID
   * and correctly calls addSnapshotPair; the second finds SnapshotMarkerRID
   * but must also call addSnapshotPair to preserve the historical version.
   */
  @Test
  public void snapshotMarkerReplacedByPut_mustCreateSnapshotPair() throws Exception {
    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // TX-0: create a vertex with name="Foo"
    var graph = openGraph();
    try {
      graph.tx().begin();
      var v = graph.addV("Userr").property("name", "Foo").next();
      graph.tx().commit();

      var rid = (com.jetbrains.youtrackdb.internal.core.db.record.record.RID) v.id();

      // Disable cleanup so snapshot entries are not evicted
      db.getStorage().getContextConfiguration().setValue(
          GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, Integer.MAX_VALUE);

      var index = db.getIndex("IndexPropertyName");

      // TX-1: standalone PUT via Index.put() — bypasses ClassIndexManager's
      // remove+put pairing. put() finds committed RecordId → writes
      // SnapshotMarkerRID + addSnapshotPair (correct).
      long entriesBeforeTx1 = db.getStorage().getIndexesSnapshotEntriesCount().get();
      var tx1 = db.begin();
      index.put(tx1, "Foo", rid);
      tx1.commit();

      long entriesAfterTx1 = db.getStorage().getIndexesSnapshotEntriesCount().get();
      assertTrue("TX-1 standalone PUT must create snapshot entries",
          entriesAfterTx1 > entriesBeforeTx1);

      // TX-2: standalone PUT via Index.put() — put() finds SnapshotMarkerRID
      // from TX-1. Must also create snapshot entries.
      long entriesBefore = db.getStorage().getIndexesSnapshotEntriesCount().get();
      var tx2 = db.begin();
      index.put(tx2, "Foo", rid);
      tx2.commit();

      long entriesAfterTx2 = db.getStorage().getIndexesSnapshotEntriesCount().get();

      // TX-2 must have created 2 snapshot entries (one addSnapshotPair = 2 entries:
      // TombstoneRID at oldKey + RecordId guard at newKey). If the bug is present,
      // put() skips addSnapshotPair for SnapshotMarkerRID → 0 new entries.
      assertTrue("TX-2 must create snapshot entries (addSnapshotPair for SnapshotMarkerRID)",
          entriesAfterTx2 > entriesBefore);
    } finally {
      graph.close();
    }
  }

  /**
   * Regression: same-TX double put for a non-null key must not corrupt the index.
   *
   * <p>Scenario: TX-0 creates a vertex with name="Foo". TX-1 calls put("Foo", rid)
   * twice in the same TX (interpretAsNonUnique collapses them to a single PUT at
   * commit time). The engine's doPut guard must correctly handle the resulting
   * SnapshotMarkerRID — with the fix, the guard checks for both RecordId and
   * SnapshotMarkerRID, matching the single-value engine. After commit, the index
   * must contain exactly one entry and the snapshot must have entries for the
   * replaced RecordId.
   */
  @Test
  public void sameTxReput_nonNullKey_skipsWhenSnapshotMarkerRID() throws Exception {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // TX-0: create a vertex with name="Foo"
    var graph = openGraph();
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var v = graph.addV("Userr").property("name", "Foo").next();
      graph.tx().commit();

      var rid = (com.jetbrains.youtrackdb.internal.core.db.record.record.RID) v.id();

      // Disable cleanup so snapshot entries are not evicted
      db.getStorage().getContextConfiguration().setValue(
          GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, Integer.MAX_VALUE);

      var index = db.getIndex("IndexPropertyName");

      // TX-1: two puts for the same (key, rid) — collapsed to one engine call
      // at commit. The engine replaces the committed RecordId with
      // SnapshotMarkerRID and creates snapshot entries.
      long entriesBefore = db.getStorage().getIndexesSnapshotEntriesCount().get();
      var tx1 = db.begin();
      index.put(tx1, "Foo", rid);
      index.put(tx1, "Foo", rid);
      tx1.commit();

      long entriesAfterCommit = db.getStorage().getIndexesSnapshotEntriesCount().get();
      assertTrue("Commit must create snapshot entries (SnapshotMarkerRID replacement)",
          entriesAfterCommit > entriesBefore);

      // TX-2: second standalone PUT — finds SnapshotMarkerRID from TX-1.
      // The re-put guard (with the fix) recognises SnapshotMarkerRID at a
      // different version and proceeds to create snapshot entries.
      long entriesBeforeTx2 = db.getStorage().getIndexesSnapshotEntriesCount().get();
      var tx2 = db.begin();
      index.put(tx2, "Foo", rid);
      tx2.commit();

      long entriesAfterTx2 = db.getStorage().getIndexesSnapshotEntriesCount().get();
      assertTrue("TX-2 must create snapshot entries (addSnapshotPair for SnapshotMarkerRID)",
          entriesAfterTx2 > entriesBeforeTx2);

      // Final state: a fresh TX must see exactly 1 vertex via the index
      newGraph = openGraph();
      newGraph.tx().begin();
      var foos = newGraph.V().hasLabel("Userr").has("name", "Foo").toList();
      assertEquals(1, foos.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      graph.close();
    }
  }

  /**
   * Regression: same-TX double put for a null key must not corrupt the index.
   * Exercises the nullTree + nullIndexesSnapshot path.
   *
   * <p>Mirrors {@link #sameTxReput_nonNullKey_skipsWhenSnapshotMarkerRID()} but
   * uses a null property value so the entry goes into the null B-tree.
   */
  @Test
  public void sameTxReput_nullKey_skipsWhenSnapshotMarkerRID() throws Exception {
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.NOTUNIQUE, "name");

    // TX-0: create a vertex with name=null
    var graph = openGraph();
    YTDBGraphTraversalSource newGraph = null;
    try {
      graph.tx().begin();
      var v = graph.addV("Userr").next();
      graph.tx().commit();

      var rid = (com.jetbrains.youtrackdb.internal.core.db.record.record.RID) v.id();

      // Disable cleanup so snapshot entries are not evicted
      db.getStorage().getContextConfiguration().setValue(
          GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, Integer.MAX_VALUE);

      var index = db.getIndex("IndexPropertyName");

      // TX-1: two puts for the same (null, rid) — collapsed to one engine call.
      long entriesBefore = db.getStorage().getIndexesSnapshotEntriesCount().get();
      var tx1 = db.begin();
      index.put(tx1, null, rid);
      index.put(tx1, null, rid);
      tx1.commit();

      long entriesAfterCommit = db.getStorage().getIndexesSnapshotEntriesCount().get();
      assertTrue("Commit must create null-key snapshot entries",
          entriesAfterCommit > entriesBefore);

      // TX-2: PUT encountering SnapshotMarkerRID from TX-1 in the nullTree.
      long entriesBeforeTx2 = db.getStorage().getIndexesSnapshotEntriesCount().get();
      var tx2 = db.begin();
      index.put(tx2, null, rid);
      tx2.commit();

      long entriesAfterTx2 = db.getStorage().getIndexesSnapshotEntriesCount().get();
      assertTrue("TX-2 must create null-key snapshot entries",
          entriesAfterTx2 > entriesBeforeTx2);

      // Final state: a fresh TX must see the vertex
      newGraph = openGraph();
      newGraph.tx().begin();
      var all = newGraph.V().hasLabel("Userr").toList();
      assertEquals(1, all.size());
      newGraph.tx().commit();
    } finally {
      if (newGraph != null)
        newGraph.close();
      graph.close();
    }
  }
}
