package com.jetbrains.youtrack.db.internal.core.db.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LightWeightEdgesTest {

  private YouTrackDB youTrackDB;
  private DatabaseSession session;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    session = youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    session.createVertexClass("Vertex");
    session.createLightweightEdgeClass("Edge");
  }

  @Test
  public void testSimpleLightWeight() {
    session.begin();
    var v = session.newVertex("Vertex");
    var v1 = session.newVertex("Vertex");
    v.addLightWeightEdge(v1, "Edge");
    v.setProperty("name", "aName");
    v1.setProperty("name", "bName");
    session.commit();

    session.begin();
    try (var res =
        session.query(" select expand(out('Edge')) from `Vertex` where name = 'aName'")) {
      assertTrue(res.hasNext());
      var r = res.next();
      assertEquals(r.getProperty("name"), "bName");
    }

    try (var res =
        session.query(" select expand(in('Edge')) from `Vertex` where name = 'bName'")) {
      assertTrue(res.hasNext());
      var r = res.next();
      assertEquals(r.getProperty("name"), "aName");
    }
    session.commit();
  }

  @Test
  public void testRegularBySchema() {
    var vClazz = "VtestRegularBySchema";
    var vClass = session.createVertexClass(vClazz);

    var eClazz = "EtestRegularBySchema";
    var eClass = session.createEdgeClass(eClazz);

    vClass.createProperty("out_" + eClazz, PropertyType.LINKBAG, eClass);
    vClass.createProperty("in_" + eClazz, PropertyType.LINKBAG, eClass);

    session.begin();
    var v = session.newVertex(vClass);
    v.setProperty("name", "a");
    var v1 = session.newVertex(vClass);
    v1.setProperty("name", "b");
    session.commit();

    session.begin();
    session.execute(
        "create edge "
            + eClazz
            + " from (select from "
            + vClazz
            + " where name = 'a') to (select from "
            + vClazz
            + " where name = 'b') set name = 'foo'");
    session.commit();

    session.runScript(
        "sql",
        "begin;"
            + "delete edge "
            + eClazz
            + ";"
            + "create edge "
            + eClazz
            + " from (select from "
            + vClazz
            + " where name = 'a') to (select from "
            + vClazz
            + " where name = 'b') set name = 'foo';"
            + "commit;");
  }

  @After
  public void after() {
    session.close();
    youTrackDB.close();
  }
}
