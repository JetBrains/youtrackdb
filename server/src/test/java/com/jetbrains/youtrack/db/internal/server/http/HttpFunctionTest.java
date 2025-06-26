package com.jetbrains.youtrack.db.internal.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test HTTP "function" command.
 */
public class HttpFunctionTest extends BaseHttpDatabaseTest {
  @Test
  public void callFunction() throws Exception {
    // CREATE FUNCTION FIRST
    var response1 =
        post("command/" + getDatabaseName() + "/sql/")
            .payload(
                "CREATE FUNCTION hello \"return 'Hello ' + name + ' ' + surname;\" PARAMETERS"
                    + " [name,surname] LANGUAGE javascript",
                CONTENT.TEXT)
            .getResponse();
    Assert.assertEquals(response1.getReasonPhrase(), 200, response1.getCode());

    var response2 =
        post("function/" + getDatabaseName() + "/hello")
            .payload("{\"name\": \"Jay\", \"surname\": \"Miner\"}", CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse();
    Assert.assertEquals(response2.getReasonPhrase(), 200, response2.getCode());

    var response = EntityUtils.toString(getResponse().getEntity());

    Assert.assertNotNull(response);
    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(response).get("result").iterator().next();

    Assert.assertEquals("Hello Jay Miner", result.get("value").asText());
  }

  @Override
  public String getDatabaseName() {
    return "httpfunction";
  }
}
