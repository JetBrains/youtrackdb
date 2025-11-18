package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class YouTrackDBRemoteTest {

  private static final String SERVER_DIRECTORY = "./target/dbfactory";
  private YouTrackDBServer server;

  private YouTrackDB youTrackDB;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "com/jetbrains/youtrackdb/internal/server/network/youtrackdb-server-config.xml"));
    server.activate();

    youTrackDB = YourTracks.instance("localhost", "root", "root");
  }

  @Ignore
  @Test
  public void createAndUseRemoteDatabase() {
//    if (!youTrackDB.exists("test")) {
//      youTrackDB.execute("create database test memory users (admin identified by 'admin' role admin)");
//    }
//
//    var db = youTrackDB.open("test", "admin",
//        "admin");
//    db.executeSQLScript("""
//        begin;
//        insert into O;
//        commit;
//        """);
//    db.close();
  }

  // @Test(expected = StorageExistsException.class)
  // TODO: Uniform database exist exceptions
  @Test(expected = StorageException.class)
  @Ignore
  public void doubleCreateRemoteDatabase() {
//    youTrackDB.execute("create database test memory users (admin identified by 'admin' role admin)");
//    youTrackDB.execute("create database test memory users (admin identified by 'admin' role admin)");
  }

  @Ignore
  @Test
  public void createDropRemoteDatabase() {
//    youTrackDB.execute(
//        "create database test memory users (admin identified by 'admin' role admin)");
//    assertTrue(youTrackDB.exists("test"));
//    youTrackDB.drop("test");
//    assertFalse(youTrackDB.exists("test"));
  }

  @Ignore
  @Test
  public void testPool() {
//    if (!youTrackDB.exists("test")) {
//      youTrackDB.execute(
//          "create database test memory users (admin identified by 'admin' role admin)");
//    }
//
//    var pool = youTrackDB.cachedPool("test", "admin", "admin");
//    var db = pool.acquire();
//    db.executeSQLScript("""
//        begin;
//        insert into O;
//        commit;
//        """);
//    db.close();
//    pool.close();
  }

  @Test
  @Ignore
  public void testCachedPool() {
//    if (!youTrackDB.exists("testdb")) {
//      youTrackDB.execute(
//          "create database testdb memory users (admin identified by 'admin' role admin, reader"
//              + " identified by 'reader' role reader, writer identified by 'writer' role writer)");
//    }
//
//    var poolAdmin1 = youTrackDB.cachedPool("testdb", "admin", "admin");
//    var poolAdmin2 = youTrackDB.cachedPool("testdb", "admin", "admin");
//    var poolReader1 = youTrackDB.cachedPool("testdb", "reader", "reader");
//    var poolReader2 = youTrackDB.cachedPool("testdb", "reader", "reader");
//
//    assertEquals(poolAdmin1, poolAdmin2);
//    assertEquals(poolReader1, poolReader2);
//    assertNotEquals(poolAdmin1, poolReader1);
//
//    var poolWriter1 = youTrackDB.cachedPool("testdb", "writer", "writer");
//    var poolWriter2 = youTrackDB.cachedPool("testdb", "writer", "writer");
//    assertEquals(poolWriter1, poolWriter2);
//
//    var poolAdmin3 = youTrackDB.cachedPool("testdb", "admin", "admin");
//    assertNotEquals(poolAdmin1, poolAdmin3);
//
//    poolAdmin1.close();
//    poolReader1.close();
//    poolWriter1.close();
  }

  @Ignore
  @Test
  public void testCachedPoolFactoryCleanUp() throws Exception {
//    if (!youTrackDB.exists("testdb")) {
//      youTrackDB.execute(
//          "create database testdb memory users (admin identified by 'admin' role admin)");
//    }
//
//    var poolAdmin1 = youTrackDB.cachedPool("testdb", "admin", "admin");
//    var poolAdmin2 = youTrackDB.cachedPool("testdb", "admin", "admin");
//
//    assertFalse(poolAdmin1.isClosed());
//    assertEquals(poolAdmin1, poolAdmin2);
//
//    poolAdmin1.close();
//
//    assertTrue(poolAdmin1.isClosed());
//
//    Thread.sleep(5_000);
//
//    var poolAdmin3 = youTrackDB.cachedPool("testdb", "admin", "admin");
//    assertNotEquals(poolAdmin1, poolAdmin3);
//    assertFalse(poolAdmin3.isClosed());
//
//    poolAdmin3.close();
  }

  @Test
  @Ignore
  public void testMultiThread() {
//    if (!youTrackDB.exists("test")) {
//      youTrackDB.execute(
//          "create database test memory users (admin identified by 'admin' role admin, reader"
//              + " identified by 'reader' role reader, writer identified by 'writer' role writer)");
//    }
//
//    var pool = youTrackDB.cachedPool("test", "admin", "admin");
//
//    // do a query and assert on other thread
//    Runnable acquirer =
//        () -> {
//          try (var db = pool.acquire()) {
//            var res = db.query("SELECT * FROM OUser");
//            assertEquals(3, res.stream().count());
//          }
//        };
//
//    // spawn 20 threads
//    var futures =
//        IntStream.range(0, 19)
//            .boxed()
//            .map(i -> CompletableFuture.runAsync(acquirer))
//            .toList();
//
//    futures.forEach(CompletableFuture::join);
//
//    pool.close();
  }

  @Ignore
  @Test
  public void testListDatabases() {
//    assertEquals(0, youTrackDB.listDatabases().size());
//    youTrackDB.execute(
//        "create database test memory users (admin identified by 'admin' role admin)");
//    var databases = youTrackDB.listDatabases();
//    assertEquals(1, databases.size());
//    assertTrue(databases.contains("test"));
  }

  @Ignore
  @Test
  public void createDatabaseNoUsers() {
//    youTrackDB.create(
//        "noUser",
//        DatabaseType.MEMORY,
//        YouTrackDBConfig.builder()
//            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
//            .build());
//    try (var session = youTrackDB.open("noUser", "root", "root")) {
//      assertEquals(0, session.query("select from OUser").stream().count());
//    }
  }

  @Ignore
  @Test
  public void createDatabaseDefaultUsers() {
//    youTrackDB.create(
//        "noUser",
//        DatabaseType.MEMORY,
//        YouTrackDBConfig.builder()
//            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, true)
//            .build());
//    try (var session = youTrackDB.open("noUser", "root", "root")) {
//      assertEquals(3, session.query("select from OUser").stream().count());
//    }
  }

  @Ignore
  @Test
  public void testCreateDatabaseViaSQL() {
//    var dbName = "testCreateDatabaseViaSQL";
//
//    try (var result = youTrackDB.execute("create database " + dbName + " disk")) {
//      Assert.assertTrue(result.hasNext());
//      var item = result.next();
//      Assert.assertEquals(true, item.getProperty("created"));
//    }
//    Assert.assertTrue(youTrackDB.exists(dbName));
//    youTrackDB.drop(dbName);
  }

  @After
  public void after() {
    for (var db : youTrackDB.listDatabases()) {
      youTrackDB.drop(db);
    }

    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
