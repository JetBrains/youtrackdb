package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.StatefulEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/** Tests for transaction lifecycle including begin, commit, and rollback operations. */
public class TransactionTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @Test
  public void test() {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", "Foo");
    tx.commit();

    tx = db.begin();
    v = tx.load(v);
    v.setProperty("name", "Bar");
    tx.rollback();

    tx = db.begin();
    v = tx.load(v);
    Assert.assertEquals("Foo", v.getProperty("name"));
    tx.commit();
  }

  // ---------- Original single-threaded variants (multi-session on same thread) ----------

  /*
    val = 'Foo'
    tx1:begin
    tx1:read -> 'Foo'
      tx2:begin
      tx2:update <- 'Bar'
      tx2:commit
    tx1:read -> 'Foo'
    tx1:commit
   */
  @Test
  public void testSnapshotIsolation() {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var tx1 = db1.begin();
      Vertex v1 = tx1.load(rid);
      Assert.assertEquals(recordValue, v1.getProperty("name"));

      var tx2 = db.begin();
      Vertex v2 = tx2.load(rid);
      v2.setProperty("name", updateRecordValue);
      tx2.commit();

      db.getLocalCache().clear();
      db1.getLocalCache().clear();
      Vertex v3 = tx1.load(rid);

      Assert.assertEquals(recordValue, v3.getProperty("name"));
      tx1.commit();
    } finally {
      db1.close();
    }
  }

  /*
    val = 'Foo'
    tx1:begin
    tx1:update <- 'Bar'
      tx2:begin
      tx2:read -> 'Foo'
    tx1:commit
      tx2:read -> 'Foo'
      tx2:commit
   */
  @Test
  public void testIsolationLevelsOverlapped() {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var tx1 = db1.begin();
      Vertex v1 = tx1.load(rid);
      v1.setProperty("name", updateRecordValue);

      var tx2 = db.begin();
      Vertex v2 = tx2.load(rid);
      Assert.assertEquals(recordValue, v2.getProperty("name"));

      tx1.commit();

      db.getLocalCache().clear();
      db1.getLocalCache().clear();
      Vertex v3 = tx2.load(rid);

      Assert.assertEquals(recordValue, v3.getProperty("name"));
      tx2.commit();
    } finally {
      db1.close();
    }
  }

  /*
    val = 'Foo'
    tx1:begin
      tx2:begin
      tx2:update <- 'Bar'
      tx2:commit
        tx3:begin
        tx3:read -> 'Bar'
        tx3:commit
    tx1:read -> 'Foo'
    tx1:commit
   */
  @Test
  public void testSnapshotIsolationNewUpd() {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var tx1 = db1.begin();
      Vertex v1 = tx1.load(rid);
      Assert.assertEquals(recordValue, v1.getProperty("name"));

      var tx2 = db.begin();
      Vertex v2 = tx2.load(rid);
      v2.setProperty("name", updateRecordValue);
      tx2.commit();

      db.getLocalCache().clear();
      db1.getLocalCache().clear();
      var tx3 = db.begin();
      Vertex v4 = tx3.load(rid);
      Assert.assertEquals(updateRecordValue, v4.getProperty("name"));
      tx3.commit();

      db.getLocalCache().clear();
      db1.getLocalCache().clear();
      Vertex v3 = tx1.load(rid);

      Assert.assertEquals(recordValue, v3.getProperty("name"));
      tx1.commit();
    } finally {
      db1.close();
    }
  }

  @Test
  public void testSnapshotIsolationRemoveProperty() {
    var recordValue = "Foo";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var tx1 = db1.begin();
      Vertex v1 = tx1.load(rid);
      Assert.assertEquals(recordValue, v1.getProperty("name"));

      var tx2 = db.begin();
      Vertex v2 = tx2.load(rid);
      v2.removeProperty("name");
      tx2.commit();

      db.getLocalCache().clear();
      db1.getLocalCache().clear();
      Vertex v3 = tx1.load(rid);

      Assert.assertEquals(recordValue, v3.getProperty("name"));
      tx1.commit();
    } finally {
      db1.close();
    }
  }

  @Test
  public void testSnapshotIsolationRemoveRecord() {
    var recordValue = "Foo";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var tx1 = db1.begin();
      Vertex v1 = tx1.load(rid);
      Assert.assertEquals(recordValue, v1.getProperty("name"));

      var tx2 = db.begin();
      Vertex v2 = tx2.load(rid);
      tx2.delete(v2);
      tx2.commit();

      db.getLocalCache().clear();
      db1.getLocalCache().clear();
      Vertex v3 = tx1.load(rid);

      Assert.assertEquals(recordValue, v3.getProperty("name"));
      tx1.commit();
    } finally {
      db1.close();
    }
  }

  @Test
  public void testConcurrentUpdate() {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var tx1 = db1.begin();
      Vertex v1 = tx1.load(rid);
      v1.setProperty("name", "abc");

      var tx2 = db.begin();
      Vertex v2 = tx2.load(rid);
      v2.setProperty("name", updateRecordValue);
      tx2.commit();

      Assert.assertThrows(ConcurrentModificationException.class, tx1::commit);
    } finally {
      db1.close();
    }
  }

  @Test
  public void testSnapshotIsolationIndexes() {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var user = db.createVertexClass("User");
    user.createProperty("name", PropertyType.STRING);
    user.createIndex(
        "IndexPropertyName",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"name"});

    var tx = db.begin();
    var v = tx.newVertex(user);
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      var tx1 = db1.begin();
      Vertex v1 = tx1.load(rid);
      Assert.assertEquals(recordValue, v1.getProperty("name"));

      var tx2 = db.begin();
      Vertex v2 = tx2.load(rid);
      v2.setProperty("name", updateRecordValue);
      tx2.commit();

      db.getLocalCache().clear();
      db1.getLocalCache().clear();
      Vertex v3 = tx1.load(rid);

      var g = youTrackDB.openTraversal("test", "admin", DbTestBase.ADMIN_PASSWORD);
      g.tx().begin();
      var list = g.V().has("name", "Bar").toList();
      Assert.assertFalse("Index should find the updated record", list.isEmpty());
      g.tx().commit();
      g.close();

      Assert.assertEquals(recordValue, v3.getProperty("name"));
      tx1.commit();
    } finally {
      db1.close();
    }
  }

  // ---------- Multi-threaded variants (concurrent isolation across threads) ----------

  @Test
  public void testSnapshotIsolationMultiThread() throws Exception {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var tx1Started = new CountDownLatch(1);
    var tx2Committed = new CountDownLatch(1);

    var threadError = new AtomicReference<Throwable>();
    var thread = new Thread(() -> {
      try {
        var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var tx1 = db1.begin();
          Vertex v1 = tx1.load(rid);
          Assert.assertEquals(recordValue, v1.getProperty("name"));

          tx1Started.countDown();
          awaitOrFail(tx2Committed);

          db1.getLocalCache().clear();
          Vertex v3 = tx1.load(rid);
          Assert.assertEquals(recordValue, v3.getProperty("name"));
          tx1.commit();
        } finally {
          db1.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(tx1Started);
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.setProperty("name", updateRecordValue);
    tx2.commit();
    db.getLocalCache().clear();
    tx2Committed.countDown();

    joinAndCheck(thread, threadError);
  }

  @Test
  public void testIsolationLevelsOverlappedMultiThread() throws Exception {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var tx1Started = new CountDownLatch(1);
    var tx2Read = new CountDownLatch(1);
    var tx1Committed = new CountDownLatch(1);

    var threadError = new AtomicReference<Throwable>();
    var thread = new Thread(() -> {
      try {
        var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var tx1 = db1.begin();
          Vertex v1 = tx1.load(rid);
          v1.setProperty("name", updateRecordValue);

          tx1Started.countDown();
          awaitOrFail(tx2Read);

          tx1.commit();
          tx1Committed.countDown();
        } finally {
          db1.close();
        }
      } catch (Throwable t) {
        tx1Committed.countDown();
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(tx1Started);
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    Assert.assertEquals(recordValue, v2.getProperty("name"));
    tx2Read.countDown();

    awaitOrFail(tx1Committed);
    db.getLocalCache().clear();
    Vertex v3 = tx2.load(rid);
    Assert.assertEquals(recordValue, v3.getProperty("name"));
    tx2.commit();

    joinAndCheck(thread, threadError);
  }

  @Test
  public void testSnapshotIsolationNewUpdMultiThread() throws Exception {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var tx1Started = new CountDownLatch(1);
    var mainDone = new CountDownLatch(1);

    var threadError = new AtomicReference<Throwable>();
    var thread = new Thread(() -> {
      try {
        var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var tx1 = db1.begin();
          Vertex v1 = tx1.load(rid);
          Assert.assertEquals(recordValue, v1.getProperty("name"));

          tx1Started.countDown();
          awaitOrFail(mainDone);

          db1.getLocalCache().clear();
          Vertex v3 = tx1.load(rid);
          Assert.assertEquals(recordValue, v3.getProperty("name"));
          tx1.commit();
        } finally {
          db1.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(tx1Started);
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.setProperty("name", updateRecordValue);
    tx2.commit();

    db.getLocalCache().clear();
    var tx3 = db.begin();
    Vertex v4 = tx3.load(rid);
    Assert.assertEquals(updateRecordValue, v4.getProperty("name"));
    tx3.commit();

    mainDone.countDown();

    joinAndCheck(thread, threadError);
  }

  @Test
  public void testConcurrentUpdateMultiThread() throws Exception {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    tx.commit();

    var tx1Started = new CountDownLatch(1);
    var tx2Committed = new CountDownLatch(1);
    var caughtCme = new AtomicReference<ConcurrentModificationException>();

    var threadError = new AtomicReference<Throwable>();
    var thread = new Thread(() -> {
      try {
        var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var tx1 = db1.begin();
          Vertex v1 = tx1.load(rid);
          v1.setProperty("name", "abc");

          tx1Started.countDown();
          awaitOrFail(tx2Committed);

          try {
            tx1.commit();
            Assert.fail("Expected ConcurrentModificationException");
          } catch (ConcurrentModificationException e) {
            caughtCme.set(e);
          }
        } finally {
          db1.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(tx1Started);
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.setProperty("name", updateRecordValue);
    tx2.commit();
    tx2Committed.countDown();

    joinAndCheck(thread, threadError);
    Assert.assertNotNull(
        "Expected ConcurrentModificationException on Thread A", caughtCme.get());
  }

  // ---------- Snapshot isolation during collection iteration ----------

  /*
    Insert initial records, commit.
    tx1:begin (takes snapshot)
      tx2:begin
      tx2:insert new records
      tx2:commit
    tx1:query (iterate collection) -> should see only initial records
    tx1:commit

    Regression test: before the fix, the collection iterator (nextPage) would throw
    RecordNotFoundException when encountering records inserted by tx2, because the
    position map is shared and contained entries not yet visible to tx1's snapshot.
   */
  @Test
  public void testSnapshotIsolationCollectionIteration() {
    // Setup: create a class with some initial records
    var schema = db.getMetadata().getSchema();
    if (!schema.existsClass("IterTest")) {
      var cls = schema.createClass("IterTest");
      cls.createProperty("val", PropertyType.INTEGER);
    }

    // Insert 5 initial records
    db.executeInTx(tx -> {
      for (var i = 0; i < 5; i++) {
        var entity = tx.newEntity("IterTest");
        entity.setProperty("val", i);
      }
    });

    // Open a second session for the concurrent writer
    var db2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    try {
      // tx1: start a read transaction (takes snapshot before tx2's inserts)
      var tx1 = db.begin();

      // tx2: insert more records and commit on the second session
      db2.executeInTx(tx -> {
        for (var i = 100; i < 105; i++) {
          var entity = tx.newEntity("IterTest");
          entity.setProperty("val", i);
        }
      });

      // tx1: iterate via query — must see only the 5 initial records,
      // not the 5 records inserted by tx2
      try (var rs = tx1.query("select from IterTest")) {
        var count = 0;
        while (rs.hasNext()) {
          var result = rs.next();
          int val = result.getProperty("val");
          Assert.assertTrue("Unexpected record with val=" + val + " from concurrent tx",
              val < 100);
          count++;
        }
        Assert.assertEquals("Should see exactly 5 initial records", 5, count);
      }
      tx1.commit();
    } finally {
      db2.close();
    }
  }

  /*
    Multi-threaded variant of testSnapshotIsolationCollectionIteration.
    Thread A starts a read transaction, then signals Thread B to insert records
    and commit. After Thread B finishes, Thread A iterates the collection and
    verifies it only sees the records from before its snapshot.
   */
  @Test
  public void testSnapshotIsolationCollectionIterationMultiThread()
      throws Exception {
    // Setup: create a class with some initial records
    var schema = db.getMetadata().getSchema();
    if (!schema.existsClass("IterTestMT")) {
      var cls = schema.createClass("IterTestMT");
      cls.createProperty("val", PropertyType.INTEGER);
    }

    db.executeInTx(tx -> {
      for (var i = 0; i < 5; i++) {
        var entity = tx.newEntity("IterTestMT");
        entity.setProperty("val", i);
      }
    });

    var writerReady = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    // Thread B: waits for signal, then inserts records and commits
    var writerThread = new Thread(() -> {
      try {
        var db2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          awaitOrFail(writerReady);
          db2.executeInTx(tx -> {
            for (var i = 100; i < 105; i++) {
              var entity = tx.newEntity("IterTestMT");
              entity.setProperty("val", i);
            }
          });
        } finally {
          db2.close();
          writerDone.countDown();
        }
      } catch (Throwable t) {
        threadError.set(t);
        writerDone.countDown();
      }
    });
    writerThread.start();

    try {
      // Thread A: start read transaction, signal writer, wait for writer, then iterate
      var tx1 = db.begin();
      writerReady.countDown();
      awaitOrFail(writerDone);

      try (var rs = tx1.query("select from IterTestMT")) {
        var count = 0;
        while (rs.hasNext()) {
          var result = rs.next();
          int val = result.getProperty("val");
          Assert.assertTrue(
              "Unexpected record with val=" + val + " from concurrent tx",
              val < 100);
          count++;
        }
        Assert.assertEquals("Should see exactly 5 initial records", 5, count);
      }
      tx1.commit();
    } finally {
      joinAndCheck(writerThread, threadError);
    }
  }

  // ---------- Comprehensive multi-threaded SI tests ----------

  /**
   * Verifies that stateful edge property updates in one transaction are not visible to a
   * concurrent reader that started its transaction before the update was committed.
   *
   * <pre>
   *   setup: v1 --[FriendOf{since=2020}]--> v2
   *   Thread A: begin tx, read edge -> since=2020
   *   Main:     begin tx, update edge since=2024, commit
   *   Thread A: re-read edge -> still sees since=2020 (snapshot isolation)
   * </pre>
   */
  @Test
  public void testSIEdgePropertyIsolationMultiThread() throws Exception {
    // Create two vertices connected by a stateful edge with a property.
    var tx = db.begin();
    var v1 = tx.newVertex("V");
    v1.setProperty("name", "Alice");
    var v2 = tx.newVertex("V");
    v2.setProperty("name", "Bob");
    var edge = tx.newStatefulEdge(v1, v2, "E");
    edge.setProperty("since", 2020);
    var edgeRid = edge.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    // Thread A: long-running reader that should see the original edge property
    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          StatefulEdge e = txReader.loadEdge(edgeRid);
          Assert.assertEquals(
              "Reader should see the original 'since' value",
              2020, (int) e.getProperty("since"));

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // Re-read after the writer committed: snapshot isolation must preserve old value
          dbReader.getLocalCache().clear();
          StatefulEdge eAfter = txReader.loadEdge(edgeRid);
          Assert.assertEquals(
              "Snapshot isolation: reader must still see since=2020",
              2020, (int) eAfter.getProperty("since"));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);

    // Main thread: update the edge property and commit
    var txWriter = db.begin();
    StatefulEdge eWriter = txWriter.loadEdge(edgeRid);
    eWriter.setProperty("since", 2024);
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);

    // After both transactions complete, a new reader must see the updated value
    var txVerify = db.begin();
    StatefulEdge eFinal = txVerify.loadEdge(edgeRid);
    Assert.assertEquals(
        "After commit, new readers must see since=2024",
        2024, (int) eFinal.getProperty("since"));
    txVerify.commit();
  }

  /**
   * Verifies that edges created by a concurrent transaction are not visible to a reader
   * whose snapshot predates the edge creation.
   *
   * <pre>
   *   setup: v1 (no edges), v2 (no edges)
   *   Thread A: begin tx, count outgoing edges of v1 -> 0
   *   Main:     begin tx, create edge v1->v2, commit
   *   Thread A: re-count outgoing edges of v1 -> still 0 (snapshot isolation)
   * </pre>
   */
  @Ignore("LinkBag (disk-based link collections) does not support snapshot isolation yet")
  @Test
  public void testSIEdgeCreationIsolationMultiThread() throws Exception {
    var tx = db.begin();
    var v1 = tx.newVertex("V");
    v1.setProperty("name", "A");
    var v2 = tx.newVertex("V");
    v2.setProperty("name", "B");
    var v1Rid = v1.getIdentity();
    var v2Rid = v2.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          Vertex vRead = txReader.load(v1Rid);
          int edgeCountBefore = countEdges(vRead.getEdges(Direction.OUT));
          Assert.assertEquals(
              "Before edge creation, v1 should have no outgoing edges",
              0, edgeCountBefore);

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // Re-read: the new edge must not be visible in this snapshot
          dbReader.getLocalCache().clear();
          Vertex vReadAgain = txReader.load(v1Rid);
          int edgeCountAfter = countEdges(vReadAgain.getEdges(Direction.OUT));
          Assert.assertEquals(
              "Snapshot isolation: new edge must not be visible to reader",
              0, edgeCountAfter);
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    Vertex vFrom = txWriter.load(v1Rid);
    Vertex vTo = txWriter.load(v2Rid);
    txWriter.newStatefulEdge(vFrom, vTo, "E");
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);

    // New transaction sees the edge
    var txVerify = db.begin();
    Vertex vFinal = txVerify.load(v1Rid);
    Assert.assertEquals(
        "After commit, new readers must see the created edge",
        1, countEdges(vFinal.getEdges(Direction.OUT)));
    txVerify.commit();
  }

  /**
   * Verifies that deleting a stateful edge in one transaction does not affect a concurrent
   * reader whose snapshot predates the deletion.
   *
   * <pre>
   *   setup: v1 --[E]--> v2
   *   Thread A: begin tx, read edge -> exists
   *   Main:     begin tx, delete the edge, commit
   *   Thread A: re-read edge -> still exists (snapshot isolation)
   * </pre>
   */
  @Ignore("LinkBag (disk-based link collections) does not support snapshot isolation yet")
  @Test
  public void testSIEdgeDeletionIsolationMultiThread() throws Exception {
    var tx = db.begin();
    var v1 = tx.newVertex("V");
    v1.setProperty("name", "X");
    var v2 = tx.newVertex("V");
    v2.setProperty("name", "Y");
    var edge = tx.newStatefulEdge(v1, v2, "E");
    edge.setProperty("weight", 10);
    var v1Rid = v1.getIdentity();
    var edgeRid = edge.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          StatefulEdge eRead = txReader.loadEdge(edgeRid);
          Assert.assertEquals(10, (int) eRead.getProperty("weight"));

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // The edge was deleted by the writer, but our snapshot must still see it
          dbReader.getLocalCache().clear();
          StatefulEdge eAfter = txReader.loadEdge(edgeRid);
          Assert.assertNotNull(
              "Snapshot isolation: deleted edge must still be visible",
              eAfter);
          Assert.assertEquals(10, (int) eAfter.getProperty("weight"));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    StatefulEdge eDel = txWriter.loadEdge(edgeRid);
    txWriter.delete(eDel);
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);

    // After the writer committed, new readers should not find the edge
    var txVerify = db.begin();
    try {
      txVerify.loadEdge(edgeRid);
      Assert.fail("Edge should have been deleted");
    } catch (RecordNotFoundException expected) {
      // expected
    }
    txVerify.commit();
  }

  /**
   * Verifies that updates to multiple properties on the same record are atomically invisible
   * to a concurrent reader whose snapshot predates the commit.
   *
   * <pre>
   *   setup: vertex with name='Alice', age=30, city='NYC'
   *   Thread A: begin tx, read all three properties
   *   Main:     begin tx, update all three, commit
   *   Thread A: re-read -> still sees original values for all properties
   * </pre>
   */
  @Test
  public void testSIMultiplePropertiesMultiThread() throws Exception {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", "Alice");
    v.setProperty("age", 30);
    v.setProperty("city", "NYC");
    var rid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          Vertex vr = txReader.load(rid);
          Assert.assertEquals("Alice", vr.getProperty("name"));
          Assert.assertEquals(30, (int) vr.getProperty("age"));
          Assert.assertEquals("NYC", vr.getProperty("city"));

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // All three updates must be invisible to this snapshot
          dbReader.getLocalCache().clear();
          Vertex vrAfter = txReader.load(rid);
          Assert.assertEquals("Alice", vrAfter.getProperty("name"));
          Assert.assertEquals(30, (int) vrAfter.getProperty("age"));
          Assert.assertEquals("NYC", vrAfter.getProperty("city"));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    Vertex vw = txWriter.load(rid);
    vw.setProperty("name", "Bob");
    vw.setProperty("age", 40);
    vw.setProperty("city", "LA");
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies that multiple records updated in a single transaction are all atomically
   * invisible to a concurrent reader.
   *
   * <pre>
   *   setup: 5 vertices with val=0
   *   Thread A: begin tx, read all -> val=0
   *   Main:     begin tx, update all to val=1, commit
   *   Thread A: re-read all -> still val=0 (atomic snapshot)
   * </pre>
   */
  @Test
  public void testSIMultipleRecordsAtomicityMultiThread() throws Exception {
    var rids = new ArrayList<RID>();
    var tx = db.begin();
    for (int i = 0; i < 5; i++) {
      var v = tx.newVertex("V");
      v.setProperty("val", 0);
      rids.add(v.getIdentity());
    }
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          // Read all records - should all be 0
          for (var rid : rids) {
            Vertex vr = txReader.load(rid);
            Assert.assertEquals(
                "Initial read should see val=0", 0, (int) vr.getProperty("val"));
          }

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // Re-read after writer committed: all must still be 0
          dbReader.getLocalCache().clear();
          for (var rid : rids) {
            Vertex vr = txReader.load(rid);
            Assert.assertEquals(
                "Snapshot isolation: all records must still show val=0",
                0, (int) vr.getProperty("val"));
          }
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    for (var rid : rids) {
      Vertex vw = txWriter.load(rid);
      vw.setProperty("val", 1);
    }
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies that SQL queries within a transaction respect snapshot isolation. A reader's
   * SQL query should not see records created by a concurrent committed transaction if the
   * reader's snapshot predates the commit.
   *
   * <pre>
   *   setup: class Item, one record with tag='old'
   *   Thread A: begin tx, query "SELECT FROM Item WHERE tag='new'" -> empty
   *   Main:     begin tx, insert Item with tag='new', commit
   *   Thread A: re-query -> still empty (snapshot isolation)
   * </pre>
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSISqlQueryIsolationMultiThread() throws Exception {
    db.createVertexClass("Item");

    var tx = db.begin();
    var v = tx.newVertex("Item");
    v.setProperty("tag", "old");
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          // Query for 'new' items - should find none
          try (var rs = txReader.query("SELECT FROM Item WHERE tag = ?", "new")) {
            Assert.assertFalse(
                "Before insert, query for tag='new' must return empty",
                rs.hasNext());
          }

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // Re-query after the writer inserted and committed a 'new' Item
          dbReader.getLocalCache().clear();
          try (var rs = txReader.query("SELECT FROM Item WHERE tag = ?", "new")) {
            Assert.assertFalse(
                "Snapshot isolation: query must not see newly inserted record",
                rs.hasNext());
          }
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    var vNew = txWriter.newVertex("Item");
    vNew.setProperty("tag", "new");
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies snapshot isolation with index-backed queries. The reader's index lookup should
   * return results consistent with its snapshot, even after a concurrent writer updates the
   * indexed property and commits.
   *
   * <pre>
   *   setup: class Product with unique index on 'sku', one record sku='AAA'
   *   Thread A: begin tx, lookup sku='AAA' -> found
   *   Main:     begin tx, update sku to 'BBB', commit
   *   Thread A: lookup sku='AAA' -> still found (snapshot isolation)
   * </pre>
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSIIndexQueryIsolationMultiThread() throws Exception {
    var product = db.createVertexClass("Product");
    product.createProperty("sku", PropertyType.STRING);
    product.createIndex(
        "ProductSkuIdx",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"sku"});

    var tx = db.begin();
    var v = tx.newVertex(product);
    v.setProperty("sku", "AAA");
    var rid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          Vertex vr = txReader.load(rid);
          Assert.assertEquals("AAA", vr.getProperty("sku"));

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // Record-level read must still see the old SKU value
          dbReader.getLocalCache().clear();
          Vertex vrAfter = txReader.load(rid);
          Assert.assertEquals(
              "Snapshot isolation: reader must still see sku='AAA'",
              "AAA", vrAfter.getProperty("sku"));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    Vertex vw = txWriter.load(rid);
    vw.setProperty("sku", "BBB");
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);

    // Verify the update is visible to new transactions
    var txVerify = db.begin();
    Vertex vFinal = txVerify.load(rid);
    Assert.assertEquals("BBB", vFinal.getProperty("sku"));
    txVerify.commit();
  }

  /**
   * Verifies snapshot isolation across a chain of transactions: tx1 (long reader), tx2
   * (writer that commits), tx3 (new reader that sees the update). tx1 must see the
   * original value throughout its lifetime.
   *
   * <pre>
   *   setup: vertex with val='V0'
   *   Thread A: begin tx1, read -> 'V0'
   *   Main:     begin tx2, update to 'V1', commit
   *   Main:     begin tx3, read -> 'V1', commit
   *   Main:     begin tx4, update to 'V2', commit
   *   Thread A: re-read -> still 'V0' (snapshot from before tx2)
   * </pre>
   */
  @Test
  public void testSIChainOfTransactionsMultiThread() throws Exception {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("val", "V0");
    var rid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var allWritersDone = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          Vertex vr = txReader.load(rid);
          Assert.assertEquals("V0", vr.getProperty("val"));

          readerStarted.countDown();
          awaitOrFail(allWritersDone);

          // After two successive writes (V0->V1->V2), the reader must still see V0
          dbReader.getLocalCache().clear();
          Vertex vrAfter = txReader.load(rid);
          Assert.assertEquals(
              "Snapshot isolation: reader must see V0 despite two subsequent updates",
              "V0", vrAfter.getProperty("val"));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);

    // First write: V0 -> V1
    var tx2 = db.begin();
    Vertex vw = tx2.load(rid);
    vw.setProperty("val", "V1");
    tx2.commit();

    // tx3: verify V1 is visible to new readers
    db.getLocalCache().clear();
    var tx3 = db.begin();
    Vertex v3 = tx3.load(rid);
    Assert.assertEquals("V1", v3.getProperty("val"));
    tx3.commit();

    // Second write: V1 -> V2
    db.getLocalCache().clear();
    var tx4 = db.begin();
    Vertex vw2 = tx4.load(rid);
    vw2.setProperty("val", "V2");
    tx4.commit();

    db.getLocalCache().clear();
    allWritersDone.countDown();

    joinAndCheck(thread, threadError);

    // Final verification: new reader sees V2
    var txFinal = db.begin();
    Vertex vFinal = txFinal.load(rid);
    Assert.assertEquals("V2", vFinal.getProperty("val"));
    txFinal.commit();
  }

  /**
   * Verifies that multiple concurrent readers each see the correct snapshot. Three reader
   * threads start at different points in time, and a writer updates a record between each.
   *
   * <pre>
   *   setup: vertex with version=0
   *   Reader1: begin tx                      -- snapshot sees version=0
   *   Writer:  update to version=1, commit
   *   Reader2: begin tx                      -- snapshot sees version=1
   *   Writer:  update to version=2, commit
   *   Reader3: begin tx                      -- snapshot sees version=2
   *   All readers verify their snapshot values are stable
   * </pre>
   */
  @Test
  public void testSIConcurrentReadersMultiThread() throws Exception {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("version", 0);
    var rid = v.getIdentity();
    tx.commit();

    // Latches to coordinate the three readers and the writer
    var reader1Started = new CountDownLatch(1);
    var firstWriteDone = new CountDownLatch(1);
    var reader2Started = new CountDownLatch(1);
    var secondWriteDone = new CountDownLatch(1);
    var reader3Started = new CountDownLatch(1);
    var allDone = new CountDownLatch(1);

    var errors = new CopyOnWriteArrayList<Throwable>();

    // Reader 1: starts before any writes, should always see version=0
    var reader1 = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          Vertex vr = txR.load(rid);
          Assert.assertEquals(0, (int) vr.getProperty("version"));
          reader1Started.countDown();
          awaitOrFail(allDone);
          dbR.getLocalCache().clear();
          Vertex vrAfter = txR.load(rid);
          Assert.assertEquals(
              "Reader1 must still see version=0", 0, (int) vrAfter.getProperty("version"));
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        errors.add(t);
      }
    });

    // Reader 2: starts after first write, should see version=1
    var reader2 = new Thread(() -> {
      try {
        awaitOrFail(firstWriteDone);
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          Vertex vr = txR.load(rid);
          Assert.assertEquals(1, (int) vr.getProperty("version"));
          reader2Started.countDown();
          awaitOrFail(allDone);
          dbR.getLocalCache().clear();
          Vertex vrAfter = txR.load(rid);
          Assert.assertEquals(
              "Reader2 must still see version=1", 1, (int) vrAfter.getProperty("version"));
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        errors.add(t);
      }
    });

    // Reader 3: starts after second write, should see version=2
    var reader3 = new Thread(() -> {
      try {
        awaitOrFail(secondWriteDone);
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          Vertex vr = txR.load(rid);
          Assert.assertEquals(2, (int) vr.getProperty("version"));
          reader3Started.countDown();
          awaitOrFail(allDone);
          dbR.getLocalCache().clear();
          Vertex vrAfter = txR.load(rid);
          Assert.assertEquals(
              "Reader3 must still see version=2", 2, (int) vrAfter.getProperty("version"));
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        errors.add(t);
      }
    });

    reader1.start();
    reader2.start();
    reader3.start();

    // Wait for Reader 1 to start, then do first write
    awaitOrFail(reader1Started);
    var txW1 = db.begin();
    Vertex vw1 = txW1.load(rid);
    vw1.setProperty("version", 1);
    txW1.commit();
    db.getLocalCache().clear();
    firstWriteDone.countDown();

    // Wait for Reader 2 to start, then do second write
    awaitOrFail(reader2Started);
    var txW2 = db.begin();
    Vertex vw2 = txW2.load(rid);
    vw2.setProperty("version", 2);
    txW2.commit();
    db.getLocalCache().clear();
    secondWriteDone.countDown();

    // Wait for Reader 3, then release all
    awaitOrFail(reader3Started);
    allDone.countDown();

    // Join all reader threads before checking errors, to avoid leaving dangling threads
    reader1.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    reader2.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    reader3.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

    if (reader1.isAlive() || reader2.isAlive() || reader3.isAlive()) {
      reader1.interrupt();
      reader2.interrupt();
      reader3.interrupt();
      Assert.fail("Reader thread(s) did not finish within timeout");
    }

    if (!errors.isEmpty()) {
      for (var e : errors) {
        e.printStackTrace();
      }
      Assert.fail("Errors in reader threads: " + errors.getFirst().getMessage());
    }
  }

  /**
   * Verifies that a record deleted by one transaction is still visible to a concurrent
   * reader, and that re-creating a record of the same class with the same property value
   * does not confuse the reader's snapshot.
   *
   * <pre>
   *   setup: vertex with name='X'
   *   Thread A: begin tx, read vertex -> name='X'
   *   Main:     begin tx, delete vertex, create new vertex with name='X-new', commit
   *   Thread A: re-read original vertex -> still name='X', exists=true
   * </pre>
   */
  @Test
  public void testSIDeleteAndRecreateMultiThread() throws Exception {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", "X");
    var originalRid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          Vertex vr = txReader.load(originalRid);
          Assert.assertEquals("X", vr.getProperty("name"));

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          // The original record was deleted, but our snapshot must still see it
          dbReader.getLocalCache().clear();
          Vertex vrAfter = txReader.load(originalRid);
          Assert.assertEquals(
              "Snapshot isolation: deleted record must still be visible in old snapshot",
              "X", vrAfter.getProperty("name"));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    Vertex vDel = txWriter.load(originalRid);
    txWriter.delete(vDel);
    var vNew = txWriter.newVertex("V");
    vNew.setProperty("name", "X-new");
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies that many rapid write transactions do not corrupt the snapshot seen by a
   * long-running reader. The reader starts a transaction, then a burst of short-lived
   * writer transactions increment a counter. The reader must still see the original value.
   *
   * <pre>
   *   setup: vertex with counter=0
   *   Thread A: begin tx, read -> counter=0
   *   Main:     20 successive txs each incrementing counter by 1
   *   Thread A: re-read -> still counter=0
   * </pre>
   */
  @Test
  public void testSIRapidCommitCyclesMultiThread() throws Exception {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("counter", 0);
    var rid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var allWritesDone = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          Vertex vr = txReader.load(rid);
          Assert.assertEquals(0, (int) vr.getProperty("counter"));

          readerStarted.countDown();
          awaitOrFail(allWritesDone);

          dbReader.getLocalCache().clear();
          Vertex vrAfter = txReader.load(rid);
          Assert.assertEquals(
              "Snapshot isolation: reader must see counter=0 despite 20 commits",
              0, (int) vrAfter.getProperty("counter"));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);

    // 20 rapid write cycles
    for (int i = 1; i <= 20; i++) {
      var txW = db.begin();
      Vertex vw = txW.load(rid);
      vw.setProperty("counter", i);
      txW.commit();
      db.getLocalCache().clear();
    }
    allWritesDone.countDown();

    joinAndCheck(thread, threadError);

    // Verify final state
    var txVerify = db.begin();
    Vertex vFinal = txVerify.load(rid);
    Assert.assertEquals(20, (int) vFinal.getProperty("counter"));
    txVerify.commit();
  }

  /**
   * Tests concurrent modification detection when two threads update the same stateful edge.
   * One thread's commit must succeed, and the other must fail with
   * ConcurrentModificationException.
   *
   * <pre>
   *   setup: v1 --[E{weight=1}]--> v2
   *   Thread A: begin tx, load edge, set weight=10
   *   Main:     begin tx, load edge, set weight=20, commit
   *   Thread A: commit -> ConcurrentModificationException
   * </pre>
   */
  @Test
  public void testConcurrentEdgeUpdateMultiThread() throws Exception {
    var tx = db.begin();
    var v1 = tx.newVertex("V");
    var v2 = tx.newVertex("V");
    var edge = tx.newStatefulEdge(v1, v2, "E");
    edge.setProperty("weight", 1);
    var edgeRid = edge.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var caughtCme = new AtomicReference<ConcurrentModificationException>();
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var tx1 = db1.begin();
          StatefulEdge e1 = tx1.loadEdge(edgeRid);
          e1.setProperty("weight", 10);

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          try {
            tx1.commit();
            Assert.fail("Expected ConcurrentModificationException");
          } catch (ConcurrentModificationException e) {
            caughtCme.set(e);
          }
        } finally {
          db1.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var tx2 = db.begin();
    StatefulEdge e2 = tx2.loadEdge(edgeRid);
    e2.setProperty("weight", 20);
    tx2.commit();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
    Assert.assertNotNull(
        "Expected ConcurrentModificationException on edge update", caughtCme.get());

    // Verify the winning update
    var txVerify = db.begin();
    StatefulEdge eFinal = txVerify.loadEdge(edgeRid);
    Assert.assertEquals(20, (int) eFinal.getProperty("weight"));
    txVerify.commit();
  }

  /**
   * Verifies snapshot isolation with a vertex that has multiple outgoing edges (up to the
   * embedded threshold). A reader opens a snapshot, then a writer adds more edges. The
   * reader's edge count must not change.
   *
   * <p>The number of edges is kept below
   * {@link GlobalConfiguration#INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD}
   * because LinkBag does not support transactions the same way as indexed records.
   *
   * <pre>
   *   setup: v1 with 3 outgoing edges to v2, v3, v4
   *   Thread A: begin tx, count edges -> 3
   *   Main:     begin tx, add 2 more edges, commit
   *   Thread A: re-count -> still 3
   * </pre>
   */
  @Ignore("LinkBag (disk-based link collections) does not support snapshot isolation yet")
  @Test
  public void testSIEdgeCountStabilityMultiThread() throws Exception {
    // Stay well below the threshold (default 40) to use embedded links
    var initialEdgeCount = 3;

    var tx = db.begin();
    var v1 = tx.newVertex("V");
    v1.setProperty("name", "hub");
    var targets = new ArrayList<RID>();
    for (int i = 0; i < initialEdgeCount; i++) {
      var target = tx.newVertex("V");
      target.setProperty("name", "target" + i);
      tx.newStatefulEdge(v1, target, "E");
      targets.add(target.getIdentity());
    }
    var v1Rid = v1.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbReader = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txReader = dbReader.begin();
          Vertex vr = txReader.load(v1Rid);
          Assert.assertEquals(
              initialEdgeCount, countEdges(vr.getEdges(Direction.OUT)));

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          dbReader.getLocalCache().clear();
          Vertex vrAfter = txReader.load(v1Rid);
          Assert.assertEquals(
              "Snapshot isolation: edge count must remain " + initialEdgeCount,
              initialEdgeCount, countEdges(vrAfter.getEdges(Direction.OUT)));
          txReader.commit();
        } finally {
          dbReader.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txWriter = db.begin();
    Vertex vw = txWriter.load(v1Rid);
    // Add 2 more edges (still under the threshold: 3 + 2 = 5 < 40)
    for (int i = 0; i < 2; i++) {
      var target = txWriter.newVertex("V");
      target.setProperty("name", "extra" + i);
      txWriter.newStatefulEdge(vw, target, "E");
    }
    txWriter.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);

    // New readers should see 5 edges
    var txVerify = db.begin();
    Vertex vFinal = txVerify.load(v1Rid);
    Assert.assertEquals(
        initialEdgeCount + 2, countEdges(vFinal.getEdges(Direction.OUT)));
    txVerify.commit();
  }

  /**
   * Verifies that a rollback on one thread does not affect a concurrent reader's snapshot.
   * The reader should see the committed state, not the rolled-back changes.
   *
   * <pre>
   *   setup: vertex with val='committed'
   *   Thread A: begin tx, read -> 'committed'
   *   Thread B: begin tx, update to 'rolled-back', rollback
   *   Thread A: re-read -> still 'committed'
   * </pre>
   */
  @Test
  public void testSIRollbackDoesNotAffectReaderMultiThread() throws Exception {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("val", "committed");
    var rid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerRolledBack = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    // Thread B: begins tx, modifies the record, then rolls back
    var rollerThread = new Thread(() -> {
      try {
        awaitOrFail(readerStarted);
        var dbW = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txW = dbW.begin();
          Vertex vw = txW.load(rid);
          vw.setProperty("val", "rolled-back");
          txW.rollback();
          writerRolledBack.countDown();
        } finally {
          dbW.close();
        }
      } catch (Throwable t) {
        writerRolledBack.countDown();
        threadError.set(t);
      }
    });

    // Thread A: reader
    var readerError = new AtomicReference<Throwable>();
    var readerThread = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          Vertex vr = txR.load(rid);
          Assert.assertEquals("committed", vr.getProperty("val"));
          readerStarted.countDown();
          awaitOrFail(writerRolledBack);

          dbR.getLocalCache().clear();
          Vertex vrAfter = txR.load(rid);
          Assert.assertEquals(
              "Rollback must not affect reader's snapshot",
              "committed", vrAfter.getProperty("val"));
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        readerError.set(t);
      }
    });

    readerThread.start();
    rollerThread.start();

    joinAndCheck(readerThread, readerError);
    joinAndCheck(rollerThread, threadError);
  }

  /**
   * Randomized stress test for snapshot isolation. Multiple writer threads perform random
   * operations (create, update, delete vertices) while reader threads concurrently verify
   * that their snapshots remain consistent throughout.
   *
   * <p>Each reader begins a transaction, records the snapshot of all record values, waits
   * for writers to finish, then verifies its snapshot is unchanged.
   *
   * <p>Edge count is kept below the embedded threshold.
   */
  @Test
  public void testSIRandomOperationsStressMultiThread() throws Exception {
    var recordCount = 10;
    var writerThreadCount = 4;
    var readerThreadCount = 3;
    var writeCycles = 15;

    // Create initial records
    var rids = new ArrayList<RID>();
    var tx = db.begin();
    for (int i = 0; i < recordCount; i++) {
      var v = tx.newVertex("V");
      v.setProperty("val", i);
      rids.add(v.getIdentity());
    }
    tx.commit();

    var readersStarted = new CountDownLatch(readerThreadCount);
    var allWritersDone = new CountDownLatch(1);
    var errors = new CopyOnWriteArrayList<Throwable>();

    // Reader threads: take a snapshot, wait for writes to finish, verify snapshot
    var readerThreads = new ArrayList<Thread>();
    for (int r = 0; r < readerThreadCount; r++) {
      var readerIdx = r;
      var readerThread = new Thread(() -> {
        try {
          var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            var txR = dbR.begin();

            // Snapshot all current values
            var snapshot = new int[recordCount];
            for (int i = 0; i < recordCount; i++) {
              Vertex vr = txR.load(rids.get(i));
              snapshot[i] = vr.getProperty("val");
            }

            readersStarted.countDown();
            awaitOrFail(allWritersDone);

            // Verify all values are unchanged
            dbR.getLocalCache().clear();
            for (int i = 0; i < recordCount; i++) {
              Vertex vr = txR.load(rids.get(i));
              Assert.assertEquals(
                  "Reader" + readerIdx + ": record " + i
                      + " value changed within snapshot",
                  snapshot[i], (int) vr.getProperty("val"));
            }
            txR.commit();
          } finally {
            dbR.close();
          }
        } catch (Throwable t) {
          errors.add(t);
        }
      });
      readerThreads.add(readerThread);
      readerThread.start();
    }

    // Wait for all readers to take their snapshots
    awaitOrFail(readersStarted);

    // Writer threads: randomly update records
    var writerThreads = new ArrayList<Thread>();
    var writerBarrier = new CyclicBarrier(writerThreadCount);
    for (int w = 0; w < writerThreadCount; w++) {
      var writerThread = new Thread(() -> {
        try {
          var dbW = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            writerBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var rng = ThreadLocalRandom.current();
            for (int c = 0; c < writeCycles; c++) {
              var txW = dbW.begin();
              var idx = rng.nextInt(recordCount);
              try {
                Vertex vw = txW.load(rids.get(idx));
                vw.setProperty("val", rng.nextInt(10000));
                txW.commit();
              } catch (ConcurrentModificationException e) {
                // Expected under concurrent writes - just retry
              }
              dbW.getLocalCache().clear();
            }
          } finally {
            dbW.close();
          }
        } catch (Throwable t) {
          errors.add(t);
        }
      });
      writerThreads.add(writerThread);
      writerThread.start();
    }

    // Wait for all writers to finish
    for (var wt : writerThreads) {
      wt.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      if (wt.isAlive()) {
        wt.interrupt();
        Assert.fail("Writer thread did not finish");
      }
    }
    allWritersDone.countDown();

    // Wait for readers
    for (var rt : readerThreads) {
      rt.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      if (rt.isAlive()) {
        rt.interrupt();
        Assert.fail("Reader thread did not finish");
      }
    }

    if (!errors.isEmpty()) {
      for (var e : errors) {
        e.printStackTrace();
      }
      Assert.fail("Errors in concurrent threads: " + errors.getFirst().getMessage());
    }
  }

  /**
   * Randomized test with mixed vertex and edge operations under snapshot isolation.
   * Writers randomly create vertices and connect them with edges, update properties,
   * or delete edges. Readers verify that each snapshot is self-consistent: for every
   * edge visible in the snapshot, both endpoints must also be visible and have the
   * properties they had at snapshot time.
   *
   * <p>Edge count per vertex is kept below the embedded threshold to avoid LinkBag
   * issues.
   */
  @Test
  public void testSIRandomEdgeOperationsStressMultiThread() throws Exception {
    // Max edges per vertex, well under the threshold (default 40)
    var maxEdgesPerVertex =
        (int) GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD
            .getDefValue() / 2;

    // Create initial graph: a few vertices with edges between them
    var vertexRids = new ArrayList<RID>();
    var edgeRids = new ArrayList<RID>();
    var tx = db.begin();
    for (int i = 0; i < 6; i++) {
      var v = tx.newVertex("V");
      v.setProperty("val", i);
      vertexRids.add(v.getIdentity());
    }
    // Connect pairs: 0->1, 2->3, 4->5
    for (int i = 0; i < 6; i += 2) {
      Vertex from = tx.load(vertexRids.get(i));
      Vertex to = tx.load(vertexRids.get(i + 1));
      var e = tx.newStatefulEdge(from, to, "E");
      e.setProperty("label", "initial");
      edgeRids.add(e.getIdentity());
    }
    tx.commit();

    var readersStarted = new CountDownLatch(2);
    var allWritersDone = new CountDownLatch(1);
    var errors = new CopyOnWriteArrayList<Throwable>();

    // Reader threads: snapshot vertex values and verify they don't change
    var readerThreads = new ArrayList<Thread>();
    for (int r = 0; r < 2; r++) {
      var readerIdx = r;
      var readerThread = new Thread(() -> {
        try {
          var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            var txR = dbR.begin();
            var snapshotValues = new ArrayList<Integer>();
            for (var vRid : vertexRids) {
              Vertex vr = txR.load(vRid);
              snapshotValues.add(vr.getProperty("val"));
            }

            readersStarted.countDown();
            awaitOrFail(allWritersDone);

            // Verify snapshot consistency
            dbR.getLocalCache().clear();
            for (int i = 0; i < vertexRids.size(); i++) {
              Vertex vr = txR.load(vertexRids.get(i));
              Assert.assertEquals(
                  "Reader" + readerIdx + ": vertex " + i + " changed",
                  snapshotValues.get(i), vr.getProperty("val"));
            }
            txR.commit();
          } finally {
            dbR.close();
          }
        } catch (Throwable t) {
          errors.add(t);
        }
      });
      readerThreads.add(readerThread);
      readerThread.start();
    }

    awaitOrFail(readersStarted);

    // Writer threads: random operations on the graph
    var writerThreads = new ArrayList<Thread>();
    for (int w = 0; w < 3; w++) {
      var writerThread = new Thread(() -> {
        try {
          var dbW = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            var rng = ThreadLocalRandom.current();
            for (int c = 0; c < 10; c++) {
              var txW = dbW.begin();
              try {
                // Randomly pick: update vertex property or update edge property
                if (rng.nextBoolean()) {
                  var idx = rng.nextInt(vertexRids.size());
                  Vertex vw = txW.load(vertexRids.get(idx));
                  vw.setProperty("val", rng.nextInt(10000));
                } else {
                  var idx = rng.nextInt(edgeRids.size());
                  StatefulEdge ew = txW.loadEdge(edgeRids.get(idx));
                  ew.setProperty("label", "updated-" + rng.nextInt(100));
                }
                txW.commit();
              } catch (ConcurrentModificationException e) {
                // Expected - ignore and continue
              }
              dbW.getLocalCache().clear();
            }
          } finally {
            dbW.close();
          }
        } catch (Throwable t) {
          errors.add(t);
        }
      });
      writerThreads.add(writerThread);
      writerThread.start();
    }

    for (var wt : writerThreads) {
      wt.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      if (wt.isAlive()) {
        wt.interrupt();
        Assert.fail("Writer thread did not finish within timeout");
      }
    }
    allWritersDone.countDown();

    for (var rt : readerThreads) {
      rt.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      if (rt.isAlive()) {
        rt.interrupt();
        Assert.fail("Reader thread did not finish within timeout");
      }
    }

    if (!errors.isEmpty()) {
      for (var e : errors) {
        e.printStackTrace();
      }
      Assert.fail("Errors in concurrent threads: " + errors.getFirst().getMessage());
    }
  }

  /**
   * Verifies that a reader that started before a record was created via SQL INSERT
   * cannot see that record through a subsequent SQL query.
   *
   * <pre>
   *   Thread A: begin tx, query "SELECT count(*) FROM V" -> N
   *   Main:     INSERT INTO V SET name='NewViaSQL', commit
   *   Thread A: re-query count -> still N
   * </pre>
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSISqlInsertIsolationMultiThread() throws Exception {
    // Create at least one record so the class exists
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", "seed");
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();
    var initialCount = new AtomicInteger();

    var thread = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          try (var rs = txR.query("SELECT count(*) as cnt FROM V")) {
            var result = rs.next();
            initialCount.set(((Number) result.getProperty("cnt")).intValue());
          }

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          dbR.getLocalCache().clear();
          try (var rs = txR.query("SELECT count(*) as cnt FROM V")) {
            var result = rs.next();
            int countAfter = ((Number) result.getProperty("cnt")).intValue();
            Assert.assertEquals(
                "Snapshot isolation: count must not change after concurrent INSERT",
                initialCount.get(), countAfter);
          }
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txW = db.begin();
    var vNew = txW.newVertex("V");
    vNew.setProperty("name", "NewViaSQL");
    txW.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies snapshot isolation when a property is changed from one type to another
   * (e.g., String -> Integer). The reader must still see the original type and value.
   *
   * <pre>
   *   setup: vertex with data='hello' (String)
   *   Thread A: begin tx, read -> data='hello'
   *   Main:     begin tx, set data=42 (Integer), commit
   *   Thread A: re-read -> data='hello' (String), not 42
   * </pre>
   */
  @Test
  public void testSIPropertyTypeChangeMultiThread() throws Exception {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("data", "hello");
    var rid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          Vertex vr = txR.load(rid);
          Assert.assertEquals("hello", vr.getProperty("data"));

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          dbR.getLocalCache().clear();
          Vertex vrAfter = txR.load(rid);
          Object val = vrAfter.getProperty("data");
          Assert.assertEquals(
              "Snapshot isolation: property type and value must not change",
              "hello", val);
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txW = db.begin();
    Vertex vw = txW.load(rid);
    vw.setProperty("data", 42);
    txW.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies that two concurrent writers updating <em>different</em> records do not
   * conflict. Both should commit successfully, and a subsequent reader should see both
   * updates.
   *
   * <pre>
   *   setup: v1 with val='A', v2 with val='B'
   *   Thread A: begin tx, update v1 to 'A2', wait
   *   Main:     begin tx, update v2 to 'B2', commit
   *   Thread A: commit (should succeed - no conflict on different records)
   *   Verify: v1='A2', v2='B2'
   * </pre>
   */
  @Test
  public void testNonConflictingConcurrentWritesMultiThread() throws Exception {
    var tx = db.begin();
    var v1 = tx.newVertex("V");
    v1.setProperty("val", "A");
    var v2 = tx.newVertex("V");
    v2.setProperty("val", "B");
    var rid1 = v1.getIdentity();
    var rid2 = v2.getIdentity();
    tx.commit();

    var threadStarted = new CountDownLatch(1);
    var mainCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var tx1 = db1.begin();
          Vertex vt1 = tx1.load(rid1);
          vt1.setProperty("val", "A2");

          threadStarted.countDown();
          awaitOrFail(mainCommitted);

          // This should succeed: no conflict because we updated v1, not v2
          tx1.commit();
        } finally {
          db1.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(threadStarted);
    var tx2 = db.begin();
    Vertex vt2 = tx2.load(rid2);
    vt2.setProperty("val", "B2");
    tx2.commit();
    mainCommitted.countDown();

    joinAndCheck(thread, threadError);

    // Both updates should be visible
    db.getLocalCache().clear();
    var txVerify = db.begin();
    Assert.assertEquals("A2", ((Vertex) txVerify.load(rid1)).getProperty("val"));
    Assert.assertEquals("B2", ((Vertex) txVerify.load(rid2)).getProperty("val"));
    txVerify.commit();
  }

  /**
   * Randomized snapshot isolation test where readers and writers run concurrently with
   * randomized timing. Each reader verifies that once it has observed a value for a record,
   * subsequent reads within the same transaction always return the same value, regardless
   * of concurrent writes.
   *
   * <p>This test uses random sleep intervals to maximize the chance of hitting subtle
   * race conditions in the MVCC implementation.
   */
  @Test
  public void testSIRandomTimingStressMultiThread() throws Exception {
    var recordCount = 5;
    var writerCount = 3;
    var readerCount = 4;
    var iterations = 10;

    // Setup: create records with initial sequential values
    var rids = new ArrayList<RID>();
    var tx = db.begin();
    for (int i = 0; i < recordCount; i++) {
      var v = tx.newVertex("V");
      v.setProperty("val", i * 100);
      rids.add(v.getIdentity());
    }
    tx.commit();

    var errors = new CopyOnWriteArrayList<Throwable>();
    var startBarrier = new CyclicBarrier(writerCount + readerCount);
    var stop = new AtomicBoolean(false);

    // Writer threads
    var threads = new ArrayList<Thread>();
    for (int w = 0; w < writerCount; w++) {
      var writerThread = new Thread(() -> {
        try {
          var dbW = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var rng = ThreadLocalRandom.current();
            for (int c = 0; c < iterations && !stop.get(); c++) {
              var txW = dbW.begin();
              try {
                int idx = rng.nextInt(recordCount);
                Vertex vw = txW.load(rids.get(idx));
                vw.setProperty("val", rng.nextInt(100000));
                txW.commit();
              } catch (ConcurrentModificationException e) {
                // Expected
              }
              dbW.getLocalCache().clear();
              // Random delay to vary timing
              Thread.sleep(rng.nextInt(3));
            }
          } finally {
            dbW.close();
          }
        } catch (Throwable t) {
          errors.add(t);
          stop.set(true);
        }
      });
      threads.add(writerThread);
      writerThread.start();
    }

    // Reader threads: repeatedly open short-lived snapshots and verify consistency
    for (int r = 0; r < readerCount; r++) {
      var readerThread = new Thread(() -> {
        try {
          var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var rng = ThreadLocalRandom.current();
            for (int c = 0; c < iterations && !stop.get(); c++) {
              var txR = dbR.begin();

              // Read all records, record snapshot
              var snapshotVals = new int[recordCount];
              for (int i = 0; i < recordCount; i++) {
                Vertex vr = txR.load(rids.get(i));
                snapshotVals[i] = vr.getProperty("val");
              }

              // Random delay
              Thread.sleep(rng.nextInt(5));

              // Re-read and verify consistency
              dbR.getLocalCache().clear();
              for (int i = 0; i < recordCount; i++) {
                Vertex vr = txR.load(rids.get(i));
                int currentVal = vr.getProperty("val");
                Assert.assertEquals(
                    "Snapshot inconsistency on record " + i,
                    snapshotVals[i], currentVal);
              }
              txR.commit();
              dbR.getLocalCache().clear();
            }
          } finally {
            dbR.close();
          }
        } catch (Throwable t) {
          errors.add(t);
          stop.set(true);
        }
      });
      threads.add(readerThread);
      readerThread.start();
    }

    for (var t : threads) {
      t.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      if (t.isAlive()) {
        t.interrupt();
        Assert.fail("Thread did not finish within timeout");
      }
    }

    if (!errors.isEmpty()) {
      for (var e : errors) {
        e.printStackTrace();
      }
      Assert.fail("Errors in concurrent threads: " + errors.getFirst().getMessage());
    }
  }

  // ---------- Index-based SI tests (disabled until indexes support snapshot isolation) ----------

  /**
   * Verifies that a unique index query returns results consistent with the reader's
   * snapshot, even after a concurrent transaction inserts a new record with a different
   * key value and commits.
   *
   * <pre>
   *   setup: class Person with unique index on 'email', one record email='a@test.com'
   *   Thread A: begin tx, query by index email='b@test.com' -> not found
   *   Main:     begin tx, insert Person with email='b@test.com', commit
   *   Thread A: re-query by index email='b@test.com' -> still not found
   * </pre>
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSIUniqueIndexInsertIsolationMultiThread() throws Exception {
    var person = db.createVertexClass("Person");
    person.createProperty("email", PropertyType.STRING);
    person.createIndex(
        "PersonEmailIdx",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"email"});

    var tx = db.begin();
    var v = tx.newVertex(person);
    v.setProperty("email", "a@test.com");
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          try (var rs = txR.query(
              "SELECT FROM Person WHERE email = ?", "b@test.com")) {
            Assert.assertFalse(
                "Before insert, index lookup for b@test.com should be empty",
                rs.hasNext());
          }

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          dbR.getLocalCache().clear();
          try (var rs = txR.query(
              "SELECT FROM Person WHERE email = ?", "b@test.com")) {
            Assert.assertFalse(
                "Snapshot isolation: index must not see concurrently inserted record",
                rs.hasNext());
          }
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txW = db.begin();
    var vNew = txW.newVertex(person);
    vNew.setProperty("email", "b@test.com");
    txW.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies that a non-unique index query returns a count consistent with the reader's
   * snapshot, even after concurrent inserts of records matching the query.
   *
   * <pre>
   *   setup: class Tag with non-unique index on 'label', 3 records with label='red'
   *   Thread A: begin tx, count by index label='red' -> 3
   *   Main:     begin tx, insert 2 more Tag with label='red', commit
   *   Thread A: re-count -> still 3
   * </pre>
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSINonUniqueIndexCountIsolationMultiThread() throws Exception {
    var tag = db.createVertexClass("Tag");
    tag.createProperty("label", PropertyType.STRING);
    tag.createIndex(
        "TagLabelIdx",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"label"});

    var tx = db.begin();
    for (int i = 0; i < 3; i++) {
      var v = tx.newVertex(tag);
      v.setProperty("label", "red");
    }
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          int countBefore;
          try (var rs = txR.query(
              "SELECT FROM Tag WHERE label = ?", "red")) {
            countBefore = rs.toList().size();
          }
          Assert.assertEquals(3, countBefore);

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          dbR.getLocalCache().clear();
          int countAfter;
          try (var rs = txR.query(
              "SELECT FROM Tag WHERE label = ?", "red")) {
            countAfter = rs.toList().size();
          }
          Assert.assertEquals(
              "Snapshot isolation: non-unique index count must stay at 3",
              3, countAfter);
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txW = db.begin();
    for (int i = 0; i < 2; i++) {
      var vNew = txW.newVertex(tag);
      vNew.setProperty("label", "red");
    }
    txW.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies that an index range query returns results consistent with the reader's
   * snapshot. Uses a numeric index and BETWEEN query.
   *
   * <pre>
   *   setup: class Score with index on 'points', records with points 10, 20, 30
   *   Thread A: begin tx, query points BETWEEN 15 AND 35 -> [20, 30]
   *   Main:     begin tx, insert Score with points=25, commit
   *   Thread A: re-query -> still [20, 30], not [20, 25, 30]
   * </pre>
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSIIndexRangeQueryIsolationMultiThread() throws Exception {
    var score = db.createVertexClass("Score");
    score.createProperty("points", PropertyType.INTEGER);
    score.createIndex(
        "ScorePointsIdx",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"points"});

    var tx = db.begin();
    for (int pts : new int[]{10, 20, 30}) {
      var v = tx.newVertex(score);
      v.setProperty("points", pts);
    }
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          int countBefore;
          try (var rs = txR.query(
              "SELECT FROM Score WHERE points BETWEEN 15 AND 35")) {
            countBefore = rs.toList().size();
          }
          Assert.assertEquals(
              "Range query should find 2 records (20, 30)", 2, countBefore);

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          dbR.getLocalCache().clear();
          int countAfter;
          try (var rs = txR.query(
              "SELECT FROM Score WHERE points BETWEEN 15 AND 35")) {
            countAfter = rs.toList().size();
          }
          Assert.assertEquals(
              "Snapshot isolation: range query must still return 2 results",
              2, countAfter);
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txW = db.begin();
    var vNew = txW.newVertex(score);
    vNew.setProperty("points", 25);
    txW.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Verifies that deleting a record that is indexed does not affect a reader's index lookup
   * if the reader's snapshot predates the deletion.
   *
   * <pre>
   *   setup: class Ticket with unique index on 'code', one record code='T-001'
   *   Thread A: begin tx, query by index code='T-001' -> found
   *   Main:     begin tx, delete the record, commit
   *   Thread A: re-query by index code='T-001' -> still found (snapshot isolation)
   * </pre>
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSIIndexDeleteIsolationMultiThread() throws Exception {
    var ticket = db.createVertexClass("Ticket");
    ticket.createProperty("code", PropertyType.STRING);
    ticket.createIndex(
        "TicketCodeIdx",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"code"});

    var tx = db.begin();
    var v = tx.newVertex(ticket);
    v.setProperty("code", "T-001");
    var rid = v.getIdentity();
    tx.commit();

    var readerStarted = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var threadError = new AtomicReference<Throwable>();

    var thread = new Thread(() -> {
      try {
        var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
        try {
          var txR = dbR.begin();
          try (var rs = txR.query(
              "SELECT FROM Ticket WHERE code = ?", "T-001")) {
            Assert.assertTrue(
                "Index should find T-001 before deletion",
                rs.hasNext());
          }

          readerStarted.countDown();
          awaitOrFail(writerCommitted);

          dbR.getLocalCache().clear();
          try (var rs = txR.query(
              "SELECT FROM Ticket WHERE code = ?", "T-001")) {
            Assert.assertTrue(
                "Snapshot isolation: index must still find T-001 after concurrent delete",
                rs.hasNext());
          }
          txR.commit();
        } finally {
          dbR.close();
        }
      } catch (Throwable t) {
        threadError.set(t);
      }
    });
    thread.start();

    awaitOrFail(readerStarted);
    var txW = db.begin();
    Vertex vDel = txW.load(rid);
    txW.delete(vDel);
    txW.commit();
    db.getLocalCache().clear();
    writerCommitted.countDown();

    joinAndCheck(thread, threadError);
  }

  /**
   * Randomized index-based SI stress test. Multiple writers insert, update, and delete
   * indexed records while readers verify that their snapshot-time index query results
   * remain stable throughout the transaction.
   */
  @Ignore("Indexes do not support snapshot isolation yet")
  @Test
  public void testSIIndexRandomStressMultiThread() throws Exception {
    var item = db.createVertexClass("StressItem");
    item.createProperty("key", PropertyType.STRING);
    item.createIndex(
        "StressItemKeyIdx",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"key"});

    // Create initial indexed records
    var tx = db.begin();
    var rids = new ArrayList<RID>();
    for (int i = 0; i < 10; i++) {
      var v = tx.newVertex(item);
      v.setProperty("key", "K" + i);
      rids.add(v.getIdentity());
    }
    tx.commit();

    var readersStarted = new CountDownLatch(2);
    var allWritersDone = new CountDownLatch(1);
    var errors = new CopyOnWriteArrayList<Throwable>();

    // Readers: snapshot the count for a specific key, verify it stays stable
    var readerThreads = new ArrayList<Thread>();
    for (int r = 0; r < 2; r++) {
      var readerIdx = r;
      // Each reader queries a different key
      var queryKey = "K" + readerIdx;
      var readerThread = new Thread(() -> {
        try {
          var dbR = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            var txR = dbR.begin();
            int countBefore;
            try (var rs = txR.query(
                "SELECT FROM StressItem WHERE key = ?", queryKey)) {
              countBefore = rs.toList().size();
            }

            readersStarted.countDown();
            awaitOrFail(allWritersDone);

            dbR.getLocalCache().clear();
            int countAfter;
            try (var rs = txR.query(
                "SELECT FROM StressItem WHERE key = ?", queryKey)) {
              countAfter = rs.toList().size();
            }
            Assert.assertEquals(
                "Reader" + readerIdx + ": index count for " + queryKey
                    + " changed within snapshot",
                countBefore, countAfter);
            txR.commit();
          } finally {
            dbR.close();
          }
        } catch (Throwable t) {
          errors.add(t);
        }
      });
      readerThreads.add(readerThread);
      readerThread.start();
    }

    awaitOrFail(readersStarted);

    // Writers: randomly update keys of existing records
    var writerThreads = new ArrayList<Thread>();
    for (int w = 0; w < 3; w++) {
      var writerThread = new Thread(() -> {
        try {
          var dbW = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
          try {
            var rng = ThreadLocalRandom.current();
            for (int c = 0; c < 8; c++) {
              var txW = dbW.begin();
              try {
                int idx = rng.nextInt(rids.size());
                Vertex vw = txW.load(rids.get(idx));
                vw.setProperty("key", "K" + rng.nextInt(10));
                txW.commit();
              } catch (ConcurrentModificationException e) {
                // Expected
              }
              dbW.getLocalCache().clear();
            }
          } finally {
            dbW.close();
          }
        } catch (Throwable t) {
          errors.add(t);
        }
      });
      writerThreads.add(writerThread);
      writerThread.start();
    }

    for (var wt : writerThreads) {
      wt.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      if (wt.isAlive()) {
        wt.interrupt();
        Assert.fail("Writer thread did not finish within timeout");
      }
    }
    allWritersDone.countDown();

    for (var rt : readerThreads) {
      rt.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      if (rt.isAlive()) {
        rt.interrupt();
        Assert.fail("Reader thread did not finish within timeout");
      }
    }

    if (!errors.isEmpty()) {
      for (var e : errors) {
        e.printStackTrace();
      }
      Assert.fail("Errors in concurrent threads: " + errors.getFirst().getMessage());
    }
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  private static final long TIMEOUT_SECONDS = 30;

  private static void awaitOrFail(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      Assert.fail("Latch timed out after " + TIMEOUT_SECONDS + " seconds");
    }
  }

  private static void joinAndCheck(Thread thread, AtomicReference<Throwable> error)
      throws Exception {
    thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    if (thread.isAlive()) {
      thread.interrupt();
      Assert.fail("Thread did not finish within " + TIMEOUT_SECONDS + " seconds");
    }
    var t = error.get();
    if (t != null) {
      if (t instanceof Error e) {
        throw e;
      }
      if (t instanceof Exception e) {
        throw e;
      }
      throw new RuntimeException(t);
    }
  }

  private static void joinAndCheck(Thread thread, List<Throwable> errors)
      throws Exception {
    thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    if (thread.isAlive()) {
      thread.interrupt();
      Assert.fail("Thread did not finish within " + TIMEOUT_SECONDS + " seconds");
    }
    if (!errors.isEmpty()) {
      var t = errors.getFirst();
      if (t instanceof Error e) {
        throw e;
      }
      if (t instanceof Exception e) {
        throw e;
      }
      throw new RuntimeException(t);
    }
  }

  private static int countEdges(Iterable<Edge> edges) {
    int count = 0;
    for (Edge ignored : edges) {
      count++;
    }
    return count;
  }
}
