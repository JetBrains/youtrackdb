/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionAstarTest {

  private static final String ADMIN_PASSWORD = "adminpwd";

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  private Vertex v0;
  private Vertex v1;
  private Vertex v2;
  private Vertex v3;
  private Vertex v4;
  private Vertex v5;
  private Vertex v6;
  private SQLFunctionAstar functionAstar;

  @Before
  public void setUp() throws Exception {

    setUpDatabase();

    functionAstar = new SQLFunctionAstar();
  }

  @After
  public void tearDown() throws Exception {
    session.close();
    youTrackDB.close();
  }

  private void setUpDatabase() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", ADMIN_PASSWORD, "admin");

    session = youTrackDB.open("SQLFunctionAstarTest", "admin", ADMIN_PASSWORD);

    session.createEdgeClass("has_path");

    session.begin();
    var cf = session.getMetadata().getFunctionLibrary().createFunction("myCustomHeuristic");
    cf.setCode("return 1;");
    cf.save(session);

    v0 = session.newVertex();
    v1 = session.newVertex();
    v2 = session.newVertex();
    v3 = session.newVertex();
    v4 = session.newVertex();
    v5 = session.newVertex();
    v6 = session.newVertex();

    v0.setProperty("node_id", "Z"); // Tabriz
    v0.setProperty("name", "Tabriz");
    v0.setProperty("lat", 31.746512f);
    v0.setProperty("lon", 51.427002f);
    v0.setProperty("alt", 2200);

    v1.setProperty("node_id", "A"); // Tehran
    v1.setProperty("name", "Tehran");
    v1.setProperty("lat", 35.746512f);
    v1.setProperty("lon", 51.427002f);
    v1.setProperty("alt", 1800);

    v2.setProperty("node_id", "B"); // Mecca
    v2.setProperty("name", "Mecca");
    v2.setProperty("lat", 21.371244f);
    v2.setProperty("lon", 39.847412f);
    v2.setProperty("alt", 1500);

    v3.setProperty("node_id", "C"); // Bejin
    v3.setProperty("name", "Bejin");
    v3.setProperty("lat", 39.904041f);
    v3.setProperty("lon", 116.408011f);
    v3.setProperty("alt", 1200);

    v4.setProperty("node_id", "D"); // London
    v4.setProperty("name", "London");
    v4.setProperty("lat", 51.495065f);
    v4.setProperty("lon", -0.120850f);
    v4.setProperty("alt", 900);

    v5.setProperty("node_id", "E"); // NewYork
    v5.setProperty("name", "NewYork");
    v5.setProperty("lat", 42.779275f);
    v5.setProperty("lon", -74.641113f);
    v5.setProperty("alt", 1700);

    v6.setProperty("node_id", "F"); // Los Angles
    v6.setProperty("name", "Los Angles");
    v6.setProperty("lat", 34.052234f);
    v6.setProperty("lon", -118.243685f);
    v6.setProperty("alt", 400);

    var e1 = session.newStatefulEdge(v1, v2, "has_path");
    e1.setProperty("weight", 250.0f);
    e1.setProperty("ptype", "road");
    var e2 = session.newStatefulEdge(v2, v3, "has_path");
    e2.setProperty("weight", 250.0f);
    e2.setProperty("ptype", "road");
    var e3 = session.newStatefulEdge(v1, v3, "has_path");
    e3.setProperty("weight", 1000.0f);
    e3.setProperty("ptype", "road");
    var e4 = session.newStatefulEdge(v3, v4, "has_path");
    e4.setProperty("weight", 250.0f);
    e4.setProperty("ptype", "road");
    var e5 = session.newStatefulEdge(v2, v4, "has_path");
    e5.setProperty("weight", 600.0f);
    e5.setProperty("ptype", "road");
    var e6 = session.newStatefulEdge(v4, v5, "has_path");
    e6.setProperty("weight", 400.0f);
    e6.setProperty("ptype", "road");
    var e7 = session.newStatefulEdge(v5, v6, "has_path");
    e7.setProperty("weight", 300.0f);
    e7.setProperty("ptype", "road");
    var e8 = session.newStatefulEdge(v3, v6, "has_path");
    e8.setProperty("weight", 200.0f);
    e8.setProperty("ptype", "road");
    var e9 = session.newStatefulEdge(v4, v6, "has_path");
    e9.setProperty("weight", 900.0f);
    e9.setProperty("ptype", "road");
    var e10 = session.newStatefulEdge(v2, v6, "has_path");
    e10.setProperty("weight", 2500.0f);
    e10.setProperty("ptype", "road");
    var e11 = session.newStatefulEdge(v1, v5, "has_path");
    e11.setProperty("weight", 100.0f);
    e11.setProperty("ptype", "road");
    var e12 = session.newStatefulEdge(v4, v1, "has_path");
    e12.setProperty("weight", 200.0f);
    e12.setProperty("ptype", "road");
    var e13 = session.newStatefulEdge(v5, v3, "has_path");
    e13.setProperty("weight", 800.0f);
    e13.setProperty("ptype", "road");
    var e14 = session.newStatefulEdge(v5, v2, "has_path");
    e14.setProperty("weight", 500.0f);
    e14.setProperty("ptype", "road");
    var e15 = session.newStatefulEdge(v6, v5, "has_path");
    e15.setProperty("weight", 250.0f);
    e15.setProperty("ptype", "road");
    var e16 = session.newStatefulEdge(v3, v1, "has_path");
    e16.setProperty("weight", 550.0f);
    e16.setProperty("ptype", "road");
    session.commit();
  }

  @Test
  public void test1Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, "out");
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    var ctx = new BasicCommandContext();

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    v1 = activeTx3.load(v1);
    var activeTx2 = session.getActiveTransaction();
    v2 = activeTx2.load(v2);
    var activeTx1 = session.getActiveTransaction();
    v3 = activeTx1.load(v3);
    var activeTx = session.getActiveTransaction();
    v4 = activeTx.load(v4);

    ctx.setDatabaseSession(session);
    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v1, v4, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(4, result.size());
    assertEquals(v1, result.get(0));
    assertEquals(v2, result.get(1));
    assertEquals(v3, result.get(2));
    assertEquals(v4, result.get(3));
    session.commit();
  }

  @Test
  public void test2Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, "out");
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    v1 = activeTx2.load(v1);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v1, v6, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }
    assertEquals(3, result.size());
    assertEquals(v1, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v6, result.get(2));
    session.commit();
  }

  @Test
  public void test3Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, "out");
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    v1 = activeTx2.load(v1);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v1, v6, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(3, result.size());
    assertEquals(v1, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v6, result.get(2));
    session.commit();
  }

  @Test
  public void test4Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, "out");
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon", "alt"});
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    v1 = activeTx2.load(v1);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v1, v6, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(3, result.size());
    assertEquals(v1, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v6, result.get(2));
    session.commit();
  }

  @Test
  public void test5Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, "out");
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    v3 = activeTx2.load(v3);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v3, v5, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(3, result.size());
    assertEquals(v3, result.get(0));
    assertEquals(v6, result.get(1));
    assertEquals(v5, result.get(2));
    session.commit();
  }

  @Test
  public void test6Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, "out");
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx5 = session.getActiveTransaction();
    v1 = activeTx5.load(v1);
    var activeTx4 = session.getActiveTransaction();
    v2 = activeTx4.load(v2);
    var activeTx3 = session.getActiveTransaction();
    v3 = activeTx3.load(v3);
    var activeTx2 = session.getActiveTransaction();
    v4 = activeTx2.load(v4);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v6, v1, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(6, result.size());
    assertEquals(v6, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v2, result.get(2));
    assertEquals(v3, result.get(3));
    assertEquals(v4, result.get(4));
    assertEquals(v1, result.get(5));
    session.commit();
  }

  @Test
  public void test7Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, "out");
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    options.put(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA, "EucliDEAN");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx5 = session.getActiveTransaction();
    v1 = activeTx5.load(v1);
    var activeTx4 = session.getActiveTransaction();
    v2 = activeTx4.load(v2);
    var activeTx3 = session.getActiveTransaction();
    v3 = activeTx3.load(v3);
    var activeTx2 = session.getActiveTransaction();
    v4 = activeTx2.load(v4);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v6, v1, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(6, result.size());
    assertEquals(v6, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v2, result.get(2));
    assertEquals(v3, result.get(3));
    assertEquals(v4, result.get(4));
    assertEquals(v1, result.get(5));
    session.commit();
  }

  @Test
  public void test8Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, Direction.OUT);
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_TIE_BREAKER, false);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    options.put(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA, HeuristicFormula.EUCLIDEANNOSQR);
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx5 = session.getActiveTransaction();
    v1 = activeTx5.load(v1);
    var activeTx4 = session.getActiveTransaction();
    v2 = activeTx4.load(v2);
    var activeTx3 = session.getActiveTransaction();
    v3 = activeTx3.load(v3);
    var activeTx2 = session.getActiveTransaction();
    v4 = activeTx2.load(v4);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v6, v1, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(5, result.size());
    assertEquals(v6, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v2, result.get(2));
    assertEquals(v4, result.get(3));
    assertEquals(v1, result.get(4));
    session.commit();
  }

  @Test
  public void test9Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, Direction.BOTH);
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_TIE_BREAKER, false);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    options.put(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA, HeuristicFormula.MAXAXIS);
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    v1 = activeTx2.load(v1);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v6, v1, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(3, result.size());
    assertEquals(v6, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v1, result.get(2));
    session.commit();
  }

  @Test
  public void test10Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, Direction.OUT);
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_TIE_BREAKER, false);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    options.put(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA, HeuristicFormula.CUSTOM);
    options.put(SQLFunctionAstar.PARAM_CUSTOM_HEURISTIC_FORMULA, "myCustomHeuristic");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx5 = session.getActiveTransaction();
    v1 = activeTx5.load(v1);
    var activeTx4 = session.getActiveTransaction();
    v2 = activeTx4.load(v2);
    var activeTx3 = session.getActiveTransaction();
    v3 = activeTx3.load(v3);
    var activeTx2 = session.getActiveTransaction();
    v4 = activeTx2.load(v4);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v6, v1, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(6, result.size());
    assertEquals(v6, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v2, result.get(2));
    assertEquals(v3, result.get(3));
    assertEquals(v4, result.get(4));
    assertEquals(v1, result.get(5));
    session.commit();
  }

  @Test
  public void test11Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, Direction.OUT);
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_TIE_BREAKER, false);
    options.put(SQLFunctionAstar.PARAM_EMPTY_IF_MAX_DEPTH, true);
    options.put(SQLFunctionAstar.PARAM_MAX_DEPTH, 3);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    options.put(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA, HeuristicFormula.CUSTOM);
    options.put(SQLFunctionAstar.PARAM_CUSTOM_HEURISTIC_FORMULA, "myCustomHeuristic");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    v6 = activeTx1.load(v6);
    var activeTx = session.getActiveTransaction();
    v1 = activeTx.load(v1);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v6, v1, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(0, result.size());
    session.commit();
  }

  @Test
  public void test12Execute() throws Exception {
    Map<String, Object> options = new HashMap<String, Object>();
    options.put(SQLFunctionAstar.PARAM_DIRECTION, Direction.OUT);
    options.put(SQLFunctionAstar.PARAM_PARALLEL, true);
    options.put(SQLFunctionAstar.PARAM_TIE_BREAKER, false);
    options.put(SQLFunctionAstar.PARAM_EMPTY_IF_MAX_DEPTH, false);
    options.put(SQLFunctionAstar.PARAM_MAX_DEPTH, 3);
    options.put(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES, new String[]{"has_path"});
    options.put(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES, new String[]{"lat", "lon"});
    options.put(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA, HeuristicFormula.CUSTOM);
    options.put(SQLFunctionAstar.PARAM_CUSTOM_HEURISTIC_FORMULA, "myCustomHeuristic");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var activeTx4 = session.getActiveTransaction();
    v1 = activeTx4.load(v1);
    var activeTx3 = session.getActiveTransaction();
    v2 = activeTx3.load(v2);
    var activeTx2 = session.getActiveTransaction();
    v3 = activeTx2.load(v3);
    var activeTx1 = session.getActiveTransaction();
    v5 = activeTx1.load(v5);
    var activeTx = session.getActiveTransaction();
    v6 = activeTx.load(v6);

    final List<Vertex> result =
        functionAstar.execute(null, null, null, new Object[]{v6, v1, "'weight'", options}, ctx);
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(4, result.size());
    assertEquals(v6, result.get(0));
    assertEquals(v5, result.get(1));
    assertEquals(v2, result.get(2));
    assertEquals(v3, result.get(3));
    session.commit();
  }

  @Test
  public void testSql() {
    session.begin();
    var activeTx3 = session.getActiveTransaction();
    v1 = activeTx3.load(v1);
    var activeTx2 = session.getActiveTransaction();
    v2 = activeTx2.load(v2);
    var activeTx1 = session.getActiveTransaction();
    v3 = activeTx1.load(v3);
    var activeTx = session.getActiveTransaction();
    v4 = activeTx.load(v4);
    var r =
        session.query(
            "select expand(astar("
                + v1.getIdentity()
                + ", "
                + v4.getIdentity()
                + ", 'weight', {'direction':'out', 'parallel':true, 'edgeTypeNames':'has_path'}))");

    List<RID> result = new ArrayList<>();
    while (r.hasNext()) {
      result.add(r.next().getIdentity());
    }
    try (var rs = session.query("select count(*) as count from has_path")) {
      assertEquals((Object) 16L, rs.next().getProperty("count"));
    }

    assertEquals(4, result.size());
    assertEquals(v1.getIdentity(), result.get(0));
    assertEquals(v2.getIdentity(), result.get(1));
    assertEquals(v3.getIdentity(), result.get(2));
    assertEquals(v4.getIdentity(), result.get(3));
    session.commit();
  }
}
