package com.jetbrains.youtrack.db.internal.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.io.IOException;
import java.util.HashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test HTTP "Graph" .
 */
public class HttpGraphTest extends BaseHttpDatabaseTest {

  @Test
  public void updateWithEdges() throws IOException {
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("create class Foo extends V", CONTENT.TEXT)
            .getResponse()
            .getCode());
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("create class FooEdge extends E", CONTENT.TEXT)
            .getResponse()
            .getCode());

    var script = "begin;";
    script += "let $v1 = create vertex Foo set name = 'foo1';";
    script += "let $v2 = create vertex Foo set name = 'foo2';";
    script += "create edge FooEdge from $v1 to $v2;";
    script += "let $v3 = select detach() from $v1;";
    script += "commit;";
    script += "return $v3;";

    final var scriptPayload =
        "{ \"operations\" : [{ \"type\" : \"script\", \"language\" : \"SQL\",  \"script\" :"
            + " \"%s\"}]}";

    var response =
        post("batch/" + getDatabaseName() + "/sql/")
            .payload(String.format(scriptPayload, script), CONTENT.JSON)
            .getResponse();
    Assert.assertEquals(200, response.getCode());

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(response.getEntity().getContent());

    var res = result.get("result");
    Assert.assertEquals(1, res.size());

    var created = res.get(0).get("value").get(0);
    Assert.assertEquals("foo1", created.get("name").asText());
    Assert.assertEquals(1, created.get("@version").asInt());

    var coll = created.get("out_FooEdge");
    Assert.assertEquals(1, coll.size());

    var createdNode = created.<ObjectNode>deepCopy();
    createdNode.put("name", "fooUpdated");

    response =
        put("document/" + getDatabaseName() + "/" + createdNode.get("@rid").asText().substring(1))
            .payload(createdNode.toString(), CONTENT.JSON)
            .exec()
            .getResponse();
    Assert.assertEquals(200, response.getCode());

    var updated = objectMapper.readTree(response.getEntity().getContent());
    Assert.assertEquals("fooUpdated", updated.get("name").asText());
    Assert.assertEquals(2, updated.get("@version").asInt());

    coll = updated.get("out_FooEdge");
    Assert.assertEquals(1, coll.size());
  }

  @Test
  public void getGraphResult() throws IOException {
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("create class Foo extends V", CONTENT.TEXT)
            .getResponse()
            .getCode());
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("create class FooEdge extends E", CONTENT.TEXT)
            .getResponse()
            .getCode());

    var script = "begin;";
    script += "let $v1 = create vertex Foo set name = 'foo1';";
    script += "let $v2 = create vertex Foo set name = 'foo2';";
    script += "create edge FooEdge from $v1 to $v2;";
    script += "let $v3 = select detach() from $v1;";
    script += "commit;";
    script += "return $v3;";

    final var scriptPayload =
        "{ \"operations\" : [{ \"type\" : \"script\", \"language\" : \"SQL\",  \"script\" :"
            + " \"%s\"}]}";

    var response =
        post("batch/" + getDatabaseName() + "/sql/")
            .payload(String.format(scriptPayload, script), CONTENT.JSON)
            .getResponse();
    Assert.assertEquals(200, response.getCode());

    final var payload = new HashMap<String, String>();
    payload.put("command", "select from E");
    payload.put("mode", "graph");
    var json = JSONSerializerJackson.INSTANCE.mapToJson(payload);

    response =
        post("command/" + getDatabaseName() + "/sql/").payload(json, CONTENT.JSON).getResponse();

    var objectMapper = new ObjectMapper();
    var result = objectMapper.readTree(response.getEntity().getContent());

    var res = result.get("graph");
    var vertices = res.get("vertices");
    var edges = res.get("edges");

    Assert.assertEquals(2, vertices.size());
    Assert.assertEquals(1, edges.size());
  }

  @Override
  public String getDatabaseName() {
    return "httpgraph";
  }
}
