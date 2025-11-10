package com.jetbrains.youtrackdb.internal.server.security;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class RemoteBasicSecurityTest {

  private YouTrackDBServer server;

  @Ignore
  @Before
  public void before() throws Exception {
    server = YouTrackDBServer.startFromClasspathConfig("abstract-youtrackdb-server-config.xml");
    var youTrackDB =
        (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root", "root");
    youTrackDB.execute(
        "create database test memory users (admin identified by 'admin' role admin, reader"
            + " identified by 'reader' role reader, writer identified by 'writer' role writer)");
    try (var session = youTrackDB.open("test", "admin", "admin")) {
      session.executeSQLScript("""
          create class one;
          begin;
          insert into one;
          commit;
          """);
    }
    youTrackDB.close();
  }

  @Ignore
  @Test
  public void testCreateAndConnectWriter() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (var youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost",
        "root",
        "root")) {
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

  @Ignore
  @Test
  public void testCreateAndConnectReader() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (var youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost",
        "root",
        "root")) {
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
