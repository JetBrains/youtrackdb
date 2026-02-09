package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class DeepLinkedDocumentSaveTest extends DbTestBase {

  @Test
  public void testLinkedTx() {
    final Set<EntityImpl> docs = new HashSet<>();

    session.getMetadata().getSchema().createClass("Test");

    session.begin();
    var doc = (EntityImpl) session.newEntity("Test");
    docs.add(doc);
    for (var i = 0; i < 3000; i++) {
      doc = (EntityImpl) session.newEntity("Test");
      doc.setProperty("linked", doc);
      docs.add(doc);
    }
    session.commit();

    session.begin();
    assertEquals(3001, session.countClass("Test"));
    session.rollback();
  }
}
