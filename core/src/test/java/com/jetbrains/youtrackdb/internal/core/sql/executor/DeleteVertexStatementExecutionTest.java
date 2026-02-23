package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of DELETE VERTEX SQL statements.
 */
public class DeleteVertexStatementExecutionTest extends DbTestBase {

  @Test
  public void testDeleteSingleVertex() {
    var className = "testDeleteSingleVertex";
    session.createVertexClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(className);
      v1.setProperty("name", "a" + i);
      session.commit();
    }

    session.begin();
    session.execute("DELETE VERTEX " + className + " WHERE name = 'a3'").close();
    var rs = session.query("SELECT FROM " + className);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();
    session.commit();
  }

  @Test
  public void testDeleteAllVertices() {
    var className = "testDeleteAllVertices";
    session.createVertexClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(className);
      v1.setProperty("name", "a" + i);
      session.commit();
    }

    session.begin();
    session.execute("DELETE VERTEX " + className).close();
    var rs = session.query("SELECT FROM " + className);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();
    session.commit();
  }

  @Test
  public void testFilterClass() {
    var className1 = "testDeleteAllVertices1";
    session.createVertexClass(className1);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(className1);
      v1.setProperty("name", "a" + i);
      session.commit();
    }

    var className2 = "testDeleteAllVertices2";
    session.createVertexClass(className2);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(className2);
      v1.setProperty("name", "a" + i);
      session.commit();
    }

    session.begin();
    session.execute("DELETE VERTEX " + className1).close();
    var rs = session.query("SELECT FROM " + className1);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();
    session.commit();

    session.begin();
    rs = session.query("SELECT FROM " + className2);
    Assert.assertEquals(10, rs.stream().count());
    rs.close();
    session.commit();
  }
}
