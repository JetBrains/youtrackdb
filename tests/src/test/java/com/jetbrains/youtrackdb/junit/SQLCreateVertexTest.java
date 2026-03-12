package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for SQL CREATE VERTEX command.
 */
class SQLCreateVertexTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLCreateVertexTest#testCreateVertexByContent
  @Test
  @Order(1)
  void testCreateVertexByContent() {
    session.close();

    session = createSessionInstance();

    Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("CreateVertexByContent")) {
      var vClass = schema.createClass("CreateVertexByContent", schema.getClass("V"));
      vClass.createProperty("message", PropertyType.STRING);
    }

    session.begin();
    session.execute("create vertex CreateVertexByContent content { \"message\": \"(:\"}").close();
    session
        .execute(
            "create vertex CreateVertexByContent content { \"message\": \"\\\"‎ה, כן?...‎\\\"\"}")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query("select from CreateVertexByContent").stream().collect(Collectors.toList());
    assertEquals(2, result.size());

    List<String> messages = new ArrayList<String>();
    messages.add("\"‎ה, כן?...‎\"");
    messages.add("(:");

    List<String> resultMessages = new ArrayList<String>();

    for (var document : result) {
      resultMessages.add(document.getProperty("message"));
    }

    //    issue #1787, works fine locally, not on CI
    // Using sorted comparison since assertEqualsNoOrder has no JUnit 5 equivalent
    var sortedMessages = messages.stream().sorted().toList();
    var sortedResultMessages = resultMessages.stream().sorted().toList();
    assertEquals(
        sortedMessages,
        sortedResultMessages,
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

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLCreateVertexTest#testCreateVertexBooleanProp
  @Test
  @Order(2)
  void testCreateVertexBooleanProp() {
    session.close();
    session = createSessionInstance();

    session.begin();
    session.execute("create vertex set script = true").close();
    session.execute("create vertex").close();
    session.execute("create vertex V").close();
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLCreateVertexTest#testIsClassName
  @Test
  @Order(3)
  void testIsClassName() {
    session.close();

    session = createSessionInstance();
    session.createVertexClass("Like").createProperty("anything", PropertyType.STRING);
    session.createVertexClass("Is").createProperty("anything", PropertyType.STRING);
  }
}
