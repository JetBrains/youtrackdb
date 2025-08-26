package com.jetbrains.youtrackdb.security;

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
        DbTestBase.getBaseDirectoryPath(SystemUsersTest.class))) {
      youTrackDB.execute(
          "create database " + "test" + " memory users ( admin identified by 'admin' role admin)");

      youTrackDB.execute("create system user systemxx identified by systemxx role admin").close();
      var db = youTrackDB.open("test", "systemxx", "systemxx");

      db.close();
    }
  }
}
