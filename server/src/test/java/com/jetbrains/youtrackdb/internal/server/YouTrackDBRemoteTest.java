package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
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
    youTrackDB.create("testLocalUser", DatabaseType.MEMORY,
        new UserCredential("superuser", "password", PredefinedRole.ADMIN));

    try (var g = youTrackDB.openTraversal("testLocalUser", "superuser", "password")) {
      g.addV("label").iterate();
    }
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
  public void testMultiThread() {
    if (!youTrackDB.exists("test")) {
      youTrackDB.create("test", DatabaseType.MEMORY,
          new UserCredential("admin", "admin", PredefinedRole.ADMIN),
          new UserCredential("reader", "reader", PredefinedRole.READER),
          new UserCredential("writer", "writer", PredefinedRole.WRITER)
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


  @After
  public void after() {
    for (var db : youTrackDB.listDatabases()) {
      youTrackDB.drop(db);
    }

    youTrackDB.close();
    server.shutdown();
  }
}
