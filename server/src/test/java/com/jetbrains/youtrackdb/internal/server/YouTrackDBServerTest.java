package com.jetbrains.youtrackdb.internal.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.server.config.ServerConfiguration;
import com.jetbrains.youtrackdb.internal.server.config.ServerHandlerConfiguration;
import com.jetbrains.youtrackdb.internal.server.config.ServerParameterConfiguration;
import java.io.File;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class YouTrackDBServerTest {
  private String prevPassword;
  private String prevOrientHome;
  private YouTrackDBServer server;
  private ServerConfiguration conf;

  @Before
  public void setUp() throws Exception {
    prevPassword = System.setProperty("YOUTRACKDB_ROOT_PASSWORD", "rootPassword");
    prevOrientHome = System.setProperty("YOUTRACKDB_HOME", "./target/testhome");

    conf = new ServerConfiguration();

    conf.handlers = new ArrayList<>();
    var handlerConfiguration = new ServerHandlerConfiguration();
    handlerConfiguration.clazz = ServerFailingOnStarupPluginStub.class.getName();
    handlerConfiguration.parameters = new ServerParameterConfiguration[0];

    conf.handlers.add(0, handlerConfiguration);
  }

  @After
  public void tearDown() throws Exception {
    if (server.isActive()) {
      server.shutdown();
    }

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File("./target/testhome"));

    if (prevOrientHome != null) {
      System.setProperty("YOUTRACKDB_HOME", prevOrientHome);
    }
    if (prevPassword != null) {
      System.setProperty("YOUTRACKDB_ROOT_PASSWORD", prevPassword);
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
