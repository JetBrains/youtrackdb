package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 3/24/14
 */
@Test
public class SQLCreateVertexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLCreateVertexTest(boolean remote) {
    super(remote);
  }

  public void testCreateVertexByContent() {
    session.close();

    session = createSessionInstance();

    Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("CreateVertexByContent")) {
      var vClass = schema.createClass("CreateVertexByContent", schema.getClass("V"));
      vClass.createProperty("message", PropertyType.STRING);
    }

    session.begin();
    session.command("create vertex CreateVertexByContent content { \"message\": \"(:\"}").close();
    session
        .command(
            "create vertex CreateVertexByContent content { \"message\": \"\\\"‎ה, כן?...‎\\\"\"}")
        .close();
    session.commit();

    var result =
        session.query("select from CreateVertexByContent").stream().collect(Collectors.toList());
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
    session.command("create vertex set script = true").close();
    session.command("create vertex").close();
    session.command("create vertex V").close();
    session.commit();

    // TODO complete this!
    // database.command(new CommandSQL("create vertex set")).execute();
    // database.command(new CommandSQL("create vertex set set set = 1")).execute();

  }

  public void testIsClassName() {
    session.close();

    session = createSessionInstance();
    session.createVertexClass("Like").createProperty("anything", PropertyType.STRING);
    session.createVertexClass("Is").createProperty("anything", PropertyType.STRING);
  }
}
