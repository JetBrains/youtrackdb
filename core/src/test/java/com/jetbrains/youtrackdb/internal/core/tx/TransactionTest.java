package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
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

    // Session 1: create record
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    System.out.println("Initial txId = " + tx.getId());
    tx.commit();

    // Session 2: start read tx1
    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    var tx1 = db1.begin();
    Vertex v1 = tx1.load(rid);
    Assert.assertEquals(recordValue, v1.getProperty("name"));
    System.out.println("On Load with txId = " + tx1.getId() + " sees: " + v1.getProperty("name"));

    // Session 1: start update tx2
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.setProperty("name", updateRecordValue);
    System.out.println("Modifying with txId = " + tx2.getId() + " sees: " + v2.getProperty("name"));
    tx2.commit();

    // re-load inside tx1
    db.getLocalCache().clear();
    db1.getLocalCache().clear();
    Vertex v3 = tx1.load(rid);

    // isolation check tx1
    System.out.println("Isolation txId = " + tx1.getId() + " sees: " + v3.getProperty("name"));
    Assert.assertEquals(recordValue, v3.getProperty("name"));
    ((AbstractStorage) db.getStorage()).cleanUnreachableRecordVersions();

    tx1.commit();

    ((AbstractStorage) db.getStorage()).cleanUnreachableRecordVersions();
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

    // Session 1: create record
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    System.out.println("Initial txId = " + tx.getId());
    tx.commit();

    // Session 2: start an update tx1
    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    var tx1 = db1.begin();
    Vertex v1 = tx1.load(rid);
    v1.setProperty("name", updateRecordValue);

    // Session 1: start read tx2
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    System.out.println("Before modifying with txId = " + tx2.getId() + " sees: " + v2.getProperty("name"));
    Assert.assertEquals(recordValue, v2.getProperty("name"));

    // commit tx1
    System.out.println("Modifying with txId = " + tx1.getId() + " sees: " + v1.getProperty("name"));

    tx1.commit();

    // re-load inside tx2
    db.getLocalCache().clear();
    db1.getLocalCache().clear();
    Vertex v3 = tx2.load(rid);

    // isolation check tx2
    System.out.println("Isolation txId = " + tx2.getId() + " sees: " + v3.getProperty("name"));
    Assert.assertEquals(recordValue, v3.getProperty("name"));
    tx2.commit();
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

    // Session 1: create record
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    System.out.println("Initial txId = " + tx.getId());
    tx.commit();

    // Session 2: start read tx1
    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    var tx1 = db1.begin();
    Vertex v1 = tx1.load(rid);
    Assert.assertEquals(recordValue, v1.getProperty("name"));
    System.out.println("On Load with txId = " + tx1.getId() + " sees: " + v1.getProperty("name"));

    // Session 1: start update tx2
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.setProperty("name", updateRecordValue);
    System.out.println("Modifying with txId = " + tx2.getId() + " sees: " + v2.getProperty("name"));
    tx2.commit();

    db.getLocalCache().clear();
    db1.getLocalCache().clear();
    var tx3 = db.begin();
    Vertex v4 = tx3.load(rid);
    Assert.assertEquals(updateRecordValue, v4.getProperty("name"));
    tx3.commit();

    // re-load inside tx1
    db.getLocalCache().clear();
    db1.getLocalCache().clear();
    Vertex v3 = tx1.load(rid);

    // isolation check tx1
    System.out.println("Isolation txId = " + tx1.getId() + " sees: " + v3.getProperty("name"));
    Assert.assertEquals(recordValue, v3.getProperty("name"));
    tx1.commit();
  }

  /*
  val = 'Foo'
  tx1:begin
  tx1:read -> 'Foo'
    tx2:begin
    tx2:remove property <- 'null'
    tx2:commit
  tx1:read -> 'Foo'
  tx1:commit
 */
  @Test
  public void testSnapshotIsolationRemoveProperty() {
    var recordValue = "Foo";

    // Session 1: create record
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    System.out.println("Initial txId = " + tx.getId());
    tx.commit();

    // Session 2: start read tx1
    var db1 = youTrackDB.open("test", "admin",
        DbTestBase.ADMIN_PASSWORD);
    var tx1 = db1.begin();
    Vertex v1 = tx1.load(rid);
    Assert.assertEquals(recordValue, v1.getProperty("name"));
    System.out.println("On Load with txId = " + tx1.getId() + " sees: " + v1.getProperty("name"));

    // Session 1: start update tx2
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.removeProperty("name");
    System.out.println("Remove property txId = " + tx2.getId() + " sees: " + v2.getProperty("name"));
    tx2.commit();

    // re-load inside tx1
    db.getLocalCache().clear();
    db1.getLocalCache().clear();
    Vertex v3 = tx1.load(rid);

    // isolation check tx1
    System.out.println("Isolation txId = " + tx1.getId() + " sees: " + v3.getProperty("name"));
    Assert.assertEquals(recordValue, v3.getProperty("name"));
    ((AbstractStorage) db.getStorage()).cleanUnreachableRecordVersions();

    tx1.commit();

    ((AbstractStorage) db.getStorage()).cleanUnreachableRecordVersions();
  }

  /*
 val = 'Foo'
 tx1:begin
 tx1:read -> 'Foo'
   tx2:begin
   tx2:remove record
   tx2:commit
 tx1:read -> 'Foo'
 tx1:commit
*/
  @Test
  public void testSnapshotIsolationRemoveRecord() {
    var recordValue = "Foo";

    // Session 1: create record
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    System.out.println("Initial txId = " + tx.getId());
    tx.commit();

    // Session 2: start read tx1
    var db1 = (DatabaseSessionEmbedded) youTrackDB.open("test", "admin",
        DbTestBase.ADMIN_PASSWORD);
    var tx1 = db1.begin();
    Vertex v1 = tx1.load(rid);
    Assert.assertEquals(recordValue, v1.getProperty("name"));
    System.out.println("On Load with txId = " + tx1.getId() + " sees: " + v1.getProperty("name"));

    // Session 1: start update tx2
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    //v2.removeProperty("name");
    tx2.delete(v2);
    System.out.println("Removing record with txId = " + tx2.getId() + " sees: " + v2);
    tx2.commit();

    // re-load inside tx1
    db.getLocalCache().clear();
    db1.getLocalCache().clear();
    Vertex v3 = tx1.load(rid);

    // isolation check tx1
    System.out.println("Isolation txId = " + tx1.getId() + " sees: " + v3.getProperty("name"));
    Assert.assertEquals(recordValue, v3.getProperty("name"));
    ((AbstractStorage) db.getStorage()).cleanUnreachableRecordVersions();

    tx1.commit();

    ((AbstractStorage) db.getStorage()).cleanUnreachableRecordVersions();
  }

  @Test(expected = com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException.class)
  public void testConcurrentUpdate() {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    // Session 1: create record
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    System.out.println("Initial txId = " + tx.getId());
    tx.commit();

    // Session 2: start read tx1
    var db1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    var tx1 = db1.begin();
    Vertex v1 = tx1.load(rid);
    v1.setProperty("name", "abc");

    // Session 1: start update tx2
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.setProperty("name", updateRecordValue);
    tx2.commit();

    tx1.commit();
  }

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
  public void testSnapshotIsolationIndexes() throws Exception {
    var recordValue = "Foo";
    var updateRecordValue = "Bar";

    var user = db.createVertexClass("Userr");
    user.createProperty("name", PropertyType.STRING);

    user.createIndex(
        "IndexPropertyName",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"name"});

    // Session 1: create record
    var tx = db.begin();
    var v = tx.newVertex(user);
    v.setProperty("name", recordValue);
    var rid = v.getIdentity();
    System.out.println("Initial txId = " + tx.getId());
    tx.commit();

    // Session 2: start read tx1
    var db1 = youTrackDB.open("test", "admin",
        DbTestBase.ADMIN_PASSWORD);
    var tx1 = db1.begin();
    Vertex v1 = tx1.load(rid);
    Assert.assertEquals(recordValue, v1.getProperty("name"));
    System.out.println("On Load with txId = " + tx1.getId() + " sees: " + v1.getProperty("name"));

    // Session 1: start update tx2
    var tx2 = db.begin();
    Vertex v2 = tx2.load(rid);
    v2.setProperty("name", updateRecordValue);
    System.out.println("Modifying with txId = " + tx2.getId() + " sees: " + v2.getProperty("name"));
    tx2.commit();

    // re-load inside tx1
    db.getLocalCache().clear();
    db1.getLocalCache().clear();
    Vertex v3 = tx1.load(rid);

    // try to find via index

    var g = youTrackDB.openTraversal("test", "admin",
        DbTestBase.ADMIN_PASSWORD);
    g.tx().begin();
    var list = g.V().has("name", "Bar").toList();
    System.out.println("Found via index: " + list);
    g.tx().commit();
    g.close();

    // isolation check tx1
    System.out.println("Isolation txId = " + tx1.getId() + " sees: " + v3.getProperty("name"));
    Assert.assertEquals(recordValue, v3.getProperty("name"));

    tx1.commit();
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }
}
