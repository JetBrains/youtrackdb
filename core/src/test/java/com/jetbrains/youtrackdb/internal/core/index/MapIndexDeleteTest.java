package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that map index entries are properly cleaned up when records are deleted.
 * Verifies that both "by key" and "by value" map indexes are maintained correctly
 * under snapshot isolation.
 */
public class MapIndexDeleteTest extends DbTestBase {

  @Test
  public void testMapIndexEntriesRemovedOnRecordDeleteViaSQL() {
    var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("id", PropertyType.STRING);
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    mapper.createIndex("mapKeyIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");
    mapper.createIndex("mapValueIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "intMap by value");

    // Insert a record with a map
    session.begin();
    var doc = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);
    doc.setProperty("intMap", map);
    session.commit();

    // Verify index has 2 entries
    var keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");
    var valueIndex = session.getSharedContext().getIndexManager().getIndex("mapValueIdx");

    session.begin();
    Assert.assertEquals(2, keyIndex.size(session));
    Assert.assertEquals(2, valueIndex.size(session));
    session.rollback();

    // Delete the record via SQL
    session.begin();
    session.execute("delete from Mapper").close();
    session.commit();

    // Verify index entries are cleaned up
    session.begin();
    Assert.assertEquals(
        "Map key index entries should be removed after record deletion",
        0, keyIndex.size(session));
    Assert.assertEquals(
        "Map value index entries should be removed after record deletion",
        0, valueIndex.size(session));
    session.rollback();
  }

  @Test
  public void testMapIndexEntriesRemovedOnRecordDeleteViaAPI() {
    var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("id", PropertyType.STRING);
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    mapper.createIndex("mapKeyIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");
    mapper.createIndex("mapValueIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "intMap by value");

    // Insert a record with a map
    session.begin();
    var doc = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);
    doc.setProperty("intMap", map);
    session.commit();

    // Verify index has 2 entries
    var keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");
    var valueIndex = session.getSharedContext().getIndexManager().getIndex("mapValueIdx");

    session.begin();
    Assert.assertEquals(2, keyIndex.size(session));
    Assert.assertEquals(2, valueIndex.size(session));
    session.rollback();

    // Delete the record via API
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.load(doc));
    session.commit();

    // Verify index entries are cleaned up
    session.begin();
    Assert.assertEquals(
        "Map key index entries should be removed after record deletion via API",
        0, keyIndex.size(session));
    Assert.assertEquals(
        "Map value index entries should be removed after record deletion via API",
        0, valueIndex.size(session));
    session.rollback();
  }

  @Test
  public void testMapIndexDeleteAndReinsertWithSameSession() {
    // Reproduces the exact pattern from the tests module MapIndexTest:
    // 1. Create schema (once)
    // 2. Insert data, commit
    // 3. Read index size (should be 2)
    // 4. Delete all records via SQL, commit
    // 5. Insert new data, commit
    // 6. Read index size (should be 2, not 4)

    var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("id", PropertyType.STRING);
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    mapper.createIndex("mapKeyIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");
    mapper.createIndex("mapValueIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "intMap by value");

    var keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");
    var valueIndex = session.getSharedContext().getIndexManager().getIndex("mapValueIdx");

    // First insert (simulates testIndexMap)
    session.begin();
    var doc1 = session.newEntity("Mapper");
    var map1 = session.newEmbeddedMap();
    map1.put("key1", 10);
    map1.put("key2", 20);
    doc1.setProperty("intMap", map1);
    session.commit();

    session.begin();
    Assert.assertEquals(2, keyIndex.size(session));
    Assert.assertEquals(2, valueIndex.size(session));
    session.rollback();

    // Delete all (simulates afterMethod)
    session.begin();
    session.execute("delete from Mapper").close();
    session.commit();

    // Verify cleanup
    session.begin();
    Assert.assertEquals(
        "Key index should be empty after SQL delete", 0, keyIndex.size(session));
    Assert.assertEquals(
        "Value index should be empty after SQL delete", 0, valueIndex.size(session));
    session.rollback();

    // Second insert (simulates testIndexMapInTx)
    session.begin();
    var doc2 = session.newEntity("Mapper");
    var map2 = session.newEmbeddedMap();
    map2.put("key1", 10);
    map2.put("key2", 20);
    doc2.setProperty("intMap", map2);
    session.commit();

    // Should be exactly 2, not 4
    session.begin();
    Assert.assertEquals(
        "Key index should have exactly 2 entries after re-insert",
        2, keyIndex.size(session));
    Assert.assertEquals(
        "Value index should have exactly 2 entries after re-insert",
        2, valueIndex.size(session));
    session.rollback();
  }

  @Test
  public void testMapIndexDeleteWithHeavySchemaAndSessionCloseReopen() {
    // Reproduce the tests module environment: create many schema classes
    // BEFORE creating the Mapper class, to ensure the database has a complex
    // schema similar to the shared test database
    for (var i = 0; i < 20; i++) {
      var cls = session.createClass("BulkClass" + i);
      cls.createProperty("name", PropertyType.STRING);
      cls.createProperty("value", PropertyType.INTEGER);
      cls.createIndex("bulkIdx" + i, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    }

    var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("id", PropertyType.STRING);
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    mapper.createIndex("mapKeyIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");
    mapper.createIndex("mapValueIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "intMap by value");

    // Insert some data into other classes to create storage activity
    for (var i = 0; i < 20; i++) {
      session.begin();
      for (var j = 0; j < 10; j++) {
        var entity = session.newEntity("BulkClass" + i);
        entity.setProperty("name", "name" + j);
        entity.setProperty("value", j);
      }
      session.commit();
    }

    var keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");
    var valueIndex = session.getSharedContext().getIndexManager().getIndex("mapValueIdx");

    // First insert
    session.begin();
    var doc = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);
    doc.setProperty("intMap", map);
    session.commit();

    session.begin();
    Assert.assertEquals(2, keyIndex.size(session));
    Assert.assertEquals(2, valueIndex.size(session));
    session.rollback();

    // Delete all via SQL
    session.begin();
    session.execute("delete from Mapper").close();
    session.commit();

    // Close and reopen session
    session.close();
    session = youTrackDB.open(databaseName, adminUser, adminPassword);
    keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");
    valueIndex = session.getSharedContext().getIndexManager().getIndex("mapValueIdx");

    // Verify cleanup
    session.begin();
    Assert.assertEquals(
        "Key index should be empty after delete + session reopen (heavy schema)",
        0, keyIndex.size(session));
    Assert.assertEquals(
        "Value index should be empty after delete + session reopen (heavy schema)",
        0, valueIndex.size(session));
    session.rollback();

    // Second insert
    session.begin();
    var doc2 = session.newEntity("Mapper");
    var map2 = session.newEmbeddedMap();
    map2.put("key1", 10);
    map2.put("key2", 20);
    doc2.setProperty("intMap", map2);
    session.commit();

    session.begin();
    Assert.assertEquals(
        "Key index should have exactly 2 after re-insert (heavy schema)",
        2, keyIndex.size(session));
    session.rollback();
  }

  @Test
  public void testMapIndexDeleteWithSessionCloseReopen() {
    // Reproduces the TestNG lifecycle: session is closed and reopened
    // between test method + afterMethod and the next test method
    var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("id", PropertyType.STRING);
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    mapper.createIndex("mapKeyIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");

    var keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");

    // First insert (simulates testIndexMap)
    session.begin();
    var doc = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);
    doc.setProperty("intMap", map);
    session.commit();

    session.begin();
    Assert.assertEquals(2, keyIndex.size(session));
    session.rollback();

    // Delete all (simulates afterMethod)
    session.begin();
    session.execute("delete from Mapper").close();
    session.commit();

    // Close and reopen session (simulates TestNG afterMethod -> beforeMethod)
    session.close();
    session = youTrackDB.open(databaseName, adminUser, adminPassword);

    keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");

    // Verify cleanup after session reopen
    session.begin();
    Assert.assertEquals(
        "Key index should be empty after delete + session reopen",
        0, keyIndex.size(session));
    session.rollback();

    // Second insert (simulates testIndexMapInTx)
    session.begin();
    var doc2 = session.newEntity("Mapper");
    var map2 = session.newEmbeddedMap();
    map2.put("key1", 10);
    map2.put("key2", 20);
    doc2.setProperty("intMap", map2);
    session.commit();

    session.begin();
    Assert.assertEquals(
        "Key index should have exactly 2 entries after re-insert",
        2, keyIndex.size(session));
    session.rollback();
  }

  @Test
  public void testMapIndexEntriesRemovedAndReaddedAcrossMultipleTransactions() {
    var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    mapper.createIndex("mapKeyIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");

    var keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");

    // First transaction: insert
    session.begin();
    var doc = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();
    map.put("a", 1);
    map.put("b", 2);
    doc.setProperty("intMap", map);
    session.commit();

    session.begin();
    Assert.assertEquals(2, keyIndex.size(session));
    session.rollback();

    // Second transaction: delete all
    session.begin();
    session.execute("delete from Mapper").close();
    session.commit();

    session.begin();
    Assert.assertEquals(
        "Index should be empty after deleting all records", 0, keyIndex.size(session));
    session.rollback();

    // Third transaction: insert new records
    session.begin();
    var doc2 = session.newEntity("Mapper");
    var map2 = session.newEmbeddedMap();
    map2.put("c", 3);
    map2.put("d", 4);
    doc2.setProperty("intMap", map2);
    session.commit();

    session.begin();
    Assert.assertEquals(
        "Index should have exactly 2 entries from the new record", 2, keyIndex.size(session));
    session.rollback();
  }

  @Test
  public void testNestedTransactionDeleteDoesNotCleanupIndexEntries() {
    // Regression test for YTDB-510: when a test leaves an open transaction
    // and the afterMethod calls session.begin() (creating a nested tx),
    // the commit only decrements txStartCounter without actually committing.
    // This causes delete operations to be silently discarded, leaving
    // stale map index entries in the B-tree.

    var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    mapper.createIndex("mapKeyIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");
    mapper.createIndex("mapValueIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "intMap by value");

    var keyIndex = session.getSharedContext().getIndexManager().getIndex("mapKeyIdx");
    var valueIndex = session.getSharedContext().getIndexManager().getIndex("mapValueIdx");

    // Insert a record
    session.begin();
    var doc = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);
    doc.setProperty("intMap", map);
    session.commit();

    session.begin();
    Assert.assertEquals(2, keyIndex.size(session));
    Assert.assertEquals(2, valueIndex.size(session));
    session.rollback();

    // Simulate a test that leaves an open transaction (the bug scenario)
    session.begin();
    // ... verification code that forgets to rollback ...

    // Simulate afterMethod: begin() creates a nested transaction
    session.begin(); // txStartCounter is now 2

    // Delete all records in the nested transaction
    session.execute("delete from Mapper").close();

    // commit() only decrements txStartCounter from 2 to 1, does NOT actually commit
    session.commit();

    // The delete was silently discarded! Rollback to clean up the remaining tx.
    session.rollback();

    // Verify that index entries are NOT cleaned up (the bug behavior)
    session.begin();
    Assert.assertEquals(
        "Index entries should still exist because nested commit was a no-op",
        2, keyIndex.size(session));
    session.rollback();

    // Now do a PROPER cleanup: rollback any stale tx, then delete
    session.begin();
    session.execute("delete from Mapper").close();
    session.commit();

    // Verify proper cleanup works
    session.begin();
    Assert.assertEquals(
        "Index should be empty after proper (non-nested) delete",
        0, keyIndex.size(session));
    Assert.assertEquals(
        "Value index should be empty after proper (non-nested) delete",
        0, valueIndex.size(session));
    session.rollback();
  }
}
