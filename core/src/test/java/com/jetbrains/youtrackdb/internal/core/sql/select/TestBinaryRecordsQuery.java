package com.jetbrains.youtrackdb.internal.core.sql.select;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Blob;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class TestBinaryRecordsQuery extends DbTestBase {

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    session.addBlobCollection("BlobCollection");
  }

  @Test
  public void testSelectBinary() {
    session.begin();
    var record = session.newBlob("blabla".getBytes());
    session.commit();

    session.begin();
    var res = session.query("select from " + record.getIdentity());

    assertEquals(1, res.stream().count());
    session.commit();
  }

  @Test
  public void testSelectRidBinary() {
    session.begin();
    var blob = session.newBlob("blabla".getBytes());

    var res = session.query("select @rid from " + blob.getIdentity());
    assertEquals(1, res.stream().count());
    session.commit();
  }

  @Test
  public void testDeleteBinary() {
    session.begin();
    var rec = session.newBlob("blabla".getBytes());
    session.commit();

    session.begin();
    var res = session.execute("delete from (select from ?)", rec.getIdentity());
    assertEquals(1, (long) res.next().getProperty("count"));
    try {
      session.load(rec.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    session.commit();
  }

  @Test
  public void testSelectDeleteBinary() {
    session.begin();
    var rec = session.newBlob("blabla".getBytes());
    session.commit();

    session.getMetadata().getSchema().createClass("RecordPointer");

    session.begin();
    var doc = (EntityImpl) session.newEntity("RecordPointer");
    var activeTx = session.getActiveTransaction();
    doc.setProperty("ref", activeTx.<Blob>load(rec));
    session.commit();

    session.begin();
    var res =
        session.execute(
            "delete from (select expand(ref) from ?)", doc.getIdentity());

    assertEquals(1, (long) res.next().getProperty("count"));
    try {
      session.load(rec.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    session.commit();
  }

  @Test
  public void testDeleteFromSelectBinary() {
    session.begin();
    var rec = session.newBlob("blabla".getBytes());
    var rec1 = session.newBlob("blabla".getBytes());
    session.commit();

    session.getMetadata().getSchema().createClass("RecordPointer");

    session.begin();
    var doc = (EntityImpl) session.newEntity("RecordPointer");
    var activeTx1 = session.getActiveTransaction();
    doc.setProperty("ref", activeTx1.<Blob>load(rec));
    session.commit();

    session.begin();
    var doc1 = (EntityImpl) session.newEntity("RecordPointer");
    var activeTx = session.getActiveTransaction();
    doc1.setProperty("ref", activeTx.<Blob>load(rec1));
    session.commit();

    session.begin();
    var res = session.execute("delete from (select expand(ref) from RecordPointer)");
    assertEquals(2, (long) res.next().getProperty("count"));
    session.commit();

    session.begin();
    try {
      session.load(rec.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }

    try {
      session.load(rec1.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    session.commit();
  }
}
