package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 *
 */
public class MoveVertexStatementExecutionTest {

  @Rule
  public TestName name = new TestName();

  private DatabaseSession session;

  private YouTrackDB youTrackDB;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    session = youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @After
  public void after() {
    session.close();
    youTrackDB.close();
  }

  @Test
  public void testMoveVertex() {
    var vertexClassName1 = "testMoveVertexV1";
    var vertexClassName2 = "testMoveVertexV2";
    var edgeClassName = "testMoveVertexE";
    session.createVertexClass(vertexClassName1);
    session.createVertexClass(vertexClassName2);
    session.createEdgeClass(edgeClassName);

    session.begin();
    session.command("create vertex " + vertexClassName1 + " set name = 'a'");
    session.command("create vertex " + vertexClassName1 + " set name = 'b'");
    session.command(
        "create edge "
            + edgeClassName
            + " from (select from "
            + vertexClassName1
            + " where name = 'a' ) to (select from "
            + vertexClassName1
            + " where name = 'b' )");

    session.command(
        "MOVE VERTEX (select from "
            + vertexClassName1
            + " where name = 'a') to class:"
            + vertexClassName2);
    session.commit();

    session.begin();
    var rs = session.query("select from " + vertexClassName1);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();

    rs = session.query("select from " + vertexClassName2);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();

    rs = session.query("select expand(out()) from " + vertexClassName2);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();

    rs = session.query("select expand(in()) from " + vertexClassName1);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }

  @Test
  public void testMoveVertexBatch() {
    var vertexClassName1 = "testMoveVertexBatchV1";
    var vertexClassName2 = "testMoveVertexBatchV2";
    var edgeClassName = "testMoveVertexBatchE";
    session.createVertexClass(vertexClassName1);
    session.createVertexClass(vertexClassName2);
    session.createEdgeClass(edgeClassName);

    session.begin();
    session.command("create vertex " + vertexClassName1 + " set name = 'a'");
    session.command("create vertex " + vertexClassName1 + " set name = 'b'");
    session.command(
        "create edge "
            + edgeClassName
            + " from (select from "
            + vertexClassName1
            + " where name = 'a' ) to (select from "
            + vertexClassName1
            + " where name = 'b' )");

    session.command(
        "MOVE VERTEX (select from "
            + vertexClassName1
            + " where name = 'a') to class:"
            + vertexClassName2
            + " BATCH 2");
    session.commit();

    session.begin();
    var rs = session.query("select from " + vertexClassName1);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();

    rs = session.query("select from " + vertexClassName2);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();

    rs = session.query("select expand(out()) from " + vertexClassName2);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();

    rs = session.query("select expand(in()) from " + vertexClassName1);
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }
}
