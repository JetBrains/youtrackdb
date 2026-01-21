/*
 * JUnit 4 version of SQLCreateVertexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateVertexTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 3/24/14
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLCreateVertexTest extends BaseDBTest {

  private static SQLCreateVertexTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLCreateVertexTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateVertexTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testCreateVertexByContent (line 16) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateVertexTest.java
   */
  @Test
  public void test01_CreateVertexByContent() {
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
    Assert.assertEquals(result.size(), 2);

    List<String> messages = new ArrayList<String>();
    messages.add("\"‎ה, כן?...‎\"");
    messages.add("(:");

    List<String> resultMessages = new ArrayList<String>();

    for (var document : result) {
      resultMessages.add(document.getProperty("message"));
    }

    //    issue #1787, works fine locally, not on CI
    // Using containsAll for order-independent comparison (JUnit equivalent of TestNG assertEqualsNoOrder)
    Assert.assertTrue(
        "arrays are different: " + messages + " - " + resultMessages,
        messages.containsAll(resultMessages) && resultMessages.containsAll(messages));
    session.commit();
  }

  /**
   * Original: testCreateVertexBooleanProp (line 75) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateVertexTest.java
   */
  @Test
  public void test02_CreateVertexBooleanProp() {
    session.close();
    session = createSessionInstance();

    session.begin();
    session.execute("create vertex set script = true").close();
    session.execute("create vertex").close();
    session.execute("create vertex V").close();
    session.commit();

    // TODO complete this!
    // database.command(new CommandSQL("create vertex set")).execute();
    // database.command(new CommandSQL("create vertex set set set = 1")).execute();

  }

  /**
   * Original: testIsClassName (line 91) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateVertexTest.java
   */
  @Test
  public void test03_IsClassName() {
    session.close();

    session = createSessionInstance();
    session.createVertexClass("Like").createProperty("anything", PropertyType.STRING);
    session.createVertexClass("Is").createProperty("anything", PropertyType.STRING);
  }

}
