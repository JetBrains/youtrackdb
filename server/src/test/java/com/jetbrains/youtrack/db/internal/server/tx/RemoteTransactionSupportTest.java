package com.jetbrains.youtrack.db.internal.server.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.ArrayList;
import org.junit.Test;

/**
 *
 */
public class RemoteTransactionSupportTest extends BaseServerMemoryDatabase {

  private static final String FIELD_VALUE = "VALUE";

  public void beforeTest() {
    GlobalConfiguration.CLASS_MINIMUM_CLUSTERS.setValue(1);
    super.beforeTest();

    db.createClass("SomeTx");
    db.createClass("SomeTx2");

    var klass = db.createClass("IndexedTx");
    klass.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var uniqueClass = db.createClass("UniqueIndexedTx");
    uniqueClass.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
  }

  @Test
  public void testQueryUpdateUpdatedInTxTransaction() {
    db.begin();
    var doc = ((EntityImpl) db.newEntity("SomeTx"));
    doc.setProperty("name", "Joe");
    Identifiable id = doc;
    db.commit();

    db.begin();
    EntityImpl doc2 = db.load(id.getIdentity());
    doc2.setProperty("name", "Jane");
    var result = db.command("update SomeTx set name='July' where name = 'Jane' ");
    assertEquals(1L, (long) result.next().getProperty("count"));
    EntityImpl doc3 = db.load(id.getIdentity());
    assertEquals("July", doc3.getProperty("name"));
    db.rollback();
  }

  @Test
  public void testResetUpdatedInTxTransaction() {
    db.begin();

    var doc1 = ((EntityImpl) db.newEntity());
    doc1.setProperty("name", "Jane");
    var doc2 = ((EntityImpl) db.newEntity("SomeTx"));
    doc2.setProperty("name", "Jane");
    var result = db.command("update SomeTx set name='July' where name = 'Jane' ");
    assertEquals(1L, (long) result.next().getProperty("count"));
    assertEquals("July", doc2.getProperty("name"));
    result.close();
  }

  @Test
  public void testQueryUpdateCreatedInTxTransaction() throws InterruptedException {
    db.begin();
    var doc1 = ((EntityImpl) db.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane");
    Identifiable id = doc1;

    var docx = ((EntityImpl) db.newEntity("SomeTx2"));
    docx.setProperty("name", "Jane");

    var result = db.command("update SomeTx set name='July' where name = 'Jane' ");
    assertTrue(result.hasNext());
    assertEquals(1L, (long) result.next().getProperty("count"));
    EntityImpl doc2 = db.load(id.getIdentity());
    assertEquals("July", doc2.getProperty("name"));
    assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testRollbackTxTransaction() {
    db.begin();
    var doc = ((EntityImpl) db.newEntity("SomeTx"));
    doc.setProperty("name", "Jane");
    db.commit();

    db.begin();
    var doc1 = ((EntityImpl) db.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane");

    var result = db.command("update SomeTx set name='July' where name = 'Jane' ");
    assertTrue(result.hasNext());
    assertEquals(2L, (long) result.next().getProperty("count"));
    result.close();
    db.rollback();

    var result1 = db.command("select count(*) from SomeTx where name='Jane'");
    assertTrue(result1.hasNext());
    assertEquals(1L, (long) result1.next().getProperty("count(*)"));
    result1.close();
  }

  @Test
  public void testRollbackTxCheckStatusTransaction() {
    db.begin();
    var doc = ((EntityImpl) db.newEntity("SomeTx"));
    doc.setProperty("name", "Jane");
    db.commit();

    db.begin();
    var doc1 = ((EntityImpl) db.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane");

    var result = db.command("select count(*) from SomeTx where name='Jane' ");
    assertTrue(result.hasNext());
    assertEquals(2L, (long) result.next().getProperty("count(*)"));

    assertTrue(db.getTransaction().isActive());
    result.close();
    db.rollback();

    var result1 = db.command("select count(*) from SomeTx where name='Jane'");
    assertTrue(result1.hasNext());
    assertEquals(1L, (long) result1.next().getProperty("count(*)"));

    assertFalse(db.getTransaction().isActive());
    result1.close();
  }

  @Test
  public void testDownloadTransactionAtStart() {
    db.begin();

    db.command("insert into SomeTx set name ='Jane' ").close();
    assertEquals(1, db.getTransaction().getEntryCount());
  }

  @Test
  public void testQueryUpdateCreatedInTxSQLTransaction() {
    db.begin();

    db.command("insert into SomeTx set name ='Jane' ").close();

    var result = db.command("update SomeTx set name='July' where name = 'Jane' ");
    assertTrue(result.hasNext());
    assertEquals(1L, (long) result.next().getProperty("count"));
    result.close();
    var result1 = db.query("select from SomeTx where name='July'");
    assertTrue(result1.hasNext());
    assertEquals("July", result1.next().getProperty("name"));
    assertFalse(result.hasNext());
    result1.close();
  }

  @Test
  public void testQueryDeleteTxSQLTransaction() {
    db.begin();
    var someTx = db.newEntity("SomeTx");
    someTx.setProperty("name", "foo");
    db.commit();

    db.begin();
    db.command("delete from SomeTx");
    db.commit();

    var result = db.command("select from SomeTx");
    assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testDoubleSaveTransaction() {
    db.begin();
    var someTx = db.newEntity("SomeTx");
    someTx.setProperty("name", "foo");
    assertEquals(1, db.getTransaction().getEntryCount());
    assertEquals(1, db.countClass("SomeTx"));
    db.commit();
    assertEquals(1, db.countClass("SomeTx"));
  }

  @Test
  public void testDoubleSaveDoubleFlushTransaction() {
    db.begin();
    var someTx = db.newEntity("SomeTx");
    someTx.setProperty("name", "foo");
    var result = db.query("select from SomeTx");
    assertEquals(1, result.stream().count());
    result.close();
    result = db.query("select from SomeTx");
    assertEquals(1, result.stream().count());
    result.close();
    assertEquals(1, db.getTransaction().getEntryCount());
    assertEquals(1, db.countClass("SomeTx"));
    db.commit();
    assertEquals(1, db.countClass("SomeTx"));
  }

  @Test
  public void testRefFlushedInTransaction() {
    db.begin();
    var someTx = db.newEntity("SomeTx");
    someTx.setProperty("name", "foo");

    var oneMore = db.newEntity("SomeTx");
    oneMore.setProperty("name", "bar");
    oneMore.setProperty("ref", someTx);
    var result = db.query("select from SomeTx");
    assertEquals(1, result.stream().count());
    result.close();
    db.commit();
    var result1 = db.query("select ref from SomeTx where name='bar'");
    assertTrue(result1.hasNext());
    assertEquals(someTx.getIdentity(), result1.next().getProperty("ref"));
    result1.close();
  }

  @Test
  public void testDoubleRefFlushedInTransaction() {
    db.begin();
    var someTx = db.newEntity("SomeTx");
    someTx.setProperty("name", "foo");

    var oneMore = db.newEntity("SomeTx");
    oneMore.setProperty("name", "bar");
    oneMore.setProperty("ref", someTx.getIdentity());

    var result = db.query("select from SomeTx");
    assertEquals(1, result.stream().count());
    result.close();

    var ref2 = db.newEntity("SomeTx");
    ref2.setProperty("name", "other");

    oneMore.setProperty("ref2", ref2.getIdentity());
    result = db.query("select from SomeTx");
    assertEquals(2, result.stream().count());
    result.close();

    var result1 = db.query("select ref,ref2 from SomeTx where name='bar'");
    assertTrue(result1.hasNext());
    var next = result1.next();
    assertEquals(someTx.getIdentity(), next.getProperty("ref"));
    assertEquals(ref2.getIdentity(), next.getProperty("ref2"));
    result1.close();

    db.commit();
    result1 = db.query("select ref,ref2 from SomeTx where name='bar'");
    assertTrue(result1.hasNext());
    next = result1.next();
    assertEquals(someTx.getIdentity(), next.getProperty("ref"));
    assertEquals(ref2.getIdentity(), next.getProperty("ref2"));
    result1.close();
  }

  @Test
  public void testGenerateIdCounterTransaction() {
    db.begin();

    var doc = ((EntityImpl) db.newEntity("SomeTx"));
    doc.setProperty("name", "Jane");

    db.command("insert into SomeTx set name ='Jane1' ").close();
    db.command("insert into SomeTx set name ='Jane2' ").close();

    var doc1 = ((EntityImpl) db.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane3");

    doc1 = ((EntityImpl) db.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane4");
    db.command("insert into SomeTx set name ='Jane2' ").close();

    var result = db.command("select count(*) from SomeTx");
    System.out.println(result.getExecutionPlan().toString());
    assertTrue(result.hasNext());
    assertEquals(6L, (long) result.next().getProperty("count(*)"));
    result.close();
    assertTrue(db.getTransaction().isActive());

    db.commit();

    var result1 = db.command("select count(*) from SomeTx ");
    assertTrue(result1.hasNext());
    assertEquals(6L, (long) result1.next().getProperty("count(*)"));
    result1.close();
    assertFalse(db.getTransaction().isActive());
  }

  @Test
  public void testGraphInTx() {
    db.createVertexClass("MyV");
    db.createEdgeClass("MyE");
    db.begin();

    var v1 = db.newVertex("MyV");
    var v2 = db.newVertex("MyV");
    var edge = v1.addStateFulEdge(v2, "MyE");
    edge.setProperty("some", "value");
    var result1 = db.query("select out_MyE from MyV  where out_MyE is not null");
    assertTrue(result1.hasNext());
    var val = new ArrayList<Object>();
    val.add(edge.getIdentity());
    assertEquals(result1.next().getProperty("out_MyE"), val);
    result1.close();
  }

  @Test
  public void testRidbagsTx() {
    db.begin();

    var v1 = db.newEntity("SomeTx");
    var v2 = db.newEntity("SomeTx");
    var ridbag = new RidBag(db);
    ridbag.add(v2.getIdentity());
    v1.setProperty("rids", ridbag);
    var result1 = db.query("select rids from SomeTx where rids is not null");
    assertTrue(result1.hasNext());
    var v3 = db.newEntity("SomeTx");
    var val = new ArrayList<Object>();
    val.add(v2.getIdentity());
    assertEquals(result1.next().getProperty("rids"), val);
    result1.close();
    result1 = db.query("select rids from SomeTx where rids is not null");
    assertTrue(result1.hasNext());
    assertEquals(result1.next().getProperty("rids"), val);
    result1.close();
  }

  @Test
  public void testProperIndexingOnDoubleInternalBegin() {
    db.begin();

    var idx = db.newEntity("IndexedTx");
    idx.setProperty("name", FIELD_VALUE);
    var someTx = db.newEntity("SomeTx");
    someTx.setProperty("name", "foo");
    var id = (DBRecord) someTx;
    try (var rs = db.query("select from ?", id)) {
    }

    db.commit();

    // nothing is found (unexpected behaviour)
    try (var rs = db.query("select * from IndexedTx where name = ?", FIELD_VALUE)) {
      assertEquals(1, rs.stream().count());
    }
  }

  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateIndexTx() {
    db.begin();

    var v1 = db.newEntity("UniqueIndexedTx");
    v1.setProperty("name", "a");

    var v2 = db.newEntity("UniqueIndexedTx");
    v2.setProperty("name", "a");
    db.commit();
  }
}
