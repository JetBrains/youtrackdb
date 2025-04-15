package com.jetbrains.youtrack.db.internal.server.token;

import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class YouTrackDBServerUserTokenTest {

  YouTrackDBServer server;

  @Before
  public void setup() throws Exception {

    server = YouTrackDBServer.startFromClasspathConfig("youtrackdb-server-config.xml");
  }

  @Test
  public void testToken() throws Exception {

    var root = server.authenticateUser("root", "root", "*");

    var signedWebTokenServerUser = server.getTokenHandler().getSignedWebTokenServerUser(root);

    Assert.assertNotNull(signedWebTokenServerUser);

    var token =
        (JsonWebToken) server.getTokenHandler().parseWebToken(signedWebTokenServerUser);

    server.getTokenHandler().validateServerUserToken(token, "", "");

    Assert.assertEquals("root", token.getUserName());
    Assert.assertEquals("YouTrackDBServer", token.getPayload().getAudience());
  }

  @After
  public void teardown() {

    server.shutdown();
  }
}
