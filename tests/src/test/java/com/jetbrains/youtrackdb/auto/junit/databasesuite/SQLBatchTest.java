/*
 * JUnit 4 version of SQLBatchTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLBatchTest.java
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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLBatchTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLBatchTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLBatchTest extends BaseDBTest {

  private static SQLBatchTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLBatchTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLBatchTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testInlineArray (line 50) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLBatchTest.java
   */
  @Test
  public void test01_InlineArray() {
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
    Assert.assertEquals(result.size(), 1);
    List foos = result.getFirst().getProperty("foos");
    Assert.assertEquals(foos.size(), 3);
    Assert.assertTrue(foos.get(0) instanceof Identifiable);
    Assert.assertTrue(foos.get(1) instanceof Identifiable);
    Assert.assertTrue(foos.get(2) instanceof Identifiable);
    session.commit();
  }

  /**
   * Original: testInlineArray2 (line 88) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLBatchTest.java
   */
  @Test
  public void test02_InlineArray2() {
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
    Assert.assertEquals(result.size(), 1);
    List foos = result.getFirst().getProperty("foos");
    Assert.assertEquals(foos.size(), 3);
    Assert.assertTrue(foos.get(0) instanceof Identifiable);
    Assert.assertTrue(foos.get(1) instanceof Identifiable);
    Assert.assertTrue(foos.get(2) instanceof Identifiable);
    session.commit();
  }

}
