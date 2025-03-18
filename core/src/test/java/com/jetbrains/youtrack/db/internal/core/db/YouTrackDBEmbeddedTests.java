package com.jetbrains.youtrack.db.internal.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.StorageDoesNotExistException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.io.File;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class YouTrackDBEmbeddedTests {

  @Test
  public void testCompatibleUrl() {
    try (YouTrackDB youTrackDb = new YouTrackDBImpl(
        "plocal:" + DbTestBase.getBaseDirectoryPath(getClass()) + "compatibleUrl",
        YouTrackDBConfig.defaultConfig())) {
    }
    try (YouTrackDB youTrackDb = new YouTrackDBImpl(
        "memory:" + DbTestBase.getBaseDirectoryPath(getClass()) + "compatibleUrl",
        YouTrackDBConfig.defaultConfig())) {
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWrongUrlFalure() {
    new YouTrackDBImpl("wrong", YouTrackDBConfig.defaultConfig()).close();
  }

  @Test
  public void createAndUseEmbeddedDatabase() {
    try (final YouTrackDB youTrackDb =
        CreateDatabaseUtil.createDatabase(
            "createAndUseEmbeddedDatabase", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY)) {
      final var db =
          (DatabaseSessionInternal)
              youTrackDb.open(
                  "createAndUseEmbeddedDatabase", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      db.executeInTx(
          () -> db.newEntity());
      db.close();
    }
  }

  @Test(expected = DatabaseException.class)
  public void testEmbeddedDoubleCreate() {
    try (YouTrackDB youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      youTrackDb.create("test", DatabaseType.MEMORY);
      youTrackDb.create("test", DatabaseType.MEMORY);
    }
  }

  @Test
  public void createDropEmbeddedDatabase() {
    try (YouTrackDB youTrackDb = new YouTrackDBImpl(
        DbTestBase.embeddedDBUrl(getClass()) + "createDropEmbeddedDatabase",
        YouTrackDBConfig.defaultConfig())) {
      youTrackDb.create("test", DatabaseType.MEMORY);
      assertTrue(youTrackDb.exists("test"));
      youTrackDb.drop("test");
      assertFalse(youTrackDb.exists("test"));
    }
  }

  @Test
  public void testMultiThread() {
    try (final var youTrackDb =
        CreateDatabaseUtil.createDatabase(
            "testMultiThread", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY)) {
      final SessionPool pool =
          new SessionPoolImpl(
              youTrackDb, "testMultiThread", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

      // do a query and assert on other thread
      Runnable acquirer =
          () -> {
            try (var db = pool.acquire()) {
              db.executeInTx(() -> {
                assertThat(((DatabaseSessionInternal) db).isActiveOnCurrentThread()).isTrue();
                final var res = db.query("SELECT * FROM OUser");
                assertThat(res).hasSize(1); // Only 'admin' created in this test
              });
            }
          };

      // spawn 20 threads
      final var futures =
          IntStream.range(0, 19)
              .boxed()
              .map(i -> CompletableFuture.runAsync(acquirer))
              .toList();

      futures.forEach(CompletableFuture::join);

      pool.close();
    }
  }

  @Test
  public void testListDatabases() {
    var youTrackDb = new YouTrackDBImpl(
        DbTestBase.embeddedDBUrl(getClass()) + "listTest1",
        YouTrackDBConfig.defaultConfig());
    assertEquals(0, youTrackDb.list().size());
    youTrackDb.create("test", DatabaseType.MEMORY);
    var databases = youTrackDb.list();
    assertEquals(1, databases.size());
    assertTrue(databases.contains("test"));
    youTrackDb.close();
  }

  @Test
  public void testListDatabasesPersistent() {
    YouTrackDB youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()) + "listTest2",
        YouTrackDBConfig.defaultConfig());
    assertEquals(0, youTrackDb.list().size());
    youTrackDb.create("testListDatabase", DatabaseType.PLOCAL);
    var databases = youTrackDb.list();
    assertEquals(1, databases.size());
    assertTrue(databases.contains("testListDatabase"));
    youTrackDb.drop("testListDatabase");
    youTrackDb.close();
  }

  @Test
  public void testRegisterDatabase() {
    final var youtrack = new YouTrackDBImpl(
        DbTestBase.embeddedDBUrl(getClass()) + "testRegisterDatabase",
        YouTrackDBConfig.defaultConfig());
    try {
      youtrack.execute("create system user admin identified by 'admin' role root");

      final var youtrackEmbedded = (YouTrackDBEmbedded) youtrack.internal;
      assertEquals(0, youtrackEmbedded.listDatabases("", "").size());
      youtrackEmbedded.initCustomStorage("database1", DbTestBase.getBaseDirectoryPath(getClass()) +
              "testRegisterDatabase/database1",
          "", "");
      try (final DatabaseSession db = youtrackEmbedded.open("database1", "admin", "admin")) {
        assertEquals("database1", db.getDatabaseName());
      }
      youtrackEmbedded.initCustomStorage("database2", DbTestBase.getBaseDirectoryPath(getClass()) +
              "testRegisterDatabase/database2",
          "", "");

      try (final DatabaseSession db = youtrackEmbedded.open("database2", "admin", "admin")) {
        assertEquals("database2", db.getDatabaseName());
      }
      youtrackEmbedded.drop("database1", null, null);
      youtrackEmbedded.drop("database2", null, null);
      youtrackEmbedded.close();
    } finally {
      FileUtils.deleteRecursively(
          new File(DbTestBase.getBaseDirectoryPath(getClass()) + "testRegisterDatabase"));
    }
  }

  @Test
  public void testCopyOpenedDatabase() {
    try (final YouTrackDB youTrackDb =
        CreateDatabaseUtil.createDatabase(
            "testCopyOpenedDatabase", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY)) {
      DatabaseSession db1;
      try (var db =
          (DatabaseSessionInternal)
              youTrackDb.open(
                  "testCopyOpenedDatabase", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
        db1 = db.copy();
      }
      assertFalse(db1.isClosed());
      db1.close();
    }
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseCreate() {
    var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.close();
    youTrackDb.create("test", DatabaseType.MEMORY);
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseOpen() {
    var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.close();
    youTrackDb.open("testUseAfterCloseOpen", "", "");
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseList() {
    var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.close();
    youTrackDb.list();
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseExists() {
    var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.close();
    youTrackDb.exists("");
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseOpenPoolInternal() {
    var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.close();
    youTrackDb.openPool("", "", "", YouTrackDBConfig.defaultConfig());
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseDrop() {
    var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDb.close();
    youTrackDb.drop("");
  }

  @Test
  public void testPoolByUrl() {
    final YouTrackDB youTrackDb =
        CreateDatabaseUtil.createDatabase(
            "some", DbTestBase.embeddedDBUrl(getClass()) + "poolTest",
            CreateDatabaseUtil.TYPE_PLOCAL);
    youTrackDb.close();

    final SessionPool pool =
        new SessionPoolImpl(
            DbTestBase.embeddedDBUrl(getClass()) + "poolTest/some", "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    pool.close();
  }

  @Test
  public void testDropTL() {
    final var youTrackDb =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    if (!youTrackDb.exists("some")) {
      youTrackDb.execute(
          "create database "
              + "some"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin)");
    }
    if (!youTrackDb.exists("some1")) {
      youTrackDb.execute(
          "create database "
              + "some1"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin)");
    }
    var db = youTrackDb.open("some", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    youTrackDb.drop("some1");
    db.close();
    youTrackDb.close();
  }

  @Test
  public void testClosePool() {
    try (var youTrackDB = YourTracks.embedded(
        DbTestBase.getBaseDirectoryPath(getClass()) + "testClosePool",
        YouTrackDBConfig.defaultConfig())) {
      if (!youTrackDB.exists("test")) {
        youTrackDB.create("test", DatabaseType.PLOCAL, "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD, "admin");
      }
    }

    final SessionPool pool =
        new SessionPoolImpl(
            DbTestBase.embeddedDBUrl(getClass()) + "testClosePool/test",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    assertFalse(pool.isClosed());
    pool.close();
    assertTrue(pool.isClosed());

  }

  @Test
  public void testPoolFactory() {
    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_CACHED_POOL_CAPACITY, 2)
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
            .build();
    var youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()), config);
    if (!youTrackDB.exists("testdb")) {
      youTrackDB.execute(
          "create database "
              + "testdb"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin, reader identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role reader, writer identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role writer)");
    }
    var poolAdmin1 =
        youTrackDB.cachedPool(
            "testdb",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    var poolAdmin2 =
        youTrackDB.cachedPool(
            "testdb",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    var poolReader1 =
        youTrackDB.cachedPool("testdb", "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var poolReader2 =
        youTrackDB.cachedPool("testdb", "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    assertEquals(poolAdmin1, poolAdmin2);
    assertEquals(poolReader1, poolReader2);
    assertNotEquals(poolAdmin1, poolReader1);

    var poolWriter1 =
        youTrackDB.cachedPool("testdb", "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var poolWriter2 =
        youTrackDB.cachedPool("testdb", "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    assertEquals(poolWriter1, poolWriter2);

    var poolAdmin3 =
        youTrackDB.cachedPool(
            "testdb",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    assertNotEquals(poolAdmin1, poolAdmin3);

    youTrackDB.close();
  }

  @Test
  public void testPoolFactoryCleanUp() throws Exception {
    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_CACHED_POOL_CAPACITY, 2)
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_CACHED_POOL_CLEAN_UP_TIMEOUT,
                1_000)
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
            .build();
    var youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()), config);
    if (!youTrackDB.exists("testdb")) {
      youTrackDB.execute(
          "create database "
              + "testdb"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin)");
    }
    if (!youTrackDB.exists("testdb1")) {
      youTrackDB.execute(
          "create database "
              + "testdb1"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin)");
    }

    var poolNotUsed =
        youTrackDB.cachedPool(
            "testdb1",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    var poolAdmin1 =
        youTrackDB.cachedPool(
            "testdb",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    var poolAdmin2 =
        youTrackDB.cachedPool(
            "testdb",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    assertFalse(poolAdmin1.isClosed());
    assertEquals(poolAdmin1, poolAdmin2);

    poolAdmin1.close();

    assertTrue(poolAdmin1.isClosed());

    Thread.sleep(3_000);

    var poolAdmin3 =
        youTrackDB.cachedPool(
            "testdb",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    assertNotEquals(poolAdmin1, poolAdmin3);
    assertFalse(poolAdmin3.isClosed());

    var poolOther =
        youTrackDB.cachedPool(
            "testdb",
            "admin",
            CreateDatabaseUtil.NEW_ADMIN_PASSWORD,
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    assertNotEquals(poolNotUsed, poolOther);
    assertTrue(poolNotUsed.isClosed());

    youTrackDB.close();
  }

  @Test
  public void testInvalidatePoolCache() {
    final var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_CACHED_POOL_CAPACITY, 2)
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
            .build();
    final var youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        config);
    youTrackDB.execute(
        "create database "
            + "testdb"
            + " "
            + "memory"
            + " users ( admin identified by '"
            + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
            + "' role admin)");

    var poolAdmin1 =
        youTrackDB.cachedPool("testdb", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var poolAdmin2 =
        youTrackDB.cachedPool("testdb", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    assertEquals(poolAdmin1, poolAdmin2);

    youTrackDB.invalidateCachedPools();

    poolAdmin1 = youTrackDB.cachedPool("testdb", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    assertNotEquals(poolAdmin2, poolAdmin1);
    youTrackDB.close();
  }

  @Test
  public void testOpenKeepClean() {
    var youTrackDb =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()) + "keepClean",
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    try {
      youTrackDb.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    } catch (Exception e) {
      // ignore
    }
    assertFalse(youTrackDb.exists("test"));

    youTrackDb.close();
  }

  @Test
  public void testYouTrackDBDatabaseOnlyMemory() {
    final YouTrackDB youTrackDb =
        CreateDatabaseUtil.createDatabase("test",
            DbTestBase.embeddedDBUrl(getClass()) + "testYouTrackDBDatabaseOnlyMemory",
            CreateDatabaseUtil.TYPE_MEMORY);
    final var db =
        (DatabaseSessionInternal)
            youTrackDb.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    db.executeInTx(
        () -> db.newEntity());
    db.close();
    youTrackDb.close();
  }

  @Test
  public void createForceCloseOpen() {
    try (final var youTrackDB =
        CreateDatabaseUtil.createDatabase(
            "testCreateForceCloseOpen", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_PLOCAL)) {
      youTrackDB.internal.forceDatabaseClose("test");
      var db1 =
          youTrackDB.open(
              "testCreateForceCloseOpen", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      assertFalse(db1.isClosed());
      db1.close();
      youTrackDB.drop("testCreateForceCloseOpen");
    }
  }

  @Test
  @Ignore
  public void autoClose() throws InterruptedException {
    var youTrackDB =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    var embedded = ((YouTrackDBEmbedded) YouTrackDBInternal.extract(
        youTrackDB));
    embedded.initAutoClose(3000);
    youTrackDB.execute(
        "create database "
            + "test"
            + " "
            + "plocal"
            + " users ( admin identified by '"
            + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
            + "' role admin)");
    var db1 = youTrackDB.open("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    assertFalse(db1.isClosed());
    db1.close();
    assertNotNull(embedded.getStorage("test"));
    Thread.sleep(4100);
    assertNull(embedded.getStorage("test"));
    youTrackDB.drop("test");
    youTrackDB.close();
  }

  @Test(expected = StorageDoesNotExistException.class)
  public void testOpenNotExistDatabase() {
    try (var youTrackDB =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build())) {
      youTrackDB.open("testOpenNotExistDatabase", "two", "three");
    }
  }

  @Test
  public void testExecutor() throws ExecutionException, InterruptedException {
    try (var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      youTrackDb.create("testExecutor", DatabaseType.MEMORY);
      var internal = YouTrackDBInternal.extract(youTrackDb);
      var result =
          internal.execute(
              "testExecutor",
              "admin",
              (session) -> !session.isClosed() || session.getCurrentUser() != null);

      assertTrue(result.get());
    }
  }

  @Test
  public void testExecutorNoAuthorization() throws ExecutionException, InterruptedException {

    try (var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      youTrackDb.create("testExecutorNoAuthorization", DatabaseType.MEMORY);
      var internal = YouTrackDBInternal.extract(youTrackDb);
      var result =
          internal.executeNoAuthorizationAsync(
              "testExecutorNoAuthorization",
              (session) -> !session.isClosed() || session.getCurrentUser() == null);

      assertTrue(result.get());
    }
  }

  @Test
  public void testScheduler() throws InterruptedException {
    var youTrackDb =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    var internal = YouTrackDBInternal.extract(youTrackDb);
    var latch = new CountDownLatch(2);
    internal.schedule(
        new TimerTask() {
          @Override
          public void run() {
            latch.countDown();
          }
        },
        10,
        10);

    assertTrue(latch.await(5, TimeUnit.MINUTES));

    var once = new CountDownLatch(1);
    internal.scheduleOnce(
        new TimerTask() {
          @Override
          public void run() {
            once.countDown();
          }
        },
        10);

    assertTrue(once.await(5, TimeUnit.MINUTES));
  }

  @Test
  public void testUUID() {
    try (final YouTrackDB youTrackDb =
        CreateDatabaseUtil.createDatabase(
            "testUUID", DbTestBase.embeddedDBUrl(getClass()), CreateDatabaseUtil.TYPE_MEMORY)) {
      final var session =
          youTrackDb.open("testUUID", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      assertNotNull(
          ((AbstractPaginatedStorage) ((DatabaseSessionInternal) session).getStorage())
              .getUuid());
      session.close();
    }
  }

  @Test
  public void testPersistentUUID() {
    final YouTrackDB youTrackDb =
        CreateDatabaseUtil.createDatabase(
            "testPersistentUUID", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_PLOCAL);
    final var session =
        youTrackDb.open("testPersistentUUID", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var uuid =
        ((AbstractPaginatedStorage) ((DatabaseSessionInternal) session).getStorage()).getUuid();
    assertNotNull(uuid);
    session.close();
    youTrackDb.close();

    YouTrackDB youTrackDb1 =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    var session1 =
        youTrackDb1.open("testPersistentUUID", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    assertEquals(
        uuid,
        ((AbstractPaginatedStorage) ((DatabaseSessionInternal) session1).getStorage()).getUuid());
    session1.close();
    youTrackDb1.drop("testPersistentUUID");
    youTrackDb1.close();
  }

  @Test
  public void testCreateDatabaseViaSQL() {
    var dbName = "testCreateDatabaseViaSQL";
    var youTrackDb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    try (var result = youTrackDb.execute("create database " + dbName + " plocal")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(true, item.getProperty("created"));
    }
    Assert.assertTrue(youTrackDb.exists(dbName));

    youTrackDb.drop(dbName);
    youTrackDb.close();
  }

  @Test
  public void testCreateDatabaseViaSQLWithUsers() {
    var youTrackDB =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()) + "testCreateDatabaseViaSQLWithUsers",
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    youTrackDB.execute(
        "create database test memory users(admin identified by 'adminpwd' role admin)");
    youTrackDB.open("test", "admin", "adminpwd").close();

    youTrackDB.close();
  }

  @Test
  public void testCreateDatabaseViaSQLIfNotExistsWithUsers() {
    final var youTrackDB =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()) + "testCreateDatabaseViaSQLIfNotExistsWithUsers",
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
    youTrackDB.execute(
        "create database test memory if not exists users(admin identified by 'adminpwd' role"
            + " admin)");

    youTrackDB.execute(
        "create database test memory if not exists users(admin identified by 'adminpwd' role"
            + " admin)");

    youTrackDB.open("test", "admin", "adminpwd").close();
    youTrackDB.close();
  }
}
