package com.jetbrains.youtrack.db.security;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
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

    YouTrackDB youTrackDB =
        new YouTrackDBAbstract(
            "disk:target/" + SystemUsersTest.class.getSimpleName(),
            YouTrackDBConfig.defaultConfig());

    try {
      youTrackDB.execute(
          "create database " + "test" + " memory users ( admin identified by 'admin' role admin)");

      youTrackDB.execute("create system user systemxx identified by systemxx role admin").close();
      var db = youTrackDB.open("test", "systemxx", "systemxx");

      db.close();
    } finally {
      youTrackDB.close();
    }
  }
}
