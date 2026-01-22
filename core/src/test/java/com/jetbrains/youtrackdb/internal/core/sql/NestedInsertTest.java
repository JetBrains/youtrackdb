package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class NestedInsertTest extends DbTestBase {

  @Test
  public void testEmbeddedValueDate() {
    Schema schm = session.getMetadata().getSchema();
    schm.createClass("myClass");

    session.begin();
    var result =
        session.execute(
            "insert into myClass (name,meta) values"
                + " (\"claudio\",{\"@type\":\"d\",\"country\":\"italy\","
                + " \"date\":\"2013-01-01\",\"@fieldTypes\":\"date=a\"}) return @this");
    session.commit();

    session.begin();
    var transaction = session.getActiveTransaction();
    var res = transaction.loadEntity(result.next().asEntity());
    final EntityImpl embedded = res.getProperty("meta");
    Assert.assertNotNull(embedded);

    Assert.assertEquals(2, embedded.getPropertiesCount());
    Assert.assertEquals("italy", embedded.getProperty("country"));
    Assert.assertEquals(java.util.Date.class, embedded.getProperty("date").getClass());
    session.commit();
  }

  @Test
  public void testLinkedNested() {
    Schema schm = session.getMetadata().getSchema();
    var cl = schm.createClass("myClass");
    var linked = schm.createClass("Linked");
    cl.createProperty("some", PropertyType.LINK, linked);

    session.begin();
    var result =
        session.execute(
            "insert into myClass set some ={\"@class\":\"Linked\",\"name\":\"a"
                + " name\"} return @this");

    session.commit();
    session.begin();
    var transaction = session.getActiveTransaction();
    var res = transaction.loadEntity(result.next().asEntity());
    final EntityImpl ln = res.getProperty("some");
    Assert.assertNotNull(ln);
    Assert.assertTrue(ln.getIdentity().isPersistent());
    Assert.assertEquals(2, ln.getPropertiesCount());
    Assert.assertEquals("a name", ln.getProperty("name"));
    session.commit();
  }
}
