package com.jetbrains.youtrack.db.internal.server.script;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class JSScriptServerTest {
  @Rule
  public TestName name = new TestName();

  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {

    server =
        YouTrackDBServer.startFromStreamConfig(
            getClass().getResourceAsStream("youtrackdb-server-javascript-config.xml"));
  }

  @Test
  public void jsPackagesFromConfigTest() {

    var youTrackDB =
        YourTracks.remote("remote:localhost", "root", "root", YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        name.getMethodName());
    try (var db = youTrackDB.open(name.getMethodName(), "admin", "admin")) {
      try (var resultSet = db.computeScript("javascript", "new java.math.BigDecimal(1.0);")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }

      try (var resultSet = db.computeScript("javascript", "new java.util.ArrayList();")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }

    } finally {

      youTrackDB.drop(name.getMethodName());
      youTrackDB.close();
    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
