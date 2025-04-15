package com.jetbrains.youtrack.db.internal.server.http;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests HTTP "listDatabases" command.
 */
public class HttpListDatabasesTest extends BaseHttpTest {

  @Test
  public void testListDatabasesRootUser() throws Exception {
    Assert.assertEquals(
        setUserName("root").setUserPassword("root").get("listDatabases").getResponse().getCode(),
        200);
  }

  @Test
  public void testListDatabasesGuestUser() throws Exception {
    Assert.assertEquals(
        setUserName("guest").setUserPassword("guest").get("listDatabases").getResponse().getCode(),
        200);
  }

  @Override
  public String getDatabaseName() {
    return "-";
  }

  @Before
  public void startServer() throws Exception {
    super.startServer();
  }

  @After
  public void stopServer() throws Exception {
    super.stopServer();
  }
}
