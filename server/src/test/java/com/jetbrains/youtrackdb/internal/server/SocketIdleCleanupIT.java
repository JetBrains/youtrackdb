package com.jetbrains.youtrackdb.internal.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.client.remote.YouTrackDBInternalRemote;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SocketIdleCleanupIT {

  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {
    var classpath = System.getProperty("java.class.path");
    System.out.println("Class path " + classpath);
    server =
        YouTrackDBServer.startFromStreamConfig(
            this.getClass().getResourceAsStream("youtrackdb-server-config.xml"));
  }

  @Test
  public void test() throws InterruptedException {
    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CLIENT_CHANNEL_IDLE_CLOSE, true)
            .addGlobalConfigurationParameter(GlobalConfiguration.CLIENT_CHANNEL_IDLE_TIMEOUT, 1)
            .build();
    var youTrackDb = YourTracks.remote("remote:localhost", "root", "root",
        config);
    youTrackDb.execute(
        "create database test memory users (admin identified by 'admin' role admin)");
    var session = youTrackDb.open("test", "admin", "admin");
    session.executeSQLScript("""
        begin;
        create vertex V;
        commit;
        """);

    Thread.sleep(2000);

    var remote = (YouTrackDBInternalRemote) YouTrackDBInternal.extract(
        (YouTrackDBRemoteImpl) youTrackDb);
    var connectionManager = remote.getConnectionManager();
    var pool =
        connectionManager.getPool(connectionManager.getURLs().iterator().next());
    assertFalse(pool.pool().getResources().iterator().next().isConnected());

    try (var result = session.query("select from V")) {
      assertEquals(1, result.stream().count());
    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
