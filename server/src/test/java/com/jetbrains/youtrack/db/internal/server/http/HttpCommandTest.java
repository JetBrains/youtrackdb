package com.jetbrains.youtrack.db.internal.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test HTTP "command" command.
 */
public class HttpCommandTest extends BaseHttpDatabaseTest {

  @Test
  public void commandRootCredentials() throws IOException {
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("select from OUSer", CONTENT.TEXT)
            .setUserName("root")
            .setUserPassword("root")
            .getResponse()
            .getCode());
  }

  @Test
  public void commandDatabaseCredentials() throws IOException {
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("select from OUSer", CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse()
            .getCode());
  }

  @Test
  public void commandWithNamedParams() throws IOException {
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload(
                "{\"command\":\"select from OUSer where name ="
                    + " :name\",\"parameters\":{\"name\":\"admin\"}}",
                CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse()
            .getCode());

    final var response = getResponse().getEntity().getContent();

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(response);

    var res = result.get("result").iterator();

    Assert.assertTrue(res.hasNext());

    var doc = res.next();
    Assert.assertEquals("admin", doc.get("name").asText());
  }

  @Test
  public void commandWithPosParams() throws IOException {
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload(
                "{\"command\":\"select from OUSer where name = ?\",\"parameters\":[\"admin\"]}",
                CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse()
            .getCode());

    final var response = getResponse().getEntity().getContent();
    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(response);

    var res = result.get("result").iterator();

    Assert.assertTrue(res.hasNext());

    var doc = res.next();
    Assert.assertEquals("admin", doc.get("name").asText());
  }

  @Override
  public String getDatabaseName() {
    return "httpcommand";
  }
}
