package com.jetbrains.youtrackdb.security;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedSystemRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.io.File;
import org.junit.Test;

public class SystemUsersTest {

  @Test
  public void test() {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    System.setProperty(
        "YOUTRACKDB_HOME",
        buildDirectory + File.separator + SystemUsersTest.class.getSimpleName());

    LogManager.instance()
        .info(this, "YOUTRACKDB_HOME: " + System.getProperty("YOUTRACKDB_HOME"));

    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(SystemUsersTest.class))) {
      youTrackDB.create("test", DatabaseType.MEMORY,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
      youTrackDB.createSystemUser("systemxx", "systemxx", PredefinedSystemRole.ROOT);

      var db = youTrackDB.open("test", "systemxx", "systemxx");
      db.close();
    }
  }
}
