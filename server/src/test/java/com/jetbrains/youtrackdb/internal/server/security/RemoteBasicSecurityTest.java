package com.jetbrains.youtrackdb.internal.server.security;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
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
    server = YouTrackDBServer.startFromFileConfig(
        "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
    var youTrackDB = YourTracks.instance("localhost", "root", "root");
    youTrackDB.create("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN),
        new LocalUserCredential("reader", "reader", PredefinedLocalRole.READER),
        new LocalUserCredential("writer", "writer", PredefinedLocalRole.WRITER)
    );
//    try (var session = youTrackDB.open("test", "admin", "admin")) {
//      session.executeSQLScript("""
//          create class one;
//          begin;
//          insert into one;
//          commit;
//          """);
//    }
    youTrackDB.close();
  }

  @Ignore
  @Test
  public void testCreateAndConnectWriter() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
//    try (var youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost",
//        "root",
//        "root")) {
//      try (var session = youTrackDB.open("test", "writer", "writer")) {
//        session.executeSQLScript("""
//            begin;
//            insert into one;
//            commit;
//            """);
//
//        try (var rs = session.query("select from one")) {
//          assertEquals(2, rs.stream().count());
//        }
//      }
//    }
  }

  @Ignore
  @Test
  public void testCreateAndConnectReader() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
//    try (var youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost",
//        "root",
//        "root")) {
//      try (var reader = youTrackDB.open("test", "reader", "reader")) {
//        try (var rs = reader.query("select from one")) {
//          assertEquals(1, rs.stream().count());
//        }
//      }
//    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
