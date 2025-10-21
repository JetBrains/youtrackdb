package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class NestedInsertTest extends DbTestBase {

  @Test
  public void testEmbeddedValueDate() {
    Schema schm = session.getMetadata().getSlowMutableSchema();
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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("myClass").
            createSchemaClass("Linked").
            schemaClass("myClass")
            .createSchemaProperty("some", PropertyType.LINK, "Linked")
    );

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
