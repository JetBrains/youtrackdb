package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class AbstractRemoteTest {
  protected static final String SERVER_DIRECTORY = "./target/remotetest";

  YouTrackDBServer server;

  @Rule
  public TestName name = new TestName();

  @Before
  public void setup() throws Exception {
    System.setProperty("YOUTRACKDB_HOME", SERVER_DIRECTORY);
    server = ServerMain.create(false);
    server.startup(
        "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
    server.activate();

    final var dbName = name.getMethodName();
    if (dbName != null) {
      server
          .getYouTrackDB().create(dbName, DatabaseType.MEMORY,
              new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
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
