package com.jetbrains.youtrackdb.internal.server.plugin.gremlin.features;

import com.jetbrains.youtrackdb.internal.server.plugin.gremlin.YTDBRemoteGraphBinaryFormatFeatureTest.YTDBGraphWorld;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;

public class YTDBRemoteGraphFeatureTestHooks {
  @BeforeAll
  public static void setupSuite() throws Exception {
    YTDBGraphWorld.provider.startServer();
  }

  @AfterAll
  public static void tearDownSuite() {
    YTDBGraphWorld.provider.stopServer();
  }
}
