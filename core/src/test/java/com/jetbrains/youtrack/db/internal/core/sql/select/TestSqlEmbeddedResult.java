package com.jetbrains.youtrack.db.internal.core.sql.select;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class TestSqlEmbeddedResult extends DbTestBase {

  @Test
  public void testEmbeddedRusultTypeNotLink() {
    session.getMetadata().getSchema().createClass("Test");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    var doc1 = ((EntityImpl) session.newEntity());
    doc1.setProperty("format", 1);
    Set<Identifiable> docs = new HashSet<>();
    docs.add(doc1);
    doc.newLinkSet("rel", docs);
    // doc
    session.commit();

    var res =
        session
            .query(
                "select $current as el " + " from (select expand(rel.include('format')) from Test)")
            .toList();
    Assert.assertEquals(1, res.size());
    var ele = res.getFirst();
    Assert.assertTrue(ele.getProperty("el") instanceof EntityImpl);

    res =
        session.query("select rel as el " + " from (select rel from Test)").stream()
            .toList();

    Assert.assertEquals(1, res.size());
    ele = res.getFirst();
    Assert.assertTrue(ele.getProperty("el") instanceof Set<?>);
  }
}
