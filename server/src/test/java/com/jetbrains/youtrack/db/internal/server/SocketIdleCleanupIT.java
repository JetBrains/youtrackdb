package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.client.remote.YouTrackDBRemote;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SocketIdleCleanupIT {

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
    var classpath = System.getProperty("java.class.path");
    System.out.println("Class path " + classpath);
    server =
        YouTrackDBServer.startFromStreamConfig(
            this.getClass().getResourceAsStream("youtrackdb-server-config.xml"));
  }

  @Test
  public void test() throws InterruptedException {
    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CLIENT_CHANNEL_IDLE_CLOSE, true)
            .addGlobalConfigurationParameter(GlobalConfiguration.CLIENT_CHANNEL_IDLE_TIMEOUT, 1)
            .build();
    var youTrackDb = new YouTrackDBImpl("remote:localhost", "root", "root",
        config);
    youTrackDb.execute(
        "create database test memory users (admin identified by 'admin' role admin)");
    var session = youTrackDb.open("test", "admin", "admin");
    var tx = session.begin();
    tx.newVertex("V");
    tx.commit();

    Thread.sleep(2000);
    var remote = (YouTrackDBRemote) YouTrackDBInternal.extract(youTrackDb);
    var connectionManager = remote.getConnectionManager();
    var pool =
        connectionManager.getPool(connectionManager.getURLs().iterator().next());
    assertFalse(pool.getPool().getResources().iterator().next().isConnected());
    session.executeInTx(transaction -> {
      try (var result = transaction.query("select from V")) {
        assertEquals(result.stream().count(), 1);
      }
    });
  }

  @After
  public void after() {
    server.shutdown();
  }
}
