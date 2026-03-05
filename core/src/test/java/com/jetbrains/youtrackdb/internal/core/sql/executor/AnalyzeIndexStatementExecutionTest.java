package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

/**
 * ANALYZE INDEX execution tests (Section 10.9.2 of the ADR).
 *
 * <p>Verifies end-to-end SQL execution of ANALYZE INDEX: named index with
 * property verification, wildcard (*) across multiple indexes, non-existent
 * index error, empty index zero counts, and concurrent background rebalance
 * wait behavior.
 */
public class AnalyzeIndexStatementExecutionTest extends DbTestBase {

  @Test
  public void analyzeNamedIndexReturnsHistogramProperties() {
    // Given: a class with an index and 100 distinct string values
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

    // Then: result contains all expected properties with correct values
    assertTrue(result.hasNext());
    var row = result.next();
    assertEquals("analyze index", row.getProperty("operation"));
    assertEquals(indexName, row.getProperty("indexName"));
    assertEquals(100L, (long) (Long) row.getProperty("totalCount"));
    assertEquals(100L, (long) (Long) row.getProperty("distinctCount"));
    assertEquals(0L, (long) (Long) row.getProperty("nullCount"));
    assertTrue("bucketCount should be > 0",
        (Integer) row.getProperty("bucketCount") > 0);
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

    // Then: counts are zero, no histogram (bucketCount=0)
    assertTrue(result.hasNext());
    var row = result.next();
    assertEquals("analyze index", row.getProperty("operation"));
    assertEquals(0L, (long) (Long) row.getProperty("totalCount"));
    assertEquals(0L, (long) (Long) row.getProperty("distinctCount"));
    assertEquals(0L, (long) (Long) row.getProperty("nullCount"));
    assertEquals(0, (int) (Integer) row.getProperty("bucketCount"));
    assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void analyzeWhileBackgroundRebalanceInProgress_waitsAndReturnsRefreshed()
      throws Exception {
    // Given: an index with enough data to have a histogram
    var className = "AnalyzeConcurrent";
    var indexName = className + "keyIdx";
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty("key", PropertyType.INTEGER);
    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    session.begin();
    for (int i = 0; i < 200; i++) {
      var doc = session.newEntity(className);
      doc.setProperty("key", i);
    }
    session.commit();

    // Force initial histogram build so we have a baseline
    var firstResult = session.execute("ANALYZE INDEX " + indexName);
    assertTrue(firstResult.hasNext());
    firstResult.next();
    firstResult.close();

    // Get the histogram manager via reflection to simulate a background
    // rebalance in progress
    var idx = session.getSharedContext().getIndexManager().getIndex(indexName);
    var indexIdField = IndexAbstract.class.getDeclaredField("indexId");
    indexIdField.setAccessible(true);
    int indexId = indexIdField.getInt(idx);
    var storageField = IndexAbstract.class.getDeclaredField("storage");
    storageField.setAccessible(true);
    var storage = storageField.get(idx);
    var getEngineMethod = storage.getClass().getMethod("getIndexEngine", int.class);
    var engine = (BTreeIndexEngine) getEngineMethod.invoke(storage, indexId);
    var manager = engine.getHistogramManager();

    // Set rebalanceInProgress=true to simulate an ongoing background rebalance
    var rebalanceField =
        IndexHistogramManager.class.getDeclaredField("rebalanceInProgress");
    rebalanceField.setAccessible(true);
    var rebalanceFlag = (AtomicBoolean) rebalanceField.get(manager);
    rebalanceFlag.set(true);

    var analyzeError = new AtomicReference<Exception>();
    var analyzeDone = new CountDownLatch(1);
    Thread analyzeThread = null;
    try {
      // Launch ANALYZE INDEX on a separate thread — it should wait for
      // the rebalance to complete via waitForRebalanceAndReturn()
      analyzeThread = new Thread(() -> {
        try {
          // Activate session on this thread (required for embedded DB)
          session.activateOnCurrentThread();
          var result = session.execute("ANALYZE INDEX " + indexName);
          assertTrue(result.hasNext());
          var row = result.next();
          // Should return a valid snapshot from the cache
          assertEquals("analyze index", row.getProperty("operation"));
          assertNotNull(row.getProperty("totalCount"));
          result.close();
        } catch (Exception e) {
          analyzeError.set(e);
        } finally {
          analyzeDone.countDown();
        }
      });
      analyzeThread.start();

      // Best-effort wait for the analyze thread to enter the poll loop
      // inside waitForRebalanceAndReturn(). Under heavy CI load the
      // thread may not reach the loop before the flag is cleared, but
      // the test still validates the no-deadlock / correct-return path.
      Thread.sleep(300);

      // Clear the rebalance flag — the analyze call detects this and returns
      rebalanceFlag.set(false);

      // Then: ANALYZE INDEX completes within a reasonable time
      assertTrue(
          "ANALYZE INDEX should complete after rebalance flag is cleared",
          analyzeDone.await(10, TimeUnit.SECONDS));

      if (analyzeError.get() != null) {
        throw analyzeError.get();
      }
    } finally {
      // Always restore the flag and join the thread to prevent
      // afterTest() from racing with an active analyze thread
      rebalanceFlag.set(false);
      if (analyzeThread != null) {
        analyzeThread.interrupt();
        analyzeThread.join(5000);
      }
      // Re-activate session on the main thread for afterTest() cleanup
      session.activateOnCurrentThread();
    }
  }
}
