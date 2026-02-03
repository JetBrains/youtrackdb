package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedSystemRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.lang3.function.FailableConsumer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class YouTrackDBRemoteTest {
  private YouTrackDBServer server;
  private YouTrackDB youTrackDB;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(DbTestBase.getBaseDirectoryPathStr(YouTrackDBRemoteTest.class));
    server.startup(
        "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
    server.activate();

    youTrackDB = YourTracks.instance("localhost", 45940, "root", "root");
  }

  @Test
  public void createAndUseRemoteDatabase() {
    if (!youTrackDB.exists("test")) {
      youTrackDB.create("test", DatabaseType.MEMORY, "admin", "admin", "admin");
    }

    try (var g = youTrackDB.openTraversal("test", "admin", "admin")) {
      g.addV("label").iterate();
    }
  }

  @Test
  public void createDatabaseLoginWithLocalUser() {
    var dbName = "testLocalUser";
    var userName = "superuser";
    var password = "password";

    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential(userName, password, PredefinedLocalRole.ADMIN));

    try (var g = youTrackDB.openTraversal(dbName, userName, password)) {
      g.addV("label").iterate();
    }

    youTrackDB.drop(dbName);
  }

  @Test
  public void createDatabaseLoginWithLocalUserAndWrongPassword() {
    var dbName = "testLocalUser1";
    var userName = "superuser";
    var password = "password";

    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential(userName, password, PredefinedLocalRole.ADMIN));

    try {
      try (var g = youTrackDB.openTraversal(dbName, userName, "pass")) {
        g.addV("label").iterate();
      }
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(
          e.getMessage().contains("Combination of username/databasename/password are incorrect"));
    }

    youTrackDB.drop(dbName);
  }

  @Test
  public void createDatabaseLoginWithLocalUserAndWrongUsername() {
    var dbName = "testLocalUser3";
    var userName = "superuser";
    var password = "password";

    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential(userName, password, PredefinedLocalRole.ADMIN));

    try {
      try (var g = youTrackDB.openTraversal(dbName, "super", password)) {
        g.addV("label").iterate();
      }
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(
          e.getMessage().contains("Combination of username/databasename/password are incorrect"));
    }

    youTrackDB.drop(dbName);
  }

  @Test
  public void createDatabaseLoginWithLocalUserAndWrongDbName() {
    var dbName = "testLocalUser4";
    var userName = "superuser";
    var password = "password";

    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential(userName, password, PredefinedLocalRole.ADMIN));

    try {
      try (var g = youTrackDB.openTraversal("db", userName, password)) {
        g.addV("label").iterate();
      }
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(
          e.getMessage().contains("Database 'db' does not exist"));
    }

    youTrackDB.drop(dbName);
  }

  @Test(expected = RuntimeException.class)
  public void doubleCreateRemoteDatabase() {
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", "admin", "admin");
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", "admin", "admin");
  }

  @Test
  public void createDropRemoteDatabase() {
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", "admin", "admin");
    Assert.assertTrue(youTrackDB.exists("test"));
    youTrackDB.drop("test");
    Assert.assertFalse(youTrackDB.exists("test"));
  }

  @Test
  public void backupAndRestoreRemoteDatabase() {
    var dbName = "testBackupRestore";
    var backupDir = new File(DbTestBase.getBaseDirectoryPathStr(YouTrackDBRemoteTest.class),
        "backupDir");
    FileUtils.deleteRecursively(backupDir);
    Assert.assertTrue(backupDir.mkdirs());

    youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

    try (var g = youTrackDB.openTraversal(dbName, "admin", "admin")) {
      g.addV("Person").property("name", "Alice").iterate();
      g.addV("Person").property("name", "Bob").iterate();
      g.backup(backupDir.toPath());
    }

    youTrackDB.drop(dbName);
    Assert.assertFalse(youTrackDB.exists(dbName));

    youTrackDB.restore(dbName, backupDir.getAbsolutePath());

    Assert.assertTrue(youTrackDB.exists(dbName));
    try (var g = youTrackDB.openTraversal(dbName, "admin", "admin")) {
      var count = g.V().hasLabel("Person").count().next();
      Assert.assertEquals(2L, count.longValue());
    }

    youTrackDB.drop(dbName);
    FileUtils.deleteRecursively(backupDir);
  }

  @Test
  public void testMultiThread() {
    if (!youTrackDB.exists("test")) {
      youTrackDB.create("test", DatabaseType.MEMORY,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN),
          new LocalUserCredential("reader", "reader", PredefinedLocalRole.READER),
          new LocalUserCredential("writer", "writer", PredefinedLocalRole.WRITER)
      );
    }

    try (var g = youTrackDB.openTraversal("test", "admin", "admin")) {
      g.inject(1, 2, 3).addV("label").iterate();

      // do a query and assert on other thread
      Runnable acquirer =
          () -> {
            var res = g.V().hasLabel("label").count().next();
            Assert.assertEquals(3, res.longValue());
          };

      // spawn 20 threads
      var futures =
          IntStream.range(0, 19)
              .boxed()
              .map(i -> CompletableFuture.runAsync(acquirer))
              .toList();

      futures.forEach(CompletableFuture::join);
    }
  }

  @Test
  public void testListDatabases() {
    Assert.assertEquals(0, youTrackDB.listDatabases().size());
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", "admin", "admin");

    var databases = youTrackDB.listDatabases();
    Assert.assertEquals(1, databases.size());
    Assert.assertTrue(databases.contains("test"));
  }

  @Test
  public void testCreateRootSystemUser() throws Exception {
    createAndCheckSystemUser(PredefinedSystemRole.ROOT,
        graphTraversalSource -> graphTraversalSource.command("create class TestClass")
    );
  }

  @Test
  public void testCreateGuestSystemUser() throws Exception {
    createAndCheckSystemUser(PredefinedSystemRole.GUEST, graphTraversalSource -> {
      try {
        graphTraversalSource.executeInTx(traversalSource ->
            traversalSource.V().iterate()
        );
        Assert.fail("Guests can not read the data");
      } catch (Exception e) {
        //expected
      }

      try (var scopedYtdb = YourTracks.instance("localhost", 45940, "systemUser", "spwd")) {
        Assert.assertTrue(scopedYtdb.listDatabases().contains("test"));
        Assert.assertTrue(scopedYtdb.exists("test"));
      }
    });
  }

  private void createAndCheckSystemUser(PredefinedSystemRole role,
      FailableConsumer<YTDBGraphTraversalSource, Exception> checker) throws Exception {
    var systemUserName = "systemUser";

    var users = youTrackDB.listSystemUsers();
    Assert.assertFalse(users.contains(systemUserName));

    var dbName = "test";
    youTrackDB.createSystemUser(systemUserName, "spwd", role);
    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential("user", "user", PredefinedLocalRole.ADMIN));

    users = youTrackDB.listSystemUsers();
    Assert.assertTrue(users.contains(systemUserName));

    try (var traversal = youTrackDB.openTraversal(dbName, systemUserName, "spwd")) {
      checker.accept(traversal);
    }

    youTrackDB.dropSystemUser(systemUserName);
    users = youTrackDB.listSystemUsers();
    Assert.assertFalse(users.contains(systemUserName));

    try (var traversal = youTrackDB.openTraversal(dbName, systemUserName, "spwd")) {
      checker.accept(traversal);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(
          e.getMessage().contains("Combination of username/databasename/password are incorrect"));
    }

    youTrackDB.drop(dbName);
  }


  @Test
  public void testCreateDatabaseWithMultipleConfigKeys() {
    var dbName = "testConfigKeys";
    var config = new BaseConfiguration();
    config.setProperty("key1", "value1");
    config.setProperty("key2", "value2");
    config.setProperty("key3", "value3");

    youTrackDB.create(dbName, DatabaseType.MEMORY, config, "admin", "admin", "admin");

    Assert.assertTrue(youTrackDB.exists(dbName));

    youTrackDB.drop(dbName);
  }

  @After
  public void after() {
    for (var db : youTrackDB.listDatabases()) {
      youTrackDB.drop(db);
    }

    youTrackDB.close();
    server.shutdown();
  }
}
