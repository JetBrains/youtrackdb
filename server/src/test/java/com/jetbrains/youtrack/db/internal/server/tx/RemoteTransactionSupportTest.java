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

public class RemoteTransactionSupportTest extends BaseServerMemoryDatabase {

  private static final String FIELD_VALUE = "VALUE";

  public void beforeTest() {
    GlobalConfiguration.CLASS_MINIMUM_CLUSTERS.setValue(1);
    super.beforeTest();

    session.createClass("SomeTx");
    session.createClass("SomeTx2");

    var klass = session.createClass("IndexedTx");
    klass.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var uniqueClass = session.createClass("UniqueIndexedTx");
    uniqueClass.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
  }

  @Test
  public void testQueryUpdateUpdatedInTxTransaction() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("SomeTx"));
    doc.setProperty("name", "Joe");
    session.commit();

    session.begin();
    EntityImpl doc2 = session.load(((Identifiable) doc).getIdentity());
    doc2.setProperty("name", "Jane");
    var result = session.execute("update SomeTx set name='July' where name = 'Jane' ");
    assertEquals(1L, (long) result.next().getProperty("count"));
    EntityImpl doc3 = session.load(((Identifiable) doc).getIdentity());
    assertEquals("July", doc3.getProperty("name"));
    session.rollback();
  }

  @Test
  public void testResetUpdatedInTxTransaction() {
    session.begin();

    var doc1 = ((EntityImpl) session.newEntity());
    doc1.setProperty("name", "Jane");
    var doc2 = ((EntityImpl) session.newEntity("SomeTx"));
    doc2.setProperty("name", "Jane");
    var result = session.execute("update SomeTx set name='July' where name = 'Jane' ");
    assertEquals(1L, (long) result.next().getProperty("count"));
    assertEquals("July", doc2.getProperty("name"));
    result.close();
  }

  @Test
  public void testQueryUpdateCreatedInTxTransaction() {
    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane");

    var docx = ((EntityImpl) session.newEntity("SomeTx2"));
    docx.setProperty("name", "Jane");

    var result = session.execute("update SomeTx set name='July' where name = 'Jane' ");
    assertTrue(result.hasNext());
    assertEquals(1L, (long) result.next().getProperty("count"));
    EntityImpl doc2 = session.load(((Identifiable) doc1).getIdentity());
    assertEquals("July", doc2.getProperty("name"));
    assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testRollbackTxTransaction() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("SomeTx"));
    doc.setProperty("name", "Jane");
    session.commit();

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane");

    var result = session.execute("update SomeTx set name='July' where name = 'Jane' ");
    assertTrue(result.hasNext());
    assertEquals(2L, (long) result.next().getProperty("count"));
    result.close();
    session.rollback();

    var tx = session.begin();
    var result1 = tx.query("select count(*) from SomeTx where name='Jane'");
    assertTrue(result1.hasNext());
    assertEquals(1L, (long) result1.next().getProperty("count(*)"));
    result1.close();
    tx.commit();
  }

  @Test
  public void testRollbackTxCheckStatusTransaction() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("SomeTx"));
    doc.setProperty("name", "Jane");
    session.commit();

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane");

    var result = session.execute("select count(*) from SomeTx where name='Jane' ");
    assertTrue(result.hasNext());
    assertEquals(2L, (long) result.next().getProperty("count(*)"));

    assertTrue(session.getTransactionInternal().isActive());
    result.close();
    session.rollback();

    assertFalse(session.getTransactionInternal().isActive());

    var tx = session.begin();
    var result1 = tx.query("select count(*) from SomeTx where name='Jane'");
    assertTrue(result1.hasNext());
    assertEquals(1L, (long) result1.next().getProperty("count(*)"));

    result1.close();
    tx.commit();
  }

  @Test
  public void testDownloadTransactionAtStart() {
    session.begin();

    session.execute("insert into SomeTx set name ='Jane' ").close();
    assertEquals(1, session.getTransactionInternal().getEntryCount());
    session.commit();
  }

  @Test
  public void testQueryUpdateCreatedInTxSQLTransaction() {
    session.begin();

    session.execute("insert into SomeTx set name ='Jane' ").close();

    var result = session.execute("update SomeTx set name='July' where name = 'Jane' ");
    assertTrue(result.hasNext());
    assertEquals(1L, (long) result.next().getProperty("count"));
    result.close();
    var result1 = session.query("select from SomeTx where name='July'");
    assertTrue(result1.hasNext());
    assertEquals("July", result1.next().getProperty("name"));
    assertFalse(result.hasNext());
    result1.close();

    session.commit();
  }

  @Test
  public void testQueryDeleteTxSQLTransaction() {
    session.begin();
    var someTx = session.newEntity("SomeTx");
    someTx.setProperty("name", "foo");
    session.commit();

    session.begin();
    session.execute("delete from SomeTx");
    session.commit();

    var tx = session.begin();
    var result = session.execute("select from SomeTx");
    assertFalse(result.hasNext());
    result.close();
    tx.commit();
  }

  @Test
  public void testDoubleSaveTransaction() {
    session.begin();
    var someTx = session.newEntity("SomeTx");
    someTx.setProperty("name", "foo");
    assertEquals(1, session.getTransactionInternal().getEntryCount());
    assertEquals(1, session.countClass("SomeTx"));
    session.commit();
    var tx = session.begin();
    assertEquals(1, session.countClass("SomeTx"));
    tx.commit();
  }

  @Test
  public void testDoubleSaveDoubleFlushTransaction() {
    session.begin();
    var someTx = session.newEntity("SomeTx");
    someTx.setProperty("name", "foo");
    var result = session.query("select from SomeTx");
    assertEquals(1, result.stream().count());
    result.close();
    result = session.query("select from SomeTx");
    assertEquals(1, result.stream().count());
    result.close();
    assertEquals(1, session.getTransactionInternal().getEntryCount());
    assertEquals(1, session.countClass("SomeTx"));
    session.commit();
    var tx = session.begin();
    assertEquals(1, session.countClass("SomeTx"));
    tx.commit();
  }

  @Test
  public void testRefFlushedInTransaction() {
    session.begin();
    var someTx = session.newEntity("SomeTx");
    someTx.setProperty("name", "foo");

    var oneMore = session.newEntity("SomeTx");
    oneMore.setProperty("name", "bar");
    oneMore.setProperty("ref", someTx);

    var result = session.query("select from SomeTx");
    assertEquals(2, result.stream().count());
    result.close();
    session.commit();

    var tx = session.begin();
    var result1 = session.query("select ref from SomeTx where name='bar'");
    assertTrue(result1.hasNext());
    assertEquals(someTx.getIdentity(), result1.next().getProperty("ref"));
    result1.close();
    tx.commit();
  }

  @Test
  public void testDoubleRefFlushedInTransaction() {
    session.begin();
    var someTx = session.newEntity("SomeTx");
    someTx.setProperty("name", "foo");

    var oneMore = session.newEntity("SomeTx");
    oneMore.setProperty("name", "bar");
    oneMore.setProperty("ref", someTx.getIdentity());

    var result = session.query("select from SomeTx");
    assertEquals(2, result.stream().count());
    result.close();

    var ref2 = session.newEntity("SomeTx");
    ref2.setProperty("name", "other");

    oneMore.setProperty("ref2", ref2.getIdentity());

    result = session.query("select from SomeTx");
    assertEquals(3, result.stream().count());
    result.close();

    var result1 = session.query("select ref,ref2 from SomeTx where name='bar'");
    assertTrue(result1.hasNext());
    var next = result1.next();
    assertEquals(someTx.getIdentity(), next.getProperty("ref"));
    assertEquals(ref2.getIdentity(), next.getProperty("ref2"));
    result1.close();

    session.commit();

    var tx = session.begin();
    result1 = session.query("select ref,ref2 from SomeTx where name='bar'");
    assertTrue(result1.hasNext());
    next = result1.next();
    assertEquals(someTx.getIdentity(), next.getProperty("ref"));
    assertEquals(ref2.getIdentity(), next.getProperty("ref2"));
    result1.close();
    tx.commit();
  }

  @Test
  public void testGenerateIdCounterTransaction() {
    session.begin();

    var doc = ((EntityImpl) session.newEntity("SomeTx"));
    doc.setProperty("name", "Jane");

    session.execute("insert into SomeTx set name ='Jane1' ").close();
    session.execute("insert into SomeTx set name ='Jane2' ").close();

    var doc1 = ((EntityImpl) session.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane3");

    doc1 = ((EntityImpl) session.newEntity("SomeTx"));
    doc1.setProperty("name", "Jane4");
    session.execute("insert into SomeTx set name ='Jane2' ").close();

    var result = session.execute("select count(*) from SomeTx");

    assertTrue(result.hasNext());
    assertEquals(6L, (long) result.next().getProperty("count(*)"));
    result.close();
    assertTrue(session.getTransactionInternal().isActive());

    session.commit();

    var tx = session.begin();
    var result1 = session.execute("select count(*) from SomeTx ");
    assertTrue(result1.hasNext());
    assertEquals(6L, (long) result1.next().getProperty("count(*)"));
    result1.close();
    tx.commit();

    assertFalse(session.getTransactionInternal().isActive());
  }

  @Test
  public void testGraphInTx() {
    session.createVertexClass("MyV");
    session.createEdgeClass("MyE");

    var tx = session.begin();
    var v1 = tx.newVertex("MyV");
    var v2 = tx.newVertex("MyV");
    var edge = v1.addStateFulEdge(v2, "MyE");
    edge.setProperty("some", "value");
    var result1 = tx.query("select out_MyE from MyV  where out_MyE is not null");
    assertTrue(result1.hasNext());
    var val = new ArrayList<>();
    val.add(edge.getIdentity());
    assertEquals(result1.next().getProperty("out_MyE"), val);
    result1.close();
    tx.commit();
  }

  @Test
  public void testRidbagsTx() {
    var tx = session.begin();
    var v1 = tx.newEntity("SomeTx");
    var v2 = tx.newEntity("SomeTx");

    var ridbag = new RidBag(session);
    ridbag.add(v2.getIdentity());
    v1.setProperty("rids", ridbag);

    var result1 = tx.query("select rids from SomeTx where rids is not null");
    assertTrue(result1.hasNext());

    tx.newEntity("SomeTx");
    var val = new ArrayList<>();
    val.add(v2.getIdentity());
    assertEquals(result1.next().getProperty("rids"), val);
    result1.close();

    result1 = tx.query("select rids from SomeTx where rids is not null");
    assertTrue(result1.hasNext());
    assertEquals(result1.next().getProperty("rids"), val);
    result1.close();
    tx.commit();
  }

  @Test
  public void testProperIndexingOnDoubleInternalBegin() {
    session.begin();

    var idx = session.newEntity("IndexedTx");
    idx.setProperty("name", FIELD_VALUE);

    var someTx = session.newEntity("SomeTx");
    someTx.setProperty("name", "foo");

    var id = (DBRecord) someTx;
    try (var rs = session.query("select from ?", id)) {
      assertEquals(1, rs.stream().count());
    }

    session.commit();

    var tx = session.begin();
    try (var rs = tx.query("select * from IndexedTx where name = ?", FIELD_VALUE)) {
      assertEquals(1, rs.stream().count());
    }
    tx.commit();
  }

  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateIndexTx() {
    session.begin();

    var v1 = session.newEntity("UniqueIndexedTx");
    v1.setProperty("name", "a");

    var v2 = session.newEntity("UniqueIndexedTx");
    v2.setProperty("name", "a");
    session.commit();
  }
}
