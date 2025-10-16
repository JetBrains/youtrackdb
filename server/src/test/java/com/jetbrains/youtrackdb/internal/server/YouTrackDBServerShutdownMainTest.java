package com.jetbrains.youtrackdb.internal.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.tools.config.ServerConfiguration;
import com.jetbrains.youtrackdb.internal.tools.config.ServerNetworkConfiguration;
import com.jetbrains.youtrackdb.internal.tools.config.ServerNetworkListenerConfiguration;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class YouTrackDBServerShutdownMainTest {

  private YouTrackDBServer server;
  private String prevPassword;
  private String prevOrientHome;

  @Before
  public void startupOServer() throws Exception {
    prevPassword = System.setProperty("YOUTRACKDB_ROOT_PASSWORD", "rootPassword");
    prevOrientHome = System.setProperty("YOUTRACKDB_HOME", "./target/testhome");

    var conf = new ServerConfiguration();
    conf.network = new ServerNetworkConfiguration();

    conf.network.listeners = new ArrayList<>();
    conf.network.listeners.add(new ServerNetworkListenerConfiguration());

    server = new YouTrackDBServer(false);
    server.startup(conf);
    server.activate();

    assertThat(server.isActive()).isTrue();
  }

  @After
  public void tearDown() throws Exception {
    if (server.isActive()) {
      server.shutdown();
    }

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File("./target/testhome"));
    YouTrackDBEnginesManager.instance().startup();

    if (prevOrientHome != null) {
      System.setProperty("YOUTRACKDB_HOME", prevOrientHome);
    }
    if (prevPassword != null) {
      System.setProperty("YOUTRACKDB_ROOT_PASSWORD", prevPassword);
    }
  }

  @Test
  @Ignore
  public void shouldShutdownServerWithDirectCall() throws Exception {

//    var shutdownMain =
//        new ServerShutdownMain("localhost", "2424", "root", "rootPassword");
//    shutdownMain.connect(5000);

    TimeUnit.SECONDS.sleep(2);
    assertThat(server.isActive()).isFalse();
  }

  @Test
  @Ignore
  public void shouldShutdownServerParsingShortArguments() throws Exception {

//    ServerShutdownMain.main(
//        new String[]{"-h", "localhost", "-P", "2424", "-p", "rootPassword", "-u", "root"});

    TimeUnit.SECONDS.sleep(2);
    assertThat(server.isActive()).isFalse();
  }

  @Test
  @Ignore
  public void shouldShutdownServerParsingLongArguments() throws Exception {

//    ServerShutdownMain.main(
//        new String[]{
//            "--host", "localhost", "--ports", "2424", "--password", "rootPassword", "--user", "root"
//        });

    TimeUnit.SECONDS.sleep(2);
    assertThat(server.isActive()).isFalse();
  }
}
