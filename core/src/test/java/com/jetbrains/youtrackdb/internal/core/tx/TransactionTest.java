package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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
}
