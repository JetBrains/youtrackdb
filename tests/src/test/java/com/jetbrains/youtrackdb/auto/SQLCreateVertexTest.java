package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @since 3/24/14
 */
@Test
public class SQLCreateVertexTest extends BaseDBTest {

  public void testCreateVertexByContent() {
    session.close();

    session = createSessionInstance();

    graph.autoExecuteInTx(g -> g.schemaClass("CreateVertexByContent").fold().coalesce(
        __.unfold(),
        __.createSchemaClass("CreateVertexByContent")
            .createSchemaProperty("message", PropertyType.STRING)
    ));

    session.begin();
    session.execute("create vertex CreateVertexByContent content { \"message\": \"(:\"}").close();
    session
        .execute(
            "create vertex CreateVertexByContent content { \"message\": \"\\\"‎ה, כן?...‎\\\"\"}")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query("select from CreateVertexByContent").stream().toList();
    Assert.assertEquals(result.size(), 2);

    List<String> messages = new ArrayList<String>();
    messages.add("\"‎ה, כן?...‎\"");
    messages.add("(:");

    List<String> resultMessages = new ArrayList<String>();

    for (var document : result) {
      resultMessages.add(document.getProperty("message"));
    }

    //    issue #1787, works fine locally, not on CI
    Assert.assertEqualsNoOrder(
        messages.toArray(),
        resultMessages.toArray(),
        "arrays are different: " + toString(messages) + " - " + toString(resultMessages));
    session.commit();
  }

  private String toString(List<String> resultMessages) {
    var result = new StringBuilder();
    result.append("[");
    var first = true;
    for (var msg : resultMessages) {
      if (!first) {
        result.append(", ");
      }
      result.append("\"");
      result.append(msg);
      result.append("\"");
      first = false;
    }
    result.append("]");
    return result.toString();
  }

  public void testCreateVertexBooleanProp() {
    session.close();
    session = createSessionInstance();

    session.begin();
    session.execute("create vertex set script = true").close();
    session.execute("create vertex").close();
    session.execute("create vertex V").close();
    session.commit();
  }

  public void testIsClassName() {
    session.close();

    session = createSessionInstance();
    graph.autoExecuteInTx(g -> g.createSchemaClass("Like").
        createSchemaProperty("anything", PropertyType.STRING).
        createSchemaClass("Is").createSchemaProperty("anything", PropertyType.STRING)
    );
  }
}
