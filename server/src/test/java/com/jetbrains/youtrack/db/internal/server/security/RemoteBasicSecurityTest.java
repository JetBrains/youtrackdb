package com.jetbrains.youtrack.db.internal.server.security;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
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
      session.getSchema().createClass("one");
      var tx = session.begin();
      tx.newEntity("one");
      tx.commit();
    }
    youTrackDB.close();
  }

  @Test
  public void testCreateAndConnectWriter() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (var writerYouTrack = YourTracks.remote("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig())) {
      try (var db = writerYouTrack.open("test", "writer", "writer")) {
        var tx = db.begin();
        tx.newEntity("one");
        tx.commit();
        db.executeInTx(transaction -> {
          try (var rs = transaction.query("select from one")) {
            assertEquals(2, rs.stream().count());
          }
        });
      }
    }
  }

  @Test
  public void testCreateAndConnectReader() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (BasicYouTrackDB writerOrient = new YouTrackDBAbstract("remote:localhost",
        YouTrackDBConfig.defaultConfig())) {
      try (var writer = writerOrient.open("test", "reader", "reader")) {
        writer.executeInTx(transaction -> {
          try (var rs = transaction.query("select from one")) {
            assertEquals(1, rs.stream().count());
          }
        });
      }
    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
