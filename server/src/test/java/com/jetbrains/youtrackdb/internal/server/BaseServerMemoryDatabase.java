package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class BaseServerMemoryDatabase {

  protected YTDBGraphTraversalSource traversal;
  protected YouTrackDB youTrackDB;

  @Rule
  public TestName name = new TestName();
  protected YouTrackDBServer server;

  @Before
  public void beforeTest() {
    server = new YouTrackDBServer(false);
    try {
      server.startup(
          "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
      server.activate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    youTrackDB = YourTracks.instance("localhost", 45940, "root", "root");
    youTrackDB.create(name.getMethodName(), DatabaseType.MEMORY,
        new UserCredential("admin", "admin", PredefinedRole.ADMIN));
    traversal = youTrackDB.openTraversal(name.getMethodName(), "admin", "adminpwd");
  }

  @After
  public void afterTest() {
    youTrackDB.drop(name.getMethodName());
    youTrackDB.close();
    var directory = server.getDatabaseDirectory();
    server.shutdown();
    FileUtils.deleteRecursively(new File(directory));
  }
}
