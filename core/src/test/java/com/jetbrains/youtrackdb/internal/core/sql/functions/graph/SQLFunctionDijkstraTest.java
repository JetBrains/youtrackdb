package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionDijkstraTest {

  private YouTrackDB youTrackDB;
  private DatabaseSession session;

  private Vertex v1;
  private Vertex v2;
  private Vertex v3;
  private Vertex v4;
  private SQLFunctionDijkstra functionDijkstra;

  @Before
  public void setUp() throws Exception {
    setUpDatabase();

    functionDijkstra = new SQLFunctionDijkstra();
  }

  @After
  public void tearDown() throws Exception {
    session.close();
    youTrackDB.close();
  }

  private void setUpDatabase() {
    youTrackDB =
        (YouTrackDBImpl) CreateDatabaseUtil.createDatabase(
            "SQLFunctionDijkstraTest", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    session =
        youTrackDB.open("SQLFunctionDijkstraTest", "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    session.getSchema().createEdgeClass("weight");

    var tx = session.begin();
    v1 = tx.newVertex();
    v2 = tx.newVertex();
    v3 = tx.newVertex();
    v4 = tx.newVertex();

    v1.setProperty("node_id", "A");
    v2.setProperty("node_id", "B");
    v3.setProperty("node_id", "C");
    v4.setProperty("node_id", "D");

    var e1 = tx.newStatefulEdge(v1, v2, "weight");
    e1.setProperty("weight", 1.0f);

    var e2 = tx.newStatefulEdge(v2, v3, "weight");
    e2.setProperty("weight", 1.0f);

    var e3 = tx.newStatefulEdge(v1, v3, "weight");
    e3.setProperty("weight", 100.0f);

    var e4 = tx.newStatefulEdge(v3, v4, "weight");
    e4.setProperty("weight", 1.0f);
    tx.commit();
  }

  @Test
  public void testExecute() throws Exception {
    var tx = session.begin();
    v1 = tx.load(v1);
    v2 = tx.load(v2);
    v3 = tx.load(v3);
    v4 = tx.load(v4);

    var context = new BasicCommandContext();
    context.setDatabaseSession((DatabaseSessionEmbedded) session);

    final List<Vertex> result =
        functionDijkstra.execute(
            null, null, null, new Object[]{v1, v4, "'weight'"}, context);

    assertEquals(4, result.size());
    assertEquals(v1, result.get(0));
    assertEquals(v2, result.get(1));
    assertEquals(v3, result.get(2));
    assertEquals(v4, result.get(3));
    tx.commit();
  }
}
