package com.jetbrains.youtrack.db.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class TestNullLinkInCollection extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();
    session.getMetadata().getSchema().createClass("Test");
  }

  @Test
  public void testLinkListRemovedRecord() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    List<RecordId> docs = new ArrayList<>();
    docs.add(new RecordId(10, 20));
    doc.setProperty("items", docs, PropertyType.LINKLIST);
    session.commit();

    try (var res = session.query("select items from Test")) {
      assertEquals(new RecordId(10, 20), ((List) res.next().getProperty("items")).get(0));
    }
  }

  @Test
  public void testLinkSetRemovedRecord() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    Set<RecordId> docs = new HashSet<>();
    docs.add(new RecordId(10, 20));
    doc.setProperty("items", docs, PropertyType.LINKSET);
    session.commit();

    try (var res = session.query("select items from Test")) {
      Assert.assertEquals(
          new RecordId(10, 20), ((Set) res.next().getProperty("items")).iterator().next());
    }
  }
}
