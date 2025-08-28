package com.jetbrains.youtrackdb.internal.server;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import java.io.File;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SessionPoolRemoteTest {

  private static final String SERVER_DIRECTORY = "./target/poolRemote";
  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "com/jetbrains/youtrackdb/internal/server/network/youtrackdb-server-config.xml"));
    server.activate();
  }

  @Test
  public void testPoolCloseTx() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 1);
    var youTrackDb =
        (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote(
            "remote:localhost:",
            "root",
            "root",
            config);

    if (!youTrackDb.exists("test")) {
      youTrackDb.execute(
          "create database test memory users (admin identified by 'admin' role admin)");
    }

    var pool = youTrackDb.cachedPool("test", "admin", "admin");

    var session = pool.acquire();
    session.command("create class Test");

    session.begin();
    assertEquals(0,
        session.query("select count(*)  as count from Test").
            findFirst(res -> res.getLong("count")).longValue());
    session.commit();

    session.command("begin");
    session.command("insert into Test");
    assertEquals(1,
        session.query("select count(*)  as count from Test").
            findFirst(res -> res.getLong("count")).longValue());
    session.close();

    session = pool.acquire();
    assertEquals(0,
        session.query("select count(*)  as count from Test").
            findFirst(res -> res.getLong("count")).longValue());

    pool.close();
    youTrackDb.close();
  }

  @Test
  public void testPoolDoubleClose() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 1);
    var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(getClass()),
            config);

    if (!youTrackDb.exists("test")) {
      youTrackDb.execute(
          "create database test memory users (admin identified by 'admin' role admin)");
    }

    var pool = youTrackDb.cachedPool("test", "admin", "admin");
    var db = pool.acquire();
    db.close();
    pool.close();
    youTrackDb.close();
  }

  @After
  public void after() {
    server.shutdown();
    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
