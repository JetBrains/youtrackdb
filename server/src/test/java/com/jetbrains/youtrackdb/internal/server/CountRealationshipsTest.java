package com.jetbrains.youtrackdb.internal.server;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import java.io.File;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CountRealationshipsTest {

  private static final String SERVER_DIRECTORY = "./target/collection";
  private YouTrackDBServer server;
  private YouTrackDBRemoteImpl youTrackDB;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config-tree-ridbag.xml"));
    server.activate();

    youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root",
        "root");
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        CountRealationshipsTest.class.getSimpleName());
  }

  @Test
  public void test() throws Exception {
    var session =
        youTrackDB.open(CountRealationshipsTest.class.getSimpleName(),
            "admin", "admin");
    var res = session.computeSQLScript("""
        begin;
        let $v1 = create vertex V;
        let $v2 = create vertex V;
        commit;
        return {"v1" : $v1, "v2" : $v2};
        """).findFirst().getEmbeddedMap("value");

    @SuppressWarnings("unchecked")
    var vertex1 = ((List<Identifiable>) res.get("v1")).getFirst().getIdentity();
    @SuppressWarnings("unchecked")
    var vertex2 = ((List<Identifiable>) res.get("v2")).getFirst().getIdentity();

    assertEquals(0, countOutEdges(session, vertex1));

    session.command("begin");
    session.command("create edge from ? to ?", vertex1, vertex2);
    assertEquals(1, countOutEdges(session, vertex1));
    session.command("commit");

    assertEquals(1, countOutEdges(session, vertex1));
    session.close();

    session = youTrackDB.open(CountRealationshipsTest.class.getSimpleName(), "admin", "admin");
    assertEquals(1, countOutEdges(session, vertex1));
    session.close();
  }

  private static long countOutEdges(RemoteDatabaseSession session, RID v) {
    session.begin();
    try {
      return session.query("select out().size() as size from ?", v).findFirst().getLong("size");
    } finally {
      session.commit();
    }
  }

  @After
  public void after() {
    youTrackDB.close();
    server.shutdown();
    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
