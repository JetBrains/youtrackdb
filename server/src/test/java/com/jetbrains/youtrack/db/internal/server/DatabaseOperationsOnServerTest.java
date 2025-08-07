package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.tools.config.ServerConfiguration;
import com.jetbrains.youtrack.db.internal.tools.config.ServerUserConfiguration;
import java.io.File;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DatabaseOperationsOnServerTest {
  private static final String SERVER_DIRECTORY = "./target/db";

  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {
    var conf = new ServerConfiguration();
    conf.handlers = new ArrayList<>();
    var rootUser = new ServerUserConfiguration();
    rootUser.name = "root";
    rootUser.password = "root";
    rootUser.resources = "server.listDatabases";
    conf.users = new ServerUserConfiguration[]{rootUser};
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(conf);
    server.activate();

    server
        .getContext()
        .execute("create database " + DatabaseOperationsOnServerTest.class.getSimpleName() +
            " memory users (admin identified by 'admin' role admin)").close();

    assertTrue(server.existsDatabase(DatabaseOperationsOnServerTest.class.getSimpleName()));
    try (var session = server.openSession(
        DatabaseOperationsOnServerTest.class.getSimpleName())) {

      var map = JSONSerializerJackson.INSTANCE.mapFromJson(IOUtils.readStreamAsString(
          this.getClass().getClassLoader().getResourceAsStream("security.json")));
      server.getSecurity().reload(session, map);
    } finally {
      server.dropDatabase(DatabaseOperationsOnServerTest.class.getSimpleName());
    }
  }

  @After
  public void after() {
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }

  @Test
  public void testServerLoginDatabase() {
    assertNotNull(server.authenticateUser("root", "root", "server.listDatabases"));
  }

  @Test
  public void testCreateOpenDatabase() {
    server
        .getContext()
        .execute("create database " + DatabaseOperationsOnServerTest.class.getSimpleName()
            + " memory users (admin identified by 'admin' role admin)").close();
    assertTrue(server.existsDatabase(DatabaseOperationsOnServerTest.class.getSimpleName()));
    BasicDatabaseSession session = server.openSession(
        DatabaseOperationsOnServerTest.class.getSimpleName());
    assertNotNull(session);
    session.close();
    server.dropDatabase(DatabaseOperationsOnServerTest.class.getSimpleName());
  }
}
