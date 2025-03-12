package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class NestedInsertTest extends DbTestBase {

  @Test
  public void testEmbeddedValueDate() {
    Schema schm = session.getMetadata().getSchema();
    schm.createClass("myClass");

    session.begin();
    var result =
        session.command(
            "insert into myClass (name,meta) values"
                + " (\"claudio\",{\"@type\":\"d\",\"country\":\"italy\","
                + " \"date\":\"2013-01-01\",\"@fieldTypes\":\"date=a\"}) return @this");
    session.commit();

    final EntityImpl res = ((Identifiable) result.next().getProperty("@this")).getRecord(session);
    final EntityImpl embedded = res.field("meta");
    Assert.assertNotNull(embedded);

    Assert.assertEquals(2, embedded.fields());
    Assert.assertEquals("italy", embedded.field("country"));
    Assert.assertEquals(java.util.Date.class, embedded.field("date").getClass());
  }

  @Test
  public void testLinkedNested() {
    Schema schm = session.getMetadata().getSchema();
    var cl = schm.createClass("myClass");
    var linked = schm.createClass("Linked");
    cl.createProperty("some", PropertyType.LINK, linked);

    session.begin();
    var result =
        session.command(
            "insert into myClass set some ={\"@type\":\"d\",\"@class\":\"Linked\",\"name\":\"a"
                + " name\"} return @this");
    session.commit();

    final EntityImpl res = ((Identifiable) result.next().getProperty("@this")).getRecord(session);
    final EntityImpl ln = res.field("some");
    Assert.assertNotNull(ln);
    Assert.assertTrue(ln.getIdentity().isPersistent());
    Assert.assertEquals(1, ln.fields());
    Assert.assertEquals("a name", ln.field("name"));
  }
}
