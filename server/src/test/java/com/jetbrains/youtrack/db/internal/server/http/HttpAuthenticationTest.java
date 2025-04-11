package com.jetbrains.youtrack.db.internal.server.http;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;

/**
 *
 */
public class HttpAuthenticationTest extends BaseHttpDatabaseTest {

  public void testChangeOfUserOnSameConnectionIsAllowed() throws IOException {
    var response =
        get("query/"
            + getDatabaseName()
            + "/sql/"
            + URLEncoder.encode("select from OUSer", StandardCharsets.UTF_8)
            + "/10")
            .setUserName("root")
            .setUserPassword("root")
            .getResponse();
    Assert.assertEquals(response.getReasonPhrase(), response.getCode(), 200);

    var response1 =
        get("query/"
            + getDatabaseName()
            + "/sql/"
            + URLEncoder.encode("select from OUSer", StandardCharsets.UTF_8)
            + "/10")
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse();
    Assert.assertEquals(response1.getReasonPhrase(), response1.getCode(), 200);
  }

  @Override
  public String getDatabaseName() {
    return "httpauth";
  }
}
