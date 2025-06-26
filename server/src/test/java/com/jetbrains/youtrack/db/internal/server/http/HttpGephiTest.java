package com.jetbrains.youtrack.db.internal.server.http;

import java.io.IOException;
import org.apache.hc.core5.http.HttpResponse;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test HTTP "gephi" command.
 */
public class HttpGephiTest extends BaseHttpDatabaseTest {

  @Test
  public void commandRootCredentials() throws IOException {
    HttpResponse response =
        get("gephi/" + getDatabaseName() + "/sql/select%20from%20V")
            .setUserName("root")
            .setUserPassword("root")
            .getResponse();

    Assert.assertEquals(response.getReasonPhrase(), 200, response.getCode());
  }

  @Test
  public void commandDatabaseCredentials() throws IOException {
    var response =
        get("gephi/" + getDatabaseName() + "/sql/select%20from%20V")
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse();

    response.getEntity().writeTo(System.out);

    Assert.assertEquals(response.getReasonPhrase(), 200, response.getCode());
  }

  @Override
  public void createDatabase() throws Exception {
    super.createDatabase();

    var response =
        post("command/" + getDatabaseName() + "/sql/")
            .payload(
                "{\"command\":\"create vertex set name = ?\",\"parameters\":[\"Jay\"]}",
                CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse();
    Assert.assertEquals(response.getReasonPhrase(), response.getCode(), 200);

    var response1 =
        post("command/" + getDatabaseName() + "/sql/")
            .payload(
                "{\"command\":\"create vertex set name = ?\",\"parameters\":[\"Amiga\"]}",
                CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse();
    Assert.assertEquals(response1.getReasonPhrase(), response1.getCode(), 200);

    var response2 =
        post("command/" + getDatabaseName() + "/sql/")
            .payload(
                "{\"command\":\"create edge from (select from v where name = 'Jay') to (select from"
                    + " v where name = 'Amiga')\"}",
                CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse();
    Assert.assertEquals(response2.getReasonPhrase(), response2.getCode(), 200);
  }

  @Override
  public String getDatabaseName() {
    return "httpgephi";
  }
}
