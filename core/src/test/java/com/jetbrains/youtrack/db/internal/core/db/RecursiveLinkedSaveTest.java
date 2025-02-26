package com.jetbrains.youtrack.db.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Test;

/**
 *
 */
public class RecursiveLinkedSaveTest extends DbTestBase {

  @Test
  public void testTxLinked() {
    session.getMetadata().getSchema().createClass("Test");
    session.begin();
    var doc = (EntityImpl) session.newEntity("Test");
    var doc1 = (EntityImpl) session.newEntity("Test");
    doc.field("link", doc1);
    var doc2 = (EntityImpl) session.newEntity("Test");
    doc1.field("link", doc2);
    doc2.field("link", doc);
    session.commit();
    session.begin();
    assertEquals(3, session.countClass("Test"));
    doc = session.load(doc.getIdentity());
    doc1 = doc.field("link");
    doc2 = doc1.field("link");
    assertEquals(doc, doc2.field("link"));
    session.commit();
  }
}
