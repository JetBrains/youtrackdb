package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateLinkStatementExecutionTest extends DbTestBase {

  @Test
  public void testBasic() {
    var basic1 = session.getMetadata().getSlowMutableSchema().createClass("Basic1");
    basic1.createProperty("theLink", PropertyType.LINK);

    var basic2 = session.getMetadata().getSlowMutableSchema().createClass("Basic2");
    basic2.createProperty("theLink", PropertyType.LINK);

    session.begin();
    session.execute("insert into Basic1 set pk = 'pkb1_1', fk = 'pkb2_1'").close();
    session.execute("insert into Basic1 set pk = 'pkb1_2', fk = 'pkb2_2'").close();

    session.execute("insert into Basic2 set pk = 'pkb2_1'").close();
    session.execute("insert into Basic2 set pk = 'pkb2_2'").close();

    session.execute("CREATE LINK theLink type link FROM Basic1.fk TO Basic2.pk ").close();
    session.commit();

    session.begin();
    var result = session.query("select pk, theLink.pk as other from Basic1 order by pk");
    Assert.assertTrue(result.hasNext());

    var item = result.next();
    var otherKey = item.getProperty("other");
    Assert.assertNotNull(otherKey);

    Assert.assertEquals(otherKey, "pkb2_1");

    Assert.assertTrue(result.hasNext());

    item = result.next();
    otherKey = item.getProperty("other");
    Assert.assertEquals(otherKey, "pkb2_2");
    session.commit();
  }

  @Test
  public void testInverse() throws Exception {
    var inverse1 = session.getMetadata().getSlowMutableSchema().createClass("Inverse1");
    inverse1.createProperty("theLink", PropertyType.LINKSET);

    var inverse2 = session.getMetadata().getSlowMutableSchema().createClass("Inverse2");
    inverse2.createProperty("theLink", PropertyType.LINKSET);

    session.begin();
    session.execute("insert into Inverse1 set pk = 'pkb1_1', fk = 'pkb2_1'").close();
    session.execute("insert into Inverse1 set pk = 'pkb1_2', fk = 'pkb2_2'").close();
    session.execute("insert into Inverse1 set pk = 'pkb1_3', fk = 'pkb2_2'").close();

    session.execute("insert into Inverse2 set pk = 'pkb2_1'").close();
    session.execute("insert into Inverse2 set pk = 'pkb2_2'").close();

    session.execute("CREATE LINK theLink TYPE LINKSET FROM Inverse1.fk TO Inverse2.pk INVERSE")
        .close();
    session.commit();

    session.begin();
    var result = session.query("select pk, theLink.pk as other from Inverse2 order by pk");
    Assert.assertTrue(result.hasNext());
    var item = result.next();

    var otherKeys = item.getProperty("other");
    Assert.assertNotNull(otherKeys);
    Assert.assertTrue(otherKeys instanceof List);
    Assert.assertEquals(((List) otherKeys).get(0), "pkb1_1");

    Assert.assertTrue(result.hasNext());
    item = result.next();
    otherKeys = item.getProperty("other");
    Assert.assertNotNull(otherKeys);
    Assert.assertTrue(otherKeys instanceof List);
    Assert.assertEquals(((List) otherKeys).size(), 2);
    Assert.assertTrue(((List) otherKeys).contains("pkb1_2"));
    Assert.assertTrue(((List) otherKeys).contains("pkb1_3"));
    session.commit();
  }
}
