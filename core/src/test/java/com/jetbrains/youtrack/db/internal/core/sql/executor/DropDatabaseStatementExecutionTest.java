package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DropDatabaseStatementExecutionTest {

  @Test
  public void testPlain() {
    var dbName = "ODropDatabaseStatementExecutionTest_testPlain";
    var youTrackDb =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    try {
      try (var result =
          youTrackDb.execute(
              "create database "
                  + dbName
                  + " disk"
                  + " users ( admin identified by '"
                  + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
                  + "' role admin)")) {
        Assert.assertTrue(result.hasNext());
        var item = result.next();
        Assert.assertEquals(true, item.getProperty("created"));
      }
      Assert.assertTrue(youTrackDb.exists(dbName));

      var session =
          youTrackDb.open(dbName, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      session.close();

      youTrackDb.execute("drop database " + dbName);
      Assert.assertFalse(youTrackDb.exists(dbName));
    } finally {
      youTrackDb.close();
    }
  }

  @Test
  public void testIfExists1() {
    var dbName = "ODropDatabaseStatementExecutionTest_testIfExists1";
    final var youTrackDb =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    try {
      try (var result =
          youTrackDb.execute(
              "create database "
                  + dbName
                  + " disk"
                  + " users ( admin identified by '"
                  + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
                  + "' role admin)")) {
        Assert.assertTrue(result.hasNext());
        var item = result.next();
        Assert.assertEquals(true, item.getProperty("created"));
      }
      Assert.assertTrue(youTrackDb.exists(dbName));

      var session =
          youTrackDb.open(dbName, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      session.close();

      youTrackDb.execute("drop database " + dbName + " if exists");
      Assert.assertFalse(youTrackDb.exists(dbName));
    } finally {
      youTrackDb.close();
    }
  }

  @Test
  public void testIfExists2() {
    var dbName = "ODropDatabaseStatementExecutionTest_testIfExists2";
    try (var youTrackDb = YourTracks.embedded(
        DbTestBase.getBaseDirectoryPath(getClass()) + getClass().getSimpleName(),
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
            .build())) {
      youTrackDb.execute("drop database " + dbName + " if exists");
      Assert.assertFalse(youTrackDb.exists(dbName));
    }
  }
}
