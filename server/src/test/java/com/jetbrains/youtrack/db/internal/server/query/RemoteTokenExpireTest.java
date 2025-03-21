package com.jetbrains.youtrack.db.internal.server.query;

import static com.jetbrains.youtrack.db.api.config.GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.client.remote.StorageRemote;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.TokenSecurityException;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.token.TokenHandlerImpl;
import java.io.File;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class RemoteTokenExpireTest {

  private static final String SERVER_DIRECTORY = "./target/token";
  private YouTrackDBServer server;
  private YouTrackDBImpl youTrackDB;
  private DatabaseSession session;
  private int oldPageSize;

  private final long expireTimeout = 500;

  @Before
  public void before() throws Exception {

    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));

    server.activate();

    var token = (TokenHandlerImpl) server.getTokenHandler();
    token.setSessionInMills(expireTimeout);

    youTrackDB = new YouTrackDBImpl("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        RemoteTokenExpireTest.class.getSimpleName());

    session = youTrackDB.open(RemoteTokenExpireTest.class.getSimpleName(), "admin", "admin");
    session.createClass("Some");

    session.close();
    youTrackDB.close();

    var config =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.NETWORK_SOCKET_RETRY, 0)
            .addGlobalConfigurationParameter(QUERY_REMOTE_RESULTSET_PAGE_SIZE, 1).build();

    youTrackDB = new YouTrackDBImpl("remote:localhost", "root", "root", config);
    session = youTrackDB.open(RemoteTokenExpireTest.class.getSimpleName(), "admin", "admin",
        config);
  }

  private void clean() {
    server.getClientConnectionManager().cleanExpiredConnections();
  }

  private void waitAndClean(long ms) {
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
    session.executeInTx(transaction -> {
      try (var res = transaction.query("select from Some")) {
        Assert.assertEquals(0, res.stream().count());

      } catch (TokenSecurityException e) {

        Assert.fail("It should not get the exception");
      }
    });
  }

  @Test
  public void itShouldNotFailWithCommand() {

    waitAndClean();
    var tx = session.begin();
    try (var res = tx.execute("insert into V set name = 'foo'")) {
      tx.commit();

      Assert.assertEquals(1, res.stream().count());

    } catch (TokenSecurityException e) {

      Assert.fail("It should not get the exception");
    }
  }

  @Test
  public void itShouldNotFailWithScript() {

    waitAndClean();
    try (var res = session.runScript("sql", "begin;insert into V set name = 'foo';commit;")) {

      Assert.assertEquals(1, res.stream().count());

    } catch (TokenSecurityException e) {

      Assert.fail("It should not get the exception");
    }
  }

  @Test
  public void itShouldFailWithQueryNext() throws InterruptedException {

    try {
      session.executeInTx(transaction -> {
        try (var res = transaction.query("select from ORole")) {
          waitAndClean();
          Assert.assertEquals(3, res.stream().count());
          Assert.fail("It should get an exception");
        } catch (TokenSecurityException e) {
        }
      });
    } catch (TokenSecurityException e) {
    }

  }

  @Test
  public void itShouldNotFailWithNewTXAndQuery() {

    waitAndClean();
    var tx = session.begin();

    tx.newEntity("Some");
    try (var res = tx.query("select from Some")) {
      Assert.assertEquals(1, res.stream().count());
    } catch (TokenSecurityException e) {
      Assert.fail("It should not get the expire exception");
    } finally {
      tx.rollback();
    }
  }

  @Test
  public void itShouldFailAtBeingAndQuery() {

    var tx = session.begin();
    tx.newEntity("Some");
    try (var resultSet = tx.query("select from Some")) {
      Assert.assertEquals(1, resultSet.stream().count());
    }
    waitAndClean();

    try {
      tx.query("select from Some").stream().count();
      Assert.fail("It should not get the expire exception");
    } catch (TokenSecurityException e) {
    }
  }

  @Test
  public void itShouldNotFailWithRoundRobin() {
    SessionPool pool =
        new SessionPoolImpl(
            youTrackDB,
            RemoteTokenExpireTest.class.getSimpleName(),
            "admin",
            "admin",
            (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(
                    GlobalConfiguration.CLIENT_CONNECTION_STRATEGY,
                    StorageRemote.CONNECTION_STRATEGY.ROUND_ROBIN_CONNECT)
                .build());

    var session = pool.acquire();

    session.executeInTx(transaction -> {
      try (var resultSet = transaction.query("select from Some")) {
        Assert.assertEquals(0, resultSet.stream().count());
      }

      waitAndClean();
      try {
        try (var resultSet = transaction.query("select from Some")) {
          Assert.assertEquals(0, resultSet.stream().count());
        }
        Assert.fail("It should  get the expire exception");
      } catch (TokenSecurityException e) {
        //expected
      }
    });
    pool.close();
  }

  @After
  public void after() {
    QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(oldPageSize);
    session.close();
    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
