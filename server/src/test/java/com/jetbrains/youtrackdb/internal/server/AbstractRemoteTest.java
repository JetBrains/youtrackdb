package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

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
          .getYouTrackDB().create(dbName, DatabaseType.MEMORY,
              new UserCredential("admin", "admin", PredefinedRole.ADMIN));
    }
  }

  @After
  public void teardown() throws Exception {
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
