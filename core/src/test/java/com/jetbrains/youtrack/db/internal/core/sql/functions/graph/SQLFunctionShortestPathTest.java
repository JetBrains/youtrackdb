package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import static java.util.Arrays.asList;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionShortestPathTest {

  private YouTrackDB youTrackDB;
  private DatabaseSessionEmbedded session;

  private Map<Integer, Vertex> vertices = new HashMap<Integer, Vertex>();
  private SQLFunctionShortestPath function;

  @Before
  public void setUp() throws Exception {
    setUpDatabase();

    function = new SQLFunctionShortestPath();
  }

  @After
  public void tearDown() throws Exception {
    session.close();
    youTrackDB.close();
  }

  private void setUpDatabase() {
    youTrackDB =
        (YouTrackDBImpl) CreateDatabaseUtil.createDatabase(
            "SQLFunctionShortestPath", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    session =
        (DatabaseSessionEmbedded) youTrackDB.open("SQLFunctionShortestPath", "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    session.createEdgeClass("Edge1");
    session.createEdgeClass("Edge2");

    session.begin();
    var v1 = session.newVertex();
    vertices.put(1, v1);
    var v2 = session.newVertex();
    vertices.put(2, v2);
    var v3 = session.newVertex();
    vertices.put(3, v3);
    var v4 = session.newVertex();
    vertices.put(4, v4);

    vertices.get(1).setProperty("node_id", "A");
    vertices.get(2).setProperty("node_id", "B");
    vertices.get(3).setProperty("node_id", "C");
    vertices.get(4).setProperty("node_id", "D");

    session.newStatefulEdge(vertices.get(1), vertices.get(2), "Edge1");
    session.newStatefulEdge(vertices.get(2), vertices.get(3), "Edge1");
    session.newStatefulEdge(vertices.get(3), vertices.get(1), "Edge2");
    session.newStatefulEdge(vertices.get(3), vertices.get(4), "Edge1");

    for (var i = 5; i <= 20; i++) {
      var v = session.newVertex();
      vertices.put(i, v);
      vertices.get(i).setProperty("node_id", "V" + i);
      session.newStatefulEdge(vertices.get(i - 1), vertices.get(i), "Edge1");
      if (i % 2 == 0) {
        session.newStatefulEdge(vertices.get(i - 2), vertices.get(i), "Edge1");
      }
    }
    session.commit();
  }

  @Test
  public void testExecute() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(4)}, context
        );

    Assert.assertEquals(3, result.size());
    Assert.assertEquals(vertices.get(1).getIdentity(), result.get(0));
    Assert.assertEquals(vertices.get(3).getIdentity(), result.get(1));
    Assert.assertEquals(vertices.get(4).getIdentity(), result.get(2));
    session.commit();
  }

  @Test
  public void testExecuteOut() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(4), "out", null},
            context);

    Assert.assertEquals(4, result.size());
    Assert.assertEquals(vertices.get(1).getIdentity(), result.get(0));
    Assert.assertEquals(vertices.get(2).getIdentity(), result.get(1));
    Assert.assertEquals(vertices.get(3).getIdentity(), result.get(2));
    Assert.assertEquals(vertices.get(4).getIdentity(), result.get(3));
    session.commit();
  }

  @Test
  public void testExecuteOnlyEdge1() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(4), null, "Edge1"},
            context);

    Assert.assertEquals(4, result.size());
    Assert.assertEquals(vertices.get(1).getIdentity(), result.get(0));
    Assert.assertEquals(vertices.get(2).getIdentity(), result.get(1));
    Assert.assertEquals(vertices.get(3).getIdentity(), result.get(2));
    Assert.assertEquals(vertices.get(4).getIdentity(), result.get(3));
    session.commit();
  }

  @Test
  public void testExecuteOnlyEdge1AndEdge2() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(4), "BOTH", asList("Edge1", "Edge2")},
            context);

    Assert.assertEquals(3, result.size());
    Assert.assertEquals(vertices.get(1).getIdentity(), result.get(0));
    Assert.assertEquals(vertices.get(3).getIdentity(), result.get(1));
    Assert.assertEquals(vertices.get(4).getIdentity(), result.get(2));
    session.commit();
  }

  @Test
  public void testLong() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(20)},
            context);

    Assert.assertEquals(11, result.size());
    Assert.assertEquals(vertices.get(1).getIdentity(), result.get(0));
    Assert.assertEquals(vertices.get(3).getIdentity(), result.get(1));
    var next = 2;
    for (var i = 4; i <= 20; i += 2) {
      Assert.assertEquals(vertices.get(i).getIdentity(), result.get(next++));
    }
    session.commit();
  }

  @Test
  public void testMaxDepth1() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> additionalParams = new HashMap<String, Object>();
    additionalParams.put(SQLFunctionShortestPath.PARAM_MAX_DEPTH, 11);
    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(20), null, null, additionalParams},
            context);

    Assert.assertEquals(11, result.size());
    session.commit();
  }

  @Test
  public void testMaxDepth2() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> additionalParams = new HashMap<String, Object>();
    additionalParams.put(SQLFunctionShortestPath.PARAM_MAX_DEPTH, 12);
    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(20), null, null, additionalParams},
            context);

    Assert.assertEquals(11, result.size());
    session.commit();
  }

  @Test
  public void testMaxDepth3() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> additionalParams = new HashMap<String, Object>();
    additionalParams.put(SQLFunctionShortestPath.PARAM_MAX_DEPTH, 10);
    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(20), null, null, additionalParams},
            context);

    Assert.assertEquals(0, result.size());
    session.commit();
  }

  @Test
  public void testMaxDepth4() {
    session.begin();
    bindVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> additionalParams = new HashMap<String, Object>();
    additionalParams.put(SQLFunctionShortestPath.PARAM_MAX_DEPTH, 3);
    final var result =
        function.execute(
            null,
            null,
            null,
            new Object[]{vertices.get(1), vertices.get(20), null, null, additionalParams},
            context);

    Assert.assertEquals(0, result.size());
    session.commit();
  }

  private void bindVertices() {
    var newVertices = new HashMap<Integer, Vertex>();
    for (var entry : vertices.entrySet()) {
      var activeTx = session.getActiveTransaction();
      newVertices.put(entry.getKey(), activeTx.load(entry.getValue()));
    }

    vertices = newVertices;
  }
}
