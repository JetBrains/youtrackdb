package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateDatabaseStatementExecutionTest {

  @Test
  public void testPlain() {
    final var dbName = "OCreateDatabaseStatementExecutionTest_testPlain";

    final var youTrackDb =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
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

    try {
      final var session =
          youTrackDb.open(dbName, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      session.close();
    } finally {
      youTrackDb.drop(dbName);
      youTrackDb.close();
    }
  }

  @Test
  public void testNoDefaultUsers() {
    var dbName = "OCreateDatabaseStatementExecutionTest_testNoDefaultUsers";
    var youTrackDb = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig());
    try (var result =
        youTrackDb.execute(
            "create database "
                + dbName
                + " disk {'config':{'youtrackdb.security.createDefaultUsers': false}}")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(true, item.getProperty("created"));
    }
    Assert.assertTrue(youTrackDb.exists(dbName));

    try {
      var session =
          youTrackDb.open(dbName, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      Assert.fail();
    } catch (SecurityAccessException e) {
    } finally {
      youTrackDb.drop(dbName);
      youTrackDb.close();
    }
  }
}
