package com.jetbrains.youtrackdb.internal.server.security;

import com.jetbrains.youtrackdb.api.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RemoteScriptSecurityTest {

  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {
    server = YouTrackDBServer.startFromClasspathConfig("abstract-youtrackdb-server-config.xml");

    var youTrackDB =
        (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root", "root");
    youTrackDB.execute(
        "create database RemoteScriptSecurityTest memory users (admin identified by 'admin' role"
            + " admin)");

    youTrackDB.close();
  }

  @Test(expected = SecurityException.class)
  public void testRunJavascript() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (var youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost",
        "root",
        "root")) {
      try (var reader =
          youTrackDB.open("RemoteScriptSecurityTest", "reader", "reader")) {
        try (var rs = reader.computeScript("javascript", "1+1;")) {
        }
      }
    }
  }

  @Test(expected = SecurityException.class)
  public void testRunEcmascript() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (var youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost",
        "root",
        "root")) {
      try (var reader =
          youTrackDB.open("RemoteScriptSecurityTest", "reader", "reader")) {
        try (var rs = reader.computeScript("ecmascript", "1+1;")) {
        }
      }
    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
