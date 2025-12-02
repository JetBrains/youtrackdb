package com.jetbrains.youtrackdb.internal.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageDoesNotExistException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

public class YouTrackDBEmbeddedTests {

  @After
  public void after() {
    YTDBGraphFactory.closeAll();
  }

  @Test
  public void createAndUseEmbeddedDatabase() {
    try (final var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDb.create("createAndUseEmbeddedDatabase", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      final var db =
          (DatabaseSessionInternal)
              youTrackDb.open(
                  "createAndUseEmbeddedDatabase", "admin", DbTestBase.ADMIN_PASSWORD);
      db.executeInTx(
          Transaction::newEntity);
      db.close();
    }
  }

  @Test(expected = CoreException.class)
  public void testEmbeddedDoubleCreate() {
    try (var youTrackDb = YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      youTrackDb.create("test", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      youTrackDb.create("test", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
    }
  }

  @Test
  public void createDropEmbeddedDatabase() {
    try (var youTrackDb = YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + "createDropEmbeddedDatabase")) {
      youTrackDb.create("test", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      assertTrue(youTrackDb.exists("test"));
      youTrackDb.drop("test");
      assertFalse(youTrackDb.exists("test"));
    }
  }

  @Test
  public void testMultiThread() {
    try (final var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDb.create("testMultiThread", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      final SessionPool pool =
          new SessionPoolImpl(
              youTrackDb, "testMultiThread", "admin", DbTestBase.ADMIN_PASSWORD);

      // do a query and assert on other thread
      Runnable acquirer =
          () -> {
            try (var db = pool.acquire()) {
              db.executeInTx(transaction -> {
                assertThat(db.isActiveOnCurrentThread()).isTrue();
                final var res = transaction.query("SELECT * FROM OUser");
                assertThat(res.toList()).hasSize(1); // Only 'admin' created in this test
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
    try (var youTrackDb = YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + "listTest1")) {
      assertEquals(0, youTrackDb.listDatabases().size());
      youTrackDb.create("test", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      var databases = youTrackDb.listDatabases();
      assertEquals(1, databases.size());
      assertTrue(databases.contains("test"));
    }
  }

  @Test
  public void testListDatabasesPersistent() {
    try (var youTrackDb = YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + "listTest2")) {
      assertEquals(0, youTrackDb.listDatabases().size());
      youTrackDb.create("testListDatabase", DatabaseType.DISK,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      var databases = youTrackDb.listDatabases();
      assertEquals(1, databases.size());
      assertTrue(databases.contains("testListDatabase"));
      youTrackDb.drop("testListDatabase");
    }
  }

  @Test
  public void testRegisterDatabase() {
    try (final var youtrack = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + ThreadLocalRandom.current().nextInt())) {
      final var youtrackEmbedded = (YouTrackDBInternalEmbedded) YouTrackDBInternal.extract(
          youtrack);
      youtrackEmbedded.getSystemDatabase().executeInDBScope(session -> {
        session.executeInTx(tx -> {
          var security = session.getMetadata().getSecurity();
          security.createUser("root", "root", "root");
        });

        return null;
      });

      assertEquals(0, youtrackEmbedded.listDatabases("", "").size());
      youtrackEmbedded.initCustomStorage("database1",
          DbTestBase.getBaseDirectoryPathStr(getClass()) +
              "testRegisterDatabase/database1"
      );
      try (final var db = youtrackEmbedded.open("database1", "root", "root")) {
        assertEquals("database1", db.getDatabaseName());
      }
      youtrackEmbedded.initCustomStorage("database2",
          DbTestBase.getBaseDirectoryPathStr(getClass()) +
              "testRegisterDatabase/database2"
      );

      try (final var db = youtrackEmbedded.open("database2", "root", "root")) {
        assertEquals("database2", db.getDatabaseName());
      }
      youtrackEmbedded.drop("database1", null, null);
      youtrackEmbedded.drop("database2", null, null);
      youtrackEmbedded.close();
    }
  }

  @Test
  public void testCopyOpenedDatabase() {
    try (final var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDb.create("testCopyOpenedDatabase", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      DatabaseSessionEmbedded db1;
      try (var db =
          youTrackDb.open(
              "testCopyOpenedDatabase", "admin", DbTestBase.ADMIN_PASSWORD)) {
        db1 = db.copy();
      }
      assertFalse(db1.isClosed());
      db1.close();
    }
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseCreate() {
    var youTrackDb = YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDb.close();
    youTrackDb.create("test", DatabaseType.MEMORY,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseOpen() {
    var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDb.close();
    youTrackDb.open("testUseAfterCloseOpen", "", "");
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseList() {
    var youTrackDb = YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDb.close();
    youTrackDb.listDatabases();
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseExists() {
    var youTrackDb = YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDb.close();
    youTrackDb.exists("");
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseOpenPoolInternal() {
    var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDb.close();
    youTrackDb.openPool("", "", "", YouTrackDBConfig.defaultConfig());
  }

  @Test(expected = DatabaseException.class)
  public void testUseAfterCloseDrop() {
    var youTrackDb = YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDb.close();
    youTrackDb.drop("");
  }

  @Test
  public void testDropTL() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    try (final var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()),
            config)) {
      youTrackDb.createIfNotExists("some", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      youTrackDb.createIfNotExists("some1", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      var db = youTrackDb.open("some", "admin", DbTestBase.ADMIN_PASSWORD);
      youTrackDb.drop("some1");
      db.close();
    }
  }

  @Test
  public void testClosePool() {
    try (var youTrackDB = YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + "testClosePool")) {
      youTrackDB.createIfNotExists("test", DatabaseType.DISK,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      final SessionPool pool =
          new SessionPoolImpl(
              (YouTrackDBImpl) youTrackDB,
              "test",
              "admin",
              DbTestBase.ADMIN_PASSWORD);
      assertFalse(pool.isClosed());
      pool.close();
      assertTrue(pool.isClosed());
    }


  }

  @Test
  public void testPoolFactory() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.DB_CACHED_POOL_CAPACITY.getKey(), 2);
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()), config)) {
      youTrackDB.createIfNotExists("testdb", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN),
          new UserCredential("reader", DbTestBase.ADMIN_PASSWORD, PredefinedRole.READER),
          new UserCredential("writer", DbTestBase.ADMIN_PASSWORD, PredefinedRole.WRITER)
      );
      var poolAdmin1 =
          youTrackDB.cachedPool(
              "testdb",
              "admin",
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
                  .build());
      var poolAdmin2 =
          youTrackDB.cachedPool(
              "testdb",
              "admin",
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
                  .build());
      var poolReader1 =
          youTrackDB.cachedPool("testdb", "reader", DbTestBase.ADMIN_PASSWORD);
      var poolReader2 =
          youTrackDB.cachedPool("testdb", "reader", DbTestBase.ADMIN_PASSWORD);

      assertEquals(poolAdmin1, poolAdmin2);
      assertEquals(poolReader1, poolReader2);
      assertNotEquals(poolAdmin1, poolReader1);

      var poolWriter1 =
          youTrackDB.cachedPool("testdb", "writer", DbTestBase.ADMIN_PASSWORD);
      var poolWriter2 =
          youTrackDB.cachedPool("testdb", "writer", DbTestBase.ADMIN_PASSWORD);
      assertEquals(poolWriter1, poolWriter2);

      var poolAdmin3 =
          youTrackDB.cachedPool(
              "testdb",
              "admin",
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
                  .build());
      assertNotEquals(poolAdmin1, poolAdmin3);
    }
  }

  @Test
  public void testPoolFactoryCleanUp() throws Exception {
    var config = new BaseConfiguration();

    config.setProperty(GlobalConfiguration.DB_CACHED_POOL_CAPACITY.getKey(), 2);
    config.setProperty(GlobalConfiguration.DB_CACHED_POOL_CLEAN_UP_TIMEOUT.getKey(), 1_000);
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()), config)) {
      youTrackDB.createIfNotExists("testdb", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      youTrackDB.createIfNotExists("testdb1", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));

      var poolNotUsed =
          youTrackDB.cachedPool(
              "testdb1",
              "admin",
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
                  .build());
      var poolAdmin1 =
          youTrackDB.cachedPool(
              "testdb",
              "admin",
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
                  .build());
      var poolAdmin2 =
          youTrackDB.cachedPool(
              "testdb",
              "admin",
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
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
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
                  .build());
      assertNotEquals(poolAdmin1, poolAdmin3);
      assertFalse(poolAdmin3.isClosed());

      var poolOther =
          youTrackDB.cachedPool(
              "testdb",
              "admin",
              DbTestBase.ADMIN_PASSWORD,
              YouTrackDBConfig.builder()
                  .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS,
                      false)
                  .build());
      assertNotEquals(poolNotUsed, poolOther);
      assertTrue(poolNotUsed.isClosed());
    }
  }

  @Test
  public void testInvalidatePoolCache() {
    final var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.DB_CACHED_POOL_CAPACITY.getKey(), 2);
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    final var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()), config);
    youTrackDB.create("testdb", DatabaseType.MEMORY,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
    var poolAdmin1 =
        youTrackDB.cachedPool("testdb", "admin", DbTestBase.ADMIN_PASSWORD);
    var poolAdmin2 =
        youTrackDB.cachedPool("testdb", "admin", DbTestBase.ADMIN_PASSWORD);

    assertEquals(poolAdmin1, poolAdmin2);

    youTrackDB.invalidateCachedPools();

    poolAdmin1 = youTrackDB.cachedPool("testdb", "admin", DbTestBase.ADMIN_PASSWORD);
    assertNotEquals(poolAdmin2, poolAdmin1);
    youTrackDB.close();
  }

  @Test
  public void testOpenKeepClean() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()) + "keepClean",
            config);
    try {
      youTrackDb.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    } catch (Exception e) {
      // ignore
    }
    assertFalse(youTrackDb.exists("test"));

    youTrackDb.close();
  }

  @Test
  public void testYouTrackDBDatabaseOnlyMemory() {
    final var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + "testYouTrackDBDatabaseOnlyMemory");
    youTrackDb.create("test", DatabaseType.MEMORY,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
    final var db =
        (DatabaseSessionInternal)
            youTrackDb.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    db.executeInTx(
        Transaction::newEntity);
    db.close();
    youTrackDb.close();
  }

  @Test
  public void createForceCloseOpen() {
    try (final var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDB.create("testCreateForceCloseOpen", DatabaseType.DISK,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      youTrackDB.internal.forceDatabaseClose("test");
      var db1 =
          youTrackDB.open(
              "testCreateForceCloseOpen", "admin", DbTestBase.ADMIN_PASSWORD);
      assertFalse(db1.isClosed());
      db1.close();
      youTrackDB.drop("testCreateForceCloseOpen");
    }
  }

  @Test
  @Ignore
  public void autoClose() throws InterruptedException {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()),
        config);
    var embedded = ((YouTrackDBInternalEmbedded) YouTrackDBInternal.extract(
        youTrackDB));
    embedded.initAutoClose(3000);
    youTrackDB.create("test", DatabaseType.DISK,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
    var db1 = youTrackDB.open("test", "admin",
        DbTestBase.ADMIN_PASSWORD);
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
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    try (var youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()),
            config)) {
      youTrackDB.open("testOpenNotExistDatabase", "two", "three");
    }
  }

  @Test
  public void testExecutor() throws ExecutionException, InterruptedException {
    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      youTrackDb.create("testExecutor", DatabaseType.MEMORY);
      var internal = (YouTrackDBInternalEmbedded) YouTrackDBInternal.extract(youTrackDb);
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
    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      youTrackDb.create("testExecutorNoAuthorization", DatabaseType.MEMORY);
      var internal = (YouTrackDBInternalEmbedded) YouTrackDBInternal.extract(youTrackDb);
      var result =
          internal.executeNoAuthorizationAsync(
              "testExecutorNoAuthorization",
              (session) -> !session.isClosed() || session.getCurrentUser() == null);

      assertTrue(result.get());
    }
  }

  @Test
  public void testScheduler() throws InterruptedException {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()),
            config);
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
    try (final var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDb.create("testUUID", DatabaseType.MEMORY,
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
      final var session =
          youTrackDb.open("testUUID", "admin", DbTestBase.ADMIN_PASSWORD);
      assertNotNull(
          ((AbstractStorage) session.getStorage())
              .getUuid());
      session.close();
    }
  }

  @Test
  public void testPersistentUUID() {
    final var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDb.create("testPersistentUUID", DatabaseType.DISK,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
    final var session =
        youTrackDb.open("testPersistentUUID", "admin", DbTestBase.ADMIN_PASSWORD);
    var uuid =
        ((AbstractStorage) session.getStorage()).getUuid();
    assertNotNull(uuid);
    session.close();
    youTrackDb.close();

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    var youTrackDb1 =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()),
            config);
    var session1 =
        youTrackDb1.open("testPersistentUUID", "admin", DbTestBase.ADMIN_PASSWORD);
    assertEquals(
        uuid,
        ((AbstractStorage) session1.getStorage()).getUuid());
    session1.close();
    youTrackDb1.drop("testPersistentUUID");
    youTrackDb1.close();
  }
}
