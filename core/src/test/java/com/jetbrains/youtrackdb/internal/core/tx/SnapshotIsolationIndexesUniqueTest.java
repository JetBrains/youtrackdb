package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
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
 * Snapshot-isolation tests for UNIQUE indexes. Exercises BTreeSingleValueIndexEngine via Gremlin
 * traversals.
 */
public class SnapshotIsolationIndexesUniqueTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @Test
  public void noVisibilityForUpdates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();

    // start repeatable-read snapshot TX
    var snapshotGraph = openGraph();

    snapshotGraph.tx().begin();
    var beforeInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();

    assertEquals(1, beforeInsert.size());

    // update Foo -> Bar
    graph.tx().begin();
    graph
        .V(id1)
        .property("name", barValue)
        .iterate();
    graph.tx().commit();

    var afterInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();

    assertEquals(1, afterInsert.size());
    snapshotGraph.tx().commit();

    // New graph must see final result
    // ==========================================================
    var newGraph = openGraph();

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

    assertEquals(0, foos.size());
    assertEquals(1, bars.size()); // 1 updated
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  @Test
  public void noVisibilityForUpdates_MultipleIndexes() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createProperty("surname", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name", "surname");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).property("surname", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();

    // start repeatable-read snapshot TX
    var snapshotGraph = openGraph();

    snapshotGraph.tx().begin();
    var beforeInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();

    assertEquals(1, beforeInsert.size());

    // update Foo -> Bar
    graph.tx().begin();
    graph
        .V(id1)
        .property("name", barValue)
        .property("surname", barValue)
        .iterate();
    graph.tx().commit();

    var afterInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .has("surname", fooValue)
        .toList();

    assertEquals(1, afterInsert.size());
    snapshotGraph.tx().commit();

    // New graph must see final values
    // ==========================================================
    var newGraph = openGraph();

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

    assertEquals(0, foos.size()); // 2 old untouched
    assertEquals(1, bars.size()); // 1 updated
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  @Test
  public void noVisibilityForTXBeforeInsertAndAfterUpdate() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with UNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graphBeforeInsert = openGraph();
    graphBeforeInsert.tx().begin();
    var beforeInsertFoo = graphBeforeInsert
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();

    assertEquals(0, beforeInsertFoo.size());

    // create record
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
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
    var newGraph = openGraph();
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

    assertEquals(0, foos.size());
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

    graphBeforeInsert.close();
    graph.close();
    newGraph.close();
  }

  @Test
  public void visibleABAUpdates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    // Schema with NOTUNIQUE index
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    // create MULTIPLE records with the same property value
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();

    // start repeatable-read snapshot TX
    var snapshotGraph = openGraph();

    snapshotGraph.tx().begin();
    var beforeInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();

    assertEquals(1, beforeInsert.size());

    // update Foo -> Bar
    graph.tx().begin();
    graph
        .V(id1)
        .property("name", barValue)
        .iterate();
    graph.tx().commit();

    // start repeatable-read snapshot TX for Bar
    var barGraph = openGraph();
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

    var afterInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();

    assertEquals(1, afterInsert.size());
    snapshotGraph.tx().commit();

    // New graph must see final reality
    // ==========================================================
    var newGraph = openGraph();

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
    assertEquals(0, bars.size());
    newGraph.tx().commit();

    var barRepeatable = barGraph
        .V()
        .hasLabel("Userr")
        .has("name", barValue)
        .toList();

    assertEquals(1, barRepeatable.size());
    barGraph.tx().commit();

    barGraph.close();
    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Phantom insert is invisible to the snapshot.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot: has(name, Foo) → 1
   *   Concurrent: insert name="Foo2"
   *   Snapshot: V().hasLabel("Userr") → still 1
   *   Fresh: V().hasLabel("Userr") → 2
   * </pre>
   */
  @Test
  public void noVisibilityForPhantoms() throws Exception {

    String fooValue = "Foo";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("name", fooValue).next();
    graph.tx().commit();

    // start repeatable-read snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var beforeInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();

    assertEquals(1, beforeInsert.size());

    // insert NEW record with different unique key (phantom candidate)
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo2").next();
    graph.tx().commit();

    // snapshot must not see the new record
    var afterInsert = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", "Foo2")
        .toList();

    assertEquals(0, afterInsert.size());

    // snapshot still sees original
    var fooAfter = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();
    assertEquals(1, fooAfter.size());
    snapshotGraph.tx().commit();

    // Fresh graph sees both via index
    var newGraph = openGraph();
    newGraph.tx().begin();
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Foo2").toList().size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Update of a single record (Foo→Bar) is invisible to snapshot; fresh TX sees updated state.
   * Uses multiple records with different keys committed in separate TXs.
   */
  @Test
  public void visibleMultipleVersionsUpdates() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    // create records in separate TXs (different versions in the BTree)
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo2").next();
    graph.tx().commit();

    // start repeatable-read snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var beforeUpdate = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();
    assertEquals(1, beforeUpdate.size());

    // update Foo -> Bar
    graph.tx().begin();
    graph
        .V(id1)
        .property("name", barValue)
        .iterate();
    graph.tx().commit();

    // snapshot still sees Foo
    var afterUpdate = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();
    assertEquals(1, afterUpdate.size());

    // snapshot does not see Bar
    var barInSnapshot = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", barValue)
        .toList();
    assertEquals(0, barInSnapshot.size());
    snapshotGraph.tx().commit();

    // Fresh graph sees final state
    var newGraph = openGraph();
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

    assertEquals(0, foos.size());
    assertEquals(1, bars.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Snapshot isolation with ABA pattern plus a concurrent insert.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot: has(name, Foo) → 1
   *   Concurrent TX: update Foo→Bar AND insert "Foo2"
   *   Snapshot: Foo still 1, Bar 0, Foo2 0
   *   Fresh: Foo 0, Bar 1, Foo2 1
   * </pre>
   */
  @Test
  public void snapshotIsolationABA() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();

    // start repeatable-read snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var fooBefore = snapshotGraph
        .V()
        .has("name", fooValue)
        .toList();
    assertEquals(1, fooBefore.size());

    // Concurrent TX: update Foo→Bar and insert new Foo2
    graph.tx().begin();
    graph
        .V(id1)
        .property("name", barValue)
        .iterate();
    graph.addV("Userr").property("name", "Foo2").next();
    graph.tx().commit();

    // snapshot must NOT see changes
    var fooAfter = snapshotGraph
        .V()
        .has("name", fooValue)
        .toList();
    var barAfter = snapshotGraph
        .V()
        .has("name", barValue)
        .toList();
    var foo2After = snapshotGraph
        .V()
        .has("name", "Foo2")
        .toList();

    assertEquals(1, fooAfter.size());
    assertEquals(0, barAfter.size());
    assertEquals(0, foo2After.size());
    snapshotGraph.tx().commit();

    // Fresh graph sees final reality
    var newGraph = openGraph();
    newGraph.tx().begin();
    var foos = newGraph
        .V()
        .has("name", fooValue)
        .toList();
    var bars = newGraph
        .V()
        .has("name", barValue)
        .toList();
    var foo2s = newGraph
        .V()
        .has("name", "Foo2")
        .toList();

    assertEquals(0, foos.size());
    assertEquals(1, bars.size());
    assertEquals(1, foo2s.size());
    newGraph.tx().commit();

    graph.close();
    newGraph.close();
  }

  /**
   * Multiple ABA cycles with SnapshotMarkerRID replacement.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot: has(name, Foo) → 1
   *   TX1: Foo→Bar  (TombstoneRID for Foo)
   *   TX2: Bar→Foo  (SnapshotMarkerRID for Foo)
   *   TX3: Foo→Bar  (SnapshotMarkerRID replaced on TombstoneRID)
   *   Snapshot: Foo still 1, Bar 0
   *   Fresh: Foo 0, Bar 1
   *   TX4: Bar→Foo
   *   newGraph snapshot: Foo 0, Bar 1 (repeatable read)
   * </pre>
   */
  @Test
  public void removedSnapshotMarkerRID() throws Exception {

    String fooValue = "Foo";
    String barValue = "Bar";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();

    // start repeatable-read snapshot
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var fooBefore = snapshotGraph
        .V()
        .has("name", fooValue)
        .toList();
    assertEquals(1, fooBefore.size());

    // TX1: Foo → Bar
    graph.tx().begin();
    graph.V(id1).property("name", barValue).iterate();
    graph.tx().commit();

    // TX2: Bar → Foo
    graph.tx().begin();
    graph.V(id1).property("name", fooValue).iterate();
    graph.tx().commit();

    // TX3: Foo → Bar
    graph.tx().begin();
    graph.V(id1).property("name", barValue).iterate();
    graph.tx().commit();

    // snapshot must NOT see changes
    var fooAfter = snapshotGraph
        .V()
        .has("name", fooValue)
        .toList();
    var barAfter = snapshotGraph
        .V()
        .has("name", barValue)
        .toList();

    assertEquals(1, fooAfter.size());
    assertEquals(0, barAfter.size());
    snapshotGraph.tx().commit();

    // Fresh graph sees final state: Bar
    var newGraph = openGraph();
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

    assertEquals(0, foos.size());
    assertEquals(1, bars.size());

    // TX4: Bar → Foo
    graph.tx().begin();
    graph.V(id1).property("name", fooValue).iterate();
    graph.tx().commit();

    // newGraph repeatable read: still sees Bar
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

    assertEquals(0, foosRep.size());
    assertEquals(1, barsRep.size());
    newGraph.tx().commit();

    graph.close();
    newGraph.close();
  }

  /**
   * Delete is invisible to the snapshot.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot: has(name, Foo) → 1
   *   Concurrent: delete
   *   Snapshot: has(name, Foo) → still 1
   *   Fresh: has(name, Foo) → 0
   * </pre>
   */
  @Test
  public void noVisibilityForDeletes() throws Exception {

    String fooValue = "Foo";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();

    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();
    assertEquals(1, before.size());

    // delete in concurrent TX
    graph.tx().begin();
    graph.V(id1).drop().iterate();
    graph.tx().commit();

    // snapshot still sees it
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();
    assertEquals(1, after.size());
    snapshotGraph.tx().commit();

    // fresh sees 0
    var newGraph = openGraph();
    newGraph.tx().begin();
    var foos = newGraph
        .V()
        .hasLabel("Userr")
        .has("name", fooValue)
        .toList();
    assertEquals(0, foos.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Delete all records is invisible to the snapshot.
   * <pre>
   *   Initial: name = {"Foo", "Bar"}
   *   Snapshot: V().hasLabel("Userr") → 2
   *   Concurrent: delete all
   *   Snapshot: V().hasLabel("Userr") → still 2
   *   Fresh: 0
   * </pre>
   */
  @Test
  public void noVisibilityForMultipleDeletes() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo").next();
    graph.addV("Userr").property("name", "Bar").next();
    graph.tx().commit();

    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());

    // delete all in concurrent TX
    graph.tx().begin();
    graph.V().hasLabel("Userr").drop().iterate();
    graph.tx().commit();

    // snapshot still sees both via index
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    snapshotGraph.tx().commit();

    // fresh sees 0
    var newGraph = openGraph();
    newGraph.tx().begin();
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Delete one record and insert a new one with a different key — invisible to snapshot.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot: has(name, Foo) → 1
   *   Concurrent: delete Foo, insert "Bar"
   *   Snapshot: Foo → 1, Bar → 0
   *   Fresh: Foo → 0, Bar → 1
   * </pre>
   */
  @Test
  public void noVisibilityForDeleteAndInsert() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", "Foo")
        .toList();
    assertEquals(1, before.size());

    // delete Foo, insert Bar
    graph.tx().begin();
    graph.V(id1).drop().iterate();
    graph.addV("Userr").property("name", "Bar").next();
    graph.tx().commit();

    // snapshot sees original Foo, not Bar
    var fooAfter = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", "Foo")
        .toList();
    var barAfter = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", "Bar")
        .toList();
    assertEquals(1, fooAfter.size());
    assertEquals(0, barAfter.size());
    snapshotGraph.tx().commit();

    // fresh sees Bar only
    var newGraph = openGraph();
    newGraph.tx().begin();
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Update one record and delete another — invisible to snapshot.
   * <pre>
   *   Initial: name = {"Foo", "Bar"}
   *   Snapshot: Foo → 1, Bar → 1
   *   Concurrent: update Foo→Baz, delete Bar
   *   Snapshot: Foo → 1, Bar → 1, Baz → 0
   *   Fresh: Foo → 0, Bar → 0, Baz → 1
   * </pre>
   */
  @Test
  public void noVisibilityForUpdateAndDelete() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var u2 = graph.addV("Userr").property("name", "Bar").next();
    var id1 = u1.id();
    var id2 = u2.id();
    graph.tx().commit();

    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());

    // update Foo→Baz, delete Bar
    graph.tx().begin();
    graph.V(id1).property("name", "Baz").iterate();
    graph.V(id2).drop().iterate();
    graph.tx().commit();

    // snapshot still sees original
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    assertEquals(0, snapshotGraph.V().hasLabel("Userr").has("name", "Baz").toList().size());
    snapshotGraph.tx().commit();

    // fresh sees final state
    var newGraph = openGraph();
    newGraph.tx().begin();
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Baz").toList().size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Sequential concurrent TXs: snapshot sees none of the changes.
   * <pre>
   *   Initial: name = {"Foo", "Bar"}
   *   Snapshot: Foo → 1, Bar → 1
   *   TX1: update Foo→Baz
   *   TX2: update Bar→Qux, insert "New"
   *   Snapshot: Foo → 1, Bar → 1, Baz → 0, Qux → 0, New → 0
   *   Fresh: Foo → 0, Bar → 0, Baz → 1, Qux → 1, New → 1
   * </pre>
   */
  @Test
  public void noVisibilityForSequentialConcurrentTXs() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var u2 = graph.addV("Userr").property("name", "Bar").next();
    var id1 = u1.id();
    var id2 = u2.id();
    graph.tx().commit();

    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());

    // TX1: Foo→Baz
    graph.tx().begin();
    graph.V(id1).property("name", "Baz").iterate();
    graph.tx().commit();

    // TX2: Bar→Qux, insert New
    graph.tx().begin();
    graph.V(id2).property("name", "Qux").iterate();
    graph.addV("Userr").property("name", "New").next();
    graph.tx().commit();

    // snapshot sees none of the changes
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    assertEquals(0, snapshotGraph.V().hasLabel("Userr").has("name", "Baz").toList().size());
    assertEquals(0, snapshotGraph.V().hasLabel("Userr").has("name", "Qux").toList().size());
    assertEquals(0, snapshotGraph.V().hasLabel("Userr").has("name", "New").toList().size());
    snapshotGraph.tx().commit();

    // fresh sees final state
    var newGraph = openGraph();
    newGraph.tx().begin();
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Baz").toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Qux").toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "New").toList().size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Delete and reinsert with the same key value — invisible to snapshot.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot: has(name, Foo) → 1
   *   Concurrent: delete Foo, insert new Foo
   *   Snapshot: has(name, Foo) → still 1
   *   Fresh: has(name, Foo) → 1 (new record)
   * </pre>
   */
  @Test
  public void noVisibilityForDeleteAndReinsertSameValue() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", "Foo")
        .toList();
    assertEquals(1, before.size());

    // delete and reinsert with same key
    graph.tx().begin();
    graph.V(id1).drop().iterate();
    graph.addV("Userr").property("name", "Foo").next();
    graph.tx().commit();

    // snapshot still sees 1
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("name", "Foo")
        .toList();
    assertEquals(1, after.size());
    snapshotGraph.tx().commit();

    // fresh also sees 1 (the new one)
    var newGraph = openGraph();
    newGraph.tx().begin();
    var foos = newGraph
        .V()
        .hasLabel("Userr")
        .has("name", "Foo")
        .toList();
    assertEquals(1, foos.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Snapshot sees its own writes but not concurrent external writes.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot TX: inserts "Bar"
   *   Concurrent TX: inserts "Baz"
   *   Snapshot: Foo → 1, Bar → 1 (own), Baz → 0
   *   Fresh: Foo → 1, Bar → 1, Baz → 1
   * </pre>
   */
  @Test
  public void snapshotSeesOwnIndexedKey() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo").next();
    graph.tx().commit();

    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());

    // snapshot inserts its own Bar
    snapshotGraph.addV("Userr").property("name", "Bar").next();

    // concurrent TX inserts Baz
    graph.tx().begin();
    graph.addV("Userr").property("name", "Baz").next();
    graph.tx().commit();

    // snapshot sees Foo (1), own Bar (1), not external Baz (0)
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    assertEquals(0, snapshotGraph.V().hasLabel("Userr").has("name", "Baz").toList().size());
    snapshotGraph.tx().commit();

    // fresh sees all 3
    var newGraph = openGraph();
    newGraph.tx().begin();
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Bar").toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", "Baz").toList().size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /**
   * Two snapshots started at different times see different states.
   * <pre>
   *   Initial: name = "Foo"
   *   Snapshot1: Foo → 1
   *   Concurrent: update Foo→Bar
   *   Snapshot2: Foo → 0, Bar → 1
   *   Snapshot1: still Foo → 1, Bar → 0
   * </pre>
   */
  @Test
  public void twoSnapshotsSeeDifferentStates() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // Snapshot1
    var snapshot1 = openGraph();
    snapshot1.tx().begin();
    assertEquals(1, snapshot1.V().hasLabel("Userr").has("name", "Foo").toList().size());

    // Concurrent: Foo→Bar
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // Snapshot2 (started after update)
    var snapshot2 = openGraph();
    snapshot2.tx().begin();
    assertEquals(0, snapshot2.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(1, snapshot2.V().hasLabel("Userr").has("name", "Bar").toList().size());

    // Snapshot1 still sees original
    assertEquals(1, snapshot1.V().hasLabel("Userr").has("name", "Foo").toList().size());
    assertEquals(0, snapshot1.V().hasLabel("Userr").has("name", "Bar").toList().size());

    snapshot1.tx().commit();
    snapshot2.tx().commit();

    snapshot1.close();
    snapshot2.close();
    graph.close();
  }

  /**
   * Duplicate key insert when the previous entry is a TombstoneRID.
   *
   * <pre>
   *   TX1: insert vertex with name="Foo"
   *   TX2: delete the vertex → index entry becomes TombstoneRID
   *   TX3: insert NEW vertex with name="Foo" → should SUCCEED
   *        (TombstoneRID means key is logically free)
   *   Fresh: has(name, Foo) → 1 (the new record)
   * </pre>
   */
  @Test
  public void duplicateKeyAfterTombstone_shouldSucceed() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    // TX1: create vertex with name="Foo"
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // TX2: delete the vertex → TombstoneRID in the index for key "Foo"
    graph.tx().begin();
    graph.V(id1).drop().iterate();
    graph.tx().commit();

    // TX3: insert a NEW vertex with the same key "Foo"
    // The index entry is a TombstoneRID → logically deleted → key is free
    // This should NOT throw RecordDuplicatedException
    graph.tx().begin();
    graph.addV("Userr").property("name", "Foo").next();
    graph.tx().commit();

    // Verify: fresh graph sees exactly 1 record with name="Foo"
    var newGraph = openGraph();
    newGraph.tx().begin();
    var foos = newGraph.V().hasLabel("Userr").has("name", "Foo").toList();
    assertEquals(1, foos.size());
    newGraph.tx().commit();

    newGraph.close();
    graph.close();
  }

  /**
   * Duplicate key insert when the previous entry is a SnapshotMarkerRID.
   *
   * <pre>
   *   TX1: insert vertex with name="Foo"
   *   TX2: update name Foo→Bar → TombstoneRID for "Foo" in index
   *   TX3: update name Bar→Foo → SnapshotMarkerRID for "Foo" in index
   *        (re-insert after delete creates a SnapshotMarkerRID)
   *   TX4: insert NEW vertex with name="Foo" → should FAIL with
   *        RecordDuplicatedException (SnapshotMarkerRID means key is
   *        occupied by a live record)
   * </pre>
   */
  @Test
  public void duplicateKeyAfterSnapshotMarker_shouldThrowDuplicate() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    // TX1: create vertex with name="Foo"
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", "Foo").next();
    var id1 = u1.id();
    graph.tx().commit();

    // TX2: update Foo → Bar
    // Index: remove("Foo") → TombstoneRID, put("Bar") → live
    graph.tx().begin();
    graph.V(id1).property("name", "Bar").iterate();
    graph.tx().commit();

    // TX3: update Bar → Foo
    // Index: remove("Bar") → TombstoneRID, put("Foo") → SnapshotMarkerRID
    // because the previous "Foo" entry was a TombstoneRID (re-insert)
    graph.tx().begin();
    graph.V(id1).property("name", "Foo").iterate();
    graph.tx().commit();

    // TX4: insert a NEW vertex with the same key "Foo"
    // The index entry is a SnapshotMarkerRID → logically alive → key is
    // occupied. This MUST throw RecordDuplicatedException.
    try {
      graph.tx().begin();
      graph.addV("Userr").property("name", "Foo").next();
      graph.tx().commit();
      fail("Expected RecordDuplicatedException: key 'Foo' is occupied "
          + "by a live SnapshotMarkerRID entry");
    } catch (RecordDuplicatedException e) {
      // expected — the key is occupied
    }

    // Verify: fresh graph sees exactly 1 record with name="Foo" (the original)
    var newGraph = openGraph();
    newGraph.tx().begin();
    var foos = newGraph.V().hasLabel("Userr").has("name", "Foo").toList();
    assertEquals(1, foos.size());
    newGraph.tx().commit();

    newGraph.close();
    graph.close();
  }

  /**
   * Exercises SnapshotMarkerRID lifecycle through repeated ABA updates on a UNIQUE index.
   *
   * <p>Sequence: create 1 Foo record → snapshot TX sees 1 Foo → concurrent TX does Foo→Bar
   * (creates TombstoneRID), Bar→Foo (creates SnapshotMarkerRID), Foo→Bar (replaces
   * SnapshotMarkerRID with TombstoneRID) → snapshot TX still sees 1 Foo / 0 Bar → fresh TX sees
   * 0 Foo / 1 Bar → another concurrent TX does Bar→Foo (replaces TombstoneRID with
   * SnapshotMarkerRID) → the fresh TX still sees its snapshot (0 Foo / 1 Bar).
   */
  @Test
  public void snapshotMarkerRIDLifecycle() throws Exception {
    String fooValue = "Foo";
    String barValue = "Bar";

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("name", PropertyType.STRING);
    userSchema.createIndex("IndexPropertyName", INDEX_TYPE.UNIQUE, "name");

    // Session 1: create 1 record with name=Foo
    var graph = openGraph();
    graph.tx().begin();
    var u1 = graph.addV("Userr").property("name", fooValue).next();
    var id1 = u1.id();
    graph.tx().commit();

    // Session 2: start repeatable-read snapshot — sees 1 Foo
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());

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

    // Session 2: snapshot must NOT see any changes — still 1 Foo, 0 Bar
    assertEquals(1, snapshotGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());
    assertEquals(0, snapshotGraph.V().hasLabel("Userr").has("name", barValue).toList().size());
    snapshotGraph.tx().commit();

    // Session 3: fresh TX sees current reality — 0 Foo, 1 Bar
    var newGraph = openGraph();
    newGraph.tx().begin();
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", barValue).toList().size());

    // Session 1: Bar→Foo (TombstoneRID replaced with SnapshotMarkerRID)
    graph.tx().begin();
    graph.V(id1).property("name", fooValue).iterate();
    graph.tx().commit();

    // Session 3: repeatable-read — still sees 0 Foo, 1 Bar
    assertEquals(0, newGraph.V().hasLabel("Userr").has("name", fooValue).toList().size());
    assertEquals(1, newGraph.V().hasLabel("Userr").has("name", barValue).toList().size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
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
