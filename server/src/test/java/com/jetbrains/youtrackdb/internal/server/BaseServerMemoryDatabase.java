package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
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
    server.setServerRootDirectory(DbTestBase.getBaseDirectoryPathStr(getClass()));
    try {
      server.startup(
          "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
      server.activate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    youTrackDB = YourTracks.instance("localhost", server.getGremlinServer().getPort(),
        "root", "root");
    youTrackDB.create(name.getMethodName(), DatabaseType.MEMORY,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    traversal = youTrackDB.openTraversal(name.getMethodName(), "admin", "admin");
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
