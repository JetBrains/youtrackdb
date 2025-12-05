package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 *
 */
@RunWith(JUnit4.class)
public class CommandExecutorSQLCreateEdgeTest extends DbTestBase {

  private EntityImpl owner1;
  private EntityImpl owner2;

  public void beforeTest() throws Exception {
    super.beforeTest();

    final Schema schema = session.getMetadata().getSchema();
    schema.createClass("Owner", schema.getClass("V"));
    schema.createClass("link", schema.getClass("E"));

    session.begin();
    owner1 = (EntityImpl) session.newVertex("Owner");
    owner1.setProperty("id", 1);

    owner2 = (EntityImpl) session.newVertex("Owner");
    owner2.setProperty("id", 2);

    session.commit();
  }

  @Test
  public void testParametersBinding() {
    session.begin();
    session.execute(
            "CREATE EDGE link from "
                + owner1.getIdentity()
                + " TO "
                + owner2.getIdentity()
                + " SET foo = ?",
            "123")
        .close();
    session.commit();

    session.begin();
    var list = session.query("SELECT FROM link");

    var res = list.next();
    Assert.assertEquals(res.getProperty("foo"), "123");
    Assert.assertFalse(list.hasNext());
    session.commit();
  }

  @Test
  public void testSubqueryParametersBinding() throws Exception {
    final var params = new HashMap<String, Object>();
    params.put("foo", "bar");
    params.put("fromId", 1);
    params.put("toId", 2);

    session.begin();
    session.execute(
            "CREATE EDGE link from (select from Owner where id = :fromId) TO (select from Owner"
                + " where id = :toId) SET foo = :foo",
            params)
        .close();
    session.commit();

    session.begin();
    var list = session.query("SELECT FROM link");

    var edge = list.next();
    Assert.assertEquals(edge.getProperty("foo"), "bar");
    Assert.assertEquals(((EntityImpl) edge.asEntity()).getPropertyInternal("out"),
        owner1.getIdentity());
    Assert.assertEquals(((EntityImpl) edge.asEntity()).getPropertyInternal("in"),
        owner2.getIdentity());
    Assert.assertFalse(list.hasNext());
    session.commit();
  }

  @Test
  public void testBatch() throws Exception {
    for (var i = 0; i < 20; ++i) {
      session.begin();
      session.execute("CREATE VERTEX Owner SET testbatch = true, id = ?", i).close();
      session.commit();
    }

    session.begin();
    var edges =
        session.execute(
            "CREATE EDGE link from (select from owner where testbatch = true and id > 0) TO (select"
                + " from owner where testbatch = true and id = 0) batch 10",
            "456");
    session.commit();

    Assert.assertEquals(edges.stream().count(), 19);

    session.begin();
    var list = session.query("select from owner where testbatch = true and id = 0");

    var res = list.next();
    Assert.assertEquals(
        ((LinkBag) ((EntityImpl) res.asEntity()).getPropertyInternal("in_link")).size(), 19);
    Assert.assertFalse(list.hasNext());
    session.commit();
  }

  @Test
  public void testEdgeConstraints() {
    session.computeScript(
            "sql",
            "create class E2 extends E;"
                + "create property E2.x LONG;"
                + "create property E2.in LINK;"
                + "alter property E2.in MANDATORY true;"
                + "create property E2.out LINK;"
                + "alter property E2.out MANDATORY true;"
                + "create class E1 extends E;"
                + "create property E1.x LONG;"
                + "alter property E1.x MANDATORY true;"
                + "create property E1.in LINK;"
                + "alter property E1.in MANDATORY true;"
                + "create property E1.out LINK;"
                + "alter property E1.out MANDATORY true;"
                + "create class FooType extends V;"
                + "create property FooType.name STRING;"
                + "alter property FooType.name MANDATORY true;")
        .close();

    session.computeScript(
            "sql",
            "begin;"
                + "let $v1 = create vertex FooType content {'name':'foo1'};"
                + "let $v2 = create vertex FooType content {'name':'foo2'};"
                + "create edge E1 from $v1 to $v2 content {'x':22};"
                + "create edge E1 from $v1 to $v2 set x=22;"
                + "create edge E2 from $v1 to $v2 content {'x':345};"
                + "commit;")
        .close();
  }
}
