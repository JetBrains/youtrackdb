package com.jetbrains.youtrack.db.internal.server.security;

import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
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
    server = YouTrackDBServer.startFromClasspathConfig("abstract-youtrackdb-server-config.xml");

    BasicYouTrackDB youTrackDB =
        new YouTrackDBAbstract("remote:localhost", "root", "root", YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database RemoteScriptSecurityTest memory users (admin identified by 'admin' role"
            + " admin)");

    youTrackDB.close();
  }

  @Test(expected = SecurityException.class)
  public void testRunJavascript() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (BasicYouTrackDB writerOrient = new YouTrackDBAbstract("remote:localhost",
        YouTrackDBConfig.defaultConfig())) {
      try (var writer =
          writerOrient.open("RemoteScriptSecurityTest", "reader", "reader")) {
        try (var rs = writer.computeScript("javascript", "1+1;")) {
        }
      }
    }
  }

  @Test(expected = SecurityException.class)
  public void testRunEcmascript() {
    // CREATE A SEPARATE CONTEXT TO MAKE SURE IT LOAD STAFF FROM SCRATCH
    try (BasicYouTrackDB writerOrient = new YouTrackDBAbstract("remote:localhost",
        YouTrackDBConfig.defaultConfig())) {
      try (var writer =
          writerOrient.open("RemoteScriptSecurityTest", "reader", "reader")) {

        try (var rs = writer.computeScript("ecmascript", "1+1;")) {
        }
      }
    }
  }

  @After
  public void after() {
    server.shutdown();
  }
}
