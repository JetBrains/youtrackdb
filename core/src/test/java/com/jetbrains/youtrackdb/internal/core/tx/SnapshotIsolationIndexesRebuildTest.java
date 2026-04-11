package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Test;

/**
 * Verifies that REBUILD INDEX * works correctly with snapshot-isolation indexes.
 *
 * <p>After rebuilding all indexes, queries that use the index must still find
 * previously-inserted records. This test uses the raw database session API
 * (not Gremlin) to isolate whether the problem is in the storage/index layer
 * or in the Gremlin transaction layer.
 */
public class SnapshotIsolationIndexesRebuildTest extends SnapshotIsolationIndexesTestBase {

  /**
   * Insert a record, rebuild all indexes with REBUILD INDEX *, then verify
   * that a query using the index still finds the record.
   *
   * <p>Regression test for the issue where the Gremlin API returns empty
   * results after REBUILD INDEX * on the first transaction.
   */
  @Test
  public void rebuildAllIndexes_queryFindsRecordAfterRebuild() {
    // Create schema with an indexed property.
    var schema = db.getMetadata().getSchema();
    var clazz = schema.createClass("RebuildTestPerson");
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(
        "RebuildTestPerson.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Insert one record inside a transaction.
    db.executeInTx(tx -> {
      var entity = db.newEntity("RebuildTestPerson");
      entity.setProperty("name", "Alice");
    });

    // Rebuild all indexes (DDL command, runs outside a transaction).
    db.command("REBUILD INDEX *");

    // Query using the index — the record must be found.
    // query() auto-opens a read-only transaction internally.
    try (var rs = db.query("SELECT FROM RebuildTestPerson WHERE name = 'Alice'")) {
      assertTrue(
          "Record should be found by index query after REBUILD INDEX *",
          rs.hasNext());
      var result = rs.next();
      assertEquals("Alice", result.getProperty("name"));
    }
  }

  /**
   * Same as {@link #rebuildAllIndexes_queryFindsRecordAfterRebuild()} but
   * opens a fresh session after the rebuild — simulating what happens when a
   * new Gremlin traversal source is opened post-rebuild.
   */
  @Test
  public void rebuildAllIndexes_newSessionFindsRecordAfterRebuild() {
    // Create schema with an indexed property.
    var schema = db.getMetadata().getSchema();
    var clazz = schema.createClass("RebuildTestPerson2");
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(
        "RebuildTestPerson2.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Insert one record inside a transaction.
    db.executeInTx(tx -> {
      var entity = db.newEntity("RebuildTestPerson2");
      entity.setProperty("name", "Bob");
    });

    // Rebuild all indexes.
    db.command("REBUILD INDEX *");

    // Open a brand-new session (simulates opening a new Gremlin traversal source).
    try (DatabaseSessionEmbedded freshSession =
        youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD)) {
      try (var rs =
          freshSession.query("SELECT FROM RebuildTestPerson2 WHERE name = 'Bob'")) {
        assertTrue(
            "Fresh session should find the record after REBUILD INDEX *",
            rs.hasNext());
        var result = rs.next();
        assertEquals("Bob", result.getProperty("name"));
      }
    }
  }

  /**
   * Reproduces the exact Gremlin flow: DDL via g.command() runs on a schema
   * session from the pool, then g.computeInTx() opens a transaction on a
   * different session. This is where the bug manifests — the first
   * computeInTx after REBUILD INDEX * returns empty results.
   */
  @Test
  public void rebuildAllIndexes_gremlinApi_queryFindsRecordAfterRebuild() {
    // Use Gremlin API exclusively — matches the DocValidationTest pattern
    var g = openGraph();
    g.command("CREATE CLASS RbGremlinTest EXTENDS V");
    g.command("CREATE PROPERTY RbGremlinTest.tag STRING");
    g.command("CREATE INDEX RbGremlinTest.tag ON RbGremlinTest (tag) NOTUNIQUE");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX RbGremlinTest SET tag = 'hello'").iterate();
    });
    g.close();

    // Reopen and rebuild all indexes (exactly like DocValidationTest)
    var g2 = openGraph();
    g2.command("REBUILD INDEX *");

    // The very first computeInTx after rebuild must find the record
    var results = g2.computeInTx(
        tx -> tx.yql("SELECT FROM RbGremlinTest WHERE tag = 'hello'").toList());
    g2.close();

    assertEquals("Query via index after REBUILD INDEX * must find the record",
        1, results.size());
  }

  /**
   * Verifies REBUILD INDEX * with a UNIQUE index. A unique index has a
   * different internal engine path (single-value vs multi-value B-tree),
   * so it is important to test both.
   */
  @Test
  public void rebuildAllIndexes_uniqueIndex_queryFindsRecordAfterRebuild() {
    // Create schema with a unique indexed property.
    var schema = db.getMetadata().getSchema();
    var clazz = schema.createClass("RebuildTestUniq");
    clazz.createProperty("code", PropertyType.STRING);
    clazz.createIndex(
        "RebuildTestUniq.code", SchemaClass.INDEX_TYPE.UNIQUE, "code");

    // Insert one record.
    db.executeInTx(tx -> {
      var entity = db.newEntity("RebuildTestUniq");
      entity.setProperty("code", "X42");
    });

    // Rebuild all indexes.
    db.command("REBUILD INDEX *");

    // Query using the unique index.
    try (var rs = db.query("SELECT FROM RebuildTestUniq WHERE code = 'X42'")) {
      assertTrue(
          "Record should be found via unique index after REBUILD INDEX *",
          rs.hasNext());
      var result = rs.next();
      assertEquals("X42", result.getProperty("code"));
    }
  }
}
