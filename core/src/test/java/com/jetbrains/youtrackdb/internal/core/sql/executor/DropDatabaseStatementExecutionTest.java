package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DropDatabaseStatementExecutionTest {

  @Test
  public void testPlain() {
    var dbName = "ODropDatabaseStatementExecutionTest_testPlain";
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()),
        config)) {
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
    }
  }

  @Test
  public void testIfExists1() {
    var dbName = "ODropDatabaseStatementExecutionTest_testIfExists1";
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()), config)) {
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
    }
  }

  @Test
  public void testIfExists2() {
    var dbName = "ODropDatabaseStatementExecutionTest_testIfExists2";
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()) + getClass().getSimpleName(),
        config)) {
      youTrackDb.execute("drop database " + dbName + " if exists");
      Assert.assertFalse(youTrackDb.exists(dbName));
    }
  }
}
