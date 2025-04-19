package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class TraverseStatementExecutionTest extends DbTestBase {

  @Test
  @Ignore
  public void testPlainTraverse() {
    var classPrefix = "testPlainTraverse_";
    session.createVertexClass(classPrefix + "V");
    session.createEdgeClass(classPrefix + "E");

    session.begin();
    session.execute("create vertex " + classPrefix + "V set name = 'a'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'b'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'c'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'd'").close();

    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'a') to (select from "
                + classPrefix
                + "V where name = 'b')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'b') to (select from "
                + classPrefix
                + "V where name = 'c')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'c') to (select from "
                + classPrefix
                + "V where name = 'd')")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query("traverse out() from (select from " + classPrefix + "V where name = 'a')");

    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(i, ((ResultInternal) item).getMetadata("$depth"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  @Ignore
  public void testWithDepth() {
    var classPrefix = "testWithDepth_";
    session.createVertexClass(classPrefix + "V");
    session.createEdgeClass(classPrefix + "E");

    session.begin();
    session.execute("create vertex " + classPrefix + "V set name = 'a'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'b'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'c'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'd'").close();

    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'a') to (select from "
                + classPrefix
                + "V where name = 'b')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'b') to (select from "
                + classPrefix
                + "V where name = 'c')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'c') to (select from "
                + classPrefix
                + "V where name = 'd')")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query(
            "traverse out() from (select from "
                + classPrefix
                + "V where name = 'a') WHILE $depth < 2");

    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(i, ((ResultInternal) item).getMetadata("$depth"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  @Ignore
  public void testMaxDepth() {
    var classPrefix = "testMaxDepth";
    session.createVertexClass(classPrefix + "V");
    session.createEdgeClass(classPrefix + "E");

    session.begin();
    session.execute("create vertex " + classPrefix + "V set name = 'a'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'b'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'c'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'd'").close();

    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'a') to (select from "
                + classPrefix
                + "V where name = 'b')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'b') to (select from "
                + classPrefix
                + "V where name = 'c')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'c') to (select from "
                + classPrefix
                + "V where name = 'd')")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query(
            "traverse out() from (select from " + classPrefix + "V where name = 'a') MAXDEPTH 1");

    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(i, ((ResultInternal) item).getMetadata("$depth"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();

    result =
        session.query(
            "traverse out() from (select from " + classPrefix + "V where name = 'a') MAXDEPTH 2");

    for (var i = 0; i < 3; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(i, ((ResultInternal) item).getMetadata("$depth"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  @Ignore
  public void testBreadthFirst() {
    var classPrefix = "testBreadthFirst_";
    session.createVertexClass(classPrefix + "V");
    session.createEdgeClass(classPrefix + "E");

    session.begin();
    session.execute("create vertex " + classPrefix + "V set name = 'a'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'b'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'c'").close();
    session.execute("create vertex " + classPrefix + "V set name = 'd'").close();

    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'a') to (select from "
                + classPrefix
                + "V where name = 'b')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'b') to (select from "
                + classPrefix
                + "V where name = 'c')")
        .close();
    session.execute(
            "create edge "
                + classPrefix
                + "E from (select from "
                + classPrefix
                + "V where name = 'c') to (select from "
                + classPrefix
                + "V where name = 'd')")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query(
            "traverse out() from (select from "
                + classPrefix
                + "V where name = 'a') STRATEGY BREADTH_FIRST");

    for (var i = 0; i < 4; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(i, ((ResultInternal) item).getMetadata("$depth"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  @Ignore
  public void testTraverseInBatchTx() {
    var script = "";
    script += "";

    script += "drop class testTraverseInBatchTx_V if exists unsafe;";
    script += "create class testTraverseInBatchTx_V extends V;";
    script += "create property testTraverseInBatchTx_V.name STRING;";
    script += "drop class testTraverseInBatchTx_E if exists unsafe;";
    script += "create class testTraverseInBatchTx_E extends E;";

    script += "begin;";
    script += "insert into testTraverseInBatchTx_V(name) values ('a'), ('b'), ('c');";
    script +=
        "create edge testTraverseInBatchTx_E from (select from testTraverseInBatchTx_V where name ="
            + " 'a') to (select from testTraverseInBatchTx_V where name = 'b');";
    script +=
        "create edge testTraverseInBatchTx_E from (select from testTraverseInBatchTx_V where name ="
            + " 'b') to (select from testTraverseInBatchTx_V where name = 'c');";
    script +=
        "let top = (select @rid as rid from (traverse in('testTraverseInBatchTx_E') from (select from"
            + " testTraverseInBatchTx_V where name='c')) where in('testTraverseInBatchTx_E').size()"
            + " == 0);";
    script += "commit;";
    script += "return $top";

    var result = session.computeScript("sql", script);
    Assert.assertTrue(result.hasNext());
    var item = result.next();

    var val = item.getEmbeddedList("value");
    Assert.assertEquals(1, val.size());
    result.close();
  }
}
