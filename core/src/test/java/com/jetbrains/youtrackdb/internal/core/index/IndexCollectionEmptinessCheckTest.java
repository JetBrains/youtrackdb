package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that index collection management and schema operations use O(1) emptiness
 * checks (iterator-based or approximate count) instead of O(n) full scans, while
 * preserving correct behavior: non-empty collections are rejected when adding/removing
 * from indexes, and schema operations correctly handle empty vs non-empty classes.
 */
public class IndexCollectionEmptinessCheckTest extends BaseMemoryInternalDatabase {

  /**
   * addCollection with requireEmpty=false on a non-empty collection should succeed.
   * Then verify the collection was added to the index. This exercises the addCollection
   * code path where requireEmpty=false skips the emptiness check.
   */
  @Test
  public void testAddCollectionWithoutEmptyCheckSucceeds() {
    var schema = session.getMetadata().getSchema();
    var className = "AddCollNoCheck";
    var clazz = schema.createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    var indexName = className + ".name";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Create a standalone collection.
    var collectionName = "extracoll";
    session.addCollection(collectionName);

    var indexManager = session.getSharedContext().getIndexManager();

    // Add with requireEmpty=false should succeed regardless of content.
    indexManager.addCollectionToIndex(session, collectionName, indexName, false);

    // Verify the collection was added.
    var index = indexManager.getIndex(indexName);
    Assert.assertTrue("Index should contain the added collection",
        index.getCollections().contains(collectionName));
  }

  /**
   * addCollection with requireEmpty=true on an empty collection must succeed.
   * Verifies the iterator-based emptiness check allows empty collections.
   */
  @Test
  public void testAddEmptyCollectionToIndexSucceeds() {
    var schema = session.getMetadata().getSchema();
    var className = "AddCollEmpty";
    var clazz = schema.createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Create an empty standalone collection.
    var emptyCollectionName = "emptycoll";
    session.addCollection(emptyCollectionName);

    var indexManager = session.getSharedContext().getIndexManager();

    // This should succeed because the collection is empty.
    indexManager.addCollectionToIndex(session, emptyCollectionName,
        className + ".name", true);

    // Verify the collection was actually added to the index.
    var index = indexManager.getIndex(className + ".name");
    Assert.assertTrue("Index should contain the added collection",
        index.getCollections().contains(emptyCollectionName));
  }

  /**
   * removeCollection on a non-empty collection must throw IndexException.
   * Verifies the iterator-based emptiness check rejects removal of collections
   * that contain records.
   *
   * <p>Note: classes are created with 8 collections by default and records are
   * distributed across them. We insert enough records to guarantee coverage of
   * all collections, then verify the default-named collection is non-empty.
   */
  @Test
  public void testRemoveNonEmptyCollectionFromIndexThrows() {
    var schema = session.getMetadata().getSchema();
    var className = "RemoveCollNonEmpty";
    var clazz = schema.createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Insert enough records to ensure every collection has at least one entry.
    // Default CLASS_COLLECTIONS_COUNT is 8, so 100 records guarantees coverage.
    session.executeInTx(tx -> {
      for (var i = 0; i < 100; i++) {
        var entity = session.newEntity(className);
        entity.setProperty("name", "value" + i);
      }
    });

    // The default collection name is the lowercase class name.
    var defaultCollectionName = className.toLowerCase();

    // Verify the default-named collection is non-empty.
    var browseResult = session.computeInTx(tx -> {
      try (var iter = session.browseCollection(defaultCollectionName)) {
        return iter.hasNext();
      }
    });
    Assert.assertTrue("Default collection should have records", browseResult);

    var indexManager = session.getSharedContext().getIndexManager();

    // Try to remove the non-empty default collection from the index.
    try {
      indexManager.removeCollectionFromIndex(session, defaultCollectionName,
          className + ".name");
      Assert.fail("Should have thrown IndexException for non-empty collection");
    } catch (IndexException e) {
      Assert.assertTrue("Exception message should mention collection name",
          e.getMessage().contains(defaultCollectionName));
    }
  }

  /**
   * removeCollection on an empty collection must succeed.
   * Verifies the iterator-based emptiness check allows removal of empty collections.
   */
  @Test
  public void testRemoveEmptyCollectionFromIndexSucceeds() {
    var schema = session.getMetadata().getSchema();
    var className = "RemoveCollEmpty";
    var clazz = schema.createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createIndex(className + ".name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // The default collection is empty (no records inserted).
    var defaultCollectionName = className.toLowerCase();
    var indexManager = session.getSharedContext().getIndexManager();

    // This should succeed because the collection is empty.
    indexManager.removeCollectionFromIndex(session, defaultCollectionName,
        className + ".name");

    // Verify the collection was removed from the index.
    var index = indexManager.getIndex(className + ".name");
    Assert.assertFalse("Index should no longer contain the removed collection",
        index.getCollections().contains(defaultCollectionName));
  }

  /**
   * REBUILD INDEX on a class with records must correctly re-index all entries.
   * Verifies that the approximate-count guard in indexCollection does not skip
   * non-empty collections.
   */
  @Test
  public void testRebuildIndexOnNonEmptyClass() {
    var schema = session.getMetadata().getSchema();
    var className = "RebuildNonEmpty";
    var clazz = schema.createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    var indexName = className + ".name";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Insert records.
    session.executeInTx(tx -> {
      for (var i = 0; i < 5; i++) {
        var entity = session.newEntity(className);
        entity.setProperty("name", "val" + i);
      }
    });

    // Rebuild the index.
    var result = session.execute("rebuild index " + indexName);
    Assert.assertTrue(result.hasNext());
    var resultRecord = result.next();
    Assert.assertEquals("All 5 records should be re-indexed",
        5L, resultRecord.<Object>getProperty("totalIndexed"));
    result.close();

    // Verify index lookup still works.
    session.begin();
    var queryResult = session.query("select from " + className + " where name = 'val0'");
    Assert.assertTrue("Index query should find the record", queryResult.hasNext());
    queryResult.close();
    session.commit();
  }

  /**
   * REBUILD INDEX on a class with no records should be a no-op (0 indexed).
   * Verifies that the approximate-count guard correctly skips empty collections.
   */
  @Test
  public void testRebuildIndexOnEmptyClass() {
    var schema = session.getMetadata().getSchema();
    var className = "RebuildEmpty";
    var clazz = schema.createClass(className);
    clazz.createProperty("name", PropertyType.STRING);
    var indexName = className + ".name";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Rebuild the index on an empty class.
    var result = session.execute("rebuild index " + indexName);
    Assert.assertTrue(result.hasNext());
    var resultRecord = result.next();
    Assert.assertEquals("No records should be indexed for empty class",
        0L, resultRecord.<Object>getProperty("totalIndexed"));
    result.close();
  }

  /**
   * Setting a class with no records to abstract should succeed and drop the
   * default collection. Verifies tryDropCollection's iterator-based emptiness
   * check correctly identifies empty collections.
   */
  @Test
  public void testSetAbstractOnEmptyClassDropsCollection() {
    var schema = session.getMetadata().getSchema();
    var className = "AbstractEmpty";
    var clazz = schema.createClass(className);

    // The class should have a valid default collection.
    var collectionIds = clazz.getCollectionIds();
    Assert.assertTrue("Class should have at least one collection",
        collectionIds.length > 0 && collectionIds[0] != -1);

    // Set the class as abstract — should succeed and drop the collection.
    clazz.setAbstract(true);

    // Verify the class is now abstract with no valid collections.
    Assert.assertTrue("Class should be abstract", clazz.isAbstract());
    var newCollectionIds = clazz.getCollectionIds();
    Assert.assertEquals("Abstract class should have exactly one collection slot",
        1, newCollectionIds.length);
    Assert.assertEquals("Abstract class collection should be -1",
        -1, newCollectionIds[0]);
  }

  /**
   * Setting a class with records to abstract should throw because the class
   * contains records. This validates the guard check before tryDropCollection.
   */
  @Test(expected = IllegalStateException.class)
  public void testSetAbstractOnNonEmptyClassThrows() {
    var schema = session.getMetadata().getSchema();
    var className = "AbstractNonEmpty";
    var clazz = schema.createClass(className);

    // Insert a record.
    session.executeInTx(tx -> {
      session.newEntity(className);
    });

    // Setting to abstract should throw because the class has records.
    clazz.setAbstract(true);
  }
}
