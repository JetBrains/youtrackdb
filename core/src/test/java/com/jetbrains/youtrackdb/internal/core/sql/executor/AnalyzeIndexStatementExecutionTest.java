package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

public class AnalyzeIndexStatementExecutionTest extends DbTestBase {

  @Test
  public void analyzeNamedIndexReturnsHistogramProperties() {
    // Given: a class with an index and some data
    var className = "AnalyzeTestClass";
    var indexName = className + "keyIdx";
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("key", PropertyType.STRING);
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    session.begin();
    for (int i = 0; i < 100; i++) {
      var doc = session.newEntity(className);
      doc.setProperty("key", "value" + i);
    }
    session.commit();

    // When: ANALYZE INDEX is executed
    var result = session.execute("ANALYZE INDEX " + indexName);

    // Then: result contains expected properties
    assertTrue(result.hasNext());
    var row = result.next();
    assertEquals("analyze index", row.getProperty("operation"));
    assertEquals(indexName, row.getProperty("indexName"));
    assertNotNull(row.getProperty("totalCount"));
    assertTrue((Long) row.getProperty("totalCount") > 0);
    assertNotNull(row.getProperty("distinctCount"));
    assertNotNull(row.getProperty("nullCount"));
    assertNotNull(row.getProperty("bucketCount"));
    assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void analyzeAllIndexesReturnMultipleRows() {
    // Given: two classes with separate indexes
    var schema = session.getMetadata().getSchema();
    var clazz1 = schema.createClass("AnalyzeAll1");
    clazz1.createProperty("key", PropertyType.STRING);
    clazz1.createIndex("idx1", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    var clazz2 = schema.createClass("AnalyzeAll2");
    clazz2.createProperty("key", PropertyType.INTEGER);
    clazz2.createIndex("idx2", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    session.begin();
    for (int i = 0; i < 10; i++) {
      var doc1 = session.newEntity("AnalyzeAll1");
      doc1.setProperty("key", "v" + i);
      var doc2 = session.newEntity("AnalyzeAll2");
      doc2.setProperty("key", i);
    }
    session.commit();

    // When: ANALYZE INDEX * is executed
    var result = session.execute("ANALYZE INDEX *");

    // Then: at least 2 rows returned (one per automatic index)
    int count = 0;
    while (result.hasNext()) {
      var row = result.next();
      assertEquals("analyze index", row.getProperty("operation"));
      assertNotNull(row.getProperty("indexName"));
      count++;
    }
    assertTrue("Expected at least 2 analyzed indexes, got " + count, count >= 2);
    result.close();
  }

  @Test
  public void analyzeNonExistentIndexThrowsError() {
    // When/Then: ANALYZE INDEX on a non-existent index throws an exception
    try {
      session.execute("ANALYZE INDEX nonExistentIndex");
      Assert.fail("Expected exception for non-existent index");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("not found"));
    }
  }

  @Test
  public void analyzeEmptyIndexReturnsZeroCounts() {
    // Given: an index with no data
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("AnalyzeEmpty");
    clazz.createProperty("key", PropertyType.STRING);
    var indexName = "AnalyzeEmptyIdx";
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    // When: ANALYZE INDEX is executed on an empty index
    var result = session.execute("ANALYZE INDEX " + indexName);

    // Then: counts are zero
    assertTrue(result.hasNext());
    var row = result.next();
    assertEquals("analyze index", row.getProperty("operation"));
    assertEquals((Object) 0L, row.getProperty("totalCount"));
    assertEquals((Object) 0L, row.getProperty("nullCount"));
    assertFalse(result.hasNext());
    result.close();
  }
}
