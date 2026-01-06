package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

@Test
public class RunServerTest {

  private YouTrackDBServer server;

  @BeforeSuite
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.startup(
        "classpath:com/jetbrains/youtrackdb/internal/server/config/youtrack-server-integration.yaml");
    server.activate();
  }

  @Test
  public void test() {
  }

  @AfterSuite
  public void after() {
    server.shutdown();
  }
}
