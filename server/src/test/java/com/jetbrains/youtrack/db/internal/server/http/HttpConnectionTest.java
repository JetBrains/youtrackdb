package com.jetbrains.youtrack.db.internal.server.http;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests HTTP "connect" command.
 */
public class HttpConnectionTest extends BaseHttpDatabaseTest {

  @Test
  public void testConnect() throws Exception {
    Assert.assertEquals(get("connect/" + getDatabaseName()).getResponse().getCode(), 204);
  }

  public void testTooManyConnect() throws Exception {
    if (isInDevelopmentMode())
    // SKIP IT
    {
      return;
    }

    final var originalMax =
        GlobalConfiguration.NETWORK_MAX_CONCURRENT_SESSIONS.getValueAsInteger();
    try {

      var MAX = 10;
      var TOTAL = 30;

      var good = 0;
      var bad = 0;
      GlobalConfiguration.NETWORK_MAX_CONCURRENT_SESSIONS.setValue(MAX);
      for (var i = 0; i < TOTAL; ++i) {
        try {
          final var response =
              get("connect/" + getDatabaseName()).setRetry(0).getResponse().getCode();
          Assert.assertEquals(response, 204);
          good++;
        } catch (IOException e) {
          bad++;
        }
      }

      System.out.printf("\nTOTAL %d - MAX %d - GOOD %d - BAD %d", TOTAL, MAX, good, bad);

      Assert.assertTrue(good >= MAX);
      Assert.assertEquals(bad + good, TOTAL);

    } finally {
      GlobalConfiguration.NETWORK_MAX_CONCURRENT_SESSIONS.setValue(originalMax);
    }
  }

  public void testConnectAutoDisconnectKeepAlive() throws Exception {
    setKeepAlive(true);
    testConnectAutoDisconnect();
  }

  public void testConnectAutoDisconnectNoKeepAlive() throws Exception {
    setKeepAlive(false);
    testConnectAutoDisconnect();
  }

  protected void testConnectAutoDisconnect() throws Exception {
    if (isInDevelopmentMode())
    // SKIP IT
    {
      return;
    }

    final var max = GlobalConfiguration.NETWORK_MAX_CONCURRENT_SESSIONS.getValueAsInteger();

    var TOTAL = max * 3;

    for (var i = 0; i < TOTAL; ++i) {
      final var response = get("connect/" + getDatabaseName()).setRetry(0).getResponse().getCode();
      Assert.assertEquals(response, 204);

      if (i % 100 == 0) {
        System.out.printf("\nConnections " + i);
      }
    }

    System.out.print("\nTest completed");

    Collection<EntityImpl> conns = null;
    for (var i = 0; i < 20; ++i) {
      Assert.assertEquals(
          get("server")
              .setKeepAlive(false)
              .setUserName("root")
              .setUserPassword("root")
              .getResponse()
              .getCode(),
          200);

      final EntityImpl serverStatus =
          new EntityImpl(null).updateFromJSON(getResponse().getEntity().getContent());
      conns = serverStatus.getProperty("connections");

      final var openConnections = conns.size();

      System.out.printf("\nConnections still open: " + openConnections);

      if (openConnections <= 1) {
        break;
      }

      Thread.sleep(1000);
    }

    System.out.printf("\nOK: connections: " + conns.size());

    Assert.assertTrue(conns.size() <= 1);
  }

  @Override
  public String getDatabaseName() {
    return "httpconnection";
  }
}
