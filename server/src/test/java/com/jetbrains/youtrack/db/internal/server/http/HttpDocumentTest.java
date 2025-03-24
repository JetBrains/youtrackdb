package com.jetbrains.youtrack.db.internal.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test HTTP "query" command.
 */
public class HttpDocumentTest extends BaseHttpDatabaseTest {

  @Test
  public void create() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{ \"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":99}",
            CONTENT.JSON)
        .exec();

    var response = getResponse();
    Assert.assertEquals(response.getReasonPhrase(), 201, response.getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(response.getEntity().getContent());

    Assert.assertEquals(1, result.get("@version").asInt());
    Assert.assertEquals("Jay", result.get("name").asText());
    Assert.assertEquals("Miner", result.get("surname").asText());
    Assert.assertEquals(99, result.get("age").asInt());
  }

  @Test
  public void read() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":99}",
            CONTENT.JSON)
        .exec();
    var response = getResponse();
    Assert.assertEquals(response.getReasonPhrase(), 201, response.getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(response.getEntity().getContent());

    Assert.assertEquals("Jay", result.get("name").asText());
    Assert.assertEquals("Miner", result.get("surname").asText());
    Assert.assertEquals(99, result.get("age").asInt());
    Assert.assertEquals(1, result.get("@version").asInt());

    get("document/" + getDatabaseName() + "/" + result.get("@rid").asText().substring(1))
        .exec();

    Assert.assertEquals(200, getResponse().getCode());
    var updated = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", updated.get("name").asText());
    Assert.assertEquals("Miner", updated.get("surname").asText());
    Assert.assertEquals(99, updated.get("age").asInt());
    Assert.assertEquals(1, updated.get("@version").asInt());
  }

  @Test
  public void updateFull() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":0}",
            CONTENT.JSON)
        .exec();
    Assert.assertEquals(201, getResponse().getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", result.get("name").asText());
    Assert.assertEquals("Miner", result.get("surname").asText());
    Assert.assertEquals(0, result.get("age").asInt());
    Assert.assertEquals(1, result.get("@version").asInt());

    var created = result.<ObjectNode>deepCopy();

    created.put("name", "Jay2");
    created.put("surname", "Miner2");
    created.put("age", 1);

    put("document/" + getDatabaseName() + "/" + created.get("@rid").asText().substring(1))
        .payload(created.toString(), CONTENT.JSON)
        .exec();
    Assert.assertEquals(200, getResponse().getCode());

    var updated = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay2", updated.get("name").asText());
    Assert.assertEquals("Miner2", updated.get("surname").asText());
    Assert.assertEquals(1, updated.get("age").asInt());
    Assert.assertEquals(2, updated.get("@version").asInt());
  }

  @Test
  public void updateFullNoVersion() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":0}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(201, getResponse().getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", result.get("name").asText());
    Assert.assertEquals("Miner", result.get("surname").asText());
    Assert.assertEquals(0, result.get("age").asInt());
    Assert.assertEquals(1, result.get("@version").asInt());

    put("document/" + getDatabaseName() + "/" + result.get("@rid").asText().substring(1))
        .payload("{\"name\":\"Jay2\", \"surname\":\"Miner2\",\"age\":1}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(200, getResponse().getCode());

    var updated = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay2", updated.get("name").asText());
    Assert.assertEquals("Miner2", updated.get("surname").asText());
    Assert.assertEquals(1, updated.get("age").asInt());
    Assert.assertEquals(2, updated.get("@version").asInt());
  }

  @Test
  public void updateFullBadVersion() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":0}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(201, getResponse().getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", result.get("name").asText());
    Assert.assertEquals("Miner", result.get("surname").asText());
    Assert.assertEquals(0, result.get("age").asInt());
    Assert.assertEquals(1, result.get("@version").asInt());

    put("document/" + getDatabaseName() + "/" + result.get("@rid").asText().substring(1))
        .payload("{\"name\":\"Jay2\", \"surname\":\"Miner2\",\"age\":1, \"@version\": 2}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(500, getResponse().getCode());
  }

  @Test
  public void updatePartial() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":0}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(201, getResponse().getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", result.get("name").asText());
    Assert.assertEquals("Miner", result.get("surname").asText());
    Assert.assertEquals(0, result.get("age").asInt());
    Assert.assertEquals(1, result.get("@version").asInt());

    put("document/"
        + getDatabaseName()
        + "/"
        + result.get("@rid").asText().substring(1)
        + "?updateMode=partial")
        .payload("{\"age\":1}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(200, getResponse().getCode());

    var updated = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", updated.get("name").asText());
    Assert.assertEquals("Miner", updated.get("surname").asText());
    Assert.assertEquals(1, updated.get("age").asInt());
    Assert.assertEquals(2, updated.get("@version").asInt());
  }

  @Test
  public void patchPartial() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":0}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(201, getResponse().getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", result.get("name").asText());
    Assert.assertEquals("Miner", result.get("surname").asText());
    Assert.assertEquals(0, result.get("age").asInt());
    Assert.assertEquals(1, result.get("@version").asInt());

    patch("document/" + getDatabaseName() + "/" + result.get("@rid").asText().substring(1))
        .payload("{\"age\":1,\"@version\":1}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(200, getResponse().getCode());

    var updated = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", updated.get("name").asText());
    Assert.assertEquals("Miner", updated.get("surname").asText());
    Assert.assertEquals(1, updated.get("age").asInt());
    Assert.assertEquals(2, updated.get("@version").asInt());
  }

  @Test
  public void deleteByRid() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":0}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(201, getResponse().getCode());

    var objectMapper = new ObjectMapper();
    var created = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", created.get("name").asText());
    Assert.assertEquals("Miner", created.get("surname").asText());
    Assert.assertEquals(0, created.get("age").asInt());
    Assert.assertEquals(1, created.get("@version").asInt());

    delete("document/" + getDatabaseName() + "/" + created.get("@rid").asText().substring(1))
        .exec();
    Assert.assertEquals(204, getResponse().getCode());

    get("document/" + getDatabaseName() + "/" + created.get("@rid").asText().substring(1))
        .exec();
    Assert.assertEquals(404, getResponse().getCode());
  }

  @Test
  public void deleteWithMVCC() throws IOException {
    post("document/" + getDatabaseName())
        .payload("{\"@class\":\"V\", \"name\":\"Jay\", \"surname\":\"Miner\",\"age\":0}", CONTENT.JSON)
        .exec();
    Assert.assertEquals(201, getResponse().getCode());

    var objectMapper = new ObjectMapper();
    var created = objectMapper.readTree(getResponse().getEntity().getContent());

    Assert.assertEquals("Jay", created.get("name").asText());
    Assert.assertEquals("Miner", created.get("surname").asText());
    Assert.assertEquals(0, created.get("age").asInt());
    Assert.assertEquals(1, created.get("@version").asInt());

    delete("document/" + getDatabaseName() + "/" + created.get("@rid").asText().substring(1))
        .payload(created.toString(), CONTENT.JSON)
        .exec();
    Assert.assertEquals(204, getResponse().getCode());

    get("document/" + getDatabaseName() + "/" + created.get("@rid").asText().substring(1))
        .exec();
    Assert.assertEquals(404, getResponse().getCode());
  }

  @Override
  public String getDatabaseName() {
    return "httpdocument";
  }
}
