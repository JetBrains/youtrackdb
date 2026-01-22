package com.jetbrains.youtrackdb.internal.core.db.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LightWeightEdgesTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSession session;

  @Before
  public void before() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    session = youTrackDB.open("test", "admin", "adminpwd");

    session.getSchema().createVertexClass("Vertex");
    session.getSchema().createLightweightEdgeClass("Edge");
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
      assertEquals(r.getProperty("name"), "bName");
    }

    try (var res =
        tx.query(" select expand(in('Edge')) from `Vertex` where name = 'bName'")) {
      assertTrue(res.hasNext());
      var r = res.next();
      assertEquals(r.getProperty("name"), "aName");
    }
    tx.commit();
  }

  @Test
  public void testRegularBySchema() {
    var vClazz = "VtestRegularBySchema";
    var vClass = session.getSchema().createVertexClass(vClazz);

    var eClazz = "EtestRegularBySchema";
    var eClass = session.getSchema().createEdgeClass(eClazz);

    vClass.createProperty("out_" + eClazz, PropertyType.LINKBAG, eClass);
    vClass.createProperty("in_" + eClazz, PropertyType.LINKBAG, eClass);

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
