package com.jetbrains.youtrackdb.internal.core.db.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class DirtyManagerReferenceCleanTest extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();

    session.getMetadata().getSlowMutableSchema().createClass("test");
  }

  @Test
  public void testReferDeletedDocument() {
    var id = session.computeInTx(transaction -> {
      var doc = (EntityImpl) session.newEntity();
      var doc1 = (EntityImpl) session.newEntity();
      doc1.setProperty("aa", "aa");
      doc.setProperty("ref", doc1);
      doc.getProperty("bb");

      return doc.getIdentity();
    });

    var rid1 = session.computeInTx(transaction -> {
      var doc = session.loadEntity(id.getIdentity());
      var doc1 = doc.getEntity("ref");
      doc1.delete();
      doc.setProperty("ab", "ab");
      return doc1.getIdentity();
    });

    session.executeInTx(transaction -> {
      try {
        session.loadEntity(rid1);
        Assert.fail();
      } catch (RecordNotFoundException e) {
        //
      }
      var doc = session.loadEntity(id.getIdentity());
      Assert.assertEquals("ab", doc.getProperty("ab"));
    });
  }
}
