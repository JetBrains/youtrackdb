package com.jetbrains.youtrack.db.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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

    assertEquals(3001, session.countClass("Test"));

    for (var d : docs) {
      assertEquals(1, d.getVersion());
    }
  }
}
