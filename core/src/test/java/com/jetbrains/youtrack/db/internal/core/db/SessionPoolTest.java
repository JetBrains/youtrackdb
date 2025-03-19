package com.jetbrains.youtrack.db.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import org.junit.Test;

public class SessionPoolTest {

  @Test
  public void testPool() {
    final var youTrackDb =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    final SessionPool pool =
        new SessionPoolImpl(youTrackDb, "test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
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
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
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

    final SessionPool pool =
        new SessionPoolImpl(youTrackDb, "test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var db = (DatabaseSessionInternal) pool.acquire();
    db.createClass("Test");
    db.begin();
    db.newEntity("Test");
    db.close();
    db = (DatabaseSessionInternal) pool.acquire();
    assertEquals(db.countClass("Test"), 0);
    db.close();
    pool.close();
    youTrackDb.close();
  }

  @Test
  public void testPoolDoubleClose() {
    final var youTrackDb =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
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

    final SessionPool pool =
        new SessionPoolImpl(youTrackDb, "test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var db = pool.acquire();
    db.close();
    pool.close();
    youTrackDb.close();
  }
}
