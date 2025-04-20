package com.jetbrains.youtrack.db.internal.server.security;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RemoteBasicSecurityTest {

  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {
    server = YouTrackDBServer.startFromClasspathConfig("abstract-youtrackdb-server-config.xml");
    var youTrackDB =
        YourTracks.remote("remote:localhost", "root", "root",
            YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database test memory users (admin identified by 'admin' role admin, reader"
            + " identified by 'reader' role reader, writer identified by 'writer' role writer)");
    try (var session = youTrackDB.open("test", "admin", "admin")) {
      session.executeSQLScript("""
          ceate class one;
          begin;
          insert into one;
          commit;
          """);
    }
    youTrackDB.close();
  }

  @Test
  public void testCreateAndConnectWriter() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (var youTrackDB = YourTracks.remote("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig())) {
      try (var session = youTrackDB.open("test", "writer", "writer")) {
        session.executeSQLScript("""
            begin;
            insert into one;
            commit;
            """);

        try (var rs = session.query("select from one")) {
          assertEquals(2, rs.stream().count());
        }
      }
    }
  }

  @Test
  public void testCreateAndConnectReader() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (var youTrackDB = YourTracks.remote("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig())) {
      try (var reader = youTrackDB.open("test", "reader", "reader")) {
        try (var rs = reader.query("select from one")) {
          assertEquals(1, rs.stream().count());
        }
      }
    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
