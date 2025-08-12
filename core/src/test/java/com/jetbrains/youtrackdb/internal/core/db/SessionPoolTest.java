package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import org.junit.Test;

public class SessionPoolTest {

  @Test
  public void testPool() {
    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
            .build();
    final var youTrackDb =
        YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()), config);
    youTrackDb.createIfNotExists("test", DatabaseType.MEMORY, "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD, "admin");
    @SuppressWarnings("unchecked") final SessionPool<DatabaseSession> pool =
        new SessionPoolImpl<>((YouTrackDBAbstract<?, DatabaseSession>) youTrackDb, "test", "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var db = (DatabaseSessionInternal) pool.acquire();
    db.executeInTx(
        transaction -> db.newEntity());
    db.close();
    pool.close();
    youTrackDb.close();
  }

  @Test
  public void testPoolCloseTx() {
    final var youTrackDb =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 1)
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());

    if (!youTrackDb.exists("test")) {
      youTrackDb.execute(
          "create database "
              + "test"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin)");
    }

    @SuppressWarnings("unchecked") final var pool =
        new SessionPoolImpl<>(
            (YouTrackDBAbstract<Result, DatabaseSession>) youTrackDb, "test", "admin",

            CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var db = (DatabaseSessionEmbedded) pool.acquire();
    db.createClass("Test");
    db.begin();
    db.newEntity("Test");
    db.close();
    db = (DatabaseSessionEmbedded) pool.acquire();
    assertEquals(db.countClass("Test"), 0);
    db.close();
    pool.close();
    youTrackDb.close();
  }

  @Test
  public void testPoolDoubleClose() {
    final var youTrackDb =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 1)
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());

    if (!youTrackDb.exists("test")) {
      youTrackDb.execute(
          "create database "
              + "test"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin)");
    }

    @SuppressWarnings("unchecked") final SessionPool<DatabaseSession> pool =
        new SessionPoolImpl<>((YouTrackDBAbstract<?, DatabaseSession>) youTrackDb, "test", "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var db = pool.acquire();
    db.close();
    pool.close();
    youTrackDb.close();
  }
}
