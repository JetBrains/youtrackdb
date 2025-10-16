package com.jetbrains.youtrackdb.internal.core.db.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LightWeightEdgesTest {
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  @Before
  public void before() {
    youTrackDB =
        (YouTrackDBImpl) CreateDatabaseUtil.createDatabase("test",
            DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);

    session = (DatabaseSessionEmbedded) youTrackDB.open("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    var schema = session.getMetadata().getSlowMutableSchema();

    schema.createVertexClass("Vertex");
    schema.createLightweightEdgeClass("Edge");
  }

  @Test
  public void testSimpleLightWeight() {
    var tx = session.begin();
    var v = tx.newVertex("Vertex");
    var v1 = tx.newVertex("Vertex");
    v.addLightWeightEdge(v1, "Edge");
    v.setProperty("name", "aName");
    v1.setProperty("name", "bName");
    tx.commit();

    tx = session.begin();
    try (var res =
        tx.query(" select expand(out('Edge')) from `Vertex` where name = 'aName'")) {
      assertTrue(res.hasNext());
      var r = res.next();
      assertEquals("bName", r.getProperty("name"));
    }

    try (var res =
        tx.query(" select expand(in('Edge')) from `Vertex` where name = 'bName'")) {
      assertTrue(res.hasNext());
      var r = res.next();
      assertEquals("aName", r.getProperty("name"));
    }
    tx.commit();
  }

  @Test
  public void testRegularBySchema() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var vClazz = "VtestRegularBySchema";
    var vClass = schema.createVertexClass(vClazz);

    var eClazz = "EtestRegularBySchema";
    var eClass = schema.createEdgeClass(eClazz);

    vClass.createProperty("out_" + eClazz, PropertyTypeInternal.LINKBAG, eClass);
    vClass.createProperty("in_" + eClazz, PropertyTypeInternal.LINKBAG, eClass);

    var tx = session.begin();
    var v = tx.newVertex(vClass);
    v.setProperty("name", "a");
    var v1 = tx.newVertex(vClass);
    v1.setProperty("name", "b");
    tx.commit();

    tx = session.begin();
    tx.command(
        "create edge "
            + eClazz
            + " from (select from "
            + vClazz
            + " where name = 'a') to (select from "
            + vClazz
            + " where name = 'b') set name = 'foo'");
    tx.commit();

    session.computeScript(
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
