package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class YouTrackDBRemoteTest {

  private static final String SERVER_DIRECTORY = "./target/dbfactory";
  private YouTrackDBServer server;

  private RemoteYouTrackDB factory;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "com/jetbrains/youtrack/db/internal/server/network/youtrackdb-server-config.xml"));
    server.activate();

    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_CACHED_POOL_CAPACITY, 2)
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_CACHED_POOL_CLEAN_UP_TIMEOUT,
                300_000)
            .build();

    factory = YourTracks.remote("remote:localhost", "root", "root", config);
  }

  @Test
  public void createAndUseRemoteDatabase() {
    if (!factory.exists("test")) {
      factory.execute("create database test memory users (admin identified by 'admin' role admin)");
    }

    var db = factory.open("test", "admin",
        "admin");
    db.executeSQLScript("""
        begin;
        insert into O;
        commit;
        """);
    db.close();
  }

  // @Test(expected = StorageExistsException.class)
  // TODO: Uniform database exist exceptions
  @Test(expected = StorageException.class)
  public void doubleCreateRemoteDatabase() {
    factory.execute("create database test memory users (admin identified by 'admin' role admin)");
    factory.execute("create database test memory users (admin identified by 'admin' role admin)");
  }

  @Test
  public void createDropRemoteDatabase() {
    factory.execute("create database test memory users (admin identified by 'admin' role admin)");
    assertTrue(factory.exists("test"));
    factory.drop("test");
    assertFalse(factory.exists("test"));
  }

  @Test
  public void testPool() {
    if (!factory.exists("test")) {
      factory.execute("create database test memory users (admin identified by 'admin' role admin)");
    }

    var pool = factory.cachedPool("test", "admin", "admin");
    var db = pool.acquire();
    db.executeSQLScript("""
        begin;
        insert into O;
        commit;
        """);
    db.close();
    pool.close();
  }

  @Test
  @Ignore
  public void testCachedPool() {
    if (!factory.exists("testdb")) {
      factory.execute(
          "create database testdb memory users (admin identified by 'admin' role admin, reader"
              + " identified by 'reader' role reader, writer identified by 'writer' role writer)");
    }

    var poolAdmin1 = factory.cachedPool("testdb", "admin", "admin");
    var poolAdmin2 = factory.cachedPool("testdb", "admin", "admin");
    var poolReader1 = factory.cachedPool("testdb", "reader", "reader");
    var poolReader2 = factory.cachedPool("testdb", "reader", "reader");

    assertEquals(poolAdmin1, poolAdmin2);
    assertEquals(poolReader1, poolReader2);
    assertNotEquals(poolAdmin1, poolReader1);

    var poolWriter1 = factory.cachedPool("testdb", "writer", "writer");
    var poolWriter2 = factory.cachedPool("testdb", "writer", "writer");
    assertEquals(poolWriter1, poolWriter2);

    var poolAdmin3 = factory.cachedPool("testdb", "admin", "admin");
    assertNotEquals(poolAdmin1, poolAdmin3);

    poolAdmin1.close();
    poolReader1.close();
    poolWriter1.close();
  }

  @Test
  public void testCachedPoolFactoryCleanUp() throws Exception {
    if (!factory.exists("testdb")) {
      factory.execute(
          "create database testdb memory users (admin identified by 'admin' role admin)");
    }

    var poolAdmin1 = factory.cachedPool("testdb", "admin", "admin");
    var poolAdmin2 = factory.cachedPool("testdb", "admin", "admin");

    assertFalse(poolAdmin1.isClosed());
    assertEquals(poolAdmin1, poolAdmin2);

    poolAdmin1.close();

    assertTrue(poolAdmin1.isClosed());

    Thread.sleep(5_000);

    var poolAdmin3 = factory.cachedPool("testdb", "admin", "admin");
    assertNotEquals(poolAdmin1, poolAdmin3);
    assertFalse(poolAdmin3.isClosed());

    poolAdmin3.close();
  }

  @Test
  @Ignore
  public void testMultiThread() {
    if (!factory.exists("test")) {
      factory.execute(
          "create database test memory users (admin identified by 'admin' role admin, reader"
              + " identified by 'reader' role reader, writer identified by 'writer' role writer)");
    }

    var pool = factory.cachedPool("test", "admin", "admin");

    // do a query and assert on other thread
    Runnable acquirer =
        () -> {
          try (var db = pool.acquire()) {
            var res = db.query("SELECT * FROM OUser");
            assertEquals(3, res.stream().count());
          }
        };

    // spawn 20 threads
    var futures =
        IntStream.range(0, 19)
            .boxed()
            .map(i -> CompletableFuture.runAsync(acquirer))
            .toList();

    futures.forEach(CompletableFuture::join);

    pool.close();
  }

  @Test
  public void testListDatabases() {
    assertEquals(0, factory.listDatabases().size());
    factory.execute("create database test memory users (admin identified by 'admin' role admin)");
    var databases = factory.listDatabases();
    assertEquals(1, databases.size());
    assertTrue(databases.contains("test"));
  }

  @Test
  public void createDatabaseNoUsers() {
    factory.create(
        "noUser",
        DatabaseType.MEMORY,
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
            .build());
    try (var session = factory.open("noUser", "root", "root")) {
      assertEquals(0, session.query("select from OUser").stream().count());
    }
  }

  @Test
  public void createDatabaseDefaultUsers() {
    factory.create(
        "noUser",
        DatabaseType.MEMORY,
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, true)
            .build());
    try (var session = factory.open("noUser", "root", "root")) {
      assertEquals(3, session.query("select from OUser").stream().count());
    }
  }

  @Test
  public void testCreateDatabaseViaSQL() {
    var dbName = "testCreateDatabaseViaSQL";

    try (var result = factory.execute("create database " + dbName + " disk")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertEquals(true, item.getProperty("created"));
    }
    Assert.assertTrue(factory.exists(dbName));
    factory.drop(dbName);
  }

  @After
  public void after() {
    for (var db : factory.listDatabases()) {
      factory.drop(db);
    }

    factory.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
