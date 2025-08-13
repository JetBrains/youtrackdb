package com.jetbrains.youtrackdb.internal.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class HttpServerTest extends BaseHttpDatabaseTest {

  @Test
  public void testGetServer() throws Exception {
    var res = get("server").getResponse();
    Assert.assertEquals(res.getReasonPhrase(), 200, res.getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(res.getEntity().getContent());

    Assert.assertTrue(result.hasNonNull("connections"));
  }

  @Test
  public void testGetConnections() throws Exception {
    var res = setUserName("root").setUserPassword("root").get("connections/").getResponse();
    Assert.assertEquals(res.getReasonPhrase(), 200, res.getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(res.getEntity().getContent());

    Assert.assertTrue(result.hasNonNull("connections"));
  }

  @Test
  public void testCreateDatabase() throws IOException {
    var dbName = getClass().getSimpleName() + "testCreateDatabase";
    var res =
        setUserName("root")
            .setUserPassword("root")
            .post("servercommand")
            .payload("{\"command\":\"create database " + dbName + " disk\" }", CONTENT.JSON)
            .getResponse();
    Assert.assertEquals(res.getReasonPhrase(), 200, res.getCode());

    Assert.assertTrue(getServer().getContext().exists(dbName));
    getServer().getContext().drop(dbName);
  }

  @Override
  protected String getDatabaseName() {
    return "server";
  }
}
