package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Test;

/**
 *
 */
public class RecursiveLinkedSaveTest extends DbTestBase {

  @Test
  public void testTxLinked() {
    session.getMetadata().getSlowMutableSchema().createClass("Test");
    session.begin();
    var doc = (EntityImpl) session.newEntity("Test");
    var doc1 = (EntityImpl) session.newEntity("Test");
    doc.setProperty("link", doc1);
    var doc2 = (EntityImpl) session.newEntity("Test");
    doc1.setProperty("link", doc2);
    doc2.setProperty("link", doc);
    session.commit();
    session.begin();
    assertEquals(3, session.countClass("Test"));
    doc = session.load(doc.getIdentity());
    doc1 = doc.getProperty("link");
    doc2 = doc1.getProperty("link");
    assertEquals(doc, doc2.getProperty("link"));
    session.commit();
  }
}
