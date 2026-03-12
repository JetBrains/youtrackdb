/*
 *
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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class SQLBatchTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLBatchTest#createEdgeFailIfNoSourceOrTargetVertices
  // Original used: @Test(enabled = false)
  @Test
  @Order(1)
  @Disabled("Disabled in original TestNG test")
  void createEdgeFailIfNoSourceOrTargetVertices() {
    try {
      session.computeScript("sql",
          """
              BEGIN;
              LET credential = INSERT INTO V SET email = '123', password = '123';
              LET order = SELECT FROM V WHERE cannotFindThisAttribute = true;
              LET edge = CREATE EDGE E FROM $credential TO $order set crazyName = 'yes';
              COMMIT;
              RETURN $credential;""");

      fail("Tx has been committed while a rollback was expected");
    } catch (CommandExecutionException e) {

      var result = executeQuery("select from V where email = '123'");
      assertTrue(result.isEmpty());

      result = executeQuery("select from E where crazyName = 'yes'");
      assertTrue(result.isEmpty());

    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLBatchTest#testInlineArray
  @Test
  @Order(2)
  void testInlineArray() {
    var className1 = "SQLBatchTest_testInlineArray1";
    var className2 = "SQLBatchTest_testInlineArray2";
    session.execute("CREATE CLASS " + className1 + " EXTENDS V").close();
    session.execute("CREATE CLASS " + className2 + " EXTENDS V").close();
    session.execute("CREATE PROPERTY " + className2 + ".foos LinkList " + className1).close();

    session.begin();
    var script =
        "BEGIN;"
            + "LET a = CREATE VERTEX "
            + className1
            + ";"
            + "LET b = CREATE VERTEX "
            + className1
            + ";"
            + "LET c = CREATE VERTEX "
            + className1
            + ";"
            + "CREATE VERTEX "
            + className2
            + " SET foos=[$a,$b,$c];"
            + "COMMIT";

    session.computeScript("sql", script);
    session.commit();

    session.begin();
    var result = executeQuery("select from " + className2);
    assertEquals(1, result.size());
    List foos = result.getFirst().getProperty("foos");
    assertEquals(3, foos.size());
    assertInstanceOf(Identifiable.class, foos.get(0));
    assertInstanceOf(Identifiable.class, foos.get(1));
    assertInstanceOf(Identifiable.class, foos.get(2));
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLBatchTest#testInlineArray2
  @Test
  @Order(3)
  void testInlineArray2() {
    var className1 = "SQLBatchTest_testInlineArray21";
    var className2 = "SQLBatchTest_testInlineArray22";
    session.execute("CREATE CLASS " + className1 + " EXTENDS V").close();
    session.execute("CREATE CLASS " + className2 + " EXTENDS V").close();
    session.execute("CREATE PROPERTY " + className2 + ".foos LinkList " + className1).close();

    session.begin();
    var script =
        "BEGIN;\n"
            + "LET a = CREATE VERTEX "
            + className1
            + ";\n"
            + "LET b = CREATE VERTEX "
            + className1
            + ";\n"
            + "LET c = CREATE VERTEX "
            + className1
            + ";\n"
            + "LET foos = [$a,$b,$c];"
            + "CREATE VERTEX "
            + className2
            + " SET foos= $foos;\n"
            + "COMMIT;";

    session.computeScript("sql", script);
    session.commit();

    session.begin();
    var result = executeQuery("select from " + className2);
    assertEquals(1, result.size());
    List foos = result.getFirst().getProperty("foos");
    assertEquals(3, foos.size());
    assertInstanceOf(Identifiable.class, foos.get(0));
    assertInstanceOf(Identifiable.class, foos.get(1));
    assertInstanceOf(Identifiable.class, foos.get(2));
    session.commit();
  }
}
