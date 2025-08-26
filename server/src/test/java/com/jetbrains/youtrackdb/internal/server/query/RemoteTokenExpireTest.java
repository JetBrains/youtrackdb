package com.jetbrains.youtrackdb.internal.server.query;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.internal.client.remote.RemoteCommandsDispatcherImpl;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.TokenSecurityException;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.token.TokenHandlerImpl;
import java.io.File;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteTokenExpireTest {

  private static final String SERVER_DIRECTORY = "./target/token";
  private YouTrackDBServer server;
  private YouTrackDBRemoteImpl youTrackDB;
  private RemoteDatabaseSession session;


  private static final long expireTimeout = 500;

  @Before
  public void before() throws Exception {

    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));

    server.activate();

    var token = (TokenHandlerImpl) server.getTokenHandler();
    token.setSessionInMills(expireTimeout);

    youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root",
        "root");
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        RemoteTokenExpireTest.class.getSimpleName());

    session = youTrackDB.open(RemoteTokenExpireTest.class.getSimpleName(), "admin", "admin");
    session.command("create class Some");

    session.close();
    youTrackDB.close();

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.NETWORK_SOCKET_RETRY.getKey(), 0);
    config.setProperty(GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getKey(), 1);

    youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root",
        "root",
        config);
    session = youTrackDB.open(RemoteTokenExpireTest.class.getSimpleName(), "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
  }

  private void clean() {
    server.getClientConnectionManager().cleanExpiredConnections();
  }

  private void waitAndClean(@SuppressWarnings("SameParameterValue") long ms) {
    try {
      Thread.sleep(ms);
      clean();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void waitAndClean() {
    waitAndClean(expireTimeout);
  }

  @Test
  public void itShouldNotFailWithQuery() {

    waitAndClean();
    try (var res = session.query("select from Some")) {
      Assert.assertEquals(0, res.stream().count());

    } catch (TokenSecurityException e) {

      Assert.fail("It should not get the exception");
    }
  }

  @Test
  public void itShouldNotFailWithCommand() {
    waitAndClean();
    session.command("begin");
    try (var res = session.execute("insert into V set name = 'foo'")) {
      session.command("commit");
      Assert.assertEquals(1, res.stream().count());
    } catch (TokenSecurityException e) {
      Assert.fail("It should not get the exception");
    }
  }

  @Test
  public void itShouldNotFailWithScript() {
    waitAndClean();
    try (var res = session.computeScript("sql", "begin;insert into V set name = 'foo';commit;")) {

      Assert.assertEquals(1, res.stream().count());

    } catch (TokenSecurityException e) {

      Assert.fail("It should not get the exception");
    }
  }

  @Test
  public void itShouldFailWithQueryNext() {
    try {
      try (var res = session.query("select from ORole")) {
        waitAndClean();
        Assert.assertEquals(3, res.stream().count());
        Assert.fail("It should get an exception");
      } catch (TokenSecurityException e) {
        //expected
      }
    } catch (TokenSecurityException e) {
      //expected
    }

  }

  @Test
  public void itShouldNotFailWithNewTXAndQuery() {
    waitAndClean();

    session.command("begin");
    session.command("insert into Some");
    try (var res = session.query("select from Some")) {
      Assert.assertEquals(1, res.stream().count());
    } catch (TokenSecurityException e) {
      Assert.fail("It should not get the expire exception");
    }
  }

  @Test
  public void itShouldFailAtBeginAndQuery() {
    session.command("begin");
    session.command("insert into Some");
    try (var resultSet = session.query("select from Some")) {
      Assert.assertEquals(1, resultSet.stream().count());
    }
    waitAndClean();

    try {
      //noinspection ResultOfMethodCallIgnored
      session.query("select from Some").stream().toList();
      Assert.fail("It should get the expire exception");
    } catch (TokenSecurityException e) {
      //expected
    }
  }

  @Test
  public void itShouldNotFailWithRoundRobin() {
    var config = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(
            GlobalConfiguration.CLIENT_CONNECTION_STRATEGY,
            RemoteCommandsDispatcherImpl.CONNECTION_STRATEGY.ROUND_ROBIN_CONNECT).
        addGlobalConfigurationParameter(
            GlobalConfiguration.NETWORK_SOCKET_RETRY, 0)
        .addGlobalConfigurationParameter(GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE, 1)
        .build();
    try (var pool = youTrackDB.cachedPool(RemoteTokenExpireTest.class.getSimpleName(), "admin",
        "admin",
        config)) {
      try (var session = pool.acquire()) {
        try (var resultSet = session.query("select from Some")) {
          Assert.assertEquals(0, resultSet.stream().count());
        }

        waitAndClean();
        try {
          try (var resultSet = session.query("select from Some")) {
            Assert.assertEquals(0, resultSet.stream().count());
          }
          Assert.fail("It should  get the expire exception");
        } catch (TokenSecurityException e) {
          //expected
        }
      }
    }
  }

  @After
  public void after() {
    session.close();
    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
