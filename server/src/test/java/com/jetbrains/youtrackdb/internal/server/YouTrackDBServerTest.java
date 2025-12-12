package com.jetbrains.youtrackdb.internal.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.server.plugin.gremlin.YTDBSettings;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class YouTrackDBServerTest {

  private String prevYtdbHome;
  private YouTrackDBServer server;
  private YTDBSettings conf;

  @Before
  public void setUp() {
    prevYtdbHome = System.setProperty("YOUTRACKDB_HOME", "./target/testhome");
    conf = new YTDBSettings();
  }

  @After
  public void tearDown() {
    if (server.isActive()) {
      server.shutdown();
    }

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File("./target/testhome"));

    if (prevYtdbHome != null) {
      System.setProperty("YOUTRACKDB_HOME", prevYtdbHome);
    }

    YouTrackDBEnginesManager.instance().startup();
  }

  @Test
  public void shouldShutdownOnPluginStartupException() {
    try {
      server = new YouTrackDBServer(false);
      server.startup(conf);
      server.activate();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(BaseException.class);
    }

    assertThat(server.isActive()).isFalse();
    server.shutdown();
  }
}
