package com.jetbrains.youtrack.db.internal.server.security;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
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

public class RemoteScriptSecurityTest {

  private YouTrackDBServer server;

  @Before
  public void before()
      throws IOException,
      InstantiationException,
      InvocationTargetException,
      NoSuchMethodException,
      MBeanRegistrationException,
      IllegalAccessException,
      InstanceAlreadyExistsException,
      NotCompliantMBeanException,
      ClassNotFoundException,
      MalformedObjectNameException {
    GlobalConfiguration.SERVER_BACKWARD_COMPATIBILITY.setValue(false);
    server = YouTrackDBServer.startFromClasspathConfig("abstract-youtrackdb-server-config.xml");

    YouTrackDB youTrackDB =
        new YouTrackDBImpl("remote:localhost", "root", "root", YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database RemoteScriptSecurityTest memory users (admin identified by 'admin' role"
            + " admin)");

    youTrackDB.close();
  }

  @Test(expected = SecurityException.class)
  public void testRunJavascript() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (YouTrackDB writerOrient = new YouTrackDBImpl("remote:localhost",
        YouTrackDBConfig.defaultConfig())) {
      try (var writer =
          writerOrient.open("RemoteScriptSecurityTest", "reader", "reader")) {
        try (var rs = writer.runScript("javascript", "1+1;")) {
        }
      }
    }
  }

  @Test(expected = SecurityException.class)
  public void testRunEcmascript() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (YouTrackDB writerOrient = new YouTrackDBImpl("remote:localhost",
        YouTrackDBConfig.defaultConfig())) {
      try (var writer =
          writerOrient.open("RemoteScriptSecurityTest", "reader", "reader")) {

        try (var rs = writer.runScript("ecmascript", "1+1;")) {
        }
      }
    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
