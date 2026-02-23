package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of DELETE EDGE SQL statements.
 */
public class DeleteEdgeStatementExecutionTest extends DbTestBase {

  @Test
  public void testDeleteSingleEdge() {
    var vertexClassName = "testDeleteSingleEdgeV";
    session.createVertexClass(vertexClassName);

    var edgeClassName = "testDeleteSingleEdgeE";
    session.createEdgeClass(edgeClassName);

    Vertex prev = null;
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(vertexClassName);
      v1.setProperty("name", "a" + i);
      if (prev != null) {
        var activeTx = session.getActiveTransaction();
        prev = activeTx.load(prev);
        prev.addEdge(v1, edgeClassName);
      }
      prev = v1;
      session.commit();
    }

    session.begin();
    var rs = session.query("SELECT expand(out()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    session.execute(
            "DELETE EDGE "
                + edgeClassName
                + " from (SELECT FROM "
                + vertexClassName
                + " where name = 'a1') to (SELECT FROM "
                + vertexClassName
                + " where name = 'a2')")
        .close();
    session.commit();

    session.begin();
    rs = session.query("SELECT FROM " + edgeClassName);
    Assert.assertEquals(8, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(out()) FROM " + vertexClassName + " where name = 'a1'");
    Assert.assertEquals(0, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName + " where name = 'a2'");
    Assert.assertEquals(0, rs.stream().count());
    rs.close();
    session.commit();
  }

  @Test
  public void testDeleteAll() {
    var vertexClassName = "testDeleteAllV";
    session.createVertexClass(vertexClassName);

    var edgeClassName = "testDeleteAllE";
    session.createEdgeClass(edgeClassName);

    Vertex prev = null;
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(vertexClassName);
      v1.setProperty("name", "a" + i);
      if (prev != null) {
        var activeTx = session.getActiveTransaction();
        prev = activeTx.load(prev);
        prev.addEdge(v1, edgeClassName);
      }
      prev = v1;
      session.commit();
    }

    session.begin();
    var rs = session.query("SELECT expand(out()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    session.execute("DELETE EDGE " + edgeClassName).close();
    session.commit();

    session.begin();
    rs = session.query("SELECT FROM " + edgeClassName);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(out()) FROM " + vertexClassName);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();
    session.commit();
  }
}
