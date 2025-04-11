package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

/**
 *
 */
public class AbstractRemoteTest {

  protected static final String SERVER_DIRECTORY = "./target/remotetest";

  private YouTrackDBServer server;

  @Rule
  public TestName name = new TestName();

  @Before
  public void setup() throws Exception {

    System.setProperty("YOUTRACKDB_HOME", SERVER_DIRECTORY);

    var stream =
        ClassLoader.getSystemResourceAsStream("abstract-youtrackdb-server-config.xml");
    server = ServerMain.create(false);
    server.startup(stream);
    server.activate();

    final var dbName = name.getMethodName();
    if (dbName != null) {
      server
          .getContext()
          .execute(
              "create database ? memory users (admin identified by 'admin' role admin)", dbName);
    }
  }

  @After
  public void teardown() {
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
