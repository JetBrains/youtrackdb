package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import java.io.File;
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
                "com/jetbrains/youtrack/db/internal/server/network/youtrackdb-server-config.xml"));
    server.activate();
  }

  @Test
  public void testPoolCloseTx() {
    var youTrackDb =
        YourTracks.remote(
            "remote:localhost:",
            "root",
            "root",
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 1).build());

    if (!youTrackDb.exists("test")) {
      youTrackDb.execute(
          "create database test memory users (admin identified by 'admin' role admin)");
    }

    var pool = youTrackDb.cachedPool("test", "admin", "admin");
    var db = pool.acquire();
    db.command("create class Test");

    db.command("begin");
    db.command("insert into Test");
    db.close();

    db = pool.acquire();
    assertEquals(0,
        db.query("select count(*)  as count from Test").
            findFirst(res -> res.getInt("count")).intValue());

    pool.close();
    youTrackDb.close();
  }

  @Test
  public void testPoolDoubleClose() {
    var youTrackDb =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 1).build());

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
