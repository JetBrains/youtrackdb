package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Test;

public class SessionPoolTest {

  private static final String PASSWORD = "adminpwd";

  @Test
  public void testPool() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    final var youTrackDb =
        YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()), config);
    youTrackDb.createIfNotExists("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", PASSWORD, PredefinedLocalRole.ADMIN));
    final SessionPool pool =
        new SessionPoolImpl((YouTrackDBImpl) youTrackDb, "test", "admin", PASSWORD);
    var db = (DatabaseSessionInternal) pool.acquire();
    db.executeInTx(
        transaction -> db.newEntity());
    db.close();
    pool.close();
    youTrackDb.close();
  }

  @Test
  public void testPoolCloseTx() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 1);

    final var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()),
            config);

    youTrackDb.createIfNotExists("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", PASSWORD, PredefinedLocalRole.ADMIN));
    final var pool =
        new SessionPoolImpl(youTrackDb, "test", "admin", PASSWORD);
    var db = pool.acquire();
    db.createClass("Test");
    db.begin();
    db.newEntity("Test");
    db.close();
    db = pool.acquire();
    assertEquals(0, db.countClass("Test"));
    db.close();
    pool.close();
    youTrackDb.close();
  }

  @Test
  public void testPoolDoubleClose() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 1);
    final var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()),
            config);

    youTrackDb.createIfNotExists("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", PASSWORD, PredefinedLocalRole.ADMIN));
    final SessionPool pool =
        new SessionPoolImpl(youTrackDb, "test", "admin", PASSWORD);
    var db = pool.acquire();
    db.close();
    pool.close();
    youTrackDb.close();
  }
}
